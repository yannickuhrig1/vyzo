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
] as const

export const MAX_PROFILE_NAME = 24

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
