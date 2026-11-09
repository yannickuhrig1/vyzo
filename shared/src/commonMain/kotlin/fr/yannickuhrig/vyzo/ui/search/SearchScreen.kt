package fr.yannickuhrig.vyzo.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.repo.SearchResults
import fr.yannickuhrig.vyzo.ui.common.EmptyState
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.ProgramMenu
import fr.yannickuhrig.vyzo.ui.common.SectionTitle
import fr.yannickuhrig.vyzo.ui.common.Tile
import fr.yannickuhrig.vyzo.ui.common.appViewModel
import fr.yannickuhrig.vyzo.ui.common.isDesktop
import fr.yannickuhrig.vyzo.ui.common.isTv
import fr.yannickuhrig.vyzo.ui.common.rememberVoiceSearch
import fr.yannickuhrig.vyzo.ui.live.ChannelRow
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import fr.yannickuhrig.vyzo.ui.vod.PosterCard
import fr.yannickuhrig.vyzo.ui.vod.VodCard
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val results = MutableStateFlow<SearchResults?>(null)
    val now = MutableStateFlow<Map<String, ProgramEntity>>(emptyMap())

    init {
        viewModelScope.launch {
            query.debounce(350).distinctUntilChanged().mapLatest { q ->
                if (q.trim().length < 2) null else c.repo.search(q)
            }.collect { r ->
                results.value = r
                now.value = r?.channels?.let { list -> c.repo.nowPrograms(list.map { it.epgKey }) }.orEmpty()
            }
        }
    }
}

@Composable
fun SearchScreen(
    onChannel: (ChannelEntity) -> Unit,
    onMovie: (String) -> Unit,
    onSeries: (String) -> Unit,
    onCatchup: (ChannelEntity, ProgramEntity) -> Unit,
) {
    val vm = appViewModel { SearchViewModel(it) }
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val now by vm.now.collectAsState()
    val tv = isTv()
    val focus = remember { FocusRequester() }
    var programDialog by remember { mutableStateOf<Pair<ChannelEntity, ProgramEntity>?>(null) }

    val voice = rememberVoiceSearch { vm.query.value = it }
    val desktop = isDesktop()

    LaunchedEffect(Unit) {
        delay(150)
        // Sur PC, on tape directement au clavier.
        if (!tv || desktop) runCatching { focus.requestFocus() }
    }

    programDialog?.let { (ch, p) ->
        ProgramMenu(ch, p, onDismiss = { programDialog = null }, onWatchLive = { onChannel(ch) }, onCatchup = { onCatchup(ch, p) })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = if (tv) 24.dp else 12.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { vm.query.value = it },
                placeholder = { Text("Chaîne, programme, film, série…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, "Effacer") } }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            if (voice != null) {
                Spacer(Modifier.width(8.dp))
                Tile(onClick = voice, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Icon(Icons.Default.Mic, "Recherche vocale", Modifier.padding(14.dp))
                }
            }
        }

        val r = results
        var allChannels by remember(r) { mutableStateOf(false) }
        when {
            query.trim().length < 2 -> EmptyState("Rechercher", "Tape au moins deux lettres. La recherche porte sur la playlist active et le guide TV.", Icons.Default.Search)
            r == null -> Unit
            r.channels.isEmpty() && r.movies.isEmpty() && r.series.isEmpty() && r.programs.isEmpty() ->
                EmptyState("Aucun résultat pour « $query »", icon = Icons.Default.Search)
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                if (r.channels.isNotEmpty()) {
                    val more = if (r.channels.size >= Repository.MAX_SEARCH_CHANNELS) "+" else ""
                    item { SectionTitle("Chaînes (${r.channels.size}$more)") }
                    items(if (allChannels) r.channels else r.channels.take(20), key = { "c" + it.uid }) { ch ->
                        ChannelRow(ch, now[ch.epgKey], playing = false, showNumber = true, onClick = { onChannel(ch) }, onLongClick = {})
                    }
                    if (!allChannels && r.channels.size > 20) {
                        item {
                            androidx.compose.material3.TextButton(onClick = { allChannels = true }) {
                                Text("Voir les ${r.channels.size}$more chaînes")
                            }
                        }
                    }
                }
                if (r.programs.isNotEmpty()) {
                    item { SectionTitle("Programmes (${r.programs.size})") }
                    items(r.programs, key = { "p" + it.first.id }) { (p, ch) ->
                        Tile(onClick = { programDialog = ch to p }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), focusScale = 1.01f) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${Fmt.dayTime(p.start)} · ${ch.name}", style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim)
                                }
                                if (p.start <= System.currentTimeMillis() && p.stop > System.currentTimeMillis()) {
                                    fr.yannickuhrig.vyzo.ui.common.Badge("EN DIRECT", VyzoColors.Red)
                                }
                            }
                        }
                    }
                }
                if (r.movies.isNotEmpty()) {
                    item { SectionTitle("Films (${r.movies.size})") }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(r.movies, key = { it.uid }) { m ->
                                PosterCard(VodCard(m.uid, m.name, m.poster, m.rating, m.year), onClick = { onMovie(m.uid) }, modifier = Modifier.width(if (tv) 140.dp else 110.dp))
                            }
                        }
                    }
                }
                if (r.series.isNotEmpty()) {
                    item { SectionTitle("Séries (${r.series.size})") }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(r.series, key = { it.uid }) { s ->
                                PosterCard(VodCard(s.uid, s.name, s.poster, s.rating, s.year), onClick = { onSeries(s.uid) }, modifier = Modifier.width(if (tv) 140.dp else 110.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
