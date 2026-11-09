package fr.yannickuhrig.vyzo.data.vod

import fr.yannickuhrig.vyzo.data.db.StreamQualityEntity
import fr.yannickuhrig.vyzo.data.db.WatchProgressEntity
import fr.yannickuhrig.vyzo.player.QualityVariants
import fr.yannickuhrig.vyzo.player.StreamInfo

/** Libellé court d'une définition : « 4K », « 1080p », « 720p » ou « SD ». */
fun resolutionShort(width: Int, height: Int): String =
    StreamInfo(width = width, height = height).resolutionLabel.substringBefore(' ')

// Test rapide avant de découper le nom : une section peut compter des milliers de films.
private val UhdHints = listOf("4k", "uhd", "2160", "8k")

/** Vrai si un nom de film ou de section annonce de la 4K. */
fun announcesUhd(name: String): Boolean =
    UhdHints.any { name.contains(it, ignoreCase = true) } && QualityVariants.announced(name) == QualityVariants.UHD

/**
 * Sait quels films et séries sont en 4K. Une définition relevée sur le flux fait foi ;
 * à défaut, on se fie au titre ou au nom de la section.
 */
class VodQualityIndex(
    private val uhdCategories: Set<String> = emptySet(),
    private val measured: Map<String, StreamQualityEntity> = emptyMap(),
) {
    fun isUhd(uid: String, title: String, categoryUid: String): Boolean {
        val real = measured[uid]
        return if (real != null) resolutionShort(real.width, real.height) == "4K"
        else categoryUid in uhdCategories || announcesUhd(title)
    }
}

/** Avancement par contenu ; une série prend celui de son dernier épisode regardé. */
fun resumeFractions(progress: List<WatchProgressEntity>): Map<String, Float> {
    val out = HashMap<String, Float>()
    progress.filter { it.uid.isNotBlank() && it.durationMs > 0 }.sortedBy { it.updatedAt }.forEach { p ->
        val fraction = (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f)
        out[p.uid] = fraction
        if (p.parentUid.isNotBlank()) out[p.parentUid] = fraction
    }
    return out
}
