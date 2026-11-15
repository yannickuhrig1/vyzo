package fr.yannickuhrig.vyzo.cast

import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.repo.Repository.Companion.MIME_HLS
import fr.yannickuhrig.vyzo.data.repo.StreamSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** Conteneur d'un flux, tel que le récepteur Cast doit l'entendre. */
enum class CastFormat(val contentType: String?) {
    HLS("application/x-mpegURL"),
    DASH("application/dash+xml"),
    MP4("video/mp4"),
    WEBM("video/webm"),
    MKV("video/x-matroska"),
    /** MPEG-TS continu : le récepteur standard ne sait pas le lire. */
    TS(null),
    /** AVI, FLV, WMV… : non pris en charge par le Chromecast. */
    UNSUPPORTED(null),
    UNKNOWN(null),
}

sealed interface CastDecision {
    data class Cast(val contentType: String) : CastDecision
    data class Refuse(val message: String) : CastDecision
}

/** Décide si un flux peut partir sur le Chromecast, et sous quel type. Sans état : testé à part. */
object CastPlanner {
    private val unsupported = setOf("avi", "flv", "wmv", "divx", "xvid", "3gp", "rmvb", "rm", "vob", "mpg", "mpeg")

    /** Format deviné d'après l'adresse (et l'indice du lecteur), sans rien télécharger. */
    fun formatFromUrl(spec: StreamSpec): CastFormat {
        if (spec.mimeHint == MIME_HLS) return CastFormat.HLS
        val ext = spec.url.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty().substringAfterLast('.', "").lowercase()
        return when (ext) {
            "m3u8", "m3u" -> CastFormat.HLS
            "mpd" -> CastFormat.DASH
            "mp4", "m4v", "mov" -> CastFormat.MP4
            "webm" -> CastFormat.WEBM
            "mkv" -> CastFormat.MKV
            "ts", "mts", "m2ts" -> CastFormat.TS
            in unsupported -> CastFormat.UNSUPPORTED
            else -> CastFormat.UNKNOWN
        }
    }

    /** Format reconnu aux premiers octets du flux (adresses sans extension, liens Stalker…). */
    fun formatFromBytes(head: ByteArray, contentType: String = ""): CastFormat {
        val t = contentType.lowercase()
        val text = String(head, 0, minOf(head.size, 16), Charsets.ISO_8859_1).removePrefix("ï»¿")
        return when {
            "mpegurl" in t || text.startsWith("#EXTM3U") -> CastFormat.HLS
            "dash+xml" in t || (text.startsWith("<?xml") && "dash" in t) -> CastFormat.DASH
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> CastFormat.MP4
            head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() ->
                if ("webm" in t) CastFormat.WEBM else CastFormat.MKV
            head.isNotEmpty() && head[0] == 0x47.toByte() -> CastFormat.TS
            "video/mp4" in t -> CastFormat.MP4
            "video/mp2t" in t -> CastFormat.TS
            "video/webm" in t -> CastFormat.WEBM
            else -> CastFormat.UNKNOWN
        }
    }

    private val xtreamLiveTs = Regex("""^(https?://[^?#]+/live/[^/?#]+/[^/?#]+/\d+)\.ts([?#].*)?$""")
    private val xtreamShortLive = Regex("""^(https?://[^/?#]+)/([^/?#]+)/([^/?#]+)/(\d+)(?:\.ts)?$""")

    /**
     * Version HLS d'une chaîne Xtream lue en MPEG-TS : les serveurs Xtream servent la même chaîne en « .m3u8 ».
     * Reconnaît aussi la forme courte des listes M3U Xtream (serveur/identifiant/mot de passe/numéro).
     */
    fun xtreamHlsVariant(url: String): String? {
        xtreamLiveTs.matchEntire(url)?.let { m -> return "${m.groupValues[1]}.m3u8${m.groupValues[2]}" }
        xtreamShortLive.matchEntire(url)?.let { m ->
            val (host, user, pass, id) = m.destructured
            if (user == "live" || user == "movie" || user == "series") return null
            return "$host/live/$user/$pass/$id.m3u8"
        }
        return null
    }

    fun decide(spec: StreamSpec, format: CastFormat): CastDecision {
        if (spec.drmType.isNotBlank()) return CastDecision.Refuse("Flux protégé (DRM) : il ne peut pas être casté.")
        if (spec.url.startsWith("file:") || spec.url.startsWith("content:") || spec.url.startsWith("/")) {
            return CastDecision.Refuse("Les films téléchargés et les enregistrements ne peuvent pas encore être castés.")
        }
        return when (format) {
            CastFormat.TS -> CastDecision.Refuse(
                if (spec.isLive) "Cette chaîne n'est diffusée qu'en MPEG-TS, un format que le Chromecast ne lit pas encore depuis Vyzo."
                else "Ce flux est en MPEG-TS, un format que le Chromecast ne lit pas encore depuis Vyzo.",
            )
            CastFormat.UNSUPPORTED -> CastDecision.Refuse("Ce format de vidéo n'est pas lu par le Chromecast.")
            CastFormat.UNKNOWN -> CastDecision.Refuse("Format du flux inconnu : impossible de l'envoyer au Chromecast.")
            else -> CastDecision.Cast(format.contentType!!)
        }
    }

    /**
     * Lit les premiers octets du flux pour en connaître le format. Ouvre une connexion chez le fournisseur : le lecteur du
     * téléphone doit déjà être arrêté pour un abonnement à une seule connexion.
     */
    suspend fun probe(spec: StreamSpec, client: OkHttpClient = Http.client): CastFormat = withContext(Dispatchers.IO) {
        val request = Http.request(spec.url, spec.userAgent, spec.headers).newBuilder()
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=0-1023")
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use CastFormat.UNKNOWN
                val input = resp.body?.byteStream() ?: return@use CastFormat.UNKNOWN
                val head = ByteArray(16)
                var n = 0
                while (n < head.size) {
                    val r = input.read(head, n, head.size - n)
                    if (r < 0) break
                    n += r
                }
                formatFromBytes(head.copyOf(n), resp.header("Content-Type").orEmpty())
            }
        }.getOrDefault(CastFormat.UNKNOWN)
    }
}
