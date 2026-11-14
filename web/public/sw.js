// Site du compte Vyzo installé : la page s'ouvre même sans réseau (les données, elles, viennent toujours du serveur).
// Réseau d'abord, copie gardée en secours ; seules les ressources du site lui-même passent par ici.
const CACHE = 'vyzo-compte-v1'

self.addEventListener('install', () => self.skipWaiting())

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) if (key !== CACHE) await caches.delete(key)
    await self.clients.claim()
  })())
})

self.addEventListener('fetch', (event) => {
  const req = event.request
  if (req.method !== 'GET' || new URL(req.url).origin !== self.location.origin) return
  event.respondWith((async () => {
    try {
      const res = await fetch(req)
      if (res.ok && res.type === 'basic') {
        const copy = res.clone()
        event.waitUntil(caches.open(CACHE).then((c) => c.put(req, copy)))
      }
      return res
    } catch (e) {
      const cached = await caches.match(req, { ignoreSearch: req.mode === 'navigate' })
      if (cached) return cached
      if (req.mode === 'navigate') {
        const shell = await caches.match('./')
        if (shell) return shell
      }
      throw e
    }
  })())
})
