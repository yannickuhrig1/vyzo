package fr.yannickuhrig.vyzo.ui.sport

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.data.sport.SportEvent
import fr.yannickuhrig.vyzo.data.sport.SportLineup
import fr.yannickuhrig.vyzo.ui.common.ActionDialog
import fr.yannickuhrig.vyzo.ui.common.ActionItem
import fr.yannickuhrig.vyzo.ui.common.Badge
import fr.yannickuhrig.vyzo.ui.common.EmptyState
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.NetImage
import fr.yannickuhrig.vyzo.ui.common.ProgramMenu
import fr.yannickuhrig.vyzo.ui.common.Tile
import fr.yannickuhrig.vyzo.ui.common.appViewModel
import fr.yannickuhrig.vyzo.ui.common.isTv
import fr.yannickuhrig.vyzo.ui.home.ChannelItem
import fr.yannickuhrig.vyzo.ui.home.ChannelTile
import fr.yannickuhrig.vyzo.ui.home.OnAirItem
import fr.yannickuhrig.vyzo.ui.home.OnAirTile
import fr.yannickuhrig.vyzo.ui.live.tickerFlow
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class SportState(
    val live: List<SportEvent>,
    val upcoming: List<SportEvent>,
    val channels: List<ChannelItem>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SportViewModel(private val c: AppContainer) : ViewModel() {
    /** null pendant le premier chargement. Rafraîchi chaque minute. */
    val state: StateFlow<SportState?> = combine(c.repo.sportChannels(), tickerFlow(60_000)) { chs, t -> chs to t }
        .mapLatest { (chs, now) ->
            val events = c.repo.sportEvents(chs, now, now + AHEAD_MS)
            val nowMap = c.repo.nowPrograms(chs.map { it.epgKey }, now)
            SportState(
                live = SportLineup.live(events, now),
                upcoming = SportLineup.upcoming(events, now, now + AHEAD_MS).take(MAX_UPCOMING),
                channels = chs.take(MAX_CHANNELS).map { ChannelItem(it, nowMap[it.epgKey], Repository.KEY_ALL) },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        /** Aujourd'hui et demain. */
        const val AHEAD_MS = 36 * 3_600_000L
        private const val MAX_UPCOMING = 80
        private const val MAX_CHANNELS = 200
    }
}

/** Un événement en direct vu comme une carte « À l'antenne » (première chaîne qui le diffuse). */
fun SportEvent.toOnAir(): OnAirItem = OnAirItem(channels.first(), program, null, Repository.KEY_ALL, alsoOn = channels.size - 1)

/**
 * Espace sport : matchs en direct, matchs à venir (aujourd'hui et demain) et chaînes de sport.
 * Tout vient des catégories, des noms de chaînes et du guide TV : sans guide, seules les chaînes s'affichent.
 */
@Composable
fun SportScreen(
    onWatch: (ChannelEntity) -> Unit,
    onCatchup: (ChannelEntity, ProgramEntity) -> Unit,
) {
    val vm = appViewModel { SportViewModel(it) }
    val tv = isTv()
    val state by vm.state.collectAsState()
    var chooser by remember { mutableStateOf<SportEvent?>(null) }
    var programMenu by remember { mutableStateOf<Pair<ChannelEntity, ProgramEntity>?>(null) }
    val side = if (tv) 32.dp else 16.dp

    val s = state
    when {
        s == null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        s.channels.isEmpty() -> EmptyState(
            "Aucune chaîne de sport trouvée",
            "Vyzo les reconnaît au nom de leur catégorie ou de la chaîne (« Sports », « beIN SPORTS », « RMC Sport »…).",
            Icons.Default.SportsSoccer, Modifier.fillMaxSize(),
        )
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(if (tv) 18.dp else 14.dp),
        ) {
            item(key = "live") {
                Section("En direct", side) {
                    if (s.live.isEmpty()) {
                        Text(
                            "Aucun match en cours d'après le guide TV.",
                            color = VyzoColors.TextDim, modifier = Modifier.padding(horizontal = side),
                        )
                    } else {
                        LazyRow(contentPadding = PaddingValues(horizontal = side - 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(s.live, key = { it.key }) { e ->
                                OnAirTile(
                                    e.toOnAir(), tv, onFocus = {},
                                    onClick = { if (e.channels.size > 1) chooser = e else onWatch(e.channels.first()) },
                                    onLongClick = { chooser = e },
                                )
                            }
                        }
                    }
                }
            }
            item(key = "upcoming") {
                Section("À venir", side) {
                    if (s.upcoming.isEmpty()) {
                        Text("Rien d'annoncé pour aujourd'hui et demain.", color = VyzoColors.TextDim, modifier = Modifier.padding(horizontal = side))
                    }
                }
            }
            items(s.upcoming, key = { "up:" + it.key }) { e ->
                UpcomingRow(e, side) { programMenu = e.channels.first() to e.program }
            }
            item(key = "channels") {
                Section("Chaînes de sport", side) {
                    LazyRow(contentPadding = PaddingValues(horizontal = side - 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(s.channels, key = { it.key }) { item ->
                            ChannelTile(item, tv, onFocus = {}, onClick = { onWatch(item.channel) }, onLongClick = { onWatch(item.channel) })
                        }
                    }
                }
            }
        }
    }

    chooser?.let { e ->
        ActionDialog(
            title = e.program.title,
            subtitle = "${Fmt.range(e.program.start, e.program.stop)} · choisis la chaîne",
            actions = e.channels.map { ch -> ActionItem(ch.name, Icons.Default.LiveTv) { onWatch(ch) } },
            onDismiss = { chooser = null },
        )
    }
    programMenu?.let { (ch, p) ->
        ProgramMenu(ch, p, onDismiss = { programMenu = null }, onWatchLive = { onWatch(ch) }, onCatchup = { onCatchup(ch, p) })
    }
}

@Composable
private fun Section(title: String, side: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Column {
        Text(
            title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = side, bottom = 6.dp),
        )
        content()
    }
}

/** Ligne « À venir » : jour et heure, titre, chaîne ; un clic ouvre la fiche (rappel, enregistrement). */
@Composable
private fun UpcomingRow(e: SportEvent, side: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val p = e.program
    val ch = e.channels.first()
    Tile(
        onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = side - 4.dp),
        focusScale = 1.02f, color = VyzoColors.Surface.copy(alpha = 0.85f), shape = RoundedCornerShape(10.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(96.dp)) {
                Text(Fmt.time(p.start), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(Fmt.day(p.start), style = MaterialTheme.typography.labelMedium, color = VyzoColors.TextDim, maxLines = 1)
            }
            NetImage(ch.logo, Modifier.size(width = 48.dp, height = 30.dp), fallback = ch.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val more = e.channels.size - 1
                Text(
                    ch.name + if (more > 0) " · et ${more} autre${if (more > 1) "s" else ""}" else "",
                    style = MaterialTheme.typography.labelMedium, color = VyzoColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (e.announcedLive) Badge("DIRECT", VyzoColors.Red)
        }
    }
}
