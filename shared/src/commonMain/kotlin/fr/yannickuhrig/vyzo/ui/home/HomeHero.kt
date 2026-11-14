package fr.yannickuhrig.vyzo.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.yannickuhrig.vyzo.data.weather.Weather
import fr.yannickuhrig.vyzo.data.weather.WeatherPlace
import fr.yannickuhrig.vyzo.ui.common.Badge
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.NetImage
import fr.yannickuhrig.vyzo.ui.common.ProgressLine
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Arrière-plan plein écran de l'Accueil, repris sur Films, Séries et TV :
 * l'image du titre ou du programme s'affiche en fondu en haut à droite, sinon une lueur teintée
 * prend le relais, puis les deux dégradés fondent le tout dans le fond de l'appli.
 */
@Composable
fun HeroBackdrop(
    imageUrl: String?,
    accentColor: Color? = null,
    tv: Boolean,
    modifier: Modifier = Modifier,
) {
    val bg = MaterialTheme.colorScheme.background
    Box(modifier.background(bg)) {
        if (accentColor != null) {
            Spacer(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(accentColor.copy(alpha = if (tv) 0.24f else 0.20f), Color.Transparent),
                            center = Offset(size.width * 0.82f, size.height * 0.18f),
                            radius = size.maxDimension * 0.65f,
                        ),
                    )
                },
            )
        }
        Crossfade(imageUrl?.takeIf { it.isNotBlank() }, animationSpec = tween(700), label = "homeBackdrop") { url ->
            if (url != null) {
                Box(Modifier.fillMaxSize()) {
                    NetImage(
                        url,
                        Modifier.fillMaxWidth().fillMaxHeight(if (tv) 0.72f else 0.45f).align(Alignment.TopEnd),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to bg, 0.55f to bg.copy(alpha = 0.75f), 1f to bg.copy(alpha = 0.15f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to bg.copy(alpha = 0.35f), 0.62f to bg)))
    }
}

/** Petite ligne au-dessus du titre mis en avant : barre lumineuse ambre ou badge « EN DIRECT ». */
@Composable
fun HeroKicker(text: String, live: Boolean = false, liveChannel: String = "") {
    if (live) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge("EN DIRECT", VyzoColors.Red)
            if (liveChannel.isNotBlank()) {
                Text(
                    liveChannel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (text.isNotBlank()) {
                Text("·", color = VyzoColors.TextDim, style = MaterialTheme.typography.labelMedium)
                Text(
                    text.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    } else if (text.isNotBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(4.dp).height(15.dp).clip(RoundedCornerShape(50)).background(VyzoColors.Amber))
            Text(
                text.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.3.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Bloc d'information du titre mis en avant en haut de l'écran : étiquette, grand titre,
 * note/4K/métadonnées, éventuelle barre de progression (direct) et résumé.
 */
@Composable
fun HeroHeader(
    kicker: String,
    title: String?,
    tv: Boolean,
    modifier: Modifier = Modifier,
    rating: Float = 0f,
    uhd: Boolean = false,
    meta: List<String> = emptyList(),
    plot: String = "",
    live: Boolean = false,
    liveChannel: String = "",
    replay: Boolean = false,
    progress: Float? = null,
    minHeight: Dp = if (tv) 200.dp else 0.dp,
    titleMaxLines: Int = if (tv) 2 else 1,
    plotMaxLines: Int = if (tv) 2 else 2,
    onClick: (() -> Unit)? = null,
) {
    val clickMod = if (onClick != null) {
        Modifier.focusProperties { canFocus = false }.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
    } else {
        Modifier
    }
    Column(
        modifier.padding(end = if (tv) 24.dp else 12.dp).heightIn(min = minHeight).then(clickMod),
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (title.isNullOrBlank()) {
            Spacer(Modifier.height(if (tv) 0.dp else 40.dp))
            return@Column
        }
        HeroKicker(kicker, live = live, liveChannel = liveChannel)
        Text(
            title,
            style = if (tv) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        val cleanMeta = meta.filter { it.isNotBlank() }
        val hasRating = rating > 0f && rating <= 10f
        if (hasRating || uhd || cleanMeta.isNotEmpty() || replay) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                if (hasRating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, null, Modifier.size(16.dp), tint = VyzoColors.Amber)
                        Text(" %.1f".format(rating), style = MaterialTheme.typography.labelLarge, color = VyzoColors.Amber, fontWeight = FontWeight.Bold)
                    }
                }
                if (uhd) {
                    Text(
                        "4K",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(5.dp))
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                if (cleanMeta.isNotEmpty()) {
                    Text(
                        cleanMeta.joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = VyzoColors.TextDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (replay) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Default.History, "Replay", Modifier.size(14.dp), tint = VyzoColors.Cyan)
                        Text("Replay", style = MaterialTheme.typography.labelMedium, color = VyzoColors.Cyan, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (progress != null) {
            ProgressLine(progress, Modifier.padding(top = 6.dp).widthIn(max = if (tv) 420.dp else 260.dp))
        }
        if (plot.isNotBlank()) {
            Text(
                plot,
                style = if (tv) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = VyzoColors.Text.copy(alpha = 0.85f),
                maxLines = plotMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).then(if (tv) Modifier.widthIn(max = 620.dp) else Modifier),
            )
        }
    }
}

/** Météo de la ville configurée, partagée entre Accueil, Films, Séries et Direct (cache de 30 min dans [WeatherService]). */
@Composable
fun rememberWeatherState(): Pair<Weather?, WeatherPlace?> {
    val c = LocalContainer.current
    val place by remember(c) {
        c.settings.flow.map { s -> if (s.weatherCity.isBlank()) null else WeatherPlace(s.weatherCity, s.weatherLat, s.weatherLon) }
            .distinctUntilChanged()
    }.collectAsState(null)
    var weather by remember(place) { mutableStateOf<Weather?>(null) }
    LaunchedEffect(place) {
        val p = place ?: return@LaunchedEffect
        while (true) {
            val w = runCatching { c.weather.forecast(p) }.getOrNull()
            if (w != null) weather = w
            delay(if (w != null) 30 * 60_000L else 2 * 60_000L)
        }
    }
    return weather to place
}

/** Horloge, météo et actions éventuelles en haut à droite, comme sur l'Accueil. */
@Composable
fun HeaderStatusCorner(
    tv: Boolean,
    focusableWeather: Boolean = true,
    extraActions: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalContainer.current
    val settings by c.settings.state.collectAsState()
    val (weather, place) = rememberWeatherState()
    var weatherOpen by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (settings.homeClock) Clock(tv)
        if (place != null || extraActions != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                place?.let {
                    WeatherChip(
                        weather,
                        it.name,
                        modifier = if (focusableWeather) Modifier else Modifier.focusProperties { canFocus = false },
                    ) { weatherOpen = true }
                }
                extraActions?.invoke(this)
            }
        }
    }

    if (weatherOpen) WeatherDialog(place?.name.orEmpty(), weather) { weatherOpen = false }
}
