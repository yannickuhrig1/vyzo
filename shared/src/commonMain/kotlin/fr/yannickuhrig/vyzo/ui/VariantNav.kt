package fr.yannickuhrig.vyzo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.ui.player.VariantPlayer

/** Ce qu'un menu principal propre à une variante reçoit : la position vient des réglages, le focus reste géré par [MainNav]. */
class MenuArgs(
    val layout: NavLayout,
    val route: String?,
    val entries: List<Dest>,
    /** Une entrée par écran principal : Retour y ramène le focus. */
    val requesters: Map<String, FocusRequester>,
    val onSelect: (String) -> Unit,
    val onProfile: () -> Unit,
    val onFocusInside: (Boolean) -> Unit,
    /** Déjà aligné sur le bord choisi. */
    val modifier: Modifier,
)

class SearchActions(
    val onChannel: (ChannelEntity) -> Unit,
    val onMovie: (String) -> Unit,
    val onSeries: (String) -> Unit,
    val onCatchup: (ChannelEntity, ProgramEntity) -> Unit,
)

/** Menu, recherche et catalogues propres à une variante de l'appli ; sans elle, les écrans communs s'affichent. */
class VariantNav(
    val menu: @Composable (MenuArgs) -> Unit,
    val search: @Composable (SearchActions) -> Unit,
    val browse: @Composable (kind: ContentKind, onOpen: (String) -> Unit) -> Unit,
    val live: @Composable (onOpenPlayer: (ChannelEntity, String) -> Unit) -> Unit,
    val sport: @Composable (onWatch: (ChannelEntity) -> Unit, onCatchup: (ChannelEntity, ProgramEntity) -> Unit) -> Unit,
    val player: VariantPlayer,
)

val LocalVariantNav = staticCompositionLocalOf<VariantNav?> { null }
