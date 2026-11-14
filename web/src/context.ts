import {
  friendlyError, SupabaseBackend,
  type Account, type Backend, type LiveMessage, type Table, type VaultStatus,
} from './backend'
import { DemoBackend } from './demo'
import {
  sortProfiles, updateInProgress,
  type AccountEvent, type CatalogEntry, type Device, type EpgSource, type LatestVersion, type Parental, type PlaybackState,
  type Playlist, type Profile, type ProfileActivity, type ProgressItem, type Recording, type ScreenTimeRule, type SealedPrefs,
  type SyncedSettings, type UsageDay,
} from './model'
import { type ListsData } from './backend'
import { applyTheme, type Theme } from './theme'
import { toast } from './ui'

const demoRequested = import.meta.env.DEV && new URLSearchParams(location.search).has('demo')
export const backend: Backend = demoRequested ? new DemoBackend() : new SupabaseBackend()

export type Route = 'accueil' | 'appareils' | 'abonnements' | 'enregistrements' | 'profils' | 'historique' | 'listes' | 'statistiques'
  | 'parental' | 'reglages' | 'securite' | 'journal'

export interface Page { id: Route; label: string; icon: string; group: string | null; lead: string | null }

export const PAGES: Page[] = [
  { id: 'accueil', label: 'Accueil', icon: 'home', group: null, lead: null },
  { id: 'appareils', label: 'Appareils', icon: 'devices', group: 'Maison', lead: 'Tout ce qui est connecté à ton compte.' },
  { id: 'abonnements', label: 'Abonnements', icon: 'key', group: 'Maison', lead: 'Playlists IPTV, sources de guide TV et préférences de chaînes chiffrées.' },
  { id: 'enregistrements', label: 'Enregistrements', icon: 'record', group: 'Maison', lead: 'Programmés depuis le guide. Vyzo PC enregistre.' },
  { id: 'profils', label: 'Profils', icon: 'users', group: 'Profils', lead: 'Les profils « Qui regarde ? » de la maison.' },
  { id: 'historique', label: 'Historique', icon: 'history', group: 'Profils', lead: 'Ce que chaque profil a commencé.' },
  { id: 'listes', label: 'Listes', icon: 'list', group: 'Profils', lead: 'Favoris et listes de chaque profil.' },
  { id: 'statistiques', label: 'Statistiques', icon: 'chart', group: 'Profils', lead: 'Le temps passé devant Vyzo, profil par profil.' },
  { id: 'parental', label: 'Contrôle parental', icon: 'lock', group: 'Profils', lead: 'Code, catégories verrouillées et temps d’écran pour toute la maison.' },
  { id: 'reglages', label: 'Réglages', icon: 'sliders', group: 'Compte', lead: 'Réglages de lecture, EPG, clés API et services partagés sur tous tes appareils.' },
  { id: 'securite', label: 'Sécurité', icon: 'shield', group: 'Compte', lead: 'Mot de passe, sessions, sauvegarde et clé de chiffrement.' },
  { id: 'journal', label: 'Journal', icon: 'journal', group: 'Compte', lead: 'Ce qui s’est passé sur le compte ces six derniers mois.' },
]

export const page = (id: Route) => PAGES.find((p) => p.id === id)!

/** Données chargées séparément : une erreur sur l'une (table absente du serveur…) n'empêche pas d'afficher les autres. */
export type DataKey = 'profiles' | 'activity' | 'recordings' | 'devices' | 'playlists' | 'progress' | 'lists' | 'parental' | 'catalog'
  | 'screentime' | 'usage' | 'events' | 'epg' | 'apiKeys' | 'syncedSettings'

export type RealtimeStatus = 'connecting' | 'connected' | 'offline'

export interface State {
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
  /** Sources XMLTV manuelles chiffrées. */
  epg: EpgSource[] | null
  /** Clés API personnelles chiffrées (tmdb, omdb, opensubtitles, github, trakt_id, trakt_secret). */
  apiKeys: Record<string, string> | null
  /** Réglages synchronisés (SealedSettings) ; undefined = pas encore lu, null = aucun appareil ne les a encore envoyés. */
  syncedSettings: SyncedSettings | null | undefined
  /** Profil choisi dans Accueil, Historique et Listes. */
  selected: string | null
  progress: { profileId: string; items: ProgressItem[] } | null
  lists: ({ profileId: string } & ListsData) | null
  /** undefined : pas encore lu ; null : pas de contrôle parental commun. */
  parental: Parental | null | undefined
  /** Abonnement choisi dans Contrôle parental ou Préférences, et ses catégories. */
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

export const emptyState = (): State => ({
  account: null, vault: null, remembered: false, profiles: null, activity: null, recordings: null, devices: null, latest: null,
  playlists: null, epg: null, apiKeys: null, syncedSettings: undefined, selected: null, progress: null, lists: null,
  parental: undefined, catalogFor: null, catalog: null, playback: new Map(), screentime: null, usage: null, events: null, errors: {},
})

export let state: State = emptyState()
export let realtimeStatus: RealtimeStatus = 'connecting'
let stopWatching: (() => void) | null = null

export interface ShellHandlers {
  renderView: (animate?: boolean) => void
  renderWho: () => void
  renderShell: () => void
  renderLogin: (message?: string, prefill?: string) => void
}

let handlers: ShellHandlers = {
  renderView: () => {},
  renderWho: () => {},
  renderShell: () => {},
  renderLogin: () => {},
}

export function registerShellHandlers(h: ShellHandlers) {
  handlers = h
}

export const renderView = (animate = false) => handlers.renderView(animate)
export const renderWho = () => handlers.renderWho()
export const renderLogin = (message?: string, prefill = '') => handlers.renderLogin(message, prefill)

export function setRealtimeStatus(status: RealtimeStatus) {
  if (realtimeStatus === status) return
  realtimeStatus = status
  renderWho()
}

export function resetStateForRestore(account: Account) {
  state = { ...emptyState(), account, playback: state.playback }
}

export function route(): Route {
  const id = location.hash.replace(/^#\/?/, '') as Route
  return PAGES.some((r) => r.id === id) ? id : 'accueil'
}

export const go = (r: Route) => { location.hash = `#/${r}` }

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
  async epg() {
    if (state.vault === 'open') state.epg = await backend.epgSources(state.account!)
  },
  async apiKeys() {
    if (state.vault === 'open') state.apiKeys = await backend.apiKeys(state.account!)
  },
  async syncedSettings() {
    if (state.vault === 'open') state.syncedSettings = await backend.syncedSettings(state.account!)
  },
}

/** Recharge les données demandées ; les profils d'abord, l'activité en dépend. */
export async function refresh(keys: Iterable<DataKey>) {
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
  const loaded = (k: DataKey): DataKey[] => (state[k as 'progress' | 'lists' | 'catalog' | 'screentime' | 'usage' | 'events' | 'epg' | 'apiKeys'] ? [k] : [])
  // Le journal n'est pas diffusé en temps réel : il se relit quand une table qui l'alimente change.
  const events = loaded('events')
  switch (table) {
    case 'profiles': return ['profiles', 'activity', ...events]
    case 'watch_progress': return ['activity', ...loaded('progress')]
    case 'list_items': case 'user_lists': return ['activity', ...loaded('lists')]
    case 'recordings': return ['recordings', ...events]
    case 'devices': return ['devices', ...events]
    case 'sealed_items': return ['playlists', ...(state.parental !== undefined ? ['parental' as const] : []), ...loaded('catalog'),
      ...loaded('screentime'), ...loaded('usage'), ...loaded('epg'), ...loaded('apiKeys'),
      ...(state.syncedSettings !== undefined ? ['syncedSettings' as const] : []), ...events]
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
export function ensureRouteData() {
  const r = route()
  if (r === 'accueil' && state.vault === 'open') {
    if (!state.screentime) void refresh(['screentime'])
    if (!state.usage) void refresh(['usage'])
  }
  if (r === 'abonnements' && state.vault === 'open' && !state.epg) void refresh(['epg'])
  if (r === 'reglages' && state.vault === 'open') {
    if (state.syncedSettings === undefined) void refresh(['syncedSettings'])
    if (!state.apiKeys) void refresh(['apiKeys'])
  }
  if (r === 'parental' && state.vault === 'open') {
    if (state.parental === undefined) void refresh(['parental'])
    if (!state.catalog && state.playlists?.length) void refresh(['catalog'])
    if (!state.screentime) void refresh(['screentime'])
  }
  if (r === 'statistiques' && state.vault === 'open' && !state.usage) void refresh(['usage'])
  if (r === 'securite' && !state.events) void refresh(['events'])
  if (r === 'journal') void refresh(['events'])
  if (r === 'appareils' || r === 'accueil') askPlayback()
  if (!state.selected) return
  if ((r === 'historique' || r === 'accueil') && state.progress?.profileId !== state.selected) void refresh(['progress'])
  if (r === 'listes' && state.lists?.profileId !== state.selected) void refresh(['lists'])
}

export async function enter(account: Account) {
  state = { ...emptyState(), account }
  realtimeStatus = 'connecting'
  stopWatching?.()
  const stopRemote = backend.watch(account, onRemoteChange, setRealtimeStatus)
  const stopLive = backend.onLive(onLive)
  stopWatching = () => { stopRemote(); stopLive() }
  handlers.renderShell()
  await refresh(['profiles', 'activity', 'recordings', 'devices', 'playlists'])
  ensureRouteData()
}

export async function signOut() {
  await flushDeletes()
  stopWatching?.()
  stopWatching = null
  await backend.signOut()
  state = emptyState()
  realtimeStatus = 'connecting'
  renderLogin()
}

// ------------------------------------------------------------------ Réponses des appareils

/** Demandes en attente d'un accusé de l'appareil (message, vérification) : identifiant → suite. */
const awaiting = new Map<string, { device: Device; timer: number; onAck: (action: string) => void }>()

export function expectAck(req: string, device: Device, onAck: (action: string) => void, silent: string) {
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
      playbackRender = window.setTimeout(() => renderView(), 250)
    })
  }
}

let lastPlaybackAsk = 0

/** Demande aux appareils connectés ce qu'ils lisent (au plus toutes les 20 s). */
export function askPlayback(force = false) {
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

const UNDO_MS = 6000
const hidden = new Set<string>()
const pendingDeletes = new Map<string, { timer: number; commit: () => Promise<void>; after: DataKey[] }>()

export function softDelete(key: string, message: string, commit: () => Promise<void>, after: DataKey[]) {
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

export function undoDelete(key: string) {
  const p = pendingDeletes.get(key)
  if (!p) return
  clearTimeout(p.timer)
  pendingDeletes.delete(key)
  hidden.delete(key)
  renderView()
  toast('Rétabli')
}

/** Envoie tout de suite ce qui attend (déconnexion, verrouillage). */
export const flushDeletes = () => Promise.all([...pendingDeletes.keys()].map(commitDelete))

window.addEventListener('beforeunload', (e) => {
  if (pendingDeletes.size > 0) e.preventDefault()
})

export const isHidden = (key: string) => hidden.has(key)

// ------------------------------------------------------------------ Thème et installation PWA

export function setTheme(t: Theme) {
  applyTheme(t, true)
  for (const b of document.querySelectorAll<HTMLElement>('[data-theme-choice]')) {
    const on = String(b.dataset.themeChoice === t)
    b.setAttribute(b.getAttribute('role') === 'radio' ? 'aria-checked' : 'aria-pressed', on)
  }
}

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

export const canInstallApp = () => !!installPrompt

export async function installApp() {
  const p = installPrompt
  if (!p) return
  await p.prompt()
  const choice = await p.userChoice.catch(() => ({ outcome: 'dismissed' }))
  if (choice.outcome === 'accepted') installPrompt = null
  renderView()
}
