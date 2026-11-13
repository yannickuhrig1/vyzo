// Dérivé de DesignK.kt, Ui.kt, Billboard.kt et MainActivity.kt d'IPVIO Stream, Copyright (C) 2026 IPVIO Stream contributors, GNU GPL 3.
// Modifié pour Vyzo le 2026-11-08 : commentaires en français, seules les pièces de l'accueil sont gardées, la rangée active est calée sur une hauteur fixe.
package fr.yannickuhrig.vyzo.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Échelle de la maquette (1658 pixels de large) : les dimensions sont données en « pixels de maquette »
 * et converties selon la largeur réelle, pour garder les mêmes proportions sur chaque téléviseur.
 */
object Design {
    const val WIDTH = 1658f

    /** dp par pixel de maquette : 960 dp de large, le format habituel d'un téléviseur, donne 0,58. */
    var k by mutableFloatStateOf(960f / WIDTH)
}

/** Cale l'échelle de la maquette sur la largeur réelle de l'écran. */
@Composable
fun TrackDesignScale() {
    val screenW = LocalConfiguration.current.screenWidthDp
    SideEffect {
        val k = screenW / Design.WIDTH
        if (Design.k != k) Design.k = k
    }
}

/** Pixels de maquette vers dp. */
fun dpx(v: Number): Dp = (v.toFloat() * Design.k).dp

/** Pixels de maquette vers sp. */
fun spx(v: Number): TextUnit = typeSize(v.toFloat()).sp

/** Lisibilité à 3 m : les petits textes ne descendent pas sous 13 sp, les grands titres suivent la maquette. */
fun typeSize(v: Float): Float = maxOf(v * Design.k, minOf(v * 0.85f, 13f))

val Bg: Color get() = Look.bg
val Surf: Color get() = Look.surf
val Surf2: Color get() = Look.surf2
val Accent1: Color get() = Look.accent1
val Accent2: Color get() = Look.accent2

/** Teintes de texte et de lignes, valables sur tous les fonds sombres. */
object Tok {
    val TextHi = Color(0xFFF4F6FB)
    val TextMid = Color(0xFFD3D7E2)
    val TextLow = Color(0xFFA0A6B8)
    val Line = Color(0x26FFFFFF)
    val Lift = Color(0xE6000000)
    val FocusFill = Color.White
    val FocusInk = Color(0xFF0B0F19)
}

/** Ombre portée de l'élément sélectionné à la télécommande. */
fun Modifier.focusGlow(focused: Boolean, shape: Shape): Modifier =
    if (!focused) this else this.shadow(20.dp, shape, clip = false, ambientColor = Tok.Lift, spotColor = Tok.Lift)

/** Défilement doux d'une rangée : l'élément entre juste dans l'écran, avec un peu de marge pour voir le suivant. */
@OptIn(ExperimentalFoundationApi::class)
val SmoothSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val peek = 90f
        val trailing = offset + size
        return when {
            offset >= 0f && trailing <= containerSize -> 0f
            offset < 0f && trailing > containerSize -> 0f
            offset < 0f -> offset - peek
            else -> trailing - containerSize + peek
        }
    }
}

/**
 * Distance à faire défiler pour amener l'élément sélectionné à la hauteur [target].
 * Rien à faire au bout de la liste : la demander quand même occuperait le processeur sans fin sur les petites clés TV.
 */
fun pivotDistance(offset: Float, target: Float, canScrollBack: Boolean, canScrollForward: Boolean): Float {
    val d = offset - target
    return when {
        d < 0f && !canScrollBack -> 0f
        d > 0f && !canScrollForward -> 0f
        abs(d) < 2f -> 0f
        else -> d
    }
}

/**
 * Garde la rangée active en haut de la liste : le titre mis en avant reste lisible au-dessus.
 * L'animation reste celle de Compose : une durée fixe y serait relancée à chaque image et ferait ramper la liste.
 */
@OptIn(ExperimentalFoundationApi::class)
class PivotSpec(private val target: Float, private val list: () -> LazyListState) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val st = list()
        return pivotDistance(offset, target, st.canScrollBackward, st.canScrollForward)
    }
}

/**
 * Défilement de [base], suspendu tant que [frozen] est vrai. Sur la fiche d'une série, la page ne bouge plus d'elle-même
 * quand la télécommande est dans les épisodes : au bout de la liste, elle partait vers le bas et É10 devenait inatteignable.
 */
@OptIn(ExperimentalFoundationApi::class)
class FrozenSpec(private val base: BringIntoViewSpec, private val frozen: () -> Boolean) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        if (frozen()) 0f else base.calculateScrollDistance(offset, size, containerSize)
}
