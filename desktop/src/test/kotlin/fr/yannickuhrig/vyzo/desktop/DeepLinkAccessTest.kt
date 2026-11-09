package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.db.CategoryEntity
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.EpisodeEntity
import fr.yannickuhrig.vyzo.data.db.FAVORITES_GROUP_ID
import fr.yannickuhrig.vyzo.data.db.MovieEntity
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.db.UserGroupItemEntity
import fr.yannickuhrig.vyzo.data.db.WatchProgressEntity
import fr.yannickuhrig.vyzo.data.link.DeepLink
import fr.yannickuhrig.vyzo.data.link.DeepLinkKind
import fr.yannickuhrig.vyzo.data.link.ambientPictures
import fr.yannickuhrig.vyzo.data.link.deepLinkTarget
import fr.yannickuhrig.vyzo.data.link.launcherRows
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkAccessTest {
    private fun category(uid: String, pid: Long, kind: ContentKind, adult: Boolean) =
        CategoryEntity(uid, pid, kind, uid, uid, 0, 1, adult = adult)

    private fun movie(uid: String, pid: Long, cat: String) = MovieEntity(
        uid = uid, playlistId = pid, remoteId = uid, name = uid, nameNorm = uid, categoryUid = cat,
        poster = "https://img/$uid.jpg", backdrop = "https://img/$uid-b.jpg", position = 0, syncToken = 1,
    )

    private fun series(uid: String, pid: Long, cat: String) = SeriesEntity(
        uid = uid, playlistId = pid, remoteId = uid, name = uid, nameNorm = uid, categoryUid = cat,
        poster = "https://img/$uid.jpg", position = 0, syncToken = 1,
    )

    private fun channel(uid: String, pid: Long, cat: String, locked: Boolean = false) = ChannelEntity(
        uid = uid, playlistId = pid, remoteId = uid, name = uid, nameNorm = uid, matchKey = uid, number = 1,
        logo = "https://img/$uid.png", categoryUid = cat, epgId = "", epgKey = "", streamUrl = "", position = 0, syncToken = 1, locked = locked,
    )

    private class Library(val c: AppContainer, val pid: Long, val profile: String)

    private fun library(block: suspend (Library) -> Unit) = runBlocking {
        val c = testContainer()
        c.start()
        val pid = c.db.playlists().insert(PlaylistEntity(name = "Test", type = SourceType.M3U, url = "http://test.invalid/a.m3u"))
        c.db.categories().upsert(
            listOf(
                category("films", pid, ContentKind.MOVIE, false), category("films-x", pid, ContentKind.MOVIE, true),
                category("series", pid, ContentKind.SERIES, false), category("series-x", pid, ContentKind.SERIES, true),
                category("tv", pid, ContentKind.LIVE, false), category("tv-x", pid, ContentKind.LIVE, true),
            ),
        )
        c.db.movies().upsert(listOf(movie("m1", pid, "films"), movie("mx", pid, "films-x")))
        c.db.series().upsert(listOf(series("s1", pid, "series"), series("sx", pid, "series-x")))
        c.db.series().upsertEpisodes(
            listOf(
                EpisodeEntity("e1", "s1", pid, "e1", 1, 1, "E1", syncToken = 1),
                EpisodeEntity("ex", "sx", pid, "ex", 1, 1, "EX", syncToken = 1),
            ),
        )
        c.db.channels().upsert(listOf(channel("c1", pid, "tv"), channel("cx", pid, "tv-x"), channel("cl", pid, "tv", locked = true)))
        val scope = withTimeout(5_000) { c.repo.viewScope.first { it.profileId.isNotBlank() && pid in it.pids } }
        block(Library(c, pid, scope.profileId))
    }

    private suspend fun Library.target(kind: DeepLinkKind, uid: String) = c.deepLinkTarget(DeepLink(kind, uid))

    private suspend fun Library.favorite(uid: String, kind: ContentKind) =
        c.db.groups().upsertItem(UserGroupItemEntity(profile, FAVORITES_GROUP_ID, uid, uid, kind))

    private suspend fun Library.watched(uid: String, kind: ContentKind, parent: String = "", at: Long) =
        c.db.progress().upsert(WatchProgressEntity(profile, uid, uid, kind, parent, parent, 60_000, 600_000, at))

    @Test
    fun openContentNeedsNoCode() = library { l ->
        assertEquals(false, l.target(DeepLinkKind.MOVIE, "m1")?.locked)
        assertEquals(false, l.target(DeepLinkKind.SERIES, "s1")?.locked)
        assertEquals(false, l.target(DeepLinkKind.PLAY, "c1")?.locked)
        assertEquals(false, l.target(DeepLinkKind.PLAY, "m1")?.locked)
        assertEquals(false, l.target(DeepLinkKind.PLAY, "e1")?.locked)
    }

    @Test
    fun lockedContentAsksForTheCodeWhateverTheLink() = library { l ->
        assertEquals(true, l.target(DeepLinkKind.MOVIE, "mx")?.locked)
        assertEquals(true, l.target(DeepLinkKind.SERIES, "sx")?.locked)
        assertEquals(true, l.target(DeepLinkKind.PLAY, "mx")?.locked)
        assertEquals("épisode d'une série verrouillée", true, l.target(DeepLinkKind.PLAY, "ex")?.locked)
        assertEquals("chaîne d'un groupe verrouillé", true, l.target(DeepLinkKind.PLAY, "cx")?.locked)
        assertEquals("chaîne verrouillée seule", true, l.target(DeepLinkKind.PLAY, "cl")?.locked)
    }

    @Test
    fun unknownUidsOpenNothing() = library { l ->
        assertNull(l.target(DeepLinkKind.MOVIE, "inconnu"))
        assertNull(l.target(DeepLinkKind.SERIES, "inconnu"))
        assertNull(l.target(DeepLinkKind.PLAY, "inconnu"))
        assertNull("une série n'est pas un film", l.target(DeepLinkKind.MOVIE, "s1"))
    }

    @Test
    fun aPlaylistOutsideTheProfileStaysOutOfReach() = library { l ->
        val other = l.c.db.playlists().insert(PlaylistEntity(name = "Autre", type = SourceType.M3U, url = "http://test.invalid/b.m3u"))
        l.c.db.movies().upsert(listOf(movie("autre", other, "films")))
        l.c.settings.update { it.copy(activePlaylistId = l.pid, showAllPlaylists = false) }
        withTimeout(5_000) { l.c.repo.viewScope.first { it.pids == listOf(l.pid) } }
        assertNull(l.target(DeepLinkKind.MOVIE, "autre"))
    }

    @Test
    fun launcherNeverShowsLockedContentEvenOnceUnlocked() = library { l ->
        l.c.parental.setPin("1234")
        withTimeout(5_000) { l.c.parental.pinSet.first { it } }
        assertTrue(l.c.parental.unlockVault("1234"))
        withTimeout(5_000) { l.c.repo.viewScope.first { !it.hide } }
        listOf("m1" to ContentKind.MOVIE, "mx" to ContentKind.MOVIE, "s1" to ContentKind.SERIES, "sx" to ContentKind.SERIES,
            "c1" to ContentKind.LIVE, "cx" to ContentKind.LIVE, "cl" to ContentKind.LIVE).forEach { (uid, kind) -> l.favorite(uid, kind) }
        l.watched("m1", ContentKind.MOVIE, at = 100)
        l.watched("mx", ContentKind.MOVIE, at = 200)
        l.watched("e1", ContentKind.SERIES, parent = "s1", at = 300)
        l.watched("ex", ContentKind.SERIES, parent = "sx", at = 400)

        val rows = l.c.launcherRows()
        assertEquals(listOf("s1", "m1"), rows.resume.map { it.uid })
        assertEquals(setOf("c1", "m1", "s1"), rows.favorites.map { it.uid }.toSet())
        assertEquals("la reprise d'un film repart de la lecture", DeepLinkKind.PLAY, rows.resume.last().link.kind)
        assertEquals(600_000L, rows.resume.first().durationMs)

        val pictures = l.c.ambientPictures().map { it.uid }
        assertTrue(pictures.containsAll(listOf("m1", "s1")))
        assertFalse(pictures.any { it == "mx" || it == "sx" })
    }

    @Test
    fun launcherSkipsImagesTheSystemCannotFetch() = library { l ->
        l.c.db.movies().upsert(listOf(movie("local", l.pid, "films").copy(poster = "/data/poster.jpg")))
        l.favorite("local", ContentKind.MOVIE)
        l.favorite("m1", ContentKind.MOVIE)
        assertEquals(listOf("m1"), l.c.launcherRows().favorites.map { it.uid })
    }
}
