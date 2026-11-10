package fr.yannickuhrig.vyzo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.data.trakt.TraktDeviceCode
import fr.yannickuhrig.vyzo.data.trakt.traktConnected
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.SectionTitle
import fr.yannickuhrig.vyzo.ui.common.TextInputDialog
import fr.yannickuhrig.vyzo.ui.remote.QrImage
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.launch

private enum class TraktField(val title: String) { ID("Identifiant de l'application"), SECRET("Secret de l'application") }

@Composable
fun TraktScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.state.collectAsState()
    val ui by c.trakt.ui.collectAsState()
    var editing by remember { mutableStateOf<TraktField?>(null) }
    val connected = settings.traktConnected
    val ready = settings.traktClientId.isNotBlank() && settings.traktClientSecret.isNotBlank()

    fun status(value: String) = if (value.isBlank()) "À renseigner" else "Configuré"

    ScreenColumn {
        item { SectionTitle("Trakt") }
        if (ui.message.isNotBlank()) item { Hint(ui.message) }
        if (!connected) {
            item {
                Hint(
                    "Crée une application sur trakt.tv/oauth/applications/new, avec comme adresse de redirection " +
                        "urn:ietf:wg:oauth:2.0:oob, puis recopie ici son identifiant et son secret.",
                )
            }
            item { SettingRow(TraktField.ID.title, status(settings.traktClientId)) { editing = TraktField.ID } }
            item { SettingRow(TraktField.SECRET.title, status(settings.traktClientSecret)) { editing = TraktField.SECRET } }
            val login = ui.login
            when {
                login != null -> {
                    item { DeviceCode(login) }
                    item { SettingRow("Annuler", icon = Icons.Default.Clear) { c.trakt.cancelLogin() } }
                }
                ui.busy -> item { Hint("Connexion à Trakt…") }
                else -> item {
                    SettingRow("Se connecter", if (ready) "Avec un code à saisir sur ton téléphone" else "Renseigne d'abord les deux champs", Icons.Default.Link) {
                        if (ready) c.trakt.startLogin()
                    }
                }
            }
        } else {
            if (settings.traktProfileId != settings.activeProfileId) {
                item { Hint("Ce compte est relié à un autre profil de Vyzo : rien n'est suivi sur celui-ci.") }
            }
            item {
                ToggleRow("Suivre ce que je regarde", "Films et épisodes apparaissent sur Trakt pendant la lecture", settings.traktScrobble) { on ->
                    scope.launch { c.settings.update { it.copy(traktScrobble = on) } }
                }
            }
            item {
                val last = settings.traktLastSync
                SettingRow(
                    if (ui.busy) "Synchronisation…" else "Synchroniser maintenant",
                    if (last > 0) "Dernière fois : ${Fmt.day(last)} à ${Fmt.time(last)}" else "Jamais",
                    Icons.Default.Refresh,
                ) { c.trakt.syncNow() }
            }
            item { Hint("Tes listes Trakt s'ajoutent à l'accueil depuis « Personnaliser l'accueil ».") }
            item { SettingRow("Se déconnecter", icon = Icons.AutoMirrored.Filled.Logout) { c.trakt.disconnect() } }
        }
    }

    editing?.let { field ->
        TextInputDialog(
            field.title,
            initial = if (field == TraktField.ID) settings.traktClientId else settings.traktClientSecret,
            onConfirm = { text ->
                val value = text.trim()
                scope.launch {
                    c.settings.update { if (field == TraktField.ID) it.copy(traktClientId = value) else it.copy(traktClientSecret = value) }
                }
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
private fun DeviceCode(login: TraktDeviceCode) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Ouvre ${login.verificationUrl} et saisis ce code", color = VyzoColors.TextDim)
        Text(login.userCode, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        QrImage(login.verificationUrl, 180.dp)
    }
}
