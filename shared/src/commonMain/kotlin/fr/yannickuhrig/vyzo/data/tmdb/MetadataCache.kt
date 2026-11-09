package fr.yannickuhrig.vyzo.data.tmdb

import fr.yannickuhrig.vyzo.data.db.MetadataCacheDao
import fr.yannickuhrig.vyzo.data.db.MetadataCacheEntity

/** Réponses de TMDB et d'OMDb, sans jamais la clé d'API dans l'identifiant. */
class MetadataCache(
    private val dao: MetadataCacheDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun get(key: String, maxAgeMs: Long = MAX_AGE_MS): String? =
        dao.get(key)?.takeIf { now() - it.fetchedAt < maxAgeMs }?.body

    suspend fun put(key: String, body: String) = dao.upsert(MetadataCacheEntity(key, body, now()))

    suspend fun purge() = dao.deleteOlderThan(now() - MAX_AGE_MS)

    companion object {
        const val DAY_MS = 24 * 3_600_000L
        const val MAX_AGE_MS = 30 * DAY_MS
    }
}
