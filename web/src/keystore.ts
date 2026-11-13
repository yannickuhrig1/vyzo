/**
 * « Rester déverrouillé sur cet ordinateur » : la clé du compte est gardée dans IndexedDB, chiffrée (AES-GCM) par une
 * clé propre à ce navigateur, créée non extractible. Les scripts du site peuvent s'en servir, mais personne ne peut
 * recopier cette clé ailleurs : un fichier volé du profil ou une sauvegarde ne suffit pas à lire le compte sur une
 * autre machine. En revanche, quiconque utilise ce navigateur voit les données déchiffrées, d'où le choix explicite.
 */

const DB_NAME = 'vyzo-web'
const STORE = 'vault'
const ENTRY = 'key'

interface Saved {
  userId: string
  /** Empreinte publique de la clé du compte (VaultRecord.keyId) : si elle change, la clé gardée est périmée. */
  keyId: string
  iv: Uint8Array<ArrayBuffer>
  wrapped: ArrayBuffer
  deviceKey: CryptoKey
}

function request<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    r.onsuccess = () => resolve(r.result)
    r.onerror = () => reject(r.error)
  })
}

async function store(mode: IDBTransactionMode): Promise<IDBObjectStore> {
  const open = indexedDB.open(DB_NAME, 1)
  open.onupgradeneeded = () => open.result.createObjectStore(STORE)
  const db = await request(open)
  return db.transaction(STORE, mode).objectStore(STORE)
}

const aad = (userId: string) => new TextEncoder().encode(`vyzo-web|remember|${userId}`)

export async function rememberKey(userId: string, keyId: string, key: Uint8Array): Promise<void> {
  const deviceKey = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const wrapped = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: aad(userId) }, deviceKey, new Uint8Array(key))
  const saved: Saved = { userId, keyId, iv, wrapped, deviceKey }
  await request((await store('readwrite')).put(saved, ENTRY))
}

/** Clé gardée pour ce compte, ou null (rien de gardé, autre compte, clé du compte remplacée, stockage refusé). */
export async function recallKey(userId: string, keyId: string): Promise<Uint8Array | null> {
  try {
    const saved = await request((await store('readonly')).get(ENTRY)) as Saved | undefined
    if (!saved) return null
    if (saved.userId !== userId || saved.keyId !== keyId) {
      await forgetKey()
      return null
    }
    const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: saved.iv, additionalData: aad(userId) }, saved.deviceKey, saved.wrapped)
    return new Uint8Array(plain)
  } catch {
    return null
  }
}

export async function forgetKey(): Promise<void> {
  try {
    await request((await store('readwrite')).delete(ENTRY))
  } catch {
    // Stockage indisponible (navigation privée…) : rien n'a pu y être gardé.
  }
}

export async function hasRememberedKey(): Promise<boolean> {
  try {
    return !!(await request((await store('readonly')).get(ENTRY)))
  } catch {
    return false
  }
}
