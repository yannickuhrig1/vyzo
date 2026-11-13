/**
 * Données chiffrées du compte, comme VaultCrypto.kt et SealedSync.kt : AES-256-GCM avec un contexte (données associées)
 * qui lie chaque bloc à sa ligne, et PBKDF2-HMAC-SHA256 pour tirer du mot de passe la clé qui protège celle du compte.
 * La clé du compte ne quitte jamais la mémoire de l'onglet.
 */

const PREFIX = 'v1.'
const IV_BYTES = 12
export const KEY_BYTES = 32
const enc = new TextEncoder()
const dec = new TextDecoder()

export function toBase64(bytes: Uint8Array): string {
  let s = ''
  for (const b of bytes) s += String.fromCharCode(b)
  return btoa(s)
}

export function fromBase64(text: string): Uint8Array {
  const s = atob(text)
  const out = new Uint8Array(s.length)
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i)
  return out
}

export function hex(bytes: ArrayBuffer | Uint8Array): string {
  return Array.from(bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes), (b) => b.toString(16).padStart(2, '0')).join('')
}

export async function digestHex(algo: 'SHA-1' | 'SHA-256', text: string): Promise<string> {
  return hex(await crypto.subtle.digest(algo, enc.encode(text)))
}

/** Comme VaultCrypto.deriveKey (bloc 1 de PBKDF2, 32 octets). */
export async function deriveKey(password: string, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  const base = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveBits'])
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: salt as BufferSource, iterations }, base, KEY_BYTES * 8)
  return new Uint8Array(bits)
}

async function aesKey(raw: Uint8Array): Promise<CryptoKey> {
  return crypto.subtle.importKey('raw', raw as BufferSource, 'AES-GCM', false, ['encrypt', 'decrypt'])
}

export async function seal(key: Uint8Array, plain: Uint8Array | string, context: string): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(IV_BYTES))
  const data = typeof plain === 'string' ? enc.encode(plain) : plain
  const out = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: enc.encode(context), tagLength: 128 }, await aesKey(key), data as BufferSource)
  const all = new Uint8Array(IV_BYTES + out.byteLength)
  all.set(iv)
  all.set(new Uint8Array(out), IV_BYTES)
  return PREFIX + toBase64(all)
}

/** Lève une erreur si la clé est mauvaise ou si le bloc a été modifié. */
export async function open(key: Uint8Array, sealed: string, context: string): Promise<Uint8Array> {
  if (!sealed.startsWith(PREFIX)) throw new Error('Bloc chiffré invalide')
  const raw = fromBase64(sealed.slice(PREFIX.length))
  if (raw.length <= IV_BYTES) throw new Error('Bloc chiffré invalide')
  const out = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: raw.slice(0, IV_BYTES), additionalData: enc.encode(context), tagLength: 128 },
    await aesKey(key), raw.slice(IV_BYTES))
  return new Uint8Array(out)
}

export async function openText(key: Uint8Array, sealed: string, context: string): Promise<string> {
  return dec.decode(await open(key, sealed, context))
}

/** Comme VaultCrypto.keyId : empreinte publique de la clé. */
export async function keyId(key: Uint8Array): Promise<string> {
  const data = new Uint8Array([...enc.encode('vyzo-key-id'), ...key])
  return hex(await crypto.subtle.digest('SHA-256', data)).slice(0, 16)
}

export const context = (userId: string, kind: string, id: string) => `vyzo|${userId}|${kind}|${id}`
export const metaContext = (userId: string, contentKey: string) => `vyzo|${userId}|meta|${contentKey}`

export interface VaultRecord { salt: string; iterations: number; wrapped: string; keyId: string }

/** Ouvre la clé du compte avec le mot de passe ; null si le mot de passe ne l'ouvre pas. */
export async function unwrapVault(record: VaultRecord, password: string, userId: string): Promise<Uint8Array | null> {
  const kek = await deriveKey(password, fromBase64(record.salt), record.iterations)
  try {
    const key = await open(kek, record.wrapped, context(userId, 'vault', 'key'))
    return key.length === KEY_BYTES ? key : null
  } catch {
    return null
  }
}

// ------------------------------------------------------------------ Playlists

export type SourceType = 'XTREAM' | 'M3U' | 'STALKER'

/** Mêmes champs, dans le même ordre, que SealedPlaylist (kotlinx.serialization, encodeDefaults). */
export interface SealedPlaylist {
  name: string
  type: SourceType
  url: string
  username: string
  password: string
  mac: string
  userAgent: string
}

/** JSON identique à celui de l'appli : un appareil qui reçoit le bloc ne le renvoie pas pour une simple différence d'écriture. */
export function playlistJson(p: SealedPlaylist): string {
  return JSON.stringify({
    name: p.name, type: p.type, url: p.url, username: p.username, password: p.password, mac: p.mac, userAgent: p.userAgent,
  })
}

export function parsePlaylist(text: string): SealedPlaylist {
  const o = JSON.parse(text) as Partial<SealedPlaylist>
  const type: SourceType = o.type === 'XTREAM' || o.type === 'STALKER' ? o.type : 'M3U'
  return {
    name: o.name ?? '', type, url: o.url ?? '', username: o.username ?? '', password: o.password ?? '',
    mac: o.mac ?? '', userAgent: o.userAgent ?? '',
  }
}

/** Comme PlaylistKeys.normalizeUrl (java.net.URI : hôte en minuscules, port par défaut retiré, « / » final retiré). */
export function normalizeUrl(url: string): string {
  const u = url.trim()
  const m = /^([a-zA-Z][a-zA-Z0-9+.-]*):\/\/(?:[^@/?#]*@)?(\[[^\]]*\]|[^:/?#]*)(?::(\d*))?([^?#]*)(?:\?([^#]*))?(?:#.*)?$/
    .exec(u.includes('://') ? u : `http://${u}`)
  // java.net.URI refuse les espaces et caractères non échappés, et n'a pas d'hôte pour un nom avec « _ » : l'appli
  // prend alors l'adresse entière en minuscules.
  if (!m || !m[2] || /[\s"<>\\^`{|}]/.test(u) || !/^(\[[0-9a-fA-F:.]+\]|[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?)$/.test(m[2])) {
    return u.toLowerCase()
  }
  const host = m[2].toLowerCase()
  const portNum = m[3] ? Number(m[3]) : -1
  const port = portNum > 0 && portNum !== 80 && portNum !== 443 ? `:${portNum}` : ''
  const path = (m[4] ?? '').replace(/\/+$/, '')
  const query = m[5] !== undefined ? `?${m[5]}` : ''
  return host + port + path + query
}

/** Comme PlaylistKeys.of : identifiant stable d'un abonnement d'un appareil à l'autre. */
export async function playlistKey(type: SourceType, url: string, username: string, mac: string): Promise<string> {
  const basis = type === 'XTREAM' ? `xtream|${username.trim().toLowerCase()}`
    : type === 'STALKER' ? `stalker|${mac.trim().toUpperCase()}`
    : `m3u|${normalizeUrl(url)}`
  return (await digestHex('SHA-1', basis)).slice(0, 12)
}

/** Titre chiffré d'un contenu (ContentTitles.kt). */
export interface SealedMeta { title: string; subtitle: string }

export function parseMeta(text: string): SealedMeta {
  const o = JSON.parse(text) as Partial<SealedMeta>
  return { title: o.title ?? '', subtitle: o.subtitle ?? '' }
}
