import '@fontsource-variable/instrument-sans'
import '@fontsource/instrument-serif/400.css'
import '@fontsource-variable/jetbrains-mono'
import './style.css'
import {
  SupabaseBackend, UserError, friendlyError, type PasswordResetResult,
  type Account, type Backend, type BackupData, type CastItem, type ListsData, type LiveMessage, type PlaybackAction, type PlaylistDraft, type Table,
  type VaultStatus,
} from './backend'
import { DemoBackend } from './demo'
import { append, clear, h, icon } from './dom'
import {
  FAVORITES_LIST_ID, PROFILE_PALETTE, MAX_PROFILE_NAME, castItemType, castReadiness, categoryLocked, cleanName, hashParental,
  hashPin, isOnline, isValidPin, moveProfile, needsUpdate, nextColor, progressRatio, recordingGroup, sortProfiles, sortRecordings,
  statusLabel, subscriptionState, updateInProgress, updatePending,
  EMPTY_RULE, MAX_DEVICE_LABEL, MAX_MESSAGE, SENSITIVE_EVENTS, checkRule, deviceName, eventText, livePosition, minutesLabel, recordingId,
  ruleSummary, summarizeUsage, supportsRemote,
  MIN_PASSWORD, emailLinkError, parseEmailLink, type EmailLinkParams,
  type AccountEvent, type BackupCounts, type GuideResult, type PlaybackState, type ScreenTimeRule, type UsageDay,
  type CatalogEntry, type ContentKind, type Parental, type SealedPrefs, type Tone, type Device, type LatestVersion, type ListItem, type Playlist, type Profile, type ProfileActivity,
  type ProgressItem, type Recording, type RecordingGroup, type UserList,
} from './model'
import { THEMES, applyTheme, savedTheme, type Theme } from './theme'
import {
  act, avatar, barFill, confirmDialog, dateFmt, dayLabel, duration, emptyCard, fold, fullFmt, header, loading, longDayFmt, openDialog,
  plural, relative, timeFmt, toast,
} from './ui'

// ------------------------------------------------------------------ Cadre

/**
 * Refus d'être affiché dans le cadre d'une autre page : sinon un site piégé pourrait faire cliquer, à travers un
 * calque, « Supprimer » ou « Mettre à jour ». L'hébergement (GitHub Pages) ne permet pas d'envoyer frame-ancestors.
 */
if (window.top !== window.self) {
  document.body.textContent = 'Ouvre le site du compte Vyzo directement, pas dans une autre page.'
  throw new Error('Affichage dans un cadre refusé')
}

applyTheme(savedTheme())

// ------------------------------------------------------------------ État

const demoRequested = import.meta.env.DEV && new URLSearchParams(location.search).has('demo')
const backend: Backend = demoRequested ? new DemoBackend() : new SupabaseBackend()

type Route = 'accueil' | 'appareils' | 'abonnements' | 'enregistrements' | 'profils' | 'historique' | 'listes' | 'statistiques' | 'parental'
  | 'securite' | 'journal'

interface Page { id: Route; label: string; icon: string; group: string | null; lead: string | null }

const PAGES: Page[] = [
  { id: 'accueil', label: 'Accueil', icon: 'home', group: null, lead: null },
  { id: 'appareils', label: 'Appareils', icon: 'devices', group: 'Maison', lead: 'Tout ce qui est connecté à ton compte.' },
  { id: 'abonnements', label: 'Abonnements', icon: 'key', group: 'Maison', lead: 'Chiffrés de bout en bout, synchronisés sur tous tes appareils.' },
  { id: 'enregistrements', label: 'Enregistrements', icon: 'record', group: 'Maison', lead: 'Programmés depuis le guide. Vyzo PC enregistre.' },
  { id: 'profils', label: 'Profils', icon: 'users', group: 'Profils', lead: 'Les profils « Qui regarde ? » de la maison.' },
  { id: 'historique', label: 'Historique', icon: 'history', group: 'Profils', lead: 'Ce que chaque profil a commencé.' },
  { id: 'listes', label: 'Listes', icon: 'list', group: 'Profils', lead: 'Favoris et listes de chaque profil.' },
  { id: 'statistiques', label: 'Statistiques', icon: 'chart', group: 'Profils', lead: 'Le temps passé devant Vyzo, profil par profil.' },
  { id: 'parental', label: 'Contrôle parental', icon: 'lock', group: 'Profils', lead: 'Code, catégories verrouillées et temps d’écran pour toute la maison.' },
  { id: 'securite', label: 'Sécurité', icon: 'shield', group: 'Compte', lead: 'Mot de passe, sessions, sauvegarde et clé de chiffrement.' },
  { id: 'journal', label: 'Journal', icon: 'journal', group: 'Compte', lead: 'Ce qui s’est passé sur le compte ces six derniers mois.' },
]
const page = (id: Route) => PAGES.find((p) => p.id === id)!

/** Données chargées séparément : une erreur sur l'une (table absente du serveur…) n'empêche pas d'afficher les autres. */
type DataKey = 'profiles' | 'activity' | 'recordings' | 'devices' | 'playlists' | 'progress' | 'lists' | 'parental' | 'catalog'
  | 'screentime' | 'usage' | 'events'

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
  /** Profil choisi dans Accueil, Historique et Listes. */
  selected: string | null
  progress: { profileId: string; items: ProgressItem[] } | null
  lists: ({ profileId: string } & ListsData) | null
  /** undefined : pas encore lu ; null : pas de contrôle parental commun. */
  parental: Parental | null | undefined
  /** Abonnement choisi dans Contrôle parental, et ses catégories. */
  catalogFor: string | null
  catalog: { playlistId: string; entries: CatalogEntry[]; prefs: SealedPrefs } | null
  /** Ce que lit chaque appareil connecté (messages « playback_state »). */
  playback: Map<string, PlaybackState>
  /** Règles de temps d'écran par profil (chiffrées). */
  screentime: Map<string, ScreenTimeRule> | null
  usage: UsageDay[] | null
  events: AccountEvent[] | null
  errors: Partial<Record<DataKey, string>>
}

const emptyState = (): State => ({
  account: null, vault: null, remembered: false, profiles: null, activity: null, recordings: null, devices: null, latest: null,
  playlists: null, selected: null, progress: null, lists: null, parental: undefined, catalogFor: null, catalog: null,
  playback: new Map(), screentime: null, usage: null, events: null, errors: {},
})

let state: State = emptyState()
let stopWatching: (() => void) | null = null

const root = document.getElementById('app')!

function route(): Route {
  const id = location.hash.replace(/^#\/?/, '') as Route
  return PAGES.some((r) => r.id === id) ? id : 'accueil'
}

const go = (r: Route) => { location.hash = `#/${r}` }

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
  async parental() {
    if (state.vault === 'open') state.parental = await backend.parental(state.account!)
  },
  async screentime() {
    if (state.vault === 'open') state.screentime = await backend.screenTime(state.account!)
  },
  async usage() {
    if (state.vault === 'open') state.usage = await backend.usage(state.account!)
  },
  async events() {
    state.events = await backend.events()
  },
  async catalog() {
    const id = state.catalogFor ?? state.playlists?.[0]?.id ?? null
    if (state.vault !== 'open' || !id) return
    state.catalogFor = id
    const data = await backend.catalog(state.account!, id)
    if (state.catalogFor === id) state.catalog = { playlistId: id, ...data }
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
  const loaded = (k: DataKey): DataKey[] => (state[k as 'progress' | 'lists' | 'catalog' | 'screentime' | 'usage' | 'events'] ? [k] : [])
  // Le journal n'est pas diffusé en temps réel : il se relit quand une table qui l'alimente change.
  const events = loaded('events')
  switch (table) {
    case 'profiles': return ['profiles', 'activity', ...events]
    case 'watch_progress': return ['activity', ...loaded('progress')]
    case 'list_items': case 'user_lists': return ['activity', ...loaded('lists')]
    case 'recordings': return ['recordings', ...events]
    case 'devices': return ['devices', ...events]
    case 'sealed_items': return ['playlists', ...(state.parental !== undefined ? ['parental' as const] : []), ...loaded('catalog'),
      ...loaded('screentime'), ...loaded('usage'), ...events]
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

/** Historique et listes ne sont lus qu'à l'ouverture d'une section qui les montre. */
function ensureRouteData() {
  const r = route()
  if (r === 'parental' && state.vault === 'open') {
    if (state.parental === undefined) void refresh(['parental'])
    if (!state.catalog && state.playlists?.length) void refresh(['catalog'])
    if (!state.screentime) void refresh(['screentime'])
  }
  if (r === 'statistiques' && state.vault === 'open' && !state.usage) void refresh(['usage'])
  if (r === 'journal') void refresh(['events'])
  if (r === 'appareils' || r === 'accueil') askPlayback()
  if (!state.selected) return
  if ((r === 'historique' || r === 'accueil') && state.progress?.profileId !== state.selected) void refresh(['progress'])
  if (r === 'listes' && state.lists?.profileId !== state.selected) void refresh(['lists'])
}

async function enter(account: Account) {
  state = { ...emptyState(), account }
  stopWatching?.()
  const stopRemote = backend.watch(account, onRemoteChange)
  const stopLive = backend.onLive(onLive)
  stopWatching = () => { stopRemote(); stopLive() }
  renderShell()
  await refresh(['profiles', 'activity', 'recordings', 'devices', 'playlists'])
  ensureRouteData()
}

// ------------------------------------------------------------------ Réponses des appareils

/** Demandes en attente d'un accusé de l'appareil (message, vérification) : identifiant → suite. */
const awaiting = new Map<string, { device: Device; timer: number; onAck: (action: string) => void }>()

function expectAck(req: string, device: Device, onAck: (action: string) => void, silent: string) {
  const timer = window.setTimeout(() => {
    awaiting.delete(req)
    toast(silent, 'error')
  }, 8000)
  awaiting.set(req, { device, timer, onAck })
}

let playbackRender: number | undefined

function onLive(m: LiveMessage) {
  if (m.type === 'device_ack') {
    const w = awaiting.get(m.req)
    if (!w) return
    window.clearTimeout(w.timer)
    // Une vérification envoie deux accusés : « check » (reçue) puis « check_done ».
    if (m.action !== 'check') awaiting.delete(m.req)
    else w.timer = window.setTimeout(() => awaiting.delete(m.req), 60_000)
    w.onAck(m.action)
    return
  }
  if (m.type === 'playback_state' && state.account) {
    void backend.openPlayback(state.account, m.box).then((p) => {
      if (!p || !m.device) return
      state.playback.set(m.device, p)
      const r = route()
      if (r !== 'appareils' && r !== 'accueil') return
      // Plusieurs appareils répondent presque en même temps : un seul nouveau rendu.
      window.clearTimeout(playbackRender)
      playbackRender = window.setTimeout(renderView, 250)
    })
  }
}

let lastPlaybackAsk = 0

/** Demande aux appareils connectés ce qu'ils lisent (au plus toutes les 20 s). */
function askPlayback(force = false) {
  if (!state.account || state.vault !== 'open') return
  if (!force && Date.now() - lastPlaybackAsk < 20_000) return
  lastPlaybackAsk = Date.now()
  void backend.queryPlayback(state.account).catch(() => undefined)
}

// Lecture en cours : la position avance à l'écran, et les appareils sont réinterrogés de temps en temps.
setInterval(() => {
  const r = route()
  if (!state.account || (r !== 'appareils' && r !== 'accueil')) return
  askPlayback()
  if ([...state.playback.values()].some((p) => p.state === 'playing') && !document.querySelector('dialog[open]')) renderView()
}, 30_000)

// Une mise à jour demandée : on suit sa progression même si le temps réel n'arrive pas.
setInterval(() => {
  const r = route()
  if (state.account && (r === 'appareils' || r === 'accueil') && state.devices?.some((d) => updateInProgress(d))) void refresh(['devices'])
}, 6000)

// ------------------------------------------------------------------ Suppressions annulables

/**
 * Une suppression « simple » (un titre, un appareil, un abonnement…) disparaît tout de suite de l'écran mais n'est
 * envoyée au compte qu'après UNDO_MS : « Annuler » dans la notification la fait revenir sans rien avoir touché.
 */
const UNDO_MS = 6000
const hidden = new Set<string>()
const pendingDeletes = new Map<string, { timer: number; commit: () => Promise<void>; after: DataKey[] }>()

function softDelete(key: string, message: string, commit: () => Promise<void>, after: DataKey[]) {
  if (pendingDeletes.has(key)) return
  hidden.add(key)
  renderView()
  const timer = window.setTimeout(() => void commitDelete(key), UNDO_MS)
  pendingDeletes.set(key, { timer, commit, after })
  toast(message, 'ok', { label: 'Annuler', run: () => undoDelete(key) }, UNDO_MS)
}

async function commitDelete(key: string) {
  const p = pendingDeletes.get(key)
  if (!p) return
  pendingDeletes.delete(key)
  clearTimeout(p.timer)
  try {
    await p.commit()
    await refresh(p.after)
  } catch (e) {
    toast(friendlyError(e), 'error')
  } finally {
    hidden.delete(key)
    renderView()
  }
}

function undoDelete(key: string) {
  const p = pendingDeletes.get(key)
  if (!p) return
  clearTimeout(p.timer)
  pendingDeletes.delete(key)
  hidden.delete(key)
  renderView()
  toast('Rétabli')
}

/** Envoie tout de suite ce qui attend (déconnexion, verrouillage). */
const flushDeletes = () => Promise.all([...pendingDeletes.keys()].map(commitDelete))

window.addEventListener('beforeunload', (e) => {
  if (pendingDeletes.size > 0) e.preventDefault()
})

const isHidden = (key: string) => hidden.has(key)

// ------------------------------------------------------------------ Thème

function setTheme(t: Theme) {
  applyTheme(t, true)
  for (const b of document.querySelectorAll<HTMLElement>('[data-theme-choice]')) {
    const on = String(b.dataset.themeChoice === t)
    b.setAttribute(b.getAttribute('role') === 'radio' ? 'aria-checked' : 'aria-pressed', on)
  }
}

function themeSwitch() {
  const current = savedTheme()
  return h('div', { class: 'theme-switch', role: 'group', 'aria-label': 'Thème' },
    THEMES.map((t) => h('button', {
      type: 'button', 'data-theme-choice': t.id, 'aria-pressed': String(t.id === current), title: t.hint,
      onclick: () => setTheme(t.id),
    }, t.label)))
}

// ------------------------------------------------------------------ Connexion

/** Case « Rester déverrouillé sur cet ordinateur », décochée par défaut. */
function rememberBox(id: string) {
  const input = h('input', { id, type: 'checkbox' })
  const box = h('label', { class: 'check compact', for: id }, input, h('span', {},
    h('strong', {}, 'Rester déverrouillé sur cet ordinateur'),
    h('small', {}, 'Plus besoin du mot de passe à chaque visite. À éviter sur un ordinateur partagé : tes abonnements et ton historique y resteraient lisibles. « Verrouiller » ou la déconnexion l’oublient.')))
  return { input, box }
}

/** Cadre commun des pages sans compte ouvert (connexion, mot de passe oublié, liens reçus par e-mail). */
function authPage(pageTitle: string, card: HTMLElement) {
  clear(root)
  document.title = `${pageTitle} · Vyzo`
  card.classList.add('login-card')
  card.prepend(h('div', { class: 'login-mark' }, h('img', { src: 'icon.svg', alt: '', width: 28, height: 28 }), 'Vyzo',
    backend.demo ? h('span', { class: 'demo-tag' }, 'démo') : null))
  card.append(h('div', { class: 'login-theme' }, themeSwitch()))
  append(root, [h('main', { class: 'login' }, card)])
}

/** Bouton qui ressemble à un lien (action secondaire sous un formulaire). */
const linkButton = (label: string, onclick: () => void) => h('button', { class: 'link-btn', type: 'button', onclick }, label)

/** Envoi pendant lequel le bouton principal est occupé ; renvoie false et affiche l'erreur en cas d'échec. */
async function busy(button: HTMLButtonElement, error: HTMLElement, work: () => Promise<void>): Promise<boolean> {
  error.textContent = ''
  button.disabled = true
  button.setAttribute('aria-busy', 'true')
  try {
    await work()
    return true
  } catch (e) {
    error.textContent = friendlyError(e)
    return false
  } finally {
    button.disabled = false
    button.removeAttribute('aria-busy')
  }
}

const notConfirmed = (e: unknown) => {
  const err = e as { code?: string; message?: string }
  return err?.code === 'email_not_confirmed' || /Email not confirmed/i.test(err?.message ?? '')
}

function renderLogin(message?: string, prefill = '') {
  const email = h('input', { id: 'email', type: 'email', autocomplete: 'username', required: true, spellcheck: 'false', value: prefill })
  const password = h('input', { id: 'password', type: 'password', autocomplete: 'current-password', required: true })
  const error = h('p', { class: 'form-error', role: 'alert' }, message ?? '')
  const submit = h('button', { class: 'btn primary wide', type: 'submit' }, 'Se connecter')
  const stay = rememberBox('login-remember')
  const resend = h('div', { class: 'auth-extra', hidden: true })

  const form = h('form', {
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      error.textContent = ''
      resend.hidden = true
      submit.disabled = true
      submit.setAttribute('aria-busy', 'true')
      try {
        const account = await backend.signIn(email.value, password.value, stay.input.checked)
        password.value = ''
        await enter(account)
      } catch (e) {
        error.textContent = friendlyError(e)
        if (notConfirmed(e)) showResend(email.value)
        submit.disabled = false
        submit.removeAttribute('aria-busy')
        password.focus()
      }
    },
  },
    h('h1', {}, 'Connexion'),
    h('p', { class: 'lead' }, 'Ton compte Vyzo depuis l’ordinateur : appareils, abonnements, profils et listes.'),
    h('label', { for: 'email' }, 'Adresse e-mail'), email,
    h('div', { class: 'label-row' },
      h('label', { for: 'password' }, 'Mot de passe'),
      linkButton('Mot de passe oublié ?', () => renderForgot(email.value))),
    password,
    stay.box,
    error,
    resend,
    submit,
    h('p', { class: 'fine' },
      'Le compte de tes appareils. Pas encore de compte ? Crée-le dans l’appli : Réglages, Compte Vyzo. Un e-mail te demandera de confirmer l’adresse.'),
    import.meta.env.DEV && !backend.demo
      ? h('p', { class: 'fine' }, h('a', { href: '?demo' }, 'Ouvrir la démo (données fictives)'))
      : null,
  )

  function showResend(address: string) {
    const button = h('button', { class: 'btn outline small', type: 'button' }, 'Renvoyer l’e-mail de confirmation')
    const note = h('p', { class: 'form-note', role: 'status' })
    button.addEventListener('click', async () => {
      if (await busy(button, error, () => backend.resendConfirmation(address)))
        note.textContent = `E-mail renvoyé à ${address.trim()}. Pense à regarder dans les indésirables.`
    })
    clear(resend)
    append(resend, [button, note])
    resend.hidden = false
  }

  authPage('Connexion', form)
  if (prefill) password.focus()
  else email.focus()
}

/** Mot de passe oublié : demande du lien. [message] : lien précédent refusé. */
function renderForgot(prefill = '', message?: string) {
  const email = h('input', { id: 'forgot-email', type: 'email', autocomplete: 'username', required: true, spellcheck: 'false', value: prefill.trim() })
  const error = h('p', { class: 'form-error', role: 'alert' })
  const submit = h('button', { class: 'btn primary wide', type: 'submit' }, 'Recevoir le lien')
  const form: HTMLFormElement = h('form', {
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      const address = email.value.trim()
      if (await busy(submit, error, () => backend.requestPasswordReset(address))) renderMailSent(address)
    },
  },
    h('h1', {}, 'Mot de passe oublié'),
    message ? h('p', { class: 'form-error', role: 'alert' }, message) : null,
    h('p', { class: 'lead' }, 'Saisis l’adresse du compte : tu recevras un lien pour choisir un nouveau mot de passe.'),
    h('label', { for: 'forgot-email' }, 'Adresse e-mail'), email,
    error,
    submit,
    h('p', { class: 'fine' }, linkButton('Retour à la connexion', () => renderLogin(undefined, email.value))),
  )
  authPage('Mot de passe oublié', form)
  email.focus()
}

function renderMailSent(address: string) {
  const back = h('button', { class: 'btn outline wide', type: 'button', onclick: () => renderLogin(undefined, address) }, 'Retour à la connexion')
  authPage('E-mail envoyé', h('div', {},
    h('div', { class: 'auth-badge' }, icon('mail', 22)),
    h('h1', {}, 'Regarde tes e-mails'),
    h('p', { class: 'lead' }, `Si un compte Vyzo existe pour ${address}, un e-mail vient de partir. Ouvre le lien qu’il contient, sur cet ordinateur ou sur ton téléphone : il est valable une heure et ne sert qu’une fois.`),
    h('p', { class: 'fine' }, 'Rien après quelques minutes ? Regarde dans les indésirables, puis redemande un lien.'),
    back,
    h('p', { class: 'fine' }, linkButton('Redemander un lien', () => renderForgot(address))),
  ))
  back.focus()
}

/** Arrivée depuis un lien d'e-mail : on le vérifie, puis la suite dépend de son type. */
async function openEmailLink(link: EmailLinkParams) {
  authPage('Vérification', h('div', {}, h('h1', {}, 'Vérification du lien…'), h('p', { class: 'lead' }, 'Un instant.')))
  if (link.mode === 'error') {
    renderForgot('', emailLinkError(link.code))
    return
  }
  try {
    const { type, account } = await backend.verifyEmailLink(link)
    if (type === 'recovery') renderNewPassword(account)
    else if (type === 'signup') {
      // Le site s'ouvre ensuite avec le mot de passe : c'est lui qui déverrouille les données chiffrées.
      await backend.signOut().catch(() => undefined)
      renderConfirmed(account.email)
    } else {
      // Pas d'ouverture automatique : le compte s'ouvre avec son mot de passe, comme après une inscription.
      await backend.signOut().catch(() => undefined)
      renderEmailChanged(account.email)
    }
  } catch (e) {
    // verifyEmailLink ne lève que des messages fixes (UserError) ou des erreurs réseau.
    renderForgot('', friendlyError(e))
  }
}

function renderConfirmed(address: string) {
  const go = h('button', { class: 'btn primary wide', type: 'button', onclick: () => renderLogin(undefined, address) }, 'Se connecter sur ce site')
  authPage('Adresse confirmée', h('div', {},
    h('div', { class: 'auth-badge ok' }, icon('check', 22)),
    h('h1', {}, 'Adresse confirmée'),
    h('p', { class: 'lead' }, `Le compte ${address} est prêt. Reviens dans Vyzo, sur la TV, le téléphone ou le PC, et connecte-toi avec cette adresse et ton mot de passe.`),
    go,
    h('p', { class: 'fine' }, 'Tes playlists et réglages sont chiffrés sur tes appareils : le serveur ne peut pas les lire.'),
  ))
  go.focus()
}

function renderEmailChanged(address: string) {
  const go = h('button', { class: 'btn primary wide', type: 'button', onclick: () => renderLogin(undefined, address) }, 'Se connecter')
  authPage('Adresse modifiée', h('div', {},
    h('div', { class: 'auth-badge ok' }, icon('check', 22)),
    h('h1', {}, 'Adresse confirmée'),
    h('p', { class: 'lead' }, `Le compte utilise maintenant ${address}. Si un e-mail est aussi arrivé sur l’autre adresse, ouvre son lien pour terminer.`),
    go,
  ))
  go.focus()
}

function renderNewPassword(account: Account) {
  const pass1 = h('input', { id: 'new-password', type: 'password', autocomplete: 'new-password', required: true, minlength: MIN_PASSWORD })
  const pass2 = h('input', { id: 'new-password-2', type: 'password', autocomplete: 'new-password', required: true })
  const error = h('p', { class: 'form-error', role: 'alert' })
  const submit = h('button', { class: 'btn primary wide', type: 'submit' }, 'Enregistrer le mot de passe')
  const form = h('form', {
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      error.textContent = ''
      if (pass1.value.length < MIN_PASSWORD) {
        error.textContent = `Au moins ${MIN_PASSWORD} caractères`
        return
      }
      if (pass1.value !== pass2.value) {
        error.textContent = 'Les deux mots de passe ne sont pas identiques'
        pass2.focus()
        return
      }
      let result: PasswordResetResult = { vault: 'none', othersSignedOut: true }
      const next = pass1.value
      if (await busy(submit, error, async () => { result = await backend.resetPassword(account, next) })) {
        pass1.value = ''
        pass2.value = ''
        renderPasswordReset(account, result)
      }
    },
  },
    h('h1', {}, 'Nouveau mot de passe'),
    h('p', { class: 'lead' }, `Pour le compte ${account.email}.`),
    h('label', { for: 'new-password' }, 'Nouveau mot de passe'), pass1,
    h('label', { for: 'new-password-2' }, 'Encore une fois'), pass2,
    error,
    submit,
    h('p', { class: 'fine' }, 'Tous les appareils et navigateurs connectés au compte seront déconnectés : chacun te redemandera le nouveau mot de passe.'),
  )
  authPage('Nouveau mot de passe', form)
  pass1.focus()
}

function renderPasswordReset(account: Account, { vault, othersSignedOut }: PasswordResetResult) {
  const go = h('button', { class: 'btn primary wide', type: 'button', onclick: () => void enter(account) }, 'Ouvrir mon compte')
  const vaultText = vault === 'kept'
    ? 'Tes abonnements chiffrés restent lisibles : ce navigateur avait gardé la clé du compte, elle est maintenant protégée par le nouveau mot de passe.'
    : vault === 'pending'
      ? 'Tes abonnements et réglages chiffrés étaient protégés par l’ancien mot de passe. Reconnecte-toi avec le nouveau sur une TV, un téléphone ou un PC où Vyzo était déjà connecté : l’appareil les protégera avec le nouveau, et le site pourra de nouveau les ouvrir.'
      : null
  authPage('Mot de passe changé', h('div', {},
    h('div', { class: 'auth-badge ok' }, icon('check', 22)),
    h('h1', {}, 'Mot de passe changé'),
    othersSignedOut
      ? h('p', { class: 'lead' }, 'Les autres appareils et navigateurs ont été déconnectés. Chacun te redemandera le nouveau mot de passe.')
      : h('p', { class: 'auth-warn' }, 'Les autres appareils et navigateurs n’ont pas pu être déconnectés. Une fois dans le compte, ouvre Sécurité et choisis « Déconnecter les autres appareils ».'),
    vaultText ? h('p', { class: vault === 'kept' ? 'form-note' : 'auth-warn' }, vaultText) : null,
    go,
  ))
  go.focus()
}

async function signOut() {
  await flushDeletes()
  stopWatching?.()
  stopWatching = null
  await backend.signOut()
  state = emptyState()
  renderLogin()
}

// ------------------------------------------------------------------ Coquille

let viewEl: HTMLElement | null = null
let navEl: HTMLElement | null = null
let whoEl: HTMLElement | null = null
const isMac = /Mac|iPhone|iPad/.test(navigator.platform)

function renderShell() {
  clear(root)
  navEl = h('nav', { class: 'nav', 'aria-label': 'Sections' })
  whoEl = h('div', { class: 'who' })
  viewEl = h('main', { class: 'view', id: 'view', tabindex: '-1' })
  append(root, [
    h('div', { class: 'shell' },
      h('aside', { class: 'side' },
        h('a', { class: 'logo', href: '#/accueil' },
          h('img', { src: 'icon.svg', alt: '', width: 24, height: 24 }),
          h('span', { class: 'logo-name' }, 'Vyzo'), h('span', { class: 'logo-sub' }, 'Compte'),
          backend.demo ? h('span', { class: 'demo-tag' }, 'démo') : null),
        h('button', { class: 'search-trigger', type: 'button', 'aria-label': 'Rechercher', onclick: () => openPalette() },
          icon('search', 15), h('span', {}, 'Rechercher…'), h('kbd', {}, isMac ? '⌘K' : 'Ctrl K')),
        navEl,
        h('div', { class: 'side-foot' },
          whoEl,
          themeSwitch(),
          h('button', { class: 'btn ghost small', type: 'button', onclick: () => void signOut() }, icon('logout', 15), 'Déconnexion')),
      ),
      viewEl,
    ),
  ])
  renderView()
}

function renderNav(current: Route) {
  if (!navEl) return
  const outdated = (state.devices ?? []).filter((d) => needsUpdate(d, state.latest) && !updateInProgress(d)).length
  clear(navEl)
  let group: string | null = null
  for (const p of PAGES) {
    if (p.group && p.group !== group) navEl.appendChild(h('div', { class: 'nav-group' }, p.group))
    group = p.group
    const badge = p.id === 'appareils' && outdated > 0
      ? h('span', { class: 'nav-badge', title: `${plural(outdated, 'appareil', 'appareils')} à mettre à jour` }, String(outdated))
      : null
    navEl.appendChild(h('a', { href: `#/${p.id}`, class: 'nav-item', 'aria-current': p.id === current ? 'page' : null },
      icon(p.icon, 16), h('span', {}, p.label), badge))
  }
}

function renderWho() {
  if (!whoEl || !state.account) return
  const email = state.account.email
  const vault = state.vault === 'open' ? (state.remembered ? 'Déverrouillé, gardé ici' : 'Déverrouillé')
    : state.vault === 'locked' ? 'Verrouillé' : state.vault === 'missing' ? 'Rien de chiffré' : '…'
  clear(whoEl)
  append(whoEl, [
    h('span', { class: 'who-mark', 'aria-hidden': 'true' }, (Array.from(email)[0] ?? '?').toUpperCase()),
    h('div', { class: 'who-text' },
      h('div', { class: 'who-mail', title: email }, email),
      h('div', { class: 'who-state' }, icon(state.vault === 'open' ? 'unlock' : 'lock', 11), vault)),
  ])
}

const VIEWS: Record<Route, () => HTMLElement> = {
  statistiques: statsView,
  journal: journalView,
  accueil: homeView,
  appareils: devicesView,
  abonnements: playlistsView,
  enregistrements: recordingsView,
  profils: profilesView,
  historique: historyView,
  listes: listsView,
  parental: parentalView,
  securite: securityView,
}

function renderView() {
  if (!viewEl || !state.account) return
  const r = route()
  renderNav(r)
  renderWho()
  document.title = r === 'accueil' ? 'Vyzo · Compte' : `${page(r).label} · Vyzo`
  // Garde la position de défilement et le focus : le temps réel redessine souvent la vue.
  const scroll = window.scrollY
  const focusId = document.activeElement instanceof HTMLElement && viewEl.contains(document.activeElement) ? document.activeElement.id : ''
  clear(viewEl)
  append(viewEl, [VIEWS[r]()])
  window.scrollTo({ top: scroll })
  if (focusId) document.getElementById(focusId)?.focus({ preventScroll: true })
}

window.addEventListener('hashchange', () => {
  window.scrollTo({ top: 0 })
  renderView()
  ensureRouteData()
  viewEl?.focus({ preventScroll: true })
})

function pageHeader(r: Route, ...actions: (Node | null)[]) {
  const p = page(r)
  return header(r === 'abonnements' ? 'Abonnements IPTV' : p.label, p.lead, ...actions)
}

function errorBanner(key: DataKey) {
  const message = state.errors[key]
  if (!message) return null
  return h('div', { class: 'banner error', role: 'alert' }, icon('info'), h('span', {}, message),
    h('button', { class: 'btn ghost small', type: 'button', onclick: () => void refresh([key]) }, 'Réessayer'))
}

function sectionTitle(text: string, iconName: string | null, count: number | null, link?: { href: string; label: string }) {
  return h('h2', { class: 'section-title' }, iconName ? icon(iconName, 15) : null, text,
    count !== null ? h('span', { class: 'count' }, String(count)) : null,
    link ? h('a', { href: link.href }, link.label, ' →') : null)
}

function selectProfile(id: string) {
  if (state.selected === id) return
  state.selected = id
  state.progress = null
  state.lists = null
  renderView()
  ensureRouteData()
}

/** Choix du profil dans Accueil, Historique et Listes. */
function profilePicker(count: (p: Profile) => number | null) {
  const profiles = state.profiles ?? []
  if (profiles.length < 2) return null
  return h('div', { class: 'picker', role: 'tablist', 'aria-label': 'Profil' },
    profiles.map((p) => {
      const n = count(p)
      return h('button', {
        type: 'button', class: 'chip', role: 'tab', 'aria-selected': String(p.id === state.selected),
        onclick: () => selectProfile(p.id),
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
  return h('span', { class: `kind-badge k-${kind.toLowerCase()}`, title: KIND_LABEL[kind] }, icon(KIND_ICON[kind] ?? 'film', 16))
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
      'Certains titres ne sont pas encore connus du compte. Ils arrivent quand un appareil à jour se synchronise.'))
  }
  return null
}

// ------------------------------------------------------------------ Coffre chiffré

/** Formulaire du mot de passe du compte, qui ouvre la clé des données chiffrées. */
function unlockForm(onDone: () => void) {
  const password = h('input', { id: 'vault-pass', type: 'password', autocomplete: 'current-password', required: true })
  const error = h('p', { class: 'form-error', role: 'alert' })
  const submit = h('button', { class: 'btn primary', type: 'submit' }, icon('unlock', 15), 'Déverrouiller')
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
      ensureRouteData()
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
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('lock', 20)), h('h2', {}, 'Déverrouiller')),
    form))
  focus()
}

async function lockNow() {
  await flushDeletes()
  const wasRemembered = state.remembered
  await backend.lockVault()
  state.vault = 'locked'
  state.remembered = false
  state.playlists = null
  state.parental = undefined
  state.catalog = null
  if (state.progress) state.progress.items = state.progress.items.map((x) => ({ ...x, title: '', subtitle: '' }))
  if (state.lists) state.lists.items = state.lists.items.map((x) => ({ ...x, title: '', subtitle: '' }))
  renderView()
  toast(wasRemembered ? 'Verrouillé : la clé n’est plus gardée sur cet ordinateur' : 'Données chiffrées verrouillées')
}

function vaultGate(title = 'Abonnements chiffrés',
  text = 'Tes identifiants IPTV sont chiffrés avec le mot de passe du compte : le serveur Vyzo ne peut pas les lire. Saisis-le pour les afficher et les modifier.') {
  if (state.vault === 'missing') {
    return emptyCard('key', 'Rien de chiffré sur ce compte pour l’instant',
      'Connecte Vyzo au compte sur un de tes appareils : il crée la clé du compte et y range tes abonnements. Ils apparaîtront ici ensuite.')
  }
  const { form } = unlockForm(() => {})
  return h('div', { class: 'gate' },
    h('span', { class: 'kind-badge big' }, icon('lock', 20)),
    h('div', { class: 'gate-main' },
      h('h2', {}, title),
      h('p', {}, text),
      form))
}

// ------------------------------------------------------------------ Accueil

function homeView() {
  const now = new Date()
  const hour = now.getHours()
  const box = h('section', {},
    h('p', { class: 'greeting' }, longDayFmt.format(now)),
    header(hour >= 18 || hour < 5 ? 'Bonsoir' : 'Bonjour', null))

  // Chiffres clés
  const devices = (state.devices ?? []).filter((d) => !isHidden(`d:${d.id}`))
  const online = devices.filter((d) => isOnline(d)).length
  const outdated = devices.filter((d) => needsUpdate(d, state.latest) && !updateInProgress(d)).length
  const playlists = (state.playlists ?? []).filter((p) => !isHidden(`pl:${p.id}`))
  const upcoming = sortRecordings((state.recordings ?? []).filter((r) => recordingGroup(r) === 'upcoming' && !isHidden(`r:${r.id}`)), 'upcoming')
  const next = upcoming[0]
  const kids = (state.profiles ?? []).filter((p) => p.isKid).length
  // L'abonnement qui demande le plus d'attention (expiré, puis le plus proche de sa fin).
  const worst = playlists
    .map((p) => ({ p, st: subscriptionState(p.status) }))
    .filter((x): x is { p: Playlist; st: NonNullable<ReturnType<typeof subscriptionState>> } => !!x.st && x.st.tone !== 'ok')
    .sort((a, b) => (a.st.daysLeft ?? -1) - (b.st.daysLeft ?? -1))[0]

  const kpi = (href: string, iconName: string, label: string, value: Node | string, note: string, warn = false) =>
    h('a', { class: 'kpi', href },
      h('span', { class: 'kpi-label' }, icon(iconName, 14), label),
      h('span', { class: 'kpi-value' }, value),
      h('span', { class: `kpi-note ${warn ? 'warn' : ''}` }, note))

  append(box, [h('div', { class: 'kpis' },
    kpi('#/appareils', 'devices', 'Appareils en ligne',
      state.devices ? h('span', {}, String(online), h('small', {}, `/ ${devices.length}`)) : '—',
      outdated > 0 ? `${plural(outdated, 'mise', 'mises')} à jour à faire` : devices.length ? 'Tous à jour' : 'Aucun appareil',
      outdated > 0),
    kpi('#/abonnements', 'key', 'Abonnements',
      state.vault === 'open' && state.playlists ? String(playlists.length) : '—',
      state.vault === 'locked' ? 'Verrouillés' : state.vault === 'missing' ? 'Aucun pour l’instant'
        : worst ? `${worst.p.name} · ${worst.st.label.toLowerCase()}` : 'Chiffrés de bout en bout',
      !!worst),
    kpi('#/profils', 'users', 'Profils',
      state.profiles ? String(state.profiles.length) : '—',
      kids ? plural(kids, 'profil enfant', 'profils enfant') : 'Aucun profil enfant'),
    kpi('#/enregistrements', 'record', 'Enregistrements',
      state.recordings ? String(upcoming.length) : '—',
      next ? `${dayLabel(next.startMs)} ${timeFmt.format(next.startMs)} · ${next.title || next.channelName}` : 'Aucun prévu'),
  )])

  // Colonne principale : reprendre
  const profile = state.profiles?.find((p) => p.id === state.selected) ?? null
  const main = h('div', {},
    sectionTitle('Reprendre', 'play', null, { href: '#/historique', label: 'Historique' }),
    profilePicker(() => null))
  const progress = state.progress?.profileId === state.selected ? state.progress.items : null
  if (!state.profiles || (profile && !progress && !state.errors.progress)) append(main, [loading(2)])
  else if (!profile) append(main, [h('p', { class: 'empty' }, 'Aucun profil sur ce compte.')])
  else if (progress) {
    const resume = progress
      .filter((x) => x.kind !== 'LIVE' && x.durationMs > 0 && progressRatio(x) < 0.95 && !isHidden(`p:${x.profileId}:${x.contentKey}`))
      .slice(0, 6)
    if (resume.length === 0) {
      append(main, [h('p', { class: 'empty' }, `Rien à reprendre pour ${profile.name}.`)])
    } else {
      append(main, [titlesNotice(resume), h('ul', { class: 'resume' }, resume.map((x) => resumeCard(x)))])
    }
  }

  // Colonne de droite : appareils et prochains enregistrements
  const aside = h('div', {},
    sectionTitle('Appareils', 'devices', state.devices ? devices.length : null, { href: '#/appareils', label: 'Gérer' }))
  if (!state.devices) append(aside, [state.errors.devices ? errorBanner('devices') : loading(2)])
  else if (devices.length === 0) append(aside, [h('p', { class: 'empty small' }, 'Aucun appareil pour l’instant.')])
  else {
    const t = Date.now()
    const sorted = [...devices].sort((a, b) => Number(isOnline(b, t)) - Number(isOnline(a, t)) || b.lastSeenAt - a.lastSeenAt)
    append(aside, [h('ul', { class: 'rows' }, sorted.slice(0, 5).map((d) => {
      const on = isOnline(d, t)
      return h('li', { class: 'row-card compact' },
        h('span', { class: 'kind-badge' }, icon(DEVICE_ICON[d.kind] ?? 'devices', 16),
          h('span', { class: `presence ${on ? 'on' : ''}` })),
        h('div', { class: 'row-main' },
          h('div', { class: 'row-title' }, deviceName(d)),
          h('div', { class: 'row-sub' }, on ? playingLine(d) ?? h('span', { class: 'online' }, 'En ligne') : `Vu ${relative(d.lastSeenAt)}`)),
        needsUpdate(d, state.latest) ? h('span', { class: 'pill planned' }, 'Mise à jour') : null)
    }))])
  }
  append(aside, [sectionTitle('À enregistrer', 'calendar', null, { href: '#/enregistrements', label: 'Tout voir' })])
  if (!state.recordings) append(aside, [state.errors.recordings ? null : loading(1)])
  else if (upcoming.length === 0) append(aside, [h('p', { class: 'empty small' }, 'Aucun enregistrement prévu.')])
  else {
    append(aside, [h('ul', { class: 'rows' }, upcoming.slice(0, 3).map((r) => h('li', { class: 'row-card compact' },
      h('div', { class: 'row-main' },
        h('div', { class: 'row-title' }, r.title || 'Programme sans titre'),
        h('div', { class: 'row-sub' }, `${dayLabel(r.startMs)} · ${timeFmt.format(r.startMs)} · ${r.channelName || 'Chaîne inconnue'}`)),
      r.status === 'RECORDING' ? h('span', { class: 'pill live' }, 'En cours') : null)))])
  }

  append(box, [h('div', { class: 'dash-grid' }, main, aside)])
  return box
}

function resumeCard(x: ProgressItem) {
  const t = contentTitle(x)
  const ratio = progressRatio(x)
  return h('li', { class: 'resume-card' },
    h('div', { class: 'resume-top' }, kindBadge(x.kind),
      castButton({ contentKey: x.contentKey, itemType: castItemType(x.kind, true), title: x.title, positionMs: x.positionMs }, t.text)),
    h('div', {},
      h('div', { class: `resume-title ${t.known ? '' : 'unknown'}` }, t.text),
      h('div', { class: 'resume-sub' }, x.subtitle || relative(x.updatedAt))),
    h('div', { class: 'resume-foot' },
      h('span', { class: 'bar' }, barFill(ratio)),
      h('span', { class: 'bar-label' }, `reste ${duration(x.durationMs - x.positionMs)}`)))
}

// ------------------------------------------------------------------ Recherche (Ctrl+K)

interface Command { group: string; label: string; hint?: string; icon: string; terms?: string; run: () => void }

function commands(): Command[] {
  const list: Command[] = PAGES.map((p) => ({ group: 'Pages', label: p.label, icon: p.icon, terms: p.lead ?? '', run: () => go(p.id) }))
  const open = state.vault === 'open'
  list.push(
    ...(open ? [{ group: 'Actions', label: 'Ajouter un abonnement', icon: 'plus', terms: 'iptv xtream m3u stalker', run: () => { go('abonnements'); editPlaylist(null) } }] : []),
    { group: 'Actions', label: 'Nouveau profil', icon: 'plus', run: () => { go('profils'); editProfile(null) } },
    ...(open
      ? [{ group: 'Actions', label: 'Verrouiller les données chiffrées', icon: 'lock', run: () => void lockNow() }]
      : state.vault === 'locked' ? [{ group: 'Actions', label: 'Déverrouiller les données chiffrées', icon: 'unlock', run: () => unlockDialog() }] : []),
    { group: 'Actions', label: 'Changer le mot de passe', icon: 'key', run: () => { go('securite'); setTimeout(() => document.getElementById('pw-current')?.focus(), 50) } },
    ...THEMES.map((t) => ({ group: 'Actions', label: `Thème ${t.label}`, hint: t.hint, icon: 'sparkle', terms: 'apparence couleur', run: () => setTheme(t.id) })),
    { group: 'Actions', label: 'Se déconnecter', icon: 'logout', run: () => void signOut() },
  )
  list.push(
    { group: 'Actions', label: 'Programmer un enregistrement', icon: 'record', terms: 'guide tv programme enregistrer', run: () => { go('enregistrements'); guideDialog() } },
    ...(open ? [{ group: 'Actions', label: 'Télécharger une sauvegarde du compte', icon: 'download', terms: 'export backup', run: () => { go('securite'); backupDialog() } }] : []),
    ...(installPrompt ? [{ group: 'Actions', label: 'Installer le site', icon: 'install', terms: 'application bureau pwa', run: () => void install() }] : []),
  )
  for (const d of state.devices ?? []) {
    list.push({ group: 'Appareils', label: deviceName(d), hint: isOnline(d) ? 'En ligne' : `Vu ${relative(d.lastSeenAt)}`,
      icon: DEVICE_ICON[d.kind] ?? 'devices', terms: `${d.model} ${d.system} ${d.appVersion}`, run: () => go('appareils') })
  }
  for (const p of state.playlists ?? []) {
    list.push({ group: 'Abonnements', label: p.name, hint: TYPE_LABEL[p.type], icon: 'key', terms: hostOf(p.url),
      run: () => { go('abonnements'); editPlaylist(p) } })
  }
  for (const p of state.profiles ?? []) {
    list.push({ group: 'Profils', label: p.name, hint: p.isKid ? 'Enfant' : undefined, icon: p.isKid ? 'child' : 'users',
      run: () => { go('profils'); editProfile(p) } })
  }
  const owner = state.profiles?.find((p) => p.id === state.selected)?.name ?? ''
  const seen = new Set<string>()
  for (const x of [...(state.progress?.items ?? []), ...(state.lists?.items ?? [])]) {
    if (!x.title || seen.has(x.contentKey)) continue
    seen.add(x.contentKey)
    const inHistory = 'positionMs' in x
    list.push({ group: 'Titres', label: x.title, hint: `${KIND_LABEL[x.kind]}${owner ? ` · ${owner}` : ''}`, icon: KIND_ICON[x.kind],
      terms: x.subtitle, run: () => go(inHistory ? 'historique' : 'listes') })
  }
  return list
}

let paletteOpen = false

function openPalette() {
  if (!state.account || paletteOpen) return
  paletteOpen = true
  const all = commands()
  const input = h('input', {
    type: 'text', placeholder: 'Rechercher une page, un appareil, un titre…', 'aria-label': 'Rechercher',
    role: 'combobox', 'aria-expanded': 'true', 'aria-controls': 'palette-list', autocomplete: 'off', spellcheck: 'false',
  })
  const listEl = h('ul', { class: 'palette-list', id: 'palette-list', role: 'listbox' })
  let shown: Command[] = []
  let index = 0

  const select = (i: number) => {
    index = i
    for (const li of listEl.querySelectorAll<HTMLElement>('.palette-item')) {
      const on = Number(li.dataset.i) === i
      li.setAttribute('aria-selected', String(on))
      if (on) {
        input.setAttribute('aria-activedescendant', li.id)
        li.scrollIntoView({ block: 'nearest' })
      }
    }
  }
  const run = (c: Command | undefined) => {
    if (!c) return
    dialog.close()
    c.run()
  }
  const render = () => {
    const tokens = fold(input.value).split(/\s+/).filter(Boolean)
    shown = tokens.length === 0
      ? all.filter((c) => c.group === 'Pages' || c.group === 'Actions')
      : all.filter((c) => {
        const hay = fold(`${c.label} ${c.hint ?? ''} ${c.terms ?? ''} ${c.group}`)
        return tokens.every((t) => hay.includes(t))
      }).slice(0, 50)
    clear(listEl)
    if (shown.length === 0) {
      listEl.appendChild(h('li', { class: 'palette-empty', role: 'presentation' }, 'Aucun résultat'))
      input.removeAttribute('aria-activedescendant')
      return
    }
    let group = ''
    shown.forEach((c, i) => {
      if (c.group !== group) listEl.appendChild(h('li', { class: 'palette-group', role: 'presentation' }, c.group))
      group = c.group
      listEl.appendChild(h('li', {
        class: 'palette-item', role: 'option', id: `palette-${i}`, 'data-i': i, 'aria-selected': 'false',
        onmousemove: () => { if (index !== i) select(i) },
        onclick: () => run(c),
      }, icon(c.icon, 16), h('span', { class: 'ellipsis' }, c.label), c.hint ? h('span', { class: 'hint' }, c.hint) : null))
    })
    select(0)
  }
  input.addEventListener('input', render)
  input.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); if (shown.length) select((index + 1) % shown.length) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); if (shown.length) select((index - 1 + shown.length) % shown.length) }
    else if (e.key === 'Enter') { e.preventDefault(); run(shown[index]) }
  })
  const dialog = openDialog(h('div', {},
    h('div', { class: 'palette-input' }, icon('search', 17), input),
    listEl,
    h('div', { class: 'palette-foot' },
      h('span', {}, h('kbd', {}, '↑'), h('kbd', {}, '↓'), 'naviguer'),
      h('span', {}, h('kbd', {}, '↵'), 'ouvrir'),
      h('span', {}, h('kbd', {}, 'Échap'), 'fermer'))), 'palette')
  dialog.addEventListener('close', () => { paletteOpen = false })
  const close = dialog.close.bind(dialog)
  dialog.close = (v?: string) => { paletteOpen = false; close(v) }
  render()
  input.focus()
}

document.addEventListener('keydown', (e) => {
  if (!state.account) return
  const typing = e.target instanceof HTMLElement && (e.target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName))
  if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    openPalette()
  } else if (e.key === '/' && !typing && !document.querySelector('dialog[open]')) {
    e.preventDefault()
    openPalette()
  }
})

// ------------------------------------------------------------------ Profils

function profilesView() {
  const profiles = state.profiles
  const add = h('button', { class: 'btn primary', type: 'button', onclick: () => editProfile(null) },
    icon('plus', 16), 'Ajouter un profil')
  const box = h('section', {}, pageHeader('profils', add), errorBanner('profiles'))
  if (!profiles) return append(box, [state.errors.profiles ? null : loading()]), box

  const grid = h('ul', { class: 'profile-grid' })
  profiles.forEach((p, i) => {
    const up = h('button', {
      class: 'icon-btn', type: 'button', title: 'Monter', 'aria-label': `Monter ${p.name}`, disabled: i === 0,
      onclick: (e: Event) => void move(e.currentTarget as HTMLButtonElement, profiles, p.id, -1),
    }, icon('up', 15))
    const down = h('button', {
      class: 'icon-btn', type: 'button', title: 'Descendre', 'aria-label': `Descendre ${p.name}`, disabled: i === profiles.length - 1,
      onclick: (e: Event) => void move(e.currentTarget as HTMLButtonElement, profiles, p.id, 1),
    }, icon('down', 15))
    const a = state.activity?.find((x) => x.profileId === p.id)
    grid.appendChild(h('li', { class: 'profile-card' },
      avatar(p),
      h('div', { class: 'profile-name' }, p.name),
      h('div', { class: 'tags' },
        p.isKid ? h('span', { class: 'tag' }, icon('child', 12), 'Enfant') : null,
        p.pinHash ? h('span', { class: 'tag' }, icon('lock', 12), 'Code') : null),
      a ? h('div', { class: 'profile-stats' },
        `${a.inProgress} en cours · ${a.inLists} dans les listes`) : null,
      h('div', { class: 'card-actions' },
        h('button', { class: 'btn ghost small', type: 'button', onclick: () => editProfile(p) }, icon('edit', 14), 'Modifier'),
        h('span', { class: 'order' }, up, down)),
    ))
  })
  append(box, [grid])
  if (profiles.length === 0) {
    append(box, [h('p', { class: 'empty' }, 'Aucun profil sur ce compte. Le premier appareil connecté crée « Moi » ; tu peux aussi en ajouter un ici.')])
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
      h('p', { class: 'pin-state' }, icon('lock', 14), hasPin
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
        title: `Supprimer « ${existing!.name} » ?`,
        body: 'Le profil disparaît de tous tes appareils, avec sa progression, son historique et ses listes. Impossible de revenir en arrière.',
        confirm: 'Supprimer le profil', danger: true,
      })
      if (!ok) return
      if (await act(del, () => backend.deleteProfile(existing!), `Profil « ${existing!.name} » supprimé`)) {
        dialog.close()
        await refresh(['profiles', 'activity'])
      }
    },
  }, icon('trash', 15), 'Supprimer')

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
      if (await act(save, () => backend.saveProfiles([final]), isNew ? `Profil « ${final.name} » créé` : 'Profil enregistré')) {
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
  const devices = state.devices?.filter((d) => !isHidden(`d:${d.id}`)) ?? null
  const latest = state.latest
  const outdated = (devices ?? []).filter((d) => needsUpdate(d, latest) && !updateInProgress(d))
  const all = outdated.length > 1 ? h('button', {
    class: 'btn primary', type: 'button',
    onclick: async (e: Event) => {
      const b = e.currentTarget as HTMLButtonElement
      if (await act(b, async () => { for (const d of outdated) await backend.requestUpdate(d) },
        `Mise à jour demandée à ${plural(outdated.length, 'appareil', 'appareils')}`)) await refresh(['devices'])
    },
  }, icon('refresh', 16), `Tout mettre à jour (${outdated.length})`) : null

  const box = h('section', {}, pageHeader('appareils', all), errorBanner('devices'))
  if (!devices) return append(box, [state.errors.devices ? null : loading()]), box

  if (latest) {
    append(box, [h('p', { class: 'latest' }, icon('check', 14), 'Dernière version publiée ', h('strong', {}, latest.versionName || String(latest.versionCode)))])
  }
  if (devices.length === 0) {
    append(box, [emptyCard('devices', 'Aucun appareil pour l’instant',
      'Chaque appareil apparaît ici après sa première synchronisation avec le compte.')])
    return box
  }
  const now = Date.now()
  append(box, [h('ul', { class: 'rows' }, devices.map((d) => deviceRow(d, latest, now)))])
  append(box, [h('p', { class: 'note' }, icon('info', 15), h('span', {},
    'Un appareil reçoit la demande de mise à jour quand Vyzo y est ouvert, ou à sa prochaine ouverture dans les 24 h. ' +
    'Sur Android, l’installation peut demander une confirmation à l’écran.'))])
  return box
}

function deviceRow(d: Device, latest: LatestVersion | null, now: number) {
  const online = isOnline(d, now)
  const outdated = needsUpdate(d, latest)
  const waiting = updateInProgress(d, now)
  const label = deviceName(d)

  const versionPill = !d.appVersion ? null
    : outdated ? h('span', { class: 'pill planned mono', title: `Version installée ${d.appVersion}` }, `${d.appVersion} → ${latest!.versionName}`)
      : latest && d.versionCode >= latest.versionCode ? h('span', { class: 'pill ok mono' }, d.appVersion)
        : h('span', { class: 'pill muted mono' }, d.appVersion)

  let status: HTMLElement | null = null
  if (updatePending(d)) {
    status = h('div', { class: 'device-status waiting' }, h('span', { class: 'spinner', 'aria-hidden': 'true' }),
      online ? 'Demande envoyée, en attente de l’appareil…' : 'Demande envoyée : elle sera faite à la prochaine ouverture de Vyzo (24 h).')
  } else if (waiting) {
    status = h('div', { class: 'device-status waiting' }, h('span', { class: 'spinner', 'aria-hidden': 'true' }), d.updateStatus)
  } else if (d.updateStatus && d.updateStatusAt > 0) {
    const failed = /^Échec|n’autorise|n'autorise/.test(d.updateStatus)
    status = h('div', { class: `device-status ${failed ? 'bad' : ''}` }, icon(failed ? 'info' : 'refresh', 13),
      h('span', {}, d.updateStatus), h('span', { class: 'faint' }, ` · ${relative(d.updateStatusAt)}`))
  }

  const update: HTMLButtonElement = h('button', {
    class: `btn ${outdated ? 'primary' : 'ghost'} small`, type: 'button', disabled: waiting,
    title: outdated ? '' : 'L’appareil vérifie s’il existe une version plus récente et l’installe',
    onclick: async () => {
      if (await act(update, () => backend.requestUpdate(d), `Mise à jour demandée à « ${label} »`)) await refresh(['devices'])
    },
  }, icon('refresh', 14), outdated ? 'Mettre à jour' : 'Vérifier')

  const remove = h('button', {
    class: 'icon-btn', type: 'button', title: 'Retirer de la liste', 'aria-label': `Retirer ${label} de la liste`,
    onclick: () => softDelete(`d:${d.id}`, `« ${label} » retiré de la liste`, () => backend.removeDevice(d), ['devices']),
  }, icon('trash', 15))
  const remote = supportsRemote(d)
  const later = 'Avec Vyzo 1.13.0-beta3 et plus sur cet appareil'
  const rename = h('button', {
    class: 'icon-btn', type: 'button', title: 'Renommer', 'aria-label': `Renommer ${label}`, onclick: () => renameDialog(d),
  }, icon('edit', 15))
  const message = h('button', {
    class: 'icon-btn', type: 'button', disabled: !remote || !online, 'aria-label': `Envoyer un message à ${label}`,
    title: !remote ? later : online ? 'Afficher un message à l’écran' : 'Vyzo doit être ouvert sur l’appareil',
    onclick: () => messageDialog(d),
  }, icon('message', 15))
  const check: HTMLButtonElement = h('button', {
    class: 'icon-btn', type: 'button', disabled: !remote || !online, 'aria-label': `Vérifier les abonnements depuis ${label}`,
    title: !remote ? later : online ? 'Vérifier maintenant l’état des abonnements' : 'Vyzo doit être ouvert sur l’appareil',
    onclick: () => void checkNow(d, check),
  }, icon('pulse', 15))

  const details = [d.model && d.model !== d.name ? d.model : null, d.system || null].filter(Boolean).join(' · ')
  return h('li', { class: 'row-card device' },
    h('span', { class: 'kind-badge big' }, icon(DEVICE_ICON[d.kind] ?? 'devices', 20),
      h('span', { class: `presence ${online ? 'on' : ''}`, title: online ? 'En ligne' : 'Hors ligne' })),
    h('div', { class: 'row-main' },
      h('div', { class: 'title-line' }, h('span', { class: 'row-title' }, label), versionPill),
      h('div', { class: 'row-sub wrap' },
        h('span', { class: online ? 'online' : '' }, online ? 'En ligne' : `Vu ${relative(d.lastSeenAt)}`),
        details ? h('span', { class: 'faint' }, `· ${details}`) : null),
      status,
      online ? nowPlaying(d) : null),
    h('div', { class: 'row-actions' }, update, rename, message, check, remove),
  )
}

/** « ▶ Dune · Yannick » : ce que l'appareil lit, pour les listes courtes. */
function playingLine(d: Device): HTMLElement | null {
  const p = state.playback.get(d.id)
  if (!p || p.state === 'idle' || !p.title) return null
  return h('span', { class: 'playing-line' }, icon(p.state === 'playing' ? 'play' : 'pause', 11),
    h('span', {}, p.title), p.profile ? h('span', { class: 'faint' }, ` · ${p.profile}`) : null)
}

function nowPlaying(d: Device): HTMLElement | null {
  const p = state.playback.get(d.id)
  if (!p || p.state === 'idle' || !p.title) return null
  const account = state.account!
  const pos = livePosition(p)
  const control = (action: PlaybackAction, iconName: string, text: string) => {
    const b: HTMLButtonElement = h('button', {
      class: 'btn ghost small', type: 'button',
      onclick: () => void act(b, () => backend.controlPlayback(account, d, action)),
    }, icon(iconName, 13), text)
    return b
  }
  const timing = p.durationMs > 0
    ? h('span', { class: 'mono faint' }, `${clock(pos)} / ${clock(p.durationMs)}`)
    : h('span', { class: 'pill live' }, p.kind === 'live' ? 'Direct' : 'En cours')
  return h('div', { class: 'now-playing' },
    h('div', { class: 'np-head' },
      h('span', { class: `np-dot ${p.state}` }),
      h('div', { class: 'np-text' },
        h('div', { class: 'np-title' }, p.title),
        h('div', { class: 'np-sub' }, [p.detail, p.profile ? `Profil ${p.profile}` : '', p.state === 'paused' ? 'En pause' : ''].filter(Boolean).join(' · '))),
      timing),
    p.durationMs > 0 ? h('div', { class: 'bar np-bar' }, barFill(pos / p.durationMs)) : null,
    h('div', { class: 'np-actions' },
      p.state === 'playing' ? control('pause', 'pause', 'Pause') : control('resume', 'play', 'Reprendre'),
      control('stop', 'stop', 'Arrêter')))
}

/** 1:05:09 ou 12:40. */
function clock(ms: number): string {
  const t = Math.max(0, Math.floor(ms / 1000))
  const hh = Math.floor(t / 3600)
  const mm = Math.floor((t % 3600) / 60)
  const ss = t % 60
  const two = (n: number) => String(n).padStart(2, '0')
  return hh ? `${hh}:${two(mm)}:${two(ss)}` : `${mm}:${two(ss)}`
}

function renameDialog(d: Device) {
  const input = h('input', { id: 'dev-label', maxlength: MAX_DEVICE_LABEL, value: d.label, placeholder: d.name || 'Nom de l’appareil', autocomplete: 'off' })
  const save: HTMLButtonElement = h('button', { class: 'btn primary', type: 'submit' }, 'Enregistrer')
  const form = h('form', {
    onsubmit: async (e: Event) => {
      e.preventDefault()
      if (await act(save, () => backend.renameDevice(d, input.value), input.value.trim() ? 'Appareil renommé' : 'Nom d’origine rétabli')) {
        dialog.close()
        await refresh(['devices'])
      }
    },
  },
    h('h2', {}, 'Renommer l’appareil'),
    h('label', { for: 'dev-label' }, 'Nom affiché sur le site'), input,
    h('p', { class: 'fine' }, `Nom donné par le système : ${d.name || 'inconnu'}. Laisse vide pour le reprendre.`),
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler'), save))
  const dialog = openDialog(form, 'small')
  input.focus()
  input.select()
}

const SIGNATURE_KEY = 'vyzo-message-from'

function messageDialog(d: Device) {
  const account = state.account!
  const name = deviceName(d)
  let saved = ''
  try { saved = localStorage.getItem(SIGNATURE_KEY) ?? '' } catch { /* stockage indisponible */ }
  const text = h('textarea', { id: 'msg-text', maxlength: MAX_MESSAGE, rows: 4, required: true, placeholder: 'À table dans 10 minutes !' })
  const from = h('input', { id: 'msg-from', maxlength: 60, value: saved, placeholder: 'Papa', autocomplete: 'off' })
  const count = h('span', { class: 'fine count' }, `0 / ${MAX_MESSAGE}`)
  text.addEventListener('input', () => { count.textContent = `${text.value.length} / ${MAX_MESSAGE}` })
  const send: HTMLButtonElement = h('button', { class: 'btn primary', type: 'submit' }, icon('message', 14), 'Envoyer')
  const form = h('form', {
    onsubmit: async (e: Event) => {
      e.preventDefault()
      if (!text.value.trim()) return
      try { localStorage.setItem(SIGNATURE_KEY, from.value.trim()) } catch { /* stockage indisponible */ }
      let req = ''
      if (await act(send, async () => { req = await backend.sendMessage(account, d, text.value, from.value) })) {
        dialog.close()
        expectAck(req, d, () => toast(`Message affiché sur « ${name} »`), `« ${name} » n’a pas répondu : Vyzo doit être ouvert à l’écran`)
      }
    },
  },
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('message', 20)),
      h('div', {}, h('h2', {}, 'Message à l’écran'), h('p', { class: 'dialog-sub' }, name))),
    h('label', { for: 'msg-text' }, 'Message'), text, count,
    h('label', { for: 'msg-from' }, 'Signé (facultatif)'), from,
    h('p', { class: 'fine' }, 'Il s’affiche par-dessus ce qui est à l’écran, jusqu’à ce qu’on appuie sur OK. Chiffré de bout en bout.'),
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler'), send))
  const dialog = openDialog(form, 'small')
  text.focus()
}

async function checkNow(d: Device, button: HTMLButtonElement) {
  const name = deviceName(d)
  let req = ''
  if (!await act(button, async () => { req = await backend.checkNow(state.account!, d) })) return
  button.disabled = true
  expectAck(req, d, (action) => {
    if (action === 'check') toast(`« ${name} » interroge les fournisseurs…`)
    else {
      button.disabled = false
      toast('Vérification terminée : l’état des abonnements arrive dans quelques secondes')
      // L'appareil renvoie l'état chiffré après un court délai (envoi groupé) : la page se met à jour d'elle-même.
      window.setTimeout(() => void refresh(['playlists']), 12_000)
    }
  }, `« ${name} » n’a pas répondu : Vyzo doit être ouvert à l’écran`)
  window.setTimeout(() => { button.disabled = false }, 8000)
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
    icon('plus', 16), 'Ajouter un abonnement') : null
  const lockBtn = open ? h('button', {
    class: 'btn ghost', type: 'button',
    title: state.remembered ? 'Verrouiller et oublier la clé gardée sur cet ordinateur' : 'Oublier la clé dans ce navigateur',
    onclick: () => void lockNow(),
  }, icon('lock', 15), 'Verrouiller') : null

  const box = h('section', {}, pageHeader('abonnements', lockBtn, add), errorBanner('playlists'))
  if (state.vault === null) return append(box, [state.errors.playlists ? null : loading()]), box
  if (!open) return append(box, [vaultGate()]), box

  const playlists = state.playlists?.filter((p) => !isHidden(`pl:${p.id}`)) ?? null
  if (!playlists) return append(box, [state.errors.playlists ? null : loading()]), box
  if (playlists.length === 0) {
    append(box, [emptyCard('key', 'Aucun abonnement',
      'Ajoute ton abonnement Xtream, ta liste M3U ou ton portail Stalker : il sera proposé sur tous tes appareils.')])
    return box
  }
  append(box, [h('ul', { class: 'rows' }, playlists.map((p) => playlistRow(p, state.playlists ?? playlists)))])
  append(box, [h('p', { class: 'note' }, icon('lock', 15), h('span', {},
    'Le chiffrement se fait dans ce navigateur, avec la clé du compte : le serveur Vyzo ne voit jamais tes identifiants en clair.'))])
  return box
}

function playlistRow(p: Playlist, all: Playlist[]) {
  const sub = [TYPE_LABEL[p.type], hostOf(p.url), p.type === 'XTREAM' ? p.username : p.type === 'STALKER' ? p.mac : null]
    .filter(Boolean).join(' · ')
  const account = state.account!
  const del = h('button', {
    class: 'icon-btn', type: 'button', title: 'Supprimer', 'aria-label': `Supprimer ${p.name}`,
    onclick: () => softDelete(`pl:${p.id}`, `« ${p.name} » supprimé de tes appareils`, () => backend.deletePlaylist(account, p), ['playlists']),
  }, icon('trash', 15))
  const sub2 = p.status ? [
    p.status.maxConnections > 0 ? plural(p.status.maxConnections, 'connexion', 'connexions') : null,
    p.status.checkedDay > 0 ? `vérifié le ${dateFmt.format(p.status.checkedDay)}` : null,
  ].filter(Boolean).join(' · ') : ''
  return h('li', { class: 'row-card' },
    h('span', { class: 'kind-badge big' }, icon(p.type === 'M3U' ? 'list' : 'key', 18)),
    h('div', { class: 'row-main' },
      h('div', { class: 'title-line' }, h('span', { class: 'row-title' }, p.name), subscriptionPill(p)),
      h('div', { class: 'row-sub' }, h('span', { class: 'ellipsis' }, sub)),
      sub2 ? h('div', { class: 'row-sub faint' }, sub2) : null),
    h('div', { class: 'row-actions' },
      h('button', { class: 'btn ghost small', type: 'button', onclick: () => editPlaylist(p, all) }, icon('edit', 14), 'Modifier'),
      del),
  )
}

const TONE_PILL: Record<Tone, string> = { ok: 'ok', warn: 'planned', bad: 'bad' }

/** État chez le fournisseur, tel que le dernier appareil l'a vu. */
function subscriptionPill(p: Playlist) {
  const st = subscriptionState(p.status)
  if (!st) return null
  return h('span', { class: `pill ${TONE_PILL[st.tone]}`, title: p.status?.expiresAt ? `Fin le ${dateFmt.format(p.status.expiresAt)}` : '' }, st.label)
}

const MAC_RE = /^([0-9A-F]{2}:){5}[0-9A-F]{2}$/i

function editPlaylist(existing: Playlist | null, all: Playlist[] = state.playlists ?? []) {
  const draft: PlaylistDraft = existing
    ? { name: existing.name, type: existing.type, url: existing.url, username: existing.username, password: existing.password,
      mac: existing.mac || '00:1A:79:', userAgent: existing.userAgent }
    : { name: '', type: 'XTREAM', url: '', username: '', password: '', mac: '00:1A:79:', userAgent: '' }

  const input = (id: string, value: string, attrs: Record<string, string | boolean> = {}) =>
    h('input', { id, value, autocomplete: 'off', spellcheck: 'false', ...attrs })
  const name = input('pl-name', draft.name, { placeholder: 'Facultatif', maxlength: '60' })
  const url = input('pl-url', draft.url, { inputmode: 'url' })
  const user = input('pl-user', draft.username)
  const pass = input('pl-pass', draft.password, { type: 'password', autocomplete: 'new-password' })
  const mac = input('pl-mac', draft.mac, { placeholder: '00:1A:79:XX:XX:XX', class: 'mono-input' })
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
      reveal.appendChild(icon(shown ? 'eye' : 'eyeOff', 15))
    },
  }, icon('eye', 15))

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
        toast(existing ? 'Abonnement enregistré' : `« ${final.name} » ajouté à tes appareils`)
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
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('key', 20)),
      h('h2', {}, existing ? 'Modifier l’abonnement' : 'Nouvel abonnement')),
    h('span', { class: 'field-label' }, 'Type'), tabs,
    h('label', { for: 'pl-name' }, 'Nom'), name,
    fields,
    h('details', { class: 'more' }, h('summary', {}, 'Options avancées'),
      h('label', { for: 'pl-agent' }, 'User-Agent'), agent),
    existing ? h('p', { class: 'fine' },
      'Changer d’identifiant, d’adresse MAC ou d’adresse M3U en fait un nouvel abonnement pour les appareils : ' +
      'ses réglages de chaînes (masquées, ordre) repartent de zéro.') : null,
    error,
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }), cancel, save),
  )
  const dialog = openDialog(form, 'wide')
  ;(existing ? name : url).focus()
}

// ------------------------------------------------------------------ Historique

function historyView() {
  const profile = state.profiles?.find((p) => p.id === state.selected) ?? null
  const progress = state.progress?.profileId === state.selected
    ? state.progress.items.filter((x) => !isHidden(`p:${x.profileId}:${x.contentKey}`)) : null
  const clearAll: HTMLButtonElement | null = profile ? h('button', {
    class: 'btn danger-ghost', type: 'button', disabled: !progress || progress.length === 0,
    onclick: async () => {
      const n = progress?.length ?? 0
      const ok = await confirmDialog({
        title: `Effacer l’historique de ${profile.name} ?`,
        body: `${plural(n, 'contenu disparaît', 'contenus disparaissent')} de « Reprendre » et de l’historique, sur tous les appareils. Les listes ne sont pas touchées.`,
        confirm: 'Tout effacer', danger: true,
      })
      if (ok && await act(clearAll, () => backend.clearHistory(state.account!, profile.id), `Historique de ${profile.name} effacé`)) {
        await refresh(['progress', 'activity'])
      }
    },
  }, icon('trash', 15), 'Tout effacer') : null

  const box = h('section', {},
    pageHeader('historique', clearAll),
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

function progressRow(x: ProgressItem, profile: Profile) {
  const t = contentTitle(x)
  const ratio = progressRatio(x)
  const account = state.account!
  const remove = h('button', {
    class: 'icon-btn', type: 'button', title: 'Retirer de l’historique', 'aria-label': `Retirer ${t.text} de l’historique`,
    onclick: () => softDelete(`p:${x.profileId}:${x.contentKey}`, t.known ? `« ${t.text} » retiré de l’historique` : 'Retiré de l’historique',
      () => backend.removeProgress(account, profile.id, [x.contentKey]), ['progress', 'activity']),
  }, icon('x', 15))

  const when = x.kind === 'LIVE' ? `Regardée ${relative(x.updatedAt)}` : relative(x.updatedAt)
  const watched = x.durationMs > 0
    ? (ratio >= 0.95 ? 'Terminé' : `${Math.round(ratio * 100)} % · reste ${duration(x.durationMs - x.positionMs)}`)
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
    h('div', { class: 'row-actions' },
      castButton({ contentKey: x.contentKey, itemType: castItemType(x.kind, true), title: x.title, positionMs: ratio >= 0.95 ? 0 : x.positionMs }, t.text),
      remove),
  )
}

// ------------------------------------------------------------------ Listes

function listsView() {
  const profile = state.profiles?.find((p) => p.id === state.selected) ?? null
  const data = state.lists?.profileId === state.selected ? state.lists : null
  const add = profile && data ? h('button', { class: 'btn primary', type: 'button', onclick: () => editList(profile, null, data) },
    icon('plus', 16), 'Nouvelle liste') : null

  const box = h('section', {},
    pageHeader('listes', add),
    profilePicker((p) => state.activity?.find((a) => a.profileId === p.id)?.inLists ?? null),
    errorBanner('lists'))
  if (!state.profiles) return append(box, [loading()]), box
  if (!profile) return append(box, [h('p', { class: 'empty' }, 'Aucun profil sur ce compte.')]), box
  if (!data) return append(box, [state.errors.lists ? null : loading()]), box

  const visibleItems = data.items.filter((x) => !isHidden(`i:${x.profileId}:${x.listId}:${x.contentKey}`))
  append(box, [titlesNotice(visibleItems)])
  const favorites: UserList = { profileId: profile.id, id: FAVORITES_LIST_ID, name: 'Favoris', position: -1, updatedAt: 0 }
  const lists = [favorites, ...[...data.lists]
    .filter((l) => !isHidden(`l:${l.profileId}:${l.id}`))
    .sort((a, b) => a.position - b.position || a.name.localeCompare(b.name, 'fr'))]
  for (const list of lists) {
    const items = visibleItems.filter((x) => x.listId === list.id).sort((a, b) => a.position - b.position || b.addedAt - a.addedAt)
    const isFav = list.id === FAVORITES_LIST_ID
    const del = isFav ? null : h('button', {
      class: 'icon-btn', type: 'button', title: 'Supprimer la liste', 'aria-label': `Supprimer la liste ${list.name}`,
      onclick: () => softDelete(`l:${list.profileId}:${list.id}`, `Liste « ${list.name} » supprimée`,
        () => backend.deleteList(list), ['lists', 'activity']),
    }, icon('trash', 14))
    append(box, [
      h('div', { class: 'list-head' },
        sectionTitle(list.name, isFav ? 'heart' : 'list', items.length),
        isFav ? null : h('span', { class: 'order' },
          h('button', { class: 'icon-btn', type: 'button', title: 'Renommer', 'aria-label': `Renommer ${list.name}`,
            onclick: () => editList(profile, list, data) }, icon('edit', 14)),
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
  const remove = h('button', {
    class: 'icon-btn', type: 'button', title: `Retirer de « ${list.name} »`, 'aria-label': `Retirer ${t.text} de ${list.name}`,
    onclick: () => softDelete(`i:${x.profileId}:${x.listId}:${x.contentKey}`, `Retiré de « ${list.name} »`,
      () => backend.removeListItem(x), ['lists', 'activity']),
  }, icon('x', 15))
  return h('li', { class: 'row-card compact' },
    kindBadge(x.kind),
    h('div', { class: 'row-main' },
      h('div', { class: `row-title ${t.known ? '' : 'unknown'}` }, t.text),
      h('div', { class: 'row-sub' }, h('span', { class: 'ellipsis' },
        [t.known ? KIND_LABEL[x.kind] : null, x.subtitle || null, x.addedAt > 0 ? `ajouté ${relative(x.addedAt)}` : null].filter(Boolean).join(' · ')))),
    h('div', { class: 'row-actions' },
      castButton({ contentKey: x.contentKey, itemType: castItemType(x.kind, false), title: x.title, positionMs: 0 }, t.text),
      remove),
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
      if (await act(save, () => backend.saveList(list), existing ? 'Liste renommée' : `Liste « ${clean} » créée pour ${profile.name}`)) {
        dialog.close()
        await refresh(['lists'])
      }
    },
  },
    h('h2', {}, existing ? 'Renommer la liste' : `Nouvelle liste pour ${profile.name}`),
    h('label', { for: 'list-name' }, 'Nom'), name,
    error,
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }), cancel, save))
  const dialog = openDialog(form, 'small')
  name.focus()
}

// ------------------------------------------------------------------ Enregistrements

const GROUPS: { id: RecordingGroup; title: string; icon: string }[] = [
  { id: 'upcoming', title: 'À venir et en cours', icon: 'calendar' },
  { id: 'done', title: 'Terminés', icon: 'check' },
  { id: 'other', title: 'Annulés, en échec ou manqués', icon: 'x' },
]

function recordingsView() {
  const schedule = state.vault === 'open'
    ? h('button', { class: 'btn primary', type: 'button', onclick: () => guideDialog() }, icon('plus', 16), 'Programmer')
    : null
  const box = h('section', {}, pageHeader('enregistrements', schedule), errorBanner('recordings'))
  const recordings = state.recordings?.filter((r) => !isHidden(`r:${r.id}`)) ?? null
  if (!recordings) return append(box, [state.errors.recordings ? null : loading()]), box
  if (recordings.length === 0) {
    append(box, [emptyCard('record', 'Aucun enregistrement',
      'Cherche un programme avec « Programmer », ou choisis-le dans le guide TV de Vyzo puis « Enregistrer ». Il apparaîtra ici.',
      schedule ? h('button', { class: 'btn primary', type: 'button', onclick: () => guideDialog() }, icon('search', 15), 'Chercher dans le guide') : null)])
    return box
  }
  const now = Date.now()
  let first = true
  for (const g of GROUPS) {
    const items = sortRecordings(recordings.filter((r) => recordingGroup(r, now) === g.id), g.id)
    if (items.length === 0) continue
    const title = sectionTitle(g.title, g.icon, items.length)
    if (first) title.classList.add('first')
    first = false
    append(box, [title, h('ul', { class: 'rows' }, items.map((r) => recordingRow(r, now)))])
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
          title: 'Annuler cet enregistrement ?', body: `« ${name} » sur ${r.channelName}, ${dayLabel(r.startMs).toLowerCase()} à ${timeFmt.format(r.startMs)}.`,
          confirm: 'Annuler l’enregistrement', cancel: 'Garder', danger: true,
        })
        if (ok && await act(action, () => backend.setRecordingStatus(r, 'CANCELLED'), 'Enregistrement annulé')) await refresh(['recordings'])
      },
    }, icon('x', 14), 'Annuler')
  } else if (r.status === 'RECORDING') {
    action = h('button', {
      class: 'btn ghost small', type: 'button',
      onclick: async () => {
        const ok = await confirmDialog({
          title: 'Arrêter l’enregistrement ?', body: `Vyzo PC arrête d’enregistrer « ${name} ». Ce qui est déjà enregistré est gardé.`,
          confirm: 'Arrêter', cancel: 'Continuer', danger: true,
        })
        if (ok && await act(action, () => backend.setRecordingStatus(r, 'CANCELLED'), 'Arrêt demandé à Vyzo PC')) await refresh(['recordings'])
      },
    }, icon('stop', 14), 'Arrêter')
  } else {
    action = h('button', {
      class: 'icon-btn', type: 'button', title: 'Retirer de la liste', 'aria-label': `Retirer ${name} de la liste`,
      onclick: () => softDelete(`r:${r.id}`, `« ${name} » retiré de la liste`, () => backend.removeRecording(r), ['recordings']),
    }, icon('trash', 15))
  }

  return h('li', { class: 'row-card rec' },
    h('div', { class: 'when', title: fullFmt.format(r.startMs) },
      h('span', { class: 'when-day' }, dayLabel(r.startMs)),
      h('span', { class: 'when-time' }, `${timeFmt.format(r.startMs)} – ${timeFmt.format(r.stopMs)}`)),
    h('div', { class: 'row-main' },
      h('div', { class: 'row-title' }, name),
      h('div', { class: 'row-sub' }, icon('tv', 13), r.channelName || 'Chaîne inconnue',
        r.error ? h('span', { class: 'rec-error' }, ` · ${r.error}`) : null)),
    h('span', { class: `pill ${statusClass}` }, status),
    action,
  )
}

// ------------------------------------------------------------------ Lancer sur un appareil

/** Recherche dans le guide des appareils connectés, puis programmation (table recordings, comme depuis l'appli). */
function guideDialog() {
  if (state.vault !== 'open') return unlockDialog()
  const account = state.account!
  const input = h('input', { id: 'guide-q', type: 'search', placeholder: 'Titre ou chaîne : journal, Ligue 1, Arte…', autocomplete: 'off', minlength: 2, maxlength: 80 })
  const go_: HTMLButtonElement = h('button', { class: 'btn primary', type: 'submit' }, icon('search', 15), 'Chercher')
  const results = h('div', { class: 'guide-results' })
  const hasPc = (state.devices ?? []).some((d) => d.kind === 'pc')
  const programmed = () => new Set((state.recordings ?? []).filter((r) => r.status === 'SCHEDULED' || r.status === 'RECORDING').map((r) => r.id))

  const show = (items: GuideResult[]) => {
    clear(results)
    if (items.length === 0) {
      append(results, [h('p', { class: 'empty' }, 'Aucun programme à venir ne correspond.')])
      return
    }
    const already = programmed()
    let day = ''
    const list = h('ul', { class: 'rows' })
    for (const g of items) {
      const label = dayLabel(g.start)
      if (label !== day) {
        day = label
        list.appendChild(h('li', { class: 'guide-day' }, label))
      }
      const id = recordingId(g)
      const live = g.start <= Date.now()
      const add: HTMLButtonElement = h('button', {
        class: `btn ${already.has(id) ? 'ghost' : 'primary'} small`, type: 'button', disabled: already.has(id),
        onclick: async () => {
          if (await act(add, () => backend.scheduleRecording(g), `Programmé : ${g.title}`)) {
            add.disabled = true
            add.className = 'btn ghost small'
            clear(add)
            append(add, [icon('check', 13), 'Programmé'])
            void refresh(['recordings'])
          }
        },
      }, already.has(id) ? icon('check', 13) : icon('record', 13), already.has(id) ? 'Programmé' : 'Enregistrer')
      list.appendChild(h('li', { class: 'row-card compact' },
        h('div', { class: 'guide-time mono' }, timeFmt.format(g.start), h('small', {}, timeFmt.format(g.stop))),
        h('div', { class: 'row-main' },
          h('div', { class: 'row-title' }, g.title || 'Programme sans titre', live ? h('span', { class: 'pill live' }, 'En cours') : null),
          h('div', { class: 'row-sub' }, g.channel || 'Chaîne', g.desc ? h('span', { class: 'faint' }, ` · ${g.desc}`) : null)),
        add))
    }
    append(results, [list])
  }

  const form = h('form', {
    class: 'guide-search',
    onsubmit: async (e: Event) => {
      e.preventDefault()
      const q = input.value.trim()
      if (q.length < 2) return input.focus()
      clear(results)
      append(results, [h('p', { class: 'searching' }, h('span', { class: 'spinner', 'aria-hidden': 'true' }), 'Recherche dans le guide de tes appareils…')])
      go_.disabled = true
      try {
        show(await backend.searchGuide(account, q))
      } catch (err) {
        clear(results)
        append(results, [h('p', { class: 'form-error' }, err instanceof UserError ? err.message : friendlyError(err))])
      } finally {
        go_.disabled = false
      }
    },
  }, input, go_)

  const dialog = openDialog(h('div', {},
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('record', 20)),
      h('div', {}, h('h2', {}, 'Programmer un enregistrement'),
        h('p', { class: 'dialog-sub' }, 'Le guide est lu sur un appareil où Vyzo est ouvert.'))),
    form, results,
    h('p', { class: 'fine' }, icon('info', 13), hasPc
      ? 'Vyzo PC enregistre, même s’il est éteint maintenant : il le fera à sa prochaine ouverture si le programme n’est pas passé.'
      : 'Aucun Vyzo PC sur ce compte : c’est lui qui enregistre. Installe-le sur un ordinateur pour que l’enregistrement se fasse.'),
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Fermer'))), 'wide')
  input.focus()
}

function castButton(item: CastItem, label: string) {
  return h('button', {
    class: 'icon-btn', type: 'button', title: 'Lancer sur un appareil', 'aria-label': `Lancer ${label} sur un appareil`,
    onclick: () => castDialog(item, label),
  }, icon('cast', 15))
}

function castDialog(item: CastItem, label: string) {
  const now = Date.now()
  const devices = (state.devices ?? [])
    .filter((d) => !isHidden(`d:${d.id}`))
    .sort((a, b) => Number(castReadiness(b, now) === 'ready') - Number(castReadiness(a, now) === 'ready') || b.lastSeenAt - a.lastSeenAt)
  const account = state.account!
  const list = h('ul', { class: 'rows' }, devices.map((d) => {
    const ready = castReadiness(d, now)
    const name = deviceName(d)
    const send: HTMLButtonElement = h('button', {
      class: `btn ${ready === 'ready' ? 'primary' : 'ghost'} small`, type: 'button', disabled: ready !== 'ready',
      onclick: async () => {
        if (await act(send, () => backend.castTo(account, d, { ...item, title: item.title || label }), `Envoyé à « ${name} »`)) dialog.close()
      },
    }, icon('play', 13), 'Lancer')
    return h('li', { class: 'row-card compact' },
      h('span', { class: 'kind-badge' }, icon(DEVICE_ICON[d.kind] ?? 'devices', 16), h('span', { class: `presence ${isOnline(d, now) ? 'on' : ''}` })),
      h('div', { class: 'row-main' },
        h('div', { class: 'row-title' }, name),
        h('div', { class: 'row-sub' }, ready === 'ready' ? h('span', { class: 'online' }, 'Vyzo ouvert')
          : ready === 'offline' ? `Vyzo fermé · vu ${relative(d.lastSeenAt)}` : `Version ${d.appVersion || 'ancienne'} : mise à jour nécessaire`)),
      send)
  }))
  const dialog = openDialog(h('div', {},
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('cast', 20)),
      h('div', {}, h('h2', {}, 'Lancer sur…'), h('p', { class: 'dialog-sub' }, label))),
    devices.length ? list : h('p', { class: 'empty' }, 'Aucun appareil sur ce compte.'),
    h('p', { class: 'fine' }, 'Vyzo doit être ouvert sur l’appareil, avec l’abonnement de ce titre. Le code parental est demandé si le titre est verrouillé.'),
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Fermer'))))
}

// ------------------------------------------------------------------ Contrôle parental

let catalogQuery = ''

function parentalView() {
  const box = h('section', {}, pageHeader('parental'), errorBanner('parental'))
  if (state.vault === null) return append(box, [loading()]), box
  if (state.vault !== 'open') {
    return append(box, [vaultGate('Réglages chiffrés',
      'Le code parental et les catégories de tes abonnements sont chiffrés avec le mot de passe du compte. Saisis-le pour les gérer.')]), box
  }
  const value = state.parental
  if (value === undefined) return append(box, [state.errors.parental ? null : loading(2)]), box

  const account = state.account!
  const save = (next: Parental | null, done: string, button: HTMLButtonElement | null = null) =>
    act(button, async () => { await backend.saveParental(account, next); state.parental = next; renderView() }, done)

  // Code
  const setCode = h('button', { class: 'btn primary', type: 'button', onclick: () => parentalPinDialog(value) },
    icon('lock', 14), value?.pinHash ? 'Changer le code' : value ? 'Définir un code' : 'Définir un code pour toute la maison')
  const removeCode = value?.pinHash ? h('button', {
    class: 'btn ghost', type: 'button',
    onclick: async (e: Event) => {
      const ok = await confirmDialog({
        title: 'Retirer le code parental ?', body: 'Sur tous les appareils à jour, les catégories verrouillées redeviennent visibles sans code.',
        confirm: 'Retirer le code', danger: true,
      })
      if (ok) await save({ ...value, pinHash: '' }, 'Code parental retiré', e.currentTarget as HTMLButtonElement)
    },
  }, 'Retirer le code') : null
  const stopSharing = value ? h('button', {
    class: 'btn danger-ghost', type: 'button',
    onclick: async (e: Event) => {
      const button = e.currentTarget as HTMLButtonElement
      const ok = await confirmDialog({
        title: 'Ne plus partager le code ?', body: 'Chaque appareil garde le code actuel, puis peut en changer seul. Le site ne le gère plus.',
        confirm: 'Ne plus partager',
      })
      if (ok) await save(null, 'Chaque appareil gère maintenant son code', button)
    },
  }, 'Ne plus partager') : null
  const codeText = !value ? 'Pour l’instant, chaque appareil a son propre code parental (ou aucun).'
    : value.pinHash ? 'Un code commun protège les catégories verrouillées sur tous tes appareils.'
      : 'Contrôle commun actif, mais sans code : les catégories verrouillées restent accessibles.'
  const code = panel('Code parental', codeText, [h('div', { class: 'row' }, setCode, removeCode, h('span', { class: 'spacer' }), stopSharing)],
    [icon('info', 14), value ? 'Appliqué par Vyzo 1.13.0-beta2 et plus, dès que l’appareil se synchronise.'
      : 'Le code choisi ici remplace celui de chaque appareil (Vyzo 1.13.0-beta2 et plus).'])

  // Options
  const option = (id: string, checked: boolean, title: string, text: string, change: (on: boolean) => Parental) => {
    const input = h('input', {
      id, type: 'checkbox', checked, disabled: !value,
      onchange: () => void save(change(input.checked), 'Contrôle parental enregistré'),
    })
    return h('label', { class: 'check', for: id }, input, h('span', {}, h('strong', {}, title), h('small', {}, text)))
  }
  const options = panel('Règles', value ? null : 'Disponibles une fois le code commun défini.', [
    option('pa-adult', value?.autoAdult ?? true, 'Verrouiller les catégories pour adultes',
      'Détectées automatiquement à partir du nom et des indications du fournisseur.', (on) => ({ ...value!, autoAdult: on })),
    option('pa-kid', value?.kidExit ?? true, 'Code pour quitter un profil enfant',
      'Un enfant ne peut pas passer seul sur un profil adulte.', (on) => ({ ...value!, kidExit: on })),
  ])

  append(box, [h('div', { class: 'settings' }, code, options, screenTimePanel(value), categoriesPanel(value?.autoAdult ?? true))])
  return box
}

function screenTimePanel(parental: Parental | null) {
  const profiles = [...(state.profiles ?? [])].sort((a, b) => Number(b.isKid) - Number(a.isKid) || a.position - b.position)
  const rules = state.screentime
  let body: (Node | null)[]
  if (!rules) body = [state.errors.screentime ? h('p', { class: 'form-error' }, state.errors.screentime) : loading(2)]
  else if (profiles.length === 0) body = [h('p', { class: 'empty small' }, 'Aucun profil sur ce compte.')]
  else {
    body = [h('ul', { class: 'rows' }, profiles.map((p) => {
      const r = rules.get(p.id)
      const active = !!r && r.enabled && ruleSummary(r) !== 'Aucune limite'
      return h('li', { class: 'row-card compact' },
        avatar(p, 'sm'),
        h('div', { class: 'row-main' },
          h('div', { class: 'row-title' }, p.name, p.isKid ? h('span', { class: 'pill muted' }, 'Enfant') : null),
          h('div', { class: 'row-sub' }, active ? h('span', {}, icon('clock', 12), ' ', ruleSummary(r)) : 'Aucune limite')),
        h('button', { class: `btn ${active ? 'ghost' : 'primary'} small`, type: 'button', onclick: () => screenTimeDialog(p, r ?? null) },
          active ? 'Modifier' : 'Limiter'))
    }))]
  }
  return panel('Temps d’écran', 'Une plage horaire et une durée par jour, comptée sur tous les appareils.', body,
    [icon('info', 14), parental?.pinHash
      ? 'Une fois la limite atteinte, la lecture s’arrête ; le code parental donne une heure de plus. Vyzo 1.13.0-beta3 et plus.'
      : 'Une fois la limite atteinte, la lecture s’arrête. Définis un code parental pour pouvoir prolonger. Vyzo 1.13.0-beta3 et plus.'])
}

function screenTimeDialog(p: Profile, current: ScreenTimeRule | null) {
  const account = state.account!
  const r = current ?? EMPTY_RULE
  const field = (id: string, value: string) => h('input', { id, type: 'time', value, step: 300 })
  const minutes = h('select', { id: 'st-daily' },
    [0, 30, 45, 60, 90, 120, 150, 180, 240, 300].concat(r.dailyMinutes && ![0, 30, 45, 60, 90, 120, 150, 180, 240, 300].includes(r.dailyMinutes) ? [r.dailyMinutes] : [])
      .sort((a, b) => a - b)
      .map((m) => h('option', { value: String(m), selected: m === r.dailyMinutes }, m === 0 ? 'Pas de limite' : minutesLabel(m))))
  const from = field('st-from', r.from)
  const to = field('st-to', r.to)
  const wFrom = field('st-wfrom', r.weekendFrom)
  const wTo = field('st-wto', r.weekendTo)
  const error = h('p', { class: 'form-error', role: 'alert' })
  const save: HTMLButtonElement = h('button', { class: 'btn primary', type: 'submit' }, 'Enregistrer')
  const write = async (rule: ScreenTimeRule | null, done: string, button: HTMLButtonElement) => {
    if (await act(button, () => backend.saveScreenTime(account, p.id, rule), done)) {
      dialog.close()
      await refresh(['screentime'])
    }
  }
  const removeBtn: HTMLButtonElement | null = current ? h('button', {
    class: 'btn danger-ghost', type: 'button', onclick: () => void write(null, `Plus de limite pour ${p.name}`, removeBtn!),
  }, 'Retirer les limites') : null
  const form = h('form', {
    onsubmit: (e: Event) => {
      e.preventDefault()
      const rule: ScreenTimeRule = {
        enabled: true, dailyMinutes: Number(minutes.value), from: from.value, to: to.value, weekendFrom: wFrom.value, weekendTo: wTo.value,
      }
      const problem = checkRule(rule) ?? ((!rule.from) !== (!rule.to) ? 'Semaine : indique l’heure de début et l’heure de fin' : null)
        ?? ((!rule.weekendFrom) !== (!rule.weekendTo) ? 'Week-end : indique l’heure de début et l’heure de fin' : null)
      if (problem) { error.textContent = problem; return }
      void write(rule, `Temps d’écran de ${p.name} enregistré`, save)
    },
  },
    h('div', { class: 'dialog-head' }, avatar(p, 'sm'), h('div', {}, h('h2', {}, 'Temps d’écran'), h('p', { class: 'dialog-sub' }, p.name))),
    h('label', { for: 'st-daily' }, 'Durée maximale par jour'), minutes,
    h('p', { class: 'fine' }, 'Tous appareils confondus : TV, téléphone et PC.'),
    h('fieldset', { class: 'time-range' }, h('legend', {}, 'Du lundi au vendredi'),
      h('div', { class: 'two' }, h('div', {}, h('label', { for: 'st-from' }, 'À partir de'), from), h('div', {}, h('label', { for: 'st-to' }, 'Jusqu’à'), to))),
    h('fieldset', { class: 'time-range' }, h('legend', {}, 'Samedi et dimanche'),
      h('div', { class: 'two' }, h('div', {}, h('label', { for: 'st-wfrom' }, 'À partir de'), wFrom), h('div', {}, h('label', { for: 'st-wto' }, 'Jusqu’à'), wTo))),
    h('p', { class: 'fine' }, 'Laisse vide pour ne pas limiter les heures. Sans horaire du week-end, celui de la semaine s’applique.'),
    error,
    h('div', { class: 'dialog-actions' }, removeBtn, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler'), save))
  const dialog = openDialog(form)
  minutes.focus()
}

function categoriesPanel(autoAdult: boolean) {
  const playlists = state.playlists ?? []
  const body: (Node | null)[] = []
  if (playlists.length === 0) {
    body.push(h('p', { class: 'empty small' }, 'Aucun abonnement sur le compte.'))
    return panel('Catégories verrouillées', null, body)
  }
  if (playlists.length > 1) {
    body.push(h('div', { class: 'picker' }, playlists.map((p) => h('button', {
      type: 'button', class: 'chip', 'aria-selected': String(p.id === state.catalogFor),
      onclick: () => {
        if (state.catalogFor === p.id) return
        state.catalogFor = p.id
        state.catalog = null
        renderView()
        void refresh(['catalog'])
      },
    }, icon('key', 13), h('span', {}, p.name)))))
  }
  const data = state.catalog?.playlistId === state.catalogFor ? state.catalog : null
  if (!data) {
    body.push(state.errors.catalog ? errorBanner('catalog') : loading(2))
    return panel('Catégories verrouillées', 'Elles sont cachées tant que le code n’est pas saisi, et toujours sur un profil enfant.', body)
  }
  if (data.entries.length === 0) {
    body.push(h('p', { class: 'empty small' },
      'Aucun appareil n’a encore envoyé la liste des catégories de cet abonnement. Ouvre Vyzo 1.13.0-beta2 ou plus sur un appareil qui l’utilise.'))
    return panel('Catégories verrouillées', null, body)
  }

  const prefs = new Map(data.prefs.categories.map((c) => [c.rest, c]))
  const rows = data.entries.map((e) => ({ e, pref: prefs.get(e.rest), locked: categoryLocked(e, prefs.get(e.rest), autoAdult) }))
  const search = h('input', {
    id: 'cat-search', type: 'search', placeholder: `Chercher parmi ${data.entries.length} catégories…`, value: catalogQuery,
    autocomplete: 'off', spellcheck: 'false', 'aria-label': 'Chercher une catégorie',
  })
  const results = h('div', {})
  const KIND_TITLE: Record<ContentKind, string> = { LIVE: 'Chaînes', MOVIE: 'Films', SERIES: 'Séries' }
  const renderRows = () => {
    clear(results)
    const tokens = fold(catalogQuery).split(/\s+/).filter(Boolean)
    const shown = tokens.length
      ? rows.filter((r) => tokens.every((t) => fold(r.pref?.name || r.e.name).includes(t))).slice(0, 80)
      : rows.filter((r) => r.locked)
    if (shown.length === 0) {
      results.appendChild(h('p', { class: 'empty small' }, tokens.length ? 'Aucune catégorie ne correspond.' : 'Aucune catégorie verrouillée. Cherche une catégorie pour la verrouiller.'))
      return
    }
    for (const kind of ['LIVE', 'MOVIE', 'SERIES'] as const) {
      const group = shown.filter((r) => r.e.kind === kind)
      if (group.length === 0) continue
      append(results, [sectionTitle(KIND_TITLE[kind], KIND_ICON[kind], group.length), h('ul', { class: 'rows' }, group.map((r) => {
        const toggle: HTMLButtonElement = h('button', {
          class: `btn ${r.locked ? 'ghost' : 'outline'} small`, type: 'button',
          onclick: async () => {
            const next = !r.locked
            if (await act(toggle, () => backend.setCategoryLock(state.account!, data.playlistId, r.e.rest, next),
              next ? `« ${r.pref?.name || r.e.name} » verrouillée` : `« ${r.pref?.name || r.e.name} » déverrouillée`)) await refresh(['catalog'])
          },
        }, icon(r.locked ? 'unlock' : 'lock', 13), r.locked ? 'Déverrouiller' : 'Verrouiller')
        const auto = r.locked && r.pref?.locked == null
        return h('li', { class: 'row-card compact' },
          h('span', { class: `kind-badge ${r.locked ? 'k-locked' : ''}` }, icon(r.locked ? 'lock' : KIND_ICON[kind], 15)),
          h('div', { class: 'row-main' },
            h('div', { class: 'row-title' }, r.pref?.name || r.e.name),
            auto ? h('div', { class: 'row-sub' }, 'Détectée pour adultes') : r.pref?.name && r.pref.name !== r.e.name ? h('div', { class: 'row-sub' }, r.e.name) : null),
          toggle)
      }))])
    }
  }
  search.addEventListener('input', () => { catalogQuery = search.value; renderRows() })
  renderRows()
  const lockedCount = rows.filter((r) => r.locked).length
  body.push(h('div', { class: 'catalog-search' }, icon('search', 15), search), results)
  return panel('Catégories verrouillées', `${plural(lockedCount, 'catégorie verrouillée', 'catégories verrouillées')}. Elles sont cachées tant que le code n’est pas saisi, et toujours sur un profil enfant.`, body)
}

function parentalPinDialog(current: Parental | null | undefined) {
  const pin1 = h('input', { id: 'pp-1', type: 'password', inputmode: 'numeric', maxlength: 4, autocomplete: 'new-password', placeholder: '••••' })
  const pin2 = h('input', { id: 'pp-2', type: 'password', inputmode: 'numeric', maxlength: 4, autocomplete: 'new-password', placeholder: '••••' })
  const error = h('p', { class: 'form-error', role: 'alert' })
  const save = h('button', { class: 'btn primary', type: 'submit' }, 'Enregistrer le code')
  const form = h('form', {
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      error.textContent = ''
      if (!isValidPin(pin1.value)) { error.textContent = 'Le code doit faire 4 chiffres.'; pin1.focus(); return }
      if (pin1.value !== pin2.value) { error.textContent = 'Les deux codes ne correspondent pas.'; pin2.focus(); return }
      const next: Parental = { pinHash: await hashParental(pin1.value), autoAdult: current?.autoAdult ?? true, kidExit: current?.kidExit ?? true }
      if (await act(save, () => backend.saveParental(state.account!, next), current ? 'Code parental changé sur tous les appareils' : 'Code parental commun défini')) {
        state.parental = next
        dialog.close()
        renderView()
      }
    },
  },
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('lock', 20)), h('h2', {}, 'Code parental')),
    h('p', { class: 'dialog-sub' }, 'Demandé sur la TV, le téléphone et le PC pour ouvrir une catégorie verrouillée.'),
    h('div', { class: 'pin-fields' }, h('label', { for: 'pp-1' }, 'Nouveau code', pin1), h('label', { for: 'pp-2' }, 'Confirmer', pin2)),
    error,
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler'), save))
  const dialog = openDialog(form, 'small')
  pin1.focus()
}

// ------------------------------------------------------------------ Sécurité

// ------------------------------------------------------------------ Statistiques

let statsProfile: string | null = null
let statsDays: 7 | 30 = 7

function statsView() {
  const box = h('section', {}, pageHeader('statistiques'), errorBanner('usage'))
  if (state.vault === null) return append(box, [loading()]), box
  if (state.vault !== 'open') {
    return append(box, [vaultGate('Statistiques chiffrées', 'Le temps passé sur chaque titre est chiffré avec le mot de passe du compte. Saisis-le pour le voir.')]), box
  }
  const rows = state.usage
  if (!rows) return append(box, [state.errors.usage ? null : loading(3)]), box
  const profiles = state.profiles ?? []
  if (statsProfile && !profiles.some((p) => p.id === statsProfile)) statsProfile = null

  const chip = (label: string, active: boolean, run: () => void, lead: Node | null = null) =>
    h('button', { class: 'chip', type: 'button', 'aria-pressed': String(active), onclick: () => { run(); renderView() } }, lead, label)
  const filters = h('div', { class: 'stats-filters' },
    h('div', { class: 'chips' },
      chip('Tous', statsProfile === null, () => { statsProfile = null }),
      profiles.map((p) => chip(p.name, statsProfile === p.id, () => { statsProfile = p.id }, avatar(p, 'xs')))),
    h('div', { class: 'segmented small', role: 'radiogroup', 'aria-label': 'Période' },
      ([7, 30] as const).map((n) => h('button', {
        type: 'button', role: 'radio', 'aria-checked': String(statsDays === n), onclick: () => { statsDays = n; renderView() },
      }, `${n} jours`))))
  append(box, [filters])

  if (rows.length === 0) {
    append(box, [emptyCard('chart', 'Pas encore de statistiques',
      'Le temps passé est compté par Vyzo 1.13.0-beta3 et plus, puis envoyé chiffré au compte toutes les quelques minutes.')])
    return box
  }
  const sum = summarizeUsage(rows, statsProfile, statsDays)
  const watchedDays = sum.perDay.filter((d) => d.ms > 0).length
  const best = sum.perDay.reduce((a, b) => (b.ms > a.ms ? b : a), sum.perDay[0])
  const kpi = (label: string, value: string, note: string) =>
    h('div', { class: 'kpi static' }, h('span', { class: 'kpi-label' }, label), h('span', { class: 'kpi-value' }, value), h('span', { class: 'kpi-note' }, note))
  append(box, [h('div', { class: 'kpis' },
    kpi('Temps total', sum.totalMs ? duration(sum.totalMs) : '0 min', `sur ${statsDays} jours`),
    kpi('Par jour', sum.totalMs ? duration(sum.totalMs / statsDays) : '0 min', `en moyenne · ${plural(watchedDays, 'jour actif', 'jours actifs')}`),
    kpi('Jour le plus chargé', best && best.ms ? duration(best.ms) : '—', best && best.ms ? dayLabel(new Date(`${best.day}T12:00`).getTime()) : 'Aucun'),
    kpi('Le plus regardé', sum.top[0] ? duration(sum.top[0].ms) : '—', sum.top[0]?.title ?? 'Rien pour l’instant'))])

  // Barres par jour
  const max = Math.max(1, ...sum.perDay.map((d) => d.ms))
  const chart = h('div', { class: `day-chart ${statsDays === 30 ? 'dense' : ''}`, role: 'img',
    'aria-label': `Temps par jour sur ${statsDays} jours` },
  sum.perDay.map((d) => {
    const date = new Date(`${d.day}T12:00`)
    const bar = h('span', { class: 'day-bar' })
    bar.style.height = `${Math.round((d.ms / max) * 100)}%`
    const weekday = date.toLocaleDateString('fr-FR', { weekday: 'short' }).replace('.', '')
    return h('div', { class: 'day-col', title: `${dayLabel(date.getTime())} : ${d.ms ? duration(d.ms) : 'rien'}` },
      h('span', { class: 'day-value' }, d.ms ? duration(d.ms) : ''),
      h('span', { class: 'day-track' }, bar),
      h('span', { class: 'day-label' }, statsDays === 7 ? weekday : String(date.getDate())))
  }))
  const kinds = [
    ['Direct et replay', sum.byKind.live, 'live'], ['Films', sum.byKind.movie, 'movie'], ['Séries', sum.byKind.episode, 'episode'],
    ['Enregistrements', sum.byKind.other, 'other'],
  ] as const
  const total = Math.max(1, sum.totalMs)
  const stack = h('div', { class: 'kind-stack', role: 'img', 'aria-label': 'Répartition par type' },
    kinds.filter(([, ms]) => ms > 0).map(([label, ms, k]) => {
      const seg = h('span', { class: `seg k-${k}`, title: `${label} : ${duration(ms)}` })
      seg.style.width = `${(ms / total) * 100}%`
      return seg
    }))
  const legend = h('ul', { class: 'kind-legend' }, kinds.filter(([, ms]) => ms > 0).map(([label, ms, k]) =>
    h('li', {}, h('span', { class: `legend-dot k-${k}` }), h('span', {}, label), h('span', { class: 'mono faint' }, `${Math.round((ms / total) * 100)} %`))))

  const topMax = Math.max(1, ...sum.top.map((t) => t.ms))
  const top = sum.top.length ? h('ol', { class: 'top-list' }, sum.top.map((t) => h('li', {},
    h('div', { class: 'top-line' }, h('span', { class: 'top-title' }, t.title), h('span', { class: 'mono faint' }, duration(t.ms))),
    h('div', { class: 'bar' }, barFill(t.ms / topMax))))) : h('p', { class: 'empty small' }, 'Rien sur cette période.')

  const devices = state.devices ?? []
  const byDevice = [...sum.byDevice.entries()].sort((a, b) => b[1] - a[1])
  const deviceList = byDevice.length ? h('ul', { class: 'rows' }, byDevice.map(([id, ms]) => {
    const d = devices.find((x) => x.id === id)
    return h('li', { class: 'row-card compact' },
      h('span', { class: 'kind-badge' }, icon(DEVICE_ICON[d?.kind ?? ''] ?? 'devices', 16)),
      h('div', { class: 'row-main' }, h('div', { class: 'row-title' }, d ? deviceName(d) : 'Appareil retiré')),
      h('span', { class: 'mono faint' }, duration(ms)))
  })) : h('p', { class: 'empty small' }, 'Rien sur cette période.')

  append(box, [h('div', { class: 'dash-grid' },
    h('div', {}, sectionTitle('Jour par jour', 'calendar', null), h('div', { class: 'panel chart-panel' }, chart, stack, legend)),
    h('div', {}, sectionTitle('Les plus regardés', 'play', null), top, sectionTitle('Par appareil', 'devices', null), deviceList))])
  append(box, [h('p', { class: 'note' }, icon('info', 15), h('span', {},
    'Compté sur les appareils en Vyzo 1.13.0-beta3 et plus, lecture en cours seulement (pas les menus). Les 35 derniers jours sont gardés, chiffrés.'))])
  return box
}

// ------------------------------------------------------------------ Journal

let journalFilter: 'all' | 'security' = 'all'

function journalView() {
  const box = h('section', {}, pageHeader('journal',
    h('div', { class: 'segmented small', role: 'radiogroup', 'aria-label': 'Filtre' },
      ([['all', 'Tout'], ['security', 'Sécurité']] as const).map(([id, label]) => h('button', {
        type: 'button', role: 'radio', 'aria-checked': String(journalFilter === id), onclick: () => { journalFilter = id; renderView() },
      }, label)))), errorBanner('events'))
  const events = state.events
  if (!events) return append(box, [state.errors.events ? null : loading(4)]), box
  const shown = journalFilter === 'security' ? events.filter((e) => SENSITIVE_EVENTS.has(e.kind)) : events
  if (shown.length === 0) {
    append(box, [emptyCard('journal', journalFilter === 'security' ? 'Rien à signaler' : 'Journal vide',
      'Les connexions d’appareils, changements de mot de passe, abonnements et profils apparaîtront ici.')])
    return box
  }
  let day = ''
  const list = h('ol', { class: 'timeline' })
  for (const e of shown) {
    const label = dayLabel(e.at)
    if (label !== day) {
      day = label
      list.appendChild(h('li', { class: 'tl-day' }, label))
    }
    const t = eventText(e)
    list.appendChild(h('li', { class: `tl-item ${SENSITIVE_EVENTS.has(e.kind) ? 'sensitive' : ''}` },
      h('span', { class: 'tl-icon' }, icon(t.icon, 14)),
      h('div', { class: 'tl-main' }, h('div', { class: 'tl-title' }, t.label), e.detail ? h('div', { class: 'tl-detail' }, e.detail) : null),
      h('time', { class: 'mono faint', datetime: new Date(e.at).toISOString(), title: fullFmt.format(e.at) }, timeFmt.format(e.at))))
  }
  append(box, [list, h('p', { class: 'note' }, icon('info', 15), h('span', {},
    'Écrit par le serveur du compte : personne ne peut le modifier ni l’effacer. Un appareil inconnu ? Change le mot de passe, puis « Déconnecter les autres appareils » dans Sécurité.'))])
  return box
}

function panel(title: string, lead: string | null, body: (Node | null)[], foot?: (Node | string | null)[], danger = false) {
  return h('section', { class: `panel ${danger ? 'danger-zone' : ''}` },
    h('div', { class: 'panel-head' }, h('h2', {}, title), lead ? h('p', {}, lead) : null),
    h('div', { class: 'panel-body' }, body),
    foot ? h('div', { class: 'panel-foot' }, foot) : null)
}

function securityView() {
  const account = state.account!
  const box = h('section', {}, pageHeader('securite'))

  // Compte
  const vaultText = state.vault === 'open' ? 'Déverrouillées dans cette page'
    : state.vault === 'locked' ? 'Verrouillées' : state.vault === 'missing' ? 'Aucune pour l’instant' : '…'
  const vaultAction = state.vault === 'open'
    ? h('button', { class: 'btn ghost small', type: 'button', onclick: () => void lockNow() }, icon('lock', 14), 'Verrouiller')
    : state.vault === 'locked'
      ? h('button', { class: 'btn ghost small', type: 'button', onclick: () => unlockDialog() }, icon('unlock', 14), 'Déverrouiller')
      : null
  const forget = state.remembered ? h('button', {
    class: 'btn ghost small', type: 'button',
    onclick: async (e: Event) => {
      if (await act(e.currentTarget as HTMLButtonElement, () => backend.forgetRemembered(), 'Clé oubliée sur cet ordinateur')) {
        state.remembered = false
        renderView()
      }
    },
  }, 'Oublier') : null

  const compte = panel('Compte', 'Le compte qui relie la TV, le téléphone et le PC.', [
    h('dl', { class: 'kv' },
      h('dt', {}, 'Adresse e-mail'), h('dd', {}, account.email),
      h('dt', {}, 'Données chiffrées'), h('dd', { class: 'row' }, h('span', {}, vaultText), vaultAction),
      h('dt', {}, 'Sur cet ordinateur'), h('dd', { class: 'row' },
        h('span', {}, state.remembered ? 'Clé gardée (« Rester déverrouillé »)' : 'Rien de gardé'), forget)),
  ])

  // Mot de passe
  const current = h('input', { id: 'pw-current', type: 'password', autocomplete: 'current-password', required: true })
  const next = h('input', { id: 'pw-new', type: 'password', autocomplete: 'new-password', required: true, minlength: 8 })
  const again = h('input', { id: 'pw-again', type: 'password', autocomplete: 'new-password', required: true, minlength: 8 })
  const pwError = h('p', { class: 'form-error', role: 'alert' })
  const pwSubmit = h('button', { class: 'btn primary', type: 'submit' }, 'Changer le mot de passe')
  const pwForm = h('form', {
    onsubmit: async (ev: Event) => {
      ev.preventDefault()
      pwError.textContent = ''
      if (next.value.length < 8) { pwError.textContent = 'Le nouveau mot de passe doit faire au moins 8 caractères.'; next.focus(); return }
      if (next.value !== again.value) { pwError.textContent = 'Les deux nouveaux mots de passe ne correspondent pas.'; again.focus(); return }
      if (next.value === current.value) { pwError.textContent = 'Le nouveau mot de passe est identique à l’actuel.'; next.focus(); return }
      pwSubmit.setAttribute('aria-busy', 'true')
      pwSubmit.disabled = true
      try {
        await backend.changePassword(account, current.value, next.value)
        current.value = ''; next.value = ''; again.value = ''
        toast('Mot de passe changé')
        await refresh(['playlists'])
      } catch (e) {
        pwError.textContent = e instanceof UserError ? e.message : friendlyError(e)
      } finally {
        pwSubmit.removeAttribute('aria-busy')
        pwSubmit.disabled = false
      }
    },
  },
    h('label', { for: 'pw-current' }, 'Mot de passe actuel'), current,
    h('div', { class: 'two' },
      h('div', {}, h('label', { for: 'pw-new' }, 'Nouveau mot de passe'), next),
      h('div', {}, h('label', { for: 'pw-again' }, 'Confirmer'), again)),
    pwError,
    h('div', { class: 'form-actions' }, pwSubmit))
  const motDePasse = panel('Mot de passe', 'Il protège aussi la clé de tes données chiffrées : elle est rechiffrée avec le nouveau.',
    [pwForm], [icon('info', 14), 'Tes appareils restent connectés. Sur un nouvel appareil, utilise le nouveau mot de passe.'])

  // Sessions
  const others = h('button', {
    class: 'btn danger-ghost', type: 'button',
    onclick: async () => {
      const ok = await confirmDialog({
        title: 'Déconnecter les autres appareils ?',
        body: 'Box, téléphones, Vyzo PC et autres navigateurs sont déconnectés du compte. Ils devront s’y reconnecter avec le mot de passe. Cette page reste connectée.',
        confirm: 'Déconnecter', danger: true,
      })
      if (ok) await act(others, () => backend.signOutOthers(), 'Les autres sessions sont fermées')
    },
  }, icon('logout', 15), 'Déconnecter les autres appareils')
  const sessions = panel('Sessions', 'Un appareil perdu, vendu ou prêté ? Ferme toutes les sessions sauf celle-ci.', [h('div', { class: 'form-actions start' }, others)], undefined, true)

  // Affichage
  const current_ = savedTheme()
  const themes = h('div', { class: 'segmented', role: 'radiogroup', 'aria-label': 'Thème' },
    THEMES.map((t) => h('button', {
      type: 'button', role: 'radio', 'data-theme-choice': t.id, 'aria-checked': String(t.id === current_),
      onclick: () => setTheme(t.id),
    }, t.label)))
  const affichage = panel('Affichage', 'Sobre, Cinéma ou Clair. Le choix reste sur cet ordinateur.', [themes])

  // Sauvegarde
  const open = state.vault === 'open'
  const sauvegarde = panel('Sauvegarde du compte',
    'Profils, listes, historique, abonnements, contrôle parental et réglages dans un seul fichier, chiffré avec un mot de passe que tu choisis.',
    [h('div', { class: 'row' },
      h('button', { class: 'btn primary', type: 'button', disabled: !open, onclick: () => backupDialog() }, icon('download', 15), 'Télécharger une sauvegarde'),
      h('button', { class: 'btn ghost', type: 'button', disabled: !open, onclick: () => restoreDialog() }, icon('upload', 15), 'Restaurer'))],
    [icon('info', 14), open ? 'Garde le fichier et son mot de passe en lieu sûr : sans lui, la sauvegarde ne s’ouvre pas.'
      : 'Déverrouille d’abord les données chiffrées (mot de passe du compte).'])

  // Installation
  const standalone = window.matchMedia('(display-mode: standalone)').matches
  const ios = /iPhone|iPad/.test(navigator.userAgent)
  const installation = panel('Application', standalone ? 'Le site est ouvert comme une application.'
    : 'Ajoute le site à ton bureau ou à l’écran d’accueil du téléphone : il s’ouvre dans sa propre fenêtre.',
  [standalone ? h('p', { class: 'row' }, icon('check', 15), 'Installé sur cet appareil')
    : installPrompt ? h('button', { class: 'btn primary', type: 'button', onclick: () => void install() }, icon('install', 15), 'Installer')
      : h('p', { class: 'fine' }, ios ? 'Sur iPhone et iPad : bouton Partager de Safari, puis « Sur l’écran d’accueil ».'
        : 'Dans Chrome ou Edge : menu ⋮, puis « Installer Mon compte Vyzo » (ou « Ajouter à l’écran d’accueil »).')])

  append(box, [h('div', { class: 'settings' }, compte, motDePasse, sessions, sauvegarde, installation, affichage)])
  return box
}

// ------------------------------------------------------------------ Sauvegarde

function backupDialog() {
  if (state.vault !== 'open') return unlockDialog()
  const account = state.account!
  const pw = h('input', { id: 'bk-pw', type: 'password', autocomplete: 'new-password', minlength: 8, required: true })
  const again = h('input', { id: 'bk-again', type: 'password', autocomplete: 'new-password', minlength: 8, required: true })
  const error = h('p', { class: 'form-error', role: 'alert' })
  const go_: HTMLButtonElement = h('button', { class: 'btn primary', type: 'submit' }, icon('download', 14), 'Créer le fichier')
  const form = h('form', {
    onsubmit: async (e: Event) => {
      e.preventDefault()
      error.textContent = ''
      if (pw.value.length < 8) { error.textContent = 'Au moins 8 caractères.'; return pw.focus() }
      if (pw.value !== again.value) { error.textContent = 'Les deux mots de passe ne correspondent pas.'; return again.focus() }
      let made: { file: string; counts: BackupCounts } | null = null
      if (!await act(go_, async () => { made = await backend.exportBackup(account, pw.value) })) return
      const { file, counts } = made!
      const url = URL.createObjectURL(new Blob([file], { type: 'application/json' }))
      const a = h('a', { href: url, download: `vyzo-sauvegarde-${new Date().toISOString().slice(0, 10)}.json` })
      document.body.appendChild(a)
      a.click()
      a.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 30_000)
      dialog.close()
      toast(`Sauvegarde téléchargée : ${countsText(counts)}`)
    },
  },
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('download', 20)),
      h('div', {}, h('h2', {}, 'Sauvegarde du compte'), h('p', { class: 'dialog-sub' }, 'Un fichier chiffré, à garder en lieu sûr.'))),
    h('div', { class: 'two' },
      h('div', {}, h('label', { for: 'bk-pw' }, 'Mot de passe du fichier'), pw),
      h('div', {}, h('label', { for: 'bk-again' }, 'Confirmer'), again)),
    h('p', { class: 'fine' }, 'Il contient tes identifiants d’abonnements IPTV : sans ce mot de passe, personne ne peut le lire, pas même toi.'),
    error,
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler'), go_))
  const dialog = openDialog(form)
  pw.focus()
}

function countsText(c: BackupCounts): string {
  return [plural(c.profiles, 'profil', 'profils'), plural(c.lists + c.items, 'élément de liste', 'éléments de listes'),
    plural(c.progress, 'titre commencé', 'titres commencés'), plural(c.sealed, 'réglage chiffré', 'réglages chiffrés')].join(', ')
}

function restoreDialog() {
  if (state.vault !== 'open') return unlockDialog()
  const account = state.account!
  const file = h('input', { id: 'rs-file', type: 'file', accept: '.json,application/json', required: true })
  const pw = h('input', { id: 'rs-pw', type: 'password', autocomplete: 'off', required: true })
  const error = h('p', { class: 'form-error', role: 'alert' })
  const go_: HTMLButtonElement = h('button', { class: 'btn primary', type: 'submit' }, 'Ouvrir')
  const form = h('form', {
    onsubmit: async (e: Event) => {
      e.preventDefault()
      error.textContent = ''
      const f = file.files?.[0]
      if (!f) return file.focus()
      if (f.size > 50 * 1024 * 1024) { error.textContent = 'Fichier trop gros pour une sauvegarde Vyzo.'; return }
      go_.setAttribute('aria-busy', 'true')
      go_.disabled = true
      let data: BackupData | null = null
      try {
        data = await backend.readBackup(await f.text(), pw.value)
        if (!data) { error.textContent = 'Mot de passe incorrect pour ce fichier.'; return pw.focus() }
      } catch (err) {
        error.textContent = err instanceof UserError ? err.message : friendlyError(err)
        return
      } finally {
        go_.removeAttribute('aria-busy')
        go_.disabled = false
      }
      dialog.close()
      const ok = await confirmDialog({
        title: 'Restaurer cette sauvegarde ?',
        body: `Sauvegarde du ${data.createdAt ? fullFmt.format(data.createdAt) : 'jour inconnu'} : ${countsText(backupCountsOf(data))}. ` +
          'Ce qu’elle contient remplace la version actuelle sur tous tes appareils ; ce qui a été ajouté depuis est gardé.',
        confirm: 'Restaurer',
      })
      if (!ok) return
      const restored = data
      if (await act(null, () => backend.restoreBackup(account, restored).then(() => undefined), 'Sauvegarde restaurée')) {
        state = { ...emptyState(), account, playback: state.playback }
        await refresh(['profiles', 'activity', 'recordings', 'devices', 'playlists'])
        ensureRouteData()
      }
    },
  },
    h('div', { class: 'dialog-head' }, h('span', { class: 'kind-badge big' }, icon('upload', 20)),
      h('div', {}, h('h2', {}, 'Restaurer une sauvegarde'), h('p', { class: 'dialog-sub' }, 'Tu pourras vérifier son contenu avant.'))),
    h('label', { for: 'rs-file' }, 'Fichier de sauvegarde'), file,
    h('label', { for: 'rs-pw' }, 'Mot de passe du fichier'), pw,
    error,
    h('div', { class: 'dialog-actions' }, h('span', { class: 'spacer' }),
      h('button', { class: 'btn ghost', type: 'button', onclick: () => dialog.close() }, 'Annuler'), go_))
  const dialog = openDialog(form, 'small')
}

const backupCountsOf = (d: BackupData): BackupCounts => ({
  profiles: d.profiles.length, lists: d.lists.length, items: d.items.length, progress: d.progress.length,
  recordings: d.recordings.length, sealed: d.sealed.length,
})

// ------------------------------------------------------------------ Installation

interface InstallPromptEvent extends Event { prompt(): Promise<void>; userChoice: Promise<{ outcome: string }> }

let installPrompt: InstallPromptEvent | null = null

window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault()
  installPrompt = e as InstallPromptEvent
  if (route() === 'securite') renderView()
})
window.addEventListener('appinstalled', () => {
  installPrompt = null
  toast('Site installé')
  if (route() === 'securite') renderView()
})

async function install() {
  const p = installPrompt
  if (!p) return
  await p.prompt()
  const choice = await p.userChoice.catch(() => ({ outcome: 'dismissed' }))
  if (choice.outcome === 'accepted') installPrompt = null
  renderView()
}

// La page publiée s'ouvre aussi hors ligne une fois installée (le service worker ne touche pas aux données du compte).
if (import.meta.env.PROD && 'serviceWorker' in navigator && !backend.demo) {
  window.addEventListener('load', () => { void navigator.serviceWorker.register('sw.js').catch(() => undefined) })
}

// ------------------------------------------------------------------ Démarrage

async function start() {
  // Lien reçu par e-mail : ses paramètres sont retirés de l'adresse (historique, favoris) avant tout.
  const link = parseEmailLink(location.search, location.hash)
  if (link) {
    history.replaceState(null, '', location.pathname + (backend.demo ? '?demo' : ''))
    await openEmailLink(link)
    return
  }
  try {
    const account = await backend.currentAccount()
    if (account) await enter(account)
    else renderLogin()
  } catch (e) {
    renderLogin(friendlyError(e))
  }
}

void start()
