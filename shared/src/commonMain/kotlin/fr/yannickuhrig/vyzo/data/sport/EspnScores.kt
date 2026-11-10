package fr.yannickuhrig.vyzo.data.sport

import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.util.LenientJson
import fr.yannickuhrig.vyzo.util.TextNorm
import fr.yannickuhrig.vyzo.util.asArr
import fr.yannickuhrig.vyzo.util.asObj
import fr.yannickuhrig.vyzo.util.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class EspnTeam(val names: List<String>, val abbreviation: String, val score: String)

data class EspnGame(val startMs: Long, val home: EspnTeam, val away: EspnTeam, val state: String, val detail: String) {
    val inProgress: Boolean get() = state == "in"
    val started: Boolean get() = state == "in" || state == "post"
}

data class MatchScore(val score: String, val status: String) {
    val label: String get() = "$score · $status"
}

/** Lit un tableau de scores ESPN. Null si la réponse n'a plus la forme attendue. */
fun parseEspnScoreboard(body: String): List<EspnGame>? {
    val root = try {
        LenientJson.parseToJsonElement(body).asObj()
    } catch (_: IllegalArgumentException) {
        null
    } ?: return null
    val events = root["events"].asArr() ?: return null
    return events.mapNotNull { event -> event.asObj()?.let(::espnGame) }
}

private fun espnGame(event: JsonObject): EspnGame? {
    val start = parseEspnDate(event.str("date")) ?: return null
    val competition = event["competitions"].asArr()?.firstOrNull().asObj() ?: return null
    val teams = competition["competitors"].asArr()?.mapNotNull { it.asObj() }.orEmpty()
    val home = teams.firstOrNull { it.str("homeAway") == "home" } ?: return null
    val away = teams.firstOrNull { it.str("homeAway") == "away" } ?: return null
    val status = event["status"].asObj()?.get("type").asObj() ?: return null
    return EspnGame(start, espnTeam(home), espnTeam(away), status.str("state"), status.str("shortDetail"))
}

private fun espnTeam(competitor: JsonObject): EspnTeam {
    val team = competitor["team"].asObj()
    val names = listOf("displayName", "shortDisplayName", "name").mapNotNull { team?.str(it)?.takeIf(String::isNotBlank) }.distinct()
    return EspnTeam(names, team?.str("abbreviation").orEmpty(), competitor.str("score"))
}

/** SimpleDateFormat plutôt que java.time, absent des plus anciens Android que Vyzo prend en charge. */
private fun parseEspnDate(text: String): Long? = listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'").firstNotNullOfOrNull { pattern ->
    val format = SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
    try {
        format.parse(text)?.time
    } catch (_: ParseException) {
        null
    }
}

/** Relie un match du guide à un score ESPN : les deux équipes doivent se retrouver dans le titre, à une heure proche. */
object ScoreMatcher {
    private val SEPARATORS = Regex("[^a-z0-9]+")
    private const val WINDOW_MS = 3 * 3_600_000L
    private val GENERIC_WORDS = setOf("fc", "cf", "afc", "sc", "ac", "as", "ss", "sv", "stade", "club", "de", "the")

    /** Appellations courantes dans les guides français, absentes des noms ESPN. */
    private val ALIASES = mapOf(
        "paris saint germain" to listOf("psg", "paris sg", "paris st germain"),
        "marseille" to listOf("om"),
        "lyon" to listOf("ol"),
        "manchester united" to listOf("man utd", "man united"),
        "manchester city" to listOf("man city"),
        "bayern munich" to listOf("bayern munchen"),
    )

    private fun words(text: String) = TextNorm.search(text).split(SEPARATORS).filter { it.isNotEmpty() }

    fun find(title: String, startMs: Long, games: List<EspnGame>): MatchScore? {
        val titleWords = words(title)
        return games.filter { it.started && abs(it.startMs - startMs) <= WINDOW_MS }
            .mapNotNull { game ->
                val homeAt = position(titleWords, game.home)
                val awayAt = position(titleWords, game.away)
                if (homeAt < 0 || awayAt < 0 || homeAt == awayAt) return@mapNotNull null
                val score = if (homeAt < awayAt) "${game.home.score} - ${game.away.score}" else "${game.away.score} - ${game.home.score}"
                game to MatchScore(score, statusOf(game))
            }
            .minByOrNull { (game, _) -> abs(game.startMs - startMs) }?.second
    }

    private fun statusOf(game: EspnGame): String = when {
        game.state == "post" -> "Terminé"
        game.detail.equals("HT", ignoreCase = true) -> "Mi-temps"
        else -> game.detail
    }

    private fun position(titleWords: List<String>, team: EspnTeam): Int {
        val forms = team.names.flatMap { name ->
            val full = words(name)
            val reduced = full.filter { it !in GENERIC_WORDS }
            val aliases = ALIASES[full.joinToString(" ")].orEmpty().map(::words)
            // Sans « FC », le Paris FC se confondrait avec le Paris SG.
            listOfNotNull(full, reduced.takeIf { it.isNotEmpty() && it != listOf("paris") }) + aliases
        } + listOfNotNull(words(team.abbreviation).takeIf { it.singleOrNull().orEmpty().length >= 3 })
        return forms.distinct().map { indexOf(titleWords, it) }.filter { it >= 0 }.minOrNull() ?: -1
    }

    private fun indexOf(titleWords: List<String>, form: List<String>): Int =
        if (form.isEmpty()) -1 else titleWords.windowed(form.size).indexOf(form)
}

/** Score de chacun des [events] retrouvés dans [games], par clé d'événement. */
fun matchScores(events: List<SportEvent>, games: List<EspnGame>): Map<String, MatchScore> =
    events.mapNotNull { e -> ScoreMatcher.find(e.program.title, e.program.start, games)?.let { e.key to it } }.toMap()

/**
 * Scores du tableau public d'ESPN. Cette API n'est pas officielle : si elle ne répond plus pendant plusieurs tours,
 * Vyzo cesse de l'interroger pendant quelques heures et l'espace sport s'affiche simplement sans score.
 */
class EspnScores(
    private val fetch: suspend (String) -> String = { Http.getString(it, BROWSER_AGENT) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    @Volatile private var games: List<EspnGame> = emptyList()
    private var nextFetchAt = 0L
    private var failedRounds = 0
    private var pausedUntil = 0L

    val isPaused: Boolean get() = now() < pausedUntil

    /** Dernier résultat connu, sans attendre le réseau. */
    fun known(): List<EspnGame> = games

    suspend fun games(): List<EspnGame> = lock.withLock {
        val time = now()
        if (time < pausedUntil) return@withLock emptyList()
        if (time < nextFetchAt) return@withLock games

        val answered = coroutineScope { LEAGUES.map { league -> async { fetchLeague(league) } }.awaitAll() }.filterNotNull()
        if (answered.isEmpty()) {
            failedRounds++
            nextFetchAt = time + IDLE_REFRESH_MS
            if (failedRounds >= MAX_FAILED_ROUNDS) {
                failedRounds = 0
                games = emptyList()
                pausedUntil = time + PAUSE_MS
            }
            return@withLock games
        }
        failedRounds = 0
        games = answered.flatten()
        nextFetchAt = time + if (games.any { it.inProgress }) LIVE_REFRESH_MS else IDLE_REFRESH_MS
        games
    }

    private suspend fun fetchLeague(league: String): List<EspnGame>? = try {
        parseEspnScoreboard(fetch("$BASE_URL$league/scoreboard"))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        const val BASE_URL = "https://site.api.espn.com/apis/site/v2/sports/"
        const val MAX_FAILED_ROUNDS = 3
        const val LIVE_REFRESH_MS = 60_000L
        const val IDLE_REFRESH_MS = 10 * 60_000L
        const val PAUSE_MS = 6 * 3_600_000L
        private const val BROWSER_AGENT = "Mozilla/5.0"
        val LEAGUES = listOf(
            "soccer/fra.1", "soccer/fra.2", "soccer/uefa.champions", "soccer/uefa.europa", "soccer/uefa.europa.conf",
            "soccer/eng.1", "soccer/esp.1", "soccer/ita.1", "soccer/ger.1",
            "rugby/270559", "basketball/nba", "football/nfl", "hockey/nhl",
        )
    }
}
