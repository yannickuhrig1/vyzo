package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.data.db.MetadataCacheDao
import fr.yannickuhrig.vyzo.data.db.MetadataCacheEntity
import fr.yannickuhrig.vyzo.data.ratings.OmdbRatings
import fr.yannickuhrig.vyzo.data.ratings.parseOmdb
import fr.yannickuhrig.vyzo.data.tmdb.MetadataCache
import fr.yannickuhrig.vyzo.data.tmdb.TmdbTitle
import fr.yannickuhrig.vyzo.data.tmdb.certificationLabel
import fr.yannickuhrig.vyzo.data.tmdb.cleanTitleForTmdb
import fr.yannickuhrig.vyzo.data.tmdb.imdbIdOf
import fr.yannickuhrig.vyzo.data.tmdb.movieCertification
import fr.yannickuhrig.vyzo.data.tmdb.parseSeasonEpisodes
import fr.yannickuhrig.vyzo.data.tmdb.parseTitleList
import fr.yannickuhrig.vyzo.data.tmdb.sameTitle
import fr.yannickuhrig.vyzo.data.tmdb.seriesCertification
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

class TmdbParsingTest {
    @Test
    fun `identifiant IMDb dans external_ids ou a la racine`() {
        assertEquals("tt1", imdbIdOf(json("""{"external_ids":{"imdb_id":"tt1"}}""")))
        assertEquals("tt2", imdbIdOf(json("""{"imdb_id":"tt2"}""")))
        assertNull(imdbIdOf(json("""{"external_ids":{"imdb_id":""}}""")))
    }

    @Test
    fun `film, la sortie en salle prime`() {
        val j = json("""{"release_dates":{"results":[
            {"iso_3166_1":"US","release_dates":[{"certification":"R","type":3}]},
            {"iso_3166_1":"FR","release_dates":[{"certification":"","type":1},{"certification":"16","type":4},{"certification":"12","type":3}]}]}}""")
        assertEquals("12", movieCertification(j))
    }

    @Test
    fun `sans classification francaise rien n'est invente`() {
        assertNull(movieCertification(json("""{"release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"R","type":3}]}]}}""")))
        assertNull(seriesCertification(json("""{"content_ratings":{"results":[{"iso_3166_1":"FR","rating":"NR"}]}}""")))
    }

    @Test
    fun `serie classee`() = assertEquals("16", seriesCertification(json("""{"content_ratings":{"results":[{"iso_3166_1":"FR","rating":"16"}]}}""")))

    @Test
    fun `serie avec plusieurs classifications, la plus stricte`() =
        assertEquals("18", seriesCertification(json("""{"content_ratings":{"results":[{"iso_3166_1":"FR","rating":"12"},{"iso_3166_1":"FR","rating":"18"}]}}""")))

    @Test
    fun `un titre fait d'une annee reste cherchable`() {
        assertEquals("1992" to null, cleanTitleForTmdb("1992"))
        assertEquals("1917" to 2019, cleanTitleForTmdb("1917 (2019)"))
        assertTrue(sameTitle("1992", 2024, TmdbTitle("1992", "1992", 2024)))
    }

    @Test
    fun `libelles de classification`() {
        assertEquals("−12 ans", certificationLabel("12"))
        assertEquals("Tous publics", certificationLabel("U"))
        assertEquals("Tous publics", certificationLabel("TP"))
    }

    @Test
    fun `episodes d'une saison`() {
        val eps = parseSeasonEpisodes(json("""{"episodes":[
            {"episode_number":1,"season_number":2,"name":"Pilote","overview":"Résumé","still_path":"/a.jpg","air_date":"2026-11-20"},
            {"episode_number":2,"season_number":2,"name":"Suite","overview":"","still_path":null}]}"""))
        assertEquals(2, eps.size)
        assertEquals("https://image.tmdb.org/t/p/w500/a.jpg", eps[0].stillUrl)
        assertEquals("2026-11-20", eps[0].airDate)
        assertNull(eps[1].stillUrl)
        assertEquals("", eps[1].airDate)
    }

    @Test
    fun `liste de titres films et series`() {
        val l = parseTitleList(json("""{"results":[
            {"title":"Le Film","original_title":"The Movie","release_date":"2024-05-01"},
            {"name":"La Série","original_name":"The Show","first_air_date":"2019-01-01"},
            {"name":"Sans date"}]}"""))
        assertEquals(TmdbTitle("Le Film", "The Movie", 2024), l[0])
        assertEquals(TmdbTitle("La Série", "The Show", 2019), l[1])
        assertNull(l[2].year)
    }
}

class CatalogMatchTest {
    private val inception = TmdbTitle("Inception", "Inception", 2010)

    @Test
    fun `prefixes et qualite sont ignores`() {
        assertTrue(sameTitle("FR | Inception 4K", 2010, inception))
        assertTrue(sameTitle("VF - Inception (2010)", null, inception))
    }

    @Test
    fun `un titre qui ne fait que contenir le nom ne compte pas`() =
        assertFalse(sameTitle("Inception : le making-of", 2010, inception))

    @Test
    fun `remake d'une autre annee ecarte`() = assertFalse(sameTitle("Dune", 1984, TmdbTitle("Dune", "Dune", 2021)))

    @Test
    fun `une annee d'ecart est toleree`() = assertTrue(sameTitle("Inception", 2011, inception))

    @Test
    fun `titre original accepte`() = assertTrue(sameTitle("The Movie", 2024, TmdbTitle("Le Film", "The Movie", 2024)))
}

class OmdbTest {
    @Test
    fun `les trois notes`() {
        val r = parseOmdb(json("""{"Response":"True","imdbRating":"8.8","Metascore":"74","Ratings":[
            {"Source":"Internet Movie Database","Value":"8.8/10"},{"Source":"Rotten Tomatoes","Value":"87%"},{"Source":"Metacritic","Value":"74/100"}]}"""))
        assertEquals(OmdbRatings("8.8", "87%", "74"), r)
        assertEquals(listOf("IMDb 8,8", "Rotten Tomatoes 87 %", "Metacritic 74"), r!!.labels())
    }

    @Test
    fun `N A n'est pas une note`() {
        assertEquals(OmdbRatings("7.0", null, null), parseOmdb(json("""{"Response":"True","imdbRating":"7.0","Metascore":"N/A","Ratings":[]}""")))
        assertNull(parseOmdb(json("""{"Response":"True","imdbRating":"N/A","Metascore":"N/A"}""")))
    }

    @Test
    fun `titre inconnu`() = assertNull(parseOmdb(json("""{"Response":"False","Error":"Movie not found!"}""")))
}

class MetadataCacheTest {
    private class FakeDao : MetadataCacheDao {
        val rows = HashMap<String, MetadataCacheEntity>()
        override suspend fun get(key: String) = rows[key]
        override suspend fun upsert(item: MetadataCacheEntity) { rows[item.cacheKey] = item }
        override suspend fun deleteOlderThan(before: Long) { rows.values.removeAll { it.fetchedAt < before } }
    }

    @Test
    fun `une reponse trop vieille est ignoree puis purgee`() = runTest {
        var t = 0L
        val dao = FakeDao()
        val cache = MetadataCache(dao, now = { t })
        cache.put("a", "x")
        t = MetadataCache.MAX_AGE_MS - 1
        assertEquals("x", cache.get("a"))
        assertNull(cache.get("a", maxAgeMs = 10))
        t = MetadataCache.MAX_AGE_MS + 1
        assertNull(cache.get("a"))
        cache.purge()
        assertTrue(dao.rows.isEmpty())
    }
}
