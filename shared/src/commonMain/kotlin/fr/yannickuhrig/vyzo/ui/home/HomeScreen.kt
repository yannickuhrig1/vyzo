package fr.yannickuhrig.vyzo.ui.home

import fr.yannickuhrig.vyzo.ui.common.PosterArt
import fr.yannickuhrig.vyzo.ui.common.isDesktop
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Dashboard
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.weather.Weather
import fr.yannickuhrig.vyzo.data.weather.WeatherService
import fr.yannickuhrig.vyzo.ui.common.Badge
import fr.yannickuhrig.vyzo.ui.common.ChannelMenu
import fr.yannickuhrig.vyzo.ui.common.EmptyState
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.NetImage
import fr.yannickuhrig.vyzo.ui.common.Tile
import fr.yannickuhrig.vyzo.ui.common.ZapDialog
import fr.yannickuhrig.vyzo.ui.common.appViewModel
import fr.yannickuhrig.vyzo.ui.common.isTv
import fr.yannickuhrig.vyzo.ui.live.tickerFlow
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ce que l'accueil peut demander à la navigation. */
class HomeActions(
    val onOpenMovie: (String) -> Unit,
    val onOpenSeries: (String) -> Unit,
    val onPlayChannel: (uid: String, group: String) -> Unit,
    val onCustomize: () -> Unit,
)

/** Accueil propre à une variante de l'appli (Vyzo TV) ; null pour l'accueil commun. */
val LocalHomeScreen = staticCompositionLocalOf<(@Composable (HomeActions) -> Unit)?> { null }

/**
 * Accueil (tableau de bord) : l'arrière-plan, le titre, la note et le résumé suivent ce qu'on sélectionne ;
 * rangées choisies et ordonnées dans « Personnaliser l'accueil » ; horloge et météo en haut à droite.
 */
@Composable
fun HomeScreen(
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onPlayChannel: (uid: String, group: String) -> Unit,
    onCustomize: () -> Unit,
) {
    val vm = appViewModel { HomeViewModel(it) }
    val c = LocalContainer.current
    val tv = isTv()
    val rows by vm.rows.collectAsState()
    val hero by vm.hero.collectAsState()
    var channelMenu by remember { mutableStateOf<Pair<ChannelEntity, String>?>(null) }

    LaunchedEffect(Unit) { vm.refreshApps() }

    val open: (HomeItem) -> Unit = { item ->
        when (item) {
            is VodItem -> if (item.kind == ContentKind.MOVIE) onOpenMovie(item.uid) else onOpenSeries(item.uid)
            is ChannelItem -> onPlayChannel(item.channel.uid, item.group)
            is OnAirItem -> onPlayChannel(item.channel.uid, item.group)
            is AppItem -> if (!c.platform.launchApp(item.app.id)) c.platform.toast("Impossible d'ouvrir ${item.app.label}")
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Arrière-plan : l'image du titre mis en avant, fondue vers le fond de l'appli à gauche et en bas.
        HeroBackdrop(hero?.let { it.backdrop.ifBlank { it.poster } }, tv = tv, modifier = Modifier.fillMaxSize())

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = if (tv) 32.dp else 16.dp, end = if (tv) 28.dp else 12.dp, top = if (tv) 20.dp else 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                HeroHeader(
                    kicker = hero?.let { if (it.kind == ContentKind.MOVIE) "Film" else "Série" }.orEmpty(),
                    title = hero?.title,
                    tv = tv,
                    modifier = Modifier.weight(1f),
                    rating = hero?.rating ?: 0f,
                    uhd = hero?.uhd ?: false,
                    meta = hero?.let { listOf(it.year, it.genre.split(',', '/', '|').firstOrNull()?.trim().orEmpty()) } ?: emptyList(),
                    plot = hero?.plot.orEmpty(),
                    minHeight = if (tv) 210.dp else 0.dp,
                    plotMaxLines = if (tv) 3 else 2,
                )
                HeaderStatusCorner(
                    tv = tv,
                    extraActions = {
                        Tile(onClick = onCustomize, shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.35f)) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                                if (tv) Text("Personnaliser", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    },
                )
            }

            val list = rows
            when {
                list == null -> Spacer(Modifier.weight(1f))
                list.isEmpty() -> EmptyState(
                    "Ton accueil est vide",
                    "Ajoute des rangées (chaînes favorites, films à reprendre, genres…) depuis « Personnaliser ».",
                    Icons.Default.Dashboard, Modifier.weight(1f).fillMaxWidth(),
                )
                else -> LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = if (tv) 8.dp else 4.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(if (tv) 14.dp else 10.dp),
                ) {
                    items(list, key = { it.id }) { row ->
                        RowSection(row, tv, onFocus = vm::onFocus, onClick = open, onLongClick = {
                            when (it) {
                                is ChannelItem -> channelMenu = it.channel to it.group
                                is OnAirItem -> channelMenu = it.channel to it.group
                                else -> Unit
                            }
                        })
                    }
                }
            }
        }
    }

    channelMenu?.let { (ch, group) ->
        ChannelMenu(ch, onDismiss = { channelMenu = null }, onWatch = { onPlayChannel(ch.uid, group) })
    }
}

@Composable
private fun RowSection(
    row: HomeRow,
    tv: Boolean,
    onFocus: (HomeItem) -> Unit,
    onClick: (HomeItem) -> Unit,
    onLongClick: (HomeItem) -> Unit,
) {
    val side = if (tv) 32.dp else 16.dp
    Column {
        Text(
            row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = side, bottom = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = side - 4.dp),
            horizontalArrangement = Arrangement.spacedBy(if (tv) 10.dp else 8.dp),
        ) {
            items(row.items, key = { it.key }) { item ->
                when (item) {
                    is VodItem -> VodTile(item, tv, onFocus, onClick)
                    is ChannelItem -> ChannelTile(item, tv, onFocus, onClick, onLongClick)
                    is OnAirItem -> OnAirTile(item, tv, onFocus, onClick, onLongClick)
                    is AppItem -> AppTile(item, tv, onFocus, onClick)
                }
            }
        }
    }
}

@Composable
private fun VodTile(item: VodItem, tv: Boolean, onFocus: (HomeItem) -> Unit, onClick: (HomeItem) -> Unit) {
    val desktop = isDesktop()
    Tile(
        onClick = { onClick(item) }, onFocus = { onFocus(item) },
        modifier = Modifier.width(if (desktop) 156.dp else if (tv) 118.dp else 104.dp), focusScale = 1.08f, hoverScale = 1.05f, shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(3.dp)) {
            PosterArt(
                item.title, item.poster, item.rating, item.uhd,
                progress = item.progress, genre = item.genre,
                badge = item.badge, badgeUpcoming = item.badgeUpcoming,
            )
            if (!tv || desktop) Text(item.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
internal fun ChannelTile(item: ChannelItem, tv: Boolean, onFocus: (HomeItem) -> Unit, onClick: (HomeItem) -> Unit, onLongClick: (HomeItem) -> Unit) {
    val ch = item.channel
    Tile(
        onClick = { onClick(item) }, onLongClick = { onLongClick(item) }, onFocus = { onFocus(item) },
        modifier = Modifier.width(if (tv) 210.dp else 180.dp), focusScale = 1.05f,
        color = VyzoColors.Surface.copy(alpha = 0.85f), shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NetImage(ch.logo, Modifier.size(width = 52.dp, height = 34.dp), fallback = ch.name)
                Spacer(Modifier.width(8.dp))
                Text(ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val p = item.now
            Text(
                p?.title ?: "Pas de programme",
                style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (p != null && p.stop > p.start) {
                val now = System.currentTimeMillis()
                val f = ((now - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f)
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(VyzoColors.SurfaceHigh)) {
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

/** Grande carte « À l'antenne maintenant » : image du programme, chaîne, titre, horaire, progression et suite. */
@Composable
internal fun OnAirTile(item: OnAirItem, tv: Boolean, onFocus: (HomeItem) -> Unit, onClick: (HomeItem) -> Unit, onLongClick: (HomeItem) -> Unit) {
    val ch = item.channel
    val p = item.now
    Tile(
        onClick = { onClick(item) }, onLongClick = { onLongClick(item) }, onFocus = { onFocus(item) },
        modifier = Modifier.width(if (tv) 400.dp else 300.dp), focusScale = 1.04f,
        color = VyzoColors.Surface, shape = RoundedCornerShape(14.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
            if (p.icon.isNotBlank()) NetImage(p.icon, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.45f to Color.Black.copy(alpha = 0.45f), 1f to Color.Black.copy(alpha = 0.92f)),
                ),
            )
            Row(Modifier.align(Alignment.TopStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.92f)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                    NetImage(ch.logo, Modifier.size(width = 54.dp, height = 30.dp), fallback = ch.name)
                }
                Spacer(Modifier.width(10.dp))
                Text(ch.name, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Badge(item.score ?: "EN DIRECT", VyzoColors.Red)
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp)) {
                Text(
                    p.title, style = if (tv) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val now = System.currentTimeMillis()
                val left = ((p.stop - now) / 60_000L).coerceAtLeast(0)
                Text(
                    "${Fmt.range(p.start, p.stop)} · encore $left min" +
                        if (item.alsoOn > 0) " · aussi sur ${item.alsoOn} autre${if (item.alsoOn > 1) "s" else ""} chaîne${if (item.alsoOn > 1) "s" else ""}" else "",
                    style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 2.dp),
                )
                val f = if (p.stop > p.start) ((now - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f) else 0f
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(VyzoColors.Red))
                }
                item.next?.let { n ->
                    Text(
                        "Ensuite · ${Fmt.time(n.start)} ${n.title}",
                        style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppTile(item: AppItem, tv: Boolean, onFocus: (HomeItem) -> Unit, onClick: (HomeItem) -> Unit) {
    val app = item.app
    Tile(
        onClick = { onClick(item) }, onFocus = { onFocus(item) },
        modifier = Modifier.width(if (tv) 160.dp else 120.dp), focusScale = 1.06f,
        color = VyzoColors.Surface.copy(alpha = 0.85f), shape = RoundedCornerShape(10.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
            val icon = app.icon
            when {
                icon != null && app.banner -> Image(icon, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (icon != null) Image(icon, null, Modifier.size(40.dp)) else Icon(Icons.Default.Apps, null, Modifier.size(32.dp))
                    Text(app.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 6.dp, end = 6.dp))
                }
            }
        }
    }
}

private val clockDate = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)
}

@Composable
fun Clock(tv: Boolean) {
    val now by remember { tickerFlow(15_000) }.collectAsState(System.currentTimeMillis())
    Column(horizontalAlignment = Alignment.End) {
        Text(Fmt.time(now), fontSize = if (tv) 34.sp else 24.sp, fontWeight = FontWeight.Light)
        Text(
            clockDate.get()!!.format(Date(now)).replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelLarge, color = VyzoColors.TextDim,
        )
    }
}

fun weatherIcon(code: Int): ImageVector = when (code) {
    0, 1 -> Icons.Default.WbSunny
    2 -> Icons.Default.WbCloudy
    3 -> Icons.Default.Cloud
    45, 48 -> Icons.Default.Dehaze
    in 51..57 -> Icons.Default.Grain
    in 61..67, in 80..82 -> Icons.Default.Umbrella
    in 71..77, 85, 86 -> Icons.Default.AcUnit
    in 95..99 -> Icons.Default.Thunderstorm
    else -> Icons.Default.Cloud
}

@Composable
fun WeatherChip(weather: Weather?, city: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Tile(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.35f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (weather == null) {
                Text(city, style = MaterialTheme.typography.labelLarge, color = VyzoColors.TextDim)
            } else {
                Icon(weatherIcon(weather.code), null, Modifier.size(18.dp), tint = VyzoColors.Amber)
                Text(" ${weather.temperature.toInt()}°", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text("  $city", style = MaterialTheme.typography.labelLarge, color = VyzoColors.TextDim, maxLines = 1)
            }
        }
    }
}

private val dayName = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("EEEE", Locale.FRANCE)
}
private val isoDay = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
}

@Composable
fun WeatherDialog(city: String, weather: Weather?, onDismiss: () -> Unit) {
    ZapDialog(onDismiss) {
        Column(Modifier.padding(24.dp).width(420.dp)) {
            Text("Météo · $city", style = MaterialTheme.typography.titleLarge)
            if (weather == null) {
                Text("Météo indisponible pour l'instant (pas de connexion ?).", color = VyzoColors.TextDim, modifier = Modifier.padding(top = 12.dp))
                return@Column
            }
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(weatherIcon(weather.code), null, Modifier.size(48.dp), tint = VyzoColors.Amber)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("${weather.temperature.toInt()}°", fontSize = 40.sp, fontWeight = FontWeight.Light)
                    Text(WeatherService.label(weather.code), color = VyzoColors.TextDim)
                }
            }
            Spacer(Modifier.height(16.dp))
            weather.days.forEachIndexed { i, d ->
                val name = when (i) {
                    0 -> "Aujourd'hui"
                    1 -> "Demain"
                    else -> runCatching { dayName.get()!!.format(isoDay.get()!!.parse(d.date)!!) }.getOrDefault(d.date).replaceFirstChar { it.uppercase() }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f))
                    Icon(weatherIcon(d.code), null, Modifier.size(20.dp), tint = VyzoColors.Amber)
                    Text(WeatherService.label(d.code), Modifier.width(150.dp).padding(start = 8.dp), color = VyzoColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${d.min.toInt()}° / ${d.max.toInt()}°", fontWeight = FontWeight.SemiBold)
                }
            }
            Text("Données Open-Meteo", style = MaterialTheme.typography.labelSmall, color = VyzoColors.TextDim, modifier = Modifier.padding(top = 12.dp))
        }
    }
}
