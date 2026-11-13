import { UserError, type Account, type Backend, type ListsData, type PlaylistDraft, type Table, type VaultStatus } from './backend'
import {
  nextUpdatedAt,
  type Device, type ListItem, type Playlist, type Profile, type ProfileActivity, type ProgressItem, type Recording,
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
  private unlocked = false

  private profileList: Profile[] = [
    { id: 'p1', name: 'Yannick', colorIndex: 0, isKid: false, pinHash: '', position: 0, updatedAt: 1 },
    { id: 'p2', name: 'Salon', colorIndex: 1, isKid: false, pinHash: 'x', position: 1, updatedAt: 1 },
    { id: 'p3', name: 'Enfants', colorIndex: 4, isKid: true, pinHash: '', position: 2, updatedAt: 1 },
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
    this.dev('d1', 'Salon (Fire TV)', 'tv', 'Amazon AFTKA', 'Android 11 (API 30)', '1.12.0-beta3', 36, 4 * 60_000),
    this.dev('d2', 'Vyzo PC (MAISON)', 'pc', 'PC', 'Linux Mint 22.3', '1.12.0-beta3', 36, 40_000),
    this.dev('d3', 'Pixel 8', 'mobile', 'Google Pixel 8', 'Android 15 (API 35)', '1.11.8', 33, 3 * D),
  ]

  private playlistList: Playlist[] = [
    {
      id: 'a94c7edf66d1', name: 'Abonnement principal', type: 'XTREAM', url: 'http://exemple-iptv.tv:8080',
      username: 'demo-user', password: 'demo-pass', mac: '', userAgent: '', updatedAt: 1, hasPrefs: true,
    },
    {
      id: 'bf01aa807023', name: 'Chaînes gratuites', type: 'M3U', url: 'https://iptv-org.example/fr.m3u',
      username: '', password: '', mac: '', userAgent: '', updatedAt: 1, hasPrefs: false,
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
      id, name, kind, model, system, appVersion, versionCode, lastSeenAt: this.now - ago,
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

  async signIn(email: string, password: string) {
    await this.wait()
    if (!password) throw new Error('E-mail ou mot de passe incorrect')
    this.account = { userId: 'demo', email: email || 'demo@vyzo.local' }
    this.unlocked = true
    return this.account
  }

  async signOut() {
    this.account = null
    this.unlocked = false
  }

  watch(_account: Account, onChange: (table: Table) => void) {
    this.listeners.add(onChange)
    return () => { this.listeners.delete(onChange) }
  }

  async vaultStatus(): Promise<VaultStatus> {
    return this.unlocked ? 'open' : 'locked'
  }

  async unlockVault(_account: Account, password: string) {
    await this.wait(500)
    this.unlocked = password === 'demo'
    return this.unlocked
  }

  lockVault() {
    this.unlocked = false
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
    return { versionCode: 36, versionName: '1.12.0-beta3' }
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
      if (i === steps.length - 1) { cur.appVersion = '1.12.0-beta3'; cur.versionCode = 36; cur.lastSeenAt = Date.now() }
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
    this.playlistList.push({ ...draft, id, updatedAt: Date.now(), hasPrefs: previous?.id === id && previous.hasPrefs })
    this.changed('sealed_items')
  }

  async deletePlaylist(_account: Account, p: Playlist) {
    await this.wait()
    this.playlistList = this.playlistList.filter((o) => o.id !== p.id)
    this.changed('sealed_items')
  }
}
