package fr.yannickuhrig.vyzo.cast

import fr.yannickuhrig.vyzo.data.net.redactCredentials
import fr.yannickuhrig.vyzo.data.repo.StreamSpec
import fr.yannickuhrig.vyzo.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Appareil Google Cast trouvé sur le réseau (Chromecast, TV ou box Android TV avec Chromecast intégré). */
data class CastDevice(val id: String, val name: String, val description: String = "")

enum class CastConnection { DISCONNECTED, CONNECTING, CONNECTED }

enum class CastPlayState { IDLE, LOADING, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

/** État de la lecture sur l'appareil Cast. */
data class CastPlayback(
    val state: CastPlayState = CastPlayState.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isLive: Boolean = false,
    /** Étiquette de ce qui est lu (même forme que pour le lecteur : « ch:<uid> », « movie:<uid> »…). */
    val tag: String? = null,
)

/** Ce qu'on envoie au récepteur : l'adresse du relais, jamais celle du fournisseur. */
data class CastMedia(
    val url: String,
    val contentType: String,
    val isLive: Boolean,
    val title: String,
    val subtitle: String = "",
    val imageUrl: String? = null,
    val startMs: Long = 0,
    val tag: String,
)

/**
 * Google Cast, fourni par l'appli mobile (SDK Cast de Google). Absent sur TV et sur PC.
 * Toutes les méthodes s'appellent depuis le fil principal.
 */
interface CastSupport {
    /** Faux quand les services Google manquent sur le téléphone : le Cast n'est pas proposé. */
    val available: Boolean
    val devices: StateFlow<List<CastDevice>>
    val connection: StateFlow<CastConnection>
    /** Nom de l'appareil connecté (ou en cours de connexion). */
    val deviceName: StateFlow<String?>
    val playback: StateFlow<CastPlayback>
    val volume: StateFlow<Float>

    /** Recherche active des appareils (fenêtre de choix ouverte). */
    fun startDiscovery()
    fun stopDiscovery()

    /** Se connecte à [device] ; rend faux en cas d'échec ou d'attente trop longue. */
    suspend fun connect(device: CastDevice): Boolean

    /** Lance [media] ; rend null si le récepteur l'a accepté, sinon un message d'erreur en français. */
    suspend fun load(media: CastMedia): String?

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)

    /** Arrête la lecture et quitte l'appareil. */
    fun disconnect()
}

/**
 * Relie le Cast de la plateforme au relais du téléphone : choix du format, publication du flux, chargement.
 * Vit aussi longtemps que l'appli (le Cast continue quand on quitte l'écran du lecteur).
 */
class CastController(
    val support: CastSupport,
    scope: CoroutineScope,
    private val relay: CastRelay = CastRelay(),
) {
    init {
        // Session terminée ailleurs (TV éteinte, autre téléphone, bouton de la notification) : plus rien à relayer.
        scope.launch { support.connection.collect { if (it == CastConnection.DISCONNECTED) relay.stop() } }
    }

    val playback get() = support.playback
    val connection get() = support.connection
    val deviceName get() = support.deviceName
    val lastUpstreamError get() = relay.lastUpstreamError

    /** Envoie [spec] sur l'appareil connecté. Rend null en cas de réussite, sinon le message à afficher. */
    suspend fun cast(
        spec: StreamSpec,
        title: String,
        subtitle: String,
        imageUrl: String?,
        startMs: Long,
        tag: String,
    ): String? {
        var source = spec
        var format = CastPlanner.formatFromUrl(spec)
        // Adresse sans extension (liens Stalker, flux « /play/123 »…) : les premiers octets disent ce que c'est.
        // Film ou épisode : on regarde aussi, pour reconnaître un lien mort (page d'erreur du fournisseur) avant le Chromecast,
        // qui ne dirait qu'« échec de chargement ».
        if (format == CastFormat.UNKNOWN || !spec.isLive) {
            val probed = CastPlanner.probe(spec)
            if (probed != CastFormat.UNKNOWN) format = probed
        }
        // Chaîne Xtream en MPEG-TS, que le Chromecast ne lit pas : le même serveur la sert aussi en HLS.
        if (format == CastFormat.TS && spec.isLive) {
            CastPlanner.xtreamHlsVariant(spec.url)?.let { hls ->
                val alt = spec.copy(url = hls, mimeHint = null)
                if (CastPlanner.probe(alt) == CastFormat.HLS) {
                    source = alt
                    format = CastFormat.HLS
                }
            }
        }
        DiagLog.log("Cast", "${source.url.redactCredentials()} → $format")
        val decision = CastPlanner.decide(source, format)
        if (decision is CastDecision.Refuse) return decision.message
        decision as CastDecision.Cast
        val url = relay.publish(source) ?: return "Le téléphone doit être connecté au même Wi-Fi que l'appareil Cast."
        return support.load(
            CastMedia(
                url = url, contentType = decision.contentType, isLive = spec.isLive, title = title, subtitle = subtitle,
                imageUrl = imageUrl?.takeIf { it.startsWith("http") }, startMs = if (spec.isLive) 0 else startMs, tag = tag,
            ),
        )
    }

    /** Fin du Cast : l'appareil est quitté et le relais fermé. */
    fun disconnect() {
        support.disconnect()
        relay.stop()
    }
}
