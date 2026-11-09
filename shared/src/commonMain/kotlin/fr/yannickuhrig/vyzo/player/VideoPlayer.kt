package fr.yannickuhrig.vyzo.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import fr.yannickuhrig.vyzo.data.repo.StreamSpec
import kotlinx.coroutines.flow.StateFlow

enum class TrackType { AUDIO, VIDEO, TEXT }

/** Piste proposée à l'utilisateur. [ref] est propre au lecteur (groupe Media3, identifiant VLC…). */
data class TrackOption(
    val type: TrackType,
    val ref: Any?,
    val index: Int,
    val label: String,
    val selected: Boolean,
)

enum class PlaybackState { IDLE, BUFFERING, READY, ENDED }

data class PlayerStatus(val isPlaying: Boolean = false, val state: PlaybackState = PlaybackState.IDLE)

/** Informations techniques sur le flux vidéo et audio (Stats for Nerds). */
data class StreamInfo(
    val videoCodec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val audioCodec: String? = null,
    val audioChannels: Int = 0,
) {
    val resolutionLabel: String
        get() = when {
            width >= 3840 || height >= 2160 -> "4K UHD"
            width >= 1920 || height >= 1080 -> "1080p FHD"
            width >= 1280 || height >= 720 -> "720p HD"
            else -> "SD"
        }
}

/** Mesures de la lecture en cours (panneau d'informations) ; une valeur à 0 ou null n'est pas connue. */
data class PlaybackStats(
    val bitrateBps: Long = 0,
    val bufferedMs: Long = 0,
    val droppedFrames: Long = 0,
    val videoDecoder: String? = null,
    val audioOutput: String? = null,
    val tunneled: Boolean = false,
    val displayHz: Float = 0f,
)

/** Ce que le lecteur de cette plateforme sait faire : l'interface ne propose que ça. */
data class PlayerCapabilities(
    val audioDelay: Boolean = false,
    val loudnessLeveling: Boolean = false,
    val passthrough: Boolean = false,
    val refreshRate: Boolean = false,
    val tunneling: Boolean = false,
)

/** Format d'image dans le lecteur. */
enum class ResizeMode(val label: String) {
    FIT("Ajusté"),
    ZOOM("Zoom (recadré)"),
    FILL("Étiré"),
}

/**
 * Lecteur unique partagé entre l'aperçu et le plein écran.
 * Android : Media3 ExoPlayer (PlayerManager) ; PC : libVLC (VlcPlayer).
 */
interface VideoPlayer {
    /** Flux en cours (null à l'arrêt). */
    val current: StateFlow<StreamSpec?>

    /** Étiquette de ce qui est lu (ex. "ch:<uid>"), pour savoir quelle chaîne est à l'antenne. */
    val tag: StateFlow<String?>

    /** Message d'erreur en français, null si tout va bien. */
    val error: StateFlow<String?>

    /** Code HTTP de la dernière erreur de lecture (403, 509… : souvent la limite de connexions de l'abonnement). */
    val httpCode: StateFlow<Int?>

    val status: StateFlow<PlayerStatus>

    /** Taille de l'image (largeur, hauteur en pixels carrés), null si inconnue. */
    val videoSize: StateFlow<Pair<Int, Int>?>

    /** Informations techniques sur le flux vidéo et audio en cours (résolution, codecs, fps). */
    val streamInfo: StateFlow<StreamInfo?>

    /** Le lecteur a déjà été créé. */
    val hasPlayer: Boolean

    val isPlaying: Boolean get() = status.value.isPlaying

    val currentPosition: Long

    /** Durée en ms, 0 si inconnue (direct). */
    val duration: Long

    fun play(spec: StreamSpec, startPositionMs: Long = 0, tag: String? = null)

    fun stop()

    /** Lit un fichier qui se remplit encore (pause du direct). */
    fun playGrowingFile(file: java.io.File, isGrowing: () -> Boolean, tag: String?)

    /** Coupe la connexion au flux en gardant la dernière image à l'écran (pause du direct). */
    fun releaseConnection()

    fun retry()

    /** À appeler après un changement de décodeur ou de tampon. */
    fun recreate()

    fun pause()

    fun resume()

    fun seekTo(positionMs: Long)

    fun tracks(type: TrackType): List<TrackOption>

    fun textEnabled(): Boolean

    fun select(option: TrackOption)

    fun disable(type: TrackType)

    /** Revient au choix automatique (qualité vidéo). */
    fun clearOverrides(type: TrackType)

    /** Coupe ou rétablit le son. Renvoie true si le son est maintenant coupé. */
    fun toggleMute(): Boolean

    /** Active ou désactive le son. */
    fun setMuted(muted: Boolean) {}

    /** Libère définitivement les ressources du lecteur. */
    fun release() {}

    /** Change le volume de [deltaPercent] points. Renvoie le nouveau volume (0-100). */
    fun adjustVolume(deltaPercent: Int): Int

    /** Bascule en mode Picture-in-Picture (PiP). */
    fun enterPictureInPicture(): Boolean = false

    /** Active/désactive le mode "Toujours au premier plan" sur Desktop. */
    fun toggleAlwaysOnTop(): Boolean = false

    /** Indique si le mode "Toujours au premier plan" est actuellement actif. */
    val isAlwaysOnTop: StateFlow<Boolean> get() = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Décalage appliqué aux sous-titres en millisecondes. */
    val subtitleDelayMs: StateFlow<Long> get() = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** Ajuste le décalage des sous-titres de [deltaMs] millisecondes (ex: +500L ou -500L). */
    fun adjustSubtitleDelay(deltaMs: Long) {}

    /** Réinitialise le décalage des sous-titres à 0. */
    fun resetSubtitleDelay() {}

    val capabilities: PlayerCapabilities get() = PlayerCapabilities()

    /** Décalage du son par rapport à l'image, en millisecondes (positif : le son arrive plus tard). */
    val audioDelayMs: StateFlow<Long> get() = kotlinx.coroutines.flow.MutableStateFlow(0L)

    fun setAudioDelay(ms: Long) {}

    /** Mesures instantanées, null si le lecteur n'en fournit pas. */
    fun playbackStats(): PlaybackStats? = null

    /** Surface vidéo sans contrôles (les surcouches Compose s'affichent par-dessus). */
    @Composable
    fun Surface(modifier: Modifier, resizeMode: ResizeMode)
}
