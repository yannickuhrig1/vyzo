package fr.yannickuhrig.vyzo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors

private val placeholderColors = listOf(0xFF7C5CFF, 0xFF00B8AE, 0xFFE0566B, 0xFFE59A2F, 0xFF3F8EE8, 0xFFA855F7).map { Color(it) }

/** Couleur stable tirée du titre, pour les cartes sans image. */
fun colorFor(title: String): Color = placeholderColors[(title.hashCode() and 0x7fffffff) % placeholderColors.size]

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
    titleOnFallback: Boolean = false,
) {
    Box(
        modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(PosterShape).background(VyzoColors.SurfaceHigh)
            .then(if (isDesktop()) Modifier.border(1.dp, PosterEdge, PosterShape) else Modifier),
    ) {
        NetImage(
            poster, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, fallback = title,
            fallbackContent = { PosterFallback(title, titleOnFallback) },
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
        modifier = Modifier.align(corner).padding(4.dp).background(Color(0xB3000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Remplaçant d'une affiche absente : un dégradé propre au titre, ses initiales en filigrane et, au besoin, le titre. */
@Composable
private fun PosterFallback(title: String, showTitle: Boolean) {
    val base = colorFor(title)
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(lerp(base, Color.Black, 0.15f), lerp(VyzoColors.SurfaceHigh, base, 0.22f), VyzoColors.SurfaceHigh)))) {
        Text(
            initials(title), color = Color.White.copy(alpha = 0.16f), fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.align(Alignment.Center),
        )
        if (showTitle) {
            Text(
                title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
        }
    }
}
