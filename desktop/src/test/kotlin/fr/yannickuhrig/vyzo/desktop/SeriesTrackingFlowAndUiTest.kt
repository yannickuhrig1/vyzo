package fr.yannickuhrig.vyzo.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.closeForTest
import fr.yannickuhrig.vyzo.data.db.CategoryEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.EpisodeEntity
import fr.yannickuhrig.vyzo.data.db.FAVORITES_GROUP_ID
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import fr.yannickuhrig.vyzo.data.db.SeriesScheduleEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.intro.TvMazeClient
import fr.yannickuhrig.vyzo.data.vod.SeriesTracking
import fr.yannickuhrig.vyzo.ui.common.FormFactor
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.LocalDesktop
import fr.yannickuhrig.vyzo.ui.common.LocalFormFactor
import fr.yannickuhrig.vyzo.ui.profile.ProfileAvatar
import fr.yannickuhrig.vyzo.ui.profile.ProfileAvatars
import fr.yannickuhrig.vyzo.ui.profile.ProfilePalette
import fr.yannickuhrig.vyzo.ui.theme.VyzoTheme
import fr.yannickuhrig.vyzo.ui.vod.PosterCard
import fr.yannickuhrig.vyzo.ui.vod.SeriesDetailScreen
import fr.yannickuhrig.vyzo.ui.vod.VodCard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Calendar

@OptIn(ExperimentalTestApi::class)
class SeriesTrackingFlowAndUiTest {

    @get:Rule
    val rule = sqliteThenComposeRule()

    private val containers = mutableListOf<AppContainer>()
    private val dirs = mutableListOf<File>()
    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() {
        containers.forEach { it.closeForTest() }
        servers.forEach { it.shutdown() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun newContainer(): AppContainer {
        val dir = Files.createTempDirectory("vyzo-track").toFile().also { dirs += it }
        return testContainer(dir).also { containers += it }
    }

    @Test
    fun `les 16 avatars vectoriels et la palette 12 couleurs se composent sans erreur`() {
        assertEquals(16, ProfileAvatars.all.size)
        assertEquals(12, ProfilePalette.size)
        rule.setContent {
            VyzoTheme {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileAvatars.all.forEachIndexed { idx, av ->
                        ProfileAvatar("U$idx", idx, size = 32.dp, avatar = av.id)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `posterCard affiche le badge nouvel episode et la date a venir`() {
        rule.setContent {
            CompositionLocalProvider(LocalDesktop provides true, LocalFormFactor provides FormFactor.TV) {
                VyzoTheme {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PosterCard(VodCard(uid = "s1", title = "Severance", badge = "+2 à voir"), onClick = {}, Modifier.width(170.dp))
                        PosterCard(VodCard(uid = "s2", title = "Silo", badge = "S3E1 · Demain", badgeUpcoming = true), onClick = {}, Modifier.width(170.dp))
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("+2 à voir", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("S3E1 · Demain", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `le suivi d'episodes enregistre les alertes les expose et les nettoie au visionnage`() = runBlocking {
        val c = newContainer()
        c.profiles.ensureOne()
        val pid = c.db.playlists().insert(PlaylistEntity(name = "Test", type = SourceType.XTREAM, url = "http://localhost"))
        c.settings.update { it.copy(activePlaylistId = pid) }
        c.db.categories().upsert(listOf(CategoryEntity("$pid:S:cat", pid, ContentKind.SERIES, "cat", "Drame", 0, 1L)))
        val s = SeriesEntity("$pid:S:100", pid, "100", "Severance", "severance", "$pid:S:cat", "", position = 0, syncToken = 1L)
        c.db.series().upsert(listOf(s))
        val ep1 = EpisodeEntity("${s.uid}:1", s.uid, pid, "1", 2, 1, "S2E1", durationSecs = 3000, syncToken = 1L)
        val ep2 = EpisodeEntity("${s.uid}:2", s.uid, pid, "2", 2, 2, "S2E2", durationSecs = 3000, syncToken = 1L)
        val ep3 = EpisodeEntity("${s.uid}:3", s.uid, pid, "3", 2, 3, "S2E3", durationSecs = 3000, syncToken = 1L)
        c.db.series().upsertEpisodes(listOf(ep1, ep2))
        c.repo.setInGroup(FAVORITES_GROUP_ID, s.uid, ContentKind.SERIES, inGroup = true)

        val fresh = SeriesTracking.recordNewEpisodesIfChanged(c.db, s, listOf(ep1, ep2), listOf(ep1, ep2, ep3), now = System.currentTimeMillis())
        c.db.series().upsertEpisodes(listOf(ep1, ep2, ep3))
        assertEquals(listOf(ep3), fresh)

        val rows = withTimeout(5_000) { c.repo.newEpisodesSeries().first { it.isNotEmpty() } }
        assertEquals(s.uid, rows.first().first.uid)
        assertEquals(1, rows.first().second.newCount)
        assertEquals("1 nouveau", rows.first().second.badge)

        c.repo.saveProgress(ep3.uid, ContentKind.SERIES, s.uid, 3_000_000L, 3_000_000L)
        val cleared = withTimeout(5_000) { c.repo.newEpisodesSeries().first { list -> list.none { it.first.uid == s.uid } } }
        assertTrue(cleared.isEmpty())
    }

    @Test
    fun `tvMazeClient recupere le prochain episode sans cle api`() = runBlocking {
        val server = MockWebServer().apply { start() }.also { servers += it }
        server.enqueue(
            MockResponse().setBody(
                """{
                  "id": 42,
                  "name": "Dark",
                  "premiered": "2017-12-01",
                  "status": "Running",
                  "_embedded": {"nextepisode": {"season": 3, "number": 1, "name": "Déjà-vu", "airdate": "2026-12-01"}}
                }""",
            ),
        )
        val client = TvMazeClient(server.url("/").toString())
        val sched = client.fetchSchedule("1:S:9", "FR - Dark (2017)")
        assertNotNull(sched)
        assertEquals("Returning Series", sched!!.status)
        assertEquals(3, sched.nextSeason)
        assertEquals(1, sched.nextEpisode)
        assertEquals("Déjà-vu", sched.nextTitle)
        assertEquals("2026-12-01", sched.nextAirDate)
    }

    @Test
    fun `la fiche serie affiche les nouveaux episodes et la date du prochain`() {
        val c = newContainer()
        val tomorrowIso = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }.let {
            "%04d-%02d-%02d".format(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH))
        }
        val uid: String
        runBlocking {
            c.profiles.ensureOne()
            val pid = c.db.playlists().insert(PlaylistEntity(name = "Test", type = SourceType.M3U, url = "http://localhost"))
            c.settings.update { it.copy(activePlaylistId = pid) }
            val s = SeriesEntity("$pid:S:55", pid, "55", "The Last of Us", "the last of us", "", "", position = 0, syncToken = 1L)
            uid = s.uid
            c.db.series().upsert(listOf(s))
            val ep1 = EpisodeEntity("${s.uid}:1", s.uid, pid, "1", 2, 1, "Jours à venir", durationSecs = 3200, syncToken = 1L)
            val ep2 = EpisodeEntity("${s.uid}:2", s.uid, pid, "2", 2, 2, "Épisode récent", durationSecs = 3200, syncToken = 1L)
            c.db.series().upsertEpisodes(listOf(ep1, ep2))
            SeriesTracking.recordNewEpisodesIfChanged(c.db, s, listOf(ep1), listOf(ep1, ep2), now = System.currentTimeMillis())
            c.db.seriesTracking().upsertSchedule(
                SeriesScheduleEntity(
                    seriesUid = s.uid,
                    status = "Returning Series",
                    nextSeason = 2,
                    nextEpisode = 3,
                    nextTitle = "La suite",
                    nextAirDate = tomorrowIso,
                    upcomingSeason = 0,
                    upcomingSeasonAirDate = "",
                    upcomingSeasonEpisodes = 0,
                    tmdbId = 100,
                    checkedAt = System.currentTimeMillis(),
                ),
            )
        }
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        try {
            rule.setContent {
                CompositionLocalProvider(
                    LocalContainer provides c,
                    LocalViewModelStoreOwner provides owner,
                    LocalDesktop provides true,
                    LocalFormFactor provides FormFactor.PHONE,
                ) {
                    VyzoTheme {
                        Surface {
                            SeriesDetailScreen(uid = uid, onPlayEpisode = { _, _ -> })
                        }
                    }
                }
            }
            rule.waitUntil(5_000) {
                rule.onAllNodes(hasText("1 nouvel épisode à voir"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    rule.onAllNodes(hasText("Prochain épisode · S2 E3 « La suite » · Demain"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    rule.onAllNodes(hasText("NOUVEAU"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            owner.viewModelStore.clear()
        }
    }
}
