package fr.yannickuhrig.vyzo.data.subtitles

import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.diag.DiagLog
import fr.yannickuhrig.vyzo.util.LenientJson
import fr.yannickuhrig.vyzo.util.asArr
import fr.yannickuhrig.vyzo.util.asObj
import fr.yannickuhrig.vyzo.util.int
import fr.yannickuhrig.vyzo.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

data class SubtitleItem(
    val id: String,
    val title: String,
    val language: String,
    val downloadUrl: String? = null,
    val fileId: Long? = null,
    val format: String = "srt",
    val source: String = "OpenSubtitles",
    val downloads: Int = 0,
    val rating: Float = 0f,
)

class SubtitleService(private val settings: SettingsStore) {

    /**
     * Recherche des sous-titres en ligne par titre, langue, année, saison et épisode.
     */
    suspend fun search(
        query: String,
        language: String = "fr",
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
    ): List<SubtitleItem> = withContext(Dispatchers.IO) {
        val s = settings.get()
        val apiKey = s.openSubtitlesApiKey.trim()
        val cleanQuery = sanitizeQuery(query)

        val results = if (apiKey.isNotBlank()) {
            runCatching { searchOpenSubtitlesV1(cleanQuery, language, year, season, episode, apiKey) }
                .getOrElse { e ->
                    DiagLog.error("Subtitles", "Échec OpenSubtitles v1 avec clé, bascule vers le secours", e)
                    emptyList()
                }
        } else {
            emptyList()
        }

        if (results.isNotEmpty()) return@withContext results

        // Fournisseur public de secours sans clé API (OpenSubtitles REST)
        runCatching { searchPublicOpenSubtitles(cleanQuery, language, season, episode) }
            .getOrElse { e ->
                DiagLog.error("Subtitles", "Échec recherche publique OpenSubtitles", e)
                emptyList()
            }
    }

    /**
     * Télécharge et décompresse le fichier de sous-titres, renvoyant le texte brut .srt.
     */
    suspend fun download(item: SubtitleItem): String = withContext(Dispatchers.IO) {
        val s = settings.get()
        val apiKey = s.openSubtitlesApiKey.trim()

        if (item.fileId != null && apiKey.isNotBlank()) {
            val downloadUrl = runCatching { getV1DownloadUrl(item.fileId, apiKey) }.getOrNull()
            if (!downloadUrl.isNullOrBlank()) {
                return@withContext fetchSrtText(downloadUrl)
            }
        }

        if (!item.downloadUrl.isNullOrBlank()) {
            return@withContext fetchSrtText(item.downloadUrl)
        }

        throw IllegalArgumentException("Aucune URL ou identifiant de téléchargement pour ce sous-titre")
    }

    /**
     * Télécharge un fichier de sous-titres depuis une URL personnalisée.
     */
    suspend fun loadFromUrl(url: String): String = withContext(Dispatchers.IO) {
        fetchSrtText(url)
    }

    /**
     * Charge un fichier de sous-titres depuis le système de fichiers local.
     */
    suspend fun loadFromFile(path: String): String = withContext(Dispatchers.IO) {
        val f = File(path)
        if (!f.exists() || !f.isFile) throw IllegalArgumentException("Fichier introuvable : $path")
        val bytes = f.readBytes()
        decompressIfGzip(bytes)
    }

    private suspend fun fetchSrtText(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "TemporaryUserAgent")
            .build()

        return Http.execute(request = req) { res ->
            val body = res.body ?: throw IllegalArgumentException("Réponse vide lors du téléchargement")
            val bytes = body.bytes()
            decompressIfGzip(bytes)
        }
    }

    private fun decompressIfGzip(bytes: ByteArray): String {
        val isGzip = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        val data = if (isGzip) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } else {
            bytes
        }
        val s = try {
            val utf8 = data.toString(Charsets.UTF_8)
            if (utf8.contains("\uFFFD")) data.toString(Charsets.ISO_8859_1) else utf8
        } catch (_: Exception) {
            data.toString(Charsets.ISO_8859_1)
        }
        return s
    }

    private suspend fun searchOpenSubtitlesV1(
        query: String,
        language: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        apiKey: String,
    ): List<SubtitleItem> {
        val lang2 = toIso2(language)
        val sb = StringBuilder("https://api.opensubtitles.com/api/v1/subtitles?query=")
            .append(URLEncoder.encode(query, "UTF-8"))
            .append("&languages=").append(lang2)

        if (year != null && year > 0) sb.append("&year=").append(year)
        if (season != null && season > 0) sb.append("&season_number=").append(season)
        if (episode != null && episode > 0) sb.append("&episode_number=").append(episode)

        val req = Request.Builder()
            .url(sb.toString())
            .header("Api-Key", apiKey)
            .header("User-Agent", "Vyzo v1.7.0")
            .header("Accept", "application/json")
            .build()

        return Http.execute(request = req) { res ->
            val body = res.body?.string().orEmpty()
            val root = LenientJson.parseToJsonElement(body).asObj() ?: return@execute emptyList()
            val data = root["data"].asArr() ?: return@execute emptyList()

            data.mapNotNull { elem ->
                val obj = elem.asObj() ?: return@mapNotNull null
                val id = obj.str("id")
                val attr = obj["attributes"].asObj() ?: return@mapNotNull null
                val lang = attr.str("language")
                val release = attr.str("release")
                val dlCount = attr.int("download_count")
                val ratings = attr.str("ratings").toFloatOrNull() ?: 0f

                val files = attr["files"].asArr()
                val firstFile = files?.firstOrNull()?.asObj()
                val fileId = firstFile?.str("file_id")?.toLongOrNull()
                val fileName = firstFile?.str("file_name").orEmpty()
                val title = fileName.ifBlank { release.ifBlank { "Sous-titre $id" } }

                SubtitleItem(
                    id = id,
                    title = title,
                    language = lang,
                    fileId = fileId,
                    source = "OpenSubtitles.com",
                    downloads = dlCount,
                    rating = ratings,
                )
            }
        }
    }

    private suspend fun getV1DownloadUrl(fileId: Long, apiKey: String): String? {
        val jsonBody = """{"file_id":$fileId}"""
        val req = Request.Builder()
            .url("https://api.opensubtitles.com/api/v1/download")
            .header("Api-Key", apiKey)
            .header("User-Agent", "Vyzo v1.7.0")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        return Http.execute(request = req) { res ->
            val body = res.body?.string().orEmpty()
            val root = LenientJson.parseToJsonElement(body).asObj()
            root?.str("link")
        }
    }

    private suspend fun searchPublicOpenSubtitles(
        query: String,
        language: String,
        season: Int?,
        episode: Int?,
    ): List<SubtitleItem> {
        val lang3 = toIso3(language)
        val encodedQuery = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val url = buildString {
            append("https://rest.opensubtitles.org/search/query-").append(encodedQuery)
            if (season != null && season > 0 && episode != null && episode > 0) {
                append("/season-").append(season).append("/episode-").append(episode)
            }
            append("/sublanguageid-").append(lang3)
        }

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "TemporaryUserAgent")
            .header("Accept", "application/json")
            .build()

        return Http.execute(request = req) { res ->
            val body = res.body?.string().orEmpty()
            val arr = LenientJson.parseToJsonElement(body).asArr() ?: return@execute emptyList()

            arr.mapNotNull { elem ->
                val obj = elem.asObj() ?: return@mapNotNull null
                val id = obj.str("IDSubtitleFile")
                val fileName = obj.str("SubFileName")
                val movieName = obj.str("MovieName")
                val lang = obj.str("SubLanguageID")
                val dlLink = obj.str("SubDownloadLink")
                val downloads = obj.int("SubDownloadsCnt")
                val rating = obj.str("SubRating").toFloatOrNull() ?: 0f

                val title = fileName.ifBlank { movieName.ifBlank { "Sous-titre $id" } }
                if (id.isBlank() && dlLink.isBlank()) return@mapNotNull null

                SubtitleItem(
                    id = id,
                    title = title,
                    language = toIso2(lang),
                    downloadUrl = dlLink,
                    source = "OpenSubtitles (Public)",
                    downloads = downloads,
                    rating = rating,
                )
            }
        }
    }

    private fun sanitizeQuery(raw: String): String {
        // Enlève les résolutions (1080p, 4K, 720p), les métadonnées (MULTI, VF, VOSTFR, x264...)
        return raw.replace(Regex("""\b(1080p|720p|2160p|4k|uhd|fhd|bluray|webrip|web-dl|hdtv|x264|x265|hevc|vostfr|vf|multi|french)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[._\-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun toIso2(lang: String): String = when (lang.lowercase().trim()) {
        "fre", "fra", "fr" -> "fr"
        "eng", "en" -> "en"
        "spa", "es" -> "es"
        "ger", "deu", "de" -> "de"
        "ita", "it" -> "it"
        "por", "pt" -> "pt"
        else -> lang.lowercase().trim().take(2)
    }

    fun toIso3(lang: String): String = when (toIso2(lang)) {
        "fr" -> "fre"
        "en" -> "eng"
        "es" -> "spa"
        "de" -> "ger"
        "it" -> "ita"
        "pt" -> "por"
        else -> "fre"
    }
}
