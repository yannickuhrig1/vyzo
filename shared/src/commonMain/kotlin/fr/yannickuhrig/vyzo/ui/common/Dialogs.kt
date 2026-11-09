package fr.yannickuhrig.vyzo.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.delay

data class ActionItem(
    val label: String,
    val icon: ImageVector? = null,
    val detail: String = "",
    val enabled: Boolean = true,
    val tint: Color? = null,
    val onClick: () -> Unit,
)

@Composable
fun ZapDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val desktop = isDesktop()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Gestionnaire de focus de la fenêtre de dialogue (pas celui de l'écran derrière).
        val focusManager = LocalFocusManager.current
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.padding(24.dp).widthIn(min = 280.dp, max = 560.dp)
                .then(
                    // Sur PC, les flèches ne déplacent pas le focus d'elles-mêmes dans une fenêtre de dialogue.
                    if (desktop) Modifier.onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (e.key) {
                            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                            else -> false
                        }
                    } else Modifier,
                ),
        ) {
            content()
        }
    }
}

/** Liste d'actions façon menu contextuel, utilisable à la télécommande. */
@Composable
fun ActionDialog(
    title: String,
    subtitle: String = "",
    body: String = "",
    actions: List<ActionItem>,
    onDismiss: () -> Unit,
    header: @Composable (() -> Unit)? = null,
) {
    val first = remember { FocusRequester() }
    ZapDialog(onDismiss) {
        Column(Modifier.padding(vertical = 18.dp)) {
            Column(Modifier.padding(horizontal = 22.dp)) {
                header?.invoke()
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim)
                if (body.isNotBlank()) {
                    Text(
                        body, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(actions) { i, a ->
                    Tile(
                        onClick = { if (a.enabled) { a.onClick(); onDismiss() } },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                        focusScale = 1f,
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (a.icon != null) {
                                Icon(a.icon, null, Modifier.size(22.dp), tint = if (a.enabled) a.tint ?: MaterialTheme.colorScheme.primary else VyzoColors.TextDim)
                                Spacer(Modifier.width(14.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(a.label, style = MaterialTheme.typography.bodyLarge, color = if (a.enabled) Color.Unspecified else VyzoColors.TextDim)
                                if (a.detail.isNotBlank()) Text(a.detail, style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
}

data class Choice<T>(val value: T, val label: String, val detail: String = "")

@Composable
fun <T> ChoiceDialog(
    title: String,
    choices: List<Choice<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val selIndex = choices.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selIndex - 2).coerceAtLeast(0))
    ZapDialog(onDismiss) {
        Column(Modifier.padding(vertical = 18.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
            LazyColumn(Modifier.heightIn(max = 460.dp), state = listState) {
                itemsIndexed(choices) { i, c ->
                    val isSel = c.value == selected
                    Tile(
                        onClick = { onSelect(c.value); onDismiss() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
                            .then(if (i == selIndex) Modifier.focusRequester(focus) else Modifier),
                        focusScale = 1f,
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isSel) Icons.Default.Check else Icons.Default.RadioButtonUnchecked, null,
                                Modifier.size(20.dp), tint = if (isSel) MaterialTheme.colorScheme.primary else VyzoColors.TextDim.copy(alpha = 0.5f),
                            )
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(c.label, style = MaterialTheme.typography.bodyLarge)
                                if (c.detail.isNotBlank()) Text(c.detail, style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { focus.requestFocus() }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    label: String = "",
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    confirmLabel: String = "Valider",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    ZapDialog(onDismiss) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = if (label.isNotBlank()) ({ Text(label) }) else null,
                placeholder = if (placeholder.isNotBlank()) ({ Text(placeholder) }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(text); onDismiss() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Annuler") }
                TextButton(onClick = { onConfirm(text); onDismiss() }) { Text(confirmLabel) }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { focus.requestFocus() }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirmer",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ActionDialog(
        title = title, body = message, onDismiss = onDismiss,
        actions = listOf(
            ActionItem(confirmLabel, tint = VyzoColors.Red, onClick = onConfirm),
            ActionItem("Annuler", onClick = {}),
        ),
    )
}

@Composable
fun MessageBox(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(16.dp)
            .widthIn(max = 520.dp),
    ) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, tonalElevation = 4.dp) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}

/** Boîte de dialogue pour envoyer la lecture vers les autres écrans Vyzo du compte. */
@Composable
fun CastDialog(onSend: () -> Unit, onDismiss: () -> Unit) {
    ActionDialog(
        title = "Caster la lecture",
        subtitle = "Choisis un écran pour continuer la lecture",
        actions = listOf(
            ActionItem(label = "Envoyer vers mes écrans Vyzo (TV / PC)", icon = Icons.Default.Tv, detail = "Cloud Sync", onClick = onSend),
        ),
        onDismiss = onDismiss,
    )
}
