package fr.yannickuhrig.vyzo.player

import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.util.ChannelSeparator
import fr.yannickuhrig.vyzo.util.TextNorm

/**
 * Qualité annoncée dans le nom d'une chaîne : les fournisseurs proposent souvent la même chaîne en plusieurs versions
 * (« TF1 4K », « TF1 FHD », « TF1 HD », « TF1 SD »), qui partagent la même [ChannelEntity.matchKey].
 */
object QualityVariants {
    const val UHD = 4
    const val FHD = 3
    const val HD = 2
    const val SD = 1

    private val byToken = mapOf(
        "4k" to UHD, "8k" to UHD, "uhd" to UHD, "2160" to UHD, "2160p" to UHD,
        "fhd" to FHD, "fullhd" to FHD, "1080" to FHD, "1080p" to FHD, "1080i" to FHD,
        "hd" to HD, "hq" to HD, "720" to HD, "720p" to HD,
        "sd" to SD, "lq" to SD, "480" to SD, "480p" to SD, "576" to SD, "576p" to SD,
    )
    private val separators = Regex("[^a-z0-9]+")
    private val countryPrefix = Regex("^[^a-z0-9]*([a-z]{2,4})\\s*[:|]")

    /** Préfixe de pays (« FR: », « |BE| »…) : [ChannelEntity.matchKey] l'ignore, mais « BE: TF1 » n'est pas « FR: TF1 ». */
    private fun country(name: String): String = countryPrefix.find(TextNorm.search(name))?.groupValues?.get(1).orEmpty()

    /**
     * Qualité lue dans le nom. Sans mention, on suppose du HD : c'est le cas le plus courant
     * (« TF1 » à côté de « TF1 FHD » et « TF1 SD »).
     */
    fun rank(name: String): Int = announced(name).takeIf { it > 0 } ?: HD

    /** Qualité écrite dans le nom, 0 s'il n'en mentionne aucune. */
    fun announced(name: String): Int {
        val words = TextNorm.search(name).split(separators).filter { it.isNotEmpty() }
        var best = 0
        for ((i, w) in words.withIndex()) {
            val r = if (w == "hd" && i > 0 && words[i - 1] == "full") FHD else byToken[w] ?: 0
            if (r > best) best = r
        }
        return best
    }

    /**
     * Variante de qualité juste en dessous de [current] parmi [candidates] (même chaîne, même playlist),
     * null s'il n'y en a pas. À qualité égale, on garde l'ordre de la playlist.
     */
    fun lower(current: ChannelEntity, candidates: List<ChannelEntity>): ChannelEntity? {
        if (current.matchKey.isBlank()) return null
        val cur = rank(current.name)
        val land = country(current.name)
        return candidates
            .asSequence()
            .filter { it.uid != current.uid && it.playlistId == current.playlistId && it.matchKey == current.matchKey }
            .filter { !ChannelSeparator.matches(it.name) && country(it.name) == land }
            .map { it to rank(it.name) }
            .filter { it.second < cur }
            .sortedWith(compareByDescending<Pair<ChannelEntity, Int>> { it.second }.thenBy { it.first.position })
            .firstOrNull()?.first
    }
}

/**
 * Repère un direct qui saccade : plusieurs coupures pour recharger en peu de temps, ou une coupure qui s'éternise.
 * Le chargement du début (avant la première image) ne compte pas, ni les micro-coupures de moins de [minStallMs].
 */
class StallDetector(
    private val minStallMs: Long = 1_500,
    private val maxStalls: Int = 3,
    private val windowMs: Long = 60_000,
    private val longStallMs: Long = 12_000,
) {
    private var started = false
    private var bufferingSince = -1L
    private val stalls = ArrayDeque<Long>()

    /** Nouveau flux : on repart de zéro. */
    fun reset() {
        started = false
        bufferingSince = -1L
        stalls.clear()
    }

    /** À chaque changement d'état du lecteur. Renvoie true quand il vaut mieux baisser la qualité. */
    fun onState(state: PlaybackState, now: Long): Boolean {
        when (state) {
            PlaybackState.READY -> {
                if (bufferingSince >= 0 && now - bufferingSince >= minStallMs) stalls.addLast(bufferingSince)
                bufferingSince = -1L
                started = true
            }
            PlaybackState.BUFFERING -> if (started && bufferingSince < 0) bufferingSince = now
            PlaybackState.IDLE, PlaybackState.ENDED -> bufferingSince = -1L
        }
        return check(now)
    }

    /** À appeler régulièrement : une coupure en cours ne prévient pas quand elle dure. */
    fun tick(now: Long): Boolean = check(now)

    private fun check(now: Long): Boolean {
        while (stalls.isNotEmpty() && now - stalls.first() > windowMs) stalls.removeFirst()
        val ongoing = bufferingSince >= 0 && now - bufferingSince >= minStallMs
        if (bufferingSince >= 0 && now - bufferingSince >= longStallMs) return true
        return stalls.size + (if (ongoing) 1 else 0) >= maxStalls
    }
}
