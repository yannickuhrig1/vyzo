package fr.yannickuhrig.vyzo.tv

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Dégradé décrit par ses paliers : position de 0 à 1, puis opacité de 0 à 1, positions croissantes. */
internal typealias ScrimStops = List<Pair<Float, Float>>

/** Opacité du dégradé [stops] à la position [t], avec une variation linéaire entre deux paliers. */
internal fun stopAlpha(stops: ScrimStops, t: Float): Float {
    if (t <= stops.first().first) return stops.first().second
    for (i in 1 until stops.size) {
        val (p1, a1) = stops[i]
        if (t <= p1) {
            val (p0, a0) = stops[i - 1]
            return a0 + (a1 - a0) * (t - p0) / (p1 - p0)
        }
    }
    return stops.last().second
}

/** Opacité obtenue en superposant un dégradé horizontal et un dégradé vertical de même couleur. */
internal fun scrimAlpha(horizontal: ScrimStops, vertical: ScrimStops, x: Float, y: Float): Float =
    1f - (1f - stopAlpha(horizontal, x)) * (1f - stopAlpha(vertical, y))

/**
 * Opacité en (x, y) d'une lueur circulaire centrée en ([cx], [cy]), qui s'éteint linéairement jusqu'à [radius].
 * Positions de 0 à 1 ; [radius] est exprimé en largeurs d'écran, le cercle restant rond sur un écran au rapport [aspect].
 */
internal fun glowAlpha(cx: Float, cy: Float, radius: Float, x: Float, y: Float, aspect: Float = 16f / 9f): Float {
    val dx = x - cx
    val dy = (y - cy) / aspect
    return (1f - sqrt(dx * dx + dy * dy) / radius).coerceIn(0f, 1f)
}

private const val ScrimWidth = 160
private const val ScrimHeight = 90

/** Masque blanc d'une lueur (voir [glowAlpha]), à teinter au dessin. */
internal fun bakeGlow(cx: Float, cy: Float, radius: Float): ImageBitmap {
    val pixels = IntArray(ScrimWidth * ScrimHeight) { i ->
        val x = (i % ScrimWidth + 0.5f) / ScrimWidth
        val y = (i / ScrimWidth + 0.5f) / ScrimHeight
        ((glowAlpha(cx, cy, radius, x, y) * 255f).roundToInt() shl 24) or 0xFFFFFF
    }
    return Bitmap.createBitmap(pixels, ScrimWidth, ScrimHeight, Bitmap.Config.ARGB_8888).asImageBitmap()
}

internal fun bakeScrim(color: Color, horizontal: ScrimStops, vertical: ScrimStops): ImageBitmap {
    val rgb = color.toArgb() and 0xFFFFFF
    val pixels = IntArray(ScrimWidth * ScrimHeight) { i ->
        val x = (i % ScrimWidth + 0.5f) / ScrimWidth
        val y = (i / ScrimWidth + 0.5f) / ScrimHeight
        ((scrimAlpha(horizontal, vertical, x, y) * 255f).roundToInt() shl 24) or rgb
    }
    return Bitmap.createBitmap(pixels, ScrimWidth, ScrimHeight, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Voile d'une seule couleur (celle du fond par défaut), fait de deux dégradés superposés, précalculé dans une petite image étirée à l'affichage.
 * Sur les petites puces des box TV, deux dégradés plein écran recalculés à chaque image font perdre près de 30 ms par image.
 */
@Composable
internal fun BakedScrim(horizontal: ScrimStops, vertical: ScrimStops, modifier: Modifier = Modifier, color: Color = Bg) {
    val scrim = remember(color, horizontal, vertical) { bakeScrim(color, horizontal, vertical) }
    Spacer(modifier.drawBehind { drawImage(scrim, dstSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())) })
}
