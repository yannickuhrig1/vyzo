package fr.yannickuhrig.vyzo.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.db.RecordingEntity
import fr.yannickuhrig.vyzo.data.repo.GroupItem
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.data.sync.SyncManager
import fr.yannickuhrig.vyzo.schedule.covers
import fr.yannickuhrig.vyzo.schedule.recordingMarginMs
import fr.yannickuhrig.vyzo.ui.common.ChannelMenu
import fr.yannickuhrig.vyzo.ui.common.Choice
import fr.yannickuhrig.vyzo.ui.common.ChoiceDialog
import fr.yannickuhrig.vyzo.ui.common.EmptyState
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.NetImage
import fr.yannickuhrig.vyzo.ui.common.ProgramMenu
import fr.yannickuhrig.vyzo.ui.common.Tile
import fr.yannickuhrig.vyzo.ui.common.VideoSurface
import fr.yannickuhrig.vyzo.ui.common.repeatCount
import fr.yannickuhrig.vyzo.ui.common.appViewModel
import fr.yannickuhrig.vyzo.ui.common.isDesktop
import fr.yannickuhrig.vyzo.ui.common.isTv
import fr.yannickuhrig.vyzo.ui.live.tickerFlow
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.TimeZone

private const val SLOT = 30 * 60_000L
private const val MIN = 60_000L

/** Taille des cases "pas d'information" (créneaux sans programme). */
private const val GAP = 60 * 60_000L

fun slotStart(t: Long): Long = t - Math.floorMod(t, SLOT)

private fun gapStart(t: Long): Long = t - Math.floorMod(t, GAP)

/** Case de la grille : un programme, ou un créneau sans information. */
data class Cell(val start: Long, val stop: Long, val program: ProgramEntity?)

fun cellAt(list: List<ProgramEntity>, t: Long): Cell {
    list.firstOrNull { it.start <= t && it.stop > t }?.let { return Cell(it.start, it.stop, it) }
    val s = gapStart(t)
    val prevStop = list.lastOrNull { it.stop <= t }?.stop ?: Long.MIN_VALUE
    val nextStart = list.firstOrNull { it.start > t }?.start ?: Long.MAX_VALUE
    return Cell(maxOf(s, prevStop), minOf(s + GAP, nextStart), null)
}

private const val DAY = 24 * 60 * MIN

/** Minuit (heure locale) du jour de [t]. */
fun dayStartOf(t: Long): Long = t - Math.floorMod(t + TimeZone.getDefault().getOffset(t), DAY)

/** Jours couverts par le guide, de [from] à [to] : minuit de chacun. */
fun guideDays(from: Long, to: Long): List<Long> = buildList {
    var d = dayStartOf(from)
    // +3 h : un jour de changement d'heure dure 23 ou 25 h.
    while (d <= to) { add(d); d = dayStartOf(d + DAY + 3 * 60 * MIN) }
}

/** Même heure qu'en ce moment, le jour [day] : on passe de « ce soir » à « demain soir ». */
fun sameTimeOn(day: Long, now: Long): Long = day + (now - dayStartOf(now))

/** En-tête : jour et heure de la case choisie, ou l'heure qu'il est si cette case est en cours. */
fun guideHeaderLabel(cell: Cell?, now: Long): String =
    if (cell == null || (cell.start <= now && cell.stop > now)) "${Fmt.day(now, now)} · ${Fmt.time(now)}"
    else "${Fmt.day(cell.start, now)} · ${Fmt.time(cell.start)}"

fun cellsIn(list: List<ProgramEntity>, from: Long, to: Long): List<Cell> {
    val out = ArrayList<Cell>()
    var t = from
    var guard = 0
    while (t < to && guard++ < 200) {
        val c = cellAt(list, t)
        out += c
        t = if (c.stop > t) c.stop else t + SLOT
    }
    return out
}

@OptIn(ExperimentalCoroutinesApi::class)
class GuideViewModel(private val c: AppContainer) : ViewModel() {
    val groups: StateFlow<List<GroupItem>> = c.repo.liveGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val group = MutableStateFlow(c.settings.state.value.lastGroupKey.ifBlank { Repository.KEY_ALL })
    val channels: StateFlow<List<ChannelEntity>?> = group.flatMapLatest { c.repo.channels(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val programs = mutableStateMapOf<String, List<ProgramEntity>>()
    private val loading = HashSet<String>()

    val rangeFrom: Long
    val rangeTo: Long

    init {
        val s = c.settings.state.value
        val now = System.currentTimeMillis()
        rangeFrom = slotStart(now - s.epgDaysBehind * SyncManager.DAY)
        rangeTo = now + s.epgDaysAhead * SyncManager.DAY
        viewModelScope.launch {
            c.sync.state.map { it.lastFinished }.distinctUntilChanged().collect {
                programs.clear(); loading.clear()
            }
        }
    }

    val reminderKeys: StateFlow<Set<String>> = c.db.reminders().observeUpcoming(System.currentTimeMillis() - 6 * 3600_000L)
        .map { list -> list.map { "${it.channelUid}@${it.start}" }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val recordings: StateFlow<List<RecordingEntity>> = c.db.recordings().observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recordingMargin get() = c.settings.state.value.let { recordingMarginMs(it.recPaddingBeforeMin, it.recPaddingAfterMin) }

    val playingUid = c.player.tag.map { it?.takeIf { t -> t.startsWith("ch:") }?.removePrefix("ch:") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun ensure(key: String) {
        if (key.isBlank() || programs.containsKey(key) || !loading.add(key)) return
        viewModelScope.launch {
            programs[key] = runCatching { c.repo.programs(key, rangeFrom, rangeTo) }.getOrDefault(emptyList())
            loading.remove(key)
        }
    }

    fun list(ch: ChannelEntity?): List<ProgramEntity> = ch?.let { programs[it.epgKey] }.orEmpty()

    suspend fun initialRow(): Int {
        val list = channels.first { it != null }.orEmpty()
        val uid = playingUid.value ?: c.settings.get().lastChannelUid
        return list.indexOfFirst { it.uid == uid }.coerceAtLeast(0)
    }
}

@Composable
fun GuideScreen(
    onWatch: (ChannelEntity, String) -> Unit,
    onCatchup: (ChannelEntity, ProgramEntity) -> Unit,
) {
    val vm = appViewModel { GuideViewModel(it) }
    val c = LocalContainer.current
    val tv = isTv()
    val desktop = isDesktop()
    val channels by vm.channels.collectAsState()
    val groups by vm.groups.collectAsState()
    val group by vm.group.collectAsState()
    val reminders by vm.reminderKeys.collectAsState()
    val recordings by vm.recordings.collectAsState()
    val playing by vm.playingUid.collectAsState()
    val now by remember { tickerFlow(30_000) }.collectAsState(System.currentTimeMillis())

    // Taille de la grille (Réglages → Guide TV) : plus de programmes à l'écran, ou plus lisible de loin.
    val guideSize by remember { c.settings.flow.map { it.guideSize }.distinctUntilChanged() }.collectAsState(c.settings.state.value.guideSize)
    val (timeScale, rowScale) = when (guideSize) {
        "COMPACT" -> 0.75f to 0.8f
        "LARGE" -> 1.3f to 1.25f
        else -> 1f to 1f
    }
    val dpPerMin: Dp = (if (tv) 6.dp else 3.dp) * timeScale
    val rowH: Dp = (if (tv) 56.dp else 60.dp) * rowScale
    val chColW: Dp = if (tv) 200.dp else 84.dp

    var windowStart by remember { mutableLongStateOf(slotStart(System.currentTimeMillis())) }
    var selRow by remember { mutableIntStateOf(0) }
    var selTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var gridFocused by remember { mutableStateOf(false) }
    var programDialog by remember { mutableStateOf<Pair<ChannelEntity, ProgramEntity>?>(null) }
    var channelMenu by remember { mutableStateOf<ChannelEntity?>(null) }
    var groupPicker by remember { mutableStateOf(false) }
    var dayPicker by remember { mutableStateOf(false) }
    var centerDown by remember { mutableStateOf(false) }
    val miniPlayer by c.pip.isMiniPlayerMode.collectAsState()

    val listState = rememberLazyListState()
    val gridFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(group) {
        selRow = vm.initialRow()
        listState.scrollToItem((selRow - 3).coerceAtLeast(0))
        if (tv) {
            delay(80)
            runCatching { gridFocus.requestFocus() }
        }
    }

    programDialog?.let { (ch, p) ->
        ProgramMenu(
            ch, p, onDismiss = { programDialog = null },
            onWatchLive = { onWatch(ch, group) }, onCatchup = { onCatchup(ch, p) },
        )
    }
    channelMenu?.let { ch -> ChannelMenu(ch, onDismiss = { channelMenu = null }, onWatch = { onWatch(ch, group) }) }
    val gate = fr.yannickuhrig.vyzo.ui.profile.rememberParentalGate()
    if (groupPicker) {
        ChoiceDialog(
            title = "Groupe affiché",
            choices = groups.map { Choice(it.key, it.title, if (it.locked) "Verrouillé" else if (it.count >= 0) "${it.count} chaînes" else "") },
            selected = group,
            onSelect = { key -> gate(groups.firstOrNull { it.key == key }?.locked == true) { vm.group.value = key } },
            onDismiss = { groupPicker = false },
        )
    }
    if (dayPicker) {
        ChoiceDialog(
            title = "Jour affiché",
            choices = guideDays(vm.rangeFrom, vm.rangeTo).map { d ->
                Choice(d, Fmt.day(d, now), if (Fmt.dayIndex(d) - Fmt.dayIndex(now) in -1L..1L) Fmt.date(d) else "")
            },
            selected = dayStartOf(windowStart),
            onSelect = { d ->
                val t = sameTimeOn(d, System.currentTimeMillis()).coerceIn(vm.rangeFrom, vm.rangeTo)
                windowStart = slotStart(t)
                selTime = t
            },
            onDismiss = { dayPicker = false },
        )
    }

    val list = channels
    val selChannel = list?.getOrNull(selRow)
    val selCell = selChannel?.let { cellAt(vm.list(it), selTime) }
    val earlier = { windowStart = (windowStart - 3 * 60 * MIN).coerceAtLeast(vm.rangeFrom) }
    val later = { windowStart = (windowStart + 3 * 60 * MIN).coerceAtMost(vm.rangeTo) }
    val backToNow = { windowStart = slotStart(System.currentTimeMillis()); selTime = System.currentTimeMillis() }

    Column(Modifier.fillMaxSize()) {
        if (tv) {
            TvHeader(
                groupTitle = groups.firstOrNull { it.key == group }?.title.orEmpty(),
                channel = selChannel, cell = selCell, now = now,
                canCatchup = selChannel != null && selCell?.program != null && c.repo.canCatchup(selChannel, selCell.program, now),
                showPreview = playing != null, onPickGroup = { groupPicker = true },
                // À la souris (Vyzo PC), la télécommande ne suffit plus : boutons de temps et choix du jour.
                timeControls = if (desktop) {
                    { TimeControls(Fmt.day(windowStart, now), { dayPicker = true }, earlier, backToNow, later) }
                } else null,
            )
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { groupPicker = true }) {
                        Text(groups.firstOrNull { it.key == group }?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                }
                TimeControls(Fmt.day(windowStart, now), { dayPicker = true }, earlier, backToNow, later, compact = true)
            }
        }

        when {
            list == null -> Unit
            list.isEmpty() -> EmptyState("Aucune chaîne dans ce groupe", "Choisis un autre groupe en haut de l'écran.", Icons.Default.Tv)
            else -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val timelineW = maxWidth - chColW
                val visibleMs = ((timelineW / dpPerMin) * MIN).toLong().coerceAtLeast(SLOT)
                val minTime = vm.rangeFrom
                val maxTime = vm.rangeTo

                fun ensureVisible() {
                    if (selTime < windowStart) windowStart = slotStart(selTime)
                    else if (selTime >= windowStart + visibleMs - SLOT / 2) windowStart = slotStart(selTime) - SLOT
                    windowStart = windowStart.coerceIn(minTime, (maxTime - visibleMs).coerceAtLeast(minTime))
                }

                fun keepRowVisible() {
                    val info = listState.layoutInfo
                    val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    scope.launch {
                        when {
                            selRow < first + 1 && selRow > 0 -> listState.scrollToItem((selRow - 1).coerceAtLeast(0))
                            selRow <= first -> listState.scrollToItem(selRow)
                            selRow >= last -> listState.scrollToItem((selRow - (last - first) + 2).coerceAtLeast(0))
                        }
                    }
                }

                Column(Modifier.fillMaxSize()) {
                    TimelineHeader(windowStart, visibleMs, dpPerMin, chColW, now)
                    LazyColumn(
                        state = listState,
                        // Le mini-lecteur flotte en bas à droite : de quoi faire défiler les dernières chaînes au-dessus.
                        contentPadding = PaddingValues(bottom = if (miniPlayer) 240.dp else 24.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .onKeyEvent { ev ->
                                val isOk = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                                if (ev.type == KeyEventType.KeyUp) {
                                    if (isOk && centerDown) {
                                        centerDown = false
                                        val ch = list.getOrNull(selRow) ?: return@onKeyEvent true
                                        val cell = cellAt(vm.list(ch), selTime)
                                        if (cell.program != null) programDialog = ch to cell.program else onWatch(ch, group)
                                        return@onKeyEvent true
                                    }
                                    return@onKeyEvent false
                                }
                                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (ev.key) {
                                    Key.DirectionDown -> {
                                        if (selRow < list.lastIndex) { selRow++; keepRowVisible() }
                                        true
                                    }
                                    Key.DirectionUp -> if (selRow > 0) { selRow--; keepRowVisible(); true } else false
                                    Key.ChannelDown, Key.PageDown -> { selRow = (selRow + 8).coerceAtMost(list.lastIndex); keepRowVisible(); true }
                                    Key.ChannelUp, Key.PageUp -> { selRow = (selRow - 8).coerceAtLeast(0); keepRowVisible(); true }
                                    Key.DirectionRight -> {
                                        val cell = cellAt(vm.list(list.getOrNull(selRow)), selTime)
                                        selTime = cell.stop.coerceAtMost(maxTime)
                                        ensureVisible(); true
                                    }
                                    Key.DirectionLeft -> {
                                        val cell = cellAt(vm.list(list.getOrNull(selRow)), selTime)
                                        if (cell.start <= minTime) false
                                        else {
                                            val prev = cellAt(vm.list(list.getOrNull(selRow)), cell.start - 1)
                                            selTime = prev.start.coerceAtLeast(minTime)
                                            ensureVisible(); true
                                        }
                                    }
                                    Key.MediaFastForward -> { selTime = (selTime + 2 * 60 * MIN).coerceAtMost(maxTime); ensureVisible(); true }
                                    Key.MediaRewind -> { selTime = (selTime - 2 * 60 * MIN).coerceAtLeast(minTime); ensureVisible(); true }
                                    Key.Menu -> { list.getOrNull(selRow)?.let { channelMenu = it }; true }
                                    else -> if (isOk) { if (ev.repeatCount == 0) centerDown = true; true } else false
                                }
                            }
                            .focusRequester(gridFocus)
                            .onFocusChanged { gridFocused = it.hasFocus }
                            .focusable()
                            .pointerInput(dpPerMin, visibleMs) {
                                val pxPerMin = dpPerMin.toPx()
                                detectHorizontalDragGestures { change, drag ->
                                    change.consume()
                                    windowStart = (windowStart - (drag / pxPerMin * MIN).toLong())
                                        .coerceIn(minTime, (maxTime - visibleMs).coerceAtLeast(minTime))
                                }
                            }
                            .pointerInput(visibleMs) {
                                // Molette horizontale, ou Maj + molette : 30 min par cran. La molette seule fait défiler les chaînes.
                                awaitPointerEventScope {
                                    while (true) {
                                        val e = awaitPointerEvent(PointerEventPass.Initial)
                                        if (e.type != PointerEventType.Scroll) continue
                                        val d = e.changes.firstOrNull()?.scrollDelta ?: continue
                                        val dx = if (d.x != 0f) d.x else if (e.keyboardModifiers.isShiftPressed) d.y else 0f
                                        if (dx == 0f) continue
                                        e.changes.forEach { it.consume() }
                                        windowStart = (windowStart + (dx * SLOT).toLong())
                                            .coerceIn(minTime, (maxTime - visibleMs).coerceAtLeast(minTime))
                                    }
                                }
                            },
                    ) {
                        itemsIndexed(list, key = { _, ch -> ch.uid }) { i, ch ->
                            LaunchedEffect(ch.epgKey) { vm.ensure(ch.epgKey) }
                            GuideRow(
                                ch = ch,
                                programs = vm.list(ch),
                                windowStart = windowStart, visibleMs = visibleMs,
                                dpPerMin = dpPerMin, rowH = rowH, chColW = chColW, now = now,
                                isSelectedRow = tv && gridFocused && i == selRow,
                                selTime = selTime,
                                playing = ch.uid == playing,
                                reminders = reminders, recordings = recordings, recordingMargin = vm.recordingMargin,
                                onTapCell = { cell ->
                                    selRow = i; selTime = cell.start.coerceAtLeast(windowStart)
                                    if (cell.program != null) programDialog = ch to cell.program else onWatch(ch, group)
                                },
                                // Souris en interface TV : un clic choisit la case (détails en haut), un 2e clic ou le clic droit ouvre son menu.
                                onPointCell = { cell, menu ->
                                    val sel = cellAt(vm.list(ch), selTime)
                                    val again = gridFocused && selRow == i && sel.start == cell.start && sel.stop == cell.stop
                                    selRow = i; selTime = cell.start.coerceAtLeast(windowStart)
                                    runCatching { gridFocus.requestFocus() }
                                    when {
                                        !menu && !again -> Unit
                                        cell.program != null -> programDialog = ch to cell.program
                                        menu -> channelMenu = ch
                                        else -> onWatch(ch, group)
                                    }
                                },
                                onTapChannel = { onWatch(ch, group) },
                                onLongChannel = { channelMenu = ch },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvHeader(
    groupTitle: String,
    channel: ChannelEntity?,
    cell: Cell?,
    now: Long,
    canCatchup: Boolean,
    showPreview: Boolean,
    onPickGroup: () -> Unit,
    timeControls: (@Composable () -> Unit)? = null,
) {
    val c = LocalContainer.current
    Row(Modifier.fillMaxWidth().height(168.dp).padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tile(onClick = onPickGroup, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(groupTitle, style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Text(guideHeaderLabel(cell, now), style = MaterialTheme.typography.labelLarge, color = VyzoColors.TextDim)
                if (timeControls != null) {
                    Spacer(Modifier.weight(1f))
                    timeControls()
                }
            }
            Spacer(Modifier.height(10.dp))
            val p = cell?.program
            if (p != null && channel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (p.start <= now && p.stop > now) { Spacer(Modifier.width(8.dp)); fr.yannickuhrig.vyzo.ui.common.Badge("EN DIRECT", VyzoColors.Red) }
                    if (canCatchup) { Spacer(Modifier.width(6.dp)); fr.yannickuhrig.vyzo.ui.common.Badge("REPLAY", VyzoColors.Cyan) }
                }
                Text(
                    listOf(Fmt.range(p.start, p.stop), channel.name, p.category).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim,
                )
                if (p.description.isNotBlank()) {
                    Text(p.description, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            } else if (channel != null) {
                Text(channel.name, style = MaterialTheme.typography.headlineSmall)
                Text("Pas d'information de programme sur ce créneau", style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim)
            }
        }
        if (showPreview) {
            Spacer(Modifier.width(16.dp))
            Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                VideoSurface(c.player, Modifier.fillMaxSize())
            }
        }
    }
}

/** Jour affiché (avec son choix), plus tôt, maintenant, plus tard. [compact] : le jour en icône (téléphone). */
@Composable
private fun TimeControls(day: String, onPickDay: () -> Unit, onEarlier: () -> Unit, onNow: () -> Unit, onLater: () -> Unit, compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (compact) {
            IconButton(onClick = onPickDay) { Icon(Icons.Default.Today, "Jour affiché : $day") }
        } else {
            TextButton(onClick = onPickDay) {
                Text(day, maxLines = 1)
                Icon(Icons.Default.ArrowDropDown, null)
            }
        }
        IconButton(onClick = onEarlier) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Plus tôt") }
        TextButton(onClick = onNow) { Text("Maintenant") }
        IconButton(onClick = onLater) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Plus tard") }
    }
}

@Composable
private fun TimelineHeader(windowStart: Long, visibleMs: Long, dpPerMin: Dp, chColW: Dp, now: Long) {
    Row(Modifier.fillMaxWidth().height(28.dp)) {
        Box(Modifier.width(chColW).fillMaxHeight().padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
            Text(Fmt.day(windowStart + visibleMs / 2, now), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            var t = slotStart(windowStart)
            while (t < windowStart + visibleMs) {
                if (t >= windowStart) {
                    val x = dpPerMin * ((t - windowStart) / MIN.toFloat())
                    Box(Modifier.absoluteOffset(x = x).width(1.dp).height(8.dp).align(Alignment.BottomStart).background(VyzoColors.Outline))
                    Text(
                        Fmt.time(t), style = MaterialTheme.typography.labelMedium, color = VyzoColors.TextDim,
                        modifier = Modifier.absoluteOffset(x = x + 5.dp).align(Alignment.CenterStart),
                    )
                }
                t += SLOT
            }
            if (now in windowStart until windowStart + visibleMs) {
                val x = dpPerMin * ((now - windowStart) / MIN.toFloat())
                Box(Modifier.absoluteOffset(x = x - 4.dp).size(8.dp).align(Alignment.BottomStart).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

@Composable
private fun GuideRow(
    ch: ChannelEntity,
    programs: List<ProgramEntity>,
    windowStart: Long,
    visibleMs: Long,
    dpPerMin: Dp,
    rowH: Dp,
    chColW: Dp,
    now: Long,
    isSelectedRow: Boolean,
    selTime: Long,
    playing: Boolean,
    reminders: Set<String>,
    recordings: List<RecordingEntity>,
    recordingMargin: Long,
    onTapCell: (Cell) -> Unit,
    onPointCell: (cell: Cell, menu: Boolean) -> Unit,
    onTapChannel: () -> Unit,
    onLongChannel: () -> Unit,
) {
    val tv = isTv()
    val desktop = isDesktop()
    val windowEnd = windowStart + visibleMs
    Row(Modifier.fillMaxWidth().height(rowH).padding(vertical = 1.dp)) {
        // Colonne chaîne
        val cellBg = if (isSelectedRow) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow
        if (tv) {
            Row(
                Modifier.width(chColW).fillMaxHeight().padding(end = 2.dp).clip(RoundedCornerShape(6.dp)).background(cellBg)
                    .then(if (desktop) Modifier.mouseClicks(onClick = onTapChannel, onRightClick = onLongChannel) else Modifier)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ch.number > 0) {
                    Text("${ch.number}", style = MaterialTheme.typography.labelMedium, color = VyzoColors.TextDim, modifier = Modifier.width(34.dp), maxLines = 1)
                }
                NetImage(ch.logo, Modifier.size(width = 46.dp, height = 30.dp), fallback = ch.name)
                Spacer(Modifier.width(8.dp))
                Text(
                    ch.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (playing) MaterialTheme.colorScheme.primary else Color.Unspecified,
                )
            }
        } else {
            // Téléphone : logo au-dessus, nom en petit dessous, toute la case est cliquable.
            Column(
                Modifier.width(chColW).fillMaxHeight().padding(end = 2.dp).clip(RoundedCornerShape(6.dp)).background(cellBg)
                    .clickable(onClick = onTapChannel).padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                NetImage(ch.logo, Modifier.size(width = 40.dp, height = 26.dp), fallback = ch.name)
                Text(
                    ch.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (playing) MaterialTheme.colorScheme.primary else VyzoColors.TextDim,
                )
            }
        }
        // Programmes
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            val selCell = if (isSelectedRow) cellAt(programs, selTime) else null
            cellsIn(programs, windowStart, windowEnd).forEach { cell ->
                val from = maxOf(cell.start, windowStart)
                val to = minOf(cell.stop, windowEnd)
                if (to <= from) return@forEach
                val x = dpPerMin * ((from - windowStart) / MIN.toFloat())
                val w = dpPerMin * ((to - from) / MIN.toFloat())
                val selected = selCell != null && selCell.start == cell.start && selCell.stop == cell.stop
                val p = cell.program
                val isNow = cell.start <= now && cell.stop > now
                val isPast = cell.stop <= now
                val bg = when {
                    selected -> MaterialTheme.colorScheme.primaryContainer
                    p == null -> MaterialTheme.colorScheme.surfaceContainerLow
                    isNow -> VyzoColors.SurfaceHigh
                    isPast -> VyzoColors.Past
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                }
                Box(
                    Modifier.absoluteOffset(x = x).width(w).fillMaxHeight().padding(horizontal = 1.dp)
                        .clip(RoundedCornerShape(5.dp)).background(bg)
                        .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)) else Modifier)
                        .then(
                            when {
                                !tv -> Modifier.clickable { onTapCell(cell) }
                                desktop -> Modifier.mouseClicks(onClick = { onPointCell(cell, false) }, onRightClick = { onPointCell(cell, true) })
                                else -> Modifier
                            },
                        ),
                ) {
                    if (isNow && p != null) {
                        Box(
                            Modifier.fillMaxHeight().fillMaxWidth(((now - from).toFloat() / (to - from)).coerceIn(0f, 1f))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                        )
                    }
                    if (w > 18.dp) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).align(Alignment.CenterStart)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val remind = p != null && "${ch.uid}@${p.start}" in reminders
                                val rec = p != null && recordings.any { it.channelUid == ch.uid && it.covers(cell.start, cell.stop, recordingMargin) }
                                if (rec) Icon(Icons.Default.FiberManualRecord, null, Modifier.size(12.dp).padding(end = 2.dp), tint = VyzoColors.Red)
                                if (remind) Icon(Icons.Default.Notifications, null, Modifier.size(12.dp).padding(end = 2.dp), tint = VyzoColors.Amber)
                                if (p != null && isPast && ch.catchupDays > 0 && cell.start > now - ch.catchupDays * SyncManager.DAY) {
                                    Icon(Icons.Default.History, null, Modifier.size(12.dp).padding(end = 2.dp), tint = VyzoColors.Cyan)
                                }
                                Text(
                                    p?.title ?: "Pas d'information",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isNow) FontWeight.SemiBold else FontWeight.Normal),
                                    color = when {
                                        selected -> MaterialTheme.colorScheme.onPrimaryContainer
                                        p == null || isPast -> VyzoColors.TextDim
                                        else -> Color.Unspecified
                                    },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (p != null && w > 90.dp) {
                                Text(
                                    Fmt.range(p.start, p.stop), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else VyzoColors.TextDim,
                                )
                            }
                        }
                    }
                }
            }
            if (now in windowStart until windowEnd) {
                // Repère « maintenant » en haut et en bas de chaque ligne seulement : il ne barre plus le titre des programmes.
                val x = dpPerMin * ((now - windowStart) / MIN.toFloat())
                val mark = Modifier.absoluteOffset(x = x - 1.dp).width(2.dp).height(7.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                Box(mark.align(Alignment.TopStart))
                Box(mark.align(Alignment.BottomStart))
            }
        }
    }
}

/** Clic gauche et clic droit à la souris (Vyzo PC). */
@Composable
private fun Modifier.mouseClicks(onClick: () -> Unit, onRightClick: () -> Unit): Modifier {
    val right by rememberUpdatedState(onRightClick)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                    e.changes.forEach { it.consume() }
                    right()
                }
            }
        }
    }.clickable(onClick = onClick)
}
