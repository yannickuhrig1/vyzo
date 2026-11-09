package fr.yannickuhrig.vyzo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.cloud.CloudException
import fr.yannickuhrig.vyzo.data.cloud.SignUpResult
import fr.yannickuhrig.vyzo.data.cloud.VaultState
import fr.yannickuhrig.vyzo.data.remote.ConfigReceiver
import fr.yannickuhrig.vyzo.data.remote.RemoteEvent
import fr.yannickuhrig.vyzo.ui.common.ConfirmDialog
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.SectionTitle
import fr.yannickuhrig.vyzo.ui.common.ZapDialog
import fr.yannickuhrig.vyzo.ui.common.isTv
import fr.yannickuhrig.vyzo.ui.remote.QrImage
import fr.yannickuhrig.vyzo.ui.remote.rememberConfigReceiver
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.launch

/** Connexion demandée depuis la page web du téléphone : la TV se connecte elle-même au compte. */
suspend fun connectAccountFromPhone(c: AppContainer, receiver: ConfigReceiver, e: RemoteEvent.AccountReceived) {
    val status = c.cloud.status.value
    runCatching { if (status.signedIn && status.email == e.email) c.cloud.unlockVault(e.password) else c.cloud.signIn(e.email, e.password) }
        .onSuccess {
            val unreadable = c.cloud.status.value.vault == VaultState.LOST
            receiver.setAccountResult(true, "La TV est connectée au compte ${e.email}" + if (unreadable) ". Ses playlists sont illisibles : regarde l'écran du compte sur la TV." else "")
        }
        .onFailure { receiver.setAccountResult(false, it.message ?: "Connexion impossible") }
}

@Composable
fun AccountScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val tv = isTv()
    val status by c.cloud.status.collectAsState()
    val live by c.cloud.realtimeState.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var phoneLogin by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; info = null
        scope.launch {
            try { block() } catch (e: CloudException) { error = e.message } catch (e: Exception) { error = e.message ?: e.toString() }
            busy = false
        }
    }

    if (confirmSignOut) {
        ConfirmDialog(
            "Se déconnecter du compte ?",
            "Les profils, favoris et la progression restent sur cet appareil, mais ne seront plus synchronisés.",
            "Se déconnecter",
            onConfirm = { scope.launch { c.cloud.signOut() } }, onDismiss = { confirmSignOut = false },
        )
    }
    if (phoneLogin) AccountFromPhoneDialog(onDismiss = { phoneLogin = false })
    if (confirmReset) {
        ConfirmDialog(
            "Repartir de cet appareil ?",
            "Ce qui est illisible sur le compte sera effacé. Les autres appareils redemanderont le mot de passe une fois.",
            "Repartir de cet appareil",
            onConfirm = { run { c.cloud.resetVault(password); password = "" } }, onDismiss = { confirmReset = false },
        )
    }

    LazyColumn(
        Modifier.fillMaxHeight().widthIn(max = if (tv) 860.dp else 640.dp).padding(horizontal = if (tv) 40.dp else 16.dp),
        contentPadding = PaddingValues(top = if (tv) 16.dp else 4.dp, bottom = 40.dp),
    ) {
        if (!status.configured) {
            item {
                Text("Cette version de Vyzo a été compilée sans serveur de synchronisation.", color = VyzoColors.TextDim, modifier = Modifier.padding(16.dp))
            }
            return@LazyColumn
        }
        if (status.signedIn) {
            item { SectionTitle("Compte Vyzo") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Icon(
                        if (status.error != null) Icons.Default.CloudOff else Icons.Default.CloudDone, null, Modifier.size(36.dp),
                        tint = if (status.error != null) VyzoColors.Red else MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(status.email.orEmpty(), style = MaterialTheme.typography.titleLarge)
                        Text(
                            when {
                                status.running -> "Synchronisation en cours…"
                                status.lastSyncAt > 0 -> "Dernière synchronisation : ${Fmt.dayTime(status.lastSyncAt)}"
                                else -> "Pas encore synchronisé"
                            },
                            style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim,
                        )
                        Text(
                            when (live) {
                                fr.yannickuhrig.vyzo.data.cloud.RealtimeClient.State.ON -> "Synchro instantanée active"
                                fr.yannickuhrig.vyzo.data.cloud.RealtimeClient.State.CONNECTING -> "Synchro instantanée : connexion…"
                                fr.yannickuhrig.vyzo.data.cloud.RealtimeClient.State.OFF -> "Synchro instantanée en pause"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (live == fr.yannickuhrig.vyzo.data.cloud.RealtimeClient.State.ON) VyzoColors.Cyan else VyzoColors.TextDim,
                        )
                    }
                }
            }
            status.error?.let { e -> item { Text(e, color = VyzoColors.Red, modifier = Modifier.padding(vertical = 6.dp)) } }
            status.restored?.let { r -> item { Text(r, color = VyzoColors.Cyan, modifier = Modifier.padding(vertical = 6.dp)) } }
            if (status.vault == VaultState.LOCKED || status.vault == VaultState.LOST) {
                item {
                    val lost = status.vault == VaultState.LOST
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (lost) "Playlists du compte illisibles" else "Synchroniser les playlists et réglages", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (lost) "Le mot de passe a changé et cet appareil n'a pas la clé. Si un autre appareil te demande de te reconnecter, fais-le d'abord, " +
                                "puis réessaie ici. Sinon, repars de cet appareil : ce qui est illisible sur le compte sera remplacé par ses playlists, son guide, ses réglages et ses chaînes."
                            else "Ils sont chiffrés sur tes appareils : le serveur ne peut pas les lire. Saisis le mot de passe du compte une seule fois sur cet appareil.",
                            style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim,
                        )
                        if (tv) SettingRow(
                            "Saisir le mot de passe depuis le téléphone",
                            "Affiche un QR code, pour éviter la télécommande",
                            Icons.Default.QrCode2,
                        ) { phoneLogin = true }
                        OutlinedTextField(
                            value = password, onValueChange = { password = it }, label = { Text("Mot de passe du compte") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        error?.let { Text(it, color = VyzoColors.Red) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(enabled = !busy && password.isNotEmpty(), onClick = {
                                run { c.cloud.unlockVault(password); if (c.cloud.status.value.vault == VaultState.OPEN) password = "" }
                            }) { Text(if (lost) "Réessayer" else "Déverrouiller") }
                            if (lost) OutlinedButton(enabled = !busy && password.isNotEmpty(), onClick = { confirmReset = true }) { Text("Repartir de cet appareil") }
                            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
            item {
                SettingRow("Synchroniser maintenant", "Profils, favoris, progression, playlists, guide, réglages et chaînes", Icons.Default.CloudSync) {
                    scope.launch { c.cloud.sync() }
                }
            }
            item { SettingRow("Se déconnecter", icon = Icons.AutoMirrored.Filled.Logout) { confirmSignOut = true } }
            item {
                Text(
                    "Les autres appareils connectés au même compte retrouvent les mêmes profils et playlists. Les playlists, le guide, " +
                        "les réglages et les chaînes sont chiffrés sur tes appareils, le serveur ne peut pas les lire. Supprimer une playlist ici la supprime partout. La reprise d'un film ou d'un épisode " +
                        "fonctionne quand les appareils utilisent la même playlist (même compte Xtream, même portail ou même lien M3U).",
                    style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, modifier = Modifier.padding(14.dp),
                )
            }
            return@LazyColumn
        }

        item { SectionTitle("Compte Vyzo") }
        item {
            Text(
                "Un compte pour le foyer : les playlists, les profils, leurs favoris et où chacun en est dans ses films et séries se retrouvent " +
                    "sur la TV et sur le téléphone.",
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        if (tv) item {
            SettingRow(
                "Se connecter depuis le téléphone",
                "Affiche un QR code : saisis l'adresse et le mot de passe sur le téléphone plutôt qu'à la télécommande",
                Icons.Default.QrCode2,
            ) { phoneLogin = true }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = email, onValueChange = { email = it.trim() }, label = { Text("Adresse e-mail") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Mot de passe") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !busy && email.contains('@') && password.isNotEmpty(), onClick = {
                        run { c.cloud.signIn(email, password) }
                    }) { Text("Se connecter") }
                    OutlinedButton(enabled = !busy && email.contains('@') && password.length >= 6, onClick = {
                        run {
                            when (c.cloud.signUp(email, password)) {
                                is SignUpResult.SignedIn -> Unit
                                SignUpResult.ConfirmationSent -> info =
                                    "Compte créé. Un e-mail de confirmation a été envoyé à $email : ouvre le lien qu'il contient " +
                                        "(la page qui s'affiche ensuite peut sembler en erreur, ce n'est pas grave), puis reviens te connecter ici."
                            }
                        }
                    }) { Text("Créer un compte") }
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                TextButton(enabled = !busy && email.contains('@'), onClick = {
                    run { c.cloud.sendPasswordReset(email); info = "Si un compte existe pour $email, un e-mail pour changer le mot de passe vient d'être envoyé." }
                }) { Text("Mot de passe oublié") }
                if (error?.contains("confirm", ignoreCase = true) == true) {
                    TextButton(enabled = !busy, onClick = { run { c.cloud.resendConfirmation(email); info = "E-mail de confirmation renvoyé à $email." } }) {
                        Text("Renvoyer l'e-mail de confirmation")
                    }
                }
                error?.let { Text(it, color = VyzoColors.Red) }
                info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Text(
                    "Les profils déjà créés sur cet appareil sont ajoutés au compte. Un profil qui porte le même nom qu'un profil du compte " +
                        "est fusionné avec lui (progression la plus récente conservée). Les profils locaux restent sur cet appareil.",
                    style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** QR code affiché par la TV : la page ouverte sur le téléphone envoie l'adresse et le mot de passe du compte. */
@Composable
fun AccountFromPhoneDialog(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val receiver = rememberConfigReceiver(accountOnly = true)
    val url by receiver.url.collectAsState()
    val noNetwork by receiver.noNetwork.collectAsState()
    val status by c.cloud.status.collectAsState()
    val live by c.cloud.realtimeState.collectAsState()
    LaunchedEffect(receiver) {
        receiver.start()
        receiver.events.collect { e -> if (e is RemoteEvent.AccountReceived) connectAccountFromPhone(c, receiver, e) }
    }
    val accountUrl = url?.replace("/?k=", "/compte?k=")
    ZapDialog(onDismiss) {
        Column(Modifier.padding(26.dp).widthIn(max = 720.dp)) {
            Text("Se connecter depuis le téléphone", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                    when {
                        noNetwork -> Icon(Icons.Default.WifiOff, null, Modifier.size(56.dp), tint = VyzoColors.TextDim)
                        accountUrl != null -> QrImage(accountUrl, 220.dp)
                        else -> CircularProgressIndicator()
                    }
                }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (status.signedIn && status.vault == VaultState.OPEN) {
                        Text("Connecté au compte ${status.email}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Les profils, les playlists et la progression arrivent sur la TV.", color = VyzoColors.TextDim)
                    } else {
                        Text("1. Vise ce QR code avec l'appareil photo du téléphone.", style = MaterialTheme.typography.bodyLarge)
                        Text("2. Saisis l'adresse e-mail et le mot de passe du compte Vyzo.", style = MaterialTheme.typography.bodyLarge)
                        Text("Le téléphone doit être sur le même réseau Wi-Fi que la TV.", style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onDismiss) { Text(if (status.signedIn) "Terminé" else "Fermer") }
            }
        }
    }
}
