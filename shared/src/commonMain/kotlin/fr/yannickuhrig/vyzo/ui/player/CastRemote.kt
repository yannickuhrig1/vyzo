package fr.yannickuhrig.vyzo.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.cast.CastPlayState
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.NetImage

/**
 * Lecteur du téléphone pendant le Cast : il devient la télécommande de l'appareil (pause, avance, zapping, volume).
 * « Arrêter le Cast » rend la lecture au téléphone, là où elle en était.
 */
@Composable
internal fun CastRemote(
    vm: PlayerViewModel,
    message: String?,
    onBack: () -> Unit,
    onZap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by vm.ui.collectAsState()
    val pb by vm.castPlayback.collectAsState()
    val device by vm.castDeviceName.collectAsState()
    val volume by vm.castVolume.collectAsState()
    val busy by vm.castBusy.collectAsState()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val live = ui.isLive || pb.isLive
    val image = ui.artwork.ifBlank { ui.channel?.logo.orEmpty() }

    Box(modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Color.White)
        }
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.CastConnected, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "Sur « ${device ?: "l'appareil Cast"} »", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (image.isNotBlank()) {
                NetImage(
                    image, contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 200.dp, height = 120.dp).clip(RoundedCornerShape(12.dp)),
                )
            }
            Text(
                ui.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val sub = ui.now?.takeIf { live }?.title ?: ui.subtitle
            if (sub.isNotBlank()) {
                Text(sub, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 2)
            }
            val stateText = when {
                busy || pb.state == CastPlayState.LOADING || ui.resolving -> "Envoi sur l'appareil…"
                pb.state == CastPlayState.BUFFERING -> "Chargement…"
                pb.state == CastPlayState.PAUSED -> "En pause"
                pb.state == CastPlayState.ERROR -> "L'appareil n'a pas pu lire ce flux"
                pb.state == CastPlayState.ENDED -> "Terminé"
                pb.state == CastPlayState.IDLE -> "En attente"
                else -> null
            }
            Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                if (busy || pb.state in setOf(CastPlayState.LOADING, CastPlayState.BUFFERING)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (stateText != null) {
                    Text(stateText, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
                }
            }

            if (!live && pb.durationMs > 0) {
                val shown = dragging ?: (pb.positionMs.toFloat() / pb.durationMs).coerceIn(0f, 1f)
                Slider(
                    value = shown,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { vm.castSeekTo((it * pb.durationMs).toLong()) }
                        dragging = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(Fmt.duration((shown * pb.durationMs).toLong()), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                    Text(Fmt.duration(pb.durationMs), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                val side = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                if (live) {
                    IconButton(onClick = { onZap(-1) }, colors = side, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, "Chaîne précédente", Modifier.size(36.dp))
                    }
                } else {
                    IconButton(onClick = { vm.castSeekRelative(-10_000) }, colors = side, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.Replay10, "Reculer de 10 s", Modifier.size(32.dp))
                    }
                }
                FilledIconButton(onClick = { vm.castTogglePause() }, modifier = Modifier.size(72.dp)) {
                    Icon(
                        if (pb.state == CastPlayState.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (pb.state == CastPlayState.PLAYING) "Pause" else "Lecture", Modifier.size(40.dp),
                    )
                }
                if (live) {
                    IconButton(onClick = { onZap(1) }, colors = side, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, "Chaîne suivante", Modifier.size(36.dp))
                    }
                } else {
                    IconButton(onClick = { vm.castSeekRelative(30_000) }, colors = side, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.Forward30, "Avancer de 30 s", Modifier.size(32.dp))
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { vm.castVolumeStep(-0.05f) }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeDown, "Baisser le volume", tint = Color.White)
                }
                Text("${(volume * 100).toInt()} %", color = Color.White, style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { vm.castVolumeStep(0.05f) }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, "Monter le volume", tint = Color.White)
                }
            }

            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { vm.stopCasting() }) { Text("Arrêter le Cast et regarder ici") }
            Text(
                "La vidéo passe par le téléphone : garde-le sur le même Wi-Fi (l'écran peut s'éteindre).",
                color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            )
        }

        message?.let {
            Text(
                it, color = Color.White, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp, start = 56.dp, end = 16.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}
