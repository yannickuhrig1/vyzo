package fr.yannickuhrig.vyzo.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.yannickuhrig.vyzo.data.subtitles.SubtitleItem
import fr.yannickuhrig.vyzo.data.subtitles.SubtitleService
import fr.yannickuhrig.vyzo.player.TrackOption
import fr.yannickuhrig.vyzo.ui.common.Tile
import fr.yannickuhrig.vyzo.ui.common.ZapDialog
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SubtitlesMenuDialog(
    internalTracks: List<TrackOption>,
    internalSelected: TrackOption?,
    externalSubtitleName: String?,
    delayMs: Long,
    onSelectInternal: (TrackOption?) -> Unit,
    onClearExternal: () -> Unit,
    onAdjustDelay: (Long) -> Unit,
    onResetDelay: () -> Unit,
    onSearchOnline: () -> Unit,
    onLoadCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    val delaySec = ((delayMs / 100.0).roundToInt()) / 10.0
    val delayText = when {
        delayMs > 0 -> "+${delaySec}s"
        delayMs < 0 -> "${delaySec}s"
        else -> "0.0s"
    }

    ZapDialog(onDismiss = onDismiss) {
        Column(Modifier.padding(vertical = 18.dp, horizontal = 18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sous-titres", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Fermer")
                }
            }

            Spacer(Modifier.height(12.dp))

            // --- Ligne de synchronisation du délai
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Délai des sous-titres",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(
                                onClick = { onAdjustDelay(-500L) },
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text("-0.5s", fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                delayText,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            FilledTonalButton(
                                onClick = { onAdjustDelay(500L) },
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text("+0.5s", fontSize = 12.sp)
                            }
                        }
                        if (delayMs != 0L) {
                            TextButton(onClick = onResetDelay) {
                                Text("Réinitialiser", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // --- Actions sous-titres externes
            Text(
                "Sous-titres externes (.srt)",
                style = MaterialTheme.typography.labelSmall,
                color = VyzoColors.TextDim,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )

            if (externalSubtitleName != null) {
                Tile(
                    onClick = onClearExternal,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        ) {
                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Actif : $externalSubtitleName",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text("Retirer", color = VyzoColors.Red, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Tile(
                onClick = { onDismiss(); onSearchOnline() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("🔍 Rechercher des sous-titres en ligne (.srt)", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Tile(
                onClick = { onDismiss(); onLoadCustom() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FileOpen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("📁 Charger un fichier .srt / URL", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            // --- Pistes intégrées au flux
            Text(
                "Pistes intégrées au flux",
                style = MaterialTheme.typography.labelSmall,
                color = VyzoColors.TextDim,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )

            LazyColumn(Modifier.heightIn(max = 240.dp)) {
                item {
                    val isNone = internalSelected == null && externalSubtitleName == null
                    Tile(
                        onClick = { onSelectInternal(null); onDismiss() },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isNone) Icons.Default.Check else Icons.Default.RadioButtonUnchecked,
                                null,
                                Modifier.size(18.dp),
                                tint = if (isNone) MaterialTheme.colorScheme.primary else VyzoColors.TextDim,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Désactivés", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                items(internalTracks) { opt ->
                    val isSel = opt.selected && externalSubtitleName == null
                    Tile(
                        onClick = { onSelectInternal(opt); onDismiss() },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isSel) Icons.Default.Check else Icons.Default.RadioButtonUnchecked,
                                null,
                                Modifier.size(18.dp),
                                tint = if (isSel) MaterialTheme.colorScheme.primary else VyzoColors.TextDim,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(opt.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

private val subtitleLanguages = listOf("fr" to "Français", "en" to "Anglais", "es" to "Espagnol")

fun noSubtitleMessage(query: String, language: String): String {
    val name = subtitleLanguages.firstOrNull { it.first == language }?.second?.lowercase() ?: language
    return "Aucun sous-titre trouvé pour « $query » en $name"
}

@Composable
fun SubtitleSearchDialog(
    initialQuery: String,
    season: Int? = null,
    episode: Int? = null,
    subtitleService: SubtitleService,
    onApply: (title: String, content: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var language by remember { mutableStateOf("fr") }
    var isSearching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SubtitleItem>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var downloadingId by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    fun doSearch() {
        val q = query.trim()
        if (q.isBlank()) return
        isSearching = true
        errorMessage = null
        scope.launch {
            try {
                // Saison et épisode ne valent que pour le titre d'origine : une recherche retapée porte sur autre chose.
                val exact = q == initialQuery.trim() && season != null && episode != null
                val list = if (exact) {
                    subtitleService.search(query = q, language = language, season = season, episode = episode)
                        .ifEmpty { subtitleService.search(query = q, language = language) }
                } else {
                    subtitleService.search(query = q, language = language)
                }
                results = list
                if (list.isEmpty()) {
                    errorMessage = noSubtitleMessage(q, language)
                }
            } catch (e: Exception) {
                errorMessage = "Erreur de recherche : ${e.message ?: "problème réseau"}"
            } finally {
                isSearching = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (query.isNotBlank()) {
            doSearch()
        }
    }

    ZapDialog(onDismiss = onDismiss) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Recherche de sous-titres", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Fermer")
                }
            }

            Spacer(Modifier.height(12.dp))

            // Barre de recherche
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Titre du film ou de la série") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            // Sélecteur de langue et bouton Rechercher
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row {
                    subtitleLanguages.forEach { (code, label) ->
                        val sel = language == code
                        SuggestionChip(
                            onClick = {
                                language = code
                                doSearch()
                            },
                            label = { Text(label, fontSize = 12.sp) },
                            modifier = Modifier.padding(end = 6.dp),
                            border = if (sel) androidx.compose.material3.SuggestionChipDefaults.suggestionChipBorder(
                                enabled = true,
                                borderColor = MaterialTheme.colorScheme.primary,
                                borderWidth = 1.5.dp,
                            ) else null,
                        )
                    }
                }

                Button(
                    onClick = { doSearch() },
                    enabled = !isSearching && query.isNotBlank(),
                ) {
                    Text("Chercher")
                }
            }

            Spacer(Modifier.height(12.dp))

            // Résultats ou chargement
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 340.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isSearching -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(36.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Recherche en cours…", style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim)
                        }
                    }
                    errorMessage != null && results.isEmpty() -> {
                        Text(errorMessage.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim)
                    }
                    results.isNotEmpty() -> {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                            items(results) { item ->
                                val isDl = downloadingId == item.id
                                Tile(
                                    onClick = {
                                        if (downloadingId != null) return@Tile
                                        downloadingId = item.id
                                        scope.launch {
                                            try {
                                                val content = subtitleService.download(item)
                                                onApply(item.title, content)
                                                onDismiss()
                                            } catch (e: Exception) {
                                                downloadingId = null
                                                errorMessage = "Téléchargement impossible : ${e.message}"
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                            Text(
                                                item.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Row(
                                                modifier = Modifier.padding(top = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer,
                                                    modifier = Modifier.padding(end = 6.dp),
                                                ) {
                                                    Text(
                                                        item.language.uppercase(),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                    )
                                                }
                                                if (item.downloads > 0) {
                                                    Text(
                                                        "${item.downloads} dl",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = VyzoColors.TextDim,
                                                        modifier = Modifier.padding(end = 8.dp),
                                                    )
                                                }
                                                if (item.rating > 0f) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.Star, null, tint = Color(0xFFFFB300), modifier = Modifier.size(12.dp))
                                                        Spacer(Modifier.width(2.dp))
                                                        Text(
                                                            "${item.rating}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = VyzoColors.TextDim,
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        if (isDl) {
                                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                        } else {
                                            Text(
                                                "Activer",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        Text("Saisis un titre pour chercher des sous-titres.", style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim)
                    }
                }
            }
        }
    }
}

@Composable
fun SubtitleUrlOrFileDialog(
    subtitleService: SubtitleService,
    onApply: (title: String, content: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    ZapDialog(onDismiss = onDismiss) {
        Column(Modifier.padding(22.dp)) {
            Text("Charger un sous-titre (.srt)", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Saisis l'adresse web directe (http/https) ou le chemin absolu vers un fichier .srt sur cet appareil.",
                style = MaterialTheme.typography.bodySmall,
                color = VyzoColors.TextDim,
            )

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = input,
                onValueChange = { input = it; error = null },
                label = { Text("URL ou chemin de fichier .srt") },
                placeholder = { Text("https://.../sub.srt ou /sdcard/sub.srt") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error.orEmpty(), color = VyzoColors.Red, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Annuler")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val pathOrUrl = input.trim()
                        if (pathOrUrl.isBlank()) return@Button
                        isLoading = true
                        error = null
                        scope.launch {
                            try {
                                val content = if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
                                    subtitleService.loadFromUrl(pathOrUrl)
                                } else {
                                    subtitleService.loadFromFile(pathOrUrl)
                                }
                                val name = pathOrUrl.substringAfterLast("/").substringAfterLast("\\").ifBlank { "Sous-titre externe" }
                                onApply(name, content)
                                onDismiss()
                            } catch (e: Exception) {
                                error = "Impossible de charger : ${e.message ?: "erreur inconnue"}"
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    enabled = !isLoading && input.isNotBlank(),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    } else {
                        Text("Charger et activer")
                    }
                }
            }
        }
    }
}
