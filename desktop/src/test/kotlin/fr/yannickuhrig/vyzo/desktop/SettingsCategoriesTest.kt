package fr.yannickuhrig.vyzo.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.closeForTest
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.LocalDesktop
import fr.yannickuhrig.vyzo.ui.settings.SettingsScreen
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

/** Réglages rangés en 6 catégories, en vue maître/détail (écran large) comme en accordéon avec recherche (écran étroit). */
class SettingsCategoriesTest {
    @get:Rule
    val rule = sqliteThenComposeRule()

    private val dir = Files.createTempDirectory("vyzo-reglages").toFile()
    private val c = testContainer(dir)

    private val categories = listOf(
        "Sources, Profils & EPG",
        "Lecture, PiP & Sous-titres",
        "Chaînes, Groupes & Métadonnées",
        "Cloud, Enregistrements & Hors-Ligne",
        "Confidentialité & Coffre-fort",
        "Mises à jour & À propos",
    )

    @After
    fun tearDown() {
        c.closeForTest()
        dir.deleteRecursively()
    }

    private fun show(width: Dp) {
        rule.setContent {
            CompositionLocalProvider(LocalContainer provides c, LocalDesktop provides true) {
                MaterialTheme { Box(Modifier.size(width, 740.dp)) { SettingsScreen(onEditPlaylist = {}, onEpgSources = {}, onGroups = {}) } }
            }
        }
    }

    private fun exists(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun ecranLargeAfficheLesSixCategoriesEtLeurDetail() {
        show(1000.dp)
        categories.forEach { assertTrue("catégorie absente : $it", exists(it)) }
        // La première catégorie est ouverte d'office.
        assertTrue(exists("Sources du guide (EPG)"))
        assertFalse(exists("Rechercher au lancement"))

        rule.onAllNodes(hasText("Mises à jour & À propos"))[0].performClick()
        rule.waitForIdle()
        assertTrue(exists("Rechercher au lancement"))
        assertTrue(exists("Journal détaillé"))
        assertFalse(exists("Sources du guide (EPG)"))
    }

    @Test
    fun ecranEtroitAccordeonEtRecherche() {
        show(400.dp)
        assertTrue(exists("Cloud Sync"))
        assertTrue(exists("Coffre-fort"))
        categories.forEach { assertTrue("catégorie absente : $it", exists(it)) }
        assertFalse(exists("Décodeur"))

        // L'accordéon déplie une catégorie au toucher.
        rule.onNode(hasText("Lecture, PiP & Sous-titres")).performClick()
        rule.waitForIdle()
        assertTrue(exists("Décodeur"))
        assertTrue(exists("Mémoire tampon"))
        rule.onNode(hasText("Lecture, PiP & Sous-titres")).performClick()
        rule.waitForIdle()

        // La recherche ignore la casse et les accents, et ne garde que les réglages qui correspondent.
        rule.onNode(hasSetTextAction()).performTextInput("decodeur")
        rule.waitForIdle()
        assertTrue(exists("Décodeur"))
        assertFalse(exists("Mémoire tampon"))
        assertFalse(exists("Confidentialité & Coffre-fort"))

        // Recherche en début de mot : « cle » trouve les clés API, pas « déclenche ».
        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput("cle")
        rule.waitForIdle()
        assertTrue(exists("Clé API OpenSubtitles"))
        assertTrue(exists("Clé API TheMovieDB (TMDB)"))
        assertFalse(exists("Exécuter les enregistrements programmés sur cet appareil"))

        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput("decodeur introuvable")
        rule.waitForIdle()
        assertTrue(exists("Aucun réglage ne correspond à « decodeur introuvable »"))
    }
}
