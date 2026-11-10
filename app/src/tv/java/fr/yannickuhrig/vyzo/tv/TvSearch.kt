// Dérivé de SearchScreen.kt et Ui.kt d'IPVIO Stream, Copyright (C) 2026 IPVIO Stream contributors, GNU GPL 3.
// Modifié pour Vyzo le 2026-11-08 : recherche de Vyzo (profils, contrôle parental, guide TV), le clavier ne s'ouvre qu'à la demande.
package fr.yannickuhrig.vyzo.tv

import fr.yannickuhrig.vyzo.ui.vod.vodCardMeta
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.MovieEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.data.repo.SearchResults
import fr.yannickuhrig.vyzo.ui.SearchActions
import fr.yannickuhrig.vyzo.ui.common.Badge
import fr.yannickuhrig.vyzo.ui.common.EmptyState
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.PlatformBackHandler
import fr.yannickuhrig.vyzo.ui.common.ProgramMenu
import fr.yannickuhrig.vyzo.ui.common.appViewModel
import fr.yannickuhrig.vyzo.ui.common.rememberVoiceSearch
import fr.yannickuhrig.vyzo.ui.home.ChannelItem
import fr.yannickuhrig.vyzo.ui.home.VodItem
import fr.yannickuhrig.vyzo.ui.search.SearchViewModel
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.delay

private val PageX: Dp get() = dpx(48)

internal fun MovieEntity.toVodItem() = VodItem(ContentKind.MOVIE, uid, name, poster, backdrop, rating, year, genre, plot)

internal fun SeriesEntity.toVodItem() = VodItem(ContentKind.SERIES, uid, name, poster, backdrop, rating, year, genre, plot)

/** Vrai quand une recherche a tourné et n'a rien trouvé. */
internal fun SearchResults.isEmpty() = channels.isEmpty() && movies.isEmpty() && series.isEmpty() && programs.isEmpty()

/** Au retour d'une fiche, laisse la carte quittée reprendre le focus ; sinon, ou si elle n'est plus là, va sur [target]. */
internal suspend fun focusOnArrival(target: FocusRequester) {
    if (LastFocus.pending != null) {
        delay(400)
        if (LastFocus.pending == null) return
        LastFocus.pending = null
    }
    focusWhenReady(target)
}

@Composable
fun TvSearch(actions: SearchActions) {
    val vm = appViewModel { SearchViewModel(it) }
    val c = LocalContainer.current
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val now by vm.now.collectAsState()
    val voice = rememberVoiceSearch { vm.query.value = it }
    var programDialog by remember { mutableStateOf<Pair<ChannelEntity, ProgramEntity>?>(null) }

    val field = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusOnArrival(field) }

    val typed = query.trim().length >= 2
    val found = results?.takeIf { typed }

    // Rien de saisi : ce qu'on regardait, sans le retaper à la télécommande.
    val continueMovies by remember { c.repo.movies(Repository.KEY_CONTINUE, 30) }.collectAsState(emptyList())
    val continueSeries by remember { c.repo.series(Repository.KEY_CONTINUE, 30) }.collectAsState(emptyList())
    val recentChannels by remember { c.repo.channels(Repository.KEY_RECENT) }.collectAsState(emptyList())
    val recentNow by produceState(emptyMap<String, ProgramEntity>(), recentChannels) {
        value = c.repo.nowPrograms(recentChannels.map { it.epgKey })
    }

    val openVod: (VodItem) -> Unit = {
        LastFocus.pending = it.key
        if (it.kind == ContentKind.MOVIE) actions.onMovie(it.uid) else actions.onSeries(it.uid)
    }
    val openChannel: (ChannelItem) -> Unit = {
        LastFocus.pending = it.key
        actions.onChannel(it.channel)
    }

    programDialog?.let { (ch, p) ->
        ProgramMenu(ch, p, onDismiss = { programDialog = null }, onWatchLive = { actions.onChannel(ch) }, onCatchup = { actions.onCatchup(ch, p) })
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = dpx(32), bottom = dpx(60))) {
            item(key = "field") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = PageX, vertical = dpx(8)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dpx(14)),
                ) {
                    SearchField(
                        query, { vm.query.value = it }, field, Modifier.weight(1f),
                        onSubmit = { found?.takeIf { !it.isEmpty() }?.let { runCatching { firstResult.requestFocus() }.isSuccess } ?: false },
                    )
                    if (query.isNotEmpty()) HeroButton("Effacer", Icons.Default.Close, primary = false) { vm.query.value = "" }
                    if (voice != null) HeroButton("Parler", Icons.Default.Mic, primary = false, onClick = voice)
                }
            }
            when {
                !typed -> {
                    if (continueMovies.isEmpty() && continueSeries.isEmpty() && recentChannels.isEmpty()) {
                        item(key = "hint") {
                            EmptyState("Rechercher", "Une chaîne, un programme du guide, un film ou une série.", Icons.Default.Search, Modifier.fillMaxWidth())
                        }
                    }
                    posterRow("resume-m", "Reprendre · Films", continueMovies.map { it.toVodItem() }, null, openVod)
                    posterRow("resume-s", "Reprendre · Séries", continueSeries.map { it.toVodItem() }, null, openVod)
                    channelRow("recent", "Dernières chaînes", recentChannels.take(30), recentNow, null, openChannel)
                }
                found == null -> Unit
                found.isEmpty() -> item(key = "none") {
                    EmptyState("Aucun résultat pour « ${query.trim()} »", "Vérifie l'orthographe ou cherche une partie du titre.", Icons.Default.Search, Modifier.fillMaxWidth())
                }
                else -> {
                    var first: FocusRequester? = firstResult
                    fun takeFirst(present: Boolean): FocusRequester? = if (present) first.also { first = null } else null
                    posterRow("movies", "Films", found.movies.map { it.toVodItem() }, takeFirst(found.movies.isNotEmpty()), openVod)
                    posterRow("series", "Séries", found.series.map { it.toVodItem() }, takeFirst(found.series.isNotEmpty()), openVod)
                    channelRow("channels", "Chaînes", found.channels, now, takeFirst(found.channels.isNotEmpty()), openChannel)
                    programRow(found.programs, takeFirst(found.programs.isNotEmpty())) { programDialog = it }
                }
            }
        }
    }
}

/**
 * Champ de recherche. Tant qu'on ne valide pas, c'est un simple bouton : l'appli n'ouvre pas le clavier de la TV
 * dès qu'on passe dessus, et le clavier ne cache pas les résultats.
 */
@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    requester: FocusRequester,
    modifier: Modifier,
    onSubmit: () -> Boolean,
) {
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    val input = remember { FocusRequester() }
    val shape = RoundedCornerShape(dpx(16))
    LaunchedEffect(editing) { if (editing) focusWhenReady(input) }
    PlatformBackHandler(enabled = editing) {
        editing = false
        runCatching { requester.requestFocus() }
    }
    val lit = focused || inputFocused
    Row(
        modifier.height(dpx(66)).clip(shape).background(Surf)
            .border(2.dp, if (lit) Tok.FocusFill else Surf2, shape)
            // ▼ va aux résultats, même pendant la saisie où le champ garderait la touche pour lui.
            .onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && onSubmit() }
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .clickable { editing = true }
            .padding(horizontal = dpx(22)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, Modifier.size(dpx(30)), tint = if (lit) Tok.TextHi else Tok.TextLow)
        Spacer(Modifier.width(dpx(14)))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    if (editing) "Tape au moins deux lettres" else "Chaîne, programme, film, série… (OK pour écrire)",
                    color = Tok.TextLow, fontSize = spx(22), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (editing) {
                BasicTextField(
                    value = value, onValueChange = onChange, singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = spx(22)),
                    cursorBrush = SolidColor(Accent1),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        val moved = onSubmit()
                        editing = false
                        if (!moved) runCatching { requester.requestFocus() }
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(input).onFocusChanged {
                        // Le focus part vers les résultats (▼) : le champ redevient un bouton.
                        if (inputFocused && !it.isFocused) editing = false
                        inputFocused = it.isFocused
                    },
                )
            } else if (value.isNotEmpty()) {
                Text(value, color = Color.White, fontSize = spx(22), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun RowHeading(title: String, count: Int?) {
    Row(
        Modifier.padding(start = PageX, top = dpx(24)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dpx(10)),
    ) {
        Box(Modifier.width(dpx(4)).height(dpx(22)).clip(RoundedCornerShape(50)).background(Accent1))
        Text(title, color = Color.White, fontSize = spx(24), fontWeight = FontWeight.Bold, maxLines = 1)
        if (count != null) Text("$count", color = Tok.TextLow, fontSize = spx(18), maxLines = 1)
    }
}

/** Année et note sous une affiche ; vide si le catalogue ne les donne pas. */
/** Affiche, puis titre, année et genre dessous : à 3 m, une affiche seule ne dit pas toujours quel film c'est. */
@Composable
internal fun TitledPoster(item: VodItem, modifier: Modifier, onFocus: () -> Unit = {}, onClick: () -> Unit, requester: FocusRequester? = null) {
    var f by remember { mutableStateOf(false) }
    Column(modifier.onFocusChanged { f = it.hasFocus }) {
        PosterTile(
            item,
            Modifier.fillMaxWidth().restoreFocus(item.key).then(if (requester != null) Modifier.focusRequester(requester) else Modifier),
            onFocus, onClick, titleInside = false,
        )
        Text(
            item.title, color = if (f) Accent1 else Tok.TextHi, fontSize = spx(15), fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = dpx(12)),
        )
        Text(vodCardMeta(item.year, item.genre), color = Tok.TextLow, fontSize = spx(13), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.posterRow(
    id: String,
    title: String,
    list: List<VodItem>,
    firstFocus: FocusRequester?,
    open: (VodItem) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = id) {
        Column {
            RowHeading(title, list.size)
            LazyRow(
                contentPadding = PaddingValues(horizontal = PageX, vertical = dpx(18)),
                horizontalArrangement = Arrangement.spacedBy(dpx(18)),
            ) {
                items(list, key = { it.key }) { item ->
                    TitledPoster(item, Modifier.width(dpx(180)), onClick = { open(item) }, requester = firstFocus.takeIf { item === list.first() })
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.channelRow(
    id: String,
    title: String,
    list: List<ChannelEntity>,
    now: Map<String, ProgramEntity>,
    firstFocus: FocusRequester?,
    open: (ChannelItem) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = id) {
        Column {
            RowHeading(title, list.size)
            LazyRow(
                contentPadding = PaddingValues(horizontal = PageX, vertical = dpx(18)),
                horizontalArrangement = Arrangement.spacedBy(dpx(18)),
            ) {
                items(list, key = { it.uid }) { ch ->
                    val item = ChannelItem(ch, now[ch.epgKey], Repository.KEY_ALL)
                    ChannelNowCard(
                        item,
                        Modifier.width(dpx(270)).restoreFocus(item.key).then(if (firstFocus != null && ch === list.first()) Modifier.focusRequester(firstFocus) else Modifier),
                        onFocus = {}, onClick = { open(item) }, onLongClick = {},
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.programRow(
    list: List<Pair<ProgramEntity, ChannelEntity>>,
    firstFocus: FocusRequester?,
    open: (Pair<ChannelEntity, ProgramEntity>) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "programs") {
        Column {
            RowHeading("Dans le guide TV", list.size)
            LazyRow(
                contentPadding = PaddingValues(horizontal = PageX, vertical = dpx(18)),
                horizontalArrangement = Arrangement.spacedBy(dpx(18)),
            ) {
                items(list, key = { "p" + it.first.id }) { pair ->
                    val (p, ch) = pair
                    ProgramCard(
                        p, ch,
                        Modifier.width(dpx(400)).then(if (firstFocus != null && pair === list.first()) Modifier.focusRequester(firstFocus) else Modifier),
                    ) { open(ch to p) }
                }
            }
        }
    }
}

@Composable
private fun ProgramCard(p: ProgramEntity, ch: ChannelEntity, modifier: Modifier, onClick: () -> Unit) {
    val t = System.currentTimeMillis()
    CardFrame(modifier.height(dpx(116)), SolidColor(Surf), 1.05f, 10, {}, onClick) {
        Column(Modifier.fillMaxWidth().padding(horizontal = dpx(16), vertical = dpx(12))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, color = Color.White, fontSize = spx(18), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (p.start <= t && p.stop > t) Badge("EN DIRECT", VyzoColors.Red)
            }
            Text(Fmt.dayTime(p.start), color = Accent2, fontSize = spx(15), maxLines = 1, modifier = Modifier.padding(top = dpx(6)))
            Text(ch.name, color = Tok.TextLow, fontSize = spx(14), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
