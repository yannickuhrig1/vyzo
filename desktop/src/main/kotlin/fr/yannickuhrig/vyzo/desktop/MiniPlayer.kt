package fr.yannickuhrig.vyzo.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.player.ResizeMode
import fr.yannickuhrig.vyzo.ui.theme.VyzoTheme
import androidx.compose.foundation.window.WindowDraggableArea

/**
 * Mini-lecteur : petite fenêtre sans bordure, toujours au premier plan, en bas à droite de l'écran.
 * La vidéo continue (même lecteur) pendant que la fenêtre principale est réduite.
 * On la déplace en la faisant glisser ; au survol : agrandir, lecture/pause, fermer.
 */
@Composable
fun MiniPlayerWindow(c: AppContainer, onRestore: () -> Unit, onClose: () -> Unit) {
    val state = rememberWindowState(size = DpSize(480.dp, 270.dp), position = WindowPosition(Alignment.BottomEnd))
    val status by c.player.status.collectAsState()
    val current by c.player.current.collectAsState()
    Window(
        onCloseRequest = onClose,
        state = state,
        title = "Vyzo – mini-lecteur",
        icon = painterResource("vyzo-icon.png"),
        undecorated = true,
        alwaysOnTop = true,
        resizable = true,
        onKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown) return@Window false
            when (e.key) {
                Key.Escape -> { onClose(); true }
                Key.Enter -> { onRestore(); true }
                Key.Spacebar -> { if (c.player.isPlaying) c.player.pause() else c.player.resume(); true }
                else -> false
            }
        },
    ) {
        VyzoTheme {
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            WindowDraggableArea(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.Black).border(1.dp, Color.White.copy(alpha = 0.12f)).hoverable(hover)) {
                    c.player.Surface(Modifier.fillMaxSize(), ResizeMode.FIT)
                    AnimatedVisibility(visible = hovered, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent, Color.Black.copy(alpha = 0.6f))))) {
                            Text(
                                current?.let { if (it.isLive) "En direct" else "Lecture" } ?: "Arrêté",
                                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                            )
                            Row(Modifier.align(Alignment.TopEnd)) {
                                IconButton(onClick = onRestore) { Icon(Icons.Default.OpenInFull, "Agrandir", tint = Color.White) }
                                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Fermer le mini-lecteur", tint = Color.White) }
                            }
                            Row(Modifier.align(Alignment.Center).fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                IconButton(
                                    onClick = { if (status.isPlaying) c.player.pause() else c.player.resume() },
                                    modifier = Modifier.size(56.dp),
                                ) {
                                    Icon(if (status.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Lecture", tint = Color.White, modifier = Modifier.size(40.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
