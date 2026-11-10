package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.MovieEntity
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.trakt.TraktClient
import fr.yannickuhrig.vyzo.data.trakt.TraktManager
import fr.yannickuhrig.vyzo.data.trakt.TraktMedia
import fr.yannickuhrig.vyzo.data.trakt.TraktPoll
import fr.yannickuhrig.vyzo.data.trakt.TraktSessionLost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class TraktSyncTest {
    private val tokenJson = """{"access_token":"nouveau","refresh_token":"suivant","expires_in":7776000}"""
    private val requests = CopyOnWriteArrayList<Pair<RecordedRequest, String>>()

    private fun scenario(answer: (RecordedRequest) -> MockResponse, block: suspend (AppContainer, TraktClient, MockWebServer) -> Unit) = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request to request.body.readUtf8()
                return answer(request)
            }
        }
        server.start()
        try {
            val c = testContainer()
            c.settings.update { it.copy(traktClientId = "id", traktClientSecret = "secret") }
            block(c, TraktClient(c.settings, server.url("/").toString().trimEnd('/')), server)
        } finally {
            server.shutdown()
        }
    }

    private suspend fun signIn(c: AppContainer, access: String = "valide", expiresIn: Long = 30 * 86_400_000L) {
        c.settings.update {
            it.copy(
                traktAccessToken = access, traktRefreshToken = "recharge", traktTokenExpiresAt = System.currentTimeMillis() + expiresIn,
                traktProfileId = it.activeProfileId,
            )
        }
    }

    private fun ok(body: String = "{}") = MockResponse().setBody(body)
    private fun status(code: Int) = MockResponse().setResponseCode(code)
    private fun sent(path: String) = requests.filter { it.first.path == path }

    @Test
    fun deviceLoginWalksThroughTheStatesOfTheCode() {
        val answers = ArrayDeque(listOf(status(400), status(429), ok(tokenJson)))
        scenario({ r ->
            if (r.path == "/oauth/device/code") {
                ok("""{"device_code":"d","user_code":"ABCD1234","verification_url":"https://trakt.tv/activate","expires_in":600,"interval":5}""")
            } else {
                answers.removeFirst()
            }
        }) { _, client, _ ->
            val code = client.deviceCode()
            assertEquals("ABCD1234", code.userCode)
            assertEquals(5, code.intervalSec)
            assertEquals(TraktPoll.Pending, client.pollDevice(code.deviceCode))
            assertEquals(TraktPoll.SlowDown, client.pollDevice(code.deviceCode))
            assertEquals("nouveau", (client.pollDevice(code.deviceCode) as TraktPoll.Granted).tokens.access)
            val polled = sent("/oauth/device/token").first()
            assertEquals("2", polled.first.getHeader("trakt-api-version"))
            assertEquals("id", polled.first.getHeader("trakt-api-key"))
            assertTrue(polled.second.contains("\"client_secret\":\"secret\""))
        }
    }

    @Test
    fun aDeniedOrExpiredCodeStopsTheWait() {
        val answers = ArrayDeque(listOf(status(418), status(410), status(404)))
        scenario({ answers.removeFirst() }) { _, client, _ ->
            assertEquals(TraktPoll.Denied, client.pollDevice("d"))
            assertEquals(TraktPoll.Expired, client.pollDevice("d"))
            assertEquals(TraktPoll.Expired, client.pollDevice("d"))
        }
    }

    @Test
    fun scrobblesDescribeTheMovieOrTheEpisode() = scenario({ ok() }) { c, client, _ ->
        signIn(c)
        client.scrobble("start", TraktMedia("Dune", 2021, 438631), 12.5)
        client.scrobble("stop", TraktMedia("Dark", 2017, 70523, season = 2, episode = 3), 96.0)
        val film = sent("/scrobble/start").single()
        assertEquals("Bearer valide", film.first.getHeader("Authorization"))
        assertTrue(film.second, film.second.contains("\"movie\":{\"title\":\"Dune\",\"year\":2021,\"ids\":{\"tmdb\":438631}}"))
        assertTrue(film.second, film.second.contains("\"progress\":12.5"))
        val episode = sent("/scrobble/stop").single().second
        assertTrue(episode, episode.contains("\"show\":{\"title\":\"Dark\""))
        assertTrue(episode, episode.contains("\"episode\":{\"season\":2,\"number\":3}"))
    }

    @Test
    fun aScrobbleAlreadyKnownIsNotAnError() = scenario({ status(409) }) { c, client, _ ->
        signIn(c)
        client.scrobble("stop", TraktMedia("Dune", 2021, 1), 90.0)
    }

    @Test
    fun anExpiredSessionIsRenewedOnceThenRetried() {
        var firstCall = true
        scenario({ r ->
            when {
                r.path == "/oauth/token" -> ok(tokenJson)
                firstCall -> { firstCall = false; status(401) }
                else -> ok()
            }
        }) { c, client, _ ->
            signIn(c, access = "perime")
            client.scrobble("start", TraktMedia("Dune", 2021, 1), 1.0)
            val tries = sent("/scrobble/start")
            assertEquals(listOf("Bearer perime", "Bearer nouveau"), tries.map { it.first.getHeader("Authorization") })
            assertEquals(1, sent("/oauth/token").size)
            assertEquals("nouveau", c.settings.get().traktAccessToken)
            assertEquals("suivant", c.settings.get().traktRefreshToken)
        }
    }

    @Test
    fun aRefusedRenewalLogsOutCleanly() = scenario({ status(401) }) { c, client, _ ->
        signIn(c, access = "perime")
        try {
            client.scrobble("start", TraktMedia("Dune", 2021, 1), 1.0)
            fail("la session aurait dû être perdue")
        } catch (_: TraktSessionLost) {
            assertEquals("", c.settings.get().traktAccessToken)
            assertEquals("", c.settings.get().traktRefreshToken)
        }
    }

    private fun movie(pid: Long, id: String, name: String, year: String) = MovieEntity(
        uid = "$pid:$id", playlistId = pid, remoteId = id, name = name, nameNorm = name.lowercase(), categoryUid = "c", poster = "",
        year = year, durationSecs = 7_200, streamUrl = "", position = 0, syncToken = 1,
    )

    @Test
    fun syncReceivesWhatTraktKnowsAndSendsWhatItDoesNot() = scenario({ r ->
        when (r.path) {
            "/sync/watched/movies" -> ok("""[{"movie":{"title":"Dune","year":2021,"ids":{"tmdb":438631}}},{"movie":{"title":"Absent","year":2000,"ids":{}}},{"movie":{"title":"En cours","year":2010,"ids":{}}}]""")
            "/sync/watched/shows" -> ok("[]")
            else -> ok()
        }
    }) { c, client, _ ->
        signIn(c)
        val pid = c.db.playlists().insert(PlaylistEntity(name = "T", type = SourceType.M3U, url = "http://test.invalid/a.m3u"))
        c.db.movies().upsert(listOf(movie(pid, "1", "Dune", "2021"), movie(pid, "2", "Alien", "1979"), movie(pid, "3", "En cours", "2010")))
        c.repo.markWatched("$pid:2", ContentKind.MOVIE, "", 7_200_000)
        c.repo.saveProgress("$pid:3", ContentKind.MOVIE, "", 600_000, 7_200_000)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = TraktManager(c.settings, c.repo, c.db, c.tmdb, scope, client)
        manager.syncIfDue()

        val profile = c.repo.viewScopeNow().profileId
        val dune = c.db.progress().get(profile, "$pid:1")!!
        assertEquals("marqué vu en local", dune.durationMs, dune.positionMs)
        assertEquals("la position en cours n'est pas écrasée", 600_000L, c.db.progress().get(profile, "$pid:3")!!.positionMs)

        val history = sent("/sync/history").single().second
        assertTrue(history, history.contains("Alien"))
        assertFalse("déjà sur Trakt : $history", history.contains("Dune"))
        assertFalse("pas terminé : $history", history.contains("En cours"))
        assertTrue(c.settings.get().traktLastSync > 0)

        val before = requests.size
        manager.syncIfDue()
        assertEquals("pas de nouvelle synchro avant six heures", before, requests.size)
        assertNull(c.db.progress().get("autre-profil", "$pid:1"))
    }

    @Test
    fun anotherProfileNeverTalksToTheAccount() = scenario({ ok("[]") }) { c, client, _ ->
        c.settings.update { it.copy(traktAccessToken = "valide", traktTokenExpiresAt = Long.MAX_VALUE, traktProfileId = "quelqu-un-d-autre") }
        val manager = TraktManager(c.settings, c.repo, c.db, c.tmdb, CoroutineScope(SupervisorJob() + Dispatchers.Default), client)
        manager.syncIfDue()
        assertTrue(requests.isEmpty())
        assertFalse(manager.canScrobble)
    }
}
