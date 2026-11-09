package fr.yannickuhrig.vyzo.ui.common

import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.repo.StreamSpec
import fr.yannickuhrig.vyzo.util.Log
import kotlinx.coroutines.launch

/**
 * Ouvre un flux dans un autre lecteur (VLC, MX Player, Kodi… ; VLC ou mpv sur PC).
 * [resolve] construit l'adresse du flux (Xtream, Stalker : demandée au serveur au moment du clic).
 */
fun openInExternalPlayer(c: AppContainer, title: String, startMs: Long = 0, resolve: suspend () -> StreamSpec) {
    c.scope.launch {
        val spec = runCatching { resolve() }.getOrElse {
            Log.w("ExternalPlayer", "Flux introuvable pour « $title »", it)
            c.platform.toast("Impossible de récupérer le flux vidéo")
            return@launch
        }
        if (spec.drmType.isNotBlank()) {
            c.platform.toast("Flux protégé (DRM) : seul le lecteur de Vyzo peut le lire", long = true)
            return@launch
        }
        val ok = c.platform.openInExternalPlayer(
            url = spec.url,
            title = title,
            userAgent = spec.userAgent,
            headers = spec.headers,
            mimeHint = spec.mimeHint,
            startMs = startMs,
        )
        if (!ok) {
            c.platform.toast(
                if (c.platform.isDesktop) "Aucun autre lecteur trouvé : installez VLC ou mpv"
                else "Aucun autre lecteur vidéo installé (VLC, MX Player, Kodi…)",
                long = true,
            )
        }
    }
}
