// Dérivé de LiveTab.kt et LiveNow.kt d'IPVIO Stream, Copyright (C) 2026 IPVIO Stream contributors, GNU GPL 3.
// Modifié pour Vyzo le 2026-11-08 : groupes, chaînes et contrôle parental de Vyzo, aperçu vidéo au-dessus du programme, libellés en français.
package fr.yannickuhrig.vyzo.tv

import fr.yannickuhrig.vyzo.ui.common.colorFor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.repo.GroupKind
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.ui.common.ChannelMenu
import fr.yannickuhrig.vyzo.ui.common.EmptyState
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.NetImage
import fr.yannickuhrig.vyzo.ui.common.VideoSurface
import fr.yannickuhrig.vyzo.ui.common.appViewModel
import fr.yannickuhrig.vyzo.ui.live.LiveViewModel
import fr.yannickuhrig.vyzo.ui.profile.rememberParentalGate
import kotlinx.coroutines.delay

/**
 * Le direct : groupes, chaînes et, à droite, l'aperçu avec ce qui passe sur la chaîne sélectionnée.
 * OK lance l'aperçu, OK encore sur la chaîne en cours passe en plein écran, un appui long ouvre le menu de la chaîne.
 */
@Composable
fun TvLive(onOpenPlayer: (ChannelEntity, String) -> Unit) {
    val vm = appViewModel { LiveViewModel(it) }
    val c = LocalContainer.current
    val groups by vm.groups.collectAsState()
    val group by vm.group.collectAsState()
    val channels by vm.channels.collectAsState()
    val now by vm.nowPrograms.collectAsState()
    val playing by vm.playingUid.collectAsState()
    val showNumbers by vm.showNumbers.collectAsState()
    val focused by vm.focused.collectAsState()
    val programs by vm.focusedPrograms.collectAsState()
    val error by vm.error.collectAsState()
    var menuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    val gate = rememberParentalGate()
    val clock by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    menuFor?.let { ch ->
        ChannelMenu(ch, onDismiss = { menuFor = null }, onWatch = { onOpenPlayer(ch, group) })
    }

    // Parcourir les groupes change la liste après un court arrêt, jamais à chaque ligne survolée.
    var focusedGroup by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedGroup) {
        val g = focusedGroup ?: return@LaunchedEffect
        delay(280)
        vm.selectGroup(g)
    }

    // Même chose pour l'arrière-plan et le programme : pas de chargement à chaque chaîne survolée.
    var focusTarget by remember { mutableStateOf<ChannelEntity?>(null) }
    LaunchedEffect(focusTarget) {
        val ch = focusTarget ?: return@LaunchedEffect
        if (vm.focused.value != null) delay(300)
        vm.focused.value = ch
    }
    // Tant que la sélection reste sur les groupes, le panneau montre la première chaîne du groupe affiché.
    LaunchedEffect(channels) {
        val list = channels ?: return@LaunchedEffect
        if (list.none { it.uid == vm.focused.value?.uid }) {
            vm.focused.value = list.firstOrNull()
            // Sinon, revenir sur la même chaîne ne relancerait pas la mise à jour du panneau.
            focusTarget = null
        }
    }

    val groupState = rememberLazyListState()
    val channelState = rememberLazyListState()
    val groupFocus = remember { FocusRequester() }
    val channelFocus = remember { FocusRequester() }
    val target = channels?.indexOfFirst { it.uid == playing }?.takeIf { it >= 0 } ?: 0
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(channels?.isEmpty(), groups.isEmpty()) {
        val list = channels ?: return@LaunchedEffect
        if (arrived) return@LaunchedEffect
        if (list.isNotEmpty()) {
            channelState.scrollToItem((target - 3).coerceAtLeast(0))
            focusOnArrival(channelFocus)
            arrived = true
        } else if (groups.isNotEmpty()) {
            groupState.scrollToItem(groups.indexOfFirst { it.key == group }.coerceAtLeast(0))
            focusOnArrival(groupFocus)
            arrived = true
        }
    }

    var lastGroup by remember { mutableStateOf(group) }
    LaunchedEffect(group) {
        if (group != lastGroup) {
            lastGroup = group
            channelState.scrollToItem(0)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LiveBackdrop(focused?.name, Modifier.fillMaxSize())
        Row(Modifier.fillMaxSize().padding(top = dpx(24))) {
            // ◄ depuis les chaînes revient sur le groupe ouvert, pas sur celui qui se trouve à la même hauteur.
            LazyColumn(
                Modifier.width(dpx(320)).fillMaxHeight().focusRestorer(),
                state = groupState,
                contentPadding = PaddingValues(start = dpx(20), end = dpx(10), bottom = dpx(40)),
                verticalArrangement = Arrangement.spacedBy(dpx(4)),
            ) {
                itemsIndexed(groups, key = { _, it -> it.key }) { _, g ->
                    val own = remember { FocusRequester() }
                    CategoryRow(
                        g.title, if (g.kind == GroupKind.FAVORITES) -1 else g.count, g.locked, g.key == group,
                        Modifier.focusRequester(if (g.key == group) groupFocus else own),
                        // Une catégorie verrouillée ne s'ouvre qu'avec OK et le code, jamais au passage du focus.
                        onFocus = { if (!g.locked) focusedGroup = g.key },
                        onClick = { gate(g.locked) { vm.selectGroup(g.key) } },
                    )
                }
            }
            Column(Modifier.weight(0.9f).fillMaxHeight().padding(start = dpx(10), end = dpx(16))) {
                Row(Modifier.padding(bottom = dpx(14)), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
                    Text(
                        groups.firstOrNull { it.key == group }?.title.orEmpty(), color = Tok.TextHi, fontSize = spx(30),
                        fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    channels?.let { Text(channelCount(it.size), color = Tok.TextLow, fontSize = spx(17), maxLines = 1) }
                }
                val list = channels
                when {
                    list == null -> Unit
                    list.isEmpty() -> EmptyState(emptyTitle(group), emptyMessage(group), Icons.Default.Tv, Modifier.fillMaxSize())
                    // ► depuis les groupes revient sur la dernière chaîne parcourue, sinon sur celle en cours.
                    else -> LazyColumn(
                        Modifier.focusRestorer(channelFocus),
                        state = channelState,
                        verticalArrangement = Arrangement.spacedBy(dpx(6)),
                        contentPadding = PaddingValues(start = dpx(8), end = dpx(8), top = dpx(4), bottom = dpx(40)),
                    ) {
                        itemsIndexed(list, key = { _, ch -> ch.uid }) { i, ch ->
                            val own = remember { FocusRequester() }
                            ChannelLine(
                                ch, now[ch.epgKey], ch.uid == playing, showNumbers, clock,
                                Modifier.focusRequester(if (i == target) channelFocus else own),
                                onFocus = { focusTarget = ch },
                                onClick = { if (ch.uid == playing) onOpenPlayer(ch, group) else vm.preview(ch) },
                                onLongClick = { menuFor = ch },
                            )
                        }
                    }
                }
            }
            Column(
                Modifier.weight(1.1f).fillMaxHeight().padding(start = dpx(14), end = dpx(36), bottom = dpx(24)),
                verticalArrangement = Arrangement.spacedBy(dpx(18)),
            ) {
                val shape = RoundedCornerShape(dpx(18))
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Color.Black).border(1.dp, Tok.Line, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (playing != null) {
                        VideoSurface(c.player, Modifier.fillMaxSize())
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(dpx(10))) {
                            Icon(Icons.Default.LiveTv, null, Modifier.size(dpx(52)), tint = Tok.TextLow)
                            Text(
                                "OK pour l'aperçu, OK encore pour le plein écran", color = Tok.TextLow, fontSize = spx(17),
                                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = dpx(24)),
                            )
                        }
                    }
                    error?.let {
                        Text(
                            it, color = Color.White, fontSize = spx(16),
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(dpx(12)),
                        )
                    }
                }
                focused?.let { LiveNowPanel(it, programs, clock) }
            }
        }
    }
}

/** « 1 chaîne », « 12 chaînes ». */
fun channelCount(n: Int): String = if (n > 1) "$n chaînes" else "$n chaîne"

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

private val LiveScrimX = listOf(0f to 1f, 0.3f to .9f, 0.55f to .35f, 1f to .15f)
private val LiveScrimY = listOf(0.55f to 0f, 1f to .9f)

/** Fond du direct : une lueur à la couleur de la chaîne sélectionnée, assombrie vers la gauche et vers le bas. */
@Composable
private fun LiveBackdrop(name: String?, modifier: Modifier) {
    val base = Bg
    val glowMain = remember { bakeGlow(0.78f, 0.35f, 0.73f) }
    val glowSide = remember { bakeGlow(1f, 0.83f, 0.47f) }
    val scrim = remember(base) { bakeScrim(base, LiveScrimX, LiveScrimY) }
    val tint by animateColorAsState(
        if (name != null) colorFor(name) else base,
        tween(if (Look.motion == 0) 0 else 700),
        label = "liveBg",
    )
    Box(
        modifier.background(base).drawBehind {
            val dst = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())
            drawImage(glowMain, dstSize = dst, colorFilter = ColorFilter.tint(tint.copy(alpha = 0.55f)))
            drawImage(glowSide, dstSize = dst, colorFilter = ColorFilter.tint(lerp(tint, Color.White, 0.2f).copy(alpha = 0.22f)))
            drawImage(scrim, dstSize = dst)
        },
    )
}

/** Une ligne de chaîne : numéro, logo, nom, programme en cours et progression. */
@Composable
private fun ChannelLine(
    ch: ChannelEntity,
    program: ProgramEntity?,
    playing: Boolean,
    showNumber: Boolean,
    clock: Long,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    CardFrame(
        modifier.fillMaxWidth().height(dpx(96)), SolidColor(if (playing) Accent1.copy(alpha = 0.2f) else Color(0x12FFFFFF)),
        1.02f, 14, onFocus, onClick, onLongClick,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = dpx(12)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
            if (showNumber) {
                Text(
                    if (ch.number > 0) "${ch.number}" else "", color = Tok.TextLow, fontSize = spx(18), maxLines = 1,
                    textAlign = TextAlign.End, modifier = Modifier.width(dpx(46)),
                )
            }
            Box(Modifier.size(width = dpx(92), height = dpx(66)).clip(RoundedCornerShape(dpx(10))).background(Color(0x14FFFFFF)), contentAlignment = Alignment.Center) {
                NetImage(ch.logo, Modifier.fillMaxSize().padding(dpx(6)), fallback = ch.name)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dpx(3))) {
                Text(ch.name, color = if (playing) Accent2 else Tok.TextHi, fontSize = spx(20), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (program != null) {
                    Text(program.title, color = Tok.TextMid, fontSize = spx(16), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ProgressBar(programProgress(program.start, program.stop, clock), Accent2)
                }
            }
            if (ch.catchupDays > 0) Icon(Icons.Default.History, "Replay", Modifier.size(dpx(24)), tint = Accent2)
            if (playing) Icon(Icons.AutoMirrored.Filled.VolumeUp, "En lecture", Modifier.size(dpx(26)), tint = Accent1)
        }
    }
}

/** Ce qui passe sur la chaîne sélectionnée : titre, résumé, progression et les deux programmes suivants. */
@Composable
private fun LiveNowPanel(ch: ChannelEntity, programs: List<ProgramEntity>, clock: Long) {
    val current = programs.firstOrNull { it.start <= clock }
    val next = programs.filter { it !== current }.take(2)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dpx(12))) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
            LiveBadge()
            Text(ch.name, color = Tok.TextMid, fontSize = spx(19), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            current?.title ?: ch.name, color = Tok.TextHi, fontSize = spx(40), lineHeight = spx(44), fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.6).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (current != null) {
            if (current.description.isNotBlank()) {
                Text(current.description, color = Tok.TextMid, fontSize = spx(18), lineHeight = spx(26), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
                Text(Fmt.time(current.start), color = Tok.TextLow, fontSize = spx(16), maxLines = 1)
                ProgressBar(programProgress(current.start, current.stop, clock), Accent1, Modifier.weight(1f), h = 6)
                Text(Fmt.time(current.stop), color = Tok.TextLow, fontSize = spx(16), maxLines = 1)
            }
        } else {
            Text("Pas d'information de programme", color = Tok.TextLow, fontSize = spx(18))
        }
        if (next.isNotEmpty()) {
            Row(Modifier.padding(top = dpx(4)), horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
                next.forEachIndexed { i, p ->
                    val shape = RoundedCornerShape(dpx(16))
                    Column(
                        Modifier.weight(1f).clip(shape).background(Color(0x12FFFFFF)).border(1.dp, Color(0x1AFFFFFF), shape).padding(dpx(14)),
                        verticalArrangement = Arrangement.spacedBy(dpx(4)),
                    ) {
                        Text(
                            (if (i == 0) "Ensuite · " else "") + Fmt.time(p.start), color = if (i == 0) Accent2 else Tok.TextLow,
                            fontSize = spx(15), fontWeight = FontWeight.Bold, maxLines = 1,
                        )
                        Text(p.title, color = Tok.TextHi, fontSize = spx(18), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** « EN DIRECT » rouge, avec un point qui pulse tant que les animations sont activées. */
@Composable
fun LiveBadge() {
    Row(
        Modifier.clip(RoundedCornerShape(dpx(8))).background(Color(0xFFE11D48)).padding(horizontal = dpx(12), vertical = dpx(4)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dpx(8)),
    ) {
        val dot = Modifier.size(dpx(9))
        if (Look.motion == 0) Box(dot.clip(CircleShape).background(Color.White)) else PulsingDot(dot)
        Text("EN DIRECT", color = Color.White, fontSize = spx(15), fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp, maxLines = 1)
    }
}

@Composable
private fun PulsingDot(modifier: Modifier) {
    // Un clignotement par paliers : une animation continue ferait redessiner tout l'écran 60 fois par seconde.
    var bright by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(800)
            bright = !bright
        }
    }
    Box(modifier.graphicsLayer { alpha = if (bright) 1f else 0.3f }.clip(CircleShape).background(Color.White))
}
