import { describe, expect, it } from 'vitest'
import {
  deriveKey, open, openText, parseMeta, playlistJson, playlistKey, seal, toBase64, type SourceType,
} from './vault'

/** Valeurs vérifiées aussi par desktop/src/test/…/WebCompatTest.kt. */
describe('concordance avec l’appli', () => {
  it('calcule le même identifiant de playlist que PlaylistKeys.of', async () => {
    const cases: [SourceType, string, string, string, string][] = [
      ['XTREAM', 'http://ex.com:8080', ' Jean.Dupont ', '', '643d1967e1c6'],
      ['STALKER', 'http://portail.tv/c/', '', '00:1a:79:ab:cd:ef', '22fc07a7cb17'],
      ['M3U', 'HTTP://Example.COM:80/list/playlist.m3u?user=A&pass=B', '', '', 'a1aef7764fb9'],
      ['M3U', 'example.com:8443/path/', '', '', 'b45ef1053d6e'],
      ['M3U', 'https://exa_mple.com/x.m3u', '', '', '4abeb593a48f'],
      ['M3U', 'http://ex.com/a b.m3u', '', '', 'f0d4481642fa'],
      ['M3U', 'https://cdn.ex.com:443/get.php?username=u&password=p&type=m3u_plus#frag', '', '', '7b106d8ca985'],
    ]
    for (const [type, url, user, mac, expected] of cases) expect(await playlistKey(type, url, user, mac)).toBe(expected)
  })

  it('tire du mot de passe la même clé que VaultCrypto.deriveKey', async () => {
    const salt = new Uint8Array(16).map((_, i) => 100 + i)
    expect(toBase64(await deriveKey('mot de passe', salt, 1000))).toBe('HTcHyTFRi96zuxSl5vE33zPbLTUavdpjWvzpxdTmviI=')
  })

  it('ouvre un bloc chiffré par l’appli', async () => {
    const key = new Uint8Array(32).map((_, i) => 31 - i)
    const sealed = 'v1.Cm6rQCtQ3HuhSy5jBW3/1box3sGFA1dkT/stFt5lDFIjkMvQGoi8LcCzs2ZFKzA8meF7qKM2FHvXnR/eqQGdfkj6LqbhFUc1Ujtie4fV2hLDd7cfsZ0='
    const meta = parseMeta(await openText(key, sealed, 'vyzo|u2|meta|abc:vod:42'))
    expect(meta).toEqual({ title: 'Le Bureau des légendes', subtitle: 'S1 · É2' })
  })

  it('refuse un bloc déplacé sur une autre ligne', async () => {
    const key = new Uint8Array(32).fill(7)
    const sealed = await seal(key, 'secret', 'vyzo|u|playlist|a')
    await expect(open(key, sealed, 'vyzo|u|playlist|b')).rejects.toThrow()
  })

  it('écrit le JSON de playlist comme kotlinx.serialization', () => {
    expect(playlistJson({ name: 'A', type: 'XTREAM', url: 'http://x', username: 'u', password: 'p"', mac: '', userAgent: '' }))
      .toBe('{"name":"A","type":"XTREAM","url":"http://x","username":"u","password":"p\\"","mac":"","userAgent":""}')
  })
})
