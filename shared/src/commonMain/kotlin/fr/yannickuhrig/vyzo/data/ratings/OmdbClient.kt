package fr.yannickuhrig.vyzo.data.ratings

import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.data.tmdb.MetadataCache
import fr.yannickuhrig.vyzo.util.LenientJson
import fr.yannickuhrig.vyzo.util.asArr
import fr.yannickuhrig.vyzo.util.asObj
import fr.yannickuhrig.vyzo.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Notes d'un film ou d'une série, telles qu'OMDb les écrit (« 8.8 », « 87% », « 74 »). */
data class OmdbRatings(val imdb: String? = null, val rottenTomatoes: String? = null, val metacritic: String? = null) {
    fun labels(): List<String> = listOfNotNull(
        imdb?.let { "IMDb ${it.replace('.', ',')}" },
        rottenTomatoes?.let { "Rotten Tomatoes ${it.replace("%", " %")}" },
        metacritic?.let { "Metacritic $it" },
    )
}

/** Null si OMDb ne connaît pas le titre ou n'a aucune note. */
fun parseOmdb(json: JsonObject): OmdbRatings? {
    if (!json.str("Response").equals("True", ignoreCase = true)) return null
    fun known(value: String?) = value?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
    val sources = json["Ratings"].asArr()?.mapNotNull { it.asObj() }.orEmpty().associate { it.str("Source") to it.str("Value") }
    val ratings = OmdbRatings(
        imdb = known(sources["Internet Movie Database"])?.substringBefore('/') ?: known(json.str("imdbRating")),
        rottenTomatoes = known(sources["Rotten Tomatoes"]),
        metacritic = known(sources["Metacritic"])?.substringBefore('/') ?: known(json.str("Metascore")),
    )
    return ratings.takeIf { it.labels().isNotEmpty() }
}

/**
 * Notes IMDb, Rotten Tomatoes et Metacritic. Appelé seulement à l'ouverture d'une fiche, et mis en cache :
 * l'offre gratuite d'OMDb est limitée à 1 000 requêtes par jour et réservée à un usage personnel, d'où la clé de l'utilisateur.
 */
class OmdbClient(private val settings: SettingsStore, private val cache: MetadataCache? = null) {
    private val client by lazy {
        Http.client.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()
    }

    suspend fun ratings(imdbId: String): OmdbRatings? = withContext(Dispatchers.IO) {
        val apiKey = settings.get().omdbApiKey.trim()
        if (apiKey.isBlank()) return@withContext null
        val key = "omdb:$imdbId"
        cache?.get(key)?.let { cached ->
            runCatching { LenientJson.parseToJsonElement(cached).asObj() }.getOrNull()?.let { return@withContext parseOmdb(it) }
        }
        val body = runCatching {
            val req = Request.Builder().url("https://www.omdbapi.com/?i=$imdbId&apikey=$apiKey").build()
            Http.execute(client = client, request = req) { res -> if (res.isSuccessful) res.body?.string() else null }
        }.getOrNull() ?: return@withContext null
        val ratings = runCatching { LenientJson.parseToJsonElement(body).asObj() }.getOrNull()?.let(::parseOmdb) ?: return@withContext null
        cache?.put(key, body)
        ratings
    }
}
