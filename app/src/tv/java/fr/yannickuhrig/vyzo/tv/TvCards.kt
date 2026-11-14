// Dérivé de HomeParts.kt, Billboard.kt, Visuals.kt, Nav.kt et Components.kt d'IPVIO Stream, Copyright (C) 2026 IPVIO Stream contributors, GNU GPL 3.
// Modifié pour Vyzo le 2026-11-08 : cartes alimentées par les rangées de Vyzo, appui long pour le menu des chaînes, libellés en français.
package fr.yannickuhrig.vyzo.tv

import fr.yannickuhrig.vyzo.ui.common.colorFor
import fr.yannickuhrig.vyzo.ui.common.PosterTitleArt
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.ui.common.Badge
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.NetImage
import fr.yannickuhrig.vyzo.ui.common.repeatCount
import fr.yannickuhrig.vyzo.ui.home.AppItem
import fr.yannickuhrig.vyzo.ui.home.ChannelItem
import fr.yannickuhrig.vyzo.ui.home.OnAirItem
import fr.yannickuhrig.vyzo.ui.home.VodItem
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Largeur des cartes : chaque rangée occupe toute la largeur avec des colonnes égales. */
class CardGrid(val land: Dp, val poster: Dp, val chan: Dp, val air: Dp, val gap: Dp, val posterGap: Dp)

val LocalCardGrid = compositionLocalOf { CardGrid(155.dp, 109.dp, 186.dp, 310.dp, 9.dp, 8.dp) }

fun cardGrid(contentW: Dp): CardGrid {
    val gap = dpx(16)
    val pgap = dpx(14)
    fun col(n: Int, g: Dp) = (contentW - g * (n - 1)) / n
    return CardGrid(land = col(6, gap), poster = col(7, pgap), chan = col(5, gap), air = col(3, gap), gap = gap, posterGap = pgap)
}

/** Vrai tant que la rangée de la carte a le focus : les autres cartes s'effacent alors un peu. */
val LocalRowFocus = compositionLocalOf { false }

/** Luminosité des cartes voisines quand leur rangée a le focus (1 : aucune atténuation). */
val LocalDimLevel = compositionLocalOf { 0.5f }

/** Carte à resélectionner au retour d'une fiche : la télécommande reprend là où elle était. */
object LastFocus {
    var pending: String? = null
}

@Composable
fun Modifier.restoreFocus(key: String): Modifier {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (LastFocus.pending == key) {
            delay(150)
            runCatching { fr.requestFocus() }
            LastFocus.pending = null
        }
    }
    return this.focusRequester(fr)
}

/** Petit rebond d'un bouton quand on le valide. */
class Pulse(private val anim: Animatable<Float, AnimationVector1D>, private val scope: CoroutineScope) {
    val scale: Float get() = anim.value

    fun pulse() {
        if (Look.motion == 0) return
        scope.launch {
            anim.snapTo(0.93f)
            anim.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
        }
    }
}

@Composable
fun rememberPulse(): Pulse {
    val scope = rememberCoroutineScope()
    val anim = remember { Animatable(1f) }
    return remember { Pulse(anim, scope) }
}

/** Grand bouton en pastille : verre sombre au repos, fond blanc une fois sélectionné. */
@Composable
fun HeroButton(text: String, icon: ImageVector, primary: Boolean, modifier: Modifier = Modifier, h: Int = 50, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val pulse = rememberPulse()
    val s by animateFloatAsState(if (f) focusScale(1.06f) else 1f, label = "herobtn")
    val shape = RoundedCornerShape(50)
    val ink = if (f) Tok.FocusInk else Color.White
    Row(
        modifier.graphicsLayer { val k = s * pulse.scale; scaleX = k; scaleY = k }.height(dpx(h)).clip(shape)
            .background(if (f) Tok.FocusFill else Color(0x33FFFFFF))
            .border(1.dp, if (f) Tok.FocusFill else Color(0x40FFFFFF), shape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = { pulse.pulse(); onClick() })
            .padding(horizontal = dpx(h * 0.6f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dpx(h * 0.28f)),
    ) {
        Icon(icon, null, Modifier.size(dpx(h * 0.4f)), tint = if (f) Tok.FocusInk else if (primary) Accent1 else Color.White)
        Text(text, color = ink, fontSize = spx(h * 0.34f), fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Deux premières initiales d'un titre, pour les cartes sans image. */
fun initialsOf(title: String): String =
    title.split(' ', '-', ':').filter { it.isNotBlank() }.take(2).map { it.first().uppercaseChar() }.joinToString("")

/** Remplaçant d'une affiche absente ou en échec : initiales, type de contenu et titre. */
@Composable
fun ArtPlaceholder(title: String, kind: ContentKind, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val base = colorFor(title)
    Box(modifier.background(Brush.linearGradient(listOf(lerp(base, Color.Black, 0.15f), lerp(Look.bg, base, 0.22f), Look.bg)))) {
        Text(
            initialsOf(title), color = Color.White.copy(alpha = 0.14f), fontSize = spx(54), fontWeight = FontWeight.Bold,
            maxLines = 1, modifier = Modifier.align(Alignment.Center),
        )
        Icon(
            if (kind == ContentKind.MOVIE) Icons.Default.Movie else Icons.Default.Tv, null,
            Modifier.align(Alignment.TopEnd).padding(dpx(10)).size(dpx(20)), tint = Color.White.copy(alpha = 0.55f),
        )
        if (showTitle) {
            Text(
                title, color = Tok.TextHi, fontSize = spx(16), fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(dpx(12)),
            )
        }
    }
}

/** Image TMDB à une taille adaptée aux cartes. */
fun cardBackdrop(url: String): String = url.replace("/t/p/w1280/", "/t/p/w780/")

// Bordure fine : quelques pixels à peine, contrairement à un dégradé posé sur toute la carte.
private val RestBorder = Brush.linearGradient(listOf(Color(0x47FFFFFF), Color(0x14FFFFFF), Color(0x0AFFFFFF)))
private val FocusBorder = SolidColor(Color.White)

/**
 * Cadre commun des cartes : agrandissement et contour blanc à la sélection, voisines atténuées dans la rangée active.
 * OK maintenu ou touche Menu ouvre [onLongClick], comme sur le reste de l'appli.
 */
@Composable
internal fun CardFrame(
    modifier: Modifier,
    background: Brush,
    focusScaleTarget: Float,
    radius: Int,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    /** Contour coloré à la sélection, avec un halo de la même couleur (sinon contour blanc et ombre noire). */
    ring: Color? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var f by remember { mutableStateOf(false) }
    var longHandled by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(focusScaleTarget) else 1f, label = "card")
    val shape = RoundedCornerShape(dpx(radius))
    val rowF = LocalRowFocus.current
    val dimTo = LocalDimLevel.current
    val dimA by animateFloatAsState(if (rowF && !f) dimTo else 1f, tween(if (Look.motion == 0) 0 else 320), label = "dim")
    Box(
        modifier.graphicsLayer { scaleX = s; scaleY = s }
            .then(if (ring != null) Modifier.ringGlow(f, shape, ring) else Modifier.focusGlow(f, shape)).clip(shape)
            .background(background)
            .border(
                if (f) (if (ring != null) maxOf(dpx(4), 2.dp) else 2.dp) else 1.dp,
                if (f) (if (ring != null) SolidColor(ring) else FocusBorder) else RestBorder, shape,
            )
            .onFocusChanged {
                if (it.isFocused != f) {
                    f = it.isFocused
                    if (it.isFocused) onFocus()
                }
            }
            .onPreviewKeyEvent { ev ->
                val ok = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                val repeat = ev.repeatCount
                when {
                    onLongClick == null -> false
                    ev.type == KeyEventType.KeyDown && ok && repeat == 1 -> { longHandled = true; onLongClick(); true }
                    ev.type == KeyEventType.KeyDown && ok && repeat > 1 -> true
                    ev.type == KeyEventType.KeyUp && ok && longHandled -> { longHandled = false; true }
                    ev.type == KeyEventType.KeyUp && ev.key == Key.Menu -> { onLongClick(); true }
                    else -> false
                }
            }
            .clickable(onClick = onClick),
    ) {
        content(f)
        // Atténuation dessinée par-dessus : moins coûteux qu'une transparence sur toute la carte.
        Box(Modifier.matchParentSize().drawBehind { val d = 1f - dimA; if (d > 0.01f) drawRect(Color.Black.copy(alpha = d)) })
    }
}

/** Halo de la couleur du contour autour de la carte sélectionnée. */
private fun Modifier.ringGlow(focused: Boolean, shape: androidx.compose.ui.graphics.Shape, color: Color): Modifier =
    if (!focused) this else this.shadow(dpx(30), shape, clip = false, ambientColor = color, spotColor = color)

private fun artBackground(title: String) = Brush.linearGradient(listOf(colorFor(title).copy(alpha = .75f), Surf2))

/** Affiche sans texte. Le remplaçant reste dessous : il apparaît si l'image manque ou ne se charge pas. */
@Composable
fun PosterTile(item: VodItem, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, titleInside: Boolean = true) {
    CardFrame(modifier.aspectRatio(2f / 3f), artBackground(item.title), 1.07f, 8, onFocus, onClick, ring = Accent1) { focused ->
        val caption = focused && titleInside
        // Sans image : une affiche dessinée, teintée par le genre, avec le titre en capitales (caché sous la légende).
        PosterTitleArt(item.title, item.genre, Modifier.fillMaxSize(), showTitle = !caption)
        if (item.poster.isNotBlank()) AsyncImage(item.poster, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (item.uhd) CardBadge("4K", Color.White, Modifier.align(Alignment.TopStart).padding(dpx(8)))
        // Certains catalogues donnent des notes hors échelle.
        if (item.rating > 0f && item.rating <= 10f) CardBadge("★ %.1f".format(item.rating), Accent1, Modifier.align(Alignment.TopEnd).padding(dpx(8)))
        if (!caption && item.badge.isNotBlank()) {
            val bg = if (item.badgeUpcoming) Color(0xEB2563EB) else Accent1
            val fg = if (item.badgeUpcoming) Color.White else Color.Black
            CardBadge(item.badge, fg, Modifier.align(Alignment.BottomStart).padding(dpx(8)), background = bg, bordered = false)
        }
        if (caption) PosterCaption(item, Modifier.align(Alignment.BottomStart))
    }
}

/** Petite étiquette posée sur une affiche. */
@Composable
private fun CardBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    background: Color = Color(0x99000000),
    bordered: Boolean = true,
) {
    Text(
        text, color = color, fontSize = spx(13), lineHeight = spx(16), fontWeight = FontWeight.Bold, maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.background(background, RoundedCornerShape(dpx(6)))
            .then(if (bordered) Modifier.border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(dpx(6))) else Modifier)
            .padding(horizontal = dpx(7), vertical = dpx(2)),
    )
}

/** Titre de l'affiche sélectionnée, sur un dégradé : une seule carte à la fois le dessine. */
@Composable
private fun PosterCaption(item: VodItem, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to Color(0xD9000000), 1f to Color(0xF5070709)))
            .padding(start = dpx(10), end = dpx(10), top = dpx(34), bottom = dpx(10)),
    ) {
        if (item.badge.isNotBlank()) {
            CardBadge(
                item.badge,
                color = if (item.badgeUpcoming) Color.White else Color.Black,
                modifier = Modifier.padding(bottom = dpx(4)),
                background = if (item.badgeUpcoming) Color(0xEB2563EB) else Accent1,
                bordered = false,
            )
        }
        Text(item.title, color = Color.White, fontSize = spx(15), lineHeight = spx(19), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = listOf(item.year, firstGenre(item.genre)).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, color = Tok.TextMid, fontSize = spx(13), lineHeight = spx(17), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Carte paysage : image, puis titre, genre, année et note. */
@Composable
fun LandscapeCard(item: VodItem, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val img = item.backdrop.ifBlank { item.poster }
    CardFrame(modifier.aspectRatio(1.3f), artBackground(item.title), 1.07f, 8, onFocus, onClick) {
        ArtPlaceholder(item.title, item.kind, Modifier.fillMaxSize(), showTitle = false)
        if (img.isNotBlank()) AsyncImage(cardBackdrop(img), item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xF5070709))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = dpx(12), end = dpx(12), bottom = dpx(10))) {
            Text(item.title, color = Color.White, fontSize = spx(16), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = dpx(4)), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOf(item.year, firstGenre(item.genre)).filter { it.isNotBlank() }.joinToString(" · "),
                    color = Tok.TextLow, fontSize = spx(13), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (item.rating > 0f) {
                    Icon(Icons.Default.Star, null, Modifier.size(dpx(14)), tint = Accent1)
                    Spacer(Modifier.width(dpx(5)))
                    Text("%.1f".format(item.rating), color = Color.White, fontSize = spx(13), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (item.uhd) CardBadge("4K", Color.White, Modifier.align(Alignment.TopStart).padding(dpx(8)))
        if (item.badge.isNotBlank()) {
            CardBadge(
                item.badge,
                color = if (item.badgeUpcoming) Color.White else Color.Black,
                modifier = Modifier.align(Alignment.TopEnd).padding(dpx(8)),
                background = if (item.badgeUpcoming) Color(0xEB2563EB) else Accent1,
                bordered = false,
            )
        }
        if (item.progress > 0f) ProgressBar(item.progress, Accent1, Modifier.align(Alignment.BottomStart), h = 4)
    }
}

/** Premier genre d'une liste « Drame, Action » ou « Drame / Action ». */
fun firstGenre(genre: String): String = genre.split(',', '/', '|').firstOrNull()?.trim().orEmpty()

/** Avancement d'un programme entre 0 et 1. */
fun programProgress(start: Long, stop: Long, now: Long): Float =
    if (stop > start) ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f) else 0f

@Composable
internal fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, h: Int = 3) {
    Box(modifier.fillMaxWidth().height(dpx(h)).clip(RoundedCornerShape(50)).background(Color(0x26FFFFFF))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color))
    }
}

/** Carte d'une chaîne : logo, nom, programme en cours, horaire et progression. */
@Composable
fun ChannelNowCard(item: ChannelItem, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val ch = item.channel
    val p = item.now
    CardFrame(modifier, SolidColor(Surf), 1.06f, 10, onFocus, onClick, onLongClick) {
        Column(
            Modifier.fillMaxWidth().padding(start = dpx(14), end = dpx(14), top = dpx(10), bottom = dpx(12)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NetImage(ch.logo, Modifier.fillMaxWidth().height(dpx(56)).padding(vertical = dpx(2)), fallback = ch.name)
            Text(ch.name, color = Color.White, fontSize = spx(15), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = dpx(6)))
            Text(p?.title ?: "Pas de programme", color = Tok.TextLow, fontSize = spx(13), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth().padding(top = dpx(6)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(6))) {
                Box(Modifier.size(dpx(7)).clip(CircleShape).background(Accent1))
                Text(if (p != null) Fmt.range(p.start, p.stop) else "En direct", color = Accent2, fontSize = spx(12), maxLines = 1)
            }
            ProgressBar(if (p != null) programProgress(p.start, p.stop, System.currentTimeMillis()) else 0f, Accent1, Modifier.padding(top = dpx(6)))
        }
    }
}

/** Grande carte « À l'antenne » : image du programme, chaîne, titre, horaire, progression et suite. */
@Composable
fun OnAirCard(item: OnAirItem, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val ch = item.channel
    val p = item.now
    val now = System.currentTimeMillis()
    CardFrame(modifier.aspectRatio(16f / 9f), SolidColor(Surf), 1.04f, 14, onFocus, onClick, onLongClick) {
        if (p.icon.isNotBlank()) AsyncImage(p.icon, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.45f to Color.Black.copy(alpha = 0.45f), 1f to Color.Black.copy(alpha = 0.92f))))
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(dpx(14)), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(dpx(8))).background(Color.White.copy(alpha = 0.92f)).padding(horizontal = dpx(8), vertical = dpx(4))) {
                NetImage(ch.logo, Modifier.size(width = dpx(76), height = dpx(42)), fallback = ch.name)
            }
            Spacer(Modifier.width(dpx(12)))
            Text(ch.name, color = Color.White, fontSize = spx(17), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Badge("EN DIRECT", VyzoColors.Red)
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(dpx(14))) {
            Text(p.title, color = Color.White, fontSize = spx(26), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val left = ((p.stop - now) / 60_000L).coerceAtLeast(0)
            val also = if (item.alsoOn > 0) " · aussi sur ${item.alsoOn} autre${if (item.alsoOn > 1) "s" else ""} chaîne${if (item.alsoOn > 1) "s" else ""}" else ""
            Text("${Fmt.range(p.start, p.stop)} · encore $left min$also", color = Color.White.copy(alpha = 0.8f), fontSize = spx(15), maxLines = 1, overflow = TextOverflow.Ellipsis)
            ProgressBar(programProgress(p.start, p.stop, now), VyzoColors.Red, Modifier.padding(top = dpx(6)), h = 4)
            item.next?.let { n ->
                Text("Ensuite · ${Fmt.time(n.start)} ${n.title}", color = Color.White.copy(alpha = 0.7f), fontSize = spx(14), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = dpx(5)))
            }
        }
    }
}

/** Carte d'une application installée : sa bannière TV, sinon son icône et son nom. */
@Composable
fun AppCard(item: AppItem, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val app = item.app
    val icon = app.icon
    CardFrame(modifier.aspectRatio(16f / 9f), SolidColor(Surf), 1.06f, 10, onFocus, onClick) {
        if (icon != null && app.banner) {
            Image(icon, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (icon != null) Image(icon, null, Modifier.size(dpx(64))) else Icon(Icons.Default.Apps, null, Modifier.size(dpx(52)), tint = Color.White)
                Text(app.label, color = Color.White, fontSize = spx(14), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = dpx(4)))
            }
        }
    }
}
