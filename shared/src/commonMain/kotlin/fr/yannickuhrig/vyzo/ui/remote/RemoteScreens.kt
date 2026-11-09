package fr.yannickuhrig.vyzo.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.data.backup.BackupManager
import fr.yannickuhrig.vyzo.data.backup.BackupPart
import fr.yannickuhrig.vyzo.data.backup.BackupSelection
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.remote.ConfigReceiver
import fr.yannickuhrig.vyzo.data.remote.ConfigSender
import fr.yannickuhrig.vyzo.data.remote.QrContent
import fr.yannickuhrig.vyzo.data.remote.RemoteEvent
import fr.yannickuhrig.vyzo.data.remote.RemotePhase
import fr.yannickuhrig.vyzo.data.remote.RemotePlaylist
import fr.yannickuhrig.vyzo.data.remote.ScannedCode
import fr.yannickuhrig.vyzo.ui.common.ActionDialog
import fr.yannickuhrig.vyzo.ui.common.ActionItem
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.ZapDialog
import fr.yannickuhrig.vyzo.ui.common.isTv
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Serveur de réception lié à l'écran qui l'utilise : arrêté automatiquement à la sortie. */
@Composable
fun rememberConfigReceiver(accountOnly: Boolean = false): ConfigReceiver {
    val c = LocalContainer.current
    val receiver = remember {
        ConfigReceiver(
            deviceName = c.platform.deviceName,
            syncState = { c.sync.state.value },
            inspectConfig = { BackupManager.inspect(it) },
            accountOnly = accountOnly,
        )
    }
    DisposableEffect(receiver) { onDispose { receiver.stop() } }
    return receiver
}

/**
 * Affiché sur la TV : QR code à scanner avec le téléphone.
 * - Avec l'appareil photo : ouvre un formulaire web servi par la TV pour saisir une playlist.
 * - Avec Vyzo sur le téléphone : Réglages → Envoyer vers une TV, puis on coche ce qu'elle doit recevoir.
 *
 * [onPlaylist] reçoit une playlist saisie sur le téléphone ; si null, elle est ajoutée et chargée ici même.
 */
@Composable
fun ReceiveFromPhoneDialog(
    onDismiss: () -> Unit,
    onPlaylist: ((RemotePlaylist) -> Unit)? = null,
    onConfigImported: () -> Unit = {},
    receiver: ConfigReceiver = rememberConfigReceiver(),
) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val tv = isTv()
    val url by receiver.url.collectAsState()
    val noNetwork by receiver.noNetwork.collectAsState()
    val sync by c.sync.state.collectAsState()
    var phase by remember { mutableStateOf(receiver.phase) }
    var pendingConfig by remember { mutableStateOf<RemoteEvent.ConfigReceived?>(null) }
    var importedConfig by remember { mutableStateOf(false) }
    val closeFocus = remember { FocusRequester() }

    LaunchedEffect(receiver) {
        receiver.start()
        receiver.events.collect { e ->
            when (e) {
                is RemoteEvent.PlaylistReceived -> {
                    if (onPlaylist != null) onPlaylist(e.playlist)
                    else {
                        val id = c.repo.addPlaylist(e.playlist.toEntity())
                        if (e.playlist.epgUrl.isNotBlank()) c.repo.addEpgSource("${e.playlist.toEntity().name} (guide perso)", e.playlist.epgUrl)
                        c.sync.launchPlaylist(id)
                    }
                }
                is RemoteEvent.ConfigReceived -> pendingConfig = e
                is RemoteEvent.AccountReceived -> scope.launch { fr.yannickuhrig.vyzo.ui.settings.connectAccountFromPhone(c, receiver, e) }
            }
        }
    }
    LaunchedEffect(receiver) {
        while (true) {
            phase = receiver.phase
            delay(400)
        }
    }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { closeFocus.requestFocus() }
    }

    pendingConfig?.let { cfg ->
        ActionDialog(
            title = "Importer la configuration du téléphone ?",
            body = if (cfg.selective) BackupManager.describeSelection(cfg.json).joinToString(", ", postfix = ". ") +
                "Le reste de la configuration de cet appareil est conservé."
            else "${cfg.playlists} playlist(s), favoris, groupes, sources de guide et réglages. " +
                "La configuration actuelle de cet appareil sera remplacée.",
            actions = listOf(
                ActionItem("Importer", Icons.Default.CheckCircle) {
                    receiver.markLoading()
                    importedConfig = true
                    c.scope.launch {
                        runCatching { if (cfg.selective) BackupManager.importSelection(c, cfg.json) else BackupManager.import(c, cfg.json) }
                        c.sync.launchAll()
                    }
                },
                ActionItem("Refuser", tint = VyzoColors.Red) { receiver.markRejected() },
            ),
            onDismiss = { pendingConfig = null },
        )
    }

    val finished = phase == RemotePhase.LOADING && !sync.running && sync.lastFinished >= receiver.acceptedAt && receiver.acceptedAt > 0
    ZapDialog(onDismiss = onDismiss) {
        Column(Modifier.padding(26.dp).widthIn(max = 760.dp)) {
            Text("Configurer depuis le téléphone", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(18.dp))
            // Sur une TV, ce bloc peut dépasser : il défile, et l'état et le bouton restent visibles.
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(if (tv) 230.dp else 200.dp), contentAlignment = Alignment.Center) {
                    when {
                        noNetwork -> Icon(Icons.Default.WifiOff, null, Modifier.size(56.dp), tint = VyzoColors.TextDim)
                        url != null -> QrImage(url!!, if (tv) 230.dp else 200.dp)
                        else -> CircularProgressIndicator()
                    }
                }
                if (tv) {
                    Spacer(Modifier.width(26.dp))
                    Instructions(url, noNetwork, Modifier.weight(1f))
                }
            }
            if (!tv) Instructions(url, noNetwork, Modifier.padding(top = 16.dp))
            }

            Spacer(Modifier.height(16.dp))
            StatusLine(phase, sync.running, sync.message, sync.error, finished)

            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End) {
                if (finished && importedConfig) {
                    Button(onClick = { onConfigImported(); onDismiss() }, modifier = Modifier.focusRequester(closeFocus)) { Text("Continuer") }
                } else {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeFocus)) { Text(if (finished) "Terminé" else "Fermer") }
                }
            }
        }
    }
}

@Composable
private fun Instructions(url: String?, noNetwork: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (noNetwork) {
            Text("Cet appareil n'est connecté à aucun réseau local.", style = MaterialTheme.typography.titleMedium)
            Text("Connecte-le au Wi-Fi ou en Ethernet, sur le même réseau que ton téléphone, puis rouvre cet écran.", color = VyzoColors.TextDim)
            return@Column
        }
        Step("1", "Sur le même Wi-Fi que cet appareil, vise ce QR code avec l'appareil photo de ton téléphone.")
        Step("2", "Dans la page qui s'ouvre, saisis ta playlist ou envoie un fichier de sauvegarde.")
        Text(
            "Vyzo est déjà installé sur ton téléphone ? Réglages → « Envoyer vers une TV » pour cocher ce que tu veux envoyer.",
            style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim,
        )
        if (url != null) {
            Text(
                "Sans QR code, ouvre dans le navigateur du téléphone :\n" + url.substringBefore("&${QrContent.MARKER}"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = VyzoColors.TextDim,
            )
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row {
        Text(n, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StatusLine(phase: RemotePhase, running: Boolean, message: String, error: String?, finished: Boolean) {
    val (icon, text) = when {
        phase == RemotePhase.WAITING -> null to "En attente du téléphone…"
        phase == RemotePhase.PENDING_CONFIRM -> null to "Configuration reçue, en attente de ta confirmation."
        phase == RemotePhase.REJECTED -> Icons.Default.ErrorOutline to "Import refusé."
        finished && error != null -> Icons.Default.ErrorOutline to "Échec du chargement : $error"
        finished -> Icons.Default.CheckCircle to "C'est prêt !"
        running -> null to "Chargement : $message"
        else -> null to "Préparation…"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            icon != null -> Icon(icon, null, Modifier.size(22.dp), tint = if (icon == Icons.Default.CheckCircle) MaterialTheme.colorScheme.primary else VyzoColors.Red)
            phase == RemotePhase.LOADING || phase == RemotePhase.WAITING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

// ------------------------------------------------------------------ Côté téléphone : envoi

private sealed interface SendStep {
    data object Scan : SendStep
    data class Choose(val device: ScannedCode.VyzoDevice, val name: String) : SendStep
    data class Progress(val device: ScannedCode.VyzoDevice, val name: String, val message: String, val done: Boolean = false, val failed: Boolean = false) : SendStep
    data class Failed(val message: String) : SendStep
}

/** Téléphone → TV : scanne le QR code affiché par la TV puis envoie la configuration ou une playlist. */
@Composable
fun SendToTvFlow(onClose: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val playlists by c.repo.playlists.collectAsState(emptyList())
    var step by remember { mutableStateOf<SendStep>(SendStep.Scan) }
    val uriHandler = LocalUriHandler.current
    val cloud by c.cloud.status.collectAsState()

    fun follow(device: ScannedCode.VyzoDevice, name: String, first: String) {
        step = SendStep.Progress(device, name, first)
        scope.launch {
            var seenLoading = false
            repeat(400) {
                delay(1_500)
                val s = runCatching { ConfigSender.status(device) }.getOrNull()
                if (s == null) {
                    step = if (seenLoading) SendStep.Progress(device, name, "Transfert terminé : la TV finit le chargement.", done = true)
                    else SendStep.Progress(device, name, "La TV ne répond plus (écran de réception fermé ?).", failed = true)
                    return@launch
                }
                if (s.state == "loading") seenLoading = true
                step = SendStep.Progress(device, name, s.message, done = s.state == "done", failed = s.state == "error" || s.state == "rejected")
                if (s.finished) return@launch
            }
        }
    }

    when (val s = step) {
        SendStep.Scan -> QrScannerDialog(
            title = "Scanne le QR code de la TV",
            hint = "Sur la TV : Réglages → « Recevoir depuis un téléphone »",
            onResult = { text ->
                when (val code = QrContent.parse(text)) {
                    is ScannedCode.VyzoDevice -> scope.launch {
                        step = runCatching { SendStep.Choose(code, ConfigSender.deviceName(code)) }
                            .getOrElse { SendStep.Failed(it.message ?: "TV injoignable") }
                    }
                    // La TV attend le compte, pas une configuration : sa page de connexion s'ouvre dans le navigateur.
                    is ScannedCode.VyzoAccount -> runCatching { uriHandler.openUri(code.url) }
                        .onSuccess { onClose() }
                        .onFailure { step = SendStep.Failed("Impossible d'ouvrir le navigateur. Scanne ce QR code avec l'appareil photo du téléphone.") }
                    else -> step = SendStep.Failed("Ce QR code ne vient pas de Vyzo. Sur la TV, ouvre Réglages → « Recevoir depuis un téléphone ».")
                }
            },
            onDismiss = onClose,
        )
        is SendStep.Choose -> SendChecklist(s.name, playlists, cloud.email, onCancel = onClose) { selection, password ->
            // Avant le lancement : à l'étape Choose, fermer la boîte quitterait tout le parcours et annulerait l'envoi.
            step = SendStep.Progress(s.device, s.name, if (password != null) "Connexion de la TV au compte…" else "Envoi en cours…")
            scope.launch {
                runCatching {
                    if (password != null) ConfigSender.sendAccount(s.device, cloud.email.orEmpty(), password)
                    if (!selection.isEmpty) ConfigSender.sendSelection(s.device, BackupManager.exportSelection(c, selection))
                }
                    .onSuccess {
                        if (selection.isEmpty) step = SendStep.Progress(s.device, s.name, "La TV est connectée au compte.", done = true)
                        else follow(s.device, s.name, "Accepte l'import sur l'écran de la TV…")
                    }
                    .onFailure { step = SendStep.Failed(it.message ?: "Échec de l'envoi") }
            }
        }
        is SendStep.Progress -> ResultDialog(
            title = "Envoi vers « ${s.name} »",
            message = s.message,
            icon = when { s.done -> Icons.Default.CheckCircle; s.failed -> Icons.Default.ErrorOutline; else -> null },
            onClose = onClose,
        )
        is SendStep.Failed -> ResultDialog("Envoi impossible", s.message, Icons.Default.ErrorOutline, onClose)
    }
}

/** Une fois le QR code scanné : on coche ce que la TV doit recevoir. Le compte demande son mot de passe, qui n'est jamais gardé. */
@Composable
private fun SendChecklist(
    name: String,
    playlists: List<PlaylistEntity>,
    accountEmail: String?,
    onCancel: () -> Unit,
    onSend: (BackupSelection, String?) -> Unit,
) {
    var excluded by remember { mutableStateOf(emptySet<Long>()) }
    var parts by remember { mutableStateOf(BackupPart.entries.toSet()) }
    var withAccount by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    val selection = BackupSelection(playlists.map { it.id }.toSet() - excluded, parts)
    ZapDialog(onDismiss = onCancel) {
        Column(Modifier.padding(24.dp)) {
            Text("Envoyer vers « $name »", style = MaterialTheme.typography.titleLarge)
            Text(
                "Coche ce que la TV doit recevoir. Le reste de sa configuration ne change pas.",
                style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            )
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                playlists.forEach { p ->
                    CheckRow(p.name, "Playlist", p.id !in excluded) { on -> excluded = if (on) excluded - p.id else excluded + p.id }
                }
                if (accountEmail != null) {
                    CheckRow("Compte Vyzo", "Connecte la TV à $accountEmail", withAccount) { withAccount = it }
                    if (withAccount) {
                        OutlinedTextField(
                            password, { password = it }, label = { Text("Mot de passe du compte") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                }
                BackupPart.entries.forEach { part ->
                    CheckRow(part.label, part.detail, part in parts) { on -> parts = if (on) parts + part else parts - part }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onCancel) { Text("Annuler") }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { onSend(selection, if (withAccount) password else null) },
                    enabled = if (withAccount) password.isNotEmpty() else !selection.isEmpty,
                ) { Text("Envoyer") }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim)
        }
    }
}

@Composable
private fun ResultDialog(title: String, message: String, icon: ImageVector?, onClose: () -> Unit) {
    ZapDialog(onDismiss = onClose) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) Icon(icon, null, Modifier.size(24.dp), tint = if (icon == Icons.Default.CheckCircle) MaterialTheme.colorScheme.primary else VyzoColors.Red)
                else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(message, style = MaterialTheme.typography.bodyLarge)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onClose) { Text(if (icon == null) "Masquer" else "Fermer") }
            }
        }
    }
}
