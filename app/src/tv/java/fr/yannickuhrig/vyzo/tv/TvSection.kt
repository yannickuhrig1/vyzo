// Dérivé de HomeScreen.kt, HomeParts.kt et Billboard.kt d'IPVIO Stream, Copyright (C) 2026 IPVIO Stream contributors, GNU GPL 3.
// Modifié pour Vyzo le 2026-11-13 : image et informations du titre mis en avant sorties de l'accueil, pour les Films et les Séries.
package fr.yannickuhrig.vyzo.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.home.Clock
import fr.yannickuhrig.vyzo.ui.home.VodItem
import fr.yannickuhrig.vyzo.ui.home.WeatherChip
import fr.yannickuhrig.vyzo.ui.home.WeatherDialog
import fr.yannickuhrig.vyzo.ui.home.rememberWeatherState

/** Voile de l'accueil : texte à gauche de l'écran, rangées en bas. */
internal val HomeScrimX = listOf(0f to 1f, 0.24f to .94f, 0.48f to .5f, 0.72f to 0f)
internal val HomeScrimY = listOf(0f to .5f, 0.16f to 0f, 0.38f to 0f, 0.58f to .82f, 0.78f to 1f)

/** Image plein écran du titre mis en avant, fondue dans le fond à gauche (texte) et en bas (cartes). */
@Composable
internal fun SectionBackdrop(item: VodItem?, modifier: Modifier, scrimX: ScrimStops = HomeScrimX, scrimY: ScrimStops = HomeScrimY) {
    Box(modifier.background(Bg)) {
        Crossfade(item?.let { it.backdrop.ifBlank { it.poster } }, animationSpec = tween(if (Look.motion == 0) 0 else 600), label = "backdrop") { url ->
            if (!url.isNullOrBlank()) AsyncImage(cardBackdrop(url), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopEnd)
        }
        BakedScrim(scrimX, scrimY, Modifier.fillMaxSize())
    }
}

/** Taille du titre selon sa longueur : il tient toujours sur une ligne au-dessus des rangées. */
fun heroTitleSize(title: String): Int = if (title.length <= 24) 72 else if (title.length <= 34) 60 else 48

/**
 * Petite ligne, titre, année · genre · note et deux lignes de résumé du titre mis en avant.
 * [kicker] remplace « FILM » ou « SÉRIE » ; [titleScale] réduit le titre quand la place manque ;
 * [maxWidth] (pixels de maquette) garde le résumé sur la partie assombrie de l'image.
 */
@Composable
internal fun SectionInfo(item: VodItem, kicker: String? = null, titleScale: Float = 1f, maxWidth: Int = 980) {
    Crossfade(item, animationSpec = tween(if (Look.motion == 0) 0 else 450), label = "info") { c ->
        Column(Modifier.widthIn(max = dpx(maxWidth)), verticalArrangement = Arrangement.spacedBy(dpx(10))) {
            Kicker(kicker ?: if (c.kind == ContentKind.MOVIE) "FILM" else "SÉRIE")
            Text(
                c.title, color = Tok.TextHi, fontSize = spx(heroTitleSize(c.title) * titleScale), lineHeight = spx(76 * titleScale),
                fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            MetaLine(c)
            if (c.plot.isNotBlank()) {
                Text(c.plot, color = Tok.TextMid, fontSize = spx(19), lineHeight = spx(29), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Petite ligne au-dessus du titre, avec une barre lumineuse à la couleur de l'appli. */
@Composable
fun Kicker(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
        Box(
            Modifier.width(dpx(5)).height(dpx(24)).shadow(dpx(10), RoundedCornerShape(50), ambientColor = Accent1, spotColor = Accent1)
                .clip(RoundedCornerShape(50)).background(Accent1),
        )
        Text(text, color = Accent2, fontSize = spx(17), fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** « 2016 | Aventure | ★ 7,6 | 4K ». */
@Composable
internal fun MetaLine(item: VodItem) {
    val parts = listOf(item.year, firstGenre(item.genre)).filter { it.isNotBlank() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
        parts.forEachIndexed { i, p ->
            if (i > 0) Separator()
            Text(p, color = Color(0xFFD4D4DA), fontSize = spx(17), maxLines = 1)
        }
        if (item.rating > 0f) {
            if (parts.isNotEmpty()) Separator()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(5))) {
                Icon(Icons.Default.Star, null, Modifier.size(dpx(18)), tint = Accent1)
                Text("%.1f".format(item.rating), color = Accent1, fontSize = spx(17), fontWeight = FontWeight.SemiBold)
            }
        }
        if (item.uhd) {
            Text(
                "4K", color = Color.White, fontSize = spx(13), fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(dpx(5))).padding(horizontal = dpx(7), vertical = dpx(1)),
            )
        }
    }
}

@Composable
internal fun Separator() {
    Box(Modifier.width(dpx(1)).height(dpx(16)).background(Color(0x66FFFFFF)))
}

/** Horloge et météo en haut à droite sur TV (non focusables à la télécommande pour garder la navigation fluide). */
@Composable
internal fun TvStatusCorner(modifier: Modifier = Modifier, focusableWeather: Boolean = false) {
    val c = LocalContainer.current
    val settings by c.settings.state.collectAsState()
    val (weather, place) = rememberWeatherState()
    var weatherOpen by remember { mutableStateOf(false) }
    if (!settings.homeClock && place == null) return

    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(dpx(10))) {
        if (settings.homeClock) Clock(tv = true)
        place?.let {
            WeatherChip(
                weather,
                it.name,
                modifier = if (focusableWeather) Modifier else Modifier.focusProperties { canFocus = false },
            ) { weatherOpen = true }
        }
    }
    if (weatherOpen) WeatherDialog(place?.name.orEmpty(), weather) { weatherOpen = false }
}
