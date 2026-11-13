import { describe, expect, it } from 'vitest'
import {
  cleanName, hashPin, isOnline, isValidPin, moveProfile, needsUpdate, nextColor, nextUpdatedAt, profileColor, progressRatio,
  recordingGroup, statusLabel, updateInProgress, updatePending,
  type Device, type Profile, type Recording,
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
    id: 'd', name: 'TV', kind: 'tv', model: '', system: '', appVersion: '1.0', versionCode: 30, lastSeenAt: now - 60_000,
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
