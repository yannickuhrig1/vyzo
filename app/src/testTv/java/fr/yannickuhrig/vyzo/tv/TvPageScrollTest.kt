package fr.yannickuhrig.vyzo.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Fiche d'une série : la page défile, la liste des épisodes est dedans, et les deux suivent la sélection comme sur un téléviseur.
 * Au dernier épisode, la liste ne pouvait plus avancer et la page prenait le relais : É10 de Mr. Robot (saison 3) devenait inatteignable.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class TvPageScrollTest {
    @get:Rule val compose = createComposeRule()

    /** Comme sur un téléviseur : l'élément sélectionné est amené au tiers haut, même au bout de la liste. */
    @OptIn(ExperimentalFoundationApi::class)
    private val tvPivot = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = offset - containerSize * 0.3f
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun pageAfterLastEpisode(guarded: Boolean): Pair<Int, Int> {
        val page = LazyListState(firstVisibleItemIndex = 1)
        var episodes: LazyListState? = null
        val last = FocusRequester()
        compose.setContent {
            CompositionLocalProvider(LocalBringIntoViewSpec provides FrozenSpec(tvPivot) { guarded }) {
                LazyColumn(Modifier.fillMaxSize(), state = page) {
                    item { Box(Modifier.fillMaxWidth().height(200.dp).focusable()) }
                    item {
                        // La liste est au bout : le dernier épisode est à l'écran, prêt à être sélectionné.
                        val list = remember { LazyListState(firstVisibleItemIndex = 6) }.also { episodes = it }
                        Box(Modifier.fillMaxWidth().height(400.dp)) {
                            CompositionLocalProvider(LocalBringIntoViewSpec provides if (guarded) PivotSpec(120f) { list } else tvPivot) {
                                LazyColumn(Modifier.fillMaxSize(), state = list) {
                                    items(10) { i ->
                                        Box(Modifier.fillMaxWidth().height(100.dp).then(if (i == 9) Modifier.focusRequester(last) else Modifier).focusable())
                                    }
                                }
                            }
                        }
                    }
                    item { Box(Modifier.fillMaxWidth().height(600.dp).focusable()) }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(1 to 0, page.firstVisibleItemIndex to page.firstVisibleItemScrollOffset)
        compose.runOnIdle { last.requestFocus() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        if (guarded) assertEquals("La liste des épisodes reste au bout", 6, episodes!!.firstVisibleItemIndex)
        return page.firstVisibleItemIndex to page.firstVisibleItemScrollOffset
    }

    @Test fun sansGardeLaPagePartVersLeBas() {
        assertNotEquals(1 to 0, pageAfterLastEpisode(guarded = false))
    }

    @Test fun laPageNeBougePasAuDernierEpisode() {
        assertEquals(1 to 0, pageAfterLastEpisode(guarded = true))
    }
}
