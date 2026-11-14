package fr.yannickuhrig.vyzo.data.tmdb

import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.util.LenientJson
import fr.yannickuhrig.vyzo.util.TextNorm
import fr.yannickuhrig.vyzo.util.asArr
import fr.yannickuhrig.vyzo.util.asObj
import fr.yannickuhrig.vyzo.util.float
import fr.yannickuhrig.vyzo.util.int
import fr.yannickuhrig.vyzo.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Membre de l'équipe ou du casting. */
data class CastMember(
    val id: Int,
    val name: String,
    val character: String,
    val profileUrl: String? = null,
)

/** Métadonnées enrichies depuis TheMovieDB et/ou les métadonnées de secours. */
data class TmdbEnrichment(
    val tmdbId: Int? = null,
    val title: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val genres: List<String> = emptyList(),
    val voteAverage: Float = 0f,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val cast: List<CastMember> = emptyList(),
    val directors: List<String> = emptyList(),
    val youtubeTrailerId: String? = null,
    val trailerWebUrl: String? = null,
    /** Identifiant IMDb (« tt0111161 »), nécessaire pour demander les notes à OMDb. */
    val imdbId: String? = null,
    /** Classification française brute (« 12 », « U »…), voir [certificationLabel]. */
    val certification: String? = null,
)

enum class TmdbKind(val path: String) { MOVIE("movie"), SERIES("tv") }

/** Résumé et image d'un épisode. */
data class TmdbEpisode(val season: Int, val number: Int, val title: String, val overview: String, val stillUrl: String?, val airDate: String = "")

/** Titre d'une liste TMDB (tendances, recommandations), à retrouver dans le catalogue. */
data class TmdbTitle(val title: String, val originalTitle: String, val year: Int?)

fun imdbIdOf(json: JsonObject): String? =
    json["external_ids"].asObj()?.str("imdb_id").orEmpty().ifBlank { json.str("imdb_id") }.takeIf { it.startsWith("tt") }

private fun JsonObject.franceEntry(field: String): JsonObject? =
    this[field].asObj()?.get("results").asArr()?.mapNotNull { it.asObj() }?.firstOrNull { it.str("iso_3166_1") == "FR" }

/** Classification française d'un film : celle de la sortie en salle (type 3), sinon la première renseignée. */
fun movieCertification(json: JsonObject): String? {
    val dates = json.franceEntry("release_dates")?.get("release_dates").asArr()?.mapNotNull { it.asObj() }.orEmpty()
        .filter { it.str("certification").isNotBlank() }
    return (dates.firstOrNull { it.int("type") == 3 } ?: dates.firstOrNull())?.str("certification")
}

/** Classification française d'une série. */
fun seriesCertification(json: JsonObject): String? =
    json["content_ratings"].asObj()?.get("results").asArr()?.mapNotNull { it.asObj() }.orEmpty()
        .filter { it.str("iso_3166_1") == "FR" }
        .map { it.str("rating") }
        .filter { it.isNotBlank() && !it.equals("NR", ignoreCase = true) }
        // TMDB donne parfois plusieurs classifications pour un même pays : la plus stricte fait foi.
        .maxByOrNull { it.toIntOrNull() ?: 0 }

/** « 12 » devient « −12 ans », « U » devient « Tous publics ». */
fun certificationLabel(certification: String): String = when {
    certification.equals("U", ignoreCase = true) || certification.equals("TP", ignoreCase = true) || certification == "0" -> "Tous publics"
    certification.toIntOrNull() != null -> "−$certification ans"
    else -> certification
}

fun parseSeasonEpisodes(json: JsonObject): List<TmdbEpisode> =
    json["episodes"].asArr()?.mapNotNull { it.asObj() }.orEmpty().mapNotNull { e ->
        val number = e.int("episode_number")
        if (number <= 0) return@mapNotNull null
        TmdbEpisode(
            season = e.int("season_number"), number = number, title = e.str("name"), overview = e.str("overview"),
            stillUrl = e.str("still_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" },
            airDate = e.str("air_date"),
        )
    }

/** Extrait l'état et les prochaines dates de diffusion d'une série depuis la réponse TMDB `/tv/{id}`. */
fun parseTmdbSeriesSchedule(
    seriesUid: String,
    tmdbId: Int,
    json: JsonObject,
    now: Long = System.currentTimeMillis(),
): fr.yannickuhrig.vyzo.data.db.SeriesScheduleEntity {
    val status = json.str("status")
    val nextObj = json["next_episode_to_air"].asObj()
    val today = fr.yannickuhrig.vyzo.ui.common.Fmt.todayIso(now)
    val futureSeason = json["seasons"].asArr()?.mapNotNull { it.asObj() }.orEmpty()
        .filter { it.int("season_number") > 0 && it.str("air_date").take(10) >= today }
        .minByOrNull { it.int("season_number") }
    return fr.yannickuhrig.vyzo.data.db.SeriesScheduleEntity(
        seriesUid = seriesUid,
        status = status,
        nextSeason = nextObj?.int("season_number") ?: 0,
        nextEpisode = nextObj?.int("episode_number") ?: 0,
        nextTitle = nextObj?.str("name").orEmpty(),
        nextAirDate = nextObj?.str("air_date").orEmpty(),
        upcomingSeason = futureSeason?.int("season_number") ?: 0,
        upcomingSeasonAirDate = futureSeason?.str("air_date").orEmpty(),
        upcomingSeasonEpisodes = futureSeason?.int("episode_count") ?: 0,
        tmdbId = tmdbId,
        checkedAt = now,
    )
}

fun parseTitleList(json: JsonObject): List<TmdbTitle> =
    json["results"].asArr()?.mapNotNull { it.asObj() }.orEmpty().mapNotNull { r ->
        val title = r.str("title").ifBlank { r.str("name") }
        if (title.isBlank()) return@mapNotNull null
        TmdbTitle(
            title = title,
            originalTitle = r.str("original_title").ifBlank { r.str("original_name") },
            year = r.str("release_date").ifBlank { r.str("first_air_date") }.take(4).toIntOrNull(),
        )
    }

/** Le nom du catalogue désigne bien ce titre, préfixes et qualité retirés ; une année d'écart est tolérée. */
fun sameTitle(catalogName: String, catalogYear: Int?, wanted: TmdbTitle): Boolean {
    val (clean, nameYear) = cleanTitleForTmdb(catalogName)
    val norm = TextNorm.search(clean)
    if (norm.isBlank() || (norm != TextNorm.search(wanted.title) && norm != TextNorm.search(wanted.originalTitle))) return false
    val year = catalogYear?.takeIf { it > 0 } ?: nameYear
    return wanted.year == null || year == null || abs(year - wanted.year) <= 1
}

/**
 * Adresse YouTube de la bande-annonce : la vidéo quand on la connaît (TMDB ou fiche du fournisseur),
 * sinon une recherche « <titre> bande annonce vf ».
 */
fun trailerUrl(youtubeId: String?, title: String): String =
    if (youtubeId != null) "https://www.youtube.com/watch?v=$youtubeId"
    else "https://www.youtube.com/results?search_query=" + URLEncoder.encode("$title bande annonce vf", "UTF-8")

/**
 * Nettoie le titre brut d'un contenu IPTV (films ou séries) pour la recherche TMDB :
 * retire les préfixes linguistiques (FR |, VF, MULTI…), résolutions (4K, 1080p…),
 * codecs (x264, HEVC…), balises de saisons, et extrait l'année si elle est présente.
 */
fun cleanTitleForTmdb(raw: String): Pair<String, Int?> {
    var text = raw.trim()

    // 1. Extraction de l'année (4 chiffres entre 1900 et 2099)
    val yearRegex = Regex("""[\[\(]?\b(19\d\d|20\d\d)\b[\]\)]?""")
    val yearMatch = yearRegex.findAll(text).lastOrNull()
    val year = yearMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

    if (yearMatch != null) {
        text = text.removeRange(yearMatch.range)
    }

    // 2. Nettoyage des préfixes pays/langue courants en IPTV : "FR |", "VF -", "[FR]", "MULTI :"
    text = text.replace(
        Regex("""^(FR|VF|VOSTFR|MULTI|TRUEFRENCH|VFF|VO|EN|UK|US|ESP|ITA|GER|4K|FHD|HD|UHD)\s*[:|/\-]\s*""", RegexOption.IGNORE_CASE),
        "",
    )
    text = text.replace(
        Regex("""^[\[\(](FR|VF|VOSTFR|MULTI|TRUEFRENCH|VFF|VO|EN|UK|US|4K|FHD|HD|UHD)[\]\)]\s*""", RegexOption.IGNORE_CASE),
        "",
    )

    // 3. Nettoyage des qualités, résolutions et codecs vidéo/audio
    val techRegex = Regex(
        """\b(4K|2160p|1080p|720p|480p|UHD|FHD|HD|BluRay|BRRip|BDRip|WEBRip|WEB-DL|DVDRip|HDRip|HDTV|x264|x265|HEVC|H\.?264|H\.?265|10bit|HDR10\+?|HDR|REMUX|AC3|AAC|DTS|DD5\.1)\b""",
        RegexOption.IGNORE_CASE,
    )
    text = text.replace(techRegex, "")

    // 4. Nettoyage des mentions de saison/épisode (ex: S01, Saison 1, Intégrale)
    text = text.replace(
        Regex("""\b(S\d{1,2}(?:E\d{1,2})?|Saison\s*\d+|Season\s*\d+|Complete|Intégrale)\b""", RegexOption.IGNORE_CASE),
        "",
    )

    // 5. Remplacement des séparateurs parasites (points, underscores, crochets) par des espaces
    text = text.replace(Regex("""[._\[\]()]"""), " ")

    // 6. Nettoyage des tirets ou barres orphelins en début et fin
    text = text.replace(Regex("""^\s*[-|:]+\s*"""), "")
    text = text.replace(Regex("""\s*[-|:]+\s*$"""), "")

    // 7. Normalisation des espaces multiples
    text = text.replace(Regex("""\s+"""), " ").trim()

    // Un titre fait d'une seule année (« 1992 », « 1917 ») : l'année est le titre.
    if (text.isEmpty() && year != null) return year.toString() to null

    return text to year
}

/**
 * Extrait l'identifiant YouTube (chaîne de 11 caractères) depuis une URL ou un identifiant direct.
 */
fun extractYoutubeId(input: String?): String? {
    if (input.isNullOrBlank()) return null
    val trimmed = input.trim()

    // Identifiant direct à 11 caractères
    if (Regex("""^[A-Za-z0-9_-]{11}$""").matches(trimmed)) {
        return trimmed
    }

    // URLs courtes youtu.be/<id> ou classiques youtube.com/watch?v=<id> / embed/<id>
    val urlRegex = Regex("""(?:youtu\.be/|youtube\.com/(?:embed/|v/|watch\?v=|watch\?.+&v=))([A-Za-z0-9_-]{11})""")
    val match = urlRegex.find(trimmed)
    if (match != null) {
        return match.groupValues[1]
    }

    // Paramètre v=<id> dans n'importe quelle URL de recherche ou query string
    val paramRegex = Regex("""[?&]v=([A-Za-z0-9_-]{11})""")
    val paramMatch = paramRegex.find(trimmed)
    if (paramMatch != null) {
        return paramMatch.groupValues[1]
    }

    return null
}

/** Le seul résultat de recherche TMDB portant exactement ce titre (et cette année si connue), sinon null. */
fun exactSeriesMatch(results: List<JsonObject>, title: String, year: Int?): Int? {
    val wanted = TextNorm.search(title)
    return results.filter { r ->
        (TextNorm.search(r.str("name")) == wanted || TextNorm.search(r.str("original_name")) == wanted) &&
            (year == null || r.str("first_air_date").take(4) == year.toString())
    }.singleOrNull()?.int("id")?.takeIf { it > 0 }
}

/**
 * Service d'enrichissement des fiches de films et séries via TheMovieDB (TMDB),
 * avec extraction des bandes-annonces et mode de secours autonome sans clé API.
 */
class TmdbService(
    private val settings: SettingsStore,
    private val cache: MetadataCache? = null,
    private val builtInKey: String = "",
) {
    /** Une clé est intégrée à cette version de l'appli : celle de l'utilisateur reste prioritaire. */
    val hasBuiltInKey: Boolean get() = builtInKey.isNotBlank()

    private suspend fun apiKey(): String = settings.get().tmdbApiKey.trim().ifBlank { builtInKey.trim() }

    private val fastHttpClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Enrichit une fiche de film.
     */
    suspend fun enrichMovie(
        rawTitle: String,
        hintYear: Int? = null,
        tmdbId: Int? = null,
        rawTrailer: String? = null,
    ): TmdbEnrichment = withContext(Dispatchers.IO) {
        val (cleanTitle, extractedYear) = cleanTitleForTmdb(rawTitle)
        val year = hintYear ?: extractedYear
        val apiKey = apiKey()

        // 1. Si une clé API TMDB est configurée, recherche sur l'API TMDB
        if (apiKey.isNotBlank()) {
            val enriched = runCatching { fetchMovieWithKey(apiKey, cleanTitle, year, tmdbId) }.getOrNull()
            if (enriched != null) {
                // Si aucune bande-annonce TMDB mais un trailer brut Xtream est fourni
                val finalYoutubeId = enriched.youtubeTrailerId ?: extractYoutubeId(rawTrailer)
                return@withContext enriched.copy(
                    youtubeTrailerId = finalYoutubeId,
                    trailerWebUrl = trailerUrl(finalYoutubeId, cleanTitle),
                )
            }
        }

        // 2. Mode de secours sans clé API ou en cas d'échec de la recherche TMDB
        val ytId = extractYoutubeId(rawTrailer)
        TmdbEnrichment(
            title = cleanTitle,
            youtubeTrailerId = ytId,
            trailerWebUrl = trailerUrl(ytId, cleanTitle),
        )
    }

    /**
     * Enrichit une fiche de série.
     */
    suspend fun enrichSeries(
        rawTitle: String,
        hintYear: Int? = null,
        tmdbId: Int? = null,
        rawTrailer: String? = null,
    ): TmdbEnrichment = withContext(Dispatchers.IO) {
        val (cleanTitle, extractedYear) = cleanTitleForTmdb(rawTitle)
        val year = hintYear ?: extractedYear
        val apiKey = apiKey()

        if (apiKey.isNotBlank()) {
            val enriched = runCatching { fetchSeriesWithKey(apiKey, cleanTitle, year, tmdbId) }.getOrNull()
            if (enriched != null) {
                val finalYoutubeId = enriched.youtubeTrailerId ?: extractYoutubeId(rawTrailer)
                return@withContext enriched.copy(
                    youtubeTrailerId = finalYoutubeId,
                    trailerWebUrl = trailerUrl(finalYoutubeId, cleanTitle),
                )
            }
        }

        val ytId = extractYoutubeId(rawTrailer)
        TmdbEnrichment(
            title = cleanTitle,
            youtubeTrailerId = ytId,
            trailerWebUrl = trailerUrl(ytId, cleanTitle),
        )
    }

    /**
     * Identifiant TMDB d'une série d'après son titre, seulement si la correspondance est exacte et unique :
     * un résultat approchant donnerait les repères d'une autre série.
     */
    suspend fun findSeriesId(rawTitle: String, hintYear: Int?): Int? = withContext(Dispatchers.IO) {
        val apiKey = apiKey()
        if (apiKey.isBlank()) return@withContext null
        val (cleanTitle, extractedYear) = cleanTitleForTmdb(rawTitle)
        val enc = URLEncoder.encode(cleanTitle, "UTF-8")
        getJson("/search/tv", "query=$enc&language=fr-FR&include_adult=false", apiKey, SEARCH_MAX_AGE_MS)?.get("results").asArr()
            ?.mapNotNull { it.asObj() }?.let { exactSeriesMatch(it, cleanTitle, hintYear ?: extractedYear) }
    }

    /** Réponse de TMDB, depuis le cache si elle y est encore. L'identifiant de cache ne contient pas la clé. */
    private suspend fun getJson(path: String, query: String, apiKey: String, maxAgeMs: Long = MetadataCache.MAX_AGE_MS): JsonObject? {
        val key = "tmdb:$path?$query"
        cache?.get(key, maxAgeMs)?.let { cached ->
            runCatching { LenientJson.parseToJsonElement(cached).asObj() }.getOrNull()?.let { return it }
        }
        val url = "https://api.themoviedb.org/3$path?api_key=$apiKey&$query"
        val body = runCatching {
            Http.execute(client = fastHttpClient, request = Request.Builder().url(url).build()) { res ->
                if (res.isSuccessful) res.body?.string() else null
            }
        }.getOrNull() ?: return null
        val json = runCatching { LenientJson.parseToJsonElement(body).asObj() }.getOrNull() ?: return null
        cache?.put(key, body)
        return json
    }

    private suspend fun fetchMovieWithKey(apiKey: String, cleanTitle: String, year: Int?, tmdbId: Int?): TmdbEnrichment? {
        val id = tmdbId ?: searchId("movie", "year", apiKey, cleanTitle, year) ?: return null
        val json = getJson("/movie/$id", "language=fr-FR&append_to_response=credits,videos,external_ids,release_dates", apiKey) ?: return null
        return parseMovieResponse(json, id, apiKey)
    }

    private suspend fun fetchSeriesWithKey(apiKey: String, cleanTitle: String, year: Int?, tmdbId: Int?): TmdbEnrichment? {
        val id = tmdbId ?: searchId("tv", "first_air_date_year", apiKey, cleanTitle, year) ?: return null
        val json = getSeriesJson(id, apiKey) ?: return null
        return parseSeriesResponse(json, id, apiKey)
    }

    /** Retourne le suivi de diffusion d'une série (TMDB avec cache adaptatif 24 h / 30 j, repli TVmaze sans clé). */
    suspend fun fetchSeriesSchedule(
        seriesUid: String,
        rawTitle: String,
        hintYear: Int? = null,
        tmdbId: Int? = null,
        tvMaze: fr.yannickuhrig.vyzo.data.intro.TvMazeClient? = null,
    ): fr.yannickuhrig.vyzo.data.db.SeriesScheduleEntity? = withContext(Dispatchers.IO) {
        val (cleanTitle, extractedYear) = cleanTitleForTmdb(rawTitle)
        val year = hintYear ?: extractedYear
        val apiKey = apiKey()
        if (apiKey.isNotBlank() && cleanTitle.isNotBlank()) {
            val id = tmdbId ?: searchId("tv", "first_air_date_year", apiKey, cleanTitle, year)
            if (id != null) {
                val json = getSeriesJson(id, apiKey)
                if (json != null) {
                    return@withContext parseTmdbSeriesSchedule(seriesUid, id, json)
                }
            }
        }
        tvMaze?.fetchSchedule(seriesUid, rawTitle, year)
    }

    private suspend fun getSeriesJson(id: Int, apiKey: String): JsonObject? {
        val path = "/tv/$id"
        val query = "language=fr-FR&append_to_response=credits,videos,external_ids,content_ratings"
        val cacheKey = "tmdb:$path?$query"
        cache?.get(cacheKey, MetadataCache.DAY_MS)?.let { cached ->
            runCatching { LenientJson.parseToJsonElement(cached).asObj() }.getOrNull()?.let { return it }
        }
        val staleObj = cache?.get(cacheKey, MetadataCache.MAX_AGE_MS)?.let { cached ->
            runCatching { LenientJson.parseToJsonElement(cached).asObj() }.getOrNull()
        }
        if (staleObj != null && fr.yannickuhrig.vyzo.data.vod.SeriesTracking.isEndedStatus(staleObj.str("status"))) {
            return staleObj
        }
        return getJson(path, query, apiKey, maxAgeMs = 1L) ?: staleObj
    }

    private suspend fun searchId(kind: String, yearField: String, apiKey: String, query: String, year: Int?): Int? {
        val enc = URLEncoder.encode(query, "UTF-8")
        val base = "query=$enc&language=fr-FR&include_adult=false"
        suspend fun first(q: String) = getJson("/search/$kind", q, apiKey, SEARCH_MAX_AGE_MS)?.get("results").asArr()?.firstOrNull()?.asObj()?.int("id")
        if (year != null && year > 1900) first("$base&$yearField=$year")?.let { return it }
        // Deuxième tentative sans l'année, au cas où celle du fournisseur diffère.
        return first(base)
    }

    /** Résumés et images des épisodes d'une saison, vides sans clé TMDB. */
    suspend fun seasonEpisodes(tvId: Int, season: Int, maxAgeMs: Long = MetadataCache.MAX_AGE_MS): List<TmdbEpisode> = withContext(Dispatchers.IO) {
        val apiKey = apiKey()
        if (apiKey.isBlank()) return@withContext emptyList()
        getJson("/tv/$tvId/season/$season", "language=fr-FR", apiKey, maxAgeMs)?.let(::parseSeasonEpisodes).orEmpty()
    }

    /** Titres en vogue cette semaine, du plus au moins populaire. */
    suspend fun trending(kind: TmdbKind): List<TmdbTitle> = withContext(Dispatchers.IO) {
        val apiKey = apiKey()
        if (apiKey.isBlank()) return@withContext emptyList()
        (1..TRENDING_PAGES).flatMap { page ->
            getJson("/trending/${kind.path}/week", "language=fr-FR&page=$page", apiKey, MetadataCache.DAY_MS)?.let(::parseTitleList).orEmpty()
        }
    }

    /** Titre français de [id] : les listes Trakt sont en anglais, les catalogues IPTV souvent en français. */
    suspend fun titleById(kind: TmdbKind, id: Int): TmdbTitle? = withContext(Dispatchers.IO) {
        val apiKey = apiKey()
        if (apiKey.isBlank()) return@withContext null
        getJson("/${kind.path}/$id", "language=fr-FR", apiKey)
            ?.let { parseTitleList(JsonObject(mapOf("results" to kotlinx.serialization.json.JsonArray(listOf(it))))) }?.firstOrNull()
    }

    /** Titres recommandés par TMDB à partir de [id]. */
    suspend fun recommendations(kind: TmdbKind, id: Int): List<TmdbTitle> = withContext(Dispatchers.IO) {
        val apiKey = apiKey()
        if (apiKey.isBlank()) return@withContext emptyList()
        getJson("/${kind.path}/$id/recommendations", "language=fr-FR", apiKey)?.let(::parseTitleList).orEmpty()
    }

    fun parseMovieResponse(json: JsonObject, id: Int, apiKey: String): TmdbEnrichment {
        val title = json.str("title")
        val overview = json.str("overview")
        val tagline = json.str("tagline")
        val voteAvg = json.float("vote_average")

        val posterPath = json.str("poster_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
        val backdropPath = json.str("backdrop_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w1280$it" }

        val genres = json["genres"].asArr()?.mapNotNull { (it as? JsonObject)?.str("name")?.takeIf { g -> g.isNotBlank() } }.orEmpty()

        // Casting et réalisateurs
        val credits = json["credits"].asObj()
        val cast = credits?.get("cast").asArr()?.take(25)?.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val actorId = o.int("id")
            val name = o.str("name")
            val character = o.str("character")
            val profile = o.str("profile_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w185$it" }
            if (name.isNotBlank()) CastMember(actorId, name, character, profile) else null
        }.orEmpty()

        val directors = credits?.get("crew").asArr()?.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            if (o.str("job").equals("Director", ignoreCase = true)) o.str("name") else null
        }?.distinct().orEmpty()

        // Vidéos / Bande-annonce
        val videosObj = json["videos"].asObj()
        val videoResults = videosObj?.get("results").asArr()?.mapNotNull { it as? JsonObject }.orEmpty()
        var ytKey = findBestYoutubeTrailerKey(videoResults)

        return TmdbEnrichment(
            tmdbId = id,
            title = title,
            overview = overview.ifBlank { null },
            tagline = tagline.ifBlank { null },
            genres = genres,
            voteAverage = voteAvg,
            posterUrl = posterPath,
            backdropUrl = backdropPath,
            cast = cast,
            directors = directors,
            youtubeTrailerId = ytKey,
            imdbId = imdbIdOf(json),
            certification = movieCertification(json),
        )
    }

    fun parseSeriesResponse(json: JsonObject, id: Int, apiKey: String): TmdbEnrichment {
        val title = json.str("name")
        val overview = json.str("overview")
        val tagline = json.str("tagline")
        val voteAvg = json.float("vote_average")

        val posterPath = json.str("poster_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
        val backdropPath = json.str("backdrop_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w1280$it" }

        val genres = json["genres"].asArr()?.mapNotNull { (it as? JsonObject)?.str("name")?.takeIf { g -> g.isNotBlank() } }.orEmpty()

        val credits = json["credits"].asObj()
        val cast = credits?.get("cast").asArr()?.take(25)?.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val actorId = o.int("id")
            val name = o.str("name")
            val character = o.str("character")
            val profile = o.str("profile_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w185$it" }
            if (name.isNotBlank()) CastMember(actorId, name, character, profile) else null
        }.orEmpty()

        val directors = credits?.get("crew").asArr()?.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            if (o.str("job").equals("Executive Producer", ignoreCase = true) || o.str("job").equals("Director", ignoreCase = true)) {
                o.str("name")
            } else null
        }?.distinct().orEmpty()

        val videosObj = json["videos"].asObj()
        val videoResults = videosObj?.get("results").asArr()?.mapNotNull { it as? JsonObject }.orEmpty()
        val ytKey = findBestYoutubeTrailerKey(videoResults)

        return TmdbEnrichment(
            tmdbId = id,
            title = title,
            overview = overview.ifBlank { null },
            tagline = tagline.ifBlank { null },
            genres = genres,
            voteAverage = voteAvg,
            posterUrl = posterPath,
            backdropUrl = backdropPath,
            cast = cast,
            directors = directors,
            youtubeTrailerId = ytKey,
            imdbId = imdbIdOf(json),
            certification = seriesCertification(json),
        )
    }

    /**
     * Recherche la meilleure bande-annonce YouTube :
     * privilégie le français ("fr"), puis type "Trailer", puis n'importe quel clip YouTube.
     */
    fun findBestYoutubeTrailerKey(videos: List<JsonObject>): String? {
        val ytVideos = videos.filter { it.str("site").equals("YouTube", ignoreCase = true) }
        if (ytVideos.isEmpty()) return null

        // 1. Trailer en français
        val frTrailer = ytVideos.firstOrNull {
            it.str("type").equals("Trailer", ignoreCase = true) && it.str("iso_639_1").equals("fr", ignoreCase = true)
        }
        if (frTrailer != null) return frTrailer.str("key")

        // 2. N'importe quelle vidéo en français
        val frAny = ytVideos.firstOrNull { it.str("iso_639_1").equals("fr", ignoreCase = true) }
        if (frAny != null) return frAny.str("key")

        // 3. Trailer officiel en anglais ou toute langue
        val officialTrailer = ytVideos.firstOrNull {
            it.str("type").equals("Trailer", ignoreCase = true) && it.str("official").equals("true", ignoreCase = true)
        }
        if (officialTrailer != null) return officialTrailer.str("key")

        // 4. Premier trailer
        val anyTrailer = ytVideos.firstOrNull { it.str("type").equals("Trailer", ignoreCase = true) }
        if (anyTrailer != null) return anyTrailer.str("key")

        // 5. Première vidéo YouTube disponible
        return ytVideos.firstOrNull()?.str("key")
    }

    private companion object {
        /** Plus court que le reste : un titre absent de TMDB aujourd'hui peut y être ajouté. */
        const val SEARCH_MAX_AGE_MS = 7 * MetadataCache.DAY_MS
        const val TRENDING_PAGES = 2
    }
}

