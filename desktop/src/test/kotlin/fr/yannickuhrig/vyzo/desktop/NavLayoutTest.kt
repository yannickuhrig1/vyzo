package fr.yannickuhrig.vyzo.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import fr.yannickuhrig.vyzo.closeForTest
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.prefs.AppSettings
import fr.yannickuhrig.vyzo.ui.MainMenu
import fr.yannickuhrig.vyzo.ui.common.FormFactor
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.LocalDesktop
import fr.yannickuhrig.vyzo.ui.common.LocalFormFactor
import fr.yannickuhrig.vyzo.ui.theme.VyzoTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import fr.yannickuhrig.vyzo.ui.NavLayout
import fr.yannickuhrig.vyzo.ui.NavPosition
import fr.yannickuhrig.vyzo.ui.NavStyle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/** Position du menu principal (Réglages → Interface) : barre en haut ou en bas, rail à gauche ou à droite. */
@OptIn(ExperimentalTestApi::class)
class NavLayoutTest {
    @get:Rule
    val rule = sqliteThenComposeRule()

    private val dir = Files.createTempDirectory("vyzo-nav").toFile()
    private val c = testContainer(dir)
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }


    @After
    fun tearDown() {
        owner.viewModelStore.clear()
        c.closeForTest()
        dir.deleteRecursively()
    }

    @Test
    fun placeReserveeSelonLaPosition() {
        assertEquals(NavLayout.RAIL_CLOSED, NavLayout.from(AppSettings()).reserved)
        assertEquals(NavLayout.RAIL_OPEN, NavLayout.from(AppSettings(navStyle = "EXPANDED")).reserved)
        assertEquals(0.dp, NavLayout.from(AppSettings(navPosition = "RIGHT", navStyle = "HIDDEN")).reserved)
        // Barre : toujours la même hauteur, le style du rail ne compte pas.
        assertEquals(NavLayout.BAR_HEIGHT, NavLayout.from(AppSettings(navPosition = "TOP", navStyle = "HIDDEN")).reserved)
        // Valeur inconnue (ancienne version, fichier abîmé) : rail replié à gauche.
        assertEquals(NavLayout(NavPosition.LEFT, NavStyle.AUTO), NavLayout.from(AppSettings(navPosition = "??", navStyle = "??")))
    }

    private fun show(position: String, style: String = "AUTO") {
        runBlocking { c.db.playlists().insert(PlaylistEntity(name = "Locale", type = SourceType.M3U, url = "file:///dev/null")) }
        val layout = NavLayout.from(AppSettings(navPosition = position, navStyle = style))
        rule.setContent {
            CompositionLocalProvider(
                LocalContainer provides c, LocalDesktop provides true, LocalFormFactor provides FormFactor.TV,
                LocalViewModelStoreOwner provides owner,
            ) {
                VyzoTheme {
                    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                        Box(Modifier.size(1280.dp, 720.dp)) {
                            // Contenu factice à la place de l'écran, dans la place laissée par le menu.
                            Box(
                                Modifier.fillMaxSize().padding(
                                    start = if (layout.position == NavPosition.LEFT) layout.reserved else 0.dp,
                                    end = if (layout.position == NavPosition.RIGHT) layout.reserved else 0.dp,
                                    top = if (layout.position == NavPosition.TOP) layout.reserved else 0.dp,
                                    bottom = if (layout.position == NavPosition.BOTTOM) layout.reserved else 0.dp,
                                ).background(androidx.compose.ui.graphics.Color(0xFF1A2140)),
                            )
                            MainMenu(layout, route = "home", onSelect = {})
                        }
                    }
                }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodes(hasContentDescription("Films")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun bounds(desc: String) = rule.onAllNodes(hasContentDescription(desc))[0].fetchSemanticsNode().boundsInRoot

    @Test
    fun barreEnHaut() {
        show("TOP")
        // Les entrées sont côte à côte, en haut de l'écran.
        val films = bounds("Films")
        val series = bounds("Séries")
        assertTrue("même ligne", kotlin.math.abs(films.top - series.top) < 2f)
        assertTrue("à droite de Films", series.left > films.right)
        assertTrue("en haut", films.bottom < 80f)
        // L'écran affiché (l'accueil) montre son nom dans la barre, les autres seulement leur icône.
        assertTrue(rule.onAllNodes(hasText("Accueil")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(hasText("Films")).fetchSemanticsNodes().isEmpty())
        ImageIO.write(rule.onRoot().captureToImage().toAwtImage(), "png", File("build/nav-top.png"))
    }

    @Test
    fun railADroite() {
        show("RIGHT")
        val films = bounds("Films")
        val series = bounds("Séries")
        assertTrue("l'une sous l'autre", series.top > films.bottom)
        val width = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertTrue("collé au bord droit", films.left > width - 100f)
        ImageIO.write(rule.onRoot().captureToImage().toAwtImage(), "png", File("build/nav-right.png"))
    }
}
