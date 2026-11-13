// Dérivé de HomeScreen.kt, HomeParts.kt et Billboard.kt d'IPVIO Stream, Copyright (C) 2026 IPVIO Stream contributors, GNU GPL 3.
// Modifié pour Vyzo le 2026-11-08 : accueil alimenté par HomeViewModel (profils, contrôle parental, personnalisation), horloge, météo et applications de Vyzo.
// Modifié pour Vyzo le 2026-11-13 : image et informations du titre déplacées dans TvSection.kt.
package fr.yannickuhrig.vyzo.tv

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.home.HomeRows
import fr.yannickuhrig.vyzo.ui.common.ChannelMenu
import fr.yannickuhrig.vyzo.ui.common.EmptyState
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.appViewModel
import fr.yannickuhrig.vyzo.ui.home.AppItem
import fr.yannickuhrig.vyzo.ui.home.ChannelItem
import fr.yannickuhrig.vyzo.ui.home.Clock
import fr.yannickuhrig.vyzo.ui.home.HomeActions
import fr.yannickuhrig.vyzo.ui.home.HomeItem
import fr.yannickuhrig.vyzo.ui.home.HomeRow
import fr.yannickuhrig.vyzo.ui.home.HomeViewModel
import fr.yannickuhrig.vyzo.ui.home.OnAirItem
import fr.yannickuhrig.vyzo.ui.home.VodItem
import fr.yannickuhrig.vyzo.ui.home.WeatherChip
import fr.yannickuhrig.vyzo.ui.home.WeatherDialog
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Marge latérale des rangées. */
private val RowPadX: Dp get() = dpx(24)

/** Hauteur du haut de l'écran : titre mis en avant, horloge et météo. */
private val TopZoneH: Dp get() = dpx(330)

/**
 * Accueil de Vyzo TV : l'image et les informations du titre sélectionné occupent le haut de l'écran,
 * la rangée active reste juste en dessous et les rangées précédentes disparaissent.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvHome(actions: HomeActions) {
    val vm = appViewModel { HomeViewModel(it) }
    val c = LocalContainer.current
    val rows by vm.rows.collectAsState()
    val hero by vm.hero.collectAsState()
    val settings by c.settings.state.collectAsState()
    val weather by vm.weather.collectAsState()
    val place by vm.weatherPlace.collectAsState()
    var channelMenu by remember { mutableStateOf<Pair<ChannelEntity, String>?>(null) }
    var weatherOpen by remember { mutableStateOf(false) }

    TrackDesignScale()

    LaunchedEffect(Unit) {
        vm.refreshApps()
        // Passé ce délai, la carte à reprendre n'est plus à l'écran : elle ne doit pas capter le focus plus tard.
        delay(1500)
        LastFocus.pending = null
    }

    val openVod: (VodItem) -> Unit = { if (it.kind == ContentKind.MOVIE) actions.onOpenMovie(it.uid) else actions.onOpenSeries(it.uid) }
    val open: (HomeItem) -> Unit = { item ->
        if (item !is AppItem) LastFocus.pending = item.key
        when (item) {
            is VodItem -> openVod(item)
            is ChannelItem -> actions.onPlayChannel(item.channel.uid, item.group)
            is OnAirItem -> actions.onPlayChannel(item.channel.uid, item.group)
            is AppItem -> if (!c.platform.launchApp(item.app.id)) c.platform.toast("Impossible d'ouvrir ${item.app.label}")
        }
    }
    val longPress: (HomeItem) -> Unit = {
        when (it) {
            is ChannelItem -> channelMenu = it.channel to it.group
            is OnAirItem -> channelMenu = it.channel to it.group
            else -> Unit
        }
    }

    // Parcours rapide : l'image ne change qu'après un court arrêt, pour ne pas charger chaque grande image au passage.
    var shown by remember { mutableStateOf(hero) }
    LaunchedEffect(hero) {
        if (shown != null) delay(450)
        shown = hero
    }

    val firstCard = remember { FocusRequester() }
    var entered by rememberSaveable { mutableStateOf(false) }
    val list = rows
    val listState = rememberLazyListState()
    // Les rangées arrivent une à une : relancé à chaque arrivée, pour que le focus aille bien sur la première.
    LaunchedEffect(list?.map { it.id }) {
        if (list.isNullOrEmpty() || entered || LastFocus.pending != null) return@LaunchedEffect
        delay(350)
        // Une rangée arrivée après coup (les programmes en cours sont plus lents) s'insère au-dessus de l'écran : on remonte la voir.
        listState.scrollToItem(0)
        repeat(20) {
            delay(100)
            if (runCatching { firstCard.requestFocus() }.getOrDefault(false)) {
                entered = true
                return@LaunchedEffect
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        SectionBackdrop(shown, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(TopZoneH).padding(start = dpx(48), end = dpx(40), top = dpx(24))) {
                Box(Modifier.weight(1f)) {
                    shown?.let {
                        Column(verticalArrangement = Arrangement.spacedBy(dpx(10))) {
                            SectionInfo(it)
                            HeroButton("Plus d'infos", Icons.Default.Info, true, h = 46, onClick = { openVod(it) })
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(dpx(14))) {
                    if (settings.homeClock) Clock(tv = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(dpx(14)), verticalAlignment = Alignment.CenterVertically) {
                        place?.let { WeatherChip(weather, it.name) { weatherOpen = true } }
                        HeroButton("Personnaliser", Icons.Default.Edit, false, h = 46, onClick = actions.onCustomize)
                    }
                }
            }
            when {
                list == null -> SkeletonRow(Modifier.weight(1f).fillMaxWidth())
                list.isEmpty() -> EmptyState(
                    "Ton accueil est vide",
                    "Ajoute des rangées (chaînes favorites, films à reprendre, genres…) depuis « Personnaliser ».",
                    Icons.Default.Dashboard, Modifier.weight(1f).fillMaxWidth(),
                )
                else -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val grid = remember(maxWidth, Design.k) { cardGrid(maxWidth - RowPadX * 2) }
                    val density = LocalDensity.current
                    // Le haut de la carte s'arrête sous le titre de sa rangée, lui-même calé en haut de la liste.
                    val target = with(density) { RowTitlePad.toPx() + RowTitleLine.toPx() + RowCardsPad.toPx() }
                    val pivot = remember(listState, target) { PivotSpec(target) { listState } }
                    CompositionLocalProvider(LocalCardGrid provides grid, LocalBringIntoViewSpec provides pivot) {
                        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = dpx(420))) {
                            itemsIndexed(list, key = { _, r -> r.id }) { i, row ->
                                TvRow(row, if (i == 0) firstCard else null, vm::onFocus, open, longPress)
                            }
                        }
                    }
                }
            }
        }
    }

    channelMenu?.let { (ch, group) ->
        ChannelMenu(ch, onDismiss = { channelMenu = null }, onWatch = { actions.onPlayChannel(ch.uid, group) })
    }
    if (weatherOpen) WeatherDialog(place?.name.orEmpty(), weather) { weatherOpen = false }
}

/** Espaces d'une rangée, repris par le calage vertical de la liste. */
private val RowTitlePad: Dp get() = dpx(16)
private val RowTitleLine: Dp get() = dpx(34)
private val RowCardsPad: Dp get() = dpx(24)

/** Rangée titrée de cartes en colonnes égales. La sélection reste au centre, la rangée glisse dessous. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvRow(
    row: HomeRow,
    firstCard: FocusRequester?,
    onFocus: (HomeItem) -> Unit,
    onClick: (HomeItem) -> Unit,
    onLongClick: (HomeItem) -> Unit,
) {
    val g = LocalCardGrid.current
    val state = rememberLazyListState()
    var cur by remember { mutableIntStateOf(-1) }
    CenterFollow(state, cur)
    val landscape = row.id == HomeRows.CONTINUE_MOVIES || row.id == HomeRows.CONTINUE_SERIES
    val wide = row.items.firstOrNull() !is VodItem || landscape
    Column {
        Box(Modifier.fillMaxWidth().padding(start = dpx(29), end = dpx(28), top = RowTitlePad).height(RowTitleLine), contentAlignment = Alignment.CenterStart) {
            Text(row.title, color = Color.White, fontSize = spx(25), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        var rowFocused by remember { mutableStateOf(false) }
        Box(Modifier.onFocusChanged { rowFocused = it.hasFocus }) {
            CompositionLocalProvider(LocalRowFocus provides rowFocused, LocalBringIntoViewSpec provides SmoothSpec) {
                LazyRow(
                    state = state,
                    contentPadding = PaddingValues(start = RowPadX, end = RowPadX, top = RowCardsPad, bottom = RowCardsPad),
                    horizontalArrangement = Arrangement.spacedBy(if (wide) g.gap else g.posterGap),
                ) {
                    itemsIndexed(row.items, key = { _, it -> it.key }) { i, item ->
                        // Toujours le même enchaînement : si une rangée s'insère au-dessus, la carte active garde le focus.
                        val own = remember { FocusRequester() }
                        val track = Modifier.onFocusChanged { if (it.hasFocus) cur = i }
                            .restoreFocus(item.key)
                            .focusRequester(if (i == 0 && firstCard != null) firstCard else own)
                        val focus = { onFocus(item) }
                        val click = { onClick(item) }
                        val long = { onLongClick(item) }
                        when (item) {
                            is VodItem -> if (landscape) LandscapeCard(item, track.width(g.land), focus, click) else PosterTile(item, track.width(g.poster), focus, click)
                            is ChannelItem -> ChannelNowCard(item, track.width(g.chan), focus, click, long)
                            is OnAirItem -> OnAirCard(item, track.width(g.air), focus, click, long)
                            is AppItem -> AppCard(item, track.width(g.land), focus, click)
                        }
                    }
                }
            }
        }
    }
}

/** Garde la carte sélectionnée au milieu de l'écran. Aux extrémités de la rangée, elle va naturellement jusqu'au bord. */
@Composable
private fun CenterFollow(state: LazyListState, cur: Int) {
    LaunchedEffect(cur) {
        if (cur < 0) return@LaunchedEffect
        snapshotFlow {
            val info = state.layoutInfo
            info.visibleItemsInfo.firstOrNull { x -> x.index == cur }?.let { x -> x.offset + x.size / 2f - info.viewportSize.width / 2f }
        }.collectLatest { d ->
            if (d != null && abs(d) > 1.5f) state.scrollBy(d * 0.4f)
        }
    }
}

/** Rangée d'attente pendant le chargement. */
@Composable
private fun SkeletonRow(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.05f, 0.12f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ska")
    val col = Color.White.copy(alpha = if (Look.motion > 0) a else 0.08f)
    Row(modifier.padding(horizontal = RowPadX, vertical = dpx(58)), horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
        repeat(8) { Box(Modifier.weight(1f).height(dpx(280)).clip(RoundedCornerShape(dpx(8))).background(col)) }
    }
}
