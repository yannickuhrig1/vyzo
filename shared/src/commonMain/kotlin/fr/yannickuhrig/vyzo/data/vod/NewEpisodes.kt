package fr.yannickuhrig.vyzo.data.vod

import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.EpisodeEntity
import fr.yannickuhrig.vyzo.data.db.FAVORITES_GROUP_ID
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.data.sync.SyncManager
import fr.yannickuhrig.vyzo.platform.Platform
import fr.yannickuhrig.vyzo.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Nouveaux épisodes d'une série, pour une notification. */
data class NewEpisodes(val series: SeriesEntity, val episodes: List<EpisodeEntity>) {
    val title: String
        get() = if (episodes.size == 1) "Nouvel épisode : ${series.name.trim()}" else "${episodes.size} nouveaux épisodes : ${series.name.trim()}"

    val body: String
        get() {
            val sorted = episodes.sortedWith(compareBy({ it.season }, { it.episode }))
            if (sorted.size == 1) {
                val e = sorted.first()
                val t = e.title.trim().takeIf { it.isNotBlank() && !it.startsWith("Épisode ") }
                return label(e) + (t?.let { " · $it" } ?: "")
            }
            return "${label(sorted.first())} à ${label(sorted.last())}"
        }

    private fun label(e: EpisodeEntity) = "S${e.season} E${e.episode}"

    companion object {
        /**
         * Épisodes apparus entre deux chargements. Sans liste précédente (série jamais ouverte sur cet appareil),
         * on ne sait pas ce qui est nouveau : rien n'est signalé, la liste sert de point de départ.
         */
        fun between(before: List<EpisodeEntity>, after: List<EpisodeEntity>): List<EpisodeEntity> {
            if (before.isEmpty()) return emptyList()
            val known = before.mapTo(HashSet()) { it.uid }
            // Même saison et même numéro sous un autre identifiant : le fournisseur a remplacé le fichier, pas un nouvel épisode.
            val slots = before.mapTo(HashSet()) { it.season to it.episode }
            return after.filter { it.uid !in known && (it.season to it.episode) !in slots }
        }
    }
}

/**
 * Surveille les séries suivies (favoris et séries en cours, tous profils) : après chaque mise à jour, au plus toutes
 * les [CHECK_EVERY_MS], on recharge leurs épisodes et on prévient de ceux qui viennent d'arriver.
 * Seules les playlists Xtream donnent la liste des épisodes à la demande ; les séries verrouillées par le contrôle
 * parental ne sont jamais signalées (la notification se voit sur l'écran de verrouillage).
 */
class NewEpisodeWatcher(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val sync: SyncManager,
    private val platform: Platform,
) {
    private val mutex = Mutex()

    /** Une vérification à la fois : un second appel attend la fin de la première (le travail Android reste en vie). */
    suspend fun check(force: Boolean = false): List<NewEpisodes> = mutex.withLock {
        val s = settings.get()
        if (!s.newEpisodeAlerts) return@withLock emptyList()
        val now = System.currentTimeMillis()
        if (!force && now - s.lastEpisodeCheck < CHECK_EVERY_MS) return@withLock emptyList()
        settings.update { it.copy(lastEpisodeCheck = now) }
        val found = ArrayList<NewEpisodes>()
        val playlists = db.playlists().all().associateBy { it.id }
        for (series in db.series().followed(FAVORITES_GROUP_ID, now - RECENT_MS, MAX_SERIES)) {
            val p = playlists[series.playlistId]?.takeIf { it.type == SourceType.XTREAM } ?: continue
            val before = db.series().episodesList(series.uid)
            runCatching { sync.loadXtreamEpisodes(p, series) }.onFailure { Log.w(TAG, "épisodes de ${series.name}", it) }
            val fresh = NewEpisodes.between(before, db.series().episodesList(series.uid))
            if (fresh.isNotEmpty()) found += NewEpisodes(series, fresh)
        }
        for (n in found) platform.notifyNewEpisodes(n.title, n.body, n.series.uid)
        found
    }

    companion object {
        private const val TAG = "VyzoEpisodes"
        const val CHECK_EVERY_MS = 6 * 3_600_000L
        /** Une série regardée dans les 60 derniers jours compte comme suivie. */
        const val RECENT_MS = 60 * 24 * 3_600_000L
        /** Une requête par série : on reste raisonnable avec le serveur du fournisseur. */
        const val MAX_SERIES = 40
    }
}
