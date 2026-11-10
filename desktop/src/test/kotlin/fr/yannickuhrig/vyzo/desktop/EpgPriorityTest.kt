package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.Fixtures
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.EpgMode
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class EpgPriorityTest {
    private val hour = 3_600_000L
    private val base = System.currentTimeMillis() / hour * hour

    private fun channel(uid: String, name: String, epgId: String = "") = ChannelEntity(
        uid = uid, playlistId = 1, remoteId = uid, name = name, nameNorm = name.lowercase(), matchKey = name.lowercase(), number = 1,
        logo = "", categoryUid = "c", epgId = epgId, epgKey = epgId.lowercase(), streamUrl = "", position = 0, syncToken = 1,
    )

    /** Guide XMLTV : une chaîne [id] appelée [name], et des programmes (heure de début, heure de fin, titre). */
    private fun guide(id: String, name: String, vararg shows: Triple<Int, Int, String>): String = buildString {
        append("<tv><channel id=\"$id\"><display-name>$name</display-name></channel>")
        shows.forEach { (from, to, title) ->
            append("<programme start=\"${Fixtures.xmlTime(base + from * hour)}\" stop=\"${Fixtures.xmlTime(base + to * hour)}\" channel=\"$id\"><title>$title</title></programme>")
        }
        append("</tv>")
    }

    private fun scenario(guides: Map<String, String>, channels: List<ChannelEntity>, block: suspend (AppContainer, (String) -> String) -> Unit) = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                guides[request.path!!.removePrefix("/")]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
        }
        server.start()
        try {
            val c = testContainer()
            c.start()
            val pid = c.db.playlists().insert(PlaylistEntity(name = "T", type = SourceType.M3U, url = "http://test.invalid/a.m3u"))
            c.db.channels().upsert(channels.map { it.copy(playlistId = pid) })
            block(c) { server.url("/$it").toString() }
        } finally {
            server.shutdown()
        }
    }

    private suspend fun titles(c: AppContainer, key: String) =
        c.db.programs().range(key, base - 24 * hour, base + 48 * hour).map { it.title }

    @Test
    fun theFirstSourceWinsAndOrderCanBeChanged() = scenario(
        mapOf("a" to guide("tf1.fr", "TF1", Triple(0, 1, "A")), "b" to guide("tf1.fr", "TF1", Triple(0, 1, "B"))),
        listOf(channel("c1", "TF1", "tf1.fr")),
    ) { c, url ->
        c.repo.addEpgSource("A", url("a"))
        val second = c.repo.addEpgSource("B", url("b"))
        c.sync.launchEpg().join()
        assertEquals(listOf("A"), titles(c, "tf1.fr"))

        c.repo.moveEpgSource(c.db.epg().sources().first { it.id == second }, -1)
        assertEquals(listOf("B", "A"), c.db.epg().sources().map { it.name })
        c.sync.launchEpg().join()
        assertEquals(listOf("B"), titles(c, "tf1.fr"))
    }

    @Test
    fun aGapSourceOnlyFillsFreeSlots() = scenario(
        mapOf(
            "a" to guide("tf1.fr", "TF1", Triple(0, 2, "A1")),
            "b" to guide("tf1.fr", "TF1", Triple(1, 3, "chevauche"), Triple(3, 4, "B1"), Triple(2, 3, "B0")),
        ),
        listOf(channel("c1", "TF1", "tf1.fr")),
    ) { c, url ->
        c.repo.addEpgSource("A", url("a"))
        c.repo.addEpgSource("B", url("b"), EpgMode.GAPS)
        c.sync.launchEpg().join()
        assertEquals(listOf("A1", "B0", "B1"), titles(c, "tf1.fr"))
    }

    @Test
    fun aGapSourceCanNameTheChannelDifferently() = scenario(
        mapOf(
            "a" to guide("tf1.fr", "TF1", Triple(0, 1, "A1")),
            "b" to guide("autre-id", "TF1", Triple(0, 1, "chevauche"), Triple(1, 2, "B1")),
        ),
        listOf(channel("c1", "TF1", "tf1.fr")),
    ) { c, url ->
        c.repo.addEpgSource("A", url("a"))
        c.repo.addEpgSource("B", url("b"), EpgMode.GAPS)
        c.sync.launchEpg().join()
        assertEquals(listOf("A1", "B1"), titles(c, "tf1.fr"))
        assertEquals(emptyList<String>(), titles(c, "autre-id"))
    }

    @Test
    fun aLowerSourceDoesNotStealAChannelMatchedByName() = scenario(
        mapOf("a" to guide("id-a", "Arte", Triple(0, 1, "A")), "b" to guide("id-b", "Arte", Triple(0, 1, "B"))),
        listOf(channel("c1", "Arte")),
    ) { c, url ->
        c.repo.addEpgSource("A", url("a"))
        c.repo.addEpgSource("B", url("b"))
        c.sync.launchEpg().join()
        assertEquals("id-a", c.db.channels().get("c1")?.epgKey)
        assertEquals(emptyList<String>(), titles(c, "id-b"))
    }

    @Test
    fun aChannelWithoutGuideStillGetsItFromALowerSource() = scenario(
        mapOf("a" to guide("tf1.fr", "TF1", Triple(0, 1, "A")), "b" to guide("arte.fr", "Arte", Triple(0, 1, "B"))),
        listOf(channel("c1", "TF1", "tf1.fr"), channel("c2", "Arte", "arte.fr")),
    ) { c, url ->
        c.repo.addEpgSource("A", url("a"))
        c.repo.addEpgSource("B", url("b"))
        c.sync.launchEpg().join()
        assertEquals(listOf("B"), titles(c, "arte.fr"))
    }
}
