package fr.yannickuhrig.vyzo.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import fr.yannickuhrig.vyzo.closeForTest
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.remote.ConfigReceiver
import fr.yannickuhrig.vyzo.data.remote.RemoteEvent
import fr.yannickuhrig.vyzo.data.sync.SyncState
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.LocalDesktop
import fr.yannickuhrig.vyzo.ui.remote.SendToTvFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

/** « Envoyer vers une TV » de bout en bout : l'écran du téléphone (ici PC) envoie à un vrai récepteur local. */
class SendToTvFlowTest {
    @get:Rule
    val rule = sqliteThenComposeRule()

    private val dir = Files.createTempDirectory("vyzo-envoi").toFile()
    private val c = testContainer(dir)
    private val receiver = ConfigReceiver("Salon", { SyncState() }, { 1 }, ipProvider = { "127.0.0.1" }, preferredPort = 0)
    private val scope = CoroutineScope(Dispatchers.Default)

    @After
    fun tearDown() {
        receiver.stop()
        scope.cancel()
        c.closeForTest()
        dir.deleteRecursively()
    }

    @Test
    fun seulLaCocheArriveSurLaTv() {
        runBlocking {
            c.repo.addPlaylist(PlaylistEntity(name = "Gardée", type = SourceType.M3U, url = "file:///gardee"))
            c.repo.addPlaylist(PlaylistEntity(name = "Décochée", type = SourceType.XTREAM, url = "http://srv", username = "bob", password = "secret-decoche"))
        }
        val events = mutableListOf<RemoteEvent>()
        receiver.start()
        scope.launch { receiver.events.collect { synchronized(events) { events += it } } }
        rule.waitUntil(5_000) { receiver.url.value != null }

        var closed = false
        rule.setContent {
            CompositionLocalProvider(LocalContainer provides c, LocalDesktop provides true) {
                MaterialTheme { SendToTvFlow(onClose = { closed = true }) }
            }
        }
        rule.onNode(hasSetTextAction()).performTextInput(receiver.url.value!!)
        rule.onNode(hasText("Continuer")).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Décochée")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Décochée")).performClick()
        rule.onNode(hasText("Envoyer")).performClick()

        rule.waitUntil(10_000) { synchronized(events) { events.any { it is RemoteEvent.ConfigReceived } } || closed }
        assertFalse("le parcours ne doit pas se fermer pendant l'envoi", closed)
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Accepte l'import sur l'écran de la TV…")).fetchSemanticsNodes().isNotEmpty() }
        val sent = synchronized(events) { events.filterIsInstance<RemoteEvent.ConfigReceived>().single() }
        assertTrue(sent.selective)
        assertTrue(sent.json.contains("file:///gardee"))
        assertFalse("une playlist décochée ne quitte pas le téléphone", sent.json.contains("secret-decoche"))
    }
}
