// Dérivé de Sidebar.kt d'IPVIO Stream, Copyright (C) 2026 IPVIO Stream contributors, GNU GPL 3.
// Modifié pour Vyzo le 2026-11-08 : entrées, profils, coffre-fort et quatre positions de Vyzo, icônes Material, libellés en français.
package fr.yannickuhrig.vyzo.tv

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.ui.Dest
import fr.yannickuhrig.vyzo.ui.MenuArgs
import fr.yannickuhrig.vyzo.ui.NavLayout
import fr.yannickuhrig.vyzo.ui.NavPosition
import fr.yannickuhrig.vyzo.ui.NavStyle
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.Routes
import fr.yannickuhrig.vyzo.ui.common.VyzoMark
import fr.yannickuhrig.vyzo.ui.common.VyzoWordmark
import fr.yannickuhrig.vyzo.ui.profile.PinDialog
import fr.yannickuhrig.vyzo.ui.profile.ProfileAvatar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val MenuBg: Color get() = lerp(Look.bg, Color.Black, 0.25f)
private val MenuInk = Color(0xFFCFCFD6)

/** Pour entrer dans l'écran depuis le menu : la direction qui s'éloigne du bord où il se trouve. */
internal fun menuExitDirection(position: NavPosition): FocusDirection = when (position) {
    NavPosition.LEFT -> FocusDirection.Right
    NavPosition.RIGHT -> FocusDirection.Left
    NavPosition.TOP -> FocusDirection.Down
    NavPosition.BOTTOM -> FocusDirection.Up
}

/** Menu principal de la TV, placé selon Réglages → Interface : rail replié, ouvert ou caché, ou barre horizontale. */
@Composable
fun TvMenu(args: MenuArgs) {
    TrackDesignScale()
    var inside by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val out = menuExitDirection(args.layout.position)

    // Après OK, le focus passe dans l'écran choisi ; son contenu peut mettre un instant à arriver.
    val pick: (String) -> Unit = { route ->
        args.onSelect(route)
        scope.launch {
            delay(200)
            repeat(14) { attempt ->
                if (!inside) return@launch
                // Un écran qui place lui-même son focus passe avant : on lui laisse un peu d'avance.
                if (attempt >= 4) focusManager.moveFocus(out)
                delay(100)
            }
        }
    }

    val group = Modifier
        .onFocusChanged {
            if (it.hasFocus != inside) {
                inside = it.hasFocus
                args.onFocusInside(it.hasFocus)
            }
        }
        // Depuis l'écran, on arrive sur l'entrée de l'écran affiché, pas sur celle qui se trouve à la même hauteur.
        .focusProperties {
            onEnter = {
                if (requestedFocusDirection != FocusDirection.Enter) {
                    (args.requesters[args.route] ?: args.requesters[Routes.SETTINGS])?.let { runCatching { it.requestFocus() } }
                }
            }
        }
        .focusGroup()

    if (args.layout.isRail) TvRail(args, inside, group, pick) else TvBar(args, group, pick)
}

private val MenuShadeLeft = listOf(0f to .95f, 0.55f to .7f, 1f to 0f)
private val MenuShadeRight = listOf(0f to 0f, 0.45f to .7f, 1f to .95f)
private val NoShade = listOf(0f to 0f, 1f to 0f)

@Composable
private fun TvRail(args: MenuArgs, inside: Boolean, group: Modifier, pick: (String) -> Unit) {
    val c = LocalContainer.current
    val profile by c.profiles.active.collectAsState()
    val unlocked by c.isVaultUnlocked.collectAsState()
    val style = args.layout.style
    val right = args.layout.position == NavPosition.RIGHT
    val expanded = inside || style == NavStyle.EXPANDED
    val closed = if (style == NavStyle.HIDDEN) 0.dp else NavLayout.RAIL_CLOSED
    val width by animateDpAsState(
        if (expanded) NavLayout.RAIL_OPEN else closed,
        if (Look.motion == 0) snap() else tween(220),
        label = "rail",
    )
    val side = if (right) Alignment.CenterEnd else Alignment.CenterStart

    Box(args.modifier.fillMaxHeight()) {
        // Rail ouvert par-dessus l'écran : le contenu s'assombrit derrière, pour que les noms restent lisibles.
        if (inside && style != NavStyle.EXPANDED) {
            BakedScrim(if (right) MenuShadeRight else MenuShadeLeft, NoShade, Modifier.width(width + dpx(260)).fillMaxHeight().align(side), Color.Black)
        }
        Column(
            Modifier.width(width).fillMaxHeight().align(side)
                // Rail caché : rien ne dépasse de sa largeur nulle.
                .then(if (style == NavStyle.HIDDEN && !expanded) Modifier.clipToBounds() else Modifier)
                .background(MenuBg)
                .drawBehind {
                    val x = if (right) 0f else size.width - 1f
                    drawLine(Color(0x1AFFFFFF), Offset(x, 0f), Offset(x, size.height), 1f)
                }
                .then(group)
                .padding(horizontal = 10.dp, vertical = dpx(20)),
            verticalArrangement = Arrangement.spacedBy(dpx(6)),
        ) {
            VaultLogo(expanded, Modifier.padding(start = 6.dp).padding(bottom = dpx(10)))
            if (unlocked) {
                MenuItem(Icons.Default.LockOpen, "Reverrouiller", active = false, expanded, Modifier.fillMaxWidth().height(dpx(58))) { c.lockVault() }
            }
            profile?.let { p ->
                MenuRow(active = false, Modifier.fillMaxWidth().height(dpx(84)), start = 11.dp, onClick = args.onProfile) { focused ->
                    ProfileAvatar(p.name, p.colorIndex, 30.dp, p.isKid, avatar = p.avatar)
                    if (expanded) {
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                p.name, color = if (focused) Tok.FocusInk else Color.White, fontSize = spx(18), fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text("Changer de profil", color = if (focused) Tok.FocusInk.copy(alpha = 0.7f) else Tok.TextLow, fontSize = spx(14), maxLines = 1)
                        }
                    }
                }
            }
            args.entries.forEach { d ->
                MenuItem(
                    d.icon, d.label, active = args.route == d.route, expanded,
                    entryModifier(args, d).fillMaxWidth().height(dpx(58)),
                ) { pick(d.route) }
            }
        }
    }
}

/** Barre en haut ou en bas : le nom ne s'affiche que sur l'entrée choisie, pour que tout tienne sur la largeur. */
@Composable
private fun TvBar(args: MenuArgs, group: Modifier, pick: (String) -> Unit) {
    val c = LocalContainer.current
    val profile by c.profiles.active.collectAsState()
    val unlocked by c.isVaultUnlocked.collectAsState()
    val top = args.layout.position == NavPosition.TOP
    Row(
        args.modifier.fillMaxWidth().height(NavLayout.BAR_HEIGHT)
            .background(MenuBg)
            .drawBehind {
                val y = if (top) size.height - 1f else 0f
                drawLine(Color(0x1AFFFFFF), Offset(0f, y), Offset(size.width, y), 1f)
            }
            .then(group)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dpx(8)),
    ) {
        VaultLogo(expanded = false, Modifier.padding(end = dpx(14)))
        args.entries.forEach { d ->
            val active = args.route == d.route
            var focused by remember { mutableStateOf(false) }
            MenuItem(
                d.icon, d.label, active, expanded = focused || active,
                entryModifier(args, d).height(dpx(62)).onFocusChanged { focused = it.isFocused },
                end = 14.dp,
            ) { pick(d.route) }
        }
        Spacer(Modifier.weight(1f))
        if (unlocked) MenuItem(Icons.Default.LockOpen, "Reverrouiller", active = false, expanded = false, Modifier.height(dpx(62)), end = 14.dp) { c.lockVault() }
        profile?.let { p ->
            MenuRow(active = false, Modifier.height(dpx(62)), start = 6.dp, end = 6.dp, onClick = args.onProfile) {
                ProfileAvatar(p.name, p.colorIndex, 30.dp, p.isKid, avatar = p.avatar)
            }
        }
    }
}

private fun entryModifier(args: MenuArgs, d: Dest): Modifier =
    Modifier.focusRequester(args.requesters[d.route] ?: FocusRequester.Default)

@Composable
private fun MenuItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    expanded: Boolean,
    modifier: Modifier,
    end: androidx.compose.ui.unit.Dp = 0.dp,
    onClick: () -> Unit,
) {
    MenuRow(active, modifier, start = 16.dp, end = end, onClick = onClick) { focused ->
        val ink = if (focused) Tok.FocusInk else if (active) Accent1 else MenuInk
        Icon(icon, label.takeUnless { expanded }, Modifier.size(24.dp), tint = ink)
        if (expanded) {
            Spacer(Modifier.width(14.dp))
            Text(
                label, color = if (focused) Tok.FocusInk else if (active) Accent1 else Color.White, fontSize = spx(19),
                fontWeight = if (active || focused) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
            )
        }
    }
}

/** Ligne du menu : fond blanc à la sélection, teinte de l'accent sur l'écran affiché. */
@Composable
private fun MenuRow(
    active: Boolean,
    modifier: Modifier,
    start: androidx.compose.ui.unit.Dp,
    end: androidx.compose.ui.unit.Dp = 0.dp,
    onClick: () -> Unit,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(12))
    Row(
        modifier.clip(shape)
            .background(if (focused) Tok.FocusFill else if (active) Accent1.copy(alpha = .16f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(start = start, end = end),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(focused) }
}

/** Logo du menu : trois validations rapides ou un appui long demandent le code du coffre-fort. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultLogo(expanded: Boolean, modifier: Modifier) {
    val c = LocalContainer.current
    val pinSet by c.parental.pinSet.collectAsState()
    var askPin by remember { mutableStateOf(false) }
    var clicks by remember { mutableIntStateOf(0) }
    var lastClick by remember { mutableLongStateOf(0L) }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(10))
    Row(
        modifier.height(dpx(52)).clip(shape)
            // Sans code coffre-fort, le logo n'est pas interactif et ne doit jamais capter le focus par défaut.
            .focusProperties { canFocus = pinSet }
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) Tok.FocusFill else Color.Transparent, shape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = { if (pinSet) askPin = true },
                onClick = {
                    val now = System.currentTimeMillis()
                    clicks = if (now - lastClick < 500) clicks + 1 else 1
                    lastClick = now
                    if (clicks >= 3) {
                        clicks = 0
                        if (pinSet) askPin = true
                    }
                },
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expanded) {
            VyzoWordmark(markSize = 30.dp, fontSize = 22)
            Spacer(Modifier.width(10.dp))
            Text("v${c.platform.appVersion}", color = Tok.TextLow, fontSize = spx(14), maxLines = 1)
        } else {
            VyzoMark(30.dp)
        }
    }
    if (askPin) {
        PinDialog(title = "Coffre-fort", subtitle = "Saisis ton code pour afficher les contenus masqués", onDismiss = { askPin = false }) { pin ->
            val ok = c.unlockVault(pin)
            if (ok) askPin = false
            ok
        }
    }
}
