package fr.yannickuhrig.vyzo.ui

import fr.yannickuhrig.vyzo.ui.common.InputMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import fr.yannickuhrig.vyzo.player.ResizeMode
import fr.yannickuhrig.vyzo.ui.common.VideoSurface
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.link.deepLinkTarget
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.schedule.AppEvent
import fr.yannickuhrig.vyzo.ui.common.ActionDialog
import fr.yannickuhrig.vyzo.ui.common.ActionItem
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.LocalFormFactor
import fr.yannickuhrig.vyzo.ui.common.PlayKind
import fr.yannickuhrig.vyzo.ui.common.Routes
import fr.yannickuhrig.vyzo.ui.common.Tile
import fr.yannickuhrig.vyzo.ui.common.LocalDesktop
import fr.yannickuhrig.vyzo.ui.common.isTv
import fr.yannickuhrig.vyzo.ui.common.isDesktop
import fr.yannickuhrig.vyzo.ui.common.PlatformBackHandler
import fr.yannickuhrig.vyzo.ui.downloads.DownloadsScreen
import fr.yannickuhrig.vyzo.ui.guide.GuideScreen
import fr.yannickuhrig.vyzo.ui.home.HomeEditScreen
import fr.yannickuhrig.vyzo.ui.home.HomeActions
import fr.yannickuhrig.vyzo.ui.home.HomeScreen
import fr.yannickuhrig.vyzo.ui.home.LocalHomeScreen
import fr.yannickuhrig.vyzo.data.db.EpisodeEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.ui.vod.VariantDetails
import fr.yannickuhrig.vyzo.ui.vod.LocalVariantDetails
import fr.yannickuhrig.vyzo.ui.live.LiveScreen
import fr.yannickuhrig.vyzo.ui.player.PlayerScreen
import fr.yannickuhrig.vyzo.ui.scheduled.ScheduledScreen
import fr.yannickuhrig.vyzo.ui.search.SearchScreen
import fr.yannickuhrig.vyzo.ui.sport.SportScreen
import fr.yannickuhrig.vyzo.ui.profile.ActiveProfileBadge
import fr.yannickuhrig.vyzo.ui.profile.PinDialog
import fr.yannickuhrig.vyzo.ui.profile.ProfileAvatar
import fr.yannickuhrig.vyzo.ui.profile.ProfilesScreen
import fr.yannickuhrig.vyzo.ui.profile.WhoIsWatchingScreen
import fr.yannickuhrig.vyzo.ui.profile.rememberParentalGate
import fr.yannickuhrig.vyzo.ui.settings.AccountScreen
import fr.yannickuhrig.vyzo.ui.settings.ChannelsEditScreen
import fr.yannickuhrig.vyzo.ui.settings.EpgSourcesScreen
import fr.yannickuhrig.vyzo.ui.settings.GroupsScreen
import fr.yannickuhrig.vyzo.ui.settings.GroupOrderScreen
import fr.yannickuhrig.vyzo.ui.settings.PlaylistEditScreen
import fr.yannickuhrig.vyzo.ui.settings.SettingsScreen
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import fr.yannickuhrig.vyzo.ui.common.VyzoMark
import fr.yannickuhrig.vyzo.ui.common.VyzoWordmark
import fr.yannickuhrig.vyzo.ui.theme.VyzoTheme
import fr.yannickuhrig.vyzo.ui.vod.MovieDetailScreen
import fr.yannickuhrig.vyzo.ui.vod.SeriesDetailScreen
import fr.yannickuhrig.vyzo.ui.vod.VodBrowser
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import fr.yannickuhrig.vyzo.data.cloud.VaultState
import kotlinx.coroutines.launch

sealed interface LaunchRequest {
    data class PlayChannel(val uid: String) : LaunchRequest
    data class Reminder(val id: Long) : LaunchRequest
    data class OpenSeries(val uid: String) : LaunchRequest
    data class Open(val link: fr.yannickuhrig.vyzo.data.link.DeepLink) : LaunchRequest
}

data class Dest(val route: String, val label: String, val icon: ImageVector)

private val tvDestinations = listOf(
    Dest(Routes.SEARCH, "Recherche", Icons.Default.Search),
    Dest(Routes.HOME, "Accueil", Icons.Default.Home),
    Dest(Routes.LIVE, "TV en direct", Icons.Default.LiveTv),
    Dest(Routes.GUIDE, "Guide TV", Icons.Default.CalendarViewDay),
    Dest(Routes.SPORT, "Sport", Icons.Default.SportsSoccer),
    Dest(Routes.MOVIES, "Films", Icons.Default.Movie),
    Dest(Routes.SERIES, "Séries", Icons.Default.VideoLibrary),
    Dest(Routes.RECORDINGS, "Enregistrements", Icons.Default.FiberManualRecord),
    Dest(Routes.SETTINGS, "Réglages", Icons.Default.Settings),
)

private val phoneTabs = listOf(
    Dest(Routes.HOME, "Accueil", Icons.Default.Home),
    Dest(Routes.LIVE, "Direct", Icons.Default.LiveTv),
    Dest(Routes.GUIDE, "Guide", Icons.Default.CalendarViewDay),
    Dest(Routes.MOVIES, "Films", Icons.Default.Movie),
    Dest(Routes.SERIES, "Séries", Icons.Default.VideoLibrary),
    Dest(Routes.RECORDINGS, "Enreg.", Icons.Default.FiberManualRecord),
)

private val topLevel = tvDestinations.map { it.route }.toSet()

private fun titleFor(route: String?): String = when {
    route == null -> ""
    route == Routes.HOME -> "Accueil"
    route == Routes.HOME_EDIT -> "Personnaliser l'accueil"
    route == Routes.LIVE -> "TV en direct"
    route == Routes.GUIDE -> "Guide TV"
    route == Routes.SPORT -> "Sport"
    route == Routes.MOVIES -> "Films"
    route == Routes.SERIES -> "Séries"
    route == Routes.RECORDINGS -> "Enregistrements"
    route == Routes.DOWNLOADS -> "Téléchargements"
    route == Routes.SEARCH -> "Recherche"
    route == Routes.SETTINGS -> "Réglages"
    route == Routes.EPG -> "Sources du guide"
    route == Routes.GROUPS -> "Groupes et catégories"
    route == Routes.GROUP_ORDER -> "Gérer l'ordre des groupes"
    route == Routes.PROFILES -> "Profils"
    route == Routes.ACCOUNT -> "Compte Vyzo"
    route == Routes.CHANNELS_EDIT -> "Chaînes"
    route.startsWith("playlist") -> "Playlist"
    else -> ""
}

@Composable
fun AppRoot(
    container: AppContainer,
    launch: MutableStateFlow<LaunchRequest?>,
    homeScreen: (@Composable (HomeActions) -> Unit)? = null,
    details: VariantDetails? = null,
    variantNav: VariantNav? = null,
) {
    val settings by container.settings.state.collectAsState()
    val form = remember(settings.uiMode) { container.platform.formFactor(settings.uiMode) }
    CompositionLocalProvider(
        LocalFormFactor provides form,
        LocalContainer provides container,
        LocalDesktop provides container.platform.isDesktop,
        LocalHomeScreen provides homeScreen,
        LocalVariantDetails provides details,
        LocalVariantNav provides variantNav,
    ) {
        VyzoTheme {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                val playlists by container.repo.playlists.collectAsState(initial = null)
                var onboarding by rememberSaveable { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(playlists) {
                    if (onboarding == null && playlists != null) onboarding = playlists!!.isEmpty()
                }
                when (onboarding) {
                    null -> Box(Modifier.fillMaxSize())
                    true -> Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        PlaylistEditScreen(0, firstRun = true, onDone = { onboarding = false })
                    }
                    false -> ProfileGate { MainNav(launch) }
                }
            }
        }
    }
}

/** « Qui regarde ? » au lancement quand il y a plusieurs profils (et que l'option est active). */
@Composable
fun ProfileGate(content: @Composable () -> Unit) {
    val c = LocalContainer.current
    val profiles by c.profiles.profiles.collectAsState()
    val settings by c.settings.state.collectAsState()
    var chosen by rememberSaveable { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    val list = profiles ?: return Box(Modifier.fillMaxSize())
    if (chosen || !settings.askProfileOnStart || list.size < 2) {
        // La question se pose au lancement : un profil arrivé ensuite (restauration, compte…) n'interrompt pas la session.
        SideEffect { chosen = true }
        content()
        return
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (managing) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { managing = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                    Text("Profils", style = MaterialTheme.typography.titleLarge)
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) { ProfilesScreen() }
            }
            PlatformBackHandler { managing = false }
        } else {
            WhoIsWatchingScreen(onPicked = { chosen = true }, onManage = { managing = true })
        }
    }
}

@Composable
fun MainNav(launch: MutableStateFlow<LaunchRequest?>) {
    val c = LocalContainer.current
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tv = isTv()
    val isPlayer = route?.startsWith("player") == true
    var reminderPopup by remember { mutableStateOf<fr.yannickuhrig.vyzo.data.db.ReminderEntity?>(null) }
    val cloudStatus by c.cloud.status.collectAsState()
    var vaultAsked by rememberSaveable { mutableStateOf(false) }
    var profilePicker by remember { mutableStateOf(false) }
    // Télécommande : une entrée du rail par écran principal, pour y ramener le focus.
    val railItems = remember { tvDestinations.associate { it.route to FocusRequester() } }
    var railFocused by remember { mutableStateOf(false) }

    // Vyzo PC : Échap revient à l'écran précédent (sur Android, NavHost gère déjà la touche Retour).
    val desktop = isDesktop()
    PlatformBackHandler(enabled = desktop && !isPlayer && nav.previousBackStackEntry != null) { nav.popBackStack() }

    // Écran du bas de la pile : Retour y ramène depuis les autres écrans principaux, puis quitte l'appli.
    // « TV en direct », ou l'accueil quand il est choisi comme écran de démarrage.
    var baseRoute by remember { mutableStateOf(Routes.LIVE) }

    fun navigateTop(r: String) {
        nav.navigate(r) {
            popUpTo(baseRoute) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Couper l'aperçu en direct quand on part vers les films / séries (sauf si mini-lecteur actif).
    LaunchedEffect(route) {
        val vodRoute = route == Routes.MOVIES || route == Routes.SERIES || route?.startsWith("movie/") == true ||
            route?.startsWith("seriesDetail/") == true || route == Routes.RECORDINGS
        if (!c.pip.isMiniPlayerMode.value && vodRoute && c.player.current.value?.isLive == true) c.player.stop()
    }

    // Lue une seule fois : la demande est effacée dès qu'elle est traitée, parfois avant la fin de ce démarrage.
    val startedByRequest = remember { launch.value != null }
    val gate = rememberParentalGate()

    // Démarrage : dernière chaîne, intents (notification de rappel…), événements internes.
    LaunchedEffect(Unit) {
        val s = c.settings.get()
        // Accueil choisi comme écran de démarrage : il remplace « TV en direct » en bas de la pile,
        // pour que Retour depuis l'accueil quitte l'appli au lieu de passer par « TV en direct ».
        if (s.startScreen == "HOME" && !startedByRequest) {
            baseRoute = Routes.HOME
            nav.navigate(Routes.HOME) {
                popUpTo(Routes.LIVE) { inclusive = true }
                launchSingleTop = true
            }
        }
        if (s.autoplayLast && s.lastChannelUid.isNotBlank() && !startedByRequest && c.repo.channel(s.lastChannelUid) != null) {
            nav.navigate(Routes.channel(s.lastChannelUid, s.lastGroupKey))
        }
    }
    val req by launch.collectAsState()
    LaunchedEffect(req) {
        when (val r = req) {
            is LaunchRequest.PlayChannel -> {
                if (c.repo.channel(r.uid) != null) nav.navigate(Routes.channel(r.uid, Repository.KEY_ALL))
                launch.value = null
            }
            is LaunchRequest.Reminder -> {
                reminderPopup = c.db.reminders().get(r.id)
                launch.value = null
            }
            is LaunchRequest.OpenSeries -> {
                if (c.db.series().get(r.uid) != null) nav.navigate(Routes.series(r.uid))
                launch.value = null
            }
            is LaunchRequest.Open -> {
                val target = c.deepLinkTarget(r.link)
                if (target == null) c.platform.toast("Ce contenu n'est pas disponible")
                else gate(target.locked) { nav.navigate(target.route) }
                launch.value = null
            }
            null -> Unit
        }
    }
    LaunchedEffect(Unit) {
        c.events.flow.collect { e -> if (e is AppEvent.ReminderDue) reminderPopup = e.reminder }
    }
    LaunchedEffect(Unit) {
        c.cloud.incomingCastCommands.collect { cmd ->
            val myId = c.platform.deviceModel
            if (cmd.senderDeviceId != null && cmd.senderDeviceId == myId) return@collect
            if (cmd.targetDeviceId != null && cmd.targetDeviceId != myId && cmd.targetDeviceId != "all") return@collect
            val route = when (cmd.itemType) {
                PlayKind.CHANNEL -> Routes.channel(cmd.itemId, Repository.KEY_ALL)
                PlayKind.MOVIE -> Routes.player(PlayKind.MOVIE, cmd.itemId, start = cmd.positionMs)
                PlayKind.EPISODE -> Routes.player(PlayKind.EPISODE, cmd.itemId, start = cmd.positionMs)
                else -> null
            }
            if (route != null) {
                nav.navigate(route)
            }
        }
    }

    StartupNotices()

    // Connecté au compte sans la clé (connexion faite avant la 1.9.0, par exemple) : les playlists de cet appareil
    // ne suivent pas le compte, et un autre appareil qui se connecte ne les retrouve pas. On le dit une fois par lancement.
    if (!vaultAsked && cloudStatus.vault == VaultState.LOCKED && !c.settings.state.value.vaultPromptOff && !isPlayer) {
        ActionDialog(
            title = "Tes playlists ne suivent pas encore ton compte",
            body = "Saisis une fois le mot de passe de ton compte Vyzo sur cet appareil : ses playlists IPTV, son guide et ses réglages " +
                "seront alors retrouvés sur tes autres appareils dès qu'ils se connectent.",
            actions = listOf(
                ActionItem("Saisir le mot de passe", Icons.Default.Key) {
                    vaultAsked = true
                    nav.navigate(Routes.ACCOUNT) { launchSingleTop = true }
                },
                ActionItem("Plus tard") { vaultAsked = true },
                ActionItem("Ne plus demander") {
                    vaultAsked = true
                    c.scope.launch { c.settings.update { it.copy(vaultPromptOff = true) } }
                },
            ),
            onDismiss = { vaultAsked = true },
        )
    }
    reminderPopup?.let { r ->
        ActionDialog(
            title = "C'est l'heure : ${r.title}",
            subtitle = "${Fmt.time(r.start)} sur ${r.channelName}",
            actions = listOf(
                ActionItem("Regarder", Icons.Default.LiveTv) { nav.navigate(Routes.channel(r.channelUid, Repository.KEY_ALL)) },
                ActionItem("Ignorer") {},
            ),
            onDismiss = { reminderPopup = null },
        )
    }

    // TV et PC : position et style du menu (Réglages → Interface).
    val navSettings by c.settings.state.collectAsState()
    val navLayout = NavLayout.from(navSettings)
    val variantNav = LocalVariantNav.current
    // Les fiches de la variante occupent tout l'écran, sans le menu.
    val fullScreenDetail = variantNav != null && (route == Routes.MOVIE || route == Routes.SERIES_DETAIL)
    val showNav = tv && !isPlayer && !fullScreenDetail
    val navSpace = if (showNav) navLayout.reserved else 0.dp
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .then(if (!isPlayer) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
                .padding(
                    start = if (navLayout.position == NavPosition.LEFT) navSpace else 0.dp,
                    end = if (navLayout.position == NavPosition.RIGHT) navSpace else 0.dp,
                    top = if (navLayout.position == NavPosition.TOP) navSpace else 0.dp,
                    bottom = if (navLayout.position == NavPosition.BOTTOM) navSpace else 0.dp,
                ),
        ) {
            if (!tv && !isPlayer) PhoneTopBar(nav, route, onProfile = { profilePicker = true })
            // Variante : en revenant du menu, la télécommande retrouve l'élément qu'elle avait quitté.
            Box(Modifier.weight(1f).fillMaxWidth().then(if (variantNav != null) Modifier.focusRestorer().focusGroup() else Modifier)) {
                NavGraph(nav)
            }
            // Télécommande : sur un écran principal, Retour ouvre d'abord le menu de gauche (sur l'écran affiché).
            // Depuis le menu, Retour revient à « TV en direct », puis quitte : plus de sortie de l'appli par mégarde.
            // Déclaré après le NavHost pour passer avant lui.
            PlatformBackHandler(enabled = tv && !desktop && !isPlayer && route in topLevel && !railFocused && !profilePicker) {
                runCatching { railItems[route]?.requestFocus() }
            }
            if (!tv && !isPlayer && route in phoneTabs.map { it.route }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    phoneTabs.forEach { d ->
                        NavigationBarItem(
                            selected = route == d.route, onClick = { navigateTop(d.route) },
                            icon = { Icon(d.icon, d.label) }, label = { Text(d.label, maxLines = 1) },
                        )
                    }
                }
            }
        }
        if (showNav) {
            val menuModifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
            if (variantNav != null) {
                val side = when (navLayout.position) {
                    NavPosition.LEFT -> Alignment.TopStart
                    NavPosition.RIGHT -> Alignment.TopEnd
                    NavPosition.TOP -> Alignment.TopCenter
                    NavPosition.BOTTOM -> Alignment.BottomCenter
                }
                variantNav.menu(
                    MenuArgs(
                        navLayout, route, tvDestinations, railItems,
                        onSelect = { navigateTop(it) }, onProfile = { profilePicker = true },
                        onFocusInside = { railFocused = it },
                        modifier = menuModifier.align(side),
                    ),
                )
            } else {
                MainMenu(
                    navLayout, route, railItems,
                    onSelect = { navigateTop(it) }, onProfile = { profilePicker = true },
                    onFocusInside = { railFocused = it },
                    modifier = menuModifier,
                )
            }
        }
        val isMiniPlayerMode by c.pip.isMiniPlayerMode.collectAsState()
        // L'écran de lecture est fermé : c'est le mini-lecteur qui continue d'enregistrer la position.
        var miniSavedMs by remember { mutableLongStateOf(-1L) }
        fun saveMiniPosition() {
            val pos = c.player.currentPosition
            if (abs(pos - miniSavedMs) < 1_000L) return
            miniSavedMs = pos
            val tag = c.player.tag.value
            val dur = c.player.duration
            c.scope.launch { c.repo.savePlayerPosition(tag, pos, dur) }
        }
        LaunchedEffect(isMiniPlayerMode) {
            miniSavedMs = -1L
            while (isMiniPlayerMode) {
                saveMiniPosition()
                delay(10_000L)
            }
        }
        if (!isPlayer && isMiniPlayerMode && c.player.current.value != null) {
            MiniPlayerOverlay(
                onExpand = {
                    saveMiniPosition()
                    c.pip.setMiniPlayerMode(false)
                    val target = c.pip.lastPlayerRoute ?: Routes.LIVE
                    nav.navigate(target) {
                        launchSingleTop = true
                    }
                },
                onClose = {
                    saveMiniPosition()
                    c.pip.setMiniPlayerMode(false)
                    c.player.stop()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 16.dp + if (navLayout.position == NavPosition.RIGHT) navSpace else 0.dp,
                        bottom = (if (tv) 24.dp else 96.dp) + if (navLayout.position == NavPosition.BOTTOM) navSpace else 0.dp,
                    ),
            )
        } else if (!isPlayer) {
            SyncPill(
                Modifier.align(Alignment.BottomEnd).padding(
                    bottom = (if (tv) 16.dp else 88.dp) + if (navLayout.position == NavPosition.BOTTOM) navSpace else 0.dp,
                    end = 16.dp + if (navLayout.position == NavPosition.RIGHT) navSpace else 0.dp,
                ),
            )
        }
        if (profilePicker) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                WhoIsWatchingScreen(
                    onPicked = { profilePicker = false },
                    onManage = { profilePicker = false; nav.navigate(Routes.PROFILES) { launchSingleTop = true } },
                    onClose = { profilePicker = false },
                )
            }
            PlatformBackHandler { profilePicker = false }
        }
        variantNav?.screensaver?.invoke(!isPlayer)
    }
}

/** Au lancement : mise à jour disponible, abonnement qui expire bientôt. */
@Composable
private fun StartupNotices() {
    val c = LocalContainer.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val update by c.updater.state.collectAsState()
    var updateDismissed by remember { mutableStateOf<Int?>(null) }
    var expiry by remember { mutableStateOf<String?>(null) }

    // Android garde l'appli en arrière-plan : la rouvrir ne la relance pas. On cherche donc aussi à chaque retour au
    // premier plan, puis régulièrement tant qu'elle reste affichée (une TV peut rester des jours sur une chaîne).
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(4_000)
            while (true) {
                runCatching { c.updater.check(automatic = true) }
                delay(fr.yannickuhrig.vyzo.update.AppUpdater.AUTO_CHECK_INTERVAL_MS)
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(4_000)
        val s = c.settings.get()
        if (s.expiryWarnDays > 0 && System.currentTimeMillis() - s.lastExpiryWarning > 20 * 3_600_000L) {
            val now = System.currentTimeMillis()
            val soon = c.db.playlists().all().filter { it.expiresAt > 0 && it.expiresAt < now + s.expiryWarnDays * 86_400_000L }
            if (soon.isNotEmpty()) {
                expiry = soon.joinToString("\n") { p ->
                    val days = ((p.expiresAt - now) / 86_400_000L).toInt()
                    when {
                        p.expiresAt < now -> "« ${p.name} » a expiré le ${Fmt.date(p.expiresAt)}."
                        days == 0 -> "« ${p.name} » expire aujourd'hui."
                        else -> "« ${p.name} » expire dans $days jour${if (days > 1) "s" else ""} (le ${Fmt.date(p.expiresAt)})."
                    }
                }
                c.settings.update { it.copy(lastExpiryWarning = now) }
            }
        }
    }

    expiry?.let { text ->
        ActionDialog(
            title = "Abonnement bientôt expiré", body = text,
            actions = listOf(ActionItem("Compris") {}),
            onDismiss = { expiry = null },
        )
    }

    when (val u = update) {
        is fr.yannickuhrig.vyzo.update.UpdateState.Available -> if (updateDismissed != u.info.versionCode) {
            ActionDialog(
                title = "Vyzo ${u.info.versionName} est disponible",
                body = u.info.notes.ifBlank { "Une nouvelle version de l'appli est prête à être installée." },
                actions = listOf(
                    ActionItem("Installer maintenant", Icons.Default.Settings) {
                        if (!c.updater.canInstall()) {
                            c.platform.toast("Autorise Vyzo à installer des applis, puis relance la mise à jour", long = true)
                            c.updater.openInstallPermissionSettings()
                        } else {
                            scope.launch { c.updater.downloadAndInstall(u.info) }
                        }
                    },
                    ActionItem("Plus tard") { updateDismissed = u.info.versionCode; c.updater.dismiss() },
                    ActionItem("Ignorer cette version") { scope.launch { c.updater.skip(u.info) } },
                ),
                onDismiss = { updateDismissed = u.info.versionCode },
            )
        }
        is fr.yannickuhrig.vyzo.update.UpdateState.Downloading -> fr.yannickuhrig.vyzo.ui.common.ZapDialog(onDismiss = {}) {
            Column(Modifier.padding(24.dp).width(360.dp)) {
                Text("Téléchargement de Vyzo ${u.info.versionName}", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                androidx.compose.material3.LinearProgressIndicator(progress = { u.progress }, modifier = Modifier.fillMaxWidth())
                Text("${(u.progress * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim, modifier = Modifier.padding(top = 8.dp))
            }
        }
        else -> Unit
    }
}

@Composable
private fun NavGraph(nav: NavHostController) {
    fun playChannel(uid: String, group: String) = nav.navigate(Routes.channel(uid, group))
    NavHost(nav, startDestination = Routes.LIVE) {
        composable(Routes.HOME) {
            val actions = remember(nav) {
                HomeActions(
                    onOpenMovie = { nav.navigate(Routes.movie(it)) },
                    onOpenSeries = { nav.navigate(Routes.series(it)) },
                    onPlayChannel = { uid, group -> playChannel(uid, group) },
                    onCustomize = { nav.navigate(Routes.HOME_EDIT) },
                )
            }
            val variantHome = LocalHomeScreen.current
            if (variantHome != null) {
                variantHome(actions)
            } else {
                HomeScreen(actions.onOpenMovie, actions.onOpenSeries, actions.onPlayChannel, actions.onCustomize)
            }
        }
        composable(Routes.HOME_EDIT) { HomeEditScreen() }
        composable(Routes.LIVE) {
            val open: (ChannelEntity, String) -> Unit = { ch, group -> playChannel(ch.uid, group) }
            val live = LocalVariantNav.current?.live
            if (live != null) live(open) else LiveScreen(onOpenPlayer = open)
        }
        composable(Routes.GUIDE) {
            GuideScreen(
                onWatch = { ch, group -> playChannel(ch.uid, group) },
                onCatchup = { ch, p -> nav.navigate(Routes.catchup(ch.uid, p.id)) },
            )
        }
        composable(Routes.SPORT) {
            val watch: (ChannelEntity) -> Unit = { ch -> playChannel(ch.uid, Repository.KEY_ALL) }
            val catchup: (ChannelEntity, ProgramEntity) -> Unit = { ch, p -> nav.navigate(Routes.catchup(ch.uid, p.id)) }
            val sport = LocalVariantNav.current?.sport
            if (sport != null) sport(watch, catchup) else SportScreen(onWatch = watch, onCatchup = catchup)
        }
        composable(Routes.MOVIES) {
            val open = { uid: String -> nav.navigate(Routes.movie(uid)) }
            val browse = LocalVariantNav.current?.browse
            if (browse != null) browse(ContentKind.MOVIE, open) else VodBrowser(ContentKind.MOVIE, onOpen = open)
        }
        composable(Routes.SERIES) {
            val open = { uid: String -> nav.navigate(Routes.series(uid)) }
            val browse = LocalVariantNav.current?.browse
            if (browse != null) browse(ContentKind.SERIES, open) else VodBrowser(ContentKind.SERIES, onOpen = open)
        }
        composable(Routes.RECORDINGS) {
            ScheduledScreen(
                onPlayRecording = { id -> nav.navigate(Routes.player(PlayKind.RECORDING, id.toString())) },
                onWatchChannel = { uid -> playChannel(uid, Repository.KEY_ALL) },
                onPlayMovie = { uid -> nav.navigate(Routes.player(PlayKind.MOVIE, uid)) },
                onPlayEpisode = { uid -> nav.navigate(Routes.player(PlayKind.EPISODE, uid)) },
            )
        }
        composable(Routes.DOWNLOADS) {
            DownloadsScreen(
                onPlayMovie = { uid -> nav.navigate(Routes.player(PlayKind.MOVIE, uid)) },
                onPlayEpisode = { uid -> nav.navigate(Routes.player(PlayKind.EPISODE, uid)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SEARCH) {
            val actions = remember(nav) {
                SearchActions(
                    onChannel = { playChannel(it.uid, Repository.KEY_ALL) },
                    onMovie = { nav.navigate(Routes.movie(it)) },
                    onSeries = { nav.navigate(Routes.series(it)) },
                    onCatchup = { ch, p -> nav.navigate(Routes.catchup(ch.uid, p.id)) },
                )
            }
            val search = LocalVariantNav.current?.search
            if (search != null) search(actions)
            else SearchScreen(actions.onChannel, actions.onMovie, actions.onSeries, actions.onCatchup)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onEditPlaylist = { nav.navigate(Routes.playlistEdit(it)) },
                onEpgSources = { nav.navigate(Routes.EPG) },
                onGroups = { nav.navigate(Routes.GROUPS) },
                onGroupOrder = { nav.navigate(Routes.GROUP_ORDER) },
                onProfiles = { nav.navigate(Routes.PROFILES) },
                onAccount = { nav.navigate(Routes.ACCOUNT) },
                onDownloads = { nav.navigate(Routes.DOWNLOADS) },
                onHome = { nav.navigate(Routes.HOME_EDIT) },
            )
        }
        composable(Routes.EPG) { EpgSourcesScreen() }
        composable(Routes.GROUPS) { GroupsScreen(onEditChannels = { nav.navigate(Routes.channelsEdit(it)) }) }
        composable(Routes.GROUP_ORDER) { GroupOrderScreen() }
        composable(Routes.PROFILES) { ProfilesScreen() }
        composable(Routes.ACCOUNT) { AccountScreen() }
        composable(Routes.CHANNELS_EDIT, arguments = listOf(navArgument("cat") { type = NavType.StringType })) { e ->
            ChannelsEditScreen(e.arguments?.read { getStringOrNull("cat") }.orEmpty())
        }
        composable(Routes.PLAYLIST_EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            PlaylistEditScreen(e.arguments?.read { getLongOrNull("id") } ?: 0, firstRun = false, onDone = { nav.popBackStack() })
        }
        composable(Routes.MOVIE, arguments = listOf(navArgument("uid") { type = NavType.StringType })) { e ->
            val uid = e.arguments?.read { getStringOrNull("uid") }.orEmpty()
            val onPlay = { u: String, start: Long -> nav.navigate(Routes.player(PlayKind.MOVIE, u, start = start)) }
            val onOpenMovie = { mUid: String -> nav.navigate(Routes.movie(mUid)) }
            val onOpenSeries = { sUid: String -> nav.navigate(Routes.series(sUid)) }
            val variant = LocalVariantDetails.current
            if (variant != null) variant.movie(uid, onPlay, onOpenMovie, onOpenSeries)
            else MovieDetailScreen(uid = uid, onPlay = onPlay, onOpenMovie = onOpenMovie, onOpenSeries = onOpenSeries)
        }
        composable(Routes.SERIES_DETAIL, arguments = listOf(navArgument("uid") { type = NavType.StringType })) { e ->
            val uid = e.arguments?.read { getStringOrNull("uid") }.orEmpty()
            val onPlayEpisode = { ep: EpisodeEntity, start: Long -> nav.navigate(Routes.player(PlayKind.EPISODE, ep.uid, start = start)) }
            val onOpenMovie = { mUid: String -> nav.navigate(Routes.movie(mUid)) }
            val onOpenSeries = { sUid: String -> nav.navigate(Routes.series(sUid)) }
            val variant = LocalVariantDetails.current
            if (variant != null) variant.series(uid, onPlayEpisode, onOpenMovie, onOpenSeries)
            else SeriesDetailScreen(uid = uid, onPlayEpisode = onPlayEpisode, onOpenMovie = onOpenMovie, onOpenSeries = onOpenSeries)
        }
        composable(
            Routes.PLAYER,
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType; defaultValue = PlayKind.CHANNEL },
                navArgument("uid") { type = NavType.StringType; defaultValue = "" },
                navArgument("group") { type = NavType.StringType; defaultValue = "" },
                navArgument("program") { type = NavType.LongType; defaultValue = 0L },
                navArgument("start") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { e ->
            val a = e.arguments
            PlayerScreen(
                kind = a?.read { getStringOrNull("kind") } ?: PlayKind.CHANNEL,
                uid = a?.read { getStringOrNull("uid") }.orEmpty(),
                group = a?.read { getStringOrNull("group") }.orEmpty(),
                programId = a?.read { getLongOrNull("program") } ?: 0,
                startMs = a?.read { getLongOrNull("start") } ?: -1,
                onExit = { if (!nav.popBackStack()) nav.navigate(Routes.LIVE) { launchSingleTop = true } },
                onCatchup = { chUid, pid ->
                    nav.navigate(Routes.catchup(chUid, pid)) { popUpTo(Routes.PLAYER) { inclusive = true } }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneTopBar(nav: NavHostController, route: String?, onProfile: () -> Unit) {
    val isTop = route in topLevel
    val c = LocalContainer.current
    val isVaultUnlocked by c.isVaultUnlocked.collectAsState()
    val pinSet by c.parental.pinSet.collectAsState()
    var showVaultPin by remember { mutableStateOf(false) }
    var clickCount by remember { mutableIntStateOf(0) }
    var lastClickTime by remember { mutableLongStateOf(0L) }

    fun triggerVaultUnlock() {
        if (pinSet) {
            showVaultPin = true
        }
    }

    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isTop) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        val headerModifier = Modifier
            .weight(1f)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = { triggerVaultUnlock() },
                onClick = {
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime < 500) {
                        clickCount++
                        if (clickCount >= 3) {
                            clickCount = 0
                            triggerVaultUnlock()
                        }
                    } else {
                        clickCount = 1
                    }
                    lastClickTime = now
                },
            )
        if (isTop && route == Routes.LIVE) {
            Row(headerModifier, verticalAlignment = Alignment.CenterVertically) {
                VyzoWordmark(markSize = 28.dp, fontSize = 22)
                Spacer(Modifier.width(8.dp))
                VersionBadge(c.platform.appVersion)
            }
        } else {
            Text(
                titleFor(route),
                style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = headerModifier,
            )
        }
        if (isVaultUnlocked) {
            IconButton(onClick = { c.lockVault() }) {
                Icon(
                    Icons.Default.LockOpen,
                    "Reverrouiller le coffre-fort",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (isTop) {
            IconButton(onClick = onProfile) { ActiveProfileBadge(28.dp) }
            // Le menu du bas est plein sur téléphone : l'espace sport s'ouvre d'ici.
            if (!isTv()) IconButton(onClick = { nav.navigate(Routes.SPORT) { launchSingleTop = true } }) { Icon(Icons.Default.SportsSoccer, "Sport") }
            IconButton(onClick = { nav.navigate(Routes.SEARCH) { launchSingleTop = true } }) { Icon(Icons.Default.Search, "Recherche") }
            IconButton(onClick = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } }) { Icon(Icons.Default.Settings, "Réglages") }
        }
    }

    if (showVaultPin) {
        PinDialog(
            title = "Coffre-fort",
            subtitle = "Saisis ton code pour afficher les contenus masqués",
            onDismiss = { showVaultPin = false },
        ) { pin ->
            val ok = c.unlockVault(pin)
            if (ok) showVaultPin = false
            ok
        }
    }
}

/** Pastille « v1.7.1 » affichée à côté du logo. */
@Composable
private fun VersionBadge(version: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            "v$version",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** Menu principal de la TV et du PC, placé selon [layout] (rail à gauche ou à droite, barre en haut ou en bas). */
@Composable
fun BoxScope.MainMenu(
    layout: NavLayout,
    route: String?,
    items: Map<String, FocusRequester> = remember { tvDestinations.associate { it.route to FocusRequester() } },
    onSelect: (String) -> Unit,
    onProfile: () -> Unit = {},
    onFocusInside: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when (layout.position) {
        NavPosition.LEFT, NavPosition.RIGHT -> SideRail(
            route, items, onSelect = onSelect, onProfile = onProfile, onFocusInside = onFocusInside, style = layout.style,
            modifier = modifier.align(if (layout.position == NavPosition.RIGHT) Alignment.TopEnd else Alignment.TopStart),
        )
        NavPosition.TOP, NavPosition.BOTTOM -> NavBar(
            route, items, onSelect = onSelect, onProfile = onProfile, onFocusInside = onFocusInside,
            modifier = modifier.align(if (layout.position == NavPosition.BOTTOM) Alignment.BottomCenter else Alignment.TopCenter),
        )
    }
}

/** Menu principal en barre horizontale (en haut ou en bas de l'écran) : le nom s'affiche sur l'entrée choisie. */
@Composable
private fun NavBar(
    route: String?,
    items: Map<String, FocusRequester>,
    onSelect: (String) -> Unit,
    onProfile: () -> Unit,
    onFocusInside: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalContainer.current
    val profile by c.profiles.active.collectAsState()
    val isVaultUnlocked by c.isVaultUnlocked.collectAsState()
    var focusedInside by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().height(NavLayout.BAR_HEIGHT)
            .background(VyzoColors.Background.copy(alpha = 0.97f))
            .onFocusChanged {
                if (it.hasFocus != focusedInside) {
                    focusedInside = it.hasFocus
                    onFocusInside(it.hasFocus)
                }
            }
            // ▲ ou ▼ depuis l'écran arrive sur l'écran affiché, pas sur l'entrée qui se trouve au-dessus.
            .focusProperties {
                onEnter = {
                    if (requestedFocusDirection != FocusDirection.Enter) {
                        items[route]?.let { r -> runCatching { r.requestFocus() } }
                    }
                }
            }
            .focusGroup()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        VyzoMark(30.dp)
        Spacer(Modifier.width(14.dp))
        tvDestinations.forEach { d ->
            val selected = route == d.route
            Tile(
                onClick = { onSelect(d.route) },
                modifier = items[d.route]?.let { Modifier.focusRequester(it) } ?: Modifier,
                shape = RoundedCornerShape(50), focusScale = 1f,
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                focusedColor = MaterialTheme.colorScheme.primary,
            ) { focused ->
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val tint = when {
                        focused -> MaterialTheme.colorScheme.onPrimary
                        selected -> MaterialTheme.colorScheme.primary
                        else -> VyzoColors.TextDim
                    }
                    Icon(d.icon, d.label, Modifier.size(22.dp), tint = tint)
                    // Le nom seulement sur l'entrée choisie ou sélectionnée : la barre tient sur un écran de TV.
                    if (focused || selected) {
                        Spacer(Modifier.width(8.dp))
                        Text(d.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, color = tint)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (isVaultUnlocked) {
            Tile(onClick = { c.lockVault() }, shape = RoundedCornerShape(50), focusScale = 1f, focusedColor = MaterialTheme.colorScheme.primary) { focused ->
                Icon(
                    Icons.Default.LockOpen, "Reverrouiller le coffre-fort", Modifier.padding(8.dp).size(22.dp),
                    tint = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                )
            }
        }
        profile?.let { p ->
            Tile(onClick = onProfile, shape = RoundedCornerShape(50), focusScale = 1f, focusedColor = MaterialTheme.colorScheme.primary) {
                Box(Modifier.padding(5.dp)) { ProfileAvatar(p.name, p.colorIndex, 30.dp, p.isKid) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SideRail(
    route: String?,
    items: Map<String, FocusRequester>,
    onSelect: (String) -> Unit,
    onProfile: () -> Unit,
    onFocusInside: (Boolean) -> Unit,
    style: NavStyle = NavStyle.AUTO,
    modifier: Modifier = Modifier,
) {
    val c = LocalContainer.current
    val profile by c.profiles.active.collectAsState()
    val isVaultUnlocked by c.isVaultUnlocked.collectAsState()
    val pinSet by c.parental.pinSet.collectAsState()
    var showVaultPin by remember { mutableStateOf(false) }
    var clickCount by remember { mutableIntStateOf(0) }
    var lastClickTime by remember { mutableLongStateOf(0L) }

    fun triggerVaultUnlock() {
        if (pinSet) {
            showVaultPin = true
        }
    }

    var focusedInside by remember { mutableStateOf(false) }
    // Sur PC, le rail s'ouvre aussi au survol de la souris.
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    // Traverser le rail avec la souris pour aller sur l'écran ne l'ouvre pas : il faut s'y attarder.
    var hoverOpen by remember { mutableStateOf(false) }
    LaunchedEffect(hovered) {
        if (hovered) delay(250)
        hoverOpen = hovered
    }
    // Sur PC, après un clic sur le rail, le focus y reste : on ne l'ouvre au focus qu'en navigation au clavier.
    val keyboardNav by InputMode.keyboard
    val expanded = style == NavStyle.EXPANDED || hoverOpen || (focusedInside && (!isDesktop() || keyboardNav))
    // Menu caché : invisible tant qu'on n'y va pas (Retour) ; sur PC, une fine bande au bord l'ouvre au survol.
    val closedWidth = when (style) {
        NavStyle.HIDDEN -> if (isDesktop()) 8.dp else 0.dp
        else -> NavLayout.RAIL_CLOSED
    }
    val width by animateDpAsState(if (expanded) NavLayout.RAIL_OPEN else closedWidth, label = "rail")
    Column(
        modifier.fillMaxHeight().width(width)
            // Menu caché : rien ne doit dépasser de la fine bande (ailleurs, la pastille de version peut déborder).
            .then(if (style == NavStyle.HIDDEN && !expanded) Modifier.clipToBounds() else Modifier)
            .hoverable(hover)
            .background(
                if (expanded) Brush.horizontalGradient(listOf(VyzoColors.Surface, VyzoColors.Surface.copy(alpha = 0.97f)))
                else Brush.horizontalGradient(listOf(VyzoColors.Background, VyzoColors.Background)),
            )
            .onFocusChanged {
                if (it.hasFocus != focusedInside) {
                    focusedInside = it.hasFocus
                    onFocusInside(it.hasFocus)
                }
            }
            // ◄ depuis l'écran arrive sur l'écran affiché, pas sur l'entrée du rail qui se trouve à la même hauteur.
            .focusProperties {
                onEnter = {
                    if (requestedFocusDirection != FocusDirection.Enter) {
                        items[route]?.let { r -> runCatching { r.requestFocus() } }
                    }
                }
            }
            .focusGroup()
            .padding(horizontal = 10.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        var logoFocused by remember { mutableStateOf(false) }
        Box(
            Modifier
                .height(44.dp)
                .padding(start = 8.dp)
                .onFocusChanged { logoFocused = it.isFocused }
                // Le logo prend le focus à la télécommande : sans contour, on ne voyait plus où l'on était.
                .then(if (logoFocused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onLongClick = { triggerVaultUnlock() },
                    onClick = {
                        val now = System.currentTimeMillis()
                        if (now - lastClickTime < 500) {
                            clickCount++
                            if (clickCount >= 3) {
                                clickCount = 0
                                triggerVaultUnlock()
                            }
                        } else {
                            clickCount = 1
                        }
                        lastClickTime = now
                    },
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (expanded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VyzoWordmark(markSize = 34.dp, fontSize = 24)
                    Spacer(Modifier.width(10.dp))
                    VersionBadge(c.platform.appVersion)
                }
            } else VyzoMark(34.dp)
        }
        // Rail replié : la pastille passe sous le logo.
        // Centrée : un peu plus large que le rail, elle déborde des deux côtés dans les marges (rail à droite compris).
        if (!expanded) VersionBadge(c.platform.appVersion, Modifier.align(Alignment.CenterHorizontally).wrapContentWidth(unbounded = true))
        if (isVaultUnlocked) {
            Tile(
                onClick = { c.lockVault() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                focusScale = 1f,
                focusedColor = MaterialTheme.colorScheme.primary,
            ) { focused ->
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.LockOpen,
                        "Reverrouiller le coffre-fort",
                        Modifier.size(24.dp),
                        tint = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    )
                    if (expanded) {
                        Spacer(Modifier.width(16.dp))
                        Text(
                            "Reverrouiller",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            color = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        profile?.let { p ->
            Tile(
                onClick = onProfile, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), focusScale = 1f,
                focusedColor = MaterialTheme.colorScheme.primary,
            ) { focused ->
                Row(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(p.name, p.colorIndex, 30.dp, p.isKid)
                    if (expanded) {
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                            Text("Changer de profil", style = MaterialTheme.typography.labelSmall,
                                color = if (focused) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else VyzoColors.TextDim)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        tvDestinations.forEach { d ->
            val selected = route == d.route
            Tile(
                onClick = { onSelect(d.route) },
                modifier = Modifier.fillMaxWidth().then(items[d.route]?.let { Modifier.focusRequester(it) } ?: Modifier), shape = RoundedCornerShape(12.dp), focusScale = 1f,
                focusedColor = MaterialTheme.colorScheme.primary,
            ) { focused ->
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        d.icon, d.label, Modifier.size(24.dp),
                        tint = when {
                            focused -> MaterialTheme.colorScheme.onPrimary
                            selected -> MaterialTheme.colorScheme.primary
                            else -> VyzoColors.TextDim
                        },
                    )
                    if (expanded) {
                        Spacer(Modifier.width(16.dp))
                        Text(
                            d.label, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                            color = when {
                                focused -> MaterialTheme.colorScheme.onPrimary
                                selected -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }

    if (showVaultPin) {
        PinDialog(
            title = "Coffre-fort",
            subtitle = "Saisis ton code pour afficher les contenus masqués",
            onDismiss = { showVaultPin = false },
        ) { pin ->
            val ok = c.unlockVault(pin)
            if (ok) showVaultPin = false
            ok
        }
    }
}

@Composable
private fun SyncPill(modifier: Modifier) {
    val c = LocalContainer.current
    val s by c.sync.state.collectAsState()
    var showError by remember { mutableStateOf(false) }
    LaunchedEffect(s.lastFinished) {
        if (s.error != null) {
            showError = true
            delay(8_000)
            showError = false
        }
    }
    if (!s.running && !(showError && s.error != null)) return
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (s.running) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(s.message.ifBlank { "Mise à jour…" }, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(260.dp))
        } else {
            Text("Mise à jour impossible : ${s.error}", style = MaterialTheme.typography.labelMedium, color = VyzoColors.Red, maxLines = 2, modifier = Modifier.width(300.dp))
        }
    }
}

/**
 * Mini-lecteur vidéo interne flottant et déplaçable par-dessus l'interface de navigation.
 */
@Composable
fun MiniPlayerOverlay(
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalContainer.current
    val title by c.pip.miniPlayerTitle.collectAsState()
    val subtitle by c.pip.miniPlayerSubtitle.collectAsState()
    val status by c.player.status.collectAsState()
    val isPlaying = status.isPlaying
    val onNextChannel = c.pip.onNextChannel
    val isLive = c.player.current.value?.isLive == true

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
            .width(260.dp)
            .clip(RoundedCornerShape(12.dp)),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
                VideoSurface(
                    c.player,
                    Modifier
                        .fillMaxSize()
                        .clickable(onClick = onExpand),
                    ResizeMode.FIT,
                )

                // Bouton Agrandir en haut à gauche
                IconButton(
                    onClick = onExpand,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(28.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(14.dp)),
                ) {
                    Icon(
                        Icons.Default.Fullscreen,
                        contentDescription = "Agrandir",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }

                // Bouton Fermer en haut à droite
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(28.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(14.dp)),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Fermer",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // Bandeau inférieur : titre et boutons de contrôle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onExpand)
                        .padding(end = 4.dp),
                ) {
                    Text(
                        text = title.ifBlank { "Lecture en cours" },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLive && onNextChannel != null) {
                        IconButton(
                            onClick = onNextChannel,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.SkipNext,
                                contentDescription = "Chaîne suivante",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            if (isPlaying) c.player.pause() else c.player.resume()
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Lecture",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
