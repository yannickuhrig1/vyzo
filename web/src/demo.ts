import {
  UserError, backupCounts, requestId,
  type Account, type Backend, type BackupData, type CastItem, type ListsData, type LiveMessage, type PlaybackAction, type PlaylistDraft, type Table,
  type PasswordResetResult, type VaultStatus,
} from './backend'
import {
  dayKey, mergeGuide, nextUpdatedAt, recordingId, withCategoryLock,
  type AccountEvent, type BackupCounts, type EmailLinkParams, type EmailLinkType, emailLinkError, type GuideResult, type PlaybackState, type ScreenTimeRule, type UsageDay,
  type CatalogEntry, type Device, type Parental, type SealedPrefs, type ListItem, type Playlist, type Profile, type ProfileActivity, type ProgressItem, type Recording,
  type RecordingStatus, type UserList,
} from './model'
import { playlistKey } from './vault'

const H = 3600_000
const D = 24 * H

/** Compte fictif en mémoire, pour le développement (adresse ?demo, uniquement avec « npm run dev »). */
export class DemoBackend implements Backend {
  readonly demo = true
  private account: Account | null = { userId: 'demo', email: 'demo@vyzo.local' }
  private listeners = new Set<(t: Table) => void>()
  private now = Date.now()
  /** « Rester déverrouillé » simulé : survit au rechargement de la page. */
  private unlocked = sessionStorage.getItem('vyzo-demo-remember') === '1'

  private profileList: Profile[] = [
    { id: 'p1', name: 'Yannick', colorIndex: 0, isKid: false, pinHash: '', avatar: 'astronaut', position: 0, updatedAt: 1 },
    { id: 'p2', name: 'Salon', colorIndex: 1, isKid: false, pinHash: 'x', avatar: '', position: 1, updatedAt: 1 },
    { id: 'p3', name: 'Enfants', colorIndex: 4, isKid: true, pinHash: '', avatar: 'star', position: 2, updatedAt: 1 },
  ]

  private progressList: ProgressItem[] = [
    this.prog('p1', 'k1:series:9:ep:1203', 'SERIES', 0.42, 52, 2 * H, 'Le Bureau des légendes', 'S3 · É4 · Le Mur'),
    this.prog('p1', 'k1:vod:5521', 'MOVIE', 0.78, 128, 26 * H, 'Dune : deuxième partie', '2024'),
    this.prog('p1', 'k1:live:88', 'LIVE', 0, 0, 3 * D, 'France 24', ''),
    this.prog('p1', 'k1:vod:771', 'MOVIE', 0.12, 104, 6 * D, 'Anatomie d’une chute', '2023'),
    this.prog('p1', 'k1:vod:900', 'MOVIE', 0.5, 95, 9 * D, '', ''),
    this.prog('p2', 'k1:series:12:ep:88', 'SERIES', 0.9, 45, 5 * D, 'Engrenages', 'S8 · É10'),
  ]

  private listRows: UserList[] = [
    { profileId: 'p1', id: 'l-weekend', name: 'Pour le week-end', position: 1, updatedAt: 1 },
  ]

  private itemRows: ListItem[] = [
    this.item('p1', 'fav', 'k1:live:12', 'LIVE', 'TF1 HD', ''),
    this.item('p1', 'fav', 'k1:live:88', 'LIVE', 'France 24', ''),
    this.item('p1', 'fav', 'k1:series:9', 'SERIES', 'Le Bureau des légendes', '2015'),
    this.item('p1', 'l-weekend', 'k1:vod:5521', 'MOVIE', 'Dune : deuxième partie', '2024'),
    this.item('p1', 'l-weekend', 'k1:vod:6001', 'MOVIE', 'Le Comte de Monte-Cristo', '2024'),
    this.item('p3', 'fav', 'k1:vod:42', 'MOVIE', 'Vice-versa 2', '2024'),
  ]

  private recordingList: Recording[] = [
    this.rec('r1', 'TF1 HD', 'Le Journal de 20h', 2 * H, 2.6 * H, 'SCHEDULED'),
    this.rec('r2', 'France 2', 'Envoyé spécial', 26 * H, 28 * H, 'SCHEDULED'),
    this.rec('r3', 'Canal+ Sport', 'Ligue 1 : PSG – OM', -0.5 * H, 1.5 * H, 'RECORDING'),
    this.rec('r4', 'Arte', 'Le Dessous des cartes', -50 * H, -49.5 * H, 'DONE'),
    this.rec('r5', 'M6', 'Top Chef', -74 * H, -72 * H, 'FAILED', 'Flux interrompu après 12 min'),
    this.rec('r6', 'France 5', 'C dans l’air', -98 * H, -97 * H, 'CANCELLED'),
  ]

  private deviceList: Device[] = [
    this.dev('d1', 'Salon (Fire TV)', 'tv', 'Amazon AFTKA', 'Android 11 (API 30)', '1.13.0-beta3', 41, 4 * 60_000),
    this.dev('d2', 'Vyzo PC (MAISON)', 'pc', 'PC', 'Linux Mint 22.3', '1.13.0-beta3', 41, 40_000),
    this.dev('d3', 'Pixel 8', 'mobile', 'Google Pixel 8', 'Android 15 (API 35)', '1.11.8', 33, 3 * D),
  ]

  private playlistList: Playlist[] = [
    {
      id: 'a94c7edf66d1', name: 'Abonnement principal', type: 'XTREAM', url: 'http://exemple-iptv.tv:8080',
      username: 'demo-user', password: 'demo-pass', mac: '', userAgent: '', updatedAt: 1, hasPrefs: true,
      status: { expiresAt: Date.now() + 9 * D, maxConnections: 2, status: 'Active', checkedDay: Date.now() - (Date.now() % D) },
    },
    {
      id: 'bf01aa807023', name: 'Chaînes gratuites', type: 'M3U', url: 'https://iptv-org.example/fr.m3u',
      username: '', password: '', mac: '', userAgent: '', updatedAt: 1, hasPrefs: false, status: null,
    },
  ]

  private prog(profileId: string, contentKey: string, kind: ProgressItem['kind'], ratio: number, minutes: number, ago: number, title: string, subtitle: string): ProgressItem {
    return {
      profileId, contentKey, kind, positionMs: ratio * minutes * 60_000, durationMs: minutes * 60_000,
      updatedAt: this.now - ago, title, subtitle,
    }
  }

  private item(profileId: string, listId: string, contentKey: string, kind: ListItem['kind'], title: string, subtitle: string): ListItem {
    return { profileId, listId, contentKey, kind, position: 0, addedAt: this.now - 5 * D, updatedAt: 1, title, subtitle }
  }

  private rec(id: string, channel: string, title: string, start: number, stop: number, status: RecordingStatus, error = ''): Recording {
    return {
      id, channelKey: `demo:${id}`, channelName: channel, title, startMs: this.now + start, stopMs: this.now + stop,
      status, error, sender: 'Vyzo PC', updatedAt: 1,
    }
  }

  private dev(id: string, name: string, kind: Device['kind'], model: string, system: string, appVersion: string, versionCode: number, ago: number): Device {
    return {
      id, name, label: '', kind, model, system, appVersion, versionCode, lastSeenAt: this.now - ago,
      updateRequestedAt: 0, updateStatus: '', updateStatusAt: 0,
    }
  }

  private changed(t: Table) {
    for (const l of this.listeners) l(t)
  }

  private wait(ms = 150) {
    return new Promise((r) => setTimeout(r, ms))
  }

  async currentAccount() {
    return this.account
  }

  async signIn(email: string, password: string, remember = false) {
    await this.wait()
    if (!password) throw new Error('E-mail ou mot de passe incorrect')
    this.account = { userId: 'demo', email: email || 'demo@vyzo.local' }
    this.unlocked = true
    if (remember) sessionStorage.setItem('vyzo-demo-remember', '1')
    return this.account
  }

  async signOut() {
    this.account = null
    await this.lockVault()
  }

  watch(_account: Account, onChange: (table: Table) => void) {
    this.listeners.add(onChange)
    return () => { this.listeners.delete(onChange) }
  }

  async vaultStatus(): Promise<VaultStatus> {
    return this.unlocked ? 'open' : 'locked'
  }

  async unlockVault(_account: Account, password: string, remember = false) {
    await this.wait(500)
    this.unlocked = password === 'demo'
    if (this.unlocked && remember) sessionStorage.setItem('vyzo-demo-remember', '1')
    return this.unlocked
  }

  async checkPassword(_account: Account, password: string) {
    await this.wait(400)
    return password === 'demo' || password === 'nouveau'
  }

  /** Démo : « nouveau » joue un mot de passe changé par « Mot de passe oublié », la clé restant sous « demo ». */
  async recoverVault(_account: Account, current: string, old: string, remember = false) {
    await this.wait(700)
    if (current !== 'nouveau') throw new UserError('Ce n’est pas le mot de passe actuel du compte')
    if (old !== 'demo') throw new UserError('L’ancien mot de passe n’ouvre pas les données chiffrées')
    this.unlocked = true
    if (remember) sessionStorage.setItem('vyzo-demo-remember', '1')
  }

  async lockVault() {
    this.unlocked = false
    sessionStorage.removeItem('vyzo-demo-remember')
  }

  async changePassword(_account: Account, current: string, next: string) {
    await this.wait(900)
    if (current !== 'demo') throw new UserError('Mot de passe actuel incorrect')
    if (!next) throw new UserError('Nouveau mot de passe vide')
  }

  async signOutOthers() {
    await this.wait(400)
  }

  async requestPasswordReset() {
    await this.wait(600)
  }

  async resendConfirmation() {
    await this.wait(600)
  }

  async verifyEmailLink(link: EmailLinkParams): Promise<{ type: EmailLinkType; account: Account }> {
    await this.wait(500)
    if (link.mode === 'error') throw new UserError(emailLinkError(link.code))
    return { type: link.type, account: { userId: 'demo', email: 'demo@vyzo.local' } }
  }

  async resetPassword(_account: Account, _next: string, old = ''): Promise<PasswordResetResult> {
    await this.wait(800)
    if (old && old !== 'demo') throw new UserError('L’ancien mot de passe n’ouvre pas les données chiffrées : rien n’a été changé. Laisse le champ vide si tu ne t’en souviens pas.')
    return { vault: old ? 'kept' : 'pending', othersSignedOut: true }
  }

  async forgetRemembered() {
    sessionStorage.removeItem('vyzo-demo-remember')
  }

  async vaultRemembered() {
    return sessionStorage.getItem('vyzo-demo-remember') === '1'
  }

  async profiles() {
    await this.wait()
    return this.profileList.map((p) => ({ ...p }))
  }

  async saveProfiles(list: Profile[]) {
    await this.wait()
    for (const p of list) {
      const saved = { ...p, updatedAt: nextUpdatedAt(p.updatedAt) }
      const i = this.profileList.findIndex((o) => o.id === p.id)
      if (i >= 0) this.profileList[i] = saved
      else this.profileList.push(saved)
    }
    this.changed('profiles')
  }

  async deleteProfile(p: Profile) {
    await this.wait()
    this.profileList = this.profileList.filter((o) => o.id !== p.id)
    this.progressList = this.progressList.filter((o) => o.profileId !== p.id)
    this.itemRows = this.itemRows.filter((o) => o.profileId !== p.id)
    this.changed('profiles')
  }

  async activity(profiles: Profile[]): Promise<ProfileActivity[]> {
    await this.wait()
    return profiles.map((p) => {
      const mine = this.progressList.filter((x) => x.profileId === p.id)
      return {
        profileId: p.id, inProgress: mine.length, inLists: this.itemRows.filter((x) => x.profileId === p.id).length,
        lastWatchedAt: mine.length ? Math.max(...mine.map((x) => x.updatedAt)) : null,
      }
    })
  }

  async clearHistory(_account: Account, profileId: string) {
    await this.wait()
    this.progressList = this.progressList.filter((x) => x.profileId !== profileId)
    this.changed('watch_progress')
  }

  private hide<T extends { title: string; subtitle: string }>(x: T): T {
    return this.unlocked ? { ...x } : { ...x, title: '', subtitle: '' }
  }

  async progress(_account: Account, profileId: string) {
    await this.wait()
    return this.progressList.filter((x) => x.profileId === profileId).sort((a, b) => b.updatedAt - a.updatedAt).map((x) => this.hide(x))
  }

  async removeProgress(_account: Account, profileId: string, keys: string[]) {
    await this.wait()
    this.progressList = this.progressList.filter((x) => x.profileId !== profileId || !keys.includes(x.contentKey))
    this.changed('watch_progress')
  }

  async lists(_account: Account, profileId: string): Promise<ListsData> {
    await this.wait()
    return {
      lists: this.listRows.filter((l) => l.profileId === profileId).map((l) => ({ ...l })),
      items: this.itemRows.filter((x) => x.profileId === profileId).map((x) => this.hide(x)),
    }
  }

  async saveList(list: UserList) {
    await this.wait()
    const i = this.listRows.findIndex((l) => l.id === list.id && l.profileId === list.profileId)
    if (i >= 0) this.listRows[i] = { ...list }
    else this.listRows.push({ ...list })
    this.changed('user_lists')
  }

  async deleteList(list: UserList) {
    await this.wait()
    this.listRows = this.listRows.filter((l) => !(l.id === list.id && l.profileId === list.profileId))
    this.itemRows = this.itemRows.filter((x) => !(x.listId === list.id && x.profileId === list.profileId))
    this.changed('user_lists')
  }

  async removeListItem(item: ListItem) {
    await this.wait()
    this.itemRows = this.itemRows.filter((x) => !(x.listId === item.listId && x.profileId === item.profileId && x.contentKey === item.contentKey))
    this.changed('list_items')
  }

  async recordings() {
    await this.wait()
    return this.recordingList.map((r) => ({ ...r }))
  }

  async setRecordingStatus(r: Recording, status: RecordingStatus) {
    await this.wait()
    const cur = this.recordingList.find((o) => o.id === r.id)
    if (cur) { cur.status = status; cur.sender = 'Vyzo Web'; cur.updatedAt = nextUpdatedAt(cur.updatedAt) }
    this.changed('recordings')
  }

  async removeRecording(r: Recording) {
    await this.wait()
    this.recordingList = this.recordingList.filter((o) => o.id !== r.id)
    this.changed('recordings')
  }

  async devices() {
    await this.wait()
    return this.deviceList.map((d) => ({ ...d }))
  }

  async latestVersion() {
    return { versionCode: 41, versionName: '1.13.0-beta3' }
  }

  async requestUpdate(d: Device) {
    await this.wait()
    const cur = this.deviceList.find((o) => o.id === d.id)
    if (!cur) return
    cur.updateRequestedAt = Date.now()
    cur.updateStatus = ''
    cur.updateStatusAt = 0
    this.changed('devices')
    // L'appareil répond comme le ferait l'appli.
    const steps = ['Recherche d’une nouvelle version…', 'Téléchargement de la version 1.12.0-beta3…', 'Installation de la version 1.12.0-beta3 lancée']
    steps.forEach((text, i) => setTimeout(() => {
      cur.updateStatus = text
      cur.updateStatusAt = Date.now()
      if (i === steps.length - 1) { cur.appVersion = '1.13.0-beta3'; cur.versionCode = 41; cur.lastSeenAt = Date.now() }
      this.changed('devices')
    }, 1200 * (i + 1)))
  }

  async removeDevice(d: Device) {
    await this.wait()
    this.deviceList = this.deviceList.filter((o) => o.id !== d.id)
    this.changed('devices')
  }

  async playlists() {
    await this.wait()
    if (!this.unlocked) throw new UserError('Données chiffrées verrouillées : saisis le mot de passe du compte')
    return this.playlistList.map((p) => ({ ...p })).sort((a, b) => a.name.localeCompare(b.name, 'fr'))
  }

  async savePlaylist(_account: Account, draft: PlaylistDraft, previous: Playlist | null, all: Playlist[]) {
    await this.wait()
    const id = await playlistKey(draft.type, draft.url, draft.username, draft.mac)
    const clash = all.find((p) => p.id === id && p.id !== previous?.id)
    if (clash) throw new UserError(`Cet abonnement est déjà sur le compte (« ${clash.name} »)`)
    if (previous) this.playlistList = this.playlistList.filter((p) => p.id !== previous.id)
    this.playlistList.push({ ...draft, id, updatedAt: Date.now(), hasPrefs: previous?.id === id && previous.hasPrefs, status: previous?.id === id ? previous.status : null })
    this.changed('sealed_items')
  }

  async deletePlaylist(_account: Account, p: Playlist) {
    await this.wait()
    this.playlistList = this.playlistList.filter((o) => o.id !== p.id)
    this.changed('sealed_items')
  }

  async castTo(_account: Account, _device: Device, _item: CastItem) {
    await this.wait(300)
  }

  private parentalValue: Parental | null = null
  private prefs: Record<string, SealedPrefs> = {
    a94c7edf66d1: { categories: [{ rest: 'C:L:12', hidden: false, position: null, locked: true, name: '' }], channels: [] },
  }

  async parental() {
    await this.wait()
    return this.parentalValue && { ...this.parentalValue }
  }

  async saveParental(_account: Account, value: Parental | null) {
    await this.wait()
    this.parentalValue = value && { ...value }
    this.changed('sealed_items')
  }

  async catalog(_account: Account, playlistId: string) {
    await this.wait()
    const names: [CatalogEntry['kind'], string, boolean][] = playlistId === 'a94c7edf66d1'
      ? [['LIVE', 'FR | Généralistes', false], ['LIVE', 'FR | Sport', false], ['LIVE', 'FR | Jeunesse', false], ['LIVE', 'Adultes 18+', true],
        ['LIVE', 'FR | Info', false], ['MOVIE', 'Films | Nouveautés', false], ['MOVIE', 'Films | Horreur', false], ['MOVIE', 'XXX Films', true],
        ['SERIES', 'Séries | Netflix', false], ['SERIES', 'Séries | Animation', false]]
      : [['LIVE', 'Toutes les chaînes', false]]
    const letter = { LIVE: 'L', MOVIE: 'M', SERIES: 'S' } as const
    const entries: CatalogEntry[] = names.map(([kind, name, adult], i) => ({ rest: `C:${letter[kind]}:${10 + i}`, name, kind, adult }))
    return { entries, prefs: this.prefs[playlistId] ?? { categories: [], channels: [] } }
  }

  async setCategoryLock(_account: Account, playlistId: string, rest: string, locked: boolean) {
    await this.wait(250)
    this.prefs[playlistId] = withCategoryLock(this.prefs[playlistId] ?? { categories: [], channels: [] }, rest, locked)
    this.changed('sealed_items')
  }

  // ---------------------------------------------------------------- 1.13.0-beta3

  private liveListeners = new Set<(m: LiveMessage) => void>()
  private playing = new Map<string, PlaybackState>([
    ['d1', { state: 'playing', title: 'Dune : deuxième partie', detail: '', kind: 'movie', positionMs: 74 * 60_000, durationMs: 166 * 60_000, profile: 'Yannick', receivedAt: Date.now() }],
    ['d2', { state: 'idle', title: '', detail: '', kind: '', positionMs: 0, durationMs: 0, profile: 'Yannick', receivedAt: Date.now() }],
  ])
  private rules = new Map<string, ScreenTimeRule>([
    ['p3', { enabled: true, dailyMinutes: 90, from: '07:30', to: '20:00', weekendFrom: '08:00', weekendTo: '21:00' }],
  ])
  private eventList: AccountEvent[] = [
    { id: 6, at: this.now - 12 * 60_000, kind: 'site_signin', detail: 'Firefox · Linux' },
    { id: 5, at: this.now - 3 * H, kind: 'recording_added', detail: 'Le Journal de 20h · TF1 HD' },
    { id: 4, at: this.now - 26 * H, kind: 'device_updated', detail: 'Salon (Fire TV) · 1.13.0-beta3' },
    { id: 3, at: this.now - 2 * D, kind: 'parental_changed', detail: '' },
    { id: 2, at: this.now - 5 * D, kind: 'device_added', detail: 'Pixel 8' },
    { id: 1, at: this.now - 9 * D, kind: 'playlist_added', detail: '' },
  ]

  private emit(m: Partial<LiveMessage> & { type: string }, ms = 500) {
    setTimeout(() => { for (const l of this.liveListeners) l({ device: '', req: '', box: '', action: '', ...m }) }, ms)
  }

  private log(kind: string, detail = '') {
    this.eventList.unshift({ id: Date.now(), at: Date.now(), kind, detail })
  }

  async renameDevice(d: Device, label: string) {
    await this.wait()
    const x = this.deviceList.find((y) => y.id === d.id)
    if (x) x.label = label.trim()
    this.log('device_renamed', label.trim() || d.name)
    this.changed('devices')
  }

  onLive(listener: (m: LiveMessage) => void) {
    this.liveListeners.add(listener)
    return () => { this.liveListeners.delete(listener) }
  }

  async checkNow(_account: Account, d: Device) {
    const req = requestId()
    this.emit({ type: 'device_ack', device: d.id, req, action: 'check' }, 300)
    this.emit({ type: 'device_ack', device: d.id, req, action: 'check_done' }, 2200)
    return req
  }

  async sendMessage(_account: Account, d: Device) {
    const req = requestId()
    this.emit({ type: 'device_ack', device: d.id, req, action: 'message' }, 600)
    this.log('message_sent', d.label || d.name)
    return req
  }

  async queryPlayback() {
    for (const id of this.playing.keys()) this.emit({ type: 'playback_state', device: id, box: id }, 300 + Math.random() * 400)
  }

  async controlPlayback(_account: Account, d: Device, action: PlaybackAction) {
    const p = this.playing.get(d.id)
    if (!p) return
    const pos = p.state === 'playing' ? p.positionMs + Date.now() - p.receivedAt : p.positionMs
    this.playing.set(d.id, action === 'stop'
      ? { ...p, state: 'idle', title: '', detail: '', kind: '', positionMs: 0, durationMs: 0, receivedAt: Date.now() }
      : { ...p, state: action === 'pause' ? 'paused' : 'playing', positionMs: pos, receivedAt: Date.now() })
    this.emit({ type: 'playback_state', device: d.id, box: d.id }, 700)
  }

  async openPlayback(_account: Account, box: string) {
    const p = this.playing.get(box)
    return p ? { ...p } : null
  }

  async searchGuide(_account: Account, query: string) {
    await this.wait(1200)
    const q = query.toLowerCase()
    const day = new Date(this.now)
    day.setHours(0, 0, 0, 0)
    const base = day.getTime()
    const all: GuideResult[] = [
      { channelKey: 'demo:tf1', channel: 'TF1 HD', title: 'Le Journal de 20h', start: base + D + 20 * H, stop: base + D + 20.6 * H, desc: 'L’actualité du jour.' },
      { channelKey: 'demo:tf1', channel: 'TF1 HD', title: 'Le Journal de 20h', start: base + 2 * D + 20 * H, stop: base + 2 * D + 20.6 * H, desc: '' },
      { channelKey: 'demo:f2', channel: 'France 2', title: 'Journal de 13h', start: base + D + 13 * H, stop: base + D + 13.7 * H, desc: '' },
      { channelKey: 'demo:arte', channel: 'Arte', title: 'Le Dessous des cartes', start: base + D + 19.75 * H, stop: base + D + 20 * H, desc: 'Géopolitique.' },
      { channelKey: 'demo:c+s', channel: 'Canal+ Sport', title: 'Ligue 1 : Lyon – Lens', start: base + 2 * D + 21 * H, stop: base + 2 * D + 23 * H, desc: '' },
    ]
    return mergeGuide([all.filter((g) => g.title.toLowerCase().includes(q) || g.channel.toLowerCase().includes(q))])
  }

  async scheduleRecording(g: GuideResult) {
    await this.wait()
    this.recordingList.unshift({
      id: recordingId(g), channelKey: g.channelKey, channelName: g.channel, title: g.title, startMs: g.start, stopMs: g.stop,
      status: 'SCHEDULED', error: '', sender: 'Vyzo Web', updatedAt: Date.now(),
    })
    this.log('recording_added', `${g.title} · ${g.channel}`)
    this.changed('recordings')
  }

  async screenTime() {
    await this.wait()
    return new Map([...this.rules].map(([k, v]) => [k, { ...v }]))
  }

  async saveScreenTime(_account: Account, profileId: string, rule: ScreenTimeRule | null) {
    await this.wait()
    if (rule) this.rules.set(profileId, { ...rule })
    else this.rules.delete(profileId)
    this.log('screentime_changed')
    this.changed('sealed_items')
  }

  async usage(): Promise<UsageDay[]> {
    await this.wait()
    const out: UsageDay[] = []
    const titles = [['Dune : deuxième partie', 'movie'], ['Le Bureau des légendes', 'episode'], ['France 24', 'live'], ['TF1 HD', 'live'],
      ['Vice-versa 2', 'movie'], ['Bluey', 'episode'], ['Engrenages', 'episode']] as const
    let seed = 7
    const rnd = () => (seed = (seed * 9301 + 49297) % 233280) / 233280
    for (let i = 0; i < 30; i++) {
      for (const [profile, device, scale] of [['p1', 'd1', 1], ['p1', 'd3', 0.3], ['p3', 'd1', 0.8], ['p2', 'd1', 0.5]] as const) {
        if (rnd() < 0.25) continue
        const items = titles.filter(() => rnd() < 0.4).map(([t]) => ({ t, ms: Math.round(rnd() * 70 * scale) * 60_000 })).filter((x) => x.ms > 0)
        if (!items.length) continue
        const u: UsageDay = { profile, device, day: dayKey(this.now - i * D), totalMs: 0, liveMs: 0, movieMs: 0, episodeMs: 0, otherMs: 0, items }
        for (const it of items) {
          const kind = titles.find(([t]) => t === it.t)![1]
          u.totalMs += it.ms
          if (kind === 'live') u.liveMs += it.ms
          else if (kind === 'movie') u.movieMs += it.ms
          else u.episodeMs += it.ms
        }
        out.push(u)
      }
    }
    return out
  }

  async events() {
    await this.wait()
    return this.eventList.map((e) => ({ ...e }))
  }

  async logEvent(kind: string, detail = '') {
    this.log(kind, detail)
  }

  async exportBackup(): Promise<{ file: string; counts: BackupCounts }> {
    await this.wait(900)
    const data: BackupData = {
      createdAt: Date.now(), profiles: this.profileList.map((p) => ({ ...p })), lists: this.listRows.map((l) => ({ ...l })),
      items: this.itemRows.map((i) => ({ ...i })), progress: this.progressList.map((p) => ({ ...p })), recordings: [],
      sealed: this.playlistList.map((p) => ({ kind: 'playlist', id: p.id, plain: '{}' })),
    }
    this.log('backup_export')
    return { file: JSON.stringify({ format: 'vyzo-backup', version: 1, createdAt: data.createdAt, salt: 'AAAA', iterations: 600000, box: 'demo', demo: data }), counts: backupCounts(data) }
  }

  async readBackup(text: string, password: string) {
    await this.wait(900)
    const o = JSON.parse(text) as { format?: string; demo?: BackupData }
    if (o.format !== 'vyzo-backup') throw new UserError('Ce fichier n’est pas une sauvegarde Vyzo')
    return password === 'demo' && o.demo ? o.demo : null
  }

  async restoreBackup(_account: Account, data: BackupData) {
    await this.wait(900)
    this.log('backup_restore')
    return backupCounts(data)
  }
}
