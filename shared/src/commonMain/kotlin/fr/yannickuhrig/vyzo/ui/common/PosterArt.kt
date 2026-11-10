package fr.yannickuhrig.vyzo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import fr.yannickuhrig.vyzo.data.vod.Genres
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors

private val placeholderColors = listOf(0xFF7C5CFF, 0xFF00B8AE, 0xFFE0566B, 0xFFE59A2F, 0xFF3F8EE8, 0xFFA855F7).map { Color(it) }

/** Couleur stable tirée du titre, pour les cartes sans image. */
fun colorFor(title: String): Color = placeholderColors[(title.hashCode() and 0x7fffffff) % placeholderColors.size]

/** Une teinte par genre : une affiche absente d'horreur ne ressemble pas à celle d'une comédie. */
private val genreColors: Map<String, Color> = mapOf(
    "action" to 0xFFE4572E, "aventure" to 0xFFE59A2F, "animation" to 0xFF00B8AE, "anime" to 0xFFFF4F8B,
    "comedie" to 0xFFE8B931, "policier" to 0xFF3F6A9C, "documentaire" to 0xFF3E9E6A, "drame" to 0xFF3F7FD8,
    "famille" to 0xFF5DB83E, "fantastique" to 0xFF8E5CFF, "horreur" to 0xFFB3202A, "romance" to 0xFFE0566B,
    "sf" to 0xFF5C6BFF, "thriller" to 0xFF7A3E9D, "guerre" to 0xFF7D7A4F, "western" to 0xFFB8733A,
    "musique" to 0xFFE040C0, "histoire" to 0xFFA8834A, "mystere" to 0xFF4B4F9C, "biopic" to 0xFF8091A8,
    "sport" to 0xFF1EA7E8,
).mapValues { Color(it.value) }

/** Couleur d'une affiche absente : celle du genre principal, sinon une couleur tirée du titre. */
fun posterColor(title: String, genre: String): Color =
    Genres.primary(genre)?.let { genreColors[it] } ?: colorFor(title)

/** Dégradé d'une affiche absente : la teinte du genre en haut, qui plonge vers le noir. */
fun posterBrush(base: Color): Brush = Brush.verticalGradient(
    0f to lerp(base, Color.Black, 0.08f),
    0.52f to lerp(base, Color.Black, 0.64f),
    1f to Color(0xFF09090B),
)

/**
 * Taille du titre écrit sur une affiche absente, en part de sa largeur : plus il est long, plus il est petit,
 * et le mot le plus long tient toujours sur une ligne (« INTOUCHABLES » ne se coupe pas en deux).
 */
fun posterTitleScale(title: String): Float {
    val byLength = when {
        title.length <= 6 -> 0.2f
        title.length <= 12 -> 0.15f
        title.length <= 22 -> 0.12f
        else -> 0.1f
    }
    val longest = title.split(' ', '-').maxOfOrNull { it.length } ?: 1
    // Une capitale grasse, espacement compris, fait environ 0,75 fois la taille du texte ; 84 % de la largeur sert.
    return minOf(byLength, 0.84f / (maxOf(longest, 1) * 0.75f))
}

// Trait clair en haut à gauche : il détache les affiches sombres du fond de l'appli.
private val PosterEdge = Brush.linearGradient(listOf(Color(0x47FFFFFF), Color(0x14FFFFFF), Color(0x0AFFFFFF)))
private val PosterShape = RoundedCornerShape(8.dp)

/** Affiche commune aux grilles et à l'accueil, hors TV : image ou remplaçant, note, badge 4K et avancement. */
@Composable
fun PosterArt(
    title: String,
    poster: String?,
    rating: Float,
    uhd: Boolean,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    genre: String = "",
) {
    Box(
        modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(PosterShape).background(VyzoColors.SurfaceHigh)
            .then(if (isDesktop()) Modifier.border(1.dp, PosterEdge, PosterShape) else Modifier),
    ) {
        NetImage(
            poster, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, fallback = title,
            fallbackContent = { PosterTitleArt(title, genre, Modifier.fillMaxSize()) },
        )
        if (uhd) PosterBadge("4K", Color.White, Alignment.TopStart)
        // Certains catalogues donnent des notes hors échelle.
        if (rating > 0f && rating <= 10f) PosterBadge("★ %.1f".format(rating), VyzoColors.Amber, Alignment.TopEnd)
        if (progress > 0f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(VyzoColors.Amber))
            }
        }
    }
}

@Composable
private fun BoxScope.PosterBadge(text: String, color: Color, corner: Alignment) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color, maxLines = 1,
        modifier = Modifier.align(corner).padding(5.dp)
            .background(Color(0x99000000), RoundedCornerShape(5.dp))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * Remplaçant d'une affiche absente, dessiné comme une vraie affiche : dégradé teinté par le genre,
 * halo en haut, et le titre en capitales dans le bas. [showTitle] faux : le dégradé seul (une légende le recouvre).
 */
@Composable
fun PosterTitleArt(title: String, genre: String, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val base = remember(title, genre) { posterColor(title, genre) }
    BoxWithConstraints(
        modifier.background(posterBrush(base)).drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.2f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height * 0.2f), radius = size.width * 0.85f,
                ),
            )
        },
    ) {
        if (!showTitle) return@BoxWithConstraints
        val w = maxWidth
        val h = maxHeight
        val fontSize = with(LocalDensity.current) { (w * posterTitleScale(title)).toSp() }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = w * 0.08f).padding(bottom = h * 0.11f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.width(w * 0.14f).height(2.dp).clip(RoundedCornerShape(1.dp)).background(lerp(base, Color.White, 0.45f)))
            Text(
                title.uppercase(),
                style = TextStyle(
                    color = Color.White, fontSize = fontSize, lineHeight = 1.08.em, fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.06.em, textAlign = TextAlign.Center,
                    shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 8f),
                ),
                maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = h * 0.03f),
            )
        }
    }
}
