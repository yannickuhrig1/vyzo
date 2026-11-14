package fr.yannickuhrig.vyzo.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.FakeSupabase
import fr.yannickuhrig.vyzo.closeForTest
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.LocalDesktop
import fr.yannickuhrig.vyzo.ui.settings.AccountScreen
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

/** Écran du compte sur PC : après « Se connecter », il passe à l'état connecté sans relancer l'appli. */
class AccountLoginUiTest {
    @get:Rule
    val rule = sqliteThenComposeRule()

    private val fake = FakeSupabase()
    private val supa = MockWebServer().apply { dispatcher = fake; start() }
    private val dir = Files.createTempDirectory("vyzo-compte-ui").toFile()
    private val c = testContainer(dir, fakeCloudUrl = supa.url("/").toString().trimEnd('/'))

    @After
    fun tearDown() {
        c.closeForTest()
        supa.shutdown()
        dir.deleteRecursively()
    }

    private fun exists(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun seConnecterAfficheLeCompteSansRelancer() {
        rule.setContent {
            CompositionLocalProvider(LocalContainer provides c, LocalDesktop provides true) {
                MaterialTheme { Box(Modifier.size(900.dp, 900.dp)) { AccountScreen() } }
            }
        }
        rule.onNode(hasSetTextAction() and hasText("Adresse e-mail")).performTextInput("foyer@example.fr")
        rule.onNode(hasSetTextAction() and hasText("Mot de passe")).performTextInput("motdepasse")
        rule.onNode(hasText("Se connecter")).performClick()
        rule.waitUntil(15_000) { exists("foyer@example.fr") && !exists("Adresse e-mail") }
    }
}
