package fr.yannickuhrig.vyzo

import android.app.Application
import android.util.Log as AndroidLog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import fr.yannickuhrig.vyzo.data.cloud.CloudStore
import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.DATABASE_NAME
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.data.sync.SyncWorker
import fr.yannickuhrig.vyzo.diag.CrashReporter
import fr.yannickuhrig.vyzo.player.PlayerManager
import fr.yannickuhrig.vyzo.schedule.AndroidRecordingScheduler
import fr.yannickuhrig.vyzo.schedule.AndroidReminderScheduler
import fr.yannickuhrig.vyzo.schedule.DownloadService
import fr.yannickuhrig.vyzo.schedule.Notif
import fr.yannickuhrig.vyzo.ui.common.vyzoImageLoader
import fr.yannickuhrig.vyzo.update.AndroidUpdateInstaller
import fr.yannickuhrig.vyzo.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Construit le conteneur commun avec les briques Android.
 * [dbName] null = base en mémoire (tests) ; [prefix] sépare les fichiers de réglages de plusieurs conteneurs (tests).
 */
fun androidContainer(
    app: Application,
    dbName: String? = DATABASE_NAME,
    prefix: String = "",
    platform: AndroidPlatform = AndroidPlatform(app),
    realtimeEnabled: Boolean = true,
): AppContainer {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val builder = if (dbName == null) Room.inMemoryDatabaseBuilder<AppDatabase>(app)
    else Room.databaseBuilder<AppDatabase>(app, app.getDatabasePath(dbName).absolutePath)
    val db = AppDatabase.build(builder)
    // Mêmes fichiers qu'avant le passage au code commun (preferencesDataStoreFile("settings") et ("cloud")).
    val datastore = File(app.filesDir, "datastore")
    val settings = SettingsStore(File(datastore, "${prefix}settings.preferences_pb"), scope)
    return AppContainer(
        platform = platform,
        scope = scope,
        db = db,
        settings = settings,
        cloudStore = CloudStore(File(datastore, "${prefix}cloud.preferences_pb")),
        playerFactory = { PlayerManager(app, it.settings) },
        newPlayerFactory = { c, sec -> PlayerManager(app, c.settings, isSecondary = sec) },
        remindersFactory = { AndroidReminderScheduler(app, it.db, it.settings) },
        recordingsFactory = { AndroidRecordingScheduler(app, it.db, it.settings, it.platform) },
        updateInstallerFactory = { AndroidUpdateInstaller(app) },
        realtimeEnabled = realtimeEnabled,
    )
}

class VyzoApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    @Volatile private var downloadsActive = false

    override fun onCreate() {
        super.onCreate()
        Log.sink = { level, tag, msg, t ->
            // La pile est masquée ici plutôt que confiée à android.util.Log, qui l'imprimerait en clair.
            val text = if (t == null) msg else "$msg\n${Log.trace(t)}"
            when (level) {
                Log.Level.DEBUG -> AndroidLog.d(tag, text)
                Log.Level.INFO -> AndroidLog.i(tag, text)
                Log.Level.WARN -> AndroidLog.w(tag, text)
                Log.Level.ERROR -> AndroidLog.e(tag, text)
            }
        }
        val platform = AndroidPlatform(this)
        CrashReporter.install(platform)
        container = androidContainer(this, platform = platform)
        Notif.ensureChannels(this)
        val c = container
        c.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                c.onForeground()
                // Service refusé plus tôt (processus lancé en arrière-plan) : l'appli visible peut le démarrer.
                if (downloadsActive) DownloadService.start(this@VyzoApp)
            }

            override fun onStop(owner: LifecycleOwner) {
                c.onBackground()
                FlavorStartup.onAppBackground(this@VyzoApp)
            }
        })
        c.scope.launch {
            c.downloads.observeAll().map { l -> l.any { it.isDownloading || it.isQueued } }.distinctUntilChanged().collect { active ->
                downloadsActive = active
                if (active) DownloadService.start(this@VyzoApp)
            }
        }
        c.scope.launch {
            val s = c.settings.get()
            SyncWorker.schedule(this@VyzoApp, s.refreshHours)
            FlavorStartup.onAppStart(this@VyzoApp)
            runCatching { c.reminders.rescheduleAll() }
            runCatching { c.recordings.rescheduleAll() }
            runCatching { CrashReporter.sendPending(c.platform, s.sendCrashReports) }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = vyzoImageLoader(context, cacheDir)
}
