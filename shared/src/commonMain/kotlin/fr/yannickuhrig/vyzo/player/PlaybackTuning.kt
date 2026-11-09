package fr.yannickuhrig.vyzo.player

import kotlin.math.abs
import kotlin.math.roundToInt

/** Valeurs proposées pour le décalage audio, en millisecondes. */
val AUDIO_DELAY_CHOICES: List<Long> = (-1000L..1000L step 50L).toList()

fun audioDelayLabel(ms: Long): String = when {
    ms > 0 -> "+$ms ms (son retardé)"
    ms < 0 -> "−${-ms} ms (son avancé)"
    else -> "Aucun"
}

private const val RATE_TOLERANCE = 0.0005f
private const val SMOOTH_RATE_HZ = 47f

/**
 * Fréquence d'écran à choisir pour une vidéo de [fps] images par seconde, ou null s'il vaut mieux ne rien changer.
 * Seuls les multiples exacts conviennent (59,94 Hz n'est pas 60 Hz). À choix égal on garde la plus basse à partir de
 * 47 Hz : une vidéo à 25 i/s passe en 50 Hz, où l'interface reste fluide, et un film à 24 i/s reste en 24 Hz s'il n'y a pas mieux.
 */
fun bestRefreshRate(supported: List<Float>, fps: Float): Float? {
    if (fps <= 0f) return null
    val exact = supported.filter { rate ->
        val multiple = (rate / fps).roundToInt()
        multiple >= 1 && abs(rate - multiple * fps) / rate <= RATE_TOLERANCE
    }
    return exact.filter { it >= SMOOTH_RATE_HZ }.minOrNull() ?: exact.maxOrNull()
}

/**
 * Zapping en rafale : la première chaîne s'ouvre tout de suite, les suivantes attendent que la télécommande se calme.
 * Traverser dix chaînes n'ouvre ainsi pas dix connexions à l'abonnement.
 */
class ZapPacer(private val quietMs: Long = 350L) {
    private var lastRequest: Long? = null

    /** Temps à attendre avant d'ouvrir le flux demandé à l'instant [now]. */
    fun waitBeforeOpening(now: Long): Long {
        val previous = lastRequest
        lastRequest = now
        return if (previous != null && now - previous < quietMs) quietMs else 0L
    }
}
