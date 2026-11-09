package fr.yannickuhrig.vyzo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.ui.common.ActionDialog
import fr.yannickuhrig.vyzo.ui.common.ActionItem
import fr.yannickuhrig.vyzo.ui.common.Badge
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.NetImage
import fr.yannickuhrig.vyzo.ui.common.SectionTitle
import fr.yannickuhrig.vyzo.ui.common.TextInputDialog
import fr.yannickuhrig.vyzo.ui.common.Tile
import fr.yannickuhrig.vyzo.ui.common.ZapDialog
import fr.yannickuhrig.vyzo.ui.common.isTv
import fr.yannickuhrig.vyzo.ui.profile.PinDialog
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Menu de personnalisation d'une chaîne : nom, numéro, masquage, verrouillage, guide. */
@Composable
fun ChannelCustomizeDialog(ch: ChannelEntity, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.state.collectAsState()
    val unlocked by c.parental.unlocked.collectAsState()
    var step by remember { mutableStateOf("menu") }
    val pref by produceState<fr.yannickuhrig.vyzo.data.db.ChannelPrefEntity?>(null, ch.uid) { value = c.db.channelPrefs().get(ch.uid) }
    val customized = ch.origName.isNotBlank() || ch.origNumber > 0 || ch.hidden || ch.locked || !pref?.epgKey.isNullOrBlank()

    when (step) {
        "rename" -> TextInputDialog(
            "Nom de la chaîne", initial = ch.name, label = "Nom affiché",
            onConfirm = { n -> scope.launch { c.repo.renameChannel(ch.uid, if (n.isBlank() || n == ch.origName) "" else n) } },
            onDismiss = onDismiss,
        )
        "number" -> TextInputDialog(
            "Numéro de la chaîne", initial = if (ch.number > 0) ch.number.toString() else "", label = "Numéro (vide = celui de la playlist)",
            keyboardType = KeyboardType.Number,
            onConfirm = { n -> scope.launch { c.repo.setChannelNumber(ch.uid, n.trim().toIntOrNull() ?: 0) } },
            onDismiss = onDismiss,
        )
        "epg" -> EpgPickDialog(ch, current = pref?.epgKey.orEmpty(), onPick = { key -> scope.launch { c.repo.setChannelEpg(ch.uid, key) } }, onDismiss = onDismiss)
        "pin" -> PinDialog(title = "Code parental", onDismiss = onDismiss) { pin ->
            val ok = c.parental.unlock(pin)
            if (ok) scope.launch { c.repo.setChannelLocked(ch.uid, !ch.locked) }
            ok
        }
        else -> ActionDialog(
            title = ch.name,
            subtitle = listOfNotNull(
                if (ch.number > 0) "Chaîne ${ch.number}" else null,
                if (ch.origName.isNotBlank()) "nom d'origine : ${ch.origName}" else null,
            ).joinToString(" · "),
            actions = buildList {
                add(ActionItem("Renommer", Icons.Default.Edit) { step = "rename" })
                add(ActionItem("Changer le numéro", Icons.Default.Numbers) { step = "number" })
                add(ActionItem("Associer au guide TV", Icons.Default.CalendarViewDay, detail = pref?.epgKey?.takeIf { it.isNotBlank() }?.let { "Choisi à la main : $it" } ?: "Automatique : ${ch.epgKey.ifBlank { "aucun" }}") { step = "epg" })
                add(
                    ActionItem(if (ch.hidden) "Afficher la chaîne" else "Masquer la chaîne", if (ch.hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff) {
                        scope.launch { c.repo.setChannelHidden(ch.uid, !ch.hidden) }
                    },
                )
                add(
                    ActionItem(if (ch.locked) "Déverrouiller" else "Verrouiller (contrôle parental)", if (ch.locked) Icons.Default.LockOpen else Icons.Default.Lock) {
                        if (settings.parentalPinHash.isNotBlank() && !unlocked) step = "pin"
                        else scope.launch { c.repo.setChannelLocked(ch.uid, !ch.locked) }
                    },
                )
                if (customized) add(ActionItem("Tout rétablir comme dans la playlist", Icons.Default.Restore, tint = VyzoColors.Red) { scope.launch { c.repo.resetChannel(ch.uid) } })
            },
            onDismiss = { if (step == "menu") onDismiss() },
        )
    }
}

/** Choix de l'identifiant XMLTV d'une chaîne parmi ceux des guides chargés. */
@Composable
fun EpgPickDialog(ch: ChannelEntity, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val keys by produceState<List<String>?>(null) { value = c.repo.guideKeys() }
    var filter by remember { mutableStateOf(ch.matchKey.take(6)) }
    val shown = remember(keys, filter) {
        val f = filter.trim().lowercase()
        keys.orEmpty().filter { f.isBlank() || it.contains(f) }.take(150)
    }
    val now by produceState<Map<String, ProgramEntity>>(emptyMap(), shown) { value = c.repo.nowPrograms(shown) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }

    ZapDialog(onDismiss) {
        Column(Modifier.padding(20.dp).widthIn(min = 360.dp)) {
            Text("Guide de « ${ch.name} »", style = MaterialTheme.typography.titleLarge)
            Text("Choisis l'identifiant du guide qui correspond à cette chaîne.", style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim)
            OutlinedTextField(
                value = filter, onValueChange = { filter = it }, singleLine = true, label = { Text("Rechercher") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).focusRequester(focus),
            )
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item {
                    PickRow("Association automatique", "Par tvg-id, puis par nom", selected = current.isBlank()) { onPick(""); onDismiss() }
                }
                if (keys == null) item { Text("Chargement…", color = VyzoColors.TextDim, modifier = Modifier.padding(12.dp)) }
                else if (keys!!.isEmpty()) item { Text("Aucun guide chargé pour l'instant.", color = VyzoColors.TextDim, modifier = Modifier.padding(12.dp)) }
                items(shown, key = { it }) { k ->
                    PickRow(k, now[k]?.let { "En ce moment : ${it.title}" } ?: "", selected = k == current) { onPick(k); onDismiss() }
                }
            }
        }
    }
}

@Composable
private fun PickRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Tile(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp), focusScale = 1f) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Chaînes d'une catégorie, y compris masquées, pour les personnaliser une à une. */
@Composable
fun ChannelsEditScreen(categoryUid: String) {
    val c = LocalContainer.current
    val tv = isTv()
    val channels by remember(categoryUid) { c.repo.channelsForEditing(categoryUid) }.collectAsState(null)
    val category by produceState<fr.yannickuhrig.vyzo.data.db.CategoryEntity?>(null, categoryUid) { value = c.db.categories().get(categoryUid) }
    var edit by remember { mutableStateOf<ChannelEntity?>(null) }

    edit?.let { ch -> ChannelCustomizeDialog(ch, onDismiss = { edit = null }) }

    LazyColumn(
        Modifier.fillMaxHeight().widthIn(max = if (tv) 980.dp else 720.dp).padding(horizontal = if (tv) 40.dp else 12.dp),
        contentPadding = PaddingValues(top = if (tv) 16.dp else 4.dp, bottom = 40.dp),
    ) {
        item { SectionTitle(category?.name ?: "Chaînes") }
        item {
            Text(
                "Renomme, renumérote, masque ou verrouille une chaîne, ou associe-la à un autre guide. " +
                    "Ces choix sont conservés quand la playlist se met à jour.",
                style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        items(channels.orEmpty(), key = { it.uid }) { ch ->
            Tile(onClick = { edit = ch }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), focusScale = 1.01f) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (ch.number > 0) "${ch.number}" else "", style = MaterialTheme.typography.labelLarge, color = VyzoColors.TextDim, modifier = Modifier.width(44.dp))
                    NetImage(ch.logo, Modifier.size(width = 54.dp, height = 36.dp), fallback = ch.name)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ch.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (ch.hidden) VyzoColors.TextDim else Color.Unspecified)
                        if (ch.origName.isNotBlank()) Text("Playlist : ${ch.origName}", style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, maxLines = 1)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (ch.hidden) Badge("MASQUÉE", VyzoColors.TextDim)
                        if (ch.locked) Icon(Icons.Default.Lock, "Verrouillée", Modifier.size(18.dp), tint = VyzoColors.Amber)
                        if (ch.origNumber > 0) Badge("N° ${ch.origNumber} → ${ch.number}", VyzoColors.Cyan)
                    }
                }
            }
        }
        if (channels?.isEmpty() == true) item { Text("Aucune chaîne dans cette catégorie.", color = VyzoColors.TextDim, modifier = Modifier.padding(14.dp)) }
    }
}
