package fr.yannickuhrig.vyzo.util

import java.security.MessageDigest
import java.text.Normalizer

object TextNorm {
    private val marks = Regex("\\p{Mn}+")
    private val countryPrefix = Regex("^(\\|[a-z0-9]{2,4}\\|\\s*|[a-z]{2,3}\\s*[:|]\\s+|[a-z]{2,3}:\\s*)")
    private val brackets = Regex("[\\[(][^\\])]*[\\])]")
    private val qualityTokens = Regex("\\b(hd|fhd|uhd|sd|hq|4k|8k|hevc|h265|h264|1080p?|720p?|2160p?|50fps|60fps|backup|vip|raw)\\b")
    private val nonAlnum = Regex("[^a-z0-9+]")

    /** Retire accents et signes combinants ; NFKD ramène aussi les caractères « pleine largeur » (ＰＯＲＮ) à l'ASCII. */
    fun stripAccents(s: String): String = marks.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "")

    /** Normalisation légère pour la recherche plein texte. */
    fun search(s: String): String = stripAccents(s).lowercase().trim()

    /** Normalisation agressive pour rapprocher un nom de chaîne d'un nom XMLTV. */
    fun matchKey(s: String): String {
        var t = stripAccents(s).lowercase().trim()
        t = countryPrefix.replace(t, "")
        t = brackets.replace(t, " ")
        t = qualityTokens.replace(t, " ")
        t = nonAlnum.replace(t, "")
        return t
    }
}

/** « 1 série », « 3 séries » ; le français met le singulier à 0 et à 1. */
fun plural(n: Int, singular: String, pluralForm: String = singular + "s"): String =
    "$n ${if (n > 1) pluralForm else singular}"

fun String.md5(): String {
    val d = MessageDigest.getInstance("MD5").digest(toByteArray())
    return d.joinToString("") { "%02x".format(it) }
}

fun String.sha256Upper(): String {
    val d = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return d.joinToString("") { "%02X".format(it) }
}

fun String.shortHash(): String = md5().take(16)

/** Hôte d'une adresse web (équivalent tolérant d'Uri.parse(url).host), null s'il n'y en a pas. */
fun urlHost(url: String): String? {
    val u = url.trim()
    return runCatching { java.net.URI(u).host }.getOrNull()
        ?: Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/:?#@]+@)?([^/:?#]+)").find(u)?.groupValues?.get(2)
}

/**
 * Reconnaît les catégories « adultes » à leur nom, pour le contrôle parental automatique.
 * Seuls les noms de catégories passent par ici : un titre (« xXx », « Magic Mike XXL », « Adult Swim ») ne dit rien
 * du contenu, et un film ou une chaîne se verrouille à la main. D'où des marqueurs sans ambiguïté, en mots entiers.
 */
object AdultDetector {
    private val pattern = Regex(
        "(^|[^a-z0-9])(xxx+|adult(?:e|es|s)?|for adults|porn[a-z]*|p[o0]rno|erotic[a-z]*|erotique[s]?|erotisme|hentai|x[ -]?rated|nsfw|" +
            "brazzers|dorcel|hustler|penthouse|playboy|colmax|redlight|pink\\s*x)([^a-z0-9]|$)" +
            "|(^|[^0-9])(18\\s*\\+|\\+\\s*18|18\\s*plus)([^0-9]|$)",
    )
    private val notAdult = Regex("adult\\s*swim")

    fun isAdultCategory(name: String): Boolean {
        if (name.isBlank()) return false
        val norm = notAdult.replace(TextNorm.search(name), " ")
        return pattern.containsMatchIn(norm)
    }
}

/**
 * Fausses chaînes que les fournisseurs glissent dans leurs listes comme intertitres
 * (« ░▒▓█ FRANCE FHD █▓▒░ », « ##### SPORTS ##### », « ▂ ▄ ▅ ▆ PERU ▆ ▅ ▄ ▂ ») : elles ne diffusent rien.
 * Reconnues à leur nom encadré d'au moins deux symboles de chaque côté. Les préfixes de pays (« [FR] », « |UK| »)
 * et les noms ornés d'un seul symbole (« ⸗ Peru ◊ … ») restent des chaînes.
 */
object ChannelSeparator {
    private val edge = Regex("^[^\\p{L}\\p{N}\\[\\](){}|<>\"'«»]*")

    fun matches(name: String): Boolean {
        val s = name.trim()
        if (s.isEmpty()) return false
        if (s.none { it.isLetterOrDigit() }) return true
        fun symbols(t: String) = edge.find(t)?.value.orEmpty().count { !it.isWhitespace() }
        return symbols(s) >= 2 && symbols(s.reversed()) >= 2
    }
}
