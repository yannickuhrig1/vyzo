package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.db.EpisodeEntity
import fr.yannickuhrig.vyzo.data.vod.GenreSource
import fr.yannickuhrig.vyzo.data.vod.Genres
import fr.yannickuhrig.vyzo.player.TrackOption
import fr.yannickuhrig.vyzo.player.TrackType
import fr.yannickuhrig.vyzo.ui.player.pickTrack
import fr.yannickuhrig.vyzo.ui.vod.findEpisode
import fr.yannickuhrig.vyzo.util.FuzzyMatch
import fr.yannickuhrig.vyzo.util.TextNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Genres automatiques, recherche tolérante aux fautes, « Aller à l'épisode », pistes retenues par série. */
class DiscoveryTest {

    @Test
    fun genresFromProviderCategoryNames() {
        assertEquals(setOf("action"), Genres.detect("FR | ACTION 4K"))
        assertEquals(setOf("comedie"), Genres.detect("VOD - Comédie"))
        assertEquals(setOf("sf"), Genres.detect("EN| Sci-Fi"))
        assertEquals(setOf("sf"), Genres.detect("Science-Fiction"))
        assertEquals(setOf("horreur"), Genres.detect("Épouvante-Horreur"))
        assertEquals(setOf("anime"), Genres.detect("ANIME VOSTFR"))
        // « Dessins animés » : de l'animation, pas de l'anime japonais.
        assertEquals(setOf("animation"), Genres.detect("Dessins animés"))
        assertTrue(Genres.detect("NETFLIX FR").isEmpty())
        // Mot entier seulement : « Star Wars » n'est pas un film de guerre.
        assertTrue(Genres.detect("Star Wars Saga").isEmpty())
    }

    @Test
    fun genreIndexUsesCategoryAndTitleGenre() {
        val idx = Genres.index(
            listOf(
                GenreSource("m1", "", "FR | ACTION"),
                GenreSource("m2", "Drame, Romance", "NETFLIX"),
                GenreSource("m3", "Action, Thriller", "NOUVEAUTÉS"),
            ),
        )
        assertEquals(listOf("m1", "m3"), idx["action"])
        assertEquals(listOf("m2"), idx["drame"])
        assertEquals(listOf("m2"), idx["romance"])
        assertEquals(listOf("m3"), idx["thriller"])
    }

    private fun fuzzy(q: String, names: List<String>) =
        FuzzyMatch.rank(TextNorm.search(q), names, 10) { TextNorm.search(it) }

    @Test
    fun fuzzySearchToleratesTypos() {
        val names = listOf("Spider-Man: No Way Home", "Harry Potter à l'école des sorciers", "Gladiator", "The Office", "Top Gun")
        assertEquals(listOf("Spider-Man: No Way Home"), fuzzy("spiderman", names))
        assertEquals(listOf("Harry Potter à l'école des sorciers"), fuzzy("harry poter", names))
        assertEquals(listOf("Gladiator"), fuzzy("gladiateur", names))
        assertEquals(listOf("The Office"), fuzzy("the ofice", names))
        // Mots courts : pas de faute tolérée, sinon tout correspondrait.
        assertTrue(fuzzy("tap gun", names).isEmpty())
        assertTrue(fuzzy("batman", names).isEmpty())
    }

    @Test
    fun fuzzyWordsSplitOnAnythingButLettersAndDigits() {
        assertEquals(listOf("spider", "man", "2"), FuzzyMatch.words("--spider-man  2!"))
        assertEquals(emptyList<String>(), FuzzyMatch.words(" - "))
        assertEquals(listOf("x", "men"), FuzzyMatch.words("x/men"))
    }

    @Test
    fun preparedRankingMatchesPlainRanking() {
        val names = listOf("Spider-Man: No Way Home", "Spider-Man", "Gladiator", "Gladiator II")
        val prepared = names.map { FuzzyMatch.prepare(it, TextNorm.search(it)) }
        val q = TextNorm.search("spiderman")
        assertEquals(FuzzyMatch.rank(q, names, 10) { TextNorm.search(it) }, FuzzyMatch.rankPrepared(q, prepared, 10))
        // À note égale, le nom le plus court passe devant.
        assertEquals(listOf("Spider-Man", "Spider-Man: No Way Home"), FuzzyMatch.rankPrepared(q, prepared, 10))
    }

    @Test
    fun damerauDistanceCountsSwaps() {
        assertEquals(1, FuzzyMatch.distance("potter", "pottre", 2))
        assertEquals(1, FuzzyMatch.distance("poter", "potter", 2))
        assertEquals(3, FuzzyMatch.distance("abc", "xyzabc", 2))
    }

    private fun ep(season: Int, episode: Int) =
        EpisodeEntity(uid = "s$season-e$episode", seriesUid = "x", playlistId = 1, remoteId = "", season = season, episode = episode, title = "", syncToken = 0)

    @Test
    fun jumpToEpisode() {
        val eps = listOf(ep(1, 1), ep(1, 2), ep(2, 1), ep(2, 2), ep(2, 3))
        assertEquals("s2-e2", findEpisode(eps, 2, "2")?.uid)
        assertEquals("s1-e2", findEpisode(eps, 1, "2")?.uid)
        assertEquals("s2-e3", findEpisode(eps, 1, "3")?.uid)
        assertEquals("s2-e1", findEpisode(eps, 1, "S2E1")?.uid)
        assertEquals("s1-e2", findEpisode(eps, 2, "1x2")?.uid)
        // Numéro absolu au-delà des numéros d'épisode : compté depuis le premier.
        assertEquals("s2-e3", findEpisode(listOf(ep(1, 1), ep(1, 2), ep(2, 1), ep(2, 2), ep(2, 3)), 1, "5")?.uid)
        assertNull(findEpisode(eps, 1, "S9E9"))
        assertNull(findEpisode(eps, 1, "abc"))
    }

    @Test
    fun rememberedTrackMatchesByNameThenLanguage() {
        fun t(i: Int, label: String) = TrackOption(TrackType.AUDIO, null, i, label, selected = false)
        val opts = listOf(t(0, "English (AAC stéréo)"), t(1, "Français (AC3 5.1)"))
        assertEquals(1, pickTrack(opts, "Français (AC3 5.1)")?.index)
        assertEquals(1, pickTrack(opts, "Français (AAC stéréo)")?.index)
        assertNull(pickTrack(opts, "Deutsch"))
    }
}
