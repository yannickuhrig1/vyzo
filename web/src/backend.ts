import { createClient, type RealtimeChannel, type SupabaseClient } from '@supabase/supabase-js'
import {
  FAVORITES_LIST_ID, WEB_SENDER, nextUpdatedAt,
  type ContentKind, type Device, type DeviceKind, type LatestVersion, type ListItem, type Playlist, type Profile,
  type ProfileActivity, type ProgressItem, type Recording, type RecordingStatus, type UserList,
} from './model'
import {
  context, metaContext, openText, parseMeta, parsePlaylist, playlistJson, playlistKey, seal, unwrapVault,
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
export type PlaylistDraft = Omit<Playlist, 'id' | 'updatedAt' | 'hasPrefs'>

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
}

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
  id: string; name: string; kind: DeviceKind; model: string; system: string; app_version: string; version_code: number
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
  id: r.id, name: r.name, kind: r.kind ?? '', model: r.model ?? '', system: r.system ?? '', appVersion: r.app_version ?? '',
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
    const { data, error } = await this.db.from('devices')
      .select('id,name,kind,model,system,app_version,version_code,last_seen_at,update_requested_at,update_status,update_status_at')
      .order('last_seen_at', { ascending: false }).limit(PAGE)
    if (error) throw error
    return (data as DeviceRow[]).map(toDevice)
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
      (q) => q.in('kind', ['playlist', 'prefs']).eq('deleted', false))
    const prefs = new Set(rows.filter((r) => r.kind === 'prefs').map((r) => r.id))
    const out: Playlist[] = []
    for (const r of rows.filter((x) => x.kind === 'playlist')) {
      try {
        const p = parsePlaylist(await openText(key, r.payload, context(account.userId, 'playlist', r.id)))
        out.push({ ...p, id: r.id, updatedAt: Number(r.updated_at), hasPrefs: prefs.has(r.id) })
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
    const { error } = await this.db.from('sealed_items').upsert(rows, { onConflict: 'user_id,kind,id' })
    if (error) throw error
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
