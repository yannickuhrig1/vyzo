package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.cloud.CloudApi
import fr.yannickuhrig.vyzo.data.cloud.CloudStore
import fr.yannickuhrig.vyzo.data.cloud.CloudSync
import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.data.profile.LocalProfiles
import fr.yannickuhrig.vyzo.data.profile.ParentalControl
import fr.yannickuhrig.vyzo.data.profile.ProfileManager
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.data.sync.SyncManager
import fr.yannickuhrig.vyzo.diag.DiagLog
import fr.yannickuhrig.vyzo.platform.Platform
import fr.yannickuhrig.vyzo.player.PipState
import fr.yannickuhrig.vyzo.player.VideoPlayer
import fr.yannickuhrig.vyzo.schedule.AppEvents
import fr.yannickuhrig.vyzo.schedule.RecordingScheduler
import fr.yannickuhrig.vyzo.schedule.ReminderScheduler
import fr.yannickuhrig.vyzo.update.AppUpdater
import fr.yannickuhrig.vyzo.update.UpdateInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Conteneur d'injection manuel : une seule instance de chaque service pour toute l'appli.
 * Construit par l'appli Android (VyzoApp) ou par Vyzo PC (Main.kt), qui fournissent les briques propres à leur système.
 */
class AppContainer(
    val platform: Platform,
    val scope: CoroutineScope,
    val db: AppDatabase,
    val settings: SettingsStore,
    /** Session du compte Vyzo et curseurs de synchro. */
    cloudStore: CloudStore,
    private val playerFactory: (AppContainer) -> VideoPlayer,
    private val newPlayerFactory: ((AppContainer, Boolean) -> VideoPlayer)? = null,
    remindersFactory: (AppContainer) -> ReminderScheduler,
    recordingsFactory: (AppContainer) -> RecordingScheduler,
    updateInstallerFactory: (AppContainer) -> UpdateInstaller,
    /** Notifications instantanées des autres appareils (désactivées dans les tests sur faux serveur). */
    realtimeEnabled: Boolean = true,
) {
    val sync = SyncManager(platform, db, settings, scope)
    val profiles = ProfileManager(db, settings, scope)
    val localProfiles = LocalProfiles(db, profiles, settings, platform.localProfileDir)
    val parental = ParentalControl(db, settings, profiles, scope)
    val repo = Repository(db, settings, sync, profiles, parental)
    val cloud = CloudSync(
        CloudApi(platform.cloudUrl, platform.cloudKey, Http.client),
        cloudStore, db, repo, profiles, settings, scope, realtimeEnabled = realtimeEnabled, platform = platform,
    )
    val events = AppEvents()
    val pip = PipState()
    val sleepTimer by lazy {
        fr.yannickuhrig.vyzo.player.SleepTimer(scope, onExpire = { scope.launch(kotlinx.coroutines.Dispatchers.Main) { player.stop() } })
    }
    val subtitles = fr.yannickuhrig.vyzo.data.subtitles.SubtitleService(settings)
    private val metadataCache = fr.yannickuhrig.vyzo.data.tmdb.MetadataCache(db.metadataCache())
    val tmdb = fr.yannickuhrig.vyzo.data.tmdb.TmdbService(settings, metadataCache, platform.tmdbBuiltInKey)
    val omdb = fr.yannickuhrig.vyzo.data.ratings.OmdbClient(settings, metadataCache)
    val introDb = fr.yannickuhrig.vyzo.data.intro.IntroDbClient()
    val tvMaze = fr.yannickuhrig.vyzo.data.intro.TvMazeClient()
    val weather = fr.yannickuhrig.vyzo.data.weather.WeatherService()
    val downloads = fr.yannickuhrig.vyzo.data.download.DownloadManager(platform, db, scope) { d ->
        when (d.itemType) {
            "movie" -> db.movies().get(d.itemUid)?.let { repo.resolveMovie(it) }
            "episode" -> db.series().episode(d.itemUid)?.let { repo.resolveEpisode(it) }
            else -> null
        }
    }
    val isVaultUnlocked: StateFlow<Boolean> get() = parental.isVaultUnlocked
    fun unlockVault(pin: String): Boolean = parental.unlockVault(pin)
    fun lockVault() = parental.lockVault()
    val player: VideoPlayer by lazy { playerFactory(this) }
    /** Crée une nouvelle instance de lecteur vidéo (utilisé notamment pour le Multi-View). */
    fun createPlayer(isSecondary: Boolean = true): VideoPlayer =
        newPlayerFactory?.invoke(this, isSecondary) ?: playerFactory(this)
    val reminders: ReminderScheduler by lazy { remindersFactory(this) }
    val recordings: RecordingScheduler by lazy {
        recordingsFactory(this).apply {
            onScheduleChanged = { event ->
                cloud.sendScheduleSync(event)
            }
        }
    }
    val newEpisodes by lazy { fr.yannickuhrig.vyzo.data.vod.NewEpisodeWatcher(db, settings, sync, platform) }
    val updater: AppUpdater by lazy { AppUpdater(settings, platform, updateInstallerFactory(this)) }

    /** Démarrage commun : séries suivies après chaque guide, compte Vyzo, catégories adultes, journal de diagnostic. */
    fun start() {
        sync.afterEpg = {
            runCatching { recordings.applyRules() }
            // Hors du verrou de la synchro : une requête par série suivie, au plus toutes les 6 h.
            scope.launch { runCatching { newEpisodes.check() } }
        }
        scope.launch { runCatching { localProfiles.removeForeign() } }
        cloud.onRemoteRecording = { recordings.applyRemote(it) }
        cloud.start()
        downloads.start()
        scope.launch { runCatching { parental.refreshAdultFlags() } }
        scope.launch { runCatching { metadataCache.purge() } }
        scope.launch {
            settings.flow.map { it.diagnosticLog }.distinctUntilChanged().collect { DiagLog.enabled = it }
        }
    }

    /** L'appli revient au premier plan (Android) ou la fenêtre de Vyzo PC est rouverte. */
    fun onForeground() {
        parental.onForeground()
        cloud.onForeground()
    }

    /** L'appli passe en arrière-plan, ou Vyzo PC est réduit dans la zone de notification. */
    fun onBackground() {
        parental.onBackground()
        // Vyzo PC réduit dans la zone de notification reste joignable : c'est lui qui enregistre.
        cloud.onBackground(stayConnected = platform.isDesktop)
    }
}
