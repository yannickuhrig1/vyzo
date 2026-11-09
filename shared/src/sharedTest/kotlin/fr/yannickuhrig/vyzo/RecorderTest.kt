package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.repo.StreamSpec
import fr.yannickuhrig.vyzo.schedule.StreamRecorder
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Garde-fous de l'enregistreur : un serveur qui renvoie une vidéo courte ne doit pas remplir le disque. */
class RecorderTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val clip = ByteArray(188 * 1000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(clip))
        }
        server.start()
        dir = Files.createTempDirectory("vyzo-rec").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun spec() = StreamSpec(url = server.url("/live/ch1.ts").toString(), userAgent = "Vyzo-test", isLive = true)

    @Test
    fun shortClipServedInLoopStopsTheRecording() = runBlocking {
        val out = File(dir, "rec.ts")
        try {
            StreamRecorder(spec(), out, System.currentTimeMillis() + 60_000, minFreeBytes = 0, reconnectDelayMs = 5) {}.run()
            fail("L'enregistrement aurait dû s'arrêter")
        } catch (e: StreamRecorder.LoopingStreamException) {
            // attendu
        }
        // 8 tentatives au maximum, pas des centaines.
        assertTrue(server.requestCount in 1..8)
        assertEquals(clip.size.toLong() * server.requestCount, out.length())
    }

    @Test
    fun stopsWhenDiskIsAlmostFull() = runBlocking {
        val out = File(dir, "rec.ts")
        try {
            StreamRecorder(spec(), out, System.currentTimeMillis() + 60_000, minFreeBytes = Long.MAX_VALUE, reconnectDelayMs = 5) {}.run()
            fail("L'enregistrement aurait dû s'arrêter")
        } catch (e: StreamRecorder.DiskFullException) {
            // attendu
        }
        assertEquals(1, server.requestCount)
    }
}
