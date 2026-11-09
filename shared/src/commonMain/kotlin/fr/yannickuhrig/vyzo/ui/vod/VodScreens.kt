package fr.yannickuhrig.vyzo.ui.vod

import fr.yannickuhrig.vyzo.ui.common.PosterArt
import fr.yannickuhrig.vyzo.ui.common.isDesktop
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
)

data class VodCategory(val key: String, val title: String, val count: Int = -1, val locked: Boolean = false)

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
            add(VodCategory(Repository.KEY_CONTINUE, "Reprendre"))
            add(VodCategory(Repository.KEY_FAVORITES, "Favoris"))
            add(VodCategory(Repository.KEY_RECENT_ADDED, "Ajoutés récemment"))
            add(VodCategory(Repository.KEY_TOP_RATED, if (kind == ContentKind.MOVIE) "Les mieux notés" else "Les mieux notées"))
            genreCounts.forEach { (id, n) -> add(VodCategory(Repository.GENRE_PREFIX + id, "Genre · ${Genres.label(id)}", n)) }
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
            if (kind == ContentKind.MOVIE) combine(c.repo.movies(key), quality, done) { l, q, p ->
                l.map { VodCard(it.uid, it.name, it.poster, it.rating, it.year, it.addedAt, it.releaseDate, q.isUhd(it.uid, it.name, it.categoryUid), p[it.uid] ?: 0f) }
            }
            else combine(c.repo.series(key), quality, done) { l, q, p ->
                l.map { VodCard(it.uid, it.name, it.poster, it.rating, it.year, it.addedAt, it.releaseDate, q.isUhd(it.uid, it.name, it.categoryUid), p[it.uid] ?: 0f) }
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
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (isDesktop()) 168.dp else if (tv) 148.dp else 106.dp),
                    state = state,
                    // ► depuis les catégories revient sur la dernière affiche parcourue.
                    modifier = Modifier.weight(1f).fillMaxWidth().focusRestorer(),
                    contentPadding = PaddingValues(if (tv) 12.dp else 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (tv) 14.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(if (tv) 16.dp else 10.dp),
                ) {
                    itemsIndexed(items!!, key = { _, it -> it.uid }) { _, card ->
                        PosterCard(card, onClick = { onOpen(card.uid) })
                    }
                    if (loadingMore) {
                        item { Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
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
        Row(Modifier.fillMaxSize().padding(start = 8.dp, top = 16.dp, end = 12.dp)) {
            // ◄ depuis les affiches revient sur la catégorie ouverte.
            LazyColumn(Modifier.width(250.dp).fillMaxHeight().focusRestorer(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(cats, key = { it.key }) { cat ->
                    GroupRow(
                        fr.yannickuhrig.vyzo.data.repo.GroupItem(cat.key, cat.title, fr.yannickuhrig.vyzo.data.repo.GroupKind.CATEGORY, cat.count, cat.locked),
                        selected = cat.key == selected, onClick = { pick(cat) }, onFocus = { if (!cat.locked) focusedCat = cat.key },
                    )
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

@Composable
fun PosterCard(card: VodCard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Tile(onClick = onClick, modifier = modifier, focusScale = 1.06f, hoverScale = 1.05f, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(4.dp)) {
            PosterArt(card.title, card.poster, card.rating, card.uhd, progress = card.progress)
            Text(
                card.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp).height(34.dp),
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
