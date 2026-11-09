// Dérivé de Models.kt d'IPVIO Stream, Copyright (C) 2026 IPVIO Stream contributors, GNU GPL 3.
// Modifié pour Vyzo le 2026-11-08 : seuls les modèles utiles aux écrans sont repris, les données viennent de Room.
package fr.yannickuhrig.vyzo.tv

enum class Kind { LIVE, MOVIE, SERIES }

data class Variant(val url: String, val label: String, val quality: String?, val lang: String?)

data class Episode(
    val season: Int,
    val num: Int,
    val title: String,
    val url: String,
    val variants: List<Variant> = emptyList(),
    val quality: String? = null,
)

/** Langue inconnue pour [Item.lang]. */
const val LANG_UNKNOWN = "?"

/** Élément tel que les écrans TV l'affichent : chaîne, film ou série. */
data class Item(
    val id: String,
    val kind: Kind,
    val title: String,
    val year: Int?,
    val url: String,
    val logo: String?,
    val group: String,
    val episodes: List<Episode> = emptyList(),
    val quality: String? = null,
    val tvgId: String? = null,
    val lang: String = LANG_UNKNOWN,
    /** Rang de la playlist parmi celles affichées ensemble (0 = principale). */
    val src: Int = 0,
) {
    // Champ plutôt que « by lazy » : deux objets de moins par titre sur des catalogues de plus de 100 000 titres.
    private var mkCache: String? = null

    /** Clé du cache de métadonnées, calculée une fois par titre. */
    val mk: String get() = mkCache ?: (kind.name + "|" + title.lowercase() + "|" + (year ?: 0)).also { mkCache = it }
}
