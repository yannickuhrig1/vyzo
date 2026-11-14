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

/** Déplace un profil d'un cran : renvoie les profils dont la position change. */
export function moveProfile(list: Profile[], id: string, delta: -1 | 1): Profile[] {
  const sorted = sortProfiles(list)
  const i = sorted.findIndex((p) => p.id === id)
  const j = i + delta
  if (i < 0 || j < 0 || j >= sorted.length) return []
  const reordered = [...sorted]
  ;[reordered[i], reordered[j]] = [reordered[j], reordered[i]]
  return reordered
    .map((p, index) => ({ ...p, position: index }))
    .filter((p) => p.position !== sorted.find((o) => o.id === p.id)!.position)
}

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
export interface SealedPrefs { categories: SealedCategoryPref[]; channels: unknown[] }

/** Règle de l'appli : choix explicite, sinon détection « adulte » si elle est active. */
export function categoryLocked(entry: CatalogEntry, pref: SealedCategoryPref | undefined, autoAdult: boolean): boolean {
  return pref?.locked ?? (autoAdult && entry.adult)
}

/** Préférences avec la catégorie [rest] verrouillée ou non ; le reste (masquage, ordre, nom, chaînes) est gardé. */
export function withCategoryLock(prefs: SealedPrefs, rest: string, locked: boolean): SealedPrefs {
  const categories = prefs.categories.map((c) => (c.rest === rest ? { ...c, locked } : c))
  if (!categories.some((c) => c.rest === rest)) {
    categories.push({ rest, hidden: false, position: null, locked, name: '' })
    // L'appli range par uid complet (même préfixe pour toute la playlist) : l'ordre de « rest » suffit.
    categories.sort((a, b) => (a.rest < b.rest ? -1 : a.rest > b.rest ? 1 : 0))
  }
  return { categories, channels: prefs.channels }
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

export interface UsageSummary {
  totalMs: number
  /** Un total par jour, du plus ancien au plus récent ([days] jours, aujourd'hui compris). */
  perDay: { day: string; ms: number }[]
  byKind: { live: number; movie: number; episode: number; other: number }
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
  const byKind = { live: 0, movie: 0, episode: 0, other: 0 }
  const titles = new Map<string, number>()
  const byDevice = new Map<string, number>()
  let totalMs = 0
  for (const r of rows) {
    if (!inRange.has(r.day) || (profileId && r.profile !== profileId)) continue
    totalMs += r.totalMs
    perDay.set(r.day, (perDay.get(r.day) ?? 0) + r.totalMs)
    byKind.live += r.liveMs
    byKind.movie += r.movieMs
    byKind.episode += r.episodeMs
    byKind.other += r.otherMs
    byDevice.set(r.device, (byDevice.get(r.device) ?? 0) + r.totalMs)
    for (const i of r.items) titles.set(i.t, (titles.get(i.t) ?? 0) + i.ms)
  }
  const top = [...titles.entries()].map(([title, ms]) => ({ title, ms })).sort((a, b) => b.ms - a.ms).slice(0, 10)
  return { totalMs, perDay: keys.map((day) => ({ day, ms: perDay.get(day) ?? 0 })), byKind, top, byDevice }
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
