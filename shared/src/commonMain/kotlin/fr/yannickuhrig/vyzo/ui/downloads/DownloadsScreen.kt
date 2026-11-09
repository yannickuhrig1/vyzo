package fr.yannickuhrig.vyzo.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.data.db.DownloadEntity
import fr.yannickuhrig.vyzo.data.db.DownloadStatus
import fr.yannickuhrig.vyzo.ui.common.ActionDialog
import fr.yannickuhrig.vyzo.ui.common.ActionItem
import fr.yannickuhrig.vyzo.ui.common.Badge
import fr.yannickuhrig.vyzo.ui.common.EmptyState
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.NetImage
import fr.yannickuhrig.vyzo.ui.common.ProgressLine
import fr.yannickuhrig.vyzo.ui.common.Tile
import fr.yannickuhrig.vyzo.ui.common.isTv
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.launch

@Composable
fun DownloadsScreen(
    onPlayMovie: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val tv = isTv()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (tv) 24.dp else 12.dp, vertical = 12.dp),
    ) {
        if (onBack != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Téléchargements hors-ligne",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        DownloadsContent(
            onPlayMovie = onPlayMovie,
            onPlayEpisode = onPlayEpisode,
        )
    }
}

@Composable
fun DownloadsContent(
    onPlayMovie: (String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val downloads by c.downloads.observeAll().collectAsState(emptyList())

    val inProgress = remember(downloads) {
        downloads.filter { it.status != DownloadStatus.COMPLETED }
    }
    val completed = remember(downloads) {
        downloads.filter { it.status == DownloadStatus.COMPLETED }
    }
    val totalCompletedBytes = remember(completed) {
        completed.sumOf { it.bytesDownloaded }
    }

    var deleteConfirmItem by remember { mutableStateOf<DownloadEntity?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }

    deleteConfirmItem?.let { item ->
        ActionDialog(
            title = "Supprimer le téléchargement ?",
            subtitle = "${item.title} · ${Fmt.size(item.bytesDownloaded)}",
            body = "Le fichier local sera définitivement effacé de cet appareil.",
            actions = listOf(
                ActionItem("Supprimer", Icons.Default.Delete, tint = VyzoColors.Red) {
                    scope.launch { c.downloads.deleteDownload(item.id) }
                },
                ActionItem("Annuler") {},
            ),
            onDismiss = { deleteConfirmItem = null },
        )
    }

    if (confirmClearAll) {
        ActionDialog(
            title = "Supprimer tous les téléchargements ?",
            subtitle = "Total : ${Fmt.size(totalCompletedBytes)} libérés",
            body = "Tous les films et épisodes téléchargés sur cet appareil seront effacés.",
            actions = listOf(
                ActionItem("Tout supprimer", Icons.Default.DeleteSweep, tint = VyzoColors.Red) {
                    scope.launch {
                        completed.forEach { c.downloads.deleteDownload(it.id) }
                    }
                },
                ActionItem("Annuler") {},
            ),
            onDismiss = { confirmClearAll = false },
        )
    }

    if (downloads.isEmpty()) {
        EmptyState(
            title = "Aucun contenu téléchargé",
            message = "Téléchargez vos films et séries préférés pour les regarder en voyage ou sans connexion.",
            icon = Icons.Default.ArrowDownward,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (completed.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Stockage utilisé : ${Fmt.size(totalCompletedBytes)} (${completed.size} élément${if (completed.size > 1) "s" else ""})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VyzoColors.TextDim,
                    )
                    TextButton(onClick = { confirmClearAll = true }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp), tint = VyzoColors.Red)
                        Spacer(Modifier.width(6.dp))
                        Text("Tout vider", color = VyzoColors.Red)
                    }
                }
            }
        }

        if (inProgress.isNotEmpty()) {
            item {
                Text(
                    "En cours de téléchargement (${inProgress.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(inProgress, key = { it.id }) { item ->
                DownloadInProgressRow(
                    item = item,
                    onPause = { scope.launch { c.downloads.pauseDownload(item.id) } },
                    onResume = { scope.launch { c.downloads.resumeDownload(item.id) } },
                    onDelete = { scope.launch { c.downloads.deleteDownload(item.id) } },
                )
            }
        }

        if (completed.isNotEmpty()) {
            item {
                Text(
                    "Téléchargés (${completed.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(completed, key = { it.id }) { item ->
                DownloadCompletedRow(
                    item = item,
                    onPlay = {
                        if (item.itemType == "movie") {
                            onPlayMovie(item.itemUid)
                        } else {
                            onPlayEpisode(item.itemUid)
                        }
                    },
                    onDelete = { deleteConfirmItem = item },
                )
            }
        }
    }
}

@Composable
private fun DownloadInProgressRow(
    item: DownloadEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    Tile(
        onClick = { if (item.isDownloading) onPause() else onResume() },
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.posterUrl.isNotBlank()) {
                    NetImage(
                        item.posterUrl,
                        modifier = Modifier
                            .width(80.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.subtitle.isNotBlank()) {
                        Text(
                            item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = VyzoColors.TextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val statusText = when {
                        item.isDownloading -> {
                            val percent = (item.progress * 100).toInt()
                            val loaded = Fmt.size(item.bytesDownloaded)
                            val total = if (item.totalBytes > 0) Fmt.size(item.totalBytes) else "..."
                            "Téléchargement $percent% ($loaded / $total)"
                        }
                        item.isQueued -> "En attente…"
                        item.isPaused -> "En pause (${(item.progress * 100).toInt()}%)"
                        else -> "Échec du téléchargement"
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            item.isFailed -> VyzoColors.Red
                            item.isPaused -> VyzoColors.Amber
                            else -> VyzoColors.Cyan
                        },
                    )
                }

                Spacer(Modifier.width(8.dp))

                when {
                    item.isDownloading -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    item.isPaused -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Reprendre", tint = VyzoColors.Amber)
                        }
                    }
                    item.isFailed -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Default.Refresh, contentDescription = "Réessayer", tint = VyzoColors.Red)
                        }
                    }
                    item.isQueued -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }

                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Annuler", tint = VyzoColors.TextDim)
                }
            }

            Spacer(Modifier.height(8.dp))
            ProgressLine(
                progress = item.progress,
                modifier = Modifier.fillMaxWidth(),
                color = when {
                    item.isFailed -> VyzoColors.Red
                    item.isPaused -> VyzoColors.Amber
                    else -> MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun DownloadCompletedRow(
    item: DownloadEntity,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Tile(
        onClick = onPlay,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.posterUrl.isNotBlank()) {
                NetImage(
                    item.posterUrl,
                    modifier = Modifier
                        .width(80.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Badge("Hors-ligne", color = Color(0xFF4CAF50))
                }
                if (item.subtitle.isNotBlank()) {
                    Text(
                        item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = VyzoColors.TextDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "Taille : ${Fmt.size(item.bytesDownloaded)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = VyzoColors.TextDim,
                )
            }

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = onPlay,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Lire")
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = VyzoColors.Red)
            }
        }
    }
}
