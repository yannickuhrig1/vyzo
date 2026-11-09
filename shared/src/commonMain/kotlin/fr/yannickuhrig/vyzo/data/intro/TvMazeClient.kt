package fr.yannickuhrig.vyzo.data.intro

import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.net.HttpStatusException
import fr.yannickuhrig.vyzo.data.tmdb.cleanTitleForTmdb
import fr.yannickuhrig.vyzo.util.LenientJson
import fr.yannickuhrig.vyzo.util.TextNorm
import fr.yannickuhrig.vyzo.util.asArr
import fr.yannickuhrig.vyzo.util.asObj
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
        return try {
            Http.execute(client = http, request = Request.Builder().url(url).build()) { res ->
                // Réponse illisible : une panne, à ne pas retenir comme un titre inconnu.
                val results = LenientJson.parseToJsonElement(res.body?.string().orEmpty()).asArr()
                    ?: throw SerializationException("réponse TVmaze illisible")
                tvMazeMatch(results.mapNotNull { it.asObj() }, title, year)
            }.also { ref -> synchronized(session) { session[key] = ref } }
        } catch (e: HttpStatusException) {
            null
        } catch (e: IOException) {
            null
        } catch (e: SerializationException) {
            null
        }
    }
}
