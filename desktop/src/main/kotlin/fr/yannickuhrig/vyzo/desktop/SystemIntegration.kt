package fr.yannickuhrig.vyzo.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import com.sun.jna.platform.win32.WinReg
import fr.yannickuhrig.vyzo.util.Log
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.concurrent.thread

object Os {
    private val name = System.getProperty("os.name").lowercase(Locale.ROOT)
    val isWindows = name.contains("win")
    val isLinux = name.contains("linux")
    val isMac = name.contains("mac") || name.contains("darwin")

    /** Lance une commande et renvoie sa sortie (null si elle n'existe pas ou échoue). */
    fun run(vararg cmd: String, timeoutSec: Long = 5): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroy()
            return null
        }
        if (p.exitValue() == 0) out.trim() else null
    }.getOrNull()

    fun hasCommand(cmd: String): Boolean =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, cmd).canExecute() }

    /** « Linux Mint 22.3 », « Windows 11 », « macOS 14.5 »… */
    val description: String by lazy {
        val linux = if (isLinux) runCatching {
            File("/etc/os-release").readLines().firstOrNull { it.startsWith("PRETTY_NAME=") }?.substringAfter('=')?.trim('"')
        }.getOrNull() else null
        (linux ?: "${System.getProperty("os.name")} ${System.getProperty("os.version")}") + " · Java ${System.getProperty("java.version")}"
    }

    /** Modèle de l'ordinateur quand le système le donne (Linux : DMI, macOS : Mac), sinon « PC ». */
    val model: String by lazy {
        if (isMac) {
            "Mac"
        } else {
            val dmi = if (isLinux) runCatching {
                listOf("sys_vendor", "product_name").map { File("/sys/class/dmi/id/$it").readText().trim() }.filter { it.isNotBlank() }.joinToString(" ")
            }.getOrNull() else null
            dmi?.takeIf { it.isNotBlank() } ?: "PC"
        }
    }
}

/** Dossiers de Vyzo PC. */
object AppDirs {
    private val home = File(System.getProperty("user.home"))

    val data: File by lazy {
        val dir = when {
            Os.isWindows -> File(System.getenv("APPDATA") ?: home.resolve("AppData/Roaming").path, "Vyzo")
            Os.isMac -> home.resolve("Library/Application Support/Vyzo")
            else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: home.resolve(".local/share").path, "vyzo")
        }
        dir.apply { mkdirs() }
    }

    val cache: File by lazy {
        val dir = when {
            Os.isWindows -> File(System.getenv("LOCALAPPDATA") ?: data.path, "Vyzo/cache")
            Os.isMac -> home.resolve("Library/Caches/Vyzo")
            else -> File(System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: home.resolve(".cache").path, "vyzo")
        }
        dir.apply { mkdirs() }
    }

    val logs: File get() = data.resolve("logs").apply { mkdirs() }

    /** Dossier « Vidéos » de l'utilisateur (Vidéos sous Mint en français, Videos sous Windows, Movies sous macOS). */
    private val videos: File by lazy {
        val dir = when {
            Os.isWindows -> runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Videos)) }.getOrNull()
            Os.isMac -> home.resolve("Movies")
            else -> Os.run("xdg-user-dir", "VIDEOS")?.takeIf { it.isNotBlank() && File(it) != home }?.let(::File)
        }
        dir ?: listOf("Vidéos", "Videos", "Movies").map { home.resolve(it) }.firstOrNull { it.isDirectory } ?: home.resolve("Videos")
    }

    val recordings: File get() = videos.resolve("Vyzo").apply { mkdirs() }
}

/** Notifications du bureau. Linux : notify-send (avec boutons) ; Windows : bulle de la zone de notification. */
object Notifier {
    /** Bulle de l'icône de notification (fournie par Main.kt) ; false si pas d'icône. */
    @Volatile
    var trayNotify: (title: String, body: String) -> Boolean = { _, _ -> false }

    /** [onWatch] est appelé si l'utilisateur clique « Regarder » ou sur la notification. */
    fun notify(title: String, body: String, onWatch: (() -> Unit)? = null) {
        if (Os.isLinux && Os.hasCommand("notify-send")) {
            thread(isDaemon = true, name = "notify") {
                val icon = Icons.pngFile()?.absolutePath ?: "video-display"
                val cmd = mutableListOf("notify-send", "-a", "Vyzo", "-i", icon)
                if (onWatch != null) cmd += listOf("-A", "default=Ouvrir", "-A", "watch=Regarder", "--wait")
                cmd += listOf(title, body)
                val out = Os.run(*cmd.toTypedArray(), timeoutSec = 600)
                if (onWatch != null && (out == "watch" || out == "default")) onWatch()
            }
            return
        }
        trayNotify(title, body)
    }
}

/**
 * Empêche la mise en veille pendant un enregistrement (et l'écran de s'éteindre pendant la lecture).
 * Windows : SetThreadExecutionState ; Linux : systemd-inhibit pour la veille, xdg-screensaver pour l'écran.
 */
object KeepAwake {
    private val reasons = HashSet<String>()
    private var watchingVideo = false
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "keep-awake").apply { isDaemon = true } }
    private var inhibitor: Process? = null

    init {
        // Écran allumé pendant la lecture : Linux réinitialise l'économiseur régulièrement.
        worker.scheduleWithFixedDelay({
            if (watchingVideo && Os.isLinux) {
                if (Os.hasCommand("xdg-screensaver")) Os.run("xdg-screensaver", "reset") else Os.run("xset", "s", "reset")
            }
        }, 30, 45, TimeUnit.SECONDS)
    }

    fun acquire(reason: String) = worker.execute { if (reasons.add(reason)) apply() }

    fun release(reason: String) = worker.execute { if (reasons.remove(reason)) apply() }

    fun setWatching(watching: Boolean) = worker.execute {
        watchingVideo = watching
        apply()
    }

    private fun apply() {
        val sleepBlocked = reasons.isNotEmpty() || watchingVideo
        if (Os.isWindows) {
            val ES_CONTINUOUS = 0x80000000.toInt()
            val ES_SYSTEM_REQUIRED = 0x00000001
            val ES_DISPLAY_REQUIRED = 0x00000002
            var flags = ES_CONTINUOUS
            if (sleepBlocked) flags = flags or ES_SYSTEM_REQUIRED
            if (watchingVideo) flags = flags or ES_DISPLAY_REQUIRED
            runCatching { Kernel32.INSTANCE.SetThreadExecutionState(flags) }
            return
        }
        if (sleepBlocked && inhibitor == null && Os.hasCommand("systemd-inhibit")) {
            inhibitor = runCatching {
                ProcessBuilder(
                    "systemd-inhibit", "--what=sleep:idle", "--who=Vyzo", "--why=Lecture ou enregistrement en cours", "--mode=block",
                    // Attend la fin de Vyzo plutôt qu'un « sleep infinity » : si Vyzo plante, l'inhibiteur s'arrête avec lui
                    // au lieu de bloquer la mise en veille jusqu'au redémarrage.
                    "tail", "--pid=${ProcessHandle.current().pid()}", "-f", "/dev/null",
                ).redirectErrorStream(true).start()
            }.getOrNull()
        } else if (!sleepBlocked) {
            inhibitor?.destroy()
            inhibitor = null
        }
    }

    fun shutdown() {
        inhibitor?.destroy()
        worker.shutdownNow()
    }
}

/** Lancement de Vyzo à l'ouverture de session (réduit dans la zone de notification). */
object Autostart {
    /** Chemin du lanceur installé (fourni par jpackage) ; null quand Vyzo tourne depuis les sources. */
    private val launcher: String? get() = System.getProperty("jpackage.app-path")?.takeIf { File(it).exists() }

    private val linuxFile: File
        get() = File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.config", "autostart/vyzo.desktop")

    fun set(enabled: Boolean): Boolean = runCatching {
        val exe = launcher
        if (Os.isWindows) {
            val key = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
            if (enabled) {
                exe ?: return false
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "Vyzo", "\"$exe\" --minimized")
            } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, "Vyzo")) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, key, "Vyzo")
            }
            true
        } else {
            val f = linuxFile
            if (enabled) {
                exe ?: return false
                f.parentFile.mkdirs()
                f.writeText(
                    """
                    |[Desktop Entry]
                    |Type=Application
                    |Name=Vyzo
                    |Comment=Lecteur IPTV
                    |Exec="$exe" --minimized
                    |Icon=vyzo
                    |X-GNOME-Autostart-enabled=true
                    |NoDisplay=false
                    """.trimMargin() + "\n",
                )
            } else {
                f.delete()
            }
            true
        }
    }.onFailure { Log.w("Vyzo", "lancement automatique", it) }.getOrDefault(false)
}

/** Sélecteurs de fichiers natifs : GTK (zenity) sous Linux, boîte de dialogue Windows. */
object FileChoosers {
    @Volatile
    var owner: Frame? = null

    fun open(title: String, extensions: List<String>): File? {
        if (Os.isLinux && Os.hasCommand("zenity")) {
            val cmd = mutableListOf("zenity", "--file-selection", "--title=$title")
            if (extensions.isNotEmpty()) cmd += "--file-filter=Fichiers (${extensions.joinToString(", ") { "*.$it" }}) | ${extensions.joinToString(" ") { "*.$it" }}"
            cmd += "--file-filter=Tous les fichiers | *"
            return zenity(cmd)
        }
        return awtDialog(title, FileDialog.LOAD, null, extensions)
    }

    fun save(title: String, suggestedName: String): File? {
        if (Os.isLinux && Os.hasCommand("zenity")) {
            val start = File(System.getProperty("user.home"), suggestedName).absolutePath
            return zenity(listOf("zenity", "--file-selection", "--save", "--confirm-overwrite", "--title=$title", "--filename=$start"))
        }
        return awtDialog(title, FileDialog.SAVE, suggestedName, emptyList())
    }

    fun folder(title: String): File? {
        if (Os.isLinux && Os.hasCommand("zenity")) {
            return zenity(listOf("zenity", "--file-selection", "--directory", "--title=$title"))
        }
        var result: File? = null
        val show = Runnable {
            val c = JFileChooser().apply {
                dialogTitle = title
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            }
            if (c.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) result = c.selectedFile
        }
        if (SwingUtilities.isEventDispatchThread()) show.run() else SwingUtilities.invokeAndWait(show)
        return result
    }

    private fun zenity(cmd: List<String>): File? = Os.run(*cmd.toTypedArray(), timeoutSec = 3600)?.takeIf { it.isNotBlank() }?.let(::File)

    private fun awtDialog(title: String, mode: Int, name: String?, extensions: List<String>): File? {
        var result: File? = null
        val show = Runnable {
            if (Os.isWindows) {
                val d = FileDialog(owner, title, mode)
                if (name != null) d.file = name
                if (extensions.isNotEmpty() && mode == FileDialog.LOAD) d.file = extensions.joinToString(";") { "*.$it" }
                d.isVisible = true
                if (d.file != null) result = File(d.directory, d.file)
            } else {
                val c = JFileChooser().apply {
                    dialogTitle = title
                    if (extensions.isNotEmpty()) fileFilter = FileNameExtensionFilter(extensions.joinToString(", "), *extensions.toTypedArray())
                    if (name != null) selectedFile = File(System.getProperty("user.home"), name)
                }
                val r = if (mode == FileDialog.SAVE) c.showSaveDialog(owner) else c.showOpenDialog(owner)
                if (r == JFileChooser.APPROVE_OPTION) result = c.selectedFile
            }
        }
        if (SwingUtilities.isEventDispatchThread()) show.run() else SwingUtilities.invokeAndWait(show)
        return result
    }
}

fun openInFileManager(dir: File) {
    dir.mkdirs()
    thread(isDaemon = true) {
        when {
            Os.isLinux && Os.hasCommand("xdg-open") -> Os.run("xdg-open", dir.absolutePath)
            Os.isWindows -> runCatching { ProcessBuilder("explorer.exe", dir.absolutePath).start() }
            else -> runCatching { Desktop.getDesktop().open(dir) }
        }
    }
}

/**
 * Une seule instance de Vyzo : relancer Vyzo (raccourci, menu) remet la fenêtre existante au premier plan.
 */
class SingleInstance(private val dir: File) {
    private val lockFile = dir.resolve("vyzo.lock")
    private val portFile = dir.resolve("vyzo.port")
    private var raf: RandomAccessFile? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /** true si cette instance est la seule ; sinon l'instance existante a été prévenue. */
    fun acquire(onActivate: () -> Unit): Boolean {
        val raf = RandomAccessFile(lockFile, "rw")
        lock = runCatching { raf.channel.tryLock() }.getOrNull()
        if (lock == null) {
            raf.close()
            val port = runCatching { portFile.readText().trim().toInt() }.getOrNull()
            if (port != null) runCatching { Socket(InetAddress.getLoopbackAddress(), port).use { it.getOutputStream().write("show\n".toByteArray()) } }
            return false
        }
        this.raf = raf
        // Arrêt par signal (fin de session, kill) : les fichiers sont aussi retirés.
        runCatching { Runtime.getRuntime().addShutdownHook(Thread({ release() }, "single-instance-release")) }
        val s = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        server = s
        portFile.writeText(s.localPort.toString())
        thread(isDaemon = true, name = "single-instance") {
            while (!s.isClosed) {
                runCatching { s.accept().use { c -> if (c.getInputStream().bufferedReader().readLine() == "show") onActivate() } }
            }
        }
        return true
    }

    /** Ferme le canal d'activation et retire vyzo.port et vyzo.lock. */
    @Synchronized
    fun release() {
        if (lock == null) return
        runCatching { server?.close() }
        portFile.delete()
        // Supprimé pendant que le verrou est tenu (Linux, macOS) : une instance lancée entre-temps ne peut pas verrouiller
        // un fichier que l'on effacerait juste après. Windows refuse d'effacer un fichier ouvert : on réessaie après fermeture.
        val deleted = lockFile.delete()
        runCatching { lock?.release() }
        runCatching { raf?.close() }
        lock = null
        if (!deleted) lockFile.delete()
    }
}
