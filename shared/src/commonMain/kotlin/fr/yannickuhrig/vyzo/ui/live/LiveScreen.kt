package fr.yannickuhrig.vyzo.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.repo.GroupItem
import fr.yannickuhrig.vyzo.data.repo.GroupKind
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.ui.common.ChannelMenu
import fr.yannickuhrig.vyzo.ui.common.EmptyState
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.NetImage
import fr.yannickuhrig.vyzo.ui.common.ProgressLine
import fr.yannickuhrig.vyzo.ui.common.RailChoiceRow
import fr.yannickuhrig.vyzo.ui.common.RailSectionHeader
import fr.yannickuhrig.vyzo.ui.common.Tile
import fr.yannickuhrig.vyzo.ui.common.VideoSurface
import fr.yannickuhrig.vyzo.ui.common.appViewModel
import fr.yannickuhrig.vyzo.ui.common.colorFor
import fr.yannickuhrig.vyzo.ui.common.isDesktop
import fr.yannickuhrig.vyzo.ui.common.isTv
import fr.yannickuhrig.vyzo.ui.home.HeaderStatusCorner
import fr.yannickuhrig.vyzo.ui.home.HeroBackdrop
import fr.yannickuhrig.vyzo.ui.home.HeroHeader
import fr.yannickuhrig.vyzo.ui.home.HeroKicker
import fr.yannickuhrig.vyzo.ui.profile.rememberParentalGate
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import fr.yannickuhrig.vyzo.util.ChannelSeparator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Horloge partagée : rafraîchit les "en cours" toutes les 30 s. */
fun tickerFlow(periodMs: Long = 30_000) = flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(periodMs)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModel(private val c: AppContainer) : ViewModel() {
    val groups: StateFlow<List<GroupItem>> = c.repo.liveGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val chosen = MutableStateFlow<String?>(null)

    init {
        // Un autre profil reprend à son propre dernier groupe, pas à celui choisi par le précédent.
        viewModelScope.launch { c.profiles.switched.collect { chosen.value = null } }
    }

    val group: StateFlow<String> = combine(chosen, c.settings.flow.map { it.lastGroupKey }) { sel, last ->
        sel ?: last.ifBlank { Repository.KEY_ALL }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Repository.KEY_ALL)

    val channels: StateFlow<List<ChannelEntity>?> = group.flatMapLatest { c.repo.channels(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val nowPrograms: StateFlow<Map<String, ProgramEntity>> =
        combine(channels, tickerFlow()) { list, t -> list.orEmpty() to t }
            .mapLatest { (list, t) -> c.repo.nowPrograms(list.map { it.epgKey }, t) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val focused = MutableStateFlow<ChannelEntity?>(null)

    val focusedPrograms: StateFlow<List<ProgramEntity>> = focused.mapLatest { ch ->
        ch?.let { c.repo.upcoming(it.epgKey, 3) }.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playingUid: StateFlow<String?> = c.player.tag.map { t -> t?.takeIf { it.startsWith("ch:") }?.removePrefix("ch:") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val error = c.player.error
    val showNumbers = c.settings.flow.map { it.showNumbers }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun selectGroup(key: String) {
        chosen.value = key
    }

    fun moveGroupUp(groupKey: String) {
        moveGroup(groupKey, -1)
    }

    fun moveGroupDown(groupKey: String) {
        moveGroup(groupKey, 1)
    }

    fun moveGroup(groupKey: String, delta: Int) = viewModelScope.launch {
        if (groupKey.startsWith("cat:")) {
            c.repo.moveCategory(fr.yannickuhrig.vyzo.data.db.ContentKind.LIVE, groupKey.removePrefix("cat:"), delta)
        } else if (groupKey.startsWith("ug:")) {
            val id = groupKey.removePrefix("ug:").toLongOrNull() ?: return@launch
            c.repo.moveUserGroup(id, delta)
        }
    }

    fun preview(ch: ChannelEntity) = viewModelScope.launch {
        // Intertitre du fournisseur (« ░▒▓█ FRANCE FHD █▓▒░ ») : rien à lancer.
        if (ChannelSeparator.matches(ch.name)) return@launch
        runCatching {
            val spec = c.repo.resolveChannel(ch)
            c.player.play(spec, tag = "ch:${ch.uid}")
            c.repo.touchChannel(ch, group.value)
        }
    }
}

enum class LiveSection(val label: String) {
    LIBRARY("Accès rapide"),
    USER("Mes groupes"),
    CATEGORIES("Catégories"),
}

/** Ligne du rail des groupes du direct : titre de rubrique ou groupe. */
sealed interface LiveGroupLine {
    val key: String

    data class Header(val section: LiveSection) : LiveGroupLine {
        override val key get() = "rubrique:${section.name}"
    }

    data class Entry(val group: GroupItem) : LiveGroupLine {
        override val key get() = group.key
    }
}

private fun GroupKind.liveSection(): LiveSection = when (this) {
    GroupKind.ALL, GroupKind.FAVORITES, GroupKind.RECENT -> LiveSection.LIBRARY
    GroupKind.USER -> LiveSection.USER
    GroupKind.CATEGORY -> LiveSection.CATEGORIES
}

/** Groupes du direct précédés du titre de leur rubrique, dès que celle-ci change. */
fun liveGroupLines(groups: List<GroupItem>): List<LiveGroupLine> = buildList {
    groups.forEachIndexed { i, g ->
        val section = g.kind.liveSection()
        if (i == 0 || groups[i - 1].kind.liveSection() != section) add(LiveGroupLine.Header(section))
        add(LiveGroupLine.Entry(g))
    }
}

@Composable
fun LiveScreen(onOpenPlayer: (ChannelEntity, String) -> Unit) {
    val vm = appViewModel { LiveViewModel(it) }
    val groups by vm.groups.collectAsState()
    val group by vm.group.collectAsState()
    val channels by vm.channels.collectAsState()
    val now by vm.nowPrograms.collectAsState()
    val playing by vm.playingUid.collectAsState()
    val showNumbers by vm.showNumbers.collectAsState()
    var menuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    val gate = rememberParentalGate()
    val selectGroup: (GroupItem) -> Unit = { g -> gate(g.locked) { vm.selectGroup(g.key) } }

    menuFor?.let { ch ->
        ChannelMenu(ch, onDismiss = { menuFor = null }, onWatch = { onOpenPlayer(ch, group) })
    }

    if (isTv()) {
        LiveTv(vm, groups, group, channels, now, playing, showNumbers, onOpenPlayer, onMenu = { menuFor = it }, onSelectGroup = selectGroup)
    } else {
        LivePhone(vm, groups, group, channels, now, playing, showNumbers, onOpenPlayer, onMenu = { menuFor = it }, onSelectGroup = selectGroup)
    }
}

@Composable
private fun LiveTv(
    vm: LiveViewModel,
    groups: List<GroupItem>,
    group: String,
    channels: List<ChannelEntity>?,
    now: Map<String, ProgramEntity>,
    playing: String?,
    showNumbers: Boolean,
    onOpenPlayer: (ChannelEntity, String) -> Unit,
    onMenu: (ChannelEntity) -> Unit,
    onSelectGroup: (GroupItem) -> Unit,
) {
    val c = LocalContainer.current
    val focusedCh by vm.focused.collectAsState()
    val focusedProgs by vm.focusedPrograms.collectAsState()
    var focusedGroup by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedGroup) {
        val g = focusedGroup ?: return@LaunchedEffect
        delay(280)
        vm.selectGroup(g)
    }
    val listState = rememberLazyListState()
    val channelFocus = remember { FocusRequester() }
    val target = channels?.indexOfFirst { it.uid == playing }?.takeIf { it >= 0 } ?: 0
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(channels?.isNotEmpty()) {
        if (!initialFocusDone && !channels.isNullOrEmpty()) {
            listState.scrollToItem((target - 3).coerceAtLeast(0))
            delay(60)
            runCatching { channelFocus.requestFocus() }
            initialFocusDone = true
        }
    }
    LaunchedEffect(channels, playing) {
        val list = channels ?: return@LaunchedEffect
        if (list.isEmpty()) {
            vm.focused.value = null
            return@LaunchedEffect
        }
        val current = vm.focused.value
        if (current == null || list.none { it.uid == current.uid }) {
            vm.focused.value = list.firstOrNull { it.uid == playing }
                ?: list.firstOrNull { !ChannelSeparator.matches(it.name) }
                ?: list.first()
        }
    }

    var lastGroup by remember { mutableStateOf(group) }
    LaunchedEffect(group) {
        if (group != lastGroup) {
            lastGroup = group
            listState.scrollToItem(0)
        }
    }

    val lines = remember(groups) { liveGroupLines(groups) }
    val groupTitle = groups.firstOrNull { it.key == group }?.title.orEmpty()
    val playableCount = channels?.count { !ChannelSeparator.matches(it.name) }
    val countLabel = playableCount?.let { if (it == 1) "1 chaîne" else "$it chaînes" }
    val heroCh = focusedCh
    val currentTime = System.currentTimeMillis()
    val heroProg = focusedProgs.firstOrNull { it.start <= currentTime && it.stop > currentTime }
        ?: heroCh?.let { now[it.epgKey] }
    val remainingMin = heroProg?.let { ((it.stop - currentTime) / 60_000L).toInt().coerceAtLeast(1) }

    Box(Modifier.fillMaxSize()) {
        HeroBackdrop(
            imageUrl = heroProg?.icon,
            accentColor = heroCh?.let { colorFor(it.name) },
            tv = true,
            modifier = Modifier.fillMaxSize(),
        )
        Row(Modifier.fillMaxSize().padding(start = 12.dp, top = 16.dp, end = 20.dp, bottom = 12.dp)) {
            // Groupes. ◄ depuis les chaînes revient sur le groupe ouvert, pas sur celui qui se trouve à la même hauteur.
            LazyColumn(Modifier.width(240.dp).fillMaxHeight().focusRestorer(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(lines, key = { it.key }) { line ->
                    when (line) {
                        is LiveGroupLine.Header -> RailSectionHeader(line.section.label, first = line === lines.first())
                        is LiveGroupLine.Entry -> {
                            val g = line.group
                            // Une catégorie verrouillée ne s'ouvre qu'avec OK (et le code), pas au simple passage du focus.
                            GroupRow(g, selected = g.key == group, onClick = { onSelectGroup(g) }, onFocus = { if (!g.locked) focusedGroup = g.key })
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Top) {
                    if (heroCh != null) {
                        HeroHeader(
                            kicker = listOfNotNull(groupTitle.takeIf { it.isNotBlank() }, countLabel).joinToString(" · "),
                            title = heroProg?.title ?: heroCh.name,
                            tv = true,
                            modifier = Modifier.weight(1f),
                            meta = if (heroProg != null) listOf(Fmt.range(heroProg.start, heroProg.stop), "encore ${remainingMin} min") else emptyList(),
                            plot = heroProg?.description.orEmpty(),
                            live = true,
                            liveChannel = heroCh.name,
                            replay = heroCh.catchupDays > 0,
                            progress = heroProg?.let { Fmt.progress(it.start, it.stop) },
                            minHeight = 165.dp,
                            titleMaxLines = 1,
                            plotMaxLines = 2,
                        )
                    } else {
                        Column(Modifier.weight(1f).padding(top = 8.dp, bottom = 8.dp)) {
                            HeroKicker("Télévision")
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                Text(
                                    groupTitle.ifBlank { "Direct" },
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                countLabel?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = VyzoColors.TextDim) }
                            }
                        }
                    }
                    HeaderStatusCorner(tv = true, focusableWeather = false)
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    // Chaînes
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        when {
                            channels == null -> Unit
                            channels.isEmpty() -> EmptyState(
                                title = emptyTitle(group), message = emptyMessage(group), icon = Icons.Default.Tv,
                            )
                            // ► depuis les groupes revient sur la dernière chaîne parcourue (ou celle en cours).
                            else -> LazyColumn(state = listState, modifier = Modifier.focusRestorer(channelFocus), contentPadding = PaddingValues(bottom = 24.dp)) {
                                itemsIndexed(channels, key = { _, ch -> ch.uid }) { i, ch ->
                                    ChannelRow(
                                        ch = ch, program = now[ch.epgKey], playing = ch.uid == playing, showNumber = showNumbers,
                                        modifier = if (i == target) Modifier.focusRequester(channelFocus) else Modifier,
                                        onFocus = { vm.focused.value = ch },
                                        onClick = { if (ch.uid == playing) onOpenPlayer(ch, group) else vm.preview(ch) },
                                        onLongClick = { onMenu(ch) },
                                        onHover = { vm.focused.value = ch },
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    // Aperçu
                    PreviewPanel(
                        vm,
                        heroCh,
                        focusedProgs,
                        currentTime,
                        playing = playing,
                        modifier = Modifier.weight(0.88f).fillMaxHeight(),
                        hasPlayer = playing != null,
                        player = if (playing != null) c.player else null,
                    )
                }
            }
        }
    }
}

private fun emptyTitle(group: String) = when (group) {
    Repository.KEY_FAVORITES -> "Aucun favori"
    Repository.KEY_RECENT -> "Aucune chaîne regardée récemment"
    else -> "Aucune chaîne"
}

private fun emptyMessage(group: String) = when (group) {
    Repository.KEY_FAVORITES -> "Appuie longuement sur une chaîne (ou touche Menu) pour l'ajouter aux favoris."
    Repository.KEY_RECENT -> ""
    else -> "Si la playlist est en cours de chargement, patiente quelques instants."
}

@Composable
private fun PreviewPanel(
    vm: LiveViewModel,
    focused: ChannelEntity?,
    progs: List<ProgramEntity>,
    currentTime: Long,
    playing: String?,
    modifier: Modifier,
    hasPlayer: Boolean,
    player: fr.yannickuhrig.vyzo.player.VideoPlayer?,
) {
    val error by vm.error.collectAsState()
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (hasPlayer && player != null) VideoSurface(player, Modifier.fillMaxSize())
            else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Default.LiveTv, null, tint = VyzoColors.TextDim, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isDesktop()) "Clic ou Entrée : aperçu · une deuxième fois : plein écran" else "OK pour l'aperçu, OK encore pour le plein écran",
                    style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim,
                )
            }
            error?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(8.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        focused?.let { ch ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NetImage(ch.logo, Modifier.size(width = 50.dp, height = 34.dp), fallback = ch.name)
                Text(ch.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (ch.uid == playing) {
                    Text(
                        "EN LECTURE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = VyzoColors.OnAmber,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(VyzoColors.Amber).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            val current = progs.firstOrNull { it.start <= currentTime && it.stop > currentTime }
            val upcoming = progs.filter { it !== current }.take(2)
            if (upcoming.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("ENSUITE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = VyzoColors.TextDim)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    upcoming.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(VyzoColors.Surface.copy(alpha = 0.65f))
                                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(Fmt.time(p.start), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(52.dp))
                            Text(p.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else if (current == null) {
                Text("Pas d'information de programme", style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
fun GroupRow(g: GroupItem, selected: Boolean, onClick: () -> Unit, onFocus: () -> Unit = {}, onLongClick: (() -> Unit)? = null) {
    RailChoiceRow(
        title = g.title,
        count = if (g.kind == GroupKind.FAVORITES) -1 else g.count,
        locked = g.locked,
        selected = selected,
        onClick = onClick,
        onFocus = onFocus,
        onLongClick = onLongClick,
    )
}

@Composable
fun ChannelRow(
    ch: ChannelEntity,
    program: ProgramEntity?,
    playing: Boolean,
    showNumber: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onHover: (() -> Unit)? = null,
) {
    Tile(
        onClick = onClick, onLongClick = onLongClick, onFocus = onFocus, onHover = onHover, selected = playing,
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp), focusScale = 1.015f,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showNumber) {
                Text(
                    if (ch.number > 0) "${ch.number}" else "", style = MaterialTheme.typography.labelLarge, color = VyzoColors.TextDim,
                    modifier = Modifier.width(40.dp), maxLines = 1,
                )
            }
            Box(Modifier.size(width = 60.dp, height = 40.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.04f)), contentAlignment = Alignment.Center) {
                NetImage(ch.logo, Modifier.fillMaxSize().padding(3.dp), fallback = ch.name)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (playing) MaterialTheme.colorScheme.primary else Color.Unspecified, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (ch.catchupDays > 0) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.History, "Replay", Modifier.size(14.dp), tint = VyzoColors.Cyan)
                    }
                }
                if (program != null) {
                    Text(
                        program.title, style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    ProgressLine(Fmt.progress(program.start, program.stop), Modifier.fillMaxWidth(0.9f).padding(top = 4.dp))
                }
            }
            if (playing) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, "En lecture", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun LivePhone(
    vm: LiveViewModel,
    groups: List<GroupItem>,
    group: String,
    channels: List<ChannelEntity>?,
    now: Map<String, ProgramEntity>,
    playing: String?,
    showNumbers: Boolean,
    onOpenPlayer: (ChannelEntity, String) -> Unit,
    onMenu: (ChannelEntity) -> Unit,
    onSelectGroup: (GroupItem) -> Unit,
) {
    val chipsState = rememberLazyListState()
    LaunchedEffect(groups.isNotEmpty()) {
        val i = groups.indexOfFirst { it.key == group }
        if (i > 0) chipsState.scrollToItem(i)
    }
    val groupTitle = groups.firstOrNull { it.key == group }?.title.orEmpty()
    val playableCount = channels?.count { !ChannelSeparator.matches(it.name) }
    val countLabel = playableCount?.let { if (it == 1) "1 chaîne" else "$it chaînes" }
    val heroCh = channels?.firstOrNull { it.uid == playing }
        ?: channels?.firstOrNull { !ChannelSeparator.matches(it.name) && now[it.epgKey] != null }
        ?: channels?.firstOrNull { !ChannelSeparator.matches(it.name) }
    val heroProg = heroCh?.let { now[it.epgKey] }

    Box(Modifier.fillMaxSize()) {
        HeroBackdrop(
            imageUrl = heroProg?.icon,
            accentColor = heroCh?.let { colorFor(it.name) },
            tv = false,
            modifier = Modifier.fillMaxSize(),
        )
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (heroCh != null) {
                    HeroHeader(
                        kicker = groupTitle,
                        title = heroProg?.title ?: heroCh.name,
                        tv = false,
                        modifier = Modifier.weight(1f),
                        meta = if (heroProg != null) listOf(Fmt.range(heroProg.start, heroProg.stop)) else emptyList(),
                        plot = heroProg?.description.orEmpty(),
                        live = true,
                        liveChannel = heroCh.name,
                        replay = heroCh.catchupDays > 0,
                        progress = heroProg?.let { Fmt.progress(it.start, it.stop) },
                        minHeight = 0.dp,
                        titleMaxLines = 1,
                        plotMaxLines = 2,
                        onClick = { onOpenPlayer(heroCh, group) },
                    )
                } else {
                    Column(Modifier.weight(1f).padding(top = 4.dp)) {
                        HeroKicker("Télévision")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Text(
                                groupTitle.ifBlank { "Direct" },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            countLabel?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = VyzoColors.TextDim) }
                        }
                    }
                }
                HeaderStatusCorner(tv = false, focusableWeather = false)
            }
            LazyRow(
                state = chipsState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(groups, key = { it.key }) { g ->
                    FilterChip(
                        selected = g.key == group, onClick = { onSelectGroup(g) },
                        label = { Text(g.title, maxLines = 1) },
                        leadingIcon = if (g.locked) ({ Icon(Icons.Default.Lock, "Verrouillé", Modifier.size(16.dp)) }) else null,
                    )
                }
            }
            when {
                channels == null -> Unit
                channels.isEmpty() -> EmptyState(emptyTitle(group), emptyMessage(group).replace("(ou touche Menu) ", ""), Icons.Default.Tv)
                else -> LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp)) {
                    items(channels, key = { it.uid }) { ch ->
                        ChannelRow(
                            ch = ch, program = now[ch.epgKey], playing = ch.uid == playing, showNumber = showNumbers,
                            onClick = { onOpenPlayer(ch, group) }, onLongClick = { onMenu(ch) },
                        )
                    }
                }
            }
        }
    }
}
