package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.closeForTest
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.db.DownloadEntity
import fr.yannickuhrig.vyzo.data.db.DownloadStatus
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.download.DownloadManager
import fr.yannickuhrig.vyzo.data.repo.StreamSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class DesktopDownloadTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var c: AppContainer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val data = Random(7).nextBytes(3 * 1024 * 1024)
    private val requests = mutableListOf<RecordedRequest>()
    @Volatile private var cutAt = -1

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                val from = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                val body = Buffer().write(data, from, data.size - from)
                val r = MockResponse().setResponseCode(if (from > 0) 206 else 200).setBody(body)
                    .throttleBody(128 * 1024, 50, TimeUnit.MILLISECONDS)
                if (cutAt >= 0) r.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                return r
            }
        }
        server.start()
        dir = Files.createTempDirectory("vyzo-dl").toFile()
        c = testContainer(dir)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        c.closeForTest()
        dir.deleteRecursively()
    }

    private fun manager(ua: String = "") = DownloadManager(c.platform, c.db, scope) {
        StreamSpec(url = server.url("/movie/film.mkv").toString(), userAgent = ua, isLive = false)
    }

    private fun entity() = DownloadEntity(
        id = "movie:1", itemType = "movie", itemUid = "1", title = "Film",
        remoteUrl = "http://127.0.0.1:9/ancienne-adresse.mkv", localFilePath = File(dir, "film.mkv").absolutePath,
    )

    private fun waitFor(timeoutMs: Long = 20_000, cond: suspend () -> Boolean): Boolean = runBlocking {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return@runBlocking true
            Thread.sleep(50)
        }
        cond()
    }

    private suspend fun status() = c.db.downloads().get("movie:1")?.status

    @Test
    fun pauseArreteLEcritureEtLaRepriseCompleteLeFichier() = runBlocking {
        val dm = manager()
        c.db.downloads().upsert(entity())
        dm.start()
        val file = File(entity().localFilePath)
        assertTrue(waitFor { file.length() > 256 * 1024 })

        dm.pauseDownload("movie:1")
        val sizeAtPause = file.length()
        Thread.sleep(800)
        assertEquals("plus rien n'est écrit après la pause", sizeAtPause, file.length())
        assertEquals(DownloadStatus.PAUSED, status())
        assertTrue(sizeAtPause < data.size)

        dm.resumeDownload("movie:1")
        assertTrue(waitFor { status() == DownloadStatus.COMPLETED })
        assertArrayEquals(data, file.readBytes())
        val last = synchronized(requests) { requests.last() }
        assertEquals("bytes=$sizeAtPause-", last.getHeader("Range"))
    }

    @Test
    fun fluxResoluAvecLeUserAgentDeLaPlaylist() = runBlocking {
        val dm = manager(ua = "MonLecteur/2.0")
        c.db.downloads().upsert(entity())
        dm.start()
        assertTrue(waitFor { status() == DownloadStatus.COMPLETED })
        val first = synchronized(requests) { requests.first() }
        assertEquals("/movie/film.mkv", first.path)
        assertEquals("MonLecteur/2.0", first.getHeader("User-Agent"))
    }

    @Test
    fun uneConnexionResteLibrePourRegarder() = runBlocking {
        assertEquals(1, DownloadManager.slotsFor(1))
        assertEquals(1, DownloadManager.slotsFor(2))
        assertEquals(2, DownloadManager.slotsFor(3))
        assertEquals(2, DownloadManager.slotsFor(0))

        // Abonnement à 2 connexions : les deux films se téléchargent l'un après l'autre.
        val pid = c.db.playlists().insert(PlaylistEntity(name = "Xtream", type = SourceType.XTREAM, url = "http://x", maxConnections = 2))
        val dm = manager()
        listOf("a", "b").forEach { n ->
            c.db.downloads().upsert(
                entity().copy(id = "movie:$pid:$n", itemUid = "$pid:$n", localFilePath = File(dir, "$n.mkv").absolutePath),
            )
        }
        dm.start()
        var maxParallel = 0
        assertTrue(
            waitFor {
                val all = c.db.downloads().pendingQueue()
                maxParallel = maxOf(maxParallel, all.count { it.status == DownloadStatus.DOWNLOADING })
                all.isEmpty()
            },
        )
        assertEquals(1, maxParallel)
        assertEquals(2, synchronized(requests) { requests.size })
    }

    @Test
    fun coupureEnCoursNestPasMarqueeTerminee() = runBlocking {
        cutAt = 0
        val dm = manager()
        c.db.downloads().upsert(entity())
        dm.start()
        assertTrue(waitFor { status() == DownloadStatus.FAILED })
        assertTrue(File(entity().localFilePath).length() < data.size)
    }
}
