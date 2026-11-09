package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.db.EpisodeEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import fr.yannickuhrig.vyzo.data.vod.NewEpisodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alerte de nouvel épisode : ce qui compte comme nouveau, et le texte de la notification. */
class NewEpisodesTest {

    private fun ep(season: Int, episode: Int, id: String = "$season-$episode", title: String = "Épisode $episode") =
        EpisodeEntity(uid = "s:$id", seriesUid = "s", playlistId = 1, remoteId = id, season = season, episode = episode, title = title, syncToken = 0)

    private val series = SeriesEntity(uid = "s", playlistId = 1, remoteId = "s", name = "The Office ", nameNorm = "the office", categoryUid = "", poster = "", position = 0, syncToken = 0)

    @Test
    fun onlyEpisodesThatWereNotThereBefore() {
        val before = listOf(ep(1, 1), ep(1, 2))
        val after = before + ep(1, 3) + ep(2, 1)
        assertEquals(listOf("s:1-3", "s:2-1"), NewEpisodes.between(before, after).map { it.uid })
    }

    @Test
    fun firstLoadIsABaselineNotANewEpisode() {
        assertTrue(NewEpisodes.between(emptyList(), listOf(ep(1, 1), ep(1, 2))).isEmpty())
    }

    @Test
    fun replacedFileIsNotANewEpisode() {
        // Même saison, même numéro, autre identifiant : le fournisseur a changé le fichier.
        assertTrue(NewEpisodes.between(listOf(ep(1, 1)), listOf(ep(1, 1, id = "999"))).isEmpty())
    }

    @Test
    fun notificationText() {
        val one = NewEpisodes(series, listOf(ep(2, 5, title = "Le dîner")))
        assertEquals("Nouvel épisode : The Office", one.title)
        assertEquals("S2 E5 · Le dîner", one.body)
        // Titre générique du fournisseur : on ne le répète pas.
        assertEquals("S2 E5", NewEpisodes(series, listOf(ep(2, 5))).body)
        val many = NewEpisodes(series, listOf(ep(2, 7), ep(2, 5), ep(2, 6)))
        assertEquals("3 nouveaux épisodes : The Office", many.title)
        assertEquals("S2 E5 à S2 E7", many.body)
    }
}
