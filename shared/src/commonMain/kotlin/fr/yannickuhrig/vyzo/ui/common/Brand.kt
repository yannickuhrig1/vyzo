package fr.yannickuhrig.vyzo.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Couleurs du logo Vyzo « Le Rebond ». */
object VyzoBrand {
    val Cyan = Color(0xFF00E5FF)
    val Blue = Color(0xFF3A86FF)
    val Violet = Color(0xFF8338EC)
    val Plum = Color(0xFF9B287B)
    val Magenta = Color(0xFFFF006E)
    val Orange = Color(0xFFFB5607)
    val Yellow = Color(0xFFFFBE0B)

    /** Branche montante du V (magenta → jaune), utilisée pour les barres de progression. */
    val Rise = Brush.horizontalGradient(listOf(Magenta, Orange, Yellow))
}

// Géométrie du logo dans son repère 1024 × 1024 (même tracé que l'icône de l'appli).
private const val MIN_X = 155f
private const val MIN_Y = 155f
private const val EXTENT = 714f
private const val STROKE = 210f

/** Le « V » de Vyzo : deux rubans arrondis en dégradé, avec le triangle lecture au point de rebond. */
@Composable
fun VyzoMark(size: Dp, modifier: Modifier = Modifier, showPlay: Boolean = size >= 40.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / EXTENT
        withTransform({
            translate(left = -MIN_X * s, top = -MIN_Y * s)
            scale(s, s, pivot = Offset.Zero)
        }) {
            // Branche droite (dessous), qui « rebondit » vers le haut.
            drawLine(
                brush = Brush.linearGradient(
                    0f to VyzoBrand.Plum, 0.3f to VyzoBrand.Magenta, 0.7f to VyzoBrand.Orange, 1f to VyzoBrand.Yellow,
                    start = Offset(450f, 780f), end = Offset(804f, 220f),
                ),
                start = Offset(512f, 760f), end = Offset(764f, 260f), strokeWidth = STROKE, cap = StrokeCap.Round,
            )
            // Branche gauche (dessus), pour l'effet de ruban plié.
            drawLine(
                brush = Brush.linearGradient(
                    0f to VyzoBrand.Cyan, 0.45f to VyzoBrand.Blue, 1f to VyzoBrand.Violet,
                    start = Offset(220f, 300f), end = Offset(512f, 820f),
                ),
                start = Offset(260f, 360f), end = Offset(512f, 760f), strokeWidth = STROKE, cap = StrokeCap.Round,
            )
            if (showPlay) {
                val play = Path().apply { moveTo(488f, 695f); lineTo(488f, 755f); lineTo(540f, 725f); close() }
                drawPath(play, Color.White.copy(alpha = 0.9f))
                drawPath(play, Color.White.copy(alpha = 0.9f), style = Stroke(width = 6f))
            }
        }
    }
}

/** Logo + nom, pour les en-têtes. */
@Composable
fun VyzoWordmark(markSize: Dp, modifier: Modifier = Modifier, fontSize: Int = 22) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((markSize.value * 0.32f).dp)) {
        VyzoMark(markSize)
        Text(
            "Vyzo",
            style = TextStyle(fontSize = fontSize.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
