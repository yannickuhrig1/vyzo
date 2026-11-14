package fr.yannickuhrig.vyzo.data.intro

import fr.yannickuhrig.vyzo.data.db.SeriesScheduleEntity
import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.net.HttpStatusException
import fr.yannickuhrig.vyzo.data.tmdb.cleanTitleForTmdb
import fr.yannickuhrig.vyzo.util.LenientJson
import fr.yannickuhrig.vyzo.util.TextNorm
import fr.yannickuhrig.vyzo.util.asArr
import fr.yannickuhrig.vyzo.util.asObj
import fr.yannickuhrig.vyzo.util.int
import fr.yannickuhrig.vyzo.util.str
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * La seule série de la recherche TVmaze portant exactement ce titre (et cette année si connue), sinon null :
 * un résultat approchant donnerait les repères d'une autre série.
 */
fun tvMazeMatch(results: List<JsonObject>, title: String, year: Int?): SeriesRef? {
    val wanted = TextNorm.search(title)
    val show = results.mapNotNull { it["show"].asObj() }.filter { s ->
        TextNorm.search(s.str("name")) == wanted && (year == null || s.str("premiered").take(4) == year.toString())
    }.singleOrNull() ?: return null
    val externals = show["externals"].asObj() ?: return null
    return externals.str("imdb").takeIf { it.isNotBlank() }?.let { SeriesRef("imdb_id", it) }
        ?: externals.str("thetvdb").takeIf { it.isNotBlank() }?.let { SeriesRef("tvdb_id", it) }
}

/**
 * Retrouve une série par son titre sur TVmaze (https://www.tvmaze.com/api, sans clé, licence CC BY-SA), pour les
 * panels qui ne publient pas d'identifiant TMDB. Seul le titre part, et seulement quand un épisode est lu.
 */
class TvMazeClient(var baseUrl: String = "https://api.tvmaze.com") {

    private val http by lazy {
        Http.client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    private val session = object : LinkedHashMap<String, SeriesRef?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SeriesRef?>) = size > 32
    }

    /** Null : titre absent ou ambigu, ou service injoignable. */
    suspend fun findSeries(rawTitle: String, hintYear: Int?): SeriesRef? {
        val (title, extractedYear) = cleanTitleForTmdb(rawTitle)
        val year = hintYear ?: extractedYear
        val key = "$title:$year"
        synchronized(session) { if (session.containsKey(key)) return session[key] }
        val url = "${baseUrl.trimEnd('/')}/search/shows".toHttpUrl().newBuilder().addQueryParameter("q", title).build()
        return executeOrNull(url) { body ->
            // Réponse illisible : une panne, à ne pas retenir comme un titre inconnu.
            val results = LenientJson.parseToJsonElement(body).asArr()
                ?: throw SerializationException("réponse TVmaze illisible")
            tvMazeMatch(results.mapNotNull { it.asObj() }, title, year)
                .also { ref -> synchronized(session) { session[key] = ref } }
        }
    }

    /** Retourne le statut et le prochain épisode via TVmaze (sans clé API). */
    suspend fun fetchSchedule(
        seriesUid: String,
        rawTitle: String,
        hintYear: Int? = null,
        now: Long = System.currentTimeMillis(),
    ): SeriesScheduleEntity? {
        val (title, extractedYear) = cleanTitleForTmdb(rawTitle)
        if (title.isBlank()) return null
        val year = hintYear ?: extractedYear
        val base = baseUrl.trimEnd('/')
        val singleUrl = "$base/singlesearch/shows".toHttpUrl().newBuilder()
            .addQueryParameter("q", title)
            .addQueryParameter("embed", "nextepisode")
            .build()
        val direct = executeOrNull(singleUrl) { LenientJson.parseToJsonElement(it).asObj() }
        if (direct != null && matchesShow(direct, title, year)) {
            return parseTvMazeSchedule(seriesUid, direct, now)
        }
        val searchUrl = "$base/search/shows".toHttpUrl().newBuilder().addQueryParameter("q", title).build()
        val id = executeOrNull(searchUrl) { body ->
            val arr = LenientJson.parseToJsonElement(body).asArr() ?: return@executeOrNull null
            arr.mapNotNull { it.asObj()?.get("show").asObj() }
                .filter { matchesShow(it, title, year) }
                .singleOrNull()
                ?.int("id")
                ?.takeIf { it > 0 }
        } ?: return null
        val showUrl = "$base/shows/$id".toHttpUrl().newBuilder().addQueryParameter("embed", "nextepisode").build()
        val show = executeOrNull(showUrl) { LenientJson.parseToJsonElement(it).asObj() } ?: return null
        return parseTvMazeSchedule(seriesUid, show, now)
    }

    private suspend fun <T> executeOrNull(url: okhttp3.HttpUrl, parse: (String) -> T?): T? = try {
        Http.execute(client = http, request = Request.Builder().url(url).build()) { res ->
            parse(res.body?.string().orEmpty())
        }
    } catch (_: HttpStatusException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    }

    private fun matchesShow(show: JsonObject, title: String, year: Int?): Boolean {
        if (TextNorm.search(show.str("name")) != TextNorm.search(title)) return false
        if (year != null) {
            val premieredYear = show.str("premiered").take(4)
            if (premieredYear.isNotBlank() && premieredYear != year.toString()) return false
        }
        return true
    }
}

/** Parse une réponse TVmaze (`/shows/{id}?embed=nextepisode` ou `singlesearch`) en [SeriesScheduleEntity]. */
fun parseTvMazeSchedule(
    seriesUid: String,
    show: JsonObject,
    now: Long = System.currentTimeMillis(),
): SeriesScheduleEntity {
    val rawStatus = show.str("status")
    val status = when (rawStatus.trim().lowercase()) {
        "running" -> "Returning Series"
        "ended" -> "Ended"
        "to be determined", "in development" -> "Planned"
        else -> rawStatus
    }
    val next = show["_embedded"].asObj()?.get("nextepisode").asObj()
    return SeriesScheduleEntity(
        seriesUid = seriesUid,
        status = status,
        nextSeason = next?.int("season") ?: 0,
        nextEpisode = next?.int("number") ?: 0,
        nextTitle = next?.str("name").orEmpty(),
        nextAirDate = next?.str("airdate").orEmpty(),
        upcomingSeason = 0,
        upcomingSeasonAirDate = "",
        upcomingSeasonEpisodes = 0,
        tmdbId = 0,
        checkedAt = now,
    )
}
