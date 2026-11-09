package fr.yannickuhrig.vyzo.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.data.prefs.AppSettings

/** Où se trouve le menu principal sur TV et PC : rail à gauche ou à droite, barre en haut ou en bas. */
enum class NavPosition(val label: String) {
    LEFT("À gauche"),
    RIGHT("À droite"),
    TOP("En haut"),
    BOTTOM("En bas"),
}

/** Rail latéral : replié (s'ouvre au focus), toujours ouvert (avec les noms), ou caché (s'ouvre avec Retour). */
enum class NavStyle(val label: String, val detail: String) {
    AUTO("Replié", "Les icônes seules ; le menu s'ouvre quand on y va"),
    EXPANDED("Toujours ouvert", "Les icônes et les noms, en permanence"),
    HIDDEN("Caché", "Tout l'écran pour le contenu ; Retour ouvre le menu"),
}

/** Disposition du menu principal, d'après les réglages. */
data class NavLayout(val position: NavPosition, val style: NavStyle) {
    /** Rail sur le côté (sinon, barre en haut ou en bas). */
    val isRail: Boolean get() = position == NavPosition.LEFT || position == NavPosition.RIGHT

    /** Place que le menu prend en permanence à l'écran (largeur du rail ou hauteur de la barre). */
    val reserved: Dp get() = when {
        !isRail -> BAR_HEIGHT
        style == NavStyle.EXPANDED -> RAIL_OPEN
        style == NavStyle.HIDDEN -> 0.dp
        else -> RAIL_CLOSED
    }

    companion object {
        val RAIL_CLOSED = 76.dp
        val RAIL_OPEN = 240.dp
        val BAR_HEIGHT = 64.dp

        fun from(s: AppSettings) = NavLayout(
            NavPosition.entries.firstOrNull { it.name == s.navPosition } ?: NavPosition.LEFT,
            NavStyle.entries.firstOrNull { it.name == s.navStyle } ?: NavStyle.AUTO,
        )
    }
}
