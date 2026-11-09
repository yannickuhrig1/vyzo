package fr.yannickuhrig.vyzo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.data.source.stalker.StalkerClient
import fr.yannickuhrig.vyzo.diag.DiagLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Faux portail Stalker / Ministra : poignée de main, profil, genres, chaînes, guide, VOD page par page,
 * liens de lecture et de replay, fin d'abonnement. Vérifie aussi que l'appli se reconnecte quand le jeton expire
 * et que le journal de diagnostic ne contient ni l'adresse MAC ni le jeton.
 */
@RunWith(AndroidJUnit4::class)
class StalkerPortalTest {
    private lateinit var server: MockWebServer
    private val app get() = ApplicationProvider.getApplicationContext<VyzoApp>()
    private val portal = FakePortal()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = portal
        server.start()
    }

    @After
    fun tearDown() {
        DiagLog.enabled = false
        DiagLog.clear()
        server.shutdown()
        app.container.closeForTest()
    }

    @Test
    fun fullPortalSyncPlaybackCatchupAndVod() = runBlocking {
        val c = app.container
        // Le démarrage de l'appli applique le réglage du journal en arrière-plan : on l'active par le réglage.
        c.settings.update { it.copy(diagnosticLog = true) }
        while (!DiagLog.enabled) kotlinx.coroutines.delay(20)
        val id = c.repo.addPlaylist(
            PlaylistEntity(name = "Portail", type = SourceType.STALKER, url = server.url("/c/").toString(), mac = "00:1a:79:12:34:56"),
        )
        val res = c.sync.syncPlaylist(id)
        assertTrue(res.exceptionOrNull()?.toString() ?: "", res.isSuccess)

        // Poignée de main puis profil, avec l'adresse MAC dans le cookie et le jeton ensuite.
        assertEquals(listOf("stb/handshake", "stb/get_profile"), portal.actions.take(2))
        val itvCall = portal.requests.first { it.requestUrl?.queryParameter("type") == "itv" }
        assertEquals("Bearer ${FakePortal.TOKEN}", itvCall.getHeader("Authorization"))
        assertTrue(itvCall.getHeader("Cookie")!!.contains("mac=00%3A1A%3A79%3A12%3A34%3A56"))

        // Chaînes et catégories (le genre « * » est ignoré, le genre adulte est détecté).
        assertEquals(3, c.db.channels().count(id))
        val groups = c.repo.liveCategoryRows().first()
        assertEquals(listOf("Généralistes", "Adultes XXX"), groups.map { it.name })
        assertTrue(groups.first { it.name == "Adultes XXX" }.adult)
        val tf1 = c.db.channels().get("$id:L:101")!!
        assertEquals(1, tf1.number)
        assertEquals("${server.url("/").toString().trimEnd('/')}/stalker_portal/misc/logos/320/tf1.png", tf1.logo)
        assertEquals(3, tf1.catchupDays)

        // Guide fourni par le portail, associé à la chaîne.
        val now = System.currentTimeMillis()
        val current = c.repo.nowPrograms(listOf(tf1.epgKey), now)[tf1.epgKey]
        assertNotNull("programme en cours", current)
        assertEquals("Journal", current!!.title)
        assertEquals("9001", current.remoteId)

        // Lecture : la commande est transformée en lien (et le préfixe « ffmpeg » retiré).
        val live = c.repo.resolveChannel(tf1)
        assertEquals("http://flux.exemple/live/101.ts?play_token=PT", live.url)
        assertTrue(live.isLive)

        // Replay du programme en cours (« revoir depuis le début »).
        assertTrue(c.repo.canCatchup(tf1, current, now))
        val catchup = c.repo.resolveCatchup(tf1, current)!!
        assertEquals("http://flux.exemple/archive/9001.ts", catchup.url)
        assertEquals("auto /media/9001.mpg", portal.lastCmd("tv_archive"))

        // Jeton expiré : le portail répond « Authorization failed », l'appli refait la poignée de main.
        val handshakes = portal.handshakes.get()
        portal.failNextCall = true
        assertEquals("http://flux.exemple/live/101.ts?play_token=PT", c.repo.resolveChannel(tf1).url)
        assertEquals(handshakes + 1, portal.handshakes.get())

        // VOD chargée page par page : un film, une série (rangée dans une catégorie « séries » virtuelle).
        val movieCat = c.repo.movieCategories().first().first { it.name == "Films" }
        assertTrue("il reste une page", c.repo.loadMoreVod(movieCat.uid))
        assertEquals(listOf("Film A"), c.db.movies().byCategory(movieCat.uid, false).first().map { it.name })
        assertEquals(1, c.db.series().count(id))
        assertFalse("dernière page", c.repo.loadMoreVod(movieCat.uid))
        assertEquals(listOf("Film A", "Film C"), c.db.movies().byCategory(movieCat.uid, false).first().map { it.name })
        val film = c.db.movies().get("$id:M:501")!!
        assertEquals(95 * 60, film.durationSecs)
        assertEquals("http://flux.exemple/vod/501.mkv", c.repo.resolveMovie(film).url)

        // Fin d'abonnement lue dans les infos du compte.
        val checked = c.sync.checkAccount(c.db.playlists().get(id)!!)!!
        assertTrue(checked.expiresAt > 0)

        // Ni l'adresse MAC complète, ni le jeton, ni le numéro de série dans le journal.
        val log = DiagLog.dump()
        assertTrue("journal : " + log.take(3000), log.contains("stb/handshake"))
        assertFalse(log, log.contains("12:34:56", ignoreCase = true))
        assertFalse(log, log.contains("12%3A34%3A56", ignoreCase = true))
        assertFalse(log, log.contains(FakePortal.TOKEN))
    }

    @Test
    fun unreachablePortalGivesClearError() = runBlocking {
        val c = app.container
        portal.refuseHandshake = true
        val id = c.repo.addPlaylist(
            PlaylistEntity(name = "Portail", type = SourceType.STALKER, url = server.url("/c/").toString(), mac = "00:1A:79:00:00:01"),
        )
        val res = c.sync.syncPlaylist(id)
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message.orEmpty(), res.exceptionOrNull()?.message.orEmpty().contains("adresse MAC refusée"))
        // Tous les chemins habituels des portails ont été essayés.
        val paths = portal.requests.map { it.requestUrl!!.encodedPath }.distinct()
        assertTrue(paths.toString(), paths.containsAll(listOf("/portal.php", "/server/load.php", "/stalker_portal/server/load.php")))
        assertEquals(0, c.db.channels().count(id))
        assertEquals(emptyList<String>(), c.repo.channels(Repository.KEY_ALL).first().map { it.name })
    }

    @Test
    fun commandsAndDatesAreParsed() {
        assertEquals("http://a.b/c.ts", StalkerClient.stripCmd("ffmpeg http://a.b/c.ts"))
        assertEquals("http://a.b/c.ts", StalkerClient.stripCmd("ffrt http://a.b/c.ts"))
        assertEquals("http://a.b/c.ts", StalkerClient.stripCmd("auto  http://a.b/c.ts"))
        assertTrue(StalkerClient.isValidMac("00:1A:79:AB:cd:01"))
        assertFalse(StalkerClient.isValidMac("00:1A:79:AB:cd"))
        assertEquals("http://portail.tv", StalkerClient.normalize("portail.tv/c/"))
        assertTrue(fr.yannickuhrig.vyzo.data.sync.SyncManager.parseStalkerDate("2027-01-31 00:00:00") > 0)
        assertTrue(fr.yannickuhrig.vyzo.data.sync.SyncManager.parseStalkerDate("31.01.2027") > 0)
        assertEquals(0L, fr.yannickuhrig.vyzo.data.sync.SyncManager.parseStalkerDate("illimité"))
        // Masquage du journal de diagnostic.
        val redacted = DiagLog.redact(
            "GET http://p/portal.php?type=stb&sn=ABCDEF0123456&device_id=0123456789ABCDEF mac=00:1A:79:12:34:56 " +
                "{\"js\":{\"token\":\"ZYXWVUT98765\"}} Authorization: Bearer ZYXWVUT98765 http://s/live/bob/secret/1.ts",
        )
        assertFalse(redacted, redacted.contains("ABCDEF0123456"))
        assertFalse(redacted, redacted.contains("0123456789ABCDEF"))
        assertFalse(redacted, redacted.contains("12:34:56"))
        assertFalse(redacted, redacted.contains("ZYXWVUT98765"))
        assertFalse(redacted, redacted.contains("bob/secret"))
        assertTrue(redacted, redacted.contains("00:1A:79"))
    }
}

/** Portail Stalker minimal, conforme aux réponses des portails Ministra (JSON enveloppé dans « js »). */
private class FakePortal : Dispatcher() {
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val actions = CopyOnWriteArrayList<String>()
    val handshakes = AtomicInteger(0)
    private val cmds = CopyOnWriteArrayList<Pair<String, String>>()
    @Volatile var failNextCall = false
    @Volatile var refuseHandshake = false

    fun lastCmd(type: String): String? = cmds.lastOrNull { it.first == type }?.second

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
        if (url.encodedPath != "/portal.php") return MockResponse().setResponseCode(404)
        val type = url.queryParameter("type").orEmpty()
        val action = url.queryParameter("action").orEmpty()
        actions += "$type/$action"
        if (type == "stb" && action == "handshake") {
            if (refuseHandshake) return js("""{"token":""}""")
            handshakes.incrementAndGet()
            return js("""{"token":"$TOKEN","random":"abc"}""")
        }
        if (request.getHeader("Authorization") != "Bearer $TOKEN") return MockResponse().setBody("Authorization failed.")
        if (failNextCall && type != "stb") {
            failNextCall = false
            return MockResponse().setBody("Authorization failed.")
        }
        val now = System.currentTimeMillis() / 1000
        return when ("$type/$action") {
            "stb/get_profile" -> js("""{"id":"7","name":"MAG","status":1}""")
            "itv/get_genres" -> js("""[{"id":"*","title":"Tous"},{"id":"10","title":"Généralistes"},{"id":"20","title":"Adultes XXX"}]""")
            "itv/get_all_channels" -> js(
                """{"total_items":3,"data":[
                  {"id":"101","name":"TF1","number":"1","tv_genre_id":"10","cmd":"ffmpeg http://flux.exemple/ch/101","logo":"tf1.png","tv_archive":"1","tv_archive_duration":"72","xmltv_id":"TF1.fr"},
                  {"id":"102","name":"Arte","number":"7","tv_genre_id":"10","cmd":"ffmpeg http://flux.exemple/ch/102","logo":""},
                  {"id":"201","name":"Nuit chaude","number":"90","tv_genre_id":"20","cmd":"ffmpeg http://flux.exemple/ch/201"}
                ]}""",
            )
            "vod/get_categories" -> js("""[{"id":"*","title":"Tous"},{"id":"5","title":"Films"}]""")
            "series/get_categories" -> js("""[{"id":"8","title":"Séries"}]""")
            "itv/get_epg_info" -> js(
                """{"data":{"101":[
                  {"id":"9001","ch_id":"101","name":"Journal","descr":"L'info","start_timestamp":${now - 1200},"stop_timestamp":${now + 1800}},
                  {"id":"9002","ch_id":"101","name":"Météo","start_timestamp":${now + 1800},"stop_timestamp":${now + 2400}}
                ],"102":[
                  {"id":"9101","ch_id":"102","name":"Arte journal","start_timestamp":${now - 600},"stop_timestamp":${now + 600}}
                ]}}""",
            )
            "itv/create_link" -> {
                cmds += "itv" to url.queryParameter("cmd").orEmpty()
                js("""{"id":"101","cmd":"ffmpeg http://flux.exemple/live/101.ts?play_token=PT"}""")
            }
            "tv_archive/create_link" -> {
                cmds += "tv_archive" to url.queryParameter("cmd").orEmpty()
                js("""{"cmd":"ffmpeg http://flux.exemple/archive/9001.ts"}""")
            }
            "vod/create_link" -> {
                cmds += "vod" to url.queryParameter("cmd").orEmpty()
                js("""{"cmd":"ffmpeg http://flux.exemple/vod/501.mkv"}""")
            }
            "vod/get_ordered_list" -> when (url.queryParameter("p")) {
                "1" -> js(
                    """{"total_items":3,"max_page_items":2,"data":[
                      {"id":"501","name":"Film A","cmd":"/media/501.mpg","screenshot_uri":"/img/a.jpg","year":"2020","time":"95","rating_imdb":"7.1"},
                      {"id":"601","name":"Série B","is_series":"1","cmd":"/media/601.mpg","series":[1,2,3]}
                    ]}""",
                )
                else -> js("""{"total_items":3,"max_page_items":2,"data":[{"id":"502","name":"Film C","cmd":"/media/502.mpg","time":"110"}]}""")
            }
            "account_info/get_main_info" -> js("""{"mac":"00:1A:79:12:34:56","end_date":"2027-01-31 00:00:00","phone":""}""")
            else -> js("[]")
        }
    }

    private fun js(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"js":$body}""")

    companion object {
        const val TOKEN = "TOKEN0123456789ABCDEF"
    }
}
