package fr.yannickuhrig.vyzo.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.yannickuhrig.vyzo.data.home.HomeRows
import fr.yannickuhrig.vyzo.data.prefs.AppSettings
import fr.yannickuhrig.vyzo.data.vod.Genres
import fr.yannickuhrig.vyzo.data.weather.WeatherPlace
import fr.yannickuhrig.vyzo.ui.common.ActionDialog
import fr.yannickuhrig.vyzo.ui.common.ActionItem
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.SectionTitle
import fr.yannickuhrig.vyzo.ui.common.TextInputDialog
import fr.yannickuhrig.vyzo.ui.common.isTv
import fr.yannickuhrig.vyzo.ui.settings.SettingRow
import fr.yannickuhrig.vyzo.ui.settings.ToggleRow
import kotlinx.coroutines.launch

/** « Personnaliser l'accueil » : écran de démarrage, widgets (horloge, météo), rangées affichées et leur ordre. */
@Composable
fun HomeEditScreen() {
    val c = LocalContainer.current
    val s by c.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    val tv = isTv()
    fun update(t: (AppSettings) -> AppSettings) = scope.launch { c.settings.update(t) }

    // Chaque profil a son accueil : les rangées modifiées ici sont celles du profil actif.
    val profile by c.profiles.active.collectAsState()
    val profiles by c.profiles.profiles.collectAsState()
    val multiProfile = profiles.orEmpty().size > 1
    val homeRows = s.homeRowsFor(s.activeProfileId)
    fun rows(t: (List<String>) -> List<String>) = update { it.withHomeRows(it.activeProfileId, t) }

    var cityDialog by remember { mutableStateOf(false) }
    var cityResults by remember { mutableStateOf<List<WeatherPlace>?>(null) }
    var genreKind by remember { mutableStateOf<String?>(null) }
    var genrePicker by remember { mutableStateOf(false) }
    // Télécommande : OK sur une rangée ouvre Monter / Descendre / Retirer.
    var rowMenu by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = if (tv) 40.dp else 12.dp, vertical = if (tv) 16.dp else 4.dp),
    ) {
        item { SectionTitle("Démarrage") }
        item {
            ToggleRow(
                "Ouvrir l'accueil au lancement",
                if (s.startScreen == "HOME") "Vyzo s'ouvre sur l'accueil" else "Vyzo s'ouvre sur « TV en direct »",
                s.startScreen == "HOME",
            ) { on -> update { it.copy(startScreen = if (on) "HOME" else "LIVE") } }
        }

        item { SectionTitle("Widgets") }
        item { ToggleRow("Horloge et date", checked = s.homeClock) { v -> update { it.copy(homeClock = v) } } }
        item {
            SettingRow(
                "Météo",
                if (s.weatherCity.isBlank()) "Aucune ville : la météo n'est pas affichée" else s.weatherCity,
                Icons.Default.WbSunny,
                trailing = if (s.weatherCity.isBlank()) null else ({
                    IconButton(onClick = { update { it.copy(weatherCity = "", weatherLat = 0.0, weatherLon = 0.0) } }) {
                        Icon(Icons.Default.Close, "Retirer la météo")
                    }
                }),
            ) { cityDialog = true }
        }
        item {
            ToggleRow(
                "Faire défiler les affiches",
                "Quand on ne parcourt pas les rangées, l'arrière-plan passe d'un titre à l'autre",
                s.homeRotateBackdrop,
            ) { v -> update { it.copy(homeRotateBackdrop = v) } }
        }
        item {
            if (isTv()) {
                ToggleRow(
                    "Rangées sur l'écran d'accueil de la TV",
                    "Reprendre et Favoris, hors de l'appli. Le contenu verrouillé n'y apparaît jamais",
                    s.tvHomeRows,
                ) { v -> update { it.copy(tvHomeRows = v) } }
                SettingRow(
                    "Économiseur d'écran",
                    if (s.screensaverMinutes == 0) "Désactivé" else "Après ${s.screensaverMinutes} min sans action, hors lecture",
                    Icons.Default.Wallpaper,
                ) {
                    val next = SCREENSAVER_CHOICES[(SCREENSAVER_CHOICES.indexOf(s.screensaverMinutes) + 1) % SCREENSAVER_CHOICES.size]
                    update { it.copy(screensaverMinutes = next) }
                }
            }
        }

        item { SectionTitle(if (multiProfile && profile != null) "Rangées de l'accueil de ${profile!!.name}" else "Rangées affichées") }
        if (multiProfile) item {
            SettingRow(
                "Utiliser cet accueil pour tous les profils",
                if (s.hasOwnHome(s.activeProfileId)) "Les autres profils reprennent ces rangées (leurs accueils personnalisés sont remplacés)"
                else "Ce profil utilise l'accueil commun à tous les profils",
                Icons.Default.Group,
            ) { update { it.shareHomeOf(it.activeProfileId) }; c.platform.toast("Accueil partagé avec tous les profils") }
        }
        if (homeRows.isEmpty()) item { SettingRow("Aucune rangée", "Ajoute des rangées ci-dessous") {} }
        items(homeRows, key = { "on-$it" }) { id ->
            val i = homeRows.indexOf(id)
            SettingRow(
                HomeRows.title(id),
                trailing = {
                    Row {
                        IconButton(onClick = { rows { HomeRows.move(it, id, -1) } }, enabled = i > 0) {
                            Icon(Icons.Default.KeyboardArrowUp, "Monter")
                        }
                        IconButton(onClick = { rows { HomeRows.move(it, id, 1) } }, enabled = i < homeRows.size - 1) {
                            Icon(Icons.Default.KeyboardArrowDown, "Descendre")
                        }
                        IconButton(onClick = { rows { HomeRows.toggle(it, id, false) } }) {
                            Icon(Icons.Default.Close, "Retirer")
                        }
                    }
                },
            ) { rowMenu = id }
        }

        val available = HomeRows.BUILT_IN.filter { it !in homeRows }
        item { SectionTitle("Ajouter une rangée") }
        items(available, key = { "off-$it" }) { id ->
            SettingRow(HomeRows.title(id), icon = Icons.Default.Add) { rows { HomeRows.toggle(it, id, true) } }
        }
        item {
            SettingRow("Rangée de films d'un genre…", "Action, Comédie, Anime…", Icons.Default.Movie) {
                genreKind = HomeRows.GENRE_MOVIES; genrePicker = true
            }
        }
        item {
            SettingRow("Rangée de séries d'un genre…", "Drame, Policier, Animation…", Icons.Default.VideoLibrary) {
                genreKind = HomeRows.GENRE_SERIES; genrePicker = true
            }
        }
        item {
            SettingRow("Rétablir l'accueil par défaut", icon = Icons.Default.Restore) {
                rows { HomeRows.DEFAULT }
            }
        }
    }

    if (cityDialog) {
        TextInputDialog(
            title = "Ville de la météo",
            initial = s.weatherCity,
            label = "Ville",
            placeholder = "Paris, Lyon, Bruxelles…",
            confirmLabel = "Chercher",
            onConfirm = { q ->
                scope.launch {
                    val found = runCatching { c.weather.searchPlaces(q) }.getOrNull()
                    when {
                        found == null -> c.platform.toast("Recherche impossible (pas de connexion ?)")
                        found.isEmpty() -> c.platform.toast("Aucune ville trouvée pour « $q »")
                        else -> cityResults = found
                    }
                }
            },
            onDismiss = { cityDialog = false },
        )
    }
    cityResults?.let { places ->
        ActionDialog(
            title = "Choisis la ville",
            actions = places.map { p ->
                ActionItem(p.name, Icons.Default.LocationOn, detail = p.country) {
                    update { it.copy(weatherCity = p.name, weatherLat = p.lat, weatherLon = p.lon) }
                }
            },
            onDismiss = { cityResults = null },
        )
    }
    rowMenu?.let { id ->
        val i = homeRows.indexOf(id)
        ActionDialog(
            title = HomeRows.title(id),
            actions = listOf(
                ActionItem("Monter", Icons.Default.KeyboardArrowUp, enabled = i > 0) { rows { HomeRows.move(it, id, -1) } },
                ActionItem("Descendre", Icons.Default.KeyboardArrowDown, enabled = i in 0 until homeRows.size - 1) {
                    rows { HomeRows.move(it, id, 1) }
                },
                ActionItem("Retirer de l'accueil", Icons.Default.Close) { rows { HomeRows.toggle(it, id, false) } },
            ),
            onDismiss = { rowMenu = null },
        )
    }
    if (genrePicker) {
        val prefix = genreKind ?: HomeRows.GENRE_MOVIES
        ActionDialog(
            title = if (prefix == HomeRows.GENRE_MOVIES) "Films : quel genre ?" else "Séries : quel genre ?",
            actions = Genres.ALL.sortedBy { it.label }.map { g ->
                val id = prefix + g.id
                val on = id in homeRows
                ActionItem(g.label, if (on) null else Icons.Default.Add, detail = if (on) "Déjà sur l'accueil" else "", enabled = !on) {
                    rows { HomeRows.toggle(it, id, true) }
                }
            },
            onDismiss = { genrePicker = false },
        )
    }
}

private val SCREENSAVER_CHOICES = listOf(0, 5, 10, 15, 30)
