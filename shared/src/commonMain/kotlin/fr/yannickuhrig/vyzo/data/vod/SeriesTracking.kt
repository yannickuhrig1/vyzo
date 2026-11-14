package fr.yannickuhrig.vyzo.data.vod

import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.EpisodeEntity
import fr.yannickuhrig.vyzo.data.db.FollowedEpisodeRow
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEpisodeAlertEntity
import fr.yannickuhrig.vyzo.data.db.SeriesScheduleEntity
import fr.yannickuhrig.vyzo.data.tmdb.MetadataCache
import fr.yannickuhrig.vyzo.ui.common.Fmt
import kotlin.math.max

/** Seuil à partir duquel un épisode est considéré comme vu (95 %). */
private const val WATCHED_FRACTION = 0.95

/** Durée de rétention par défaut des alertes de nouveaux épisodes non regardés (14 jours). */
const val ALERT_RETENTION_MS = 14L * 24 * 3_600_000L

/** État consolidé de suivi d'une série : épisodes à voir + prochaines diffusions. */
data class SeriesTrackingInfo(
    val newCount: Int = 0,
    val badge: String = "",
    val badgeUpcoming: Boolean = false,
    val scheduleBanner: String = "",
    val freshSlots: Set<Pair<Int, Int>> = emptySet(),
    val alertDetectedAt: Long = 0L,
    val lastWatchUpdatedAt: Long = 0L,
    val schedule: SeriesScheduleEntity? = null,
)

data class SeriesScheduleDisplay(
    val banner: String,
    val shortBadge: String,
    val isEnded: Boolean = false,
    val hasUpcomingDate: Boolean = false,
)

/**
 * Calcul pur et persistance du suivi d'épisodes à voir et des prochaines sorties de séries.
 */
object SeriesTracking {
    fun parseFreshSlots(raw: String): Set<Pair<Int, Int>> {
        if (raw.isBlank()) return emptySet()
        val out = LinkedHashSet<Pair<Int, Int>>()
        for (part in raw.split(',')) {
            val idx = part.indexOf(':')
            if (idx <= 0) continue
            val s = part.substring(0, idx).trim().toIntOrNull() ?: continue
            val e = part.substring(idx + 1).trim().toIntOrNull() ?: continue
            if (s >= 0 && e > 0) out += s to e
        }
        return out
    }

    fun encodeFreshSlots(slots: Iterable<Pair<Int, Int>>): String =
        slots.distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString(",") { "${it.first}:${it.second}" }

    fun episodeBadgeLabel(unwatchedCount: Int): String = when {
        unwatchedCount <= 0 -> ""
        unwatchedCount == 1 -> "1 nouveau"
        else -> "+$unwatchedCount à voir"
    }

    fun alertBannerLabel(unwatchedCount: Int): String = when {
        unwatchedCount <= 0 -> ""
        unwatchedCount == 1 -> "1 nouvel épisode à voir"
        else -> "$unwatchedCount nouveaux épisodes à voir"
    }

    /**
     * Compte les épisodes à voir pour une série suivie :
     * - Si l'utilisateur a déjà commencé un épisode : compte les épisodes non vus strictement après le dernier
     *   commencé (ainsi commencer à la S3 ne compte pas S1-S2 comme nouveaux), ou d'éventuelles alertes non vues.
     * - S'il n'a encore rien vu mais qu'une alerte est active (favori mis à jour) : compte les créneaux de l'alerte.
     */
    fun countUnwatchedEpisodes(
        rows: List<FollowedEpisodeRow>,
        alert: SeriesEpisodeAlertEntity?,
    ): Int {
        val alertSlots = parseFreshSlots(alert?.freshSlots.orEmpty())
        if (rows.isEmpty()) {
            return alertSlots.size.takeIf { it > 0 } ?: (alert?.newCount ?: 0)
        }
        val finishedSlots = rows.asSequence()
            .filter { isEpisodeFinished(it.positionMs, it.durationMs) }
            .map { it.season to it.episode }
            .toHashSet()
        val latestStarted = rows.asSequence()
            .filter { it.positionMs > 0L }
            .map { it.season to it.episode }
            .maxWithOrNull(compareBy({ it.first }, { it.second }))

        val unwatchedAfterLatest = if (latestStarted != null) {
            rows.count { row ->
                val slot = row.season to row.episode
                slot !in finishedSlots && compareSlots(slot, latestStarted) > 0
            }
        } else {
            0
        }
        val unwatchedAlert = rows.count { row ->
            val slot = row.season to row.episode
            slot in alertSlots && slot !in finishedSlots
        }
        return max(unwatchedAfterLatest, unwatchedAlert)
    }

    fun isEpisodeFinished(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0L && positionMs >= (durationMs * WATCHED_FRACTION).toLong()

    private fun compareSlots(a: Pair<Int, Int>, b: Pair<Int, Int>): Int =
        if (a.first != b.first) a.first.compareTo(b.first) else a.second.compareTo(b.second)

    fun summarize(
        rows: List<FollowedEpisodeRow>,
        alert: SeriesEpisodeAlertEntity?,
        schedule: SeriesScheduleEntity?,
        now: Long = System.currentTimeMillis(),
    ): SeriesTrackingInfo {
        val newCount = countUnwatchedEpisodes(rows, alert)
        val epBadge = episodeBadgeLabel(newCount)
        val schedDisplay = schedule?.let { formatScheduleDisplay(it, now) }
        val badge: String
        val badgeUpcoming: Boolean
        if (epBadge.isNotEmpty()) {
            badge = epBadge
            badgeUpcoming = false
        } else if (schedDisplay != null && schedDisplay.shortBadge.isNotEmpty()) {
            badge = schedDisplay.shortBadge
            badgeUpcoming = true
        } else {
            badge = ""
            badgeUpcoming = false
        }
        val finishedSlots = rows.asSequence()
            .filter { isEpisodeFinished(it.positionMs, it.durationMs) }
            .map { it.season to it.episode }
            .toHashSet()
        val freshSlots = parseFreshSlots(alert?.freshSlots.orEmpty())
            .filterNot { it in finishedSlots }
            .toSet()
        val lastWatch = rows.maxOfOrNull { it.progressUpdatedAt } ?: 0L
        return SeriesTrackingInfo(
            newCount = newCount,
            badge = badge,
            badgeUpcoming = badgeUpcoming,
            scheduleBanner = schedDisplay?.banner.orEmpty(),
            freshSlots = freshSlots,
            alertDetectedAt = alert?.detectedAt ?: 0L,
            lastWatchUpdatedAt = lastWatch,
            schedule = schedule,
        )
    }

    fun isActiveStatus(status: String): Boolean = status.trim().lowercase() in setOf(
        "returning series",
        "in production",
        "planned",
        "running",
        "to be determined",
        "in development",
    )

    fun isEndedStatus(status: String): Boolean {
        val norm = status.trim().lowercase()
        return norm == "ended" || norm == "canceled"
    }

    /** Durée de cache adaptative : 24 h pour les séries en cours/à venir, 30 j pour les séries terminées. */
    fun scheduleNeedsRefresh(schedule: SeriesScheduleEntity?, now: Long = System.currentTimeMillis()): Boolean {
        if (schedule == null) return true
        val age = now - schedule.checkedAt
        if (age < 0L) return true
        val today = Fmt.todayIso(now)
        if (isActiveStatus(schedule.status)) {
            if (schedule.nextAirDate.isNotBlank() && schedule.nextAirDate.take(10) < today && age >= 6L * 3_600_000L) return true
            return age >= MetadataCache.DAY_MS
        }
        if (isEndedStatus(schedule.status)) {
            return age >= MetadataCache.MAX_AGE_MS
        }
        return age >= MetadataCache.DAY_MS
    }

    fun formatScheduleDisplay(schedule: SeriesScheduleEntity, now: Long = System.currentTimeMillis()): SeriesScheduleDisplay? {
        val today = Fmt.todayIso(now)
        if (schedule.nextEpisode > 0 && schedule.nextAirDate.isNotBlank() && schedule.nextAirDate.take(10) >= today) {
            val d = Fmt.isoDate(schedule.nextAirDate, now)
            val titlePart = if (schedule.nextTitle.isNotBlank()) " « ${schedule.nextTitle} »" else ""
            return SeriesScheduleDisplay(
                banner = "Prochain épisode · S${schedule.nextSeason} E${schedule.nextEpisode}$titlePart · $d",
                shortBadge = "S${schedule.nextSeason}E${schedule.nextEpisode} · $d",
                hasUpcomingDate = true,
            )
        }
        if (schedule.upcomingSeason > 0 && schedule.upcomingSeasonAirDate.isNotBlank() && schedule.upcomingSeasonAirDate.take(10) >= today) {
            val d = Fmt.isoDate(schedule.upcomingSeasonAirDate, now)
            val epPart = if (schedule.upcomingSeasonEpisodes > 0) " (${schedule.upcomingSeasonEpisodes} ép.)" else ""
            return SeriesScheduleDisplay(
                banner = "Saison ${schedule.upcomingSeason} annoncée · $d$epPart",
                shortBadge = "S${schedule.upcomingSeason} · $d",
                hasUpcomingDate = true,
            )
        }
        if (isActiveStatus(schedule.status)) {
            return SeriesScheduleDisplay(
                banner = "Série en cours · Prochaine saison confirmée (date à venir)",
                shortBadge = "",
            )
        }
        if (isEndedStatus(schedule.status)) {
            return SeriesScheduleDisplay(
                banner = "Série terminée",
                shortBadge = "",
                isEnded = true,
            )
        }
        return null
    }

    /**
     * Enregistre en base les nouveaux épisodes détectés entre [before] et [after] (si [before] n'est pas vide).
     * Idempotent : fusionne les créneaux déjà signalés pour survivre aux re-synchros successives.
     */
    suspend fun recordNewEpisodesIfChanged(
        db: AppDatabase,
        series: SeriesEntity,
        before: List<EpisodeEntity>,
        after: List<EpisodeEntity>,
        now: Long = System.currentTimeMillis(),
    ): List<EpisodeEntity> {
        if (before.isEmpty() || after.isEmpty()) return emptyList()
        val fresh = NewEpisodes.between(before, after)
        if (fresh.isEmpty()) return emptyList()
        val dao = db.seriesTracking()
        val existing = dao.alert(series.uid)
        val merged = LinkedHashSet<Pair<Int, Int>>()
        existing?.let { merged += parseFreshSlots(it.freshSlots) }
        fresh.forEach { merged += it.season to it.episode }
        val latest = merged.maxWith(compareBy({ it.first }, { it.second }))
        dao.upsertAlert(
            SeriesEpisodeAlertEntity(
                seriesUid = series.uid,
                latestSeason = latest.first,
                latestEpisode = latest.second,
                newCount = merged.size,
                freshSlots = encodeFreshSlots(merged),
                detectedAt = now,
            ),
        )
        return fresh
    }

    /** Retire un épisode terminé de l'alerte active (et supprime l'alerte s'il n'en reste plus). */
    suspend fun markSlotWatched(db: AppDatabase, seriesUid: String, season: Int, episode: Int) {
        val dao = db.seriesTracking()
        val existing = dao.alert(seriesUid) ?: return
        val remaining = parseFreshSlots(existing.freshSlots) - (season to episode)
        if (remaining.isEmpty()) {
            dao.deleteAlert(seriesUid)
        } else {
            val latest = remaining.maxWith(compareBy({ it.first }, { it.second }))
            dao.upsertAlert(
                existing.copy(
                    latestSeason = latest.first,
                    latestEpisode = latest.second,
                    newCount = remaining.size,
                    freshSlots = encodeFreshSlots(remaining),
                ),
            )
        }
    }

    /** Met à jour les dates de diffusion en cache adaptatif (24 h si en cours, 30 j si terminée). */
    suspend fun refreshScheduleIfDue(
        db: AppDatabase,
        tmdb: fr.yannickuhrig.vyzo.data.tmdb.TmdbService,
        tvMaze: fr.yannickuhrig.vyzo.data.intro.TvMazeClient?,
        series: SeriesEntity,
        tmdbId: Int? = null,
        now: Long = System.currentTimeMillis(),
    ): SeriesScheduleEntity? {
        val dao = db.seriesTracking()
        val existing = dao.schedule(series.uid)
        if (!scheduleNeedsRefresh(existing, now)) return existing
        val fetched = tmdb.fetchSeriesSchedule(
            seriesUid = series.uid,
            rawTitle = series.name,
            hintYear = series.year.toIntOrNull(),
            tmdbId = tmdbId ?: existing?.tmdbId?.takeIf { it > 0 },
            tvMaze = tvMaze,
        ) ?: return existing
        dao.upsertSchedule(fetched)
        return fetched
    }
}
