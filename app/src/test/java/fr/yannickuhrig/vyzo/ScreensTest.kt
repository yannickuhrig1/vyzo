package fr.yannickuhrig.vyzo

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import fr.yannickuhrig.vyzo.ui.MainNav
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.RecordingEntity
import fr.yannickuhrig.vyzo.data.db.RecordingRuleEntity
import fr.yannickuhrig.vyzo.data.db.RecordingStatus
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.ui.common.FormFactor
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.LocalFormFactor
import fr.yannickuhrig.vyzo.ui.common.PlayKind
import fr.yannickuhrig.vyzo.ui.guide.GuideScreen
import fr.yannickuhrig.vyzo.ui.live.LiveScreen
import fr.yannickuhrig.vyzo.ui.player.PlayerScreen
import fr.yannickuhrig.vyzo.ui.profile.PinDialog
import fr.yannickuhrig.vyzo.ui.profile.ProfilesScreen
import fr.yannickuhrig.vyzo.ui.profile.WhoIsWatchingScreen
import fr.yannickuhrig.vyzo.ui.scheduled.ScheduledScreen
import fr.yannickuhrig.vyzo.ui.settings.AccountScreen
import fr.yannickuhrig.vyzo.ui.settings.PlaylistEditScreen
import fr.yannickuhrig.vyzo.ui.settings.SettingsScreen
import fr.yannickuhrig.vyzo.ui.theme.VyzoTheme
import fr.yannickuhrig.vyzo.ui.vod.MovieDetailScreen
import fr.yannickuhrig.vyzo.ui.vod.SeriesDetailScreen
import fr.yannickuhrig.vyzo.ui.vod.VodBrowser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Rend les écrans principaux avec de fausses données et enregistre des captures (Roborazzi). */
abstract class ScreensBase {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    protected lateinit var server: MockWebServer
    protected val app get() = ApplicationProvider.getApplicationContext<VyzoApp>()
    protected var playlistId = 0L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = Fixtures.dispatcher(server)
        server.start()
        runBlocking {
            val c = app.container
            playlistId = c.repo.addPlaylist(
                PlaylistEntity(name = "Mon abonnement", type = SourceType.XTREAM, url = server.url("/").toString(), username = "bob", password = "secret"),
            )
            c.sync.syncPlaylist(playlistId).getOrThrow()
            val ch = c.repo.channels(Repository.KEY_ALL).first()
            c.repo.toggleFavorite(ch[0].uid, ContentKind.LIVE)
            c.repo.toggleFavorite(ch[6].uid, ContentKind.LIVE)
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Foyer de démonstration : un profil enfant, un adulte avec code. */
    protected fun addFamily() = runBlocking {
        val c = app.container
        c.profiles.create("Léa", isKid = true)
        val marc = c.profiles.create("Marc")
        c.profiles.setPin(marc.id, "1234")
    }

    /** Une série suivie, trois enregistrements en même temps (au-delà des 2 connexions de l'abonnement), un terminé. */
    protected fun addRecordings() = runBlocking {
        val c = app.container
        val ch = c.repo.channels(Repository.KEY_ALL).first()
        val now = System.currentTimeMillis()
        val h = 3_600_000L
        val rule = c.db.recordingRules().insert(RecordingRuleEntity(channelUid = ch[0].uid, channelName = ch[0].name, title = "Koh-Lanta", titleNorm = "koh lanta"))
        c.db.recordings().insert(RecordingEntity(channelUid = ch[0].uid, channelName = ch[0].name, title = "Koh-Lanta", start = now + 2 * h, stop = now + 4 * h, ruleId = rule))
        c.db.recordings().insert(RecordingEntity(channelUid = ch[1].uid, channelName = ch[1].name, title = "Envoyé spécial", start = now + 2 * h, stop = now + 3 * h))
        c.db.recordings().insert(RecordingEntity(channelUid = ch[2].uid, channelName = ch[2].name, title = "Football : Ligue des champions", start = now + 2 * h, stop = now + 4 * h))
        c.db.recordings().insert(
            RecordingEntity(
                channelUid = ch[0].uid, channelName = ch[0].name, title = "Koh-Lanta", start = now - 22 * h, stop = now - 20 * h,
                status = RecordingStatus.DONE, bytes = 1_900_000_000L, filePath = "/enregistrements/koh-lanta.ts", ruleId = rule,
            ),
        )
    }

    @OptIn(ExperimentalTestApi::class)
    protected fun pressRight() {
        compose.onNode(androidx.compose.ui.test.isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
    }

    protected fun shoot(name: String, form: FormFactor, settleMs: Long = 1500, after: (() -> Unit)? = null, capture: Boolean = true, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides app.container, LocalFormFactor provides form) {
                VyzoTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        settle(settleMs)
        if (after != null) {
            after()
            settle(1200)
        }
        if (capture) compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            Thread.sleep(50)
        }
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class TvScreensTest : ScreensBase() {
    @Test fun shell() = shoot("tv_0_accueil", FormFactor.TV) { MainNav(kotlinx.coroutines.flow.MutableStateFlow(null)) }

    @Test fun brand() = shoot("tv_0_marque", FormFactor.TV, 300) {
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxSize().padding(48.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(28.dp),
        ) {
            fr.yannickuhrig.vyzo.ui.common.VyzoWordmark(markSize = 34.dp, fontSize = 24)
            fr.yannickuhrig.vyzo.ui.common.VyzoWordmark(markSize = 28.dp, fontSize = 22)
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(28.dp)) {
                listOf(24, 34, 64, 128, 200).forEach { fr.yannickuhrig.vyzo.ui.common.VyzoMark(it.dp) }
            }
            fr.yannickuhrig.vyzo.ui.common.ProgressLine(0.65f, Modifier.width(360.dp))
        }
    }

    @Test fun live() = shoot("tv_1_direct", FormFactor.TV) { LiveScreen(onOpenPlayer = { _, _ -> }) }

    @Test fun guide() = shoot("tv_2_guide", FormFactor.TV) { GuideScreen(onWatch = { _, _ -> }, onCatchup = { _, _ -> }) }

    @Test fun movies() = shoot("tv_3_films", FormFactor.TV) { VodBrowser(ContentKind.MOVIE, onOpen = {}) }

    @Test fun movieDetail() = shoot("tv_4_fiche_film", FormFactor.TV) { MovieDetailScreen("$playlistId:M:501", onPlay = { _, _ -> }) }

    @Test fun seriesDetail() = shoot("tv_5_fiche_serie", FormFactor.TV, 2500) { SeriesDetailScreen("$playlistId:S:701", onPlayEpisode = { _, _ -> }) }

    @Test fun settings() = shoot("tv_6_reglages", FormFactor.TV) { SettingsScreen(onEditPlaylist = {}, onEpgSources = {}, onGroups = {}) }

    @Test fun receiveFromPhone() = shoot("tv_8_qr_telephone", FormFactor.TV) {
        val receiver = androidx.compose.runtime.remember {
            fr.yannickuhrig.vyzo.data.remote.ConfigReceiver(
                deviceName = "Salon", syncState = { app.container.sync.state.value }, inspectConfig = { null },
                ipProvider = { "192.168.1.42" },
            )
        }
        fr.yannickuhrig.vyzo.ui.remote.ReceiveFromPhoneDialog(onDismiss = {}, receiver = receiver)
    }

    @Test fun wizardTv() = shoot("tv_9_assistant", FormFactor.TV) { PlaylistEditScreen(0, firstRun = true, onDone = {}) }

    @Test fun player() = shoot("tv_7_lecteur", FormFactor.TV, 2500) {
        PlayerScreen(PlayKind.CHANNEL, "$playlistId:L:101", Repository.KEY_ALL, 0, -1, onExit = {}, onCatchup = { _, _ -> })
    }

    @Test fun whoIsWatching() {
        addFamily()
        shoot("tv_10_qui_regarde", FormFactor.TV) { WhoIsWatchingScreen(onPicked = {}, onManage = {}) }
    }

    @Test fun profiles() {
        addFamily()
        shoot("tv_11_profils", FormFactor.TV) { ProfilesScreen() }
    }

    @Test fun account() = shoot("tv_12_compte", FormFactor.TV) { AccountScreen() }

    @Test fun pin() = shoot("tv_13_code", FormFactor.TV, 800) {
        PinDialog("Code parental", "Pour afficher les catégories verrouillées", onDismiss = {}, onSubmit = { false })
    }

    @Test fun recordings() {
        addRecordings()
        shoot("tv_14_enregistrements", FormFactor.TV) { ScheduledScreen(onPlayRecording = {}, onWatchChannel = {}) }
    }

    @Test fun playerMiniGuide() = shoot("tv_15_lecteur_mini_guide", FormFactor.TV, 2500, after = { pressRight() }) {
        PlayerScreen(PlayKind.CHANNEL, "$playlistId:L:101", Repository.KEY_ALL, 0, -1, onExit = {}, onCatchup = { _, _ -> })
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h852dp-port-xxhdpi")
class PhoneScreensTest : ScreensBase() {
    @Test fun shell() = shoot("tel_0_accueil", FormFactor.PHONE) { MainNav(kotlinx.coroutines.flow.MutableStateFlow(null)) }

    @Test fun live() = shoot("tel_1_direct", FormFactor.PHONE) { LiveScreen(onOpenPlayer = { _, _ -> }) }

    @Test fun guide() = shoot("tel_2_guide", FormFactor.PHONE) { GuideScreen(onWatch = { _, _ -> }, onCatchup = { _, _ -> }) }

    @Test fun series() = shoot("tel_3_series", FormFactor.PHONE) { VodBrowser(ContentKind.SERIES, onOpen = {}) }

    @Test fun wizard() = shoot("tel_4_assistant", FormFactor.PHONE) { PlaylistEditScreen(0, firstRun = true, onDone = {}) }

    @Test fun whoIsWatching() {
        addFamily()
        shoot("tel_5_qui_regarde", FormFactor.PHONE) { WhoIsWatchingScreen(onPicked = {}, onManage = {}) }
    }

    @Test fun settings() = shoot("tel_6_reglages", FormFactor.PHONE) { SettingsScreen(onEditPlaylist = {}, onEpgSources = {}, onGroups = {}) }

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-xxhdpi")
    fun player() = shoot("tel_7_lecteur", FormFactor.PHONE, 2500) {
        PlayerScreen(PlayKind.CHANNEL, "$playlistId:L:101", Repository.KEY_ALL, 0, -1, onExit = {}, onCatchup = { _, _ -> })
    }
}
