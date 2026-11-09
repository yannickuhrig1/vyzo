package fr.yannickuhrig.vyzo

import androidx.compose.runtime.Composable
import fr.yannickuhrig.vyzo.ui.VariantNav
import fr.yannickuhrig.vyzo.ui.home.HomeActions
import fr.yannickuhrig.vyzo.ui.vod.VariantDetails

/** Vyzo Mobile garde les écrans communs. */
object FlavorScreens {
    val home: (@Composable (HomeActions) -> Unit)? = null
    val details: VariantDetails? = null
    val nav: VariantNav? = null
}
