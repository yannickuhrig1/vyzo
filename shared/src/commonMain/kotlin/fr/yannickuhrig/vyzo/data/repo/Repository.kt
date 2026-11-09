package fr.yannickuhrig.vyzo.data.repo

import fr.yannickuhrig.vyzo.data.db.withTransaction
import fr.yannickuhrig.vyzo.data.catchup.CatchupBuilder
import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.CategoryPrefEntity
import fr.yannickuhrig.vyzo.data.db.CategoryRow
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ChannelHistoryEntity
import fr.yannickuhrig.vyzo.data.db.ChannelPrefEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.EpgSourceEntity
import fr.yannickuhrig.vyzo.data.db.EpisodeEntity
import fr.yannickuhrig.vyzo.data.db.FAVORITES_GROUP_ID
import fr.yannickuhrig.vyzo.data.db.MovieEntity
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.PlaylistKeys
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.db.SyncTombstoneEntity
import fr.yannickuhrig.vyzo.data.db.UserGroupEntity
import fr.yannickuhrig.vyzo.data.db.UserGroupItemEntity
import fr.yannickuhrig.vyzo.data.db.WatchProgressEntity
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.data.profile.ParentalControl
import fr.yannickuhrig.vyzo.data.profile.ProfileManager
import fr.yannickuhrig.vyzo.data.source.xtream.XtreamClient
import fr.yannickuhrig.vyzo.data.sync.SyncManager
import fr.yannickuhrig.vyzo.data.vod.Genres
import fr.yannickuhrig.vyzo.player.QualityVariants
import fr.yannickuhrig.vyzo.data.vod.VodQualityIndex
import fr.yannickuhrig.vyzo.data.vod.announcesUhd
import fr.yannickuhrig.vyzo.data.vod.resumeFractions
import fr.yannickuhrig.vyzo.data.sport.SportDetector
import fr.yannickuhrig.vyzo.data.sport.SportEvent
import fr.yannickuhrig.vyzo.data.sport.SportLineup
import fr.yannickuhrig.vyzo.util.AdultDetector
import fr.yannickuhrig.vyzo.util.FuzzyMatch
import fr.yannickuhrig.vyzo.util.TextNorm
import fr.yannickuhrig.vyzo.data.tmdb.TmdbTitle
import fr.yannickuhrig.vyzo.data.tmdb.sameTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class StreamSpec(
    val url: String,
    val userAgent: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeHint: String? = null,
    val drmType: String = "",
    val drmKey: String = "",
    val isLive: Boolean,
)

enum class GroupKind { ALL, FAVORITES, RECENT, USER, CATEGORY }

data class GroupItem(
    val key: String,
    val title: String,
    val kind: GroupKind,
    val count: Int = -1,
    /** Catégorie verrouillée par le contrôle parental : le code est demandé pour l'ouvrir. */
    val locked: Boolean = false,
)

data class SearchResults(
    val channels: List<ChannelEntity> = emptyList(),
    val movies: List<MovieEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
    val programs: List<Pair<ProgramEntity, ChannelEntity>> = emptyList(),
)

/** Ce que voit l'utilisateur : playlists affichées, profil actif, contenu verrouillé caché ou non. */
data class ViewScope(
    val pids: List<Long>,
    val profileId: String,
    val hide: Boolean,
    val kid: Boolean,
    val stealth: Boolean = false,
    /** Noms des playlists, quand plusieurs sont affichées ensemble (pour distinguer leurs catégories). */
    val playlistNames: Map<Long, String> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class Repository(
    val db: AppDatabase,
    val settings: SettingsStore,
    val sync: SyncManager,
    val profiles: ProfileManager,
    val parental: ParentalControl,
) {
    private val _localChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    /** Émis quand la progression, les favoris ou les groupes changent, pour les envoyer au compte Vyzo. */
    val localChanges: SharedFlow<Unit> = _localChanges.asSharedFlow()

    private fun changed() {
        _localChanges.tryEmit(Unit)
    }

    val playlists: Flow<List<PlaylistEntity>> = db.playlists().observeAll()

    val activePlaylist: Flow<PlaylistEntity?> =
        combine(playlists, settings.flow.map { it.activePlaylistId }.distinctUntilChanged()) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.distinctUntilChanged()

    suspend fun activePlaylistNow(): PlaylistEntity? = activePlaylist.first()

    /** Playlists affichées : la playlist active, ou toutes si l'option « toutes ensemble » est activée. */
    val activePids: Flow<List<Long>> =
        combine(playlists, settings.flow.map { it.activePlaylistId to it.showAllPlaylists }.distinctUntilChanged()) { list, (id, all) ->
            if (all) list.map { it.id } else listOfNotNull((list.firstOrNull { it.id == id } ?: list.firstOrNull())?.id)
        }.distinctUntilChanged()

    val isStealthActive: Flow<Boolean> =
        combine(settings.flow.map { it.stealthModeEnabled }.distinctUntilChanged(), parental.pinSet, parental.isVaultUnlocked) { stealthPref, pinSet, vaultUnlocked ->
            stealthPref && pinSet && !vaultUnlocked
        }.distinctUntilChanged()

    val viewScope: Flow<ViewScope> =
        combine(activePids, profiles.active, parental.hideLocked, isStealthActive, playlists) { pids, profile, hide, stealth, list ->
            ViewScope(
                pids = pids, profileId = profile?.id.orEmpty(), hide = hide || stealth, kid = profile?.isKid == true,
                stealth = stealth,
                playlistNames = if (pids.size > 1) list.filter { it.id in pids }.associate { it.id to it.name } else emptyMap(),
            )
        }.distinctUntilChanged()

    suspend fun viewScopeNow(): ViewScope = viewScope.first { it.profileId.isNotBlank() }

    private val playlistCache = ConcurrentHashMap<Long, PlaylistEntity>()

    private suspend fun playlist(id: Long): PlaylistEntity =
        db.playlists().get(id)?.also { playlistCache[id] = it } ?: playlistCache[id] ?: throw IOException("Playlist supprimée")

    /** Clé stable d'un contenu (identique sur tous les appareils), ou null si sa playlist est inconnue. */
    suspend fun contentKey(uid: String): String? {
        val pid = PlaylistKeys.playlistIdOf(uid) ?: return null
        val p = db.playlists().get(pid) ?: return null
        return PlaylistKeys.contentKey(uid, pid, PlaylistKeys.of(p))
    }

    // ------------------------------------------------------------ Groupes & chaînes

    private fun categoryTitle(r: CategoryRow, scope: ViewScope): String =
        scope.playlistNames[r.playlistId]?.let { "${r.name} · $it" } ?: r.name

    fun liveGroups(includeHidden: Boolean = false): Flow<List<GroupItem>> = viewScope.flatMapLatest { sc ->
        if (sc.pids.isEmpty()) flowOf(emptyList())
        else combine(db.categories().liveCategories(sc.pids), db.groups().observeGroups(sc.profileId)) { cats, groups ->
            val visible = cats.filter { cat ->
                (includeHidden || !cat.hidden) &&
                    !(sc.kid && cat.locked) &&
                    !(sc.stealth && (cat.locked || cat.adult))
            }
            buildList {
                add(GroupItem(KEY_ALL, "Toutes les chaînes", GroupKind.ALL, visible.filter { !it.hidden && !(sc.hide && it.locked) }.sumOf { it.itemCount }))
                add(GroupItem(KEY_FAVORITES, groups.firstOrNull { it.id == FAVORITES_GROUP_ID }?.name ?: "Favoris", GroupKind.FAVORITES))
                add(GroupItem(KEY_RECENT, "Récentes", GroupKind.RECENT))
                groups.filter { it.id != FAVORITES_GROUP_ID }.forEach { add(GroupItem("ug:${it.id}", it.name, GroupKind.USER)) }
                visible.forEach {
                    add(GroupItem("cat:${it.uid}", categoryTitle(it, sc), GroupKind.CATEGORY, it.itemCount, locked = it.locked && sc.hide))
                }
            }
        }
    }

    /** Catégories de la TV en direct, y compris masquées et verrouillées (écran de personnalisation). */
    fun liveCategoryRows(): Flow<List<CategoryRow>> = viewScope.flatMapLatest { sc ->
        if (sc.pids.isEmpty()) flowOf(emptyList())
        else db.categories().liveCategories(sc.pids).map { l ->
            val filtered = if (sc.stealth) l.filter { cat -> !(cat.locked || cat.adult) } else l
            filtered.map { it.copy(name = categoryTitle(it, sc)) }
        }
    }

    fun channels(groupKey: String): Flow<List<ChannelEntity>> = viewScope.flatMapLatest { sc ->
        if (sc.pids.isEmpty()) flowOf(emptyList())
        else {
            val base = when {
                groupKey == KEY_ALL || groupKey.isBlank() -> db.channels().allVisible(sc.pids, sc.hide)
                groupKey == KEY_RECENT -> db.channels().recent(sc.pids, sc.profileId, sc.hide)
                groupKey.startsWith("ug:") -> db.channels().inUserGroup(groupKey.removePrefix("ug:").toLongOrNull() ?: 0, sc.profileId, sc.pids, sc.hide)
                groupKey.startsWith("cat:") -> db.channels().byCategory(groupKey.removePrefix("cat:"), sc.hide)
                else -> db.channels().allVisible(sc.pids, sc.hide)
            }
            base
        }
    }

    /**
     * Chaînes de sport visibles, dans l'ordre de la playlist : celles des catégories de sport
     * (« FR | SPORTS », « DAZN »…) et celles dont le nom est sportif (« beIN SPORTS 1 », « L'Équipe »…).
     */
    fun sportChannels(): Flow<List<ChannelEntity>> = viewScope.flatMapLatest { sc ->
        if (sc.pids.isEmpty()) flowOf(emptyList())
        else liveCategoryRows()
            .map { rows -> rows.filter { !it.hidden && !(sc.hide && it.locked) && SportDetector.isSportName(it.name) }.map { it.uid } }
            .distinctUntilChanged()
            .mapLatest { cats ->
                withContext(Dispatchers.IO) {
                    // Deux requêtes simples : une longue suite de « OR … LIKE » fait caler la vérification des requêtes de Room.
                    val fromCats = cats.chunked(400).flatMap { db.channels().inCategories(it, sc.hide) }
                    val known = fromCats.mapTo(HashSet()) { it.uid }
                    val byName = db.channels().visibleNames(sc.pids, sc.hide)
                        .filter { it.uid !in known && SportDetector.isSportName(it.name) }
                        .map { it.uid }
                        .chunked(400).flatMap { db.channels().byUids(it) }
                        .filter { SportDetector.isSportName(it.name) }
                    (fromCats + byName).sortedBy { it.position }
                }
            }
    }

    /** Matchs et courses diffusés par [channels] entre [from] et [to], un seul par événement. */
    suspend fun sportEvents(channels: List<ChannelEntity>, from: Long, to: Long): List<SportEvent> = withContext(Dispatchers.IO) {
        val keys = channels.map { it.epgKey }.filter { it.isNotBlank() }.distinct()
        val programs = keys.chunked(400).flatMap { db.programs().window(it, from, to) }
        SportLineup.events(channels, programs)
    }

    /** Toutes les chaînes d'une catégorie, y compris masquées (personnalisation). */
    fun channelsForEditing(categoryUid: String): Flow<List<ChannelEntity>> = db.channels().byCategoryAll(categoryUid)

    suspend fun channel(uid: String): ChannelEntity? = db.channels().get(uid)

    /** Même chaîne en qualité juste inférieure (« TF1 FHD » → « TF1 HD »), null s'il n'y en a pas. */
    suspend fun lowerQuality(ch: ChannelEntity): ChannelEntity? {
        if (ch.matchKey.isBlank()) return null
        val sc = viewScopeNow()
        return QualityVariants.lower(ch, db.channels().sameChannel(ch.playlistId, ch.matchKey, ch.uid, sc.hide))
    }

    /** Chaîne visible portant ce numéro (saisie du numéro au clavier). */
    suspend fun channelByNumber(number: Int): ChannelEntity? {
        val sc = viewScopeNow()
        return db.channels().byNumber(sc.pids, number, sc.hide)
    }

    suspend fun touchChannel(ch: ChannelEntity, groupKey: String) {
        db.progress().touchChannel(ChannelHistoryEntity(profiles.activeIdNow(), ch.uid, System.currentTimeMillis()))
        settings.update { it.copy(lastChannelUid = ch.uid, lastGroupKey = groupKey) }
    }

    // ------------------------------------------------------------ Guide

    suspend fun nowPrograms(keys: Collection<String>, now: Long = System.currentTimeMillis()): Map<String, ProgramEntity> =
        withContext(Dispatchers.IO) {
            val out = HashMap<String, ProgramEntity>()
            keys.filter { it.isNotBlank() }.distinct().chunked(400).forEach { chunk ->
                db.programs().nowFor(chunk, now).forEach { out[it.channelKey] = it }
            }
            out
        }

    suspend fun programs(key: String, from: Long, to: Long): List<ProgramEntity> =
        if (key.isBlank()) emptyList() else db.programs().range(key, from, to)

    suspend fun upcoming(key: String, n: Int = 3): List<ProgramEntity> =
        if (key.isBlank()) emptyList() else db.programs().upcoming(key, System.currentTimeMillis(), n)

    fun canCatchup(ch: ChannelEntity, p: ProgramEntity, now: Long = System.currentTimeMillis()): Boolean {
        if (ch.catchupDays <= 0 || ch.catchupType.isBlank()) return false
        if (p.start >= now) return false
        if (p.start < now - ch.catchupDays * SyncManager.DAY) return false
        if (ch.catchupType == "stalker" && p.remoteId.isBlank()) return false
        return true
    }

    /** Identifiants XMLTV disponibles dans les guides chargés (association manuelle d'une chaîne). */
    suspend fun guideKeys(): List<String> = withContext(Dispatchers.IO) { db.channels().guideKeys() }

    // ------------------------------------------------------------ Résolution des flux

    suspend fun resolveChannel(ch: ChannelEntity): StreamSpec = withContext(Dispatchers.IO) {
        val p = playlist(ch.playlistId)
        val s = settings.get()
        val ua = ch.userAgent.ifBlank { p.userAgent }.ifBlank { s.userAgent }
        when (p.type) {
            SourceType.M3U -> StreamSpec(
                url = ch.streamUrl, userAgent = ua, headers = refererHeaders(ch.referer),
                drmType = ch.drmType, drmKey = ch.drmKey, isLive = true,
            )
            SourceType.XTREAM -> {
                val c = XtreamClient(p.url, p.username, p.password, ua)
                val url = ch.streamUrl.ifBlank { c.liveUrl(ch.remoteId, s.xtreamFormat) }
                StreamSpec(url = url, userAgent = ua, isLive = true, mimeHint = if (s.xtreamFormat == "m3u8") MIME_HLS else null)
            }
            SourceType.STALKER -> {
                val url = sync.stalkerClient(p).createLink("itv", ch.streamUrl)
                StreamSpec(url = url, userAgent = ch.userAgent.ifBlank { p.userAgent }.ifBlank { STALKER_UA }, isLive = true)
            }
        }
    }

    suspend fun resolveCatchup(ch: ChannelEntity, prog: ProgramEntity): StreamSpec? = resolveCatchupAt(ch, prog.start, prog.stop, prog.remoteId)

    /** Replay à partir d'un instant quelconque (pause du direct prolongée au-delà de la mémoire tampon). */
    suspend fun resolveCatchupAt(ch: ChannelEntity, start: Long, stop: Long, remoteId: String = ""): StreamSpec? = withContext(Dispatchers.IO) {
        val p = playlist(ch.playlistId)
        val s = settings.get()
        val ua = ch.userAgent.ifBlank { p.userAgent }.ifBlank { s.userAgent }
        val url = when (p.type) {
            SourceType.XTREAM -> {
                val c = XtreamClient(p.url, p.username, p.password, ua)
                val minutes = ((stop - start + 59_999) / 60_000).toInt().coerceAtLeast(1)
                c.timeshiftUrl(ch.remoteId, start, minutes, p.serverTimezone)
            }
            SourceType.M3U -> CatchupBuilder.build(
                liveUrl = ch.streamUrl, type = ch.catchupType, source = ch.catchupSource,
                startMs = start, stopMs = stop, tz = TimeZone.getDefault(),
            )
            SourceType.STALKER -> remoteId.takeIf { it.isNotBlank() }?.let { sync.stalkerClient(p).archiveLink(it) }
        } ?: return@withContext null
        StreamSpec(
            url = url, userAgent = if (p.type == SourceType.STALKER) p.userAgent.ifBlank { STALKER_UA } else ua,
            headers = refererHeaders(ch.referer), isLive = false,
        )
    }

    suspend fun resolveMovie(m: MovieEntity): StreamSpec = withContext(Dispatchers.IO) {
        val p = playlist(m.playlistId)
        val ua = p.userAgent.ifBlank { settings.get().userAgent }
        val url = when (p.type) {
            SourceType.M3U -> m.streamUrl
            SourceType.XTREAM -> m.streamUrl.ifBlank { XtreamClient(p.url, p.username, p.password, ua).movieUrl(m.remoteId, m.containerExt) }
            SourceType.STALKER -> sync.stalkerClient(p).createLink("vod", m.streamUrl)
        }
        StreamSpec(url = url, userAgent = if (p.type == SourceType.STALKER) p.userAgent.ifBlank { STALKER_UA } else ua, isLive = false)
    }

    suspend fun resolveEpisode(e: EpisodeEntity): StreamSpec = withContext(Dispatchers.IO) {
        val p = playlist(e.playlistId)
        val ua = p.userAgent.ifBlank { settings.get().userAgent }
        val url = when (p.type) {
            SourceType.M3U -> e.streamUrl
            SourceType.XTREAM -> e.streamUrl.ifBlank { XtreamClient(p.url, p.username, p.password, ua).episodeUrl(e.remoteId, e.containerExt) }
            SourceType.STALKER -> sync.stalkerClient(p).createLink("vod", e.streamUrl, e.remoteId)
        }
        StreamSpec(url = url, userAgent = if (p.type == SourceType.STALKER) p.userAgent.ifBlank { STALKER_UA } else ua, isLive = false)
    }

    private fun refererHeaders(referer: String) =
        if (referer.isBlank()) emptyMap() else mapOf("Referer" to referer)

    // ------------------------------------------------------------ Films & séries

    private fun vodCategories(kind: ContentKind): Flow<List<CategoryRow>> = viewScope.flatMapLatest { sc ->
        if (sc.pids.isEmpty()) flowOf(emptyList())
        else (if (kind == ContentKind.MOVIE) db.categories().movieCategories(sc.pids) else db.categories().seriesCategories(sc.pids))
            .map { l ->
                l.filter { cat ->
                    !(sc.kid && cat.locked) &&
                        !(sc.stealth && (cat.locked || cat.adult))
                }.map { it.copy(name = categoryTitle(it, sc), locked = it.locked && sc.hide) }
            }
    }

    /** Catégories de films (verrouillage = « code à demander » pour le profil actif). */
    fun movieCategories(): Flow<List<CategoryRow>> = vodCategories(ContentKind.MOVIE)

    fun seriesCategories(): Flow<List<CategoryRow>> = vodCategories(ContentKind.SERIES)

    /** Catégories de films ou séries telles quelles (écran de personnalisation). */
    fun vodCategoryRows(kind: ContentKind): Flow<List<CategoryRow>> = viewScope.flatMapLatest { sc ->
        if (sc.pids.isEmpty()) flowOf(emptyList())
        else (if (kind == ContentKind.MOVIE) db.categories().movieCategories(sc.pids) else db.categories().seriesCategories(sc.pids))
            .map { l ->
                val filtered = if (sc.stealth) l.filter { cat -> !(cat.locked || cat.adult) } else l
                filtered.map { it.copy(name = categoryTitle(it, sc)) }
            }
    }

    /**
     * Genres automatiques des films ou des séries : genre id → uids des titres (les plus récents d'abord).
     * Voir [Genres] : déduits du genre du titre et du nom de sa catégorie.
     */
    fun vodGenres(kind: ContentKind): Flow<Map<String, List<String>>> = genreIndexes.getOrPut(kind) {
        // Un seul calcul partagé par l'écran Films/Séries et toutes les rangées « genre » de l'accueil :
        // sinon chaque rangée relirait tout le catalogue (15 000 films) et referait le tri par genre.
        // replayExpiration = 0 : rien de périmé (autre profil, contrôle parental) n'est rejoué après un arrêt.
        viewScope.flatMapLatest { sc ->
            if (sc.pids.isEmpty()) flowOf(emptyMap())
            else (if (kind == ContentKind.MOVIE) db.movies().genreSources(sc.pids, sc.hide) else db.series().genreSources(sc.pids, sc.hide))
                .map { Genres.index(it) }
        }.flowOn(Dispatchers.Default)
            .shareIn(cacheScope, SharingStarted.WhileSubscribed(SHARED_KEEP_MS, replayExpirationMillis = 0), replay = 1)
    }

    /** Calculs partagés entre écrans (genres, noms pour la recherche) ; arrêtés quand plus personne ne les écoute. */
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val genreIndexes = ConcurrentHashMap<ContentKind, Flow<Map<String, List<String>>>>()

    /** Titres d'un genre automatique, au plus [limit], dans l'ordre de l'index. */
    private fun <T> genreTitles(
        kind: ContentKind,
        genreId: String,
        limit: Int,
        uidOf: (T) -> String,
        load: suspend (List<String>) -> List<T>,
    ): Flow<List<T>> = vodGenres(kind)
        .map { it[genreId].orEmpty().take(limit) }
        .distinctUntilChanged()
        .mapLatest { uids -> loadInOrder(uids, uidOf, load) }
        .flowOn(Dispatchers.Default)

    /** Charge des titres par lots (limite de variables SQLite), dans l'ordre de [uids]. */
    private suspend fun <T> loadInOrder(uids: List<String>, uidOf: (T) -> String, load: suspend (List<String>) -> List<T>): List<T> {
        val byUid = HashMap<String, T>(uids.size)
        uids.chunked(500).forEach { chunk -> load(chunk).forEach { byUid[uidOf(it)] = it } }
        return uids.mapNotNull { byUid[it] }
    }

    /** Films d'une rangée ou d'une catégorie. [limit] borne les genres automatiques (l'accueil n'en montre que quelques-uns). */
    fun movies(key: String, limit: Int = Int.MAX_VALUE): Flow<List<MovieEntity>> = viewScope.flatMapLatest { sc ->
        if (sc.pids.isEmpty()) flowOf(emptyList())
        else {
            val base = when {
                key == KEY_FAVORITES -> db.movies().inUserGroup(FAVORITES_GROUP_ID, sc.profileId, sc.pids, sc.hide)
                key == KEY_CONTINUE -> db.movies().continueWatching(sc.pids, sc.profileId, sc.hide)
                key == KEY_RECENT_ADDED -> db.movies().recentlyAdded(sc.pids, sc.hide)
                key == KEY_TOP_RATED -> db.movies().topRated(sc.pids, sc.hide)
                key.startsWith(GENRE_PREFIX) ->
                    genreTitles(ContentKind.MOVIE, key.removePrefix(GENRE_PREFIX), limit, MovieEntity::uid) { db.movies().byUids(it) }
                key.startsWith("cat:") -> db.movies().byCategory(key.removePrefix("cat:"), sc.hide)
                else -> flowOf(emptyList())
            }
            base
        }
    }

    /** Séries d'une rangée ou d'une catégorie. [limit] borne les genres automatiques (l'accueil n'en montre que quelques-unes). */
    fun series(key: String, limit: Int = Int.MAX_VALUE): Flow<List<SeriesEntity>> = viewScope.flatMapLatest { sc ->
        if (sc.pids.isEmpty()) flowOf(emptyList())
        else {
            val base = when {
                key == KEY_FAVORITES -> db.series().inUserGroup(FAVORITES_GROUP_ID, sc.profileId, sc.pids, sc.hide)
                key == KEY_CONTINUE -> db.series().continueWatching(sc.pids, sc.profileId, sc.hide)
                key == KEY_RECENT_ADDED -> db.series().recentlyAdded(sc.pids, sc.hide)
                key == KEY_TOP_RATED -> db.series().topRated(sc.pids, sc.hide)
                key.startsWith(GENRE_PREFIX) ->
                    genreTitles(ContentKind.SERIES, key.removePrefix(GENRE_PREFIX), limit, SeriesEntity::uid) { db.series().byUids(it) }
                key.startsWith("cat:") -> db.series().byCategory(key.removePrefix("cat:"), sc.hide)
                else -> flowOf(emptyList())
            }
            base
        }
    }

    private val stalkerPages = ConcurrentHashMap<String, Pair<Int, Boolean>>()

    /** Pour les portails Stalker : charge la page suivante d'une catégorie. Renvoie true s'il en reste. */
    suspend fun loadMoreVod(categoryUid: String): Boolean {
        val cat = db.categories().get(categoryUid) ?: return false
        val p = playlist(cat.playlistId)
        if (p.type != SourceType.STALKER) return false
        val (lastPage, hasMore) = stalkerPages[categoryUid] ?: (0 to true)
        if (!hasMore) return false
        val res = sync.loadStalkerVodPage(p, cat, lastPage + 1)
        stalkerPages[categoryUid] = (lastPage + 1) to res.hasMore
        return res.hasMore
    }

    suspend fun isStalkerCategory(categoryUid: String): Boolean {
        val cat = db.categories().get(categoryUid) ?: return false
        return playlist(cat.playlistId).type == SourceType.STALKER
    }

    suspend fun refreshMovieDetails(m: MovieEntity) {
        val p = playlist(m.playlistId)
        if (p.type == SourceType.XTREAM && m.plot.isBlank()) runCatching { sync.loadXtreamMovieDetails(p, m) }
    }

    suspend fun refreshEpisodes(s: SeriesEntity) {
        val p = playlist(s.playlistId)
        when (p.type) {
            SourceType.XTREAM -> sync.loadXtreamEpisodes(p, s)
            SourceType.STALKER -> if (db.series().episodesList(s.uid).isEmpty()) sync.loadStalkerEpisodes(p, s)
            SourceType.M3U -> Unit
        }
    }

    /** Identifiant TMDB donné par le fournisseur Xtream, null s'il n'en publie pas. */
    suspend fun seriesTmdbId(s: SeriesEntity): Int? {
        val p = playlist(s.playlistId)
        if (p.type != SourceType.XTREAM) return null
        return runCatching { sync.xtreamSeriesTmdbId(p, s) }.getOrNull()
    }

    // ------------------------------------------------------------ Progression

    /** Avancement de tout ce que le profil a commencé, pour les cartes « Reprendre ». */
    fun resumeFractions(): Flow<Map<String, Float>> =
        profiles.activeId.flatMapLatest { db.progress().observeForProfile(it) }.map { resumeFractions(it) }

    /** De quoi afficher le badge 4K : noms des sections et définitions déjà relevées. */
    fun vodQuality(): Flow<VodQualityIndex> =
        combine(movieCategories(), seriesCategories(), db.streamQuality().observeAll()) { movies, series, measured ->
            VodQualityIndex((movies + series).filter { announcesUhd(it.name) }.mapTo(HashSet()) { it.uid }, measured.associateBy { it.uid })
        }

    fun progress(uid: String): Flow<WatchProgressEntity?> = profiles.activeId.flatMapLatest { db.progress().observe(it, uid) }
    fun seriesProgress(seriesUid: String): Flow<List<WatchProgressEntity>> =
        profiles.activeId.flatMapLatest { db.progress().forSeries(it, seriesUid) }
    suspend fun progressNow(uid: String) = db.progress().get(profiles.activeIdNow(), uid)
    suspend fun lastEpisodeProgress(seriesUid: String) = db.progress().lastForSeries(profiles.activeIdNow(), seriesUid)

    suspend fun saveWatchProgress(
        uid: String,
        kind: ContentKind,
        parentUid: String,
        profileId: String,
        positionMs: Long,
        durationMs: Long,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        if (durationMs <= 0) return
        val pid = PlaylistKeys.playlistIdOf(uid) ?: return
        val key = PlaylistKeys.of(playlist(pid))
        val contentKey = PlaylistKeys.contentKey(uid, pid, key) ?: return
        val profId = profileId.ifBlank { profiles.activeIdNow() }
        db.progress().upsert(
            WatchProgressEntity(
                profileId = profId,
                contentKey = contentKey,
                uid = uid,
                kind = kind,
                parentUid = parentUid,
                parentKey = PlaylistKeys.contentKey(parentUid, pid, key).orEmpty(),
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = updatedAt,
                dirty = true,
            ),
        )
        changed()
    }

    suspend fun saveProgress(uid: String, kind: ContentKind, parentUid: String, positionMs: Long, durationMs: Long) {
        saveWatchProgress(uid, kind, parentUid, profiles.activeIdNow(), positionMs, durationMs)
    }

    /** Position enregistrée hors de l'écran de lecture (mini-lecteur), d'après l'étiquette de ce que lit le lecteur. */
    suspend fun savePlayerPosition(tag: String?, positionMs: Long, durationMs: Long) {
        when {
            tag == null -> Unit
            tag.startsWith("movie:") -> saveProgress(tag.removePrefix("movie:"), ContentKind.MOVIE, "", positionMs, durationMs)
            tag.startsWith("episode:") -> db.series().episode(tag.removePrefix("episode:"))?.let {
                saveProgress(it.uid, ContentKind.SERIES, it.seriesUid, positionMs, durationMs)
            }
        }
    }

    /** Marque un contenu comme non commencé (la remise à zéro est aussi envoyée aux autres appareils). */
    suspend fun clearProgress(uid: String) {
        val cur = db.progress().get(profiles.activeIdNow(), uid) ?: return
        db.progress().upsert(cur.copy(positionMs = 0, updatedAt = System.currentTimeMillis(), dirty = true))
        changed()
    }

    /**
     * Purge l'historique de visionnage (chaînes récentes et reprises de lecture).
     * Retourne la liste des clés de contenu affectées (pour nettoyage Supabase ciblé).
     */
    suspend fun clearWatchHistory(
        profileId: String? = null,
        adultOnly: Boolean = false,
        categoryUid: String? = null,
        keyword: String? = null,
        itemUid: String? = null,
    ): List<String> = db.withTransaction {
        val targetProfile = profileId ?: profiles.activeIdNow()
        val affectedKeys = when {
            adultOnly -> {
                val keys = db.progress().clearAdultHistory(targetProfile).toMutableList()
                val hist = db.progress().history(targetProfile)
                val adultUids = ArrayList<String>()
                for (h in hist) {
                    val ch = db.channels().get(h.channelUid)
                    val cat = ch?.categoryUid?.let { db.categories().get(it) }
                    val pref = ch?.categoryUid?.let { db.categories().pref(it) }
                    val isAdult = (cat?.adult == true) ||
                        (pref?.locked == true) ||
                        (cat != null && AdultDetector.isAdultCategory(cat.name))
                    if (isAdult) {
                        adultUids += h.channelUid
                    }
                }
                if (adultUids.isNotEmpty()) {
                    db.progress().deleteChannelHistoryByUids(adultUids, targetProfile)
                }

                val progs = db.progress().forProfile(targetProfile)
                val adultProgressKeys = ArrayList<String>()
                for (p in progs) {
                    val m = db.movies().get(p.uid)
                    val s = if (p.parentUid.isNotBlank()) db.series().get(p.parentUid) else null
                    val catUid = m?.categoryUid ?: s?.categoryUid
                    val cat = catUid?.let { db.categories().get(it) }
                    val isAdult = (cat?.adult == true) ||
                        (cat != null && AdultDetector.isAdultCategory(cat.name))
                    if (isAdult) {
                        adultProgressKeys += p.contentKey
                    }
                }
                if (adultProgressKeys.isNotEmpty()) {
                    db.progress().deleteWatchProgressByKeys(adultProgressKeys, targetProfile)
                    keys.addAll(adultProgressKeys)
                }

                val active = targetProfile == profiles.activeIdNow()
                settings.update { s -> s.forgetLastChannel(targetProfile, active) { it in adultUids } }

                keys.distinct()
            }
            categoryUid != null -> db.progress().clearHistoryByCategory(categoryUid, targetProfile)
            keyword != null -> db.progress().clearHistoryByKeyword(keyword, targetProfile)
            itemUid != null -> db.progress().clearHistoryByItem(itemUid, targetProfile)
            else -> {
                db.progress().clearWatchHistory(targetProfile)
                val active = targetProfile == profiles.activeIdNow()
                settings.update { s -> s.forgetLastChannel(targetProfile, active) { true } }
                emptyList()
            }
        }
        changed()
        affectedKeys
    }

    /** Marque un contenu comme vu (fin atteinte). */
    suspend fun markWatched(uid: String, kind: ContentKind, parentUid: String, durationMs: Long) {
        val d = durationMs.takeIf { it > 0 } ?: progressNow(uid)?.durationMs?.takeIf { it > 0 } ?: 1
        saveProgress(uid, kind, parentUid, d, d)
    }

    /** Rattache aux playlists locales les progressions et favoris reçus d'autres appareils (ou d'une ancienne playlist). */
    suspend fun attachToPlaylists() {
        db.playlists().all().forEach { p -> attach(p) }
    }

    private suspend fun attach(p: PlaylistEntity, key: String = PlaylistKeys.of(p)) {
        db.progress().attach("$key:", "${p.id}:")
        db.groups().attach("$key:", "${p.id}:")
    }

    // ------------------------------------------------------------ Favoris & groupes perso (par profil)

    fun userGroups(): Flow<List<UserGroupEntity>> = profiles.activeId.flatMapLatest { db.groups().observeGroups(it) }
    fun groupsOf(uid: String): Flow<List<Long>> = profiles.activeId.flatMapLatest { db.groups().groupsOf(it, uid) }
    fun isFavorite(uid: String): Flow<Boolean> = groupsOf(uid).map { FAVORITES_GROUP_ID in it }

    suspend fun setInGroup(groupId: Long, uid: String, kind: ContentKind, inGroup: Boolean) {
        val profileId = profiles.activeIdNow()
        val key = contentKey(uid) ?: return
        val group = db.groups().group(groupId) ?: return
        if (inGroup) {
            if (db.groups().item(profileId, groupId, key) != null) return
            db.groups().upsertItem(
                UserGroupItemEntity(profileId, groupId, key, uid, kind, db.groups().maxPosition(profileId, groupId) + 1),
            )
            db.tombstones().remove(TOMB_ITEM, profileId, itemTombKey(group.syncId, key))
        } else {
            db.groups().removeItem(profileId, groupId, key)
            db.tombstones().add(SyncTombstoneEntity(TOMB_ITEM, profileId, itemTombKey(group.syncId, key)))
        }
        changed()
    }

    suspend fun toggleFavorite(uid: String, kind: ContentKind) {
        val inFav = FAVORITES_GROUP_ID in db.groups().groupsOfNow(profiles.activeIdNow(), uid)
        setInGroup(FAVORITES_GROUP_ID, uid, kind, !inFav)
    }

    suspend fun createGroup(name: String): Long {
        val profileId = profiles.activeIdNow()
        val id = db.groups().insertGroup(
            UserGroupEntity(
                name = name.trim(), position = db.groups().maxGroupPosition(profileId) + 1,
                profileId = profileId, syncId = UUID.randomUUID().toString(),
            ),
        )
        changed()
        return id
    }

    suspend fun renameGroup(id: Long, name: String) {
        val g = db.groups().group(id) ?: return
        db.groups().updateGroup(g.copy(name = name.trim(), updatedAt = System.currentTimeMillis(), dirty = id != FAVORITES_GROUP_ID))
        changed()
    }

    suspend fun deleteGroup(id: Long) {
        if (id == FAVORITES_GROUP_ID) return
        val g = db.groups().group(id) ?: return
        db.withTransaction {
            db.groups().clearGroup(id)
            db.groups().deleteGroup(id)
            db.tombstones().add(SyncTombstoneEntity(TOMB_GROUP, g.profileId, g.syncId))
        }
        changed()
    }

    // ------------------------------------------------------------ Personnalisation des catégories

    private suspend fun updateCategoryPref(uid: String, transform: (CategoryPrefEntity) -> CategoryPrefEntity) {
        val cur = db.categories().pref(uid) ?: CategoryPrefEntity(uid)
        db.categories().upsertPrefs(listOf(transform(cur).copy(uid = uid)))
    }

    suspend fun setCategoryHidden(uid: String, hidden: Boolean) = updateCategoryPref(uid) { it.copy(hidden = hidden) }

    /** true = verrouillée, false = jamais, null = automatique (selon la détection « adulte »). */
    suspend fun setCategoryLocked(uid: String, locked: Boolean?) = updateCategoryPref(uid) { it.copy(locked = locked) }

    suspend fun renameCategory(uid: String, name: String) = updateCategoryPref(uid) { it.copy(name = name.trim()) }

    /** Enregistre un ordre personnalisé pour toutes les catégories passées. */
    suspend fun reorderCategories(rows: List<CategoryRow>) {
        db.withTransaction {
            val prefs = rows.mapIndexed { i, r -> (db.categories().pref(r.uid) ?: CategoryPrefEntity(r.uid, r.hidden)).copy(position = i) }
            db.categories().upsertPrefs(prefs)
        }
        changed()
    }

    suspend fun updateCategoryPosition(uid: String, newPosition: Int) {
        db.categories().updateGroupPosition(uid, newPosition)
        changed()
    }

    suspend fun moveCategory(kind: ContentKind, categoryUid: String, delta: Int) {
        val rows = when (kind) {
            ContentKind.LIVE -> liveCategoryRows().first()
            ContentKind.MOVIE -> vodCategoryRows(ContentKind.MOVIE).first()
            ContentKind.SERIES -> vodCategoryRows(ContentKind.SERIES).first()
        }
        val currentIndex = rows.indexOfFirst { it.uid == categoryUid }
        if (currentIndex < 0) return
        val targetIndex = currentIndex + delta
        if (targetIndex !in rows.indices) return
        val mutable = rows.toMutableList()
        val item = mutable.removeAt(currentIndex)
        mutable.add(targetIndex, item)
        reorderCategories(mutable)
    }

    suspend fun reorderUserGroups(groups: List<UserGroupEntity>) {
        db.withTransaction {
            val now = System.currentTimeMillis()
            groups.forEachIndexed { i, g ->
                db.groups().updateGroupPosition(g.id, i, now)
            }
        }
        changed()
    }

    suspend fun moveUserGroup(id: Long, delta: Int) {
        val groups = userGroups().first().filter { it.id != FAVORITES_GROUP_ID }
        val currentIndex = groups.indexOfFirst { it.id == id }
        if (currentIndex < 0) return
        val targetIndex = currentIndex + delta
        if (targetIndex !in groups.indices) return
        val mutable = groups.toMutableList()
        val item = mutable.removeAt(currentIndex)
        mutable.add(targetIndex, item)
        reorderUserGroups(mutable)
    }

    // ------------------------------------------------------------ Personnalisation des chaînes

    /** Modifie la personnalisation d'une chaîne et l'applique tout de suite à la liste. */
    suspend fun updateChannelPref(uid: String, transform: (ChannelPrefEntity) -> ChannelPrefEntity) {
        val ch = db.channels().get(uid) ?: return
        val cur = db.channelPrefs().get(uid) ?: ChannelPrefEntity(uid)
        val next = transform(cur).copy(uid = uid)
        val epgChanged = next.epgKey != cur.epgKey
        db.withTransaction {
            if (next == ChannelPrefEntity(uid)) db.channelPrefs().delete(uid) else db.channelPrefs().upsert(next)
            db.channels().upsert(listOf(ChannelPrefs.apply(ChannelPrefs.original(ch), next)))
        }
        if (epgChanged) {
            db.channels().resetEpgKeys()
            db.channels().remapEpgKeysByName()
            db.channels().applyEpgOverrides()
        }
    }

    suspend fun renameChannel(uid: String, name: String) = updateChannelPref(uid) { it.copy(name = name.trim()) }
    suspend fun setChannelNumber(uid: String, number: Int) = updateChannelPref(uid) { it.copy(number = number.coerceAtLeast(0)) }
    suspend fun setChannelHidden(uid: String, hidden: Boolean) = updateChannelPref(uid) { it.copy(hidden = hidden) }
    suspend fun setChannelLocked(uid: String, locked: Boolean) = updateChannelPref(uid) { it.copy(locked = locked) }
    suspend fun setChannelEpg(uid: String, epgKey: String) = updateChannelPref(uid) { it.copy(epgKey = epgKey.trim().lowercase()) }
    suspend fun resetChannel(uid: String) = updateChannelPref(uid) { ChannelPrefEntity(uid) }

    // ------------------------------------------------------------ Recherche

    suspend fun search(query: String): SearchResults = withContext(Dispatchers.IO) {
        val q = TextNorm.search(query)
        val sc = viewScopeNow()
        if (sc.pids.isEmpty() || q.length < 2) return@withContext SearchResults()
        // Le contenu verrouillé (chaînes, catégories choisies ou détectées « adultes ») est exclu par les requêtes.
        val channels = withFuzzy(db.channels().search(sc.pids, q, MAX_SEARCH_CHANNELS, sc.hide), q, ChannelEntity::uid, 30,
            fuzzyNames(NameTable.CHANNELS, sc), { db.channels().byUids(it) })
        val movies = withFuzzy(db.movies().search(sc.pids, q, 60, sc.hide), q, MovieEntity::uid, 30,
            fuzzyNames(NameTable.MOVIES, sc), { db.movies().byUids(it) })
        val series = withFuzzy(db.series().search(sc.pids, q, 60, sc.hide), q, SeriesEntity::uid, 30,
            fuzzyNames(NameTable.SERIES, sc), { db.series().byUids(it) })
        val now = System.currentTimeMillis()
        val progs = db.programs().search(query.trim(), now - 3 * 3600_000L, 80).mapNotNull { pr ->
            db.channels().byEpgKey(sc.pids, pr.channelKey, sc.hide)?.let { pr to it }
        }
        SearchResults(channels, movies, series, progs.take(40))
    }

    /**
     * Complète une recherche exacte qui trouve peu de résultats par une recherche tolérante aux fautes de frappe
     * (« harry poter », « spiderman », « gladiateur »). Les résultats exacts restent en tête.
     */
    private suspend fun <T> withFuzzy(
        exact: List<T>,
        q: String,
        uidOf: (T) -> String,
        limit: Int,
        names: Flow<List<FuzzyMatch.Entry<String>>>,
        load: suspend (List<String>) -> List<T>,
    ): List<T> {
        if (exact.size >= FUZZY_BELOW || q.length < 3) return exact
        // Une nouvelle lettre tapée annule la recherche en cours : inutile de finir la comparaison.
        currentCoroutineContext().ensureActive()
        val known = exact.mapTo(HashSet()) { uidOf(it) }
        val uids = withContext(Dispatchers.Default) { FuzzyMatch.rankPrepared(q, names.first(), limit) }.filter { it !in known }
        if (uids.isEmpty()) return exact
        return exact + loadInOrder(uids, uidOf, load)
    }

    private enum class NameTable { CHANNELS, MOVIES, SERIES }
    private data class NamesKey(val table: NameTable, val pids: List<Long>, val hide: Boolean)
    private val fuzzyNameCache = ConcurrentHashMap<NamesKey, Flow<List<FuzzyMatch.Entry<String>>>>()

    /**
     * Noms visibles, déjà découpés pour la recherche tolérante. Gardés quelques minutes après la dernière recherche,
     * pour ne pas relire et redécouper ~35 000 noms à chaque lettre. Room les réémet dès que la table ou un verrou
     * (contrôle parental) change : le cache ne montre jamais un contenu verrouillé depuis.
     */
    private fun fuzzyNames(table: NameTable, sc: ViewScope): Flow<List<FuzzyMatch.Entry<String>>> =
        fuzzyNameCache.getOrPut(NamesKey(table, sc.pids, sc.hide)) {
            when (table) {
                NameTable.CHANNELS -> db.channels().observeSearchNames(sc.pids, sc.hide)
                NameTable.MOVIES -> db.movies().observeSearchNames(sc.pids, sc.hide)
                NameTable.SERIES -> db.series().observeSearchNames(sc.pids, sc.hide)
            }.map { rows -> rows.map { FuzzyMatch.prepare(it.uid, it.name) } }
                .flowOn(Dispatchers.Default)
                .shareIn(cacheScope, SharingStarted.WhileSubscribed(FUZZY_KEEP_MS, replayExpirationMillis = 0), replay = 1)
        }

    /** Films visibles du catalogue qui correspondent à [titles], dans le même ordre. */
    suspend fun moviesMatching(titles: List<TmdbTitle>, limit: Int = 30): List<MovieEntity> {
        val sc = viewScopeNow()
        return matchCatalog(titles, limit, { db.movies().matchingName(sc.pids, sc.hide, it) }, { it.name }, { it.yearInt }, { it.uid })
    }

    /** Séries visibles du catalogue qui correspondent à [titles], dans le même ordre. */
    suspend fun seriesMatching(titles: List<TmdbTitle>, limit: Int = 30): List<SeriesEntity> {
        val sc = viewScopeNow()
        return matchCatalog(titles, limit, { db.series().matchingName(sc.pids, sc.hide, it) }, { it.name }, { it.yearInt }, { it.uid })
    }

    private suspend fun <T> matchCatalog(
        titles: List<TmdbTitle>,
        limit: Int,
        candidates: suspend (String) -> List<T>,
        name: (T) -> String,
        year: (T) -> Int,
        uid: (T) -> String,
    ): List<T> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, T>()
        for (title in titles) {
            if (found.size >= limit) break
            val match = listOf(title.title, title.originalTitle).map { TextNorm.search(it) }.filter { it.length >= 2 }.distinct()
                .firstNotNullOfOrNull { norm -> candidates("%$norm%").firstOrNull { sameTitle(name(it), year(it), title) } }
            if (match != null) found.putIfAbsent(uid(match), match)
        }
        found.values.toList()
    }

    suspend fun findContentByPerson(name: String): Pair<List<MovieEntity>, List<SeriesEntity>> = withContext(Dispatchers.IO) {
        val person = name.trim()
        if (person.length < 2) return@withContext emptyList<MovieEntity>() to emptyList<SeriesEntity>()
        val sc = viewScopeNow()
        if (sc.pids.isEmpty()) return@withContext emptyList<MovieEntity>() to emptyList<SeriesEntity>()
        db.movies().findByPerson(sc.pids, person, 50, sc.hide) to db.series().findByPerson(sc.pids, person, 50, sc.hide)
    }

    // ------------------------------------------------------------ Playlists & guides

    suspend fun addPlaylist(p: PlaylistEntity): Long {
        val id = db.playlists().insert(p.copy(position = db.playlists().all().size))
        attach(p.copy(id = id))
        val s = settings.get()
        if (s.activePlaylistId == 0L || db.playlists().get(s.activePlaylistId) == null) {
            settings.update { it.copy(activePlaylistId = id) }
        }
        return id
    }

    suspend fun updatePlaylist(p: PlaylistEntity) {
        val old = db.playlists().get(p.id)
        db.playlists().update(p)
        sync.forgetClient(p.id)
        val oldKey = old?.let { PlaylistKeys.of(it) }
        val newKey = PlaylistKeys.of(p)
        if (oldKey != null && oldKey != newKey) rekey(oldKey, newKey)
        attach(p, newKey)
    }

    /** La source d'une playlist a changé (adresse M3U, identifiant…) : progression et favoris suivent sous la nouvelle clé. */
    private suspend fun rekey(oldKey: String, newKey: String) {
        fun swap(k: String) = if (k.startsWith("$oldKey:")) newKey + k.substring(oldKey.length) else k
        db.withTransaction {
            db.progress().forPlaylistKey("$oldKey:").forEach { r ->
                val moved = r.copy(contentKey = swap(r.contentKey), parentKey = swap(r.parentKey), dirty = true)
                val existing = db.progress().byKey(r.profileId, moved.contentKey)
                if (existing == null || existing.updatedAt < r.updatedAt) db.progress().upsert(moved)
            }
            db.groups().itemsForPlaylistKey("$oldKey:").forEach { r ->
                val moved = r.copy(contentKey = swap(r.contentKey), updatedAt = System.currentTimeMillis(), dirty = true)
                db.groups().upsertItem(moved)
            }
        }
        changed()
    }

    suspend fun deletePlaylist(id: Long) {
        sync.deletePlaylistData(id)
        // Progression et favoris sont gardés (sous leur clé de contenu) : ils reviennent si la playlist est rajoutée.
        db.progress().detach("$id:")
        db.groups().detach("$id:")
        db.playlists().delete(id)
        val s = settings.get()
        if (s.activePlaylistId == id) {
            val next = db.playlists().all().firstOrNull()?.id ?: 0
            settings.update { it.copy(activePlaylistId = next) }
        }
    }

    /** Retire une source dont le tout premier chargement a échoué. Une source déjà chargée (échec du guide seul) reste. */
    suspend fun discardNeverLoadedPlaylist(id: Long, epgSourceId: Long? = null) {
        if (db.playlists().get(id)?.lastSync != 0L) return
        deletePlaylist(id)
        epgSourceId?.let { db.epg().deleteSource(it) }
    }

    suspend fun setActivePlaylist(id: Long) = settings.update { it.copy(activePlaylistId = id) }

    fun epgSources() = db.epg().observeSources()

    suspend fun addEpgSource(name: String, url: String) =
        db.epg().insertSource(EpgSourceEntity(name = name.ifBlank { "Guide perso" }, url = url.trim()))

    suspend fun setEpgSourceEnabled(src: EpgSourceEntity, enabled: Boolean) = db.epg().updateSource(src.copy(enabled = enabled))

    suspend fun deleteEpgSource(src: EpgSourceEntity) {
        db.epg().deleteSource(src.id)
        db.programs().deleteSource("src:${src.id}")
    }

    companion object {
        const val MAX_SEARCH_CHANNELS = 200
        /** En dessous de ce nombre de résultats exacts, la recherche tolère aussi les fautes de frappe. */
        const val FUZZY_BELOW = 8
        /** Durée pendant laquelle un calcul partagé (genres) reste actif après le départ du dernier écran. */
        private const val SHARED_KEEP_MS = 30_000L
        /** Durée pendant laquelle les noms de la recherche tolérante restent en mémoire après la dernière recherche. */
        private const val FUZZY_KEEP_MS = 3 * 60_000L
        const val KEY_ALL = "all"
        const val KEY_FAVORITES = "ug:$FAVORITES_GROUP_ID"
        const val KEY_RECENT = "recent"
        const val KEY_CONTINUE = "continue"
        const val KEY_RECENT_ADDED = "added"
        const val KEY_TOP_RATED = "top"
        /** Genre automatique : « genre:action », « genre:comedie »… (voir [Genres]). */
        const val GENRE_PREFIX = "genre:"
        const val MIME_HLS = "application/x-mpegURL"
        const val STALKER_UA =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

        /** Types de suppressions envoyées au compte Vyzo. */
        const val TOMB_ITEM = "item"
        const val TOMB_GROUP = "group"

        fun itemTombKey(listSyncId: String, contentKey: String) = "$listSyncId|$contentKey"
    }
}

/** Application des personnalisations de chaîne (nom, numéro, masquage, verrouillage) sur une ligne de la playlist. */
object ChannelPrefs {
    /** La chaîne telle que fournie par la playlist (sans personnalisation). */
    fun original(ch: ChannelEntity): ChannelEntity = ch.copy(
        name = ch.origName.ifBlank { ch.name },
        nameNorm = TextNorm.search(ch.origName.ifBlank { ch.name }),
        number = if (ch.origNumber > 0) ch.origNumber else ch.number,
        origName = "", origNumber = 0, hidden = false, locked = false,
    )

    fun apply(ch: ChannelEntity, p: ChannelPrefEntity?): ChannelEntity {
        if (p == null) return ch
        val rename = p.name.isNotBlank() && p.name != ch.name
        val renumber = p.number > 0 && p.number != ch.number
        return ch.copy(
            name = if (rename) p.name else ch.name,
            nameNorm = if (rename) TextNorm.search(p.name) else ch.nameNorm,
            origName = if (rename) ch.name else "",
            number = if (renumber) p.number else ch.number,
            origNumber = if (renumber) ch.number else 0,
            hidden = p.hidden,
            locked = p.locked,
            epgKey = p.epgKey.ifBlank { ch.epgKey },
        )
    }
}
