import { createClient, type RealtimeChannel, type SupabaseClient } from '@supabase/supabase-js'
import {
  BACKUP_FORMAT, BACKUP_SEALED_KINDS, BACKUP_VERSION, FAVORITES_LIST_ID, MAX_DEVICE_LABEL, MAX_MESSAGE, WEB_SENDER, mergeGuide, nextUpdatedAt,
  parsePlayback, parseRule, parseUsage, recordingId, ruleJson, withCategoryLock,
  type AccountEvent, type BackupCounts, type BackupFile, type GuideResult, type PlaybackState, type ScreenTimeRule, type UsageDay,
  type CatalogEntry, type ContentKind, type Device, type Parental, type PlaylistStatus, type SealedPrefs, type DeviceKind, type LatestVersion, type ListItem, type Playlist, type Profile,
  type ProfileActivity, type ProgressItem, type Recording, type RecordingStatus, type UserList,
} from './model'
import {
  context, deriveKey, fromBase64, keyId, metaContext, openText, toBase64, parseMeta, parsePlaylist, playlistJson, playlistKey, seal, unwrapVault,
  type SealedMeta, type VaultRecord,
} from './vault'
import { forgetKey, hasRememberedKey, recallKey, rememberKey } from './keystore'

declare const __SUPABASE_URL__: string
declare const __SUPABASE_KEY__: string

export interface Account {
  userId: string
  email: string
}

export type Table = 'profiles' | 'watch_progress' | 'list_items' | 'user_lists' | 'recordings' | 'devices' | 'sealed_items'

/** open : clé du compte en mémoire. locked : mot de passe à saisir. missing : aucun appareil n'a encore créé la clé. */
export type VaultStatus = 'open' | 'locked' | 'missing'

export interface ListsData { lists: UserList[]; items: ListItem[] }

/** Nouvelles valeurs d'un abonnement (sans identifiant : il se calcule). */
export type PlaylistDraft = Omit<Playlist, 'id' | 'updatedAt' | 'hasPrefs' | 'status'>

export interface CastItem { contentKey: string; itemType: string; title: string; positionMs: number }

/** Message d'un appareil sur le canal du compte (DeviceRemote) : réponse à une commande ou état de lecture. */
export interface LiveMessage { type: string; device: string; req: string; box: string; action: string }

export type PlaybackAction = 'pause' | 'resume' | 'stop'

/** Contenu d'une sauvegarde, une fois déchiffrée. */
export interface BackupData {
  createdAt: number
  profiles: Record<string, unknown>[]
  lists: Record<string, unknown>[]
  items: Record<string, unknown>[]
  progress: Record<string, unknown>[]
  recordings: Record<string, unknown>[]
  sealed: { kind: string; id: string; plain: string }[]
}

export const backupCounts = (d: BackupData): BackupCounts => ({
  profiles: d.profiles.length, lists: d.lists.length, items: d.items.length, progress: d.progress.length,
  recordings: d.recordings.length, sealed: d.sealed.length,
})

const EMPTY_PREFS: SealedPrefs = { categories: [], channels: [] }

/** Accès aux données du compte. Deux versions : Supabase, et une démo en mémoire pour le développement. */
export interface Backend {
  readonly demo: boolean
  currentAccount(): Promise<Account | null>
  /** [remember] : garder la clé du compte dans ce navigateur (« Rester déverrouillé »). */
  signIn(email: string, password: string, remember?: boolean): Promise<Account>
  signOut(): Promise<void>
  /** Prévient quand une table change ailleurs (appli, autre onglet). Renvoie de quoi arrêter l'écoute. */
  watch(account: Account, onChange: (table: Table) => void): () => void

  vaultStatus(account: Account): Promise<VaultStatus>
  /** false : mauvais mot de passe. */
  unlockVault(account: Account, password: string, remember?: boolean): Promise<boolean>
  /** Verrouille et oublie la clé gardée dans ce navigateur. */
  lockVault(): Promise<void>
  /** La clé du compte est gardée dans ce navigateur. */
  vaultRemembered(): Promise<boolean>
  /** Nouveau mot de passe du compte ; la clé des données chiffrées est rechiffrée avec lui (même clé, mêmes appareils). */
  changePassword(account: Account, current: string, next: string): Promise<void>
  /** Oublie la clé gardée dans ce navigateur, sans verrouiller la page. */
  forgetRemembered(): Promise<void>
  /** Ferme toutes les autres sessions du compte (box, téléphones, PC, autres navigateurs). */
  signOutOthers(): Promise<void>

  profiles(): Promise<Profile[]>
  saveProfiles(profiles: Profile[]): Promise<void>
  deleteProfile(profile: Profile): Promise<void>

  activity(profiles: Profile[]): Promise<ProfileActivity[]>
  clearHistory(account: Account, profileId: string): Promise<void>
  progress(account: Account, profileId: string): Promise<ProgressItem[]>
  removeProgress(account: Account, profileId: string, contentKeys: string[]): Promise<void>

  lists(account: Account, profileId: string): Promise<ListsData>
  saveList(list: UserList): Promise<void>
  deleteList(list: UserList): Promise<void>
  removeListItem(item: ListItem): Promise<void>

  recordings(): Promise<Recording[]>
  setRecordingStatus(r: Recording, status: RecordingStatus): Promise<void>
  removeRecording(r: Recording): Promise<void>

  devices(): Promise<Device[]>
  latestVersion(): Promise<LatestVersion | null>
  requestUpdate(d: Device): Promise<void>
  removeDevice(d: Device): Promise<void>

  playlists(account: Account): Promise<Playlist[]>
  savePlaylist(account: Account, draft: PlaylistDraft, previous: Playlist | null, all: Playlist[]): Promise<void>
  deletePlaylist(account: Account, p: Playlist): Promise<void>

  /** Ouvre un titre sur un appareil du compte (RealtimeClient : « cast_command » avec contentKey). */
  castTo(account: Account, device: Device, item: CastItem): Promise<void>

  /** Contrôle parental commun ; null : chaque appareil garde le sien. */
  parental(account: Account): Promise<Parental | null>
  saveParental(account: Account, value: Parental | null): Promise<void>
  /** Catégories d'un abonnement (envoyées par un appareil) et préférences actuelles. */
  catalog(account: Account, playlistId: string): Promise<{ entries: CatalogEntry[]; prefs: SealedPrefs }>
  setCategoryLock(account: Account, playlistId: string, rest: string, locked: boolean): Promise<void>

  /** Nom choisi pour un appareil ; vide : celui du système. */
  renameDevice(d: Device, label: string): Promise<void>
  /** Messages des appareils sur le canal du compte (watch doit être actif). */
  onLive(listener: (m: LiveMessage) => void): () => void
  /** Demande à l'appareil de vérifier tout de suite l'état de ses abonnements. Renvoie l'identifiant de la demande. */
  checkNow(account: Account, d: Device): Promise<string>
  sendMessage(account: Account, d: Device, text: string, from: string): Promise<string>
  /** Chaque appareil connecté répond par son état de lecture (« playback_state »). */
  queryPlayback(account: Account): Promise<void>
  controlPlayback(account: Account, d: Device, action: PlaybackAction): Promise<void>
  openPlayback(account: Account, box: string): Promise<PlaybackState | null>
  /** Recherche dans le guide des appareils connectés (le site n'a pas le guide). */
  searchGuide(account: Account, query: string): Promise<GuideResult[]>
  scheduleRecording(g: GuideResult): Promise<void>

  screenTime(account: Account): Promise<Map<string, ScreenTimeRule>>
  saveScreenTime(account: Account, profileId: string, rule: ScreenTimeRule | null): Promise<void>
  usage(account: Account): Promise<UsageDay[]>

  events(): Promise<AccountEvent[]>
  logEvent(kind: string, detail?: string): Promise<void>

  /** Fichier de sauvegarde chiffré avec [password]. */
  exportBackup(account: Account, password: string): Promise<{ file: string; counts: BackupCounts }>
  /** null : mot de passe incorrect. */
  readBackup(text: string, password: string): Promise<BackupData | null>
  restoreBackup(account: Account, data: BackupData): Promise<BackupCounts>
}

/** Contexte du chiffrement d'une sauvegarde : un bloc d'un autre usage ne s'ouvre pas comme sauvegarde. */
export const BACKUP_CONTEXT = `${BACKUP_FORMAT}|v${BACKUP_VERSION}`

export function parseBackupFile(text: string): BackupFile {
  let o: Partial<BackupFile>
  try {
    o = JSON.parse(text) as Partial<BackupFile>
  } catch {
    throw new UserError('Ce fichier n’est pas une sauvegarde Vyzo')
  }
  if (o.format !== BACKUP_FORMAT || typeof o.box !== 'string' || typeof o.salt !== 'string') throw new UserError('Ce fichier n’est pas une sauvegarde Vyzo')
  if (Number(o.version) > BACKUP_VERSION) throw new UserError('Sauvegarde faite par une version plus récente du site')
  const iterations = Number(o.iterations)
  if (!Number.isInteger(iterations) || iterations < 100_000 || iterations > 5_000_000) throw new UserError('Sauvegarde abîmée')
  return { format: o.format, version: Number(o.version), createdAt: Number(o.createdAt) || 0, salt: o.salt, iterations, box: o.box }
}

export function parseBackupData(text: string): BackupData {
  const o = JSON.parse(text) as Partial<BackupData>
  const rows = (v: unknown) => (Array.isArray(v) ? v.filter((x) => x && typeof x === 'object') as Record<string, unknown>[] : [])
  return {
    createdAt: Number(o.createdAt) || 0,
    profiles: rows(o.profiles), lists: rows(o.lists), items: rows(o.items), progress: rows(o.progress), recordings: rows(o.recordings),
    sealed: rows(o.sealed).filter((x) => BACKUP_SEALED_KINDS.includes(String(x.kind)) && typeof x.id === 'string' && typeof x.plain === 'string')
      .map((x) => ({ kind: String(x.kind), id: String(x.id), plain: String(x.plain) })),
  }
}

/** Identifiant de demande, pour reconnaître les réponses. */
export const requestId = () => Array.from(crypto.getRandomValues(new Uint8Array(8)), (b) => b.toString(16).padStart(2, '0')).join('')

/** Message d'erreur lisible, comme CloudApi.friendlyMessage. */
export function friendlyError(e: unknown): string {
  const err = e as { message?: string; code?: string; status?: number }
  const code = err?.code ?? ''
  const raw = err?.message ?? String(e)
  if (code === 'invalid_credentials' || /Invalid login credentials/i.test(raw)) return 'E-mail ou mot de passe incorrect'
  if (code === 'email_not_confirmed' || /Email not confirmed/i.test(raw))
    return 'Adresse pas encore confirmée : ouvre le lien reçu par e-mail lors de la création du compte'
  if (code.startsWith('over_') || err?.status === 429) return 'Trop de tentatives, réessaie dans quelques minutes'
  if (/Failed to fetch|NetworkError|Load failed/i.test(raw)) return 'Serveur Vyzo injoignable : vérifie la connexion internet'
  if (/JWT|session/i.test(raw)) return 'Session expirée : reconnecte-toi'
  if (code === 'PGRST205' || /Could not find the table/i.test(raw)) return 'Le serveur Vyzo n’a pas encore cette fonction (mise à jour du serveur à faire)'
  return raw || 'Erreur inconnue'
}

/** Erreur à montrer telle quelle (pas un message technique). */
export class UserError extends Error {}

interface ProfileRow {
  id: string; name: string; color_index: number; is_kid: boolean; pin_hash: string; position: number; updated_at: number
}

interface RecordingRow {
  id: string; channel_key: string; channel_name: string; title: string; start_ms: number; stop_ms: number
  status: RecordingStatus; error: string; sender: string; updated_at: number
}

interface DeviceRow {
  id: string; name: string; label?: string; kind: DeviceKind; model: string; system: string; app_version: string; version_code: number
  last_seen_at: number; update_requested_at: number; update_status: string; update_status_at: number
}

interface SealedRow { kind: string; id: string; payload: string; updated_at: number }

interface ListRow { profile_id: string; id: string; name: string; position: number; updated_at: number }

interface ItemRow {
  profile_id: string; list_id: string; content_key: string; kind: ContentKind; position: number; added_at: number
  updated_at: number; meta?: string
}

interface ProgressRow {
  profile_id: string; content_key: string; kind: ContentKind; position_ms: number; duration_ms: number; updated_at: number
  meta?: string
}

const toProfile = (r: ProfileRow): Profile => ({
  id: r.id, name: r.name || 'Profil', colorIndex: r.color_index ?? 0, isKid: !!r.is_kid, pinHash: r.pin_hash ?? '',
  position: r.position ?? 0, updatedAt: Number(r.updated_at),
})

const toRecording = (r: RecordingRow): Recording => ({
  id: r.id, channelKey: r.channel_key, channelName: r.channel_name, title: r.title, startMs: Number(r.start_ms),
  stopMs: Number(r.stop_ms), status: r.status, error: r.error ?? '', sender: r.sender ?? '', updatedAt: Number(r.updated_at),
})

const toDevice = (r: DeviceRow): Device => ({
  id: r.id, name: r.name, label: r.label ?? '', kind: r.kind ?? '', model: r.model ?? '', system: r.system ?? '', appVersion: r.app_version ?? '',
  versionCode: r.version_code ?? 0, lastSeenAt: Number(r.last_seen_at), updateRequestedAt: Number(r.update_requested_at),
  updateStatus: r.update_status ?? '', updateStatusAt: Number(r.update_status_at),
})

/** Les tables synchronisées sont lues par pages de 1000 lignes au plus (limite PostgREST). */
const PAGE = 1000

/** Colonne « meta » absente : serveur pas encore mis à jour. */
const missingMeta = (e: { code?: string; message?: string } | null) =>
  !!e && (e.code === '42703' || /meta/.test(e.message ?? ''))

export class SupabaseBackend implements Backend {
  readonly demo = false
  private readonly db: SupabaseClient
  private readonly url: string
  private channel: RealtimeChannel | null = null
  private readonly liveListeners = new Set<(m: LiveMessage) => void>()
  /** Clé du compte, en mémoire seulement : perdue au rechargement de la page. */
  private vaultKey: Uint8Array | null = null
  private vaultUser = ''

  constructor(url: string = __SUPABASE_URL__, key: string = __SUPABASE_KEY__) {
    this.url = url
    this.db = createClient(url, key, {
      auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: false, storageKey: 'vyzo-web-session' },
    })
    // Fin de session, quelle qu'en soit la cause (déconnexion, jeton révoqué, autre onglet) : la clé gardée est oubliée.
    this.db.auth.onAuthStateChange((event) => {
      if (event === 'SIGNED_OUT') void this.lockVault()
    })
  }

  // ---------------------------------------------------------------- Compte

  async currentAccount(): Promise<Account | null> {
    const { data } = await this.db.auth.getSession()
    const user = data.session?.user
    return user ? { userId: user.id, email: user.email ?? '' } : null
  }

  async signIn(email: string, password: string, remember = false): Promise<Account> {
    const { data, error } = await this.db.auth.signInWithPassword({ email: email.trim(), password })
    if (error) throw error
    const account = { userId: data.user.id, email: data.user.email ?? '' }
    void this.logEvent('site_signin', navigatorLabel())
    if (!remember) await forgetKey()
    // Le mot de passe du compte ouvre aussi ses données chiffrées : autant le faire tout de suite.
    await this.unlockVault(account, password, remember).catch(() => false)
    return account
  }

  async signOut(): Promise<void> {
    await this.lockVault()
    if (this.channel) await this.db.removeChannel(this.channel)
    this.channel = null
    // « local » : les appareils du compte restent connectés.
    await this.db.auth.signOut({ scope: 'local' })
  }

  watch(account: Account, onChange: (table: Table) => void): () => void {
    const tables: Table[] = ['profiles', 'watch_progress', 'list_items', 'user_lists', 'recordings', 'devices', 'sealed_items']
    const run = async () => {
      // Le canal privé « user:<uid> » exige le jeton du compte (migration realtime_canal_prive).
      const { data } = await this.db.auth.getSession()
      if (data.session) await this.db.realtime.setAuth(data.session.access_token)
      let ch = this.db.channel(`user:${account.userId}`, { config: { private: true, broadcast: { self: false } } })
      for (const table of tables) {
        ch = ch.on('postgres_changes', { event: '*', schema: 'public', table, filter: `user_id=eq.${account.userId}` },
          () => onChange(table))
      }
      ch = ch.on('broadcast', { event: 'broadcast' }, (msg) => this.emitLive(msg.payload as Record<string, unknown> | undefined))
      this.channel = ch.subscribe()
    }
    void run()
    return () => {
      if (this.channel) void this.db.removeChannel(this.channel)
      this.channel = null
    }
  }

  // ---------------------------------------------------------------- Coffre

  private async vaultRecord(): Promise<VaultRecord | null> {
    const { data, error } = await this.db.from('sealed_items').select('payload')
      .eq('kind', 'vault').eq('id', 'key').eq('deleted', false).limit(1)
    if (error) throw error
    const payload = (data as { payload: string }[])[0]?.payload
    return payload ? JSON.parse(payload) as VaultRecord : null
  }

  async vaultStatus(account: Account): Promise<VaultStatus> {
    if (this.vaultKey && this.vaultUser === account.userId) return 'open'
    const record = await this.vaultRecord()
    if (!record) return 'missing'
    // « Rester déverrouillé » : la clé gardée dans ce navigateur, si c'est toujours celle du compte.
    const kept = await recallKey(account.userId, record.keyId)
    if (kept && kept.length === 32) {
      this.vaultKey = kept
      this.vaultUser = account.userId
      return 'open'
    }
    return 'locked'
  }

  async unlockVault(account: Account, password: string, remember = false): Promise<boolean> {
    const record = await this.vaultRecord()
    if (!record) return false
    const key = await unwrapVault(record, password, account.userId)
    if (!key) return false
    this.vaultKey = key
    this.vaultUser = account.userId
    // Case décochée : une clé gardée plus tôt (session expirée, autre compte) ne doit pas survivre à ce choix.
    if (remember) await rememberKey(account.userId, record.keyId, key).catch(() => undefined)
    else await forgetKey()
    return true
  }

  async lockVault(): Promise<void> {
    this.vaultKey?.fill(0)
    this.vaultKey = null
    this.vaultUser = ''
    await forgetKey()
  }

  vaultRemembered(): Promise<boolean> {
    return hasRememberedKey()
  }

  async changePassword(account: Account, current: string, next: string): Promise<void> {
    // Vérifie l'actuel en se reconnectant : une session ouverte ne suffit pas pour changer de mot de passe.
    const check = await this.db.auth.signInWithPassword({ email: account.email, password: current })
    if (check.error) throw new UserError('Mot de passe actuel incorrect')
    const record = await this.vaultRecord()
    let key = record ? await unwrapVault(record, current, account.userId) : null
    // Reprise après un rechiffrement raté : le compte a déjà le nouveau mot de passe, la clé l'ancien. La clé ouverte
    // dans cette page (même empreinte) suffit alors à finir le travail.
    if (record && !key && this.vaultKey && this.vaultUser === account.userId && await keyId(this.vaultKey) === record.keyId) key = this.vaultKey
    if (record && !key) throw new UserError('Le mot de passe actuel n’ouvre pas les données chiffrées du compte')
    if (key) {
      this.vaultKey = key
      this.vaultUser = account.userId
    }
    const { error } = await this.db.auth.updateUser({ password: next })
    if (error) throw error
    if (!key) {
      void this.logEvent('password_changed')
      return
    }
    // Comme SealedSync.writeVault : nouveau sel, même clé. keyId ne change pas, les appareils restent ouverts.
    const salt = crypto.getRandomValues(new Uint8Array(16))
    const iterations = 600_000
    const kek = await deriveKey(next, salt, iterations)
    const payload = JSON.stringify({
      salt: toBase64(salt), iterations, wrapped: await seal(kek, key, context(account.userId, 'vault', 'key')), keyId: await keyId(key),
    })
    let last: unknown = null
    for (let attempt = 0; attempt < 3; attempt++) {
      const { error: e } = await this.db.from('sealed_items')
        .upsert({ kind: 'vault', id: 'key', payload, updated_at: Date.now(), deleted: false }, { onConflict: 'user_id,kind,id' })
      if (!e) {
        void this.logEvent('password_changed')
        return
      }
      last = e
    }
    throw new UserError(`Mot de passe changé, mais la clé n’a pas pu être rechiffrée (${friendlyError(last)}). Sans fermer cette page, recommence avec le nouveau mot de passe comme mot de passe actuel.`)
  }

  async forgetRemembered(): Promise<void> {
    await forgetKey()
  }

  async signOutOthers(): Promise<void> {
    const { error } = await this.db.auth.signOut({ scope: 'others' })
    if (error) throw error
    void this.logEvent('signout_others')
  }

  private key(account: Account): Uint8Array {
    if (!this.vaultKey || this.vaultUser !== account.userId) throw new UserError('Données chiffrées verrouillées : saisis le mot de passe du compte')
    return this.vaultKey
  }

  private async decryptMeta(account: Account, contentKey: string, meta: string | undefined): Promise<SealedMeta | null> {
    if (!meta || !this.vaultKey || this.vaultUser !== account.userId) return null
    try {
      return parseMeta(await openText(this.vaultKey, meta, metaContext(account.userId, contentKey)))
    } catch {
      return null
    }
  }

  // ---------------------------------------------------------------- Profils

  async profiles(): Promise<Profile[]> {
    const { data, error } = await this.db.from('profiles')
      .select('id,name,color_index,is_kid,pin_hash,position,updated_at')
      .eq('deleted', false).order('position').limit(PAGE)
    if (error) throw error
    return (data as ProfileRow[]).map(toProfile)
  }

  async saveProfiles(profiles: Profile[]): Promise<void> {
    if (profiles.length === 0) return
    const rows = profiles.map((p) => ({
      id: p.id, name: p.name, color_index: p.colorIndex, is_kid: p.isKid, pin_hash: p.pinHash, position: p.position,
      updated_at: nextUpdatedAt(p.updatedAt), deleted: false,
    }))
    const { error } = await this.db.from('profiles').upsert(rows, { onConflict: 'user_id,id' })
    if (error) throw error
  }

  async deleteProfile(profile: Profile): Promise<void> {
    // Le déclencheur vyzo_profile_deleted efface aussi sa progression et ses listes sur le serveur.
    const { error } = await this.db.from('profiles')
      .update({ deleted: true, updated_at: nextUpdatedAt(profile.updatedAt) }).eq('id', profile.id)
    if (error) throw error
  }

  // ---------------------------------------------------------------- Historique

  async activity(profiles: Profile[]): Promise<ProfileActivity[]> {
    return Promise.all(profiles.map(async (p): Promise<ProfileActivity> => {
      const [progress, items, last] = await Promise.all([
        this.db.from('watch_progress').select('content_key', { count: 'exact', head: true }).eq('profile_id', p.id),
        this.db.from('list_items').select('content_key', { count: 'exact', head: true })
          .eq('profile_id', p.id).eq('deleted', false),
        this.db.from('watch_progress').select('updated_at').eq('profile_id', p.id)
          .order('updated_at', { ascending: false }).limit(1),
      ])
      for (const r of [progress, items, last]) if (r.error) throw r.error
      const lastRow = (last.data as { updated_at: number }[] | null)?.[0]
      return {
        profileId: p.id, inProgress: progress.count ?? 0, inLists: items.count ?? 0,
        lastWatchedAt: lastRow ? Number(lastRow.updated_at) : null,
      }
    }))
  }

  async clearHistory(account: Account, profileId: string): Promise<void> {
    // Comme CloudSync.deleteCloudHistory : on efface les lignes, puis on prévient les appareils connectés,
    // qui vident leur historique local (la suppression d'une ligne ne leur parvient pas par la synchro).
    const { error } = await this.db.from('watch_progress').delete().eq('profile_id', profileId)
    if (error) throw error
    await this.broadcast(account, {
      type: 'history_clear',
      command: { profileId, clearAll: true, contentKeys: [], senderDeviceId: WEB_SENDER },
    })
  }

  async progress(account: Account, profileId: string): Promise<ProgressItem[]> {
    const base = 'profile_id,content_key,kind,position_ms,duration_ms,updated_at'
    let rows: ProgressRow[]
    try {
      rows = await this.selectAll<ProgressRow>('watch_progress', `${base},meta`, (q) => q.eq('profile_id', profileId).order('updated_at', { ascending: false }))
    } catch (e) {
      if (!missingMeta(e as { code?: string })) throw e
      rows = await this.selectAll<ProgressRow>('watch_progress', base, (q) => q.eq('profile_id', profileId).order('updated_at', { ascending: false }))
    }
    return Promise.all(rows.map(async (r) => {
      const meta = await this.decryptMeta(account, r.content_key, r.meta)
      return {
        profileId: r.profile_id, contentKey: r.content_key, kind: r.kind, positionMs: Number(r.position_ms),
        durationMs: Number(r.duration_ms), updatedAt: Number(r.updated_at), title: meta?.title ?? '', subtitle: meta?.subtitle ?? '',
      }
    }))
  }

  async removeProgress(account: Account, profileId: string, contentKeys: string[]): Promise<void> {
    for (let i = 0; i < contentKeys.length; i += 100) {
      const chunk = contentKeys.slice(i, i + 100)
      const { error } = await this.db.from('watch_progress').delete().eq('profile_id', profileId).in('content_key', chunk)
      if (error) throw error
    }
    // « history_remove » et non « history_clear » : une version ancienne de l'appli viderait tout l'historique.
    await this.broadcast(account, { type: 'history_remove', command: { profileId, contentKeys, senderDeviceId: WEB_SENDER } })
  }

  // ---------------------------------------------------------------- Listes

  async lists(account: Account, profileId: string): Promise<ListsData> {
    const { data, error } = await this.db.from('user_lists').select('profile_id,id,name,position,updated_at')
      .eq('profile_id', profileId).eq('deleted', false).order('position').limit(PAGE)
    if (error) throw error
    const base = 'profile_id,list_id,content_key,kind,position,added_at,updated_at'
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const filter = (q: any) => q.eq('profile_id', profileId).eq('deleted', false).order('position')
    let rows: ItemRow[]
    try {
      rows = await this.selectAll<ItemRow>('list_items', `${base},meta`, filter)
    } catch (e) {
      if (!missingMeta(e as { code?: string })) throw e
      rows = await this.selectAll<ItemRow>('list_items', base, filter)
    }
    const items = await Promise.all(rows.map(async (r): Promise<ListItem> => {
      const meta = await this.decryptMeta(account, r.content_key, r.meta)
      return {
        profileId: r.profile_id, listId: r.list_id, contentKey: r.content_key, kind: r.kind, position: r.position ?? 0,
        addedAt: Number(r.added_at), updatedAt: Number(r.updated_at), title: meta?.title ?? '', subtitle: meta?.subtitle ?? '',
      }
    }))
    const lists = (data as ListRow[]).map((l): UserList => ({
      profileId: l.profile_id, id: l.id, name: l.name, position: l.position ?? 0, updatedAt: Number(l.updated_at),
    }))
    return { lists, items }
  }

  async saveList(list: UserList): Promise<void> {
    if (list.id === FAVORITES_LIST_ID) return
    const { error } = await this.db.from('user_lists').upsert({
      profile_id: list.profileId, id: list.id, name: list.name, position: list.position,
      updated_at: nextUpdatedAt(list.updatedAt), deleted: false,
    }, { onConflict: 'user_id,profile_id,id' })
    if (error) throw error
  }

  async deleteList(list: UserList): Promise<void> {
    // Le déclencheur vyzo_list_deleted retire aussi ses éléments sur le serveur.
    const { error } = await this.db.from('user_lists').update({ deleted: true, updated_at: nextUpdatedAt(list.updatedAt) })
      .eq('profile_id', list.profileId).eq('id', list.id)
    if (error) throw error
  }

  async removeListItem(item: ListItem): Promise<void> {
    const { error } = await this.db.from('list_items').update({ deleted: true, updated_at: nextUpdatedAt(item.updatedAt) })
      .eq('profile_id', item.profileId).eq('list_id', item.listId).eq('content_key', item.contentKey)
    if (error) throw error
  }

  // ---------------------------------------------------------------- Enregistrements

  async recordings(): Promise<Recording[]> {
    const { data, error } = await this.db.from('recordings')
      .select('id,channel_key,channel_name,title,start_ms,stop_ms,status,error,sender,updated_at')
      .eq('deleted', false).order('start_ms', { ascending: false }).limit(PAGE)
    if (error) throw error
    return (data as RecordingRow[]).map(toRecording)
  }

  async setRecordingStatus(r: Recording, status: RecordingStatus): Promise<void> {
    const { error } = await this.db.from('recordings')
      .update({ status, sender: WEB_SENDER, updated_at: nextUpdatedAt(r.updatedAt) }).eq('id', r.id)
    if (error) throw error
  }

  async removeRecording(r: Recording): Promise<void> {
    const { error } = await this.db.from('recordings')
      .update({ deleted: true, sender: WEB_SENDER, updated_at: nextUpdatedAt(r.updatedAt) }).eq('id', r.id)
    if (error) throw error
  }

  // ---------------------------------------------------------------- Appareils

  async devices(): Promise<Device[]> {
    const base = 'id,name,kind,model,system,app_version,version_code,last_seen_at,update_requested_at,update_status,update_status_at'
    const read = (columns: string) => this.db.from('devices').select(columns).order('last_seen_at', { ascending: false }).limit(PAGE)
    let { data, error } = await read(`${base},label`)
    // Serveur sans la colonne « label » (migration journal_et_noms pas encore passée).
    if (error && (error.code === '42703' || /label/.test(error.message))) ({ data, error } = await read(base))
    if (error) throw error
    return (data as unknown as DeviceRow[]).map(toDevice)
  }

  async renameDevice(d: Device, label: string): Promise<void> {
    const { error } = await this.db.from('devices').update({ label: label.trim().slice(0, MAX_DEVICE_LABEL) }).eq('id', d.id)
    if (error && (error.code === '42703' || error.code === 'PGRST204')) throw new UserError('Le serveur Vyzo n’a pas encore cette fonction (mise à jour du serveur à faire)')
    if (error) throw error
  }

  async latestVersion(): Promise<LatestVersion | null> {
    try {
      const r = await fetch(`${this.url}/storage/v1/object/public/releases/latest.json`, { cache: 'no-store' })
      if (!r.ok) return null
      const o = await r.json() as { versionCode?: number; versionName?: string }
      return o.versionCode ? { versionCode: o.versionCode, versionName: o.versionName ?? '' } : null
    } catch {
      return null
    }
  }

  async requestUpdate(d: Device): Promise<void> {
    const { error } = await this.db.from('devices')
      .update({ update_requested_at: Math.max(Date.now(), d.updateRequestedAt + 1), update_status: '', update_status_at: 0 }).eq('id', d.id)
    if (error) throw error
  }

  async removeDevice(d: Device): Promise<void> {
    const { error } = await this.db.from('devices').delete().eq('id', d.id)
    if (error) throw error
  }

  // ---------------------------------------------------------------- Abonnements IPTV

  async playlists(account: Account): Promise<Playlist[]> {
    const key = this.key(account)
    const rows = await this.selectAll<SealedRow>('sealed_items', 'kind,id,payload,updated_at',
      (q) => q.in('kind', ['playlist', 'prefs', 'status']).eq('deleted', false))
    const prefs = new Set(rows.filter((r) => r.kind === 'prefs').map((r) => r.id))
    const statuses = new Map<string, PlaylistStatus>()
    for (const r of rows.filter((x) => x.kind === 'status')) {
      try {
        const o = JSON.parse(await openText(key, r.payload, context(account.userId, 'status', r.id))) as Partial<PlaylistStatus>
        statuses.set(r.id, { expiresAt: Number(o.expiresAt ?? 0), maxConnections: Number(o.maxConnections ?? 0), status: String(o.status ?? ''), checkedDay: Number(o.checkedDay ?? 0) })
      } catch {
        // Illisible : simplement pas d'état affiché.
      }
    }
    const out: Playlist[] = []
    for (const r of rows.filter((x) => x.kind === 'playlist')) {
      try {
        const p = parsePlaylist(await openText(key, r.payload, context(account.userId, 'playlist', r.id)))
        out.push({ ...p, id: r.id, updatedAt: Number(r.updated_at), hasPrefs: prefs.has(r.id), status: statuses.get(r.id) ?? null })
      } catch {
        // Bloc illisible (clé remplacée entre-temps) : il sera réécrit par un appareil.
      }
    }
    return out.sort((a, b) => a.name.localeCompare(b.name, 'fr'))
  }

  async savePlaylist(account: Account, draft: PlaylistDraft, previous: Playlist | null, all: Playlist[]): Promise<void> {
    const key = this.key(account)
    const id = await playlistKey(draft.type, draft.url, draft.username, draft.mac)
    const clash = all.find((p) => p.id === id && p.id !== previous?.id)
    if (clash) throw new UserError(`Cet abonnement est déjà sur le compte (« ${clash.name} »)`)
    const now = Date.now()
    const rows: { kind: string; id: string; payload: string; updated_at: number; deleted: boolean }[] = [{
      kind: 'playlist', id, deleted: false, updated_at: nextUpdatedAt(previous?.id === id ? previous.updatedAt : 0, now),
      payload: await seal(key, playlistJson(draft), context(account.userId, 'playlist', id)),
    }]
    // Identifiant changé (autre identifiant Xtream, autre MAC, autre adresse M3U) : c'est un autre abonnement pour les
    // appareils, comme quand on le modifie dans l'appli. L'ancien est retiré, avec ses réglages de chaînes.
    if (previous && previous.id !== id) {
      rows.push({ kind: 'playlist', id: previous.id, payload: '', deleted: true, updated_at: nextUpdatedAt(previous.updatedAt, now) })
      if (previous.hasPrefs) rows.push({ kind: 'prefs', id: previous.id, payload: '', deleted: true, updated_at: now })
    }
    const { error } = await this.db.from('sealed_items').upsert(rows, { onConflict: 'user_id,kind,id' })
    if (error) throw error
  }

  async deletePlaylist(account: Account, p: Playlist): Promise<void> {
    this.key(account)
    const now = Date.now()
    const rows = [{ kind: 'playlist', id: p.id, payload: '', deleted: true, updated_at: nextUpdatedAt(p.updatedAt, now) }]
    if (p.hasPrefs) rows.push({ kind: 'prefs', id: p.id, payload: '', deleted: true, updated_at: now })
    if (p.status) rows.push({ kind: 'status', id: p.id, payload: '', deleted: true, updated_at: now })
    const { error } = await this.db.from('sealed_items').upsert(rows, { onConflict: 'user_id,kind,id' })
    if (error) throw error
  }

  // ---------------------------------------------------------------- Lancer sur un appareil

  async castTo(account: Account, device: Device, item: CastItem): Promise<void> {
    await this.broadcast(account, {
      type: 'cast_command',
      command: {
        targetDeviceId: device.id, itemType: item.itemType, itemId: '', title: item.title.slice(0, 200),
        positionMs: Math.max(0, Math.round(item.positionMs)), senderDeviceId: WEB_SENDER, contentKey: item.contentKey,
      },
    })
  }

  // ---------------------------------------------------------------- Contrôle parental

  private async sealedRow(account: Account, kind: string, id: string): Promise<string | null> {
    const key = this.key(account)
    const { data, error } = await this.db.from('sealed_items').select('payload')
      .eq('kind', kind).eq('id', id).eq('deleted', false).limit(1)
    if (error) throw error
    const row = (data as { payload: string }[] | null)?.[0]
    return row ? openText(key, row.payload, context(account.userId, kind, id)) : null
  }

  private async writeSealed(account: Account, kind: string, id: string, plain: string | null): Promise<void> {
    const key = this.key(account)
    const payload = plain === null ? '' : await seal(key, plain, context(account.userId, kind, id))
    const { error } = await this.db.from('sealed_items')
      .upsert({ kind, id, payload, updated_at: Date.now(), deleted: plain === null }, { onConflict: 'user_id,kind,id' })
    if (error) throw error
  }

  async parental(account: Account): Promise<Parental | null> {
    const text = await this.sealedRow(account, 'parental', 'main')
    if (!text) return null
    const o = JSON.parse(text) as Partial<Parental>
    return { pinHash: String(o.pinHash ?? ''), autoAdult: o.autoAdult !== false, kidExit: o.kidExit !== false }
  }

  async saveParental(account: Account, value: Parental | null): Promise<void> {
    // Même ordre de champs que SealedParental : l'appli retrouve le même texte et ne le renvoie pas.
    await this.writeSealed(account, 'parental', 'main',
      value && JSON.stringify({ pinHash: value.pinHash, autoAdult: value.autoAdult, kidExit: value.kidExit }))
  }

  async catalog(account: Account, playlistId: string): Promise<{ entries: CatalogEntry[]; prefs: SealedPrefs }> {
    const [catalog, prefs] = await Promise.all([
      this.sealedRow(account, 'catalog', playlistId), this.sealedRow(account, 'prefs', playlistId),
    ])
    const entries = catalog ? ((JSON.parse(catalog) as { categories?: CatalogEntry[] }).categories ?? []) : []
    return { entries, prefs: prefs ? { ...EMPTY_PREFS, ...(JSON.parse(prefs) as SealedPrefs) } : EMPTY_PREFS }
  }

  async setCategoryLock(account: Account, playlistId: string, rest: string, locked: boolean): Promise<void> {
    // Relu juste avant d'écrire : un appareil a pu changer d'autres préférences entre-temps.
    const text = await this.sealedRow(account, 'prefs', playlistId)
    const current = text ? { ...EMPTY_PREFS, ...(JSON.parse(text) as SealedPrefs) } : EMPTY_PREFS
    await this.writeSealed(account, 'prefs', playlistId, JSON.stringify(withCategoryLock(current, rest, locked)))
  }

  // ---------------------------------------------------------------- Commandes aux appareils

  onLive(listener: (m: LiveMessage) => void): () => void {
    this.liveListeners.add(listener)
    return () => this.liveListeners.delete(listener)
  }

  private emitLive(p: Record<string, unknown> | undefined) {
    if (!p || typeof p.type !== 'string') return
    const m: LiveMessage = {
      type: p.type, device: String(p.device ?? ''), req: String(p.req ?? ''), box: String(p.box ?? ''), action: String(p.action ?? ''),
    }
    for (const l of this.liveListeners) l(m)
  }

  private async sealLive(account: Account, type: string, plain: string): Promise<string> {
    return seal(this.key(account), plain, context(account.userId, 'live', type))
  }

  private async openLive(account: Account, type: string, box: string): Promise<string | null> {
    if (!box || !this.vaultKey || this.vaultUser !== account.userId) return null
    try {
      return await openText(this.vaultKey, box, context(account.userId, 'live', type))
    } catch {
      return null
    }
  }

  async checkNow(account: Account, d: Device): Promise<string> {
    const req = requestId()
    await this.broadcast(account, { type: 'device_check', target: d.id, req })
    return req
  }

  async sendMessage(account: Account, d: Device, text: string, from: string): Promise<string> {
    const req = requestId()
    const box = await this.sealLive(account, 'device_message', JSON.stringify({ text: text.trim().slice(0, MAX_MESSAGE), from: from.trim().slice(0, 60) }))
    await this.broadcast(account, { type: 'device_message', target: d.id, req, box })
    void this.logEvent('message_sent', d.label || d.name)
    return req
  }

  async queryPlayback(account: Account): Promise<void> {
    await this.broadcast(account, { type: 'playback_query', req: requestId() })
  }

  async controlPlayback(account: Account, d: Device, action: PlaybackAction): Promise<void> {
    await this.broadcast(account, { type: 'playback_control', target: d.id, action, req: requestId() })
  }

  async openPlayback(account: Account, box: string): Promise<PlaybackState | null> {
    const text = await this.openLive(account, 'playback_state', box)
    return text ? parsePlayback(text, Date.now()) : null
  }

  async searchGuide(account: Account, query: string): Promise<GuideResult[]> {
    const req = requestId()
    const box = await this.sealLive(account, 'guide_search', JSON.stringify({ q: query.trim().slice(0, 80) }))
    const answers: GuideResult[][] = []
    await new Promise<void>((done) => {
      let settle: number | undefined
      const total = window.setTimeout(finish, 6000)
      const stop = this.onLive((m) => {
        if (m.type !== 'guide_results' || m.req !== req) return
        void this.openLive(account, 'guide_results', m.box).then((text) => {
          if (!text) return
          try {
            const items = (JSON.parse(text) as { items?: GuideResult[] }).items ?? []
            answers.push(items.map((g) => ({
              channelKey: String(g.channelKey ?? ''), channel: String(g.channel ?? ''), title: String(g.title ?? ''),
              start: Number(g.start) || 0, stop: Number(g.stop) || 0, desc: String(g.desc ?? ''),
            })))
          } catch {
            return
          }
          // Les autres appareils ont un peu plus d'une seconde pour répondre à leur tour.
          if (settle === undefined) settle = window.setTimeout(finish, 1500)
        })
      })
      function finish() {
        window.clearTimeout(total)
        if (settle !== undefined) window.clearTimeout(settle)
        stop()
        done()
      }
      void this.broadcast(account, { type: 'guide_search', req, box })
    })
    if (answers.length === 0) throw new UserError('Aucun appareil n’a répondu : ouvre Vyzo sur un appareil qui a le guide des programmes')
    return mergeGuide(answers)
  }

  async scheduleRecording(g: GuideResult): Promise<void> {
    const { error } = await this.db.from('recordings').upsert({
      id: recordingId(g), channel_key: g.channelKey, channel_name: g.channel.slice(0, 120), title: g.title.slice(0, 200),
      start_ms: g.start, stop_ms: g.stop, status: 'SCHEDULED', error: '', sender: WEB_SENDER, updated_at: Date.now(), deleted: false,
    }, { onConflict: 'user_id,id' })
    if (error) throw error
  }

  // ---------------------------------------------------------------- Temps d'écran, statistiques

  private async openRows(account: Account, kind: string): Promise<{ id: string; text: string }[]> {
    const key = this.key(account)
    const rows = await this.selectAll<SealedRow>('sealed_items', 'kind,id,payload,updated_at', (q) => q.eq('kind', kind).eq('deleted', false))
    const out: { id: string; text: string }[] = []
    for (const r of rows) {
      try {
        out.push({ id: r.id, text: await openText(key, r.payload, context(account.userId, kind, r.id)) })
      } catch {
        // Illisible (clé remplacée) : ignoré.
      }
    }
    return out
  }

  async screenTime(account: Account): Promise<Map<string, ScreenTimeRule>> {
    const out = new Map<string, ScreenTimeRule>()
    for (const r of await this.openRows(account, 'screentime')) {
      try {
        out.set(r.id, parseRule(r.text))
      } catch {
        // ignoré
      }
    }
    return out
  }

  async saveScreenTime(account: Account, profileId: string, rule: ScreenTimeRule | null): Promise<void> {
    await this.writeSealed(account, 'screentime', profileId, rule && ruleJson(rule))
  }

  async usage(account: Account): Promise<UsageDay[]> {
    return (await this.openRows(account, 'usage')).map((r) => parseUsage(r.text)).filter((u): u is UsageDay => !!u)
  }

  // ---------------------------------------------------------------- Journal

  async events(): Promise<AccountEvent[]> {
    const { data, error } = await this.db.from('account_events').select('id,at,kind,detail').order('at', { ascending: false }).limit(300)
    if (error) throw error
    return (data as { id: number; at: string; kind: string; detail: string }[])
      .map((r) => ({ id: Number(r.id), at: Date.parse(r.at), kind: r.kind, detail: r.detail ?? '' }))
  }

  async logEvent(kind: string, detail = ''): Promise<void> {
    // Le journal ne doit jamais bloquer une action (serveur pas encore à jour, réseau coupé).
    await this.db.from('account_events').insert({ kind, detail: detail.slice(0, 200) }).then(() => undefined, () => undefined)
  }

  // ---------------------------------------------------------------- Sauvegarde

  async exportBackup(account: Account, password: string): Promise<{ file: string; counts: BackupCounts }> {
    const key = this.key(account)
    const strip = (rows: Record<string, unknown>[]) => rows.map(({ user_id: _u, synced_at: _s, ...rest }) => rest)
    const now = Date.now()
    const [profiles, lists, items, progress, recordings, sealedRows] = await Promise.all([
      this.selectAll<Record<string, unknown>>('profiles', '*', (q) => q.eq('deleted', false)),
      this.selectAll<Record<string, unknown>>('user_lists', '*', (q) => q.eq('deleted', false)),
      this.selectAll<Record<string, unknown>>('list_items', '*', (q) => q.eq('deleted', false)),
      this.selectAll<Record<string, unknown>>('watch_progress', '*', (q) => q),
      this.selectAll<Record<string, unknown>>('recordings', '*', (q) => q.eq('deleted', false).gt('stop_ms', now)),
      this.selectAll<SealedRow>('sealed_items', 'kind,id,payload,updated_at', (q) => q.in('kind', BACKUP_SEALED_KINDS).eq('deleted', false)),
    ])
    const sealed: BackupData['sealed'] = []
    for (const r of sealedRows) {
      try {
        // En clair dans la sauvegarde (elle-même chiffrée) : elle se restaure même après un changement de clé du compte.
        sealed.push({ kind: r.kind, id: r.id, plain: await openText(key, r.payload, context(account.userId, r.kind, r.id)) })
      } catch {
        // Bloc illisible : pas sauvegardé.
      }
    }
    const data: BackupData = {
      createdAt: now, profiles: strip(profiles), lists: strip(lists), items: strip(items), progress: strip(progress),
      recordings: strip(recordings), sealed,
    }
    const salt = crypto.getRandomValues(new Uint8Array(16))
    const iterations = 600_000
    const kek = await deriveKey(password, salt, iterations)
    const file: BackupFile = {
      format: BACKUP_FORMAT, version: BACKUP_VERSION, createdAt: now, salt: toBase64(salt), iterations,
      box: await seal(kek, JSON.stringify(data), BACKUP_CONTEXT),
    }
    void this.logEvent('backup_export')
    return { file: JSON.stringify(file), counts: backupCounts(data) }
  }

  async readBackup(text: string, password: string): Promise<BackupData | null> {
    const f = parseBackupFile(text)
    const kek = await deriveKey(password, fromBase64(f.salt), f.iterations)
    let plain: string
    try {
      plain = await openText(kek, f.box, BACKUP_CONTEXT)
    } catch {
      return null
    }
    return parseBackupData(plain)
  }

  async restoreBackup(account: Account, data: BackupData): Promise<BackupCounts> {
    const key = this.key(account)
    const now = Date.now()
    // Plus récent que tout : la version restaurée l'emporte sur les appareils (dernier écrit gagnant).
    const fresh = (rows: Record<string, unknown>[], extra: Record<string, unknown> = {}) =>
      rows.map(({ user_id: _u, synced_at: _s, ...rest }) => ({ ...rest, ...extra, updated_at: now }))
    const put = async (table: string, rows: Record<string, unknown>[], onConflict: string) => {
      for (let i = 0; i < rows.length; i += 500) {
        const { error } = await this.db.from(table).upsert(rows.slice(i, i + 500), { onConflict })
        if (error) throw error
      }
    }
    await put('profiles', fresh(data.profiles, { deleted: false }), 'user_id,id')
    await put('user_lists', fresh(data.lists, { deleted: false }), 'user_id,profile_id,id')
    await put('list_items', fresh(data.items, { deleted: false }), 'user_id,profile_id,list_id,content_key')
    await put('watch_progress', fresh(data.progress), 'user_id,profile_id,content_key')
    await put('recordings', fresh(data.recordings.filter((r) => Number(r.stop_ms) > now), { deleted: false, sender: WEB_SENDER }), 'user_id,id')
    const sealedRows = await Promise.all(data.sealed.map(async (x) => ({
      kind: x.kind, id: x.id, payload: await seal(key, x.plain, context(account.userId, x.kind, x.id)), updated_at: now, deleted: false,
    })))
    await put('sealed_items', sealedRows, 'user_id,kind,id')
    void this.logEvent('backup_restore')
    return backupCounts(data)
  }

  // ---------------------------------------------------------------- Outils

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private async selectAll<T>(table: string, columns: string, build: (q: any) => any): Promise<T[]> {
    const out: T[] = []
    for (let from = 0; ; from += PAGE) {
      const { data, error } = await build(this.db.from(table).select(columns)).range(from, from + PAGE - 1)
      if (error) throw error
      out.push(...(data as T[]))
      if ((data as T[]).length < PAGE) return out
    }
  }

  /** Message temps réel aux appareils du compte, au format lu par RealtimeClient (payload.type + payload.command). */
  private async broadcast(account: Account, payload: Record<string, unknown>): Promise<void> {
    const ch = this.channel
    if (ch && ch.topic === `realtime:user:${account.userId}` && ch.state === 'joined') {
      await ch.send({ type: 'broadcast', event: 'broadcast', payload })
      return
    }
    // Pas encore abonné : envoi ponctuel par un canal éphémère.
    const once = this.db.channel(`user:${account.userId}`, { config: { private: true } })
    await new Promise<void>((done) => {
      once.subscribe(async (status) => {
        if (status === 'SUBSCRIBED') {
          await once.send({ type: 'broadcast', event: 'broadcast', payload })
          done()
        } else if (status === 'CHANNEL_ERROR' || status === 'TIMED_OUT' || status === 'CLOSED') done()
      })
    })
    await this.db.removeChannel(once)
  }
}

/** Navigateur et système, pour le journal (« Firefox · Linux »). */
function navigatorLabel(): string {
  const ua = navigator.userAgent
  const browser = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Navigateur'
  const os = /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS' : /Windows/.test(ua) ? 'Windows' : /Mac OS X/.test(ua) ? 'macOS' : /Linux/.test(ua) ? 'Linux' : ''
  return os ? `${browser} · ${os}` : browser
}
