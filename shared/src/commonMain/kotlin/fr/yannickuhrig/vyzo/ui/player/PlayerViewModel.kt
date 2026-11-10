package fr.yannickuhrig.vyzo.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.catchup.CatchupBuilder
import fr.yannickuhrig.vyzo.data.db.StreamQualityEntity
import fr.yannickuhrig.vyzo.data.db.record
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.EpisodeEntity
import fr.yannickuhrig.vyzo.data.db.FAVORITES_GROUP_ID
import fr.yannickuhrig.vyzo.data.db.PlaylistKeys
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.db.RecordingStatus
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.db.WatchProgressEntity
import fr.yannickuhrig.vyzo.data.download.DownloadManager
import fr.yannickuhrig.vyzo.data.intro.EpisodeMarkers
import fr.yannickuhrig.vyzo.data.intro.SeriesRef
import fr.yannickuhrig.vyzo.data.intro.SkipKind
import fr.yannickuhrig.vyzo.data.intro.SkipSegment
import fr.yannickuhrig.vyzo.data.intro.markersFor
import fr.yannickuhrig.vyzo.data.prefs.AppSettings
import fr.yannickuhrig.vyzo.data.prefs.MultiViewPreset
import fr.yannickuhrig.vyzo.data.prefs.MultiViewPresets
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.data.repo.StreamSpec
import fr.yannickuhrig.vyzo.data.source.m3u.M3uClassifier
import fr.yannickuhrig.vyzo.data.sync.SyncManager
import fr.yannickuhrig.vyzo.player.PlaybackState
import fr.yannickuhrig.vyzo.player.StallDetector
import fr.yannickuhrig.vyzo.player.TimeshiftBuffer
import fr.yannickuhrig.vyzo.player.TrackOption
import fr.yannickuhrig.vyzo.player.TrackType
import fr.yannickuhrig.vyzo.data.prefs.SeriesTracks
import fr.yannickuhrig.vyzo.data.trakt.TraktMedia
import fr.yannickuhrig.vyzo.player.VideoPlayer
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.PlayKind
import fr.yannickuhrig.vyzo.ui.common.START_FROM_BEGINNING
import fr.yannickuhrig.vyzo.util.ChannelSeparator
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class PlayerUi(
    val title: String = "",
    val subtitle: String = "",
    /** Image de l'épisode en cours, vide si la source n'en donne pas (ou pour un film, une chaîne…). */
    val artwork: String = "",
    val channel: ChannelEntity? = null,
    val isLive: Boolean = true,
    val now: ProgramEntity? = null,
    val next: ProgramEntity? = null,
    val resolving: Boolean = true,
    val resolveError: String? = null,
    val catchupProgram: ProgramEntity? = null,
    val hasNextEpisode: Boolean = false,
    val nextEpisodeTitle: String? = null,
    /** Chaîne regardée juste avant (touche « chaîne précédente »). */
    val previous: ChannelEntity? = null,
    /** Pause du direct en cours (null = direct normal). */
    val timeshift: TimeshiftState? = null,
    /** Explication quand la lecture est refusée (limite de connexions, abonnement expiré…). */
    val limitMessage: String? = null,
    /** Possibilité de relancer l'émission en cours depuis le début via le replay. */
    val canRestartCurrentProgram: Boolean = false,
    val restartProgramTitle: String? = null,
    val restartProgramStart: Long = 0L,
    /** Mode catch-up / replay actif pour l'émission en cours. */
    val isCatchupMode: Boolean = false,
)

/** Vrai si le réglage demande de sauter ce passage et que ses repères sont assez sûrs pour le faire seul. */
internal fun AppSettings.skipsAutomatically(segment: SkipSegment): Boolean = segment.auto && when (segment.kind) {
    SkipKind.RECAP -> autoSkipRecap
    SkipKind.INTRO -> autoSkipIntro
}

/** Emplacement dans la grille Multi-View (Multiplex 2 à 4 flux). */
data class MultiViewSlot(
    val index: Int,
    val channel: ChannelEntity,
    val player: VideoPlayer,
    val isPrimary: Boolean = false,
)

enum class TimeshiftSource {
    /** Replay du fournisseur à partir de l'heure de la pause. */
    CATCHUP,
    /** Flux mis en mémoire sur l'appareil pendant la pause. */
    BUFFER,
    /** Ni replay ni mémoire tampon : simple pause, reprise au direct. */
    NONE,
}

data class TimeshiftState(
    val source: TimeshiftSource,
    /** Heure à laquelle le direct a été mis en pause. */
    val pausedAt: Long,
    val paused: Boolean,
    /** La lecture différée a commencé (sinon : image figée, en attente de reprise). */
    val started: Boolean = false,
    /** Replay : heure réelle du début du flux (à la minute près, comme le demandent les fournisseurs). */
    val origin: Long = 0,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val c: AppContainer,
    val kind: String,
    val uid: String,
    val group: String,
    private val programId: Long,
    private val startMs: Long,
) : ViewModel() {
    val ui = MutableStateFlow(PlayerUi(isLive = kind == PlayKind.CHANNEL))
    val channels = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val nowMap = MutableStateFlow<Map<String, ProgramEntity>>(emptyMap())
    val message = MutableStateFlow<String?>(null)
    val streamInfo get() = c.player.streamInfo
    val isSeriesEpisode get() = kind == PlayKind.EPISODE
    val currentSeason: Int? get() = episode?.season
    val currentEpisodeNumber: Int? get() = episode?.episode

    val isMultiViewActive = MutableStateFlow(false)
    val multiViewSlots = MutableStateFlow<List<MultiViewSlot>>(emptyList())
    val multiViewChannels: StateFlow<List<ChannelEntity>> = multiViewSlots.map { slots -> slots.map { it.channel } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeAudioSlot = MutableStateFlow(0)
    val isMiniPlayerMode: StateFlow<Boolean> get() = c.pip.isMiniPlayerMode

    val externalSubtitles = MutableStateFlow<List<fr.yannickuhrig.vyzo.data.subtitles.SubtitleCue>>(emptyList())
    val externalSubtitleName = MutableStateFlow<String?>(null)
    val subtitleDelayMs: StateFlow<Long> get() = c.player.subtitleDelayMs
    val audioDelayMs: StateFlow<Long> get() = c.player.audioDelayMs

    fun setAudioDelay(ms: Long) {
        c.player.setAudioDelay(ms)
        viewModelScope.launch { c.settings.update { it.copy(audioDelayMs = ms.toInt()) } }
    }
    val subtitleService get() = c.subtitles

    fun adjustSubtitleDelay(deltaMs: Long) {
        c.player.adjustSubtitleDelay(deltaMs)
    }

    fun resetSubtitleDelay() {
        c.player.resetSubtitleDelay()
    }

    fun loadExternalSubtitles(name: String, content: String) {
        val cues = fr.yannickuhrig.vyzo.data.subtitles.SrtParser.parse(content)
        externalSubtitles.value = cues
        externalSubtitleName.value = name
        message.value = "Sous-titres activés (${cues.size} répliques)"
        // Un seul rendu, le calque de l'écran (décalage compris) : la piste de sous-titres du lecteur est coupée.
        c.player.disable(TrackType.TEXT)
    }

    fun clearExternalSubtitles() {
        externalSubtitles.value = emptyList()
        externalSubtitleName.value = null
        message.value = "Sous-titres externes désactivés"
    }

    fun enterMiniPlayer() {
        c.pip.lastPlayerRoute = fr.yannickuhrig.vyzo.ui.common.Routes.player(kind, uid, group, programId, startMs)
        c.pip.setMiniPlayerInfo(ui.value.title, ui.value.subtitle)
        c.pip.setMiniPlayerMode(true)
    }

    fun zapNext() {
        val cur = ui.value.channel ?: return
        channelAfter(cur.uid, 1)?.let { playChannel(it) }
    }

    /** L'abonnement de ce qui est lu n'autorise qu'une connexion : en quittant le lecteur, on la rend au lieu de passer au mini-lecteur. */
    @Volatile
    var singleConnection = false
        private set

    private var episodes: List<EpisodeEntity> = emptyList()
    private var episode: EpisodeEntity? = null

    /** « Rester » (ou ✕) sur la carte Épisode suivant : la lecture s'arrête à la fin de cet épisode, sans enchaîner. */
    var stayAtEpisodeEnd = false

    /** Intro et générique de l'épisode en cours d'après TheIntroDB (vide tant qu'on ne sait pas). */
    val markers = MutableStateFlow(EpisodeMarkers())
    private var seriesRef: Pair<String, SeriesRef>? = null
    private var markersJob: Job? = null
    private var tracksJob: Job? = null
    private var playJob: Job? = null
    private val zapPacer = fr.yannickuhrig.vyzo.player.ZapPacer()

    /** La rafale en cours a coupé le flux : la chaîne affichée doit être rouverte, même si c'est celle qu'on demande. */
    private var connectionReleasedForZap = false
    private var progressSyncJob: Job? = null
    private var lastSavedItem: String? = null
    private var lastSavedMs = 0L
    private val buffer = TimeshiftBuffer(c.platform.cacheDir)

    // Qualité automatique (déclarée avant init, qui lance la surveillance).
    private val stalls = StallDetector()
    private var watchedTag: String? = null

    /** Chaînes sans version plus légère dans la playlist : inutile de chercher à nouveau. */
    private val noLowerQuality = HashSet<String>()

    /** Version choisie automatiquement → chaîne de départ (pour zapper depuis sa place dans la liste). */
    private var qualityAnchor: Pair<String, String>? = null

    /** Étiquette de la dernière lecture lancée par cet écran. */
    var currentTag: String? = null
        private set

    private fun play(spec: StreamSpec, pos: Long, tag: String) {
        currentTag = tag
        c.player.play(spec, pos, tag = tag)
    }

    val invertZap get() = c.settings.state.value.invertZap
    val groupTitle: StateFlow<String> = c.repo.liveGroups().map { l -> l.firstOrNull { it.key == group.ifBlank { Repository.KEY_ALL } }?.title ?: "Chaînes" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Chaînes")

    val isFavorite: StateFlow<Boolean> = ui.map { it.channel?.uid }.flatMapLatest { u ->
        if (u == null) flowOf(false) else c.repo.groupsOf(u).map { FAVORITES_GROUP_ID in it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Note la définition réelle du film ou de l'épisode en cours : le badge 4K des cartes s'y fie ensuite. */
    private fun recordStreamSize() = viewModelScope.launch {
        if (kind != PlayKind.MOVIE && kind != PlayKind.EPISODE) return@launch
        c.player.streamInfo.collect { info ->
            if (info == null || info.width <= 0 || info.height <= 0) return@collect
            val ep = episode
            val id = if (kind == PlayKind.MOVIE) uid else ep?.uid ?: return@collect
            c.db.streamQuality().record(listOf(StreamQualityEntity(id, info.width, info.height)), ep?.seriesUid.orEmpty())
        }
    }

    init {
        recordStreamSize()
        viewModelScope.launch { start() }
        viewModelScope.launch {
            ui.collect {
                c.pip.setMiniPlayerInfo(it.title, it.subtitle)
            }
        }
        if (kind == PlayKind.CHANNEL) {
            c.pip.onNextChannel = { zapNext() }
        } else {
            c.pip.onNextChannel = null
        }
        // Sauvegarde immédiate lors de la mise en pause ou de l'arrêt
        if (kind == PlayKind.MOVIE || kind == PlayKind.EPISODE) {
            viewModelScope.launch {
                var wasPlaying = false
                c.player.status.collect { st ->
                    if (wasPlaying && !st.isPlaying) {
                        onPlaybackPausedOrStopped()
                        reportTraktPause()
                    } else if (!wasPlaying && st.isPlaying) {
                        reportTraktPlaying()
                    }
                    wasPlaying = st.isPlaying
                }
            }
        }
        // Fin d'un épisode : on enchaîne le suivant ici plutôt que dans l'écran, pour ne pas dépendre de sa recomposition.
        if (kind == PlayKind.EPISODE) {
            viewModelScope.launch {
                c.player.status.collect { st ->
                    if (st.state == PlaybackState.ENDED && c.player.tag.value == currentTag) onEpisodeEnded()
                }
            }
        }
        // Lecture refusée par le serveur : on vérifie si c'est la limite de connexions de l'abonnement.
        viewModelScope.launch {
            c.player.httpCode.filterNotNull().collect { code -> explainRefusal(code) }
        }
        if (kind == PlayKind.CHANNEL) {
            viewModelScope.launch {
                c.repo.channels(group.ifBlank { Repository.KEY_ALL }).collect { list ->
                    channels.value = list
                    nowMap.value = c.repo.nowPrograms(list.map { it.epgKey })
                }
            }
            viewModelScope.launch { watchQuality() }
            viewModelScope.launch {
                while (true) {
                    delay(30_000)
                    ui.value.channel?.let { refreshEpg(it) }
                    nowMap.value = c.repo.nowPrograms(channels.value.map { it.epgKey })
                }
            }
        }
    }

    private fun fail(msg: String) = ui.update { it.copy(resolving = false, resolveError = msg) }

    private suspend fun start() {
        // Chaînes, films et épisodes ont un uid préfixé par leur playlist ; un enregistrement, non.
        singleConnection = PlaylistKeys.playlistIdOf(uid)?.let { c.db.playlists().get(it)?.maxConnections } == 1
        when (kind) {
            PlayKind.CHANNEL -> {
                val ch = c.repo.channel(uid) ?: return fail("Chaîne introuvable")
                playChannel(ch)
            }
            PlayKind.CATCHUP -> {
                val ch = c.repo.channel(uid) ?: return fail("Chaîne introuvable")
                val p = c.db.programs().get(programId) ?: return fail("Programme introuvable")
                ui.update {
                    it.copy(
                        title = p.title, subtitle = "${ch.name} · Replay · ${Fmt.dayTime(p.start)}",
                        channel = ch, isLive = false, catchupProgram = p, now = p,
                    )
                }
                val spec = runCatching { c.repo.resolveCatchup(ch, p) }.getOrNull() ?: return fail("Replay indisponible pour ce programme")
                play(spec, 0, "catchup:${ch.uid}:${p.id}")
                ui.update { it.copy(resolving = false) }
            }
            PlayKind.MOVIE -> {
                val m = c.db.movies().get(uid) ?: return fail("Film introuvable")
                ui.update { it.copy(title = m.name, subtitle = listOf(m.year, m.genre).filter { s -> s.isNotBlank() }.joinToString(" · "), artwork = "", isLive = false) }
                val alreadyPlaying = c.player.tag.value == "movie:$uid" && c.player.current.value != null
                if (alreadyPlaying) {
                    currentTag = "movie:$uid"
                    ui.update { it.copy(resolving = false) }
                    startProgressSyncJob(uid, ContentKind.MOVIE.name, null)
                } else {
                    val completedFile = c.downloads.getCompletedFile(uid)
                    val spec = if (completedFile != null) {
                        StreamSpec(url = DownloadManager.playUri(completedFile), userAgent = "", isLive = false)
                    } else {
                        runCatching { c.repo.resolveMovie(m) }.getOrElse { return fail(it.message ?: "Lecture impossible") }
                    }
                    val pos = resolveLatestPosition(uid, startMs)
                    play(spec, pos, "movie:$uid")
                    if (pos > 0) c.player.seekTo(pos)
                    ui.update { it.copy(resolving = false) }
                    startProgressSyncJob(uid, ContentKind.MOVIE.name, null)
                }
            }
            PlayKind.EPISODE -> {
                val e = c.db.series().episode(uid) ?: return fail("Épisode introuvable")
                episodes = c.db.series().episodesList(e.seriesUid)
                val pos = resolveLatestPosition(e.uid, startMs)
                playEpisode(e, pos)
            }
            PlayKind.RECORDING -> {
                val r = c.db.recordings().get(uid.toLongOrNull() ?: 0) ?: return fail("Enregistrement introuvable")
                if (r.filePath.isBlank() || !c.recordings.fileExists(r.filePath)) return fail("Fichier introuvable (supprimé, ou dossier débranché ?)")
                ui.update { it.copy(title = r.title, subtitle = "${r.channelName} · ${Fmt.dayTime(r.start)}", artwork = "", isLive = false) }
                val alreadyPlaying = c.player.tag.value == "rec:${r.id}" && c.player.current.value != null
                if (alreadyPlaying) {
                    currentTag = "rec:${r.id}"
                    ui.update { it.copy(resolving = false) }
                } else {
                    play(StreamSpec(url = c.recordings.playUri(r.filePath), userAgent = "", isLive = false), 0, "rec:${r.id}")
                    ui.update { it.copy(resolving = false) }
                }
            }
        }
    }

    private suspend fun playEpisode(e: EpisodeEntity, pos: Long) {
        episode = e
        stayAtEpisodeEnd = false
        val s = c.db.series().get(e.seriesUid)
        val idx = episodes.indexOfFirst { it.uid == e.uid }
        val nextEp = if (idx >= 0) episodes.getOrNull(idx + 1) else null
        ui.update {
            it.copy(
                title = s?.name ?: e.title, subtitle = "S${e.season} É${e.episode} · ${e.title}", artwork = e.poster, isLive = false,
                resolving = true, resolveError = null, hasNextEpisode = nextEp != null,
                nextEpisodeTitle = nextEp?.let { ep -> "S${ep.season} É${ep.episode} · ${ep.title}" },
            )
        }
        val alreadyPlaying = c.player.tag.value == "episode:${e.uid}" && c.player.current.value != null
        if (alreadyPlaying) {
            currentTag = "episode:${e.uid}"
            ui.update { it.copy(resolving = false) }
            startProgressSyncJob(e.uid, ContentKind.SERIES.name, e.seriesUid)
            startMarkersJob(e, s)
            return
        }
        val completedFile = c.downloads.getCompletedFile(e.uid)
        val spec = if (completedFile != null) {
            StreamSpec(url = DownloadManager.playUri(completedFile), userAgent = "", isLive = false)
        } else {
            runCatching { c.repo.resolveEpisode(e) }.getOrElse { return fail(it.message ?: "Lecture impossible") }
        }
        play(spec, pos, "episode:${e.uid}")
        if (pos > 0) c.player.seekTo(pos)
        ui.update { it.copy(resolving = false) }
        startProgressSyncJob(e.uid, ContentKind.SERIES.name, e.seriesUid)
        startMarkersJob(e, s)
        startSeriesTracksJob(e)
    }

    /** L'utilisateur a choisi une piste audio ou des sous-titres ([opt] null = sous-titres coupés) : retenu pour la série. */
    fun rememberTrack(type: TrackType, opt: TrackOption?) {
        val e = episode ?: return
        if (type == TrackType.VIDEO) return
        viewModelScope.launch {
            c.settings.update { st ->
                val cur = st.seriesTracks[e.seriesUid] ?: SeriesTracks()
                val next = when (type) {
                    TrackType.AUDIO -> cur.copy(audio = opt?.label.orEmpty())
                    else -> if (opt == null) cur.copy(text = "", textOff = true) else cur.copy(text = opt.label, textOff = false)
                }.copy(at = System.currentTimeMillis())
                val all = st.seriesTracks + (e.seriesUid to next)
                // Les 300 séries les plus récentes suffisent.
                val kept = if (all.size <= MAX_SERIES_TRACKS) all else all.entries.sortedByDescending { it.value.at }.take(MAX_SERIES_TRACKS).associate { it.key to it.value }
                st.copy(seriesTracks = kept)
            }
        }
    }

    /** Réapplique, une fois les pistes connues, la langue audio et les sous-titres choisis pour cette série. */
    private fun startSeriesTracksJob(e: EpisodeEntity) {
        tracksJob?.cancel()
        val wanted = c.settings.state.value.seriesTracks[e.seriesUid] ?: return
        val tag = "episode:${e.uid}"
        tracksJob = viewModelScope.launch {
            withTimeoutOrNull(30_000L) {
                while (c.player.tag.value != tag || c.player.status.value.state != PlaybackState.READY) delay(250L)
            } ?: return@launch
            // Les pistes arrivent parfois juste après le début de la lecture.
            repeat(3) {
                delay(700L)
                if (c.player.tag.value != tag) return@launch
                if (applyTracks(wanted)) return@launch
            }
        }
    }

    private fun applyTracks(wanted: SeriesTracks): Boolean {
        var done = true
        if (wanted.audio.isNotBlank()) {
            val audio = c.player.tracks(TrackType.AUDIO)
            if (audio.isEmpty()) done = false
            else pickTrack(audio, wanted.audio)?.let { if (!it.selected) c.player.select(it) }
        }
        when {
            wanted.textOff -> if (c.player.textEnabled()) c.player.disable(TrackType.TEXT)
            wanted.text.isNotBlank() -> {
                val text = c.player.tracks(TrackType.TEXT)
                if (text.isEmpty()) done = false
                else pickTrack(text, wanted.text)?.let { if (!it.selected || !c.player.textEnabled()) c.player.select(it) }
            }
        }
        return done
    }

    /** Cherche les repères de l'épisode une fois sa durée connue, puis saute les intros dont les données sont sûres. */
    private fun startMarkersJob(e: EpisodeEntity, s: SeriesEntity?) {
        markersJob?.cancel()
        markers.value = EpisodeMarkers()
        if (s == null) return
        val tag = "episode:${e.uid}"
        markersJob = viewModelScope.launch {
            val durationMs = withTimeoutOrNull(30_000L) {
                while (c.player.tag.value != tag || !c.player.status.value.isPlaying || c.player.duration <= 0) delay(250L)
                c.player.duration
            } ?: return@launch
            val found = loadMarkers(e, s, durationMs)
            markers.value = found
            if ((found.intros + found.recaps).none { it.auto }) return@launch
            // Une seule fois par passage : revenir en arrière pour le revoir doit rester possible.
            val skipped = HashSet<SkipSegment>()
            while (isActive && c.player.tag.value == tag) {
                val segment = found.skipAt(c.player.currentPosition)
                if (segment != null && c.settings.state.value.skipsAutomatically(segment) && c.player.status.value.isPlaying && skipped.add(segment)) {
                    c.player.seekTo(segment.endMs)
                    message.value = segment.kind.skippedMessage
                }
                delay(500L)
            }
        }
    }

    private suspend fun loadMarkers(e: EpisodeEntity, s: SeriesEntity, durationMs: Long): EpisodeMarkers {
        // M3U : sans SxxEyy dans le titre, le numéro n'est que le rang dans la liste, pas celui de l'épisode.
        if (e.remoteId.isBlank() && M3uClassifier.episode(e.title) == null) return EpisodeMarkers()
        val ref = seriesRef?.takeIf { it.first == s.uid }?.second
            ?: findSeriesRef(s)?.also { seriesRef = s.uid to it }
            ?: return EpisodeMarkers()
        return c.introDb.episode(ref, e.season, e.episode, durationMs)?.markersFor(durationMs) ?: EpisodeMarkers()
    }

    /** Le panel d'abord, puis TMDB si une clé est configurée, puis TVmaze par le titre. */
    private suspend fun findSeriesRef(s: SeriesEntity): SeriesRef? {
        val year = s.yearInt.takeIf { it > 0 }
        val tmdbId = c.repo.seriesTmdbId(s) ?: c.tmdb.findSeriesId(s.name, year)
        return tmdbId?.let(SeriesRef::tmdb) ?: c.tvMaze.findSeries(s.name, year)
    }

    private suspend fun resolveLatestPosition(itemId: String, fallbackMs: Long): Long {
        val start = if (fallbackMs == START_FROM_BEGINNING) 0L else findLatestPosition(itemId, fallbackMs)
        lastSavedItem = itemId
        lastSavedMs = start
        return start
    }

    private suspend fun findLatestPosition(itemId: String, fallbackMs: Long): Long {
        val pid = PlaylistKeys.playlistIdOf(itemId)
        val playlist = pid?.let { c.db.playlists().get(it) }
        val key = playlist?.let { PlaylistKeys.of(it) }
        val contentKey = if (pid != null && key != null) PlaylistKeys.contentKey(itemId, pid, key) else null
        val profileId = c.profiles.currentProfileId.ifBlank { c.profiles.activeIdNow() }

        val local = c.repo.progressNow(itemId)
        val cloud = if (contentKey != null) runCatching { c.cloud.fetchCloudProgress(profileId, contentKey) }.getOrNull() else null

        val latest = when {
            local != null && cloud != null -> {
                if (cloud.updatedAt > local.updatedAt) {
                    c.db.progress().upsert(cloud.copy(uid = itemId))
                    cloud
                } else {
                    local
                }
            }
            cloud != null -> {
                c.db.progress().upsert(cloud.copy(uid = itemId))
                cloud
            }
            else -> local
        } ?: return fallbackMs.coerceAtLeast(0L)
        // Vu à 95 % : on repart du début, comme les fiches. Reprendre à la fin terminerait aussitôt la lecture
        // (et enchaînerait l'épisode suivant sans montrer celui demandé).
        return if (latest.positionMs < latest.durationMs * 0.95) latest.positionMs else 0L
    }

    private fun startProgressSyncJob(itemId: String, itemType: String, seriesId: String?) {
        progressSyncJob?.cancel()
        progressSyncJob = viewModelScope.launch {
            while (isActive) {
                delay(10_000L)
                if (c.player.isPlaying) {
                    val pos = c.player.currentPosition
                    val dur = c.player.duration
                    if (dur > 0 && pos >= 0) {
                        saveWatchProgressInternal(itemId, itemType, seriesId, pos, dur)
                        runCatching { c.cloud.pushWatchProgressImmediate() }
                    }
                }
            }
        }
    }

    private suspend fun saveWatchProgressInternal(
        itemId: String, itemType: String, seriesId: String?, positionMs: Long, durationMs: Long,
    ) {
        // Rien de regardé depuis la dernière sauvegarde (pause, sortie du lecteur) : une TV restée en pause
        // n'écrase pas la position plus récente d'une autre TV.
        if (itemId == lastSavedItem && abs(positionMs - lastSavedMs) < 1_000L) return
        lastSavedItem = itemId
        lastSavedMs = positionMs
        val k = if (itemType == ContentKind.SERIES.name) ContentKind.SERIES else ContentKind.MOVIE
        val profileId = c.profiles.currentProfileId.ifBlank { c.profiles.activeIdNow() }
        c.repo.saveWatchProgress(
            uid = itemId,
            kind = k,
            parentUid = seriesId.orEmpty(),
            profileId = profileId,
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun onPlaybackPausedOrStopped() {
        val pos = c.player.currentPosition
        val dur = c.player.duration
        if (dur > 0 && pos >= 0) {
            viewModelScope.launch {
                when (kind) {
                    PlayKind.MOVIE -> saveWatchProgressInternal(uid, ContentKind.MOVIE.name, null, pos, dur)
                    PlayKind.EPISODE -> episode?.let { saveWatchProgressInternal(it.uid, ContentKind.SERIES.name, it.seriesUid, pos, dur) }
                }
                runCatching { c.cloud.pushWatchProgressImmediate() }
            }
        }
    }

    // ------------------------------------------------------------ Trakt

    private var traktCurrent: Pair<String, TraktMedia>? = null
    private var traktProgress = 0.0

    private fun readTraktProgress(): Double {
        val position = c.player.currentPosition
        val length = c.player.duration
        if (length > 0 && position >= 0) traktProgress = (position * 100.0 / length).coerceIn(0.0, 100.0)
        return traktProgress
    }

    private suspend fun currentTraktMedia(): TraktMedia? {
        val key = when (kind) {
            PlayKind.MOVIE -> uid
            PlayKind.EPISODE -> episode?.uid
            else -> null
        } ?: return null
        traktCurrent?.takeIf { it.first == key }?.let { return it.second }
        val media = when (kind) {
            PlayKind.MOVIE -> c.db.movies().get(uid)?.let { c.trakt.movieMedia(it) }
            else -> episode?.let { e -> c.db.series().get(e.seriesUid)?.let { c.trakt.episodeMedia(it, e) } }
        } ?: return null
        traktCurrent = key to media
        return media
    }

    private fun reportTraktPlaying() {
        if (!c.trakt.canScrobble) return
        viewModelScope.launch {
            val media = currentTraktMedia() ?: return@launch
            // La recherche TMDB peut durer : la lecture a pu s'arrêter entre-temps.
            if (c.player.status.value.isPlaying) c.trakt.onPlaying(media, readTraktProgress())
        }
    }

    private fun reportTraktPause() {
        if (c.trakt.canScrobble) c.trakt.onPaused(readTraktProgress())
    }

    private fun reportTraktStop() {
        if (traktCurrent != null) c.trakt.onStopped(readTraktProgress())
    }

    private fun onEpisodeEnded() {
        val dur = c.player.duration
        saveProgress(dur, dur)
        if (!stayAtEpisodeEnd) nextEpisode()
    }

    fun nextEpisode(): Boolean {
        val e = episode ?: return false
        val n = episodes.getOrNull(episodes.indexOfFirst { it.uid == e.uid } + 1) ?: return false
        viewModelScope.launch { playEpisode(n, 0) }
        return true
    }

    /** Passe le récap ou l'intro en cours. Sans repère à cette position, avance de 85 s (choix manuel). */
    fun skipSegment() {
        val current = c.player.currentPosition
        val segment = markers.value.skipAt(current)
        c.player.seekTo(segment?.endMs ?: (current + 85_000L))
        message.value = (segment?.kind ?: SkipKind.INTRO).skippedMessage
    }

    fun playChannel(ch: ChannelEntity, force: Boolean = false) {
        if (ChannelSeparator.matches(ch.name)) {
            val msg = "« ${ch.name.trim()} » est un intertitre de la playlist, pas une chaîne"
            if (ui.value.channel == null) fail(msg) else message.value = msg
            return
        }
        playJob?.cancel()
        val leavingTimeshift = ui.value.timeshift != null
        if (leavingTimeshift) buffer.stop()
        playJob = viewModelScope.launch {
            ui.update {
                val prev = it.channel?.takeIf { cur -> cur.uid != ch.uid } ?: it.previous
                it.copy(
                    channel = ch, title = ch.name, subtitle = "", isLive = true, resolving = true, resolveError = null,
                    catchupProgram = null, isCatchupMode = false, canRestartCurrentProgram = false,
                    restartProgramTitle = null, restartProgramStart = 0L,
                    previous = prev, timeshift = null, limitMessage = null,
                )
            }
            refreshEpg(ch)
            val alreadyPlaying = !force && !leavingTimeshift && !connectionReleasedForZap &&
                c.player.tag.value == "ch:${ch.uid}" && c.player.current.value != null
            if (alreadyPlaying) currentTag = "ch:${ch.uid}"
            if (!alreadyPlaying) {
                // Hors du try : une rafale annule cette attente, ce qui n'est pas un échec d'ouverture.
                val wait = if (force) 0L else zapPacer.waitBeforeOpening(System.currentTimeMillis())
                if (wait > 0) {
                    // Inutile de garder ouverte, pendant la rafale, la connexion d'une chaîne qu'on vient de quitter.
                    c.player.releaseConnection()
                    connectionReleasedForZap = true
                    delay(wait)
                }
                try {
                    val spec = c.repo.resolveChannel(ch)
                    play(spec, 0, "ch:${ch.uid}")
                    connectionReleasedForZap = false
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // une autre chaîne a été demandée entre-temps : ce n'est pas un échec
                } catch (e: Exception) {
                    ui.update { it.copy(resolving = false, resolveError = "Impossible d'ouvrir la chaîne : ${e.message}") }
                    return@launch
                }
            }
            c.repo.touchChannel(ch, group.ifBlank { Repository.KEY_ALL })
            ui.update { it.copy(resolving = false) }
        }
    }

    /** Relance après une erreur ou un retour au premier plan. */
    fun replayCurrent() {
        val ts = ui.value.timeshift
        when {
            // Direct en pause, différé pas encore lancé : on reste en pause.
            ui.value.isLive && ts != null && !ts.started -> Unit
            ui.value.isLive && ts != null -> when {
                c.player.error.value != null || c.player.current.value == null -> c.player.retry()
                !ts.paused -> c.player.resume()
            }
            ui.value.isLive -> ui.value.channel?.let { playChannel(it, force = true) }
            else -> c.player.retry()
        }
    }

    /** Programme en cours sur une chaîne de la liste (mini-guide). */
    suspend fun nowOn(ch: ChannelEntity): ProgramEntity? = c.repo.nowPrograms(listOf(ch.epgKey))[ch.epgKey]

    fun playPrevious(): Boolean {
        val p = ui.value.previous ?: return false
        playChannel(p)
        return true
    }

    // ------------------------------------------------------------ Pause du direct

    /** Lecture/pause sur une chaîne en direct : met le direct en pause, puis reprend en différé. */
    fun toggleLivePause() {
        val s = ui.value
        val ch = s.channel ?: return
        val ts = s.timeshift
        when {
            ts == null -> pauseLive(ch)
            ts.paused -> resumeTimeshift(ch, ts)
            else -> {
                c.player.pause()
                ui.update { it.copy(timeshift = ts.copy(paused = true)) }
            }
        }
    }

    private fun pauseLive(ch: ChannelEntity) {
        val now = System.currentTimeMillis()
        val canReplay = ch.catchupDays > 0 && ch.catchupType.isNotBlank() && ch.catchupType != "stalker"
        val minutes = c.settings.state.value.timeshiftMinutes
        when {
            canReplay -> {
                c.player.releaseConnection()
                ui.update { it.copy(timeshift = TimeshiftState(TimeshiftSource.CATCHUP, now, paused = true)) }
            }
            minutes > 0 -> viewModelScope.launch {
                val spec = runCatching { c.repo.resolveChannel(ch) }.getOrNull()
                c.player.releaseConnection()
                if (spec != null && buffer.start(viewModelScope, spec, minutes)) {
                    ui.update { it.copy(timeshift = TimeshiftState(TimeshiftSource.BUFFER, now, paused = true)) }
                } else {
                    message.value = buffer.error ?: "Pause du direct impossible sur cette chaîne"
                    ui.update { it.copy(timeshift = TimeshiftState(TimeshiftSource.NONE, now, paused = true)) }
                }
            }
            else -> {
                c.player.releaseConnection()
                ui.update { it.copy(timeshift = TimeshiftState(TimeshiftSource.NONE, now, paused = true)) }
            }
        }
    }

    private fun resumeTimeshift(ch: ChannelEntity, ts: TimeshiftState) {
        if (ts.started) {
            c.player.resume()
            ui.update { it.copy(timeshift = ts.copy(paused = false)) }
            return
        }
        when (ts.source) {
            TimeshiftSource.CATCHUP -> viewModelScope.launch {
                // Le replay se demande à la minute : il commence avant la pause, on avance jusqu'à l'image mise en pause.
                val from = ts.pausedAt / 60_000 * 60_000
                val spec = runCatching { c.repo.resolveCatchupAt(ch, from, from + 4 * 3_600_000L) }.getOrNull()
                if (spec == null) {
                    message.value = "Replay indisponible : retour au direct"
                    backToLive()
                    return@launch
                }
                play(spec, ts.pausedAt - from, "ch:${ch.uid}")
                ui.update { it.copy(timeshift = ts.copy(paused = false, started = true, origin = from)) }
            }
            TimeshiftSource.BUFFER -> {
                val f = buffer.file
                if (f == null || !f.exists() || f.length() == 0L) {
                    message.value = buffer.error ?: "Rien n'a été mis en mémoire : retour au direct"
                    backToLive()
                    return
                }
                currentTag = "ch:${ch.uid}"
                c.player.playGrowingFile(f, { buffer.recording }, "ch:${ch.uid}")
                ui.update { it.copy(timeshift = ts.copy(paused = false, started = true)) }
            }
            TimeshiftSource.NONE -> backToLive()
        }
    }

    /** Retour au direct (abandon du différé ou du replay). */
    fun backToLive() {
        buffer.stop()
        val ch = ui.value.channel ?: return
        ui.update { it.copy(timeshift = null, isCatchupMode = false) }
        playChannel(ch, force = true)
    }

    /** Fin du différé (mémoire tampon épuisée) : retour au direct. */
    fun onTimeshiftEnded() {
        message.value = "Fin de la mémoire tampon : retour au direct"
        backToLive()
    }

    /** Retard sur le direct, en ms, pour une position de lecture donnée. */
    fun timeshiftDelay(positionMs: Long, now: Long = System.currentTimeMillis()): Long {
        val ts = ui.value.timeshift ?: return 0
        if (!ts.started) return now - ts.pausedAt
        val origin = when {
            ts.source == TimeshiftSource.BUFFER -> buffer.startedAt
            ts.origin > 0 -> ts.origin
            else -> ts.pausedAt
        }
        return (now - (origin + positionMs)).coerceAtLeast(0)
    }

    // ------------------------------------------------------------ Multi-View (Multiplex 2 à 4 flux)

    fun enterMultiView(secondChannel: ChannelEntity? = null) {
        if (!ui.value.isLive || isMultiViewActive.value) return
        val currentChannel = ui.value.channel ?: return
        viewModelScope.launch {
            val ch2 = secondChannel ?: secondScreenChannel(currentChannel)
            if (ch2 == null) {
                message.value = "Aucune autre chaîne à afficher"
                return@launch
            }
            if (!canOpenScreen(ch2) || isMultiViewActive.value) return@launch
            multiViewSlots.value = listOf(MultiViewSlot(0, currentChannel, c.player, isPrimary = true), openSlot(1, ch2, muted = true))
            activeAudioSlot.value = 0
            c.player.setMuted(false)
            isMultiViewActive.value = true
        }
    }

    /** Deuxième écran par défaut : une chaîne regardée récemment ou favorite, plutôt que la voisine (souvent un intertitre). */
    private suspend fun secondScreenChannel(current: ChannelEntity): ChannelEntity? {
        fun ok(ch: ChannelEntity) = ch.uid != current.uid && !ChannelSeparator.matches(ch.name)
        return c.repo.channels(Repository.KEY_RECENT).first().firstOrNull(::ok)
            ?: c.repo.channels(Repository.KEY_FAVORITES).first().firstOrNull(::ok)
            ?: channelAfter(current.uid, 1)?.takeIf(::ok)
    }

    /**
     * Un écran de plus sur l'abonnement de [ch] reste-t-il dans sa limite de connexions ? Chaque écran, enregistrement
     * en cours et téléchargement en occupe une. Sinon, l'utilisateur est prévenu et rien n'est ouvert.
     */
    private suspend fun canOpenScreen(ch: ChannelEntity): Boolean {
        val p = c.db.playlists().get(ch.playlistId) ?: return true
        if (p.maxConnections <= 0) return true
        val pid = ch.playlistId
        val now = System.currentTimeMillis()
        val screens = multiViewSlots.value.map { it.channel }.ifEmpty { listOfNotNull(ui.value.channel) }.count { it.playlistId == pid }
        val recordings = c.db.recordings().overlapping(now, now + 1)
            .count { it.status == RecordingStatus.RECORDING && PlaylistKeys.playlistIdOf(it.channelUid) == pid }
        val downloads = c.db.downloads().activeDownloads().count { PlaylistKeys.playlistIdOf(it.itemUid) == pid }
        if (screens + recordings + downloads < p.maxConnections) return true
        fun n(count: Int, what: String) = "$count $what" + if (count > 1) "s" else ""
        val used = listOfNotNull(
            n(screens, "écran"),
            recordings.takeIf { it > 0 }?.let { n(it, "enregistrement") + " en cours" },
            downloads.takeIf { it > 0 }?.let { n(it, "téléchargement") },
        ).joinToString(", ")
        message.value = "Plus de connexion libre : ton abonnement en autorise ${p.maxConnections} ($used). " +
            "Ferme un écran ou arrête un enregistrement ou un téléchargement."
        return false
    }

    private fun openSlot(index: Int, ch: ChannelEntity, muted: Boolean): MultiViewSlot {
        val p = c.createPlayer(isSecondary = true)
        p.setMuted(muted)
        viewModelScope.launch {
            val spec = runCatching { c.repo.resolveChannel(ch) }.getOrNull()
            if (spec != null) p.play(spec, tag = "ch:${ch.uid}")
        }
        return MultiViewSlot(index, ch, p)
    }

    fun setActiveAudioSlot(slotIndex: Int) {
        val slots = multiViewSlots.value
        if (slotIndex !in slots.indices) return
        activeAudioSlot.value = slotIndex
        slots.forEachIndexed { idx, slot ->
            slot.player.setMuted(idx != slotIndex)
        }
    }

    fun saveMultiViewPreset(name: String) {
        val uids = multiViewSlots.value.map { it.channel.uid }
        if (name.isBlank() || uids.size < 2) return
        viewModelScope.launch {
            c.settings.update { it.copy(multiViewPresets = MultiViewPresets.save(it.multiViewPresets, it.activeProfileId, name, uids)) }
            message.value = "Préréglage « ${name.trim()} » enregistré"
        }
    }

    fun deleteMultiViewPreset(preset: MultiViewPreset) {
        viewModelScope.launch { c.settings.update { it.copy(multiViewPresets = MultiViewPresets.remove(it.multiViewPresets, preset)) } }
    }

    private suspend fun visibleChannels(uids: List<String>): List<ChannelEntity> {
        val view = c.repo.viewScopeNow()
        return uids.distinct().mapNotNull { c.db.channels().get(it) }.filter { ch ->
            ch.playlistId in view.pids && !ch.hidden && !ChannelSeparator.matches(ch.name) &&
                !(view.hide && (ch.locked || c.db.categories().isLocked(ch.categoryUid)))
        }.take(MAX_MULTI_VIEW_SCREENS)
    }

    /** Ouvre plusieurs chaînes en multi-écran, une à une : le fournisseur bloque les rafales de connexions. */
    fun openMultiViewChannels(uids: List<String>) {
        viewModelScope.launch {
            val channels = visibleChannels(uids)
            if (channels.size < 2) {
                message.value = "Ces chaînes ne sont plus disponibles"
                return@launch
            }
            exitMultiView()
            val first = channels.first()
            if (ui.value.channel?.uid != first.uid) {
                playChannel(first)
                withTimeoutOrNull(MULTI_VIEW_START_TIMEOUT_MS) { ui.first { it.channel?.uid == first.uid } } ?: return@launch
            }
            delay(MULTI_VIEW_OPEN_PAUSE_MS)
            if (!canOpenScreen(channels[1])) return@launch
            multiViewSlots.value = listOf(MultiViewSlot(0, first, c.player, isPrimary = true), openSlot(1, channels[1], muted = true))
            activeAudioSlot.value = 0
            c.player.setMuted(false)
            isMultiViewActive.value = true
            channels.drop(2).forEach { ch ->
                delay(MULTI_VIEW_OPEN_PAUSE_MS)
                addMultiViewChannel(ch).join()
            }
        }
    }

    fun addMultiViewChannel(channel: ChannelEntity) = viewModelScope.launch {
        if (multiViewSlots.value.size >= 4) return@launch
        if (ChannelSeparator.matches(channel.name)) {
            message.value = "« ${channel.name.trim()} » est un intertitre de la playlist, pas une chaîne"
            return@launch
        }
        if (!canOpenScreen(channel)) return@launch
        val newIndex = multiViewSlots.value.size
        if (newIndex >= 4) return@launch
        val slot = openSlot(newIndex, channel, muted = newIndex != activeAudioSlot.value)
        multiViewSlots.update { it + slot }
    }

    fun changeSlotChannel(slotIndex: Int, newChannel: ChannelEntity) {
        val current = multiViewSlots.value
        if (slotIndex !in current.indices) return
        if (ChannelSeparator.matches(newChannel.name)) {
            message.value = "« ${newChannel.name.trim()} » est un intertitre de la playlist, pas une chaîne"
            return
        }
        if (slotIndex == 0) {
            playChannel(newChannel)
            multiViewSlots.update { list -> list.mapIndexed { idx, s -> if (idx == 0) s.copy(channel = newChannel) else s } }
        } else {
            val slot = current[slotIndex]
            viewModelScope.launch {
                val spec = runCatching { c.repo.resolveChannel(newChannel) }.getOrNull()
                if (spec != null) {
                    slot.player.play(spec, tag = "ch:${newChannel.uid}")
                    multiViewSlots.update { list -> list.mapIndexed { idx, s -> if (idx == slotIndex) s.copy(channel = newChannel) else s } }
                }
            }
        }
    }

    fun removeSlot(slotIndex: Int) {
        val current = multiViewSlots.value
        if (slotIndex !in current.indices) return
        if (current.size <= 2) {
            val remainingIndex = if (slotIndex == 0) 1 else 0
            expandSlotToFullScreen(remainingIndex)
            return
        }
        if (slotIndex == 0) {
            val nextChannel = current[1].channel
            current[1].player.release()
            playChannel(nextChannel)
            val remaining = current.drop(1).mapIndexed { newIdx, s ->
                if (newIdx == 0) MultiViewSlot(0, nextChannel, c.player, isPrimary = true)
                else s.copy(index = newIdx)
            }
            multiViewSlots.value = remaining
        } else {
            val target = current[slotIndex]
            if (!target.isPrimary) target.player.release()
            val remaining = current.filterIndexed { idx, _ -> idx != slotIndex }
                .mapIndexed { newIdx, s -> s.copy(index = newIdx) }
            multiViewSlots.value = remaining
        }
        val safeAudio = activeAudioSlot.value.coerceIn(0 until multiViewSlots.value.size)
        setActiveAudioSlot(safeAudio)
    }

    fun expandSlotToFullScreen(slotIndex: Int) {
        val current = multiViewSlots.value
        val slot = current.getOrNull(slotIndex)
        current.forEach { if (!it.isPrimary) it.player.release() }
        multiViewSlots.value = emptyList()
        isMultiViewActive.value = false
        c.player.setMuted(false)
        if (slot != null && !slot.isPrimary) {
            playChannel(slot.channel)
        }
    }

    fun exitMultiView() {
        if (!isMultiViewActive.value) return
        isMultiViewActive.value = false
        multiViewSlots.value.forEach { if (!it.isPrimary) it.player.release() }
        multiViewSlots.value = emptyList()
        c.player.setMuted(false)
    }

    override fun onCleared() {
        exitMultiView()
        progressSyncJob?.cancel()
        onPlaybackPausedOrStopped()
        reportTraktStop()
        buffer.stop()
        super.onCleared()
    }

    // ------------------------------------------------------------ Mini-guide

    /** Programmes d'une chaîne autour de maintenant (3 h avant, 12 h après). */
    suspend fun guideFor(ch: ChannelEntity): List<ProgramEntity> {
        val now = System.currentTimeMillis()
        return c.repo.programs(ch.epgKey, now - 3 * 3_600_000L, now + 12 * 3_600_000L)
    }

    /** Chaîne voisine dans la liste en cours, pour parcourir le mini-guide sans zapper. */
    fun neighbour(ch: ChannelEntity, delta: Int): ChannelEntity? {
        val list = channels.value
        if (list.isEmpty()) return null
        val i = list.indexOfFirst { it.uid == ch.uid }
        return list[Math.floorMod((if (i < 0) 0 else i) + delta, list.size)]
    }

    fun canCatchup(ch: ChannelEntity, p: ProgramEntity) = c.repo.canCatchup(ch, p)

    // ------------------------------------------------------------ Qualité automatique

    private fun followTag() {
        val tag = c.player.tag.value
        if (tag != watchedTag) {
            watchedTag = tag
            stalls.reset()
        }
    }

    /** Direct qui saccade : on passe à une version plus légère de la même chaîne (FHD → HD → SD). */
    private suspend fun watchQuality() = coroutineScope {
        launch {
            c.player.status.collect { st ->
                followTag()
                if (stalls.onState(st.state, System.currentTimeMillis())) lowerQuality()
            }
        }
        while (isActive) {
            delay(1_000)
            followTag()
            if (stalls.tick(System.currentTimeMillis())) lowerQuality()
        }
    }

    private fun lowerQuality() {
        stalls.reset()
        val ch = ui.value.channel ?: return
        if (!c.settings.state.value.autoQuality || ch.uid in noLowerQuality) return
        // Pause du direct ou multi-écran : le flux n'est pas celui de la chaîne lue ici.
        if (!ui.value.isLive || ui.value.timeshift != null || isMultiViewActive.value) return
        if (c.player.tag.value != "ch:${ch.uid}") return
        viewModelScope.launch {
            val lower = runCatching { c.repo.lowerQuality(ch) }.getOrNull()
            if (lower == null) {
                noLowerQuality += ch.uid
                return@launch
            }
            if (ui.value.channel?.uid != ch.uid) return@launch
            val origin = qualityAnchor?.takeIf { it.first == ch.uid }?.second ?: ch.uid
            qualityAnchor = lower.uid to origin
            message.value = "L'image saccade : passage à « ${lower.name.trim()} »"
            playChannel(lower, force = true)
        }
    }

    // ------------------------------------------------------------ Lecture refusée

    private suspend fun explainRefusal(code: Int) {
        val pid = when (kind) {
            PlayKind.CHANNEL, PlayKind.CATCHUP -> ui.value.channel?.playlistId
            PlayKind.EPISODE -> episode?.playlistId
            else -> PlaylistKeys.playlistIdOf(uid)
        } ?: return
        val p = c.db.playlists().get(pid) ?: return
        if (p.type == SourceType.M3U) return
        val fresh = runCatching { c.sync.checkAccount(p) }.getOrNull() ?: p
        val msg = when {
            fresh.expiresAt in 1 until System.currentTimeMillis() -> "L'abonnement « ${fresh.name} » a expiré le ${Fmt.date(fresh.expiresAt)}."
            fresh.accountStatus.isNotBlank() && !fresh.accountStatus.equals("Active", true) -> "Le fournisseur indique un abonnement « ${fresh.accountStatus} »."
            fresh.maxConnections > 0 && fresh.activeConnections >= fresh.maxConnections ->
                "Limite de connexions atteinte : ${fresh.activeConnections}/${fresh.maxConnections} déjà utilisée(s) sur ton abonnement. " +
                    "Arrête la lecture sur un autre appareil (ou l'enregistrement en cours), puis réessaie."
            else -> null
        } ?: return
        ui.update { it.copy(limitMessage = msg) }
    }

    private suspend fun refreshEpg(ch: ChannelEntity) {
        val now = System.currentTimeMillis()
        val up = c.repo.upcoming(ch.epgKey, 3)
        val curProgram = up.firstOrNull { it.start <= now }
        val nextProgram = up.firstOrNull { it.start > now }
        ui.update { s ->
            if (s.channel?.uid != ch.uid || !s.isLive) s
            else {
                val canRestart = checkRestartEligibility(ch, curProgram, s.timeshift != null, s.isCatchupMode, now)
                s.copy(
                    now = curProgram,
                    next = nextProgram,
                    canRestartCurrentProgram = canRestart,
                    restartProgramTitle = if (canRestart) curProgram?.title else null,
                    restartProgramStart = if (canRestart) curProgram?.start ?: 0L else 0L,
                )
            }
        }
    }

    fun checkRestartEligibility(
        ch: ChannelEntity,
        prog: ProgramEntity?,
        isTimeshifting: Boolean,
        isCatchup: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (prog == null) return false
        if (isTimeshifting || isCatchup) return false
        // Support du replay sur la chaîne (via tvArchive / catchupDays / catchupType)
        val supportsCatchup = (ch.catchupDays > 0 && ch.catchupType.isNotBlank()) || c.repo.canCatchup(ch, prog, now)
        if (!supportsCatchup) return false
        // Programme commencé depuis plus de 3 minutes (180 000 ms)
        return (now - prog.start) > 180_000L
    }

    /** Revoir l'émission en cours depuis le début via le flux catch-up/replay. */
    fun restartCurrentProgramFromBeginning() {
        val s = ui.value
        val ch = s.channel ?: return
        val p = s.now ?: return
        playJob?.cancel()
        val leavingTimeshift = s.timeshift != null
        if (leavingTimeshift) buffer.stop()

        playJob = viewModelScope.launch {
            ui.update {
                it.copy(
                    resolving = true,
                    resolveError = null,
                    isCatchupMode = true,
                    canRestartCurrentProgram = false,
                    catchupProgram = p,
                    title = p.title,
                    subtitle = "${ch.name} · Replay · Début ${Fmt.time(p.start)}",
                )
            }
            try {
                val spec = c.repo.resolveCatchup(ch, p)
                if (spec == null) {
                    message.value = "Replay indisponible pour cette émission"
                    backToLive()
                    return@launch
                }
                play(spec, 0L, "catchup:${ch.uid}:${p.id}")
                ui.update { it.copy(resolving = false) }
                message.value = "Lecture depuis le début"
            } catch (e: Exception) {
                message.value = "Erreur replay : ${e.message}"
                backToLive()
            }
        }
    }

    fun zap(delta: Int): ChannelEntity? {
        val uid = ui.value.channel?.uid
        // Version plus légère choisie automatiquement : elle n'est pas forcément dans la liste, on zappe depuis l'originale.
        val from = qualityAnchor?.takeIf { it.first == uid }?.second ?: uid
        val target = channelAfter(from, delta) ?: return null
        playChannel(target)
        return target
    }

    /** Chaîne suivante dans le sens de [delta], en sautant les intertitres du fournisseur (ils ne diffusent rien). */
    private fun channelAfter(uid: String?, delta: Int): ChannelEntity? {
        val list = channels.value
        if (list.isEmpty()) return null
        val start = list.indexOfFirst { it.uid == uid }.coerceAtLeast(0)
        var idx = start
        repeat(list.size) {
            idx = Math.floorMod(idx + delta, list.size)
            if (!ChannelSeparator.matches(list[idx].name)) return list[idx]
        }
        return null
    }

    fun previousChannel(): ChannelEntity? = zap(if (invertZap) 1 else -1)
    fun nextChannel(): ChannelEntity? = zap(if (invertZap) -1 else 1)

    fun seekRelative(deltaMs: Long) {
        val dur = c.player.duration
        val target = (c.player.currentPosition + deltaMs).coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
        c.player.seekTo(target)
    }

    fun playNumber(n: Int) = viewModelScope.launch {
        // « 0 » seul : chaîne précédente (comme sur la plupart des boîtiers TV).
        if (n == 0 && playPrevious()) return@launch
        val ch = channels.value.firstOrNull { it.number == n } ?: c.repo.channelByNumber(n)
        if (ch != null) playChannel(ch) else message.value = "Aucune chaîne n°$n"
    }

    fun saveProgress(positionMs: Long, durationMs: Long) {
        if (durationMs <= 0 || positionMs < 0) return
        viewModelScope.launch {
            when (kind) {
                PlayKind.MOVIE -> saveWatchProgressInternal(uid, ContentKind.MOVIE.name, null, positionMs, durationMs)
                PlayKind.EPISODE -> episode?.let { saveWatchProgressInternal(it.uid, ContentKind.SERIES.name, it.seriesUid, positionMs, durationMs) }
                else -> Unit
            }
            runCatching { c.cloud.pushWatchProgressImmediate() }
        }
    }

    fun record() = viewModelScope.launch {
        val ch = ui.value.channel ?: return@launch
        val p = ui.value.now
        val s = c.settings.get()
        val stop = p?.let { it.stop + s.recPaddingAfterMin * 60_000L } ?: (System.currentTimeMillis() + 60 * 60_000L)
        // Regarder et enregistrer occupent chacun une connexion de l'abonnement.
        val max = c.db.playlists().get(ch.playlistId)?.maxConnections ?: 0
        val others = c.db.recordings().overlapping(System.currentTimeMillis(), stop).count { it.channelUid.substringBefore(':') == ch.uid.substringBefore(':') }
        if (p != null) c.recordings.recordProgram(ch, p) else c.recordings.recordNow(ch, ch.name, stop)
        val done = if (p != null) "Enregistrement de « ${p.title} » démarré" else "Enregistrement démarré (1 h)"
        message.value = if (max in 1..(others + 1)) "$done. Ton abonnement n'autorise que $max connexion(s) : la lecture risque de s'interrompre." else done
    }

    fun toggleFavorite() = viewModelScope.launch {
        val ch = ui.value.channel ?: return@launch
        c.repo.toggleFavorite(ch.uid, ContentKind.LIVE)
    }

    /** Envoie la lecture vers les autres écrans Vyzo du compte : chacun retrouve le contenu avec sa propre playlist. */
    fun castToMyScreens() {
        c.cloud.sendCastCommand(
            fr.yannickuhrig.vyzo.data.remote.CastCommand(
                itemType = kind,
                itemId = uid,
                title = ui.value.title,
                posterUrl = ui.value.now?.icon,
                positionMs = c.player.currentPosition,
                senderDeviceId = c.platform.deviceModel,
            ),
        )
        message.value = "Envoyé vers mes écrans Vyzo"
    }

    fun canCatchupNow(): Boolean {
        val s = ui.value
        val ch = s.channel ?: return false
        val p = s.now ?: return false
        return s.isLive && c.repo.canCatchup(ch, p)
    }
}

/** Nombre de séries dont on retient les pistes choisies. */
private const val MAX_SERIES_TRACKS = 300
private const val MAX_MULTI_VIEW_SCREENS = 4
private const val MULTI_VIEW_START_TIMEOUT_MS = 15_000L
private const val MULTI_VIEW_OPEN_PAUSE_MS = 1_500L

/**
 * Piste qui correspond au nom retenu : même nom exact, sinon même langue (premier mot du nom, « Français », « English »…).
 * Les noms changent peu d'un épisode à l'autre chez un même fournisseur.
 */
fun pickTrack(options: List<TrackOption>, label: String): TrackOption? {
    options.firstOrNull { it.label == label }?.let { return it }
    val lang = label.substringBefore(' ').substringBefore('(').trim().lowercase()
    if (lang.length < 2) return null
    return options.firstOrNull { it.label.substringBefore(' ').substringBefore('(').trim().lowercase() == lang }
}
