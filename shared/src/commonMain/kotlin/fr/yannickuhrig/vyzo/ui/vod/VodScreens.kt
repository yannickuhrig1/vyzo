package fr.yannickuhrig.vyzo.ui.vod

import fr.yannickuhrig.vyzo.ui.common.PosterArt
import fr.yannickuhrig.vyzo.ui.common.isDesktop
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.posterColor
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.db.CategoryRow
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.data.vod.VodQualityIndex
import fr.yannickuhrig.vyzo.data.vod.Genres
import fr.yannickuhrig.vyzo.ui.common.EmptyState
import fr.yannickuhrig.vyzo.ui.common.NetImage
import fr.yannickuhrig.vyzo.ui.common.Tile
import fr.yannickuhrig.vyzo.ui.common.appViewModel
import fr.yannickuhrig.vyzo.ui.common.isTv
import fr.yannickuhrig.vyzo.ui.live.GroupRow
import fr.yannickuhrig.vyzo.ui.profile.rememberParentalGate
import androidx.compose.material.icons.filled.Lock
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class VodSortOrder(val group: String, val label: String, val isDesc: Boolean) {
    ALPHABETICAL("alpha", "A-Z", false),
    ALPHABETICAL_DESC("alpha", "Z-A", true),
    ADDED_DESC("added", "Date d'ajout ↓", true),
    ADDED_ASC("added", "Date d'ajout ↑", false),
    RELEASE_DATE_DESC("release", "Date de sortie ↓", true),
    RELEASE_DATE_ASC("release", "Date de sortie ↑", false),
    YEAR_DESC("year", "Année ↓", true),
    YEAR_ASC("year", "Année ↑", false),
    RATING_DESC("rating", "Note ↓", true),
    RATING_ASC("rating", "Note ↑", false);

    fun inverted(): VodSortOrder = when (this) {
        ALPHABETICAL -> ALPHABETICAL_DESC
        ALPHABETICAL_DESC -> ALPHABETICAL
        ADDED_DESC -> ADDED_ASC
        ADDED_ASC -> ADDED_DESC
        RELEASE_DATE_DESC -> RELEASE_DATE_ASC
        RELEASE_DATE_ASC -> RELEASE_DATE_DESC
        YEAR_DESC -> YEAR_ASC
        YEAR_ASC -> YEAR_DESC
        RATING_DESC -> RATING_ASC
        RATING_ASC -> RATING_DESC
    }

    companion object {
        val GROUPS = listOf(ALPHABETICAL, ADDED_DESC, RELEASE_DATE_DESC, YEAR_DESC, RATING_DESC)
    }
}

data class VodCard(
    val uid: String,
    val title: String,
    val poster: String = "",
    val rating: Float = 0f,
    val year: String = "",
    val addedAt: Long = 0L,
    val releaseDate: String = "",
    val uhd: Boolean = false,
    /** Avancement de 0 à 1, dans la section « Reprendre ». */
    val progress: Float = 0f,
    /** Genre principal, en français quand il est reconnu (« Comédie »). */
    val genre: String = "",
    val backdrop: String = "",
    val plot: String = "",
)

/** Rubriques de la liste des catégories : la bibliothèque, les genres automatiques, puis les catégories du fournisseur. */
enum class VodSection(val label: String) {
    LIBRARY("Bibliothèque"),
    GENRES("Genres"),
    CATEGORIES("Catégories"),
}

data class VodCategory(
    val key: String,
    val title: String,
    val count: Int = -1,
    val locked: Boolean = false,
    val section: VodSection = VodSection.CATEGORIES,
)

/** Ligne de la liste des catégories : un titre de rubrique, ou une catégorie. */
sealed interface VodCategoryLine {
    val key: String

    data class Header(val section: VodSection) : VodCategoryLine {
        override val key get() = "rubrique:${section.name}"
    }

    data class Entry(val category: VodCategory) : VodCategoryLine {
        override val key get() = category.key
    }
}

/** Catégories précédées du titre de leur rubrique, dès que la rubrique change. */
fun vodCategoryLines(cats: List<VodCategory>): List<VodCategoryLine> = buildList {
    cats.forEachIndexed { i, cat ->
        if (i == 0 || cats[i - 1].section != cat.section) add(VodCategoryLine.Header(cat.section))
        add(VodCategoryLine.Entry(cat))
    }
}

/** Le titre a de quoi remplir le bandeau : une image de fond ou un résumé. */
fun VodCard.fillsSpotlight(): Boolean = backdrop.isNotBlank() || plot.isNotBlank()

/**
 * Titre du bandeau tant que rien n'est sélectionné : le premier de « Reprendre » (le plus récent),
 * ailleurs le mieux noté qui a une image de fond, sinon un résumé. Pas de bandeau sous 3 titres (il répéterait
 * la grille), ni quand aucun titre n'a d'image de fond ou de résumé (il n'afficherait qu'un nom sur un aplat).
 */
fun vodSpotlightPick(items: List<VodCard>, selected: String?): VodCard? {
    if (items.size < 3) return null
    val rich = items.filter { it.fillsSpotlight() }
    if (rich.isEmpty()) return null
    if (selected == Repository.KEY_CONTINUE) return rich.first()
    return rich.filter { it.backdrop.isNotBlank() && it.rating <= 10f }.maxByOrNull { it.rating }
        ?: rich.filter { it.rating <= 10f }.maxByOrNull { it.rating }
        ?: rich.first()
}

/** « 2026 · Comédie » : année et genre sous une affiche. */
fun vodCardMeta(year: String, genre: String): String =
    listOf(year.take(4), genre).filter { it.isNotBlank() }.joinToString(" · ")

@OptIn(ExperimentalCoroutinesApi::class)
class VodViewModel(private val c: AppContainer, val kind: ContentKind) : ViewModel() {
    private val rows: StateFlow<List<CategoryRow>> =
        (if (kind == ContentKind.MOVIE) c.repo.movieCategories() else c.repo.seriesCategories())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Genres automatiques (réglage « Genres automatiques »), avec le nombre de titres de chacun. */
    private val genres: kotlinx.coroutines.flow.Flow<List<Pair<String, Int>>> = c.settings.flow
        .map { it.autoGenres }
        .distinctUntilChanged()
        .flatMapLatest { on ->
            if (!on) kotlinx.coroutines.flow.flowOf(emptyList())
            else c.repo.vodGenres(kind).map { idx ->
                idx.map { (id, uids) -> id to uids.size }.sortedBy { Genres.label(it.first) }
            }
        }

    val categories: StateFlow<List<VodCategory>> = combine(rows, genres) { list, genreCounts ->
        buildList {
            val lib = VodSection.LIBRARY
            add(VodCategory(Repository.KEY_CONTINUE, "Reprendre", section = lib))
            add(VodCategory(Repository.KEY_FAVORITES, "Favoris", section = lib))
            add(VodCategory(Repository.KEY_RECENT_ADDED, "Ajoutés récemment", section = lib))
            add(VodCategory(Repository.KEY_TOP_RATED, if (kind == ContentKind.MOVIE) "Les mieux notés" else "Les mieux notées", section = lib))
            // Le titre de rubrique « Genres » remplace l'ancien préfixe « Genre · », qui coupait les noms longs.
            genreCounts.forEach { (id, n) -> add(VodCategory(Repository.GENRE_PREFIX + id, Genres.label(id), n, section = VodSection.GENRES)) }
            list.filter { !it.hidden }.forEach { add(VodCategory("cat:${it.uid}", it.name, it.itemCount, it.locked)) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val chosen = MutableStateFlow<String?>(null)

    val selected: StateFlow<String?> = combine(chosen, rows) { sel, list ->
        sel ?: list.firstOrNull { !it.hidden && !it.locked }?.let { "cat:${it.uid}" }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val sortOrder = MutableStateFlow(VodSortOrder.ALPHABETICAL)

    fun toggleSortOrder(target: VodSortOrder) {
        if (sortOrder.value.group == target.group) {
            sortOrder.value = sortOrder.value.inverted()
        } else {
            sortOrder.value = target
        }
    }

    fun setSortOrder(order: VodSortOrder) {
        sortOrder.value = order
    }

    fun moveCategoryUp(categoryUid: String) {
        moveCategory(categoryUid, -1)
    }

    fun moveCategoryDown(categoryUid: String) {
        moveCategory(categoryUid, 1)
    }

    fun moveCategory(categoryUid: String, delta: Int) = viewModelScope.launch {
        val rawUid = categoryUid.removePrefix("cat:")
        c.repo.moveCategory(kind, rawUid, delta)
    }

    fun moveGroupUp(groupId: String) = moveCategoryUp(groupId)
    fun moveGroupDown(groupId: String) = moveCategoryDown(groupId)

    private val quality = c.repo.vodQuality().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VodQualityIndex())

    private val rawItems: StateFlow<List<VodCard>?> = selected.flatMapLatest { key ->
        if (key == null) kotlinx.coroutines.flow.flowOf(emptyList())
        else {
            val done = if (key == Repository.KEY_CONTINUE) c.repo.resumeFractions() else kotlinx.coroutines.flow.flowOf(emptyMap())
            // Les mêmes champs « genre » reviennent des milliers de fois : chacun n'est analysé qu'une fois par liste.
            fun labels() = HashMap<String, String>()
            if (kind == ContentKind.MOVIE) combine(c.repo.movies(key), quality, done) { l, q, p ->
                val g = labels()
                l.map {
                    VodCard(
                        it.uid, it.name, it.poster, it.rating, it.year, it.addedAt, it.releaseDate, q.isUhd(it.uid, it.name, it.categoryUid), p[it.uid] ?: 0f,
                        g.getOrPut(it.genre) { Genres.primaryLabel(it.genre) }, it.backdrop, it.plot,
                    )
                }
            }
            else combine(c.repo.series(key), quality, done) { l, q, p ->
                val g = labels()
                l.map {
                    VodCard(
                        it.uid, it.name, it.poster, it.rating, it.year, it.addedAt, it.releaseDate, q.isUhd(it.uid, it.name, it.categoryUid), p[it.uid] ?: 0f,
                        g.getOrPut(it.genre) { Genres.primaryLabel(it.genre) }, it.backdrop, it.plot,
                    )
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val items: StateFlow<List<VodCard>?> = combine(rawItems, sortOrder) { list, sort ->
        if (list == null) null
        else when (sort) {
            VodSortOrder.ALPHABETICAL -> list.sortedBy { it.title.lowercase() }
            VodSortOrder.ALPHABETICAL_DESC -> list.sortedByDescending { it.title.lowercase() }
            VodSortOrder.ADDED_DESC -> list.sortedByDescending { it.addedAt }
            VodSortOrder.ADDED_ASC -> list.sortedWith(
                compareBy<VodCard> { it.addedAt == 0L }
                    .thenBy { it.addedAt }
                    .thenBy { it.title.lowercase() },
            )
            VodSortOrder.RELEASE_DATE_DESC -> list.sortedWith(
                compareByDescending<VodCard> { it.releaseDate.isNotBlank() }
                    .thenByDescending { it.releaseDate }
                    .thenBy { it.title.lowercase() },
            )
            VodSortOrder.RELEASE_DATE_ASC -> list.sortedWith(
                compareBy<VodCard> { it.releaseDate.isBlank() }
                    .thenBy { it.releaseDate }
                    .thenBy { it.title.lowercase() },
            )
            VodSortOrder.YEAR_DESC -> list.sortedWith(
                compareByDescending<VodCard> { it.year.toIntOrNull() ?: 0 }
                    .thenBy { it.title.lowercase() },
            )
            VodSortOrder.YEAR_ASC -> list.sortedWith(
                compareBy<VodCard> { (it.year.toIntOrNull() ?: 0) == 0 }
                    .thenBy { it.year.toIntOrNull() ?: 0 }
                    .thenBy { it.title.lowercase() },
            )
            VodSortOrder.RATING_DESC -> list.sortedWith(
                compareByDescending<VodCard> { it.rating }
                    .thenBy { it.title.lowercase() },
            )
            VodSortOrder.RATING_ASC -> list.sortedWith(
                compareBy<VodCard> { it.rating == 0f }
                    .thenBy { it.rating }
                    .thenBy { it.title.lowercase() },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val loadingMore = MutableStateFlow(false)
    private val exhausted = HashSet<String>()

    fun select(key: String) {
        // « Les mieux notés » n'a de sens que trié par note.
        if (key == Repository.KEY_TOP_RATED && key != chosen.value) sortOrder.value = VodSortOrder.RATING_DESC
        chosen.value = key
        maybeLoadMore(key, initial = true)
    }

    fun nearEnd() {
        selected.value?.let { maybeLoadMore(it, initial = false) }
    }

    private fun maybeLoadMore(key: String, initial: Boolean) {
        if (!key.startsWith("cat:") || loadingMore.value || key in exhausted) return
        val uid = key.removePrefix("cat:")
        viewModelScope.launch {
            if (!c.repo.isStalkerCategory(uid)) return@launch
            if (initial) {
                val count = if (kind == ContentKind.MOVIE) c.db.movies().countInCategory(uid) else c.db.series().countInCategory(uid)
                if (count > 0) return@launch
            }
            loadingMore.value = true
            val more = runCatching { c.repo.loadMoreVod(uid) }.getOrDefault(false)
            if (!more) exhausted += key
            loadingMore.value = false
        }
    }
}

@Composable
fun VodBrowser(kind: ContentKind, onOpen: (String) -> Unit) {
    val vm = appViewModel(key = "vod-$kind") { VodViewModel(it, kind) }
    val cats by vm.categories.collectAsState()
    val selected by vm.selected.collectAsState()
    val items by vm.items.collectAsState()
    val sortOrder by vm.sortOrder.collectAsState()
    val loadingMore by vm.loadingMore.collectAsState()
    val settings = LocalContainer.current.settings
    val spotlightOn by remember(settings) { settings.flow.map { it.vodSpotlight }.distinctUntilChanged() }.collectAsState(true)
    val tv = isTv()
    val gate = rememberParentalGate()
    val pick: (VodCategory) -> Unit = { cat -> gate(cat.locked) { vm.select(cat.key) } }

    LaunchedEffect(selected) { selected?.let { vm.select(it) } }

    val grid: @Composable (Modifier) -> Unit = { mod ->
        val state = rememberLazyGridState()
        val nearEnd by remember { derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - 12 } }
        LaunchedEffect(nearEnd, items?.size) { if (nearEnd) vm.nearEnd() }
        LaunchedEffect(selected, sortOrder) { state.scrollToItem(0) }
        Column(mod) {
            if (items != null && items!!.isNotEmpty()) {
                VodSortBar(
                    selected = sortOrder,
                    onSelect = { vm.toggleSortOrder(it) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = if (tv) 12.dp else 8.dp, vertical = 2.dp),
                )
            }
            when {
                items == null -> Box(Modifier.fillMaxSize())
                items!!.isEmpty() && loadingMore -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                items!!.isEmpty() -> EmptyState(
                    when (selected) {
                        Repository.KEY_CONTINUE -> "Rien à reprendre pour l'instant"
                        Repository.KEY_FAVORITES -> "Aucun favori"
                        Repository.KEY_TOP_RATED -> "Aucune note fournie pour l'instant"
                        else -> "Aucun contenu dans cette catégorie"
                    },
                    if (selected == Repository.KEY_FAVORITES) "Ajoute un titre aux favoris depuis sa fiche." else "",
                    Icons.Default.Movie, Modifier.fillMaxSize(),
                )
                else -> {
                    val list = items!!
                    val featured = remember(list, selected, spotlightOn) { if (spotlightOn) vodSpotlightPick(list, selected) else null }
                    val eyebrow = if (selected == Repository.KEY_CONTINUE) "Reprendre" else "À l'affiche · " + (cats.firstOrNull { it.key == selected }?.title ?: "")
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (isDesktop()) 168.dp else if (tv) 148.dp else 106.dp),
                        state = state,
                        // ► depuis les catégories revient sur la dernière affiche parcourue.
                        modifier = Modifier.weight(1f).fillMaxWidth().focusRestorer(),
                        contentPadding = PaddingValues(if (tv) 12.dp else 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (tv) 14.dp else 8.dp),
                        verticalArrangement = Arrangement.spacedBy(if (tv) 16.dp else 10.dp),
                    ) {
                        // Le bandeau défile avec les affiches : il ne prend pas de place une fois qu'on descend.
                        if (featured != null) {
                            item(key = "mise-en-avant", span = { GridItemSpan(maxLineSpan) }) {
                                VodSpotlight(featured, eyebrow, onOpen = { onOpen(featured.uid) }, Modifier.padding(bottom = 6.dp))
                            }
                        }
                        itemsIndexed(list, key = { _, it -> it.uid }) { _, card ->
                            PosterCard(card, onClick = { onOpen(card.uid) })
                        }
                        if (loadingMore) {
                            item { Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
                        }
                    }
                }
            }
        }
    }

    if (tv) {
        var focusedCat by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(focusedCat) {
            val k = focusedCat ?: return@LaunchedEffect
            delay(300)
            vm.select(k)
        }
        val lines = remember(cats) { vodCategoryLines(cats) }
        Row(Modifier.fillMaxSize().padding(start = 8.dp, top = 8.dp, end = 12.dp)) {
            // ◄ depuis les affiches revient sur la catégorie ouverte.
            LazyColumn(Modifier.width(250.dp).fillMaxHeight().focusRestorer(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(lines, key = { it.key }) { line ->
                    when (line) {
                        is VodCategoryLine.Header -> VodSectionHeader(line.section.label, first = line === lines.first())
                        is VodCategoryLine.Entry -> {
                            val cat = line.category
                            VodCategoryRow(cat, selected = cat.key == selected, onClick = { pick(cat) }, onFocus = { if (!cat.locked) focusedCat = cat.key })
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            grid(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            val chips = rememberLazyListState()
            LazyRow(state = chips, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cats, key = { it.key }) { cat ->
                    FilterChip(
                        selected = cat.key == selected, onClick = { pick(cat) }, label = { Text(cat.title, maxLines = 1) },
                        leadingIcon = if (cat.locked) ({ Icon(Icons.Default.Lock, "Verrouillé", Modifier.size(16.dp)) }) else null,
                    )
                }
            }
            grid(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** Titre d'une rubrique de la liste des catégories (« BIBLIOTHÈQUE », « GENRES »…). */
@Composable
private fun VodSectionHeader(text: String, first: Boolean) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
        color = VyzoColors.TextDim.copy(alpha = 0.75f), maxLines = 1,
        modifier = Modifier.padding(start = 14.dp, top = if (first) 4.dp else 18.dp, bottom = 6.dp),
    )
}

/** Catégorie de la liste : la catégorie ouverte est teintée de l'accent, son nombre de titres dans une pastille. */
@Composable
private fun VodCategoryRow(cat: VodCategory, selected: Boolean, onClick: () -> Unit, onFocus: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Tile(
        onClick = onClick, onFocus = onFocus, shape = shape,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp), focusScale = 1.02f,
        color = if (selected) VyzoColors.Amber.copy(alpha = 0.1f) else Color.Transparent,
    ) { focused ->
        Row(
            Modifier.fillMaxWidth()
                .then(if (selected && !focused) Modifier.border(1.dp, VyzoColors.Amber.copy(alpha = 0.35f), shape) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                cat.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                color = if (selected && !focused) VyzoColors.Amber else Color.Unspecified,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            when {
                cat.locked -> Icon(Icons.Default.Lock, "Verrouillé", Modifier.size(16.dp), tint = VyzoColors.Amber)
                cat.count >= 0 && selected -> Text(
                    "${cat.count}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = VyzoColors.OnAmber, maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp).background(VyzoColors.Amber, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.dp),
                )
                cat.count >= 0 -> Text("${cat.count}", style = MaterialTheme.typography.labelMedium, color = VyzoColors.TextDim, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * Bandeau du titre mis en avant, en tête de la grille : image de fond fondue vers la gauche,
 * étiquette, note, résumé et, sur grand écran, l'affiche à droite. Un clic ouvre la fiche.
 */
@Composable
fun VodSpotlight(card: VodCard, eyebrow: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val wide = isTv() || isDesktop()
    val shape = RoundedCornerShape(16.dp)
    val base = remember(card.title, card.genre) { posterColor(card.title, card.genre) }
    val bg = VyzoColors.Background
    Tile(
        onClick = onOpen, modifier = modifier.fillMaxWidth(), shape = shape, focusScale = 1.01f, hoverScale = 1.005f,
        color = Color.Transparent, focusedColor = Color.Transparent,
    ) { focused ->
        Box(
            Modifier.fillMaxWidth().height(if (wide) 220.dp else 170.dp).clip(shape)
                .background(Brush.linearGradient(listOf(lerp(base, Color.Black, 0.55f), VyzoColors.Surface, lerp(base, Color.Black, 0.8f))))
                .border(if (focused) 2.dp else 1.dp, if (focused) VyzoColors.Amber else Color.White.copy(alpha = 0.08f), shape),
        ) {
            if (card.backdrop.isNotBlank()) {
                NetImage(
                    card.backdrop, Modifier.fillMaxHeight().fillMaxWidth(if (wide) 0.66f else 1f).align(Alignment.CenterEnd),
                    contentScale = ContentScale.Crop,
                )
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to bg, if (wide) 0.4f to bg.copy(alpha = 0.85f) else 0.55f to bg.copy(alpha = 0.7f), 1f to bg.copy(alpha = 0.15f))))
            }
            // Halo de la couleur du genre, en haut à gauche.
            Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(base.copy(alpha = 0.28f), Color.Transparent), radius = 700f)))
            Row(Modifier.fillMaxSize().padding(horizontal = if (wide) 28.dp else 16.dp, vertical = if (wide) 20.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (wide) 8.dp else 5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = VyzoColors.OnAmber, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).background(VyzoColors.Amber, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                        if (card.uhd) {
                            Text(
                                "4K", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1,
                                modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(5.dp)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(5.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                        if (card.rating > 0f && card.rating <= 10f) {
                            Icon(Icons.Default.Star, null, Modifier.size(14.dp), tint = VyzoColors.Amber)
                            Text("%.1f".format(card.rating), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = VyzoColors.Amber)
                        }
                    }
                    Text(
                        card.title, style = if (wide) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val meta = vodCardMeta(card.year, card.genre)
                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelMedium, color = VyzoColors.TextDim, maxLines = 1)
                    if (card.plot.isNotBlank()) {
                        Text(
                            card.plot, style = if (wide) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                            color = VyzoColors.Text.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(if (wide) 0.75f else 1f),
                        )
                    }
                }
                if (wide) {
                    Spacer(Modifier.width(24.dp))
                    PosterArt(card.title, card.poster, 0f, false, Modifier.width(118.dp).shadow(18.dp, RoundedCornerShape(8.dp)), genre = card.genre)
                }
            }
        }
    }
}

@Composable
fun PosterCard(card: VodCard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Tile(onClick = onClick, modifier = modifier, focusScale = 1.06f, hoverScale = 1.05f, shape = RoundedCornerShape(10.dp)) { focused ->
        Column(Modifier.padding(4.dp)) {
            PosterArt(card.title, card.poster, card.rating, card.uhd, progress = card.progress, genre = card.genre)
            // Le titre seul sur sa ligne, l'année et le genre en dessous : les lignes restent alignées d'une carte à l'autre.
            Text(
                card.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (focused && isTv()) VyzoColors.Amber else Color.Unspecified,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
            )
            Text(
                vodCardMeta(card.year, card.genre), style = MaterialTheme.typography.labelSmall, color = VyzoColors.TextDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 2.dp).height(16.dp),
            )
        }
    }
}

@Composable
fun VodSortBar(
    selected: VodSortOrder,
    onSelect: (VodSortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = "Trier par",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(VodSortOrder.GROUPS) { defaultOrder ->
            val isCurrentGroup = selected.group == defaultOrder.group
            val currentOrder = if (isCurrentGroup) selected else defaultOrder
            FilterChip(
                selected = isCurrentGroup,
                onClick = { onSelect(defaultOrder) },
                label = { Text(currentOrder.label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}
