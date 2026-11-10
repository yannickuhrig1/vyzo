package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.WatchProgressEntity
import fr.yannickuhrig.vyzo.data.home.HomeRows
import fr.yannickuhrig.vyzo.data.prefs.AppSettings
import fr.yannickuhrig.vyzo.data.vod.RecoCandidate
import fr.yannickuhrig.vyzo.data.vod.Recommendations
import fr.yannickuhrig.vyzo.data.vod.TasteSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationsTest {
    private val day = 86_400_000L
    private val now = 1_000 * day

    private fun progress(uid: String, fraction: Double, daysAgo: Int, kind: ContentKind = ContentKind.MOVIE, parent: String = "") =
        WatchProgressEntity(
            profileId = "p", contentKey = uid, uid = uid, kind = kind, parentUid = parent,
            positionMs = (fraction * 6_000_000).toLong(), durationMs = 6_000_000, updatedAt = now - daysAgo * day,
        )

    private fun candidate(uid: String, vararg genres: String, rating: Float = 0f, fresh: Double = 0.0, kind: ContentKind = ContentKind.MOVIE) =
        RecoCandidate(kind, uid, genres.toSet(), rating, fresh)

    @Test
    fun finishedAndRecentTitlesWeighMoreThanAbandonedOrOldOnes() {
        val s = Recommendations.signalsFromProgress(
            listOf(
                progress("fini", 0.95, 0),
                progress("abandonne", 0.10, 0),
                progress("ancien", 0.95, 45),
                progress("a-peine-ouvert", 0.01, 0),
            ),
            now,
        ).associate { it.uid to it.weight }
        assertEquals(1.0, s.getValue("fini"), 1e-9)
        assertTrue(s.getValue("abandonne") < 0.5)
        assertEquals("demi-vie de 45 jours", 0.5, s.getValue("ancien"), 1e-9)
        assertFalse("ouvert quelques secondes : rien n'est appris", "a-peine-ouvert" in s)
    }

    @Test
    fun episodesCountForTheirSeriesWithACap() {
        val eps = (1..10).map { progress("ep$it", 1.0, 0, ContentKind.SERIES, parent = "serie") }
        val s = Recommendations.signalsFromProgress(eps, now).single()
        assertEquals(ContentKind.SERIES, s.kind)
        assertEquals("serie", s.uid)
        assertEquals("dix épisodes ne valent pas dix films", 3.0, s.weight, 1e-9)
    }

    @Test
    fun affinityIsSharedBetweenGenresAndScaledToOne() {
        val genres = mapOf("a" to setOf("action"), "b" to setOf("action", "sf"), "c" to setOf("drame"))
        val aff = Recommendations.genreAffinity(
            listOf(TasteSignal(ContentKind.MOVIE, "a", 1.0), TasteSignal(ContentKind.MOVIE, "b", 1.0), TasteSignal(ContentKind.MOVIE, "c", 0.5)),
        ) { _, uid -> genres[uid].orEmpty() }
        assertEquals(1.0, aff.getValue("action"), 1e-9)
        assertEquals(0.5 / 1.5, aff.getValue("sf"), 1e-9)
        assertEquals(0.5 / 1.5, aff.getValue("drame"), 1e-9)
    }

    @Test
    fun rankingPrefersLikedGenresSkipsSeenTitlesAndIgnoresUnrelatedOnes() {
        val aff = mapOf("action" to 1.0, "drame" to 0.3)
        val out = Recommendations.rank(
            listOf(
                candidate("drame", "drame", rating = 9f),
                candidate("action", "action", rating = 5f),
                candidate("deja-vu", "action", rating = 10f),
                candidate("horreur", "horreur", rating = 10f, fresh = 1.0),
            ),
            aff, exclude = setOf("MOVIE:deja-vu"), limit = 10,
        ).map { it.uid }
        assertEquals(listOf("action", "drame"), out)
    }

    @Test
    fun ratingAndFreshnessBreakTiesBetweenTitlesOfTheSameGenre() {
        val aff = mapOf("action" to 1.0)
        val out = Recommendations.rank(
            listOf(candidate("vieux", "action", rating = 6f), candidate("neuf", "action", rating = 6f, fresh = 1.0), candidate("note", "action", rating = 10f, fresh = 0.5)),
            aff, emptySet(), 3,
        ).map { it.uid }
        assertEquals(listOf("note", "neuf", "vieux"), out)
    }

    @Test
    fun aSecondGenreGetsIntoTheRowInsteadOfOnlyTheFavouriteOne() {
        val aff = mapOf("action" to 1.0, "comedie" to 0.8)
        val actions = (1..10).map { candidate("action$it", "action") }
        val out = Recommendations.rank(actions + candidate("comedie", "comedie"), aff, emptySet(), 4).map { it.uid }
        assertTrue("la comédie arrive avant la fin de la rangée : $out", "comedie" in out)
        assertEquals("action1", out.first())
    }

    @Test
    fun moviesAndSeriesWithTheSameUidStayDistinct() {
        val aff = mapOf("drame" to 1.0)
        val out = Recommendations.rank(
            listOf(candidate("1", "drame"), candidate("1", "drame", kind = ContentKind.SERIES)),
            aff, setOf("MOVIE:1"), 5,
        )
        assertEquals(listOf(ContentKind.SERIES), out.map { it.kind })
    }

    @Test
    fun nothingToProposeWithoutHistory() {
        assertTrue(Recommendations.rank(listOf(candidate("x", "action")), emptyMap(), emptySet(), 5).isEmpty())
        assertTrue(Recommendations.genreAffinity(emptyList()) { _, _ -> setOf("action") }.isEmpty())
    }

    @Test
    fun forYouRowGoesAfterTheResumeRowsOnce() {
        assertEquals(
            listOf(HomeRows.ON_AIR, HomeRows.CONTINUE_SERIES, HomeRows.CONTINUE_MOVIES, HomeRows.FOR_YOU, HomeRows.APPS),
            HomeRows.withForYou(listOf(HomeRows.ON_AIR, HomeRows.CONTINUE_SERIES, HomeRows.CONTINUE_MOVIES, HomeRows.APPS)),
        )
        assertEquals(listOf(HomeRows.ON_AIR, HomeRows.FOR_YOU, HomeRows.APPS), HomeRows.withForYou(listOf(HomeRows.ON_AIR, HomeRows.APPS)))
        assertEquals(listOf(HomeRows.FOR_YOU, HomeRows.APPS), HomeRows.withForYou(listOf(HomeRows.APPS)))
        val already = listOf(HomeRows.APPS, HomeRows.FOR_YOU)
        assertEquals(already, HomeRows.withForYou(already))
    }

    @Test
    fun existingHomesReceiveTheRowOnlyOnceAndARemovedRowStaysRemoved() {
        val before = AppSettings(homeRows = listOf(HomeRows.CONTINUE_MOVIES), homeRowsByProfile = mapOf("enfant" to listOf(HomeRows.APPS)))
        val after = before.withForYouRow()
        assertEquals(listOf(HomeRows.CONTINUE_MOVIES, HomeRows.FOR_YOU), after.homeRows)
        assertEquals(listOf(HomeRows.FOR_YOU, HomeRows.APPS), after.homeRowsByProfile.getValue("enfant"))
        val removed = after.copy(homeRows = listOf(HomeRows.CONTINUE_MOVIES))
        assertEquals(removed, removed.withForYouRow())
    }
}
