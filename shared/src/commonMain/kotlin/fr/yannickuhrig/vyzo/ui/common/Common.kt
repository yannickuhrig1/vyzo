package fr.yannickuhrig.vyzo.ui.common

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class FormFactor { TV, PHONE }

val LocalFormFactor = staticCompositionLocalOf { FormFactor.PHONE }
val LocalContainer = staticCompositionLocalOf<AppContainer> { error("Pas de conteneur") }

/** true dans Vyzo PC : souris (survol, clic droit) et clavier en plus de la navigation au focus. */
val LocalDesktop = staticCompositionLocalOf { false }

/**
 * Dernier moyen de navigation utilisé : clavier ou télécommande (true), souris (false).
 * Sur PC, le rail de gauche ne reste ouvert au focus qu'en navigation au clavier.
 */
object InputMode {
    val keyboard = mutableStateOf(true)
}

@Composable
fun isTv(): Boolean = LocalFormFactor.current == FormFactor.TV

@Composable
fun isDesktop(): Boolean = LocalDesktop.current

/**
 * Élément cliquable adapté à la télécommande (contour + zoom au focus, appui long sur OK ou touche Menu)
 * comme au tactile (ondulation, appui long).
 */
@Composable
fun Tile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(10.dp),
    selected: Boolean = false,
    focusScale: Float = 1.03f,
    /** Sur PC, agrandissement au survol de la souris (1 : aucun relief). */
    hoverScale: Float = 1f,
    color: Color = Color.Transparent,
    focusedColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val tv = isTv()
    val desktop = isDesktop()
    var focused by remember { mutableStateOf(false) }
    var longHandled by remember { mutableStateOf(false) }
    // Sur PC, un clic donne aussi le focus (comme sur la télécommande) pour que l'aperçu suive la souris.
    val focusRequester = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lifted = desktop && hoverScale > 1f && hovered
    val scale by animateFloatAsState(
        when {
            focused && tv -> focusScale
            lifted -> hoverScale
            else -> 1f
        },
        label = "tileScale",
    )
    val bg = when {
        focused || lifted -> focusedColor
        selected -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> color
    }
    Box(
        modifier
            .zIndex(if (lifted) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (lifted) Modifier.shadow(16.dp, shape, clip = false) else Modifier)
            .clip(shape)
            .background(bg)
            .then(
                when {
                    focused && tv -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                    lifted -> Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), shape)
                    else -> Modifier
                },
            )
            .onFocusChanged {
                if (it.isFocused != focused) {
                    focused = it.isFocused
                    if (it.isFocused) onFocus?.invoke()
                }
            }
            .onPreviewKeyEvent { ev ->
                val isOk = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                when {
                    onLongClick == null -> false
                    ev.type == KeyEventType.KeyDown && isOk && ev.repeatCount == 1 -> {
                        longHandled = true
                        onLongClick()
                        true
                    }
                    ev.type == KeyEventType.KeyDown && isOk && ev.repeatCount > 1 -> true
                    ev.type == KeyEventType.KeyUp && isOk && longHandled -> {
                        longHandled = false
                        true
                    }
                    ev.type == KeyEventType.KeyUp && ev.key == Key.Menu -> {
                        onLongClick(); true
                    }
                    else -> false
                }
            }
            .then(
                if (desktop) {
                    Modifier
                        .focusRequester(focusRequester)
                        .hoverable(interaction)
                        .pointerInput(onLongClick) {
                            // Clic droit = menu contextuel (l'équivalent de l'appui long).
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed && onLongClick != null) {
                                        e.changes.forEach { it.consume() }
                                        onLongClick()
                                    }
                                }
                            }
                        }
                } else Modifier,
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = if (tv && !desktop) null else ripple(),
                onClick = {
                    if (desktop) runCatching { focusRequester.requestFocus() }
                    onClick()
                },
                onLongClick = if (tv) null else onLongClick,
            ),
    ) {
        content(focused)
    }
}

@Composable
fun NetImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    fallback: String = "",
    fallbackIcon: ImageVector? = null,
    /** Remplace le carré à initiales quand l'image manque ou ne se charge pas. */
    fallbackContent: (@Composable () -> Unit)? = null,
) {
    val missing: @Composable (Modifier) -> Unit = { m ->
        if (fallbackContent != null) Box(m) { fallbackContent() } else Fallback(m, fallback, fallbackIcon)
    }
    if (url.isNullOrBlank()) {
        missing(modifier)
        return
    }
    // Un AsyncImage simple : la variante à emplacements (Subcompose) crée une sous-composition par image, trop lent dans les longues listes.
    var failed by remember(url) { mutableStateOf(false) }
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier, propagateMinConstraints = true) {
        when {
            failed -> missing(Modifier.matchParentSize())
            !loaded -> Box(Modifier.matchParentSize().background(VyzoColors.SurfaceVariant.copy(alpha = 0.5f)))
        }
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = contentScale,
            onSuccess = { loaded = true },
            onError = { failed = true },
        )
    }
}

@Composable
private fun Fallback(modifier: Modifier, text: String, icon: ImageVector?) {
    Box(modifier.background(VyzoColors.SurfaceHigh), contentAlignment = Alignment.Center) {
        when {
            icon != null -> Icon(icon, null, tint = VyzoColors.TextDim)
            text.isNotBlank() -> Text(
                initials(text), style = MaterialTheme.typography.titleSmall, color = VyzoColors.TextDim,
                textAlign = TextAlign.Center, maxLines = 1,
            )
        }
    }
}

fun initials(name: String): String =
    name.split(' ', '-', '_').filter { it.isNotBlank() && it[0].isLetterOrDigit() }.take(2)
        .joinToString("") { it.take(1).uppercase() }.ifBlank { name.take(2).uppercase() }

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, color: Color? = null) {
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.12f))) {
        val fill = Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).clip(RoundedCornerShape(2.dp))
        Box(if (color != null) fill.background(color) else fill.background(VyzoBrand.Rise))
    }
}

@Composable
fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun Loading(modifier: Modifier = Modifier) = Centered(modifier) { CircularProgressIndicator() }

@Composable
fun EmptyState(title: String, message: String = "", icon: ImageVector? = null, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Centered(modifier.padding(24.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(44.dp), tint = VyzoColors.TextDim)
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim, textAlign = TextAlign.Center)
            action?.invoke()
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Locale.FRENCH),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
fun Badge(text: String, color: Color = MaterialTheme.colorScheme.secondary, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.Black,
        maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(color).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun IconLabel(icon: ImageVector, text: String, modifier: Modifier = Modifier, tint: Color = VyzoColors.TextDim, size: Dp = 16.dp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(size), tint = tint)
        Text(" $text", style = MaterialTheme.typography.bodySmall, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ Formats

object Fmt {
    private fun local(pattern: String) = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat(pattern, Locale.FRANCE)
    }

    private val time = local("HH:mm")
    private val dayShort = local("EEE d MMM")
    private val dateTime = local("EEE d MMM 'à' HH:mm")
    private val date = local("d MMMM yyyy")
    private val isoDateFmt = local("yyyy-MM-dd")
    private val dayMonthYear = local("d MMM yyyy")

    fun time(ms: Long): String = time.get()!!.format(Date(ms))
    fun range(start: Long, stop: Long): String = "${time(start)} – ${time(stop)}"
    fun date(ms: Long): String = date.get()!!.format(Date(ms))

    /** Date locale YYYY-MM-DD pour comparer les dates de diffusion TMDB/TVmaze. */
    fun todayIso(now: Long = System.currentTimeMillis()): String = isoDateFmt.get()!!.format(Date(now))

    /** True si [iso] ("YYYY-MM-DD...") désigne aujourd'hui ou une date future. */
    fun isFutureOrTodayIso(iso: String, now: Long = System.currentTimeMillis()): Boolean {
        val norm = iso.trim().take(10)
        return norm.length == 10 && norm >= todayIso(now)
    }

    /** Formate une date ISO ("2026-11-21") en « Aujourd'hui », « Demain », « Ven. 21 nov. » ou « 21 nov. 2027 ». */
    fun isoDate(iso: String, now: Long = System.currentTimeMillis()): String {
        val norm = iso.trim().take(10)
        val parts = norm.split('-')
        if (parts.size != 3) return norm
        val y = parts[0].toIntOrNull() ?: return norm
        val m = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return norm
        val d = parts[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return norm
        val cal = java.util.Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d, 12, 0, 0)
        }
        val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        return when (dayIndex(cal.timeInMillis) - dayIndex(now)) {
            0L -> "Aujourd'hui"
            1L -> "Demain"
            -1L -> "Hier"
            else -> {
                val fmt = if (y == nowCal.get(java.util.Calendar.YEAR)) dayShort.get()!! else dayMonthYear.get()!!
                fmt.format(cal.time).replaceFirstChar { it.uppercase() }
            }
        }
    }

    fun day(ms: Long, now: Long = System.currentTimeMillis()): String {
        val diff = dayIndex(ms) - dayIndex(now)
        return when (diff) {
            0L -> "Aujourd'hui"
            1L -> "Demain"
            -1L -> "Hier"
            else -> dayShort.get()!!.format(Date(ms)).replaceFirstChar { it.uppercase() }
        }
    }

    fun dayTime(ms: Long, now: Long = System.currentTimeMillis()): String {
        val diff = dayIndex(ms) - dayIndex(now)
        return when (diff) {
            0L -> "Aujourd'hui à ${time(ms)}"
            1L -> "Demain à ${time(ms)}"
            -1L -> "Hier à ${time(ms)}"
            else -> dateTime.get()!!.format(Date(ms)).replaceFirstChar { it.uppercase() }
        }
    }

    /** Numéro de jour local (continu, y compris au changement d'année). */
    fun dayIndex(ms: Long): Long = Math.floorDiv(ms + java.util.TimeZone.getDefault().getOffset(ms), 86_400_000L)

    fun duration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun minutes(secs: Int): String {
        if (secs <= 0) return ""
        val h = secs / 3600
        val m = (secs % 3600) / 60
        return if (h > 0) "${h} h ${"%02d".format(m)}" else "$m min"
    }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f Go".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f Mo".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f Ko".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes o"
    }

    fun progress(start: Long, stop: Long, now: Long = System.currentTimeMillis()): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}
