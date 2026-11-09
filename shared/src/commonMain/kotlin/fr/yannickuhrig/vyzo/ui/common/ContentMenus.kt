package fr.yannickuhrig.vyzo.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.FAVORITES_GROUP_ID
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.launch

/** Menu d'une chaîne : favoris, groupes perso, enregistrement immédiat. */
@Composable
fun ChannelMenu(ch: ChannelEntity, onDismiss: () -> Unit, onWatch: () -> Unit) {
    val c = LocalContainer.current
    // Portée de l'appli : le menu se ferme dès le clic, l'action doit aller au bout quand même.
    val scope = c.scope
    val groups by c.repo.userGroups().collectAsState(emptyList())
    val memberOf by c.repo.groupsOf(ch.uid).collectAsState(emptyList())
    var step by remember { mutableIntStateOf(0) }
    val fav = FAVORITES_GROUP_ID in memberOf

    when (step) {
        1 -> ActionDialog(
            title = "Groupes de « ${ch.name} »",
            actions = groups.filter { it.id != FAVORITES_GROUP_ID }.map { g ->
                val inG = g.id in memberOf
                ActionItem(g.name, if (inG) Icons.Default.Check else Icons.Default.Add, detail = if (inG) "Dans ce groupe" else "") {
                    scope.launch { c.repo.setInGroup(g.id, ch.uid, ContentKind.LIVE, !inG) }
                }
            } + ActionItem("Nouveau groupe…", Icons.Default.Add) { step = 2 },
            onDismiss = { if (step == 1) onDismiss() },
        )
        3 -> fr.yannickuhrig.vyzo.ui.settings.ChannelCustomizeDialog(ch, onDismiss = onDismiss)
        2 -> TextInputDialog(
            title = "Nouveau groupe",
            label = "Nom du groupe",
            onConfirm = { name ->
                if (name.isNotBlank()) scope.launch {
                    val id = c.repo.createGroup(name)
                    c.repo.setInGroup(id, ch.uid, ContentKind.LIVE, true)
                }
            },
            onDismiss = onDismiss,
        )
        else -> ActionDialog(
            title = ch.name,
            subtitle = if (ch.number > 0) "Chaîne ${ch.number}" else "",
            actions = listOf(
                ActionItem("Regarder", Icons.Default.PlayArrow) { onWatch() },
                ActionItem(if (fav) "Retirer des favoris" else "Ajouter aux favoris", if (fav) Icons.Default.Star else Icons.Default.StarBorder) {
                    scope.launch { c.repo.setInGroup(FAVORITES_GROUP_ID, ch.uid, ContentKind.LIVE, !fav) }
                },
                ActionItem("Ajouter à un groupe…", Icons.Default.Folder) { step = 1 },
                ActionItem("Ouvrir dans un autre lecteur", Icons.AutoMirrored.Filled.OpenInNew, detail = "VLC, MX Player, Kodi…") {
                    openInExternalPlayer(c, ch.name) { c.repo.resolveChannel(ch) }
                },
                ActionItem("Enregistrer maintenant", Icons.Default.FiberManualRecord, tint = VyzoColors.Red) {
                    scope.launch {
                        val now = System.currentTimeMillis()
                        val prog = c.repo.nowPrograms(listOf(ch.epgKey), now)[ch.epgKey]
                        val stop = prog?.let { it.stop + c.settings.get().recPaddingAfterMin * 60_000L } ?: (now + 60 * 60_000L)
                        val conflict = c.recordings.conflictFor(ch, now, stop)
                        if (prog != null) c.recordings.recordNow(ch, prog.title, stop) else c.recordings.recordNow(ch, ch.name, stop)
                        c.platform.toast(conflict?.let { "Enregistrement démarré. Attention : ${it.message()}" } ?: "Enregistrement démarré", long = true)
                    }
                },
                ActionItem("Personnaliser…", Icons.Default.Edit, detail = "Nom, numéro, guide TV, masquer, verrouiller") { step = 3 },
            ),
            onDismiss = { if (step == 0) onDismiss() },
        )
    }
}

/** Fiche d'un programme du guide : regarder, replay, rappel, enregistrement. */
@Composable
fun ProgramMenu(
    ch: ChannelEntity,
    p: ProgramEntity,
    onDismiss: () -> Unit,
    onWatchLive: () -> Unit,
    onCatchup: () -> Unit,
) {
    val c = LocalContainer.current
    // Portée de l'appli : le menu se ferme dès le clic, l'action doit aller au bout quand même.
    val scope = c.scope
    val now = remember { System.currentTimeMillis() }
    val isLive = p.start <= now && p.stop > now
    val isPast = p.stop <= now
    val isFuture = p.start > now
    val canCatchup = c.repo.canCatchup(ch, p, now)
    var refresh by remember { mutableIntStateOf(0) }
    val reminderSet by produceState(false, refresh) { value = c.reminders.isSet(ch, p) }
    val recording by produceState<fr.yannickuhrig.vyzo.data.db.RecordingEntity?>(null, refresh) { value = c.recordings.findFor(ch, p) }
    val rule by produceState<fr.yannickuhrig.vyzo.data.db.RecordingRuleEntity?>(null, refresh) { value = c.recordings.ruleFor(ch, p) }
    var pendingReminder by remember { mutableStateOf(false) }

    val notifPermission = rememberNotificationPermission {
        if (pendingReminder) scope.launch {
            c.reminders.toggle(ch, p)
            c.platform.toast("Rappel programmé")
        }
        pendingReminder = false
    }

    val actions = buildList {
        if (isLive) add(ActionItem("Regarder en direct", Icons.Default.LiveTv) { onWatchLive() })
        if (canCatchup) add(ActionItem(if (isLive) "Revoir depuis le début" else "Revoir (replay)", Icons.Default.History) { onCatchup() })
        if (isFuture) add(
            ActionItem(if (reminderSet) "Annuler le rappel" else "Me le rappeler", if (reminderSet) Icons.Default.NotificationsOff else Icons.Default.Notifications) {
                val needPerm = !reminderSet && notifPermission.isNeeded()
                if (needPerm) {
                    pendingReminder = true
                    notifPermission.launch()
                } else scope.launch {
                    val on = c.reminders.toggle(ch, p)
                    c.platform.toast(if (on) "Rappel programmé" else "Rappel annulé")
                    refresh++
                }
            },
        )
        if (!isPast) add(
            if (recording != null) ActionItem("Annuler l'enregistrement", Icons.Default.Stop, tint = VyzoColors.Red) {
                scope.launch { c.recordings.cancel(recording!!.id); c.platform.toast("Enregistrement annulé") }
            } else ActionItem("Enregistrer", Icons.Default.FiberManualRecord, tint = VyzoColors.Red) {
                scope.launch {
                    val s = c.settings.get()
                    val conflict = c.recordings.conflictFor(ch, p.start - s.recPaddingBeforeMin * 60_000L, p.stop + s.recPaddingAfterMin * 60_000L)
                    c.recordings.recordProgram(ch, p)
                    val done = if (isLive) "Enregistrement démarré" else "Enregistrement programmé"
                    c.platform.toast(conflict?.let { "$done. Attention : ${it.message()}" } ?: done, long = true)
                }
            },
        )
        if (!isPast) add(
            if (rule != null) ActionItem("Ne plus enregistrer la série", Icons.Default.RepeatOn, tint = VyzoColors.Red, detail = "Les diffusions à venir sont annulées") {
                scope.launch { c.recordings.deleteRule(rule!!.id); c.platform.toast("Série retirée des enregistrements", long = true); refresh++ }
            } else ActionItem("Enregistrer toute la série", Icons.Default.Repeat, detail = "Chaque diffusion de « ${p.title} » sur ${ch.name}") {
                scope.launch {
                    val n = c.recordings.recordSeries(ch, p)
                    c.platform.toast("Série suivie : $n diffusion(s) programmée(s), les suivantes seront ajoutées au fil du guide", long = true)
                    refresh++
                }
            },
        )
        if (!isLive) add(ActionItem("Regarder ${ch.name}", Icons.Default.PlayArrow) { onWatchLive() })
    }

    ActionDialog(
        title = p.title,
        subtitle = "${Fmt.day(p.start)} · ${Fmt.range(p.start, p.stop)} · ${ch.name}",
        body = p.description,
        actions = actions,
        onDismiss = { if (!pendingReminder) onDismiss() },
        header = {
            Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isLive) Badge("EN DIRECT", VyzoColors.Red)
                if (canCatchup) Badge("REPLAY", VyzoColors.Cyan)
                if (reminderSet) Badge("RAPPEL", VyzoColors.Amber)
                if (recording != null) Badge("ENREGISTREMENT", VyzoColors.Red)
                if (rule != null) Badge("SÉRIE", VyzoColors.Violet)
                if (p.category.isNotBlank()) Badge(p.category, VyzoColors.TextDim)
            }
        },
    )
}
