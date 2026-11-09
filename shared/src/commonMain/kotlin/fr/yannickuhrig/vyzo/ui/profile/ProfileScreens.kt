package fr.yannickuhrig.vyzo.ui.profile

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.yannickuhrig.vyzo.data.db.ProfileEntity
import fr.yannickuhrig.vyzo.data.profile.LocalProfileBackup
import fr.yannickuhrig.vyzo.data.profile.ProfileManager
import fr.yannickuhrig.vyzo.ui.common.ActionDialog
import fr.yannickuhrig.vyzo.ui.common.ActionItem
import fr.yannickuhrig.vyzo.ui.common.Badge
import fr.yannickuhrig.vyzo.ui.common.ConfirmDialog
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.SectionTitle
import fr.yannickuhrig.vyzo.ui.common.TextInputDialog
import fr.yannickuhrig.vyzo.ui.common.Tile
import fr.yannickuhrig.vyzo.ui.common.VyzoWordmark
import fr.yannickuhrig.vyzo.ui.common.ZapDialog
import fr.yannickuhrig.vyzo.ui.common.initials
import fr.yannickuhrig.vyzo.ui.common.isTv
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Couleurs des profils (tirées du logo, puis complétées). */
val ProfilePalette = listOf(
    Color(0xFFFFBE0B), Color(0xFF22D9F2), Color(0xFFA27BFF), Color(0xFFFF3D8B),
    Color(0xFF3DDC84), Color(0xFFFF8A3D), Color(0xFF4D8BFF), Color(0xFFFF7AC6),
)

fun profileColor(index: Int): Color = ProfilePalette[Math.floorMod(index, ProfilePalette.size)]

@Composable
fun ProfileAvatar(name: String, colorIndex: Int, size: Dp, kid: Boolean = false, modifier: Modifier = Modifier) {
    val color = profileColor(colorIndex)
    Box(modifier.size(size)) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape)
                .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initials(name).take(1),
                color = Color(0xFF0A0E1A), fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp, textAlign = TextAlign.Center,
            )
        }
        if (kid) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(size * 0.36f).clip(CircleShape)
                    .background(VyzoColors.Background).padding(2.dp).clip(CircleShape).background(VyzoColors.Cyan),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.ChildCare, "Profil enfant", Modifier.size(size * 0.22f), tint = Color(0xFF00313A))
            }
        }
    }
}

// ------------------------------------------------------------------ Code PIN

/**
 * Saisie d'un code à 4 chiffres, au pavé (télécommande ou tactile) ou avec les touches numériques.
 * [onSubmit] renvoie true si le code est accepté (la boîte se ferme), false pour afficher [error].
 */
@Composable
fun PinDialog(
    title: String,
    subtitle: String = "",
    error: String = "Code incorrect",
    length: Int = 4,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Boolean,
) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val firstKey = remember { FocusRequester() }

    LaunchedEffect(code) {
        if (code.length == length) {
            delay(120)
            if (onSubmit(code)) {
                onDismiss()
            } else {
                wrong = true
                code = ""
                for (x in listOf(14f, -12f, 9f, -6f, 3f, 0f)) shake.animateTo(x)
            }
        }
    }
    LaunchedEffect(Unit) { delay(100); runCatching { firstKey.requestFocus() } }

    fun press(d: Char) {
        wrong = false
        if (code.length < length) code += d
    }

    ZapDialog(onDismiss) {
        Column(
            Modifier.padding(24.dp).onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = pinDigits.indexOfFirst { ev.key in it }
                when {
                    digit >= 0 -> { press('0' + digit); true }
                    ev.key == Key.Backspace || ev.key == Key.Delete -> { code = code.dropLast(1); true }
                    else -> false
                }
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            Row(
                Modifier.padding(vertical = 20.dp).offset { IntOffset(shake.value.dp.roundToPx(), 0) },
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                repeat(length) { i ->
                    val filled = i < code.length
                    Box(
                        Modifier.size(18.dp).clip(CircleShape)
                            .background(if (filled) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .border(2.dp, if (wrong) VyzoColors.Red else MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
            Text(
                if (wrong) error else " ", color = VyzoColors.Red, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            val rows = listOf("123", "456", "789", "<0")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (r.length == 2) Spacer(Modifier.size(64.dp))
                        r.forEach { ch ->
                            Tile(
                                onClick = { if (ch == '<') code = code.dropLast(1) else press(ch) },
                                modifier = Modifier.size(64.dp).then(if (ch == '1') Modifier.focusRequester(firstKey) else Modifier),
                                shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedColor = MaterialTheme.colorScheme.primary,
                            ) { focused ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    if (ch == '<') Icon(Icons.AutoMirrored.Filled.Backspace, "Effacer", tint = if (focused) MaterialTheme.colorScheme.onPrimary else VyzoColors.TextDim)
                                    else Text("$ch", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = if (focused) MaterialTheme.colorScheme.onPrimary else Color.Unspecified)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Choix d'un nouveau code : saisi deux fois. [onSet] reçoit le code confirmé. */
@Composable
fun NewPinDialog(title: String, onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    if (first == null) {
        PinDialog(title = title, subtitle = "Choisis 4 chiffres", onDismiss = { if (first == null) onDismiss() }) { pin ->
            first = pin
            true
        }
    } else {
        PinDialog(title = title, subtitle = "Saisis-le une seconde fois", error = "Les deux codes ne correspondent pas", onDismiss = onDismiss) { pin ->
            if (pin == first) { onSet(pin); true } else false
        }
    }
}

// ------------------------------------------------------------------ Qui regarde ?

/**
 * Écran « Qui regarde ? ». [onPicked] est appelé une fois le profil choisi (et son code saisi s'il en a un).
 * Quitter un profil enfant demande le code parental (si l'option est active).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhoIsWatchingScreen(onPicked: () -> Unit, onManage: () -> Unit, onClose: (() -> Unit)? = null) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val tv = isTv()
    val profiles by c.profiles.profiles.collectAsState()
    val active by c.profiles.active.collectAsState()
    val settings by c.settings.state.collectAsState()
    var pinFor by remember { mutableStateOf<ProfileEntity?>(null) }
    var parentalFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    val firstFocus = remember { FocusRequester() }

    fun needsParental(target: ProfileEntity?): Boolean =
        active?.isKid == true && settings.parentalKidExit && settings.parentalPinHash.isNotBlank() &&
            (target == null || (!target.isKid && target.pinHash.isBlank()))

    fun choose(p: ProfileEntity) {
        when {
            p.id == active?.id -> onPicked()
            p.pinHash.isNotBlank() -> pinFor = p
            needsParental(p) -> parentalFor = { scope.launch { c.profiles.select(p.id); onPicked() } }
            else -> scope.launch { c.profiles.select(p.id); onPicked() }
        }
    }

    pinFor?.let { p ->
        PinDialog(title = "Code de ${p.name}", onDismiss = { pinFor = null }) { pin ->
            val ok = c.profiles.checkPin(p, pin)
            if (ok) scope.launch { c.profiles.select(p.id); onPicked() }
            ok
        }
    }
    parentalFor?.let { action ->
        PinDialog(title = "Code parental", subtitle = "Demandé pour quitter un profil enfant", onDismiss = { parentalFor = null }) { pin ->
            val ok = c.parental.checkPin(pin)
            if (ok) action()
            ok
        }
    }

    LaunchedEffect(profiles?.size) { delay(150); runCatching { firstFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = if (tv) 48.dp else 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VyzoWordmark(markSize = if (tv) 40.dp else 32.dp, fontSize = if (tv) 30 else 24)
            Spacer(Modifier.height(if (tv) 40.dp else 28.dp))
            Text("Qui regarde ?", style = if (tv) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(if (tv) 40.dp else 28.dp))
            val list = profiles.orEmpty()
            val avatar = if (tv) 128.dp else 88.dp
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(if (tv) 28.dp else 18.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.widthIn(max = 1100.dp),
            ) {
                list.forEachIndexed { i, p ->
                    Tile(
                        onClick = { choose(p) },
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        shape = RoundedCornerShape(16.dp), focusScale = 1.08f,
                    ) { focused ->
                        Column(Modifier.padding(12.dp).width(avatar + 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            ProfileAvatar(
                                p.name, p.colorIndex, avatar, kid = p.isKid,
                                modifier = if (p.id == active?.id) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (p.pinHash.isNotBlank()) Icon(Icons.Default.Lock, "Code", Modifier.size(14.dp).padding(end = 2.dp), tint = VyzoColors.TextDim)
                                Text(
                                    p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (focused) Color.White else VyzoColors.Text,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(
                    onClick = { if (needsParental(null)) parentalFor = onManage else onManage() },
                    shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Settings, null, Modifier.size(20.dp), tint = VyzoColors.TextDim)
                        Spacer(Modifier.width(10.dp))
                        Text("Gérer les profils", style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (onClose != null) {
                    Tile(onClick = onClose, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text("Annuler", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Gestion des profils

@Composable
fun ProfilesScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val tv = isTv()
    val profiles by c.profiles.profiles.collectAsState()
    val active by c.profiles.active.collectAsState()
    val settings by c.settings.state.collectAsState()
    val cloud by c.cloud.status.collectAsState()
    var menu by remember { mutableStateOf<ProfileEntity?>(null) }
    var rename by remember { mutableStateOf<ProfileEntity?>(null) }
    var color by remember { mutableStateOf<ProfileEntity?>(null) }
    var newPin by remember { mutableStateOf<ProfileEntity?>(null) }
    var delete by remember { mutableStateOf<ProfileEntity?>(null) }
    var creating by remember { mutableIntStateOf(0) }
    var newName by remember { mutableStateOf("") }
    var newKid by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf<List<LocalProfileBackup>>(emptyList()) }
    var backupsChanged by remember { mutableIntStateOf(0) }
    var restore by remember { mutableStateOf<LocalProfileBackup?>(null) }
    var orphan by remember { mutableStateOf<LocalProfileBackup?>(null) }
    var forget by remember { mutableStateOf<LocalProfileBackup?>(null) }
    val list = profiles.orEmpty()

    LaunchedEffect(backupsChanged) { backups = runCatching { c.localProfiles.backups() }.getOrDefault(emptyList()) }

    // Portée de l'appli : quitter l'écran ne coupe pas une sauvegarde ou une restauration en deux.
    fun saveLocal(p: ProfileEntity) {
        c.scope.launch {
            runCatching { c.localProfiles.backup(p.id) }
                .onSuccess { c.platform.toast("« ${p.name} » sauvegardé sur cet appareil"); backupsChanged++ }
                .onFailure { c.platform.toast("Échec de la sauvegarde") }
        }
    }

    menu?.let { p ->
        val i = list.indexOfFirst { it.id == p.id }
        ActionDialog(
            title = p.name,
            subtitle = listOfNotNull(
                if (p.localOnly) "Uniquement sur cet appareil" else null,
                if (p.isKid) "Profil enfant" else null,
                if (p.pinHash.isNotBlank()) "Protégé par un code" else null,
            ).joinToString(" · "),
            header = { ProfileAvatar(p.name, p.colorIndex, 56.dp, p.isKid, Modifier.padding(bottom = 10.dp)) },
            actions = buildList {
                if (p.id != active?.id) add(ActionItem("Utiliser ce profil", Icons.Default.LockOpen) { scope.launch { c.profiles.select(p.id) } })
                add(ActionItem("Renommer", Icons.Default.Edit) { rename = p })
                add(ActionItem("Couleur", Icons.Default.Palette) { color = p })
                add(
                    ActionItem(
                        if (p.isKid) "Ne plus être un profil enfant" else "Profil enfant", Icons.Default.ChildCare,
                        detail = "Le contenu verrouillé par le contrôle parental n'y apparaît jamais",
                    ) { scope.launch { c.profiles.setKid(p.id, !p.isKid) } },
                )
                if (p.pinHash.isBlank()) add(ActionItem("Protéger par un code", Icons.Default.Lock) { newPin = p })
                else {
                    add(ActionItem("Changer le code", Icons.Default.Lock) { newPin = p })
                    add(ActionItem("Retirer le code", Icons.Default.LockOpen) { scope.launch { c.profiles.setPin(p.id, null) } })
                }
                if (p.localOnly) {
                    val saved = backups.firstOrNull { it.id == p.id }
                    add(
                        ActionItem(
                            "Sauvegarder sur cet appareil", Icons.Default.Save,
                            detail = saved?.let { "Dernière sauvegarde : ${Fmt.dayTime(it.createdAt)}" } ?: "Progression, favoris et groupes",
                        ) { saveLocal(p) },
                    )
                    if (saved != null) add(ActionItem("Restaurer la sauvegarde", Icons.Default.Restore, detail = "Sauvegarde : ${Fmt.dayTime(saved.createdAt)}") { restore = saved })
                }
                if (i > 0) add(ActionItem("Monter", Icons.Default.ArrowUpward) { scope.launch { c.profiles.move(p.id, -1) } })
                if (i in 0 until list.lastIndex) add(ActionItem("Descendre", Icons.Default.ArrowDownward) { scope.launch { c.profiles.move(p.id, 1) } })
                if (list.size > 1) add(ActionItem("Supprimer", Icons.Default.Delete, tint = VyzoColors.Red) { delete = p })
            },
            onDismiss = { menu = null },
        )
    }
    rename?.let { p ->
        TextInputDialog("Renommer", initial = p.name, label = "Prénom ou surnom", onConfirm = { n -> if (n.isNotBlank()) scope.launch { c.profiles.rename(p.id, n) } }, onDismiss = { rename = null })
    }
    color?.let { p ->
        ZapDialog(onDismiss = { color = null }) {
            Column(Modifier.padding(22.dp)) {
                Text("Couleur de ${p.name}", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProfilePalette.indices.forEach { idx ->
                        Tile(
                            onClick = { scope.launch { c.profiles.setColor(p.id, idx) }; color = null },
                            shape = CircleShape, focusScale = 1.15f,
                        ) {
                            Box(
                                Modifier.size(44.dp).padding(4.dp).clip(CircleShape).background(profileColor(idx))
                                    .then(if (idx == p.colorIndex) Modifier.border(3.dp, Color.White, CircleShape) else Modifier),
                            )
                        }
                    }
                }
            }
        }
    }
    newPin?.let { p ->
        NewPinDialog(title = "Code de ${p.name}", onDismiss = { newPin = null }) { pin -> scope.launch { c.profiles.setPin(p.id, pin) } }
    }
    delete?.let { p ->
        ConfirmDialog(
            "Supprimer « ${p.name} » ?",
            "Sa progression dans les films et séries, ses favoris et ses groupes seront effacés" +
                when {
                    p.localOnly && backups.any { it.id == p.id } -> ". Sa sauvegarde reste sur cet appareil."
                    p.localOnly -> ". Il n'a pas de sauvegarde : il ne pourra pas être récupéré."
                    cloud.signedIn -> ", sur cet appareil comme sur les autres appareils du compte."
                    else -> "."
                },
            "Supprimer",
            onConfirm = { scope.launch { c.profiles.delete(p.id) } }, onDismiss = { delete = null },
        )
    }
    restore?.let { b ->
        ConfirmDialog(
            "Restaurer « ${b.name} » ?",
            if (list.any { it.id == b.id }) "Sa progression, ses favoris et ses groupes reprennent leur état sauvegardé (${Fmt.dayTime(b.createdAt)}). Ce qui a changé depuis est perdu."
            else "Le profil revient sur cet appareil, tel qu'il était à sa sauvegarde (${Fmt.dayTime(b.createdAt)}).",
            "Restaurer",
            onConfirm = {
                c.scope.launch {
                    runCatching { c.localProfiles.restore(b.id) }
                        .onSuccess { c.platform.toast("« ${b.name} » restauré") }
                        .onFailure { c.platform.toast("Sauvegarde illisible", long = true) }
                }
            },
            onDismiss = { restore = null },
        )
    }
    orphan?.let { b ->
        ActionDialog(
            title = b.name,
            subtitle = "Sauvegarde : ${Fmt.dayTime(b.createdAt)}",
            header = { ProfileAvatar(b.name, b.colorIndex, 56.dp, b.isKid, Modifier.padding(bottom = 10.dp)) },
            actions = listOf(
                ActionItem("Restaurer ce profil", Icons.Default.Restore) { restore = b },
                ActionItem("Supprimer la sauvegarde", Icons.Default.Delete, tint = VyzoColors.Red) { forget = b },
            ),
            onDismiss = { orphan = null },
        )
    }
    forget?.let { b ->
        ConfirmDialog(
            "Supprimer la sauvegarde de « ${b.name} » ?",
            "Ce profil ne pourra plus être récupéré.",
            "Supprimer",
            onConfirm = { c.scope.launch { runCatching { c.localProfiles.deleteBackup(b.id) }; backupsChanged++ } },
            onDismiss = { forget = null },
        )
    }
    when (creating) {
        1 -> TextInputDialog("Nouveau profil", label = "Prénom ou surnom", onConfirm = { newName = it; if (it.isNotBlank()) creating = 2 }, onDismiss = { if (creating == 1) creating = 0 })
        2 -> ActionDialog(
            title = newName.trim(),
            subtitle = "Type de profil",
            actions = listOf(
                ActionItem("Profil adulte", Icons.Default.Add) { newKid = false; creating = 3 },
                ActionItem("Profil enfant", Icons.Default.ChildCare, detail = "Sans le contenu verrouillé par le contrôle parental") { newKid = true; creating = 3 },
            ),
            onDismiss = { if (creating == 2) creating = 0 },
        )
        3 -> ActionDialog(
            title = newName.trim(),
            subtitle = "Où garder ce profil ?",
            actions = listOf(
                ActionItem(
                    "Sur le compte Vyzo", Icons.Default.CloudSync,
                    detail = if (cloud.signedIn) "Retrouvé sur tes autres appareils" else "Rejoindra le compte dès que tu t'y connectes",
                ) { scope.launch { c.profiles.create(newName, isKid = newKid) } },
                ActionItem(
                    "Uniquement sur cet appareil", Icons.Default.Devices,
                    detail = "Jamais synchronisé. Sauvegarde et restauration sur cet appareil seulement",
                ) { c.scope.launch { runCatching { c.localProfiles.create(newName, isKid = newKid) }.onFailure { c.platform.toast("Impossible de créer le profil") } } },
            ),
            onDismiss = { creating = 0 },
        )
    }

    LazyColumn(
        Modifier.fillMaxHeight().widthIn(max = if (tv) 980.dp else 720.dp).padding(horizontal = if (tv) 40.dp else 12.dp),
        contentPadding = PaddingValues(top = if (tv) 16.dp else 4.dp, bottom = 40.dp),
    ) {
        item { SectionTitle("Profils du foyer") }
        items(list, key = { it.id }) { p ->
            Tile(onClick = { menu = p }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), focusScale = 1.01f) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(p.name, p.colorIndex, 44.dp, p.isKid)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when {
                                p.id == active?.id -> "Profil utilisé sur cet appareil"
                                p.localOnly -> "Reste sur cet appareil, jamais synchronisé"
                                else -> "Progression, favoris et groupes séparés"
                            },
                            style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim,
                        )
                    }
                    if (p.localOnly) Badge("LOCAL", VyzoColors.Violet, Modifier.padding(start = 6.dp))
                    if (p.isKid) Badge("ENFANT", VyzoColors.Cyan, Modifier.padding(start = 6.dp))
                    if (p.pinHash.isNotBlank()) Badge("CODE", VyzoColors.Amber, Modifier.padding(start = 6.dp))
                }
            }
        }
        item {
            Tile(onClick = { newName = ""; creating = 1 }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), focusScale = 1.01f) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(16.dp))
                    Text("Ajouter un profil", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        item {
            Tile(
                onClick = { scope.launch { c.settings.update { it.copy(askProfileOnStart = !it.askProfileOnStart) } } },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), focusScale = 1.01f,
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Demander « Qui regarde ? » au démarrage", style = MaterialTheme.typography.bodyLarge)
                        Text("Quand il y a plusieurs profils", style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim)
                    }
                    androidx.compose.material3.Switch(checked = settings.askProfileOnStart, onCheckedChange = null)
                }
            }
        }
        val orphans = backups.filter { b -> list.none { it.id == b.id } }
        if (orphans.isNotEmpty()) {
            item { SectionTitle("Profils locaux sauvegardés") }
            items(orphans, key = { "sauvegarde-" + it.id }) { b ->
                Tile(onClick = { orphan = b }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), focusScale = 1.01f) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProfileAvatar(b.name, b.colorIndex, 44.dp, b.isKid)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.name, style = MaterialTheme.typography.bodyLarge)
                            Text("Supprimé · sauvegarde : ${Fmt.dayTime(b.createdAt)}", style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim)
                        }
                    }
                }
            }
        }
        item {
            Text(
                if (cloud.signedIn) "Les profils, leur progression et leurs favoris sont synchronisés avec le compte ${cloud.email}, sauf les profils marqués LOCAL."
                else "Connecte-toi à un compte Vyzo (Réglages → Compte Vyzo) pour retrouver les profils et leur progression sur tes autres appareils. Les profils marqués LOCAL restent ici.",
                style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, modifier = Modifier.padding(14.dp),
            )
        }
    }
}

/** Petit avatar du profil actif (rail TV, barre du haut sur téléphone). */
@Composable
fun ActiveProfileBadge(size: Dp, modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val p by c.profiles.active.collectAsState()
    val profile = p ?: return
    ProfileAvatar(profile.name, profile.colorIndex, size, profile.isKid, modifier)
}

/** Nom d'un profil pour les messages (« Principal », « Léa »…). */
fun ProfileEntity.label(): String = ProfileManager.cleanName(name)

/**
 * Porte du contrôle parental : `gate(locked) { action }` exécute l'action directement, ou après saisie du code
 * parental si le contenu est verrouillé (le contenu reste ensuite visible pour la session).
 */
@Composable
fun rememberParentalGate(): (Boolean, () -> Unit) -> Unit {
    val c = LocalContainer.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    pending?.let { action ->
        PinDialog(title = "Code parental", subtitle = "Ce contenu est verrouillé", onDismiss = { pending = null }) { pin ->
            val ok = c.parental.unlock(pin)
            if (ok) action()
            ok
        }
    }
    return remember {
        { locked: Boolean, action: () -> Unit ->
            if (locked && c.parental.pinSet.value && !c.parental.unlocked.value && !c.parental.kidProfile.value) pending = action
            else if (!(locked && c.parental.kidProfile.value)) action()
        }
    }
}

/** Touches des chiffres 0 à 9 (rangée du haut et pavé numérique). */
private val pinDigits = listOf(
    setOf(Key.Zero, Key.NumPad0), setOf(Key.One, Key.NumPad1), setOf(Key.Two, Key.NumPad2), setOf(Key.Three, Key.NumPad3),
    setOf(Key.Four, Key.NumPad4), setOf(Key.Five, Key.NumPad5), setOf(Key.Six, Key.NumPad6), setOf(Key.Seven, Key.NumPad7),
    setOf(Key.Eight, Key.NumPad8), setOf(Key.Nine, Key.NumPad9),
)
