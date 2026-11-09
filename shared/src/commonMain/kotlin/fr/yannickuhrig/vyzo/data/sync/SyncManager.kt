package fr.yannickuhrig.vyzo.data.sync

import fr.yannickuhrig.vyzo.platform.Platform
import fr.yannickuhrig.vyzo.util.Log
import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.CategoryEntity
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ChannelPrefEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.EpgNameMapEntity
import fr.yannickuhrig.vyzo.data.db.EpgSourceEntity
import fr.yannickuhrig.vyzo.data.db.EpisodeEntity
import fr.yannickuhrig.vyzo.data.db.MovieEntity
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import fr.yannickuhrig.vyzo.data.db.StreamQualityEntity
import fr.yannickuhrig.vyzo.data.db.record
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.epg.XmltvParser
import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.net.redactCredentials
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.data.repo.ChannelPrefs
import fr.yannickuhrig.vyzo.data.source.m3u.M3uClassifier
import fr.yannickuhrig.vyzo.data.source.m3u.M3uHeader
import fr.yannickuhrig.vyzo.data.source.m3u.M3uKind
import fr.yannickuhrig.vyzo.data.source.m3u.M3uParser
import fr.yannickuhrig.vyzo.data.source.stalker.StalkerClient
import fr.yannickuhrig.vyzo.data.source.stalker.StalkerVodParser
import fr.yannickuhrig.vyzo.data.source.xtream.XtreamClient
import fr.yannickuhrig.vyzo.data.source.xtream.XtreamVodParser
import fr.yannickuhrig.vyzo.util.AdultDetector
import fr.yannickuhrig.vyzo.util.TextNorm
import fr.yannickuhrig.vyzo.util.arr
import fr.yannickuhrig.vyzo.util.bool
import fr.yannickuhrig.vyzo.util.float
import fr.yannickuhrig.vyzo.util.int
import fr.yannickuhrig.vyzo.util.long
import fr.yannickuhrig.vyzo.util.plural
import fr.yannickuhrig.vyzo.util.prim
import fr.yannickuhrig.vyzo.util.shortHash
import fr.yannickuhrig.vyzo.util.str
import fr.yannickuhrig.vyzo.util.strOrFirst
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

data class SyncState(
    val running: Boolean = false,
    val message: String = "",
    val error: String? = null,
    val lastFinished: Long = 0,
)

/** Synchronise playlists (M3U, Xtream, Stalker) et guides TV vers la base locale. */
class SyncManager(
    private val platform: Platform,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()
    private val mutex = Mutex()
    private val stalkerClients = ConcurrentHashMap<Long, StalkerClient>()

    /** Appelé après chaque mise à jour du guide (enregistrements de série…). */
    @Volatile var afterEpg: (suspend () -> Unit)? = null

    /** Personnalisations des chaînes de la playlist en cours de synchro. */
    @Volatile private var prefs: Map<String, ChannelPrefEntity> = emptyMap()

    private suspend fun upsertChannels(list: List<ChannelEntity>) {
        val p = prefs
        db.channels().upsert(if (p.isEmpty()) list else list.map { ChannelPrefs.apply(it, p[it.uid]) })
    }

    /** Marque « adultes » les catégories détectées comme telles (si l'option est activée). */
    private suspend fun upsertCategories(cats: List<CategoryEntity>, adultIds: Set<String> = emptySet()) {
        val auto = settings.get().parentalAutoAdult
        db.categories().upsert(cats.map { it.copy(adult = auto && (it.uid in adultIds || AdultDetector.isAdultCategory(it.name))) })
    }

    fun stalkerClient(p: PlaylistEntity): StalkerClient =
        stalkerClients.getOrPut(p.id) { StalkerClient(p.url, p.mac) }.let { c ->
            if (c.mac != p.mac.trim().uppercase()) StalkerClient(p.url, p.mac).also { stalkerClients[p.id] = it } else c
        }

    fun forgetClient(playlistId: Long) {
        stalkerClients.remove(playlistId)
    }

    private fun report(msg: String) {
        _state.value = _state.value.copy(running = true, message = msg, error = null)
    }

    fun launchPlaylist(playlistId: Long, withEpg: Boolean = true) = scope.launch { syncPlaylist(playlistId, withEpg) }
    fun launchAll() = scope.launch { syncAll() }
    fun launchEpg() = scope.launch {
        mutex.withLock { runStep { syncEpgLocked() } }
    }

    suspend fun syncAll() = mutex.withLock {
        runStep {
            for (p in db.playlists().all()) {
                runCatching { syncPlaylistLocked(p) }.onFailure { Log.w(TAG, "sync ${p.name}", it) }
            }
            syncEpgLocked()
            settings.update { it.copy(lastFullSync = System.currentTimeMillis()) }
        }
    }

    suspend fun syncPlaylist(playlistId: Long, withEpg: Boolean = true): Result<Unit> = mutex.withLock {
        val p = db.playlists().get(playlistId) ?: return@withLock Result.failure(IOException("Playlist introuvable"))
        runStep {
            syncPlaylistLocked(p)
            if (withEpg) syncEpgLocked()
            if (withEpg && db.playlists().all().size == 1) settings.update { it.copy(lastFullSync = System.currentTimeMillis()) }
        }
    }

    private suspend fun runStep(block: suspend () -> Unit): Result<Unit> {
        _state.value = SyncState(running = true, message = "Préparation…")
        return try {
            withContext(Dispatchers.IO) { block() }
            _state.value = SyncState(running = false, message = "Terminé", lastFinished = System.currentTimeMillis())
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "sync", e)
            _state.value = SyncState(running = false, error = e.message?.redactCredentials() ?: e.toString(), lastFinished = System.currentTimeMillis())
            Result.failure(e)
        }
    }

    private suspend fun syncPlaylistLocked(p: PlaylistEntity) {
        val token = System.currentTimeMillis()
        report("${p.name} : connexion…")
        try {
            prefs = db.channelPrefs().forPrefix("${p.id}:").associateBy { it.uid }
            when (p.type) {
                SourceType.M3U -> syncM3u(p, token)
                SourceType.XTREAM -> syncXtream(p, token)
                SourceType.STALKER -> syncStalker(p, token)
            }
            db.channels().resetEpgKeys()
            db.channels().remapEpgKeysByName()
            db.channels().applyEpgOverrides()
            db.playlists().setSyncResult(p.id, System.currentTimeMillis(), "")
        } catch (e: Exception) {
            db.playlists().setSyncResult(p.id, p.lastSync, e.message?.redactCredentials() ?: e.toString())
            throw e
        } finally {
            prefs = emptyMap()
        }
    }

    /**
     * Interroge le fournisseur sur l'état du compte (expiration, connexions en cours).
     * Renvoie la playlist à jour, ou null si l'information n'est pas disponible pour ce type de source.
     */
    suspend fun checkAccount(p: PlaylistEntity): PlaylistEntity? = withContext(Dispatchers.IO) {
        when (p.type) {
            SourceType.XTREAM -> {
                val acc = XtreamClient(p.url, p.username, p.password, p.userAgent.ifBlank { settings.get().userAgent }).auth()
                db.playlists().setAccountInfo(p.id, acc.expiresAt, acc.maxConnections, acc.activeConnections, acc.status, System.currentTimeMillis())
            }
            SourceType.STALKER -> {
                val end = stalkerClient(p).accountExpiry()
                val ms = parseStalkerDate(end)
                if (ms <= 0) return@withContext null
                db.playlists().setAccountInfo(p.id, ms, p.maxConnections, p.activeConnections, p.accountStatus, System.currentTimeMillis())
            }
            SourceType.M3U -> return@withContext null
        }
        db.playlists().get(p.id)
    }

    // ---------------------------------------------------------------- M3U

    private fun openSource(url: String, userAgent: String, block: (InputStream) -> Unit) {
        if (isLocalSource(url)) {
            platform.openInput(url)?.use { block(Http.maybeGunzip(it)) }
                ?: throw IOException("Fichier illisible")
        } else {
            runBlocking {
                Http.execute(client = Http.streamingClient, request = Http.request(url, userAgent)) { resp ->
                    block(Http.maybeGunzip(resp.body!!.byteStream()))
                }
            }
        }
    }

    private suspend fun syncM3u(p: PlaylistEntity, token: Long) {
        val ua = p.userAgent.ifBlank { settings.get().userAgent }
        val cats = LinkedHashMap<String, CategoryEntity>()
        val series = LinkedHashMap<String, SeriesEntity>()
        val episodeCount = HashMap<String, Int>()
        val dup = HashMap<String, Int>()
        val chBuf = ArrayList<ChannelEntity>(BATCH)
        val mvBuf = ArrayList<MovieEntity>(BATCH)
        val epBuf = ArrayList<EpisodeEntity>(BATCH)
        var header = M3uHeader(emptyMap())
        var live = 0
        var movies = 0
        var total = 0

        fun category(kind: ContentKind, name: String): String {
            val uid = "${p.id}:$kind:${name.shortHash()}"
            cats.getOrPut(uid) { CategoryEntity(uid, p.id, kind, name, name, cats.size, token) }
            return uid
        }

        fun uniq(base: String): String {
            val n = dup.merge(base, 1, Int::plus)!!
            return if (n > 1) "$base#$n" else base
        }

        openSource(p.url, ua) { input ->
            M3uParser.parse(input, onHeader = { header = it }) { e ->
                val group = e.group.ifBlank { "Sans groupe" }
                when (M3uClassifier.kind(e)) {
                    M3uKind.LIVE -> {
                        val catUid = category(ContentKind.LIVE, group)
                        val uid = "${p.id}:L:" + uniq("$group|${e.name}|${e.tvgId}".shortHash())
                        val hAttrs = header.attrs
                        val cType = (e.attrs["catchup"] ?: e.attrs["catchup-type"] ?: hAttrs["catchup"] ?: hAttrs["catchup-type"]).orEmpty()
                            .ifBlank { if (e.attrs.containsKey("timeshift") || e.attrs.containsKey("tvg-rec")) "shift" else "" }
                        val cDays = (e.attrs["catchup-days"] ?: e.attrs["timeshift"] ?: e.attrs["tvg-rec"] ?: hAttrs["catchup-days"])
                            ?.trim()?.toIntOrNull() ?: if (cType.isNotBlank()) 7 else 0
                        val cSource = (e.attrs["catchup-source"] ?: hAttrs["catchup-source"]).orEmpty()
                        val licType = e.kodiProps["inputstream.adaptive.license_type"].orEmpty()
                        chBuf += ChannelEntity(
                            uid = uid, playlistId = p.id, remoteId = uid.substringAfter(":L:"), name = e.name,
                            nameNorm = TextNorm.search(e.name), matchKey = TextNorm.matchKey(e.name),
                            number = e.number.takeIf { it > 0 } ?: (live + 1), logo = e.logo, categoryUid = catUid,
                            epgId = e.tvgId, epgKey = e.tvgId.lowercase(), streamUrl = e.url,
                            catchupType = cType, catchupDays = if (cType.isBlank()) 0 else cDays, catchupSource = cSource,
                            userAgent = e.userAgent, referer = e.referer,
                            drmType = when {
                                licType.contains("widevine", true) -> "widevine"
                                licType.contains("clearkey", true) -> "clearkey"
                                licType.contains("playready", true) -> "playready"
                                else -> ""
                            },
                            drmKey = e.kodiProps["inputstream.adaptive.license_key"].orEmpty(),
                            position = live, syncToken = token,
                        )
                        live++
                    }
                    M3uKind.MOVIE -> {
                        val catUid = category(ContentKind.MOVIE, group)
                        val uid = "${p.id}:M:" + uniq("$group|${e.name}".shortHash())
                        mvBuf += MovieEntity(
                            uid = uid, playlistId = p.id, remoteId = uid.substringAfter(":M:"), name = e.name,
                            nameNorm = TextNorm.search(e.name), categoryUid = catUid, poster = e.logo,
                            year = yearRegex.find(e.name)?.groupValues?.get(1).orEmpty(),
                            durationSecs = e.durationSecs.coerceAtLeast(0), streamUrl = e.url,
                            containerExt = e.url.substringAfterLast('.', "").substringBefore('?').take(5),
                            position = movies, syncToken = token,
                        )
                        movies++
                    }
                    M3uKind.SERIES -> {
                        val catUid = category(ContentKind.SERIES, group)
                        val ep = M3uClassifier.episode(e.name)
                        val seriesName = ep?.seriesName ?: group
                        val seriesUid = "${p.id}:S:" + "$group|$seriesName".shortHash()
                        series.getOrPut(seriesUid) {
                            SeriesEntity(
                                uid = seriesUid, playlistId = p.id, remoteId = seriesUid.substringAfter(":S:"),
                                name = seriesName, nameNorm = TextNorm.search(seriesName), categoryUid = catUid,
                                poster = e.logo, position = series.size, syncToken = token,
                            )
                        }
                        val n = episodeCount.merge(seriesUid, 1, Int::plus)!!
                        epBuf += EpisodeEntity(
                            uid = "$seriesUid:" + e.url.shortHash(), seriesUid = seriesUid, playlistId = p.id,
                            remoteId = "", season = ep?.season ?: 1, episode = ep?.episode ?: n,
                            title = e.name, poster = e.logo, durationSecs = e.durationSecs.coerceAtLeast(0),
                            streamUrl = e.url, syncToken = token,
                        )
                    }
                }
                total++
                if (chBuf.size >= BATCH) runBlocking { upsertChannels(chBuf.toList()); chBuf.clear() }
                if (mvBuf.size >= BATCH) runBlocking { db.movies().upsert(mvBuf.toList()); mvBuf.clear() }
                if (epBuf.size >= BATCH) runBlocking { db.series().upsertEpisodes(epBuf.toList()); epBuf.clear() }
                if (total % 2000 == 0) report("${p.name} : $total entrées lues…")
            }
        }
        upsertChannels(chBuf)
        db.movies().upsert(mvBuf)
        db.series().upsertEpisodes(epBuf)
        db.series().upsert(series.values.toList())
        upsertCategories(cats.values.toList())
        if (total == 0) throw IOException("Playlist vide ou invalide")
        cleanup(p.id, token, includeVod = true)
        ensureAutoEpgSources(p, header.epgUrls)
        report("${p.name} : ${plural(live, "chaîne")}, ${plural(movies, "film")}, ${plural(series.size, "série")}")
    }

    // ---------------------------------------------------------------- Xtream

    private suspend fun syncXtream(p: PlaylistEntity, token: Long) {
        val ua = p.userAgent.ifBlank { settings.get().userAgent }
        val c = XtreamClient(p.url, p.username, p.password, ua)
        val acc = c.auth()
        if (!acc.authenticated) throw IOException(XtreamClient.refusalMessage(acc.status))
        db.playlists().update(
            p.copy(
                serverTimezone = acc.serverTimezone, expiresAt = acc.expiresAt, maxConnections = acc.maxConnections,
                activeConnections = acc.activeConnections, accountStatus = acc.status, accountCheckedAt = System.currentTimeMillis(),
            ),
        )
        val adultCats = HashSet<String>()

        // Direct
        report("${p.name} : catégories TV…")
        val cats = ArrayList<CategoryEntity>()
        val knownLive = HashSet<String>()
        c.list("get_live_categories").forEachIndexed { i, o ->
            val id = o.str("category_id")
            knownLive += id
            cats += CategoryEntity("${p.id}:LIVE:$id", p.id, ContentKind.LIVE, id, o.str("category_name").ifBlank { "Catégorie $id" }, i, token)
        }
        var orphanLive = false
        val buf = ArrayList<ChannelEntity>(BATCH)
        var n = 0
        c.streamList("get_live_streams") { o ->
            val id = o.str("stream_id").ifBlank { return@streamList }
            var cat = o.str("category_id")
            if (cat !in knownLive) { cat = "_none"; orphanLive = true }
            val name = o.str("name").ifBlank { "Chaîne $id" }
            val archive = o.int("tv_archive") == 1
            if (o.int("is_adult") == 1) adultCats += "${p.id}:LIVE:$cat"
            buf += ChannelEntity(
                uid = "${p.id}:L:$id", playlistId = p.id, remoteId = id, name = name,
                nameNorm = TextNorm.search(name), matchKey = TextNorm.matchKey(name),
                number = o.int("num").takeIf { it > 0 } ?: (n + 1), logo = o.str("stream_icon"),
                categoryUid = "${p.id}:LIVE:$cat", epgId = o.str("epg_channel_id"),
                epgKey = o.str("epg_channel_id").lowercase(),
                streamUrl = "", catchupType = if (archive) "xtream" else "",
                catchupDays = if (archive) o.int("tv_archive_duration").coerceAtLeast(1) else 0,
                position = n, syncToken = token,
            )
            n++
            if (buf.size >= BATCH) runBlocking { upsertChannels(buf.toList()); buf.clear() }
            if (n % 2000 == 0) report("${p.name} : $n chaînes…")
        }
        upsertChannels(buf); buf.clear()
        if (orphanLive) cats += CategoryEntity("${p.id}:LIVE:_none", p.id, ContentKind.LIVE, "_none", "Sans catégorie", 99_999, token)
        report("${p.name} : ${plural(n, "chaîne")}")

        // Films
        val vodOk = runCatching {
            report("${p.name} : films…")
            val known = HashSet<String>()
            c.list("get_vod_categories").forEachIndexed { i, o ->
                val id = o.str("category_id"); known += id
                cats += CategoryEntity("${p.id}:MOVIE:$id", p.id, ContentKind.MOVIE, id, o.str("category_name").ifBlank { "Catégorie $id" }, i, token)
            }
            var orphan = false
            val mb = ArrayList<MovieEntity>(BATCH)
            var m = 0
            c.streamList("get_vod_streams") { o ->
                val id = o.str("stream_id").ifBlank { return@streamList }
                var cat = o.str("category_id")
                if (cat !in known) { cat = "_none"; orphan = true }
                val name = o.str("name").ifBlank { "Film $id" }
                if (o.int("is_adult") == 1) adultCats += "${p.id}:MOVIE:$cat"
                val releaseDate = XtreamVodParser.parseReleaseDate(o)
                val year = XtreamVodParser.extractYear(o.str("year"), releaseDate, name)
                val rating = XtreamVodParser.parseRating(o.float("rating"), o.float("rating_5based"))
                val addedAt = XtreamVodParser.parseAdded(o.str("added"))
                mb += MovieEntity(
                    uid = "${p.id}:M:$id", playlistId = p.id, remoteId = id, name = name, nameNorm = TextNorm.search(name),
                    categoryUid = "${p.id}:MOVIE:$cat", poster = o.str("stream_icon"),
                    rating = rating,
                    year = year,
                    releaseDate = releaseDate,
                    containerExt = o.str("container_extension"), addedAt = addedAt,
                    streamUrl = o.str("direct_source").takeIf { it.startsWith("http") }.orEmpty(),
                    position = m, syncToken = token,
                )
                m++
                if (mb.size >= BATCH) runBlocking { db.movies().upsert(mb.toList()); mb.clear() }
                if (m % 5000 == 0) report("${p.name} : $m films…")
            }
            db.movies().upsert(mb)
            if (orphan) cats += CategoryEntity("${p.id}:MOVIE:_none", p.id, ContentKind.MOVIE, "_none", "Sans catégorie", 99_999, token)
        }.onFailure { Log.w(TAG, "vod", it) }.isSuccess

        // Séries
        val seriesOk = runCatching {
            report("${p.name} : séries…")
            val known = HashSet<String>()
            c.list("get_series_categories").forEachIndexed { i, o ->
                val id = o.str("category_id"); known += id
                cats += CategoryEntity("${p.id}:SERIES:$id", p.id, ContentKind.SERIES, id, o.str("category_name").ifBlank { "Catégorie $id" }, i, token)
            }
            var orphan = false
            val sb = ArrayList<SeriesEntity>(BATCH)
            var s = 0
            c.streamList("get_series") { o ->
                val id = o.str("series_id").ifBlank { return@streamList }
                var cat = o.str("category_id")
                if (cat !in known) { cat = "_none"; orphan = true }
                val name = o.str("name").ifBlank { "Série $id" }
                val releaseDate = XtreamVodParser.parseReleaseDate(o)
                val year = XtreamVodParser.extractYear(o.str("year"), releaseDate, name)
                val rating = XtreamVodParser.parseRating(o.float("rating"), o.float("rating_5based"))
                val addedAt = XtreamVodParser.parseAdded(o.str("added").ifBlank { o.str("last_modified") })
                sb += SeriesEntity(
                    uid = "${p.id}:S:$id", playlistId = p.id, remoteId = id, name = name, nameNorm = TextNorm.search(name),
                    categoryUid = "${p.id}:SERIES:$cat", poster = o.str("cover"), backdrop = o.strOrFirst("backdrop_path"),
                    rating = rating,
                    year = year,
                    releaseDate = releaseDate,
                    plot = o.str("plot"), genre = o.str("genre"), actors = o.str("cast"),
                    addedAt = addedAt, position = s, syncToken = token,
                )
                s++
                if (sb.size >= BATCH) runBlocking { db.series().upsert(sb.toList()); sb.clear() }
            }
            db.series().upsert(sb)
            if (orphan) cats += CategoryEntity("${p.id}:SERIES:_none", p.id, ContentKind.SERIES, "_none", "Sans catégorie", 99_999, token)
        }.onFailure { Log.w(TAG, "series", it) }.isSuccess

        upsertCategories(cats, adultCats)
        db.channels().deleteStale(p.id, token)
        db.categories().deleteStale(p.id, ContentKind.LIVE, token)
        if (vodOk) {
            db.movies().deleteStale(p.id, token)
            db.categories().deleteStale(p.id, ContentKind.MOVIE, token)
        }
        if (seriesOk) {
            db.series().deleteStale(p.id, token)
            db.categories().deleteStale(p.id, ContentKind.SERIES, token)
        }
        ensureAutoEpgSources(p, listOf(c.xmltvUrl))
    }

    /** Identifiant TMDB d'une série Xtream, si le panel le publie (`tmdb_id` ou `tmdb` selon le panel). */
    suspend fun xtreamSeriesTmdbId(p: PlaylistEntity, series: SeriesEntity): Int? = withContext(Dispatchers.IO) {
        val c = XtreamClient(p.url, p.username, p.password, p.userAgent.ifBlank { settings.get().userAgent })
        (c.seriesInfo(series.remoteId)?.get("info") as? JsonObject)?.let(XtreamVodParser::parseTmdbId)
    }

    /** Charge (ou rafraîchit) les épisodes d'une série Xtream. */
    suspend fun loadXtreamEpisodes(p: PlaylistEntity, series: SeriesEntity) = withContext(Dispatchers.IO) {
        val c = XtreamClient(p.url, p.username, p.password, p.userAgent.ifBlank { settings.get().userAgent })
        val info = c.seriesInfo(series.remoteId) ?: return@withContext
        val token = System.currentTimeMillis()
        info["info"]?.let { it as? JsonObject }?.let { i ->
            db.series().updateDetails(series.uid, i.str("plot"), i.strOrFirst("backdrop_path"), i.str("cast"), i.str("genre"))
        }
        val eps = ArrayList<EpisodeEntity>()
        val sizes = ArrayList<StreamQualityEntity>()
        fun add(o: JsonObject, seasonKey: String) {
            val id = o.str("id").ifBlank { return }
            val inf = o["info"] as? JsonObject
            XtreamVodParser.parseVideoSize(inf)?.let { (w, h) -> sizes += StreamQualityEntity("${series.uid}:$id", w, h) }
            val season = o.int("season").takeIf { it > 0 } ?: seasonKey.toIntOrNull() ?: 1
            eps += EpisodeEntity(
                uid = "${series.uid}:$id", seriesUid = series.uid, playlistId = p.id, remoteId = id,
                season = season, episode = o.int("episode_num").takeIf { it > 0 } ?: (eps.count { it.season == season } + 1),
                title = o.str("title").ifBlank { "Épisode ${o.str("episode_num")}" },
                plot = inf?.str("plot").orEmpty(), poster = inf?.str("movie_image").orEmpty(),
                durationSecs = inf?.int("duration_secs") ?: 0, containerExt = o.str("container_extension"),
                streamUrl = o.str("direct_source").takeIf { it.startsWith("http") }.orEmpty(), syncToken = token,
            )
        }
        when (val e = info["episodes"]) {
            is JsonObject -> e.forEach { (season, list) -> list.arrOrEmpty().forEach { (it as? JsonObject)?.let { o -> add(o, season) } } }
            is kotlinx.serialization.json.JsonArray -> e.forEachIndexed { i, el ->
                when (el) {
                    is kotlinx.serialization.json.JsonArray -> el.forEach { (it as? JsonObject)?.let { o -> add(o, (i + 1).toString()) } }
                    is JsonObject -> add(el, "1")
                    else -> Unit
                }
            }
            else -> Unit
        }
        if (eps.isNotEmpty()) {
            db.series().upsertEpisodes(eps)
            db.series().deleteStaleEpisodes(series.uid, token)
        }
        db.streamQuality().record(sizes, series.uid)
    }

    suspend fun loadXtreamMovieDetails(p: PlaylistEntity, movie: MovieEntity) = withContext(Dispatchers.IO) {
        val c = XtreamClient(p.url, p.username, p.password, p.userAgent.ifBlank { settings.get().userAgent })
        val root = c.vodInfo(movie.remoteId) ?: return@withContext
        val i = root["info"] as? JsonObject ?: return@withContext
        XtreamVodParser.parseVideoSize(i)?.let { (w, h) -> db.streamQuality().record(listOf(StreamQualityEntity(movie.uid, w, h))) }
        val dur = i.int("duration_secs").takeIf { it > 0 } ?: parseDuration(i.str("duration"))
        val relDate = XtreamVodParser.parseReleaseDate(i)
        db.movies().updateDetails(
            uid = movie.uid, plot = i.str("plot").ifBlank { i.str("description") }, backdrop = i.strOrFirst("backdrop_path"),
            actors = i.str("cast").ifBlank { i.str("actors") }, director = i.str("director"), genre = i.str("genre"),
            durationSecs = dur, year = XtreamVodParser.extractYear(i.str("year"), relDate, movie.name),
            poster = i.str("movie_image").takeIf { movie.poster.isBlank() }.orEmpty(),
            releaseDate = relDate,
        )
    }

    // ---------------------------------------------------------------- Stalker

    private suspend fun syncStalker(p: PlaylistEntity, token: Long) {
        val c = stalkerClient(p)
        c.connect()
        report("${p.name} : catégories…")
        val cats = ArrayList<CategoryEntity>()
        val genres = runCatching { c.genres() }.getOrDefault(emptyList()).filter { it.str("id") != "*" }
        genres.forEachIndexed { i, g ->
            cats += CategoryEntity("${p.id}:LIVE:${g.str("id")}", p.id, ContentKind.LIVE, g.str("id"), g.str("title").ifBlank { "Genre ${g.str("id")}" }, i, token)
        }
        report("${p.name} : chaînes…")
        var channels = runCatching { c.allChannels() }.getOrDefault(emptyList())
        if (channels.isEmpty()) {
            val all = ArrayList<JsonObject>()
            for (g in genres) {
                var page = 1
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val pg = c.channelPage(g.str("id"), page)
                    all += pg.items
                    if (pg.items.isEmpty() || page >= pg.totalPages || page >= 200) break
                    page++
                }
            }
            channels = all.distinctBy { it.str("id") }
        }
        val known = genres.map { it.str("id") }.toHashSet()
        var orphan = false
        val list = channels.mapIndexedNotNull { i, o ->
            val id = o.str("id").ifBlank { return@mapIndexedNotNull null }
            var cat = o.str("tv_genre_id")
            if (cat !in known) { cat = "_none"; orphan = true }
            val name = o.str("name").ifBlank { "Chaîne $id" }
            val archive = o.int("tv_archive") == 1 || o.int("archive") == 1 || o.bool("allow_pvr")
            val hours = o.int("tv_archive_duration")
            ChannelEntity(
                uid = "${p.id}:L:$id", playlistId = p.id, remoteId = id, name = name, nameNorm = TextNorm.search(name),
                matchKey = TextNorm.matchKey(name), number = o.int("number").takeIf { it > 0 } ?: (i + 1),
                logo = c.logoUrl(o.str("logo")), categoryUid = "${p.id}:LIVE:$cat", epgId = o.str("xmltv_id"),
                epgKey = "stk:${p.id}:$id", streamUrl = o.str("cmd"),
                catchupType = if (archive) "stalker" else "",
                catchupDays = if (archive) ((hours + 23) / 24).coerceAtLeast(1).takeIf { hours > 0 } ?: 7 else 0,
                position = i, syncToken = token,
            )
        }
        list.chunked(BATCH).forEach { upsertChannels(it) }
        if (orphan) cats += CategoryEntity("${p.id}:LIVE:_none", p.id, ContentKind.LIVE, "_none", "Sans catégorie", 99_999, token)

        report("${p.name} : catégories VOD…")
        runCatching {
            c.vodCategories("vod").filter { it.str("id") != "*" }.forEachIndexed { i, o ->
                cats += CategoryEntity("${p.id}:MOVIE:${o.str("id")}", p.id, ContentKind.MOVIE, o.str("id"), o.str("title").ifBlank { o.str("alias") }, i, token)
            }
        }
        runCatching {
            c.vodCategories("series").filter { it.str("id") != "*" }.forEachIndexed { i, o ->
                cats += CategoryEntity("${p.id}:SERIES:${o.str("id")}", p.id, ContentKind.SERIES, o.str("id"), o.str("title").ifBlank { o.str("alias") }, i, token)
            }
        }
        // Les catégories "séries" découvertes dans la VOD (is_series) ne sont pas purgées ici.
        upsertCategories(cats)
        runCatching { checkAccount(p) }
        db.channels().deleteStale(p.id, token)
        db.categories().deleteStale(p.id, ContentKind.LIVE, token)
        db.categories().deleteStale(p.id, ContentKind.MOVIE, token)
        report("${p.name} : ${plural(list.size, "chaîne")}")
        syncStalkerEpg(p, c)
    }

    private suspend fun syncStalkerEpg(p: PlaylistEntity, c: StalkerClient) {
        report("${p.name} : guide TV…")
        val s = settings.get()
        val now = System.currentTimeMillis()
        val shift = s.epgShiftMinutes * 60_000L
        val sourceKey = "stk:${p.id}"
        val data = runCatching { c.epgInfo((24 * s.epgDaysAhead).coerceIn(6, 72)) }.getOrElse {
            Log.w(TAG, "stalker epg", it); return
        }
        if (data.isEmpty()) return
        db.programs().deleteSourceAfter(sourceKey, now)
        val buf = ArrayList<ProgramEntity>(BATCH)
        data.forEach { (chId, items) ->
            items.forEach { o ->
                val start = o.long("start_timestamp") * 1000L
                val stop = o.long("stop_timestamp") * 1000L
                if (start > 0 && stop > start && stop > now) {
                    buf += ProgramEntity(
                        channelKey = "stk:${p.id}:${o.str("ch_id").ifBlank { chId }}", start = start + shift, stop = stop + shift,
                        title = o.str("name").ifBlank { "Sans titre" }, description = o.str("descr"),
                        category = o.str("category"), remoteId = o.str("id"), sourceKey = sourceKey, generation = now,
                    )
                }
                if (buf.size >= BATCH) { db.programs().insertAll(buf.toList()); buf.clear() }
            }
        }
        db.programs().insertAll(buf)
    }

    /** Charge une page de films/séries d'une catégorie Stalker (VOD chargée à la demande). */
    suspend fun loadStalkerVodPage(p: PlaylistEntity, category: CategoryEntity, page: Int): StalkerPageResult = withContext(Dispatchers.IO) {
        val c = stalkerClient(p)
        val type = if (category.kind == ContentKind.SERIES) "series" else "vod"
        val pg = c.vodPage(type, category.remoteId, page)
        val base = (page - 1) * pg.pageSize.coerceAtLeast(pg.items.size)
        val movies = ArrayList<MovieEntity>()
        val series = ArrayList<SeriesEntity>()
        val episodes = ArrayList<EpisodeEntity>()
        var virtualCat: CategoryEntity? = null
        pg.items.forEachIndexed { i, o ->
            val id = o.str("id").ifBlank { return@forEachIndexed }
            val name = o.str("name").ifBlank { o.str("o_name") }
            val poster = c.imageUrl(o.str("screenshot_uri").ifBlank { o.str("cover_big") })
            val isSeries = category.kind == ContentKind.SERIES || o.int("is_series") == 1
            val releaseDate = StalkerVodParser.parseReleaseDate(o)
            val year = StalkerVodParser.extractYear(o.str("year"), releaseDate, name)
            val rating = StalkerVodParser.parseRating(o.float("rating"), o.float("rating_imdb"), o.float("rating_5based"))
            val addedAt = StalkerVodParser.parseAdded(o.str("added"))
            if (isSeries) {
                val catUid = if (category.kind == ContentKind.SERIES) category.uid else {
                    val v = CategoryEntity("${p.id}:SERIES:v${category.remoteId}", p.id, ContentKind.SERIES, "v${category.remoteId}", category.name, 10_000 + category.position, category.syncToken)
                    virtualCat = v; v.uid
                }
                val sUid = "${p.id}:S:$id"
                series += SeriesEntity(
                    uid = sUid, playlistId = p.id, remoteId = id, name = name, nameNorm = TextNorm.search(name),
                    categoryUid = catUid, poster = poster, rating = rating, year = year, releaseDate = releaseDate,
                    plot = o.str("description"), genre = o.str("genres_str"), actors = o.str("actors"),
                    addedAt = addedAt, position = base + i, syncToken = 0,
                )
                // Ancien format : la liste des épisodes est fournie directement.
                o.arr("series")?.mapNotNull { it.prim()?.toIntOrNull() }?.takeIf { it.isNotEmpty() }?.forEach { n ->
                    episodes += EpisodeEntity(
                        uid = "$sUid:1:$n", seriesUid = sUid, playlistId = p.id, remoteId = n.toString(), season = 1, episode = n,
                        title = "Épisode $n", streamUrl = o.str("cmd"), containerExt = "stalker", syncToken = 0,
                    )
                }
            } else {
                movies += MovieEntity(
                    uid = "${p.id}:M:$id", playlistId = p.id, remoteId = id, name = name, nameNorm = TextNorm.search(name),
                    categoryUid = category.uid, poster = poster, rating = rating, year = year, releaseDate = releaseDate,
                    plot = o.str("description"), genre = o.str("genres_str"), actors = o.str("actors"), director = o.str("director"),
                    durationSecs = o.int("time") * 60, containerExt = "stalker", streamUrl = o.str("cmd"),
                    addedAt = addedAt, position = base + i, syncToken = 0,
                )
            }
        }
        virtualCat?.let { upsertCategories(listOf(it)) }
        db.movies().upsert(movies)
        db.series().upsert(series)
        db.series().upsertEpisodes(episodes)
        StalkerPageResult(loaded = pg.items.size, hasMore = pg.items.isNotEmpty() && page < pg.totalPages)
    }

    /** Saisons/épisodes d'une série Stalker (format "series" des portails Ministra). */
    suspend fun loadStalkerEpisodes(p: PlaylistEntity, series: SeriesEntity) = withContext(Dispatchers.IO) {
        val c = stalkerClient(p)
        val seasons = c.seasons(series.remoteId)
        val eps = ArrayList<EpisodeEntity>()
        seasons.forEachIndexed { idx, s ->
            val numbers = s.arr("series")?.mapNotNull { it.prim()?.toIntOrNull() }.orEmpty()
            val seasonNum = Regex("(\\d+)").find(s.str("name"))?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
            if (numbers.isNotEmpty()) {
                numbers.forEach { n ->
                    eps += EpisodeEntity(
                        uid = "${series.uid}:$seasonNum:$n", seriesUid = series.uid, playlistId = p.id, remoteId = n.toString(),
                        season = seasonNum, episode = n, title = "Épisode $n", streamUrl = s.str("cmd"),
                        containerExt = "stalker", syncToken = 0,
                    )
                }
            } else if (s.str("cmd").isNotBlank()) {
                eps += EpisodeEntity(
                    uid = "${series.uid}:1:${s.str("id")}", seriesUid = series.uid, playlistId = p.id, remoteId = "",
                    season = 1, episode = idx + 1, title = s.str("name").ifBlank { "Épisode ${idx + 1}" },
                    streamUrl = s.str("cmd"), containerExt = "stalker", syncToken = 0,
                )
            }
        }
        db.series().upsertEpisodes(eps)
    }

    // ---------------------------------------------------------------- EPG

    private suspend fun ensureAutoEpgSources(p: PlaylistEntity, urls: List<String>) {
        val existing = db.epg().autoSourcesFor(p.id)
        val wanted = urls.distinct()
        existing.filter { it.url !in wanted }.forEach { db.epg().deleteSource(it.id); db.programs().deleteSource("src:${it.id}") }
        wanted.filter { u -> existing.none { it.url == u } }.forEachIndexed { i, u ->
            db.epg().insertSource(
                EpgSourceEntity(playlistId = p.id, name = if (wanted.size > 1) "${p.name} (guide ${i + 1})" else "${p.name} (guide)", url = u, auto = true),
            )
        }
    }

    private suspend fun syncEpgLocked() {
        val s = settings.get()
        val now = System.currentTimeMillis()
        val from = now - s.epgDaysBehind * DAY
        val to = now + s.epgDaysAhead * DAY
        val shift = s.epgShiftMinutes * 60_000L
        val refs = db.channels().epgRefs()
        val wantedIds = refs.mapNotNull { r -> r.epgId.lowercase().trim().takeIf { it.isNotEmpty() } }.toHashSet()
        val wantedNames = refs.map { it.matchKey }.filter { it.isNotEmpty() }.toHashSet()
        val filled = HashSet<String>()
        val playlists = db.playlists().all().associateBy { it.id }

        for (src in db.epg().sources().filter { it.enabled }) {
            currentCoroutineContext().ensureActive()
            report("Guide TV : ${src.name}…")
            val sourceKey = "src:${src.id}"
            val gen = System.currentTimeMillis()
            val accepted = HashSet<String>()
            val provided = HashSet<String>()
            val nameMap = HashMap<String, String>()
            val buf = ArrayList<ProgramEntity>(BATCH)
            var count = 0
            val ua = playlists[src.playlistId]?.userAgent?.ifBlank { null } ?: s.userAgent
            try {
                val ctx = currentCoroutineContext()
                openSource(src.url, ua) { input ->
                    XmltvParser { platform.newPullParser() }.parse(
                        input,
                        onChannel = { ch ->
                            val id = ch.id.lowercase().trim()
                            if (id in wantedIds) accepted += id
                            ch.names.forEach { name ->
                                val mk = TextNorm.matchKey(name)
                                if (mk.isNotEmpty() && mk in wantedNames) {
                                    nameMap.putIfAbsent(mk, id); accepted += id
                                }
                            }
                        },
                        onProgramme = { pr ->
                            val id = pr.channel.lowercase().trim()
                            if ((id in accepted || id in wantedIds) && id !in filled && pr.stop > from && pr.start < to) {
                                provided += id
                                buf += ProgramEntity(
                                    channelKey = id, start = pr.start + shift, stop = pr.stop + shift, title = pr.title,
                                    description = pr.description, category = pr.category, icon = pr.icon,
                                    sourceKey = sourceKey, generation = gen,
                                )
                                if (buf.size >= BATCH) {
                                    runBlocking { db.programs().insertAll(buf.toList()) }
                                    count += buf.size; buf.clear()
                                    if (count % 10_000 == 0) report("Guide TV : ${src.name} — $count programmes")
                                }
                            }
                        },
                        isCancelled = { !ctx.isActive },
                    )
                }
                // L'analyseur rend la main sans erreur quand on annule : ne pas publier une génération incomplète.
                ctx.ensureActive()
                db.programs().insertAll(buf); count += buf.size
                db.programs().deleteOtherGenerations(sourceKey, gen)
                db.epg().upsertNameMap(nameMap.map { (k, v) -> EpgNameMapEntity(k, v) })
                filled += provided
                db.epg().updateSource(src.copy(lastSync = System.currentTimeMillis(), lastError = "", programCount = count))
            } catch (e: Exception) {
                // Coupure, XML invalide ou annulation : retirer les lots déjà insérés, sinon ils s'ajoutent à l'ancienne
                // génération (programmes en double dans le guide).
                withContext(NonCancellable) { runCatching { db.programs().deleteGeneration(sourceKey, gen) } }
                if (e is CancellationException) throw e
                Log.w(TAG, "epg ${src.name}", e)
                db.epg().updateSource(src.copy(lastError = e.message?.redactCredentials() ?: e.toString()))
            }
        }
        db.programs().deleteBefore(from)
        db.channels().resetEpgKeys()
        db.channels().remapEpgKeysByName()
        db.channels().applyEpgOverrides()
        playlists.values.filter { it.type == SourceType.STALKER }.forEach { p ->
            runCatching { syncStalkerEpg(p, stalkerClient(p)) }
        }
        afterEpg?.let { runCatching { it() }.onFailure { e -> Log.w(TAG, "afterEpg", e) } }
    }

    private suspend fun cleanup(pid: Long, token: Long, includeVod: Boolean) {
        db.channels().deleteStale(pid, token)
        db.categories().deleteStale(pid, ContentKind.LIVE, token)
        if (includeVod) {
            db.movies().deleteStale(pid, token)
            db.series().deleteStale(pid, token)
            db.series().deleteStaleEpisodesForPlaylist(pid, token)
            db.categories().deleteStale(pid, ContentKind.MOVIE, token)
            db.categories().deleteStale(pid, ContentKind.SERIES, token)
        }
    }

    suspend fun deletePlaylistData(pid: Long) = withContext(Dispatchers.IO) {
        db.channels().deleteForPlaylist(pid)
        db.movies().deleteForPlaylist(pid)
        db.series().deleteForPlaylist(pid)
        db.series().deleteEpisodesForPlaylist(pid)
        db.categories().deleteForPlaylist(pid)
        db.epg().autoSourcesFor(pid).forEach { db.programs().deleteSource("src:${it.id}") }
        db.epg().deleteSourcesFor(pid)
        db.programs().deleteSource("stk:$pid")
        forgetClient(pid)
    }

    data class StalkerPageResult(val loaded: Int, val hasMore: Boolean)

    companion object {
        private const val TAG = "VyzoSync"
        private const val BATCH = 500
        const val DAY = 24 * 3600_000L
        private val yearRegex = Regex("\\((\\d{4})\\)")

        /** Fichier choisi sur l'appareil (Android : content://, PC : file:// ou chemin) plutôt qu'une adresse web. */
        fun isLocalSource(url: String): Boolean =
            url.startsWith("content://") || url.startsWith("file:") || url.startsWith("/") ||
                Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(url)

        /** Date de fin d'abonnement d'un portail Stalker (« 2026-12-31 23:59:59 », « 31.12.2026 »…), 0 si illisible. */
        fun parseStalkerDate(s: String): Long {
            val t = s.trim()
            if (t.isBlank()) return 0
            t.toLongOrNull()?.let { return if (it > 10_000_000_000L) it else it * 1000L }
            for (pattern in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "dd.MM.yyyy HH:mm", "dd.MM.yyyy", "MMMM d, yyyy, h:mm a", "MMMM d, yyyy")) {
                val ms = runCatching { java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply { isLenient = false }.parse(t)?.time }.getOrNull()
                if (ms != null && ms > 0) return ms
            }
            return 0
        }

        fun parseDuration(s: String): Int {
            val parts = s.split(':').mapNotNull { it.trim().toIntOrNull() }
            return when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                else -> 0
            }
        }
    }
}

private fun kotlinx.serialization.json.JsonElement.arrOrEmpty(): List<kotlinx.serialization.json.JsonElement> =
    (this as? kotlinx.serialization.json.JsonArray)?.toList().orEmpty()
