package fr.yannickuhrig.vyzo.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.cloud.CloudStore
import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.platform.Platform
import fr.yannickuhrig.vyzo.platform.WindowControls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.file.Files

/** Fenêtre factice pour les tests. */
class FakeWindow : WindowControls {
    override val fullscreen: StateFlow<Boolean> = MutableStateFlow(false)
    var fullscreenToggles = 0
    var miniPlayerOpened = 0
    override fun toggleFullscreen() { fullscreenToggles++ }
    override fun openMiniPlayer() { miniPlayerOpened++ }
}

/** Conteneur de Vyzo PC avec une base et des réglages dans un dossier temporaire (lecteur : VLC). */
fun testContainer(
    dir: File = Files.createTempDirectory("vyzo-test").toFile(),
    fakeCloudUrl: String? = null,
    decorate: (Platform) -> Platform = { it },
): AppContainer {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.build(
        Room.databaseBuilder<AppDatabase>(name = dir.resolve("vyzo.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO),
    )
    return AppContainer(
        platform = DesktopPlatform(FakeWindow()).let { real ->
            // Compte Vyzo vers un faux serveur : c.cloud (celui du lecteur) ne parle jamais au vrai Supabase.
            if (fakeCloudUrl == null) real else object : Platform by real {
                override val cloudUrl: String = fakeCloudUrl
                override val cloudKey: String = "cle-test"
            }
        }.let(decorate),
        scope = scope,
        db = db,
        settings = SettingsStore(dir.resolve("settings.preferences_pb"), scope),
        cloudStore = CloudStore(dir.resolve("cloud.preferences_pb")),
        playerFactory = { VlcPlayer(it.settings) },
        newPlayerFactory = { c, sec -> VlcPlayer(c.settings, isSecondary = sec) },
        remindersFactory = { c -> DesktopReminderScheduler(c.db, c.settings, scope) {} },
        recordingsFactory = { c -> DesktopRecordingScheduler(c, scope) },
        updateInstallerFactory = { DesktopUpdateInstaller {} },
        realtimeEnabled = false,
    )
}
