import { describe, expect, it } from 'vitest'
import {
  cleanName, hashPin, isOnline, isValidPin, moveProfile, needsUpdate, nextColor, nextUpdatedAt, profileColor, progressRatio,
  recordingGroup, statusLabel, updateInProgress, updatePending,
  CAST_MIN_VERSION, castItemType, castReadiness, categoryLocked, hashParental, subscriptionState, withCategoryLock,
  type CatalogEntry, type Device, type PlaylistStatus, type Profile, type Recording, type SealedPrefs, EMPTY_RULE, checkRule, deviceName, eventText, livePosition, mergeGuide, parsePlayback, parseRule, parseUsage, recordingId, ruleJson, ruleSummary, summarizeUsage, type GuideResult, type UsageDay,
} from './model'

const profile = (id: string, position: number, colorIndex = 0): Profile =>
  ({ id, name: id, colorIndex, isKid: false, pinHash: '', position, updatedAt: 10 })

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

  it('prend la première couleur libre, puis recommence le tour', () => {
    expect(nextColor([profile('a', 0, 0), profile('b', 1, 2)])).toBe(1)
    const full = Array.from({ length: 8 }, (_, i) => profile(String(i), i, i))
    expect(nextColor(full)).toBe(0)
    expect(profileColor(-1)).toBe(profileColor(7))
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
      channels: [{ rest: 'L:9', name: 'X' }],
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
})
