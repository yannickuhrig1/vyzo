package fr.yannickuhrig.vyzo.data.source.m3u

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

data class M3uHeader(val attrs: Map<String, String>) {
    /** URL(s) du guide XMLTV déclarées dans l'en-tête (url-tvg, x-tvg-url…), séparées par des virgules. */
    val epgUrls: List<String>
        get() = listOf("url-tvg", "x-tvg-url", "tvg-url")
            .mapNotNull { attrs[it] }
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.startsWith("http", ignoreCase = true) }
            .distinct()
}

data class M3uEntry(
    val name: String,
    val url: String,
    val attrs: Map<String, String>,
    val group: String,
    val userAgent: String,
    val referer: String,
    val kodiProps: Map<String, String>,
    val durationSecs: Int,
) {
    val tvgId: String get() = attrs["tvg-id"].orEmpty().trim()
    val logo: String get() = (attrs["tvg-logo"] ?: attrs["logo"]).orEmpty().trim()
    val number: Int get() = (attrs["tvg-chno"] ?: attrs["channel-number"] ?: attrs["tvg-num"])?.trim()?.toIntOrNull() ?: 0
}

/**
 * Lecteur M3U/M3U8 en flux : appelle [onEntry] pour chaque entrée sans charger tout le fichier en mémoire.
 * Gère les attributs IPTV usuels, #EXTGRP, #EXTVLCOPT, #KODIPROP et les en-têtes "url|User-Agent=…".
 */
object M3uParser {
    private val attrRegex = Regex("([A-Za-z0-9_\\-]+)\\s*=\\s*\"([^\"]*)\"")
    private val attrUnquotedRegex = Regex("([A-Za-z0-9_\\-]+)=([^\\s\",]+)")

    fun parse(input: InputStream, onHeader: (M3uHeader) -> Unit = {}, onEntry: (M3uEntry) -> Unit) {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024)
        var headerSent = false
        var pendingInfo: String? = null
        var pendingGroup: String? = null
        var ua = ""
        var referer = ""
        val kodi = HashMap<String, String>()

        fun reset() {
            pendingInfo = null; pendingGroup = null; ua = ""; referer = ""; kodi.clear()
        }

        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    if (!headerSent) {
                        headerSent = true
                        onHeader(M3uHeader(parseAttrs(line.substringAfter("#EXTM3U", ""))))
                    }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingInfo = line
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val k = opt.substringBefore('=').trim().lowercase()
                    val v = opt.substringAfter('=', "").trim()
                    when (k) {
                        "http-user-agent" -> ua = v
                        "http-referrer", "http-referer" -> referer = v
                    }
                }
                line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    kodi[opt.substringBefore('=').trim().lowercase()] = opt.substringAfter('=', "").trim()
                }
                line.startsWith("#") -> Unit
                else -> {
                    val info = pendingInfo
                    if (!headerSent) {
                        headerSent = true
                        onHeader(M3uHeader(emptyMap()))
                    }
                    var url = line
                    var entryUa = ua
                    var entryRef = referer
                    // Format Kodi : http://url|User-Agent=xxx&Referer=yyy
                    val pipe = url.indexOf('|')
                    if (pipe > 0) {
                        val opts = url.substring(pipe + 1)
                        url = url.substring(0, pipe)
                        opts.split('&').forEach { kv ->
                            val k = kv.substringBefore('=').trim().lowercase()
                            val v = java.net.URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
                            when (k) {
                                "user-agent" -> entryUa = v
                                "referer", "referrer" -> entryRef = v
                            }
                        }
                    }
                    if (info != null) {
                        val (left, name) = splitInfo(info)
                        val attrs = parseAttrs(left)
                        val duration = left.substringAfter(':', "").trim().takeWhile { it == '-' || it.isDigit() }.toIntOrNull() ?: -1
                        val group = attrs["group-title"]?.trim().takeUnless { it.isNullOrEmpty() }
                            ?: pendingGroup.orEmpty()
                        val ent = M3uEntry(
                            name = name.ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { url.substringAfterLast('/') },
                            url = url,
                            attrs = attrs,
                            group = group,
                            userAgent = entryUa.ifBlank { attrs["user-agent"].orEmpty() },
                            referer = entryRef.ifBlank { attrs["referer"].orEmpty() },
                            kodiProps = HashMap(kodi),
                            durationSecs = duration,
                        )
                        onEntry(ent)
                    } else if (url.contains("://")) {
                        onEntry(
                            M3uEntry(
                                name = url.substringAfterLast('/'), url = url, attrs = emptyMap(),
                                group = pendingGroup.orEmpty(), userAgent = entryUa, referer = entryRef,
                                kodiProps = HashMap(kodi), durationSecs = -1,
                            ),
                        )
                    }
                    reset()
                }
            }
        }
        if (!headerSent) onHeader(M3uHeader(emptyMap()))
    }

    /** Sépare "#EXTINF:-1 attrs…,Nom" en (partie attributs, nom) en ignorant les virgules entre guillemets. */
    fun splitInfo(line: String): Pair<String, String> {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return line.substring(0, i) to line.substring(i + 1).trim()
        }
        return line to ""
    }

    fun parseAttrs(s: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        attrRegex.findAll(s).forEach { map[it.groupValues[1].lowercase()] = it.groupValues[2] }
        attrUnquotedRegex.findAll(s).forEach { m ->
            val k = m.groupValues[1].lowercase()
            if (k !in map) map[k] = m.groupValues[2]
        }
        return map
    }
}

enum class M3uKind { LIVE, MOVIE, SERIES }

data class EpisodeInfo(val seriesName: String, val season: Int, val episode: Int)

object M3uClassifier {
    private val vodExt = Regex("\\.(mp4|mkv|avi|mov|wmv|m4v|webm|flv)(\\?.*)?$", RegexOption.IGNORE_CASE)
    private val episodeRegex = Regex("^(.*?)[\\s._\\-]*[Ss](\\d{1,2})[\\s._\\-]*[EeXx](\\d{1,3})\\b.*$")
    private val episodeRegex2 = Regex("^(.*?)\\s+(\\d{1,2})x(\\d{1,3})\\b.*$")

    fun kind(e: M3uEntry): M3uKind {
        val u = e.url.lowercase()
        return when {
            "/series/" in u -> M3uKind.SERIES
            "/movie/" in u || "/movies/" in u -> M3uKind.MOVIE
            vodExt.containsMatchIn(u) -> if (episode(e.name) != null) M3uKind.SERIES else M3uKind.MOVIE
            else -> M3uKind.LIVE
        }
    }

    fun episode(name: String): EpisodeInfo? {
        val m = episodeRegex.find(name.trim()) ?: episodeRegex2.find(name.trim()) ?: return null
        val series = m.groupValues[1].trim().trimEnd('-', '.', '_', ' ').ifBlank { return null }
        return EpisodeInfo(series, m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }
}
