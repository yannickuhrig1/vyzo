package fr.yannickuhrig.vyzo.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.yannickuhrig.vyzo.FlavorScreens
import fr.yannickuhrig.vyzo.ScreensBase
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.DownloadEntity
import fr.yannickuhrig.vyzo.data.db.DownloadStatus
import fr.yannickuhrig.vyzo.data.tmdb.CastMember
import fr.yannickuhrig.vyzo.data.tmdb.TmdbEnrichment
import fr.yannickuhrig.vyzo.ui.common.FormFactor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class TvDetailLogicTest {
    @Test fun `un film se reprend entre 30 secondes et 95 pour cent`() {
        assertFalse(isResumable(30_000, 100_000))
        assertTrue(isResumable(30_001, 100_000))
        assertFalse(isResumable(95_000, 100_000))
        assertFalse(isResumable(60_000, 0))
    }

    @Test fun `un episode termine repart du debut`() {
        assertEquals(500L, episodeResumeMs(500, 1000))
        assertEquals(0L, episodeResumeMs(950, 1000))
        assertTrue(isWatched(950, 1000))
        assertFalse(isWatched(949, 1000))
        assertFalse(isWatched(0, 0))
    }

    @Test fun `le titre de la fiche retrecit quand il est long`() {
        assertEquals(84, detailTitleSize("Le Grand Bain"))
        assertEquals(64, detailTitleSize("Un titre de film un peu plus long"))
        assertEquals(50, detailTitleSize("Un titre de film vraiment beaucoup trop long pour tenir"))
    }

    @Test fun `titres et saisons`() {
        assertEquals("Épisode 3", episodeTitle(3, ""))
        assertEquals("Épisode 3", episodeTitle(3, " episode 3 "))
        assertEquals("É3 · Le retour", episodeTitle(3, "Le retour"))
        assertEquals("Bonus", seasonLabel(0))
        assertEquals("Saison 2", seasonLabel(2))
        assertEquals("1 épisode", episodesCount(1))
        assertEquals("12 épisodes", episodesCount(12))
    }

    @Test fun `la distribution de TMDB passe avant celle de l abonnement`() {
        val tmdb = TmdbEnrichment(cast = listOf(CastMember(7, "Ana", "Reine", null)))
        assertEquals(listOf("Ana"), castFrom("Bob, Carl", tmdb).map { it.name })
        assertEquals(listOf("Bob", "Carl", "Dan"), castFrom("Bob, Carl; Dan,", null).map { it.name })
    }

    @Test fun `bouton de telechargement selon l etat`() {
        fun state(status: String) = DownloadEntity(
            id = "movie:u", itemType = "movie", itemUid = "u", title = "t", remoteUrl = "", localFilePath = "",
            status = status, bytesDownloaded = 25, totalBytes = 100,
        )
        assertEquals("Télécharger", downloadAction(null).first)
        assertEquals("25 %", downloadAction(state(DownloadStatus.DOWNLOADING)).first)
        assertEquals("En attente…", downloadAction(state(DownloadStatus.QUEUED)).first)
        assertEquals("En pause (25 %)", downloadAction(state(DownloadStatus.PAUSED)).first)
        assertEquals("Téléchargé", downloadAction(state(DownloadStatus.COMPLETED)).first)
        assertEquals("Échec, réessayer", downloadAction(state(DownloadStatus.FAILED)).first)
    }

    @Test fun `la variante TV fournit ses fiches`() {
        assertNotNull(FlavorScreens.details)
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class TvDetailScreenTest : ScreensBase() {
    /** Un téléviseur est en mode télécommande ; le simulateur démarre en mode tactile. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote(content: @Composable () -> Unit) {
        val input = LocalInputModeManager.current
        SideEffect { input.requestInputMode(InputMode.Keyboard) }
        content()
    }

    @Composable
    private fun Movie() = Remote { TvMovieDetail("$playlistId:M:501", { _, _ -> }, {}, {}) }

    @Composable
    private fun Series(onPlay: (String, Long) -> Unit = { _, _ -> }) =
        Remote { TvSeriesDetail("$playlistId:S:701", { ep, start -> onPlay(ep.uid, start) }, {}, {}) }

    @OptIn(ExperimentalTestApi::class)
    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        repeat(60) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    private fun focusedText(): String {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    }

    /** Descend jusqu'à sortir des boutons du haut de fiche, qui peuvent tenir sur une ou deux lignes. */
    private fun downToSeasons() {
        repeat(4) {
            if (focusedText().startsWith("Saison") || focusedText().startsWith("Bonus")) return
            press(Key.DirectionDown)
        }
    }

    /** À l'ouverture, la télécommande arrive sur « Lire ». */
    @Test fun ficheFilm() {
        shoot("tv_19_fiche_film", FormFactor.TV, 2500) { Movie() }
        assertEquals("Lire", focusedText())
    }

    /** Un film commencé propose de reprendre, et de repartir du début. */
    @Test fun ficheFilmCommence() {
        runBlocking {
            val uid = "$playlistId:M:501"
            app.container.repo.saveProgress(uid, ContentKind.MOVIE, "", 600_000, 3_600_000)
        }
        shoot("tv_20_fiche_film_reprise", FormFactor.TV, 2500) { Movie() }
        assertTrue(focusedText(), focusedText().startsWith("Reprendre à"))
    }

    /** Fiche d'une série : le bouton principal lance le premier épisode. */
    @Test fun ficheSerie() {
        shoot("tv_21_fiche_serie", FormFactor.TV, 3000) { Series() }
        assertTrue(focusedText(), focusedText().startsWith("Lire S"))
    }

    /** ▼ amène au panneau, ▶ dans les épisodes, ◀ revient à la saison, OK lance l'épisode. */
    @Test fun saisonsEtEpisodes() {
        var played: String? = null
        shoot("tv_22_fiche_serie_episodes", FormFactor.TV, 3000, after = {
            downToSeasons()
            assertTrue(focusedText(), focusedText().startsWith("Saison") || focusedText().startsWith("Bonus"))
            press(Key.DirectionRight)
            assertTrue("Le focus doit être sur un épisode", compose.onNode(isFocused()).fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
            assertFalse(focusedText(), focusedText().startsWith("Saison"))
        }) { Series { uid, _ -> played = uid } }

        press(Key.DirectionLeft)
        assertTrue(focusedText(), focusedText().startsWith("Saison") || focusedText().startsWith("Bonus"))
        press(Key.DirectionRight)
        compose.onNode(isFocused()).performKeyInputEnter()
        compose.waitForIdle()
        assertNotNull(played)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performKeyInputEnter() {
        performKeyInput { pressKey(Key.DirectionCenter) }
        repeat(10) { compose.mainClock.advanceTimeByFrame() }
    }
}
