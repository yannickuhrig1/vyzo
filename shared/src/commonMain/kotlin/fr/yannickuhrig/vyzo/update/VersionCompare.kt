package fr.yannickuhrig.vyzo.update

/**
 * Comparateur de versions SemVer avec support des pre-releases (-alpha, -beta, -rc).
 */
object VersionCompare {

    fun normalize(v: String): String = v.trim().removePrefix("v").removePrefix("V")

    fun isBetaOrRc(tagOrVersion: String): Boolean {
        val s = tagOrVersion.lowercase()
        return s.contains("-beta") || s.contains("-rc") || s.contains(".beta") || s.contains(".rc")
    }

    /**
     * Compare deux versions SemVer.
     * > 0 si [v1] > [v2], < 0 si [v1] < [v2], 0 si équivalentes.
     */
    fun compare(v1: String, v2: String): Int {
        val s1 = normalize(v1)
        val s2 = normalize(v2)
        if (s1 == s2) return 0

        val (core1, pre1) = splitPre(s1)
        val (core2, pre2) = splitPre(s2)

        val c1 = core1.split('.').map { it.toIntOrNull() ?: 0 }
        val c2 = core2.split('.').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(c1.size, c2.size)
        for (i in 0 until maxLen) {
            val num1 = c1.getOrElse(i) { 0 }
            val num2 = c2.getOrElse(i) { 0 }
            if (num1 != num2) return num1.compareTo(num2)
        }

        // Une version sans suffixe (finale) l'emporte sur une pre-release
        if (pre1.isEmpty() && pre2.isNotEmpty()) return 1
        if (pre1.isNotEmpty() && pre2.isEmpty()) return -1
        if (pre1.isEmpty() && pre2.isEmpty()) return 0

        val r1 = rankOf(pre1)
        val r2 = rankOf(pre2)
        if (r1 != r2) return r1.compareTo(r2)

        val n1 = numIn(pre1)
        val n2 = numIn(pre2)
        return n1.compareTo(n2)
    }

    private fun splitPre(v: String): Pair<String, String> {
        val dash = v.indexOf('-')
        return if (dash >= 0) v.substring(0, dash) to v.substring(dash + 1) else v to ""
    }

    private fun rankOf(p: String): Int {
        val s = p.lowercase()
        return when {
            s.startsWith("alpha") -> 1
            s.startsWith("beta") -> 2
            s.startsWith("rc") -> 3
            else -> 0
        }
    }

    private fun numIn(p: String): Int {
        val digits = p.filter { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }

    fun toSyntheticCode(version: String): Int {
        val norm = normalize(version)
        val (core, pre) = splitPre(norm)
        val parts = core.split('.').map { it.toIntOrNull() ?: 0 }
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        val rank = if (pre.isEmpty()) 4 else rankOf(pre)
        val num = numIn(pre)
        return major * 100_000 + minor * 10_000 + patch * 1_000 + rank * 100 + num
    }
}
