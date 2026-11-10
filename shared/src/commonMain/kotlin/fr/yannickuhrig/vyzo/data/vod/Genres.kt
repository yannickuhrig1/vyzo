package fr.yannickuhrig.vyzo.data.vod

import fr.yannickuhrig.vyzo.util.TextNorm

/** Un genre regroupé automatiquement (« Action », « Comédie »…), quel que soit le nom des catégories du fournisseur. */
data class Genre(val id: String, val label: String, val words: Set<String>, val phrases: List<String> = emptyList())

/** Titre (film ou série) vu par la détection des genres : son champ « genre » et le nom de sa catégorie. */
data class GenreSource(val uid: String, val genre: String, val categoryName: String)

/**
 * Genres automatiques : les fournisseurs rangent souvent leurs films dans des centaines de catégories
 * (« FR | ACTION 4K », « VOD - Comédie », « NETFLIX FR »…) et ne remplissent pas toujours le genre.
 * On reconnaît les genres par mots-clés (français, anglais, espagnol, italien, allemand, portugais, turc) dans le
 * genre du titre et dans le nom de sa catégorie, puis on regroupe.
 */
object Genres {
    val ALL: List<Genre> = listOf(
        Genre("action", "Action", setOf("action", "accion", "azione", "acao", "aksiyon")),
        Genre("aventure", "Aventure", setOf("aventure", "aventures", "adventure", "adventures", "aventura", "abenteuer", "avventura", "macera")),
        Genre(
            "animation", "Animation",
            setOf("animation", "animations", "animacion", "animazione", "animacao", "animasyon", "cartoon", "cartoons", "zeichentrick"),
            listOf("dessin anime", "dessins anime", "dessins animes", "dessin animes"),
        ),
        Genre("anime", "Anime et manga", setOf("anime", "animes", "manga", "mangas")),
        Genre("comedie", "Comédie", setOf("comedie", "comedies", "comedy", "comedia", "komodie", "commedia", "komedi", "humour", "humor")),
        Genre("policier", "Policier et crime", setOf("crime", "crimes", "policier", "policiers", "polar", "polars", "criminal", "crimen", "krimi", "gangster", "poliziesco", "suc")),
        Genre("documentaire", "Documentaire", setOf("documentaire", "documentaires", "documentary", "documentaries", "docu", "docs", "documental", "documentales", "dokumentation", "doku", "documentario", "belgesel")),
        Genre("drame", "Drame", setOf("drame", "drames", "drama", "dramas", "dramma", "dram")),
        Genre("famille", "Famille et enfants", setOf("famille", "family", "familia", "familie", "famiglia", "kids", "kid", "enfants", "enfant", "jeunesse", "children", "kinder", "infantil", "cocuk", "bambini")),
        Genre("fantastique", "Fantastique", setOf("fantastique", "fantasy", "fantasia", "fantastico", "fantastik")),
        Genre("horreur", "Horreur", setOf("horreur", "horror", "epouvante", "terror", "terreur", "korku", "orrore")),
        Genre("romance", "Romance", setOf("romance", "romances", "romantique", "romantic", "romantik", "romantico", "romantica", "romantici")),
        Genre(
            "sf", "Science-fiction", setOf("scifi", "sf"),
            listOf("science fiction", "sci fi", "ciencia ficcion", "fantascienza", "bilim kurgu", "ficcao cientifica"),
        ),
        Genre("thriller", "Thriller", setOf("thriller", "thrillers", "suspense", "gerilim")),
        Genre("guerre", "Guerre", setOf("guerre", "war", "guerra", "krieg", "kriegsfilm", "savas")),
        Genre("western", "Western", setOf("western", "westerns")),
        Genre("musique", "Musique", setOf("musique", "music", "musical", "musicals", "musicale", "musik", "muzik", "concert", "concerts")),
        Genre("histoire", "Histoire", setOf("historique", "historiques", "history", "historical", "historia", "historico", "histoire", "storico", "tarih", "historisch")),
        Genre("mystere", "Mystère", setOf("mystere", "mystery", "misterio", "mistero", "gizem")),
        Genre("biopic", "Biographie", setOf("biographie", "biography", "biopic", "biopics", "biografia", "biyografi")),
        Genre("sport", "Sport", setOf("sport", "sports", "deportes", "deporte", "spor")),
    )

    private val byId = ALL.associateBy { it.id }

    fun label(id: String): String = byId[id]?.label ?: id

    /**
     * Genre principal d'un titre : le premier reconnu dans l'ordre du champ (« Drame, Action » → drame).
     * Accepte aussi un libellé déjà traduit (« Comédie », « Famille et enfants »).
     */
    fun primary(genre: String): String? =
        genre.split(',', '/', '|', '&', ';').firstNotNullOfOrNull { detect(it).firstOrNull() }

    /** Genre principal en français, sinon le premier genre tel que le fournisseur l'écrit. */
    fun primaryLabel(genre: String): String =
        primary(genre)?.let(::label) ?: genre.split(',', '/', '|', ';').firstOrNull()?.trim().orEmpty()

    private val separators = Regex("[^a-z0-9]+")

    /** Genres reconnus dans un texte (genre d'un titre ou nom de catégorie). */
    fun detect(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val norm = TextNorm.search(text)
        val words = norm.split(separators).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptySet()
        // « Dessins animés » : de l'animation, pas de l'anime japonais.
        val wordSet = words.filterIndexed { i, w -> !(w.startsWith("anime") && i > 0 && words[i - 1].startsWith("dessin")) }.toHashSet()
        val joined = " " + words.joinToString(" ") + " "
        val out = LinkedHashSet<String>()
        for (g in ALL) {
            if (g.words.any { it in wordSet } || g.phrases.any { joined.contains(" $it ") }) out += g.id
        }
        return out
    }

    /**
     * Regroupe les titres par genre : genre id → uids, dans l'ordre reçu.
     * Le nom de la catégorie compte autant que le genre du titre (beaucoup de fournisseurs laissent le genre vide).
     */
    fun index(items: List<GenreSource>): Map<String, List<String>> {
        // Les mêmes noms de catégorie et de genre reviennent des milliers de fois : chacun n'est analysé qu'une fois.
        val byCategory = HashMap<String, Set<String>>()
        val byGenre = HashMap<String, Set<String>>()
        val out = LinkedHashMap<String, MutableList<String>>()
        for (it in items) {
            val fromCat = byCategory.getOrPut(it.categoryName) { detect(it.categoryName) }
            val ids = if (it.genre.isBlank()) fromCat else fromCat + byGenre.getOrPut(it.genre) { detect(it.genre) }
            for (id in ids) out.getOrPut(id) { ArrayList() } += it.uid
        }
        return out
    }
}
