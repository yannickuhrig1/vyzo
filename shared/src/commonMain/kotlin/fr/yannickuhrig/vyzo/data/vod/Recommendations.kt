package fr.yannickuhrig.vyzo.data.vod

import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.WatchProgressEntity
import kotlin.math.pow
import kotlin.math.sqrt

/** Un titre que le profil a regardé ou mis en favori, et le poids de cet indice. */
data class TasteSignal(val kind: ContentKind, val uid: String, val weight: Double)

/** Un titre du catalogue qu'on pourrait proposer. [freshness] va de 1 (ajouté en dernier) à 0. */
data class RecoCandidate(
    val kind: ContentKind,
    val uid: String,
    val genres: Set<String>,
    val rating: Float,
    val freshness: Double,
)

/**
 * Rangée « Pour toi », calculée sur l'appareil, sans service externe :
 * 1. les titres regardés (et les favoris) donnent un goût par genre, plus fort pour ce qui a été vu jusqu'au bout
 *    et récemment ;
 * 2. chaque titre pas encore commencé reçoit un score : goût pour ses genres, puis un peu de note et de nouveauté ;
 * 3. on évite une rangée faite d'un seul genre en pénalisant un genre déjà très présent.
 */
object Recommendations {
    /** Un mois et demi : un film vu il y a 45 jours compte deux fois moins qu'un film vu aujourd'hui. */
    private const val HALF_LIFE_DAYS = 45.0
    private const val DAY_MS = 86_400_000.0
    /** Une série dont on a vu beaucoup d'épisodes ne doit pas écraser tout le reste. */
    private const val SERIES_CAP = 3.0
    const val FAVORITE_WEIGHT = 1.5
    private const val RATING_WEIGHT = 0.25
    private const val FRESHNESS_WEIGHT = 0.15
    /** Chaque titre déjà choisi dans un genre réduit de 20 % le score des suivants du même genre. */
    private const val SAME_GENRE_PENALTY = 0.8

    /**
     * Indices tirés de l'historique : un par film, un par série (somme de ses épisodes, plafonnée).
     * Un titre à peine ouvert (moins de 2 %) ne dit rien du goût et ne compte pas.
     */
    fun signalsFromProgress(progress: List<WatchProgressEntity>, now: Long): List<TasteSignal> {
        val out = LinkedHashMap<Pair<ContentKind, String>, Double>()
        for (p in progress) {
            if (p.durationMs <= 0) continue
            val fraction = (p.positionMs.toDouble() / p.durationMs).coerceIn(0.0, 1.0)
            if (fraction < 0.02) continue
            val (kind, uid) = when {
                p.kind == ContentKind.MOVIE && p.uid.isNotBlank() -> ContentKind.MOVIE to p.uid
                p.kind == ContentKind.SERIES && p.parentUid.isNotBlank() -> ContentKind.SERIES to p.parentUid
                else -> continue
            }
            val ageDays = ((now - p.updatedAt).coerceAtLeast(0L)) / DAY_MS
            val decay = 0.5.pow(ageDays / HALF_LIFE_DAYS)
            // Vu jusqu'au bout (90 %) : 1 ; abandonné au début : 0,3.
            val completion = 0.3 + 0.7 * (fraction / 0.9).coerceAtMost(1.0)
            val key = kind to uid
            out[key] = (out[key] ?: 0.0) + completion * decay
        }
        return out.map { (k, w) -> TasteSignal(k.first, k.second, if (k.first == ContentKind.SERIES) w.coerceAtMost(SERIES_CAP) else w) }
    }

    /** Goût par genre, ramené entre 0 et 1 (1 pour le genre préféré). */
    fun genreAffinity(signals: List<TasteSignal>, genresOf: (ContentKind, String) -> Set<String>): Map<String, Double> {
        val raw = HashMap<String, Double>()
        for (s in signals) {
            val genres = genresOf(s.kind, s.uid)
            if (genres.isEmpty() || s.weight <= 0) continue
            // Un titre classé dans 4 genres en dit moins sur chacun qu'un titre classé dans un seul.
            val share = s.weight / genres.size
            for (g in genres) raw[g] = (raw[g] ?: 0.0) + share
        }
        val max = raw.values.maxOrNull() ?: return emptyMap()
        return raw.mapValues { it.value / max }
    }

    /** Score d'un titre ; 0 s'il ne partage aucun genre avec ce que le profil regarde. */
    fun score(c: RecoCandidate, affinity: Map<String, Double>): Double {
        if (c.genres.isEmpty()) return 0.0
        val taste = c.genres.sumOf { affinity[it] ?: 0.0 } / sqrt(c.genres.size.toDouble())
        if (taste <= 0.0) return 0.0
        val rating = (c.rating / 10f).coerceIn(0f, 1f).toDouble()
        return taste + RATING_WEIGHT * rating + FRESHNESS_WEIGHT * c.freshness.coerceIn(0.0, 1.0)
    }

    /**
     * Les [limit] meilleurs titres, sans ceux déjà vus ou en favori ([exclude], clés « MOVIE:uid »),
     * en variant les genres.
     */
    fun rank(
        candidates: List<RecoCandidate>,
        affinity: Map<String, Double>,
        exclude: Set<String>,
        limit: Int,
    ): List<RecoCandidate> {
        if (affinity.isEmpty() || limit <= 0) return emptyList()
        data class Scored(val c: RecoCandidate, val base: Double, val main: String)
        val pool = candidates.asSequence()
            .filter { key(it.kind, it.uid) !in exclude }
            .distinctBy { key(it.kind, it.uid) }
            .mapNotNull { c ->
                val s = score(c, affinity)
                if (s <= 0.0) null
                // Genre qui explique le plus la proposition : c'est lui qu'on évite de répéter.
                else Scored(c, s, c.genres.maxBy { affinity[it] ?: 0.0 })
            }
            .sortedByDescending { it.base }
            .toMutableList()
        val used = HashMap<String, Int>()
        val out = ArrayList<RecoCandidate>(limit)
        while (out.size < limit && pool.isNotEmpty()) {
            var best = 0
            var bestScore = -1.0
            for (i in pool.indices) {
                val p = pool[i]
                // La liste est triée : dès que le score brut ne peut plus battre le meilleur, on s'arrête.
                if (p.base <= bestScore) break
                val s = p.base * SAME_GENRE_PENALTY.pow(used[p.main] ?: 0)
                if (s > bestScore) { bestScore = s; best = i }
            }
            val pick = pool.removeAt(best)
            used[pick.main] = (used[pick.main] ?: 0) + 1
            out += pick.c
        }
        return out
    }

    fun key(kind: ContentKind, uid: String) = "${kind.name}:$uid"
}

/** Titre proposé dans « Pour toi ». */
sealed interface RecoTitle {
    data class Movie(val movie: fr.yannickuhrig.vyzo.data.db.MovieEntity) : RecoTitle
    data class Series(val series: fr.yannickuhrig.vyzo.data.db.SeriesEntity) : RecoTitle
}
