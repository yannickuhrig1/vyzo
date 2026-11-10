package fr.yannickuhrig.vyzo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import fr.yannickuhrig.vyzo.ui.common.ActionDialog
import fr.yannickuhrig.vyzo.ui.common.ActionItem
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.SectionTitle
import fr.yannickuhrig.vyzo.ui.player.RemoteAction
import fr.yannickuhrig.vyzo.ui.player.RemoteButton
import fr.yannickuhrig.vyzo.ui.player.RemoteKeymap
import kotlinx.coroutines.launch

private val BUTTON_ORDER = listOf(
    RemoteButton.UP, RemoteButton.DOWN, RemoteButton.LEFT, RemoteButton.LEFT_HELD, RemoteButton.RIGHT, RemoteButton.RIGHT_HELD,
    RemoteButton.MENU, RemoteButton.INFO, RemoteButton.GUIDE, RemoteButton.PLAY_PAUSE,
    RemoteButton.RED, RemoteButton.GREEN, RemoteButton.YELLOW, RemoteButton.BLUE,
)

/** Ce que fait chaque touche pendant le direct. « OK » et « Retour » gardent leur rôle : on ne peut pas se retrouver coincé. */
@Composable
fun RemoteKeysScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.state.collectAsState()
    val bindings = settings.remoteBindings
    var picking by remember { mutableStateOf<RemoteButton?>(null) }

    fun save(next: Map<String, String>) {
        scope.launch { c.settings.update { it.copy(remoteBindings = next) } }
    }

    ScreenColumn {
        item { SectionTitle("Préréglages") }
        item {
            SettingRow("Touches d'origine de Vyzo", if (RemoteKeymap.isDefault(bindings)) "Utilisées actuellement" else "◀ liste, ▶ infos puis guide") { save(emptyMap()) }
        }
        item {
            SettingRow("Façon TiviMate", if (bindings == RemoteKeymap.TIVIMATE_STYLE) "Utilisé actuellement" else "◀ liste, ◀ maintenu guide, ▶ infos") {
                save(RemoteKeymap.TIVIMATE_STYLE)
            }
        }
        item { SectionTitle("Pendant le direct") }
        BUTTON_ORDER.forEach { button ->
            item(key = button.name) { SettingRow(button.label, RemoteKeymap.action(bindings, button).label) { picking = button } }
        }
    }

    picking?.let { button ->
        val current = RemoteKeymap.action(bindings, button)
        ActionDialog(
            title = button.label,
            subtitle = "Choisis ce que fait cette touche",
            actions = RemoteAction.entries.map { action ->
                ActionItem(action.label, detail = if (action == current) "Actuel" else "") { save(RemoteKeymap.assign(bindings, button, action)) }
            },
            onDismiss = { picking = null },
        )
    }
}
