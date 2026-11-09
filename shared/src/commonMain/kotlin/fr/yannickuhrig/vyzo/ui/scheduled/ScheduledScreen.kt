package fr.yannickuhrig.vyzo.ui.scheduled

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.data.db.RecordingEntity
import fr.yannickuhrig.vyzo.data.db.RecordingRuleEntity
import fr.yannickuhrig.vyzo.data.db.RecordingStatus
import fr.yannickuhrig.vyzo.data.db.ReminderEntity
import fr.yannickuhrig.vyzo.ui.common.ActionDialog
import fr.yannickuhrig.vyzo.ui.common.ActionItem
import fr.yannickuhrig.vyzo.ui.common.Badge
import fr.yannickuhrig.vyzo.ui.common.EmptyState
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.Tile
import fr.yannickuhrig.vyzo.ui.common.isTv
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.launch
import fr.yannickuhrig.vyzo.schedule.RecordingScheduler
import fr.yannickuhrig.vyzo.ui.downloads.DownloadsContent

@Composable
fun ScheduledScreen(
    onPlayRecording: (Long) -> Unit,
    onWatchChannel: (String) -> Unit,
    onPlayMovie: (String) -> Unit = {},
    onPlayEpisode: (String) -> Unit = {},
) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val tv = isTv()
    val recordings by c.db.recordings().observeAll().collectAsState(null)
    val reminders by remember { c.db.reminders().observeUpcoming(System.currentTimeMillis()) }.collectAsState(emptyList())
    val rules by c.db.recordingRules().observeAll().collectAsState(emptyList())
    val settings by c.settings.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var recMenu by remember { mutableStateOf<RecordingEntity?>(null) }
    var remMenu by remember { mutableStateOf<ReminderEntity?>(null) }
    var ruleMenu by remember { mutableStateOf<RecordingRuleEntity?>(null) }

    // Enregistrements prévus qui dépassent le nombre de connexions de l'abonnement.
    var conflicts by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    LaunchedEffect(recordings) {
        val active = recordings.orEmpty().filter { it.status == RecordingStatus.SCHEDULED || it.status == RecordingStatus.RECORDING }
        conflicts = active.mapNotNull { r ->
            val ch = c.db.channels().get(r.channelUid) ?: return@mapNotNull null
            c.recordings.conflictFor(ch, r.start, r.stop, ignoreId = r.id)?.let { r.id to it.message() }
        }.toMap()
    }

    recMenu?.let { r ->
        val active = r.status == RecordingStatus.RECORDING || r.status == RecordingStatus.SCHEDULED
        ActionDialog(
            title = r.title,
            subtitle = "${r.channelName} · ${Fmt.dayTime(r.start)} → ${Fmt.time(r.stop)}",
            body = listOfNotNull(r.error.takeIf { it.isNotBlank() }, conflicts[r.id]?.let { "Conflit : $it" }).joinToString("\n"),
            actions = buildList {
                if (r.filePath.isNotBlank() && r.bytes > 0 && !r.fileMissing(c.recordings)) add(ActionItem("Lire", Icons.Default.PlayArrow) { onPlayRecording(r.id) })
                if (active) add(ActionItem(if (r.status == RecordingStatus.RECORDING) "Arrêter l'enregistrement" else "Annuler la programmation", Icons.Default.Stop) {
                    scope.launch { c.recordings.cancel(r.id) }
                })
                add(ActionItem("Supprimer" + if (r.bytes > 0 && !r.fileMissing(c.recordings)) " (et le fichier)" else "", Icons.Default.Delete, tint = VyzoColors.Red) {
                    scope.launch { c.recordings.delete(r.id) }
                })
                if (r.ruleId > 0) rules.firstOrNull { it.id == r.ruleId }?.let { rule ->
                    add(ActionItem("Arrêter d'enregistrer la série", Icons.Default.Repeat, detail = rule.title) { ruleMenu = rule })
                }
            },
            onDismiss = { recMenu = null },
        )
    }
    ruleMenu?.let { rule ->
        ActionDialog(
            title = rule.title,
            subtitle = "Toutes les diffusions sur ${rule.channelName}",
            body = "Les épisodes déjà enregistrés sont conservés ; les diffusions à venir ne seront plus enregistrées.",
            actions = listOf(
                ActionItem("Arrêter d'enregistrer cette série", Icons.Default.Stop, tint = VyzoColors.Red) {
                    scope.launch { c.recordings.deleteRule(rule.id) }
                },
                ActionItem("Garder") {},
            ),
            onDismiss = { ruleMenu = null },
        )
    }
    remMenu?.let { r ->
        ActionDialog(
            title = r.title, subtitle = "${r.channelName} · ${Fmt.dayTime(r.start)}",
            actions = listOf(
                ActionItem("Regarder ${r.channelName}", Icons.Default.PlayArrow) { onWatchChannel(r.channelUid) },
                ActionItem("Supprimer le rappel", Icons.Default.Delete, tint = VyzoColors.Red) { scope.launch { c.reminders.remove(r.id) } },
            ),
            onDismiss = { remMenu = null },
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = if (tv) 24.dp else 12.dp, vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Enregistrements", "Séries", "Rappels", "Téléchargements").forEachIndexed { i, label ->
                Tile(
                    onClick = { tab = i }, onFocus = { if (tv) tab = i }, selected = tab == i, shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        label, style = MaterialTheme.typography.labelLarge,
                        color = if (tab == i) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
        }
        Spacer(Modifier.size(10.dp))
        if (tab == 0) {
            val isHost = settings.resolvesRecordingHost(isDesktop = c.platform.isDesktop, isTv = tv)
            Tile(
                onClick = {
                    val next = !isHost
                    scope.launch {
                        c.settings.update { it.copy(isRecordingHost = next) }
                        if (next) c.recordings.rescheduleAll()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (isHost) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (isHost) Icons.Default.FiberManualRecord else Icons.Default.CloudSync,
                        contentDescription = null,
                        tint = if (isHost) VyzoColors.Red else VyzoColors.Cyan,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (isHost) "Enregistrement sur cet appareil : Activé" else "Enregistrement distant (Cloud)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (isHost) "Cet appareil exécute les programmations et enregistre le flux" else "Désactivé : les programmations sont exécutées par votre TV ou PC",
                            style = MaterialTheme.typography.bodySmall,
                            color = VyzoColors.TextDim,
                        )
                    }
                    Switch(
                        checked = isHost,
                        onCheckedChange = { next ->
                            scope.launch {
                                c.settings.update { it.copy(isRecordingHost = next) }
                                if (next) c.recordings.rescheduleAll()
                            }
                        },
                    )
                }
            }
            val list = recordings
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(
                    "Aucun enregistrement",
                    "Dans le guide TV, choisis un programme puis « Enregistrer » ou « Enregistrer toute la série ». " +
                        "Le dossier se choisit dans Réglages → Enregistrements.",
                    Icons.Default.VideoLibrary,
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    items(list, key = { it.id }) { r -> RecordingRow(r, conflicts[r.id] != null) { recMenu = r } }
                    item {
                        Text(
                            "Dossier : " + settings.recFolderName.ifBlank { if (settings.recFolderUri.isNotBlank()) "dossier choisi" else c.recordings.directory.absolutePath },
                            style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
        } else if (tab == 1) {
            if (rules.isEmpty()) EmptyState(
                "Aucune série suivie",
                "Dans le guide TV, choisis un programme puis « Enregistrer toute la série » : chaque nouvelle diffusion sur la même chaîne sera enregistrée.",
                Icons.Default.Repeat,
            ) else LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(rules, key = { it.id }) { rule ->
                    val upcoming = recordings.orEmpty().filter { it.ruleId == rule.id && it.status == RecordingStatus.SCHEDULED }
                    val done = recordings.orEmpty().count { it.ruleId == rule.id && it.status == RecordingStatus.DONE }
                    Tile(onClick = { ruleMenu = rule }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), focusScale = 1.01f, color = Color.White.copy(alpha = 0.03f)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Repeat, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(rule.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        rule.channelName,
                                        upcoming.minByOrNull { it.start }?.let { "prochaine : ${Fmt.dayTime(it.start)}" } ?: "aucune diffusion prévue dans le guide",
                                        "$done enregistré(s)".takeIf { done > 0 },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, maxLines = 1,
                                )
                            }
                            if (upcoming.isNotEmpty()) Badge("${upcoming.size} À VENIR", VyzoColors.Cyan)
                        }
                    }
                }
            }
        } else if (tab == 2) {
            if (reminders.isEmpty()) EmptyState(
                "Aucun rappel",
                "Dans le guide TV, choisis un programme à venir puis « Me le rappeler ».",
                Icons.Default.Notifications,
            ) else LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(reminders, key = { it.id }) { r ->
                    Tile(onClick = { remMenu = r }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), focusScale = 1.01f, color = Color.White.copy(alpha = 0.03f)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Notifications, null, tint = VyzoColors.Amber)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${Fmt.dayTime(r.start)} · ${r.channelName}", style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim)
                            }
                        }
                    }
                }
            }
        } else {
            DownloadsContent(
                onPlayMovie = onPlayMovie,
                onPlayEpisode = onPlayEpisode,
            )
        }
    }
}

/** Enregistrement terminé dont le fichier a été effacé en dehors de Vyzo. */
private fun RecordingEntity.fileMissing(recordings: RecordingScheduler): Boolean =
    status != RecordingStatus.SCHEDULED && filePath.isNotBlank() && !recordings.fileExists(filePath)

@Composable
private fun RecordingRow(r: RecordingEntity, conflict: Boolean, onClick: () -> Unit) {
    val c = LocalContainer.current
    val missing = remember(r.id, r.status, r.bytes) { r.fileMissing(c.recordings) }
    Tile(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), focusScale = 1.01f, color = Color.White.copy(alpha = 0.03f)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (r.status == RecordingStatus.DONE && !missing) Icons.Default.PlayArrow else Icons.Default.FiberManualRecord, null,
                tint = when {
                    missing -> VyzoColors.TextDim
                    r.status == RecordingStatus.RECORDING -> VyzoColors.Red
                    r.status == RecordingStatus.DONE -> MaterialTheme.colorScheme.primary
                    else -> VyzoColors.TextDim
                },
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${r.channelName} · ${Fmt.dayTime(r.start)} → ${Fmt.time(r.stop)}" + if (r.bytes > 0 && !missing) " · ${Fmt.size(r.bytes)}" else "",
                    style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, maxLines = 1,
                )
                // Enregistrement interrompu mais conservé (arrêt manuel, disque plein…) : on dit pourquoi.
                if (r.error.isNotBlank() && !missing && (r.status == RecordingStatus.FAILED || r.status == RecordingStatus.DONE)) {
                    Text(
                        r.error, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (r.status == RecordingStatus.FAILED) VyzoColors.Red else VyzoColors.Amber,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (conflict) {
                Icon(Icons.Default.Warning, "Conflit", Modifier.size(18.dp), tint = VyzoColors.Amber)
                Spacer(Modifier.width(6.dp))
            }
            if (r.ruleId > 0) {
                Icon(Icons.Default.Repeat, "Série", Modifier.size(18.dp), tint = VyzoColors.TextDim)
                Spacer(Modifier.width(6.dp))
            }
            if (missing) Badge("FICHIER SUPPRIMÉ", VyzoColors.TextDim) else when (r.status) {
                RecordingStatus.SCHEDULED -> Badge("PROGRAMMÉ", VyzoColors.Cyan)
                RecordingStatus.RECORDING -> Badge("EN COURS", VyzoColors.Red)
                RecordingStatus.DONE -> Badge("TERMINÉ", VyzoColors.Amber)
                RecordingStatus.FAILED -> Badge("ÉCHEC", VyzoColors.Red)
                RecordingStatus.CANCELLED -> Badge("ANNULÉ", VyzoColors.TextDim)
            }
        }
    }
}
