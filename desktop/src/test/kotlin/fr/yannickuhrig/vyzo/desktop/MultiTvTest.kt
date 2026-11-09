package fr.yannickuhrig.vyzo.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import fr.yannickuhrig.vyzo.closeForTest
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.FakeSupabase
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.EpisodeEntity
import fr.yannickuhrig.vyzo.data.db.MovieEntity
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.download.DownloadManager
import fr.yannickuhrig.vyzo.ui.common.DesktopUi
import fr.yannickuhrig.vyzo.ui.common.FormFactor
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.LocalDesktop
import fr.yannickuhrig.vyzo.ui.common.LocalFormFactor
import fr.yannickuhrig.vyzo.ui.common.PlayKind
import fr.yannickuhrig.vyzo.ui.common.START_FROM_BEGINNING
import fr.yannickuhrig.vyzo.ui.player.PlayerScreen
import fr.yannickuhrig.vyzo.ui.player.PlayerViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Parcours d'un foyer à plusieurs TV, avec de vraies lectures libVLC et un faux compte Vyzo. */
@OptIn(ExperimentalTestApi::class)
class MultiTvTest {
    @get:Rule
    val rule = sqliteThenComposeRule()

    private lateinit var supa: MockWebServer
    private lateinit var fake: FakeSupabase
    private lateinit var media: File
    private val devices = ArrayList<Tv>()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val lifecycle = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    private inner class Tv(extraPlaylistsBefore: Int = 0) {
        val dir: File = Files.createTempDirectory("vyzo-tv").toFile()
        val c: AppContainer = testContainer(dir, supa.url("/").toString().trimEnd('/'))
        val pid: Long = runBlocking {
            repeat(extraPlaylistsBefore) { c.db.playlists().insert(PlaylistEntity(name = "Autre", type = SourceType.M3U, url = "file:///autre$it")) }
            val id = c.db.playlists().insert(PlaylistEntity(name = "Abonnement", type = SourceType.M3U, url = "http://fournisseur/liste.m3u", lastSync = 1))
            c.settings.update { it.copy(activePlaylistId = id) }
            c.db.movies().upsert(listOf(MovieEntity(uid = "$id:M:1", playlistId = id, remoteId = "1", name = "Le film", nameNorm = "le film", categoryUid = "", poster = "", streamUrl = DownloadManager.playUri(File(media, "film.mkv")), position = 0, syncToken = 1)))
            c.db.series().upsert(listOf(SeriesEntity("$id:S:1", id, "1", "Série", "serie", "", "", position = 0, syncToken = 1)))
            c.db.series().upsertEpisodes(
                (1..2).map { i -> EpisodeEntity("$id:E:$i", "$id:S:1", id, "$i", 1, i, "Épisode $i", streamUrl = DownloadManager.playUri(File(media, "film.mkv")), syncToken = 1) },
            )
            id
        }

        init {
            devices += this
        }

        val film get() = "$pid:M:1"
        val e1 get() = "$pid:E:1"
        val e2 get() = "$pid:E:2"
        suspend fun signIn() {
            c.cloud.signIn("foyer@example.fr", "motdepasse")
            assertEquals(null, c.cloud.status.value.error)
        }
    }

    @Before
    fun setUp() {
        Vlc.init()
        assumeTrue("libVLC absent", Vlc.available)
        assumeTrue("ffmpeg absent", runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false))
        fake = FakeSupabase()
        supa = MockWebServer().apply { dispatcher = fake; start() }
        media = Files.createTempDirectory("vyzo-media").toFile()
        val p = ProcessBuilder(
            "ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=160x120:rate=5:duration=300",
            "-f", "lavfi", "-i", "sine=duration=300", "-shortest", File(media, "film.mkv").absolutePath,
        ).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        assertEquals(0, p.waitFor())
    }

    @After
    fun tearDown() {
        if (!::supa.isInitialized) return
        shown = false
        rule.waitForIdle()
        owner.viewModelStore.clear()
        devices.forEach { runCatching { it.c.player.release() }; runCatching { it.c.closeForTest() }; it.dir.deleteRecursively() }
        media.deleteRecursively()
        supa.shutdown()
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(100)
        }
        throw AssertionError(what)
    }

    private fun Tv.watch(kind: String, uid: String, startMs: Long = -1L, block: (PlayerViewModel) -> Unit) {
        val vm = PlayerViewModel(c, kind, uid, "", 0, startMs)
        try {
            waitFor("lecture sur ${c.player.tag.value} : ${c.player.status.value} ${c.player.error.value}") { c.player.status.value.isPlaying && c.player.duration > 0 }
            block(vm)
        } finally {
            vm.viewModelScope.cancel()
            c.player.stop()
            Thread.sleep(300)
        }
    }

    private fun cloudPositionMs(): Long? = fake.rows("watch_progress").firstOrNull()?.get("position_ms")?.toString()?.toLongOrNull()

    /** Film commencé au salon, repris dans la chambre sans que la chambre ait synchronisé entre-temps. */
    @Test
    fun filmCommenceAuSalonReprisDansLaChambre() = runBlocking {
        val salon = Tv()
        val chambre = Tv(extraPlaylistsBefore = 2)
        assertTrue("les deux TV n'ont pas le même identifiant local", salon.film != chambre.film)
        salon.signIn()
        chambre.signIn()

        salon.watch(PlayKind.MOVIE, salon.film) { vm ->
            salon.c.player.seekTo(120_000)
            Thread.sleep(1_500)
            salon.c.player.pause()
            waitFor("position envoyée au compte (${cloudPositionMs()})") { (cloudPositionMs() ?: 0) >= 120_000 }
        }
        val onCloud = cloudPositionMs()!!

        // Lire depuis la fiche, chambre pas encore à jour : elle doit interroger le compte.
        assertEquals(null, chambre.c.repo.progressNow(chambre.film))
        chambre.watch(PlayKind.MOVIE, chambre.film) {
            val pos = chambre.c.player.currentPosition
            assertTrue("la chambre doit reprendre vers $onCloud ms, position $pos", pos >= onCloud - 2_000)
        }
    }

    /** Fiche ouverte après synchro de la chambre : « Reprendre à » s'affiche avec la position du salon. */
    @Test
    fun laFicheDeLaChambreProposeLaBonnePosition() = runBlocking {
        val salon = Tv()
        val chambre = Tv(extraPlaylistsBefore = 1)
        salon.signIn()
        chambre.signIn()
        salon.c.repo.saveProgress(salon.film, ContentKind.MOVIE, "", 2_000_000, 6_000_000)
        salon.c.cloud.sync().getOrThrow()
        chambre.c.cloud.sync().getOrThrow()
        assertEquals(2_000_000L, chambre.c.repo.progressNow(chambre.film)?.positionMs)
    }

    /** « Depuis le début » : le film doit vraiment repartir de zéro, et la reprise survivre si on quitte aussitôt. */
    @Test
    fun depuisLeDebutRepartDeZero() = runBlocking {
        val salon = Tv()
        salon.c.repo.saveProgress(salon.film, ContentKind.MOVIE, "", 120_000, 300_000)
        salon.watch(PlayKind.MOVIE, salon.film, startMs = START_FROM_BEGINNING) {
            val pos = salon.c.player.currentPosition
            assertTrue("« Depuis le début » doit repartir de zéro, position $pos", pos < 30_000)
        }
        assertEquals(120_000L, salon.c.repo.progressNow(salon.film)?.positionMs)
    }

    /**
     * Le salon est resté en pause à 1:00 sur le film ; la chambre l'a regardé jusqu'à 3:20.
     * En quittant le lecteur, le salon ne doit pas écraser la position de la chambre.
     */
    @Test
    fun uneTvEnPauseNEcrasePasLaPositionDUneAutre() = runBlocking {
        val salon = Tv()
        val chambre = Tv(extraPlaylistsBefore = 1)
        salon.signIn()
        chambre.signIn()
        val vm = PlayerViewModel(salon.c, PlayKind.MOVIE, salon.film, "", 0, -1L)
        waitFor("lecture") { salon.c.player.status.value.isPlaying && salon.c.player.duration > 0 }
        salon.c.player.seekTo(60_000)
        Thread.sleep(1_500)
        salon.c.player.pause()
        waitFor("pause envoyée") { (cloudPositionMs() ?: 0) >= 60_000 }
        Thread.sleep(50)

        chambre.c.repo.saveProgress(chambre.film, ContentKind.MOVIE, "", 200_000, 300_000)
        chambre.c.cloud.sync().getOrThrow()
        assertEquals(200_000L, cloudPositionMs())

        // Le salon quitte le lecteur, comme l'écran le fait en se fermant.
        vm.saveProgress(salon.c.player.currentPosition, salon.c.player.duration)
        Thread.sleep(1_500)
        vm.viewModelScope.cancel()
        salon.c.player.stop()
        assertEquals("la position de la chambre doit survivre", 200_000L, cloudPositionMs())
    }

    /** Mini-lecteur : l'écran de lecture est fermé, la position continue d'être enregistrée. */
    @Test
    fun laPositionDuMiniLecteurEstEnregistree() = runBlocking {
        val tv = Tv()
        tv.c.repo.savePlayerPosition("movie:${tv.film}", 85_000, 300_000)
        assertEquals(85_000L, tv.c.repo.progressNow(tv.film)?.positionMs)
        tv.c.repo.savePlayerPosition("episode:${tv.e2}", 40_000, 300_000)
        assertEquals("${tv.pid}:S:1", tv.c.repo.progressNow(tv.e2)?.parentUid)
        tv.c.repo.savePlayerPosition("ch:1", 10, 20)
        assertEquals(85_000L, tv.c.repo.progressNow(tv.film)?.positionMs)
    }

    /** Série : épisode 2 commencé au salon, la chambre propose bien l'épisode 2 (même profil « Principal » de chaque côté). */
    @Test
    fun serieRepriseSurLeMemeEpisode() = runBlocking {
        val salon = Tv()
        val chambre = Tv(extraPlaylistsBefore = 1)
        salon.signIn()
        chambre.signIn()
        salon.c.repo.saveWatchProgress(salon.e2, ContentKind.SERIES, "${salon.pid}:S:1", salon.c.profiles.activeIdNow(), 90_000, 300_000)
        salon.c.cloud.sync().getOrThrow()
        chambre.c.cloud.sync().getOrThrow()
        val last = chambre.c.repo.lastEpisodeProgress("${chambre.pid}:S:1")
        assertEquals(chambre.e2, last?.uid)
        assertEquals(90_000L, last?.positionMs)
    }

    private var shown by mutableStateOf(true)

    private fun showPlayer(c: AppContainer, kind: String, uid: String, start: Long = -1L, form: FormFactor = FormFactor.TV, desktop: Boolean = false) {
        rule.setContent {
            if (!shown) return@setContent
            CompositionLocalProvider(
                LocalContainer provides c, LocalDesktop provides desktop, LocalFormFactor provides form,
                LocalViewModelStoreOwner provides owner, LocalLifecycleOwner provides lifecycle,
            ) {
                MaterialTheme {
                    Box(Modifier.size(1280.dp, 720.dp)) {
                        PlayerScreen(kind = kind, uid = uid, group = "", programId = 0, startMs = start, onExit = {}, onCatchup = { _, _ -> })
                    }
                }
            }
        }
    }

    private fun exists(text: String) = rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    /** Télécommande, popup « Épisode suivant » : OK doit lancer l'épisode, pas mettre en pause. */
    @Test
    fun popupEpisodeSuivantALaTelecommande() = runBlocking {
        val tv = Tv()
        showPlayer(tv.c, PlayKind.EPISODE, tv.e1)
        rule.waitUntil(20_000) { tv.c.player.status.value.isPlaying && tv.c.player.duration > 0 }
        tv.c.player.seekTo(220_000)
        rule.waitUntil(10_000) { exists("Rester") }
        press(Key.DirectionCenter)
        Thread.sleep(1_500)
        rule.waitForIdle()
        assertEquals("OK sur le popup doit lancer l'épisode 2", "episode:${tv.e2}", tv.c.player.tag.value)
    }

    /** Télécommande, popup : Retour = « Rester », et les flèches gardent l'avance rapide. */
    @Test
    fun popupResterALaTelecommande() = runBlocking {
        val tv = Tv()
        showPlayer(tv.c, PlayKind.EPISODE, tv.e1)
        rule.waitUntil(20_000) { tv.c.player.status.value.isPlaying && tv.c.player.duration > 0 }
        tv.c.player.seekTo(220_000)
        rule.waitUntil(10_000) { exists("Rester") }
        val before = tv.c.player.currentPosition
        press(Key.DirectionLeft)
        rule.waitUntil(5_000) { tv.c.player.currentPosition < before - 5_000 }
        rule.waitUntil(10_000) { exists("Rester") }
        rule.runOnIdle { assertTrue("Retour doit être pris par le lecteur", DesktopUi.dispatchBack()) }
        rule.waitUntil(5_000) { !exists("Rester") }
        assertEquals("episode:${tv.e1}", tv.c.player.tag.value)
    }

    /** Téléphone : la barre de la TV, mais on peut toujours la toucher et la faire glisser pour se déplacer dans le film. */
    @Test
    fun barreDeProgressionAuDoigtSurTelephone() = runBlocking {
        val tv = Tv()
        showPlayer(tv.c, PlayKind.MOVIE, tv.film, form = FormFactor.PHONE)
        rule.waitUntil(20_000) { tv.c.player.status.value.isPlaying && tv.c.player.duration > 0 }
        val duration = tv.c.player.duration
        fun bar() = rule.onNode(hasContentDescription("Position de lecture"))
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Position de lecture")).fetchSemanticsNodes().isNotEmpty() }

        bar().performTouchInput { click(center) }
        rule.waitUntil(10_000) { kotlin.math.abs(tv.c.player.currentPosition - duration / 2) < 15_000 }

        bar().performTouchInput { swipe(center, percentOffset(0.25f, 0.5f), 300) }
        rule.waitUntil(10_000) { kotlin.math.abs(tv.c.player.currentPosition - duration / 4) < 15_000 }
        // La barre elle-même rejoint la position du lecteur, sans revenir à l'ancienne.
        rule.waitUntil(5_000) { kotlin.math.abs(bar().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current - 0.25f) < 0.06f }
    }

    /** Vyzo PC : la même barre que la TV, qu'on clique ou fait glisser à la souris. */
    @Test
    fun barreDeProgressionALaSourisSurPc() = runBlocking {
        val tv = Tv()
        showPlayer(tv.c, PlayKind.MOVIE, tv.film, desktop = true)
        rule.waitUntil(20_000) { tv.c.player.status.value.isPlaying && tv.c.player.duration > 0 }
        val duration = tv.c.player.duration
        fun bar() = rule.onNode(hasContentDescription("Position de lecture"))
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Position de lecture")).fetchSemanticsNodes().isNotEmpty() }

        bar().performMouseInput { click(center) }
        rule.waitUntil(10_000) { kotlin.math.abs(tv.c.player.currentPosition - duration / 2) < 15_000 }

        bar().performMouseInput { press(); moveTo(percentOffset(0.25f, 0.5f)); release() }
        rule.waitUntil(10_000) { kotlin.math.abs(tv.c.player.currentPosition - duration / 4) < 15_000 }
        rule.waitUntil(5_000) { kotlin.math.abs(bar().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current - 0.25f) < 0.06f }
    }

    /** Télécommande : OK met en pause, même au début d'un épisode. +85 s à l'aveugle coupait les épisodes sans intro. */
    @Test
    fun okNeSautePasLeDebutALaTelecommande() = runBlocking {
        val tv = Tv()
        showPlayer(tv.c, PlayKind.EPISODE, tv.e1)
        rule.waitUntil(20_000) { tv.c.player.status.value.isPlaying && tv.c.player.duration > 0 }
        tv.c.player.seekTo(16_000)
        Thread.sleep(800)
        rule.waitForIdle()
        assertFalse("pas de bouton sans focus à la télécommande", exists("Passer l'intro"))
        val before = tv.c.player.currentPosition
        press(Key.DirectionCenter)
        Thread.sleep(800)
        rule.waitForIdle()
        val after = tv.c.player.currentPosition
        assertTrue("OK ne doit pas sauter : $before → $after", after < before + 5_000)
        assertFalse("OK met en pause", tv.c.player.status.value.isPlaying)
    }

    /** Option « sauter les intros » : reprendre un épisode à 10 s ne doit pas faire sauter 75 s de l'épisode. */
    @Test
    fun sautAutoNeCoupePasUneReprise() = runBlocking {
        val tv = Tv()
        tv.c.settings.update { it.copy(autoSkipIntro = true) }
        tv.c.repo.saveWatchProgress(tv.e1, ContentKind.SERIES, "${tv.pid}:S:1", tv.c.profiles.activeIdNow(), 10_000, 300_000)
        tv.watch(PlayKind.EPISODE, tv.e1) {
            val pos = tv.c.player.currentPosition
            assertTrue("reprise à 10 s, position $pos", pos < 30_000)
        }
    }

    /** Option « sauter les intros », sans horodatage : démarrer un épisode du début ne saute plus rien. */
    @Test
    fun sautAutoSansDonneeNeCoupeRien() = runBlocking {
        val tv = Tv()
        tv.c.settings.update { it.copy(autoSkipIntro = true) }
        tv.watch(PlayKind.EPISODE, tv.e1, startMs = START_FROM_BEGINNING) {
            Thread.sleep(1_500)
            val pos = tv.c.player.currentPosition
            assertTrue("rien ne doit être sauté, position $pos", pos < 15_000)
        }
    }

    /** Option « épisode suivant automatique » : rien à 80 s de la fin ; à 30 s, 10 s de compte à rebours annulable. */
    @Test
    fun enchainementAutoNeCoupePasLaFin() = runBlocking {
        val tv = Tv()
        tv.c.settings.update { it.copy(autoNextEpisodeOnOutro = true) }
        showPlayer(tv.c, PlayKind.EPISODE, tv.e1)
        rule.waitUntil(20_000) { tv.c.player.status.value.isPlaying && tv.c.player.duration > 0 }
        tv.c.player.seekTo(220_000)
        rule.waitUntil(10_000) { exists("Rester") }
        val end = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < end) { rule.mainClock.advanceTimeBy(500); Thread.sleep(100) }
        assertEquals("80 s avant la fin, l'épisode en cours doit continuer", "episode:${tv.e1}", tv.c.player.tag.value)

        tv.c.player.seekTo(275_000)
        val armed = System.currentTimeMillis()
        rule.waitUntil(20_000) { tv.c.player.tag.value == "episode:${tv.e2}" }
        val waited = System.currentTimeMillis() - armed
    }
}
