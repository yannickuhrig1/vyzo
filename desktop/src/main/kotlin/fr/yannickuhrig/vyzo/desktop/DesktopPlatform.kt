package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.ReminderEntity
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.platform.Platform
import fr.yannickuhrig.vyzo.platform.WindowControls
import fr.yannickuhrig.vyzo.schedule.RecordingRunner
import fr.yannickuhrig.vyzo.schedule.RecordingScheduler
import fr.yannickuhrig.vyzo.schedule.ReminderScheduler
import fr.yannickuhrig.vyzo.ui.common.FormFactor
import fr.yannickuhrig.vyzo.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Ce qui est propre à Vyzo PC, vu du code commun. */
class DesktopPlatform(private val windowControls: WindowControls) : Platform {
    override val isDesktop = true
    override val appVersion: String = BuildInfo.VERSION
    override val appVersionCode: Int = BuildInfo.VERSION_CODE
    override val deviceModel: String get() = Os.model
    override val systemVersion: String get() = Os.description
    override val filesDir: File get() = AppDirs.data
    override val cloudUrl: String = BuildInfo.SUPABASE_URL
    override val cloudKey: String = BuildInfo.SUPABASE_KEY
    override val tmdbBuiltInKey: String = BuildInfo.TMDB_KEY
    override val updateUrl: String = BuildInfo.UPDATE_URL
    override val deviceName: String by lazy {
        (System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull())
            ?.takeIf { it.isNotBlank() && it != "localhost" }?.let { "Vyzo PC ($it)" } ?: "Vyzo PC"
    }
    override val hasCamera = false
    override val hasVoiceSearch = false
    override val stopsLiveInBackground = false
    override val cacheDir: File get() = AppDirs.cache
    override val localBackupFile: File get() = AppDirs.data.resolve("vyzo-sauvegarde.json")
    override val window: WindowControls get() = windowControls

    private val _toasts = MutableStateFlow<Pair<String, Long>?>(null)
    /** Message à afficher en bas de la fenêtre (texte, durée en ms). */
    val toasts: StateFlow<Pair<String, Long>?> = _toasts.asStateFlow()

    /** Interface « TV » (rail à gauche, guide en grille) sauf si l'utilisateur force le mode téléphone. */
    override fun formFactor(pref: String): FormFactor = if (pref == "PHONE") FormFactor.PHONE else FormFactor.TV

    private fun fileOf(uri: String): File? = when {
        uri.startsWith("file:") -> runCatching { File(URI(uri)) }.getOrElse { File(uri.removePrefix("file://")) }
        uri.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(uri) -> File(uri)
        else -> null
    }

    override fun openInput(uri: String): InputStream? = fileOf(uri)?.takeIf { it.isFile }?.let(::FileInputStream)

    override fun openOutput(uri: String): OutputStream? = fileOf(uri)?.let { it.parentFile?.mkdirs(); FileOutputStream(it) }

    override fun newPullParser(): XmlPullParser = KXmlParser()

    override fun toast(message: String, long: Boolean) {
        _toasts.value = message to if (long) 4_500L else 2_500L
    }

    var autoSync: (Int) -> Unit = {}

    override fun scheduleAutoSync(hours: Int) = autoSync(hours)

    /** Ouvre la fiche d'une série (clic sur une alerte de nouvel épisode) ; fourni par Main.kt. */
    var onOpenSeries: (String) -> Unit = {}

    override fun notifyNewEpisodes(title: String, body: String, seriesUid: String) =
        Notifier.notify(title, body) { onOpenSeries(seriesUid) }

    override fun setLaunchAtLogin(enabled: Boolean): Boolean = Autostart.set(enabled)

    override fun openFolder(dir: File) = openInFileManager(dir)

    override fun openUrl(url: String): Boolean = runCatching {
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
            java.awt.Desktop.getDesktop().browse(URI(url))
            true
        } else {
            val os = System.getProperty("os.name", "").lowercase()
            when {
                os.contains("win") -> ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
                os.contains("mac") -> ProcessBuilder("open", url).start()
                else -> ProcessBuilder("xdg-open", url).start()
            }
            true
        }
    }.getOrElse { false }

    override fun openInExternalPlayer(
        url: String,
        title: String,
        userAgent: String,
        headers: Map<String, String>,
        mimeHint: String?,
        startMs: Long,
    ): Boolean {
        val safe = ExternalPlayers.safeUrl(url) ?: run {
            Log.w("ExternalPlayer", "Adresse de flux refusée pour le lecteur externe (schéma inattendu)")
            return false
        }
        val player = ExternalPlayers.find() ?: return false
        return runCatching {
            ProcessBuilder(ExternalPlayers.command(player, safe, title, userAgent, headers, startMs))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        }.getOrElse {
            Log.w("ExternalPlayer", "Lancement de ${player.path} impossible", it)
            false
        }
    }
}

/** VLC ou mpv installés sur le PC, pour « Ouvrir dans un autre lecteur ». */
internal object ExternalPlayers {
    enum class Kind { VLC, MPV }
    data class Player(val kind: Kind, val path: String)

    private val streamScheme = Regex("^(https?|rtsp|rtmp|rtmps|udp|rtp|mms|file)://", RegexOption.IGNORE_CASE)

    /**
     * L'adresse vient de la playlist du fournisseur : elle doit être une vraie adresse de flux.
     * Une valeur commençant par « - » serait lue par VLC ou mpv comme une option (mpv --script=… exécute du code).
     */
    fun safeUrl(url: String): String? = url.trim().takeIf { !it.startsWith("-") && streamScheme.containsMatchIn(it) }

    fun find(): Player? {
        val windows = System.getProperty("os.name", "").lowercase().contains("win")
        val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
        fun onPath(name: String): String? {
            val names = if (windows) listOf("$name.exe", name) else listOf(name)
            return dirs.firstNotNullOfOrNull { d -> names.map { File(d, it) }.firstOrNull { it.isFile && it.canExecute() }?.path }
        }
        if (windows) {
            val programDirs = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"))
            programDirs.map { File(it, "VideoLAN\\VLC\\vlc.exe") }.firstOrNull { it.isFile }?.let { return Player(Kind.VLC, it.path) }
        }
        onPath("vlc")?.let { return Player(Kind.VLC, it) }
        onPath("mpv")?.let { return Player(Kind.MPV, it) }
        return null
    }

    fun command(p: Player, url: String, title: String, userAgent: String, headers: Map<String, String>, startMs: Long): List<String> {
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value.orEmpty()
        val startSec = startMs / 1000
        return buildList {
            add(p.path)
            when (p.kind) {
                Kind.VLC -> {
                    if (title.isNotBlank()) add("--meta-title=$title")
                    if (userAgent.isNotBlank()) add("--http-user-agent=$userAgent")
                    if (referer.isNotBlank()) add("--http-referrer=$referer")
                    if (startSec > 0) add("--start-time=$startSec")
                }
                Kind.MPV -> {
                    if (title.isNotBlank()) add("--force-media-title=$title")
                    if (userAgent.isNotBlank()) add("--user-agent=$userAgent")
                    if (referer.isNotBlank()) add("--referrer=$referer")
                    if (startSec > 0) add("--start=$startSec")
                }
            }
            add(url)
        }
    }
}

/** Attend jusqu'à [at] (heure murale) en revérifiant souvent : fiable même après une mise en veille du PC. */
suspend fun delayUntil(at: Long) {
    while (true) {
        val left = at - System.currentTimeMillis()
        if (left <= 0) return
        delay(minOf(left, 30_000L))
    }
}

/** Rappels : minuterie tant que Vyzo tourne (fenêtre ouverte ou dans la zone de notification). */
class DesktopReminderScheduler(
    db: AppDatabase,
    settings: SettingsStore,
    private val scope: CoroutineScope,
    private val onDue: (ReminderEntity) -> Unit,
) : ReminderScheduler(db, settings) {
    private val timers = ConcurrentHashMap<Long, Job>()

    override fun arm(r: ReminderEntity, at: Long) {
        timers.remove(r.id)?.cancel()
        timers[r.id] = scope.launch {
            delayUntil(at)
            timers.remove(r.id)
            db.reminders().get(r.id)?.let(onDue)
        }
    }

    override fun disarm(id: Long) {
        timers.remove(id)?.cancel()
    }
}

/** Enregistrements : minuterie jusqu'au début, puis écriture du flux en tâche de fond. Pas de mise en veille pendant ce temps. */
class DesktopRecordingScheduler(
    private val c: AppContainer,
    private val scope: CoroutineScope,
) : RecordingScheduler(c.db, c.settings, c.platform) {
    private val timers = ConcurrentHashMap<Long, Job>()
    private val running = ConcurrentHashMap<Long, Job>()
    private val _active = MutableStateFlow(0)

    /** Nombre d'enregistrements en cours. */
    val active: StateFlow<Int> = _active.asStateFlow()

    override val directory: File get() = AppDirs.recordings

    /** Dossier choisi dans les réglages (autre disque, partage réseau…) s'il est accessible, sinon Vidéos/Vyzo. */
    override suspend fun newRecordingPath(name: String): Pair<String, String> {
        val chosen = c.settings.get().recFolderUri
        if (chosen.isBlank()) return directory.resolve(name).absolutePath to ""
        val dir = File(chosen)
        return if (dir.isDirectory && dir.canWrite()) dir.resolve(name).absolutePath to ""
        else directory.resolve(name).absolutePath to "Dossier choisi inaccessible : enregistré dans ${directory.absolutePath}"
    }

    override fun armAt(id: Long, at: Long) {
        timers.remove(id)?.cancel()
        timers[id] = scope.launch {
            delayUntil(at)
            timers.remove(id)
            startNow(id)
        }
    }

    override fun disarmAt(id: Long) {
        timers.remove(id)?.cancel()
    }

    override fun startNow(id: Long, retryWithAlarm: Boolean): Boolean {
        if (running[id] != null) return true
        val job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            KeepAwake.acquire("rec:$id")
            try {
                RecordingRunner(c).record(id, this) { title -> Notifier.notify("Enregistrement en cours", title) }
            } catch (e: CancellationException) {
                // Arrêt demandé : l'état est déjà enregistré par RecordingRunner.
            } catch (e: Exception) {
                Log.w("VyzoRec", "enregistrement $id", e)
            } finally {
                running.remove(id)
                _active.value = running.size
                KeepAwake.release("rec:$id")
            }
        }
        running[id] = job
        _active.value = running.size
        job.start()
        return true
    }

    override fun stopNow(id: Long) {
        running[id]?.cancel()
    }

    /** À la fermeture de Vyzo : on termine proprement les fichiers en cours. */
    fun stopAll() {
        running.values.forEach { it.cancel() }
    }
}
