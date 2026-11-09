package fr.yannickuhrig.vyzo.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import fr.yannickuhrig.vyzo.data.link.AmbientPicture
import fr.yannickuhrig.vyzo.data.link.ambientPictures
import fr.yannickuhrig.vyzo.player.PlaybackState
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.home.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull

private const val SLIDE_MS = 20_000L
private const val FADE_MS = 1_500
private const val ZOOM_END = 1.08f
private const val LOAD_TIMEOUT_MS = 10_000L
private val Corners = listOf(Alignment.BottomStart, Alignment.TopEnd, Alignment.BottomEnd, Alignment.TopStart)
private val SaverScrimX = listOf(0f to .2f, 1f to .2f)
private val SaverScrimY = listOf(0f to .55f, .3f to 0f, .7f to 0f, 1f to .55f)

/** Temps restant avant l'économiseur ; le compte part au plus tôt du moment où il devient autorisé. */
fun saverDelayMs(lastActivityAt: Long, armedAt: Long, minutes: Int, now: Long): Long =
    (maxOf(lastActivityAt, armedAt) + minutes * 60_000L - now).coerceAtLeast(0)

/** Économiseur d'écran de l'appli : se déclenche sur inactivité, hors du lecteur et quand rien n'est en lecture. */
@Composable
fun TvScreensaver(allowed: Boolean) {
    val c = LocalContainer.current
    val settings by c.settings.state.collectAsState()
    val media by c.player.current.collectAsState()
    val status by c.player.status.collectAsState()
    val shown by c.screenIdle.saverShown.collectAsState()
    val minutes = settings.screensaverMinutes
    val busy = media != null && (status.isPlaying || status.state == PlaybackState.BUFFERING)
    val armed = allowed && minutes > 0 && !busy

    LaunchedEffect(armed, minutes) {
        if (!armed) {
            c.screenIdle.hideSaver()
            return@LaunchedEffect
        }
        val armedAt = System.currentTimeMillis()
        c.screenIdle.lastActivityAt.collectLatest { last ->
            delay(saverDelayMs(last, armedAt, minutes, System.currentTimeMillis()))
            c.screenIdle.showSaver()
        }
    }

    if (shown) SaverContent()
}

@Composable
private fun SaverContent() {
    val c = LocalContainer.current
    TrackDesignScale()
    val pictures by produceState(emptyList<AmbientPicture>()) {
        value = runCatching { withTimeoutOrNull(LOAD_TIMEOUT_MS) { c.ambientPictures() } }.getOrNull().orEmpty()
    }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(SLIDE_MS)
            index++
        }
    }
    val picture = pictures.takeIf { it.isNotEmpty() }?.let { it[index % it.size] }

    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { c.screenIdle.hideSaver() } },
    ) {
        Crossfade(picture, animationSpec = tween(if (Look.motion == 0) 0 else FADE_MS), label = "economiseur") { current ->
            if (current != null) SlowZoomImage(current)
        }
        BakedScrim(SaverScrimX, SaverScrimY, Modifier.fillMaxSize())
        // La position change à chaque image : aucun élément fixe ne marque durablement la dalle.
        Column(Modifier.align(Corners[index % Corners.size]).padding(dpx(56)), horizontalAlignment = Alignment.End) {
            Clock(tv = true)
            picture?.let {
                Text(it.title, color = Color.White, fontSize = spx(24), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SlowZoomImage(picture: AmbientPicture) {
    val zoom = remember(picture.uid) { Animatable(1f) }
    LaunchedEffect(picture.uid) {
        if (Look.motion != 0) zoom.animateTo(ZOOM_END, tween((SLIDE_MS + FADE_MS).toInt(), easing = LinearEasing))
    }
    AsyncImage(
        picture.image, picture.title,
        Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom.value
            scaleY = zoom.value
        },
        contentScale = ContentScale.Crop,
    )
}
