import { describe, expect, it } from 'vitest'
import {
  cleanAvatar, cleanName, hashPin, isOnline, isValidPin, moveProfile, moveUserList, needsUpdate, nextColor, nextUpdatedAt,
  PROFILE_PALETTE, profileColor, progressRatio, parseXtreamUrl, todayScreenTime, upcomingRecordingAlert, lastBackupAt,
  recordingGroup, statusLabel, updateInProgress, updatePending,
  CAST_MIN_VERSION, castItemType, castReadiness, categoryLocked, hashParental, subscriptionState, withCategoryLock,
  withCategoryPref, withChannelPref, removeChannelPref, prefsJson, parseSealedPrefs,
  epgSourceId, epgJson, moveEpgSource, DEFAULT_SYNCED_SETTINGS, parseSyncedSettings, settingsJson,
  compareUsagePeriods, usageByProfile, dayUsageBreakdown, usageToCsv,
  type CatalogEntry, type Device, type EpgSource, type PlaylistStatus, type Profile, type Recording, type SealedPrefs, type UserList, EMPTY_RULE, checkRule, deviceName, eventText, livePosition, mergeGuide, parsePlayback, parseRule, parseUsage, recordingId, ruleJson, ruleSummary, summarizeUsage, type GuideResult, type UsageDay,
} from './model'

const profile = (id: string, position: number, colorIndex = 0, avatar = ''): Profile =>
  ({ id, name: id, colorIndex, isKid: false, pinHash: '', avatar, position, updatedAt: 10 })

const rec = (status: Recording['status'], start: number, stop: number): Recording =>
  ({ id: 'r', channelKey: 'k', channelName: 'TF1', title: 't', startMs: start, stopMs: stop, status, error: '', sender: '', updatedAt: 1 })

describe('profils', () => {
  it('calcule le code comme ProfileManager.hashPin (sha256 de « vyzo-pin:id:code »)', async () => {
    expect(await hashPin('0f8c2b1e-1111-4a2b-9c3d-123456789abc', '2580'))
      .toBe('759a9b3c3229b27f766debd3d5522884b48107d2da07bd5c477ae23aa9a89bcc')
  })

  it('accepte seulement 4 chiffres', () => {
    expect(isValidPin('2580')).toBe(true)
    expect(isValidPin('258')).toBe(false)
    expect(isValidPin('25a0')).toBe(false)
    expect(isValidPin('25800')).toBe(false)
  })

  it('nettoie le nom comme ProfileManager.cleanName', () => {
    expect(cleanName('  Mon   salon ')).toBe('Mon salon')
    expect(cleanName('   ')).toBe('Profil')
    expect(cleanName('a'.repeat(40))).toHaveLength(24)
  })

  it('nettoie l’avatar comme ProfileManager.cleanAvatar', () => {
    expect(cleanAvatar('  Rocket ')).toBe('rocket')
    expect(cleanAvatar('initial')).toBe('')
    expect(cleanAvatar('   ')).toBe('')
  })

  it('prend la première couleur libre, puis recommence le tour', () => {
    expect(nextColor([profile('a', 0, 0), profile('b', 1, 2)])).toBe(1)
    const full = Array.from({ length: PROFILE_PALETTE.length }, (_, i) => profile(String(i), i, i))
    expect(nextColor(full)).toBe(0)
    expect(profileColor(-1)).toBe(profileColor(PROFILE_PALETTE.length - 1))
  })

  it('déplace un profil en renvoyant seulement ceux qui changent de place', () => {
    const list = [profile('a', 0), profile('b', 1), profile('c', 2)]
    const moved = moveProfile(list, 'c', -1)
    expect(moved.map((p) => [p.id, p.position])).toEqual([['c', 1], ['b', 2]])
    expect(moveProfile(list, 'a', -1)).toEqual([])
  })

  it('date toujours la modification après la version connue (horloge en retard)', () => {
    expect(nextUpdatedAt(5_000, 1_000)).toBe(5_001)
    expect(nextUpdatedAt(5_000, 9_000)).toBe(9_000)
  })
})

describe('enregistrements', () => {
  const now = 1_000_000
  it('range chaque programmation dans le bon groupe', () => {
    expect(recordingGroup(rec('SCHEDULED', now + 10, now + 20), now)).toBe('upcoming')
    expect(recordingGroup(rec('RECORDING', now - 10, now + 20), now)).toBe('upcoming')
    expect(recordingGroup(rec('SCHEDULED', now - 20, now - 10), now)).toBe('other')
    expect(recordingGroup(rec('DONE', now - 20, now - 10), now)).toBe('done')
    expect(recordingGroup(rec('FAILED', now - 20, now - 10), now)).toBe('other')
  })

  it('signale une programmation passée jamais démarrée', () => {
    expect(statusLabel(rec('SCHEDULED', now - 20, now - 10), now)).toBe('Non enregistré')
    expect(statusLabel(rec('SCHEDULED', now + 10, now + 20), now)).toBe('Programmé')
  })
})

describe('appareils', () => {
  const now = 1_000_000_000
  const dev = (o: Partial<Device> = {}): Device => ({
    id: 'd', name: 'TV', label: '', kind: 'tv', model: '', system: '', appVersion: '1.0', versionCode: 30, lastSeenAt: now - 60_000,
    updateRequestedAt: 0, updateStatus: '', updateStatusAt: 0, ...o,
  })

  it('compare la version installée à la dernière publiée', () => {
    expect(needsUpdate(dev(), { versionCode: 31, versionName: '1.1' })).toBe(true)
    expect(needsUpdate(dev(), { versionCode: 30, versionName: '1.0' })).toBe(false)
    expect(needsUpdate(dev({ versionCode: 0 }), { versionCode: 31, versionName: '1.1' })).toBe(false)
    expect(needsUpdate(dev(), null)).toBe(false)
  })

  it('suit une demande de mise à jour jusqu’au dernier message de l’appareil', () => {
    const asked = dev({ updateRequestedAt: now - 5000 })
    expect(updatePending(asked)).toBe(true)
    expect(updateInProgress(asked, now)).toBe(true)
    const downloading = { ...asked, updateStatus: 'Téléchargement…', updateStatusAt: now - 1000 }
    expect(updatePending(downloading)).toBe(false)
    expect(updateInProgress(downloading, now)).toBe(true)
    expect(updateInProgress(downloading, now + 11 * 60_000)).toBe(false)
    expect(updateInProgress({ ...downloading, updateStatus: 'Installation de la version 1.1 lancée' }, now)).toBe(false)
  })

  it('considère un appareil en ligne pendant 15 minutes', () => {
    expect(isOnline(dev(), now)).toBe(true)
    expect(isOnline(dev({ lastSeenAt: now - 16 * 60_000 }), now)).toBe(false)
  })

  it('borne la part regardée', () => {
    expect(progressRatio({ positionMs: 50, durationMs: 100 })).toBe(0.5)
    expect(progressRatio({ positionMs: 150, durationMs: 100 })).toBe(1)
    expect(progressRatio({ positionMs: 10, durationMs: 0 })).toBe(0)
  })
})

describe('état de l’abonnement', () => {
  const now = Date.UTC(2026, 10, 13, 12)
  const s = (o: Partial<PlaylistStatus>): PlaylistStatus => ({ expiresAt: 0, maxConnections: 1, status: 'Active', checkedDay: 0, ...o })

  it('rien de vérifié : pas d’état', () => {
    expect(subscriptionState(null, now)).toBeNull()
  })
  it('compte refusé par le fournisseur', () => {
    expect(subscriptionState(s({ status: 'Banned' }), now)).toMatchObject({ tone: 'bad', label: 'Suspendu' })
    expect(subscriptionState(s({ status: 'Expired', expiresAt: now + 30 * 86_400_000 }), now)?.tone).toBe('bad')
  })
  it('expiration passée, proche ou lointaine', () => {
    expect(subscriptionState(s({ expiresAt: now - 1000 }), now)).toMatchObject({ tone: 'bad', label: 'Expiré' })
    expect(subscriptionState(s({ expiresAt: now + 86_400_000 }), now)).toMatchObject({ tone: 'warn', label: 'Expire demain' })
    expect(subscriptionState(s({ expiresAt: now + 5 * 86_400_000 }), now)).toMatchObject({ tone: 'warn', daysLeft: 5 })
    expect(subscriptionState(s({ expiresAt: now + 60 * 86_400_000 }), now)?.tone).toBe('ok')
    expect(subscriptionState(s({}), now)?.label).toBe('Sans date de fin')
  })
})

describe('lancer sur un appareil', () => {
  it('type envoyé selon le contenu', () => {
    expect(castItemType('MOVIE', true)).toBe('content-movie')
    expect(castItemType('SERIES', true)).toBe('content-episode')
    expect(castItemType('SERIES', false)).toBe('content-series')
    expect(castItemType('LIVE', false)).toBe('content-channel')
  })
  it('appareil prêt, éteint ou trop ancien', () => {
    const now = 1_000_000_000
    const d = { id: 'a', name: '', label: '', kind: 'tv', model: '', system: '', appVersion: '', versionCode: CAST_MIN_VERSION, lastSeenAt: now,
      updateRequestedAt: 0, updateStatus: '', updateStatusAt: 0 } as const
    expect(castReadiness(d, now)).toBe('ready')
    expect(castReadiness({ ...d, lastSeenAt: now - 3600_000 }, now)).toBe('offline')
    expect(castReadiness({ ...d, versionCode: CAST_MIN_VERSION - 1 }, now)).toBe('outdated')
  })
})

describe('contrôle parental', () => {
  it('empreinte identique à celle de l’appli', async () => {
    // ParentalControl.hashParental("1234") = sha256("vyzo-parental:1234")
    expect(await hashParental('1234')).toBe('fc41febd83f18e3997ce39eade8a4f1217921b652bc0f2f3625f68ba8c238854')
  })
  it('choix explicite, sinon détection adulte', () => {
    const e: CatalogEntry = { rest: 'C:L:1', name: 'Adultes', kind: 'LIVE', adult: true }
    expect(categoryLocked(e, undefined, true)).toBe(true)
    expect(categoryLocked(e, undefined, false)).toBe(false)
    expect(categoryLocked(e, { rest: 'C:L:1', hidden: false, position: null, locked: false, name: '' }, true)).toBe(false)
  })
  it('verrouiller garde le reste des préférences, dans l’ordre de l’appli', () => {
    const prefs: SealedPrefs = {
      categories: [{ rest: 'C:L:2', hidden: true, position: 3, locked: null, name: 'Sport' }],
      channels: [{ rest: 'L:9', name: 'X', number: 0, epgKey: '', locked: false, hidden: false }],
    }
    const a = withCategoryLock(prefs, 'C:L:2', true)
    expect(a.categories).toEqual([{ rest: 'C:L:2', hidden: true, position: 3, locked: true, name: 'Sport' }])
    expect(a.channels).toBe(prefs.channels)
    const b = withCategoryLock(prefs, 'C:L:1', true)
    expect(b.categories.map((c) => c.rest)).toEqual(['C:L:1', 'C:L:2'])
    expect(JSON.stringify(b.categories[0])).toBe('{"rest":"C:L:1","hidden":false,"position":null,"locked":true,"name":""}')
    expect(prefs.categories).toHaveLength(1)
  })
})

describe('1.13.0-beta3', () => {
  it('nom d’appareil choisi sur le site', () => {
    expect(deviceName({ name: 'AFTKA', label: '' })).toBe('AFTKA')
    expect(deviceName({ name: 'AFTKA', label: '  Salon ' })).toBe('Salon')
    expect(deviceName({ name: '', label: '' })).toBe('Appareil')
  })

  it('règles de temps d’écran', () => {
    expect(checkRule({ ...EMPTY_RULE })).toBeNull()
    expect(checkRule({ ...EMPTY_RULE, from: '7h', to: '20:00' })).toMatch(/07:30/)
    expect(checkRule({ ...EMPTY_RULE, from: '08:00', to: '08:00' })).toMatch(/identiques/)
    expect(checkRule({ ...EMPTY_RULE, weekendFrom: '25:00', weekendTo: '10:00' })).toMatch(/week-end/)
    expect(ruleSummary({ ...EMPTY_RULE, dailyMinutes: 90, from: '07:30', to: '20:00' })).toBe('1 h 30 par jour · 07:30 – 20:00')
    expect(ruleSummary(null)).toBe('Aucune limite')
    // Même texte que ScreenTimeRule côté appli : l'appareil renvoie le bloc à l'identique.
    expect(ruleJson({ ...EMPTY_RULE, dailyMinutes: 60 })).toBe('{"enabled":true,"dailyMinutes":60,"from":"","to":"","weekendFrom":"","weekendTo":""}')
    expect(parseRule('{"dailyMinutes":"45","enabled":true}')).toMatchObject({ dailyMinutes: 45, from: '' })
  })

  it('statistiques par jour, type et titre', () => {
    const now = new Date(2026, 10, 13, 15).getTime()
    const u = (profile: string, day: string, ms: number, t = 'Dune'): UsageDay =>
      ({ profile, device: 'd1', day, totalMs: ms, liveMs: 0, movieMs: ms, episodeMs: 0, otherMs: 0, items: [{ t, ms }] })
    const rows = [u('p1', '2026-11-13', 3600_000), u('p1', '2026-11-12', 1800_000, 'Arte'), u('p2', '2026-11-13', 600_000), u('p1', '2026-10-01', 999_000)]
    const all = summarizeUsage(rows, null, 7, now)
    expect(all.perDay).toHaveLength(7)
    expect(all.perDay.at(-1)).toEqual({ day: '2026-11-13', ms: 4200_000 })
    expect(all.totalMs).toBe(6000_000)
    expect(all.top[0]).toEqual({ title: 'Dune', ms: 4200_000 })
    expect(summarizeUsage(rows, 'p2', 7, now).totalMs).toBe(600_000)
    expect(parseUsage('{"profile":"p1","day":"2026-11-13","totalMs":5,"items":[{"t":"A","ms":5},{"ms":3}]}')?.items).toEqual([{ t: 'A', ms: 5 }])
    expect(parseUsage('pas du json')).toBeNull()
  })

  it('résultats du guide de plusieurs appareils', () => {
    const now = 1_000
    const g = (channelKey: string, start: number, stop: number): GuideResult => ({ channelKey, channel: channelKey, title: 't', start, stop, desc: '' })
    const merged = mergeGuide([[g('b', 3000, 4000), g('a', 2000, 3000), g('a', 0, 500)], [g('a', 2000, 3000)]], now)
    expect(merged.map((x) => recordingId(x))).toEqual(['a|2000|3000', 'b|3000|4000'])
  })

  it('lecture en cours', () => {
    const p = parsePlayback('{"state":"playing","title":"Dune","positionMs":1000,"durationMs":5000}', 10_000)!
    expect(livePosition(p, 12_000)).toBe(3000)
    expect(livePosition(p, 99_000)).toBe(5000)
    expect(livePosition({ ...p, state: 'paused' }, 99_000)).toBe(1000)
    expect(parsePlayback('{"state":"autre"}', 0)?.state).toBe('idle')
  })

  it('journal', () => {
    expect(eventText({ kind: 'device_added' }).label).toBe('Nouvel appareil connecté')
    expect(eventText({ kind: 'inconnu' }).icon).toBe('info')
  })

  it('détecte un lien Xtream complet (get.php / player_api.php)', () => {
    expect(parseXtreamUrl('http://iptv.exemple:8080/get.php?username=alice&password=secret&type=m3u_plus'))
      .toEqual({ url: 'http://iptv.exemple:8080', username: 'alice', password: 'secret' })
    expect(parseXtreamUrl('https://cdn.host/panel/player_api.php?username=u&password=p'))
      .toEqual({ url: 'https://cdn.host/panel', username: 'u', password: 'p' })
    expect(parseXtreamUrl('https://iptv-org.github.io/iptv/fr.m3u')).toBeNull()
    expect(parseXtreamUrl('http://iptv.exemple:8080/get.php?username=alice')).toBeNull()
  })

  it('calcule le temps d’écran consommé aujourd’hui et la plage active', () => {
    const fridayAfternoon = new Date(2026, 10, 13, 15, 0).getTime()
    const fridayNight = new Date(2026, 10, 13, 22, 0).getTime()
    const sundayMorning = new Date(2026, 10, 15, 9, 30).getTime()
    const rows: UsageDay[] = [
      { profile: 'kid', device: 'tv', day: '2026-11-13', totalMs: 25 * 60_000, liveMs: 0, movieMs: 25 * 60_000, episodeMs: 0, otherMs: 0, items: [] },
      { profile: 'kid', device: 'tab', day: '2026-11-13', totalMs: 20 * 60_000, liveMs: 0, movieMs: 0, episodeMs: 20 * 60_000, otherMs: 0, items: [] },
      { profile: 'kid', device: 'tv', day: '2026-11-12', totalMs: 90 * 60_000, liveMs: 0, movieMs: 0, episodeMs: 0, otherMs: 0, items: [] },
    ]
    const rule = { enabled: true, dailyMinutes: 60, from: '08:00', to: '20:30', weekendFrom: '09:00', weekendTo: '21:30' }
    const s = todayScreenTime(rows, 'kid', rule, fridayAfternoon)!
    expect(s.usedMs).toBe(45 * 60_000)
    expect(s.remainingMs).toBe(15 * 60_000)
    expect(s.ratio).toBe(0.75)
    expect(s.exceeded).toBe(false)
    expect(s.inWindow).toBe(true)
    expect(s.windowLabel).toBe('08:00 – 20:30')

    expect(todayScreenTime(rows, 'kid', rule, fridayNight)?.inWindow).toBe(false)
    expect(todayScreenTime(rows, 'kid', rule, sundayMorning)?.windowLabel).toBe('09:00 – 21:30')
    expect(todayScreenTime(rows, 'kid', EMPTY_RULE, fridayAfternoon)).toBeNull()
  })

  it('signale un enregistrement imminent si aucun Vyzo PC n’est en ligne', () => {
    const now = 1_000_000
    const pcOffline = { id: 'pc', name: 'PC', label: '', kind: 'pc', model: '', system: '', appVersion: '', versionCode: 41, lastSeenAt: now - 30 * 60_000, updateRequestedAt: 0, updateStatus: '', updateStatusAt: 0 } as Device
    const pcOnline = { ...pcOffline, lastSeenAt: now - 10_000 }
    const soon = rec('SCHEDULED', now + 30 * 60_000, now + 90 * 60_000)
    const far = { ...rec('SCHEDULED', now + 5 * 3600_000, now + 6 * 3600_000), id: 'far' }
    expect(upcomingRecordingAlert([far, soon], [pcOffline], now)?.id).toBe('r')
    expect(upcomingRecordingAlert([far, soon], [pcOnline], now)).toBeNull()
    expect(upcomingRecordingAlert([far], [pcOffline], now)).toBeNull()
  })

  it('réordonne les listes personnalisées et lit la dernière sauvegarde', () => {
    const lists: UserList[] = [
      { profileId: 'p1', id: 'a', name: 'A', position: 1, updatedAt: 1 },
      { profileId: 'p1', id: 'b', name: 'B', position: 2, updatedAt: 1 },
    ]
    expect(moveUserList(lists, 'b', -1).map((l) => [l.id, l.position])).toEqual([['b', 1], ['a', 2]])
    expect(lastBackupAt([{ id: 1, at: 100, kind: 'site_signin', detail: '' }])).toBeNull()
    expect(lastBackupAt([{ id: 1, at: 100, kind: 'backup_export', detail: '' }, { id: 2, at: 500, kind: 'backup_export', detail: '' }])).toBe(500)
  })

  it('gère les sources EPG (id sha256-24, JSON Kotlin et priorité)', async () => {
    const id = await epgSourceId('https://xmltvfr.fr/guide/tvguide.xml')
    expect(id).toHaveLength(24)
    expect(epgJson({ name: 'XMLTV FR', url: 'https://a/b.xml', enabled: true, priority: 0, mode: 'FILL' }))
      .toBe('{"name":"XMLTV FR","url":"https://a/b.xml","enabled":true,"priority":0,"mode":"FILL"}')
    const sources: EpgSource[] = [
      { id: '1', name: 'A', url: 'https://a', enabled: true, priority: 0, mode: 'FILL', updatedAt: 1 },
      { id: '2', name: 'B', url: 'https://b', enabled: true, priority: 1, mode: 'GAPS', updatedAt: 1 },
    ]
    expect(moveEpgSource(sources, '2', -1).map((s) => [s.id, s.priority])).toEqual([['2', 0], ['1', 1]])
  })

  it('préserve et nettoie les préférences catégories/chaînes et sérialise SealedSettings dans l’ordre Kotlin', () => {
    let prefs: SealedPrefs = { categories: [], channels: [] }
    prefs = withCategoryPref(prefs, 'C:L:1', { hidden: true, name: 'Cinéma' })
    prefs = withChannelPref(prefs, 'L:101', { name: 'TF1', number: 1, epgKey: 'TF1.fr' })
    expect(JSON.parse(prefsJson(prefs))).toEqual(parseSealedPrefs(prefsJson(prefs)))
    prefs = withCategoryPref(prefs, 'C:L:1', { hidden: false, name: '' })
    prefs = removeChannelPref(prefs, 'L:101')
    expect(prefs).toEqual({ categories: [], channels: [] })

    const parsed = parseSyncedSettings('{"xtreamFormat":"m3u8","autoSkipRecap":true}')
    expect(parsed.xtreamFormat).toBe('m3u8')
    expect(parsed.autoSkipRecap).toBe(true)
    const keys = Object.keys(JSON.parse(settingsJson(DEFAULT_SYNCED_SETTINGS)))
    expect(keys[0]).toBe('epgShiftMinutes')
    expect(keys[keys.length - 1]).toBe('autoSkipRecap')
    expect(keys).toHaveLength(22)
  })

  it('calcule comparatif de périodes, répartition par profil, détail du jour et CSV sécurisé', () => {
    const now = new Date(2026, 10, 14, 15).getTime()
    const rows: UsageDay[] = [
      { profile: 'p1', device: 'd1', day: '2026-11-14', totalMs: 3600_000, liveMs: 1200_000, movieMs: 2400_000, episodeMs: 0, otherMs: 0, items: [{ t: 'Dune', ms: 2400_000 }] },
      { profile: 'p2', device: 'd1', day: '2026-11-14', totalMs: 1800_000, liveMs: 1800_000, movieMs: 0, episodeMs: 0, otherMs: 0, items: [{ t: '=TF1', ms: 1800_000 }] },
      { profile: 'p1', device: 'd1', day: '2026-11-05', totalMs: 2700_000, liveMs: 2700_000, movieMs: 0, episodeMs: 0, otherMs: 0, items: [] },
    ]
    const cmp = compareUsagePeriods(rows, null, 7, now)
    expect(cmp.currentMs).toBe(5400_000)
    expect(cmp.previousMs).toBe(2700_000)
    expect(cmp.deltaPct).toBe(100)

    const p1 = profile('p1', 0)
    const p2 = profile('p2', 1)
    const slices = usageByProfile(rows, 7, [p1, p2], now)
    expect(slices.map((s) => s.profile.id)).toEqual(['p1', 'p2'])
    expect(Math.round(slices[0].share * 100)).toBe(67)

    const day = dayUsageBreakdown(rows, '2026-11-14', null, [p1, p2])
    expect(day.totalMs).toBe(5400_000)
    expect(day.top[0].title).toBe('Dune')

    const csv = usageToCsv(rows, [p1, p2], [])
    expect(csv.startsWith('\uFEFF')).toBe(true)
    expect(csv).toContain("'=TF1 (30 min)")
  })
})
