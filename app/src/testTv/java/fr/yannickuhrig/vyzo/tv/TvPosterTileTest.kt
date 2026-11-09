package fr.yannickuhrig.vyzo.tv

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.yannickuhrig.vyzo.ScreensBase
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.ui.home.VodItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class TvPosterTileTest : ScreensBase() {
    private fun film(rating: Float = 0f, uhd: Boolean = false) =
        VodItem(ContentKind.MOVIE, "1", "Dune", "", "", rating, "2021", "Science-fiction", "", uhd)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun show(item: VodItem, focused: Boolean = false, titleInside: Boolean = true) {
        compose.setContent {
            val input = LocalInputModeManager.current
            val focus = remember { FocusRequester() }
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            PosterTile(item, Modifier.focusRequester(focus).width(200.dp), {}, {}, titleInside)
            // Une image plus tard : la carte n'accepte le focus qu'une fois le mode télécommande actif.
            if (focused) LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
        }
        compose.waitForIdle()
    }

    @Test fun laNoteEtLe4kSAffichent() {
        show(film(rating = 8.4f, uhd = true))
        compose.onNodeWithText("★", substring = true).assertExists()
        compose.onNodeWithText("4K").assertExists()
    }

    @Test fun sousUneAfficheTitreeLaNoteNEstPasRepeteeMaisLe4kReste() {
        show(film(rating = 8.4f, uhd = true), titleInside = false)
        compose.onAllNodesWithText("★", substring = true).assertCountEquals(0)
        compose.onNodeWithText("4K").assertExists()
    }

    @Test fun uneNoteHorsEchelleNEstPasAffichee() {
        show(film(rating = 44f))
        compose.onAllNodesWithText("★", substring = true).assertCountEquals(0)
    }

    @Test fun sansDonneesAucunBadgeNInventeRien() {
        show(film())
        compose.onAllNodesWithText("★", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("4K").assertCountEquals(0)
    }

    @Test fun lAnneeNApparaitQueSurLAfficheSelectionnee() {
        show(film())
        compose.onAllNodesWithText("2021", substring = true).assertCountEquals(0)
    }

    @Test fun lAfficheSelectionneeMontreSonTitreEtSonAnnee() {
        show(film(), focused = true)
        compose.onNodeWithText("2021", substring = true).assertExists()
        compose.onAllNodesWithText("Dune").assertCountEquals(1)
    }

    @Test fun pasDeLegendeQuandLeTitreEstDejaSousLAffiche() {
        show(film(), focused = true, titleInside = false)
        compose.onAllNodesWithText("2021", substring = true).assertCountEquals(0)
    }
}
