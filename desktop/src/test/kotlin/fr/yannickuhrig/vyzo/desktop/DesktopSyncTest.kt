package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.closeForTest
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.Fixtures

import fr.yannickuhrig.vyzo.data.backup.BackupManager
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.FAVORITES_GROUP_ID
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.repo.Repository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import fr.yannickuhrig.vyzo.data.db.CategoryEntity
import fr.yannickuhrig.vyzo.util.TextNorm
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ChannelHistoryEntity
import fr.yannickuhrig.vyzo.data.db.MovieEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import fr.yannickuhrig.vyzo.data.db.WatchProgressEntity
import fr.yannickuhrig.vyzo.ui.vod.VodSortOrder

/** Mêmes vérifications que SyncIntegrationTest (Android), avec la base et les réglages de Vyzo PC. */
class DesktopSyncTest {
    private lateinit var server: MockWebServer
    private lateinit var c: AppContainer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = Fixtures.dispatcher(server)
        server.start()
        c = testContainer()
    }

    @After
    fun tearDown() {
        server.shutdown()
        c.closeForTest()
    }

    @Test
    fun m3uPlaylistWithGzipEpgCatchupAndVod() = runBlocking {
        val id = c.repo.addPlaylist(PlaylistEntity(name = "Test M3U", type = SourceType.M3U, url = server.url("/list.m3u").toString()))
        val res = c.sync.syncPlaylist(id)
        assertTrue(res.exceptionOrNull()?.toString() ?: "", res.isSuccess)

        assertEquals(4, c.db.channels().count(id))
        assertEquals(1, c.db.movies().count(id))
        assertEquals(1, c.db.series().count(id))

        val channels = c.repo.channels(Repository.KEY_ALL).first()
        assertEquals(listOf(1, 2, 7, 20), channels.map { it.number })
        val tf1 = channels.first { it.name == "TF1 HD" }
        val f2 = channels.first { it.name == "France 2 HD" }
        // Le guide est associé par tvg-id, et par nom normalisé quand le tvg-id manque.
        assertEquals("tf1.fr", tf1.epgKey)
        assertEquals("france2.fr", f2.epgKey)

        val now = System.currentTimeMillis()
        val current = c.repo.nowPrograms(channels.map { it.epgKey }, now)
        assertEquals(4, current.size)

        val past = c.repo.programs(tf1.epgKey, now - 4 * 3600_000L, now - 600_000L).first()
        assertTrue(c.repo.canCatchup(tf1, past, now))
        val spec = c.repo.resolveCatchup(tf1, past)!!
        assertTrue(spec.url, spec.url.contains("utc=${past.start / 1000}"))
        assertFalse(c.repo.canCatchup(f2, past, now))

        val sources = c.db.epg().sources()
        assertEquals(1, sources.size)
        assertTrue(sources[0].programCount > 30)
        assertEquals("", sources[0].lastError)

        val found = c.repo.search("france")
        assertEquals(1, found.channels.size)
        assertTrue(c.repo.search("Koh-Lanta").programs.isNotEmpty())
    }

    @Test
    fun guideCoupeEnCoursGardeLAncienSansDoublon() = runBlocking {
        val id = c.repo.addPlaylist(PlaylistEntity(name = "Test M3U", type = SourceType.M3U, url = server.url("/list.m3u").toString()))
        assertTrue(c.sync.syncPlaylist(id).isSuccess)
        val key = "src:${c.db.epg().sources().single().id}"
        val before = c.db.programs().countSource(key)
        assertTrue(before > 0)

        // Nouveau guide de plus d'un lot (500 programmes), coupé au milieu d'une balise.
        val now = System.currentTimeMillis()
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n<channel id=\"TF1.fr\"><display-name>TF1</display-name></channel>\n")
            repeat(1200) { i ->
                val start = Fixtures.xmlTime(now - 1800_000L + i * 120_000L)
                val stop = Fixtures.xmlTime(now - 1800_000L + (i + 1) * 120_000L)
                append("<programme start=\"$start\" stop=\"$stop\" channel=\"TF1.fr\"><title>Émission $i</title></programme>\n")
            }
        }
        val cut = xml.substring(0, xml.indexOf("Émission 900"))
        val normal = server.dispatcher
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                if (request.requestUrl!!.encodedPath == "/epg.xml.gz") okhttp3.mockwebserver.MockResponse().setBody(Fixtures.gzip(cut))
                else normal.dispatch(request)
        }
        c.sync.syncPlaylist(id)

        assertEquals("l'ancien guide reste, sans les lots du guide interrompu", before, c.db.programs().countSource(key))
        assertTrue(c.db.epg().sources().single().lastError.isNotEmpty())
    }

    @Test
    fun xtreamPlaylistLiveVodSeriesAndBackup() = runBlocking {
        val base = server.url("/").toString().trimEnd('/')
        val id = c.repo.addPlaylist(PlaylistEntity(name = "Xtream", type = SourceType.XTREAM, url = base, username = "bob", password = "secret"))
        val res = c.sync.syncPlaylist(id)
        assertTrue(res.exceptionOrNull()?.toString() ?: "", res.isSuccess)

        val p = c.db.playlists().get(id)!!
        assertEquals("Europe/Paris", p.serverTimezone)
        assertEquals(2, p.maxConnections)
        assertEquals(10, c.db.channels().count(id))
        assertEquals(5, c.db.movies().count(id))
        assertEquals(2, c.db.series().count(id))

        val groups = c.repo.liveGroups().first()
        assertTrue(groups.any { it.title == "France | TNT" && it.count == 6 })
        assertTrue("catégorie orpheline", groups.any { it.title == "Sans catégorie" && it.count == 1 })

        val channels = c.repo.channels(Repository.KEY_ALL).first()
        val tf1 = channels.first { it.remoteId == "101" }
        val live = c.repo.resolveChannel(tf1)
        assertEquals("$base/live/bob/secret/101.ts", live.url)
        assertEquals(7, tf1.catchupDays)
        // "FR: Arte" a un epg_channel_id ; "France 2 HD" n'en a pas mais se rapproche du nom XMLTV.
        assertEquals("france2.fr", channels.first { it.remoteId == "102" }.epgKey)

        val now = System.currentTimeMillis()
        val past = c.repo.programs(tf1.epgKey, now - 4 * 3600_000L, now - 600_000L).first()
        val catchup = c.repo.resolveCatchup(tf1, past)!!
        assertTrue(catchup.url, catchup.url.startsWith("$base/timeshift/bob/secret/"))
        assertTrue(catchup.url.endsWith("/101.ts"))

        val movie = c.db.movies().get("$id:M:502")!!
        assertEquals(8.6f, movie.rating, 0.01f)
        c.repo.refreshMovieDetails(movie)
        val detailed = c.db.movies().get(movie.uid)!!
        assertEquals("Luc Besson", detailed.director)
        assertEquals(2 * 3600 + 48 * 60, detailed.durationSecs)
        assertEquals("$base/movie/bob/secret/502.mp4", c.repo.resolveMovie(detailed).url)

        val s = c.db.series().get("$id:S:701")!!
        c.repo.refreshEpisodes(s)
        val eps = c.db.series().episodesList(s.uid)
        assertEquals(3, eps.size)
        assertEquals(listOf(1, 1, 2), eps.map { it.season })
        assertEquals("$base/series/bob/secret/9001.mkv", c.repo.resolveEpisode(eps[0]).url)

        // Favoris + progression + sauvegarde aller-retour
        c.repo.toggleFavorite(tf1.uid, ContentKind.LIVE)
        c.repo.saveProgress(eps[0].uid, ContentKind.SERIES, s.uid, 60_000, 3_000_000)
        assertEquals(listOf(s.uid), c.repo.series(Repository.KEY_CONTINUE).first().map { it.uid })
        val json = BackupManager.export(c)
        assertTrue(json.contains("\"password\": \"secret\""))
        assertTrue(json.contains("\"app\": \"Vyzo\""))
        // Les sauvegardes faites quand l'appli s'appelait Zapette restent lisibles ; les autres non.
        assertEquals(1, BackupManager.inspect(json))
        assertEquals(1, BackupManager.inspect(json.replace("\"app\": \"Vyzo\"", "\"app\": \"Zapette\"")))
        assertEquals(null, BackupManager.inspect(json.replace("\"app\": \"Vyzo\"", "\"app\": \"Autre\"")))
        val restored = BackupManager.import(c, json)
        assertEquals(1, restored)
        val newId = c.db.playlists().all().single().id
        val favs = c.db.groups().allItems().filter { it.groupId == FAVORITES_GROUP_ID }
        assertEquals(listOf("$newId:L:101"), favs.map { it.itemUid })
        assertEquals(newId, c.settings.get().activePlaylistId)
        assertNotNull(c.db.playlists().get(newId))
    }

    @Test
    fun rechercheDeChainesClasseLesMotsEntiersDabord() = runBlocking {
        val id = c.repo.addPlaylist(PlaylistEntity(name = "P", type = SourceType.M3U, url = "http://x/p.m3u"))
        val noms = listOf("FG - STARTER", "Charterfeber", "ARTEMIS", "|FR| ARTE HD", "Arte Concert", "ZZZ", "ARTE")
        c.db.channels().upsert(
            noms.mapIndexed { i, n ->
                ChannelEntity(
                    uid = "$id:$i", playlistId = id, remoteId = "$i", name = n, nameNorm = TextNorm.search(n), matchKey = "",
                    number = i + 1, logo = "", categoryUid = "", epgId = "", epgKey = "", streamUrl = "", position = i, syncToken = 1,
                )
            },
        )
        assertEquals(
            listOf("ARTE", "|FR| ARTE HD", "Arte Concert", "ARTEMIS", "FG - STARTER", "Charterfeber"),
            c.repo.search("arte").channels.map { it.name },
        )
    }

    @Test
    fun sourceJamaisChargeeEstRetiree() = runBlocking {
        val id = c.repo.addPlaylist(PlaylistEntity(name = "X", type = SourceType.XTREAM, url = server.url("/").toString(), username = "bob", password = "nope"))
        assertTrue(c.sync.syncPlaylist(id).isFailure)
        val epgId = c.repo.addEpgSource("Guide perso", "http://x/epg.xml")
        c.repo.discardNeverLoadedPlaylist(id, epgId)
        assertEquals(null, c.db.playlists().get(id))
        assertEquals(0L, c.settings.get().activePlaylistId)
        assertTrue(c.db.epg().sources().none { it.id == epgId })
    }

    @Test
    fun sourceDejaChargeeEstGardee() = runBlocking {
        val id = c.repo.addPlaylist(PlaylistEntity(name = "M", type = SourceType.M3U, url = server.url("/list.m3u").toString()))
        assertTrue(c.sync.syncPlaylist(id).isSuccess)
        c.repo.discardNeverLoadedPlaylist(id)
        assertTrue(c.db.playlists().get(id) != null)
    }

    @Test
    fun xtreamWrongPasswordFailsCleanly() = runBlocking {
        val id = c.repo.addPlaylist(PlaylistEntity(name = "X", type = SourceType.XTREAM, url = server.url("/").toString(), username = "bob", password = "nope"))
        val res = c.sync.syncPlaylist(id)
        assertTrue(res.isFailure)
        assertTrue(c.db.playlists().get(id)!!.lastError.isNotBlank())
        assertFalse(c.db.playlists().get(id)!!.lastError.contains("nope"))
    }

    @Test
    fun historyPurgeAllAdultAndKeyword() = runBlocking {
        val pid = c.repo.addPlaylist(PlaylistEntity(name = "Test", type = SourceType.M3U, url = server.url("/list.m3u").toString()))
        val profileId = c.profiles.activeIdNow()

        // Insère des catégories (standard et adulte sans flag adult=true explicite)
        val catStandard = CategoryEntity(uid = "$pid:LIVE:std", playlistId = pid, kind = ContentKind.LIVE, remoteId = "std", name = "Généraliste", position = 0, syncToken = 1L, adult = false)
        val catAdult = CategoryEntity(uid = "$pid:LIVE:xxx", playlistId = pid, kind = ContentKind.LIVE, remoteId = "xxx", name = "Adultes XXX", position = 1, syncToken = 1L, adult = false)
        val catMovie = CategoryEntity(uid = "$pid:MOVIE:adult", playlistId = pid, kind = ContentKind.MOVIE, remoteId = "m_xxx", name = "Films Adultes", position = 2, syncToken = 1L, adult = false)
        c.db.categories().upsert(listOf(catStandard, catAdult, catMovie))

        // Insère des chaînes
        val ch1 = ChannelEntity(
            uid = "$pid:L:1", playlistId = pid, remoteId = "1", name = "TF1", nameNorm = "tf1", matchKey = "tf1",
            number = 1, logo = "", categoryUid = catStandard.uid, epgId = "", epgKey = "", streamUrl = "http://srv/1", position = 0, syncToken = 1L,
        )
        val ch2 = ChannelEntity(
            uid = "$pid:L:2", playlistId = pid, remoteId = "2", name = "Dorcel XXX", nameNorm = "dorcel xxx", matchKey = "dorcel",
            number = 2, logo = "", categoryUid = catAdult.uid, epgId = "", epgKey = "", streamUrl = "http://srv/2", position = 1, syncToken = 1L,
        )
        c.db.channels().upsert(listOf(ch1, ch2))

        // Insère un film
        val m1 = MovieEntity(
            uid = "$pid:M:1", playlistId = pid, remoteId = "1", name = "Film Coquin Sensible", nameNorm = "film coquin",
            categoryUid = catMovie.uid, poster = "", position = 0, syncToken = 1L,
        )
        c.db.movies().upsert(listOf(m1))

        // Ajoute de l'historique
        c.db.progress().touchChannel(ChannelHistoryEntity(profileId, ch1.uid, 1000L))
        c.db.progress().touchChannel(ChannelHistoryEntity(profileId, ch2.uid, 2000L))
        c.db.progress().upsert(WatchProgressEntity(profileId, "key1", m1.uid, ContentKind.MOVIE, "", "", 5000L, 10000L))

        assertEquals(2, c.db.progress().history(profileId).size)
        assertNotNull(c.db.progress().get(profileId, m1.uid))

        // 1. Purge ciblée des contenus adultes
        val adultKeys = c.repo.clearWatchHistory(profileId = profileId, adultOnly = true)
        assertEquals(listOf("key1"), adultKeys)
        val histAfterAdult = c.db.progress().history(profileId)
        assertEquals(1, histAfterAdult.size)
        assertEquals(ch1.uid, histAfterAdult.first().channelUid)
        assertNull(c.db.progress().get(profileId, m1.uid))

        // 2. Re-ajouter et tester la purge par mot-clé
        c.db.progress().touchChannel(ChannelHistoryEntity(profileId, ch2.uid, 2000L))
        c.db.progress().upsert(WatchProgressEntity(profileId, "key1", m1.uid, ContentKind.MOVIE, "", "", 5000L, 10000L))
        assertEquals(2, c.db.progress().history(profileId).size)
        assertNotNull(c.db.progress().get(profileId, m1.uid))

        val kwKeys = c.repo.clearWatchHistory(profileId = profileId, keyword = "Coquin")
        assertEquals(listOf("key1"), kwKeys)
        assertNull(c.db.progress().get(profileId, m1.uid))
        assertEquals(2, c.db.progress().history(profileId).size) // ch2 a le nom "Dorcel", pas "Coquin"

        // 3. Purge totale
        c.repo.clearWatchHistory(profileId = profileId, adultOnly = false)
        assertEquals(0, c.db.progress().history(profileId).size)
        assertNull(c.db.progress().get(profileId, m1.uid))
    }

    @Test
    fun testCategoryAndGroupReordering() = runBlocking {
        val pid = c.repo.addPlaylist(PlaylistEntity(name = "Test Reorder", type = SourceType.M3U, url = "http://fake.local/test.m3u"))
        c.repo.setActivePlaylist(pid)

        // 1. Catégories Live TV
        val catLive1 = CategoryEntity(uid = "$pid:LIVE:1", playlistId = pid, kind = ContentKind.LIVE, remoteId = "l1", name = "Généraliste", position = 0, syncToken = 1L)
        val catLive2 = CategoryEntity(uid = "$pid:LIVE:2", playlistId = pid, kind = ContentKind.LIVE, remoteId = "l2", name = "Sport", position = 1, syncToken = 1L)
        val catLive3 = CategoryEntity(uid = "$pid:LIVE:3", playlistId = pid, kind = ContentKind.LIVE, remoteId = "l3", name = "Cinéma", position = 2, syncToken = 1L)
        c.db.categories().upsert(listOf(catLive1, catLive2, catLive3))

        // Vérification de l'ordre initial
        var rows = c.repo.liveCategoryRows().first()
        assertEquals(listOf("Généraliste", "Sport", "Cinéma"), rows.map { it.name })

        // Déplacer "Cinéma" vers le haut (position 2 -> 1)
        c.repo.moveCategory(ContentKind.LIVE, catLive3.uid, -1)
        rows = c.repo.liveCategoryRows().first()
        assertEquals(listOf("Généraliste", "Cinéma", "Sport"), rows.map { it.name })

        // Déplacer "Généraliste" vers le bas (position 0 -> 1)
        c.repo.moveCategory(ContentKind.LIVE, catLive1.uid, 1)
        rows = c.repo.liveCategoryRows().first()
        assertEquals(listOf("Cinéma", "Généraliste", "Sport"), rows.map { it.name })

        // 2. Catégories VOD Movies
        val catMovie1 = CategoryEntity(uid = "$pid:MOVIE:1", playlistId = pid, kind = ContentKind.MOVIE, remoteId = "m1", name = "Action", position = 0, syncToken = 1L)
        val catMovie2 = CategoryEntity(uid = "$pid:MOVIE:2", playlistId = pid, kind = ContentKind.MOVIE, remoteId = "m2", name = "Comédie", position = 1, syncToken = 1L)
        c.db.categories().upsert(listOf(catMovie1, catMovie2))

        var mRows = c.repo.vodCategoryRows(ContentKind.MOVIE).first()
        assertEquals(listOf("Action", "Comédie"), mRows.map { it.name })

        c.repo.moveCategory(ContentKind.MOVIE, catMovie2.uid, -1)
        mRows = c.repo.vodCategoryRows(ContentKind.MOVIE).first()
        assertEquals(listOf("Comédie", "Action"), mRows.map { it.name })

        // 3. Groupes utilisateurs personnalisés
        val g1Id = c.repo.createGroup("Groupe Alpha")
        val g2Id = c.repo.createGroup("Groupe Beta")
        val g3Id = c.repo.createGroup("Groupe Gamma")

        var groups = c.repo.userGroups().first().filter { it.id != FAVORITES_GROUP_ID }
        assertEquals(listOf("Groupe Alpha", "Groupe Beta", "Groupe Gamma"), groups.map { it.name })

        // Déplacer Groupe Gamma vers le haut (index 2 -> 1)
        c.repo.moveUserGroup(g3Id, -1)
        groups = c.repo.userGroups().first().filter { it.id != FAVORITES_GROUP_ID }
        assertEquals(listOf("Groupe Alpha", "Groupe Gamma", "Groupe Beta"), groups.map { it.name })

        // Déplacer Groupe Alpha vers le bas (index 0 -> 1)
        c.repo.moveUserGroup(g1Id, 1)
        groups = c.repo.userGroups().first().filter { it.id != FAVORITES_GROUP_ID }
        assertEquals(listOf("Groupe Gamma", "Groupe Alpha", "Groupe Beta"), groups.map { it.name })
    }

    @Test
    fun testVodSortInversion() {
        assertEquals(VodSortOrder.ALPHABETICAL_DESC, VodSortOrder.ALPHABETICAL.inverted())
        assertEquals(VodSortOrder.ALPHABETICAL, VodSortOrder.ALPHABETICAL_DESC.inverted())
        assertEquals(VodSortOrder.ADDED_ASC, VodSortOrder.ADDED_DESC.inverted())
        assertEquals(VodSortOrder.ADDED_DESC, VodSortOrder.ADDED_ASC.inverted())
        assertEquals(VodSortOrder.RELEASE_DATE_ASC, VodSortOrder.RELEASE_DATE_DESC.inverted())
        assertEquals(VodSortOrder.YEAR_ASC, VodSortOrder.YEAR_DESC.inverted())
        assertEquals(VodSortOrder.RATING_ASC, VodSortOrder.RATING_DESC.inverted())
    }

    @Test
    fun testStealthModeVault() = runBlocking {
        val pid = c.repo.addPlaylist(PlaylistEntity(name = "Test Stealth", type = SourceType.M3U, url = "http://fake.local/test.m3u"))
        c.repo.setActivePlaylist(pid)

        // Catégories et chaînes : une normale, une adulte
        val catNormal = CategoryEntity(uid = "$pid:LIVE:gen", playlistId = pid, kind = ContentKind.LIVE, remoteId = "g1", name = "Généraliste", position = 0, syncToken = 1L)
        val catAdult = CategoryEntity(uid = "$pid:LIVE:xxx", playlistId = pid, kind = ContentKind.LIVE, remoteId = "a1", name = "Adultes XXX", adult = true, position = 1, syncToken = 1L)
        c.db.categories().upsert(listOf(catNormal, catAdult))

        val ch1 = ChannelEntity(
            uid = "$pid:ch:tf1", playlistId = pid, remoteId = "1", name = "TF1 HD", nameNorm = "tf1 hd", matchKey = "tf1",
            number = 1, logo = "", categoryUid = catNormal.uid, epgId = "", epgKey = "", streamUrl = "http://fake.local/tf1.ts", position = 0, syncToken = 1L,
        )
        val ch2 = ChannelEntity(
            uid = "$pid:ch:dorcel", playlistId = pid, remoteId = "2", name = "Dorcel TV", nameNorm = "dorcel tv", matchKey = "dorcel",
            number = 2, logo = "", categoryUid = catAdult.uid, epgId = "", epgKey = "", streamUrl = "http://fake.local/dorcel.ts", position = 1, syncToken = 1L,
        )
        c.db.channels().upsert(listOf(ch1, ch2))

        // VOD : une normale, une adulte
        val catMovieNormal = CategoryEntity(uid = "$pid:MOVIE:act", playlistId = pid, kind = ContentKind.MOVIE, remoteId = "m_act", name = "Action", position = 0, syncToken = 1L)
        val catMovieAdult = CategoryEntity(uid = "$pid:MOVIE:xxx", playlistId = pid, kind = ContentKind.MOVIE, remoteId = "m_xxx", name = "Charme XXX", adult = true, position = 1, syncToken = 1L)
        c.db.categories().upsert(listOf(catMovieNormal, catMovieAdult))

        val m1 = MovieEntity(
            uid = "$pid:mov:matrix", playlistId = pid, remoteId = "1", name = "Matrix", nameNorm = "matrix",
            categoryUid = catMovieNormal.uid, poster = "", position = 0, syncToken = 1L,
        )
        val m2 = MovieEntity(
            uid = "$pid:mov:coquin", playlistId = pid, remoteId = "2", name = "Film Coquin", nameNorm = "film coquin",
            categoryUid = catMovieAdult.uid, poster = "", position = 1, syncToken = 1L,
        )
        c.db.movies().upsert(listOf(m1, m2))

        // Activation du contrôle parental
        c.parental.setPin("4321")
        assertTrue("Stealth mode should be enabled by default when PIN is set", c.settings.get().stealthModeEnabled)
        assertFalse("Vault should be locked initially", c.isVaultUnlocked.value)

        // 1. En mode furtif verrouillé :
        // Les catégories adultes ne doivent pas apparaître du tout dans liveGroups
        val groupsLocked = c.repo.liveGroups().first()
        val catNamesLocked = groupsLocked.map { it.title }
        assertTrue("Généraliste must be present", catNamesLocked.any { it.contains("Généraliste") })
        assertFalse("Adult category must NOT appear in liveGroups when locked", catNamesLocked.any { it.contains("Adultes") || it.contains("XXX") })
        // Aucun cadenas visible sur les catégories existantes
        assertTrue("No locks should be shown", groupsLocked.none { it.locked })

        // Les chaînes adultes ne doivent pas apparaître dans "Toutes les chaînes"
        val allChLocked = c.repo.channels(Repository.KEY_ALL).first()
        assertEquals(listOf("TF1 HD"), allChLocked.map { it.name })

        // Catégories VOD adultes invisibles
        val movieCatsLocked = c.repo.movieCategories().first()
        assertEquals(listOf("Action"), movieCatsLocked.map { it.name })

        // Recherche : les contenus adultes sont filtrés
        val searchDorcelLocked = c.repo.search("Dorcel")
        assertTrue("Adult channel must not appear in search results when locked", searchDorcelLocked.channels.isEmpty())
        val searchTf1 = c.repo.search("TF1")
        assertEquals(1, searchTf1.channels.size)

        // 2. Déverrouillage avec mauvais code
        assertFalse("Wrong pin must fail", c.unlockVault("0000"))
        assertFalse("Vault still locked after wrong pin", c.isVaultUnlocked.value)

        // 3. Déverrouillage avec le bon code
        assertTrue("Correct pin must succeed", c.unlockVault("4321"))
        assertTrue("Vault is now unlocked", c.isVaultUnlocked.value)

        // Les catégories et contenus réapparaissent
        val groupsUnlocked = c.repo.liveGroups().first()
        val catNamesUnlocked = groupsUnlocked.map { it.title }
        assertTrue("Adult category must reappear when unlocked", catNamesUnlocked.any { it.contains("Adultes") })

        val allChUnlocked = c.repo.channels(Repository.KEY_ALL).first()
        assertEquals(2, allChUnlocked.size)

        val movieCatsUnlocked = c.repo.movieCategories().first()
        assertEquals(2, movieCatsUnlocked.size)

        val searchDorcelUnlocked = c.repo.search("Dorcel")
        assertEquals(1, searchDorcelUnlocked.channels.size)

        // 4. Passage en arrière-plan : reverrouillage automatique du coffre-fort
        c.onBackground()
        assertFalse("Vault must automatically relock on background", c.isVaultUnlocked.value)

        val groupsRelocked = c.repo.liveGroups().first()
        assertFalse("Adult category must disappear again after backgrounding", groupsRelocked.map { it.title }.any { it.contains("Adultes") })
    }

    @Test
    fun testMiniPlayerAndAlwaysOnTop() = runBlocking {
        // 1. Mini-player state in PipState
        assertFalse(c.pip.isMiniPlayerMode.value)
        c.pip.setMiniPlayerInfo("TF1 HD", "Journal de 20h")
        assertEquals("TF1 HD", c.pip.miniPlayerTitle.value)
        assertEquals("Journal de 20h", c.pip.miniPlayerSubtitle.value)

        c.pip.setMiniPlayerMode(true)
        assertTrue(c.pip.isMiniPlayerMode.value)

        c.pip.setMiniPlayerMode(false)
        assertFalse(c.pip.isMiniPlayerMode.value)
        assertTrue(c.pip.screenActive.value)

        // 2. Always-on-top state
        val player = c.player
        assertFalse(player.isAlwaysOnTop.value)

        var toggled = false
        if (player is fr.yannickuhrig.vyzo.desktop.VlcPlayer) {
            player.onToggleAlwaysOnTop = {
                toggled = !toggled
                toggled
            }
            val res1 = player.toggleAlwaysOnTop()
            assertTrue(res1)
            assertTrue(player.isAlwaysOnTop.value)

            val res2 = player.toggleAlwaysOnTop()
            assertFalse(res2)
            assertFalse(player.isAlwaysOnTop.value)
        }
    }
}
