package fr.yannickuhrig.vyzo.data.source.xtream

import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.diag.DiagLog
import fr.yannickuhrig.vyzo.util.LenientJson
import fr.yannickuhrig.vyzo.util.arr
import fr.yannickuhrig.vyzo.util.int
import fr.yannickuhrig.vyzo.util.long
import fr.yannickuhrig.vyzo.util.obj
import fr.yannickuhrig.vyzo.util.prim
import fr.yannickuhrig.vyzo.util.str
import fr.yannickuhrig.vyzo.util.streamJsonObjects
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class XtreamAccount(
    val authenticated: Boolean,
    val status: String,
    val expiresAt: Long,
    val maxConnections: Int,
    val activeConnections: Int,
    val allowedFormats: List<String>,
    val serverTimezone: String,
    val message: String,
)

data class XtreamCredentials(val server: String, val username: String, val password: String)

class XtreamClient(server: String, val username: String, val password: String, private val userAgent: String) {
    val base: String = normalizeServer(server)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private val apiBase get() = "$base/player_api.php?username=${enc(username)}&password=${enc(password)}"

    fun apiUrl(action: String? = null, extra: String = ""): String =
        apiBase + (action?.let { "&action=$it" } ?: "") + extra

    val xmltvUrl: String get() = "$base/xmltv.php?username=${enc(username)}&password=${enc(password)}"

    suspend fun auth(): XtreamAccount {
        val body = Http.getString(apiUrl(), userAgent)
        val root = runCatching { LenientJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw IOException("Réponse inattendue du serveur Xtream")
        val user = root.obj("user_info") ?: throw IOException("Identifiants refusés par le serveur")
        val server = root.obj("server_info")
        val auth = user.int("auth") == 1 || user.str("auth") == "true"
        DiagLog.log(
            "Xtream",
            "compte : auth=$auth statut=${user.str("status")} connexions=${user.str("active_cons")}/${user.str("max_connections")} " +
                "formats=${user["allowed_output_formats"]} fuseau=${server?.str("timezone").orEmpty()}",
        )
        val formats = user.arr("allowed_output_formats")?.mapNotNull { it.prim() } ?: emptyList()
        return XtreamAccount(
            authenticated = auth,
            status = user.str("status"),
            expiresAt = user.long("exp_date") * 1000L,
            maxConnections = user.int("max_connections"),
            activeConnections = user.int("active_cons"),
            allowedFormats = formats,
            serverTimezone = server?.str("timezone").orEmpty(),
            message = user.str("message"),
        )
    }

    /** Lit une liste (catégories, flux…) en flux. */
    suspend fun streamList(action: String, extra: String = "", onItem: (JsonObject) -> Unit) {
        Http.execute(request = Http.request(apiUrl(action, extra), userAgent)) { resp ->
            streamJsonObjects(resp.body!!.byteStream(), onItem)
        }
    }

    suspend fun list(action: String, extra: String = ""): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        streamList(action, extra) { out += it }
        return out
    }

    suspend fun json(action: String, extra: String): JsonElement {
        val body = Http.getString(apiUrl(action, extra), userAgent)
        return LenientJson.parseToJsonElement(body)
    }

    suspend fun seriesInfo(seriesId: String): JsonObject? = json("get_series_info", "&series_id=$seriesId") as? JsonObject

    suspend fun vodInfo(vodId: String): JsonObject? = json("get_vod_info", "&vod_id=$vodId") as? JsonObject

    fun liveUrl(streamId: String, ext: String): String =
        "$base/live/${enc(username)}/${enc(password)}/$streamId.${ext.ifBlank { "ts" }}"

    fun movieUrl(streamId: String, ext: String): String =
        "$base/movie/${enc(username)}/${enc(password)}/$streamId.${ext.ifBlank { "mp4" }}"

    fun episodeUrl(episodeId: String, ext: String): String =
        "$base/series/${enc(username)}/${enc(password)}/$episodeId.${ext.ifBlank { "mp4" }}"

    fun timeshiftUrl(streamId: String, startMs: Long, durationMin: Int, serverTz: String): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        fmt.timeZone = if (serverTz.isNotBlank()) TimeZone.getTimeZone(serverTz) else TimeZone.getDefault()
        return "$base/timeshift/${enc(username)}/${enc(password)}/$durationMin/${fmt.format(startMs)}/$streamId.ts"
    }

    companion object {
        /** Motif affiché quand le serveur refuse le compte (auth=0), d'après le statut qu'il renvoie. */
        fun refusalMessage(status: String): String = when (status.trim().lowercase()) {
            "" -> "Identifiant ou mot de passe refusé par le serveur."
            "expired" -> "Abonnement expiré : renouvelle-le auprès de ton fournisseur."
            "banned" -> "Compte suspendu par le fournisseur."
            "disabled" -> "Compte désactivé par le fournisseur."
            else -> "Compte refusé par le serveur (${status.trim()})."
        }

        /** Statut du compte en français (« expired » → « expiré »), pour la ligne d'état de la source. */
        fun statusLabel(status: String): String = when (status.trim().lowercase()) {
            "expired" -> "expiré"
            "banned" -> "suspendu"
            "disabled" -> "désactivé"
            else -> status.trim().lowercase()
        }

        fun normalizeServer(raw: String): String {
            var s = raw.trim()
            if (!s.contains("://")) s = "http://$s"
            s = s.substringBefore("/player_api.php").substringBefore("/get.php").substringBefore("/xmltv.php")
            s = s.substringBefore('?').trimEnd('/')
            if (s.endsWith("/c")) s = s.removeSuffix("/c")
            return s
        }

        /** Reconnaît une URL "get.php?username=…&password=…" et en extrait les identifiants Xtream. */
        fun fromM3uUrl(url: String): XtreamCredentials? {
            val u = url.trim()
            if (!u.contains("get.php", ignoreCase = true) && !u.contains("player_api.php", ignoreCase = true)) return null
            val query = u.substringAfter('?', "")
            val params = query.split('&').associate {
                it.substringBefore('=').lowercase() to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val user = params["username"].orEmpty()
            val pass = params["password"].orEmpty()
            if (user.isBlank() || pass.isBlank()) return null
            return XtreamCredentials(normalizeServer(u), user, pass)
        }
    }
}

object XtreamVodParser {
    val yearRegex = Regex("""\((\d{4})\)""")

    /** Largeur et hauteur du fichier annoncées dans `info.video`, null si le fournisseur ne les donne pas. */
    fun parseVideoSize(info: JsonObject?): Pair<Int, Int>? {
        val video = info?.get("video") as? JsonObject ?: return null
        val width = video.int("width")
        val height = video.int("height")
        return if (width > 0 && height > 0) width to height else null
    }

    /** Identifiant TMDB d'une fiche : `tmdb_id` ou `tmdb`, nombre ou texte, souvent vide ou à 0. */
    fun parseTmdbId(info: JsonObject): Int? =
        listOf("tmdb_id", "tmdb").firstNotNullOfOrNull { k -> info.str(k).toIntOrNull()?.takeIf { it > 0 } }

    /** Convertit le champ `added` (String ou numérique) en Long timestamp (ms). 0L si absent ou null. */
    fun parseAdded(addedRaw: String?): Long {
        val s = addedRaw?.trim().orEmpty()
        val sec = s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong() ?: return 0L
        return if (sec in 1..9_999_999_999L) sec * 1000L else sec
    }

    /** Convertit le champ `rating` ou `rating_5based` en Float sur 10. 0f si null ou absent. */
    fun parseRating(rating: Float?, rating5Based: Float?): Float {
        if (rating != null && rating > 0f) return rating
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
        o.str("release_date").ifBlank { o.str("releasedate") }.ifBlank { o.str("releaseDate") }
}
