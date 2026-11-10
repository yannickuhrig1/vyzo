package fr.yannickuhrig.vyzo.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateListOf
import fr.yannickuhrig.vyzo.data.db.EpgMode
import fr.yannickuhrig.vyzo.data.epg.EpgCatalog
import fr.yannickuhrig.vyzo.data.epg.EpgCatalogEntry
import fr.yannickuhrig.vyzo.data.repo.GroupKind
import fr.yannickuhrig.vyzo.data.sync.CheckOutcome
import fr.yannickuhrig.vyzo.data.sync.PlaylistChecker
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.SectionTitle
import fr.yannickuhrig.vyzo.ui.common.TextInputDialog
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.launch

private sealed interface CatalogLoad {
    data object Loading : CatalogLoad
    data object Failed : CatalogLoad
    data class Ready(val entries: List<EpgCatalogEntry>) : CatalogLoad
}

/** Catalogue epgshare01 : on coche les pays voulus, puis on charge les guides. */
@Composable
fun EpgCatalogScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val sources by c.repo.epgSources().collectAsState(emptyList())
    val sync by c.sync.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val load by produceState<CatalogLoad>(CatalogLoad.Loading, attempt) {
        value = CatalogLoad.Loading
        value = runCatching { EpgCatalog.fetch() }.fold({ CatalogLoad.Ready(it) }, { CatalogLoad.Failed })
    }

    if (searching) {
        TextInputDialog(
            "Rechercher un pays", initial = query, label = "Pays ou code", onConfirm = { query = it.trim() }, onDismiss = { searching = false },
        )
    }

    ScreenColumn {
        item { SectionTitle("Catalogue de guides") }
        item { SettingRow("Rechercher un pays", query.ifBlank { "Tous les guides" }, Icons.Default.Search) { searching = true } }
        if (query.isNotBlank()) item { SettingRow("Effacer la recherche", icon = Icons.Default.Clear) { query = "" } }
        item {
            SettingRow("Charger les guides cochés", if (sync.running) sync.message else "Télécharge toutes les sources actives", Icons.Default.Refresh) {
                if (!sync.running) c.sync.launchEpg()
            }
        }
        when (val state = load) {
            CatalogLoad.Loading -> item { SettingRow("Chargement du catalogue…") {} }
            CatalogLoad.Failed -> item { SettingRow("Catalogue indisponible", "Réessayer", Icons.Default.Refresh) { attempt++ } }
            is CatalogLoad.Ready -> {
                val shown = state.entries.filter { EpgCatalog.matches(it, query) }
                if (shown.isEmpty()) item { SettingRow("Aucun guide ne correspond") {} }
                items(shown.size, key = { shown[it].code }) { index ->
                    val entry = shown[index]
                    val added = sources.firstOrNull { it.url == entry.url }
                    SettingRow(entry.label, Fmt.size(entry.sizeBytes), trailing = { Checkbox(checked = added != null, onCheckedChange = null) }) {
                        scope.launch {
                            if (added != null) c.repo.deleteEpgSource(added)
                            else c.repo.addEpgSource(EpgCatalog.SOURCE_PREFIX + entry.label, entry.url, EpgMode.GAPS)
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Guides gratuits fournis par epgshare01. Ils passent après ceux de ta playlist et complètent ce qui manque.",
                style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, modifier = Modifier.padding(14.dp),
            )
        }
    }
}

private fun outcomeTitle(outcome: CheckOutcome, dead: Int): String = when (outcome) {
    CheckOutcome.COMPLETE -> if (dead == 0) "Toutes les chaînes répondent" else "Vérification terminée : $dead sans réponse"
    CheckOutcome.STOPPED -> "Vérification arrêtée"
    CheckOutcome.PLAYBACK -> "Vérification interrompue par une lecture"
    CheckOutcome.CONNECTION_BUSY -> "Connexion occupée"
    CheckOutcome.PROVIDER_REFUSED -> "Le fournisseur refuse les connexions"
    CheckOutcome.TOO_MANY_FAILURES -> "Trop d'échecs d'affilée"
    CheckOutcome.BACKGROUND -> "Vérification interrompue"
    CheckOutcome.EMPTY -> "Aucune chaîne à vérifier"
}

private fun outcomeDetail(outcome: CheckOutcome): String = when (outcome) {
    CheckOutcome.CONNECTION_BUSY -> "Un enregistrement ou un téléchargement utilise déjà l'abonnement"
    CheckOutcome.PROVIDER_REFUSED -> "Vérification stoppée pour ne pas faire bloquer ton abonnement. Réessaie plus tard"
    CheckOutcome.TOO_MANY_FAILURES -> "Coupure réseau ou blocage probable : ces dernières chaînes n'ont pas été comptées"
    CheckOutcome.BACKGROUND -> "Vyzo est passé en arrière-plan"
    else -> ""
}

private fun durationLabel(channels: Int): String {
    val minutes = (channels * PlaylistChecker.AVERAGE_MS_PER_CHANNEL / 60_000).coerceAtLeast(1)
    return if (minutes < 60) "environ $minutes min" else "environ ${minutes / 60} h ${"%02d".format(minutes % 60)}"
}

/** Vérificateur lancé à la main : un flux à la fois, pour respecter la limite de connexions de l'abonnement. */
@Composable
fun PlaylistCheckScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val state by c.playlistChecker.state.collectAsState()
    val groups by remember { c.repo.liveGroups() }.collectAsState(emptyList())
    val hiddenNow = remember { mutableStateListOf<String>() }

    ScreenColumn {
        if (state.running) {
            item { SectionTitle("Vérification en cours") }
            item { SettingRow("${state.checked} / ${state.total} chaînes", state.current, Icons.Default.Refresh) {} }
            item { SettingRow("Arrêter", icon = Icons.Default.Stop) { c.playlistChecker.stop() } }
        } else {
            state.outcome?.let { outcome ->
                item { SectionTitle("Résultat") }
                item { SettingRow(outcomeTitle(outcome, state.dead.size), outcomeDetail(outcome).ifBlank { "${state.checked} / ${state.total} chaînes vérifiées" }) {} }
            }
        }
        if (state.dead.isNotEmpty()) {
            val remaining = state.dead.filter { it.uid !in hiddenNow }
            if (!state.running && remaining.isNotEmpty()) {
                item {
                    SettingRow("Masquer les ${remaining.size} chaînes sans réponse", "Elles restent récupérables dans la personnalisation des chaînes", Icons.Default.VisibilityOff) {
                        scope.launch {
                            remaining.forEach { c.repo.setChannelHidden(it.uid, true) }
                            hiddenNow.addAll(remaining.map { it.uid })
                        }
                    }
                }
            }
            items(state.dead.size, key = { state.dead[it].uid }) { index ->
                val channel = state.dead[index]
                val hidden = channel.uid in hiddenNow
                SettingRow(channel.name, if (hidden) "Masquée" else "Ne répond pas") {
                    scope.launch {
                        c.repo.setChannelHidden(channel.uid, !hidden)
                        if (hidden) hiddenNow.remove(channel.uid) else hiddenNow.add(channel.uid)
                    }
                }
            }
        }
        if (!state.running) {
            item { SectionTitle("Que vérifier ?") }
            val choices = groups.filter { (it.kind == GroupKind.ALL || it.kind == GroupKind.CATEGORY) && it.count > 0 && !it.locked }
            items(choices.size, key = { choices[it].key }) { index ->
                val group = choices[index]
                SettingRow(group.title, "${group.count} chaînes · ${durationLabel(group.count)}", Icons.Default.PlayArrow) {
                    hiddenNow.clear()
                    c.player.stop()
                    c.playlistChecker.start(group.key)
                }
            }
            item {
                Text(
                    "Les flux sont ouverts un par un, avec une pause entre chaque, pour ne pas dépasser ta limite de connexions. " +
                        "La lecture en cours est arrêtée au lancement, et Vyzo doit rester ouvert pendant la vérification.",
                    style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, modifier = Modifier.padding(14.dp),
                )
            }
        }
    }
}
