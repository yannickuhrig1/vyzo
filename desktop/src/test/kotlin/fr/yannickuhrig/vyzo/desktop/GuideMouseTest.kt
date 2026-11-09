package fr.yannickuhrig.vyzo.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import fr.yannickuhrig.vyzo.closeForTest
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.ui.common.FormFactor
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.LocalDesktop
import fr.yannickuhrig.vyzo.ui.common.LocalFormFactor
import fr.yannickuhrig.vyzo.ui.guide.Cell
import fr.yannickuhrig.vyzo.ui.guide.GuideScreen
import fr.yannickuhrig.vyzo.ui.guide.dayStartOf
import fr.yannickuhrig.vyzo.ui.guide.guideDays
import fr.yannickuhrig.vyzo.ui.guide.guideHeaderLabel
import fr.yannickuhrig.vyzo.ui.guide.sameTimeOn
import fr.yannickuhrig.vyzo.ui.guide.slotStart
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

/** Guide TV de Vyzo PC (interface TV) à la souris : clic, 2e clic, clic droit, molette horizontale, choix du jour. */
@OptIn(ExperimentalTestApi::class)
class GuideMouseTest {
    @get:Rule
    val rule = sqliteThenComposeRule()

    private val dir = Files.createTempDirectory("vyzo-guide").toFile()
    private val c = testContainer(dir)
    private val now = System.currentTimeMillis()
    private val h = 3_600_000L
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
    private val tomorrowMorning = sameTimeOn(dayStartOf(now) + 24 * h + 2 * h, now)

    @After
    fun tearDown() {
        owner.viewModelStore.clear()
        c.closeForTest()
        dir.deleteRecursively()
    }

    private fun show() {
        runBlocking {
            val pid = c.db.playlists().insert(PlaylistEntity(name = "Locale", type = SourceType.M3U, url = "file:///dev/null"))
            c.db.channels().upsert(
                listOf("TF1" to "tf1", "France 2" to "f2").mapIndexed { i, (name, key) ->
                    ChannelEntity(
                        uid = "$pid:$key", playlistId = pid, remoteId = key, name = name, nameNorm = name.lowercase(), matchKey = key,
                        number = i + 1, logo = "", categoryUid = "", epgId = key, epgKey = key, streamUrl = "file:///dev/null",
                        position = i, syncToken = 1,
                    )
                },
            )
            fun p(key: String, start: Long, stop: Long, title: String) = ProgramEntity(channelKey = key, start = start, stop = stop, title = title, sourceKey = "t", generation = 1)
            c.db.programs().insertAll(
                listOf(
                    p("tf1", now - h / 2, now + h / 2, "Journal"),
                    p("tf1", now + h / 2, now + 2 * h, "Film du soir"),
                    p("tf1", tomorrowMorning - h / 2, tomorrowMorning + h, "Matinale de demain"),
                    p("f2", now - h / 6, now + h, "Série en cours"),
                ),
            )
        }
        rule.setContent {
            CompositionLocalProvider(LocalContainer provides c, LocalDesktop provides true, LocalFormFactor provides FormFactor.TV,
                LocalViewModelStoreOwner provides owner,
            ) {
                MaterialTheme { Box(Modifier.size(1400.dp, 800.dp)) { GuideScreen(onWatch = { _, _ -> }, onCatchup = { _, _ -> }) } }
            }
        }
        rule.waitUntil(10_000) { exists("Film du soir") }
    }

    private fun exists(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun centerOf(text: String): Offset = rule.onAllNodes(hasText(text))[0].fetchSemanticsNode().boundsInRoot.center

    @Test
    fun enTeteEtJoursDuGuide() {
        val film = Cell(now + h, now + 2 * h, null)
        assertEquals("${Fmt.day(film.start, now)} · ${Fmt.time(film.start)}", guideHeaderLabel(film, now))
        // Case en cours : l'heure qu'il est, pas celle du début de la case.
        assertEquals("Aujourd'hui · ${Fmt.time(now)}", guideHeaderLabel(Cell(now - h, now + h, null), now))
        val days = guideDays(now - 24 * h, now + 3 * 24 * h)
        assertTrue(days.size in 4..5)
        assertEquals(dayStartOf(now), days[1])
        assertEquals(Fmt.time(now), Fmt.time(sameTimeOn(days[2], now)))
    }

    @Test
    fun unClicChoisitLaCaseUnDeuxiemeOuvreSonMenu() {
        show()
        val at = centerOf("Film du soir")
        rule.onRoot().performMouseInput { click(at) }
        rule.waitForIdle()
        val start = now + h / 2
        assertTrue("l'en-tête doit montrer la case choisie", exists("${Fmt.day(start, now)} · ${Fmt.time(start)}"))
        assertFalse(exists("Me le rappeler"))

        rule.onRoot().performMouseInput { click(at) }
        rule.waitForIdle()
        assertTrue("2e clic : menu du programme", exists("Me le rappeler"))
    }

    @Test
    fun clicDroitOuvreLeMenuDirectement() {
        show()
        val at = centerOf("Série en cours")
        rule.onRoot().performMouseInput { rightClick(at) }
        rule.waitForIdle()
        assertTrue(exists("Regarder en direct"))
    }

    @Test
    fun laMoletteHorizontaleAvanceDansLeTemps() {
        show()
        val later = Fmt.time(slotStart(now) + 5 * h)
        assertFalse("$later ne doit pas encore être affiché", exists(later))

        // Molette verticale seule : on fait défiler les chaînes, pas le temps.
        rule.onNode(hasScrollAction()).performMouseInput { moveTo(center); scroll(4f, ScrollWheel.Vertical) }
        rule.waitForIdle()
        assertFalse(exists(later))

        rule.onNode(hasScrollAction()).performMouseInput { moveTo(center); scroll(6f, ScrollWheel.Horizontal) }
        rule.waitForIdle()
        assertTrue("6 crans = 3 h plus tard", exists(later))
        assertFalse("le début de la grille est passé", exists(Fmt.time(slotStart(now))))
    }

    @Test
    fun choixDuJourDansLEnTete() {
        show()
        assertFalse(exists("Matinale de demain"))
        rule.onAllNodes(hasText("Aujourd'hui") and hasClickAction())[0].performClick()
        rule.waitForIdle()
        assertTrue(exists("Jour affiché"))
        rule.onAllNodes(hasText("Demain") and hasClickAction())[0].performClick()
        rule.waitForIdle()
        assertTrue("le guide passe à demain, même heure", exists("Matinale de demain"))
    }
}
