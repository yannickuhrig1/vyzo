package fr.yannickuhrig.vyzo.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Restore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.backup.BackupManager
import fr.yannickuhrig.vyzo.data.backup.FoundBackup
import fr.yannickuhrig.vyzo.data.backup.SavedBackup
import fr.yannickuhrig.vyzo.ui.common.ActionDialog
import fr.yannickuhrig.vyzo.ui.common.ActionItem
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.StorageAccess
import fr.yannickuhrig.vyzo.ui.common.rememberOpenFile
import fr.yannickuhrig.vyzo.ui.common.rememberSaveFile
import fr.yannickuhrig.vyzo.ui.common.rememberStorageAccess
import fr.yannickuhrig.vyzo.util.plural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Portée de l'appli : quitter l'écran pendant la restauration ne doit pas la couper en deux. */
private fun restore(c: AppContainer, read: suspend () -> String, onRestored: (Int) -> Unit) {
    c.scope.launch {
        runCatching { BackupManager.import(c, read()) }
            .onSuccess { n ->
                c.platform.toast("Sauvegarde restaurée (${plural(n, "playlist")}), chargement…", long = true)
                c.sync.launchAll()
                onRestored(n)
            }
            .onFailure { c.platform.toast("Fichier de sauvegarde invalide", long = true) }
    }
}

/** Exporter : sélecteur de fichier s'il existe, sinon Téléchargements et clés USB. */
@Composable
fun rememberExportBackup(): () -> Unit {
    val c = LocalContainer.current
    var saved by remember { mutableStateOf<SavedBackup?>(null) }
    fun saveOnDevice() {
        c.scope.launch {
            val text = BackupManager.export(c)
            saved = withContext(Dispatchers.IO) { runCatching { BackupManager.saveOnDevice(c.platform, text) }.getOrNull() }
            if (saved == null) c.platform.toast("Échec de la sauvegarde")
        }
    }
    val picker = rememberSaveFile("application/json") { uri ->
        if (uri != null) c.scope.launch {
            val ok = runCatching {
                val text = BackupManager.export(c)
                withContext(Dispatchers.IO) { c.platform.openOutput(uri)?.use { it.write(text.toByteArray()) } ?: error("Fichier inaccessible") }
            }.isSuccess
            c.platform.toast(if (ok) "Sauvegarde enregistrée" else "Échec de la sauvegarde")
        }
    }
    val access = rememberStorageAccess { saveOnDevice() }
    saved?.let { SavedBackupDialog(it, onDismiss = { saved = null }) }
    return {
        if (!picker.launch("vyzo-sauvegarde.json")) {
            if (access?.neededToWrite == true && !access.isGranted()) access.request() else saveOnDevice()
        }
    }
}

@Composable
private fun SavedBackupDialog(saved: SavedBackup, onDismiss: () -> Unit) {
    ActionDialog(
        title = "Sauvegarde enregistrée",
        body = saved.places.joinToString("\n") + "\n\n" +
            if (saved.survives) "Elle reste sur l'appareil si tu désinstalles Vyzo. Elle contient tes identifiants : garde cette clé ou ce fichier pour toi."
            else "Attention : ce dossier est effacé si tu désinstalles Vyzo.",
        actions = listOf(ActionItem("OK") {}),
        onDismiss = onDismiss,
    )
}

/**
 * Restaurer : sélecteur de fichier s'il existe, sinon recherche sur l'appareil. [onPhone] propose d'envoyer le fichier
 * depuis le téléphone quand rien n'est trouvé.
 */
@Composable
fun rememberRestoreBackup(onPhone: (() -> Unit)? = null, onRestored: (Int) -> Unit = {}): () -> Unit {
    val c = LocalContainer.current
    val restored by rememberUpdatedState(onRestored)
    var searching by remember { mutableStateOf(false) }
    var search by remember { mutableIntStateOf(0) }
    val picker = rememberOpenFile(listOf("application/json", "text/*", "*/*")) { uri ->
        if (uri != null) restore(c, { withContext(Dispatchers.IO) { c.platform.openInput(uri)?.use { it.readBytes().decodeToString() } } ?: error("Fichier vide") }, { restored(it) })
    }
    val access = rememberStorageAccess {
        search++
        searching = true
    }
    if (searching) {
        DeviceBackupsDialog(
            access = access, search = search, onPhone = onPhone,
            onRestore = { found -> restore(c, { found.text }, { restored(it) }) },
            onDismiss = { searching = false },
        )
    }
    return {
        if (!picker.launch()) {
            search++
            searching = true
        }
    }
}

/** Ce que l'appareil garde comme sauvegardes, et comment en retrouver une quand Android les cache. */
@Composable
fun DeviceBackupsDialog(
    access: StorageAccess?,
    search: Int,
    onPhone: (() -> Unit)?,
    onRestore: (FoundBackup) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalContainer.current
    var found by remember { mutableStateOf<List<FoundBackup>?>(null) }
    var noAccessScreen by remember { mutableStateOf(false) }
    var stayOpen by remember { mutableStateOf(false) }
    // ActionDialog se ferme après chaque action : on garde la main quand l'autorisation n'existe pas sur cet appareil.
    val close = {
        if (!stayOpen) onDismiss()
        stayOpen = false
    }
    LaunchedEffect(search) { found = withContext(Dispatchers.IO) { BackupManager.findOnDevice(c.platform) } }
    val list = found ?: return
    val phone = onPhone?.let { ActionItem("Envoyer le fichier depuis le téléphone", Icons.Default.QrCode2, detail = "Affiche un QR code à scanner", onClick = it) }
    val askAccess = access?.takeIf { !it.isGranted() && !noAccessScreen }?.let {
        ActionItem("Autoriser l'accès aux fichiers", Icons.Default.FolderOpen, detail = "Ouvre le réglage d'Android, puis reviens ici") {
            if (!it.request()) {
                noAccessScreen = true
                stayOpen = true
            }
        }
    }
    when {
        list.isNotEmpty() -> ActionDialog(
            title = "Restaurer une sauvegarde",
            body = "La configuration actuelle de cet appareil sera remplacée.",
            actions = list.map { b ->
                ActionItem(b.file.location, Icons.Default.Restore, detail = "${Fmt.dayTime(b.file.modifiedAt)} · ${plural(b.playlists, "playlist")}") { onRestore(b) }
            } + listOfNotNull(askAccess?.copy(label = "Chercher une ancienne sauvegarde"), phone),
            onDismiss = close,
        )
        askAccess != null -> ActionDialog(
            title = "Retrouver ta sauvegarde",
            body = "Android cache à Vyzo les fichiers d'une installation précédente, même dans Téléchargements. " +
                "Dans le réglage qui s'ouvre, choisis Vyzo dans la liste pour l'autoriser, puis reviens : la recherche reprend toute seule. " +
                "Vyzo ne lit que ses fichiers de sauvegarde, et tu pourras retirer cet accès ensuite.",
            actions = listOfNotNull(askAccess, phone, ActionItem("Annuler") {}),
            onDismiss = close,
        )
        else -> ActionDialog(
            title = "Aucune sauvegarde trouvée",
            body = if (noAccessScreen) "Cet appareil ne propose pas l'accès aux fichiers. Envoie le fichier depuis ton téléphone."
            else "Vyzo a cherché dans Téléchargements et sur les clés USB branchées. Le fichier doit s'appeler vyzo-sauvegarde.json.",
            actions = listOfNotNull(phone, ActionItem("Fermer") {}),
            onDismiss = close,
        )
    }
}
