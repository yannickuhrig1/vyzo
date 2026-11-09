package fr.yannickuhrig.vyzo.data.intro

import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.net.HttpStatusException
import fr.yannickuhrig.vyzo.util.LenientJson
import fr.yannickuhrig.vyzo.util.asArr
import fr.yannickuhrig.vyzo.util.asObj
import fr.yannickuhrig.vyzo.util.float
import fr.yannickuhrig.vyzo.util.int
import fr.yannickuhrig.vyzo.util.long
import fr.yannickuhrig.vyzo.util.str
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Identifiant d'une série pour TheIntroDB : le paramètre de la requête et sa valeur. */
data class SeriesRef(val param: String, val value: String) {
    companion object {
        fun tmdb(id: Int) = SeriesRef("tmdb_id", id.toString())
    }
}

/** Passage tel que renvoyé par TheIntroDB : début absent = début de l'épisode, fin absente = fin de l'épisode. */
data class RawSegment(val startMs: Long?, val endMs: Long?, val confidence: Float = 0f, val submissions: Int = 0)

/** Réponse de TheIntroDB pour un épisode. [groupDurationMs] : durée de la version décrite, null si inconnue. */
data class EpisodeTimestamps(
    val groupDurationMs: Long?,
    val intro: List<RawSegment>,
    val credits: List<RawSegment>,
    val recap: List<RawSegment> = emptyList(),
)

/** Type de passage qu'on peut sauter, avec le message affiché une fois sauté. */
enum class SkipKind(val skippedMessage: String) {
    RECAP("Récap passé"),
    INTRO("Intro passée"),
}

/** Passage à sauter. [auto] : données assez sûres pour sauter sans action de l'utilisateur. */
data class SkipSegment(val startMs: Long, val endMs: Long, val auto: Boolean, val kind: SkipKind)

/** Repères d'un épisode, vérifiés contre sa durée réelle. Vide : aucune donnée exploitable. */
data class EpisodeMarkers(
    val intros: List<SkipSegment> = emptyList(),
    /** TheIntroDB indique que l'épisode n'a pas d'intro : il n'y a rien à passer. */
    val noIntro: Boolean = false,
    val creditsStartMs: Long? = null,
    val recaps: List<SkipSegment> = emptyList(),
) {
    val hasIntroData get() = intros.isNotEmpty() || noIntro

    fun introAt(positionMs: Long): SkipSegment? = intros.at(positionMs)

    fun recapAt(positionMs: Long): SkipSegment? = recaps.at(positionMs)

    /** Passage à sauter à cette position. Si la base les fait se chevaucher, le récap d'abord : il précède l'intro. */
    fun skipAt(positionMs: Long): SkipSegment? = recapAt(positionMs) ?: introAt(positionMs)

    private fun List<SkipSegment>.at(positionMs: Long) = firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }
}

/** Au-delà, ce n'est plus la même version : PAL (4 % plus court), coupes… et les repères seraient décalés. */
private const val MAX_VERSION_GAP = 0.02

/** Un saut sans action de l'utilisateur exige la même version à quelques secondes près. */
private const val MAX_AUTO_GAP_MS = 10_000L
private const val MIN_AUTO_SUBMISSIONS = 2
private const val MIN_AUTO_CONFIDENCE = 0.4f

/** Bornes des passages acceptés par TheIntroDB. */
private const val MIN_SKIP_MS = 5_000L
private const val MAX_SKIP_MS = 300_000L

/** Un récap se termine dans le premier tiers de l'épisode : plus loin, c'est une erreur de la base. */
private const val RECAP_END_DIVISOR = 3L

/** Un générique peut être suivi d'un aperçu de l'épisode suivant, pas d'une longue partie de l'épisode. */
private const val MAX_AFTER_CREDITS_MS = 120_000L

/**
 * Garde seulement ce qui correspond au fichier lu. La base contient des erreurs : générique placé sur l'ouverture,
 * passages de 2 s, durées de version aberrantes, repères au-delà de la fin. Dans le doute, rien : le saut reste manuel.
 */
fun EpisodeTimestamps.markersFor(durationMs: Long): EpisodeMarkers {
    if (durationMs <= 0) return EpisodeMarkers()
    val gap = groupDurationMs?.let { abs(it - durationMs) }
    if (gap != null && gap > durationMs * MAX_VERSION_GAP) return EpisodeMarkers()
    fun List<RawSegment>.skippable(kind: SkipKind) = mapNotNull { s ->
        val end = s.endMs ?: return@mapNotNull null
        val start = s.startMs ?: 0L
        if (end - start !in MIN_SKIP_MS..MAX_SKIP_MS || end >= durationMs) return@mapNotNull null
        val sure = gap != null && gap <= MAX_AUTO_GAP_MS && s.submissions >= MIN_AUTO_SUBMISSIONS && s.confidence >= MIN_AUTO_CONFIDENCE
        SkipSegment(start, end, auto = sure, kind = kind)
    }
    val intros = intro.skippable(SkipKind.INTRO)
    // Une fin à 0, la convention de l'API pour « pas de passage », est écartée par la durée minimale.
    val recaps = recap.skippable(SkipKind.RECAP).filter { it.endMs <= durationMs / RECAP_END_DIVISOR }
    val credits = credits.filter { s ->
        val start = s.startMs ?: return@filter false
        start >= durationMs / 2 && start < durationMs && (s.endMs == null || s.endMs >= durationMs - MAX_AFTER_CREDITS_MS)
    }.minOfOrNull { it.startMs!! }
    return EpisodeMarkers(
        intros = intros,
        // Fin à 0 : la convention de l'API pour « pas de passage ».
        noIntro = intros.isEmpty() && intro.any { it.endMs == 0L },
        creditsStartMs = credits,
        recaps = recaps,
    )
}

/**
 * Horodatages d'épisodes de TheIntroDB (https://theintrodb.org).
 * Ses conditions n'autorisent que des appels depuis l'appareil de l'utilisateur et un cache limité à la session :
 * seul l'épisode en cours est demandé, et rien n'est écrit sur le disque.
 */
class IntroDbClient(var baseUrl: String = "https://api.theintrodb.org/v4") {

    private val http by lazy {
        Http.client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    private val session = object : LinkedHashMap<String, EpisodeTimestamps?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EpisodeTimestamps?>) = size > 32
    }

    /** Null : pas de données ou service injoignable. */
    suspend fun episode(series: SeriesRef, season: Int, episode: Int, durationMs: Long): EpisodeTimestamps? {
        val key = "${series.param}=${series.value}:$season:$episode:$durationMs"
        synchronized(session) { if (session.containsKey(key)) return session[key] }
        val url = "$baseUrl/media?${series.param}=${series.value}&season=$season&episode=$episode&duration_ms=$durationMs&details=true"
        return try {
            Http.execute(client = http, request = Request.Builder().url(url).build()) { res ->
                LenientJson.parseToJsonElement(res.body?.string().orEmpty()).asObj()?.let(::parse)
            }.also { remember(key, it) }
        } catch (e: HttpStatusException) {
            // 404 : personne n'a renseigné cet épisode. 429 ou panne : pas de mémorisation, il pourra répondre plus tard.
            if (e.code == 404) remember(key, null)
            null
        } catch (e: IOException) {
            null
        } catch (e: SerializationException) {
            null
        }
    }

    private fun remember(key: String, value: EpisodeTimestamps?) {
        synchronized(session) { session[key] = value }
    }

    companion object {
        fun parse(json: JsonObject): EpisodeTimestamps = EpisodeTimestamps(
            groupDurationMs = json.long("group_duration_ms").takeIf { it > 0 },
            intro = segments(json, "intro"),
            credits = segments(json, "credits"),
            recap = segments(json, "recap"),
        )

        private fun segments(json: JsonObject, key: String): List<RawSegment> =
            json[key].asArr().orEmpty().mapNotNull { it.asObj() }.map { o ->
                RawSegment(
                    startMs = o.str("start_ms").toLongOrNull(),
                    endMs = o.str("end_ms").toLongOrNull(),
                    confidence = o.float("confidence"),
                    submissions = o.int("submission_count"),
                )
            }
    }
}
