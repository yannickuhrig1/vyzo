package fr.yannickuhrig.vyzo.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.ui.common.FormFactor
import fr.yannickuhrig.vyzo.ui.common.LocalDesktop
import fr.yannickuhrig.vyzo.ui.common.LocalFormFactor
import fr.yannickuhrig.vyzo.ui.theme.VyzoTheme
import fr.yannickuhrig.vyzo.ui.vod.PosterCard
import fr.yannickuhrig.vyzo.ui.vod.VodCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class PosterCardTest {
    @get:Rule
    val rule = sqliteThenComposeRule()

    private fun card(title: String = "Dune", rating: Float = 0f, uhd: Boolean = false, progress: Float = 0f) =
        VodCard(uid = title, title = title, rating = rating, uhd = uhd, progress = progress)

    private fun show(desktop: Boolean = true, vararg cards: VodCard) {
        rule.setContent {
            CompositionLocalProvider(LocalDesktop provides desktop, LocalFormFactor provides FormFactor.TV) {
                VyzoTheme {
                    Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        cards.forEach { PosterCard(it, onClick = {}, Modifier.width(170.dp).testTag(it.uid)) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun hoverWidens(): Boolean {
        val title = rule.onNodeWithText("Dune", useUnmergedTree = true)
        val before = title.getBoundsInRoot().let { it.right - it.left }
        rule.onNodeWithTag("Dune").performMouseInput { moveTo(center) }
        rule.waitForIdle()
        return title.getBoundsInRoot().let { it.right - it.left } > before
    }

    @Test
    fun laNoteEtLe4kSAffichent() {
        show(cards = arrayOf(card(rating = 8.4f, uhd = true)))
        rule.onNodeWithText("★", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("4K", useUnmergedTree = true).assertExists()
    }

    @Test
    fun sansDonneesAucunBadgeNEstInvente() {
        show(cards = arrayOf(card(rating = 44f)))
        rule.onAllNodesWithText("★", substring = true, useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithText("4K", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun uneAfficheManquanteMontreLesInitialesDuTitre() {
        show(cards = arrayOf(card("Canal Quatre")))
        rule.onNodeWithText("CQ", useUnmergedTree = true).assertExists()
    }

    @Test
    fun laCarteGrandeAuSurvolSurPc() {
        show(cards = arrayOf(card(uhd = true, rating = 7.5f, progress = 0.6f), card("Canal Quatre", rating = 6f), card("Sans note")))
        assertTrue(hoverWidens())
        ImageIO.write(rule.onRoot().captureToImage().toAwtImage(), "png", File("build/cartes-pc.png"))
    }

    @Test
    fun rienNeBougeAuSurvolHorsPc() {
        show(desktop = false, cards = arrayOf(card()))
        assertEquals(false, hoverWidens())
    }
}
