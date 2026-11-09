package fr.yannickuhrig.vyzo.tv

import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.EpisodeEntity
import fr.yannickuhrig.vyzo.data.db.MovieEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvFoundationsTest {
    private val channel = ChannelEntity(
        uid = "1:C:42", playlistId = 1, remoteId = "42", name = "Arte", nameNorm = "arte", matchKey = "arte", number = 7,
        logo = "", categoryUid = "1:CAT:1", epgId = "arte.fr", epgKey = "arte", streamUrl = "", position = 0, syncToken = 1,
    )
    private val movie = MovieEntity(
        uid = "1:M:5", playlistId = 1, remoteId = "5", name = "Dune", nameNorm = "dune", categoryUid = "1:CAT:2",
        poster = "http://img/dune.jpg", year = "2021", position = 0, syncToken = 1,
    )

    @Test fun `une chaine garde son uid et perd les champs vides`() {
        val item = channel.toItem("Cinéma")
        assertEquals("1:C:42", item.id)
        assertEquals(Kind.LIVE, item.kind)
        assertEquals("Cinéma", item.group)
        assertEquals("arte.fr", item.tvgId)
        assertNull(item.logo)
        assertNull(item.year)
    }

    @Test fun `un film expose son annee et son affiche`() {
        val item = movie.toItem("Films")
        assertEquals(2021, item.year)
        assertEquals("http://img/dune.jpg", item.logo)
        assertEquals("MOVIE|dune|2021", item.mk)
    }

    @Test fun `une annee absente reste nulle`() {
        assertNull(movie.copy(year = "").toItem("Films").year)
    }

    @Test fun `une serie porte ses episodes et nomme ceux sans titre`() {
        val series = SeriesEntity(
            uid = "1:S:9", playlistId = 1, remoteId = "9", name = "Dark", nameNorm = "dark", categoryUid = "1:CAT:3",
            poster = "", position = 0, syncToken = 1,
        )
        val episode = EpisodeEntity(
            uid = "1:E:1", seriesUid = series.uid, playlistId = 1, remoteId = "1", season = 2, episode = 3, title = "",
            streamUrl = "http://s/3.mkv", syncToken = 1,
        )
        val item = series.toItem("Séries", listOf(episode))
        assertEquals(Kind.SERIES, item.kind)
        assertEquals(Episode(2, 3, "Épisode 3", "http://s/3.mkv"), item.episodes.single())
    }

    @Test fun `le theme par defaut est celui de Vyzo et son texte reste lisible`() {
        assertEquals("Vyzo", Look.ACCENTS[Look.DEFAULT_ACCENT].name)
        assertEquals(Look.BRAND_BG, Look.BGS[Look.DEFAULT_BG].bg)
        // L'ambre est clair : du texte blanc dessus serait illisible.
        assertNotEquals(0xFFFFFF, ThemeMath.onAccent(Look.BRAND_FILL))
    }
}
