package fr.yannickuhrig.vyzo.ui.player

import androidx.compose.runtime.Composable
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.ui.common.ActionItem

/** Le bandeau du direct : ce qu'il affiche, le lecteur gardant la main sur les touches. */
class LiveBarArgs(
    val ui: PlayerUi,
    val clock: Long,
    val showNumbers: Boolean,
    val positionMs: Long,
    val delayMs: Long,
)

class VodBarArgs(
    val ui: PlayerUi,
    val position: Long,
    val duration: Long,
    val isPlaying: Boolean,
)

class ChannelPanelArgs(
    val title: String,
    val channels: List<ChannelEntity>,
    val nowMap: Map<String, ProgramEntity>,
    val currentUid: String?,
    val showNumbers: Boolean,
    val onPick: (ChannelEntity) -> Unit,
    val onClose: () -> Unit,
)

class GuidePanelArgs(
    val vm: PlayerViewModel,
    val start: ChannelEntity,
    val showNumbers: Boolean,
    val onWatch: (ChannelEntity) -> Unit,
    val onCatchup: (ChannelEntity, ProgramEntity) -> Unit,
    val onClose: () -> Unit,
)

class PlayerMenuArgs(
    val title: String,
    val subtitle: String,
    val actions: List<ActionItem>,
    val onDismiss: () -> Unit,
)

/** Une liste de choix : [selected] vaut -1 quand aucune ligne n'est cochée. */
class PlayerChoiceArgs(
    val title: String,
    val labels: List<String>,
    val selected: Int,
    val onPick: (Int) -> Unit,
    val onDismiss: () -> Unit,
)

/** Habillage du lecteur propre à une variante. La lecture, les touches et les règles restent dans [PlayerScreen]. */
class VariantPlayer(
    val liveBar: @Composable (LiveBarArgs) -> Unit,
    val vodBar: @Composable (VodBarArgs) -> Unit,
    val channels: @Composable (ChannelPanelArgs) -> Unit,
    val guide: @Composable (GuidePanelArgs) -> Unit,
    val menu: @Composable (PlayerMenuArgs) -> Unit,
    val choice: @Composable (PlayerChoiceArgs) -> Unit,
)
