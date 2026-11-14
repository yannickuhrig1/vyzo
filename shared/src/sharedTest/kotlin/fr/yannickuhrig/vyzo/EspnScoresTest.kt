package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.sport.EspnGame
import fr.yannickuhrig.vyzo.data.sport.EspnScores
import fr.yannickuhrig.vyzo.data.sport.EspnTeam
import fr.yannickuhrig.vyzo.data.sport.ScoreMatcher
import fr.yannickuhrig.vyzo.data.sport.SportEvent
import fr.yannickuhrig.vyzo.data.sport.matchScores
import fr.yannickuhrig.vyzo.data.sport.parseEspnScoreboard
import fr.yannickuhrig.vyzo.util.TextNorm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class EspnScoresTest {
    private val sample = """{"events":[{"date":"2026-11-08T14:00Z","competitions":[{"competitors":[
        {"homeAway":"home","score":"1","team":{"displayName":"Stade Rennais","shortDisplayName":"Rennes","name":"Stade Rennais","abbreviation":"REN"}},
        {"homeAway":"away","score":"2","team":{"displayName":"Lille","shortDisplayName":"Lille","name":"Lille","abbreviation":"LILL"}}]}],
        "status":{"type":{"state":"post","shortDetail":"FT"}}}]}"""
    private val start = 1_794_146_400_000L

    private fun team(name: String, short: String = name, abbr: String = "", score: String = "0") = EspnTeam(listOf(name, short), abbr, score)
    private fun game(home: EspnTeam, away: EspnTeam, state: String = "in", detail: String = "67'", at: Long = start) = EspnGame(at, home, away, state, detail)

    @Test
    fun readsTheRealResponseShape() {
        val game = parseEspnScoreboard(sample)!!.single()
        assertEquals(start, game.startMs)
        assertEquals("1", game.home.score)
        assertEquals("post", game.state)
    }

    @Test
    fun anUnexpectedResponseIsNotAnEmptyDay() {
        assertNull(parseEspnScoreboard("""{"erreur":true}"""))
        assertNull(parseEspnScoreboard("<html>"))
        assertEquals(0, parseEspnScoreboard("""{"events":[]}""")!!.size)
    }

    @Test
    fun theScoreFollowsTheOrderOfTheGuideTitle() {
        val games = listOf(game(team("Stade Rennais", "Rennes", score = "1"), team("Lille", score = "2")))
        assertEquals("1 - 2 · 67'", ScoreMatcher.find("Ligue 1 : Rennes - Lille", start, games)!!.label)
        assertEquals("2 - 1 · 67'", ScoreMatcher.find("Lille / Rennes (Direct)", start, games)!!.label)
    }

    @Test
    fun usualFrenchNamesAreRecognised() {
        val games = listOf(game(team("Paris Saint-Germain", abbr = "PSG", score = "3"), team("Olympique de Marseille", "Marseille", score = "0"), detail = "HT"))
        assertEquals("3 - 0 · Mi-temps", ScoreMatcher.find("Ligue 1 : Paris SG - OM", start, games)!!.label)
    }

    @Test
    fun parisFcIsNotParisSaintGermain() {
        val games = listOf(game(team("Paris FC"), team("Lyon")))
        assertNull(ScoreMatcher.find("Paris SG - Lyon", start, games))
    }

    @Test
    fun bothTeamsAndACloseTimeAreRequired() {
        val games = listOf(game(team("Rennes"), team("Lille")))
        assertNull(ScoreMatcher.find("Rennes - Brest", start, games))
        assertNull(ScoreMatcher.find("Rennes - Lille", start + 5 * 3_600_000L, games))
        assertNull(ScoreMatcher.find("Rennes - Lille", start, listOf(game(team("Rennes"), team("Lille"), state = "pre"))))
    }

    @Test
    fun finishedGamesAreLabelled() {
        val games = listOf(game(team("Rennes", score = "1"), team("Lille", score = "2"), state = "post"))
        assertEquals("1 - 2 · Terminé", ScoreMatcher.find("Rennes - Lille", start, games)!!.label)
    }

    @Test
    fun repeatedFailuresPauseTheApiWithoutSurfacingAnError() = runBlocking {
        var time = 1_000L
        var calls = 0
        val scores = EspnScores(fetch = { calls++; throw IOException("coupé") }, now = { time })
        repeat(EspnScores.MAX_FAILED_ROUNDS) {
            assertEquals(emptyList<EspnGame>(), scores.games())
            time += EspnScores.IDLE_REFRESH_MS
        }
        assertTrue(scores.isPaused)
        val afterPause = calls
        scores.games()
        assertEquals("plus aucune requête pendant la pause", afterPause, calls)
        time += EspnScores.PAUSE_MS
        assertFalse(scores.isPaused)
    }

    @Test
    fun oneBrokenLeagueDoesNotDisableTheOthers() = runBlocking {
        val scores = EspnScores(fetch = { url -> if ("fra.1" in url) sample else throw IOException("coupé") }, now = { 1_000L })
        assertEquals(1, scores.games().size)
        assertFalse(scores.isPaused)
    }

    @Test
    fun responsesAreKeptBetweenRefreshes() = runBlocking {
        var time = 1_000L
        var calls = 0
        val scores = EspnScores(fetch = { calls++; """{"events":[]}""" }, now = { time })
        scores.games()
        val firstRound = calls
        time += EspnScores.IDLE_REFRESH_MS - 1
        scores.games()
        assertEquals(firstRound, calls)
        time += 1
        scores.games()
        assertEquals(firstRound * 2, calls)
    }

    @Test
    fun rugbyNationsAndFrenchClubsAreMatched() {
        val games = listOf(
            game(team("Wales", score = "35"), team("New Zealand", score = "28"), detail = "42'"),
            game(team("Stade Toulousain", score = "24"), team("Bordeaux Begles", score = "17"), state = "post"),
        )
        assertEquals("35 - 28 · 42'", ScoreMatcher.find("Rugby : Pays de Galles / Nouvelle-Zélande", start, games)!!.label)
        assertEquals("28 - 35 · 42'", ScoreMatcher.find("Nouvelle-Zélande - Pays de Galles", start, games)!!.label)
        assertEquals("24 - 17 · Terminé", ScoreMatcher.find("Top 14 : Toulouse - UBB", start, games)!!.label)

        val tf1 = ChannelEntity(
            uid = "tf1", playlistId = 1, remoteId = "tf1", name = "TF1", nameNorm = TextNorm.search("TF1"),
            matchKey = TextNorm.matchKey("TF1"), number = 1, logo = "", categoryUid = "", epgId = "tf1",
            epgKey = "tf1", streamUrl = "", position = 0, syncToken = 0,
        )
        val prog = ProgramEntity(
            channelKey = "tf1", start = start, stop = start + 7_200_000L, title = "Rugby",
            description = "Pays de Galles / Nouvelle-Zélande\nEn direct", sourceKey = "t", generation = 1,
        )
        val event = SportEvent(prog, listOf(tf1))
        assertEquals("35 - 28 · 42'", matchScores(listOf(event), games)[event.key]!!.label)
    }
}
