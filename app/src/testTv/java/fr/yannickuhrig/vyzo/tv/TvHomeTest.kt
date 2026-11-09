package fr.yannickuhrig.vyzo.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.yannickuhrig.vyzo.FlavorScreens
import fr.yannickuhrig.vyzo.ScreensBase
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.ui.common.FormFactor
import fr.yannickuhrig.vyzo.ui.home.HomeActions
import org.junit.Assert.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class TvHomeLogicTest {
    @Test fun `les colonnes remplissent toute la largeur`() {
        val g = cardGrid(900.dp)
        assertEquals(900f, (g.poster * 7 + g.posterGap * 6).value, 0.01f)
        assertEquals(900f, (g.land * 6 + g.gap * 5).value, 0.01f)
        assertEquals(900f, (g.chan * 5 + g.gap * 4).value, 0.01f)
        assertEquals(900f, (g.air * 3 + g.gap * 2).value, 0.01f)
    }

    @Test fun `la rangee active remonte a sa hauteur`() {
        assertEquals(200f, pivotDistance(250f, 50f, canScrollBack = true, canScrollForward = true), 0f)
        assertEquals(-40f, pivotDistance(10f, 50f, canScrollBack = true, canScrollForward = true), 0f)
    }

    @Test fun `rien a defiler au bout de la liste ou deja en place`() {
        assertEquals(0f, pivotDistance(10f, 50f, canScrollBack = false, canScrollForward = true), 0f)
        assertEquals(0f, pivotDistance(250f, 50f, canScrollBack = true, canScrollForward = false), 0f)
        assertEquals(0f, pivotDistance(51f, 50f, canScrollBack = true, canScrollForward = true), 0f)
    }

    @Test fun `le titre mis en avant retrecit quand il est long`() {
        assertEquals(72, heroTitleSize("Le Grand Bain"))
        assertEquals(60, heroTitleSize("Un titre de film un peu plus long"))
        assertEquals(48, heroTitleSize("Un titre de film vraiment beaucoup trop long pour une ligne"))
    }

    @Test fun `progression bornee et sans division par zero`() {
        assertEquals(0.5f, programProgress(0, 100, 50), 0f)
        assertEquals(1f, programProgress(0, 100, 500), 0f)
        assertEquals(0f, programProgress(100, 100, 100), 0f)
    }

    @Test fun `initiales et premier genre`() {
        assertEquals("LG", initialsOf("Le Grand Bain"))
        assertEquals("Drame", firstGenre("Drame, Action"))
        assertEquals("Drame", firstGenre(" Drame / Action"))
    }

    @Test fun `la variante TV fournit son accueil`() {
        assertNotNull(FlavorScreens.home)
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class TvHomeScreenTest : ScreensBase() {
    private val actions = HomeActions({}, {}, { _, _ -> }, {})

    @OptIn(ExperimentalTestApi::class)
    private fun pressDown() {
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
    }

    /** Le simulateur démarre en mode tactile, où les cartes ne prennent pas le focus ; un téléviseur est en mode télécommande. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun HomeWithRemote() {
        val input = LocalInputModeManager.current
        SideEffect { input.requestInputMode(InputMode.Keyboard) }
        TvHome(actions)
    }

    /** À l'ouverture, la télécommande arrive directement sur la première carte. */
    @Test fun accueil() {
        shoot("tv_16_accueil_cinema", FormFactor.TV, 3000) { HomeWithRemote() }
        compose.onNode(isFocused()).assertExists()
    }

    /** ▼ descend d'une rangée : elle remonte sous le titre et la précédente disparaît. */
    @Test fun rangeeSuivante() {
        var before = 0f
        shoot("tv_17_accueil_rangee_suivante", FormFactor.TV, 3000, after = {
            before = focusedTop()
            pressDown()
            // 1,5 s d'animation, image par image : au-delà, le défilement serait perçu comme lent.
            repeat(90) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
        }) { HomeWithRemote() }
        // Haut de la liste à 382 px à l'écran : la carte finit juste sous le titre de sa rangée, là où était la précédente.
        assertEquals(before, focusedTop(), 30f)
    }

    private fun focusedTop(): Float = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.top

    /** Avec de vraies images : l'affiche de la carte et le fond plein écran du titre sélectionné. */
    @Test fun avecImages() {
        addPictures()
        shoot("tv_18_accueil_images", FormFactor.TV, 3000, after = { pressDownAndSettle() }) { HomeWithRemote() }
    }

    private fun pressDownAndSettle() {
        pressDown()
        repeat(120) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        // Les images se chargent sur un autre fil, en temps réel.
        Thread.sleep(1500)
        repeat(60) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    private fun addPictures() = runBlocking {
        val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, 640f, 360f, 0xFFE0566B.toInt(), 0xFF3F8EE8.toInt(), Shader.TileMode.CLAMP) }
        Canvas(bmp).drawRect(0f, 0f, 640f, 360f, paint)
        val file = java.io.File.createTempFile("affiche", ".png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Simulateur : la bibliothèque native s'initialise ici, car sur le fil de décodage des images elle provoque un arrêt brutal.
        bmp.copyPixelsToBuffer(java.nio.IntBuffer.allocate(640 * 360))
        val url = "file://${file.absolutePath}"
        val c = app.container
        val movies = c.repo.movies(Repository.KEY_RECENT_ADDED, 30).first()
        c.db.movies().upsert(movies.map { it.copy(poster = url, backdrop = url) })
    }
}
