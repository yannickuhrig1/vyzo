package fr.yannickuhrig.vyzo.data.trakt

import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.net.HttpStatusException
import fr.yannickuhrig.vyzo.data.prefs.AppSettings
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.util.LenientJson
import fr.yannickuhrig.vyzo.util.asArr
import fr.yannickuhrig.vyzo.util.asObj
import fr.yannickuhrig.vyzo.util.int
import fr.yannickuhrig.vyzo.util.str
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class TraktDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSec: Int,
    val intervalSec: Int,
)

data class TraktTokens(val access: String, val refresh: String, val expiresAt: Long)

sealed interface TraktPoll {
    data object Pending : TraktPoll
    data object SlowDown : TraktPoll
    data object Expired : TraktPoll
    data object Denied : TraktPoll
    data class Granted(val tokens: TraktTokens) : TraktPoll
}

/** La session Trakt n'est plus valable : il faut se reconnecter. */
class TraktSessionLost : IOException("Session Trakt expirée")

data class TraktTitle(val title: String, val year: Int?, val tmdbId: Int?)

data class TraktShow(val title: TraktTitle, val episodes: Set<Pair<Int, Int>>)

data class TraktWatched(val movies: List<TraktTitle>, val shows: List<TraktShow>)

/** Film, ou épisode si [season] et [episode] sont renseignés (le titre est alors celui de la série). */
data class TraktMedia(val title: String, val year: Int?, val tmdbId: Int?, val season: Int? = null, val episode: Int? = null) {
    val key: String get() = "$title|$year|$season|$episode"
    val isEpisode: Boolean get() = season != null && episode != null
}

val AppSettings.traktConnected: Boolean get() = traktAccessToken.isNotBlank()

/** Client de l'API Trakt. Aucune clé n'est livrée dans Vyzo : l'utilisateur crée sa propre application Trakt. */
class TraktClient(
    private val settings: SettingsStore,
    private val baseUrl: String = "https://api.trakt.tv",
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val client by lazy { Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }
    private val refreshLock = Mutex()

    private fun request(method: String, path: String, body: JsonElement?, clientId: String, token: String? = null): Request =
        Request.Builder().url(baseUrl + path)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", JSON_TYPE)
            .header("trakt-api-version", "2")
            .header("trakt-api-key", clientId)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .method(method, body?.toString()?.toRequestBody(JSON_TYPE.toMediaType()))
            .build()

    private suspend fun send(request: Request): String = Http.execute(client, request) { it.body?.string().orEmpty() }

    private fun parseObject(body: String): JsonObject = LenientJson.parseToJsonElement(body).asObj() ?: throw IOException("Réponse Trakt inattendue")

    suspend fun deviceCode(): TraktDeviceCode {
        val clientId = settings.get().traktClientId
        val json = parseObject(send(request("POST", "/oauth/device/code", buildJsonObject { put("client_id", clientId) }, clientId)))
        return TraktDeviceCode(json.str("device_code"), json.str("user_code"), json.str("verification_url"), json.int("expires_in"), json.int("interval"))
    }

    suspend fun pollDevice(deviceCode: String): TraktPoll {
        val s = settings.get()
        val body = buildJsonObject {
            put("code", deviceCode)
            put("client_id", s.traktClientId)
            put("client_secret", s.traktClientSecret)
        }
        return try {
            TraktPoll.Granted(tokensOf(parseObject(send(request("POST", "/oauth/device/token", body, s.traktClientId)))))
        } catch (e: HttpStatusException) {
            when (e.code) {
                400 -> TraktPoll.Pending
                429 -> TraktPoll.SlowDown
                404, 409, 410 -> TraktPoll.Expired
                418 -> TraktPoll.Denied
                else -> throw e
            }
        }
    }

    private fun tokensOf(json: JsonObject) = TraktTokens(json.str("access_token"), json.str("refresh_token"), now() + json.int("expires_in") * 1_000L)

    suspend fun revoke() {
        val s = settings.get()
        val body = buildJsonObject {
            put("token", s.traktAccessToken)
            put("client_id", s.traktClientId)
            put("client_secret", s.traktClientSecret)
        }
        send(request("POST", "/oauth/revoke", body, s.traktClientId))
    }

    private suspend fun validToken(): String {
        val s = settings.get()
        if (s.traktAccessToken.isBlank()) throw TraktSessionLost()
        return if (s.traktTokenExpiresAt - now() < REFRESH_MARGIN_MS) refreshAfter(s.traktAccessToken) else s.traktAccessToken
    }

    /** Renouvelle le jeton [stale], sauf si un autre appel vient de le faire. */
    private suspend fun refreshAfter(stale: String): String = refreshLock.withLock {
        val s = settings.get()
        if (s.traktAccessToken.isNotBlank() && s.traktAccessToken != stale) return@withLock s.traktAccessToken
        if (s.traktRefreshToken.isBlank()) throw TraktSessionLost()
        val body = buildJsonObject {
            put("refresh_token", s.traktRefreshToken)
            put("client_id", s.traktClientId)
            put("client_secret", s.traktClientSecret)
            put("redirect_uri", DEVICE_REDIRECT)
            put("grant_type", "refresh_token")
        }
        val tokens = try {
            tokensOf(parseObject(send(request("POST", "/oauth/token", body, s.traktClientId))))
        } catch (e: HttpStatusException) {
            if (e.code != 400 && e.code != 401) throw e
            settings.update { it.copy(traktAccessToken = "", traktRefreshToken = "", traktTokenExpiresAt = 0) }
            throw TraktSessionLost()
        }
        settings.update { it.copy(traktAccessToken = tokens.access, traktRefreshToken = tokens.refresh, traktTokenExpiresAt = tokens.expiresAt) }
        tokens.access
    }

    private suspend fun authed(method: String, path: String, body: JsonElement? = null): String {
        val clientId = settings.get().traktClientId
        val token = validToken()
        return try {
            send(request(method, path, body, clientId, token))
        } catch (e: HttpStatusException) {
            if (e.code != 401) throw e
            send(request(method, path, body, clientId, refreshAfter(token)))
        }
    }

    private fun JsonObject.toTitle(): TraktTitle? {
        val name = str("title").takeIf { it.isNotBlank() } ?: return null
        return TraktTitle(name, int("year").takeIf { it > 0 }, this["ids"].asObj()?.int("tmdb")?.takeIf { it > 0 })
    }

    private fun parseArray(body: String) = LenientJson.parseToJsonElement(body).asArr()?.mapNotNull { it.asObj() } ?: throw IOException("Réponse Trakt inattendue")

    suspend fun watched(): TraktWatched {
        val movies = parseArray(authed("GET", "/sync/watched/movies")).mapNotNull { it["movie"].asObj()?.toTitle() }
        val shows = parseArray(authed("GET", "/sync/watched/shows")).mapNotNull { item ->
            val title = item["show"].asObj()?.toTitle() ?: return@mapNotNull null
            val episodes = item["seasons"].asArr()?.mapNotNull { it.asObj() }.orEmpty().flatMap { season ->
                season["episodes"].asArr()?.mapNotNull { it.asObj() }.orEmpty().map { season.int("number") to it.int("number") }
            }.toSet()
            TraktShow(title, episodes)
        }
        return TraktWatched(movies, shows)
    }

    /** Liste de suivi ou recommandations : les éléments sont parfois enveloppés dans « movie » ou « show ». */
    suspend fun titles(path: String): List<TraktTitle> =
        parseArray(authed("GET", path)).mapNotNull { item -> (item["movie"].asObj() ?: item["show"].asObj() ?: item).toTitle() }

    suspend fun scrobble(action: String, media: TraktMedia, progress: Double) {
        val body = buildJsonObject {
            if (media.isEpisode) {
                put("show", media.identity())
                putJsonObject("episode") {
                    put("season", media.season)
                    put("number", media.episode)
                }
            } else {
                put("movie", media.identity())
            }
            put("progress", progress)
        }
        try {
            authed("POST", "/scrobble/$action", body)
        } catch (e: HttpStatusException) {
            // 409 : déjà pris en compte. 404 : titre inconnu de Trakt.
            if (e.code != 409 && e.code != 404) throw e
        }
    }

    suspend fun addToHistory(items: List<TraktMedia>) {
        val (episodes, movies) = items.partition { it.isEpisode }
        val body = buildJsonObject {
            put("movies", buildJsonArray { movies.forEach { add(it.identity()) } })
            put(
                "shows",
                buildJsonArray {
                    episodes.groupBy { Triple(it.title, it.year, it.tmdbId) }.values.forEach { show ->
                        add(
                            JsonObject(
                                show.first().identity() + buildJsonObject {
                                    putJsonArray("seasons") {
                                        show.groupBy { it.season }.forEach { (season, list) ->
                                            add(
                                                buildJsonObject {
                                                    put("number", season)
                                                    putJsonArray("episodes") { list.forEach { add(buildJsonObject { put("number", it.episode) }) } }
                                                },
                                            )
                                        }
                                    }
                                },
                            ),
                        )
                    }
                },
            )
        }
        authed("POST", "/sync/history", body)
    }

    private fun TraktMedia.identity(): JsonObject = buildJsonObject {
        put("title", title)
        year?.let { put("year", it) }
        putJsonObject("ids") { tmdbId?.let { put("tmdb", it) } }
    }

    private companion object {
        const val USER_AGENT = "Vyzo"
        const val JSON_TYPE = "application/json"
        const val DEVICE_REDIRECT = "urn:ietf:wg:oauth:2.0:oob"
        const val REFRESH_MARGIN_MS = 24 * 3_600_000L
    }
}
