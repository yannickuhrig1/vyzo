package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.db.FollowedEpisodeRow
import fr.yannickuhrig.vyzo.data.db.SeriesEpisodeAlertEntity
import fr.yannickuhrig.vyzo.data.db.SeriesScheduleEntity
import fr.yannickuhrig.vyzo.data.home.HomeRows
import fr.yannickuhrig.vyzo.data.intro.parseTvMazeSchedule
import fr.yannickuhrig.vyzo.data.prefs.AppSettings
import fr.yannickuhrig.vyzo.data.tmdb.MetadataCache
import fr.yannickuhrig.vyzo.data.tmdb.parseTmdbSeriesSchedule
import fr.yannickuhrig.vyzo.data.vod.SeriesTracking
import fr.yannickuhrig.vyzo.ui.common.Fmt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SeriesTrackingTest {

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun row(season: Int, episode: Int, pos: Long = 0L, dur: Long = 1000L, updatedAt: Long = 0L) =
        FollowedEpisodeRow(
            seriesUid = "s",
            episodeUid = "s:$season-$episode",
            season = season,
            episode = episode,
            episodeTitle = "Épisode $episode",
            positionMs = pos,
            durationMs = dur,
            progressUpdatedAt = updatedAt,
        )

    private fun dayMillis(daysFromAnchor: Int): Pair<Long, String> {
        val base = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.NOVEMBER, 14, 12, 0, 0)
        }
        val anchor = base.timeInMillis
        base.add(Calendar.DAY_OF_MONTH, daysFromAnchor)
        return anchor to Fmt.todayIso(base.timeInMillis)
    }

    @Test
    fun parseAndEncodeFreshSlotsRoundTripsInOrder() {
        val parsed = SeriesTracking.parseFreshSlots("2:5, 1:10, 2:1, invalid, 2:5")
        assertEquals(setOf(2 to 5, 1 to 10, 2 to 1), parsed)
        assertEquals("1:10,2:1,2:5", SeriesTracking.encodeFreshSlots(parsed))
        assertTrue(SeriesTracking.parseFreshSlots("").isEmpty())
    }

    @Test
    fun startingAtSeasonThreeIgnoresUnwatchedSeasonsOneAndTwo() {
        val rows = listOf(
            row(1, 1), row(1, 2),
            row(2, 1), row(2, 2),
            row(3, 1, pos = 1000L, dur = 1000L, updatedAt = 500L),
            row(3, 2),
            row(3, 3),
        )
        assertEquals(2, SeriesTracking.countUnwatchedEpisodes(rows, alert = null))
        val info = SeriesTracking.summarize(rows, null, null)
        assertEquals("+2 à voir", info.badge)
        assertFalse(info.badgeUpcoming)
    }

    @Test
    fun favoriteWithUnwatchedAlertShowsAlertCountEvenWithoutWatchHistory() {
        val rows = listOf(row(1, 1), row(1, 2), row(2, 1))
        val alert = SeriesEpisodeAlertEntity("s", 2, 1, 1, "2:1", detectedAt = 900L)
        assertEquals(1, SeriesTracking.countUnwatchedEpisodes(rows, alert))
        assertEquals("1 nouveau", SeriesTracking.summarize(rows, alert, null).badge)
    }

    @Test
    fun caughtUpSeriesWithNoNewEpisodesHasZeroCount() {
        val rows = listOf(row(1, 1, pos = 1000L, dur = 1000L), row(1, 2, pos = 960L, dur = 1000L))
        assertEquals(0, SeriesTracking.countUnwatchedEpisodes(rows, null))
        assertEquals("", SeriesTracking.summarize(rows, null, null).badge)
    }

    @Test
    fun tmdbSeriesScheduleExtractsNextEpisodeAndFutureSeason() {
        val (now, tomorrow) = dayMillis(1)
        val (_, nextYearSeason) = dayMillis(120)
        val j = json(
            """{
              "status": "Returning Series",
              "next_episode_to_air": {"season_number": 3, "episode_number": 2, "name": "Le retour", "air_date": "$tomorrow"},
              "seasons": [
                {"season_number": 1, "air_date": "2024-01-01", "episode_count": 8},
                {"season_number": 3, "air_date": "$tomorrow", "episode_count": 10},
                {"season_number": 4, "air_date": "$nextYearSeason", "episode_count": 8}
              ]
            }""",
        )
        val sched = parseTmdbSeriesSchedule("s", 42, j, now)
        assertEquals("Returning Series", sched.status)
        assertEquals(3, sched.nextSeason)
        assertEquals(2, sched.nextEpisode)
        assertEquals("Le retour", sched.nextTitle)
        assertEquals(tomorrow, sched.nextAirDate)
        assertEquals(3, sched.upcomingSeason)
        val display = SeriesTracking.formatScheduleDisplay(sched, now)
        assertNotNull(display)
        assertTrue(display!!.banner.contains("Prochain épisode · S3 E2"))
        assertTrue(display.banner.contains("Demain"))
        assertEquals("S3E2 · Demain", display.shortBadge)
        assertTrue(display.hasUpcomingDate)
    }

    @Test
    fun tmdbSeriesScheduleFallsBackToUpcomingSeasonOrEndedLabel() {
        val (now, futureDate) = dayMillis(45)
        val upcomingSeasonOnly = parseTmdbSeriesSchedule(
            "s1",
            10,
            json(
                """{
                  "status": "In Production",
                  "next_episode_to_air": null,
                  "seasons": [{"season_number": 5, "air_date": "$futureDate", "episode_count": 12}]
                }""",
            ),
            now,
        )
        val seasonDisplay = SeriesTracking.formatScheduleDisplay(upcomingSeasonOnly, now)!!
        assertTrue(seasonDisplay.banner.contains("Saison 5 annoncée"))
        assertTrue(seasonDisplay.banner.contains("(12 ép.)"))
        assertTrue(seasonDisplay.hasUpcomingDate)

        val ended = parseTmdbSeriesSchedule("s2", 11, json("""{"status": "Ended", "seasons": []}"""), now)
        val endedDisplay = SeriesTracking.formatScheduleDisplay(ended, now)!!
        assertEquals("Série terminée", endedDisplay.banner)
        assertEquals("", endedDisplay.shortBadge)
        assertTrue(endedDisplay.isEnded)
    }

    @Test
    fun scheduleRefreshUsesAdaptiveTtl24hForActiveAnd30DaysForEnded() {
        val now = 100L * MetadataCache.DAY_MS
        val activeFresh = SeriesScheduleEntity("s", "Returning Series", 2, 1, "", "2099-01-01", 0, "", 0, 1, checkedAt = now - 12 * 3_600_000L)
        val activeStale = activeFresh.copy(checkedAt = now - MetadataCache.DAY_MS - 1)
        val endedWithin30d = activeFresh.copy(status = "Ended", nextEpisode = 0, nextAirDate = "", checkedAt = now - 20 * MetadataCache.DAY_MS)
        val endedPast30d = endedWithin30d.copy(checkedAt = now - MetadataCache.MAX_AGE_MS - 1)

        assertTrue(SeriesTracking.scheduleNeedsRefresh(null, now))
        assertFalse(SeriesTracking.scheduleNeedsRefresh(activeFresh, now))
        assertTrue(SeriesTracking.scheduleNeedsRefresh(activeStale, now))
        assertFalse(SeriesTracking.scheduleNeedsRefresh(endedWithin30d, now))
        assertTrue(SeriesTracking.scheduleNeedsRefresh(endedPast30d, now))
    }

    @Test
    fun parseTvMazeScheduleMapsRunningAndNextEpisode() {
        val (now, tomorrow) = dayMillis(1)
        val show = json(
            """{
              "name": "Severance",
              "status": "Running",
              "_embedded": {
                "nextepisode": {"season": 2, "number": 4, "name": "Woe's Hollow", "airdate": "$tomorrow"}
              }
            }""",
        )
        val parsed = parseTvMazeSchedule("s", show, now)
        assertEquals("Returning Series", parsed.status)
        assertEquals(2, parsed.nextSeason)
        assertEquals(4, parsed.nextEpisode)
        assertEquals("Woe's Hollow", parsed.nextTitle)
        assertEquals(tomorrow, parsed.nextAirDate)
    }

    @Test
    fun isoDateFormatsTodayTomorrowYesterdayAndYearProperly() {
        val (anchor, today) = dayMillis(0)
        val (_, tomorrow) = dayMillis(1)
        val (_, yesterday) = dayMillis(-1)
        val (_, nextYear) = dayMillis(90)
        assertEquals("Aujourd'hui", Fmt.isoDate(today, anchor))
        assertEquals("Demain", Fmt.isoDate(tomorrow, anchor))
        assertEquals("Hier", Fmt.isoDate(yesterday, anchor))
        assertTrue(Fmt.isoDate(nextYear, anchor).contains("2027"))
        assertNull(SeriesTracking.formatScheduleDisplay(SeriesScheduleEntity("s", "", 0, 0, "", "", 0, "", 0, 0, 0), anchor))
    }

    @Test
    fun newEpisodesHomeRowIsInsertedOnceAfterContinueSeries() {
        assertEquals(
            listOf(HomeRows.ON_AIR, HomeRows.CONTINUE_SERIES, HomeRows.NEW_EPISODES, HomeRows.CONTINUE_MOVIES, HomeRows.APPS),
            HomeRows.withNewEpisodes(listOf(HomeRows.ON_AIR, HomeRows.CONTINUE_SERIES, HomeRows.CONTINUE_MOVIES, HomeRows.APPS)),
        )
        assertEquals(listOf(HomeRows.ON_AIR, HomeRows.NEW_EPISODES, HomeRows.APPS), HomeRows.withNewEpisodes(listOf(HomeRows.ON_AIR, HomeRows.APPS)))
        val before = AppSettings(homeRows = listOf(HomeRows.CONTINUE_SERIES, HomeRows.APPS), homeRowsByProfile = mapOf("p1" to listOf(HomeRows.APPS)))
        val after = before.withNewEpisodesRow()
        assertEquals(listOf(HomeRows.CONTINUE_SERIES, HomeRows.NEW_EPISODES, HomeRows.APPS), after.homeRows)
        assertEquals(listOf(HomeRows.NEW_EPISODES, HomeRows.APPS), after.homeRowsByProfile.getValue("p1"))
        val removed = after.copy(homeRows = listOf(HomeRows.CONTINUE_SERIES, HomeRows.APPS))
        assertEquals(removed, removed.withNewEpisodesRow())
    }
}
