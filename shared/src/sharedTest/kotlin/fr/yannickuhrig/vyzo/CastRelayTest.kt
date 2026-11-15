package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.cast.CastDecision
import fr.yannickuhrig.vyzo.cast.CastFormat
import fr.yannickuhrig.vyzo.cast.CastPlanner
import fr.yannickuhrig.vyzo.cast.CastRelay
import fr.yannickuhrig.vyzo.cast.HlsRewriter
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.data.repo.StreamSpec
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Relais du Cast : le Chromecast lit tout à travers le téléphone, avec les en-têtes du fournisseur et le CORS. */
class CastRelayTest {

    private lateinit var upstream: MockWebServer
    private lateinit var relay: CastRelay
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private val http = OkHttpClient()
    private val movie = ByteArray(10_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        upstream = MockWebServer()
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/live/master.m3u8") -> MockResponse().setHeader("Content-Type", "text/plain").setBody(
                        "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8?t=abc\n" +
                            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"audio/fr.m3u8\"\n",
                    )
                    path.startsWith("/live/low/index.m3u8") -> MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(
                        "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"/keys/k1\"\n#EXTINF:4,\nseg1.ts\n#EXTINF:4,\nhttp://cdn.example/seg2.ts\n",
                    )
                    path.startsWith("/live/low/seg1.ts") -> MockResponse().setHeader("Content-Type", "video/mp2t").setBody("SEGMENT1")
                    path.startsWith("/movie/film.mp4") -> {
                        val range = request.getHeader("Range")
                        if (range == "bytes=100-199") {
                            MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4")
                                .setHeader("Content-Range", "bytes 100-199/${movie.size}")
                                .setBody(Buffer().write(movie, 100, 100))
                        } else {
                            MockResponse().setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes").setBody(Buffer().write(movie))
                        }
                    }
                    path.startsWith("/refused") -> MockResponse().setResponseCode(403)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        upstream.start()
        relay = CastRelay(client = OkHttpClient(), hostProvider = { "127.0.0.1" })
    }

    @After
    fun tearDown() {
        relay.stop()
        upstream.shutdown()
    }

    private fun spec(path: String, live: Boolean = true) = StreamSpec(
        url = upstream.url(path).toString(), userAgent = "VyzoTest/1.0", headers = mapOf("Referer" to "http://portail.example/"), isLive = live,
    )

    private fun get(url: String, range: String? = null) = http.newCall(
        Request.Builder().url(url).apply { if (range != null) header("Range", range) }.build(),
    ).execute()

    @Test
    fun `liste HLS réécrite, en-têtes du fournisseur et CORS`() {
        val url = relay.publish(spec("/live/master.m3u8"))!!
        val master = get(url).use { r ->
            assertEquals(200, r.code)
            assertEquals("*", r.header("Access-Control-Allow-Origin"))
            assertEquals("application/vnd.apple.mpegurl", r.header("Content-Type"))
            r.body!!.string()
        }
        // Plus aucune adresse du fournisseur : tout passe par le relais.
        assertFalse(master, master.contains(upstream.hostName + ":" + upstream.port))
        val variant = master.lines().first { it.startsWith("/c/") }
        assertTrue(variant, variant.endsWith("/index.m3u8"))
        assertTrue(master, Regex("""URI="/c/[^"]+/fr\.m3u8"""").containsMatchIn(master))

        val base = url.substringBefore("/c/")
        val media = get(base + variant).use { it.body!!.string() }
        assertTrue(media, Regex("""URI="/c/[^"]+/k1"""").containsMatchIn(media))
        val segments = media.lines().filter { it.startsWith("/c/") }
        assertEquals(2, segments.size)
        assertTrue(segments[1].endsWith("/seg2.ts"))

        val seg = get(base + segments[0]).use { r ->
            assertEquals("*", r.header("Access-Control-Allow-Origin"))
            r.body!!.string()
        }
        assertEquals("SEGMENT1", seg)
        val segRequest = seen.last { it.path!!.startsWith("/live/low/seg1.ts") }
        assertEquals("VyzoTest/1.0", segRequest.getHeader("User-Agent"))
        assertEquals("http://portail.example/", segRequest.getHeader("Referer"))
        // La requête de la variante garde sa chaîne de requête (jetons du fournisseur).
        assertTrue(seen.any { it.path == "/live/low/index.m3u8?t=abc" })
    }

    @Test
    fun `film servi tel quel avec les plages pour l'avance rapide`() {
        val url = relay.publish(spec("/movie/film.mp4", live = false))!!
        assertTrue(url.endsWith("/film.mp4"))
        get(url).use { r ->
            assertEquals(200, r.code)
            assertEquals("video/mp4", r.header("Content-Type"))
            assertEquals(movie.size.toString(), r.header("Content-Length"))
            assertTrue(movie.contentEquals(r.body!!.bytes()))
        }
        get(url, range = "bytes=100-199").use { r ->
            assertEquals(206, r.code)
            assertEquals("bytes 100-199/${movie.size}", r.header("Content-Range"))
            assertTrue(movie.copyOfRange(100, 200).contentEquals(r.body!!.bytes()))
        }
    }

    @Test
    fun `sans jeton valide, rien n'est servi`() {
        val url = relay.publish(spec("/movie/film.mp4", live = false))!!
        val base = url.substringBefore("/c/")
        get("$base/c/inconnu/m/film.mp4").use { assertEquals(404, it.code) }
        // Adresse arbitraire glissée sous un faux jeton : refusée.
        get("$base/c/inconnu/u/aHR0cDovL2V4YW1wbGUuY29tLw/x").use { assertEquals(404, it.code) }
        // Même sous le vrai jeton, une adresse que le relais n'a pas lui-même publiée n'est pas servie (pas de proxy ouvert).
        val token = url.substringAfter("/c/").substringBefore('/')
        get("$base/c/$token/u/aHR0cDovL2V4YW1wbGUuY29tLw/x").use { assertEquals(404, it.code) }
        val preflight = http.newCall(Request.Builder().url(url).method("OPTIONS", null).build()).execute()
        preflight.use {
            assertEquals(204, it.code)
            assertTrue(it.header("Access-Control-Allow-Headers").orEmpty().contains("Range"))
        }
    }

    @Test
    fun `refus du fournisseur transmis et retenu`() {
        val url = relay.publish(spec("/refused/1.m3u8"))!!
        get(url).use { assertEquals(403, it.code) }
        assertEquals(403, relay.lastUpstreamError.value)
    }

    @Test
    fun `hors réseau local, pas d'adresse`() {
        val offline = CastRelay(client = OkHttpClient(), hostProvider = { null })
        assertNull(offline.publish(spec("/movie/film.mp4")))
    }

    @Test
    fun `réécriture HLS laisse les clés non web`() {
        val out = HlsRewriter.rewrite(
            "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://cle\"\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2,\n../a/b.ts\n",
            "http://serveur.example/x/y/index.m3u8",
        ) { "R[$it]" }
        assertTrue(out, out.contains("URI=\"skd://cle\""))
        assertTrue(out, out.contains("URI=\"R[http://serveur.example/x/y/init.mp4]\""))
        assertTrue(out, out.contains("R[http://serveur.example/x/a/b.ts]"))
    }

    @Test
    fun `format reconnu par l'adresse ou les premiers octets`() {
        fun f(url: String, hint: String? = null) = CastPlanner.formatFromUrl(StreamSpec(url = url, userAgent = "", mimeHint = hint, isLive = true))
        assertEquals(CastFormat.HLS, f("http://s/live/u/p/1.m3u8"))
        assertEquals(CastFormat.HLS, f("http://s/play/1", hint = Repository.MIME_HLS))
        assertEquals(CastFormat.TS, f("http://s/live/u/p/1.ts"))
        assertEquals(CastFormat.MP4, f("http://s/movie/u/p/1.mp4?token=1"))
        assertEquals(CastFormat.MKV, f("http://s/movie/u/p/1.mkv"))
        assertEquals(CastFormat.UNSUPPORTED, f("http://s/movie/u/p/1.avi"))
        assertEquals(CastFormat.UNKNOWN, f("http://s/play/1"))

        assertEquals(CastFormat.HLS, CastPlanner.formatFromBytes("#EXTM3U\n#EXT".toByteArray()))
        assertEquals(CastFormat.MP4, CastPlanner.formatFromBytes(byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray()))
        assertEquals(CastFormat.MKV, CastPlanner.formatFromBytes(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 1)))
        assertEquals(CastFormat.TS, CastPlanner.formatFromBytes(byteArrayOf(0x47, 0x40, 0x11, 0x10)))
    }

    @Test
    fun `chaîne Xtream en MPEG-TS remplacée par sa version HLS`() {
        assertEquals("http://s:8080/live/u/p/42.m3u8", CastPlanner.xtreamHlsVariant("http://s:8080/live/u/p/42.ts"))
        assertEquals("http://s/live/u/p/42.m3u8?x=1", CastPlanner.xtreamHlsVariant("http://s/live/u/p/42.ts?x=1"))
        assertEquals("http://s:8080/live/u/p/42.m3u8", CastPlanner.xtreamHlsVariant("http://s:8080/u/p/42"))
        assertEquals("http://s:8080/live/u/p/42.m3u8", CastPlanner.xtreamHlsVariant("http://s:8080/u/p/42.ts"))
        assertNull(CastPlanner.xtreamHlsVariant("http://s/movie/u/p/42.mp4"))
        assertNull(CastPlanner.xtreamHlsVariant("http://s/chaines/tf1/flux.ts"))
    }

    @Test
    fun `refus clairs pour ce que le Chromecast ne lit pas`() {
        val live = StreamSpec(url = "http://s/live/1.ts", userAgent = "", isLive = true)
        assertTrue(CastPlanner.decide(live, CastFormat.TS) is CastDecision.Refuse)
        assertTrue(CastPlanner.decide(live.copy(drmType = "widevine"), CastFormat.HLS) is CastDecision.Refuse)
        assertTrue(CastPlanner.decide(live.copy(url = "file:///sdcard/film.mp4"), CastFormat.MP4) is CastDecision.Refuse)
        assertEquals(CastDecision.Cast("application/x-mpegURL"), CastPlanner.decide(live, CastFormat.HLS))
        assertEquals(CastDecision.Cast("video/mp4"), CastPlanner.decide(live, CastFormat.MP4))
    }

    @Test
    fun `sondage des premiers octets chez le fournisseur`() = runBlocking {
        assertEquals(CastFormat.HLS, CastPlanner.probe(spec("/live/master.m3u8"), OkHttpClient()))
        assertEquals(CastFormat.UNKNOWN, CastPlanner.probe(spec("/refused/x"), OkHttpClient()))
    }
}
