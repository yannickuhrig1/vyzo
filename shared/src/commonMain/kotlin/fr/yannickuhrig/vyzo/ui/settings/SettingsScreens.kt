package fr.yannickuhrig.vyzo.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.data.db.CategoryRow
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.DownloadEntity
import fr.yannickuhrig.vyzo.data.db.DownloadStatus
import fr.yannickuhrig.vyzo.data.db.EpgMode
import fr.yannickuhrig.vyzo.data.db.FAVORITES_GROUP_ID
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.db.UserGroupEntity
import fr.yannickuhrig.vyzo.data.net.redactCredentials
import fr.yannickuhrig.vyzo.data.prefs.AppSettings
import fr.yannickuhrig.vyzo.data.prefs.PlayerOrientation
import fr.yannickuhrig.vyzo.data.source.xtream.XtreamClient
import fr.yannickuhrig.vyzo.data.trakt.traktConnected
import fr.yannickuhrig.vyzo.diag.CrashReporter
import fr.yannickuhrig.vyzo.diag.DiagLog
import fr.yannickuhrig.vyzo.player.AUDIO_DELAY_CHOICES
import fr.yannickuhrig.vyzo.player.audioDelayLabel
import fr.yannickuhrig.vyzo.ui.common.ActionDialog
import fr.yannickuhrig.vyzo.ui.common.ActionItem
import fr.yannickuhrig.vyzo.ui.common.Choice
import fr.yannickuhrig.vyzo.ui.common.ChoiceDialog
import fr.yannickuhrig.vyzo.ui.common.ConfirmDialog
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.SectionTitle
import fr.yannickuhrig.vyzo.ui.common.TextInputDialog
import fr.yannickuhrig.vyzo.ui.common.Tile
import fr.yannickuhrig.vyzo.ui.common.isTv
import fr.yannickuhrig.vyzo.ui.common.rememberOverlaySetting
import fr.yannickuhrig.vyzo.ui.common.rememberPickFolder
import fr.yannickuhrig.vyzo.ui.common.rememberSaveFile
import fr.yannickuhrig.vyzo.ui.player.RemoteKeymap
import fr.yannickuhrig.vyzo.ui.profile.NewPinDialog
import fr.yannickuhrig.vyzo.ui.profile.PinDialog
import fr.yannickuhrig.vyzo.ui.remote.ReceiveFromPhoneDialog
import fr.yannickuhrig.vyzo.ui.remote.SendToTvFlow
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import fr.yannickuhrig.vyzo.update.UpdateState
import fr.yannickuhrig.vyzo.util.plural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ Briques

@Composable
fun SettingRow(
    title: String,
    value: String = "",
    icon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Tile(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), focusScale = 1.01f) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            else Spacer(Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun ToggleRow(title: String, value: String = "", checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, value, trailing = { Switch(checked = checked, onCheckedChange = null) }) { onChange(!checked) }
}

private data class PendingChoice(
    val title: String,
    val choices: List<Choice<Any>>,
    val selected: Any?,
    val apply: (Any) -> Unit,
)

@Composable
internal fun ScreenColumn(content: LazyListScope.() -> Unit) {
    val tv = isTv()
    // La liste occupe toute la largeur pour que la molette agisse partout ; seul le texte reste limité en largeur.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = if (tv) 40.dp else 12.dp
        val columnMax = if (tv) 980.dp else 720.dp
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = side, end = maxOf(side, maxWidth - columnMax + side),
                top = if (tv) 16.dp else 4.dp, bottom = 40.dp,
            ),
        ) { content() }
    }
}

// ------------------------------------------------------------------ Réglages

/** « expire le 31 décembre 2026 · 1/2 connexions » */
fun accountLine(p: PlaylistEntity, now: Long = System.currentTimeMillis()): String? {
    val parts = ArrayList<String>()
    if (p.accountStatus.isNotBlank() && !p.accountStatus.equals("Active", true)) parts += "compte ${XtreamClient.statusLabel(p.accountStatus)}"
    if (p.expiresAt > 0) parts += if (p.expiresAt < now) "expiré depuis le ${Fmt.date(p.expiresAt)}" else "expire le ${Fmt.date(p.expiresAt)}"
    if (p.maxConnections > 0) parts += "${p.activeConnections}/${p.maxConnections} connexion" + if (p.maxConnections > 1) "s" else ""
    return parts.joinToString(" · ").ifBlank { null }
}

@Composable
fun SettingsScreen(
    onEditPlaylist: (Long) -> Unit,
    onEpgSources: () -> Unit,
    onCheckChannels: () -> Unit = {},
    onTrakt: () -> Unit = {},
    onRemoteKeys: () -> Unit = {},
    onGroups: () -> Unit,
    onGroupOrder: () -> Unit = onGroups,
    onProfiles: () -> Unit = {},
    onAccount: () -> Unit = {},
    onDownloads: () -> Unit = {},
    onHome: () -> Unit = {},
) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val s by c.settings.state.collectAsState()
    val playlists by c.repo.playlists.collectAsState(emptyList())
    val sync by c.sync.state.collectAsState()
    val profiles by c.profiles.profiles.collectAsState()
    val activeProfile by c.profiles.active.collectAsState()
    val cloud by c.cloud.status.collectAsState()
    val unlocked by c.parental.unlocked.collectAsState()
    val isVaultUnlocked by c.isVaultUnlocked.collectAsState()
    val update by c.updater.state.collectAsState()
    var parentalAsk by remember { mutableStateOf<(() -> Unit)?>(null) }
    var newParentalPin by remember { mutableStateOf(false) }
    var diagLines by remember { mutableIntStateOf(DiagLog.size()) }
    LaunchedEffect(Unit) {
        while (true) {
            diagLines = DiagLog.size()
            kotlinx.coroutines.delay(2_000)
        }
    }
    val pipSupported = c.platform.supportsPip

    // Infos d'abonnement rafraîchies à l'ouverture des réglages (au plus toutes les 15 min).
    LaunchedEffect(playlists.map { it.id }) {
        playlists.filter { it.type != SourceType.M3U && System.currentTimeMillis() - it.accountCheckedAt > 15 * 60_000L }
            .forEach { runCatching { c.sync.checkAccount(it) } }
    }

    /** Les réglages du contrôle parental demandent le code (s'il existe et n'a pas été saisi). */
    fun parentalGate(action: () -> Unit) {
        if (s.parentalPinHash.isBlank() || unlocked) action() else parentalAsk = action
    }

    parentalAsk?.let { action ->
        PinDialog(title = "Code parental", onDismiss = { parentalAsk = null }) { pin ->
            val ok = c.unlockVault(pin)
            if (ok) action()
            ok
        }
    }
    if (newParentalPin) {
        NewPinDialog(title = "Code parental", onDismiss = { newParentalPin = false }) { pin -> scope.launch { c.parental.setPin(pin) } }
    }

    val folderLauncher = rememberPickFolder { uri, name ->
        if (uri != null) scope.launch { c.settings.update { it.copy(recFolderUri = uri, recFolderName = name) } }
    }
    val diagLauncher = rememberSaveFile("text/plain") { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching {
                val text = DiagLog.report(c.platform, c.db)
                withContext(Dispatchers.IO) { c.platform.openOutput(uri)?.use { it.write(text.toByteArray()) } ?: error("illisible") }
            }.isSuccess
            c.platform.toast(if (ok) "Journal enregistré" else "Échec de l'enregistrement")
        }
    }
    var pending by remember { mutableStateOf<PendingChoice?>(null) }
    var uaDialog by remember { mutableStateOf(false) }
    var openSubtitlesDialog by remember { mutableStateOf(false) }
    var tmdbDialog by remember { mutableStateOf(false) }
    var omdbDialog by remember { mutableStateOf(false) }
    var githubTokenDialog by remember { mutableStateOf(false) }
    var backupMenu by remember { mutableStateOf(false) }
    var historyMenu by remember { mutableStateOf(false) }
    var confirmClearAllDialog by remember { mutableStateOf(false) }
    var confirmClearAdultDialog by remember { mutableStateOf(false) }
    var keywordPurgeDialog by remember { mutableStateOf(false) }
    var categoryPurgeDialog by remember { mutableStateOf(false) }
    var categoryChoices by remember { mutableStateOf<List<Choice<String>>>(emptyList()) }
    var receiveDialog by remember { mutableStateOf(false) }
    var sendFlow by remember { mutableStateOf(false) }
    val tv = isTv()
    val desktop = c.platform.isDesktop
    // Téléphone : on scanne le QR code de la TV. PC : on saisit l'adresse affichée sous ce QR code.
    val canSend = (!tv && c.platform.hasCamera) || desktop
    val overlay = rememberOverlaySetting()

    if (receiveDialog) ReceiveFromPhoneDialog(onDismiss = { receiveDialog = false })
    if (sendFlow) SendToTvFlow(onClose = { sendFlow = false })

    fun update(t: (AppSettings) -> AppSettings) = scope.launch { c.settings.update(t) }

    fun <T : Any> choose(title: String, choices: List<Choice<T>>, selected: T, apply: (T) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        pending = PendingChoice(title, choices as List<Choice<Any>>, selected) { apply(it as T) }
    }

    pending?.let { p ->
        ChoiceDialog(p.title, p.choices, p.selected, onSelect = { p.apply(it) }, onDismiss = { pending = null })
    }
    if (uaDialog) {
        TextInputDialog(
            "User-Agent par défaut", initial = s.userAgent, label = "User-Agent",
            onConfirm = { v -> update { it.copy(userAgent = v.trim().ifBlank { fr.yannickuhrig.vyzo.data.net.Http.DEFAULT_UA }) } },
            onDismiss = { uaDialog = false },
        )
    }
    if (openSubtitlesDialog) {
        TextInputDialog(
            "Clé API OpenSubtitles.com", initial = s.openSubtitlesApiKey, label = "Clé API (laisser vide pour le mode public)",
            placeholder = "Ex : abcdef123456...",
            onConfirm = { v -> update { it.copy(openSubtitlesApiKey = v.trim()) } },
            onDismiss = { openSubtitlesDialog = false },
        )
    }
    if (tmdbDialog) {
        TextInputDialog(
            "Clé API TheMovieDB (TMDB)", initial = s.tmdbApiKey, label = "Clé API (optionnelle, laisser vide pour le mode de secours)",
            placeholder = "Ex : 32 caractères hexadécimaux...",
            onConfirm = { v -> update { it.copy(tmdbApiKey = v.trim()) } },
            onDismiss = { tmdbDialog = false },
        )
    }
    if (omdbDialog) {
        TextInputDialog(
            "Clé API OMDb", initial = s.omdbApiKey, label = "Clé personnelle (omdbapi.com)",
            placeholder = "Ex : 8 caractères...",
            onConfirm = { v -> update { it.copy(omdbApiKey = v.trim()) } },
            onDismiss = { omdbDialog = false },
        )
    }
    if (githubTokenDialog) {
        TextInputDialog(
            "Jeton d'accès GitHub (dépôt privé)", initial = s.githubToken, label = "Token (ghp_...)",
            onConfirm = { v -> update { it.copy(githubToken = v.trim()) } },
            onDismiss = { githubTokenDialog = false },
        )
    }

    val exportBackup = rememberExportBackup()
    val restoreBackup = rememberRestoreBackup(onPhone = { receiveDialog = true })

    if (backupMenu) {
        ActionDialog(
            title = "Sauvegarde",
            body = "Playlists (avec identifiants), groupes, favoris, sources de guide et réglages.",
            actions = listOf(
                ActionItem("Exporter vers un fichier…", Icons.Default.Backup) { exportBackup() },
                ActionItem("Importer un fichier…", Icons.Default.Restore) { restoreBackup() },
            ),
            onDismiss = { backupMenu = false },
        )
    }

    if (historyMenu) {
        ActionDialog(
            title = "Purger l'historique",
            body = "Supprimer l'historique des chaînes regardées et les positions de lecture sur cet appareil et sur le compte.",
            actions = listOf(
                ActionItem("Tout effacer", Icons.Default.Delete, tint = VyzoColors.Red, detail = "Efface l'historique complet") {
                    confirmClearAllDialog = true
                },
                ActionItem("Purger uniquement les contenus adultes", Icons.Default.Lock, detail = "Catégories sensibles / XXX identifiées") {
                    confirmClearAdultDialog = true
                },
                ActionItem("Purger par mot-clé…", Icons.Default.Edit, detail = "Supprime les titres contenant un texte précis") {
                    keywordPurgeDialog = true
                },
                ActionItem("Purger par catégorie…", Icons.AutoMirrored.Filled.List, detail = "Sélectionner une catégorie spécifique") {
                    scope.launch {
                        val cats = c.db.categories().all()
                        if (cats.isEmpty()) {
                            c.platform.toast("Aucune catégorie trouvée")
                        } else {
                            categoryChoices = cats.map { Choice(it.uid, it.name, if (it.adult) "Adulte (18+)" else "") }
                            categoryPurgeDialog = true
                        }
                    }
                },
                ActionItem("Annuler") {},
            ),
            onDismiss = { historyMenu = false },
        )
    }

    if (confirmClearAllDialog) {
        ConfirmDialog(
            title = "Effacer tout l'historique",
            message = "Voulez-vous vraiment effacer tout votre historique de visionnage ?\nCette action supprimera vos chaînes récentes et vos reprises de lecture sur tous vos appareils connectés.",
            confirmLabel = "Effacer tout",
            onConfirm = {
                confirmClearAllDialog = false
                scope.launch {
                    c.cloud.purgeHistory(clearAll = true)
                    c.platform.toast("Historique de visionnage effacé")
                }
            },
            onDismiss = { confirmClearAllDialog = false },
        )
    }

    if (confirmClearAdultDialog) {
        ConfirmDialog(
            title = "Purger les contenus adultes",
            message = "Voulez-vous supprimer l'historique de visionnage de toutes les catégories pour adultes (XXX / 18+) ?",
            confirmLabel = "Purger les contenus adultes",
            onConfirm = {
                confirmClearAdultDialog = false
                scope.launch {
                    c.cloud.purgeHistory(clearAll = false, adultOnly = true)
                    c.platform.toast("Historique des contenus adultes purgé")
                }
            },
            onDismiss = { confirmClearAdultDialog = false },
        )
    }

    if (keywordPurgeDialog) {
        TextInputDialog(
            title = "Purger par mot-clé",
            label = "Mot-clé ou titre",
            placeholder = "Ex: XXX, charme, film sensible…",
            confirmLabel = "Purger",
            onConfirm = { term ->
                keywordPurgeDialog = false
                val kw = term.trim()
                if (kw.isNotBlank()) {
                    scope.launch {
                        c.cloud.purgeHistory(clearAll = false, keyword = kw)
                        c.platform.toast("Historique purgé pour \"$kw\"")
                    }
                }
            },
            onDismiss = { keywordPurgeDialog = false },
        )
    }

    if (categoryPurgeDialog) {
        ChoiceDialog(
            title = "Choisir une catégorie à purger",
            choices = categoryChoices,
            selected = null,
            onSelect = { catUid ->
                categoryPurgeDialog = false
                scope.launch {
                    c.cloud.purgeHistory(clearAll = false, categoryUid = catUid)
                    val catName = categoryChoices.firstOrNull { it.value == catUid }?.label ?: catUid
                    c.platform.toast("Historique purgé pour la catégorie \"$catName\"")
                }
            },
            onDismiss = { categoryPurgeDialog = false },
        )
    }

    val active = playlists.firstOrNull { it.id == s.activePlaylistId } ?: playlists.firstOrNull()
    val downloads by remember { c.downloads.observeAll() }.collectAsState(emptyList())
    val isHost = s.resolvesRecordingHost(isDesktop = desktop, isTv = tv)
    val pinSet = s.parentalPinHash.isNotBlank()
    // Avec le Mode Furtif, c'est le coffre-fort (et non le simple code parental) qui donne accès aux contenus masqués.
    val isUnlocked = if (s.stealthModeEnabled) isVaultUnlocked else unlocked
    fun toggleVault() { if (isUnlocked) c.lockVault() else parentalGate {} }

    val entries = buildSettingEntries {
        // ---------------------------------------------------------- 1. Sources, Profils & EPG
        category(SettingsCategory.SOURCES)
        section("Profils")
        row(
            "Profils",
            "${profiles.orEmpty().size} profil(s)" + (activeProfile?.let { " · sur cet appareil : ${it.name}" } ?: ""),
            Icons.Default.People,
        ) { onProfiles() }

        section("Playlists")
        playlists.forEach { p ->
            val isActive = p.id == active?.id
            row(
                title = p.name + if (isActive) "  ·  active" else "",
                value = listOfNotNull(
                    when (p.type) { SourceType.M3U -> "M3U"; SourceType.XTREAM -> "Xtream Codes"; SourceType.STALKER -> "Portail Stalker" },
                    if (p.lastSync > 0) "mise à jour ${Fmt.dayTime(p.lastSync)}" else "jamais chargée",
                    accountLine(p),
                    p.lastError.takeIf { it.isNotBlank() }?.let { "erreur : $it" },
                ).joinToString(" · "),
                icon = if (isActive) Icons.Default.CheckCircle else Icons.AutoMirrored.Filled.List,
                onLongClick = { onEditPlaylist(p.id) },
                trailing = { Icon(Icons.Default.Edit, "Modifier", tint = VyzoColors.TextDim) },
            ) {
                if (!isActive) scope.launch { c.repo.setActivePlaylist(p.id) } else onEditPlaylist(p.id)
            }
        }
        if (playlists.size > 1) {
            note("Sélectionne une playlist pour l'activer ; sélectionne la playlist active (ou appui long) pour la modifier.")
            toggle(
                "Afficher toutes les playlists ensemble",
                "Chaînes, films et séries de toutes les playlists dans les mêmes listes",
                s.showAllPlaylists,
            ) { v -> update { it.copy(showAllPlaylists = v) } }
        }
        row("Ajouter une playlist", icon = Icons.AutoMirrored.Filled.PlaylistAdd) { onEditPlaylist(0) }
        row(
            "Recevoir depuis un téléphone",
            "Affiche un QR code : saisis une playlist sur le téléphone ou reçois toute sa configuration Vyzo",
            Icons.Default.QrCode2,
        ) { receiveDialog = true }
        if (canSend) {
            row(
                "Envoyer vers une TV",
                if (desktop) "Saisis l'adresse affichée sous le QR code de Vyzo sur la TV, puis coche ce que tu lui envoies"
                else "Scanne le QR code affiché par Vyzo sur la TV, puis coche ce que tu lui envoies : playlists, compte, favoris, guide, réglages, enregistrements",
                Icons.Default.Cast,
            ) { sendFlow = true }
        }
        row(
            "Tout mettre à jour maintenant",
            if (sync.running) sync.message else sync.error?.let { "Dernière erreur : $it" } ?: "Playlists et guide TV",
            icon = Icons.Default.Refresh,
        ) { if (!sync.running) c.sync.launchAll() }
        row("Mise à jour automatique", if (s.refreshHours == 0) "Jamais" else "Toutes les ${s.refreshHours} h") {
            choose("Mise à jour automatique", listOf(0, 6, 12, 24, 48).map { Choice(it, if (it == 0) "Jamais" else "Toutes les $it heures") }, s.refreshHours) { h ->
                update { it.copy(refreshHours = h) }
                c.platform.scheduleAutoSync(h)
            }
        }
        if (playlists.any { it.type != SourceType.M3U }) {
            row(
                "Prévenir avant l'expiration de l'abonnement",
                if (s.expiryWarnDays == 0) "Jamais" else "${s.expiryWarnDays} jour(s) avant (Xtream et Stalker)",
            ) {
                choose(
                    "Prévenir avant l'expiration",
                    listOf(0, 1, 3, 7, 14, 30).map { Choice(it, if (it == 0) "Jamais" else "$it jour(s) avant") },
                    s.expiryWarnDays,
                ) { v -> update { it.copy(expiryWarnDays = v, lastExpiryWarning = 0) } }
            }
        }

        section("Guide TV (EPG)")
        row("Sources du guide (EPG)", "Guides XMLTV des playlists et sources ajoutées", Icons.AutoMirrored.Filled.List) { onEpgSources() }
        row("Vérifier les chaînes", "Teste les flux un par un et repère ceux qui ne répondent plus", Icons.Default.Refresh) { onCheckChannels() }
        row("Jours de programmes à charger", "${s.epgDaysAhead} jour(s) à venir") {
            choose("Jours à venir", (1..7).map { Choice(it, "$it jour(s)") }, s.epgDaysAhead) { v -> update { it.copy(epgDaysAhead = v) } }
        }
        row("Programmes passés conservés", "${s.epgDaysBehind} jour(s) (utile pour le replay)") {
            choose("Jours passés", (0..7).map { Choice(it, if (it == 0) "Aucun" else "$it jour(s)") }, s.epgDaysBehind) { v -> update { it.copy(epgDaysBehind = v) } }
        }
        row("Taille de la grille", guideSizeLabel(s.guideSize)) {
            choose(
                "Taille de la grille du guide",
                listOf(
                    Choice("COMPACT", guideSizeLabel("COMPACT"), "Plus de chaînes et d'heures à l'écran"),
                    Choice("NORMAL", guideSizeLabel("NORMAL")),
                    Choice("LARGE", guideSizeLabel("LARGE"), "Lignes plus hautes, plus lisible de loin"),
                ),
                s.guideSize,
            ) { v -> update { it.copy(guideSize = v) } }
        }
        row("Décalage horaire du guide", shiftLabel(s.epgShiftMinutes)) {
            choose("Décalage du guide", (-24..24).map { it * 30 }.map { Choice(it, shiftLabel(it)) }, s.epgShiftMinutes) { v ->
                update { it.copy(epgShiftMinutes = v) }
                c.sync.launchEpg()
            }
        }

        // ---------------------------------------------------------- 2. Lecture, PiP & Sous-titres
        category(SettingsCategory.PLAYBACK)
        section("Lecteur")
        if (tv && !desktop) {
            row("Touches de la télécommande", if (RemoteKeymap.isDefault(s.remoteBindings)) "Touches d'origine" else "Personnalisées") { onRemoteKeys() }
        }
        if (!tv && !desktop) {
            row("Orientation de l'écran", PlayerOrientation.label(s.playerOrientation)) {
                choose(
                    "Orientation de l'écran",
                    listOf(
                        Choice(PlayerOrientation.AUTO, PlayerOrientation.label(PlayerOrientation.AUTO), "Suit le téléphone"),
                        Choice(PlayerOrientation.LANDSCAPE, PlayerOrientation.label(PlayerOrientation.LANDSCAPE)),
                        Choice(PlayerOrientation.PORTRAIT, PlayerOrientation.label(PlayerOrientation.PORTRAIT)),
                    ),
                    s.playerOrientation,
                ) { v -> scope.launch { c.settings.update { it.copy(playerOrientation = v) } } }
            }
        }
        row("Format des flux Xtream", if (s.xtreamFormat == "m3u8") "HLS (.m3u8)" else "MPEG-TS (.ts)") {
            choose("Format Xtream", listOf(Choice("ts", "MPEG-TS (.ts)", "Recommandé, le plus compatible"), Choice("m3u8", "HLS (.m3u8)", "Si le serveur le préfère")), s.xtreamFormat) { v ->
                update { it.copy(xtreamFormat = v) }
            }
        }
        row("Décodeur", decoderLabel(s.decoderMode, desktop)) {
            choose(
                "Décodeur",
                if (desktop) listOf(
                    Choice("AUTO", decoderLabel("AUTO", true), "Accélération matérielle de la carte graphique quand c'est possible"),
                    Choice("SOFTWARE", decoderLabel("SOFTWARE", true), "Si l'image saccade, se fige ou reste noire"),
                ) else listOf(
                    Choice("AUTO", decoderLabel("AUTO"), "Matériel, avec FFmpeg pour les sons non gérés (AC3, DTS…)"),
                    Choice("SOFTWARE", decoderLabel("SOFTWARE"), "Si le son ne sort pas ou grésille"),
                    Choice("HARDWARE", decoderLabel("HARDWARE"), "Désactive FFmpeg"),
                ),
                s.decoderMode,
            ) { v -> scope.launch { c.settings.update { it.copy(decoderMode = v) }; if (c.player.hasPlayer) c.player.recreate() } }
        }
        row("Mémoire tampon", bufferLabel(s.bufferMode)) {
            choose(
                "Mémoire tampon",
                listOf(Choice("SHORT", bufferLabel("SHORT"), "Zapping plus rapide"), Choice("NORMAL", bufferLabel("NORMAL")), Choice("LONG", bufferLabel("LONG"), "Connexion instable")),
                s.bufferMode,
            ) { v -> scope.launch { c.settings.update { it.copy(bufferMode = v) }; if (c.player.hasPlayer) c.player.recreate() } }
        }
        toggle(
            "Zapping rapide",
            "En direct, l'image démarre dès 500 ms de flux reçu. Peut saccader au démarrage sur une connexion faible",
            s.fastZap,
        ) { v -> update { it.copy(fastZap = v) } }
        if (c.player.capabilities.refreshRate) {
            toggle(
                "Adapter la fréquence de l'écran",
                "Cale l'écran sur la vidéo (24, 25, 50 Hz…) pour un mouvement plus régulier. L'écran peut rester noir un instant au changement",
                s.matchFrameRate,
            ) { v -> update { it.copy(matchFrameRate = v) } }
        }
        if (c.player.capabilities.tunneling) {
            toggle(
                "Lecture en tunnel (films et séries)",
                "Image et son synchronisés par le matériel. À essayer si un film saccade ; sans effet en direct ou avec un décalage audio",
                s.tunnelingVod,
            ) { v -> scope.launch { c.settings.update { it.copy(tunnelingVod = v) }; if (c.player.hasPlayer) c.player.recreate() } }
        }
        toggle("Horloge dans le lecteur", "Affiche l'heure en permanence pendant la lecture", s.playerClock) { v -> update { it.copy(playerClock = v) } }
        row("Pause du direct", if (s.timeshiftMinutes == 0) "Désactivée" else "Jusqu'à ${s.timeshiftMinutes} min (mémoire tampon sur l'appareil)", Icons.Default.Timer) {
            choose(
                "Pause du direct",
                listOf(0, 15, 30, 60, 120).map { Choice(it, if (it == 0) "Désactivée" else "$it min", if (it >= 60) "Demande de la place libre (environ 2 Go par heure en HD)" else "") },
                s.timeshiftMinutes,
            ) { v -> update { it.copy(timeshiftMinutes = v) } }
        }
        toggle(
            "Qualité automatique en direct",
            "Si l'image saccade, passe à une version plus légère de la chaîne (4K → FHD → HD → SD) quand la playlist en propose une",
            s.autoQuality,
        ) { v -> update { it.copy(autoQuality = v) } }
        row("User-Agent par défaut", s.userAgent) { uaDialog = true }

        section("Audio et sous-titres")
        row("Langue audio préférée", languages.firstOrNull { it.first == s.audioLang }?.second ?: s.audioLang) {
            choose("Langue audio", languages.map { Choice(it.first, it.second) }, s.audioLang) { v ->
                scope.launch { c.settings.update { it.copy(audioLang = v) }; if (c.player.hasPlayer) c.player.recreate() }
            }
        }
        row("Sous-titres par défaut", subtitleLabel(s.subtitleLang)) {
            choose("Sous-titres", listOf(Choice("", "Désactivés")) + languages.filter { it.first.isNotBlank() }.map { Choice(it.first, it.second) }, s.subtitleLang) { v ->
                scope.launch { c.settings.update { it.copy(subtitleLang = v) }; if (c.player.hasPlayer) c.player.recreate() }
            }
        }
        if (c.player.capabilities.audioDelay) {
            row("Décalage audio", audioDelayLabel(s.audioDelayMs.toLong())) {
                choose("Décalage audio", AUDIO_DELAY_CHOICES.map { Choice(it.toInt(), audioDelayLabel(it)) }, s.audioDelayMs) { v ->
                    update { it.copy(audioDelayMs = v) }
                    c.player.setAudioDelay(v.toLong())
                }
            }
        }
        if (c.player.capabilities.passthrough) {
            toggle(
                "Son direct vers l'ampli (passthrough)",
                "Envoie l'AC3, l'E-AC3 ou le DTS sans les décoder quand la sortie les accepte. À couper si le son disparaît",
                s.audioPassthrough,
            ) { v -> scope.launch { c.settings.update { it.copy(audioPassthrough = v) }; if (c.player.hasPlayer) c.player.recreate() } }
        }
        if (c.player.capabilities.loudnessLeveling) {
            toggle(
                "Égaliser le volume des chaînes",
                if (c.player.capabilities.passthrough) "Ramène les chaînes à un niveau comparable. Sans effet sur le son envoyé en passthrough" else "Ramène les chaînes à un niveau comparable",
                s.loudnessLeveling,
            ) { v -> scope.launch { c.settings.update { it.copy(loudnessLeveling = v) }; if (c.player.hasPlayer) c.player.recreate() } }
        }
        row(
            "Clé API OpenSubtitles",
            if (s.openSubtitlesApiKey.isBlank()) "Fournisseur public sans clé" else "Configurée (••••••••)",
        ) { openSubtitlesDialog = true }

        section("Arrière-plan et incrustation (PiP)")
        toggle(
            "Continuer la lecture en arrière-plan",
            "Affiche un mini-lecteur flottant lors de la navigation dans l'appli",
            s.continuePlaybackInBackground,
        ) { v -> update { it.copy(continuePlaybackInBackground = v) } }
        if (pipSupported) {
            toggle("Incrustation en quittant l'appli", "La vidéo continue dans une petite fenêtre (PiP)", s.autoPip) { v -> update { it.copy(autoPip = v) } }
        }

        section("Séries")
        toggle(
            "Sauter automatiquement les intros (Séries)",
            "Seulement si TheIntroDB (theintrodb.org) connaît l'intro de l'épisode. Sinon, rien n'est sauté.",
            s.autoSkipIntro,
        ) { v -> update { it.copy(autoSkipIntro = v) } }
        toggle(
            "Sauter automatiquement les récaps (Séries)",
            "Seulement si TheIntroDB connaît le récap de l'épisode. Sinon, rien n'est sauté.",
            s.autoSkipRecap,
        ) { v -> update { it.copy(autoSkipRecap = v) } }
        toggle(
            "Épisode suivant automatique (générique de fin)",
            "Lance l'épisode suivant 30 secondes avant la fin, après 10 secondes pour annuler",
            s.autoNextEpisodeOnOutro,
        ) { v -> update { it.copy(autoNextEpisodeOnOutro = v) } }

        // ---------------------------------------------------------- 3. Chaînes, Groupes & Métadonnées
        category(SettingsCategory.CHANNELS)
        section("Groupes")
        row("Groupes et favoris", "Créer des groupes, masquer ou personnaliser les catégories", Icons.Default.Folder) { onGroups() }
        row("Gérer l'ordre des groupes", "Réorganiser les catégories de Live TV, Films et Séries", Icons.AutoMirrored.Filled.List) { onGroupOrder() }

        section("Chaînes")
        toggle("Afficher les numéros de chaîne", checked = s.showNumbers) { v -> update { it.copy(showNumbers = v) } }
        toggle("Inverser le zapping", "▲ passe à la chaîne précédente", s.invertZap) { v -> update { it.copy(invertZap = v) } }
        toggle("Lancer la dernière chaîne au démarrage", checked = s.autoplayLast) { v -> update { it.copy(autoplayLast = v) } }

        section("Films et séries")
        toggle(
            "Genres automatiques",
            "Regroupe les titres par genre (Action, Comédie, Anime…), même quand le fournisseur a des centaines de catégories",
            s.autoGenres,
        ) { v -> update { it.copy(autoGenres = v) } }
        toggle(
            "Mise en avant",
            "Bandeau au-dessus des affiches : image, note et résumé du titre (sur TV, celui que tu sélectionnes)",
            s.vodSpotlight,
        ) { v -> update { it.copy(vodSpotlight = v) } }
        toggle(
            "Alerte de nouvel épisode",
            "Notification quand une série en favori ou en cours a un nouvel épisode (playlists Xtream, vérifié toutes les 6 h)",
            s.newEpisodeAlerts,
        ) { v -> update { it.copy(newEpisodeAlerts = v) } }

        section("Métadonnées")
        row(
            "Clé API TheMovieDB (TMDB)",
            if (s.tmdbApiKey.isBlank()) { if (c.tmdb.hasBuiltInKey) "Clé intégrée à Vyzo" else "Mode de secours sans clé (bandes-annonces et distribution)" } else "Configurée (••••••••)",
        ) { tmdbDialog = true }
        row(
            "Clé API OMDb",
            if (s.omdbApiKey.isBlank()) "Facultative : ajoute les notes IMDb, Rotten Tomatoes et Metacritic aux fiches" else "Configurée",
        ) { omdbDialog = true }
        row("Trakt", if (s.traktConnected) "Connecté" else "Suivi de ce que tu regardes, listes et recommandations") { onTrakt() }
        toggle("Scores en direct", "Scores des matchs dans l'espace sport (service non officiel d'ESPN)", s.sportScores) { v -> update { it.copy(sportScores = v) } }

        section("Interface")
        row(
            "Personnaliser l'accueil",
            (if (s.startScreen == "HOME") "Ouvert au lancement · " else "") + "${s.homeRowsFor(s.activeProfileId).size} rangée(s), horloge, météo",
            Icons.Default.Dashboard,
        ) { onHome() }
        if (tv) {
            val nav = fr.yannickuhrig.vyzo.ui.NavLayout.from(s)
            row("Position du menu", nav.position.label) {
                choose(
                    "Position du menu",
                    fr.yannickuhrig.vyzo.ui.NavPosition.entries.map { Choice(it.name, it.label) },
                    nav.position.name,
                ) { v -> update { it.copy(navPosition = v) } }
            }
            if (nav.isRail) {
                row("Affichage du menu", "${nav.style.label} · ${nav.style.detail}") {
                    choose(
                        "Affichage du menu",
                        fr.yannickuhrig.vyzo.ui.NavStyle.entries.map { Choice(it.name, it.label, it.detail) },
                        nav.style.name,
                    ) { v -> update { it.copy(navStyle = v) } }
                }
            }
        }
        if (!c.platform.hasFixedFormFactor) {
            row("Mode d'affichage", uiModeLabel(s.uiMode)) {
                choose("Mode d'affichage", listOf("AUTO", "TV", "PHONE").map { Choice(it, uiModeLabel(it)) }, s.uiMode) { v -> update { it.copy(uiMode = v) } }
            }
        }

        // ---------------------------------------------------------- 4. Cloud, Enregistrements & Hors-Ligne
        category(SettingsCategory.CLOUD)
        section("Compte et sauvegarde")
        row(
            "Compte Vyzo",
            when {
                cloud.signedIn && cloud.error != null -> "${cloud.email} · ${cloud.error}"
                cloud.signedIn -> "${cloud.email}" + if (cloud.lastSyncAt > 0) " · synchronisé ${Fmt.dayTime(cloud.lastSyncAt)}" else ""
                else -> "Retrouver profils, favoris et progression sur tous tes appareils"
            },
            Icons.Default.Cloud,
        ) { onAccount() }
        row("Exporter / importer la configuration", icon = Icons.Default.Backup) { backupMenu = true }

        section("Enregistrements")
        toggle(
            title = "Exécuter les enregistrements programmés sur cet appareil",
            value = if (isHost) "Cet appareil déclenche et écrit les enregistrements" else "Désactivé : un autre appareil (TV ou PC) exécutera les programmations",
            checked = isHost,
        ) { checked ->
            scope.launch {
                c.settings.update { it.copy(isRecordingHost = checked) }
                if (checked) {
                    c.recordings.rescheduleAll()
                }
            }
        }
        row(
            "Dossier des enregistrements",
            if (s.recFolderUri.isBlank()) {
                if (desktop) c.recordings.directory.absolutePath + " (choisis un autre disque ou un dossier partagé pour plus de place)"
                else "Dossier de Vyzo sur l'appareil (choisis une clé USB ou un dossier partagé pour plus de place)"
            }
            else if (desktop) s.recFolderUri
            else s.recFolderName.ifBlank { "Dossier choisi" },
            Icons.Default.Folder,
        ) {
            if (!folderLauncher.launch()) c.platform.toast("Cet appareil ne propose pas de sélecteur de dossiers", long = true)
        }
        if (s.recFolderUri.isNotBlank()) {
            row("Revenir au dossier de Vyzo") { update { it.copy(recFolderUri = "", recFolderName = "") } }
        }
        if (desktop) {
            val dir = s.recFolderUri.takeIf { it.isNotBlank() }?.let { java.io.File(it) }?.takeIf { it.isDirectory } ?: c.recordings.directory
            row("Ouvrir le dossier des enregistrements", dir.absolutePath, Icons.Default.FolderOpen) {
                c.platform.openFolder(dir)
            }
        }
        row("Marge avant l'émission", "${s.recPaddingBeforeMin} min") {
            choose("Marge avant", listOf(0, 1, 2, 5, 10, 15).map { Choice(it, "$it min") }, s.recPaddingBeforeMin) { v -> update { it.copy(recPaddingBeforeMin = v) } }
        }
        row("Marge après l'émission", "${s.recPaddingAfterMin} min") {
            choose("Marge après", listOf(0, 2, 5, 10, 15, 30).map { Choice(it, "$it min") }, s.recPaddingAfterMin) { v -> update { it.copy(recPaddingAfterMin = v) } }
        }

        section("Rappels")
        row("Rappel avant le début", if (s.reminderMinutesBefore == 0) "À l'heure" else "${s.reminderMinutesBefore} min avant") {
            choose("Rappel", listOf(0, 1, 2, 5, 10, 15).map { Choice(it, if (it == 0) "À l'heure" else "$it min avant") }, s.reminderMinutesBefore) { v ->
                scope.launch { c.settings.update { it.copy(reminderMinutesBefore = v) }; c.reminders.rescheduleAll() }
            }
        }
        if (overlay != null) {
            row(
                "Afficher les rappels par-dessus les autres applis",
                if (overlay.granted) "Autorisé" else "Permet d'ouvrir Vyzo quand un programme commence",
            ) { overlay.open() }
        }

        section("Hors-ligne")
        row("Téléchargements", downloadsLabel(downloads), Icons.Default.DownloadForOffline) { onDownloads() }

        if (desktop) {
            section("Vyzo PC")
            toggle(
                "Rester dans la zone de notification",
                "En fermant la fenêtre, Vyzo continue en arrière-plan pour les rappels et les enregistrements",
                s.keepInTray,
            ) { v -> update { it.copy(keepInTray = v) } }
            toggle("Lancer Vyzo à l'ouverture de session", "Démarre réduit dans la zone de notification", s.launchAtLogin) { v ->
                if (c.platform.setLaunchAtLogin(v)) update { it.copy(launchAtLogin = v) }
                else c.platform.toast("Impossible de modifier le lancement automatique")
            }
        }

        // ---------------------------------------------------------- 5. Confidentialité & Coffre-fort
        category(SettingsCategory.PRIVACY)
        section("Coffre-fort et contrôle parental")
        if (!pinSet) {
            row(
                "Activer le contrôle parental",
                "Choisis un code : les catégories et chaînes verrouillées restent cachées tant qu'il n'est pas saisi",
                Icons.Default.Lock,
            ) { newParentalPin = true }
        } else {
            row(
                if (isUnlocked) (if (s.stealthModeEnabled) "Reverrouiller le coffre-fort" else "Reverrouiller maintenant")
                else (if (s.stealthModeEnabled) "Déverrouiller les contenus masqués" else "Afficher le contenu verrouillé"),
                if (isUnlocked) "Les contenus protégés sont actuellement accessibles" else "Pour cette session",
                if (isUnlocked) Icons.Default.Lock else Icons.Default.LockOpen,
            ) { toggleVault() }
            toggle(
                "Mode Furtif : Masquer complètement les catégories verrouillées/adultes",
                "Les catégories sensibles et adultes sont totalement invisibles sans cadenas tant que la session n'est pas déverrouillée",
                s.stealthModeEnabled,
            ) { v -> parentalGate { update { it.copy(stealthModeEnabled = v) } } }
            row("Changer le code parental", icon = Icons.Default.Lock) { parentalGate { newParentalPin = true } }
            row("Désactiver le contrôle parental", icon = Icons.Default.LockOpen) {
                parentalGate { scope.launch { c.parental.setPin(null) } }
            }
        }
        toggle(
            "Verrouiller les catégories pour adultes",
            "Détectées par leur nom (XXX, Adultes, 18+…) ou signalées par le fournisseur ; appliqué à la prochaine mise à jour",
            s.parentalAutoAdult,
        ) { v -> parentalGate { scope.launch { c.parental.setAutoAdult(v); if (v) c.sync.launchAll() } } }
        toggle("Code parental pour quitter un profil enfant", checked = s.parentalKidExit) { v ->
            parentalGate { update { it.copy(parentalKidExit = v) } }
        }
        note(
            "Pour verrouiller une catégorie ou une chaîne précise : Groupes et catégories (cadenas), ou appui long sur une chaîne. " +
                "Sur un profil enfant, le contenu verrouillé n'apparaît jamais.",
        )

        section("Historique")
        row(
            "Effacer l'historique de lecture",
            "Purger les chaînes récentes et les reprises de lecture (global, adulte / XXX, par mot-clé ou catégorie)",
            icon = Icons.Default.Delete,
        ) {
            historyMenu = true
        }

        // ---------------------------------------------------------- 6. Mises à jour & À propos
        category(SettingsCategory.ABOUT)
        section("Mises à jour")
        row(
            "Rechercher une mise à jour",
            when (val u = update) {
                UpdateState.Checking -> "Vérification…"
                UpdateState.UpToDate -> "Vyzo est à jour"
                is UpdateState.Available -> "Version ${u.info.versionName} disponible"
                is UpdateState.Downloading -> "Téléchargement : ${(u.progress * 100).toInt()} %"
                is UpdateState.Installing -> "Installation…"
                is UpdateState.Failed -> u.message
                UpdateState.Idle -> "Version installée : ${c.platform.appVersion}"
            },
            Icons.Default.SystemUpdate,
        ) { scope.launch { c.updater.check() } }
        toggle("Rechercher au lancement", checked = s.autoCheckUpdates) { v -> update { it.copy(autoCheckUpdates = v) } }
        toggle(
            "Recevoir les préversions (bêta)",
            "Propose les versions préliminaires et de test publiées sur GitHub (-beta, -rc)",
            s.includeBetaUpdates,
        ) { v -> update { it.copy(includeBetaUpdates = v) } }
        if (s.includeBetaUpdates) {
            row(
                "Jeton GitHub (si dépôt privé)",
                if (s.githubToken.isNotBlank()) "Jeton configuré" else "Sans jeton, les versions bêta d'un dépôt privé restent invisibles",
                Icons.Default.Lock,
            ) { githubTokenDialog = true }
        }

        section("Diagnostic")
        toggle(
            "Journal détaillé",
            "Garde la trace des échanges avec les serveurs (identifiants, MAC et jetons masqués) pour analyser un problème",
            s.diagnosticLog,
        ) { v -> DiagLog.enabled = v; update { it.copy(diagnosticLog = v) } }
        row("Exporter le journal", "${plural(diagLines, "ligne")} de diagnostic · à joindre à un signalement", Icons.Default.BugReport) {
            diagLines = DiagLog.size()
            if (!diagLauncher.launch("vyzo-diagnostic.txt")) {
                scope.launch {
                    val f = java.io.File(c.platform.localBackupFile.parentFile, "vyzo-diagnostic.txt")
                    withContext(Dispatchers.IO) { f.writeText(DiagLog.report(c.platform, c.db)) }
                    c.platform.toast("Enregistré dans ${f.absolutePath}", long = true)
                }
            }
        }
        toggle(
            "Envoyer les rapports de plantage",
            "Version, modèle de l'appareil et détail technique de l'erreur, sans données personnelles" +
                CrashReporter.pendingCount(c.platform).let { if (it > 0) " · $it en attente" else "" },
            s.sendCrashReports,
        ) { v -> update { it.copy(sendCrashReports = v) } }

        section("À propos")
        row(
            if (desktop) "Vyzo PC ${c.platform.appVersion}" else "Vyzo ${c.platform.appVersion}",
            (if (desktop) "Lecteur IPTV pour Linux et Windows. " else "Lecteur IPTV pour Android TV et téléphone. ") +
                "Aucun contenu n'est fourni : l'application lit uniquement les sources que tu ajoutes.",
            Icons.Default.Info,
        ) {}
        row(
            "Licence",
            "Logiciel libre sous licence GNU GPL 3, fourni sans aucune garantie. Tu peux le redistribuer selon les termes de cette licence. " +
                "Interface TV dérivée en partie d'IPVIO Stream (github.com/Emrodev/IPVIO-Stream). " +
                "Code source de cette version : ${c.platform.cloudUrl}/storage/v1/object/public/releases/vyzo-sources-${c.platform.appVersion}.tar.gz",
            Icons.Default.Info,
        ) {}
        row(
            "Repères d'intro et de générique",
            "Horodatages : TheIntroDB (theintrodb.org). Séries retrouvées par leur titre : TVmaze (tvmaze.com), licence CC BY-SA.",
            Icons.Default.Info,
        ) {}
    }

    // Sous-titre de chaque catégorie : l'état actuel en un coup d'œil.
    val cloudState = when {
        !cloud.signedIn -> "Non connecté"
        cloud.error != null -> "Erreur de synchronisation"
        cloud.running -> "Synchronisation…"
        cloud.lastSyncAt > 0 -> "Synchronisé ${Fmt.dayTime(cloud.lastSyncAt)}"
        else -> "Connecté"
    }
    val vaultState = when {
        !pinSet -> "Désactivé"
        isUnlocked -> "Déverrouillé"
        else -> "Verrouillé"
    }
    val summaries = mapOf(
        SettingsCategory.SOURCES to listOfNotNull(
            if (playlists.isEmpty()) "Aucune playlist" else "${playlists.size} playlist(s)" + (active?.let { " · ${it.name}" } ?: ""),
            "${profiles.orEmpty().size} profil(s)",
            if (sync.running) "mise à jour…" else null,
        ).joinToString(" · "),
        SettingsCategory.PLAYBACK to listOfNotNull(
            "Décodeur ${decoderLabel(s.decoderMode, desktop).lowercase()}",
            "sous-titres : ${subtitleLabel(s.subtitleLang).lowercase()}",
            if (pipSupported) "PiP ${if (s.autoPip) "activé" else "désactivé"}" else null,
        ).joinToString(" · "),
        SettingsCategory.CHANNELS to listOf(
            if (s.showNumbers) "Numéros affichés" else "Numéros masqués",
            if (s.tmdbApiKey.isBlank()) "TMDB sans clé" else "TMDB configuré",
        ).joinToString(" · "),
        SettingsCategory.CLOUD to listOf(
            cloudState,
            if (isHost) "enregistre ici" else "enregistre ailleurs",
            downloadsLabel(downloads).lowercase(),
        ).joinToString(" · "),
        SettingsCategory.PRIVACY to listOfNotNull(
            if (pinSet) "Code parental actif" else "Contrôle parental désactivé",
            if (pinSet && s.stealthModeEnabled) "Mode Furtif" else null,
            if (pinSet) vaultState.lowercase() else null,
        ).joinToString(" · "),
        SettingsCategory.ABOUT to listOfNotNull(
            "Version ${c.platform.appVersion}",
            (update as? UpdateState.Available)?.let { "${it.info.versionName} disponible" },
            if (s.includeBetaUpdates) "bêta" else null,
        ).joinToString(" · "),
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 720.dp) {
            SettingsMasterDetail(entries, summaries)
        } else {
            SettingsCompact(
                entries, summaries,
                cloudState = cloudState, cloudOk = cloud.signedIn && cloud.error == null, onCloud = onAccount,
                vaultState = vaultState, vaultOpen = pinSet && isUnlocked,
                onVault = if (pinSet) ::toggleVault else null,
            )
        }
    }
}

private fun subtitleLabel(lang: String) =
    if (lang.isBlank()) "Désactivés" else languages.firstOrNull { it.first == lang }?.second ?: lang

private fun downloadsLabel(list: List<DownloadEntity>): String {
    val done = list.count { it.status == DownloadStatus.COMPLETED }
    val pending = list.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PAUSED }
    return when {
        list.isEmpty() -> "Aucun contenu hors-ligne"
        pending > 0 -> "$done disponible(s) hors-ligne · $pending en cours"
        else -> "$done disponible(s) hors-ligne"
    }
}

// ------------------------------------------------------------------ Catégories des réglages

internal enum class SettingsCategory(val title: String, val icon: ImageVector) {
    SOURCES("Sources, Profils & EPG", Icons.Default.Dns),
    PLAYBACK("Lecture, PiP & Sous-titres", Icons.Default.PlayCircle),
    CHANNELS("Chaînes, Groupes & Métadonnées", Icons.Default.Tv),
    CLOUD("Cloud, Enregistrements & Hors-Ligne", Icons.Default.CloudSync),
    PRIVACY("Confidentialité & Coffre-fort", Icons.Default.Shield),
    ABOUT("Mises à jour & À propos", Icons.Default.SystemUpdate),
}

/** Un réglage rangé dans sa catégorie ; [search] sert au filtre de la recherche (vide = jamais affiché en résultat). */
private class SettingEntry(
    val category: SettingsCategory,
    val section: String,
    val search: String,
    val content: @Composable () -> Unit,
)

private class SettingEntriesBuilder {
    val entries = ArrayList<SettingEntry>()
    private var category = SettingsCategory.SOURCES
    private var section = ""

    fun category(c: SettingsCategory) { category = c; section = "" }
    fun section(title: String) { section = title }

    fun custom(search: String, content: @Composable () -> Unit) {
        entries += SettingEntry(category, section, if (search.isBlank()) "" else "$section $search", content)
    }

    fun row(
        title: String,
        value: String = "",
        icon: ImageVector? = null,
        trailing: @Composable (() -> Unit)? = null,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit,
    ) = custom("$title $value") { SettingRow(title, value, icon, trailing, onLongClick, onClick) }

    fun toggle(title: String, value: String = "", checked: Boolean, onChange: (Boolean) -> Unit) =
        custom("$title $value") { ToggleRow(title, value, checked, onChange) }

    fun note(text: String) = custom("") {
        Text(
            text, style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 4.dp),
        )
    }
}

private inline fun buildSettingEntries(block: SettingEntriesBuilder.() -> Unit): List<SettingEntry> =
    SettingEntriesBuilder().apply(block).entries

/** Minuscules et sans accents : « decodeur » trouve « Décodeur ». */
private fun normalizeSearch(text: String): String =
    java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

/** La recherche part du début d'un mot : « cle » trouve « Clé API » mais pas « déclenche ». */
internal fun matchesSettingSearch(text: String, query: String): Boolean {
    val q = normalizeSearch(query.trim())
    if (q.isEmpty() || text.isBlank()) return false
    return Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(q)).containsMatchIn(normalizeSearch(text))
}

@Composable
private fun EntryColumn(list: List<SettingEntry>) {
    Column {
        var last = ""
        list.forEach { e ->
            if (e.section.isNotBlank() && e.section != last) SectionTitle(e.section)
            last = e.section
            e.content()
        }
    }
}

private fun LazyListScope.entryItems(list: List<SettingEntry>) {
    var last = ""
    list.forEachIndexed { i, e ->
        if (e.section.isNotBlank() && e.section != last) {
            val title = e.section
            item(key = "s$i-$title") { SectionTitle(title) }
        }
        last = e.section
        item(key = "e$i") { e.content() }
    }
}

@Composable
private fun CategoryTile(
    cat: SettingsCategory,
    summary: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Tile(
        onClick = onClick, onFocus = onFocus,
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(12.dp), selected = selected, focusScale = 1.02f,
        focusedColor = MaterialTheme.colorScheme.primary,
    ) { focused ->
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                cat.icon, null, Modifier.size(26.dp),
                tint = when {
                    focused -> MaterialTheme.colorScheme.onPrimary
                    selected -> MaterialTheme.colorScheme.primary
                    else -> VyzoColors.TextDim
                },
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    cat.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
                if (summary.isNotBlank()) Text(
                    summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (focused) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else VyzoColors.TextDim,
                )
            }
            trailing?.invoke()
        }
    }
}

/** Écran large (TV, PC, tablette) : liste des catégories à gauche, réglages de la catégorie choisie à droite. */
@Composable
private fun SettingsMasterDetail(entries: List<SettingEntry>, summaries: Map<SettingsCategory, String>) {
    val tv = isTv()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val cat = SettingsCategory.entries[selected]
    val listFocus = remember { SettingsCategory.entries.map { FocusRequester() } }

    Row(Modifier.fillMaxSize().padding(start = if (tv) 24.dp else 12.dp)) {
        LazyColumn(
            Modifier.width(280.dp).fillMaxHeight(),
            contentPadding = PaddingValues(top = if (tv) 16.dp else 8.dp, bottom = 40.dp),
        ) {
            items(SettingsCategory.entries.size) { i ->
                val c = SettingsCategory.entries[i]
                // À la télécommande, la catégorie suit le focus : ▼▲ parcourt, ► entre dans les réglages.
                CategoryTile(
                    c, summaries[c].orEmpty(), selected = i == selected,
                    modifier = Modifier.focusRequester(listFocus[i]),
                    onFocus = { selected = i },
                ) { selected = i }
            }
        }
        Spacer(Modifier.width(if (tv) 20.dp else 12.dp))
        Box(Modifier.fillMaxHeight().width(1.dp).background(VyzoColors.Outline.copy(alpha = 0.6f)))
        key(cat) {
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight()
                    // ◄ depuis un réglage revient sur la catégorie affichée plutôt que sur sa voisine à l'écran.
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                            runCatching { listFocus[selected].requestFocus() }.isSuccess
                        } else false
                    },
                contentPadding = PaddingValues(start = if (tv) 28.dp else 16.dp, end = if (tv) 40.dp else 16.dp, top = if (tv) 16.dp else 8.dp, bottom = 40.dp),
            ) {
                item(key = "header") {
                    Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(cat.icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(cat.title, style = MaterialTheme.typography.titleLarge)
                            summaries[cat]?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim)
                            }
                        }
                    }
                }
                entryItems(entries.filter { it.category == cat })
            }
        }
    }
}

@Composable
private fun StatusTile(
    title: String,
    state: String,
    icon: ImageVector,
    ok: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Tile(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant, focusScale = 1f,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = if (ok) MaterialTheme.colorScheme.primary else VyzoColors.TextDim)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Text(state, style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Écran étroit (téléphone) : tuiles d'état, recherche, puis catégories en accordéon. */
@Composable
private fun SettingsCompact(
    entries: List<SettingEntry>,
    summaries: Map<SettingsCategory, String>,
    cloudState: String,
    cloudOk: Boolean,
    onCloud: () -> Unit,
    vaultState: String,
    vaultOpen: Boolean,
    onVault: (() -> Unit)?,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableIntStateOf(-1) }
    val searching = query.isNotBlank()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp),
    ) {
        item(key = "status") {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusTile("Cloud Sync", cloudState, if (cloudOk) Icons.Default.CloudSync else Icons.Default.CloudOff, cloudOk, Modifier.weight(1f), onCloud)
                StatusTile(
                    "Coffre-fort", vaultState, if (vaultOpen) Icons.Default.LockOpen else Icons.Default.Lock, vaultState != "Désactivé", Modifier.weight(1f),
                ) {
                    // Sans code parental, la tuile ouvre la catégorie où on peut l'activer.
                    if (onVault != null) onVault() else { query = ""; open = SettingsCategory.PRIVACY.ordinal }
                }
            }
        }
        item(key = "search") {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                placeholder = { Text("Rechercher un réglage") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Effacer la recherche") } }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
        }
        if (!searching) {
            SettingsCategory.entries.forEach { cat ->
                item(key = "cat-${cat.name}") {
                    val isOpen = open == cat.ordinal
                    val rotation by animateFloatAsState(if (isOpen) 180f else 0f, label = "chevron")
                    CategoryTile(
                        cat, summaries[cat].orEmpty(), selected = isOpen,
                        trailing = { Icon(Icons.Default.ExpandMore, if (isOpen) "Replier" else "Déplier", Modifier.rotate(rotation), tint = VyzoColors.TextDim) },
                    ) { open = if (isOpen) -1 else cat.ordinal }
                }
                item(key = "body-${cat.name}") {
                    AnimatedVisibility(
                        open == cat.ordinal,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Box(Modifier.padding(start = 8.dp, bottom = 8.dp)) { EntryColumn(entries.filter { it.category == cat }) }
                    }
                }
            }
        } else {
            val matches = entries.filter { matchesSettingSearch(it.search, query) }
            if (matches.isEmpty()) item(key = "empty") {
                Text(
                    "Aucun réglage ne correspond à « ${query.trim()} »",
                    style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim,
                    modifier = Modifier.padding(14.dp),
                )
            }
            SettingsCategory.entries.forEach { cat ->
                val list = matches.filter { it.category == cat }
                if (list.isNotEmpty()) {
                    item(key = "r-${cat.name}") { SectionTitle(cat.title) }
                    items(list.size, key = { "r-${cat.name}-$it" }) { list[it].content() }
                }
            }
        }
    }
}

private val languages = listOf(
    "fr" to "Français", "en" to "Anglais", "de" to "Allemand", "es" to "Espagnol", "it" to "Italien",
    "pt" to "Portugais", "ar" to "Arabe", "tr" to "Turc", "nl" to "Néerlandais", "" to "Langue d'origine",
)

private fun shiftLabel(min: Int): String = when {
    min == 0 -> "Aucun"
    else -> (if (min > 0) "+" else "−") + "${kotlin.math.abs(min) / 60} h" + if (kotlin.math.abs(min) % 60 != 0) " 30" else ""
}

private fun decoderLabel(m: String, desktop: Boolean = false) = when (m) {
    "SOFTWARE" -> if (desktop) "Logiciel" else "Logiciel (FFmpeg) prioritaire"
    "HARDWARE" -> "Matériel uniquement"
    else -> "Automatique"
}

private fun bufferLabel(m: String) = when (m) {
    "SHORT" -> "Courte"
    "LONG" -> "Longue"
    else -> "Normale"
}

private fun guideSizeLabel(v: String) = when (v) {
    "COMPACT" -> "Compacte"
    "LARGE" -> "Grande"
    else -> "Normale"
}

private fun uiModeLabel(m: String) = when (m) {
    "TV" -> "Téléviseur (télécommande)"
    "PHONE" -> "Téléphone / tablette (tactile)"
    else -> "Automatique"
}

// ------------------------------------------------------------------ Sources EPG

@Composable
fun EpgSourcesScreen(onCatalog: () -> Unit = {}) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val sources by c.repo.epgSources().collectAsState(emptyList())
    val sync by c.sync.state.collectAsState()
    var adding by remember { mutableIntStateOf(0) }
    var newName by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<fr.yannickuhrig.vyzo.data.db.EpgSourceEntity?>(null) }

    when (adding) {
        1 -> TextInputDialog("Nom du guide", label = "Nom", onConfirm = { newName = it; adding = 2 }, onDismiss = { if (adding == 1) adding = 0 })
        2 -> TextInputDialog(
            "Adresse XMLTV", label = "URL (.xml ou .xml.gz)", keyboardType = KeyboardType.Uri,
            onConfirm = { url -> if (url.isNotBlank()) scope.launch { c.repo.addEpgSource(newName, url); c.sync.launchEpg() } },
            onDismiss = { adding = 0 },
        )
    }
    menu?.let { src ->
        ActionDialog(
            title = src.name, subtitle = src.url.redactCredentials(),
            actions = buildList {
                add(ActionItem(if (src.enabled) "Désactiver" else "Activer", if (src.enabled) Icons.Default.VisibilityOff else Icons.Default.Visibility) {
                    scope.launch { c.repo.setEpgSourceEnabled(src, !src.enabled) }
                })
                val rank = sources.indexOfFirst { it.id == src.id }
                if (rank > 0) add(ActionItem("Monter dans la liste", Icons.Default.KeyboardArrowUp) { scope.launch { c.repo.moveEpgSource(src, -1) } })
                if (rank in 0 until sources.lastIndex) add(ActionItem("Descendre dans la liste", Icons.Default.KeyboardArrowDown) { scope.launch { c.repo.moveEpgSource(src, 1) } })
                val fillsGaps = src.mode == EpgMode.GAPS
                add(
                    ActionItem(
                        if (fillsGaps) "Ne servir que pour les chaînes sans guide" else "Compléter aussi les trous du guide",
                        Icons.Default.Refresh,
                    ) { scope.launch { c.repo.setEpgSourceMode(src, if (fillsGaps) EpgMode.FILL else EpgMode.GAPS) } },
                )
                if (!src.auto) add(ActionItem("Supprimer", Icons.Default.Delete, tint = VyzoColors.Red) { scope.launch { c.repo.deleteEpgSource(src) } })
            },
            onDismiss = { menu = null },
        )
    }

    ScreenColumn {
        item { SectionTitle("Sources du guide TV") }
        item {
            SettingRow("Mettre à jour le guide", if (sync.running) sync.message else "Télécharge toutes les sources actives", Icons.Default.Refresh) {
                if (!sync.running) c.sync.launchEpg()
            }
        }
        item { SettingRow("Ajouter une source XMLTV", icon = Icons.Default.Add) { newName = ""; adding = 1 } }
        item { SettingRow("Ajouter depuis le catalogue", "Guides epgshare01 par pays", Icons.Default.Add, onClick = onCatalog) }
        itemsIndexed(sources, key = { _, s -> s.id }) { index, src ->
            SettingRow(
                title = src.name + if (!src.enabled) " (désactivée)" else "",
                value = listOfNotNull(
                    "Priorité ${index + 1}",
                    if (src.mode == EpgMode.GAPS) "complète les trous" else "chaînes sans guide",
                    if (src.auto) "Fournie par la playlist" else "Ajoutée manuellement",
                    if (src.lastSync > 0) "${src.programCount} programmes · ${Fmt.dayTime(src.lastSync)}" else "jamais chargée",
                    src.lastError.takeIf { it.isNotBlank() }?.let { "erreur : $it" },
                ).joinToString(" · "),
                trailing = { Switch(checked = src.enabled, onCheckedChange = null) },
            ) { menu = src }
        }
        item {
            Text(
                "Les chaînes sont associées au guide par leur identifiant tvg-id, ou à défaut par leur nom. " +
                    "Les sources passent dans l'ordre de la liste : si l'une couvre déjà une chaîne, les suivantes sont ignorées pour celle-ci, " +
                    "sauf celles qui complètent les trous du guide.",
                style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, modifier = Modifier.padding(14.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ Groupes

@Composable
fun GroupsScreen(onEditChannels: (String) -> Unit = {}) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val tv = isTv()
    val groups by c.repo.userGroups().collectAsState(emptyList())
    var kind by remember { mutableStateOf(ContentKind.LIVE) }
    val liveRows by c.repo.liveCategoryRows().collectAsState(emptyList())
    val movieRows by remember { c.repo.vodCategoryRows(ContentKind.MOVIE) }.collectAsState(emptyList())
    val seriesRows by remember { c.repo.vodCategoryRows(ContentKind.SERIES) }.collectAsState(emptyList())
    val settings by c.settings.state.collectAsState()
    val unlocked by c.parental.unlocked.collectAsState()
    var catMenu by remember { mutableStateOf<CategoryRow?>(null) }
    var catRename by remember { mutableStateOf<CategoryRow?>(null) }
    var pinThen by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun parentalGate(action: () -> Unit) {
        if (settings.parentalPinHash.isBlank() || unlocked) action() else pinThen = action
    }
    pinThen?.let { action ->
        fr.yannickuhrig.vyzo.ui.profile.PinDialog(title = "Code parental", onDismiss = { pinThen = null }) { pin ->
            val ok = c.parental.unlock(pin)
            if (ok) action()
            ok
        }
    }
    catRename?.let { r ->
        TextInputDialog(
            "Renommer la catégorie", initial = r.name, label = "Nom (vide = nom d'origine : ${r.origName})",
            onConfirm = { n -> scope.launch { c.repo.renameCategory(r.uid, if (n.trim() == r.origName) "" else n) } },
            onDismiss = { catRename = null },
        )
    }
    catMenu?.let { r ->
        ActionDialog(
            title = r.name,
            subtitle = "${r.itemCount} élément(s)" + if (r.name != r.origName) " · nom d'origine : ${r.origName}" else "",
            actions = buildList {
                if (r.kind == ContentKind.LIVE) add(ActionItem("Personnaliser les chaînes…", Icons.Default.Edit, detail = "Nom, numéro, guide, masquer, verrouiller") { onEditChannels(r.uid) })
                add(ActionItem("Renommer", Icons.Default.Edit) { catRename = r })
                add(ActionItem(if (r.hidden) "Afficher" else "Masquer", if (r.hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff) {
                    scope.launch { c.repo.setCategoryHidden(r.uid, !r.hidden) }
                })
                add(
                    ActionItem(
                        if (r.locked) "Déverrouiller" else "Verrouiller (contrôle parental)", if (r.locked) Icons.Default.LockOpen else Icons.Default.Lock,
                        detail = if (r.adult && r.locked) "Détectée comme catégorie pour adultes" else "",
                    ) { parentalGate { scope.launch { c.repo.setCategoryLocked(r.uid, !r.locked) } } },
                )
                if (r.name != r.origName) add(ActionItem("Rétablir le nom d'origine", Icons.Default.Restore) { scope.launch { c.repo.renameCategory(r.uid, "") } })
            },
            onDismiss = { catMenu = null },
        )
    }
    val rows = when (kind) { ContentKind.LIVE -> liveRows; ContentKind.MOVIE -> movieRows; ContentKind.SERIES -> seriesRows }
    var newGroup by remember { mutableStateOf(false) }
    var editGroup by remember { mutableStateOf<UserGroupEntity?>(null) }
    var renameGroup by remember { mutableStateOf<UserGroupEntity?>(null) }
    var deleteGroup by remember { mutableStateOf<UserGroupEntity?>(null) }

    if (newGroup) TextInputDialog("Nouveau groupe", label = "Nom", onConfirm = { n -> if (n.isNotBlank()) scope.launch { c.repo.createGroup(n) } }, onDismiss = { newGroup = false })
    renameGroup?.let { g -> TextInputDialog("Renommer", initial = g.name, onConfirm = { n -> if (n.isNotBlank()) scope.launch { c.repo.renameGroup(g.id, n) } }, onDismiss = { renameGroup = null }) }
    deleteGroup?.let { g ->
        ConfirmDialog("Supprimer « ${g.name} » ?", "Les chaînes ne sont pas supprimées, seulement le groupe.", "Supprimer",
            onConfirm = { scope.launch { c.repo.deleteGroup(g.id) } }, onDismiss = { deleteGroup = null })
    }
    editGroup?.let { g ->
        ActionDialog(
            title = g.name,
            actions = buildList {
                add(ActionItem("Renommer", Icons.Default.Edit) { renameGroup = g })
                if (g.id != FAVORITES_GROUP_ID) add(ActionItem("Supprimer", Icons.Default.Delete, tint = VyzoColors.Red) { deleteGroup = g })
            },
            onDismiss = { editGroup = null },
        )
    }

    fun move(list: List<CategoryRow>, index: Int, delta: Int) {
        val target = index + delta
        if (target !in list.indices) return
        val m = list.toMutableList()
        val item = m.removeAt(index)
        m.add(target, item)
        scope.launch { c.repo.reorderCategories(m) }
    }

    ScreenColumn {
        item { SectionTitle("Mes groupes") }
        item {
            Text(
                "Les favoris et les groupes sont propres à chaque profil.",
                style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
            )
        }
        itemsIndexed(groups, key = { _, g -> "g${g.id}" }) { _, g ->
            if (g.id == FAVORITES_GROUP_ID) {
                SettingRow(g.name, "Groupe des favoris", Icons.Default.Folder) { editGroup = g }
            } else {
                val nonFav = groups.filter { it.id != FAVORITES_GROUP_ID }
                val idx = nonFav.indexOfFirst { it.id == g.id }
                Row(Modifier.fillMaxWidth().widthIn(max = 900.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tile(
                        onClick = { editGroup = g },
                        modifier = Modifier.weight(1f).padding(vertical = 2.dp), focusScale = 1.01f,
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(14.dp))
                            Text(g.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Groupe personnalisé", style = MaterialTheme.typography.labelMedium, color = VyzoColors.TextDim)
                        }
                    }
                    Tile(onClick = { if (idx > 0) scope.launch { c.repo.moveUserGroup(g.id, -1) } }, modifier = Modifier.padding(start = 6.dp)) {
                        Icon(Icons.Default.ArrowUpward, "Monter", Modifier.padding(10.dp), tint = if (idx > 0) Color.Unspecified else VyzoColors.TextDim)
                    }
                    Tile(onClick = { if (idx < nonFav.lastIndex) scope.launch { c.repo.moveUserGroup(g.id, 1) } }, modifier = Modifier.padding(start = 4.dp)) {
                        Icon(Icons.Default.ArrowDownward, "Descendre", Modifier.padding(10.dp), tint = if (idx < nonFav.lastIndex) Color.Unspecified else VyzoColors.TextDim)
                    }
                }
            }
        }
        item { SettingRow("Créer un groupe", "Puis ajoute des chaînes par appui long (ou touche Menu) sur une chaîne", Icons.Default.Add) { newGroup = true } }

        item { SectionTitle("Catégories de la playlist") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                listOf(ContentKind.LIVE to "TV en direct", ContentKind.MOVIE to "Films", ContentKind.SERIES to "Séries").forEach { (k, label) ->
                    Tile(onClick = { kind = k }, selected = kind == k, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (kind == k) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                    }
                }
            }
        }
        itemsIndexed(rows, key = { _, r -> r.uid }) { i, r ->
            Row(Modifier.fillMaxWidth().widthIn(max = 900.dp), verticalAlignment = Alignment.CenterVertically) {
                Tile(
                    onClick = { catMenu = r },
                    onLongClick = { scope.launch { c.repo.setCategoryHidden(r.uid, !r.hidden) } },
                    modifier = Modifier.weight(1f).padding(vertical = 2.dp), focusScale = 1.01f,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (r.hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = if (r.hidden) VyzoColors.TextDim else MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Text(r.name, style = MaterialTheme.typography.bodyLarge, color = if (r.hidden) VyzoColors.TextDim else Color.Unspecified, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (r.locked) {
                            Icon(Icons.Default.Lock, "Verrouillée", Modifier.size(18.dp), tint = VyzoColors.Amber)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("${r.itemCount}", style = MaterialTheme.typography.labelMedium, color = VyzoColors.TextDim)
                    }
                }
                Tile(onClick = { move(rows, i, -1) }, modifier = Modifier.padding(start = 6.dp)) {
                    Icon(Icons.Default.ArrowUpward, "Monter", Modifier.padding(10.dp), tint = if (i > 0) Color.Unspecified else VyzoColors.TextDim)
                }
                Tile(onClick = { move(rows, i, 1) }, modifier = Modifier.padding(start = 4.dp)) {
                    Icon(Icons.Default.ArrowDownward, "Descendre", Modifier.padding(10.dp), tint = if (i < rows.lastIndex) Color.Unspecified else VyzoColors.TextDim)
                }
            }
        }
        if (rows.isEmpty()) item {
            Text("Aucune catégorie pour la playlist active.", color = VyzoColors.TextDim, modifier = Modifier.padding(14.dp))
        }
        item {
            Text(
                if (tv) "OK sur une catégorie : renommer, masquer, verrouiller, personnaliser ses chaînes. Appui long : masquer ou afficher. Flèches à droite : déplacer."
                else "Touche une catégorie pour la renommer, la masquer, la verrouiller ou personnaliser ses chaînes ; appui long pour la masquer.",
                style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, modifier = Modifier.padding(14.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ Ordre des groupes et catégories

@Composable
fun GroupOrderScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(ContentKind.LIVE) }
    val liveRows by c.repo.liveCategoryRows().collectAsState(emptyList())
    val movieRows by remember { c.repo.vodCategoryRows(ContentKind.MOVIE) }.collectAsState(emptyList())
    val seriesRows by remember { c.repo.vodCategoryRows(ContentKind.SERIES) }.collectAsState(emptyList())
    val userGroups by c.repo.userGroups().collectAsState(emptyList())
    val customGroups = remember(userGroups) { userGroups.filter { it.id != FAVORITES_GROUP_ID } }

    val rows = when (kind) {
        ContentKind.LIVE -> liveRows
        ContentKind.MOVIE -> movieRows
        ContentKind.SERIES -> seriesRows
    }

    ScreenColumn {
        item { SectionTitle("Ordre d'affichage des groupes") }
        item {
            Text(
                "Organise l'ordre d'apparition des catégories dans le menu latéral et les filtres. Les modifications sont appliquées et enregistrées immédiatement.",
                style = MaterialTheme.typography.bodySmall,
                color = VyzoColors.TextDim,
                modifier = Modifier.padding(start = 14.dp, bottom = 8.dp),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                listOf(ContentKind.LIVE to "TV en direct", ContentKind.MOVIE to "Films", ContentKind.SERIES to "Séries").forEach { (k, label) ->
                    Tile(
                        onClick = { kind = k },
                        selected = kind == k,
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (kind == k) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }

        if (kind == ContentKind.LIVE && customGroups.isNotEmpty()) {
            item { SectionTitle("Mes groupes personnalisés") }
            itemsIndexed(customGroups, key = { _, g -> "ug_${g.id}" }) { i, g ->
                Row(
                    Modifier.fillMaxWidth().widthIn(max = 900.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tile(
                        onClick = {},
                        modifier = Modifier.weight(1f).padding(vertical = 2.dp),
                        focusScale = 1.01f,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(14.dp))
                            Text(
                                g.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Tile(
                        onClick = { if (i > 0) scope.launch { c.repo.moveUserGroup(g.id, -1) } },
                        modifier = Modifier.padding(start = 6.dp),
                    ) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            "Monter",
                            Modifier.padding(10.dp),
                            tint = if (i > 0) Color.Unspecified else VyzoColors.TextDim.copy(alpha = 0.3f),
                        )
                    }
                    Tile(
                        onClick = { if (i < customGroups.lastIndex) scope.launch { c.repo.moveUserGroup(g.id, 1) } },
                        modifier = Modifier.padding(start = 4.dp),
                    ) {
                        Icon(
                            Icons.Default.ArrowDownward,
                            "Descendre",
                            Modifier.padding(10.dp),
                            tint = if (i < customGroups.lastIndex) Color.Unspecified else VyzoColors.TextDim.copy(alpha = 0.3f),
                        )
                    }
                }
            }
            item { Spacer(Modifier.size(8.dp)) }
        }

        item {
            SectionTitle(
                when (kind) {
                    ContentKind.LIVE -> "Catégories Live TV"
                    ContentKind.MOVIE -> "Catégories Films"
                    ContentKind.SERIES -> "Catégories Séries"
                }
            )
        }

        if (rows.isEmpty()) {
            item {
                Text("Aucune catégorie trouvée pour cette section.", color = VyzoColors.TextDim, modifier = Modifier.padding(14.dp))
            }
        } else {
            itemsIndexed(rows, key = { _, r -> r.uid }) { i, r ->
                Row(
                    Modifier.fillMaxWidth().widthIn(max = 900.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tile(
                        onClick = {},
                        modifier = Modifier.weight(1f).padding(vertical = 2.dp),
                        focusScale = 1.01f,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (r.hidden) Icons.Default.VisibilityOff else Icons.Default.Folder,
                                null,
                                tint = if (r.hidden) VyzoColors.TextDim else MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(
                                r.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (r.hidden) VyzoColors.TextDim else Color.Unspecified,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (r.locked) {
                                Icon(Icons.Default.Lock, "Verrouillée", Modifier.size(18.dp), tint = VyzoColors.Amber)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("${r.itemCount}", style = MaterialTheme.typography.labelMedium, color = VyzoColors.TextDim)
                        }
                    }
                    Tile(
                        onClick = { if (i > 0) scope.launch { c.repo.moveCategory(kind, r.uid, -1) } },
                        modifier = Modifier.padding(start = 6.dp),
                    ) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            "Monter",
                            Modifier.padding(10.dp),
                            tint = if (i > 0) Color.Unspecified else VyzoColors.TextDim.copy(alpha = 0.3f),
                        )
                    }
                    Tile(
                        onClick = { if (i < rows.lastIndex) scope.launch { c.repo.moveCategory(kind, r.uid, 1) } },
                        modifier = Modifier.padding(start = 4.dp),
                    ) {
                        Icon(
                            Icons.Default.ArrowDownward,
                            "Descendre",
                            Modifier.padding(10.dp),
                            tint = if (i < rows.lastIndex) Color.Unspecified else VyzoColors.TextDim.copy(alpha = 0.3f),
                        )
                    }
                }
            }
        }
    }
}
