package fr.yannickuhrig.vyzo.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent

/*
 * Briques d'interface qui dépendent du système : implémentées dans androidMain (Android) et desktopMain (Vyzo PC).
 */

/** Touche Retour d'Android ; touche Échap sur PC. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)

/**
 * Sélecteur de fichier à ouvrir. Renvoie la fonction qui l'affiche ; [onResult] reçoit l'adresse du fichier
 * (content:// sur Android, file:// sur PC) ou null si l'utilisateur annule.
 * [keepAccess] conserve l'autorisation de lecture après redémarrage (playlist locale relue à chaque mise à jour).
 */
@Composable
expect fun rememberOpenFile(mimeTypes: List<String>, keepAccess: Boolean = false, onResult: (String?) -> Unit): FileLauncher

/** Sélecteur d'emplacement pour enregistrer un fichier ; la fonction renvoyée prend le nom proposé. */
@Composable
expect fun rememberSaveFile(mimeType: String, onResult: (String?) -> Unit): SaveFileLauncher

/**
 * Sélecteur de dossier (dossier des enregistrements). [onResult] reçoit l'adresse du dossier
 * (arborescence content:// sur Android, chemin sur PC) et son nom lisible, ou null si l'utilisateur annule.
 */
@Composable
expect fun rememberPickFolder(onResult: (uri: String?, name: String) -> Unit): FileLauncher

fun interface FileLauncher {
    /** Renvoie false si aucun sélecteur n'est disponible sur l'appareil. */
    fun launch(): Boolean
}

fun interface SaveFileLauncher {
    fun launch(suggestedName: String): Boolean
}

/** Recherche vocale : null si indisponible. */
@Composable
expect fun rememberVoiceSearch(onResult: (String) -> Unit): (() -> Unit)?

/** Pendant la lecture : écran allumé, barres système masquées, paysage sur téléphone. */
@Composable
expect fun PlayerWindowEffect(tv: Boolean)

/** Masque le pointeur de la souris (lecteur plein écran sur PC) ; sans effet sur Android. */
expect fun Modifier.hideCursor(hidden: Boolean): Modifier

/** Nombre de répétitions d'une touche maintenue (0 au premier appui). */
expect val KeyEvent.repeatCount: Int

/** Autorisation des notifications (Android 13 et plus). */
class PermissionRequest(val isNeeded: () -> Boolean, val launch: () -> Unit)

@Composable
expect fun rememberNotificationPermission(onResult: (granted: Boolean) -> Unit): PermissionRequest

/**
 * Accès aux fichiers partagés, pour relire la sauvegarde d'une installation précédente sur un appareil sans sélecteur de
 * fichier. [neededToWrite] : l'écriture aussi le demande (Android 10 et moins). [request] renvoie false si l'appareil
 * ne propose pas cette autorisation.
 */
class StorageAccess(val isGranted: () -> Boolean, val neededToWrite: Boolean, val request: () -> Boolean)

/** Null quand il n'a pas de sens (PC). [onResult] est appelé au retour de la demande. */
@Composable
expect fun rememberStorageAccess(onResult: () -> Unit): StorageAccess?

/** Réglage « Afficher par-dessus les autres applis » ; null quand il n'a pas de sens (PC). */
class OverlaySetting(val granted: Boolean, val open: () -> Unit)

@Composable
expect fun rememberOverlaySetting(): OverlaySetting?
