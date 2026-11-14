/**
 * Données du compte Vyzo telles qu'elles sont rangées dans Supabase, et règles communes avec l'appli
 * (shared/…/data/profile/ProfileManager.kt, data/cloud/CloudSync.kt). Aucune dépendance au navigateur : testé par Vitest.
 */

export interface Profile {
  id: string
  name: string
  colorIndex: number
  isKid: boolean
  pinHash: string
  avatar: string
  position: number
  updatedAt: number
}

export type RecordingStatus = 'SCHEDULED' | 'RECORDING' | 'DONE' | 'FAILED' | 'CANCELLED'

export interface Recording {
  id: string
  channelKey: string
  channelName: string
  title: string
  startMs: number
  stopMs: number
  status: RecordingStatus
  error: string
  sender: string
  updatedAt: number
}

/** Ce que le compte sait de l'activité d'un profil (les titres restent sur les appareils). */
export interface ProfileActivity {
  profileId: string
  inProgress: number
  inLists: number
  lastWatchedAt: number | null
}

export type DeviceKind = 'tv' | 'mobile' | 'pc' | ''

/** Une installation de Vyzo connectée au compte (table devices). */
export interface Device {
  id: string
  name: string
  /** Nom choisi sur le site (vide : celui du système). */
  label: string
  kind: DeviceKind
  model: string
  system: string
  appVersion: string
  versionCode: number
  lastSeenAt: number
  updateRequestedAt: number
  updateStatus: string
  updateStatusAt: number
}

/** Dernière version publiée (latest.json). */
export interface LatestVersion { versionCode: number; versionName: string }

export type ContentKind = 'LIVE' | 'MOVIE' | 'SERIES'

/** Abonnement IPTV déchiffré ; [id] = PlaylistKeys.of, [updatedAt] = version sur le serveur. */
export interface Playlist {
  id: string
  name: string
  type: 'XTREAM' | 'M3U' | 'STALKER'
  url: string
  username: string
  password: string
  mac: string
  userAgent: string
  updatedAt: number
  hasPrefs: boolean
  /** État chez le fournisseur, envoyé par un appareil (1.13.0-beta2 et plus) ; null tant qu'aucun ne l'a vérifié. */
  status: PlaylistStatus | null
}

/** Ligne chiffrée « status » (SealedStatus dans l'appli). */
export interface PlaylistStatus {
  expiresAt: number
  maxConnections: number
  status: string
  /** Jour de la dernière vérification (minuit UTC). */
  checkedDay: number
}

export interface UserList {
  profileId: string
  id: string
  name: string
  position: number
  updatedAt: number
}

export interface ListItem {
  profileId: string
  listId: string
  contentKey: string
  kind: ContentKind
  position: number
  addedAt: number
  updatedAt: number
  title: string
  subtitle: string
}

export interface ProgressItem {
  profileId: string
  contentKey: string
  kind: ContentKind
  positionMs: number
  durationMs: number
  updatedAt: number
  title: string
  subtitle: string
}

/** Identifiant de la liste des favoris (FAVORITES_SYNC_ID). */
export const FAVORITES_LIST_ID = 'fav'

/** Part déjà regardée, entre 0 et 1. */
export function progressRatio(p: Pick<ProgressItem, 'positionMs' | 'durationMs'>): number {
  if (p.durationMs <= 0) return 0
  return Math.min(1, Math.max(0, p.positionMs / p.durationMs))
}

/** Un appareil qui s'est signalé il y a moins de 15 minutes est considéré comme allumé. */
export const ONLINE_MS = 15 * 60_000

export function isOnline(d: Device, now: number = Date.now()): boolean {
  return now - d.lastSeenAt < ONLINE_MS
}

export function needsUpdate(d: Device, latest: LatestVersion | null): boolean {
  return !!latest && d.versionCode > 0 && d.versionCode < latest.versionCode
}

/** Une demande de mise à jour encore sans réponse de l'appareil. */
export function updatePending(d: Device): boolean {
  return d.updateRequestedAt > 0 && d.updateStatusAt < d.updateRequestedAt
}

/**
 * Mise à jour pas encore terminée : demande sans réponse, ou étape intermédiaire (« Téléchargement… ») récente.
 * Au-delà de 10 minutes sans nouvelle, l'appareil a été éteint ou l'appli fermée en route.
 */
export function updateInProgress(d: Device, now: number = Date.now()): boolean {
  return updatePending(d) || (d.updateStatus.endsWith('…') && now - d.updateStatusAt < 10 * 60_000)
}

/** Mêmes couleurs que ProfilePalette (ui/profile/ProfileScreens.kt). */
export const PROFILE_PALETTE = [
  '#FFBE0B', '#22D9F2', '#A27BFF', '#FF3D8B', '#3DDC84', '#FF8A3D', '#4D8BFF', '#FF7AC6',
  '#F97316', '#14B8A6', '#EAB308', '#EC4899',
] as const

export const MAX_PROFILE_NAME = 24
export const MAX_PROFILE_AVATAR = 40

/** Identifiant d'expéditeur des modifications faites depuis le site. */
export const WEB_SENDER = 'Vyzo Web'

export function profileColor(index: number): string {
  const n = PROFILE_PALETTE.length
  return PROFILE_PALETTE[((index % n) + n) % n]
}

/** Comme ProfileManager.cleanName. */
export function cleanName(name: string): string {
  const clean = Array.from(name.trim().replace(/\s+/g, ' ')).slice(0, MAX_PROFILE_NAME).join('')
  return clean || 'Profil'
}

/** Comme ProfileManager.cleanAvatar. */
export function cleanAvatar(avatar: string): string {
  const clean = avatar.trim().toLowerCase().slice(0, MAX_PROFILE_AVATAR)
  return clean === 'initial' ? '' : clean
}

/** Comme ProfileManager.nextColor : première couleur libre, sinon on recommence le tour. */
export function nextColor(existing: Profile[]): number {
  const used = new Set(existing.map((p) => p.colorIndex))
  for (let i = 0; i < PROFILE_PALETTE.length; i++) if (!used.has(i)) return i
  return existing.length % PROFILE_PALETTE.length
}

export function sortProfiles(list: Profile[]): Profile[] {
  return [...list].sort((a, b) => a.position - b.position || a.name.localeCompare(b.name, 'fr'))
}

/**
 * Date de modification à envoyer. Le serveur garde la ligne la plus récente (vyzo_sync_guard) : une horloge de PC en
 * retard ferait ignorer la modification, d'où le +1 sur la version connue.
 */
export function nextUpdatedAt(previous: number, now: number = Date.now()): number {
  return Math.max(now, previous + 1)
}

export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')
}

/** Comme ProfileManager.hashPin. */
export function hashPin(profileId: string, pin: string): Promise<string> {
  return sha256Hex(`vyzo-pin:${profileId}:${pin}`)
}

export function isValidPin(pin: string): boolean {
  return /^\d{4}$/.test(pin)
}

function moveOrdered<T extends { id: string; position: number; name: string }>(
  list: T[], id: string, delta: -1 | 1, start = 0,
): T[] {
  const sorted = [...list].sort((a, b) => a.position - b.position || a.name.localeCompare(b.name, 'fr'))
  const i = sorted.findIndex((x) => x.id === id)
  const j = i + delta
  if (i < 0 || j < 0 || j >= sorted.length) return []
  const reordered = [...sorted]
  ;[reordered[i], reordered[j]] = [reordered[j], reordered[i]]
  return reordered
    .map((x, index) => ({ ...x, position: index + start }))
    .filter((x) => x.position !== sorted.find((o) => o.id === x.id)!.position)
}

/** Déplace un profil d'un cran : renvoie les profils dont la position change. */
export const moveProfile = (list: Profile[], id: string, delta: -1 | 1): Profile[] => moveOrdered(list, id, delta, 0)

export function sortUserLists(list: UserList[]): UserList[] {
  return [...list].sort((a, b) => a.position - b.position || a.name.localeCompare(b.name, 'fr'))
}

/** Déplace une liste personnalisée d'un cran : positions >= 1 (Favoris garde -1). */
export const moveUserList = (list: UserList[], id: string, delta: -1 | 1): UserList[] => moveOrdered(list, id, delta, 1)

export type RecordingGroup = 'upcoming' | 'done' | 'other'

/** À venir ou en cours / terminés / annulés ou en échec. */
export function recordingGroup(r: Recording, now: number = Date.now()): RecordingGroup {
  if (r.status === 'RECORDING') return 'upcoming'
  if (r.status === 'SCHEDULED') return r.stopMs > now ? 'upcoming' : 'other'
  if (r.status === 'DONE') return 'done'
  return 'other'
}

export function sortRecordings(list: Recording[], group: RecordingGroup): Recording[] {
  // À venir : le plus proche d'abord. Le reste : le plus récent d'abord.
  return [...list].sort((a, b) => (group === 'upcoming' ? a.startMs - b.startMs : b.startMs - a.startMs))
}

/** Une programmation passée sans avoir démarré n'a jamais été prise en charge (PC éteint). */
export function statusLabel(r: Recording, now: number = Date.now()): string {
  switch (r.status) {
    case 'SCHEDULED':
      return r.stopMs > now ? (r.startMs > now ? 'Programmé' : 'Doit commencer') : 'Non enregistré'
    case 'RECORDING':
      return 'En cours'
    case 'DONE':
      return 'Terminé'
    case 'FAILED':
      return 'Échec'
    case 'CANCELLED':
      return 'Annulé'
  }
}

// ------------------------------------------------------------------ État de l'abonnement

export type Tone = 'ok' | 'warn' | 'bad'

const expiryFmt = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })

/** Délai sous lequel une expiration proche est signalée. */
export const EXPIRY_WARN_DAYS = 14

const STATUS_LABEL: Record<string, string> = { expired: 'Expiré', banned: 'Suspendu', disabled: 'Désactivé' }

export function subscriptionState(s: PlaylistStatus | null, now: number = Date.now()): { tone: Tone; label: string; daysLeft: number | null } | null {
  if (!s) return null
  const status = s.status.trim().toLowerCase()
  if (status && status !== 'active') return { tone: 'bad', label: STATUS_LABEL[status] ?? s.status.trim(), daysLeft: null }
  if (s.expiresAt <= 0) return { tone: 'ok', label: 'Sans date de fin', daysLeft: null }
  const daysLeft = Math.ceil((s.expiresAt - now) / 86_400_000)
  if (daysLeft <= 0) return { tone: 'bad', label: 'Expiré', daysLeft: 0 }
  if (daysLeft <= EXPIRY_WARN_DAYS) return { tone: 'warn', label: daysLeft === 1 ? 'Expire demain' : `Expire dans ${daysLeft} jours`, daysLeft }
  return { tone: 'ok', label: `Jusqu’au ${expiryFmt.format(s.expiresAt)}`, daysLeft }
}

// ------------------------------------------------------------------ Lancer sur un appareil

/** Première version de l'appli qui sait ouvrir un titre envoyé par le site (1.13.0-beta2). */
export const CAST_MIN_VERSION = 40

/** itemType compris par CastCommand côté appli (castTarget dans DeepLink.kt). */
export function castItemType(kind: ContentKind, fromHistory: boolean): string {
  if (kind === 'LIVE') return 'content-channel'
  if (kind === 'MOVIE') return 'content-movie'
  // Dans l'historique, une ligne « série » est l'épisode en cours ; dans une liste, c'est la série.
  return fromHistory ? 'content-episode' : 'content-series'
}

export type CastReadiness = 'ready' | 'offline' | 'outdated'

export function castReadiness(d: Device, now: number = Date.now()): CastReadiness {
  if (d.versionCode < CAST_MIN_VERSION) return 'outdated'
  return isOnline(d, now) ? 'ready' : 'offline'
}

// ------------------------------------------------------------------ Contrôle parental

/** Ligne chiffrée « parental » (SealedParental) : code et options communs à tous les appareils. */
export interface Parental { pinHash: string; autoAdult: boolean; kidExit: boolean }

/** Même empreinte que ParentalControl.hashParental dans l'appli. */
export function hashParental(pin: string): Promise<string> {
  return sha256Hex(`vyzo-parental:${pin}`)
}

export interface CatalogEntry { rest: string; name: string; kind: ContentKind; adult: boolean }

/** Ligne chiffrée « prefs » (SealedPrefs), dans l'ordre des champs de l'appli pour que le texte reste identique. */
export interface SealedCategoryPref { rest: string; hidden: boolean; position: number | null; locked: boolean | null; name: string }
export interface SealedChannelPref { rest: string; name: string; number: number; epgKey: string; locked: boolean; hidden: boolean }
export interface SealedPrefs { categories: SealedCategoryPref[]; channels: SealedChannelPref[] }

export function parseSealedPrefs(text: string): SealedPrefs {
  const o = JSON.parse(text) as { categories?: Partial<SealedCategoryPref>[]; channels?: Partial<SealedChannelPref>[] }
  const categories: SealedCategoryPref[] = Array.isArray(o.categories)
    ? o.categories.filter((c) => c && c.rest).map((c) => ({
      rest: String(c.rest), hidden: c.hidden === true, position: typeof c.position === 'number' ? c.position : null,
      locked: typeof c.locked === 'boolean' ? c.locked : null, name: String(c.name ?? ''),
    }))
    : []
  const channels: SealedChannelPref[] = Array.isArray(o.channels)
    ? o.channels.filter((c) => c && c.rest).map((c) => ({
      rest: String(c.rest), name: String(c.name ?? ''), number: Math.max(0, Math.round(Number(c.number) || 0)),
      epgKey: String(c.epgKey ?? ''), locked: c.locked === true, hidden: c.hidden === true,
    }))
    : []
  return { categories, channels }
}

export function prefsJson(prefs: SealedPrefs): string {
  return JSON.stringify({
    categories: prefs.categories.map((c) => ({
      rest: c.rest, hidden: c.hidden, position: c.position, locked: c.locked, name: c.name,
    })),
    channels: prefs.channels.map((c) => ({
      rest: c.rest, name: c.name, number: c.number, epgKey: c.epgKey, locked: c.locked, hidden: c.hidden,
    })),
  })
}

export const isDefaultCategoryPref = (c: SealedCategoryPref) => !c.hidden && c.position === null && c.locked === null && !c.name.trim()
export const isDefaultChannelPref = (c: SealedChannelPref) => !c.name.trim() && c.number === 0 && !c.epgKey.trim() && !c.locked && !c.hidden

/** Règle de l'appli : choix explicite, sinon détection « adulte » si elle est active. */
export function categoryLocked(entry: CatalogEntry, pref: SealedCategoryPref | undefined, autoAdult: boolean): boolean {
  return pref?.locked ?? (autoAdult && entry.adult)
}

const byRest = <T extends { rest: string }>(a: T, b: T) => (a.rest < b.rest ? -1 : a.rest > b.rest ? 1 : 0)

/** Préférences avec la catégorie [rest] mise à jour ; supprime l'entrée si revenue aux valeurs par défaut. */
export function withCategoryPref(
  prefs: SealedPrefs, rest: string, patch: Partial<Omit<SealedCategoryPref, 'rest'>>,
): SealedPrefs {
  const existing = prefs.categories.find((c) => c.rest === rest)
  const merged: SealedCategoryPref = {
    rest,
    hidden: patch.hidden ?? existing?.hidden ?? false,
    position: patch.position !== undefined ? patch.position : (existing?.position ?? null),
    locked: patch.locked !== undefined ? patch.locked : (existing?.locked ?? null),
    name: patch.name !== undefined ? patch.name.trim() : (existing?.name ?? ''),
  }
  const categories = prefs.categories.filter((c) => c.rest !== rest)
  if (!isDefaultCategoryPref(merged)) categories.push(merged)
  categories.sort(byRest)
  return { categories, channels: prefs.channels }
}

/** Préférences avec la catégorie [rest] verrouillée ou non ; le reste (masquage, ordre, nom, chaînes) est gardé. */
export function withCategoryLock(prefs: SealedPrefs, rest: string, locked: boolean): SealedPrefs {
  return withCategoryPref(prefs, rest, { locked })
}

/** Met à jour ou ajoute la personnalisation d'une chaîne ; retire l'entrée si revenue aux défauts. */
export function withChannelPref(
  prefs: SealedPrefs, rest: string, patch: Partial<Omit<SealedChannelPref, 'rest'>>,
): SealedPrefs {
  const cleanRest = rest.trim()
  const existing = prefs.channels.find((c) => c.rest === cleanRest)
  const merged: SealedChannelPref = {
    rest: cleanRest,
    name: patch.name !== undefined ? patch.name.trim() : (existing?.name ?? ''),
    number: patch.number !== undefined ? Math.max(0, Math.round(patch.number)) : (existing?.number ?? 0),
    epgKey: patch.epgKey !== undefined ? patch.epgKey.trim() : (existing?.epgKey ?? ''),
    locked: patch.locked ?? existing?.locked ?? false,
    hidden: patch.hidden ?? existing?.hidden ?? false,
  }
  const channels = prefs.channels.filter((c) => c.rest !== cleanRest)
  if (!isDefaultChannelPref(merged)) channels.push(merged)
  channels.sort(byRest)
  return { categories: prefs.categories, channels }
}

/** Retire la personnalisation d'une chaîne (retour aux valeurs de l'abonnement). */
export function removeChannelPref(prefs: SealedPrefs, rest: string): SealedPrefs {
  return { categories: prefs.categories, channels: prefs.channels.filter((c) => c.rest !== rest) }
}

/** Libellé lisible à partir de l'identifiant sans préfixe playlist (« C:... » ou clé brute). */
export function parseChannelRest(rest: string): string {
  const parts = rest.split(':')
  return parts.length > 1 ? parts.slice(1).join(':') : rest
}

// ------------------------------------------------------------------ Appareils (1.13.0-beta3)

export const MAX_DEVICE_LABEL = 60
export const MAX_MESSAGE = 300

/** Nom affiché : celui choisi sur le site, sinon celui du système. */
export const deviceName = (d: Pick<Device, 'name' | 'label'>) => d.label.trim() || d.name || 'Appareil'

/** Versions qui comprennent les commandes du site : message, vérification, lecture en cours, guide (1.13.0-beta3). */
export const REMOTE_MIN_VERSION = 41

export const supportsRemote = (d: Device) => d.versionCode >= REMOTE_MIN_VERSION

/** Ce qu'un appareil lit (message « playback_state », déchiffré). */
export interface PlaybackState {
  state: 'idle' | 'playing' | 'paused'
  title: string
  detail: string
  kind: string
  positionMs: number
  durationMs: number
  profile: string
  receivedAt: number
}

export function parsePlayback(text: string, receivedAt: number): PlaybackState | null {
  try {
    const o = JSON.parse(text) as Partial<PlaybackState>
    const state = o.state === 'playing' || o.state === 'paused' ? o.state : 'idle'
    return {
      state, title: String(o.title ?? ''), detail: String(o.detail ?? ''), kind: String(o.kind ?? ''),
      positionMs: Math.max(0, Number(o.positionMs) || 0), durationMs: Math.max(0, Number(o.durationMs) || 0),
      profile: String(o.profile ?? ''), receivedAt,
    }
  } catch {
    return null
  }
}

/** Position estimée maintenant : l'appareil n'envoie qu'aux changements. */
export function livePosition(p: PlaybackState, now: number = Date.now()): number {
  const pos = p.state === 'playing' ? p.positionMs + (now - p.receivedAt) : p.positionMs
  return p.durationMs > 0 ? Math.min(p.durationMs, pos) : pos
}

// ------------------------------------------------------------------ Guide (enregistrer depuis le site)

export interface GuideResult {
  channelKey: string
  channel: string
  title: string
  start: number
  stop: number
  desc: string
}

/** Même identifiant que CloudSync.recordingId : « clé de chaîne | début | fin ». */
export const recordingId = (g: Pick<GuideResult, 'channelKey' | 'start' | 'stop'>) => `${g.channelKey}|${g.start}|${g.stop}`

/** Résultats de plusieurs appareils : un seul exemplaire par programme, du plus proche au plus lointain. */
export function mergeGuide(lists: GuideResult[][], now: number = Date.now()): GuideResult[] {
  const seen = new Map<string, GuideResult>()
  for (const list of lists) {
    for (const g of list) {
      if (!g.channelKey || !(g.stop > now) || !(g.stop > g.start)) continue
      const id = recordingId(g)
      if (!seen.has(id)) seen.set(id, g)
    }
  }
  return [...seen.values()].sort((a, b) => a.start - b.start || a.channel.localeCompare(b.channel, 'fr'))
}

// ------------------------------------------------------------------ Temps d'écran

/** Règles d'un profil (ScreenTimeRule dans l'appli). Heures « HH:MM », vides = pas de plage ; 0 min = pas de limite. */
export interface ScreenTimeRule {
  enabled: boolean
  dailyMinutes: number
  from: string
  to: string
  weekendFrom: string
  weekendTo: string
}

export const EMPTY_RULE: ScreenTimeRule = { enabled: true, dailyMinutes: 0, from: '', to: '', weekendFrom: '', weekendTo: '' }

export function hhmm(text: string): number | null {
  const m = /^(\d{1,2}):(\d{2})$/.exec(text.trim())
  if (!m) return null
  const h = Number(m[1])
  const min = Number(m[2])
  return h <= 24 && min <= 59 && h * 60 + min <= 1440 ? h * 60 + min : null
}

/** Même ordre de champs que ScreenTimeRule (Kotlin), pour que l'appli renvoie le même texte. */
export const ruleJson = (r: ScreenTimeRule) => JSON.stringify({
  enabled: r.enabled, dailyMinutes: r.dailyMinutes, from: r.from, to: r.to, weekendFrom: r.weekendFrom, weekendTo: r.weekendTo,
})

export function parseRule(text: string): ScreenTimeRule {
  const o = JSON.parse(text) as Partial<ScreenTimeRule>
  return {
    enabled: o.enabled !== false, dailyMinutes: Math.max(0, Math.round(Number(o.dailyMinutes) || 0)),
    from: String(o.from ?? ''), to: String(o.to ?? ''), weekendFrom: String(o.weekendFrom ?? ''), weekendTo: String(o.weekendTo ?? ''),
  }
}

/** Message d'erreur, ou null si les règles sont cohérentes. */
export function checkRule(r: ScreenTimeRule): string | null {
  const pair = (f: string, t: string, what: string) => {
    if (!f && !t) return null
    if (hhmm(f) === null || hhmm(t) === null) return `Heures ${what} : écris-les sous la forme 07:30`
    if (hhmm(f) === hhmm(t)) return `Heures ${what} : le début et la fin sont identiques`
    return null
  }
  if (r.dailyMinutes < 0 || r.dailyMinutes > 1440) return 'Durée par jour : entre 0 et 24 h'
  return pair(r.from, r.to, 'de la semaine') ?? pair(r.weekendFrom, r.weekendTo, 'du week-end')
}

export function minutesLabel(min: number): string {
  if (min < 60) return `${min} min`
  const h = Math.floor(min / 60)
  const rest = min % 60
  return rest ? `${h} h ${String(rest).padStart(2, '0')}` : `${h} h`
}

/** Résumé lisible : « 1 h 30 par jour · 07:00 – 20:30 ». */
export function ruleSummary(r: ScreenTimeRule | null | undefined): string {
  if (!r || !r.enabled) return 'Aucune limite'
  const parts: string[] = []
  if (r.dailyMinutes > 0) parts.push(`${minutesLabel(r.dailyMinutes)} par jour`)
  if (r.from && r.to) parts.push(`${r.from} – ${r.to}`)
  if (r.weekendFrom && r.weekendTo) parts.push(`week-end ${r.weekendFrom} – ${r.weekendTo}`)
  return parts.length ? parts.join(' · ') : 'Aucune limite'
}

// ------------------------------------------------------------------ Statistiques

/** Ligne chiffrée « usage » (SealedUsage) : temps d'un profil, un jour, sur un appareil. */
export interface UsageDay {
  profile: string
  device: string
  day: string
  totalMs: number
  liveMs: number
  movieMs: number
  episodeMs: number
  otherMs: number
  items: { t: string; ms: number }[]
}

export function parseUsage(text: string): UsageDay | null {
  try {
    const o = JSON.parse(text) as Partial<UsageDay>
    if (!o.profile || !o.day) return null
    const n = (v: unknown) => Math.max(0, Number(v) || 0)
    return {
      profile: String(o.profile), device: String(o.device ?? ''), day: String(o.day), totalMs: n(o.totalMs),
      liveMs: n(o.liveMs), movieMs: n(o.movieMs), episodeMs: n(o.episodeMs), otherMs: n(o.otherMs),
      items: Array.isArray(o.items) ? o.items.map((i) => ({ t: String(i?.t ?? ''), ms: n(i?.ms) })).filter((i) => i.t) : [],
    }
  } catch {
    return null
  }
}

/** Jour local « aaaa-mm-jj », comme SealedSync.dayOf. */
export function dayKey(ms: number): string {
  const d = new Date(ms)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export interface DayKinds { live: number; movie: number; episode: number; other: number }

export interface UsageSummary {
  totalMs: number
  /** Un total par jour, du plus ancien au plus récent ([days] jours, aujourd'hui compris). */
  perDay: { day: string; ms: number }[]
  perDayKinds: Map<string, DayKinds>
  byKind: DayKinds
  top: { title: string; ms: number }[]
  byDevice: Map<string, number>
}

export function summarizeUsage(rows: UsageDay[], profileId: string | null, days: number, now: number = Date.now()): UsageSummary {
  const keys: string[] = []
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(now)
    d.setDate(d.getDate() - i)
    keys.push(dayKey(d.getTime()))
  }
  const inRange = new Set(keys)
  const perDay = new Map(keys.map((k) => [k, 0]))
  const perDayKinds = new Map(keys.map((k) => [k, { live: 0, movie: 0, episode: 0, other: 0 }]))
  const byKind = { live: 0, movie: 0, episode: 0, other: 0 }
  const titles = new Map<string, number>()
  const byDevice = new Map<string, number>()
  let totalMs = 0
  for (const r of rows) {
    if (!inRange.has(r.day) || (profileId && r.profile !== profileId)) continue
    totalMs += r.totalMs
    perDay.set(r.day, (perDay.get(r.day) ?? 0) + r.totalMs)
    const dk = perDayKinds.get(r.day)!
    dk.live += r.liveMs
    dk.movie += r.movieMs
    dk.episode += r.episodeMs
    dk.other += r.otherMs
    byKind.live += r.liveMs
    byKind.movie += r.movieMs
    byKind.episode += r.episodeMs
    byKind.other += r.otherMs
    byDevice.set(r.device, (byDevice.get(r.device) ?? 0) + r.totalMs)
    for (const i of r.items) titles.set(i.t, (titles.get(i.t) ?? 0) + i.ms)
  }
  const top = [...titles.entries()].map(([title, ms]) => ({ title, ms })).sort((a, b) => b.ms - a.ms).slice(0, 10)
  return { totalMs, perDay: keys.map((day) => ({ day, ms: perDay.get(day) ?? 0 })), perDayKinds, byKind, top, byDevice }
}

// ------------------------------------------------------------------ Journal du compte

export interface AccountEvent { id: number; at: number; kind: string; detail: string }

const EVENT_TEXT: Record<string, [string, string]> = {
  device_added: ['Nouvel appareil connecté', 'devices'],
  device_removed: ['Appareil retiré du compte', 'devices'],
  device_renamed: ['Appareil renommé', 'devices'],
  device_updated: ['Appareil mis à jour', 'download'],
  update_requested: ['Mise à jour demandée', 'download'],
  profile_added: ['Profil créé', 'user'],
  profile_removed: ['Profil supprimé', 'user'],
  profile_renamed: ['Profil renommé', 'user'],
  profile_pin_set: ['Code de profil ajouté ou changé', 'lock'],
  profile_pin_removed: ['Code de profil retiré', 'lock'],
  profile_kid_on: ['Profil passé en mode enfant', 'child'],
  profile_kid_off: ['Mode enfant retiré', 'child'],
  playlist_added: ['Abonnement IPTV ajouté', 'playlist'],
  playlist_removed: ['Abonnement IPTV retiré', 'playlist'],
  vault_reset: ['Clé des données chiffrées remplacée', 'key'],
  parental_changed: ['Contrôle parental modifié', 'shield'],
  screentime_changed: ['Temps d’écran modifié', 'clock'],
  recording_added: ['Enregistrement programmé', 'record'],
  site_signin: ['Connexion au site du compte', 'login'],
  password_changed: ['Mot de passe changé', 'key'],
  signout_others: ['Autres appareils déconnectés', 'logout'],
  backup_export: ['Sauvegarde du compte téléchargée', 'download'],
  backup_restore: ['Sauvegarde du compte restaurée', 'upload'],
  message_sent: ['Message envoyé à un appareil', 'message'],
}

export function eventText(e: Pick<AccountEvent, 'kind'>): { label: string; icon: string } {
  const t = EVENT_TEXT[e.kind]
  return t ? { label: t[0], icon: t[1] } : { label: 'Activité du compte', icon: 'info' }
}

/** Les événements qui touchent à la sécurité ressortent dans le journal. */
export const SENSITIVE_EVENTS = new Set(['device_added', 'password_changed', 'vault_reset', 'signout_others', 'backup_restore', 'playlist_removed'])

// ------------------------------------------------------------------ Sauvegarde

export const BACKUP_FORMAT = 'vyzo-backup'
export const BACKUP_VERSION = 1
/** Données chiffrées reprises dans une sauvegarde : ce que l'utilisateur a choisi, pas ce que les appareils recalculent. */
export const BACKUP_SEALED_KINDS = ['playlist', 'prefs', 'epg', 'settings', 'key', 'parental', 'screentime']

export interface BackupFile {
  format: string
  version: number
  createdAt: number
  salt: string
  iterations: number
  box: string
}

export interface BackupCounts { profiles: number; lists: number; items: number; progress: number; recordings: number; sealed: number }

// ------------------------------------------------------------------ Liens reçus par e-mail

export type EmailLinkType = 'signup' | 'recovery' | 'email_change'

/** Ce qu'apporte l'adresse de la page quand on arrive depuis un e-mail du compte. */
export type EmailLinkParams =
  | { mode: 'token'; type: EmailLinkType; tokenHash: string }
  | { mode: 'error'; code: string }

const LINK_TYPES: Record<string, EmailLinkType> = {
  signup: 'signup', email: 'signup', invite: 'signup', magiclink: 'signup', recovery: 'recovery', email_change: 'email_change',
}

/**
 * Lit les paramètres d'un lien d'e-mail. Seuls les liens des modèles Vyzo, « ?token_hash=…&type=… », ouvrent quelque
 * chose : ils sont vérifiés par le serveur. Une session toute faite après « # » (modèles de Supabase par défaut) est
 * refusée, car n'importe quel site pourrait en fabriquer une pour ouvrir son propre compte ici. Le texte d'erreur venu
 * de l'adresse n'est jamais affiché, seul son code compte. null : visite ordinaire.
 */
export function parseEmailLink(search: string, hash: string): EmailLinkParams | null {
  const q = new URLSearchParams(search.replace(/^\?/, ''))
  const f = new URLSearchParams(hash.replace(/^#\/?/, ''))
  const error = q.get('error_code') ?? q.get('error') ?? f.get('error_code') ?? f.get('error')
  if (error) return { mode: 'error', code: error }
  if (f.has('access_token') || f.has('refresh_token')) return { mode: 'error', code: 'session_in_url' }
  const tokenHash = q.get('token_hash')
  const type = LINK_TYPES[q.get('type') ?? '']
  if (tokenHash && type) return { mode: 'token', type, tokenHash }
  return null
}

/** Message fixe pour un lien refusé (le texte de l'adresse ou du serveur n'est pas repris). */
export function emailLinkError(code: string): string {
  if (code === 'otp_expired' || code === 'flow_state_expired') return 'Ce lien a expiré ou a déjà servi. Demande un nouvel e-mail ci-dessous.'
  if (code === 'session_in_url') return 'Ce lien ne peut pas être utilisé ici. Demande un nouvel e-mail ci-dessous.'
  return 'Lien invalide ou refusé par le serveur Vyzo. Demande un nouvel e-mail ci-dessous.'
}

/** Longueur minimale imposée par le serveur (Supabase Auth). */
export const MIN_PASSWORD = 6

// ------------------------------------------------------------------ Helpers Phase 2 (Xtream, temps d'écran, alertes)

/** Extrait serveur, identifiant et mot de passe d'un lien M3U/Xtream collé (get.php / player_api.php). */
export function parseXtreamUrl(raw: string): { url: string; username: string; password: string } | null {
  const trimmed = raw.trim()
  if (!trimmed || !/[?&]username=/.test(trimmed) || !/[?&]password=/.test(trimmed)) return null
  try {
    const u = new URL(/^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`)
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return null
    const username = (u.searchParams.get('username') ?? '').trim()
    const password = u.searchParams.get('password') ?? ''
    if (!username || !password) return null
    const path = u.pathname.replace(/\/(get|player_api|panel_api|xmltv)\.php$/i, '').replace(/\/+$/, '')
    return { url: `${u.origin}${path}`, username, password }
  } catch {
    return null
  }
}

export interface ScreenTimeToday {
  usedMs: number
  limitMs: number | null
  ratio: number | null
  remainingMs: number | null
  exceeded: boolean
  inWindow: boolean
  windowLabel: string | null
}

/** Utilisation du jour et état de la plage horaire pour un profil avec règle de temps d'écran. */
export function todayScreenTime(
  rows: UsageDay[], profileId: string, rule: ScreenTimeRule | null | undefined, now: number = Date.now(),
): ScreenTimeToday | null {
  if (!rule || !rule.enabled) return null
  const hasHours = Boolean((rule.from && rule.to) || (rule.weekendFrom && rule.weekendTo))
  if (rule.dailyMinutes <= 0 && !hasHours) return null

  const key = dayKey(now)
  const usedMs = rows.filter((r) => r.profile === profileId && r.day === key).reduce((s, r) => s + r.totalMs, 0)
  const limitMs = rule.dailyMinutes > 0 ? rule.dailyMinutes * 60_000 : null
  const ratio = limitMs ? Math.min(1, usedMs / limitMs) : null
  const remainingMs = limitMs !== null ? Math.max(0, limitMs - usedMs) : null

  const d = new Date(now)
  const weekend = d.getDay() === 0 || d.getDay() === 6
  const winFrom = weekend && rule.weekendFrom && rule.weekendTo ? rule.weekendFrom : rule.from
  const winTo = weekend && rule.weekendFrom && rule.weekendTo ? rule.weekendTo : rule.to
  const startM = winFrom && winTo ? hhmm(winFrom) : null
  const endM = winFrom && winTo ? hhmm(winTo) : null
  let inWindow = true
  if (startM !== null && endM !== null) {
    const cur = d.getHours() * 60 + d.getMinutes()
    inWindow = startM < endM ? cur >= startM && cur < endM : cur >= startM || cur < endM
  }

  return {
    usedMs,
    limitMs,
    ratio,
    remainingMs,
    exceeded: limitMs !== null && usedMs >= limitMs,
    inWindow,
    windowLabel: winFrom && winTo ? `${winFrom} – ${winTo}` : null,
  }
}

/** Retourne le prochain enregistrement imminent (< 2h) ou en cours s'il n'y a aucun Vyzo PC en ligne. */
export function upcomingRecordingAlert(recordings: Recording[], devices: Device[], now: number = Date.now()): Recording | null {
  if (devices.some((d) => d.kind === 'pc' && isOnline(d, now))) return null
  const soon = recordings
    .filter((r) => r.stopMs > now && (r.status === 'RECORDING' || (r.status === 'SCHEDULED' && r.startMs - now <= 2 * 3600_000)))
    .sort((a, b) => a.startMs - b.startMs)
  return soon[0] ?? null
}

/** Date de la dernière sauvegarde exportée (si présente dans les événements récents). */
export function lastBackupAt(events: AccountEvent[] | null | undefined): number | null {
  if (!events?.length) return null
  let latest = 0
  for (const e of events) if (e.kind === 'backup_export' && e.at > latest) latest = e.at
  return latest > 0 ? latest : null
}

// ------------------------------------------------------------------ Sources EPG (Phase 3)

export type EpgMode = 'FILL' | 'GAPS'

/** Source XMLTV ajoutée à la main (SealedEpg dans l'appli). */
export interface EpgSource {
  id: string
  name: string
  url: string
  enabled: boolean
  priority: number
  mode: EpgMode
  updatedAt: number
}

export type EpgDraft = Omit<EpgSource, 'id' | 'updatedAt'>

/** Comme VaultCrypto.sha256(url).take(24) dans SealedSync.kt. */
export async function epgSourceId(url: string): Promise<string> {
  return (await sha256Hex(url.trim())).slice(0, 24)
}

export function parseEpg(text: string): Omit<EpgSource, 'id' | 'updatedAt'> {
  const o = JSON.parse(text) as Partial<EpgSource>
  return {
    name: String(o.name ?? ''),
    url: String(o.url ?? ''),
    enabled: o.enabled !== false,
    priority: Math.max(0, Math.round(Number(o.priority) || 0)),
    mode: o.mode === 'GAPS' ? 'GAPS' : 'FILL',
  }
}

/** Ordre exact des champs de SealedEpg (Kotlin). */
export function epgJson(s: Pick<EpgSource, 'name' | 'url' | 'enabled' | 'priority' | 'mode'>): string {
  return JSON.stringify({ name: s.name, url: s.url, enabled: s.enabled, priority: s.priority, mode: s.mode })
}

export function sortEpgSources(list: EpgSource[]): EpgSource[] {
  return [...list].sort((a, b) => a.priority - b.priority || a.name.localeCompare(b.name, 'fr'))
}

/** Déplace une source EPG dans l'ordre de priorité et renvoie celles dont le rang change. */
export function moveEpgSource(list: EpgSource[], id: string, delta: -1 | 1): EpgSource[] {
  const sorted = sortEpgSources(list)
  const i = sorted.findIndex((x) => x.id === id)
  const j = i + delta
  if (i < 0 || j < 0 || j >= sorted.length) return []
  const reordered = [...sorted]
  ;[reordered[i], reordered[j]] = [reordered[j], reordered[i]]
  return reordered
    .map((x, index) => ({ ...x, priority: index }))
    .filter((x) => x.priority !== sorted.find((o) => o.id === x.id)!.priority)
}

// ------------------------------------------------------------------ Clés API personnelles (SealedKeys)

export interface ApiKeyDef {
  id: string
  label: string
  hint: string
  placeholder: string
  secret?: boolean
}

/** Identifiants exacts de SealedKeys dans SealedSync.kt. */
export const API_KEY_DEFS: ApiKeyDef[] = [
  { id: 'tmdb', label: 'TheMovieDB (TMDB)', hint: 'Clé ou jeton v3 pour enrichir affiches, résumés, notes et bandes-annonces.', placeholder: 'Clé API TMDB', secret: true },
  { id: 'omdb', label: 'OMDb API', hint: 'Ajoute les notes IMDb, Rotten Tomatoes et Metacritic sur les fiches.', placeholder: 'Clé OMDb', secret: true },
  { id: 'opensubtitles', label: 'OpenSubtitles.com', hint: 'Augmente le quota de recherche et téléchargement de sous-titres.', placeholder: 'Clé OpenSubtitles', secret: true },
  { id: 'trakt_id', label: 'Trakt · Client ID', hint: 'Identifiant d’application Trakt personnelle.', placeholder: 'Client ID Trakt', secret: false },
  { id: 'trakt_secret', label: 'Trakt · Client Secret', hint: 'Clé secrète associée au Client ID Trakt.', placeholder: 'Client Secret Trakt', secret: true },
  { id: 'github', label: 'GitHub Token', hint: 'Facultatif : uniquement si le dépôt de mises à jour est privé.', placeholder: 'ghp_…', secret: true },
]

// ------------------------------------------------------------------ Réglages synchronisés (SealedSettings)

export const DEFAULT_USER_AGENT = 'VLC/3.0.21 LibVLC/3.0.21'

export const AUDIO_LANGUAGES: readonly [string, string][] = [
  ['fr', 'Français'], ['en', 'Anglais'], ['de', 'Allemand'], ['es', 'Espagnol'], ['it', 'Italien'],
  ['pt', 'Portugais'], ['ar', 'Arabe'], ['tr', 'Turc'], ['nl', 'Néerlandais'], ['', 'Langue d’origine'],
]

export interface SyncedSettings {
  epgShiftMinutes: number
  epgDaysAhead: number
  epgDaysBehind: number
  xtreamFormat: 'ts' | 'm3u8'
  userAgent: string
  audioLang: string
  subtitleLang: string
  autoplayLast: boolean
  invertZap: boolean
  showNumbers: boolean
  recPaddingBeforeMin: number
  recPaddingAfterMin: number
  reminderMinutesBefore: number
  showAllPlaylists: boolean
  continuePlaybackInBackground: boolean
  openSubtitlesApiKey: string
  tmdbApiKey: string
  timeshiftMinutes: number
  expiryWarnDays: number
  autoSkipIntro: boolean
  autoNextEpisodeOnOutro: boolean
  autoSkipRecap: boolean
}

export const DEFAULT_SYNCED_SETTINGS: SyncedSettings = {
  epgShiftMinutes: 0,
  epgDaysAhead: 3,
  epgDaysBehind: 3,
  xtreamFormat: 'ts',
  userAgent: DEFAULT_USER_AGENT,
  audioLang: 'fr',
  subtitleLang: '',
  autoplayLast: false,
  invertZap: false,
  showNumbers: true,
  recPaddingBeforeMin: 1,
  recPaddingAfterMin: 5,
  reminderMinutesBefore: 1,
  showAllPlaylists: false,
  continuePlaybackInBackground: true,
  openSubtitlesApiKey: '',
  tmdbApiKey: '',
  timeshiftMinutes: 30,
  expiryWarnDays: 7,
  autoSkipIntro: false,
  autoNextEpisodeOnOutro: false,
  autoSkipRecap: false,
}

export function parseSyncedSettings(text: string): SyncedSettings {
  const o = JSON.parse(text) as Partial<SyncedSettings>
  const num = (v: unknown, def: number, min: number, max: number) => {
    const n = Number(v)
    return Number.isFinite(n) ? Math.min(max, Math.max(min, Math.round(n))) : def
  }
  const bool = (v: unknown, def: boolean) => (typeof v === 'boolean' ? v : def)
  return {
    epgShiftMinutes: num(o.epgShiftMinutes, DEFAULT_SYNCED_SETTINGS.epgShiftMinutes, -720, 720),
    epgDaysAhead: num(o.epgDaysAhead, DEFAULT_SYNCED_SETTINGS.epgDaysAhead, 1, 7),
    epgDaysBehind: num(o.epgDaysBehind, DEFAULT_SYNCED_SETTINGS.epgDaysBehind, 0, 7),
    xtreamFormat: o.xtreamFormat === 'm3u8' ? 'm3u8' : 'ts',
    userAgent: typeof o.userAgent === 'string' && o.userAgent.trim() ? o.userAgent.trim() : DEFAULT_USER_AGENT,
    audioLang: typeof o.audioLang === 'string' ? o.audioLang : DEFAULT_SYNCED_SETTINGS.audioLang,
    subtitleLang: typeof o.subtitleLang === 'string' ? o.subtitleLang : DEFAULT_SYNCED_SETTINGS.subtitleLang,
    autoplayLast: bool(o.autoplayLast, DEFAULT_SYNCED_SETTINGS.autoplayLast),
    invertZap: bool(o.invertZap, DEFAULT_SYNCED_SETTINGS.invertZap),
    showNumbers: bool(o.showNumbers, DEFAULT_SYNCED_SETTINGS.showNumbers),
    recPaddingBeforeMin: num(o.recPaddingBeforeMin, DEFAULT_SYNCED_SETTINGS.recPaddingBeforeMin, 0, 30),
    recPaddingAfterMin: num(o.recPaddingAfterMin, DEFAULT_SYNCED_SETTINGS.recPaddingAfterMin, 0, 60),
    reminderMinutesBefore: num(o.reminderMinutesBefore, DEFAULT_SYNCED_SETTINGS.reminderMinutesBefore, 0, 30),
    showAllPlaylists: bool(o.showAllPlaylists, DEFAULT_SYNCED_SETTINGS.showAllPlaylists),
    continuePlaybackInBackground: bool(o.continuePlaybackInBackground, DEFAULT_SYNCED_SETTINGS.continuePlaybackInBackground),
    openSubtitlesApiKey: typeof o.openSubtitlesApiKey === 'string' ? o.openSubtitlesApiKey : '',
    tmdbApiKey: typeof o.tmdbApiKey === 'string' ? o.tmdbApiKey : '',
    timeshiftMinutes: num(o.timeshiftMinutes, DEFAULT_SYNCED_SETTINGS.timeshiftMinutes, 0, 240),
    expiryWarnDays: num(o.expiryWarnDays, DEFAULT_SYNCED_SETTINGS.expiryWarnDays, 0, 60),
    autoSkipIntro: bool(o.autoSkipIntro, DEFAULT_SYNCED_SETTINGS.autoSkipIntro),
    autoNextEpisodeOnOutro: bool(o.autoNextEpisodeOnOutro, DEFAULT_SYNCED_SETTINGS.autoNextEpisodeOnOutro),
    autoSkipRecap: bool(o.autoSkipRecap, DEFAULT_SYNCED_SETTINGS.autoSkipRecap),
  }
}

/** Même ordre de champs que SealedSettings (Kotlin) pour éviter les boucles de synchronisation. */
export function settingsJson(s: SyncedSettings): string {
  return JSON.stringify({
    epgShiftMinutes: s.epgShiftMinutes,
    epgDaysAhead: s.epgDaysAhead,
    epgDaysBehind: s.epgDaysBehind,
    xtreamFormat: s.xtreamFormat,
    userAgent: s.userAgent,
    audioLang: s.audioLang,
    subtitleLang: s.subtitleLang,
    autoplayLast: s.autoplayLast,
    invertZap: s.invertZap,
    showNumbers: s.showNumbers,
    recPaddingBeforeMin: s.recPaddingBeforeMin,
    recPaddingAfterMin: s.recPaddingAfterMin,
    reminderMinutesBefore: s.reminderMinutesBefore,
    showAllPlaylists: s.showAllPlaylists,
    continuePlaybackInBackground: s.continuePlaybackInBackground,
    openSubtitlesApiKey: s.openSubtitlesApiKey,
    tmdbApiKey: s.tmdbApiKey,
    timeshiftMinutes: s.timeshiftMinutes,
    expiryWarnDays: s.expiryWarnDays,
    autoSkipIntro: s.autoSkipIntro,
    autoNextEpisodeOnOutro: s.autoNextEpisodeOnOutro,
    autoSkipRecap: s.autoSkipRecap,
  })
}

// ------------------------------------------------------------------ Statistiques v2 (Phase 4)

export interface ProfileUsageSlice {
  profile: Profile
  totalMs: number
  liveMs: number
  movieMs: number
  episodeMs: number
  otherMs: number
  share: number
}

function dayKeysEnding(days: number, endDateMs: number): Set<string> {
  const keys = new Set<string>()
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(endDateMs)
    d.setDate(d.getDate() - i)
    keys.add(dayKey(d.getTime()))
  }
  return keys
}

/** Compare la période actuelle avec la période précédente équivalente (disponible pour 7 j et 14 j). */
export function compareUsagePeriods(
  rows: UsageDay[], profileId: string | null, days: number, now: number = Date.now(),
): { currentMs: number; previousMs: number; deltaMs: number; deltaPct: number | null; activePrevDays: number } {
  const curKeys = dayKeysEnding(days, now)
  const prevEnd = new Date(now)
  prevEnd.setDate(prevEnd.getDate() - days)
  const prevKeys = dayKeysEnding(days, prevEnd.getTime())
  let currentMs = 0
  let previousMs = 0
  const activePrev = new Set<string>()
  for (const r of rows) {
    if (profileId && r.profile !== profileId) continue
    if (curKeys.has(r.day)) currentMs += r.totalMs
    else if (prevKeys.has(r.day)) {
      previousMs += r.totalMs
      if (r.totalMs > 0) activePrev.add(r.day)
    }
  }
  const deltaMs = currentMs - previousMs
  const deltaPct = previousMs > 0 ? Math.round((deltaMs / previousMs) * 100) : null
  return { currentMs, previousMs, deltaMs, deltaPct, activePrevDays: activePrev.size }
}

/** Répartition du temps par profil sur la période. */
export function usageByProfile(
  rows: UsageDay[], days: number, profiles: Profile[], now: number = Date.now(),
): ProfileUsageSlice[] {
  const keys = dayKeysEnding(days, now)
  const totals = new Map<string, { totalMs: number; liveMs: number; movieMs: number; episodeMs: number; otherMs: number }>()
  for (const r of rows) {
    if (!keys.has(r.day)) continue
    const cur = totals.get(r.profile) ?? { totalMs: 0, liveMs: 0, movieMs: 0, episodeMs: 0, otherMs: 0 }
    cur.totalMs += r.totalMs
    cur.liveMs += r.liveMs
    cur.movieMs += r.movieMs
    cur.episodeMs += r.episodeMs
    cur.otherMs += r.otherMs
    totals.set(r.profile, cur)
  }
  const grandTotal = [...totals.values()].reduce((s, x) => s + x.totalMs, 0)
  return profiles
    .map((profile) => {
      const x = totals.get(profile.id) ?? { totalMs: 0, liveMs: 0, movieMs: 0, episodeMs: 0, otherMs: 0 }
      return { profile, ...x, share: grandTotal > 0 ? x.totalMs / grandTotal : 0 }
    })
    .filter((s) => s.totalMs > 0)
    .sort((a, b) => b.totalMs - a.totalMs)
}

export interface DayUsageBreakdown {
  day: string
  totalMs: number
  byKind: { live: number; movie: number; episode: number; other: number }
  byProfile: { profile: Profile; ms: number }[]
  top: { title: string; ms: number }[]
}

/** Détail d'une journée sélectionnée dans le graphique. */
export function dayUsageBreakdown(
  rows: UsageDay[], day: string, profileId: string | null, profiles: Profile[],
): DayUsageBreakdown {
  const byKind = { live: 0, movie: 0, episode: 0, other: 0 }
  const profTotals = new Map<string, number>()
  const titles = new Map<string, number>()
  let totalMs = 0
  for (const r of rows) {
    if (r.day !== day || (profileId && r.profile !== profileId)) continue
    totalMs += r.totalMs
    byKind.live += r.liveMs
    byKind.movie += r.movieMs
    byKind.episode += r.episodeMs
    byKind.other += r.otherMs
    profTotals.set(r.profile, (profTotals.get(r.profile) ?? 0) + r.totalMs)
    for (const i of r.items) titles.set(i.t, (titles.get(i.t) ?? 0) + i.ms)
  }
  const byProfile = profiles
    .map((profile) => ({ profile, ms: profTotals.get(profile.id) ?? 0 }))
    .filter((x) => x.ms > 0)
    .sort((a, b) => b.ms - a.ms)
  const top = [...titles.entries()].map(([title, ms]) => ({ title, ms })).sort((a, b) => b.ms - a.ms).slice(0, 6)
  return { day, totalMs, byKind, byProfile, top }
}

/** Exporte les statistiques en CSV UTF-8 (avec BOM Excel et neutralisation des formules). */
export function usageToCsv(rows: UsageDay[], profiles: Profile[], devices: Device[]): string {
  const profMap = new Map(profiles.map((p) => [p.id, p.name]))
  const devMap = new Map(devices.map((d) => [d.id, deviceName(d)]))
  const cell = (v: string | number) => {
    const s = String(v)
    const safe = /^[=+\-@\t\r]/.test(s) ? `'${s}` : s
    return `"${safe.replace(/"/g, '""')}"`
  }
  const min = (ms: number) => Math.round(ms / 60_000)
  const header = ['Date', 'Profil', 'Appareil', 'Total (min)', 'Direct (min)', 'Films (min)', 'Séries (min)', 'Enregistrements (min)', 'Titres principaux']
  const sorted = [...rows].sort((a, b) => b.day.localeCompare(a.day) || a.profile.localeCompare(b.profile))
  const lines = [
    header.map(cell).join(';'),
    ...sorted.map((r) => [
      r.day,
      profMap.get(r.profile) ?? r.profile,
      devMap.get(r.device) ?? (r.device || 'Appareil'),
      min(r.totalMs),
      min(r.liveMs),
      min(r.movieMs),
      min(r.episodeMs),
      min(r.otherMs),
      r.items.slice(0, 5).map((i) => `${i.t} (${min(i.ms)} min)`).join(' · '),
    ].map(cell).join(';')),
  ]
  return '\uFEFF' + lines.join('\r\n')
}
