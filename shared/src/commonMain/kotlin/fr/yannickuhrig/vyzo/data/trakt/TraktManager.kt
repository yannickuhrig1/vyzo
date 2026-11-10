package fr.yannickuhrig.vyzo.data.trakt

import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.EpisodeEntity
import fr.yannickuhrig.vyzo.data.db.MovieEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import fr.yannickuhrig.vyzo.data.db.WatchProgressEntity
import fr.yannickuhrig.vyzo.data.net.HttpStatusException
import fr.yannickuhrig.vyzo.data.prefs.AppSettings
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.data.tmdb.TmdbKind
import fr.yannickuhrig.vyzo.data.tmdb.TmdbService
import fr.yannickuhrig.vyzo.data.tmdb.TmdbTitle
import fr.yannickuhrig.vyzo.data.tmdb.cleanTitleForTmdb
import fr.yannickuhrig.vyzo.util.TextNorm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

enum class TraktList(val path: String, val kind: TmdbKind) {
    WATCHLIST_MOVIES("/sync/watchlist/movies", TmdbKind.MOVIE),
    WATCHLIST_SHOWS("/sync/watchlist/shows", TmdbKind.SERIES),
    RECOMMENDED_MOVIES("/recommendations/movies?limit=30", TmdbKind.MOVIE),
    RECOMMENDED_SHOWS("/recommendations/shows?limit=30", TmdbKind.SERIES),
}

data class TraktUiState(
    val login: TraktDeviceCode? = null,
    val busy: Boolean = false,
    val message: String = "",
)

/** Envoie à Trakt le début, les pauses et la fin d'une lecture, sans répéter un état déjà transmis. */
class TraktScrobbler(private val send: suspend (action: String, media: TraktMedia, progress: Double) -> Unit) {
    private var current: TraktMedia? = null
    private var running = false
    private var progress = 0.0

    suspend fun play(media: TraktMedia, progress: Double) {
        if (current?.key != media.key) {
            finish()
            current = media
        }
        this.progress = progress
        if (!running) {
            running = true
            send("start", media, progress)
        }
    }

    /** Retient où en est la lecture sans rien envoyer : une pause de fin d'épisode doit compter pour le « stop » qui suit. */
    fun note(progress: Double) {
        this.progress = progress
    }

    suspend fun pause(progress: Double) {
        val media = current ?: return
        this.progress = progress
        if (running) {
            running = false
            send("pause", media, progress)
        }
    }

    suspend fun stop(progress: Double) {
        if (current == null) return
        this.progress = progress
        finish()
    }

    private suspend fun finish() {
        val media = current ?: return
        current = null
        running = false
        send("stop", media, progress)
    }
}

class TraktManager(
    private val settings: SettingsStore,
    private val repo: Repository,
    private val db: AppDatabase,
    private val tmdb: TmdbService,
    private val scope: CoroutineScope,
    private val client: TraktClient = TraktClient(settings),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private sealed interface Playback {
        data class Playing(val media: TraktMedia, val progress: Double) : Playback
        data class Paused(val progress: Double) : Playback
        data class Stopped(val progress: Double) : Playback
    }

    private data class CachedTitles(val titles: List<TmdbTitle>, val at: Long)

    private val mutableUi = MutableStateFlow(TraktUiState())
    val ui: StateFlow<TraktUiState> = mutableUi.asStateFlow()

    private val playback = Channel<Playback>(Channel.UNLIMITED)
    private val scrobbler = TraktScrobbler(::sendScrobble)
    private val cachedLists = ConcurrentHashMap<TraktList, CachedTitles>()
    private val syncLock = Mutex()
    private var loginJob: Job? = null

    init {
        scope.launch { deliverPlayback() }
    }

    /** Ne suit que le profil qui s'est connecté : ce que regardent les enfants n'arrive pas sur ce compte. */
    private fun isActiveFor(s: AppSettings) = s.traktConnected && s.traktProfileId == s.activeProfileId

    val canScrobble: Boolean get() = settings.state.value.let { isActiveFor(it) && it.traktScrobble }

    private suspend fun deliverPlayback() {
        var pausePending: Double? = null
        while (true) {
            val pending = pausePending
            val event = if (pending != null) withTimeoutOrNull(PAUSE_GRACE_MS) { playback.receive() } else playback.receive()
            if (event == null) {
                // Une mise en mémoire tampon coupe la lecture un instant : seule une vraie pause arrive jusqu'ici.
                scrobbler.pause(pending!!)
                pausePending = null
                continue
            }
            when (event) {
                is Playback.Playing -> {
                    pausePending = null
                    scrobbler.play(event.media, event.progress)
                }
                is Playback.Paused -> {
                    scrobbler.note(event.progress)
                    pausePending = event.progress
                }
                is Playback.Stopped -> {
                    pausePending = null
                    scrobbler.stop(event.progress)
                }
            }
        }
    }

    private suspend fun sendScrobble(action: String, media: TraktMedia, progress: Double) {
        try {
            client.scrobble(action, media, progress)
        } catch (e: CancellationException) {
            throw e
        } catch (_: TraktSessionLost) {
            mutableUi.update { it.copy(message = RECONNECT_MESSAGE) }
        } catch (_: Exception) {
            // Hors ligne : la synchronisation rattrapera ce qui a été vu.
        }
    }

    fun onPlaying(media: TraktMedia, progress: Double) {
        playback.trySend(Playback.Playing(media, progress))
    }

    fun onPaused(progress: Double) {
        playback.trySend(Playback.Paused(progress))
    }

    fun onStopped(progress: Double) {
        playback.trySend(Playback.Stopped(progress))
    }

    suspend fun movieMedia(movie: MovieEntity): TraktMedia {
        val (title, nameYear) = cleanTitleForTmdb(movie.name)
        val year = movie.yearInt.takeIf { it > 0 } ?: nameYear
        return TraktMedia(title, year, quietly { tmdb.enrichMovie(movie.name, year).tmdbId })
    }

    suspend fun episodeMedia(series: SeriesEntity, episode: EpisodeEntity): TraktMedia {
        val (title, nameYear) = cleanTitleForTmdb(series.name)
        val year = series.yearInt.takeIf { it > 0 } ?: nameYear
        return TraktMedia(title, year, quietly { tmdb.findSeriesId(series.name, year) }, episode.season, episode.episode)
    }

    private suspend fun <T> quietly(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    fun startLogin() {
        if (loginJob?.isActive == true) return
        loginJob = scope.launch { runLogin() }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        mutableUi.update { it.copy(login = null, busy = false) }
    }

    private suspend fun runLogin() {
        val s = settings.get()
        if (s.traktClientId.isBlank() || s.traktClientSecret.isBlank()) {
            mutableUi.update { it.copy(message = "Renseigne d'abord l'identifiant et le secret de ton application Trakt") }
            return
        }
        mutableUi.value = TraktUiState(busy = true)
        val outcome = try {
            awaitAuthorization()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) {
            if (e.code == 401 || e.code == 403) "Trakt refuse cet identifiant ou ce secret" else UNREACHABLE_MESSAGE
        } catch (_: Exception) {
            UNREACHABLE_MESSAGE
        }
        mutableUi.value = TraktUiState(message = outcome)
    }

    private suspend fun awaitAuthorization(): String {
        val code = client.deviceCode()
        mutableUi.update { it.copy(login = code, busy = false) }
        var waitMs = code.intervalSec.coerceAtLeast(1) * 1_000L
        val deadline = now() + code.expiresInSec * 1_000L
        while (now() < deadline) {
            delay(waitMs)
            when (val poll = client.pollDevice(code.deviceCode)) {
                TraktPoll.Pending -> Unit
                TraktPoll.SlowDown -> waitMs += 1_000L
                TraktPoll.Expired -> return "Le code a expiré, recommence"
                TraktPoll.Denied -> return "Connexion refusée sur Trakt"
                is TraktPoll.Granted -> {
                    settings.update {
                        it.copy(
                            traktAccessToken = poll.tokens.access, traktRefreshToken = poll.tokens.refresh,
                            traktTokenExpiresAt = poll.tokens.expiresAt, traktProfileId = it.activeProfileId, traktLastSync = 0,
                        )
                    }
                    cachedLists.clear()
                    scope.launch { sync(force = true) }
                    return "Connecté à Trakt"
                }
            }
        }
        return "Le code a expiré, recommence"
    }

    fun disconnect() {
        scope.launch {
            quietly { client.revoke() }
            settings.update { it.copy(traktAccessToken = "", traktRefreshToken = "", traktTokenExpiresAt = 0, traktProfileId = "", traktLastSync = 0) }
            cachedLists.clear()
            mutableUi.value = TraktUiState(message = "Déconnecté de Trakt")
        }
    }

    fun syncNow() {
        scope.launch { sync(force = true) }
    }

    suspend fun syncIfDue() = sync(force = false)

    private suspend fun sync(force: Boolean) {
        if (!syncLock.tryLock()) return
        try {
            val s = settings.get()
            if (!isActiveFor(s) || (!force && now() - s.traktLastSync < SYNC_INTERVAL_MS)) return
            mutableUi.update { it.copy(busy = true, message = "") }
            val message = try {
                val startedAt = now()
                val watched = client.watched()
                val profileId = repo.viewScopeNow().profileId
                val received = pullWatched(profileId, watched)
                val sent = pushWatched(profileId, s.traktLastSync, watched)
                settings.update { it.copy(traktLastSync = startedAt) }
                "Synchronisé : $received reçu(s), $sent envoyé(s)"
            } catch (e: CancellationException) {
                throw e
            } catch (_: TraktSessionLost) {
                RECONNECT_MESSAGE
            } catch (_: Exception) {
                UNREACHABLE_MESSAGE
            }
            mutableUi.update { it.copy(busy = false, message = message) }
        } finally {
            syncLock.unlock()
        }
    }

    private suspend fun localTitles(title: TraktTitle, kind: TmdbKind): List<TmdbTitle> {
        val english = TmdbTitle(title.title, title.title, title.year)
        val french = title.tmdbId?.let { id -> quietly { tmdb.titleById(kind, id) } }
        return listOfNotNull(french, english)
    }

    /** Ne touche pas à ce qui a déjà une progression ici : un film en cours de revisionnage garde sa position. */
    private suspend fun pullWatched(profileId: String, watched: TraktWatched): Int {
        var received = 0
        for (title in watched.movies.take(MAX_ITEMS_PER_SYNC)) {
            val movie = repo.moviesMatching(localTitles(title, TmdbKind.MOVIE), 1).firstOrNull() ?: continue
            if (db.progress().get(profileId, movie.uid) != null) continue
            repo.markWatched(movie.uid, ContentKind.MOVIE, "", movie.durationSecs * 1_000L)
            received++
        }
        for (show in watched.shows.take(MAX_ITEMS_PER_SYNC)) {
            val series = repo.seriesMatching(localTitles(show.title, TmdbKind.SERIES), 1).firstOrNull() ?: continue
            for (episode in db.series().episodesList(series.uid)) {
                if ((episode.season to episode.episode) !in show.episodes) continue
                if (db.progress().get(profileId, episode.uid) != null) continue
                repo.markWatched(episode.uid, ContentKind.SERIES, series.uid, episode.durationSecs * 1_000L)
                received++
            }
        }
        return received
    }

    private fun isFinished(p: WatchProgressEntity) = p.durationMs > 0 && p.positionMs >= p.durationMs * WATCHED_RATIO

    private suspend fun pushWatched(profileId: String, since: Long, watched: TraktWatched): Int {
        val fresh = db.progress().forProfile(profileId).filter { it.updatedAt > since && it.uid.isNotBlank() && isFinished(it) }
        val movieIds = watched.movies.mapNotNull { it.tmdbId }.toSet()
        val movieNames = watched.movies.map { TextNorm.search(it.title) }.toSet()
        val toSend = fresh.take(MAX_ITEMS_PER_SYNC).mapNotNull { progress ->
            when (progress.kind) {
                ContentKind.MOVIE -> db.movies().get(progress.uid)?.let { movieMedia(it) }
                    ?.takeUnless { media -> media.tmdbId in movieIds || TextNorm.search(media.title) in movieNames }
                ContentKind.SERIES -> {
                    val episode = db.series().episode(progress.uid) ?: return@mapNotNull null
                    val series = db.series().get(episode.seriesUid) ?: return@mapNotNull null
                    episodeMedia(series, episode).takeUnless { media -> isOnTrakt(media, watched.shows) }
                }
                else -> null
            }
        }
        if (toSend.isNotEmpty()) client.addToHistory(toSend)
        return toSend.size
    }

    private fun isOnTrakt(media: TraktMedia, shows: List<TraktShow>): Boolean {
        val episode = (media.season ?: return false) to (media.episode ?: return false)
        val name = TextNorm.search(media.title)
        return shows.any { show ->
            val sameShow = (media.tmdbId != null && show.title.tmdbId == media.tmdbId) || TextNorm.search(show.title.title) == name
            sameShow && episode in show.episodes
        }
    }

    /** Titres d'une liste Trakt, vide si ce profil n'est pas connecté. */
    suspend fun titles(list: TraktList): List<TmdbTitle> {
        if (!isActiveFor(settings.get())) return emptyList()
        cachedLists[list]?.takeIf { now() - it.at < LIST_CACHE_MS }?.let { return it.titles }
        val raw = quietly { client.titles(list.path) } ?: return cachedLists[list]?.titles.orEmpty()
        val titles = raw.flatMap { localTitles(it, list.kind) }
        cachedLists[list] = CachedTitles(titles, now())
        return titles
    }

    private companion object {
        const val PAUSE_GRACE_MS = 5_000L
        const val SYNC_INTERVAL_MS = 6 * 3_600_000L
        const val LIST_CACHE_MS = 30 * 60_000L
        const val MAX_ITEMS_PER_SYNC = 300
        const val WATCHED_RATIO = 0.95
        const val RECONNECT_MESSAGE = "Reconnecte-toi à Trakt"
        const val UNREACHABLE_MESSAGE = "Trakt est injoignable pour l'instant"
    }
}
