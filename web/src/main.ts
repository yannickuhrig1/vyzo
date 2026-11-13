import './style.css'
import {
  SupabaseBackend, UserError, friendlyError,
  type Account, type Backend, type ListsData, type PlaylistDraft, type Table, type VaultStatus,
} from './backend'
import { DemoBackend } from './demo'
import { append, clear, h, icon } from './dom'
import {
  FAVORITES_LIST_ID, PROFILE_PALETTE, MAX_PROFILE_NAME, cleanName, hashPin, isOnline, isValidPin, moveProfile, needsUpdate,
  nextColor, profileColor, progressRatio, recordingGroup, sortProfiles, sortRecordings, statusLabel, updateInProgress, updatePending,
  type ContentKind, type Device, type LatestVersion, type ListItem, type Playlist, type Profile, type ProfileActivity,
  type ProgressItem, type Recording, type RecordingGroup, type UserList,
} from './model'

// ------------------------------------------------------------------ État

const demoRequested = import.meta.env.DEV && new URLSearchParams(location.search).has('demo')
const backend: Backend = demoRequested ? new DemoBackend() : new SupabaseBackend()

type Route = 'profils' | 'appareils' | 'abonnements' | 'historique' | 'listes' | 'enregistrements'
const ROUTES: { id: Route; label: string; icon: string }[] = [
  { id: 'profils', label: 'Profils', icon: 'users' },
  { id: 'appareils', label: 'Appareils', icon: 'devices' },
  { id: 'abonnements', label: 'Abonnements', icon: 'key' },
  { id: 'historique', label: 'Historique', icon: 'history' },
  { id: 'listes', label: 'Listes', icon: 'list' },
  { id: 'enregistrements', label: 'Enregistrements', icon: 'record' },
]

/** Données chargées séparément : une erreur sur l'une (table absente du serveur…) n'empêche pas d'afficher les autres. */
type DataKey = 'profiles' | 'activity' | 'recordings' | 'devices' | 'playlists' | 'progress' | 'lists'

interface State {
  account: Account | null
  vault: VaultStatus | null
  /** La clé du compte est gardée dans ce navigateur (« Rester déverrouillé »). */
  remembered: boolean
  profiles: Profile[] | null
  activity: ProfileActivity[] | null
  recordings: Recording[] | null
  devices: Device[] | null
  latest: LatestVersion | null
  playlists: Playlist[] | null
  /** Profil choisi dans Historique et Listes. */
  selected: string | null
  progress: { profileId: string; items: ProgressItem[] } | null
  lists: ({ profileId: string } & ListsData) | null
  errors: Partial<Record<DataKey, string>>
}

const emptyState = (): State => ({
  account: null, vault: null, remembered: false, profiles: null, activity: null, recordings: null, devices: null, latest: null,
  playlists: null, selected: null, progress: null, lists: null, errors: {},
})

let state: State = emptyState()
let stopWatching: (() => void) | null = null

const root = document.getElementById('app')!

function route(): Route {
  const id = location.hash.replace(/^#\/?/, '') as Route
  return ROUTES.some((r) => r.id === id) ? id : 'profils'
}

// ------------------------------------------------------------------ Formats

const dayFmt = new Intl.DateTimeFormat('fr-FR', { weekday: 'short', day: 'numeric', month: 'short' })
const timeFmt = new Intl.DateTimeFormat('fr-FR', { hour: '2-digit', minute: '2-digit' })
const fullFmt = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'full', timeStyle: 'short' })
const relFmt = new Intl.RelativeTimeFormat('fr-FR', { numeric: 'auto' })

function relative(ms: number): string {
  const diff = ms - Date.now()
  const abs = Math.abs(diff)
  if (abs < 60_000) return 'à l’instant'
  if (abs < 3600_000) return relFmt.format(Math.round(diff / 60_000), 'minute')
  if (abs < 86400_000) return relFmt.format(Math.round(diff / 3600_000), 'hour')
  if (abs < 30 * 86400_000) return relFmt.format(Math.round(diff / 86400_000), 'day')
  return dayFmt.format(ms)
}

function dayLabel(ms: number): string {
  const d = new Date(ms)
  const today = new Date()
  const startOf = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const days = Math.round((startOf(d) - startOf(today)) / 86400_000)
  if (days === 0) return 'Aujourd’hui'
  if (days === 1) return 'Demain'
  if (days === -1) return 'Hier'
  return dayFmt.format(ms)
}

function duration(ms: number): string {
  const min = Math.max(1, Math.round(ms / 60_000))
  if (min < 60) return `${min} min`
  const hours = Math.floor(min / 60)
  const rest = min % 60
  return rest ? `${hours} h ${String(rest).padStart(2, '0')}` : `${hours} h`
}

const plural = (n: number, one: string, many: string) => `${n} ${n > 1 ? many : one}`

// ------------------------------------------------------------------ Notifications

const toasts = h('div', { class: 'toasts', role: 'status', 'aria-live': 'polite' })
document.body.appendChild(toasts)

function toast(message: string, kind: 'ok' | 'error' = 'ok') {
  const el = h('div', { class: `toast ${kind}` }, message)
  toasts.appendChild(el)
  setTimeout(() => el.classList.add('out'), kind === 'error' ? 6000 : 3200)
  setTimeout(() => el.remove(), kind === 'error' ? 6400 : 3600)
}

/** Lance une action d'écriture : bouton occupé pendant l'envoi, message à la fin. */
async function act(button: HTMLButtonElement | null, work: () => Promise<void>, done?: string): Promise<boolean> {
  button?.setAttribute('aria-busy', 'true')
  if (button) button.disabled = true
  try {
    await work()
    if (done) toast(done)
    return true
  } catch (e) {
    toast(friendlyError(e), 'error')
    return false
  } finally {
    button?.removeAttribute('aria-busy')
    if (button) button.disabled = false
  }
}

// ------------------------------------------------------------------ Chargement

const LOADERS: Record<DataKey, () => Promise<void>> = {
  async profiles() {
    state.profiles = sortProfiles(await backend.profiles())
    if (!state.profiles.some((p) => p.id === state.selected)) {
      state.selected = state.profiles[0]?.id ?? null
      state.progress = null
      state.lists = null
    }
  },
  async activity() {
    if (state.profiles) state.activity = await backend.activity(state.profiles)
  },
  async recordings() {
    state.recordings = await backend.recordings()
  },
  async devices() {
    const [devices, latest] = await Promise.all([backend.devices(), backend.latestVersion()])
    state.devices = devices
    state.latest = latest
  },
  async playlists() {
    state.vault = await backend.vaultStatus(state.account!)
    state.remembered = state.vault === 'open' && await backend.vaultRemembered()
    state.playlists = state.vault === 'open' ? await backend.playlists(state.account!) : null
  },
  async progress() {
    const id = state.selected
    if (!id) return
    const items = await backend.progress(state.account!, id)
    if (state.selected === id) state.progress = { profileId: id, items }
  },
  async lists() {
    const id = state.selected
    if (!id) return
    const data = await backend.lists(state.account!, id)
    if (state.selected === id) state.lists = { profileId: id, ...data }
  },
}

/** Recharge les données demandées ; les profils d'abord, l'activité en dépend. */
async function refresh(keys: Iterable<DataKey>) {
  const wanted = new Set(keys)
  const run = async (k: DataKey) => {
    try {
      await LOADERS[k]()
      delete state.errors[k]
    } catch (e) {
      state.errors[k] = friendlyError(e)
    }
  }
  if (wanted.delete('profiles')) {
    await run('profiles')
    renderView()
  }
  await Promise.all([...wanted].map(run))
  renderView()
}

/** Ce qu'il faut recharger quand une table change ailleurs. */
function keysFor(table: Table): DataKey[] {
  const loaded = (k: DataKey): DataKey[] => (state[k as 'progress' | 'lists'] ? [k] : [])
  switch (table) {
    case 'profiles': return ['profiles', 'activity']
    case 'watch_progress': return ['activity', ...loaded('progress')]
    case 'list_items': case 'user_lists': return ['activity', ...loaded('lists')]
    case 'recordings': return ['recordings']
    case 'devices': return ['devices']
    case 'sealed_items': return ['playlists']
  }
}

let pending = new Set<DataKey>()
let pendingTimer: number | undefined

/** Les modifications arrivent souvent en rafale (synchro d'un appareil) : on recharge une fois, un peu après. */
function onRemoteChange(table: Table) {
  for (const k of keysFor(table)) pending.add(k)
  clearTimeout(pendingTimer)
  pendingTimer = window.setTimeout(() => {
    const keys = pending
    pending = new Set()
    void refresh(keys)
  }, 400)
}

/** Historique et listes ne sont lus qu'à l'ouverture de leur section. */
function ensureRouteData() {
  const r = route()
  if (!state.selected) return
  if (r === 'historique' && state.progress?.profileId !== state.selected) void refresh(['progress'])
  if (r === 'listes' && state.lists?.profileId !== state.selected) void refresh(['lists'])
}

async function enter(account: Account) {
  state = { ...emptyState(), account }
  stopWatching?.()
  stopWatching = backend.watch(account, onRemoteChange)
  renderShell()
  await refresh(['profiles', 'activity', 'recordings', 'devices', 'playlists'])
  ensureRouteData()
}

// Une mise à jour demandée : on suit sa progression même si le temps réel n'arrive pas.
setInterval(() => {
  if (state.account && route() === 'appareils' && state.devices?.some((d) => updateInProgress(d))) void refresh(['devices'])
}, 6000)

// ------------------------------------------------------------------ Connexion

function renderLogin(message?: string) {
  clear(root)
  const email = h('input', { id: 'email', type: 'email', autocomplete: 'username', required: true, spellcheck: 'false' })
  const password = h('input', { id: 'password', type: 'password', autocomplete: 'current-password', required: true })
  const error = h('p', { class: 'form-error', role: 'alert' }, message ?? '')
  const submit = h('button', { class: 'btn primary wide', type: 'submit' }, 'Se connecter')
  const stay = rememberBox('login-remember')

  const form = h('form', {
    class: 'login-card',
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      error.textContent = ''
      submit.disabled = true
      submit.textContent = 'Connexion…'
      try {
        const account = await backend.signIn(email.value, password.value, stay.input.checked)
        password.value = ''
        await enter(account)
      } catch (e) {
        error.textContent = friendlyError(e)
        submit.disabled = false
        submit.textContent = 'Se connecter'
        password.focus()
      }
    },
  },
    h('img', { class: 'login-logo', src: 'icon.svg', alt: '', width: 64, height: 64 }),
    h('h1', {}, 'Mon compte ', h('span', { class: 'brand' }, 'Vyzo')),
    h('p', { class: 'lead' }, 'Gère tes appareils, tes abonnements, tes profils et tes listes depuis ton ordinateur.'),
    h('label', { for: 'email' }, 'Adresse e-mail'), email,
    h('label', { for: 'password' }, 'Mot de passe'), password,
    stay.box,
    error,
    submit,
    h('p', { class: 'fine' },
      'Utilise le compte Vyzo de tes appareils. Pas encore de compte ? Crée-le dans l’appli, depuis Réglages → Compte Vyzo.'),
    import.meta.env.DEV && !backend.demo
      ? h('p', { class: 'fine' }, h('a', { href: '?demo' }, 'Ouvrir la démo (données fictives)'))
      : null,
  )
  append(root, [h('main', { class: 'login' }, form)])
  email.focus()
}

// ------------------------------------------------------------------ Coquille

let viewEl: HTMLElement | null = null

function renderShell() {
  clear(root)
  const current = route()
  const nav = h('nav', { class: 'nav', 'aria-label': 'Sections' },
    ROUTES.map((r) => h('a', {
      href: `#/${r.id}`, class: 'nav-item', 'aria-current': r.id === current ? 'page' : null,
    }, icon(r.icon, 20), h('span', {}, r.label))),
  )
  const signOut = h('button', {
    class: 'btn ghost small', type: 'button',
    onclick: async () => {
      stopWatching?.()
      stopWatching = null
      await backend.signOut()
      state = emptyState()
      renderLogin()
    },
  }, icon('logout', 16), 'Déconnexion')

  viewEl = h('main', { class: 'view', id: 'view', tabindex: '-1' })
  append(root, [
    h('div', { class: 'shell' },
      h('aside', { class: 'side' },
        h('a', { class: 'logo', href: '#/profils' }, h('img', { src: 'icon.svg', alt: '', width: 32, height: 32 }),
          h('span', {}, 'Vyzo'), backend.demo ? h('span', { class: 'demo-tag' }, 'démo') : null),
        nav,
        h('div', { class: 'account' },
          h('span', { class: 'account-label' }, 'Connecté'),
          h('span', { class: 'account-email', title: state.account?.email ?? '' }, state.account?.email ?? ''),
          signOut),
      ),
      viewEl,
    ),
  ])
  renderView()
}

const VIEWS: Record<Route, () => HTMLElement> = {
  profils: profilesView,
  appareils: devicesView,
  abonnements: playlistsView,
  historique: historyView,
  listes: listsView,
  enregistrements: recordingsView,
}

function renderView() {
  if (!viewEl || !state.account) return
  const r = route()
  for (const a of document.querySelectorAll<HTMLAnchorElement>('.nav-item')) {
    if (a.getAttribute('href') === `#/${r}`) a.setAttribute('aria-current', 'page')
    else a.removeAttribute('aria-current')
  }
  // Garde la position de défilement : le temps réel redessine souvent la vue.
  const scroll = window.scrollY
  clear(viewEl)
  append(viewEl, [VIEWS[r]()])
  window.scrollTo({ top: scroll })
}

window.addEventListener('hashchange', () => {
  window.scrollTo({ top: 0 })
  renderView()
  ensureRouteData()
  viewEl?.focus({ preventScroll: true })
})

function header(title: string, lead: string, ...actions: (Node | null)[]) {
  return h('header', { class: 'view-head' },
    h('div', {}, h('h1', {}, title), h('p', { class: 'lead' }, lead)),
    h('div', { class: 'head-actions' }, actions))
}

function errorBanner(key: DataKey) {
  const message = state.errors[key]
  if (!message) return null
  return h('div', { class: 'banner error', role: 'alert' }, icon('info'), h('span', {}, message),
    h('button', { class: 'btn ghost small', type: 'button', onclick: () => void refresh([key]) }, 'Réessayer'))
}

function loading() {
  return h('div', { class: 'skeleton-list', 'aria-label': 'Chargement' },
    [0, 1, 2].map(() => h('div', { class: 'skeleton' })))
}

function emptyCard(iconName: string, title: string, text: string, ...extra: (Node | null)[]) {
  return h('div', { class: 'empty-card' }, icon(iconName, 32), h('h2', {}, title), h('p', {}, text), extra)
}

function avatar(p: Pick<Profile, 'name' | 'colorIndex' | 'isKid'>, size: 'lg' | 'sm' | 'xs' = 'lg') {
  const initial = Array.from(p.name.trim())[0]?.toUpperCase() ?? '?'
  const el = h('span', { class: `avatar ${size}`, 'aria-hidden': 'true' }, initial,
    p.isKid && size !== 'xs' ? h('span', { class: 'kid-dot' }, icon('child', size === 'lg' ? 14 : 10)) : null)
  el.style.setProperty('--c', profileColor(p.colorIndex))
  return el
}

/** Choix du profil dans Historique et Listes. */
function profilePicker(count: (p: Profile) => number | null) {
  const profiles = state.profiles ?? []
  if (profiles.length < 2) return null
  return h('div', { class: 'picker', role: 'tablist', 'aria-label': 'Profil' },
    profiles.map((p) => {
      const n = count(p)
      return h('button', {
        type: 'button', class: 'chip', role: 'tab', 'aria-selected': String(p.id === state.selected),
        onclick: () => {
          if (state.selected === p.id) return
          state.selected = p.id
          state.progress = null
          state.lists = null
          renderView()
          ensureRouteData()
        },
      }, avatar(p, 'xs'), h('span', {}, p.name), n !== null ? h('span', { class: 'chip-count' }, String(n)) : null)
    }))
}

const KIND_ICON: Record<ContentKind, string> = { LIVE: 'tv', MOVIE: 'film', SERIES: 'series' }
const KIND_LABEL: Record<ContentKind, string> = { LIVE: 'Chaîne', MOVIE: 'Film', SERIES: 'Série' }

/** Titre affiché : celui que l'appli a chiffré sur le compte, sinon une indication honnête. */
function contentTitle(x: { title: string; kind: ContentKind }): { text: string; known: boolean } {
  if (x.title) return { text: x.title, known: true }
  const why = state.vault === 'open' ? 'titre pas encore connu' : 'titre masqué'
  return { text: `${KIND_LABEL[x.kind] ?? 'Contenu'} · ${why}`, known: false }
}

function kindBadge(kind: ContentKind) {
  return h('span', { class: `kind-badge k-${kind.toLowerCase()}`, title: KIND_LABEL[kind] }, icon(KIND_ICON[kind] ?? 'film', 18))
}

/** Bandeau des sections où les titres sont chiffrés. */
function titlesNotice(items: { title: string }[]) {
  if (items.length === 0) return null
  if (state.vault === 'locked') {
    return h('div', { class: 'banner info' }, icon('lock'),
      h('span', {}, 'Les titres sont chiffrés avec le mot de passe du compte. Déverrouille pour les afficher.'),
      h('button', { class: 'btn ghost small', type: 'button', onclick: () => unlockDialog() }, icon('unlock', 15), 'Déverrouiller'))
  }
  if (state.vault === 'open' && items.some((x) => !x.title)) {
    return h('p', { class: 'note' }, icon('info', 16), h('span', {},
      'Certains titres ne sont pas encore connus du compte. Ils arrivent quand un appareil à jour se synchronise ' +
      '(à partir de la prochaine version de Vyzo).'))
  }
  return null
}

// ------------------------------------------------------------------ Coffre chiffré

/** Case « Rester déverrouillé sur cet ordinateur », décochée par défaut. */
function rememberBox(id: string) {
  const input = h('input', { id, type: 'checkbox' })
  const box = h('label', { class: 'check compact', for: id }, input, h('span', {},
    h('strong', {}, 'Rester déverrouillé sur cet ordinateur'),
    h('small', {}, 'Plus besoin du mot de passe à chaque visite. À éviter sur un ordinateur partagé : tes abonnements et ton historique y resteraient lisibles. « Verrouiller » ou la déconnexion l’oublient.')))
  return { input, box }
}

/** Formulaire du mot de passe du compte, qui ouvre la clé des données chiffrées. */
function unlockForm(onDone: () => void) {
  const password = h('input', { id: 'vault-pass', type: 'password', autocomplete: 'current-password', required: true })
  const error = h('p', { class: 'form-error', role: 'alert' })
  const submit = h('button', { class: 'btn primary', type: 'submit' }, icon('unlock', 16), 'Déverrouiller')
  const stay = rememberBox('vault-remember')
  const form = h('form', {
    class: 'unlock-form',
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      error.textContent = ''
      let ok = false
      await act(submit, async () => { ok = await backend.unlockVault(state.account!, password.value, stay.input.checked) })
      if (!ok) {
        error.textContent = 'Mot de passe incorrect.'
        password.select()
        return
      }
      password.value = ''
      toast(stay.input.checked ? 'Déverrouillé, et gardé sur cet ordinateur' : 'Données chiffrées déverrouillées')
      onDone()
      await refresh(['playlists', ...(state.progress ? ['progress' as const] : []), ...(state.lists ? ['lists' as const] : [])])
    },
  },
    h('label', { for: 'vault-pass' }, 'Mot de passe du compte Vyzo'),
    h('div', { class: 'inline-field' }, password, submit),
    stay.box,
    error,
    h('p', { class: 'fine' },
      'Le déchiffrement se fait dans ce navigateur. Sans la case, la clé reste en mémoire et s’oublie au rechargement de la page.'))
  return { form, focus: () => password.focus() }
}

function unlockDialog() {
  let dialog: HTMLDialogElement
  const { form, focus } = unlockForm(() => dialog.close())
  dialog = openDialog(h('div', {},
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('lock', 22)), h('h2', {}, 'Déverrouiller')),
    form))
  focus()
}

function vaultGate() {
  if (state.vault === 'missing') {
    return emptyCard('key', 'Rien de chiffré sur ce compte pour l’instant',
      'Connecte Vyzo au compte sur un de tes appareils : il crée la clé du compte et y range tes abonnements. Ils apparaîtront ici ensuite.')
  }
  const { form } = unlockForm(() => {})
  return h('div', { class: 'gate' },
    h('span', { class: 'kind-badge big' }, icon('lock', 24)),
    h('div', { class: 'gate-main' },
      h('h2', {}, 'Abonnements chiffrés'),
      h('p', {}, 'Tes identifiants IPTV sont chiffrés avec le mot de passe du compte : le serveur Vyzo ne peut pas les lire. ' +
        'Saisis-le pour les afficher et les modifier.'),
      form))
}

// ------------------------------------------------------------------ Profils

function profilesView() {
  const profiles = state.profiles
  const add = h('button', { class: 'btn primary', type: 'button', onclick: () => editProfile(null) },
    icon('plus', 18), 'Ajouter un profil')
  const box = h('section', {},
    header('Profils', 'Les profils « Qui regarde » de tous tes appareils. Chaque modification arrive sur la TV, le téléphone et le PC.', add),
    errorBanner('profiles'))
  if (!profiles) return append(box, [state.errors.profiles ? null : loading()]), box

  const grid = h('ul', { class: 'profile-grid' })
  profiles.forEach((p, i) => {
    const up = h('button', {
      class: 'icon-btn', type: 'button', title: 'Monter', 'aria-label': `Monter ${p.name}`, disabled: i === 0,
      onclick: (e: Event) => void move(e.currentTarget as HTMLButtonElement, profiles, p.id, -1),
    }, icon('up', 16))
    const down = h('button', {
      class: 'icon-btn', type: 'button', title: 'Descendre', 'aria-label': `Descendre ${p.name}`, disabled: i === profiles.length - 1,
      onclick: (e: Event) => void move(e.currentTarget as HTMLButtonElement, profiles, p.id, 1),
    }, icon('down', 16))
    const a = state.activity?.find((x) => x.profileId === p.id)
    grid.appendChild(h('li', { class: 'profile-card' },
      avatar(p),
      h('div', { class: 'profile-name' }, p.name),
      h('div', { class: 'tags' },
        p.isKid ? h('span', { class: 'tag' }, icon('child', 13), 'Enfant') : null,
        p.pinHash ? h('span', { class: 'tag' }, icon('lock', 13), 'Code') : null),
      a ? h('div', { class: 'profile-stats' },
        `${a.inProgress} en cours · ${a.inLists} dans les listes`) : null,
      h('div', { class: 'card-actions' },
        h('button', { class: 'btn ghost small', type: 'button', onclick: () => editProfile(p) }, icon('edit', 15), 'Modifier'),
        h('span', { class: 'order' }, up, down)),
    ))
  })
  append(box, [grid])
  if (profiles.length === 0) {
    append(box, [h('p', { class: 'empty' }, 'Aucun profil sur ce compte. Le premier appareil connecté crée « Moi » ; tu peux aussi en ajouter un ici.')])
  }
  return box
}

async function move(button: HTMLButtonElement, profiles: Profile[], id: string, delta: -1 | 1) {
  if (await act(button, () => backend.saveProfiles(moveProfile(profiles, id, delta)))) await refresh(['profiles'])
}

function editProfile(existing: Profile | null) {
  const profiles = state.profiles ?? []
  const isNew = !existing
  const draft: Profile = existing ? { ...existing } : {
    id: crypto.randomUUID(), name: '', colorIndex: nextColor(profiles), isKid: false, pinHash: '',
    position: profiles.reduce((m, p) => Math.max(m, p.position), -1) + 1, updatedAt: 0,
  }
  /** null : on garde le code actuel ; '' : on le retire ; 4 chiffres : nouveau code. */
  let newPin: string | null = null

  const name = h('input', { id: 'pname', value: draft.name, maxlength: MAX_PROFILE_NAME, required: true, autocomplete: 'off' })
  let previewEl = avatar({ ...draft, name: draft.name || '?' })
  const refreshPreview = () => {
    const fresh = avatar({ name: name.value || '?', colorIndex: draft.colorIndex, isKid: kid.checked })
    previewEl.replaceWith(fresh)
    previewEl = fresh
  }
  name.addEventListener('input', refreshPreview)

  const swatches = h('div', { class: 'swatches', role: 'radiogroup', 'aria-label': 'Couleur' },
    PROFILE_PALETTE.map((color, i) => {
      const b = h('button', {
        type: 'button', class: 'swatch', role: 'radio', 'aria-checked': String(i === draft.colorIndex), 'aria-label': `Couleur ${i + 1}`,
        onclick: () => {
          draft.colorIndex = i
          for (const s of swatches.querySelectorAll('.swatch')) s.setAttribute('aria-checked', 'false')
          b.setAttribute('aria-checked', 'true')
          refreshPreview()
        },
      })
      b.style.setProperty('--c', color)
      return b
    }))

  const kid = h('input', { id: 'pkid', type: 'checkbox', checked: draft.isKid, onchange: () => refreshPreview() })

  // Code du profil
  const pinBox = h('div', { class: 'pin-box' })
  const pin1 = h('input', { type: 'password', inputmode: 'numeric', maxlength: 4, pattern: '\\d{4}', autocomplete: 'new-password', 'aria-label': 'Nouveau code', placeholder: '••••' })
  const pin2 = h('input', { type: 'password', inputmode: 'numeric', maxlength: 4, pattern: '\\d{4}', autocomplete: 'new-password', 'aria-label': 'Confirmer le code', placeholder: '••••' })
  const renderPin = (editing: boolean) => {
    clear(pinBox)
    const hasPin = newPin === null ? !!draft.pinHash : newPin !== ''
    if (editing) {
      pin1.value = ''; pin2.value = ''
      append(pinBox, [
        h('div', { class: 'pin-fields' },
          h('label', {}, 'Nouveau code', pin1), h('label', {}, 'Confirmer', pin2)),
        h('button', { class: 'btn ghost small', type: 'button', onclick: () => { newPin = null; renderPin(false) } }, 'Garder le code actuel'),
      ])
      pin1.focus()
      return
    }
    append(pinBox, [
      h('p', { class: 'pin-state' }, icon('lock', 15), hasPin
        ? (newPin ? 'Nouveau code prêt à être enregistré' : 'Code à 4 chiffres demandé à l’ouverture du profil')
        : 'Aucun code : le profil s’ouvre directement'),
      h('div', { class: 'row' },
        h('button', { class: 'btn ghost small', type: 'button', onclick: () => renderPin(true) }, hasPin ? 'Changer le code' : 'Ajouter un code'),
        hasPin ? h('button', { class: 'btn ghost small', type: 'button', onclick: () => { newPin = ''; renderPin(false) } }, 'Retirer le code') : null),
    ])
  }
  renderPin(false)

  const error = h('p', { class: 'form-error', role: 'alert' })
  const save = h('button', { class: 'btn primary', type: 'submit' }, isNew ? 'Créer le profil' : 'Enregistrer')
  const cancel = h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler')
  const canDelete = !isNew && profiles.length > 1
  const del = isNew ? null : h('button', {
    class: 'btn danger-ghost', type: 'button', disabled: !canDelete,
    title: canDelete ? '' : 'Un compte garde toujours au moins un profil',
    onclick: async () => {
      const ok = await confirmDialog({
        title: `Supprimer « ${existing!.name} » ?`,
        body: 'Le profil disparaît de tous tes appareils, avec sa progression, son historique et ses listes. Impossible de revenir en arrière.',
        confirm: 'Supprimer le profil', danger: true,
      })
      if (!ok) return
      if (await act(del, () => backend.deleteProfile(existing!), `Profil « ${existing!.name} » supprimé`)) {
        dialog.close()
        await refresh(['profiles', 'activity'])
      }
    },
  }, icon('trash', 16), 'Supprimer')

  const form = h('form', {
    method: 'dialog',
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      error.textContent = ''
      if (pinBox.contains(pin1)) {
        if (!isValidPin(pin1.value)) { error.textContent = 'Le code doit faire 4 chiffres.'; pin1.focus(); return }
        if (pin1.value !== pin2.value) { error.textContent = 'Les deux codes ne correspondent pas.'; pin2.focus(); return }
        newPin = pin1.value
      }
      const final: Profile = {
        ...draft,
        name: cleanName(name.value),
        isKid: kid.checked,
        pinHash: newPin === null ? draft.pinHash : newPin === '' ? '' : await hashPin(draft.id, newPin),
      }
      if (await act(save, () => backend.saveProfiles([final]), isNew ? `Profil « ${final.name} » créé` : 'Profil enregistré')) {
        dialog.close()
        await refresh(['profiles', 'activity'])
      }
    },
  },
    h('div', { class: 'dialog-head' }, previewEl, h('h2', {}, isNew ? 'Nouveau profil' : 'Modifier le profil')),
    h('label', { for: 'pname' }, 'Nom'), name,
    h('span', { class: 'field-label' }, 'Couleur'), swatches,
    h('label', { class: 'check' }, kid, h('span', {}, h('strong', {}, 'Profil enfant'),
      h('small', {}, 'Les catégories verrouillées par le contrôle parental sont toujours cachées, même avec le code parental.'))),
    h('span', { class: 'field-label' }, 'Code du profil'), pinBox,
    error,
    h('div', { class: 'dialog-actions' }, del, h('span', { class: 'spacer' }), cancel, save),
  )
  const dialog = openDialog(form)
  if (isNew) name.focus()
}

// ------------------------------------------------------------------ Appareils

const DEVICE_ICON: Record<string, string> = { tv: 'tv', mobile: 'phone', pc: 'monitor' }

function devicesView() {
  const devices = state.devices
  const latest = state.latest
  const outdated = (devices ?? []).filter((d) => needsUpdate(d, latest) && !updateInProgress(d))
  const all = outdated.length > 1 ? h('button', {
    class: 'btn primary', type: 'button',
    onclick: async (e: Event) => {
      const b = e.currentTarget as HTMLButtonElement
      if (await act(b, async () => { for (const d of outdated) await backend.requestUpdate(d) },
        `Mise à jour demandée à ${plural(outdated.length, 'appareil', 'appareils')}`)) await refresh(['devices'])
    },
  }, icon('refresh', 17), `Tout mettre à jour (${outdated.length})`) : null

  const box = h('section', {},
    header('Appareils', 'Les TV, téléphones et PC où Vyzo est connecté à ce compte. Tu peux lancer la mise à jour de l’appli à distance.', all),
    errorBanner('devices'))
  if (!devices) return append(box, [state.errors.devices ? null : loading()]), box

  if (latest) {
    append(box, [h('p', { class: 'latest' }, icon('check', 15), 'Dernière version publiée : ', h('strong', {}, latest.versionName || String(latest.versionCode)))])
  }
  if (devices.length === 0) {
    append(box, [emptyCard('devices', 'Aucun appareil pour l’instant',
      'Chaque appareil apparaît ici après sa première synchronisation avec une version de Vyzo qui se signale au compte (à partir de la prochaine version).')])
    return box
  }
  const now = Date.now()
  append(box, [h('ul', { class: 'rows' }, devices.map((d) => deviceRow(d, latest, now)))])
  append(box, [h('p', { class: 'note' }, icon('info', 16), h('span', {},
    'Un appareil reçoit la demande de mise à jour quand Vyzo y est ouvert, ou à sa prochaine ouverture dans les 24 h. ' +
    'Sur Android, l’installation peut demander une confirmation à l’écran.'))])
  return box
}

function deviceRow(d: Device, latest: LatestVersion | null, now: number) {
  const online = isOnline(d, now)
  const outdated = needsUpdate(d, latest)
  const waiting = updateInProgress(d, now)
  const label = d.name || d.model || 'Appareil'

  const versionPill = !d.appVersion ? null
    : outdated ? h('span', { class: 'pill planned', title: `Version installée ${d.appVersion}` }, `${d.appVersion} → ${latest!.versionName}`)
      : latest && d.versionCode >= latest.versionCode ? h('span', { class: 'pill ok' }, `${d.appVersion} · à jour`)
        : h('span', { class: 'pill muted' }, d.appVersion)

  let status: HTMLElement | null = null
  if (updatePending(d)) {
    status = h('div', { class: 'device-status waiting' }, h('span', { class: 'spinner', 'aria-hidden': 'true' }),
      online ? 'Demande envoyée, en attente de l’appareil…' : 'Demande envoyée : elle sera faite à la prochaine ouverture de Vyzo (24 h).')
  } else if (waiting) {
    status = h('div', { class: 'device-status waiting' }, h('span', { class: 'spinner', 'aria-hidden': 'true' }), d.updateStatus)
  } else if (d.updateStatus && d.updateStatusAt > 0) {
    const failed = /^Échec|n’autorise|n'autorise/.test(d.updateStatus)
    status = h('div', { class: `device-status ${failed ? 'bad' : ''}` }, icon(failed ? 'info' : 'refresh', 14),
      h('span', {}, d.updateStatus), h('span', { class: 'faint' }, ` · ${relative(d.updateStatusAt)}`))
  }

  const update: HTMLButtonElement = h('button', {
    class: `btn ${outdated ? 'primary' : 'ghost'} small`, type: 'button', disabled: waiting,
    title: outdated ? '' : 'L’appareil vérifie s’il existe une version plus récente et l’installe',
    onclick: async () => {
      if (await act(update, () => backend.requestUpdate(d), `Mise à jour demandée à « ${label} »`)) await refresh(['devices'])
    },
  }, icon('refresh', 15), outdated ? 'Mettre à jour' : 'Vérifier')

  const remove: HTMLButtonElement = h('button', {
    class: 'icon-btn', type: 'button', title: 'Retirer de la liste', 'aria-label': `Retirer ${label} de la liste`,
    onclick: async () => {
      const ok = await confirmDialog({
        title: `Retirer « ${label} » ?`,
        body: 'L’appareil disparaît de cette liste, mais il reste connecté au compte : il réapparaîtra s’il se synchronise encore. ' +
          'Pour le déconnecter, passe par Réglages → Compte Vyzo sur l’appareil.',
        confirm: 'Retirer', danger: true,
      })
      if (ok && await act(remove, () => backend.removeDevice(d), 'Appareil retiré de la liste')) await refresh(['devices'])
    },
  }, icon('trash', 16))

  const details = [d.model && d.model !== d.name ? d.model : null, d.system || null].filter(Boolean).join(' · ')
  return h('li', { class: 'row-card device' },
    h('span', { class: 'kind-badge big' }, icon(DEVICE_ICON[d.kind] ?? 'devices', 22),
      h('span', { class: `presence ${online ? 'on' : ''}`, title: online ? 'En ligne' : 'Hors ligne' })),
    h('div', { class: 'row-main' },
      h('div', { class: 'title-line' }, h('span', { class: 'row-title' }, label), versionPill),
      h('div', { class: 'row-sub wrap' },
        h('span', { class: online ? 'online' : '' }, online ? 'En ligne' : `Vu ${relative(d.lastSeenAt)}`),
        details ? h('span', { class: 'faint' }, `· ${details}`) : null),
      status),
    h('div', { class: 'row-actions' }, update, remove),
  )
}

// ------------------------------------------------------------------ Abonnements IPTV

const TYPE_LABEL: Record<Playlist['type'], string> = { XTREAM: 'Xtream Codes', M3U: 'Liste M3U', STALKER: 'Portail Stalker' }

function hostOf(url: string): string {
  const raw = url.trim()
  try {
    return new URL(/^[a-z][a-z0-9+.-]*:\/\//i.test(raw) ? raw : `http://${raw}`).host
  } catch {
    return raw
  }
}

function playlistsView() {
  const open = state.vault === 'open'
  const add = open ? h('button', { class: 'btn primary', type: 'button', onclick: () => editPlaylist(null) },
    icon('plus', 18), 'Ajouter un abonnement') : null
  const lockBtn = open ? h('button', {
    class: 'btn ghost', type: 'button',
    title: state.remembered ? 'Verrouiller et oublier la clé gardée sur cet ordinateur' : 'Oublier la clé dans ce navigateur',
    onclick: async () => {
      const wasRemembered = state.remembered
      await backend.lockVault()
      state.vault = 'locked'
      state.remembered = false
      state.playlists = null
      if (state.progress) state.progress.items = state.progress.items.map((x) => ({ ...x, title: '', subtitle: '' }))
      if (state.lists) state.lists.items = state.lists.items.map((x) => ({ ...x, title: '', subtitle: '' }))
      renderView()
      toast(wasRemembered ? 'Verrouillé : la clé n’est plus gardée sur cet ordinateur' : 'Données chiffrées verrouillées')
    },
  }, icon('lock', 16), 'Verrouiller') : null

  const box = h('section', {},
    header('Abonnements IPTV', 'Les abonnements du compte. Un abonnement ajouté ou modifié ici arrive sur la TV, le téléphone et le PC.', lockBtn, add),
    errorBanner('playlists'))
  if (state.vault === null) return append(box, [state.errors.playlists ? null : loading()]), box
  if (!open) return append(box, [vaultGate()]), box

  const playlists = state.playlists
  if (!playlists) return append(box, [state.errors.playlists ? null : loading()]), box
  if (playlists.length === 0) {
    append(box, [emptyCard('key', 'Aucun abonnement',
      'Ajoute ton abonnement Xtream, ta liste M3U ou ton portail Stalker : il sera proposé sur tous tes appareils.')])
    return box
  }
  append(box, [h('ul', { class: 'rows' }, playlists.map((p) => playlistRow(p, playlists)))])
  append(box, [h('p', { class: 'note' }, icon('lock', 16), h('span', {},
    'Le chiffrement se fait dans ce navigateur, avec la clé du compte : le serveur Vyzo ne voit jamais tes identifiants en clair.'))])
  return box
}

function playlistRow(p: Playlist, all: Playlist[]) {
  const sub = [TYPE_LABEL[p.type], hostOf(p.url), p.type === 'XTREAM' ? p.username : p.type === 'STALKER' ? p.mac : null]
    .filter(Boolean).join(' · ')
  const del: HTMLButtonElement = h('button', {
    class: 'icon-btn', type: 'button', title: 'Supprimer', 'aria-label': `Supprimer ${p.name}`,
    onclick: async () => {
      const ok = await confirmDialog({
        title: `Supprimer « ${p.name} » ?`,
        body: 'L’abonnement disparaît de tous tes appareils, avec ses réglages de chaînes (masquées, ordre). Tu pourras le rajouter plus tard.',
        confirm: 'Supprimer l’abonnement', danger: true,
      })
      if (ok && await act(del, () => backend.deletePlaylist(state.account!, p), `« ${p.name} » supprimé`)) await refresh(['playlists'])
    },
  }, icon('trash', 16))
  return h('li', { class: 'row-card' },
    h('span', { class: 'kind-badge big' }, icon(p.type === 'M3U' ? 'list' : 'key', 20)),
    h('div', { class: 'row-main' },
      h('div', { class: 'row-title' }, p.name),
      h('div', { class: 'row-sub' }, h('span', { class: 'ellipsis' }, sub))),
    h('div', { class: 'row-actions' },
      h('button', { class: 'btn ghost small', type: 'button', onclick: () => editPlaylist(p, all) }, icon('edit', 15), 'Modifier'),
      del),
  )
}

const MAC_RE = /^([0-9A-F]{2}:){5}[0-9A-F]{2}$/i

function editPlaylist(existing: Playlist | null, all: Playlist[] = state.playlists ?? []) {
  const draft: PlaylistDraft = existing
    ? { name: existing.name, type: existing.type, url: existing.url, username: existing.username, password: existing.password,
      mac: existing.mac || '00:1A:79:', userAgent: existing.userAgent }
    : { name: '', type: 'XTREAM', url: '', username: '', password: '', mac: '00:1A:79:', userAgent: '' }

  const input = (id: string, value: string, attrs: Record<string, string | boolean> = {}) => {
    const el = h('input', { id, value, autocomplete: 'off', spellcheck: 'false', ...attrs })
    return el
  }
  const name = input('pl-name', draft.name, { placeholder: 'Facultatif', maxlength: '60' })
  const url = input('pl-url', draft.url, { inputmode: 'url' })
  const user = input('pl-user', draft.username)
  const pass = input('pl-pass', draft.password, { type: 'password', autocomplete: 'new-password' })
  const mac = input('pl-mac', draft.mac, { placeholder: '00:1A:79:XX:XX:XX' })
  mac.addEventListener('input', () => { mac.value = mac.value.toUpperCase() })
  const agent = input('pl-agent', draft.userAgent, { placeholder: 'Celui de Vyzo par défaut' })
  const reveal: HTMLButtonElement = h('button', {
    class: 'icon-btn field-btn', type: 'button', 'aria-label': 'Afficher le mot de passe', 'aria-pressed': 'false',
    onclick: () => {
      const shown = pass.type === 'text'
      pass.type = shown ? 'password' : 'text'
      reveal.setAttribute('aria-pressed', String(!shown))
      reveal.setAttribute('aria-label', shown ? 'Afficher le mot de passe' : 'Masquer le mot de passe')
      clear(reveal)
      reveal.appendChild(icon(shown ? 'eye' : 'eyeOff', 16))
    },
  }, icon('eye', 16))

  const fields = h('div', {})
  const tabs = h('div', { class: 'segmented', role: 'radiogroup', 'aria-label': 'Type d’abonnement' })
  const renderFields = () => {
    clear(tabs)
    for (const t of ['XTREAM', 'M3U', 'STALKER'] as const) {
      tabs.appendChild(h('button', {
        type: 'button', role: 'radio', 'aria-checked': String(draft.type === t),
        onclick: () => { draft.type = t; renderFields(); url.focus() },
      }, TYPE_LABEL[t]))
    }
    clear(fields)
    const urlLabel = draft.type === 'M3U' ? 'Adresse de la liste M3U' : draft.type === 'XTREAM' ? 'Adresse du serveur' : 'Adresse du portail'
    url.placeholder = draft.type === 'M3U' ? 'https://…/playlist.m3u' : draft.type === 'XTREAM' ? 'http://serveur.exemple:8080' : 'http://portail.exemple/c/'
    append(fields, [h('label', { for: 'pl-url' }, urlLabel), url])
    if (draft.type === 'XTREAM') {
      append(fields, [
        h('div', { class: 'two' },
          h('div', {}, h('label', { for: 'pl-user' }, 'Identifiant'), user),
          h('div', {}, h('label', { for: 'pl-pass' }, 'Mot de passe'), h('div', { class: 'with-btn' }, pass, reveal))),
      ])
    }
    if (draft.type === 'STALKER') append(fields, [h('label', { for: 'pl-mac' }, 'Adresse MAC'), mac])
  }
  renderFields()

  const error = h('p', { class: 'form-error', role: 'alert' })
  const save = h('button', { class: 'btn primary', type: 'submit' }, existing ? 'Enregistrer' : 'Ajouter')
  const cancel = h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler')

  const validate = (): string | null => {
    if (!url.value.trim()) {
      return draft.type === 'M3U' ? 'Indique l’adresse de la playlist M3U.'
        : draft.type === 'XTREAM' ? 'Indique l’adresse du serveur.' : 'Indique l’adresse du portail.'
    }
    if (draft.type === 'XTREAM' && (!user.value.trim() || !pass.value)) return 'Identifiant et mot de passe requis.'
    if (draft.type === 'STALKER' && !MAC_RE.test(mac.value.trim())) return 'Adresse MAC invalide (format 00:1A:79:XX:XX:XX).'
    return null
  }

  const form = h('form', {
    method: 'dialog',
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      error.textContent = validate() ?? ''
      if (error.textContent) return
      // Mêmes règles que PlaylistEditScreen : champs nettoyés, nom par défaut tiré de l'adresse.
      const final: PlaylistDraft = {
        type: draft.type,
        name: name.value.trim() || hostOf(url.value) || (draft.type === 'M3U' ? 'Playlist M3U' : draft.type === 'XTREAM' ? 'Xtream' : 'Portail'),
        url: url.value.trim(),
        username: draft.type === 'XTREAM' ? user.value.trim() : '',
        password: draft.type === 'XTREAM' ? pass.value : '',
        mac: draft.type === 'STALKER' ? mac.value.trim().toUpperCase() : '',
        userAgent: agent.value.trim(),
      }
      save.setAttribute('aria-busy', 'true')
      save.disabled = true
      try {
        await backend.savePlaylist(state.account!, final, existing, all)
        toast(existing ? 'Abonnement enregistré' : `« ${final.name} » ajouté à tes appareils`)
        dialog.close()
        await refresh(['playlists'])
      } catch (e) {
        error.textContent = e instanceof UserError ? e.message : friendlyError(e)
      } finally {
        save.removeAttribute('aria-busy')
        save.disabled = false
      }
    },
  },
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('key', 22)),
      h('h2', {}, existing ? 'Modifier l’abonnement' : 'Nouvel abonnement')),
    h('span', { class: 'field-label' }, 'Type'), tabs,
    h('label', { for: 'pl-name' }, 'Nom'), name,
    fields,
    h('details', { class: 'more' }, h('summary', {}, 'Options avancées'),
      h('label', { for: 'pl-agent' }, 'User-Agent'), agent),
    existing ? h('p', { class: 'fine' },
      'Changer d’identifiant, d’adresse MAC ou d’adresse M3U en fait un nouvel abonnement pour les appareils : ' +
      'ses réglages de chaînes (masquées, ordre) repartent de zéro.') : null,
    error,
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }), cancel, save),
  )
  const dialog = openDialog(form)
  dialog.classList.add('wide')
  ;(existing ? name : url).focus()
}

// ------------------------------------------------------------------ Historique

function historyView() {
  const profile = state.profiles?.find((p) => p.id === state.selected) ?? null
  const progress = state.progress?.profileId === state.selected ? state.progress.items : null
  const clearAll: HTMLButtonElement | null = profile ? h('button', {
    class: 'btn danger-ghost', type: 'button', disabled: !progress || progress.length === 0,
    onclick: async () => {
      const n = progress?.length ?? 0
      const ok = await confirmDialog({
        title: `Effacer l’historique de ${profile.name} ?`,
        body: `${plural(n, 'contenu disparaît', 'contenus disparaissent')} de « Reprendre » et de l’historique, sur tous les appareils. Les listes ne sont pas touchées.`,
        confirm: 'Tout effacer', danger: true,
      })
      if (ok && await act(clearAll, () => backend.clearHistory(state.account!, profile.id), `Historique de ${profile.name} effacé`)) {
        await refresh(['progress', 'activity'])
      }
    },
  }, icon('trash', 16), 'Tout effacer') : null

  const box = h('section', {},
    header('Historique', 'Ce que chaque profil a commencé, sur tous les appareils. Retirer un titre l’enlève aussi de « Reprendre ».', clearAll),
    profilePicker((p) => state.activity?.find((a) => a.profileId === p.id)?.inProgress ?? null),
    errorBanner('progress'))
  if (!state.profiles) return append(box, [loading()]), box
  if (!profile) return append(box, [h('p', { class: 'empty' }, 'Aucun profil sur ce compte.')]), box
  if (!progress) return append(box, [state.errors.progress ? null : loading()]), box
  if (progress.length === 0) {
    append(box, [emptyCard('history', 'Rien en cours', `${profile.name} n’a rien commencé pour l’instant, ou l’historique vient d’être effacé.`)])
    return box
  }
  append(box, [titlesNotice(progress), h('ul', { class: 'rows' }, progress.map((x) => progressRow(x, profile)))])
  return box
}

/** Largeur posée par le CSSOM : la politique de sécurité du site refuse les attributs style. */
function barFill(ratio: number) {
  const el = h('span', {})
  el.style.width = `${Math.round(ratio * 100)}%`
  return el
}

function progressRow(x: ProgressItem, profile: Profile) {
  const t = contentTitle(x)
  const ratio = progressRatio(x)
  const remove: HTMLButtonElement = h('button', {
    class: 'icon-btn', type: 'button', title: 'Retirer de l’historique', 'aria-label': `Retirer ${t.text} de l’historique`,
    onclick: async () => {
      if (await act(remove, () => backend.removeProgress(state.account!, profile.id, [x.contentKey]), 'Retiré de l’historique')) {
        await refresh(['progress', 'activity'])
      }
    },
  }, icon('x', 16))

  const when = x.kind === 'LIVE' ? `Regardée ${relative(x.updatedAt)}` : relative(x.updatedAt)
  const watched = x.durationMs > 0
    ? (ratio >= 0.95 ? 'Terminé' : `${Math.round(ratio * 100)} % · reste ${duration(x.durationMs - x.positionMs)}`)
    : null
  return h('li', { class: 'row-card' },
    kindBadge(x.kind),
    h('div', { class: 'row-main' },
      h('div', { class: `row-title ${t.known ? '' : 'unknown'}` }, t.text),
      h('div', { class: 'row-sub wrap' },
        x.subtitle ? h('span', { class: 'ellipsis' }, x.subtitle) : null,
        x.subtitle ? h('span', { class: 'faint' }, '·') : null,
        h('span', { class: 'faint', title: fullFmt.format(x.updatedAt) }, when)),
      watched ? h('div', { class: 'progress-line' },
        h('span', { class: 'bar' }, barFill(ratio)),
        h('span', { class: 'bar-label' }, watched)) : null),
    remove,
  )
}

// ------------------------------------------------------------------ Listes

function listsView() {
  const profile = state.profiles?.find((p) => p.id === state.selected) ?? null
  const data = state.lists?.profileId === state.selected ? state.lists : null
  const add = profile && data ? h('button', { class: 'btn primary', type: 'button', onclick: () => editList(profile, null, data) },
    icon('plus', 18), 'Nouvelle liste') : null

  const box = h('section', {},
    header('Listes', 'Les favoris et les listes de chaque profil. On ajoute depuis l’appli ; ici, tu ranges et tu fais le ménage.', add),
    profilePicker((p) => state.activity?.find((a) => a.profileId === p.id)?.inLists ?? null),
    errorBanner('lists'))
  if (!state.profiles) return append(box, [loading()]), box
  if (!profile) return append(box, [h('p', { class: 'empty' }, 'Aucun profil sur ce compte.')]), box
  if (!data) return append(box, [state.errors.lists ? null : loading()]), box

  append(box, [titlesNotice(data.items)])
  const favorites: UserList = { profileId: profile.id, id: FAVORITES_LIST_ID, name: 'Favoris', position: -1, updatedAt: 0 }
  const lists = [favorites, ...[...data.lists].sort((a, b) => a.position - b.position || a.name.localeCompare(b.name, 'fr'))]
  for (const list of lists) {
    const items = data.items.filter((x) => x.listId === list.id).sort((a, b) => a.position - b.position || b.addedAt - a.addedAt)
    const isFav = list.id === FAVORITES_LIST_ID
    const del: HTMLButtonElement | null = isFav ? null : h('button', {
      class: 'icon-btn', type: 'button', title: 'Supprimer la liste', 'aria-label': `Supprimer la liste ${list.name}`,
      onclick: async () => {
        const ok = await confirmDialog({
          title: `Supprimer la liste « ${list.name} » ?`,
          body: (items.length === 0 ? 'Cette liste vide disparaît'
            : `La liste et ${items.length === 1 ? 'son seul élément' : `ses ${items.length} éléments`} disparaissent`) +
            ` de tous les appareils de ${profile.name}. Les films, séries et chaînes eux-mêmes ne sont pas touchés.`,
          confirm: 'Supprimer la liste', danger: true,
        })
        if (ok && await act(del, () => backend.deleteList(list), `Liste « ${list.name} » supprimée`)) await refresh(['lists', 'activity'])
      },
    }, icon('trash', 15))
    append(box, [
      h('div', { class: 'list-head' },
        h('h2', { class: 'group-title' }, isFav ? icon('heart', 16) : icon('list', 16), list.name,
          h('span', { class: 'count' }, String(items.length))),
        isFav ? null : h('span', { class: 'order' },
          h('button', { class: 'icon-btn', type: 'button', title: 'Renommer', 'aria-label': `Renommer ${list.name}`,
            onclick: () => editList(profile, list, data) }, icon('edit', 15)),
          del)),
      items.length === 0
        ? h('p', { class: 'empty small' }, isFav ? 'Aucun favori.' : 'Liste vide. Ajoute des films ou des séries depuis l’appli.')
        : h('ul', { class: 'rows' }, items.map((x) => listItemRow(x, list))),
    ])
  }
  return box
}

function listItemRow(x: ListItem, list: UserList) {
  const t = contentTitle(x)
  const remove: HTMLButtonElement = h('button', {
    class: 'icon-btn', type: 'button', title: `Retirer de « ${list.name} »`, 'aria-label': `Retirer ${t.text} de ${list.name}`,
    onclick: async () => {
      if (await act(remove, () => backend.removeListItem(x), `Retiré de « ${list.name} »`)) await refresh(['lists', 'activity'])
    },
  }, icon('x', 16))
  return h('li', { class: 'row-card compact' },
    kindBadge(x.kind),
    h('div', { class: 'row-main' },
      h('div', { class: `row-title ${t.known ? '' : 'unknown'}` }, t.text),
      h('div', { class: 'row-sub' }, h('span', { class: 'ellipsis' },
        [t.known ? KIND_LABEL[x.kind] : null, x.subtitle || null, x.addedAt > 0 ? `ajouté ${relative(x.addedAt)}` : null].filter(Boolean).join(' · ')))),
    remove,
  )
}

function editList(profile: Profile, existing: UserList | null, data: ListsData) {
  const name = h('input', { id: 'list-name', value: existing?.name ?? '', maxlength: 40, required: true, autocomplete: 'off' })
  const error = h('p', { class: 'form-error', role: 'alert' })
  const save = h('button', { class: 'btn primary', type: 'submit' }, existing ? 'Renommer' : 'Créer la liste')
  const cancel = h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler')
  const form = h('form', {
    method: 'dialog',
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      const clean = name.value.trim().replace(/\s+/g, ' ')
      if (!clean) { error.textContent = 'Donne un nom à la liste.'; return }
      const list: UserList = existing ? { ...existing, name: clean } : {
        profileId: profile.id, id: crypto.randomUUID(), name: clean, updatedAt: 0,
        position: data.lists.reduce((m, l) => Math.max(m, l.position), 0) + 1,
      }
      if (await act(save, () => backend.saveList(list), existing ? 'Liste renommée' : `Liste « ${clean} » créée pour ${profile.name}`)) {
        dialog.close()
        await refresh(['lists'])
      }
    },
  },
    h('h2', {}, existing ? 'Renommer la liste' : `Nouvelle liste pour ${profile.name}`),
    h('label', { for: 'list-name' }, 'Nom'), name,
    error,
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }), cancel, save))
  const dialog = openDialog(form)
  dialog.classList.add('small')
  name.focus()
}

// ------------------------------------------------------------------ Enregistrements

const GROUPS: { id: RecordingGroup; title: string }[] = [
  { id: 'upcoming', title: 'À venir et en cours' },
  { id: 'done', title: 'Terminés' },
  { id: 'other', title: 'Annulés, en échec ou manqués' },
]

function recordingsView() {
  const box = h('section', {},
    header('Enregistrements', 'Les programmations faites depuis le guide de Vyzo. C’est Vyzo PC qui enregistre : il doit être allumé à l’heure prévue.'),
    errorBanner('recordings'))
  const recordings = state.recordings
  if (!recordings) return append(box, [state.errors.recordings ? null : loading()]), box
  if (recordings.length === 0) {
    append(box, [emptyCard('record', 'Aucun enregistrement',
      'Dans le guide TV de Vyzo, sur la TV, le téléphone ou le PC, choisis un programme puis « Enregistrer ». Il apparaîtra ici.')])
    return box
  }
  const now = Date.now()
  for (const g of GROUPS) {
    const items = sortRecordings(recordings.filter((r) => recordingGroup(r, now) === g.id), g.id)
    if (items.length === 0) continue
    append(box, [h('h2', { class: 'group-title' }, g.title, h('span', { class: 'count' }, String(items.length))),
      h('ul', { class: 'rows' }, items.map((r) => recordingRow(r, now)))])
  }
  return box
}

function recordingRow(r: Recording, now: number) {
  const status = statusLabel(r, now)
  const statusClass = r.status === 'RECORDING' ? 'live' : r.status === 'DONE' ? 'ok' : r.status === 'FAILED' ? 'bad'
    : r.status === 'SCHEDULED' && r.stopMs > now ? 'planned' : 'muted'
  const name = r.title || 'Programme sans titre'
  let action: HTMLButtonElement
  if (r.status === 'SCHEDULED' && r.stopMs > now) {
    action = h('button', {
      class: 'btn ghost small', type: 'button',
      onclick: async () => {
        const ok = await confirmDialog({
          title: 'Annuler cet enregistrement ?', body: `« ${name} » sur ${r.channelName}, ${dayLabel(r.startMs).toLowerCase()} à ${timeFmt.format(r.startMs)}.`,
          confirm: 'Annuler l’enregistrement', cancel: 'Garder', danger: true,
        })
        if (ok && await act(action, () => backend.setRecordingStatus(r, 'CANCELLED'), 'Enregistrement annulé')) await refresh(['recordings'])
      },
    }, icon('x', 15), 'Annuler')
  } else if (r.status === 'RECORDING') {
    action = h('button', {
      class: 'btn ghost small', type: 'button',
      onclick: async () => {
        const ok = await confirmDialog({
          title: 'Arrêter l’enregistrement ?', body: `Vyzo PC arrête d’enregistrer « ${name} ». Ce qui est déjà enregistré est gardé.`,
          confirm: 'Arrêter', cancel: 'Continuer', danger: true,
        })
        if (ok && await act(action, () => backend.setRecordingStatus(r, 'CANCELLED'), 'Arrêt demandé à Vyzo PC')) await refresh(['recordings'])
      },
    }, icon('stop', 15), 'Arrêter')
  } else {
    action = h('button', {
      class: 'icon-btn', type: 'button', title: 'Retirer de la liste', 'aria-label': `Retirer ${name} de la liste`,
      onclick: async () => {
        if (await act(action, () => backend.removeRecording(r), 'Retiré de la liste')) await refresh(['recordings'])
      },
    }, icon('trash', 16))
  }

  return h('li', { class: 'row-card rec' },
    h('div', { class: 'when', title: fullFmt.format(r.startMs) },
      h('span', { class: 'when-day' }, dayLabel(r.startMs)),
      h('span', { class: 'when-time' }, `${timeFmt.format(r.startMs)} – ${timeFmt.format(r.stopMs)}`)),
    h('div', { class: 'row-main' },
      h('div', { class: 'row-title' }, name),
      h('div', { class: 'row-sub' }, icon('tv', 14), r.channelName || 'Chaîne inconnue',
        r.error ? h('span', { class: 'rec-error' }, ` · ${r.error}`) : null)),
    h('span', { class: `pill ${statusClass}` }, status),
    action,
  )
}

// ------------------------------------------------------------------ Boîtes de dialogue

function openDialog(content: HTMLElement): HTMLDialogElement {
  const dialog = h('dialog', { class: 'dialog' }, content)
  // Retirée tout de suite, sans attendre l'événement « close » (différé, voire suspendu dans un onglet caché).
  const close = dialog.close.bind(dialog)
  dialog.close = (value?: string) => { close(value); dialog.remove() }
  dialog.addEventListener('close', () => dialog.remove())
  dialog.addEventListener('click', (e) => { if (e.target === dialog) dialog.close() })
  document.body.appendChild(dialog)
  dialog.showModal()
  return dialog
}

function confirmDialog(o: { title: string; body: string; confirm: string; cancel?: string; danger?: boolean }): Promise<boolean> {
  return new Promise((resolve) => {
    // Réponse donnée par le bouton lui-même : l'événement « close » est différé (voire suspendu dans un onglet caché).
    const answer = (value: boolean) => { resolve(value); dialog.close() }
    const yes = h('button', { class: `btn ${o.danger ? 'danger' : 'primary'}`, type: 'button', onclick: () => answer(true) }, o.confirm)
    const no = h('button', { class: 'btn ghost', type: 'button', onclick: () => answer(false) }, o.cancel ?? 'Annuler')
    const dialog = openDialog(h('div', { class: 'confirm' },
      h('h2', {}, o.title), h('p', {}, o.body),
      h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }), no, yes)))
    dialog.classList.add('small')
    dialog.addEventListener('close', () => resolve(false))
    no.focus()
  })
}

// ------------------------------------------------------------------ Démarrage

async function start() {
  try {
    const account = await backend.currentAccount()
    if (account) await enter(account)
    else renderLogin()
  } catch (e) {
    renderLogin(friendlyError(e))
  }
}

void start()
