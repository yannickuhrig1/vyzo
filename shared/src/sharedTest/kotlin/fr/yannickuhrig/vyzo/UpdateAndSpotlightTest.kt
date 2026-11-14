package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.repo.GroupItem
import fr.yannickuhrig.vyzo.data.repo.GroupKind
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.ui.live.LiveGroupLine
import fr.yannickuhrig.vyzo.ui.live.LiveSection
import fr.yannickuhrig.vyzo.ui.live.liveGroupLines
import fr.yannickuhrig.vyzo.ui.vod.VodCard
import fr.yannickuhrig.vyzo.ui.vod.vodHeroPick
import fr.yannickuhrig.vyzo.ui.vod.vodSpotlightPick
import fr.yannickuhrig.vyzo.update.AppUpdater
import fr.yannickuhrig.vyzo.update.UpdateInfo
import fr.yannickuhrig.vyzo.update.VersionCompare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAndSpotlightTest {
    private fun github(tag: String) = UpdateInfo(
        versionCode = VersionCompare.toSyntheticCode(tag), versionName = VersionCompare.normalize(tag), fromGitHub = true,
    )

    @Test
    fun installedBetaIsNotOfferedAgainFromGitHub() {
        assertFalse(AppUpdater.isNewer(github("v1.12.0-beta1"), "1.12.0-beta1", 34))
    }

    @Test
    fun nextBetaAndFinalAreOfferedFromGitHub() {
        assertTrue(AppUpdater.isNewer(github("v1.12.0-beta2"), "1.12.0-beta1", 34))
        assertTrue(AppUpdater.isNewer(github("v1.12.0"), "1.12.0-beta2", 35))
        assertFalse(AppUpdater.isNewer(github("v1.11.9"), "1.12.0-beta1", 34))
    }

    @Test
    fun manifestWithSameNameStillComparesRealCodes() {
        assertTrue(AppUpdater.isNewer(UpdateInfo(versionCode = 36, versionName = "1.12.0"), "1.12.0", 35))
        assertFalse(AppUpdater.isNewer(UpdateInfo(versionCode = 35, versionName = "1.12.0"), "1.12.0", 35))
    }

    @Test
    fun releaseNotesLoseTheirMarkdown() {
        val md = "## Vyzo 1.12.0 bêta 2\r\n\r\n**Version d'essai.** Voir [la page](https://x.y/z).\r\n\r\n" +
            "- **Bandeau** : *Réglages › Films*\r\n* `code` et nom_de_fichier\r\n\r\n\r\n\r\nFin"
        assertEquals(
            "Vyzo 1.12.0 bêta 2\n\nVersion d'essai. Voir la page.\n\n• Bandeau : Réglages › Films\n• code et nom_de_fichier\n\nFin",
            AppUpdater.plainNotes(md),
        )
    }

    private fun card(i: Int, rating: Float = 5f, backdrop: String = "", plot: String = "") =
        VodCard(uid = "m$i", title = "Film $i", rating = rating, backdrop = backdrop, plot = plot)

    @Test
    fun noSpotlightWhenNoTitleHasBackdropOrPlot() {
        assertNull(vodSpotlightPick(List(6) { card(it, rating = it.toFloat()) }, null))
        assertNull(vodSpotlightPick(List(6) { card(it) }, Repository.KEY_CONTINUE))
    }

    @Test
    fun spotlightPrefersBestRatedWithBackdropThenPlot() {
        val items = listOf(card(0, 9f), card(1, 6f, plot = "Résumé"), card(2, 7f, backdrop = "b"), card(3, 8f, plot = "R"))
        assertEquals("m2", vodSpotlightPick(items, null)?.uid)
        val plotsOnly = listOf(card(0, 9f), card(1, 6f, plot = "Résumé"), card(2, 7f), card(3, 8f, plot = "R"))
        assertEquals("m3", vodSpotlightPick(plotsOnly, null)?.uid)
        assertEquals("m1", vodSpotlightPick(plotsOnly, Repository.KEY_CONTINUE)?.uid)
    }

    @Test
    fun heroPickFallsBackToBestPosterOrFirstForSmallLists() {
        val small = listOf(
            VodCard(uid = "a", title = "A", poster = "pa", rating = 6.2f),
            VodCard(uid = "b", title = "B", poster = "pb", rating = 8.5f),
        )
        assertNull(vodSpotlightPick(small, null))
        assertEquals("b", vodHeroPick(small, null)?.uid)

        val noArt = listOf(card(0, 9f), card(1, 5f))
        assertEquals("m0", vodHeroPick(noArt, null)?.uid)
        assertNull(vodHeroPick(emptyList(), null))
    }

    @Test
    fun liveGroupLinesInsertSectionHeadersWhenSectionChanges() {
        val groups = listOf(
            GroupItem(Repository.KEY_ALL, "Toutes", GroupKind.ALL, 12),
            GroupItem(Repository.KEY_FAVORITES, "Favoris", GroupKind.FAVORITES, 2),
            GroupItem("ug:1", "Soirée", GroupKind.USER, 4),
            GroupItem("cat:sport", "Sport", GroupKind.CATEGORY, 5),
            GroupItem("cat:cinema", "Cinéma", GroupKind.CATEGORY, 7),
        )
        val lines = liveGroupLines(groups)
        assertEquals(
            listOf(
                LiveGroupLine.Header(LiveSection.LIBRARY),
                LiveGroupLine.Entry(groups[0]),
                LiveGroupLine.Entry(groups[1]),
                LiveGroupLine.Header(LiveSection.USER),
                LiveGroupLine.Entry(groups[2]),
                LiveGroupLine.Header(LiveSection.CATEGORIES),
                LiveGroupLine.Entry(groups[3]),
                LiveGroupLine.Entry(groups[4]),
            ),
            lines,
        )
    }
}
