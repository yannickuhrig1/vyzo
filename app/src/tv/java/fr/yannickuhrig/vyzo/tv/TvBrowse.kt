// Dérivé de BrowseScreen.kt et Dropdown.kt d'IPVIO Stream, Copyright (C) 2026 IPVIO Stream contributors, GNU GPL 3.
// Modifié pour Vyzo le 2026-11-08 : catégories, tris et contrôle parental de Vyzo, filtres sur les données du catalogue, libellés en français.
// Modifié pour Vyzo le 2026-11-10 : rubriques dans la liste des catégories, bandeau du titre sélectionné, voisines atténuées.
// Modifié pour Vyzo le 2026-11-13 : image plein écran et titre mis en avant repris de l'accueil, au-dessus de la grille.
package fr.yannickuhrig.vyzo.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.ui.common.EmptyState
import fr.yannickuhrig.vyzo.ui.common.appViewModel
import fr.yannickuhrig.vyzo.ui.home.VodItem
import fr.yannickuhrig.vyzo.ui.profile.rememberParentalGate
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import fr.yannickuhrig.vyzo.ui.vod.VodCard
import fr.yannickuhrig.vyzo.ui.vod.VodSortOrder
import fr.yannickuhrig.vyzo.ui.vod.VodViewModel
import kotlinx.coroutines.delay
import androidx.compose.runtime.CompositionLocalProvider
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.vod.VodCategoryLine
import fr.yannickuhrig.vyzo.ui.vod.vodCategoryLines
import fr.yannickuhrig.vyzo.ui.vod.vodSpotlightPick
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Sept colonnes, comme les affiches de l'accueil : la liste des catégories plus étroite laisse la place.
private const val GRID_COLUMNS = 7

/** Largeur de la liste des catégories et hauteur du titre mis en avant, en pixels de maquette. */
private const val RAIL_W = 360
private const val HERO_H = 276

/**
 * Voile de l'image : le texte commence après la liste des catégories, le sombre de gauche va donc plus loin qu'à l'accueil ;
 * la grille commence plus haut, l'image s'efface donc plus tôt en bas.
 */
private val BrowseScrimX = listOf(0f to 1f, 0.36f to .94f, 0.6f to .6f, 0.86f to 0f)
private val BrowseScrimY = listOf(0f to .45f, 0.14f to 0f, 0.28f to 0f, 0.44f to .85f, 0.6f to 1f)

internal val RATING_LABELS = listOf("Toutes les notes", "5 et plus", "6 et plus", "7 et plus", "8 et plus", "9 et plus")
internal val RATING_VALUES = listOf(0f, 5f, 6f, 7f, 8f, 9f)
internal val PERIOD_LABELS = listOf("Toutes les années", "Années 2020", "Années 2010", "Années 2000", "Années 1990", "Avant 1990")

/** Le titre sort-il pendant la période [period] de [PERIOD_LABELS] ? Une année inconnue n'entre que dans « Toutes ». */
internal fun inPeriod(year: String, period: Int): Boolean {
    if (period == 0) return true
    val y = year.take(4).toIntOrNull() ?: return false
    return when (period) {
        1 -> y >= 2020
        2 -> y in 2010..2019
        3 -> y in 2000..2009
        4 -> y in 1990..1999
        else -> y in 1..1989
    }
}

internal fun filterCards(list: List<VodCard>, minRating: Float, period: Int): List<VodCard> =
    if (minRating <= 0f && period == 0) list else list.filter { it.rating >= minRating && inPeriod(it.year, period) }

private fun VodCard.toVodItem(kind: ContentKind) = VodItem(kind, uid, title, poster, backdrop, rating, year, genre, plot, uhd)

/**
 * Titre en grand tant que rien n'est sélectionné. Sur TV, il sert d'en-tête à l'écran : il s'affiche même quand
 * la liste n'a ni image de fond ni résumé (cas des films Xtream, complétés seulement à l'ouverture de leur fiche).
 * D'abord le choix du bandeau, sinon le mieux noté qui a une affiche, sinon le premier titre.
 */
internal fun tvHeroPick(items: List<VodCard>, selected: String?): VodCard? =
    vodSpotlightPick(items, selected)
        ?: items.filter { it.poster.isNotBlank() && it.rating <= 10f }.maxByOrNull { it.rating }
        ?: items.firstOrNull()

/** Films ou séries : catégories à gauche, filtres et affiches à droite. */
@Composable
fun TvBrowse(kind: ContentKind, onOpen: (String) -> Unit) {
    val vm = appViewModel(key = "vod-$kind") { VodViewModel(it, kind) }
    val cats by vm.categories.collectAsState()
    val selected by vm.selected.collectAsState()
    val items by vm.items.collectAsState()
    val sort by vm.sortOrder.collectAsState()
    val loadingMore by vm.loadingMore.collectAsState()
    val gate = rememberParentalGate()
    val settings = LocalContainer.current.settings
    val spotlightOn by remember(settings) { settings.flow.map { it.vodSpotlight }.distinctUntilChanged() }.collectAsState(true)

    var ratingIndex by rememberSaveable { mutableIntStateOf(0) }
    var period by rememberSaveable { mutableIntStateOf(0) }
    val filtersOn = ratingIndex != 0 || period != 0
    val shown = remember(items, ratingIndex, period) { items?.let { filterCards(it, RATING_VALUES[ratingIndex], period) } }

    LaunchedEffect(selected) { selected?.let { vm.select(it) } }
    // Une autre catégorie repart sans filtres : sinon, on passerait d'une catégorie vide à l'autre sans comprendre.
    var filtersFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(selected) {
        if (filtersFor != null && filtersFor != selected) {
            ratingIndex = 0
            period = 0
        }
        filtersFor = selected
    }

    // Passer sur une catégorie l'ouvre après un court arrêt : descendre la liste ne charge pas chaque catégorie.
    var focusedCat by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedCat) {
        val key = focusedCat ?: return@LaunchedEffect
        delay(300)
        vm.select(key)
    }

    val catList = rememberLazyListState()
    val catFocus = remember { FocusRequester() }
    val lines = remember(cats) { vodCategoryLines(cats) }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(cats.isNotEmpty(), selected != null) {
        val key = selected
        if (entered || cats.isEmpty() || key == null) return@LaunchedEffect
        entered = true
        val index = lines.indexOfFirst { it.key == key }
        if (index >= 0) catList.scrollToItem(index)
        focusOnArrival(catFocus)
    }

    val grid = rememberLazyGridState()
    val listKey = "$selected|$sort|$ratingIndex|$period"
    // Mémorisé : au retour d'une fiche, la grille reste où elle était.
    var scrolledFor by rememberSaveable { mutableStateOf(listKey) }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(listKey) {
        if (scrolledFor != listKey) {
            scrolledFor = listKey
            focusedIndex = -1
            grid.scrollToItem(0)
        }
    }
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 12 } }
    LaunchedEffect(nearEnd, items?.size) { if (nearEnd) vm.nearEnd() }

    val title = cats.firstOrNull { it.key == selected }?.title ?: if (kind == ContentKind.MOVIE) "Films" else "Séries"

    // Titre mis en avant : celui de la carte sélectionnée, après un court arrêt (la grande image ne se charge pas à chaque flèche).
    val list0 = shown
    val pick = remember(list0, selected) { list0?.let { tvHeroPick(it, selected) } }
    // Seule une catégorie vide garde l'en-tête simple : un titre sans image de fond prend son affiche.
    val heroOn = spotlightOn && list0 != null && pick != null
    var spot by remember { mutableStateOf<VodItem?>(null) }
    LaunchedEffect(heroOn, focusedIndex, list0, pick) {
        if (!heroOn || list0 == null) {
            spot = null
            return@LaunchedEffect
        }
        val target = (list0.getOrNull(focusedIndex) ?: pick)?.toVodItem(kind)
        if (spot != null && target != spot) delay(300)
        spot = target
    }
    val hero = spot.takeIf { heroOn }

    Box(Modifier.fillMaxSize().background(Bg)) {
    if (hero != null) SectionBackdrop(hero, Modifier.fillMaxSize(), BrowseScrimX, BrowseScrimY)
    Row(Modifier.fillMaxSize().padding(top = dpx(24))) {
        LazyColumn(
            Modifier.width(dpx(RAIL_W)).fillMaxHeight().focusRestorer(),
            state = catList,
            contentPadding = PaddingValues(start = dpx(20), end = dpx(12), bottom = dpx(40)),
            verticalArrangement = Arrangement.spacedBy(dpx(4)),
        ) {
            itemsIndexed(lines, key = { _, it -> it.key }) { index, line ->
                when (line) {
                    is VodCategoryLine.Header -> SectionHeader(line.section.label, first = index == 0)
                    is VodCategoryLine.Entry -> {
                        val cat = line.category
                        val own = remember { FocusRequester() }
                        CategoryRow(
                            cat.title, cat.count, cat.locked, cat.key == selected,
                            Modifier.focusRequester(if (cat.key == selected) catFocus else own),
                            onFocus = { if (!cat.locked) focusedCat = cat.key },
                            onClick = { gate(cat.locked) { vm.select(cat.key) } },
                        )
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = dpx(28), end = dpx(36))) {
            val counter: @Composable () -> Unit = {
                if (!shown.isNullOrEmpty() && focusedIndex >= 0) {
                    Text(
                        "${minOf(focusedIndex + 1, shown.size)} / ${shown.size}", color = Color.White, fontSize = spx(15), fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Surf2).padding(horizontal = dpx(14), vertical = dpx(5)),
                    )
                }
            }
            val count = shown?.let { if (it.size == 1) "1 titre" else "${it.size} titres" }
            if (hero != null) {
                // Hauteur fixe : la grille ne saute pas d'un titre à l'autre.
                Box(Modifier.fillMaxWidth().height(dpx(HERO_H)), contentAlignment = Alignment.TopStart) {
                    SectionInfo(hero, kicker = listOfNotNull(title, count).joinToString(" · ").uppercase(), titleScale = 0.8f, maxWidth = 860)
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
                    Text(title, color = Color.White, fontSize = spx(34), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    count?.let { Text(it, color = Tok.TextLow, fontSize = spx(17), maxLines = 1, modifier = Modifier.weight(1f)) }
                    counter()
                }
            }
            if (!items.isNullOrEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = dpx(14)), horizontalArrangement = Arrangement.spacedBy(dpx(10)), verticalAlignment = Alignment.CenterVertically) {
                    val orders = VodSortOrder.entries
                    DropdownPill("Tri", sort.label, orders.map { it.label }, orders.indexOf(sort), active = sort != VodSortOrder.ALPHABETICAL, Icons.AutoMirrored.Filled.Sort) { vm.setSortOrder(orders[it]) }
                    DropdownPill("Note", RATING_LABELS[ratingIndex], RATING_LABELS, ratingIndex, active = ratingIndex != 0, Icons.Default.Star) { ratingIndex = it }
                    DropdownPill("Année", PERIOD_LABELS[period], PERIOD_LABELS, period, active = period != 0, Icons.Default.CalendarMonth) { period = it }
                    if (filtersOn) {
                        FilterPill("Effacer", active = false, Icons.Default.Refresh) {
                            ratingIndex = 0
                            period = 0
                        }
                    }
                    if (hero != null) {
                        Spacer(Modifier.weight(1f))
                        counter()
                    }
                }
            }
            var gridFocused by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f).fillMaxWidth().onFocusChanged { gridFocused = it.hasFocus }) {
                val list = shown
                when {
                    list == null -> Unit
                    list.isEmpty() && loadingMore -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    list.isEmpty() && filtersOn -> EmptyState("Aucun titre avec ces filtres", "Choisis une autre note ou une autre période.", Icons.Default.Movie, Modifier.fillMaxSize())
                    list.isEmpty() -> EmptyState(emptyTitle(selected), if (selected == Repository.KEY_FAVORITES) "Ajoute un titre aux favoris depuis sa fiche." else "", Icons.Default.Movie, Modifier.fillMaxSize())
                    else -> CompositionLocalProvider(LocalRowFocus provides gridFocused, LocalDimLevel provides 0.72f) { LazyVerticalGrid(
                        columns = GridCells.Fixed(GRID_COLUMNS),
                        state = grid,
                        modifier = Modifier.fillMaxSize().focusRestorer(),
                        contentPadding = PaddingValues(top = dpx(18), bottom = dpx(60)),
                        horizontalArrangement = Arrangement.spacedBy(dpx(20)),
                        verticalArrangement = Arrangement.spacedBy(dpx(22)),
                    ) {
                        itemsIndexed(list, key = { _, it -> it.uid }) { index, card ->
                            val item = remember(card) { card.toVodItem(kind) }
                            TitledPoster(item, Modifier.fillMaxWidth(), onFocus = { focusedIndex = index }, onClick = {
                                LastFocus.pending = item.key
                                onOpen(card.uid)
                            })
                        }
                        if (loadingMore) {
                            item(span = { GridItemSpan(GRID_COLUMNS) }) {
                                Box(Modifier.fillMaxWidth().height(dpx(120)), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(dpx(44))) }
                            }
                        }
                    } }
                }
            }
        }
    }
    }
}

private fun emptyTitle(selected: String?) = when (selected) {
    Repository.KEY_CONTINUE -> "Rien à reprendre pour l'instant"
    Repository.KEY_FAVORITES -> "Aucun favori"
    Repository.KEY_TOP_RATED -> "Aucune note fournie pour l'instant"
    else -> "Aucun contenu dans cette catégorie"
}

/** Titre d'une rubrique de la liste des catégories : « BIBLIOTHÈQUE », « GENRES », « CATÉGORIES ». */
@Composable
private fun SectionHeader(text: String, first: Boolean) {
    Text(
        text.uppercase(), color = Tok.TextLow.copy(alpha = 0.8f), fontSize = spx(14), fontWeight = FontWeight.Bold, letterSpacing = spx(2.2),
        maxLines = 1, modifier = Modifier.padding(start = dpx(18), top = dpx(if (first) 2 else 22), bottom = dpx(8)),
    )
}

@Composable
internal fun CategoryRow(title: String, count: Int, locked: Boolean, selected: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(12))
    val ink = if (f) Tok.FocusInk else if (selected) Accent1 else Color.White
    Row(
        modifier.fillMaxWidth().height(dpx(58)).clip(shape)
            .background(if (f) Tok.FocusFill else if (selected) Accent1.copy(alpha = .14f) else Color.Transparent)
            .then(if (selected && !f) Modifier.border(1.dp, Accent1.copy(alpha = .4f), shape) else Modifier)
            .onFocusChanged {
                if (it.isFocused != f) {
                    f = it.isFocused
                    if (it.isFocused) onFocus()
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = dpx(18)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, color = ink, fontSize = spx(19), fontWeight = if (selected || f) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        when {
            locked -> Icon(Icons.Default.Lock, "Verrouillé", Modifier.size(dpx(24)), tint = if (f) Tok.FocusInk else VyzoColors.Amber)
            // La catégorie ouverte montre son nombre de titres dans une pastille de l'accent.
            count >= 0 && selected && !f -> Text(
                "$count", color = Look.onAccent, fontSize = spx(14), fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Accent1).padding(horizontal = dpx(10), vertical = dpx(2)),
            )
            count >= 0 -> Text("$count", color = if (f) Tok.FocusInk.copy(alpha = 0.7f) else Tok.TextLow, fontSize = spx(16), maxLines = 1)
        }
    }
}

/** Pastille d'un filtre : agrandie et blanche à la sélection, teinte de l'accent quand le filtre est actif. */
@Composable
internal fun FilterPill(text: String, active: Boolean, icon: ImageVector?, modifier: Modifier = Modifier, caret: Boolean = false, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.05f) else 1f, label = "pill")
    val shape = RoundedCornerShape(50)
    val ink = if (f) Tok.FocusInk else if (active) Accent1 else Color.White
    Row(
        modifier.graphicsLayer { scaleX = s; scaleY = s }.height(dpx(50)).clip(shape)
            .background(if (f) Tok.FocusFill else if (active) Accent1.copy(alpha = .16f) else Color(0x22FFFFFF))
            .border(1.dp, if (f) Tok.FocusFill else if (active) Accent1 else Tok.Line, shape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = dpx(20)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dpx(10)),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(dpx(24)), tint = ink)
        Text(text, color = ink, fontSize = spx(17), fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (caret) Icon(Icons.Default.ExpandMore, null, Modifier.size(dpx(22)), tint = ink)
    }
}

/** Pastille qui ouvre la liste de ses valeurs juste en dessous. */
@Composable
private fun DropdownPill(label: String, value: String, options: List<String>, selected: Int, active: Boolean, icon: ImageVector, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterPill(value, active, icon, caret = true) { open = true }
        if (open) OptionPopup(label, options, selected, { open = false }) {
            onPick(it)
            open = false
        }
    }
}

@Composable
private fun OptionPopup(label: String, options: List<String>, selected: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val offset = with(LocalDensity.current) { IntOffset(0, dpx(58).roundToPx()) }
    Popup(alignment = Alignment.TopStart, offset = offset, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val first = remember { FocusRequester() }
        val state = rememberLazyListState(maxOf(selected - 2, 0))
        LaunchedEffect(Unit) { focusWhenReady(first) }
        val shape = RoundedCornerShape(dpx(14))
        Column(
            Modifier.width(dpx(420)).heightIn(max = dpx(560)).clip(shape)
                .background(Surf.copy(alpha = 0.98f)).border(1.dp, Tok.Line, shape).padding(dpx(12)),
        ) {
            Text(label, color = Tok.TextLow, fontSize = spx(15), maxLines = 1, modifier = Modifier.padding(start = dpx(14), bottom = dpx(8)))
            LazyColumn(state = state) {
                itemsIndexed(options) { index, option ->
                    val own = remember { FocusRequester() }
                    OptionRow(option, index == selected, Modifier.focusRequester(if (index == maxOf(selected, 0)) first else own)) { onPick(index) }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(10))
    val ink = if (f) Tok.FocusInk else Color.White
    Row(
        modifier.fillMaxWidth().clip(shape)
            .background(if (f) Tok.FocusFill else Color.Transparent)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = dpx(14), vertical = dpx(13)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(dpx(34))) {
            if (selected) Icon(Icons.Default.Check, null, Modifier.size(dpx(24)), tint = if (f) Tok.FocusInk else Accent1)
        }
        Text(text, color = ink, fontSize = spx(18), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}
