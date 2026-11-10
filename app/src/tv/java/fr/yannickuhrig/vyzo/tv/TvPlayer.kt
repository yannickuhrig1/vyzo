// Dérivé de PlayerLive.kt, PlayerControls.kt, PlayerPanels.kt et PlayerTheme.kt d'IPVIO Stream, Copyright (C) 2026 IPVIO Stream contributors, GNU GPL 3.
// Modifié pour Vyzo le 2026-11-08 : affichage seul, branché sur le lecteur, les touches et les données de Vyzo ; icônes Material et libellés en français.
package fr.yannickuhrig.vyzo.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.ui.common.ActionItem
import fr.yannickuhrig.vyzo.ui.common.Badge
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.NetImage
import fr.yannickuhrig.vyzo.ui.common.ProgramMenu
import fr.yannickuhrig.vyzo.ui.player.ChannelPanelArgs
import fr.yannickuhrig.vyzo.ui.player.GuidePanelArgs
import fr.yannickuhrig.vyzo.ui.player.LiveBarArgs
import fr.yannickuhrig.vyzo.ui.player.PlayerChoiceArgs
import fr.yannickuhrig.vyzo.ui.player.PlayerMenuArgs
import fr.yannickuhrig.vyzo.ui.player.PlayerUi
import fr.yannickuhrig.vyzo.ui.common.ProgressLine
import fr.yannickuhrig.vyzo.ui.player.VodBarArgs
import fr.yannickuhrig.vyzo.ui.player.timeshiftLabel
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors

/** Valeurs du lecteur, données pour un téléviseur de 960 dp comme le reste de sa maquette. */
private fun pd(v: Number): Dp = dpx(v.toFloat() * Design.WIDTH / 960f)
private fun ps(v: Number): TextUnit = spx(v.toFloat() * Design.WIDTH / 960f)

private val Ink = Color(0xFF0B0C12)
private val InkSoft = Color(0xFF3C4256)
private val PanelBg = Color(0xF2111320)
private val PanelBorder = Color(0x1AFFFFFF)
private val SecondText = Color(0xFFB8BDCC)

/** Chiffres de même largeur : l'heure et le temps écoulé ne tremblent pas. */
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

private val barScrim: Brush
    get() = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.95f)))

/** Ce que les flèches font en ce moment : le bandeau ne contient aucun bouton à atteindre. */
fun liveHint(ui: PlayerUi, customKeys: Boolean = false): String =
    if (ui.timeshift != null || ui.isCatchupMode) "▶❚❚ : lecture/pause   ◀◀ ▶▶ : reculer/avancer   ■ ou Menu : revenir au direct   ▲▼ : zapper"
    else if (customKeys) "OK : chaînes   0 : chaîne précédente"
    else "OK : chaînes   ▲▼ : zapper   ▶ : guide   ▶❚❚ : pause du direct   0 : chaîne précédente   Menu : options"

/** « Se termine à 21:42 », null tant que la durée n'est pas connue. */
fun endsAtLabel(position: Long, duration: Long, now: Long): String? =
    if (duration > 0) "Se termine à ${Fmt.time(now + (duration - position).coerceAtLeast(0))}" else null

/** Bandeau du direct : logo, numéro, chaîne, état, programme en cours et progression, puis la suite. */
@Composable
fun TvLiveBar(a: LiveBarArgs) {
    TrackDesignScale()
    val ui = a.ui
    val ch = ui.channel
    val ts = ui.timeshift
    Column(Modifier.fillMaxWidth().background(barScrim).padding(horizontal = pd(56), vertical = pd(28))) {
        Spacer(Modifier.height(pd(40)))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(pd(20))) {
            if (ch != null) {
                Box(Modifier.size(pd(72)).clip(RoundedCornerShape(pd(12))).background(Color(0x24FFFFFF)), contentAlignment = Alignment.Center) {
                    NetImage(ch.logo, Modifier.fillMaxSize().padding(pd(8)), fallback = ch.name)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(pd(5))) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(pd(12))) {
                    if (a.showNumbers && (ch?.number ?: 0) > 0) {
                        Text("${ch!!.number}", color = Accent2, fontSize = ps(22), fontWeight = FontWeight.Bold, style = Tabular, maxLines = 1)
                    }
                    Text(ui.title, color = Color.White, fontSize = ps(26), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    when {
                        ui.isCatchupMode -> Badge("REPLAY", VyzoColors.Cyan)
                        ts != null -> Badge(timeshiftLabel(ts, a.delayMs), if (ts.paused) VyzoColors.Amber else VyzoColors.Cyan)
                        else -> LiveBadge()
                    }
                    Text(Fmt.time(a.clock), color = Color.White.copy(alpha = 0.85f), fontSize = ps(20), fontWeight = FontWeight.SemiBold, style = Tabular, maxLines = 1)
                }
                // Replay ou différé : la barre et le temps restant suivent ce qui est à l'écran, pas le direct.
                val p = if (ui.isCatchupMode) ui.catchupProgram ?: ui.now else ui.now
                val watched = when {
                    ui.isCatchupMode && ui.catchupProgram != null -> ui.catchupProgram!!.start + a.positionMs
                    ts != null -> a.clock - a.delayMs
                    else -> a.clock
                }
                if (p != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(pd(12))) {
                        Text(Fmt.range(p.start, p.stop), color = Accent2, fontSize = ps(18), fontWeight = FontWeight.Bold, style = Tabular, maxLines = 1)
                        Text(p.title, color = Color.White, fontSize = ps(18), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("encore ${((p.stop - watched) / 60_000).coerceAtLeast(0)} min", color = SecondText, fontSize = ps(15), maxLines = 1)
                    }
                    ProgressBar(programProgress(p.start, p.stop, watched), Accent1, h = 7)
                    if (p.description.isNotBlank()) {
                        Text(p.description, color = Tok.TextMid, fontSize = ps(15), lineHeight = ps(20), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text("Pas d'information de programme", color = SecondText, fontSize = ps(15), maxLines = 1)
                }
                ui.next?.let { n ->
                    Text("Ensuite ${Fmt.time(n.start)}  ${n.title}", color = SecondText, fontSize = ps(15), style = Tabular, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(liveHint(ui, a.customKeys), color = Color.White.copy(alpha = 0.55f), fontSize = ps(13), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = pd(12)))
    }
}

/** Barre d'un film ou d'un épisode : titre, position, temps restant et heure de fin. */
@Composable
fun TvVodBar(a: VodBarArgs) {
    TrackDesignScale()
    val ui = a.ui
    Column(Modifier.fillMaxWidth().background(barScrim).padding(horizontal = pd(56), vertical = pd(28))) {
        Spacer(Modifier.height(pd(40)))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(pd(20))) {
            if (ui.artwork.isNotBlank()) {
                Box(
                    Modifier.width(pd(176)).aspectRatio(16f / 9f).clip(RoundedCornerShape(pd(10))).background(Color(0x24FFFFFF))
                        .semantics { contentDescription = "Miniature de l'épisode" },
                ) {
                    NetImage(ui.artwork, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(ui.title, color = Color.White, fontSize = ps(26), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ui.subtitle.isNotBlank()) Text(ui.subtitle, color = SecondText, fontSize = ps(16), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = pd(14)), verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.duration(a.position), color = Color.White, fontSize = ps(16), fontWeight = FontWeight.SemiBold, style = Tabular, maxLines = 1)
            Spacer(Modifier.width(pd(14)))
            ProgressLine(if (a.duration > 0) a.position.toFloat() / a.duration else 0f, Modifier.weight(1f).height(4.dp))
            Spacer(Modifier.width(pd(14)))
            Text(if (a.duration > 0) Fmt.duration(a.duration) else "--:--", color = Color.White, fontSize = ps(16), fontWeight = FontWeight.SemiBold, style = Tabular, maxLines = 1)
        }
        Row(Modifier.padding(top = pd(10)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(pd(10))) {
            Icon(if (a.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(pd(20)), tint = Color.White)
            endsAtLabel(a.position, a.duration, System.currentTimeMillis())?.let {
                Text(it, color = SecondText, fontSize = ps(14), style = Tabular, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Text("OK : lecture/pause   ◀▶ : reculer/avancer (maintenir pour accélérer)   ▲ : options", color = Color.White.copy(alpha = 0.55f), fontSize = ps(13), maxLines = 1)
        }
    }
}

/** Ligne de panneau : blanche à la sélection, légèrement éclairée pour l'élément en cours. */
@Composable
private fun PanelLine(
    modifier: Modifier,
    tinted: Boolean,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(pd(10))
    Row(
        modifier.fillMaxWidth().clip(shape)
            .background(if (f) Color.White else if (tinted) Color(0x1FFFFFFF) else Color.Transparent)
            .onFocusChanged {
                if (it.isFocused != f) {
                    f = it.isFocused
                    if (it.isFocused) onFocus()
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = pd(12), vertical = pd(10)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(pd(12)),
    ) { content(f) }
}

/** Liste des chaînes dans le lecteur : à gauche de l'image, et en bas ce qui passe sur la chaîne sélectionnée. */
@Composable
fun TvPlayerChannels(a: ChannelPanelArgs) {
    TrackDesignScale()
    val state = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val start = a.channels.indexOfFirst { it.uid == a.currentUid }.coerceAtLeast(0)
    var focused by remember { mutableStateOf(a.channels.getOrNull(start)) }
    LaunchedEffect(Unit) {
        state.scrollToItem((start - 3).coerceAtLeast(0))
        focusWhenReady(focus)
    }
    val shape = RoundedCornerShape(topEnd = pd(24), bottomEnd = pd(24))
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxHeight().width(pd(430)).clip(shape).background(PanelBg).padding(pd(16))
                .onPreviewKeyEvent { e ->
                    when {
                        e.type != KeyEventType.KeyDown -> false
                        e.key == Key.DirectionRight -> { a.onClose(); true }
                        e.key == Key.DirectionLeft -> true
                        else -> false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(pd(6)),
        ) {
            Text(a.title, color = Color.White, fontSize = ps(24), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("OK : regarder   ▶ : fermer", color = SecondText, fontSize = ps(13), maxLines = 1)
            LazyColumn(state = state, contentPadding = PaddingValues(top = pd(4), bottom = pd(24))) {
                itemsIndexed(a.channels, key = { _, ch -> ch.uid }) { i, ch ->
                    val own = remember { FocusRequester() }
                    PanelChannelRow(
                        ch, a.nowMap[ch.epgKey], ch.uid == a.currentUid, a.showNumbers,
                        Modifier.padding(vertical = pd(2)).focusRequester(if (i == start) focus else own),
                        onFocus = { focused = ch },
                        onClick = { a.onPick(ch) },
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(pd(28)), contentAlignment = Alignment.BottomCenter) {
            focused?.let { ChannelInfoBar(it, a.nowMap[it.epgKey]) }
        }
    }
}

@Composable
private fun PanelChannelRow(
    ch: ChannelEntity,
    program: ProgramEntity?,
    current: Boolean,
    showNumber: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    PanelLine(modifier, current, onFocus, onClick) { f ->
        if (showNumber) Text(if (ch.number > 0) "${ch.number}" else "", color = if (f) InkSoft else SecondText, fontSize = ps(15), style = Tabular, maxLines = 1, modifier = Modifier.width(pd(30)))
        Box(Modifier.size(pd(46)).clip(RoundedCornerShape(pd(8))).background(if (f) Color(0x14000000) else Color(0x14FFFFFF)), contentAlignment = Alignment.Center) {
            NetImage(ch.logo, Modifier.fillMaxSize().padding(pd(4)), fallback = ch.name)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(pd(2))) {
            Text(ch.name, color = if (f) Ink else Color.White, fontSize = ps(17), fontWeight = if (current || f) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (program != null) {
                Text("${Fmt.time(program.start)}  ${program.title}", color = if (f) InkSoft else Accent2, fontSize = ps(13), style = Tabular, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ProgressBar(programProgress(program.start, program.stop, System.currentTimeMillis()), Accent1)
            }
        }
        if (ch.catchupDays > 0) Icon(Icons.Default.History, "Replay", Modifier.size(pd(18)), tint = if (f) InkSoft else Accent2)
    }
}

/** Bande basse de la liste : une carte au milieu de l'image cacherait ce qu'on est en train de regarder. */
@Composable
private fun ChannelInfoBar(ch: ChannelEntity, program: ProgramEntity?) {
    val shape = RoundedCornerShape(pd(18))
    Row(
        Modifier.fillMaxWidth().clip(shape).background(PanelBg).border(1.dp, PanelBorder, shape).padding(horizontal = pd(16), vertical = pd(12)),
        horizontalArrangement = Arrangement.spacedBy(pd(14)),
    ) {
        Box(Modifier.size(pd(54)).clip(RoundedCornerShape(pd(8))).background(Color(0x24FFFFFF)), contentAlignment = Alignment.Center) {
            NetImage(ch.logo, Modifier.fillMaxSize().padding(pd(8)), fallback = ch.name)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(pd(2))) {
            if (program != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(pd(10))) {
                    Text(ch.name, color = SecondText, fontSize = ps(14), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(Fmt.range(program.start, program.stop), color = Accent2, fontSize = ps(14), fontWeight = FontWeight.Bold, style = Tabular, maxLines = 1)
                }
                Text(program.title, color = Color.White, fontSize = ps(20), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ProgressBar(programProgress(program.start, program.stop, System.currentTimeMillis()), Accent1, Modifier.padding(vertical = pd(3)))
                if (program.description.isNotBlank()) {
                    Text(program.description, color = Tok.TextMid, fontSize = ps(15), lineHeight = ps(19), maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text(ch.name, color = Color.White, fontSize = ps(20), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Pas d'information de programme", color = SecondText, fontSize = ps(15), maxLines = 1)
            }
        }
    }
}

/** Mini-guide : les programmes d'une chaîne, ◀ ▶ pour regarder ceux des autres sans zapper. */
@Composable
fun TvPlayerGuide(a: GuidePanelArgs) {
    TrackDesignScale()
    var ch by remember { mutableStateOf(a.start) }
    var programs by remember { mutableStateOf<List<ProgramEntity>?>(null) }
    var menu by remember { mutableStateOf<ProgramEntity?>(null) }
    val state = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val now = remember(programs) { System.currentTimeMillis() }
    LaunchedEffect(ch.uid) {
        programs = null
        programs = a.vm.guideFor(ch)
    }
    val list = programs.orEmpty()
    val nowIdx = list.indexOfFirst { it.start <= now && it.stop > now }.takeIf { it >= 0 }
        ?: list.indexOfFirst { it.start > now }.coerceAtLeast(0)
    LaunchedEffect(programs) {
        if (programs == null) return@LaunchedEffect
        if (list.isNotEmpty()) state.scrollToItem((nowIdx - 1).coerceAtLeast(0))
        focusWhenReady(focus)
    }
    fun browse(delta: Int) {
        a.vm.neighbour(ch, delta)?.let { ch = it }
    }

    val shape = RoundedCornerShape(topStart = pd(24), bottomStart = pd(24))
    Column(
        Modifier.fillMaxHeight().width(pd(460)).clip(shape).background(PanelBg)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft, Key.ChannelUp -> { browse(-1); true }
                    Key.DirectionRight, Key.ChannelDown -> { browse(1); true }
                    else -> false
                }
            }
            .padding(pd(16)),
        verticalArrangement = Arrangement.spacedBy(pd(6)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(pd(12))) {
            Box(Modifier.size(pd(46)).clip(RoundedCornerShape(pd(8))).background(Color(0x24FFFFFF)), contentAlignment = Alignment.Center) {
                NetImage(ch.logo, Modifier.fillMaxSize().padding(pd(4)), fallback = ch.name)
            }
            Text(
                (if (a.showNumbers && ch.number > 0) "${ch.number}  " else "") + ch.name, color = Color.White, fontSize = ps(24),
                fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text("◀ ▶ : autres chaînes   OK : regarder, replay, rappel, enregistrer", color = SecondText, fontSize = ps(13), maxLines = 1, overflow = TextOverflow.Ellipsis)
        when {
            programs == null -> Box(Modifier.fillMaxWidth().height(pd(120)), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(pd(28))) }
            list.isEmpty() -> {
                Text("Pas de programme pour cette chaîne.", color = SecondText, fontSize = ps(15), modifier = Modifier.padding(pd(12)))
                // Le focus reste dans le panneau pour pouvoir changer de chaîne.
                PanelLine(Modifier.focusRequester(focus), false, onClick = { a.onWatch(ch) }) { f ->
                    Text("Regarder ${ch.name}", color = if (f) Ink else Color.White, fontSize = ps(17), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            else -> LazyColumn(state = state, contentPadding = PaddingValues(top = pd(4), bottom = pd(24))) {
                itemsIndexed(list, key = { _, p -> p.id }) { i, p ->
                    val own = remember { FocusRequester() }
                    GuideLine(p, now, a.vm.canCatchup(ch, p), Modifier.padding(vertical = pd(2)).focusRequester(if (i == nowIdx) focus else own)) { menu = p }
                }
            }
        }
    }
    menu?.let { p ->
        ProgramMenu(
            ch = ch, p = p, onDismiss = { menu = null },
            onWatchLive = { menu = null; a.onWatch(ch) },
            onCatchup = { menu = null; a.onCatchup(ch, p) },
        )
    }
}

@Composable
private fun GuideLine(p: ProgramEntity, now: Long, replay: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val live = p.start <= now && p.stop > now
    val past = p.stop <= now
    PanelLine(modifier, live, onClick = onClick) { f ->
        Text(Fmt.time(p.start), color = if (f) InkSoft else if (past) SecondText else Accent2, fontSize = ps(15), fontWeight = FontWeight.Bold, style = Tabular, maxLines = 1, modifier = Modifier.width(pd(52)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(pd(3))) {
            Text(p.title, color = if (f) Ink else if (past && !replay) SecondText else Color.White, fontSize = ps(17), fontWeight = if (f) FontWeight.Bold else FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (live || replay) {
                Row(horizontalArrangement = Arrangement.spacedBy(pd(6))) {
                    if (live) Badge("EN COURS", VyzoColors.Red)
                    if (replay) Badge("REPLAY", VyzoColors.Cyan)
                }
            }
            if (live) ProgressBar(programProgress(p.start, p.stop, now), Accent1)
        }
    }
}

/** Panneau en haut à droite : l'image reste visible, ◀ le referme. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CornerPanel(title: String, hint: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        val input = LocalInputModeManager.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
            // Après un geste à la souris (télécommande à pointeur), les lignes ne prendraient plus le focus.
            input.requestInputMode(InputMode.Keyboard)
        }
        TrackDesignScale()
        val shape = RoundedCornerShape(pd(24))
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.align(Alignment.TopEnd).padding(pd(32)).widthIn(max = pd(460)).fillMaxWidth()
                    .clip(shape).background(PanelBg).border(1.dp, PanelBorder, shape)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onDismiss(); true } else false
                    }
                    .padding(pd(24)),
            ) {
                Text(title, color = Color.White, fontSize = ps(24), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hint.isNotBlank()) Text(hint, color = SecondText, fontSize = ps(14), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = pd(2)))
                Spacer(Modifier.height(pd(14)))
                content()
            }
        }
    }
}

/** Options du lecteur. La ligne ferme le panneau comme le fait la fenêtre d'options des autres variantes. */
@Composable
fun TvPlayerMenu(a: PlayerMenuArgs) {
    val first = remember { FocusRequester() }
    CornerPanel(a.title, a.subtitle, a.onDismiss) {
        LazyColumn(Modifier.heightIn(max = pd(400))) {
            itemsIndexed(a.actions) { i, action ->
                val own = remember { FocusRequester() }
                MenuLine(action, Modifier.padding(vertical = pd(2)).focusRequester(if (i == 0) first else own)) {
                    if (action.enabled) {
                        action.onClick()
                        a.onDismiss()
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focusWhenReady(first) }
}

@Composable
private fun MenuLine(action: ActionItem, modifier: Modifier, onClick: () -> Unit) {
    PanelLine(modifier, false, onClick = onClick) { f ->
        action.icon?.let { icon ->
            Icon(icon, null, Modifier.size(pd(22)), tint = if (f) InkSoft else if (!action.enabled) SecondText else action.tint ?: Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(action.label, color = if (f) Ink else if (action.enabled) Color.White else SecondText, fontSize = ps(17), fontWeight = if (f) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (action.detail.isNotBlank()) Text(action.detail, color = if (f) InkSoft else SecondText, fontSize = ps(13), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Piste audio, qualité : la ligne choisie porte une coche et reçoit la sélection à l'ouverture. */
@Composable
fun TvPlayerChoice(a: PlayerChoiceArgs) {
    val focus = remember { FocusRequester() }
    val start = a.selected.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
    CornerPanel(a.title, "", a.onDismiss) {
        LazyColumn(Modifier.heightIn(max = pd(400)), state = state) {
            itemsIndexed(a.labels) { i, label ->
                val own = remember { FocusRequester() }
                val chosen = i == a.selected
                PanelLine(
                    Modifier.padding(vertical = pd(2)).focusRequester(if (i == start) focus else own), false,
                    onClick = {
                        a.onPick(i)
                        a.onDismiss()
                    },
                ) { f ->
                    Text(label, color = if (f) Ink else Color.White, fontSize = ps(17), fontWeight = if (chosen || f) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (chosen) Icon(Icons.Default.Check, "Sélectionné", Modifier.size(pd(20)), tint = if (f) InkSoft else Accent2)
                }
            }
        }
    }
    LaunchedEffect(Unit) { focusWhenReady(focus) }
}
