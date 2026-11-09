package fr.yannickuhrig.vyzo.data.db

import java.net.URI
import java.security.MessageDigest

/**
 * Clés stables qui identifient une playlist et son contenu d'un appareil à l'autre.
 *
 * Les uid locaux commencent par l'identifiant Room de la playlist (« 12:M:501 »), qui change d'un appareil
 * à l'autre. Pour la synchro de la progression, on remplace ce préfixe par une clé calculée à partir de la
 * source : « 9f2c…:M:501 ». Pour Xtream et Stalker, la clé ne dépend pas de l'adresse du serveur, que les
 * fournisseurs changent souvent.
 */
object PlaylistKeys {
    fun of(p: PlaylistEntity): String = of(p.type, p.url, p.username, p.mac)

    fun of(type: SourceType, url: String, username: String, mac: String): String {
        val basis = when (type) {
            SourceType.XTREAM -> "xtream|" + username.trim().lowercase()
            SourceType.STALKER -> "stalker|" + mac.trim().uppercase()
            SourceType.M3U -> "m3u|" + normalizeUrl(url)
        }
        return sha1(basis).take(12)
    }

    /** uid local → clé de contenu, ou null si l'uid ne commence pas par l'identifiant de la playlist. */
    fun contentKey(uid: String, playlistId: Long, playlistKey: String): String? {
        if (uid.isBlank()) return null
        val prefix = "$playlistId:"
        return if (uid.startsWith(prefix)) playlistKey + ":" + uid.substring(prefix.length) else null
    }

    /** Clé de contenu → uid local, ou null si la clé n'appartient pas à cette playlist. */
    fun localUid(contentKey: String, playlistId: Long, playlistKey: String): String? {
        if (contentKey.isBlank()) return null
        val prefix = "$playlistKey:"
        return if (contentKey.startsWith(prefix)) "$playlistId:" + contentKey.substring(prefix.length) else null
    }

    /** Identifiant Room de la playlist au début d'un uid local (« 12:M:501 » → 12). */
    fun playlistIdOf(uid: String): Long? = uid.substringBefore(':', "").toLongOrNull()

    private fun normalizeUrl(url: String): String {
        val u = url.trim()
        val uri = runCatching { URI(if (u.contains("://")) u else "http://$u") }.getOrNull() ?: return u.lowercase()
        val host = uri.host?.lowercase() ?: return u.lowercase()
        val port = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
        val path = (uri.rawPath ?: "").trimEnd('/')
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        return host + port + path + query
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
