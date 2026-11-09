// Dérivé de SportTab.kt et SportLogic.kt d'IPVIO Stream, Copyright (C) 2026 IPVIO Stream contributors, GNU GPL 3.
// Modifié pour Vyzo le 2026-11-08 : événements et chaînes de Vyzo, choix de la chaîne et menu des programmes de Vyzo, fenêtres de temps et libellés en français.
package fr.yannickuhrig.vyzo.tv

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.sport.SportEvent
import fr.yannickuhrig.vyzo.ui.common.ActionDialog
import fr.yannickuhrig.vyzo.ui.common.ActionItem
import fr.yannickuhrig.vyzo.ui.common.Badge
import fr.yannickuhrig.vyzo.ui.common.ChannelMenu
import fr.yannickuhrig.vyzo.ui.common.EmptyState
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.NetImage
import fr.yannickuhrig.vyzo.ui.common.ProgramMenu
import fr.yannickuhrig.vyzo.ui.common.appViewModel
import fr.yannickuhrig.vyzo.ui.home.ChannelItem
import fr.yannickuhrig.vyzo.ui.sport.SportState
import fr.yannickuhrig.vyzo.ui.sport.SportViewModel
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors

/** Disciplines reconnues au titre du programme, sinon au nom de la chaîne. */
object SportKinds {
    private class Kind(val name: String, val re: Regex)

    private fun kind(name: String, pattern: String) = Kind(name, Regex(pattern, RegexOption.IGNORE_CASE))

    // L'ordre compte : « football américain » doit passer avant « football ».
    private val KINDS = listOf(
        kind("Football américain", """\b(nfl|football\s+am[ée]ricain|american\s?football|super\s?bowl)\b"""),
        kind("Basket", """\b(nba|wnba|basket(ball)?|euroleague|betclic\s?[ée]lite)\b"""),
        kind("Hockey sur glace", """\b(nhl|khl|hockey\s+sur\s+glace|ice\s?hockey)\b"""),
        kind("Football", """\b(football|foot|soccer|ligue\s?[12]|champions\s?league|ligue\s+des\s+champions|europa\s?league|conference\s?league|premier\s?league|la\s?liga|bundesliga|serie\s?a|coupe\s+de\s+france|fa\s?cup)\b"""),
        kind("Tennis", """\b(tennis|wimbledon|roland[-\s]?garros|us\s?open|open\s+d'australie|australian\s?open|atp|wta)\b"""),
        kind("Sports mécaniques", """\b(formule\s?1|formula\s?1|f1|moto\s?gp|moto2|moto3|grand\s?prix|nascar|indycar|rallye|rally|wrc|superbike)\b"""),
        kind("Sports de combat", """\b(ufc|boxe|boxing|wwe|mma|catch|wrestling|kickboxing|judo)\b"""),
        kind("Golf", """\b(golf|pga|masters)\b"""),
        kind("Handball et volley", """\b(handball|volley(ball)?)\b"""),
        kind("Rugby", """\b(rugby|top\s?14|pro\s?d2|six\s+nations|tournoi\s+des\s+6\s+nations)\b"""),
        kind("Cyclisme", """\b(cyclisme|tour\s+de\s+france|giro|vuelta|cycling|paris[-\s]roubaix)\b"""),
        kind("Sports d'hiver", """\b(biathlon|ski\s+alpin|ski|saut\s+[àa]\s+ski|snowboard|patinage)\b"""),
        kind("Athlétisme", """\b(athl[ée]tisme|marathon|diamond\s?league)\b"""),
        kind("Natation", """\b(natation|swimming)\b"""),
        kind("Fléchettes et snooker", """\b(darts|fl[ée]chettes|snooker)\b"""),
    )

    val OTHER: Int get() = KINDS.size

    fun name(index: Int): String = KINDS.getOrNull(index)?.name ?: "Autres"

    fun of(title: String, channel: String): Int {
        KINDS.forEachIndexed { i, k -> if (k.re.containsMatchIn(title)) return i }
        KINDS.forEachIndexed { i, k -> if (k.re.containsMatchIn(channel)) return i }
        return OTHER
    }
}

/**
 * Filtre de temps. « Plus tard » ne s'appelle pas « Demain » : la liste s'arrête à 36 heures,
 * la soirée de demain peut donc manquer quand on regarde le matin.
 */
enum class SportWindow(val label: String) {
    ALL("Tout"),
    SOON("Prochaines 3 h"),
    TODAY("Aujourd'hui"),
    LATER("Plus tard");

    /** Un match en cours n'est pas « plus tard ». */
    val showsLive: Boolean get() = this != LATER

    fun matches(start: Long, now: Long): Boolean = when (this) {
        ALL -> true
        SOON -> start <= now + 3 * 3_600_000L
        TODAY -> Fmt.dayIndex(start) == Fmt.dayIndex(now)
        LATER -> Fmt.dayIndex(start) > Fmt.dayIndex(now)
    }
}

/** Ce qu'affiche l'écran pour un filtre donné, avec le nombre d'événements par discipline. */
class SportView(
    val live: List<SportEvent>,
    val upcoming: List<SportEvent>,
    val channels: List<ChannelItem>,
    val kinds: List<Pair<Int, Int>>,
    val total: Int,
)

fun sportView(state: SportState, window: SportWindow, kind: Int, now: Long): SportView {
    fun kindOf(e: SportEvent) = SportKinds.of(e.program.title, e.channels.first().name)
    val live = if (window.showsLive) state.live else emptyList()
    val upcoming = state.upcoming.filter { window.matches(it.program.start, now) }
    val counts = (live + upcoming).groupingBy { kindOf(it) }.eachCount()
    val chips = (counts.keys + listOfNotNull(kind.takeIf { it >= 0 })).distinct()
        .map { it to (counts[it] ?: 0) }
        .sortedByDescending { it.second }
    return SportView(
        live = live.filter { kind < 0 || kindOf(it) == kind },
        upcoming = upcoming.filter { kind < 0 || kindOf(it) == kind },
        channels = state.channels.filter { kind < 0 || SportKinds.of("", it.channel.name) == kind },
        kinds = chips,
        total = counts.values.sum(),
    )
}

/** Sport : ce qui passe et ce qui arrive, filtrable par moment et par discipline, puis les chaînes de sport. */
@Composable
fun TvSport(onWatch: (ChannelEntity) -> Unit, onCatchup: (ChannelEntity, ProgramEntity) -> Unit) {
    val vm = appViewModel { SportViewModel(it) }
    val state by vm.state.collectAsState()
    var window by remember { mutableStateOf(SportWindow.ALL) }
    var kind by remember { mutableIntStateOf(-1) }
    var chooser by remember { mutableStateOf<SportEvent?>(null) }
    var programMenu by remember { mutableStateOf<Pair<ChannelEntity, ProgramEntity>?>(null) }
    var channelMenu by remember { mutableStateOf<ChannelEntity?>(null) }
    val first = remember { FocusRequester() }
    val filterEntry = remember { FocusRequester() }
    val loaded = state != null
    LaunchedEffect(loaded) { if (loaded) focusOnArrival(first) }

    val s = state
    Box(Modifier.fillMaxSize().background(Bg)) {
        when {
            s == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            s.channels.isEmpty() -> EmptyState(
                "Aucune chaîne de sport trouvée",
                "Vyzo les reconnaît au nom de leur catégorie ou de la chaîne (« Sports », « beIN SPORTS », « RMC Sport »…).",
                Icons.Default.SportsSoccer, Modifier.fillMaxSize(),
            )
            else -> {
                val view = remember(s, window, kind) { sportView(s, window, kind, System.currentTimeMillis()) }
                val hasEvents = s.live.isNotEmpty() || s.upcoming.isNotEmpty()
                val nothing = view.live.isEmpty() && view.upcoming.isEmpty() && view.channels.isEmpty()
                val watch: (String, ChannelEntity) -> Unit = { key, ch ->
                    LastFocus.pending = key
                    onWatch(ch)
                }
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = dpx(28)),
                    contentPadding = PaddingValues(top = dpx(30), bottom = dpx(60)),
                    verticalArrangement = Arrangement.spacedBy(dpx(24)),
                ) {
                    item(key = "head") {
                        Row(Modifier.padding(start = dpx(12)), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
                            Text("Sport", color = Color.White, fontSize = spx(34), fontWeight = FontWeight.ExtraBold, maxLines = 1)
                            Text(channelCount(s.channels.size), color = Tok.TextLow, fontSize = spx(17), maxLines = 1)
                        }
                        if (!hasEvents) {
                            Text("Aucun match en cours ni annoncé d'après le guide TV.", color = Tok.TextLow, fontSize = spx(18), modifier = Modifier.padding(start = dpx(12), top = dpx(10)))
                        }
                    }
                    if (hasEvents) item(key = "filters") {
                        // ▲ depuis les cartes arrive sur le filtre actif, pas sur la pastille qui se trouve au-dessus.
                        LazyRow(
                            Modifier.focusRestorer(filterEntry),
                            horizontalArrangement = Arrangement.spacedBy(dpx(10)),
                            verticalAlignment = Alignment.CenterVertically,
                            contentPadding = PaddingValues(vertical = dpx(4), horizontal = dpx(12)),
                        ) {
                            itemsIndexed(SportWindow.entries, key = { _, w -> "w${w.name}" }) { i, w ->
                                val own = remember { FocusRequester() }
                                FilterPill(w.label, window == w, if (i == 0) Icons.Default.Schedule else null, Modifier.focusRequester(if (window == w) filterEntry else own)) { window = w }
                            }
                            item(key = "sep") { Box(Modifier.width(dpx(2)).height(dpx(28)).background(Tok.Line)) }
                            item(key = "all") { FilterPill("Tous · ${view.total}", kind < 0, null) { kind = -1 } }
                            items(view.kinds, key = { "k${it.first}" }) { (k, n) ->
                                FilterPill("${SportKinds.name(k)} · $n", kind == k, null) { kind = k }
                            }
                        }
                    }
                    if (view.live.isNotEmpty()) item(key = "live") {
                        EventRow("En direct", view.live, first, onClick = { e ->
                            if (e.channels.size > 1) chooser = e else watch(eventFocusKey(e), e.channels.first())
                        }, onLongClick = { chooser = it })
                    }
                    if (view.upcoming.isNotEmpty()) item(key = "upcoming") {
                        EventRow("À venir", view.upcoming, if (view.live.isEmpty()) first else null, onClick = { e ->
                            programMenu = e.channels.first() to e.program
                        }, onLongClick = null)
                    }
                    if (view.channels.isNotEmpty()) item(key = "channels") {
                        val leads = view.live.isEmpty() && view.upcoming.isEmpty()
                        Column(verticalArrangement = Arrangement.spacedBy(dpx(10))) {
                            RowTitle("Chaînes de sport")
                            LazyRow(
                                Modifier.focusRestorer(),
                                horizontalArrangement = Arrangement.spacedBy(dpx(14)),
                                contentPadding = PaddingValues(vertical = dpx(8), horizontal = dpx(12)),
                            ) {
                                itemsIndexed(view.channels, key = { _, it -> it.key }) { i, item ->
                                    val own = remember { FocusRequester() }
                                    ChannelTile(
                                        item, Modifier.focusRequester(if (leads && i == 0) first else own).restoreFocus(item.key),
                                        onClick = { watch(item.key, item.channel) },
                                        onLongClick = { channelMenu = item.channel },
                                    )
                                }
                            }
                        }
                    }
                    if (nothing) item(key = "none") {
                        Column(Modifier.padding(start = dpx(12)), verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                            Text("Rien pour ce choix", color = Tok.TextLow, fontSize = spx(18))
                            FilterPill("Retirer les filtres", false, null) {
                                kind = -1
                                window = SportWindow.ALL
                            }
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
            actions = e.channels.map { ch ->
                ActionItem(ch.name, Icons.Default.LiveTv) {
                    LastFocus.pending = eventFocusKey(e)
                    onWatch(ch)
                }
            },
            onDismiss = { chooser = null },
        )
    }
    programMenu?.let { (ch, p) ->
        ProgramMenu(ch, p, onDismiss = { programMenu = null }, onWatchLive = { onWatch(ch) }, onCatchup = { onCatchup(ch, p) })
    }
    channelMenu?.let { ch ->
        ChannelMenu(ch, onDismiss = { channelMenu = null }, onWatch = { onWatch(ch) })
    }
}

private fun eventFocusKey(e: SportEvent) = "sport:" + e.key

@Composable
private fun RowTitle(text: String) {
    Text(text, color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(start = dpx(12)))
}

@Composable
private fun EventRow(
    title: String,
    events: List<SportEvent>,
    firstCard: FocusRequester?,
    onClick: (SportEvent) -> Unit,
    onLongClick: ((SportEvent) -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(dpx(10))) {
        RowTitle(title)
        LazyRow(
            Modifier.focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(dpx(14)),
            contentPadding = PaddingValues(vertical = dpx(8), horizontal = dpx(12)),
        ) {
            itemsIndexed(events, key = { _, e -> e.key }) { i, e ->
                val own = remember { FocusRequester() }
                EventCard(
                    e, Modifier.focusRequester(if (i == 0 && firstCard != null) firstCard else own).restoreFocus(eventFocusKey(e)),
                    onClick = { onClick(e) },
                    onLongClick = onLongClick?.let { handler -> { handler(e) } },
                )
            }
        }
    }
}

/** Carte d'un match : chaîne, titre, horaire ; la progression pour un match en cours. */
@Composable
private fun EventCard(e: SportEvent, modifier: Modifier, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val p = e.program
    val ch = e.channels.first()
    val now = System.currentTimeMillis()
    val live = p.start <= now
    val more = e.channels.size - 1
    CardFrame(modifier.width(dpx(340)).height(dpx(196)), SolidColor(Surf), 1.05f, 14, {}, onClick, onLongClick) {
        Column(Modifier.fillMaxSize().padding(dpx(14)), verticalArrangement = Arrangement.spacedBy(dpx(8))) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
                Box(Modifier.size(dpx(44)).clip(RoundedCornerShape(dpx(8))).background(Color(0x14FFFFFF)), contentAlignment = Alignment.Center) {
                    NetImage(ch.logo, Modifier.fillMaxSize().padding(dpx(4)), fallback = ch.name)
                }
                Text(
                    ch.name + if (more > 0) " · et $more autre${if (more > 1) "s" else ""}" else "",
                    color = Tok.TextLow, fontSize = spx(15), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (live) Badge("EN DIRECT", VyzoColors.Red) else if (e.announcedLive) Badge("DIRECT", VyzoColors.Red)
            }
            Text(p.title, color = Color.White, fontSize = spx(20), lineHeight = spx(25), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text(
                if (live) Fmt.range(p.start, p.stop) else "${Fmt.day(p.start)} · ${Fmt.range(p.start, p.stop)}",
                color = Accent2, fontSize = spx(15), maxLines = 1,
            )
            if (live) ProgressBar(programProgress(p.start, p.stop, now), Accent2)
        }
    }
}

@Composable
private fun ChannelTile(item: ChannelItem, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val ch = item.channel
    CardFrame(modifier.width(dpx(190)).height(dpx(150)), SolidColor(Surf), 1.06f, 14, {}, onClick, onLongClick) {
        Column(Modifier.fillMaxSize().padding(dpx(12)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(dpx(8))) {
            NetImage(ch.logo, Modifier.fillMaxWidth().height(dpx(70)), fallback = ch.name)
            Text(ch.name, color = Color.White, fontSize = spx(16), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}
