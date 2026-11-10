package fr.yannickuhrig.vyzo.ui.common

import java.awt.image.BufferedImage
import java.awt.Toolkit
import java.awt.Point
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Liens entre le code commun et la fenêtre de Vyzo PC (module :desktop) :
 * touche Échap, répétition des touches, sélecteurs de fichiers et maintien de l'écran allumé.
 */
object DesktopUi {
    // ------------------------------------------------------------ Retour (Échap, bouton « précédent » de la souris)

    private class BackEntry(val order: Long, var enabled: Boolean, var onBack: () -> Unit)

    private val backEntries = ArrayList<BackEntry>()
    private var nextOrder = 0L

    /** Appelé par la fenêtre sur Échap. Renvoie true si un écran a traité le retour. */
    fun dispatchBack(): Boolean {
        val e = synchronized(backEntries) { backEntries.filter { it.enabled }.maxByOrNull { it.order } } ?: return false
        e.onBack()
        return true
    }

    @Composable
    internal fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
        val current by rememberUpdatedState(onBack)
        val entry = remember { BackEntry(synchronized(backEntries) { nextOrder++ }, enabled) { current() } }
        SideEffect { entry.enabled = enabled }
        DisposableEffect(entry) {
            synchronized(backEntries) { backEntries += entry }
            onDispose { synchronized(backEntries) { backEntries -= entry } }
        }
    }

    // ------------------------------------------------------------ Répétition des touches (AWT ne la fournit pas)

    private val pressed = HashMap<Key, Int>()

    /** À appeler pour chaque événement clavier de la fenêtre, avant les écrans (onPreviewKeyEvent). */
    fun trackKey(e: KeyEvent) {
        when (e.type) {
            KeyEventType.KeyDown -> pressed[e.key] = pressed[e.key]?.plus(1) ?: 0
            KeyEventType.KeyUp -> pressed.remove(e.key)
        }
    }

    internal fun repeatCountOf(e: KeyEvent): Int = pressed[e.key] ?: 0

    // ------------------------------------------------------------ Fichiers

    /** Sélecteur « Ouvrir » ; fourni par la fenêtre (GTK via zenity sous Linux, boîte native sous Windows). */
    @Volatile
    var openFileChooser: (title: String, extensions: List<String>) -> File? = { _, _ -> null }

    /** Sélecteur de dossier. */
    @Volatile
    var folderChooser: (title: String) -> File? = { null }

    /** Sélecteur « Enregistrer sous ». */
    @Volatile
    var saveFileChooser: (title: String, suggestedName: String) -> File? = { _, _ -> null }

    // ------------------------------------------------------------ Lecture en cours

    /** Prévenu quand un écran de lecture s'ouvre (true) ou se ferme (false) : écran allumé, pas de veille. */
    @Volatile
    var onPlayerScreen: (Boolean) -> Unit = {}
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = DesktopUi.BackHandler(enabled, onBack)

private fun extensionsFor(mimeTypes: List<String>): List<String> = when {
    mimeTypes.any { it == "*/*" } -> emptyList()
    mimeTypes.any { it.contains("json") } -> listOf("json")
    else -> emptyList()
}

@Composable
actual fun rememberOpenFile(mimeTypes: List<String>, keepAccess: Boolean, onResult: (String?) -> Unit): FileLauncher {
    val current by rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    return remember(mimeTypes) {
        FileLauncher {
            scope.launch {
                val f = withContext(Dispatchers.IO) { DesktopUi.openFileChooser("Choisir un fichier", extensionsFor(mimeTypes)) }
                current(f?.toPath()?.toUri()?.toString())
            }
            true
        }
    }
}

@Composable
actual fun rememberPickFolder(onResult: (uri: String?, name: String) -> Unit): FileLauncher {
    val current by rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    return remember {
        FileLauncher {
            scope.launch {
                val dir = withContext(Dispatchers.IO) { DesktopUi.folderChooser("Dossier des enregistrements") }
                current(dir?.absolutePath, dir?.name.orEmpty())
            }
            true
        }
    }
}

@Composable
actual fun rememberSaveFile(mimeType: String, onResult: (String?) -> Unit): SaveFileLauncher {
    val current by rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    return remember(mimeType) {
        SaveFileLauncher { name ->
            scope.launch {
                val f = withContext(Dispatchers.IO) { DesktopUi.saveFileChooser("Enregistrer la sauvegarde", name) }
                current(f?.toPath()?.toUri()?.toString())
            }
            true
        }
    }
}

/** Pas de reconnaissance vocale sur PC : on tape au clavier. */
@Composable
actual fun rememberVoiceSearch(onResult: (String) -> Unit): (() -> Unit)? = null

@Composable
actual fun PlayerWindowEffect(tv: Boolean, orientation: String) {
    DisposableEffect(Unit) {
        DesktopUi.onPlayerScreen(true)
        onDispose { DesktopUi.onPlayerScreen(false) }
    }
}

actual val KeyEvent.repeatCount: Int get() = DesktopUi.repeatCountOf(this)

/** Curseur transparent de 16×16 pixels. */
private val blankCursor: PointerIcon by lazy {
    val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(img, Point(0, 0), "vyzo-cache"))
}

actual fun Modifier.hideCursor(hidden: Boolean): Modifier = if (hidden) pointerHoverIcon(blankCursor) else this

@Composable
actual fun rememberNotificationPermission(onResult: (granted: Boolean) -> Unit): PermissionRequest =
    remember { PermissionRequest(isNeeded = { false }, launch = { onResult(true) }) }

@Composable
actual fun rememberStorageAccess(onResult: () -> Unit): StorageAccess? = null

@Composable
actual fun rememberOverlaySetting(): OverlaySetting? = null
