package fr.yannickuhrig.vyzo.util

/**
 * Recherche tolérante aux fautes de frappe : « spiderman » trouve « Spider-Man », « harry poter » trouve « Harry Potter »,
 * « gladiateur » trouve « Gladiator ». Utilisée quand la recherche exacte trouve peu de résultats.
 */
object FuzzyMatch {
    /** Mots d'une requête ou d'un nom, déjà normalisés par [TextNorm.search] (lettres a-z et chiffres, le reste sépare). */
    fun words(norm: String): List<String> {
        // Découpage à la main plutôt qu'avec une Regex : appelé pour des dizaines de milliers de noms.
        val out = ArrayList<String>(4)
        var start = -1
        for (i in norm.indices) {
            val ch = norm[i]
            val keep = ch in 'a'..'z' || ch in '0'..'9'
            if (keep && start < 0) start = i
            else if (!keep && start >= 0) { out += norm.substring(start, i); start = -1 }
        }
        if (start >= 0) out += norm.substring(start)
        return out
    }

    /** Nom découpé une fois pour toutes, pour comparer vite à chaque recherche. */
    class Entry<T>(val item: T, val words: List<String>, val compact: String)

    fun <T> prepare(item: T, nameNorm: String): Entry<T> {
        val w = words(nameNorm)
        return Entry(item, w, w.joinToString(""))
    }

    /** Fautes tolérées pour un mot : aucune sous 4 lettres, 1 jusqu'à 6 lettres, 2 au-delà. */
    private fun allowed(len: Int): Int = when {
        len < 4 -> 0
        len <= 6 -> 1
        else -> 2
    }

    /**
     * Note de [nameNorm] pour la requête [queryWords] (et sa version collée [queryCompact]) : null si le nom ne correspond
     * pas, sinon un nombre de fautes (0 = tous les mots trouvés tels quels). Chaque mot de la requête doit être retrouvé.
     */
    fun score(queryWords: List<String>, queryCompact: String, nameNorm: String): Int? {
        val nameWords = words(nameNorm)
        return score(queryWords, queryCompact, nameWords, nameWords.joinToString(""))
    }

    private fun score(queryWords: List<String>, queryCompact: String, nameWords: List<String>, nameCompact: String): Int? {
        if (queryWords.isEmpty()) return null
        if (nameWords.isEmpty()) return null
        // « spiderman » contre « spider-man », « x men » contre « X-Men » : on compare aussi sans espaces ni ponctuation.
        if (queryCompact.length >= 4 && nameCompact.contains(queryCompact)) return 0
        var total = 0
        for (q in queryWords) {
            var best = Int.MAX_VALUE
            for (w in nameWords) {
                val d = when {
                    w == q -> 0
                    // Début de mot (« pott » pour « potter ») : accepté sans faute.
                    q.length >= 3 && w.startsWith(q) -> 0
                    else -> {
                        val max = allowed(q.length)
                        if (max == 0) Int.MAX_VALUE
                        else {
                            // Comparer aussi au début d'un mot plus long : « harr » / « harry », « gladiateur » / « gladiator ».
                            val target = if (w.length > q.length + max) w.substring(0, q.length + max) else w
                            distance(q, target, max).takeIf { it <= max } ?: Int.MAX_VALUE
                        }
                    }
                }
                if (d < best) best = d
                if (best == 0) break
            }
            if (best == Int.MAX_VALUE) return null
            total += best
        }
        return total
    }

    /**
     * Distance de Damerau-Levenshtein (insertion, suppression, remplacement, inversion de deux lettres voisines),
     * arrêtée dès qu'elle dépasse [max] (renvoie alors max + 1).
     */
    fun distance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        val n = a.length
        val m = b.length
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    /** Garde les éléments dont le nom correspond à [query], les meilleurs d'abord (au plus [limit]). */
    fun <T> rank(query: String, items: List<T>, limit: Int, nameOf: (T) -> String): List<T> =
        rankPrepared(query, items.map { prepare(it, nameOf(it)) }, limit)

    /** Comme [rank], sur des noms déjà découpés par [prepare] (gardés en cache entre deux recherches). */
    fun <T> rankPrepared(query: String, entries: List<Entry<T>>, limit: Int): List<T> {
        val qWords = words(query)
        if (qWords.isEmpty()) return emptyList()
        val compact = qWords.joinToString("")
        val found = ArrayList<Pair<Entry<T>, Int>>()
        for (e in entries) score(qWords, compact, e.words, e.compact)?.let { found += e to it }
        return found
            .sortedWith(compareBy<Pair<Entry<T>, Int>> { it.second }.thenBy { it.first.compact.length })
            .take(limit)
            .map { it.first.item }
    }
}
