package fr.yannickuhrig.vyzo.data.epg

import org.xmlpull.v1.XmlPullParser
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

data class XmltvChannel(val id: String, val names: List<String>, val icon: String)

data class XmltvProgramme(
    val channel: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String,
    val category: String,
    val icon: String,
)

/**
 * Lecteur XMLTV en flux (XmlPullParser) : ne garde jamais tout le guide en mémoire.
 * [parserFactory] permet d'injecter l'implémentation (Android : Xml.newPullParser()).
 */
class XmltvParser(private val parserFactory: () -> XmlPullParser) {

    fun parse(
        input: InputStream,
        onChannel: (XmltvChannel) -> Unit,
        onProgramme: (XmltvProgramme) -> Unit,
        isCancelled: () -> Boolean = { false },
    ) {
        val p = parserFactory()
        // Mode tolérant : certains guides contiennent des entités HTML (&nbsp;…) ou du XML imparfait.
        runCatching { p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        // Android traite le DOCTYPE par défaut et refuse alors les entités définies ci-dessous : elles disparaîtraient du texte.
        runCatching { p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        val tail = TailInputStream(input)
        p.setInput(tail, null)
        for ((name, value) in listOf("nbsp" to " ", "eacute" to "é", "egrave" to "è", "agrave" to "à", "ccedil" to "ç", "rsquo" to "’", "hellip" to "…")) {
            runCatching { p.defineEntityReplacementText(name, value) }
        }
        var event = p.eventType
        var count = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> onChannel(readChannel(p))
                    "programme" -> {
                        readProgramme(p)?.let(onProgramme)
                        if (++count % 2000 == 0 && isCancelled()) return
                    }
                }
            }
            event = p.next()
        }
        // Le mode tolérant referme lui-même les balises ouvertes en fin de fichier : seul le flux dit si </tv> a été reçu.
        if ("</tv>" !in tail.text()) throw IOException("Guide XMLTV incomplet (téléchargement interrompu ?)")
    }

    private fun readChannel(p: XmlPullParser): XmltvChannel {
        val id = p.getAttributeValue(null, "id").orEmpty()
        val names = ArrayList<String>(2)
        var icon = ""
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "display-name" -> names += readText(p)
                    "icon" -> icon = p.getAttributeValue(null, "src").orEmpty()
                }
            }
        }
        return XmltvChannel(id, names.filter { it.isNotBlank() }, icon)
    }

    private fun readProgramme(p: XmlPullParser): XmltvProgramme? {
        val channel = p.getAttributeValue(null, "channel").orEmpty()
        val start = parseTime(p.getAttributeValue(null, "start"))
        val stopAttr = parseTime(p.getAttributeValue(null, "stop"))
        var title = ""
        var subTitle = ""
        var desc = ""
        var category = ""
        var icon = ""
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "title" -> if (title.isEmpty()) title = readText(p)
                    "sub-title" -> if (subTitle.isEmpty()) subTitle = readText(p)
                    "desc" -> if (desc.isEmpty()) desc = readText(p)
                    "category" -> if (category.isEmpty()) category = readText(p)
                    "icon" -> if (icon.isEmpty()) icon = p.getAttributeValue(null, "src").orEmpty()
                }
            }
        }
        if (channel.isEmpty() || start == null) return null
        val stop = stopAttr ?: (start + 30 * 60_000L)
        if (stop <= start) return null
        val fullDesc = if (subTitle.isNotBlank() && !desc.startsWith(subTitle)) "$subTitle\n$desc".trim() else desc
        return XmltvProgramme(channel, start, stop, title.ifBlank { "Sans titre" }, fullDesc, category, icon)
    }

    private fun readText(p: XmlPullParser): String {
        val sb = StringBuilder()
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT) sb.append(p.text)
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
        }
        return sb.toString().trim()
    }

    companion object {
        /** "20261027203000 +0100" → epoch ms. Le décalage est optionnel (UTC par défaut). */
        fun parseTime(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            val s = raw.trim()
            val digits = s.takeWhile { it.isDigit() }
            if (digits.length < 12) return null
            val y = digits.substring(0, 4).toInt()
            val mo = digits.substring(4, 6).toInt()
            val d = digits.substring(6, 8).toInt()
            val h = digits.substring(8, 10).toInt()
            val mi = digits.substring(10, 12).toInt()
            val se = if (digits.length >= 14) digits.substring(12, 14).toInt() else 0
            var epochSec = civilToEpochDays(y, mo, d) * 86400L + h * 3600L + mi * 60L + se
            val rest = s.substring(digits.length).trim()
            if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
                val sign = if (rest[0] == '-') -1 else 1
                val oh = rest.substring(1, 3).toIntOrNull() ?: 0
                val om = rest.substring(3, 5).toIntOrNull() ?: 0
                epochSec -= sign * (oh * 3600L + om * 60L)
            }
            return epochSec * 1000L
        }

        /** Algorithme "days from civil" (Howard Hinnant) : indépendant de java.time (API 23). */
        fun civilToEpochDays(year: Int, month: Int, day: Int): Long {
            val y = if (month <= 2) year - 1 else year
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097L + doe - 719468L
        }
    }
}

/** Garde les derniers octets lus, pour vérifier comment le flux se termine. */
private class TailInputStream(input: InputStream) : FilterInputStream(input) {
    private val ring = ByteArray(256)
    private var pos = 0
    private var full = false

    override fun read(): Int = super.read().also { if (it >= 0) keep(it.toByte()) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { n ->
        for (i in maxOf(off, off + n - ring.size) until off + n) keep(b[i])
    }

    private fun keep(x: Byte) {
        ring[pos] = x
        pos = (pos + 1) % ring.size
        if (pos == 0) full = true
    }

    fun text(): String = String(if (full) ring.copyOfRange(pos, ring.size) + ring.copyOfRange(0, pos) else ring.copyOf(pos), Charsets.ISO_8859_1)
}
