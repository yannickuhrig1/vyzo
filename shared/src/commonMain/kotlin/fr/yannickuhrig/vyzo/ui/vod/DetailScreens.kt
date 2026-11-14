package fr.yannickuhrig.vyzo.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import fr.yannickuhrig.vyzo.ui.common.TextInputDialog
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.vod.SeriesTracking
import fr.yannickuhrig.vyzo.data.vod.resolutionShort
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.DownloadEntity
import fr.yannickuhrig.vyzo.data.db.EpisodeEntity
import fr.yannickuhrig.vyzo.data.db.MovieEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import fr.yannickuhrig.vyzo.data.db.SeriesScheduleEntity
import fr.yannickuhrig.vyzo.data.db.WatchProgressEntity
import fr.yannickuhrig.vyzo.data.remote.CastCommand
import fr.yannickuhrig.vyzo.data.ratings.OmdbRatings
import fr.yannickuhrig.vyzo.data.tmdb.CastMember
import fr.yannickuhrig.vyzo.data.tmdb.MetadataCache
import fr.yannickuhrig.vyzo.data.tmdb.TmdbEpisode
import fr.yannickuhrig.vyzo.data.tmdb.TmdbKind
import fr.yannickuhrig.vyzo.data.tmdb.certificationLabel
import fr.yannickuhrig.vyzo.data.tmdb.TmdbEnrichment
import fr.yannickuhrig.vyzo.data.tmdb.cleanTitleForTmdb
import fr.yannickuhrig.vyzo.data.tmdb.trailerUrl
import fr.yannickuhrig.vyzo.ui.common.openInExternalPlayer
import fr.yannickuhrig.vyzo.ui.common.CastDialog
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.Loading
import fr.yannickuhrig.vyzo.ui.common.NetImage
import fr.yannickuhrig.vyzo.ui.common.ProgressLine
import fr.yannickuhrig.vyzo.ui.common.START_FROM_BEGINNING
import fr.yannickuhrig.vyzo.ui.common.Tile
import fr.yannickuhrig.vyzo.ui.common.appViewModel
import fr.yannickuhrig.vyzo.ui.common.isTv
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ Film

class MovieDetailViewModel(private val c: AppContainer, val uid: String) : ViewModel() {
    val movie: StateFlow<MovieEntity?> = c.db.movies().observe(uid).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val progress: StateFlow<WatchProgressEntity?> = c.repo.progress(uid).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val favorite: StateFlow<Boolean> = c.repo.isFavorite(uid).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val enrichment = MutableStateFlow<TmdbEnrichment?>(null)
    val ratings = MutableStateFlow<OmdbRatings?>(null)
    /** Recommandations de TMDB présentes dans le catalogue. */
    val similar = MutableStateFlow<List<MovieEntity>>(emptyList())
    val download: StateFlow<DownloadEntity?> = c.downloads.observeDownloadByItemUid(uid)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            val m = movie.filterNotNull().first()
            c.repo.refreshMovieDetails(m)
            val e = c.tmdb.enrichMovie(m.name, m.yearInt.takeIf { it > 0 })
            enrichment.value = e
            e.imdbId?.let { ratings.value = c.omdb.ratings(it) }
            e.tmdbId?.let { id -> similar.value = c.repo.moviesMatching(c.tmdb.recommendations(TmdbKind.MOVIE, id)).filter { it.uid != uid } }
        }
    }

    fun toggleFavorite() = viewModelScope.launch { c.repo.toggleFavorite(uid, ContentKind.MOVIE) }

    fun toggleDownload() {
        viewModelScope.launch {
            val dl = download.value
            val m = movie.value ?: return@launch
            if (dl == null || dl.isFailed) {
                val spec = runCatching { c.repo.resolveMovie(m) }.getOrNull()
                if (spec != null) {
                    c.downloads.startOrQueueDownload(
                        itemType = "movie",
                        itemUid = m.uid,
                        seriesUid = null,
                        title = m.name,
                        subtitle = listOf(m.year, m.genre).filter { it.isNotBlank() }.joinToString(" · "),
                        posterUrl = m.poster,
                        remoteUrl = spec.url,
                    )
                } else {
                    c.platform.toast("Impossible de récupérer le flux vidéo")
                }
            } else if (dl.isDownloading) {
                c.downloads.pauseDownload(dl.id)
            } else if (dl.isPaused) {
                c.downloads.resumeDownload(dl.id)
            } else if (dl.isCompleted) {
                c.downloads.deleteDownload(dl.id)
            }
        }
    }
}

/** Fiches propres à une variante de l'appli (Vyzo TV). */
class VariantDetails(
    val movie: @Composable (uid: String, onPlay: (String, Long) -> Unit, onOpenMovie: (String) -> Unit, onOpenSeries: (String) -> Unit) -> Unit,
    val series: @Composable (uid: String, onPlayEpisode: (EpisodeEntity, Long) -> Unit, onOpenMovie: (String) -> Unit, onOpenSeries: (String) -> Unit) -> Unit,
)

/** Null pour les fiches communes. */
val LocalVariantDetails = staticCompositionLocalOf<VariantDetails?> { null }

@Composable
fun MovieDetailScreen(
    uid: String,
    onPlay: (uid: String, startMs: Long) -> Unit,
    onOpenMovie: (String) -> Unit = {},
    onOpenSeries: (String) -> Unit = {},
) {
    val c = LocalContainer.current
    val vm = appViewModel(key = "movie-$uid") { MovieDetailViewModel(it, uid) }
    val movie by vm.movie.collectAsState()
    val progress by vm.progress.collectAsState()
    val fav by vm.favorite.collectAsState()
    val enrichment by vm.enrichment.collectAsState()
    val ratings by vm.ratings.collectAsState()
    val similar by vm.similar.collectAsState()
    val download by vm.download.collectAsState()
    val m = movie ?: return Loading()
    val resumable = progress?.let { it.positionMs > 30_000 && it.positionMs < it.durationMs * 0.95 } == true

    var castDialogVisible by remember { mutableStateOf(false) }
    var trailerDialogVisible by remember { mutableStateOf(false) }
    var selectedPerson by remember { mutableStateOf<String?>(null) }

    val effectiveRating = if (m.rating > 0f) m.rating else (enrichment?.voteAverage ?: 0f)
    val effectiveGenre = m.genre.ifBlank { enrichment?.genres?.joinToString(" · ").orEmpty() }
    val effectivePlot = m.plot.ifBlank { enrichment?.overview.orEmpty() }
    val backdrop = enrichment?.backdropUrl ?: m.backdrop.ifBlank { m.poster }

    val finalCast = remember(m.actors, enrichment?.cast) {
        val tmdbCast = enrichment?.cast.orEmpty()
        if (tmdbCast.isNotEmpty()) tmdbCast
        else m.actors.split(",", ";").map { it.trim() }.filter { it.isNotBlank() }.map {
            CastMember(0, it, "", null)
        }
    }

    DetailScaffold(backdrop = backdrop) {
        DetailHeader(
            poster = m.poster, title = m.name,
            meta = listOf(m.year, Fmt.minutes(m.durationSecs), effectiveGenre).filter { it.isNotBlank() },
            rating = effectiveRating,
            badges = detailBadges(enrichment?.certification, ratings),
        ) {
            ActionRow {
                if (resumable) {
                    DetailButton("Reprendre à ${Fmt.duration(progress!!.positionMs)}", Icons.Default.PlayArrow, primary = true, requestFocus = true) {
                        onPlay(uid, progress!!.positionMs)
                    }
                    DetailButton("Depuis le début", Icons.Default.Replay) { onPlay(uid, START_FROM_BEGINNING) }
                } else {
                    DetailButton("Lire", Icons.Default.PlayArrow, primary = true, requestFocus = true) { onPlay(uid, 0) }
                }
                DetailButton("Bande-annonce", Icons.Default.Movie) { trailerDialogVisible = true }
                val (dlLabel, dlIcon) = when {
                    download == null -> "Télécharger" to Icons.Default.ArrowDownward
                    download!!.isDownloading -> "${(download!!.progress * 100).toInt()}%" to Icons.Default.Pause
                    download!!.isQueued -> "En attente…" to Icons.Default.ArrowDownward
                    download!!.isPaused -> "En pause (${(download!!.progress * 100).toInt()}%)" to Icons.Default.PlayArrow
                    download!!.isCompleted -> "Téléchargé ✓" to Icons.Default.Check
                    else -> "Échec (Réessayer)" to Icons.Default.Refresh
                }
                DetailButton(dlLabel, dlIcon) { vm.toggleDownload() }
                DetailButton("Caster", Icons.Default.Cast) { castDialogVisible = true }
                DetailButton("Autre lecteur", Icons.AutoMirrored.Filled.OpenInNew) {
                    openInExternalPlayer(c, m.name, startMs = if (resumable) progress!!.positionMs else 0) { c.repo.resolveMovie(m) }
                }
                DetailButton(if (fav) "Favori" else "Ajouter aux favoris", if (fav) Icons.Default.Star else Icons.Default.StarBorder) { vm.toggleFavorite() }
            }
            if (resumable) ProgressLine(progress!!.positionMs.toFloat() / progress!!.durationMs, Modifier.width(320.dp).padding(top = 10.dp))
            if (!enrichment?.tagline.isNullOrBlank()) {
                Text(
                    "« ${enrichment!!.tagline} »",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = VyzoColors.Cyan,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            if (effectivePlot.isNotBlank()) Text(effectivePlot, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 14.dp))

            val dir = enrichment?.directors?.joinToString()?.takeIf { it.isNotBlank() } ?: m.director
            if (dir.isNotBlank()) Credit("Réalisation", dir)
            CastSection(cast = finalCast, onPersonClick = { selectedPerson = it })
            SimilarSection(similar.map { Triple(it.uid, it.name, it.poster) }, onOpenMovie)
        }
    }

    if (trailerDialogVisible) {
        TrailerDialog(
            title = m.name,
            youtubeId = enrichment?.youtubeTrailerId,
            onDismiss = { trailerDialogVisible = false },
        )
    }

    selectedPerson?.let { person ->
        PersonFilmographyDialog(
            person = person,
            onOpenMovie = { targetUid ->
                selectedPerson = null
                onOpenMovie(targetUid)
            },
            onOpenSeries = { targetUid ->
                selectedPerson = null
                onOpenSeries(targetUid)
            },
            onDismiss = { selectedPerson = null },
        )
    }

    if (castDialogVisible) {
        CastDialog(
            onSend = {
                castDialogVisible = false
                c.cloud.sendCastCommand(
                    CastCommand(
                        itemType = "movie",
                        itemId = uid,
                        title = m.name,
                        posterUrl = m.poster,
                        positionMs = progress?.positionMs ?: 0L,
                        senderDeviceId = c.platform.deviceModel,
                    ),
                )
            },
            onDismiss = { castDialogVisible = false },
        )
    }

    if (trailerDialogVisible) {
        TrailerDialog(
            title = m.name,
            youtubeId = enrichment?.youtubeTrailerId,
            onDismiss = { trailerDialogVisible = false },
        )
    }

    selectedPerson?.let { person ->
        PersonFilmographyDialog(
            person = person,
            onOpenMovie = { targetUid ->
                selectedPerson = null
                onOpenMovie(targetUid)
            },
            onOpenSeries = { targetUid ->
                selectedPerson = null
                onOpenSeries(targetUid)
            },
            onDismiss = { selectedPerson = null },
        )
    }
}

// ------------------------------------------------------------------ Série

class SeriesDetailViewModel(private val c: AppContainer, val uid: String) : ViewModel() {
    val series: StateFlow<SeriesEntity?> = c.db.series().observe(uid).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val episodes: StateFlow<List<EpisodeEntity>?> = c.db.series().episodes(uid).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val progress: StateFlow<Map<String, WatchProgressEntity>> = c.repo.seriesProgress(uid)
        .combine(MutableStateFlow(Unit)) { l, _ -> l.associateBy { it.uid } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val favorite: StateFlow<Boolean> = c.repo.isFavorite(uid).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val loading = MutableStateFlow(true)
    val error = MutableStateFlow<String?>(null)
    val season = MutableStateFlow<Int?>(null)
    val enrichment = MutableStateFlow<TmdbEnrichment?>(null)
    val ratings = MutableStateFlow<OmdbRatings?>(null)
    /** Recommandations de TMDB présentes dans le catalogue. */
    val similar = MutableStateFlow<List<SeriesEntity>>(emptyList())
    /** Résumés et images de TMDB par (saison, épisode), pour compléter ce que le fournisseur ne donne pas. */
    val episodeMeta = MutableStateFlow<Map<Pair<Int, Int>, TmdbEpisode>>(emptyMap())
    /** Épisodes annoncés sur TMDB non encore présents dans la playlist. */
    val upcomingBySeason = MutableStateFlow<Map<Int, List<TmdbEpisode>>>(emptyMap())
    val schedule: StateFlow<SeriesScheduleEntity?> = c.db.seriesTracking().observeSchedule(uid)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val freshSlots: StateFlow<Set<Pair<Int, Int>>> = c.db.seriesTracking().observeAlert(uid)
        .map { SeriesTracking.parseFreshSlots(it?.freshSlots.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private val seasonsAsked = HashSet<Int>()
    /** Définition de chaque épisode déjà relevée (« 4K », « 1080p »…). */
    val qualities: StateFlow<Map<String, String>> = c.db.streamQuality().observeAll()
        .map { list -> list.associate { it.uid to resolutionShort(it.width, it.height) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val downloads: StateFlow<Map<String, DownloadEntity>> = c.downloads.observeSeriesDownloads(uid)
        .map { list -> list.associateBy { it.itemUid } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        viewModelScope.launch {
            val s = series.filterNotNull().first()
            runCatching { c.repo.refreshEpisodes(s) }.onFailure { error.value = "Impossible de charger les épisodes : ${it.message}" }
            loading.value = false
            val e = c.tmdb.enrichSeries(s.name, s.yearInt.takeIf { it > 0 })
            enrichment.value = e
            launch {
                runCatching {
                    val tmdbId = e.tmdbId ?: c.repo.seriesTmdbId(s)
                    SeriesTracking.refreshScheduleIfDue(c.db, c.tmdb, c.tvMaze, s, tmdbId = tmdbId)
                }
            }
            e.imdbId?.let { ratings.value = c.omdb.ratings(it) }
            e.tmdbId?.let { id -> similar.value = c.repo.seriesMatching(c.tmdb.recommendations(TmdbKind.SERIES, id)).filter { it.uid != uid } }
        }
    }

    fun toggleFavorite() = viewModelScope.launch { c.repo.toggleFavorite(uid, ContentKind.SERIES) }

    /** Marque un épisode comme vu, ou le remet à « non vu » s'il l'était déjà (repris sur les autres appareils). */
    fun toggleWatched(ep: EpisodeEntity) = viewModelScope.launch {
        if (progress.value[ep.uid].finished()) c.repo.clearProgress(ep.uid)
        else c.repo.markWatched(ep.uid, ContentKind.SERIES, uid, ep.durationSecs * 1_000L)
    }

    /** Demande à TMDB, une seule fois par saison, les résumés et images de ses épisodes. */
    fun loadSeasonMeta(n: Int) {
        if (!seasonsAsked.add(n)) return
        viewModelScope.launch {
            val id = enrichment.filterNotNull().first().tmdbId ?: return@launch
            val latestSeason = episodes.value?.maxOfOrNull { it.season }
            val maxAge = if (n == latestSeason || SeriesTracking.isActiveStatus(schedule.value?.status.orEmpty())) {
                MetadataCache.DAY_MS
            } else {
                MetadataCache.MAX_AGE_MS
            }
            val found = c.tmdb.seasonEpisodes(id, n, maxAge)
            episodeMeta.value = episodeMeta.value + found.associateBy { n to it.number }
            val future = found.filter { Fmt.isFutureOrTodayIso(it.airDate) }
            if (future.isNotEmpty()) {
                upcomingBySeason.value = upcomingBySeason.value + (n to future)
            }
        }
    }

    fun upcomingForSeason(season: Int?, shown: List<EpisodeEntity>, map: Map<Int, List<TmdbEpisode>>): List<TmdbEpisode> {
        val s = season ?: return emptyList()
        val existing = shown.mapTo(HashSet()) { it.episode }
        return map[s].orEmpty().filter { it.number !in existing && Fmt.isFutureOrTodayIso(it.airDate) }
    }

    fun downloadEpisode(ep: EpisodeEntity) {
        viewModelScope.launch {
            val s = series.value ?: return@launch
            val dl = downloads.value[ep.uid]
            if (dl == null || dl.isFailed) {
                val spec = runCatching { c.repo.resolveEpisode(ep) }.getOrNull()
                if (spec != null) {
                    c.downloads.startOrQueueDownload(
                        itemType = "episode",
                        itemUid = ep.uid,
                        seriesUid = s.uid,
                        title = s.name,
                        subtitle = "S${ep.season} É${ep.episode} · ${ep.title}",
                        posterUrl = ep.poster.ifBlank { s.poster },
                        remoteUrl = spec.url,
                    )
                } else {
                    c.platform.toast("Impossible de récupérer le flux vidéo")
                }
            } else if (dl.isDownloading) {
                c.downloads.pauseDownload(dl.id)
            } else if (dl.isPaused) {
                c.downloads.resumeDownload(dl.id)
            } else if (dl.isCompleted) {
                c.downloads.deleteDownload(dl.id)
            }
        }
    }

    fun downloadSeason(seasonNum: Int) {
        viewModelScope.launch {
            val s = series.value ?: return@launch
            val epsToDownload = episodes.value?.filter { it.season == seasonNum }.orEmpty()
            var count = 0
            for (ep in epsToDownload) {
                val dl = downloads.value[ep.uid]
                if (dl == null || dl.isFailed) {
                    val spec = runCatching { c.repo.resolveEpisode(ep) }.getOrNull() ?: continue
                    c.downloads.startOrQueueDownload(
                        itemType = "episode",
                        itemUid = ep.uid,
                        seriesUid = s.uid,
                        title = s.name,
                        subtitle = "S${ep.season} É${ep.episode} · ${ep.title}",
                        posterUrl = ep.poster.ifBlank { s.poster },
                        remoteUrl = spec.url,
                    )
                    count++
                }
            }
            if (count > 0) {
                c.platform.toast("$count épisode(s) en cours de téléchargement")
            }
        }
    }

    /** Épisode à proposer en "Reprendre" et position de départ. */
    fun resumeTarget(eps: List<EpisodeEntity>, prog: Map<String, WatchProgressEntity>): Pair<EpisodeEntity, Long>? {
        if (eps.isEmpty()) return null
        val last = prog.values.maxByOrNull { it.updatedAt } ?: return eps.first() to 0L
        val idx = eps.indexOfFirst { it.uid == last.uid }
        if (idx < 0) return eps.first() to 0L
        if (last.positionMs < last.durationMs * 0.95) return eps[idx] to last.positionMs
        // L'épisode suivant a pu être commencé avant (un épisode marqué vu après coup) : on le reprend où il en était.
        val next = eps.getOrNull(idx + 1) ?: return eps[idx] to 0L
        return next to (prog[next.uid]?.takeUnless { it.finished() }?.positionMs ?: 0L)
    }
}

@Composable
fun SeriesDetailScreen(
    uid: String,
    onPlayEpisode: (EpisodeEntity, Long) -> Unit,
    onOpenMovie: (String) -> Unit = {},
    onOpenSeries: (String) -> Unit = {},
) {
    val c = LocalContainer.current
    val vm = appViewModel(key = "series-$uid") { SeriesDetailViewModel(it, uid) }
    val series by vm.series.collectAsState()
    val episodes by vm.episodes.collectAsState()
    val progress by vm.progress.collectAsState()
    val fav by vm.favorite.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val seasonSel by vm.season.collectAsState()
    val enrichment by vm.enrichment.collectAsState()
    val ratings by vm.ratings.collectAsState()
    val similar by vm.similar.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val schedule by vm.schedule.collectAsState()
    val freshSlots by vm.freshSlots.collectAsState()
    val upcomingBySeason by vm.upcomingBySeason.collectAsState()
    val s = series ?: return Loading()
    val eps = episodes.orEmpty()
    val seasons = eps.map { it.season }.distinct().sorted()
    val season = seasonSel ?: seasons.firstOrNull()
    val shown = eps.filter { it.season == season }
    val episodeMeta by vm.episodeMeta.collectAsState()
    val upcoming = vm.upcomingForSeason(season, shown, upcomingBySeason)
    LaunchedEffect(season) { season?.let(vm::loadSeasonMeta) }
    val resume = vm.resumeTarget(eps, progress)
    val tv = isTv()

    var castDialogVisible by remember { mutableStateOf(false) }
    var trailerDialogVisible by remember { mutableStateOf(false) }
    var selectedPerson by remember { mutableStateOf<String?>(null) }

    // « Aller à l'épisode » : pour les séries interminables (One Piece…), sans faire défiler des centaines d'épisodes.
    var jumpDialog by remember { mutableStateOf(false) }
    var jumpTo by remember { mutableStateOf<String?>(null) }
    val episodeListState = rememberLazyListState()
    LaunchedEffect(jumpTo, shown) {
        val target = jumpTo ?: return@LaunchedEffect
        val i = shown.indexOfFirst { it.uid == target }
        if (i < 0) return@LaunchedEffect
        // Sur téléphone, la fiche, les saisons et l'état occupent les 3 premières lignes de la liste.
        episodeListState.scrollToItem(if (tv) i else i + 3)
        jumpTo = null
    }

    val effectiveRating = if (s.rating > 0f) s.rating else (enrichment?.voteAverage ?: 0f)
    val effectiveGenre = s.genre.ifBlank { enrichment?.genres?.joinToString(" · ").orEmpty() }
    val effectivePlot = s.plot.ifBlank { enrichment?.overview.orEmpty() }
    val backdrop = enrichment?.backdropUrl ?: s.backdrop.ifBlank { s.poster }

    val finalCast = remember(s.actors, enrichment?.cast) {
        val tmdbCast = enrichment?.cast.orEmpty()
        if (tmdbCast.isNotEmpty()) tmdbCast
        else s.actors.split(",", ";").map { it.trim() }.filter { it.isNotBlank() }.map {
            CastMember(0, it, "", null)
        }
    }

    val info: @Composable () -> Unit = {
        DetailHeader(
            poster = s.poster, title = s.name,
            meta = listOf(s.year, effectiveGenre).filter { it.isNotBlank() },
            rating = effectiveRating, compact = true,
            badges = detailBadges(enrichment?.certification, ratings),
        ) {
            ActionRow {
                if (resume != null) {
                    val (ep, pos) = resume
                    DetailButton(
                        (if (pos > 0) "Reprendre " else "Lire ") + "S${ep.season} É${ep.episode}",
                        Icons.Default.PlayArrow, primary = true, requestFocus = true,
                    ) { onPlayEpisode(ep, pos) }
                }
                DetailButton("Bande-annonce", Icons.Default.Movie) { trailerDialogVisible = true }
                if (shown.isNotEmpty()) {
                    DetailButton("Télécharger saison ${season ?: 1}", Icons.Default.ArrowDownward) {
                        vm.downloadSeason(season ?: 1)
                    }
                }
                if (eps.size >= JUMP_MIN_EPISODES) {
                    DetailButton("Aller à l'épisode…", Icons.Default.Search) { jumpDialog = true }
                }
                if (resume != null) {
                    DetailButton("Caster", Icons.Default.Cast) { castDialogVisible = true }
                    val (ep, pos) = resume
                    DetailButton("Autre lecteur", Icons.AutoMirrored.Filled.OpenInNew) {
                        openInExternalPlayer(c, "${s.name} · S${ep.season} É${ep.episode}", startMs = pos) { c.repo.resolveEpisode(ep) }
                    }
                }
                DetailButton(if (fav) "Favori" else "Ajouter aux favoris", if (fav) Icons.Default.Star else Icons.Default.StarBorder) { vm.toggleFavorite() }
            }
            SeriesTrackingBanners(freshSlots.size, schedule, Modifier.padding(top = 12.dp))
            if (!enrichment?.tagline.isNullOrBlank()) {
                Text(
                    "« ${enrichment!!.tagline} »",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = VyzoColors.Cyan,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (effectivePlot.isNotBlank()) Text(effectivePlot, style = MaterialTheme.typography.bodyLarge, maxLines = if (tv) 5 else 12, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
            CastSection(cast = finalCast, onPersonClick = { selectedPerson = it })
            SimilarSection(similar.map { Triple(it.uid, it.name, it.poster) }, onOpenSeries)
        }
    }

    val seasonChips: @Composable () -> Unit = {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(seasons) { n ->
                Tile(
                    onClick = { vm.season.value = n }, onFocus = { if (tv) vm.season.value = n },
                    selected = n == season, shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        if (n == 0) "Bonus" else "Saison $n", style = MaterialTheme.typography.labelLarge,
                        color = if (n == season) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }

    val episodeState: @Composable () -> Unit = {
        when {
            loading && eps.isEmpty() -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null && eps.isEmpty() -> Text(error!!, color = VyzoColors.Red, modifier = Modifier.padding(8.dp))
            eps.isEmpty() -> Text("Aucun épisode disponible.", color = VyzoColors.TextDim, modifier = Modifier.padding(8.dp))
        }
    }

    fun LazyListScope.seasonEpisodeItems() {
        items(shown, key = { it.uid }) { ep ->
            EpisodeRow(
                ep = ep,
                progress = progress[ep.uid],
                download = downloads[ep.uid],
                tmdb = episodeMeta[ep.season to ep.episode],
                isFresh = (ep.season to ep.episode) in freshSlots,
                onDownload = { vm.downloadEpisode(ep) },
                onToggleWatched = { vm.toggleWatched(ep) },
                onClick = { onPlayEpisode(ep, progress[ep.uid]?.resumeMs() ?: 0) },
            )
        }
        items(upcoming, key = { "upcoming-${it.season}-${it.number}" }) { ep ->
            UpcomingEpisodeRow(ep)
        }
    }

    DetailScaffold(backdrop = s.backdrop.ifBlank { s.poster }, scrollable = false) {
        if (tv) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1.05f).fillMaxHeight().verticalScroll(rememberScrollState())) { info() }
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    seasonChips()
                    episodeState()
                    LazyColumn(state = episodeListState, contentPadding = PaddingValues(bottom = 32.dp)) {
                        seasonEpisodeItems()
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = episodeListState, contentPadding = PaddingValues(bottom = 32.dp)) {
                item { info() }
                item { seasonChips() }
                item { episodeState() }
                seasonEpisodeItems()
            }
        }
    }

    if (jumpDialog) {
        TextInputDialog(
            title = "Aller à l'épisode",
            label = "Numéro d'épisode (ou S2E5)",
            keyboardType = KeyboardType.Number,
            confirmLabel = "Aller",
            onConfirm = { text ->
                val ep = findEpisode(eps, season, text)
                if (ep == null) c.platform.toast("Épisode introuvable")
                else {
                    vm.season.value = ep.season
                    jumpTo = ep.uid
                }
            },
            onDismiss = { jumpDialog = false },
        )
    }

    if (castDialogVisible && resume != null) {
        val (targetEp, targetPos) = resume
        CastDialog(
            onSend = {
                castDialogVisible = false
                c.cloud.sendCastCommand(
                    CastCommand(
                        itemType = "episode",
                        itemId = targetEp.uid,
                        title = "${s.name} - S${targetEp.season} E${targetEp.episode} - ${targetEp.title}",
                        posterUrl = targetEp.poster.ifBlank { s.poster },
                        positionMs = targetPos,
                        senderDeviceId = c.platform.deviceModel,
                    ),
                )
            },
            onDismiss = { castDialogVisible = false },
        )
    }

    if (trailerDialogVisible) {
        TrailerDialog(
            title = s.name,
            youtubeId = enrichment?.youtubeTrailerId,
            onDismiss = { trailerDialogVisible = false },
        )
    }

    selectedPerson?.let { person ->
        PersonFilmographyDialog(
            person = person,
            onOpenMovie = { targetUid ->
                selectedPerson = null
                onOpenMovie(targetUid)
            },
            onOpenSeries = { targetUid ->
                selectedPerson = null
                onOpenSeries(targetUid)
            },
            onDismiss = { selectedPerson = null },
        )
    }
}

/** À partir de ce nombre d'épisodes, la fiche propose « Aller à l'épisode ». */
private const val JUMP_MIN_EPISODES = 40

private val seasonEpisode = Regex("""^\s*s?(\d{1,3})\s*[ex×]\s*(\d{1,4})\s*$""", RegexOption.IGNORE_CASE)

/**
 * Épisode demandé : « S2E5 » ou « 2x5 » ; sinon un numéro, cherché d'abord dans la saison affichée, puis dans toutes,
 * puis compté depuis le premier épisode (séries numérotées en continu comme One Piece).
 */
fun findEpisode(eps: List<EpisodeEntity>, currentSeason: Int?, text: String): EpisodeEntity? {
    seasonEpisode.matchEntire(text)?.let { m ->
        val (sn, en) = m.destructured
        return eps.firstOrNull { it.season == sn.toInt() && it.episode == en.toInt() }
    }
    val n = text.trim().toIntOrNull() ?: return null
    return eps.firstOrNull { it.season == currentSeason && it.episode == n }
        ?: eps.firstOrNull { it.episode == n }
        ?: eps.sortedWith(compareBy({ it.season }, { it.episode })).getOrNull(n - 1)
}

private fun WatchProgressEntity.resumeMs(): Long = if (positionMs < durationMs * 0.95) positionMs else 0

/** Vu jusqu'au bout (95 % au moins). */
fun WatchProgressEntity?.finished(): Boolean = this != null && durationMs > 0 && positionMs >= durationMs * 0.95

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesTrackingBanners(freshCount: Int, schedule: SeriesScheduleEntity?, modifier: Modifier = Modifier) {
    val schedDisplay = schedule?.let { SeriesTracking.formatScheduleDisplay(it) }
    val freshLabel = SeriesTracking.alertBannerLabel(freshCount)
    if (freshLabel.isBlank() && schedDisplay == null) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (freshLabel.isNotBlank()) {
            Text(
                freshLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(VyzoColors.Amber).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (schedDisplay != null) {
            val bg = if (schedDisplay.hasUpcomingDate) Color(0xCC1E3A8A) else Color.White.copy(alpha = 0.09f)
            val fg = when {
                schedDisplay.hasUpcomingDate -> Color(0xFFBFDBFE)
                schedDisplay.isEnded -> VyzoColors.TextDim
                else -> VyzoColors.Cyan
            }
            Text(
                schedDisplay.banner,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = fg,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    ep: EpisodeEntity,
    progress: WatchProgressEntity?,
    download: DownloadEntity? = null,
    tmdb: TmdbEpisode? = null,
    isFresh: Boolean = false,
    onDownload: (() -> Unit)? = null,
    onToggleWatched: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val poster = ep.poster.ifBlank { tmdb?.stillUrl.orEmpty() }
    val plot = ep.plot.ifBlank { tmdb?.overview.orEmpty() }
    Tile(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), focusScale = 1.015f, color = Color.White.copy(alpha = 0.03f)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (poster.isNotBlank()) {
                NetImage(poster, Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(12.dp))
            } else {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(VyzoColors.SurfaceHigh), contentAlignment = Alignment.Center) {
                    Text("${ep.episode}", style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                val generic = Regex("^(é|e|É|E)pisode\\s*\\d+$", RegexOption.IGNORE_CASE).matches(ep.title.trim())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (generic || ep.title.isBlank()) "Épisode ${ep.episode}" else "É${ep.episode} · ${ep.title}",
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isFresh) {
                        Text(
                            "NOUVEAU",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(VyzoColors.Amber).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                val airDateLabel = tmdb?.airDate?.takeIf { it.isNotBlank() }?.let { Fmt.isoDate(it) }
                val meta = listOfNotNull(airDateLabel, Fmt.minutes(ep.durationSecs).ifBlank { null }).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim)
                if (plot.isNotBlank()) Text(plot, style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (progress != null && progress.durationMs > 0) {
                    ProgressLine(progress.positionMs.toFloat() / progress.durationMs, Modifier.fillMaxWidth(0.6f).padding(top = 5.dp))
                }
            }
            if (onToggleWatched != null) {
                val watched = progress.finished()
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onToggleWatched, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (watched) Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline,
                        contentDescription = if (watched) "Vu (cliquer pour retirer)" else "Marquer comme vu",
                        tint = if (watched) VyzoColors.Amber else VyzoColors.TextDim,
                    )
                }
            }
            if (onDownload != null) {
                Spacer(Modifier.width(8.dp))
                Box(contentAlignment = Alignment.Center) {
                    when {
                        download == null -> {
                            IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "Télécharger", tint = VyzoColors.TextDim)
                            }
                        }
                        download.isDownloading -> {
                            IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                                CircularProgressIndicator(
                                    progress = { download.progress },
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Icon(Icons.Default.Pause, contentDescription = "Mettre en pause", modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        download.isQueued -> {
                            IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = VyzoColors.TextDim)
                            }
                        }
                        download.isPaused -> {
                            IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Reprendre le téléchargement", tint = VyzoColors.Amber)
                            }
                        }
                        download.isCompleted -> {
                            IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Check, contentDescription = "Téléchargé (cliquer pour supprimer)", tint = Color(0xFF4CAF50))
                            }
                        }
                        else -> {
                            IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = "Réessayer le téléchargement", tint = VyzoColors.Red)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingEpisodeRow(ep: TmdbEpisode) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.025f)).graphicsLayer { alpha = 0.62f }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!ep.stillUrl.isNullOrBlank()) {
            NetImage(ep.stillUrl, Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(VyzoColors.SurfaceHigh), contentAlignment = Alignment.Center) {
                Text("${ep.number}", style = MaterialTheme.typography.titleSmall, color = VyzoColors.TextDim)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (ep.title.isBlank()) "Épisode ${ep.number}" else "É${ep.number} · ${ep.title}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    "À venir · ${Fmt.isoDate(ep.airDate)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFBFDBFE),
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xCC1E3A8A)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (ep.overview.isNotBlank()) {
                Text(ep.overview, style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ------------------------------------------------------------------ Briques communes

@Composable
fun DetailScaffold(backdrop: String, scrollable: Boolean = true, content: @Composable () -> Unit) {
    val tv = isTv()
    Box(Modifier.fillMaxSize()) {
        if (backdrop.isNotBlank()) {
            NetImage(backdrop, Modifier.fillMaxSize().graphicsLayer { alpha = 0.28f }, contentScale = ContentScale.Crop)
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.85f), MaterialTheme.colorScheme.background)),
            ),
        )
        val pad = if (tv) Modifier.padding(horizontal = 40.dp, vertical = 28.dp) else Modifier.padding(16.dp)
        if (scrollable) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(pad)) { content() }
        else Box(Modifier.fillMaxSize().then(pad)) { content() }
    }
}

@Composable
fun DetailHeader(
    poster: String,
    title: String,
    meta: List<String>,
    rating: Float,
    compact: Boolean = false,
    badges: List<String> = emptyList(),
    body: @Composable () -> Unit,
) {
    val tv = isTv()
    Row {
        NetImage(
            poster, Modifier.width(if (tv && !compact) 200.dp else if (tv) 140.dp else 110.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop, fallback = title,
        )
        Spacer(Modifier.width(if (tv) 28.dp else 14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, style = if (tv && !compact) MaterialTheme.typography.headlineMedium else if (tv) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (rating > 0f) {
                    Icon(Icons.Default.Star, null, Modifier.size(16.dp), tint = VyzoColors.Amber)
                    Text(" %.1f   ".format(rating), style = MaterialTheme.typography.bodyMedium)
                }
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim, maxLines = 2)
            }
            if (badges.isNotEmpty()) {
                Text(badges.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
            }
            if (tv) body()
        }
    }
    if (!tv) Column(Modifier.padding(top = 14.dp)) { body() }
}

/** Limite d'âge et notes externes affichées sous le titre d'une fiche. */
fun detailBadges(certification: String?, ratings: OmdbRatings?): List<String> =
    listOfNotNull(certification?.let(::certificationLabel)) + ratings?.labels().orEmpty()

/** Titres recommandés par TMDB et présents dans le catalogue. */
@Composable
private fun SimilarSection(titles: List<Triple<String, String, String>>, onOpen: (String) -> Unit) {
    if (titles.isEmpty()) return
    Column(Modifier.padding(top = 16.dp)) {
        Text("Titres similaires", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(titles, key = { it.first }) { (uid, name, poster) ->
                Tile(onClick = { onOpen(uid) }, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.width(104.dp)) {
                        NetImage(poster, Modifier.fillMaxWidth().aspectRatio(2f / 3f), contentScale = ContentScale.Crop, fallback = name)
                        Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(6.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionRow(content: @Composable () -> Unit) {
    FlowRow(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        content()
    }
}

@Composable
fun DetailButton(label: String, icon: ImageVector, primary: Boolean = false, requestFocus: Boolean = false, onClick: () -> Unit) {
    val fr = remember { FocusRequester() }
    val tv = isTv()
    if (requestFocus && tv) {
        LaunchedEffect(Unit) { delay(120); runCatching { fr.requestFocus() } }
    }
    Tile(
        onClick = onClick, shape = RoundedCornerShape(24.dp), focusScale = 1.06f,
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        focusedColor = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.focusRequester(fr),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun Credit(label: String, value: String) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CastSection(
    cast: List<CastMember>,
    onPersonClick: (String) -> Unit,
) {
    if (cast.isEmpty()) return
    Column(Modifier.padding(top = 16.dp)) {
        Text("Distribution & Casting", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(cast, key = { if (it.id > 0) "${it.id}-${it.name}" else it.name }) { member ->
                Tile(
                    onClick = { onPersonClick(member.name) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.width(105.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (!member.profileUrl.isNullOrBlank()) {
                            NetImage(
                                member.profileUrl,
                                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(36.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(36.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    member.name.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            member.name,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        if (member.character.isNotBlank()) {
                            Text(
                                member.character,
                                style = MaterialTheme.typography.bodySmall,
                                color = VyzoColors.TextDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bande-annonce sur YouTube (application YouTube sur TV et téléphone, navigateur sur PC). On prévient avant de
 * quitter Vyzo, et on dit s'il s'agit de la vidéo du film ou d'une simple recherche.
 */
@Composable
fun TrailerDialog(
    title: String,
    youtubeId: String?,
    onDismiss: () -> Unit,
) {
    val c = LocalContainer.current
    val where = if (c.platform.isDesktop) "dans ton navigateur" else "dans l'application YouTube"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bande-annonce : $title") },
        text = {
            Text(
                if (youtubeId != null) "La bande-annonce s'ouvre sur YouTube, $where."
                else "Pas de bande-annonce connue pour ce titre : une recherche s'ouvre sur YouTube, $where.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                c.platform.openUrl(trailerUrl(youtubeId, cleanTitleForTmdb(title).first))
                c.platform.toast("Ouverture de YouTube…")
                onDismiss()
            }) { Text(if (youtubeId != null) "Ouvrir YouTube" else "Rechercher sur YouTube") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun PersonFilmographyDialog(
    person: String,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalContainer.current
    var loading by remember { mutableStateOf(true) }
    var movies by remember { mutableStateOf<List<MovieEntity>>(emptyList()) }
    var series by remember { mutableStateOf<List<SeriesEntity>>(emptyList()) }

    LaunchedEffect(person) {
        val (m, s) = c.repo.findContentByPerson(person)
        movies = m
        series = s
        loading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Filmographie locale : $person",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Fermer")
                }
            }

            Spacer(Modifier.height(12.dp))

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (movies.isEmpty() && series.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Aucun autre titre disponible dans votre catalogue local pour cet artiste.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VyzoColors.TextDim,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (movies.isNotEmpty()) {
                        item {
                            Text(
                                "Films (${movies.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(movies, key = { it.uid }) { m ->
                            Tile(
                                onClick = { onOpenMovie(m.uid) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    NetImage(
                                        m.poster,
                                        modifier = Modifier.size(width = 45.dp, height = 65.dp).clip(RoundedCornerShape(4.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(m.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            listOf(m.year, m.genre).filter { it.isNotBlank() }.joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = VyzoColors.TextDim,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (series.isNotEmpty()) {
                        item {
                            Text(
                                "Séries (${series.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(series, key = { it.uid }) { s ->
                            Tile(
                                onClick = { onOpenSeries(s.uid) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    NetImage(
                                        s.poster,
                                        modifier = Modifier.size(width = 45.dp, height = 65.dp).clip(RoundedCornerShape(4.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(s.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            listOf(s.year, s.genre).filter { it.isNotBlank() }.joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = VyzoColors.TextDim,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
