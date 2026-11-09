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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
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
import fr.yannickuhrig.vyzo.data.db.EpisodeEntity
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.download.DownloadManager
import fr.yannickuhrig.vyzo.data.intro.EpisodeTimestamps
import fr.yannickuhrig.vyzo.data.intro.IntroDbClient
import fr.yannickuhrig.vyzo.data.intro.SeriesRef
import fr.yannickuhrig.vyzo.data.intro.TvMazeClient
import fr.yannickuhrig.vyzo.data.intro.tvMazeMatch
import fr.yannickuhrig.vyzo.data.intro.RawSegment
import fr.yannickuhrig.vyzo.data.intro.SkipKind
import fr.yannickuhrig.vyzo.data.intro.markersFor
import fr.yannickuhrig.vyzo.data.source.xtream.XtreamVodParser
import fr.yannickuhrig.vyzo.data.tmdb.exactSeriesMatch
import fr.yannickuhrig.vyzo.ui.common.FormFactor
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.LocalDesktop
import fr.yannickuhrig.vyzo.ui.common.LocalFormFactor
import fr.yannickuhrig.vyzo.ui.common.PlayKind
import fr.yannickuhrig.vyzo.ui.player.PlayerScreen
import fr.yannickuhrig.vyzo.ui.player.PlayerViewModel
import fr.yannickuhrig.vyzo.util.LenientJson
import fr.yannickuhrig.vyzo.util.asObj
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Tri des réponses de TheIntroDB. Les JSON viennent de la vraie API (novembre 2026). */
class IntroMarkersTest {
    private fun parse(json: String) = IntroDbClient.parse(LenientJson.parseToJsonElement(json).jsonObject)

    private val breakingBad = parse(
        """{"tvdb_id":81189,"type":"tv","season":1,"episode":1,"group_duration_ms":3500192,
        "intro":[{"start_ms":228627,"end_ms":246339,"confidence":0.70,"submission_count":19}],
        "credits":[{"start_ms":3431000,"end_ms":null,"confidence":0.25,"submission_count":2}]}""",
    )

    /** L'intro de 18 s arrive après 3 min 48 de cold open : +85 s au démarrage aurait coupé l'épisode. */
    @Test
    fun repereReelApresUnColdOpen() {
        val m = breakingBad.markersFor(3_500_000)
        assertEquals(228_627L, m.intros.single().startMs)
        assertEquals(246_339L, m.intros.single().endMs)
        assertTrue(m.intros.single().auto)
        assertNull("rien à passer au démarrage", m.introAt(0))
        assertNull(m.introAt(85_000))
        assertEquals(246_339L, m.introAt(230_000)?.endMs)
        assertNull("fin de l'intro : l'épisode reprend", m.introAt(246_339))
        assertEquals(3_431_000L, m.creditsStartMs)
    }

    @Test
    fun debutAbsentVeutDireDebutDeLEpisode() {
        val m = parse(
            """{"group_duration_ms":1364288,"intro":[{"start_ms":null,"end_ms":46000,"confidence":0.45,"submission_count":4}]}""",
        ).markersFor(1_364_288)
        assertEquals(0L, m.intros.single().startMs)
        assertEquals(46_000L, m.introAt(0)?.endMs)
    }

    /** PAL : même épisode 4 % plus court, tout est décalé. Ni saut ni bouton sur des repères faux. */
    @Test
    fun autreVersionDuFichierNeDonneRien() {
        val m = breakingBad.markersFor((3_500_192 * 0.96).toLong())
        assertTrue(m.intros.isEmpty())
        assertFalse(m.hasIntroData)
        assertNull(m.creditsStartMs)
    }

    /** Écart de 30 s : encore proposé, mais pas sauté sans action de l'utilisateur. */
    @Test
    fun petitEcartDeVersionPasDeSautAuto() {
        val intro = breakingBad.markersFor(3_530_000).intros.single()
        assertFalse(intro.auto)
    }

    @Test
    fun uneSeuleContributionNeSautePasToute() {
        val m = parse(
            """{"group_duration_ms":2652000,"intro":[{"start_ms":173000,"end_ms":181000,"confidence":0.1,"submission_count":1}]}""",
        ).markersFor(2_652_000)
        assertFalse(m.intros.single().auto)
        assertEquals(181_000L, m.introAt(175_000)?.endMs)
    }

    /** Beaucoup de contributions mais en désaccord (confiance 0) : pas de saut automatique. */
    @Test
    fun contributionsEnDesaccordPasDeSautAuto() {
        val m = parse(
            """{"group_duration_ms":2731000,"intro":[{"start_ms":105170,"end_ms":119329,"confidence":0,"submission_count":12}]}""",
        ).markersFor(2_731_000)
        assertFalse(m.intros.single().auto)
    }

    @Test
    fun dureeDeVersionInconnueJamaisDeSautAuto() {
        val m = parse("""{"intro":[{"start_ms":null,"end_ms":107000,"confidence":0.9,"submission_count":9}]}""").markersFor(3_000_000)
        assertFalse(m.intros.single().auto)
    }

    /** Glee S1E1 : « intro » de 2 s dans la base. */
    @Test
    fun passageTropCourtIgnore() {
        val m = parse(
            """{"group_duration_ms":2972000,"intro":[{"start_ms":33290,"end_ms":35460,"confidence":0.1,"submission_count":1}]}""",
        ).markersFor(2_972_000)
        assertFalse(m.hasIntroData)
    }

    /** Fin à 0 : la base dit qu'il n'y a pas d'intro. Le +85s couperait l'épisode. */
    @Test
    fun pasDIntroConfirme() {
        val m = parse(
            """{"group_duration_ms":2622000,"intro":[{"start_ms":null,"end_ms":0,"confidence":0.3,"submission_count":1}]}""",
        ).markersFor(2_622_000)
        assertTrue(m.intros.isEmpty())
        assertTrue(m.noIntro)
    }

    /** Intro non renseignée (seul le générique l'est) : on ne sait rien, le +85s manuel reste possible. */
    @Test
    fun introNonRenseigneeNestPasUneAbsence() {
        val m = parse("""{"group_duration_ms":3426874,"credits":[{"start_ms":3338250,"end_ms":null}]}""").markersFor(3_426_874)
        assertFalse(m.hasIntroData)
        assertEquals(3_338_250L, m.creditsStartMs)
    }

    /** Erreurs réelles de la base : générique placé sur l'ouverture (Suits, La Casa de Papel), ou « pas de générique » à 0. */
    @Test
    fun generiqueAberrantIgnore() {
        assertNull(
            parse("""{"group_duration_ms":2580000,"credits":[{"start_ms":374582,"end_ms":402568}]}""").markersFor(2_580_000).creditsStartMs,
        )
        assertNull(parse("""{"group_duration_ms":2931000,"credits":[{"start_ms":0,"end_ms":0}]}""").markersFor(2_931_000).creditsStartMs)
        // Repère au-delà de la fin du fichier (Supernatural S1E1).
        assertNull(
            parse("""{"group_duration_ms":2493000,"credits":[{"start_ms":2553000,"end_ms":null}]}""").markersFor(2_493_000).creditsStartMs,
        )
    }

    /** Générique suivi d'une scène : le fichier continue longtemps après, ce n'est pas la fin de l'épisode. */
    @Test
    fun generiqueSuiviDUneLonguePartie() {
        val m = EpisodeTimestamps(3_000_000, emptyList(), listOf(RawSegment(2_000_000, 2_060_000))).markersFor(3_000_000)
        assertNull(m.creditsStartMs)
    }

    @Test
    fun plusieursPassagesDIntro() {
        val t = EpisodeTimestamps(
            1_400_000,
            listOf(RawSegment(30_000, 90_000, 0.9f, 5), RawSegment(120_000, 150_000, 0.9f, 5)),
            emptyList(),
        )
        val m = t.markersFor(1_400_000)
        assertEquals(90_000L, m.introAt(40_000)?.endMs)
        assertNull(m.introAt(100_000))
        assertEquals(150_000L, m.introAt(130_000)?.endMs)
    }

    @Test
    fun dureeInconnueNeDonneRien() = assertFalse(breakingBad.markersFor(0).hasIntroData)

    /** Lost S2E2, vraie réponse : une seule contribution, le bouton suffit. */
    @Test
    fun recapPeuSurBoutonSeulement() {
        val m = parse(
            """{"group_duration_ms":2565000,"recap":[{"start_ms":1000,"end_ms":122000,"confidence":0.1,"submission_count":1}]}""",
        ).markersFor(2_565_000)
        val recap = m.recapAt(50_000)
        assertEquals(122_000L, recap?.endMs)
        assertEquals(SkipKind.RECAP, recap?.kind)
        assertFalse(recap!!.auto)
        assertNull("avant le récap", m.recapAt(500))
        assertFalse("un récap ne dit rien de l'intro", m.hasIntroData)
    }

    @Test
    fun recapSurPeutEtreSauteSeul() {
        val m = parse(
            """{"group_duration_ms":2565000,"recap":[{"start_ms":null,"end_ms":90000,"confidence":0.8,"submission_count":4}]}""",
        ).markersFor(2_565_000)
        assertEquals(0L, m.recaps.single().startMs)
        assertTrue(m.recaps.single().auto)
    }

    /** Stranger Things S2E2, vraie réponse : fin à 0, la base dit qu'il n'y a pas de récap. */
    @Test
    fun pasDeRecapConfirme() {
        val m = parse("""{"recap":[{"start_ms":0,"end_ms":0}]}""").markersFor(3_000_000)
        assertTrue(m.recaps.isEmpty())
    }

    @Test
    fun recapLoinDansLEpisodeIgnore() {
        val m = parse(
            """{"group_duration_ms":3000000,"recap":[{"start_ms":1500000,"end_ms":1560000,"confidence":0.9,"submission_count":5}]}""",
        ).markersFor(3_000_000)
        assertTrue(m.recaps.isEmpty())
    }

    @Test
    fun recapDUneAutreVersionIgnore() {
        val m = parse(
            """{"group_duration_ms":3000000,"recap":[{"start_ms":0,"end_ms":90000,"confidence":0.9,"submission_count":5}]}""",
        ).markersFor(2_800_000)
        assertTrue(m.recaps.isEmpty())
    }

    /** Récap puis intro : chacun à son tour, et le récap d'abord si la base les fait se chevaucher. */
    @Test
    fun recapPuisIntro() {
        val m = EpisodeTimestamps(
            2_000_000,
            intro = listOf(RawSegment(100_000, 160_000, 0.9f, 5)),
            credits = emptyList(),
            recap = listOf(RawSegment(0, 120_000, 0.9f, 5)),
        ).markersFor(2_000_000)
        assertEquals(SkipKind.RECAP, m.skipAt(50_000)?.kind)
        assertEquals(SkipKind.RECAP, m.skipAt(110_000)?.kind)
        assertEquals(SkipKind.INTRO, m.skipAt(120_000)?.kind)
        assertNull(m.skipAt(160_000))
    }

    private fun obj(s: String): JsonObject = LenientJson.parseToJsonElement(s).asObj()!!

    /** Les panels publient `tmdb_id` ou `tmdb`, en nombre ou en texte, souvent vide. */
    @Test
    fun identifiantTmdbDuPanel() {
        assertEquals(1396, XtreamVodParser.parseTmdbId(obj("""{"tmdb_id":1396}""")))
        assertEquals(1396, XtreamVodParser.parseTmdbId(obj("""{"tmdb":"1396"}""")))
        assertEquals(1396, XtreamVodParser.parseTmdbId(obj("""{"tmdb_id":"","tmdb":"1396"}""")))
        assertNull(XtreamVodParser.parseTmdbId(obj("""{"tmdb":""}""")))
        assertNull(XtreamVodParser.parseTmdbId(obj("""{"tmdb_id":"0"}""")))
        assertNull(XtreamVodParser.parseTmdbId(obj("""{"name":"x"}""")))
    }

    /** Recherche par titre : le premier résultat ne suffit pas (TVmaze renvoie un autre programme pour « Les Experts »). */
    @Test
    fun correspondanceParTitreExacteEtUnique() {
        val results = listOf(
            obj("""{"id":1,"name":"Cleaners : Les experts du ménage","original_name":"Cleaners","first_air_date":"2017-01-01"}"""),
            obj("""{"id":2,"name":"Les Experts","original_name":"CSI: Crime Scene Investigation","first_air_date":"2000-10-06"}"""),
        )
        assertEquals(2, exactSeriesMatch(results, "Les Experts", null))
        assertEquals(2, exactSeriesMatch(results, "les expèrts", 2000))
        assertNull("année différente", exactSeriesMatch(results, "Les Experts", 2015))
        assertNull("titre approchant", exactSeriesMatch(results, "Experts", null))

        val homonymes = listOf(
            obj("""{"id":3,"name":"Shameless","first_air_date":"2004-01-13"}"""),
            obj("""{"id":4,"name":"Shameless","first_air_date":"2011-01-09"}"""),
        )
        assertNull("homonymes sans année : on ne choisit pas", exactSeriesMatch(homonymes, "Shameless", null))
        assertEquals(4, exactSeriesMatch(homonymes, "Shameless", 2011))
    }

    private fun show(name: String, premiered: String?, imdb: String?, tvdb: Int?): JsonObject {
        val quoted = { v: String? -> v?.let { "\"$it\"" } ?: "null" }
        return obj("""{"score":0.9,"show":{"name":"$name","premiered":${quoted(premiered)},"externals":{"imdb":${quoted(imdb)},"thetvdb":$tvdb}}}""")
    }

    /** TVmaze cherche large : seul le titre exact et unique compte, jamais le premier résultat. */
    @Test
    fun tvMazeTitreExactEtUnique() {
        val results = listOf(
            show("Stranger Things", "2016-07-15", "tt4574334", 306909),
            show("Beyond Stranger Things", "2017-10-27", "tt7363582", null),
        )
        assertEquals(SeriesRef("imdb_id", "tt4574334"), tvMazeMatch(results, "stranger things", null))
        assertEquals(SeriesRef("imdb_id", "tt4574334"), tvMazeMatch(results, "Stranger Things", 2016))
        assertNull("année différente", tvMazeMatch(results, "Stranger Things", 2020))
        assertNull("titre approchant", tvMazeMatch(results, "Stranger", null))

        val homonymes = listOf(
            show("Shameless", "2004-01-13", "tt0377260", 1),
            show("Shameless", "2011-01-09", "tt1586680", 2),
        )
        assertNull("homonymes sans année : on ne choisit pas", tvMazeMatch(homonymes, "Shameless", null))
        assertEquals(SeriesRef("imdb_id", "tt1586680"), tvMazeMatch(homonymes, "Shameless", 2011))
    }

    @Test
    fun tvMazeIdentifiantManquant() {
        assertEquals(SeriesRef("tvdb_id", "121361"), tvMazeMatch(listOf(show("Serie", "2011-04-17", null, 121361)), "Serie", null))
        assertNull(tvMazeMatch(listOf(show("Serie", null, null, null)), "Serie", null))
        assertNull("date inconnue, année demandée", tvMazeMatch(listOf(show("Serie", null, "tt1", 1)), "Serie", 2011))
    }
}

/** Client TVmaze, contre un faux serveur. */
class TvMazeClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: TvMazeClient
    private val found = """[{"show":{"name":"Game of Thrones","premiered":"2011-04-17","externals":{"imdb":"tt0944947","thetvdb":121361}}}]"""

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = TvMazeClient(server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun chercheLeTitreNettoyeUneSeuleFois() = runBlocking {
        server.enqueue(MockResponse().setBody(found))
        assertEquals(SeriesRef("imdb_id", "tt0944947"), client.findSeries("FR - Game of Thrones (2011)", null))
        assertEquals(SeriesRef("imdb_id", "tt0944947"), client.findSeries("FR - Game of Thrones (2011)", null))
        assertEquals(1, server.requestCount)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/search/shows", url.encodedPath)
        assertEquals("Game of Thrones", url.queryParameter("q"))
    }

    @Test
    fun titreInconnuMemorise() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        assertNull(client.findSeries("Le Trône de fer", null))
        assertNull(client.findSeries("Le Trône de fer", null))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun panneNonMemorisee() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody("<html>"))
        server.enqueue(MockResponse().setBody(found))
        assertNull(client.findSeries("Game of Thrones", null))
        assertNull(client.findSeries("Game of Thrones", null))
        assertEquals(SeriesRef("imdb_id", "tt0944947"), client.findSeries("Game of Thrones", null))
    }
}

/** Client HTTP, contre un faux serveur. */
class IntroDbClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: IntroDbClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = IntroDbClient(server.url("/v4").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun demandeLEpisodeEtLaDureeReelle() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"group_duration_ms":2000000,"intro":[{"start_ms":1000,"end_ms":61000,"confidence":0.8,"submission_count":3}]}"""))
        val t = assertNotNull(client.episode(SeriesRef.tmdb(1396), 2, 5, 2_000_000))
        assertEquals(61_000L, t.intro.single().endMs)
        assertEquals(3, t.intro.single().submissions)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/v4/media", url.encodedPath)
        assertEquals("1396", url.queryParameter("tmdb_id"))
        assertEquals("2", url.queryParameter("season"))
        assertEquals("5", url.queryParameter("episode"))
        assertEquals("2000000", url.queryParameter("duration_ms"))
        assertEquals("true", url.queryParameter("details"))
    }

    private fun assertNotNull(v: EpisodeTimestamps?): EpisodeTimestamps {
        assertNotNull("réponse attendue", v as Any?)
        return v!!
    }

    /** Conditions de TheIntroDB : cache de session permis, et pas de requête répétée pour rien. */
    @Test
    fun uneSeuleRequeteParEpisodeDansLaSession() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"intro":[]}"""))
        client.episode(SeriesRef.tmdb(1), 1, 1, 1_000_000)
        client.episode(SeriesRef.tmdb(1), 1, 1, 1_000_000)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun episodeInconnuMemorise() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"no submissions","code":"no_submissions"}"""))
        assertNull(client.episode(SeriesRef.tmdb(1), 1, 1, 1_000_000))
        assertNull(client.episode(SeriesRef.tmdb(1), 1, 1, 1_000_000))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun limiteOuPanneNonMemorisees() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"slow down"}"""))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("""{"intro":[{"start_ms":null,"end_ms":30000}]}"""))
        assertNull(client.episode(SeriesRef.tmdb(1), 1, 1, 1_000_000))
        assertNull(client.episode(SeriesRef.tmdb(1), 1, 1, 1_000_000))
        assertEquals(30_000L, client.episode(SeriesRef.tmdb(1), 1, 1, 1_000_000)?.intro?.single()?.endMs)
    }

    @Test
    fun reponseIllisibleOuServeurMort() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>"))
        assertNull(client.episode(SeriesRef.tmdb(1), 1, 1, 1_000_000))
        server.shutdown()
        assertNull(client.episode(SeriesRef.tmdb(2), 1, 1, 1_000_000))
    }
}

/** Vraies lectures libVLC : série Xtream, faux panel et fausse API TheIntroDB. */
@OptIn(ExperimentalTestApi::class)
class IntroPlaybackTest {
    @get:Rule
    val rule = sqliteThenComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var media: File
    private lateinit var dir: File
    private lateinit var c: AppContainer
    private var pid = 0L
    private val episodeUid get() = "$pid:S:1:10"

    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val lifecycle = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    /** Réponse de TheIntroDB (null : 404). */
    @Volatile private var introDb: String? = null

    /** Fiche de série du panel. */
    @Volatile private var panelInfo = """{"info":{"tmdb_id":"1396"},"episodes":{}}"""
    /** Résultats de TVmaze. */
    @Volatile private var tvMaze = "[]"
    @Volatile private var asked: RecordedRequest? = null

    @Before
    fun setUp() = runBlocking {
        Vlc.init()
        assumeTrue("libVLC absent", Vlc.available)
        assumeTrue("ffmpeg absent", runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false))
        media = Files.createTempDirectory("vyzo-media").toFile()
        val p = ProcessBuilder(
            "ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=160x120:rate=5:duration=300",
            "-f", "lavfi", "-i", "sine=duration=300", "-shortest", File(media, "ep.mkv").absolutePath,
        ).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        assertEquals(0, p.waitFor())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/player_api.php") -> MockResponse().setBody(panelInfo)
                    request.path!!.startsWith("/search/shows") -> MockResponse().setBody(tvMaze)
                    request.path!!.startsWith("/v4/media") -> {
                        asked = request
                        introDb?.let { MockResponse().setBody(it) }
                            ?: MockResponse().setResponseCode(404).setBody("""{"error":"no submissions","code":"no_submissions"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        dir = Files.createTempDirectory("vyzo-intro").toFile()
        c = testContainer(dir)
        c.introDb.baseUrl = server.url("/v4").toString()
        c.tvMaze.baseUrl = server.url("/").toString()
        pid = c.db.playlists().insert(
            PlaylistEntity(name = "Abonnement", type = SourceType.XTREAM, url = server.url("/").toString().trimEnd('/'), username = "u", password = "p", lastSync = 1),
        )
        c.settings.update { it.copy(activePlaylistId = pid) }
        c.db.series().upsert(listOf(SeriesEntity("$pid:S:1", pid, "1", "Série", "serie", "", "", position = 0, syncToken = 1)))
        c.db.series().upsertEpisodes(
            listOf(
                EpisodeEntity(episodeUid, "$pid:S:1", pid, "10", 2, 5, "Épisode", poster = "https://exemple.test/ep.jpg", streamUrl = DownloadManager.playUri(File(media, "ep.mkv")), syncToken = 1),
                EpisodeEntity("$pid:S:1:11", "$pid:S:1", pid, "11", 2, 6, "Suivant", streamUrl = DownloadManager.playUri(File(media, "ep.mkv")), syncToken = 1),
            ),
        )
    }

    @After
    fun tearDown() {
        if (!::server.isInitialized) return
        shown = false
        rule.waitForIdle()
        owner.viewModelStore.clear()
        runCatching { c.player.release() }
        runCatching { c.closeForTest() }
        dir.deleteRecursively()
        media.deleteRecursively()
        server.shutdown()
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(100)
        }
        throw AssertionError(what)
    }

    private fun watch(block: (PlayerViewModel) -> Unit) {
        val vm = PlayerViewModel(c, PlayKind.EPISODE, episodeUid, "", 0, 0L)
        try {
            waitFor("lecture : ${c.player.status.value} ${c.player.error.value}") { c.player.status.value.isPlaying && c.player.duration > 0 }
            block(vm)
        } finally {
            vm.viewModelScope.cancel()
            c.player.stop()
            Thread.sleep(300)
        }
    }

    private fun intro(start: String, end: Long, group: Long = 300_000, submissions: Int = 6, confidence: Double = 0.9) =
        """{"group_duration_ms":$group,"intro":[{"start_ms":$start,"end_ms":$end,"confidence":$confidence,"submission_count":$submissions}]}"""

    private fun autoSkip() = runBlocking { c.settings.update { it.copy(autoSkipIntro = true) } }

    @Test
    fun leLecteurRecoitLImageDeLEpisode() {
        watch { vm -> assertEquals("https://exemple.test/ep.jpg", vm.ui.value.artwork) }
    }

    @Test
    fun laDefinitionDuFluxEstRetenuePourLEpisodeEtSaSerie() {
        watch {
            waitFor("définition relevée") { runBlocking { c.db.streamQuality().get(episodeUid) } != null }
            val seen = runBlocking { c.db.streamQuality().get(episodeUid)!! }
            val series = runBlocking { c.db.series().episode(episodeUid)!!.seriesUid.let { c.db.streamQuality().get(it) } }
            assertTrue("taille réelle : ${seen.width}x${seen.height}", seen.width > 0 && seen.height > 0)
            assertEquals("la série retient la même définition", seen.width, series?.width)
        }
    }

    @Test
    fun sautAutoJusquALaFinReelleDeLIntro() {
        autoSkip()
        introDb = intro("null", 20_000)
        watch {
            waitFor("saut à 20 s, position ${c.player.currentPosition}") { c.player.currentPosition >= 20_000 }
            assertTrue("pas au-delà de la fin de l'intro : ${c.player.currentPosition}", c.player.currentPosition < 30_000)
            val url = asked!!.requestUrl!!
            assertEquals("identifiant du panel", "1396", url.queryParameter("tmdb_id"))
            assertEquals("2", url.queryParameter("season"))
            assertEquals("5", url.queryParameter("episode"))
            assertTrue("durée réelle envoyée", url.queryParameter("duration_ms")!!.toLong() in 295_000..305_000)
        }
    }

    /** L'intro vient après une scène d'ouverture : celle-ci doit être vue, puis l'intro sautée une seule fois. */
    @Test
    fun coldOpenConserveEtIntroSauteeUneFois() {
        autoSkip()
        introDb = intro("40000", 60_000)
        watch { vm ->
            waitFor("repères chargés") { vm.markers.value.intros.isNotEmpty() }
            Thread.sleep(1_500)
            assertTrue("la scène d'ouverture ne doit pas être coupée : ${c.player.currentPosition}", c.player.currentPosition < 40_000)
            c.player.seekTo(39_000)
            waitFor("saut à 60 s, position ${c.player.currentPosition}") { c.player.currentPosition >= 60_000 }
            // Revenir sur l'intro pour la revoir ne la fait pas sauter de nouveau.
            c.player.seekTo(45_000)
            Thread.sleep(2_000)
            assertTrue("intro revue : ${c.player.currentPosition}", c.player.currentPosition in 45_000 until 60_000)
        }
    }

    @Test
    fun sansDonneePasDeSautAuto() {
        autoSkip()
        introDb = null
        watch { vm ->
            waitFor("question posée à TheIntroDB") { asked != null }
            Thread.sleep(1_500)
            assertFalse(vm.markers.value.hasIntroData)
            assertTrue("rien ne doit être sauté : ${c.player.currentPosition}", c.player.currentPosition < 15_000)
            // Le +85s reste un choix manuel.
            val before = c.player.currentPosition
            vm.skipSegment()
            waitFor("+85s, position ${c.player.currentPosition}") { c.player.currentPosition >= before + 80_000 }
        }
    }

    /** Ni identifiant du fournisseur, ni clé TMDB, ni TVmaze : on ne demande rien à TheIntroDB et rien n'est sauté. */
    @Test
    fun sansIdentifiantPasDeRequeteNiDeSaut() {
        autoSkip()
        panelInfo = """{"info":{"tmdb":""},"episodes":{}}"""
        introDb = intro("null", 20_000)
        watch { vm ->
            Thread.sleep(2_500)
            assertNull(asked)
            assertFalse(vm.markers.value.hasIntroData)
            assertTrue("rien ne doit être sauté : ${c.player.currentPosition}", c.player.currentPosition < 15_000)
        }
    }

    /** Sans identifiant du fournisseur ni clé TMDB : TVmaze donne la série par son titre, et le saut reste possible. */
    @Test
    fun sansIdentifiantTvMazeRetrouveLaSerie() {
        panelInfo = """{"info":{"tmdb":""},"episodes":{}}"""
        tvMaze = """[{"show":{"name":"Série","premiered":"2011-04-17","externals":{"imdb":"tt0944947","thetvdb":121361}}}]"""
        introDb = intro("10000", 60_000)
        watch { vm ->
            waitFor("repères") { vm.markers.value.hasIntroData }
            val url = asked!!.requestUrl!!
            assertEquals("tt0944947", url.queryParameter("imdb_id"))
            assertNull(url.queryParameter("tmdb_id"))
            assertEquals("2", url.queryParameter("season"))
            assertEquals("5", url.queryParameter("episode"))
            assertEquals(60_000L, vm.markers.value.introAt(30_000)?.endMs)
        }
    }

    /** Autre version de l'épisode (ici une durée sans rapport) : les repères sont faux, on les ignore. */
    @Test
    fun autreVersionPasDeSaut() {
        autoSkip()
        introDb = intro("null", 20_000, group = 2_700_000)
        watch { vm ->
            waitFor("question posée à TheIntroDB") { asked != null }
            Thread.sleep(1_500)
            assertFalse(vm.markers.value.hasIntroData)
            assertTrue("rien ne doit être sauté : ${c.player.currentPosition}", c.player.currentPosition < 15_000)
        }
    }

    /** Donnée peu sûre : pas de saut seul, mais le bouton va jusqu'à la fin réelle, pas à +85 s. */
    @Test
    fun donneePeuSureBoutonSeulement() {
        autoSkip()
        introDb = intro("null", 30_000, submissions = 1, confidence = 0.1)
        watch { vm ->
            waitFor("repères chargés") { vm.markers.value.intros.isNotEmpty() }
            Thread.sleep(1_000)
            assertTrue("pas de saut seul : ${c.player.currentPosition}", c.player.currentPosition < 15_000)
            vm.skipSegment()
            waitFor("fin réelle, position ${c.player.currentPosition}") { c.player.currentPosition >= 30_000 }
            assertTrue("pas +85 s : ${c.player.currentPosition}", c.player.currentPosition < 40_000)
        }
    }

    private fun seg(start: String, end: Long) = """{"start_ms":$start,"end_ms":$end,"confidence":0.9,"submission_count":6}"""

    /** Le récap est sauté par son propre réglage : l'intro, dont le réglage est coupé, reste à l'écran. */
    @Test
    fun sautAutoDuRecapSansToucherLIntro() {
        runBlocking { c.settings.update { it.copy(autoSkipRecap = true) } }
        introDb = """{"group_duration_ms":300000,"recap":[${seg("null", 20_000)}],"intro":[${seg("40000", 60_000)}]}"""
        watch { vm ->
            waitFor("saut à 20 s, position ${c.player.currentPosition}") { c.player.currentPosition >= 20_000 }
            assertTrue("pas au-delà du récap : ${c.player.currentPosition}", c.player.currentPosition < 30_000)
            assertEquals("Récap passé", vm.message.value)
            c.player.seekTo(45_000)
            Thread.sleep(1_500)
            assertTrue("intro conservée : ${c.player.currentPosition}", c.player.currentPosition in 45_000 until 60_000)
        }
    }

    /** Le saut automatique des intros ne doit pas couper un récap que l'utilisateur veut voir. */
    @Test
    fun reglageDesIntrosNeSautePasLeRecap() {
        autoSkip()
        introDb = """{"group_duration_ms":300000,"recap":[${seg("null", 20_000)}]}"""
        watch { vm ->
            waitFor("repères chargés") { vm.markers.value.recaps.isNotEmpty() }
            Thread.sleep(1_500)
            assertTrue("récap conservé : ${c.player.currentPosition}", c.player.currentPosition < 15_000)
        }
    }

    private var shown by mutableStateOf(true)

    private fun showPlayer(desktop: Boolean = false) {
        rule.setContent {
            if (!shown) return@setContent
            CompositionLocalProvider(
                LocalContainer provides c, LocalDesktop provides desktop, LocalFormFactor provides FormFactor.TV,
                LocalViewModelStoreOwner provides owner, LocalLifecycleOwner provides lifecycle,
            ) {
                MaterialTheme {
                    Box(Modifier.size(1280.dp, 720.dp)) {
                        PlayerScreen(kind = PlayKind.EPISODE, uid = episodeUid, group = "", programId = 0, startMs = 0L, onExit = {}, onCatchup = { _, _ -> })
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

    /** À l'écran : « Passer l'intro » sur le passage réel, et ni +85s ni bouton ailleurs. */
    @Test
    fun boutonSurLePassageReel() {
        introDb = intro("40000", 60_000)
        showPlayer(desktop = true)
        rule.waitUntil(20_000) { c.player.status.value.isPlaying && c.player.duration > 0 && asked != null }
        c.player.seekTo(20_000)
        Thread.sleep(1_500)
        rule.waitForIdle()
        assertFalse("avant le passage : pas de bouton", exists("Passer l'intro"))
        c.player.seekTo(45_000)
        rule.waitUntil(10_000) { exists("Passer l'intro") }
        assertFalse("horodatage réel : pas de +85s", exists("+85s"))
    }

    /** Sur un récap connu : son propre libellé, et ni +85s ni « Passer l'intro », même si l'intro est inconnue. */
    @Test
    fun boutonSurLeRecap() {
        introDb = """{"group_duration_ms":300000,"recap":[${seg("40000", 60_000)}]}"""
        showPlayer(desktop = true)
        rule.waitUntil(20_000) { c.player.status.value.isPlaying && c.player.duration > 0 && asked != null }
        c.player.seekTo(45_000)
        rule.waitUntil(10_000) { exists("Passer le récap") }
        assertFalse(exists("+85s"))
        assertFalse(exists("Passer l'intro"))
    }

    /** Télécommande : OK va à la fin réelle du récap. */
    @Test
    fun okALaTelecommandeSurUnRecap() {
        introDb = """{"group_duration_ms":300000,"recap":[${seg("40000", 60_000)}]}"""
        showPlayer()
        rule.waitUntil(20_000) { c.player.status.value.isPlaying && c.player.duration > 0 && asked != null }
        c.player.seekTo(45_000)
        rule.waitUntil(10_000) { exists("Passer le récap · OK") }
        press(Key.DirectionCenter)
        waitFor("fin réelle, position ${c.player.currentPosition}") { c.player.currentPosition >= 60_000 }
        assertTrue("pas +85 s : ${c.player.currentPosition}", c.player.currentPosition < 70_000)
        assertTrue("OK ne met pas en pause ici", c.player.status.value.isPlaying)
    }

    /** Télécommande : sur une intro réelle, le bouton annonce OK et OK va à sa fin réelle. Ailleurs, OK reste la pause. */
    @Test
    fun okALaTelecommandeSurUneIntroReelle() {
        introDb = intro("40000", 60_000)
        showPlayer()
        rule.waitUntil(20_000) { c.player.status.value.isPlaying && c.player.duration > 0 && asked != null }
        c.player.seekTo(45_000)
        rule.waitUntil(10_000) { exists("Passer l'intro · OK") }
        press(Key.DirectionCenter)
        waitFor("fin réelle, position ${c.player.currentPosition}") { c.player.currentPosition >= 60_000 }
        assertTrue("pas +85 s : ${c.player.currentPosition}", c.player.currentPosition < 70_000)
        assertTrue("OK ne met pas en pause ici", c.player.status.value.isPlaying)
        // L'intro est passée : OK redevient la pause.
        Thread.sleep(1_000)
        rule.waitForIdle()
        press(Key.DirectionCenter)
        waitFor("OK met en pause") { !c.player.status.value.isPlaying }
    }

    /** Sur PC et téléphone, sans donnée : le +85s reste proposé, y compris si le saut automatique est activé. */
    @Test
    fun sansDonneeLeBoutonManuelReste() {
        autoSkip()
        introDb = null
        showPlayer(desktop = true)
        rule.waitUntil(20_000) { c.player.status.value.isPlaying && c.player.duration > 0 && asked != null }
        c.player.seekTo(16_000)
        rule.waitUntil(10_000) { exists("+85s") }
    }

    /** Télécommande, sans donnée : pas de +85s à l'aveugle, OK reste la pause. */
    @Test
    fun sansDonneePasDeBoutonALaTelecommande() {
        introDb = null
        showPlayer()
        rule.waitUntil(20_000) { c.player.status.value.isPlaying && c.player.duration > 0 && asked != null }
        c.player.seekTo(16_000)
        Thread.sleep(1_500)
        rule.waitForIdle()
        assertFalse(exists("Passer l'intro"))
        val before = c.player.currentPosition
        press(Key.DirectionCenter)
        waitFor("OK met en pause") { !c.player.status.value.isPlaying }
        assertTrue("OK ne saute rien", c.player.currentPosition < before + 5_000)
    }

    /** La carte « Épisode suivant » suit le vrai générique, pas les 90 dernières secondes. */
    @Test
    fun carteEpisodeSuivantSurLeVraiGenerique() {
        introDb = """{"group_duration_ms":300000,"credits":[{"start_ms":200000,"end_ms":null,"confidence":0.5,"submission_count":3}]}"""
        showPlayer()
        rule.waitUntil(20_000) { c.player.status.value.isPlaying && c.player.duration > 0 && asked != null }
        Thread.sleep(1_000)
        c.player.seekTo(190_000)
        Thread.sleep(1_500)
        rule.waitForIdle()
        assertFalse("avant le générique", exists("Rester"))
        c.player.seekTo(205_000)
        rule.waitUntil(10_000) { exists("Rester") }
    }
}
