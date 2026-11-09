package fr.yannickuhrig.vyzo.ui.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.data.remote.QrContent
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors

/**
 * Sur PC, pas de caméra : on saisit l'adresse affichée sous le QR code de la TV
 * (« http://192.168.1.20:8642/?k=… »), ou on colle n'importe quel texte de QR code (lien M3U, Xtream…).
 */
@Composable
actual fun QrScannerDialog(title: String, hint: String, onResult: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun submit() {
        val t = text.trim()
        if (t.isBlank()) return
        onResult(normalizeDeviceAddress(t))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title.replace("Scanne le QR code", "Saisis l'adresse")) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim)
                Text(
                    "Recopie l'adresse affichée sous le QR code (elle commence par http:// et contient « k= »).",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Adresse") },
                    placeholder = { Text("http://192.168.1.20:8642/?k=…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = { submit() }, enabled = text.isNotBlank()) { Text("Continuer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** L'adresse lisible sous le QR code n'a pas le marqueur Vyzo : on l'ajoute pour qu'elle soit reconnue. */
internal fun normalizeDeviceAddress(raw: String): String {
    var t = raw.trim()
    if (!t.startsWith("http", true) && Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?/?\\?k=").containsMatchIn(t)) t = "http://$t"
    val isDevice = t.startsWith("http", true) && Regex("[?&]k=[^&]+").containsMatchIn(t)
    if (isDevice && !t.contains("${QrContent.MARKER}=1") && !t.contains("zapette=1")) t += "&${QrContent.MARKER}=1"
    return t
}
