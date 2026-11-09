package fr.yannickuhrig.vyzo.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import fr.yannickuhrig.vyzo.data.cloud.CloudStore
import fr.yannickuhrig.vyzo.diag.CrashReporter
import coil3.SingletonImageLoader
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.DATABASE_NAME
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.platform.WindowControls
import fr.yannickuhrig.vyzo.schedule.AppEvent
import fr.yannickuhrig.vyzo.ui.AppRoot
import fr.yannickuhrig.vyzo.ui.LaunchRequest
import fr.yannickuhrig.vyzo.ui.common.DesktopUi
import fr.yannickuhrig.vyzo.ui.common.InputMode
import fr.yannickuhrig.vyzo.ui.common.vyzoImageLoader
import fr.yannickuhrig.vyzo.ui.theme.VyzoTheme
import fr.yannickuhrig.vyzo.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.SystemTray
import java.io.File
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/** Fenêtre principale vue du code commun : plein écran et mini-lecteur. */
class MainWindowController : WindowControls {
    private val _fullscreen = MutableStateFlow(false)
    override val fullscreen: StateFlow<Boolean> = _fullscreen.asStateFlow()

    private val _alwaysOnTop = MutableStateFlow(false)
    override val alwaysOnTop: StateFlow<Boolean> = _alwaysOnTop.asStateFlow()

    override fun toggleAlwaysOnTop(): Boolean {
        _alwaysOnTop.value = !_alwaysOnTop.value
        return _alwaysOnTop.value
    }

    private val _mini = MutableStateFlow(false)
    val miniPlayer: StateFlow<Boolean> = _mini.asStateFlow()

    /** Demandes d'affichage de la fenêtre (icône de notification, deuxième lancement, notification cliquée). */
    val showRequests = MutableStateFlow(0)

    /** Demandes de fermeture de Vyzo (installation d'une mise à jour sous Windows). */
    val quitRequests = MutableStateFlow(0)

    var state: WindowState? = null

    override fun toggleFullscreen() {
        val s = state ?: return
        val goFull = s.placement != WindowPlacement.Fullscreen
        s.placement = if (goFull) WindowPlacement.Fullscreen else WindowPlacement.Floating
        _fullscreen.value = goFull
    }

    fun exitFullscreen() {
        val s = state ?: return
        if (s.placement == WindowPlacement.Fullscreen) s.placement = WindowPlacement.Floating
        _fullscreen.value = false
    }

    override fun openMiniPlayer() {
        exitFullscreen()
        _mini.value = true
        state?.isMinimized = true
    }

    fun closeMiniPlayer(restoreMain: Boolean) {
        _mini.value = false
        if (restoreMain) showRequests.value++
    }

    fun syncPlacement() {
        _fullscreen.value = state?.placement == WindowPlacement.Fullscreen
    }
}

object Icons {
    /** L'icône en fichier, pour notify-send (qui veut un chemin). */
    fun pngFile(): File? = runCatching {
        val f = AppDirs.cache.resolve("vyzo-icon.png")
        if (!f.exists()) Icons::class.java.getResourceAsStream("/vyzo-icon.png")?.use { input -> f.outputStream().use { input.copyTo(it) } }
        f.takeIf { it.exists() }
    }.getOrNull()
}

private fun setupLogging() {
    val file = AppDirs.logs.resolve("vyzo.log")
    // On repart d'un fichier vide quand il devient gros.
    if (file.length() > 2_000_000) file.renameTo(AppDirs.logs.resolve("vyzo.old.log"))
    val out = runCatching { PrintStream(file.outputStream().let { java.io.FileOutputStream(file, true) }, true, "UTF-8") }.getOrNull()
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE)
    Log.sink = { level, tag, msg, t ->
        val line = "${fmt.format(Date())} ${level.name.first()}/$tag: $msg"
        System.err.println(line)
        out?.println(line)
        t?.let { e ->
            val trace = Log.trace(e)
            System.err.println(trace)
            out?.println(trace)
        }
    }
    Thread.setDefaultUncaughtExceptionHandler { th, e -> Log.e("Vyzo", "exception non gérée dans ${th.name}", e) }
}

/**
 * Charge SQLite avant que Compose ne charge Skiko. Dans l'autre ordre, sur certains Linux, SQLite
 * perd le texte de ses erreurs (« Error code: » tout seul) : Room n'y reconnaît plus un conflit
 * et ses upserts échouent, pour toute la durée du processus.
 */
fun loadSqliteBeforeCompose() = BundledSQLiteDriver().open(":memory:").close()

fun main(args: Array<String>) {
    val startMinimized = "--minimized" in args
    setupLogging()
    Log.i("Vyzo", "Vyzo PC ${BuildInfo.VERSION} – ${System.getProperty("os.name")} ${System.getProperty("os.version")}, Java ${System.getProperty("java.version")}")

    val controller = MainWindowController()
    val instance = SingleInstance(AppDirs.data)
    if (!instance.acquire { controller.showRequests.value++ }) {
        Log.i("Vyzo", "déjà ouvert : fenêtre existante ramenée au premier plan")
        exitProcess(0)
    }

    loadSqliteBeforeCompose()
    Vlc.init()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val launch = MutableStateFlow<LaunchRequest?>(null)
    val platform = DesktopPlatform(controller)
    val db = AppDatabase.build(
        Room.databaseBuilder<AppDatabase>(name = AppDirs.data.resolve(DATABASE_NAME).absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO),
    )
    val settings = SettingsStore(AppDirs.data.resolve("settings.preferences_pb"), scope)
    val cloudStore = CloudStore(AppDirs.data.resolve("cloud.preferences_pb"))
    lateinit var container: AppContainer
    container = AppContainer(
        platform = platform,
        scope = scope,
        db = db,
        settings = settings,
        cloudStore = cloudStore,
        playerFactory = { VlcPlayer(it.settings) },
        newPlayerFactory = { c, sec -> VlcPlayer(c.settings, isSecondary = sec) },
        remindersFactory = { c ->
            DesktopReminderScheduler(c.db, c.settings, scope) { r ->
                // Fenêtre visible : fenêtre de rappel dans Vyzo. Toujours : notification du bureau.
                c.events.emit(AppEvent.ReminderDue(r))
                Notifier.notify("C'est l'heure : ${r.title}", "Sur ${r.channelName}") {
                    launch.value = LaunchRequest.PlayChannel(r.channelUid)
                    controller.showRequests.value++
                }
            }
        },
        recordingsFactory = { c -> DesktopRecordingScheduler(c, scope) },
        updateInstallerFactory = {
            DesktopUpdateInstaller(onBeforeInstall = {
                // Windows remplace les fichiers de Vyzo : on lui laisse la place quelques secondes après l'ouverture de l'installeur.
                if (Os.isWindows) scope.launch { delay(3_000); controller.quitRequests.value++ }
            })
        },
    )
    CrashReporter.install(platform)
    platform.onOpenSeries = { uid ->
        launch.value = LaunchRequest.OpenSeries(uid)
        controller.showRequests.value++
    }
    container.start()

    val nativeMemory = NativeMemory()
    SingletonImageLoader.setSafe { ctx -> vyzoImageLoader(ctx, AppDirs.cache, nativeMemory.listener) }
    DesktopUi.openFileChooser = { title, ext -> FileChoosers.open(title, ext) }
    DesktopUi.saveFileChooser = { title, name -> FileChoosers.save(title, name) }
    DesktopUi.folderChooser = { title -> FileChoosers.folder(title) }
    DesktopUi.onPlayerScreen = { visible ->
        KeepAwake.setWatching(visible)
        if (!visible) controller.exitFullscreen()
    }

    // Mise à jour automatique : au lancement si les données sont trop anciennes, puis à intervalle régulier.
    var syncJob: kotlinx.coroutines.Job? = null
    platform.autoSync = { hours ->
        syncJob?.cancel()
        syncJob = if (hours <= 0) null else scope.launch {
            while (true) {
                val s = container.settings.get()
                val stale = System.currentTimeMillis() - s.lastFullSync > hours * 3_600_000L
                if (stale && container.db.playlists().all().isNotEmpty() && !container.sync.state.value.running) container.sync.launchAll()
                delay(10 * 60_000L)
            }
        }
    }
    scope.launch {
        val s = container.settings.get()
        platform.autoSync(s.refreshHours)
        runCatching { container.reminders.rescheduleAll() }
        runCatching { container.recordings.rescheduleAll() }
        runCatching { CrashReporter.sendPending(platform, s.sendCrashReports) }
        // Le réglage « lancer à l'ouverture de session » suit l'emplacement actuel de l'appli.
        if (s.launchAtLogin) Autostart.set(true)
    }

    application(exitProcessOnExit = false) {
        VyzoDesktop(container, platform, controller, launch, startMinimized)
    }

    Log.i("Vyzo", "fermeture")
    (container.recordings as? DesktopRecordingScheduler)?.stopAll()
    (container.player as? VlcPlayer)?.release()
    KeepAwake.shutdown()
    instance.release()
    runCatching { db.close() }
    exitProcess(0)
}

@Composable
private fun ApplicationScope.VyzoDesktop(
    c: AppContainer,
    platform: DesktopPlatform,
    controller: MainWindowController,
    launch: MutableStateFlow<LaunchRequest?>,
    startMinimized: Boolean,
) {
    val settings by c.settings.state.collectAsState()
    val trayAvailable = remember { runCatching { SystemTray.isSupported() }.getOrDefault(false) }
    var visible by remember { mutableStateOf(!(startMinimized && trayAvailable)) }
    val windowState = rememberWindowState(size = DpSize(1280.dp, 780.dp), position = WindowPosition(Alignment.Center))
    controller.state = windowState
    val trayState = rememberTrayState()
    val recording by (c.recordings as DesktopRecordingScheduler).active.collectAsState()
    val mini by controller.miniPlayer.collectAsState()
    val showRequests by controller.showRequests.collectAsState()

    fun show() {
        visible = true
        windowState.isMinimized = false
        controller.closeMiniPlayer(restoreMain = false)
        controller.showRequests.value++
    }

    fun quit(origin: String) {
        Log.i("Vyzo", "fermeture demandée : $origin")
        c.player.stop()
        exitApplication()
    }

    // Fenêtre cachée dans la zone de notification = appli en arrière-plan (verrou parental, synchro instantanée en pause).
    LaunchedEffect(visible) {
        if (visible) c.onForeground() else c.onBackground()
    }
    val quitRequests by controller.quitRequests.collectAsState()
    LaunchedEffect(quitRequests) {
        if (quitRequests > 0) quit("installation d'une mise à jour")
    }

    val isAlwaysOnTop by controller.alwaysOnTop.collectAsState()
    LaunchedEffect(isAlwaysOnTop) {
        (c.player as? VlcPlayer)?.setAlwaysOnTopState(isAlwaysOnTop)
    }
    LaunchedEffect(Unit) {
        (c.player as? VlcPlayer)?.onToggleAlwaysOnTop = { controller.toggleAlwaysOnTop() }
        (c.player as? VlcPlayer)?.onEnterPip = { controller.toggleAlwaysOnTop() }
    }

    LaunchedEffect(Unit) {
        Notifier.trayNotify = { title, body ->
            if (trayAvailable) {
                trayState.sendNotification(Notification(title, body, Notification.Type.Info))
                true
            } else {
                false
            }
        }
    }

    if (trayAvailable) {
        Tray(
            icon = painterResource("vyzo-tray.png"),
            state = trayState,
            tooltip = if (recording > 0) "Vyzo – $recording enregistrement(s) en cours" else "Vyzo",
            onAction = { show() },
            menu = {
                Item("Ouvrir Vyzo", onClick = { show() })
                if (recording > 0) Item("$recording enregistrement(s) en cours", enabled = false, onClick = {})
                Separator()
                Item(if (recording > 0) "Quitter (arrête les enregistrements)" else "Quitter", onClick = { quit("menu de la zone de notification") })
            },
        )
    }

    Window(
        onCloseRequest = {
            if (settings.keepInTray && trayAvailable) {
                // Vyzo reste actif pour les rappels et les enregistrements ; la lecture, elle, s'arrête.
                visible = false
                c.player.stop()
                controller.closeMiniPlayer(restoreMain = false)
                platform.toast("Vyzo continue dans la zone de notification")
            } else {
                quit("fermeture de la fenêtre")
            }
        },
        visible = visible,
        state = windowState,
        alwaysOnTop = isAlwaysOnTop,
        title = "Vyzo",
        icon = painterResource("vyzo-icon.png"),
        onPreviewKeyEvent = { e ->
            DesktopUi.trackKey(e)
            if (e.type == KeyEventType.KeyDown && e.key in navigationKeys) InputMode.keyboard.value = true
            when {
                e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.Q -> { quit("Ctrl+Q"); true }
                else -> false
            }
        },
        onKeyEvent = { e ->
            // Échap = retour (les fenêtres de dialogue se ferment d'abord d'elles-mêmes).
            if (e.type == KeyEventType.KeyDown && (e.key == Key.Escape || e.key == Key.Back)) DesktopUi.dispatchBack() else false
        },
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(960, 600)
            FileChoosers.owner = window
        }
        LaunchedEffect(showRequests) {
            if (showRequests > 0) {
                visible = true
                windowState.isMinimized = false
                window.toFront()
                window.requestFocus()
            }
        }
        // Fenêtre principale rouverte depuis la barre des tâches : le mini-lecteur se referme.
        LaunchedEffect(Unit) {
            snapshotFlow { windowState.isMinimized to windowState.placement }.collect { (minimized, _) ->
                controller.syncPlacement()
                if (!minimized && controller.miniPlayer.value) controller.closeMiniPlayer(restoreMain = false)
            }
        }
        Box(
            Modifier.fillMaxSize()
                .onPointerEvent(PointerEventType.Press) { ev ->
                    InputMode.keyboard.value = false
                    if (ev.button == PointerButton.Back) DesktopUi.dispatchBack()
                }
                .onPointerEvent(PointerEventType.Move) { InputMode.keyboard.value = false },
        ) {
            AppRoot(c, launch)
            ToastHost(platform, Modifier.align(Alignment.BottomCenter))
        }
    }

    if (mini) {
        MiniPlayerWindow(
            c = c,
            onRestore = { show() },
            onClose = { controller.closeMiniPlayer(restoreMain = true) },
        )
    }
}

/** Touches de déplacement : leur usage repasse l'interface en navigation au clavier. */
private val navigationKeys = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.Tab, Key.Enter, Key.NumPadEnter,
)

@Composable
private fun ToastHost(platform: DesktopPlatform, modifier: Modifier) {
    val toast by platform.toasts.collectAsState()
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) {
        val t = toast ?: return@LaunchedEffect
        shown = t.first
        delay(t.second)
        shown = null
    }
    AnimatedVisibility(visible = shown != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier.padding(bottom = 36.dp)) {
        VyzoTheme {
            Text(
                shown.orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color(0xE6202436)).padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}
