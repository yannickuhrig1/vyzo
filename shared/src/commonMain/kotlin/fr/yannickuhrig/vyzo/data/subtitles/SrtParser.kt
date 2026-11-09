package fr.yannickuhrig.vyzo.data.subtitles

data class SubtitleCue(
    val index: Int = 0,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

object SrtParser {
    // Format d'horodatage SRT standard : 00:01:20,000 --> 00:01:23,500 ou avec point 00:01:20.000
    private val TIME_REGEX = Regex(
        """(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{3})"""
    )

    private val HTML_TAG_REGEX = Regex("""<[^>]*>""")

    fun parse(bytes: ByteArray): List<SubtitleCue> {
        val s = try {
            val utf8 = bytes.toString(Charsets.UTF_8)
            if (utf8.contains("\uFFFD")) bytes.toString(Charsets.ISO_8859_1) else utf8
        } catch (_: Exception) {
            bytes.toString(Charsets.ISO_8859_1)
        }
        return parse(s)
    }

    fun parse(rawText: String): List<SubtitleCue> {
        if (rawText.isBlank()) return emptyList()
        // Nettoyage BOM et normalisation des fins de ligne
        val clean = rawText.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        val lines = clean.lines()
        val cues = mutableListOf<SubtitleCue>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                i++
                continue
            }

            // Vérifie si la ligne courante est un numéro d'index
            var cueIndex = line.toIntOrNull() ?: 0
            var timeLine = if (cueIndex > 0 && i + 1 < lines.size) lines[i + 1].trim() else line

            var match = TIME_REGEX.find(timeLine)
            if (match != null) {
                if (cueIndex > 0) i++ // On a sauté la ligne d'index
            } else {
                // Peut-être que la ligne courante était directement le timestamp
                match = TIME_REGEX.find(line)
                if (match != null) {
                    timeLine = line
                    cueIndex = cues.size + 1
                }
            }

            if (match != null) {
                val startMs = parseTime(
                    h = match.groupValues[1],
                    m = match.groupValues[2],
                    s = match.groupValues[3],
                    ms = match.groupValues[4]
                )
                val endMs = parseTime(
                    h = match.groupValues[5],
                    m = match.groupValues[6],
                    s = match.groupValues[7],
                    ms = match.groupValues[8]
                )

                i++
                val textLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().isNotEmpty()) {
                    val textLine = cleanText(lines[i])
                    if (textLine.isNotEmpty()) {
                        textLines.add(textLine)
                    }
                    i++
                }

                if (textLines.isNotEmpty() && endMs > startMs) {
                    cues.add(SubtitleCue(cueIndex, startMs, endMs, textLines.joinToString("\n")))
                }
            } else {
                i++
            }
        }

        return cues
    }

    private fun parseTime(h: String, m: String, s: String, ms: String): Long {
        val hours = if (h.isNotBlank()) h.toLongOrNull() ?: 0L else 0L
        val minutes = m.toLongOrNull() ?: 0L
        val seconds = s.toLongOrNull() ?: 0L
        val millis = ms.toLongOrNull() ?: 0L
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun cleanText(raw: String): String {
        return raw.replace(HTML_TAG_REGEX, "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .trim()
    }

    /**
     * Recherche la réplique correspondant à [positionMs], en tenant compte du décalage [delayMs].
     * Le décalage fonctionne comme suit :
     * Si les sous-titres sont en retard, un délai positif (ex: +500ms) applique [positionMs - delayMs].
     */
    fun findCueAt(cues: List<SubtitleCue>, positionMs: Long, delayMs: Long = 0L): SubtitleCue? {
        if (cues.isEmpty()) return null
        val effectiveMs = positionMs - delayMs
        return cues.firstOrNull { it.startMs <= effectiveMs && effectiveMs <= it.endMs }
    }
}
