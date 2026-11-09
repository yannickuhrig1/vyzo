package fr.yannickuhrig.vyzo.data.source.stalker

import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.diag.DiagLog
import fr.yannickuhrig.vyzo.util.LenientJson
import fr.yannickuhrig.vyzo.util.arr
import fr.yannickuhrig.vyzo.util.int
import fr.yannickuhrig.vyzo.util.md5
import fr.yannickuhrig.vyzo.util.obj
import fr.yannickuhrig.vyzo.util.sha256Upper
import fr.yannickuhrig.vyzo.util.str
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.URLEncoder
import java.util.TimeZone

data class StalkerPage(val items: List<JsonObject>, val totalItems: Int, val pageSize: Int) {
    val totalPages: Int get() = if (pageSize <= 0) 1 else (totalItems + pageSize - 1) / pageSize
}

/**
 * Client pour les portails Stalker / Ministra (authentification par adresse MAC, comme un boîtier MAG).
 */
class StalkerClient(portalUrl: String, mac: String, private val timezone: String = TimeZone.getDefault().id) {
    val mac: String = mac.trim().uppercase()
    private val portal: String = normalize(portalUrl)
    val host: String = portal.toHttpUrl().let { "${it.scheme}://${it.host}:${it.port}" }
    private val mutex = Mutex()

    @Volatile var endpoint: String? = null
        private set
    @Volatile private var token: String = ""

    private val candidates: List<String> = buildList {
        if (portal.endsWith(".php")) add(portal)
        else {
            add("$portal/portal.php")
            add("$portal/server/load.php")
            add("$portal/stalker_portal/server/load.php")
            add("$host/stalker_portal/server/load.php")
            add("$host/portal.php")
        }
    }.distinct()

    private val serial = mac.md5().uppercase().take(13)
    private val deviceId = mac.sha256Upper()

    private fun headers(): Map<String, String> = buildMap {
        put("X-User-Agent", "Model: MAG250; Link: WiFi")
        put("Referer", "$host/c/")
        put("Accept", "*/*")
        put("Cookie", "mac=${URLEncoder.encode(mac, "UTF-8")}; stb_lang=fr; timezone=${URLEncoder.encode(timezone, "UTF-8")}")
        if (token.isNotBlank()) put("Authorization", "Bearer $token")
    }

    private suspend fun raw(ep: String, params: Map<String, String>): JsonElement? {
        val url = ep.toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("JsHttpRequest", "1-xml")
        }.build().toString()
        val body = Http.getString(url, MAG_UA, headers())
        // Journal de diagnostic : action et début de la réponse (MAC, jetons et numéro de série masqués).
        DiagLog.log("Stalker", "${params["type"]}/${params["action"]} → ${body.take(400).replace('\n', ' ')}")
        if (body.contains("Authorization failed", ignoreCase = true)) throw AuthException()
        val root = runCatching { LenientJson.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: throw IOException("Réponse invalide du portail")
        return root["js"]
    }

    private class AuthException : IOException("Authentification refusée par le portail")

    suspend fun connect() = mutex.withLock {
        var lastError: Exception? = null
        for (ep in (listOfNotNull(endpoint) + candidates).distinct()) {
            try {
                token = ""
                val js = raw(ep, mapOf("type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0")) as? JsonObject
                val t = js?.str("token").orEmpty()
                if (t.isBlank()) continue
                token = t
                endpoint = ep
                raw(
                    ep,
                    mapOf(
                        "type" to "stb", "action" to "get_profile", "hd" to "1",
                        "ver" to "ImageDescription: 0.2.18-r23-250; ImageDate: Fri Jan 15 15:20:44 EET 2016; PORTAL version: 5.6.1; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c",
                        "num_banks" to "2", "sn" to serial, "stb_type" to "MAG250", "client_type" to "STB",
                        "image_version" to "218", "video_out" to "hdmi", "device_id" to deviceId, "device_id2" to deviceId,
                        "signature" to "", "auth_second_step" to "1", "hw_version" to "1.7-BD-00",
                        "not_valid_token" to "0", "api_signature" to "262", "prehash" to "",
                        "timestamp" to (System.currentTimeMillis() / 1000).toString(),
                    ),
                )
                return@withLock
            } catch (e: Exception) {
                DiagLog.error("Stalker", "connexion via $ep impossible", e)
                lastError = e
            }
        }
        endpoint = null
        throw IOException("Portail Stalker injoignable ou adresse MAC refusée" + (lastError?.message?.let { " ($it)" } ?: ""))
    }

    suspend fun call(params: Map<String, String>): JsonElement? {
        if (endpoint == null || token.isBlank()) connect()
        return try {
            raw(endpoint!!, params)
        } catch (e: AuthException) {
            connect()
            raw(endpoint!!, params)
        }
    }

    suspend fun accountExpiry(): String = runCatching {
        val js = call(mapOf("type" to "account_info", "action" to "get_main_info")) as? JsonObject
        js?.str("end_date")?.ifBlank { js.str("phone") }.orEmpty()
    }.getOrDefault("")

    suspend fun genres(): List<JsonObject> =
        (call(mapOf("type" to "itv", "action" to "get_genres")) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    suspend fun allChannels(): List<JsonObject> {
        val js = call(mapOf("type" to "itv", "action" to "get_all_channels")) as? JsonObject
        return js?.arr("data")?.mapNotNull { it as? JsonObject }.orEmpty()
    }

    suspend fun channelPage(genreId: String, page: Int): StalkerPage = page(
        mapOf(
            "type" to "itv", "action" to "get_ordered_list", "genre" to genreId, "force_ch_link_check" to "",
            "fav" to "0", "sortby" to "number", "hd" to "0", "p" to page.toString(),
        ),
    )

    suspend fun vodCategories(type: String = "vod"): List<JsonObject> =
        (call(mapOf("type" to type, "action" to "get_categories")) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    suspend fun vodPage(type: String, categoryId: String, page: Int): StalkerPage = page(
        mapOf(
            "type" to type, "action" to "get_ordered_list", "category" to categoryId, "genre" to "*",
            "p" to page.toString(), "sortby" to "added", "fav" to "0", "hd" to "0", "not_ended" to "0",
        ),
    )

    suspend fun seasons(seriesId: String): List<JsonObject> = page(
        mapOf(
            "type" to "series", "action" to "get_ordered_list", "movie_id" to seriesId,
            "season_id" to "0", "episode_id" to "0", "p" to "1",
        ),
    ).items

    private suspend fun page(params: Map<String, String>): StalkerPage {
        val js = call(params) as? JsonObject ?: return StalkerPage(emptyList(), 0, 0)
        val data = js.arr("data")?.mapNotNull { it as? JsonObject }.orEmpty()
        return StalkerPage(data, js.int("total_items"), js.int("max_page_items"))
    }

    /** Programmes des [hours] prochaines heures pour toutes les chaînes : ch_id → liste. */
    suspend fun epgInfo(hours: Int): Map<String, List<JsonObject>> {
        val js = call(mapOf("type" to "itv", "action" to "get_epg_info", "period" to hours.toString()))
        val data = (js as? JsonObject)?.let { it["data"] ?: it } ?: return emptyMap()
        val out = HashMap<String, List<JsonObject>>()
        when (data) {
            is JsonObject -> data.forEach { (k, v) -> (v as? JsonArray)?.let { arr -> out[k] = arr.mapNotNull { it as? JsonObject } } }
            is JsonArray -> data.mapNotNull { it as? JsonObject }.groupBy { it.str("ch_id") }.forEach { (k, v) -> out[k] = v }
            else -> Unit
        }
        return out
    }

    suspend fun shortEpg(channelId: String, size: Int = 10): List<JsonObject> =
        (call(mapOf("type" to "itv", "action" to "get_short_epg", "ch_id" to channelId, "size" to size.toString())) as? JsonArray)
            ?.mapNotNull { it as? JsonObject }.orEmpty()

    /** Transforme une commande ("ffmpeg http://…") en URL lisible. */
    suspend fun createLink(type: String, cmd: String, series: String = ""): String {
        val js = call(
            mapOf(
                "type" to type, "action" to "create_link", "cmd" to cmd, "series" to series,
                "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0", "force_ch_link_check" to "0",
            ),
        ) as? JsonObject
        val link = js?.str("cmd").orEmpty().ifBlank { cmd }
        return stripCmd(link)
    }

    suspend fun archiveLink(programId: String): String = createLink("tv_archive", "auto /media/$programId.mpg")

    fun logoUrl(logo: String): String = when {
        logo.isBlank() -> ""
        logo.startsWith("http") -> logo
        logo.startsWith("/") -> host + logo
        else -> "$host/stalker_portal/misc/logos/320/$logo"
    }

    fun imageUrl(path: String): String = when {
        path.isBlank() -> ""
        path.startsWith("http") -> path
        else -> host + (if (path.startsWith("/")) "" else "/") + path
    }

    companion object {
        const val MAG_UA =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

        fun stripCmd(cmd: String): String {
            val c = cmd.trim()
            val idx = c.indexOf("http", ignoreCase = true)
            return if (idx > 0) c.substring(idx).trim() else c.removePrefix("ffmpeg ").removePrefix("ffrt ").trim()
        }

        fun normalize(raw: String): String {
            var s = raw.trim()
            if (!s.contains("://")) s = "http://$s"
            s = s.substringBefore('?').trimEnd('/')
            if (s.endsWith("/c")) s = s.removeSuffix("/c")
            return s
        }

        fun isValidMac(mac: String): Boolean = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(mac.trim())
    }
}

fun JsonObject.stalkerInt(key: String): Int = int(key)
fun JsonObject.stalkerObj(key: String): JsonObject? = obj(key)

object StalkerVodParser {
    val yearRegex = Regex("""\((\d{4})\)""")

    /** Convertit le champ `added` (String ou timestamp) en Long (ms). 0L si absent ou null. */
    fun parseAdded(addedRaw: String?): Long {
        val s = addedRaw?.trim().orEmpty()
        val sec = s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong() ?: return 0L
        return if (sec in 1..9_999_999_999L) sec * 1000L else sec
    }

    /** Convertit `rating` ou `rating_imdb` ou `rating_5based` en Float sur 10. 0f si null ou absent. */
    fun parseRating(rating: Float?, ratingImdb: Float?, rating5Based: Float?): Float {
        if (rating != null && rating > 0f) return rating
        if (ratingImdb != null && ratingImdb > 0f) return ratingImdb
        if (rating5Based != null && rating5Based > 0f) return rating5Based * 2f
        return 0f
    }

    /**
     * Extrait l'année (year) depuis la date de publication si elle existe,
     * ou via une Regex sur le titre du film Regex("""\((\d{4})\)""") si le serveur ne renvoie pas l'année explicitement.
     */
    fun extractYear(rawYear: String?, releaseDate: String?, title: String): String {
        val y = rawYear?.trim()?.take(4).orEmpty()
        if (y.length == 4 && y.all { it.isDigit() }) return y
        val r = releaseDate?.trim()?.take(4).orEmpty()
        if (r.length == 4 && r.all { it.isDigit() }) return r
        return yearRegex.find(title)?.groupValues?.get(1).orEmpty()
    }

    fun parseReleaseDate(o: JsonObject): String =
        o.str("release_date").ifBlank { o.str("releasedate") }
}
