package fr.yannickuhrig.vyzo.data.link

import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.ui.common.PlayKind
import fr.yannickuhrig.vyzo.ui.common.Routes
import fr.yannickuhrig.vyzo.ui.common.encodeArg
import java.net.URLDecoder

enum class DeepLinkKind(val host: String) { PLAY("play"), MOVIE("movie"), SERIES("series") }

/** Lien `vyzo://play/<uid>`, `vyzo://movie/<uid>` ou `vyzo://series/<uid>`. L'identifiant vient de l'extérieur : rien n'est lu sans vérification. */
data class DeepLink(val kind: DeepLinkKind, val uid: String) {
    val uri: String get() = "vyzo://${kind.host}/${encodeArg(uid)}"

    companion object {
        private const val MAX_LENGTH = 512
        private val PATTERN = Regex("""vyzo://(play|movie|series)/([^/?#]+)/?""", RegexOption.IGNORE_CASE)

        fun parse(uri: String?): DeepLink? {
            if (uri == null || uri.length > MAX_LENGTH) return null
            val (host, raw) = PATTERN.matchEntire(uri)?.destructured ?: return null
            val uid = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrNull()
            if (uid.isNullOrBlank()) return null
            return DeepLink(DeepLinkKind.entries.first { it.host.equals(host, ignoreCase = true) }, uid)
        }
    }
}

/** Écran visé par un lien ; [locked] impose le passage par le code parental. */
data class DeepLinkTarget(val route: String, val locked: Boolean)

/** Résout un lien dans le catalogue du profil actif, ou null si le titre n'y figure pas. */
suspend fun AppContainer.deepLinkTarget(link: DeepLink): DeepLinkTarget? {
    val pids = repo.viewScopeNow().pids
    suspend fun locked(categoryUid: String) = db.categories().isLocked(categoryUid)
    return when (link.kind) {
        DeepLinkKind.MOVIE -> db.movies().get(link.uid)?.takeIf { it.playlistId in pids }
            ?.let { DeepLinkTarget(Routes.movie(it.uid), locked(it.categoryUid)) }
        DeepLinkKind.SERIES -> db.series().get(link.uid)?.takeIf { it.playlistId in pids }
            ?.let { DeepLinkTarget(Routes.series(it.uid), locked(it.categoryUid)) }
        DeepLinkKind.PLAY -> {
            val channel = db.channels().get(link.uid)
            val movie = if (channel == null) db.movies().get(link.uid) else null
            val episode = if (channel == null && movie == null) db.series().episode(link.uid) else null
            when {
                channel != null -> channel.takeIf { it.playlistId in pids }
                    ?.let { DeepLinkTarget(Routes.channel(it.uid, Repository.KEY_ALL), it.locked || locked(it.categoryUid)) }
                movie != null -> movie.takeIf { it.playlistId in pids }
                    ?.let { DeepLinkTarget(Routes.player(PlayKind.MOVIE, it.uid), locked(it.categoryUid)) }
                episode != null -> db.series().get(episode.seriesUid)?.takeIf { it.playlistId in pids }
                    ?.let { DeepLinkTarget(Routes.player(PlayKind.EPISODE, episode.uid), locked(it.categoryUid)) }
                else -> null
            }
        }
    }
}
