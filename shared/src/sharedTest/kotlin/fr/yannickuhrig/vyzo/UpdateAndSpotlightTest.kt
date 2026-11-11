package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.ui.vod.VodCard
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
}
