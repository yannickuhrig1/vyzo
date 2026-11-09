package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.update.AppUpdater
import fr.yannickuhrig.vyzo.update.UpdateInfo
import fr.yannickuhrig.vyzo.update.UpdateInstaller
import fr.yannickuhrig.vyzo.update.UpdatePackage
import fr.yannickuhrig.vyzo.update.UpdateState
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Mise à jour de Vyzo PC : latest.json, paquet publié en morceaux remis bout à bout, empreinte vérifiée. */
class DesktopUpdateTest {
    private lateinit var server: MockWebServer
    private val pkg = ByteArray(250_000) { (it * 7 % 256).toByte() }
    private val parts = listOf(pkg.copyOfRange(0, 100_000), pkg.copyOfRange(100_000, 200_000), pkg.copyOfRange(200_000, pkg.size))
    private val sha = MessageDigest.getInstance("SHA-256").digest(pkg).joinToString("") { "%02x".format(it) }
    private var latest = ""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.substringBefore('?')
                if (path == "/releases/latest.json") return MockResponse().setBody(latest)
                val m = Regex("/releases/vyzo_9\\.9\\.9_amd64\\.deb\\.(\\d{3})").matchEntire(path)
                    ?: return MockResponse().setResponseCode(404)
                return MockResponse().setBody(Buffer().write(parts[m.groupValues[1].toInt()]))
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private class FakeInstaller(val linux: Boolean) : UpdateInstaller {
        var installed: ByteArray? = null
        override fun packageFor(info: UpdateInfo): UpdatePackage? =
            info.deb.takeIf { linux && it.isNotBlank() }?.let { UpdatePackage(info.partsOf(it, info.debParts), info.debSha256, "test.deb") }
        override fun install(file: File) { installed = file.readBytes() }
    }

    @Test
    fun partsAreDownloadedReassembledAndChecked() = runBlocking {
        val c = testContainer()
        c.settings.update { it.copy(updateUrl = server.url("/releases/latest.json").toString()) }
        latest = """{"versionCode": 999, "versionName": "9.9.9", "apk": "vyzo-9.9.9.apk", "deb": "vyzo_9.9.9_amd64.deb",
            |"debSha256": "$sha", "debParts": 3, "notes": "Nouveautés"}""".trimMargin()
        val installer = FakeInstaller(linux = true)
        val updater = AppUpdater(c.settings, c.platform, installer)

        val info = updater.check()!!
        assertEquals("9.9.9", info.versionName)
        assertTrue(info.deb.startsWith("http"))
        updater.downloadAndInstall(info)
        assertTrue(updater.state.value is UpdateState.Installing)
        assertArrayEquals(pkg, installed(installer))

        // Empreinte différente : le fichier est refusé.
        updater.downloadAndInstall(info.copy(debSha256 = "0".repeat(64)))
        assertTrue(updater.state.value is UpdateState.Failed)
    }

    @Test
    fun versionWithoutPackageForThisSystemIsNotOffered() = runBlocking {
        val c = testContainer()
        c.settings.update { it.copy(updateUrl = server.url("/releases/latest.json").toString()) }
        latest = """{"versionCode": 999, "versionName": "9.9.9", "apk": "vyzo-9.9.9.apk"}"""
        val updater = AppUpdater(c.settings, c.platform, FakeInstaller(linux = true))
        assertEquals(null, updater.check())
        assertEquals(UpdateState.UpToDate, updater.state.value)
    }

    /** Recherche automatique : une version publiée une heure après la précédente vérification est trouvée. */
    @Test
    fun automaticCheckRunsAgainAfterAnHour() = runBlocking {
        val c = testContainer()
        c.settings.update { it.copy(updateUrl = server.url("/releases/latest.json").toString()) }
        latest = """{"versionCode": 999, "versionName": "9.9.9", "deb": "vyzo_9.9.9_amd64.deb", "debSha256": "$sha", "debParts": 3}"""
        val updater = AppUpdater(c.settings, c.platform, FakeInstaller(linux = true))
        val now = System.currentTimeMillis()

        c.settings.update { it.copy(lastUpdateCheck = now - 10 * 60_000L) }
        assertEquals(null, updater.check(automatic = true))
        assertEquals("pas de requête dix minutes après la dernière", 0, server.requestCount)

        c.settings.update { it.copy(lastUpdateCheck = now - AppUpdater.AUTO_CHECK_INTERVAL_MS - 60_000L) }
        assertEquals("9.9.9", updater.check(automatic = true)?.versionName)
        assertTrue(c.settings.get().lastUpdateCheck >= now)
    }

    private fun installed(i: FakeInstaller) = i.installed ?: ByteArray(0)
}
