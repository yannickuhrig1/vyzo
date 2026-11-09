package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.remote.ConfigReceiver
import fr.yannickuhrig.vyzo.data.remote.ConfigSender
import fr.yannickuhrig.vyzo.data.remote.QrContent
import fr.yannickuhrig.vyzo.data.remote.RemoteEvent
import fr.yannickuhrig.vyzo.data.remote.RemotePhase
import fr.yannickuhrig.vyzo.data.remote.RemotePlaylist
import fr.yannickuhrig.vyzo.data.remote.ScannedCode
import fr.yannickuhrig.vyzo.data.sync.SyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteConfigTest {

    @Test
    fun parsesScannedCodes() {
        val url = QrContent.deviceUrl("192.168.1.42", 8642, "AB12CD")
        assertEquals("http://192.168.1.42:8642/?k=AB12CD&vyzo=1", url)
        assertEquals(ScannedCode.VyzoDevice("http://192.168.1.42:8642", "AB12CD"), QrContent.parse(url))
        // QR code d'une TV encore sous l'ancien nom (Zapette 1.x)
        val legacy = QrContent.parse("http://192.168.1.42:8642/?k=AB12CD&zapette=1")
        assertEquals(ScannedCode.VyzoDevice("http://192.168.1.42:8642", "AB12CD"), legacy)
        // QR code de connexion au compte : à ouvrir dans le navigateur, pas à traiter comme une TV qui reçoit une configuration
        val account = url.replace("/?k=", "/compte?k=")
        assertEquals(ScannedCode.VyzoAccount(account), QrContent.parse(account))

        val xtream = QrContent.parse("http://srv.tv:8080/get.php?username=bob&password=pw&type=m3u_plus") as ScannedCode.Playlist
        assertEquals(SourceType.XTREAM, xtream.playlist.sourceType)
        assertEquals("http://srv.tv:8080", xtream.playlist.url)
        assertEquals("bob", xtream.playlist.username)

        val stalker = QrContent.parse("Portail : http://portail.tv:80/c/\nMAC : 00-1a-79-12-34-56") as ScannedCode.Playlist
        assertEquals(SourceType.STALKER, stalker.playlist.sourceType)
        assertEquals("00:1A:79:12:34:56", stalker.playlist.mac)

        val m3u = QrContent.parse("https://exemple.org/liste.m3u8") as ScannedCode.Playlist
        assertEquals(SourceType.M3U, m3u.playlist.sourceType)

        val json = QrContent.parse("""{"type":"xtream","url":"http://a.b:1","username":"u","password":"p","name":"Salon"}""") as ScannedCode.Playlist
        assertEquals("Salon", json.playlist.name)

        val deep = QrContent.parse("vyzo://playlist?type=stalker&url=http%3A%2F%2Fp.tv%2Fc%2F&mac=00%3A1A%3A79%3AAA%3ABB%3ACC") as ScannedCode.Playlist
        assertEquals("http://p.tv/c/", deep.playlist.url)
        assertNull(deep.playlist.validate())
        val legacyDeep = QrContent.parse("zapette://playlist?type=m3u&url=http%3A%2F%2Fl.tv%2Fa.m3u") as ScannedCode.Playlist
        assertEquals(SourceType.M3U, legacyDeep.playlist.sourceType)

        assertTrue(QrContent.parse("bonjour") is ScannedCode.Unknown)
    }

    @Test
    fun accountReceiverRefusesConfigAndPlaylist() = runBlocking {
        val receiver = ConfigReceiver(
            deviceName = "Salon",
            syncState = { SyncState() },
            inspectConfig = { 2 },
            ipProvider = { "127.0.0.1" },
            preferredPort = 0,
            accountOnly = true,
        )
        val received = CompletableDeferred<RemoteEvent>()
        val collector = CoroutineScope(Dispatchers.IO).launch {
            receiver.events.collect { received.complete(it) }
        }
        receiver.start()
        try {
            val url = withTimeout(5_000) { receiver.url.first { it != null } }!!
            val device = QrContent.parse(url) as ScannedCode.VyzoDevice
            val http = OkHttpClient()
            // Une version du téléphone qui envoie quand même sa configuration reçoit une erreur claire au lieu d'attendre sans fin.
            for (send in listOf<suspend () -> Unit>(
                { ConfigSender.sendConfig(device, """{"app":"Vyzo","playlists":[]}""") },
                { ConfigSender.sendSelection(device, """{"app":"Vyzo","playlists":[]}""") },
                { ConfigSender.sendPlaylist(device, RemotePlaylist("m3u", url = "http://a/b.m3u")) },
            )) {
                try {
                    send()
                    fail("devait être refusé")
                } catch (e: Exception) {
                    assertTrue(e.message!!.contains("appareil photo"))
                }
            }
            assertEquals(RemotePhase.WAITING, receiver.phase)
            assertEquals("waiting", ConfigSender.status(device).state)

            http.newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals(200, it.code)
                assertTrue(it.body!!.string().contains("/api/account"))
            }
            http.newCall(
                Request.Builder().url("${device.baseUrl}/api/account?k=${device.token}")
                    .post("""{"email":"a@b.fr","password":"pw"}""".toRequestBody("application/json".toMediaType())).build(),
            ).execute().use { assertEquals(200, it.code) }
            assertEquals(RemoteEvent.AccountReceived("a@b.fr", "pw"), withTimeout(5_000) { received.await() })
        } finally {
            receiver.stop()
            collector.cancel()
        }
    }

    @Test
    fun validatesPlaylists() {
        assertEquals("Identifiant et mot de passe requis", RemotePlaylist("xtream", url = "http://a").validate())
        assertTrue(RemotePlaylist("stalker", url = "http://a", mac = "00:1A").validate()!!.contains("MAC"))
        assertEquals("Type de source inconnu", RemotePlaylist("ftp", url = "http://a").validate())
        assertNull(RemotePlaylist("m3u", url = "http://a/b.m3u").validate())
        assertEquals("a", RemotePlaylist("m3u", url = "http://a/b.m3u").toEntity().name)
    }

    @Test
    fun receiverServesFormAndAcceptsPlaylistAndConfig() = runBlocking {
        var sync = SyncState()
        val receiver = ConfigReceiver(
            deviceName = "Salon <TV>",
            syncState = { sync },
            inspectConfig = { if (it.contains("\"app\":\"Vyzo\"")) 2 else null },
            ipProvider = { "127.0.0.1" },
            preferredPort = 0,
        )
        val events = mutableListOf<RemoteEvent>()
        val firstTwo = CompletableDeferred<Unit>()
        val collector = CoroutineScope(Dispatchers.IO).launch {
            receiver.events.collect { events += it; if (events.size == 2) firstTwo.complete(Unit) }
        }
        receiver.start()
        try {
            val url = withTimeout(5_000) { receiver.url.first { it != null } }!!
            val device = QrContent.parse(url) as ScannedCode.VyzoDevice
            assertEquals(receiver.token, device.token)
            val http = OkHttpClient()
            val type = "application/json".toMediaType()

            // Formulaire web : refusé sans jeton, servi avec.
            http.newCall(Request.Builder().url(device.baseUrl + "/").build()).execute().use { assertEquals(403, it.code) }
            http.newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals(200, it.code)
                val page = it.body!!.string()
                assertTrue(page.contains("Envoyer à la TV"))
                assertTrue(page.contains("Salon &lt;TV&gt;"))
                assertTrue(page.contains("var K=\"${receiver.token}\""))
                java.io.File("build/formulaire-telephone.html").writeText(page)
            }

            // Playlist invalide puis valide (via le client du téléphone)
            http.newCall(
                Request.Builder().url("${device.baseUrl}/api/playlist?k=${device.token}")
                    .post("""{"type":"xtream","url":"http://a"}""".toRequestBody(type)).build(),
            ).execute().use { assertEquals(400, it.code) }
            assertEquals("Salon <TV>", ConfigSender.deviceName(device))
            ConfigSender.sendPlaylist(device, RemotePlaylist("xtream", url = "http://srv:8080", username = "bob", password = "pw"))
            assertEquals(RemotePhase.LOADING, receiver.phase)
            assertEquals("loading", ConfigSender.status(device).state)
            sync = SyncState(running = true, message = "Mon abonnement : 120 chaînes")
            assertEquals("Mon abonnement : 120 chaînes", ConfigSender.status(device).message)
            sync = SyncState(running = false, lastFinished = System.currentTimeMillis() + 1)
            assertEquals("done", ConfigSender.status(device).state)

            // Configuration complète : invalide puis valide, en attente de confirmation sur la TV
            try {
                ConfigSender.sendConfig(device, """{"autre":"chose"}""")
                fail("devait être refusé")
            } catch (e: Exception) {
                assertTrue(e.message!!.contains("configuration Vyzo"))
            }
            ConfigSender.sendConfig(device, """{"app":"Vyzo","playlists":[]}""")
            assertEquals("pending", ConfigSender.status(device).state)
            receiver.markRejected()
            assertEquals("rejected", ConfigSender.status(device).state)
            // Après une connexion au compte, le téléphone suit bien l'import qu'il envoie ensuite.
            receiver.setAccountResult(true, "connectée")
            assertEquals("done", ConfigSender.status(device).state)
            ConfigSender.sendSelection(device, """{"app":"Vyzo","playlists":[]}""")
            assertEquals("pending", ConfigSender.status(device).state)

            withTimeout(5_000) { firstTwo.await() }
            val p = (events[0] as RemoteEvent.PlaylistReceived).playlist
            assertEquals("bob", p.username)
            assertEquals(2, (events[1] as RemoteEvent.ConfigReceived).playlists)

            // Mauvais jeton
            val bad = ScannedCode.VyzoDevice(device.baseUrl, "XXXXXX")
            try {
                ConfigSender.status(bad)
                fail("devait être refusé")
            } catch (e: Exception) {
                assertTrue(e.message!!.contains("Code invalide"))
            }
        } finally {
            receiver.stop()
            collector.cancel()
        }
    }
}
