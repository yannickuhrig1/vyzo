package fr.yannickuhrig.vyzo.ui.settings

import fr.yannickuhrig.vyzo.ui.common.isDesktop
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.source.stalker.StalkerClient
import fr.yannickuhrig.vyzo.data.source.xtream.XtreamClient
import fr.yannickuhrig.vyzo.ui.common.ConfirmDialog
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.PlatformBackHandler
import fr.yannickuhrig.vyzo.ui.common.Tile
import fr.yannickuhrig.vyzo.ui.common.isTv
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import fr.yannickuhrig.vyzo.data.remote.QrContent
import fr.yannickuhrig.vyzo.data.remote.RemotePlaylist
import fr.yannickuhrig.vyzo.data.remote.ScannedCode
import fr.yannickuhrig.vyzo.ui.remote.QrScannerDialog
import fr.yannickuhrig.vyzo.ui.common.rememberOpenFile
import fr.yannickuhrig.vyzo.util.plural
import fr.yannickuhrig.vyzo.util.urlHost
import fr.yannickuhrig.vyzo.ui.remote.ReceiveFromPhoneDialog
import fr.yannickuhrig.vyzo.ui.remote.rememberConfigReceiver
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch

private sealed interface SaveState {
    data object Idle : SaveState
    data object Running : SaveState
    data class Done(val channels: Int, val movies: Int, val series: Int) : SaveState
    data class Failed(val message: String) : SaveState
}

/** Assistant d'ajout / édition d'une playlist (M3U, Xtream Codes, portail Stalker). */
@Composable
fun PlaylistEditScreen(playlistId: Long, firstRun: Boolean, onDone: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val tv = isTv()
    val sync by c.sync.state.collectAsState()

    var loaded by remember { mutableStateOf(playlistId == 0L) }
    var existing by remember { mutableStateOf<PlaylistEntity?>(null) }
    var type by remember { mutableStateOf(SourceType.XTREAM) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("00:1A:79:") }
    var userAgent by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<SaveState>(SaveState.Idle) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showReceive by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var scanInfo by remember { mutableStateOf<String?>(null) }
    val receiver = rememberConfigReceiver()
    val canScan = !tv && c.platform.hasCamera
    val cloud by c.cloud.status.collectAsState()
    var showAccount by remember { mutableStateOf(false) }
    val restoreBackup = rememberRestoreBackup(onPhone = { showReceive = true }, onRestored = { onDone() })

    LaunchedEffect(playlistId) {
        if (playlistId != 0L) {
            c.db.playlists().get(playlistId)?.let { p ->
                existing = p; type = p.type; name = p.name; url = p.url; username = p.username
                password = p.password; mac = p.mac.ifBlank { "00:1A:79:" }; userAgent = p.userAgent
            }
            loaded = true
        }
    }

    // Playlist locale : relue à chaque mise à jour, d'où l'accès conservé (keepAccess).
    val filePicker = rememberOpenFile(listOf("*/*"), keepAccess = true) { uri ->
        if (uri != null) {
            url = uri
            if (name.isBlank()) name = "Playlist locale"
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = "Supprimer « ${existing!!.name} » ?",
            message = "Les chaînes, films, séries et guides associés seront retirés de l'appareil.",
            confirmLabel = "Supprimer",
            onConfirm = { scope.launch { c.repo.deletePlaylist(existing!!.id); onDone() } },
            onDismiss = { confirmDelete = false },
        )
    }

    val xtreamHint = remember(url, type) { if (type == SourceType.M3U) XtreamClient.fromM3uUrl(url) else null }

    fun validate(): String? = when {
        url.isBlank() -> when (type) {
            SourceType.M3U -> "Indique l'adresse de la playlist M3U."
            SourceType.XTREAM -> "Indique l'adresse du serveur."
            SourceType.STALKER -> "Indique l'adresse du portail."
        }
        type == SourceType.XTREAM && (username.isBlank() || password.isBlank()) -> "Identifiant et mot de passe requis."
        type == SourceType.STALKER && !StalkerClient.isValidMac(mac) -> "Adresse MAC invalide (format 00:1A:79:XX:XX:XX)."
        else -> null
    }

    fun save() {
        error = validate()
        if (error != null) return
        state = SaveState.Running
        scope.launch {
            val finalName = name.trim().ifBlank {
                when (type) {
                    SourceType.M3U -> urlHost(url) ?: "Playlist M3U"
                    SourceType.XTREAM -> urlHost(XtreamClient.normalizeServer(url)) ?: "Xtream"
                    SourceType.STALKER -> urlHost(StalkerClient.normalize(url)) ?: "Portail"
                }
            }
            val entity = (existing ?: PlaylistEntity(name = finalName, type = type, url = "")).copy(
                name = finalName, type = type, url = url.trim(), username = username.trim(), password = password,
                mac = if (type == SourceType.STALKER) mac.trim().uppercase() else "", userAgent = userAgent.trim(),
            )
            val id = if (existing == null) c.repo.addPlaylist(entity) else { c.repo.updatePlaylist(entity); entity.id }
            val epgId = if (epgUrl.isNotBlank()) c.repo.addEpgSource("$finalName (guide perso)", epgUrl.trim()) else null
            val res = c.sync.syncPlaylist(id, withEpg = true)
            state = res.fold(
                onSuccess = { SaveState.Done(c.db.channels().count(id), c.db.movies().count(id), c.db.series().count(id)) },
                onFailure = { SaveState.Failed(it.message ?: it.toString()) },
            )
            // Une source nouvelle qui n'a jamais chargé n'est pas gardée : le formulaire reste rempli pour réessayer.
            if (existing == null && res.isFailure) c.repo.discardNeverLoadedPlaylist(id, epgId)
            if (existing == null) existing = c.db.playlists().get(id)
        }
    }

    fun applyRemote(p: RemotePlaylist) {
        p.sourceType?.let { type = it }
        if (p.name.isNotBlank()) name = p.name
        url = p.url
        username = p.username
        password = p.password
        if (p.mac.isNotBlank()) mac = p.mac.uppercase()
        userAgent = p.userAgent
        epgUrl = p.epgUrl
        error = null
    }

    if (showReceive) {
        ReceiveFromPhoneDialog(
            onDismiss = { showReceive = false },
            onPlaylist = { p ->
                applyRemote(p)
                showReceive = false
                save()
            },
            onConfigImported = onDone,
            receiver = receiver,
        )
    }
    if (showScanner) {
        QrScannerDialog(
            title = "Scanner un QR code",
            hint = "Lien de playlist, lien Xtream (get.php) ou portail + adresse MAC",
            onResult = { text ->
                showScanner = false
                scanInfo = when (val code = QrContent.parse(text)) {
                    is ScannedCode.Playlist -> { applyRemote(code.playlist); "Champs remplis depuis le QR code : vérifie puis valide." }
                    is ScannedCode.VyzoDevice -> "C'est le QR code de Vyzo sur une TV. Pour lui envoyer ta configuration : Réglages → « Envoyer vers une TV »."
                    is ScannedCode.VyzoAccount -> "C'est le QR code de connexion au compte de la TV. Scanne-le avec l'appareil photo du téléphone."
                    is ScannedCode.Unknown -> "QR code non reconnu : « ${text.take(80)} »"
                }
            },
            onDismiss = { showScanner = false },
        )
    }

    if (!loaded) return

    if (showAccount) {
        PlatformBackHandler { showAccount = false }
        Column(Modifier.fillMaxSize()) {
            // En haut : à la télécommande, le focus y arrive d'abord et la flèche du bas mène à la connexion.
            OutlinedButton(onClick = { showAccount = false }, modifier = Modifier.padding(horizontal = if (tv) 48.dp else 16.dp, vertical = 12.dp)) {
                Text(if (cloud.signedIn) "Continuer" else "Retour")
            }
            Box(Modifier.weight(1f)) { AccountScreen() }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = if (tv) 48.dp else 16.dp, vertical = if (tv) 28.dp else 16.dp),
    ) {
        Column(Modifier.widthIn(max = 720.dp)) {
            Text(
                when {
                    firstRun -> "Bienvenue dans Vyzo"
                    existing != null -> "Modifier la playlist"
                    else -> "Ajouter une playlist"
                },
                style = MaterialTheme.typography.headlineMedium,
            )
            if (firstRun) {
                Text(
                    "Ajoute ton abonnement IPTV pour commencer. Vyzo ne fournit aucune chaîne : il lit les sources que tu possèdes.",
                    style = MaterialTheme.typography.bodyLarge, color = VyzoColors.TextDim, modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(20.dp))

            when (val s = state) {
                SaveState.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Chargement en cours…", style = MaterialTheme.typography.titleMedium)
                            Text(sync.message, style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim)
                        }
                    }
                    Text(
                        "Les grosses playlists peuvent prendre une minute ou deux, surtout pour le guide TV.",
                        style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, modifier = Modifier.padding(top = 16.dp),
                    )
                    return@Column
                }
                is SaveState.Done -> {
                    ResultCard(
                        Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary, "Playlist chargée",
                        "${plural(s.channels, "chaîne")} · ${plural(s.movies, "film")} · ${plural(s.series, "série")}" +
                            if (type == SourceType.STALKER) "\nLes films et séries du portail se chargent à l'ouverture de chaque catégorie." else "",
                    )
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onDone) { Text("C'est parti") }
                        OutlinedButton(onClick = { state = SaveState.Idle }) { Text("Modifier") }
                    }
                    return@Column
                }
                is SaveState.Failed -> {
                    ResultCard(Icons.Default.ErrorOutline, VyzoColors.Red, "Échec du chargement", s.message)
                    Spacer(Modifier.height(16.dp))
                }
                SaveState.Idle -> Unit
            }

            val firstRunShortcuts: @Composable () -> Unit = {
                if (firstRun) {
                    ShortcutTile(
                        "Restaurer une sauvegarde",
                        "Reprends tes sources, favoris et réglages depuis un fichier de sauvegarde.",
                        Icons.Default.Restore, Modifier.padding(top = 10.dp),
                    ) { restoreBackup() }
                    if (cloud.configured) {
                        ShortcutTile(
                            if (cloud.signedIn) "Compte Vyzo : ${cloud.email}" else "Compte Vyzo",
                            "Connecte-toi pour retrouver ton abonnement IPTV, tes profils, favoris et progression.",
                            Icons.Default.AccountCircle, Modifier.padding(top = 10.dp),
                        ) { showAccount = true }
                    }
                }
            }
            if (tv) {
                val focus = remember { FocusRequester() }
                if (existing == null) LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { focus.requestFocus() } }
                ShortcutTile(
                    "Saisir depuis mon téléphone",
                    "Scanne un QR code avec le téléphone et tape tes identifiants dessus, ou envoie la configuration de Vyzo depuis ton téléphone.",
                    Icons.Default.QrCode2, Modifier.focusRequester(focus),
                ) { showReceive = true }
                firstRunShortcuts()
                Text(if (isDesktop()) "ou au clavier :" else "ou à la télécommande :", style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim, modifier = Modifier.padding(top = 14.dp, bottom = 10.dp))
            } else {
                if (canScan) {
                    ShortcutTile(
                        "Scanner un QR code",
                        "Remplit automatiquement le formulaire depuis un QR code (lien de playlist, Xtream, portail + MAC).",
                        Icons.Default.QrCodeScanner,
                    ) { showScanner = true }
                }
                firstRunShortcuts()
                if (canScan || firstRun) Spacer(Modifier.height(14.dp))
            }
            scanInfo?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(bottom = 12.dp)) }

            Text("Type de source", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TypeTile("Xtream Codes", "Serveur + identifiants", Icons.Default.Person, type == SourceType.XTREAM, Modifier.weight(1f)) { type = SourceType.XTREAM }
                TypeTile("M3U", "Lien ou fichier", Icons.Default.Link, type == SourceType.M3U, Modifier.weight(1f)) { type = SourceType.M3U }
                TypeTile("Stalker", "Portail + MAC", Icons.Default.Router, type == SourceType.STALKER, Modifier.weight(1f)) { type = SourceType.STALKER }
            }
            Spacer(Modifier.height(18.dp))

            Field(name, { name = it }, "Nom (facultatif)")
            when (type) {
                SourceType.M3U -> {
                    Field(url, { url = it }, "Adresse de la playlist (http://…)", keyboard = KeyboardType.Uri)
                    if (!tv || isDesktop()) {
                        TextButton(onClick = { filePicker.launch() }) {
                            Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Choisir un fichier .m3u")
                        }
                    }
                    if (xtreamHint != null) {
                        Tile(
                            onClick = {
                                type = SourceType.XTREAM; url = xtreamHint.server; username = xtreamHint.username; password = xtreamHint.password
                            },
                            color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.padding(vertical = 6.dp),
                        ) {
                            Text(
                                "Ce lien contient des identifiants Xtream. Toucher ici pour passer en mode Xtream (films, séries et replay plus complets).",
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    Field(epgUrl, { epgUrl = it }, "Guide TV XMLTV (facultatif, si absent de la playlist)", keyboard = KeyboardType.Uri)
                }
                SourceType.XTREAM -> {
                    Field(url, { url = it }, "Serveur (ex. http://exemple.com:8080)", keyboard = KeyboardType.Uri)
                    Field(username, { username = it }, "Identifiant")
                    Field(
                        password, { password = it }, "Mot de passe", keyboard = KeyboardType.Password,
                        visual = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                        trailing = {
                            IconButton(onClick = { showPass = !showPass }) {
                                Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Afficher")
                            }
                        },
                    )
                }
                SourceType.STALKER -> {
                    Field(url, { url = it }, "Adresse du portail (ex. http://portail.tv:8080/c/)", keyboard = KeyboardType.Uri)
                    Field(mac, { mac = it.uppercase() }, "Adresse MAC (00:1A:79:XX:XX:XX)")
                }
            }
            Field(userAgent, { userAgent = it }, "User-Agent (facultatif)")

            existing?.let { p ->
                val info = buildList {
                    if (p.lastSync > 0) add("Dernière mise à jour : ${Fmt.dayTime(p.lastSync)}")
                    if (p.expiresAt > 0) add("Abonnement valable jusqu'au ${Fmt.date(p.expiresAt)}")
                    if (p.maxConnections > 0) add("Connexions simultanées autorisées : ${p.maxConnections}")
                    if (p.lastError.isNotBlank()) add("Dernière erreur : ${p.lastError}")
                }
                if (info.isNotEmpty()) Text(info.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, modifier = Modifier.padding(top = 8.dp))
            }

            error?.let { Text(it, color = VyzoColors.Red, modifier = Modifier.padding(top = 10.dp)) }

            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { save() }) { Text(if (existing == null) "Ajouter et charger" else "Enregistrer et recharger") }
                if (!firstRun) OutlinedButton(onClick = onDone) { Text("Annuler") }
                if (existing != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, null, tint = VyzoColors.Red); Text(" Supprimer", color = VyzoColors.Red)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutTile(title: String, subtitle: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Tile(
        onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f), focusScale = 1.02f,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim)
            }
        }
    }
}

@Composable
private fun ResultCard(icon: ImageVector, tint: Color, title: String, message: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, Modifier.size(30.dp), tint = tint)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim)
        }
    }
}

@Composable
private fun TypeTile(title: String, subtitle: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Tile(
        onClick = onClick, modifier = modifier, selected = selected, shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else VyzoColors.TextDim)
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified, modifier = Modifier.padding(top = 6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim)
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType = KeyboardType.Text,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        visualTransformation = visual, trailingIcon = trailing,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
