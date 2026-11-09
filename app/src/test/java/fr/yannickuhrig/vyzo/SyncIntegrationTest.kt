package fr.yannickuhrig.vyzo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.yannickuhrig.vyzo.data.backup.BackupManager
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.FAVORITES_GROUP_ID
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.repo.Repository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncIntegrationTest {
    private lateinit var server: MockWebServer
    private val app get() = ApplicationProvider.getApplicationContext<VyzoApp>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = Fixtures.dispatcher(server)
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        app.container.closeForTest()
    }

    @Test
    fun m3uPlaylistWithGzipEpgCatchupAndVod() = runBlocking {
        val c = app.container
        val id = c.repo.addPlaylist(PlaylistEntity(name = "Test M3U", type = SourceType.M3U, url = server.url("/list.m3u").toString()))
        val res = c.sync.syncPlaylist(id)
        assertTrue(res.exceptionOrNull()?.toString() ?: "", res.isSuccess)

        assertEquals(4, c.db.channels().count(id))
        assertEquals(1, c.db.movies().count(id))
        assertEquals(1, c.db.series().count(id))

        val channels = c.repo.channels(Repository.KEY_ALL).first()
        assertEquals(listOf(1, 2, 7, 20), channels.map { it.number })
        val tf1 = channels.first { it.name == "TF1 HD" }
        val f2 = channels.first { it.name == "France 2 HD" }
        // Le guide est associé par tvg-id, et par nom normalisé quand le tvg-id manque.
        assertEquals("tf1.fr", tf1.epgKey)
        assertEquals("france2.fr", f2.epgKey)

        val now = System.currentTimeMillis()
        val current = c.repo.nowPrograms(channels.map { it.epgKey }, now)
        assertEquals(4, current.size)

        val past = c.repo.programs(tf1.epgKey, now - 4 * 3600_000L, now - 600_000L).first()
        assertTrue(c.repo.canCatchup(tf1, past, now))
        val spec = c.repo.resolveCatchup(tf1, past)!!
        assertTrue(spec.url, spec.url.contains("utc=${past.start / 1000}"))
        assertFalse(c.repo.canCatchup(f2, past, now))

        val sources = c.db.epg().sources()
        assertEquals(1, sources.size)
        assertTrue(sources[0].programCount > 30)
        assertEquals("", sources[0].lastError)

        val found = c.repo.search("france")
        assertEquals(1, found.channels.size)
        assertTrue(c.repo.search("Koh-Lanta").programs.isNotEmpty())
    }

    @Test
    fun xtreamPlaylistLiveVodSeriesAndBackup() = runBlocking {
        val c = app.container
        val base = server.url("/").toString().trimEnd('/')
        val id = c.repo.addPlaylist(PlaylistEntity(name = "Xtream", type = SourceType.XTREAM, url = base, username = "bob", password = "secret"))
        val res = c.sync.syncPlaylist(id)
        assertTrue(res.exceptionOrNull()?.toString() ?: "", res.isSuccess)

        val p = c.db.playlists().get(id)!!
        assertEquals("Europe/Paris", p.serverTimezone)
        assertEquals(2, p.maxConnections)
        assertEquals(10, c.db.channels().count(id))
        assertEquals(5, c.db.movies().count(id))
        assertEquals(2, c.db.series().count(id))

        val groups = c.repo.liveGroups().first()
        assertTrue(groups.any { it.title == "France | TNT" && it.count == 6 })
        assertTrue("catégorie orpheline", groups.any { it.title == "Sans catégorie" && it.count == 1 })

        val channels = c.repo.channels(Repository.KEY_ALL).first()
        val tf1 = channels.first { it.remoteId == "101" }
        val live = c.repo.resolveChannel(tf1)
        assertEquals("$base/live/bob/secret/101.ts", live.url)
        assertEquals(7, tf1.catchupDays)
        // "FR: Arte" a un epg_channel_id ; "France 2 HD" n'en a pas mais se rapproche du nom XMLTV.
        assertEquals("france2.fr", channels.first { it.remoteId == "102" }.epgKey)

        val now = System.currentTimeMillis()
        val past = c.repo.programs(tf1.epgKey, now - 4 * 3600_000L, now - 600_000L).first()
        val catchup = c.repo.resolveCatchup(tf1, past)!!
        assertTrue(catchup.url, catchup.url.startsWith("$base/timeshift/bob/secret/"))
        assertTrue(catchup.url.endsWith("/101.ts"))

        val movie = c.db.movies().get("$id:M:502")!!
        assertEquals(8.6f, movie.rating, 0.01f)
        c.repo.refreshMovieDetails(movie)
        val detailed = c.db.movies().get(movie.uid)!!
        assertEquals("Luc Besson", detailed.director)
        assertEquals(2 * 3600 + 48 * 60, detailed.durationSecs)
        assertEquals("$base/movie/bob/secret/502.mp4", c.repo.resolveMovie(detailed).url)

        val s = c.db.series().get("$id:S:701")!!
        c.repo.refreshEpisodes(s)
        val eps = c.db.series().episodesList(s.uid)
        assertEquals(3, eps.size)
        assertEquals(listOf(1, 1, 2), eps.map { it.season })
        assertEquals("$base/series/bob/secret/9001.mkv", c.repo.resolveEpisode(eps[0]).url)

        // Favoris + progression + sauvegarde aller-retour
        c.repo.toggleFavorite(tf1.uid, ContentKind.LIVE)
        c.repo.saveProgress(eps[0].uid, ContentKind.SERIES, s.uid, 60_000, 3_000_000)
        assertEquals(listOf(s.uid), c.repo.series(Repository.KEY_CONTINUE).first().map { it.uid })
        val json = BackupManager.export(c)
        assertTrue(json.contains("\"password\": \"secret\""))
        assertTrue(json.contains("\"app\": \"Vyzo\""))
        // Les sauvegardes faites quand l'appli s'appelait Zapette restent lisibles ; les autres non.
        assertEquals(1, BackupManager.inspect(json))
        assertEquals(1, BackupManager.inspect(json.replace("\"app\": \"Vyzo\"", "\"app\": \"Zapette\"")))
        assertEquals(null, BackupManager.inspect(json.replace("\"app\": \"Vyzo\"", "\"app\": \"Autre\"")))
        val restored = BackupManager.import(c, json)
        assertEquals(1, restored)
        val newId = c.db.playlists().all().single().id
        val favs = c.db.groups().allItems().filter { it.groupId == FAVORITES_GROUP_ID }
        assertEquals(listOf("$newId:L:101"), favs.map { it.itemUid })
        assertEquals(newId, c.settings.get().activePlaylistId)
        assertNotNull(c.db.playlists().get(newId))
        // La progression suit la playlist recréée (même compte Xtream → même clé de contenu).
        val epUid = "$newId:" + eps[0].uid.substringAfter(':')
        assertEquals(60_000L, c.repo.progressNow(epUid)?.positionMs)
    }

    @Test
    fun xtreamWrongPasswordFailsCleanly() = runBlocking {
        val c = app.container
        val id = c.repo.addPlaylist(PlaylistEntity(name = "X", type = SourceType.XTREAM, url = server.url("/").toString(), username = "bob", password = "nope"))
        val res = c.sync.syncPlaylist(id)
        assertTrue(res.isFailure)
        assertTrue(c.db.playlists().get(id)!!.lastError.isNotBlank())
        assertFalse(c.db.playlists().get(id)!!.lastError.contains("nope"))
    }
}
