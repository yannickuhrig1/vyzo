package fr.yannickuhrig.vyzo.data.epg

import fr.yannickuhrig.vyzo.data.net.Http
import java.text.Normalizer
import java.util.Locale

data class EpgCatalogEntry(val code: String, val label: String, val url: String, val sizeBytes: Long)

/** Guides XMLTV gratuits d'epgshare01, lus dans l'index du site : les fichiers disponibles changent régulièrement. */
object EpgCatalog {
    const val BASE_URL = "https://epgshare01.online/epgshare01/"
    const val SOURCE_PREFIX = "epgshare01 : "

    /** Le fichier complet pèse plus de 200 Mo, et l'autre ne contient que des chaînes factices. */
    private val EXCLUDED = setOf("ALL_SOURCES1", "DUMMY_CHANNELS")
    private val LINE = Regex("""href="epg_ripper_([A-Za-z0-9_]+)\.xml\.gz">[^<]*</a>\s+\S+\s+\S+\s+(\d+)""")
    private val TRAILING_NUMBER = Regex("""\d+$""")
    private val MARKS = Regex("""\p{M}+""")
    private val SEPARATORS = Regex("""[^\p{L}\p{N}]+""")

    suspend fun fetch(): List<EpgCatalogEntry> = parseListing(Http.getString(BASE_URL, ""))

    fun parseListing(html: String): List<EpgCatalogEntry> {
        val files = LINE.findAll(html).map { it.groupValues[1] to it.groupValues[2].toLong() }
            .filter { (code, _) -> code !in EXCLUDED }.distinctBy { it.first }.toList()
        val perBase = files.groupingBy { base(it.first) }.eachCount()
        return files.map { (code, size) ->
            val name = baseLabel(base(code))
            val label = if (perBase.getValue(base(code)) > 1) "$name ${code.removePrefix(base(code))}" else name
            EpgCatalogEntry(code, label, "${BASE_URL}epg_ripper_$code.xml.gz", size)
        }.sortedWith(compareBy({ !isCountry(base(it.code)) }, { fold(it.label) }))
    }

    /** Recherche sans accents, sur le nom du pays ou son code. */
    fun matches(entry: EpgCatalogEntry, query: String): Boolean {
        val wanted = fold(query)
        return wanted.isEmpty() || fold(entry.label).contains(wanted) || entry.code.startsWith(wanted, ignoreCase = true)
    }

    private fun base(code: String) = code.replace(TRAILING_NUMBER, "")

    private fun isCountry(base: String) = countryName(base) != null

    private fun countryName(base: String): String? {
        if (base.length != 2) return null
        val region = if (base == "UK") "GB" else base
        return Locale("", region).getDisplayCountry(Locale.FRENCH).takeIf { it.isNotBlank() && it != region }
    }

    private fun baseLabel(base: String) = countryName(base) ?: base.replace('_', ' ').trim()

    private fun fold(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT).replace(SEPARATORS, "")
}
