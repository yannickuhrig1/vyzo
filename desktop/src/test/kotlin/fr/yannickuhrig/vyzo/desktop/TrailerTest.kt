package fr.yannickuhrig.vyzo.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.yannickuhrig.vyzo.closeForTest
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.vod.TrailerDialog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

/** Rapport § 3.12 : bande-annonce sans clé TMDB (instances Invidious mortes, navigateur ouvert sans prévenir). */
class TrailerTest {
    @get:Rule
    val rule = sqliteThenComposeRule()

    private val dir = Files.createTempDirectory("vyzo-trailer").toFile()
    private val c = testContainer(dir)

    @After
    fun tearDown() {
        c.closeForTest()
        dir.deleteRecursively()
    }

    @Test
    fun laFicheNAttendPlusDeServeurExterne() = runBlocking {
        val t0 = System.currentTimeMillis()
        val withId = c.tmdb.enrichMovie("FR | Noise (2025) 4K", rawTrailer = "https://youtu.be/dQw4w9WgXcQ")
        val search = c.tmdb.enrichMovie("FR | Noise (2025) 4K")
        // Sans clé TMDB, plus aucun appel réseau : avant, jusqu'à 3 × 4 s d'attente sur des instances Invidious.
        assertTrue("${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 1_000)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", withId.trailerWebUrl)
        assertEquals("https://www.youtube.com/results?search_query=Noise+bande+annonce+vf", search.trailerWebUrl)
    }

    @Test
    fun laFenetreDitOuSOuvreLaBandeAnnonce() {
        var id by mutableStateOf<String?>("dQw4w9WgXcQ")
        var closed = false
        rule.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                MaterialTheme { TrailerDialog(title = "FR | Noise (2025) 4K", youtubeId = id, onDismiss = { closed = true }) }
            }
        }
        rule.onNodeWithText("La bande-annonce s'ouvre sur YouTube, dans ton navigateur.").assertExists()
        rule.onNodeWithText("Ouvrir YouTube").assertExists()

        id = null
        rule.waitForIdle()
        rule.onNodeWithText("Pas de bande-annonce connue pour ce titre : une recherche s'ouvre sur YouTube, dans ton navigateur.").assertExists()
        rule.onNodeWithText("Rechercher sur YouTube").assertExists()

        rule.onNodeWithText("Annuler").performClick()
        rule.waitForIdle()
        assertTrue(closed)
    }
}
