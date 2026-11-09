package fr.yannickuhrig.vyzo.data.catchup

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Construit les URL de replay pour les playlists M3U selon les conventions répandues
 * (attributs catchup / catchup-source / catchup-days, types default, append, shift, flussonic, xc).
 */
object CatchupBuilder {

    fun build(
        liveUrl: String,
        type: String,
        source: String,
        startMs: Long,
        stopMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        tz: TimeZone = TimeZone.getDefault(),
    ): String? {
        val t = type.trim().lowercase()
        return when {
            t == "append" -> if (source.isBlank()) null else liveUrl + substitute(source, startMs, stopMs, nowMs, tz)
            t == "shift" || t == "timeshift" -> {
                val sep = if (liveUrl.contains('?')) "&" else "?"
                "$liveUrl${sep}utc=${startMs / 1000}&lutc=${nowMs / 1000}"
            }
            t.startsWith("flussonic") || t == "fs" -> flussonic(liveUrl, startMs, stopMs)
            t == "xc" -> xc(liveUrl, startMs, stopMs, tz)
            source.isNotBlank() -> {
                val s = substitute(source, startMs, stopMs, nowMs, tz)
                if (s.contains("://")) s else liveUrl + s
            }
            // Pas de modèle mais replay annoncé : on tente l'ajout des paramètres "shift".
            t == "default" || t.isEmpty() -> {
                val sep = if (liveUrl.contains('?')) "&" else "?"
                "$liveUrl${sep}utc=${startMs / 1000}&lutc=${nowMs / 1000}"
            }
            else -> null
        }
    }

    private val braceRegex = Regex("\\{(utc|start|utcend|end|lutc|now|timestamp|duration|offset)(?::([^}]*))?\\}")
    private val dollarRegex = Regex("\\$\\{(start|end|timestamp|now|duration|offset)(?::([^}]*))?\\}")
    private val dateTokens = Regex("\\{(Y|m|d|H|M|S)\\}")

    fun substitute(template: String, startMs: Long, stopMs: Long, nowMs: Long, tz: TimeZone): String {
        val start = startMs / 1000
        val end = stopMs / 1000
        val now = nowMs / 1000
        val duration = end - start
        val offset = now - start

        fun value(name: String, arg: String?): String {
            val epoch = when (name) {
                "utc", "start" -> start
                "utcend", "end" -> end
                "lutc", "now", "timestamp" -> now
                "duration" -> {
                    val div = arg?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
                    return (duration / div).toString()
                }
                "offset" -> {
                    val div = arg?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
                    return (offset / div).toString()
                }
                else -> start
            }
            if (arg.isNullOrBlank()) return epoch.toString()
            return formatDate(epoch * 1000, arg, tz)
        }

        var out = braceRegex.replace(template) { m -> value(m.groupValues[1], m.groupValues[2].ifEmpty { null }) }
        out = dollarRegex.replace(out) { m -> value(m.groupValues[1], m.groupValues[2].ifEmpty { null }) }
        out = dateTokens.replace(out) { m -> formatDate(startMs, m.groupValues[1], tz) }
        return out
    }

    /** Format façon strftime simplifié : Y m d H M S (ex. "Y-m-d:H-M"). */
    fun formatDate(ms: Long, pattern: String, tz: TimeZone): String {
        val sb = StringBuilder()
        for (c in pattern) {
            sb.append(
                when (c) {
                    'Y' -> "yyyy"; 'm' -> "MM"; 'd' -> "dd"; 'H' -> "HH"; 'M' -> "mm"; 'S' -> "ss"
                    else -> if (c.isLetter()) "'$c'" else c.toString()
                },
            )
        }
        val f = SimpleDateFormat(sb.toString(), Locale.US)
        f.timeZone = tz
        return f.format(ms)
    }

    private fun flussonic(url: String, startMs: Long, stopMs: Long): String? {
        val start = startMs / 1000
        val duration = (stopMs - startMs) / 1000
        val clean = url.substringBefore('?')
        val query = url.substringAfter('?', "").let { if (it.isNotEmpty()) "?$it" else "" }
        return when {
            clean.endsWith("/mpegts") -> clean.removeSuffix("mpegts") + "timeshift_abs-$start.ts$query"
            clean.endsWith(".m3u8") -> {
                val base = clean.substringBeforeLast('/')
                val name = clean.substringAfterLast('/').removeSuffix(".m3u8")
                "$base/$name-$start-$duration.m3u8$query"
            }
            else -> "$clean/timeshift_abs-$start.ts$query"
        }
    }

    /** URL Xtream "http://h:p/(live/)user/pass/id(.ext)" → timeshift. */
    private fun xc(url: String, startMs: Long, stopMs: Long, tz: TimeZone): String? {
        val m = Regex("^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\\d+)(?:\\.[a-z0-9]+)?$", RegexOption.IGNORE_CASE)
            .find(url.substringBefore('?')) ?: return null
        val (host, user, pass, id) = m.destructured
        val durationMin = ((stopMs - startMs) / 60_000).coerceAtLeast(1)
        val f = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = tz }
        return "$host/timeshift/$user/$pass/$durationMin/${f.format(startMs)}/$id.ts"
    }
}
