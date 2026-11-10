package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.MovieEntity
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.vod.RecoTitle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouTest {
    private fun movie(pid: Long, id: String, genre: String, rating: Float = 6f, addedAt: Long = 0L) = MovieEntity(
        uid = "$pid:$id", playlistId = pid, remoteId = id, name = "Film $id", nameNorm = "film $id", categoryUid = "c", poster = "",
        genre = genre, rating = rating, addedAt = addedAt, durationSecs = 6_000, streamUrl = "", position = 0, syncToken = 1,
    )

    private fun series(pid: Long, id: String, genre: String) = SeriesEntity(
        uid = "$pid:s$id", playlistId = pid, remoteId = id, name = "Série $id", nameNorm = "serie $id", categoryUid = "c", poster = "",
        genre = genre, position = 0, syncToken = 1,
    )

    private fun RecoTitle.uid() = when (this) {
        is RecoTitle.Movie -> movie.uid
        is RecoTitle.Series -> series.uid
    }

    @Test
    fun theRowFollowsWhatTheProfileWatchedAndLiked() = runBlocking {
        val c = testContainer()
        val pid = c.db.playlists().insert(PlaylistEntity(name = "T", type = SourceType.M3U, url = "http://test.invalid/a.m3u"))
        c.db.movies().upsert(
            listOf(
                movie(pid, "vu", "Action"),
                movie(pid, "action-neuf", "Action", addedAt = 2_000),
                movie(pid, "action-vieux", "Action", addedAt = 1_000),
                movie(pid, "horreur", "Horreur", rating = 9f),
            ),
        )
        c.db.series().upsert(listOf(series(pid, "1", "Drame"), series(pid, "2", "Drame"), series(pid, "3", "Western")))

        assertTrue("rien de regardé : rien à proposer", withTimeout(10_000) { c.repo.forYou().first() }.isEmpty())

        c.repo.saveProgress("$pid:vu", ContentKind.MOVIE, "", 5_800_000, 6_000_000)
        c.repo.toggleFavorite("$pid:s1", ContentKind.SERIES)

        val row = withTimeout(10_000) { c.repo.forYou().first { it.size >= 3 } }.map { it.uid() }
        assertFalse("déjà vu : $row", "$pid:vu" in row)
        assertFalse("déjà en favori : $row", "$pid:s1" in row)
        assertFalse("aucun lien avec ce qui a été regardé : $row", "$pid:horreur" in row || "$pid:s3" in row)
        assertEquals(setOf("$pid:action-neuf", "$pid:action-vieux", "$pid:s2"), row.toSet())
        assertEquals("le plus récent passe devant à goût égal", "$pid:action-vieux", row.filter { "action" in it }.last())
    }
}
