package fr.yannickuhrig.vyzo.data.remote

import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.source.stalker.StalkerClient
import fr.yannickuhrig.vyzo.data.source.xtream.XtreamClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.net.URLDecoder

/** Playlist transmise d'un appareil à l'autre (formulaire web, appli téléphone, QR code). */
@Serializable
data class RemotePlaylist(
    val type: String,
    val name: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val mac: String = "",
    val userAgent: String = "",
    val epgUrl: String = "",
) {
    val sourceType: SourceType?
        get() = when (type.trim().lowercase()) {
            "xtream", "xc" -> SourceType.XTREAM
            "m3u", "m3u8" -> SourceType.M3U
            "stalker", "mac", "portal" -> SourceType.STALKER
            else -> null
        }

    /** Message d'erreur en français, ou null si la playlist est exploitable. */
    fun validate(): String? {
        val t = sourceType ?: return "Type de source inconnu"
        if (url.isBlank()) return "Adresse manquante"
        if (!url.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) || !it.contains("://") }) {
            return "L'adresse doit commencer par http:// ou https://"
        }
        return when (t) {
            SourceType.XTREAM -> if (username.isBlank() || password.isBlank()) "Identifiant et mot de passe requis" else null
            SourceType.STALKER -> if (!StalkerClient.isValidMac(mac)) "Adresse MAC invalide (format 00:1A:79:XX:XX:XX)" else null
            SourceType.M3U -> null
        }
    }

    fun toEntity(): PlaylistEntity {
        val t = sourceType ?: SourceType.M3U
        val cleanUrl = url.trim()
        return PlaylistEntity(
            name = name.trim().ifBlank { defaultName(t, cleanUrl) },
            type = t,
            url = cleanUrl,
            username = username.trim(),
            password = password,
            mac = if (t == SourceType.STALKER) mac.trim().uppercase() else "",
            userAgent = userAgent.trim(),
        )
    }

    companion object {
        fun from(p: PlaylistEntity, epgUrl: String = "") = RemotePlaylist(
            type = when (p.type) { SourceType.XTREAM -> "xtream"; SourceType.M3U -> "m3u"; SourceType.STALKER -> "stalker" },
            name = p.name, url = p.url, username = p.username, password = p.password, mac = p.mac,
            userAgent = p.userAgent, epgUrl = epgUrl,
        )

        private fun defaultName(t: SourceType, url: String): String {
            val host = runCatching { URI(if (url.contains("://")) url else "http://$url").host }.getOrNull()
            return host ?: when (t) {
                SourceType.XTREAM -> "Xtream"
                SourceType.M3U -> "Playlist M3U"
                SourceType.STALKER -> "Portail"
            }
        }
    }
}

/** Ce qu'un QR code scanné peut contenir. */
sealed interface ScannedCode {
    /** QR code affiché par Vyzo sur une TV : adresse du serveur local + jeton. */
    data class VyzoDevice(val baseUrl: String, val token: String) : ScannedCode
    /** QR code « Se connecter depuis le téléphone » : page web où saisir le compte, à ouvrir dans le navigateur. */
    data class VyzoAccount(val url: String) : ScannedCode
    data class Playlist(val playlist: RemotePlaylist) : ScannedCode
    data class Unknown(val text: String) : ScannedCode
}

object QrContent {
    const val MARKER = "vyzo"
    /** Marqueurs des versions précédentes (l'appli s'appelait Zapette), toujours reconnus. */
    private val LEGACY_MARKERS = listOf("zapette")
    private val SCHEMES = listOf("vyzo://", "zapette://")
    private val macRegex = Regex("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")
    private val urlRegex = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Adresse encodée dans le QR code de la TV. */
    fun deviceUrl(ip: String, port: Int, token: String) = "http://$ip:$port/?k=$token&$MARKER=1"

    fun parse(raw: String): ScannedCode {
        val text = raw.trim()
        // 1. QR code de réception Vyzo (ou Zapette)
        if (text.startsWith("http", true) && (listOf(MARKER) + LEGACY_MARKERS).any { text.contains("$it=1") }) {
            val uri = runCatching { URI(text) }.getOrNull()
            val token = uri?.rawQuery?.split('&')?.firstOrNull { it.startsWith("k=") }?.substringAfter("k=")
            if (uri != null && !token.isNullOrBlank() && uri.host != null) {
                if (uri.path == "/compte") return ScannedCode.VyzoAccount(text)
                val port = if (uri.port > 0) ":${uri.port}" else ""
                return ScannedCode.VyzoDevice("${uri.scheme}://${uri.host}$port", token)
            }
        }
        // 2. JSON
        if (text.startsWith("{")) {
            runCatching { json.decodeFromString(RemotePlaylist.serializer(), text) }.getOrNull()
                ?.takeIf { it.sourceType != null }?.let { return ScannedCode.Playlist(it) }
        }
        // 3. Lien vyzo://playlist?type=…&url=… (ou zapette://)
        if (SCHEMES.any { text.startsWith(it, true) }) {
            val q = text.substringAfter('?', "").split('&').associate {
                it.substringBefore('=').lowercase() to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val p = RemotePlaylist(
                type = q["type"].orEmpty(), name = q["name"].orEmpty(), url = q["url"].orEmpty(),
                username = q["username"] ?: q["user"].orEmpty(), password = q["password"] ?: q["pass"].orEmpty(),
                mac = q["mac"].orEmpty(), userAgent = q["ua"].orEmpty(), epgUrl = q["epg"].orEmpty(),
            )
            if (p.sourceType != null) return ScannedCode.Playlist(p)
        }
        // 4. Lien Xtream "get.php?username=…&password=…"
        val firstUrl = urlRegex.find(text)?.value
        if (firstUrl != null) {
            XtreamClient.fromM3uUrl(firstUrl)?.let { c ->
                return ScannedCode.Playlist(RemotePlaylist("xtream", url = c.server, username = c.username, password = c.password))
            }
        }
        // 5. Portail + adresse MAC
        val mac = macRegex.find(text)?.value?.replace('-', ':')?.uppercase()
        if (firstUrl != null && mac != null) {
            return ScannedCode.Playlist(RemotePlaylist("stalker", url = firstUrl, mac = mac))
        }
        // 6. Simple lien de playlist
        if (firstUrl != null && text == firstUrl) return ScannedCode.Playlist(RemotePlaylist("m3u", url = firstUrl))
        return ScannedCode.Unknown(text)
    }
}

object LanAddress {
    /** Adresse IPv4 locale (Wi-Fi ou Ethernet) de l'appareil, ou null hors réseau. */
    fun ipv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { i ->
                val n = i.name.lowercase()
                when {
                    n.startsWith("wlan") -> 0
                    n.startsWith("eth") -> 1
                    n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("tun") -> 9
                    else -> 5
                }
            }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}

/**
 * Lecture demandée à un autre appareil du compte. Ne contient jamais l'URL du flux, qui porte les identifiants IPTV :
 * l'appareil qui reçoit retrouve le contenu par [itemId] et construit l'URL avec sa propre playlist.
 */
@Serializable
data class CastCommand(
    val targetDeviceId: String? = null,
    val itemType: String,
    val itemId: String,
    val title: String,
    val posterUrl: String? = null,
    val positionMs: Long = 0L,
    val senderDeviceId: String? = null,
    /**
     * Envoi du site du compte : clé de contenu commune à tous les appareils ([fr.yannickuhrig.vyzo.data.db.PlaylistKeys]),
     * que l'appareil visé traduit en uid local. [itemType] vaut alors « content-movie », « content-episode »,
     * « content-series » ou « content-channel » : les versions qui ne connaissent pas ce champ l'ignorent.
     */
    val contentKey: String? = null,
)

@Serializable
data class ScheduleSyncEvent(
    val action: String = "sync",
    val channelUid: String = "",
    val channelName: String = "",
    val title: String = "",
    val start: Long = 0L,
    val stop: Long = 0L,
    val status: String = "SCHEDULED",
    val error: String? = null,
    val senderDeviceId: String? = null,
    /** Date de la modification (dernier écrit gagnant dans la table « recordings »). */
    val updatedAt: Long = 0L,
)

@Serializable
data class HistoryClearEvent(
    val profileId: String? = null,
    val clearAll: Boolean = true,
    val adultOnly: Boolean = false,
    val categoryUid: String? = null,
    val keyword: String? = null,
    val itemUid: String? = null,
    val contentKeys: List<String> = emptyList(),
    val senderDeviceId: String? = null,
)
