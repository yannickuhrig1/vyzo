package fr.yannickuhrig.vyzo.desktop

import androidx.lifecycle.viewModelScope
import fr.yannickuhrig.vyzo.closeForTest
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.EpisodeEntity
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.db.WatchProgressEntity
import fr.yannickuhrig.vyzo.data.download.DownloadManager
import fr.yannickuhrig.vyzo.data.repo.StreamSpec
import fr.yannickuhrig.vyzo.player.PlaybackState
import fr.yannickuhrig.vyzo.player.TrackType
import fr.yannickuhrig.vyzo.ui.common.PlayKind
import fr.yannickuhrig.vyzo.ui.player.PlayerViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Lecture réelle par libVLC de courtes vidéos générées par ffmpeg : fichiers téléchargés et fin d'épisode. */
class DesktopPlaybackTest {
    private lateinit var dir: File
    private lateinit var c: AppContainer

    @Before
    fun setUp() {
        Vlc.init()
        assumeTrue("libVLC absent", Vlc.available)
        assumeTrue("ffmpeg absent", runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false))
        dir = Files.createTempDirectory("vyzo-lecture").toFile()
        c = testContainer(dir)
    }

    @After
    fun tearDown() {
        if (!::c.isInitialized) return
        c.player.release()
        c.closeForTest()
        dir.deleteRecursively()
    }

    private fun clip(name: String, seconds: Int = 2): File = File(dir, name).also {
        val p = ProcessBuilder(
            "ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=160x120:rate=25:duration=$seconds",
            "-f", "lavfi", "-i", "sine=duration=$seconds", "-shortest", it.absolutePath,
        ).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        assertEquals(0, p.waitFor())
    }

    private fun state() = "état ${c.player.status.value}, étiquette ${c.player.tag.value}, erreur ${c.player.error.value}"

    private fun waitUntil(timeoutMs: Long = 20_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(100)
        }
        return cond()
    }

    @Test
    fun fichierTelechargeLuEnFileUri() {
        val f = clip("telecharge.mkv")
        val uri = DownloadManager.playUri(f)
        assertTrue(uri, uri.startsWith("file:///"))
        c.player.play(StreamSpec(url = uri, userAgent = "", isLive = false), 0, "t")
        assertTrue("le fichier local doit être lu jusqu'au bout : ${state()}", waitUntil { c.player.status.value.state == PlaybackState.ENDED })
        assertNull(c.player.error.value)
    }

    private suspend fun twoEpisodes(seconds: Int = 2): Pair<String, String> {
        val pid = c.db.playlists().insert(PlaylistEntity(name = "Locale", type = SourceType.M3U, url = "file:///dev/null"))
        c.db.series().upsert(listOf(SeriesEntity("s1", pid, "s1", "Série", "serie", "", "", position = 0, syncToken = 1)))
        val eps = listOf(clip("e1.mkv", seconds), clip("e2.mkv", seconds)).mapIndexed { i, f ->
            EpisodeEntity("e${i + 1}", "s1", pid, "e${i + 1}", 1, i + 1, "Épisode ${i + 1}", streamUrl = DownloadManager.playUri(f), syncToken = 1)
        }
        c.db.series().upsertEpisodes(eps)
        return "episode:e1" to "episode:e2"
    }

    @Test
    fun episodeSuivantEnchaineALaFin() = runBlocking {
        val (first, second) = twoEpisodes()
        val vm = PlayerViewModel(c, PlayKind.EPISODE, "e1", "", 0, 0)
        try {
            assertTrue(state(), waitUntil { c.player.tag.value == first })
            assertTrue("l'épisode 2 doit démarrer seul à la fin de l'épisode 1", waitUntil { c.player.tag.value == second })
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun resterNEnchainePas() = runBlocking {
        val (first, _) = twoEpisodes()
        val vm = PlayerViewModel(c, PlayKind.EPISODE, "e1", "", 0, 0)
        try {
            assertTrue(state(), waitUntil { c.player.tag.value == first })
            vm.stayAtEpisodeEnd = true
            assertTrue(state(), waitUntil { c.player.status.value.state == PlaybackState.ENDED })
            Thread.sleep(1_500)
            assertEquals(first, c.player.tag.value)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun episodeDejaVuRepartDuDebut() = runBlocking {
        val (first, _) = twoEpisodes(seconds = 6)
        c.db.progress().upsert(WatchProgressEntity(c.profiles.activeIdNow(), "e1", "e1", ContentKind.SERIES, "s1", positionMs = 6_000, durationMs = 6_000))
        val vm = PlayerViewModel(c, PlayKind.EPISODE, "e1", "", 0, 0)
        try {
            assertTrue(state(), waitUntil { c.player.tag.value == first && c.player.status.value.isPlaying })
            Thread.sleep(1_500)
            assertEquals("un épisode vu jusqu'au bout doit repartir du début, pas enchaîner le suivant", first, c.player.tag.value)
            assertTrue("position ${c.player.currentPosition}", c.player.currentPosition < 4_000)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun sousTitresExternesAffichesUneSeuleFois() = runBlocking {
        val (first, _) = twoEpisodes()
        val vm = PlayerViewModel(c, PlayKind.EPISODE, "e1", "", 0, 0)
        try {
            vm.stayAtEpisodeEnd = true
            assertTrue(state(), waitUntil { c.player.tag.value == first && c.player.status.value.state == PlaybackState.READY })
            vm.loadExternalSubtitles("test.srt", "1\n00:00:00,000 --> 00:00:05,000\nBonjour\n")
            Thread.sleep(300)
            assertEquals(1, vm.externalSubtitles.value.size)
            // Rendu par le calque de l'écran seulement : VLC ne doit pas avoir chargé sa propre piste.
            assertTrue(c.player.tracks(TrackType.TEXT).isEmpty())
            assertFalse(c.player.textEnabled())
        } finally {
            vm.viewModelScope.cancel()
        }
    }
}
