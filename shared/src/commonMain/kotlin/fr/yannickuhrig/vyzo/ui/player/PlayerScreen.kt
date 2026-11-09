package fr.yannickuhrig.vyzo.ui.player

import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import fr.yannickuhrig.vyzo.ui.common.hideCursor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.yannickuhrig.vyzo.player.StreamInfo
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.produceState
import fr.yannickuhrig.vyzo.player.AUDIO_DELAY_CHOICES
import fr.yannickuhrig.vyzo.player.PlaybackState
import fr.yannickuhrig.vyzo.player.PlaybackStats
import fr.yannickuhrig.vyzo.player.SLEEP_TIMER_MINUTES
import fr.yannickuhrig.vyzo.player.SleepTimer
import fr.yannickuhrig.vyzo.player.audioDelayLabel
import fr.yannickuhrig.vyzo.player.ResizeMode
import fr.yannickuhrig.vyzo.player.TrackOption
import fr.yannickuhrig.vyzo.player.TrackType
import fr.yannickuhrig.vyzo.ui.common.PlatformBackHandler
import fr.yannickuhrig.vyzo.ui.common.PlayerWindowEffect
import fr.yannickuhrig.vyzo.ui.common.isDesktop
import fr.yannickuhrig.vyzo.ui.common.repeatCount
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.intro.SkipKind
import fr.yannickuhrig.vyzo.ui.common.Badge
import fr.yannickuhrig.vyzo.ui.common.ProgramMenu
import fr.yannickuhrig.vyzo.ui.common.Tile
import fr.yannickuhrig.vyzo.ui.common.ActionDialog
import fr.yannickuhrig.vyzo.ui.common.ActionItem
import fr.yannickuhrig.vyzo.ui.common.CastDialog
import fr.yannickuhrig.vyzo.ui.common.Choice
import fr.yannickuhrig.vyzo.ui.common.ChoiceDialog
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.data.subtitles.SrtParser
import fr.yannickuhrig.vyzo.ui.common.NetImage
import fr.yannickuhrig.vyzo.ui.common.PlayKind
import fr.yannickuhrig.vyzo.ui.common.ProgressLine
import fr.yannickuhrig.vyzo.ui.common.VideoSurface
import fr.yannickuhrig.vyzo.ui.common.appViewModel
import fr.yannickuhrig.vyzo.ui.common.isTv
import fr.yannickuhrig.vyzo.ui.live.ChannelRow
import fr.yannickuhrig.vyzo.ui.theme.VyzoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

private val resizeModes = ResizeMode.entries

/** Sur PC, les boutons du lecteur se cliquent à la souris sans prendre le focus : les raccourcis clavier restent actifs. */
private val noFocus: Modifier
    @Composable get() = if (isDesktop()) Modifier.focusProperties { canFocus = false } else Modifier

/** Chiffre tapé au clavier ou à la télécommande, -1 sinon. */
private fun digitOf(key: Key): Int = when (key) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> -1
}

@Composable
fun PlayerScreen(
    kind: String,
    uid: String,
    group: String,
    programId: Long,
    startMs: Long,
    onExit: () -> Unit,
    onCatchup: (channelUid: String, programId: Long) -> Unit,
) {
    val vm = appViewModel(key = "player-$kind-$uid-$programId-$startMs") { PlayerViewModel(it, kind, uid, group, programId, startMs) }
    val c = LocalContainer.current
    val tv = isTv()
    val ui by vm.ui.collectAsState()
    val channels by vm.channels.collectAsState()
    val nowMap by vm.nowMap.collectAsState()
    val message by vm.message.collectAsState()
    val isFav by vm.isFavorite.collectAsState()
    val groupTitle by vm.groupTitle.collectAsState()
    val playerError by c.player.error.collectAsState()
    val player = c.player
    val settings by c.settings.state.collectAsState()
    val desktop = isDesktop()
    val windowControls = c.platform.window
    val fullscreen by (windowControls?.fullscreen ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()

    val status by player.status.collectAsState()
    val isPlaying = status.isPlaying
    val buffering = status.state == PlaybackState.BUFFERING
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var pendingSeek by remember { mutableStateOf<Long?>(null) }
    var seekNonce by remember { mutableIntStateOf(0) }

    var overlay by remember { mutableStateOf(true) }
    var overlayNonce by remember { mutableIntStateOf(0) }
    var listVisible by remember { mutableStateOf(false) }
    var guideVisible by remember { mutableStateOf(false) }
    val inPip by c.pip.inPip.collectAsState()
    val streamInfo by vm.streamInfo.collectAsState()
    var optionsVisible by remember { mutableStateOf(false) }
    // Second niveau du menu : réglages techniques peu utilisés (qualité, format, flux, cast, multi-écran).
    var moreOptionsVisible by remember { mutableStateOf(false) }
    var statsVisible by remember { mutableStateOf(false) }
    var audioDelayMenuVisible by remember { mutableStateOf(false) }
    var sleepMenuVisible by remember { mutableStateOf(false) }
    val audioDelayMs by vm.audioDelayMs.collectAsState()
    val sleepEndsAt by c.sleepTimer.endsAt.collectAsState()
    val playbackStats by produceState<PlaybackStats?>(null, statsVisible) {
        while (statsVisible) {
            value = player.playbackStats()
            delay(1_000)
        }
        value = null
    }
    var trackType by remember { mutableStateOf<TrackType?>(null) }
    var resizeIndex by remember { mutableIntStateOf(0) }
    var digits by remember { mutableStateOf("") }
    var okArmed by remember { mutableStateOf(false) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }

    var outroDismissed by remember(ui.subtitle) { mutableStateOf(false) }
    var outroCountdown by remember(ui.subtitle) { mutableIntStateOf(10) }
    var autoNextTriggered by remember(ui.subtitle) { mutableStateOf(false) }
    var introSkipped by remember(ui.subtitle) { mutableStateOf(false) }
    var lastSkipKind by remember(ui.subtitle) { mutableStateOf(SkipKind.INTRO) }

    var zapIndicator by remember { mutableStateOf<ZapFeedback?>(null) }
    var zapNonce by remember { mutableIntStateOf(0) }
    var castDialogVisible by remember { mutableStateOf(false) }

    val isMultiView by vm.isMultiViewActive.collectAsState()
    val multiViewSlots by vm.multiViewSlots.collectAsState()
    val activeAudioSlot by vm.activeAudioSlot.collectAsState()
    var multiViewPickSlot by remember { mutableStateOf<Int?>(null) }
    var slotForMenu by remember { mutableStateOf<MultiViewSlot?>(null) }

    val externalSubtitles by vm.externalSubtitles.collectAsState()
    val externalSubtitleName by vm.externalSubtitleName.collectAsState()
    val subtitleDelayMs by vm.subtitleDelayMs.collectAsState()
    var subtitleSearchOpen by remember { mutableStateOf(false) }
    var subtitleCustomOpen by remember { mutableStateOf(false) }
    val effectiveSubPosition = position - subtitleDelayMs
    val currentCue = remember(externalSubtitles, effectiveSubPosition) {
        if (externalSubtitles.isEmpty()) null
        else SrtParser.findCueAt(externalSubtitles, effectiveSubPosition)
    }

    var restartBannerDismissed by remember(ui.channel?.uid, ui.now?.id) { mutableStateOf(false) }
    val showRestartPrompt = ui.canRestartCurrentProgram && !restartBannerDismissed

    LaunchedEffect(zapNonce) {
        if (zapNonce > 0) {
            delay(1500L)
            zapIndicator = null
        }
    }

    val remote = tv && !desktop
    // Habillage de la variante : pensé pour la télécommande, le tactile garde ses boutons.
    val variant = fr.yannickuhrig.vyzo.ui.LocalVariantNav.current?.player?.takeIf { remote }
    val markers by vm.markers.collectAsState()
    val knownSkip = markers.skipAt(position)
    // Retenu après la fin du passage : le texte du bouton ne doit pas changer pendant qu'il disparaît.
    LaunchedEffect(knownSkip?.kind) { knownSkip?.kind?.let { lastSkipKind = it } }
    val showSkip = vm.isSeriesEpisode && when {
        // Horodatage réel : proposé sur le passage lui-même, sauf si le saut automatique s'en charge.
        knownSkip != null -> !settings.skipsAutomatically(knownSkip)
        markers.hasIntroData -> false
        // Sans donnée, +85s reste un choix manuel. Proposé 15 s, puis seulement avec les commandes :
        // on ne sait pas où l'intro se termine, le bouton ne doit pas rester.
        // Pas à la télécommande : le bouton n'y prend pas le focus, et +85 s à l'aveugle sur OK coupait des épisodes sans intro.
        else -> !remote && !introSkipped && position in 15_000L..240_000L && (position < 30_000L || overlay)
    }
    // Vrai générique si on le connaît, sinon les 90 dernières secondes : la carte ne couvre pas la fin d'un épisode long
    // (un pourcentage la ferait venir 4 min avant).
    val isOutro = vm.isSeriesEpisode && ui.hasNextEpisode && duration > 0 &&
        (markers.creditsStartMs?.let { position >= it } ?: ((duration - position) <= 90_000L && position >= duration / 2)) &&
        position < duration - 1_000L
    val outroCardVisible = isOutro && !outroDismissed

    fun stayOnEpisode() {
        outroDismissed = true
        vm.stayAtEpisodeEnd = true
    }

    // Enchaînement automatique : 30 s avant la fin, avec 10 s pour annuler. Plus tôt, il couperait la fin de l'épisode.
    val autoNextArmed = outroCardVisible && settings.autoNextEpisodeOnOutro && (duration - position) <= 30_000L
    LaunchedEffect(autoNextArmed) {
        if (!autoNextArmed) return@LaunchedEffect
        outroCountdown = 10
        while (outroCountdown > 0) {
            delay(1_000L)
            if (player.isPlaying) outroCountdown--
        }
        if (!autoNextTriggered) {
            autoNextTriggered = true
            vm.nextEpisode()
        }
    }

    val rootFocus = remember { FocusRequester() }
    val currentUi by rememberUpdatedState(ui)

    fun showOverlay() {
        overlay = true
        overlayNonce++
    }

    fun skipPassage() {
        // Après un récap, le +85s reste possible pour l'intro quand on ne sait pas où elle est.
        if (knownSkip?.kind != SkipKind.RECAP) introSkipped = true
        vm.skipSegment()
        showOverlay()
    }

    // --- Fin de lecture : on relance le direct ; l'épisode suivant est enchaîné par le ViewModel.
    LaunchedEffect(status.state) {
        if (status.state == PlaybackState.ENDED) {
            when {
                currentUi.isLive && currentUi.timeshift?.started == true -> vm.onTimeshiftEnded()
                currentUi.isLive -> c.player.retry()
                kind == PlayKind.EPISODE -> if (!currentUi.hasNextEpisode || vm.stayAtEpisodeEnd) showOverlay()
                else -> {
                    vm.saveProgress(player.duration, player.duration)
                    showOverlay()
                }
            }
        }
    }
    // Format de la vidéo : forme de la fenêtre d'incrustation (Android).
    val videoSize by player.videoSize.collectAsState()
    LaunchedEffect(videoSize) {
        videoSize?.let { (w, h) -> c.pip.setVideoSize(w, h) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.coerceAtLeast(0)
            clock = System.currentTimeMillis()
            delay(if (externalSubtitles.isNotEmpty()) 100L else 250L)
        }
    }
    LaunchedEffect(Unit) {
        c.sleepTimer.events.collect { event ->
            when (event) {
                SleepTimer.Event.ENDING_SOON -> vm.message.value = "La lecture s'arrête dans 1 minute"
                SleepTimer.Event.EXPIRED -> {
                    if (!vm.ui.value.isLive) vm.saveProgress(player.currentPosition, player.duration)
                    if (vm.isMultiViewActive.value) vm.exitMultiView()
                    onExit()
                }
            }
        }
    }
    LaunchedEffect(seekNonce) {
        val target = pendingSeek ?: return@LaunchedEffect
        delay(350)
        player.seekTo(target)
        pendingSeek = null
    }
    if (!ui.isLive) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(10_000)
                if (player.isPlaying) vm.saveProgress(player.currentPosition, player.duration)
            }
        }
    }
    LaunchedEffect(overlayNonce, overlay, isPlaying) {
        if (overlay && (ui.isLive || isPlaying) && ui.timeshift?.paused != true) {
            delay(if (ui.isLive) 5_000 else 4_000)
            overlay = false
        }
    }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_600)
        digits.toIntOrNull()?.let { vm.playNumber(it) }
        digits = ""
    }
    LaunchedEffect(message) {
        if (message != null) { delay(3_000); vm.message.value = null }
    }
    // Fenêtre qui reprend le focus (retour du mini-lecteur, autre appli) : les touches reviennent au lecteur.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(listVisible, optionsVisible, moreOptionsVisible, trackType, guideVisible, windowFocused, overlay) {
        if (!listVisible && !optionsVisible && !moreOptionsVisible && trackType == null && !guideVisible && windowFocused) {
            delay(50)
            runCatching { rootFocus.requestFocus() }
        }
    }

    // --- Incrustation : l'activité sait qu'une vidéo plein écran est à l'écran
    DisposableEffect(Unit) {
        c.pip.setScreenActive(true)
        onDispose { c.pip.setScreenActive(false) }
    }

    // --- Fenêtre : plein écran, écran allumé, paysage sur téléphone
    PlayerWindowEffect(tv)

    // --- Cycle de vie : on coupe le direct en arrière-plan, on le relance au retour
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var stopped = false
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> {
                    stopped = true
                    if (!currentUi.isLive) vm.saveProgress(player.currentPosition, player.duration)
                }
                Lifecycle.Event.ON_START -> if (stopped) {
                    stopped = false
                    // Sur PC, le direct continue quand la fenêtre est réduite : rien à relancer.
                    if (currentUi.isLive && c.platform.stopsLiveInBackground) vm.replayCurrent()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // --- Sortie
    DisposableEffect(Unit) {
        onDispose {
            val settings = c.settings.state.value
            // Interface TV : le direct continue déjà dans l'aperçu de la TV en direct, pas besoin d'un mini-lecteur en double.
            // Abonnement à une seule connexion : on la rend en quittant (un fichier local, lui, n'en occupe pas).
            val local = c.player.current.value?.url?.startsWith("file:") == true
            val keepPlaying = settings.continuePlaybackInBackground && !(currentUi.isLive && tv) && (local || !vm.singleConnection)
            if (keepPlaying && !c.pip.isMiniPlayerMode.value && player.status.value.isPlaying) {
                c.pip.lastPlayerRoute = fr.yannickuhrig.vyzo.ui.common.Routes.player(kind, uid, group, programId, startMs)
                c.pip.setMiniPlayerInfo(currentUi.title, currentUi.subtitle)
                c.pip.setMiniPlayerMode(true)
            }
            // Si le mini-lecteur interne est actif, on ne coupe pas la lecture !
            if (c.pip.isMiniPlayerMode.value) return@onDispose
            // On ne coupe que ce que cet écran a lancé (un autre lecteur a pu prendre le relais entre-temps).
            val tag = c.player.tag.value
            val mine = if (currentUi.isLive) tag?.startsWith("ch:") == true else tag != null && tag == vm.currentTag
            if (mine) {
                if (!currentUi.isLive) {
                    vm.saveProgress(player.currentPosition, player.duration)
                    c.player.stop()
                } else if (!tv) {
                    c.player.stop()
                }
            }
        }
    }

    PlatformBackHandler {
        when {
            desktop && fullscreen -> windowControls?.toggleFullscreen()
            digits.isNotEmpty() -> digits = ""
            listVisible -> listVisible = false
            guideVisible -> guideVisible = false
            isMultiView -> vm.exitMultiView()
            // PC : Échap abandonne d'abord le différé (pause du direct ou replay) avant de quitter le lecteur.
            desktop && (ui.timeshift != null || ui.isCatchupMode) -> { vm.backToLive(); showOverlay() }
            remote && outroCardVisible -> stayOnEpisode()
            // Télécommande : Retour masque d'abord le bandeau. Sur PC, Échap quitte directement.
            overlay && !ui.isLive && tv && !desktop -> overlay = false
            else -> onExit()
        }
    }

    fun seekBy(deltaMs: Long) {
        val base = pendingSeek ?: player.currentPosition
        val max = if (duration > 0) duration - 1_000 else Long.MAX_VALUE
        pendingSeek = (base + deltaMs).coerceIn(0, max)
        seekNonce++
        showOverlay()
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else {
            if (player.status.value.state == PlaybackState.ENDED) player.seekTo(0)
            player.resume()
        }
        showOverlay()
    }

    fun toggleMute() {
        vm.message.value = if (player.toggleMute()) "Son coupé" else "Son rétabli"
    }

    fun volume(delta: Int) {
        vm.message.value = "Volume : ${player.adjustVolume(delta)} %"
    }

    fun zap(dir: Int): fr.yannickuhrig.vyzo.data.db.ChannelEntity? {
        val ch = vm.zap(if (vm.invertZap) -dir else dir)
        showOverlay()
        return ch
    }

    val keyHandler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = handler@{ ev ->
        val isOk = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
        if (listVisible || optionsVisible || moreOptionsVisible || trackType != null || guideVisible || slotForMenu != null) return@handler false
        if (isMultiView) {
            if (isOk) {
                if (ev.type == KeyEventType.KeyDown) {
                    val currentSlot = multiViewSlots.getOrNull(activeAudioSlot)
                    if (currentSlot != null) slotForMenu = currentSlot
                }
                return@handler true
            }
            if (ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight || ev.key == Key.DirectionUp || ev.key == Key.DirectionDown) {
                return@handler false
            }
            if (ev.key == Key.Menu) {
                optionsVisible = true
                return@handler true
            }
        }
        if (ev.type == KeyEventType.KeyUp) {
            if (isOk && okArmed) {
                okArmed = false
                when {
                    ui.isLive -> listVisible = true
                    // Télécommande : la carte ne prend pas le focus (les flèches gardent l'avance rapide), OK la déclenche.
                    tv && outroCardVisible -> { outroDismissed = true; vm.nextEpisode() }
                    // Seulement sur un passage réel : le bouton affiché l'annonce, et ailleurs OK reste la pause.
                    remote && showSkip && !isOutro && isPlaying -> skipPassage()
                    else -> togglePlay()
                }
                return@handler true
            }
            return@handler false
        }
        if (ev.type != KeyEventType.KeyDown) return@handler false
        val digit = digitOf(ev.key)
        if (digit >= 0 && ui.isLive) {
            // « 0 » seul : chaîne précédente tout de suite, sans attendre la fin de la saisie d'un numéro.
            if (digit == 0 && digits.isEmpty()) { if (ev.repeatCount == 0) vm.playNumber(0) }
            else if (digits.length < 4) digits += digit
            return@handler true
        }
        if (isOk) {
            if (ev.repeatCount == 0) okArmed = true
            else if (ev.repeatCount == 1) { okArmed = false; optionsVisible = true }
            return@handler true
        }
        // Raccourcis du clavier (Vyzo PC ; ils marchent aussi avec un clavier branché sur Android).
        when (ev.key) {
            Key.F, Key.F11 -> if (windowControls != null) { windowControls.toggleFullscreen(); return@handler true }
            Key.M, Key.VolumeMute -> { toggleMute(); return@handler true }
            Key.Spacebar -> {
                if (ui.isLive) { vm.toggleLivePause(); showOverlay() } else togglePlay()
                return@handler true
            }
            Key.G -> if (ui.isLive) { guideVisible = true; return@handler true }
            Key.PageUp -> if (ui.isLive) { zap(1); return@handler true }
            Key.PageDown -> if (ui.isLive) { zap(-1); return@handler true }
            Key.Plus, Key.NumPadAdd, Key.Equals -> if (desktop) { volume(5); return@handler true }
            Key.Minus, Key.NumPadSubtract -> if (desktop) { volume(-5); return@handler true }
            Key.I -> { statsVisible = !statsVisible; showOverlay(); return@handler true }
            Key.L -> if (ui.isLive) { listVisible = true; return@handler true }
            Key.P -> if (windowControls != null) { windowControls.openMiniPlayer(); return@handler true }
            else -> Unit
        }
        val repeat = ev.repeatCount
        val step = when {
            repeat < 5 -> 10_000L
            repeat < 15 -> 30_000L
            else -> 60_000L
        }
        if (ui.isLive) {
            val shifted = ui.timeshift?.started == true
            when (ev.key) {
                Key.DirectionUp, Key.ChannelUp -> { zap(1); true }
                Key.DirectionDown, Key.ChannelDown -> { zap(-1); true }
                Key.DirectionLeft -> { listVisible = true; true }
                // ▶ une fois : infos ; ▶ encore (bandeau affiché) : mini-guide.
                Key.DirectionRight -> { if (overlay) guideVisible = true else showOverlay(); true }
                Key.Info -> { statsVisible = !statsVisible; showOverlay(); true }
                Key.Guide -> { guideVisible = true; true }
                Key.Menu -> { optionsVisible = true; true }
                Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { vm.toggleLivePause(); showOverlay(); true }
                Key.MediaRewind -> { if (shifted) seekBy(-step); shifted }
                Key.MediaFastForward -> { if (shifted) seekBy(step); shifted }
                Key.MediaStop -> { if (ui.timeshift != null || ui.isCatchupMode) { vm.backToLive(); true } else false }
                Key.LastChannel -> {
                    if (!vm.playPrevious()) vm.message.value = "Pas encore de chaîne précédente"
                    showOverlay(); true
                }
                else -> false
            }
        } else {
            when (ev.key) {
                Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { togglePlay(); true }
                Key.DirectionLeft, Key.MediaRewind -> { seekBy(-step); true }
                Key.DirectionRight, Key.MediaFastForward -> { seekBy(step); true }
                Key.DirectionUp -> { if (overlay) optionsVisible = true else showOverlay(); true }
                Key.DirectionDown -> { showOverlay(); true }
                Key.Info -> { statsVisible = !statsVisible; showOverlay(); true }
                Key.Menu -> { optionsVisible = true; true }
                Key.MediaNext -> { vm.nextEpisode(); true }
                else -> false
            }
        }
    }

    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent(keyHandler)
            .focusRequester(rootFocus)
            .focusable()
            .hideCursor(desktop && !overlay && !listVisible && isPlaying)
            .pointerInput(desktop) {
                // PC : bouger la souris affiche les commandes (et le pointeur), comme dans un lecteur de bureau.
                if (!desktop) return@pointerInput
                var last = androidx.compose.ui.geometry.Offset.Unspecified
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type != PointerEventType.Move) continue
                        // Seuls les vrais déplacements comptent (Compose envoie aussi des « Move » quand l'affichage change).
                        val pos = e.changes.firstOrNull()?.position ?: continue
                        if (last.isSpecified && (pos - last).getDistance() < 3f) continue
                        last = pos
                        showOverlay()
                    }
                }
            }
            .pointerInput(ui.isLive, desktop, tv) {
                detectTapGestures(
                    onTap = {
                        if (desktop) runCatching { rootFocus.requestFocus() }
                        if (overlay) overlay = false else showOverlay()
                    },
                    onDoubleTap = { off ->
                        if (desktop) {
                            windowControls?.toggleFullscreen()
                        } else if (tv) {
                            // Sur TV, pas de geste tactile
                        } else if (ui.isLive) {
                            // Mobile Live TV : zones à 25% sur les bords
                            val w = size.width
                            when {
                                off.x < w * 0.25f -> {
                                    val ch = zap(-1)
                                    if (ch != null) {
                                        zapIndicator = ZapFeedback(isNext = false, channelName = ch.name)
                                        zapNonce++
                                    }
                                }
                                off.x > w * 0.75f -> {
                                    val ch = zap(1)
                                    if (ch != null) {
                                        zapIndicator = ZapFeedback(isNext = true, channelName = ch.name)
                                        zapNonce++
                                    }
                                }
                                else -> {
                                    if (overlay) overlay = false else showOverlay()
                                }
                            }
                        } else {
                            // Mode VOD : avance / recul classique de 10s sur mobile
                            val w = size.width
                            when {
                                off.x < w * 0.35f -> seekBy(-10_000)
                                off.x > w * 0.65f -> seekBy(10_000)
                                else -> togglePlay()
                            }
                        }
                    },
                )
            }
            .pointerInput(ui.isLive, desktop) {
                if (!ui.isLive || desktop) return@pointerInput
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        val threshold = with(density) { 70.dp.toPx() }
                        if (abs(total) > threshold) {
                            val isNext = total < 0
                            val ch = zap(if (isNext) 1 else -1)
                            if (ch != null) {
                                zapIndicator = ZapFeedback(isNext = isNext, channelName = ch.name)
                                zapNonce++
                            }
                        }
                    },
                ) { _, dy -> total += dy }
            },
    ) {
        if (isMultiView && multiViewSlots.isNotEmpty()) {
            MultiViewGrid(
                slots = multiViewSlots,
                activeAudioSlot = activeAudioSlot,
                onSelectAudioSlot = { vm.setActiveAudioSlot(it) },
                onSlotClick = { slot ->
                    slotForMenu = slot
                },
                onAddClick = {
                    multiViewPickSlot = -1
                    listVisible = true
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            VideoSurface(player, Modifier.fillMaxSize(), if (inPip) ResizeMode.FIT else resizeModes[resizeIndex])
        }

        if (currentCue != null && !inPip) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (overlay) 120.dp else 44.dp)
                    .padding(horizontal = 36.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    text = currentCue.text,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 26.sp,
                    ),
                    color = Color.White,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }

        if ((buffering || ui.resolving) && playerError == null && ui.resolveError == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(if (inPip) 28.dp else 52.dp), color = MaterialTheme.colorScheme.primary)
        }
        if (inPip) return@Box

        val err = ui.resolveError ?: playerError
        val noPanelOpen = !listVisible && !guideVisible && !optionsVisible && !moreOptionsVisible && trackType == null && slotForMenu == null
        // Les panneaux de la variante recouvriraient le message à moitié : il revient quand on les ferme.
        if (err != null && (variant == null || noPanelOpen)) {
            ErrorPanel(
                message = err, explanation = ui.limitMessage, isLive = ui.isLive,
                onRetry = { vm.replayCurrent() }, onNext = { zap(1) },
                // Un flux en échec se relance : le message ne doit pas reprendre la main à la liste qu'on parcourt pour en changer.
                takeFocus = noPanelOpen,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Bandeau d'infos (direct) ou contrôles (VOD). Pas en multi-écran : il ne parlerait que du 1er écran et
        // recouvrirait toute la grille ; chaque écran a déjà son nom et son état en haut.
        AnimatedVisibility(
            visible = !isMultiView && (overlay || (!ui.isLive && !isPlaying && !buffering && err == null)),
            enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (variant != null) {
                if (ui.isLive) variant.liveBar(LiveBarArgs(ui, clock, settings.showNumbers, position, vm.timeshiftDelay(position, clock)))
                else variant.vodBar(VodBarArgs(ui, pendingSeek ?: position, duration, isPlaying))
            } else if (ui.isLive) LiveInfoBar(
                ui = ui, clock = clock, showNumbers = settings.showNumbers, tv = tv, desktop = desktop,
                positionMs = position, delayMs = vm.timeshiftDelay(position, clock), canPause = settings.timeshiftMinutes > 0 || ui.channel?.catchupDays?.let { it > 0 } == true,
                onPrevious = { if (!vm.playPrevious()) vm.message.value = "Pas encore de chaîne précédente"; showOverlay() },
                onTogglePause = { vm.toggleLivePause(); showOverlay() },
                onGuide = { guideVisible = true },
                onLive = { vm.backToLive(); showOverlay() },
                onRestart = if (showRestartPrompt) {
                    { restartBannerDismissed = true; vm.restartCurrentProgramFromBeginning(); showOverlay() }
                } else null,
            )
            else VodControls(
                ui = ui, position = pendingSeek ?: position, duration = duration, isPlaying = isPlaying, tv = tv, desktop = desktop,
                onSeek = { player.seekTo(it); showOverlay() }, onTogglePlay = { togglePlay() },
                onBack10 = { seekBy(-10_000) }, onFwd10 = { seekBy(10_000) },
                onNext = if (ui.hasNextEpisode) ({ vm.nextEpisode() }) else null,
            )
        }

        // Barre du haut (tactile, souris)
        if (!tv || desktop) {
            val isAlwaysOnTop by player.isAlwaysOnTop.collectAsState()
            AnimatedVisibility(visible = overlay, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                PhoneTopBar(
                    ui = ui, isFav = isFav,
                    onBack = onExit,
                    onList = { listVisible = true },
                    onOptions = { optionsVisible = true },
                    onFavorite = { vm.toggleFavorite() },
                    statsVisible = statsVisible,
                    onToggleStats = { statsVisible = !statsVisible; showOverlay() },
                    onCast = { castDialogVisible = true; showOverlay() },
                    isMultiView = isMultiView,
                    onToggleMultiView = if (ui.isLive) ({
                        if (isMultiView) vm.exitMultiView() else vm.enterMultiView()
                        showOverlay()
                    }) else null,
                    fullscreen = if (windowControls != null) fullscreen else null,
                    onFullscreen = { windowControls?.toggleFullscreen() },
                    onMiniPlayer = {
                        c.pip.lastPlayerRoute = fr.yannickuhrig.vyzo.ui.common.Routes.player(kind, uid, group, programId, startMs)
                        c.pip.setMiniPlayerInfo(ui.title, ui.subtitle)
                        c.pip.setMiniPlayerMode(true)
                        onExit()
                    },
                    onPip = if (c.platform.supportsPip) ({ player.enterPictureInPicture(); showOverlay() }) else null,
                    onAlwaysOnTop = if (desktop) ({ player.toggleAlwaysOnTop(); showOverlay() }) else null,
                    isAlwaysOnTop = isAlwaysOnTop,
                )
            }
        }

        // Liste des chaînes
        AnimatedVisibility(
            visible = listVisible, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            val pickChannel: (fr.yannickuhrig.vyzo.data.db.ChannelEntity) -> Unit = { ch ->
                if (multiViewPickSlot == -1) {
                    vm.addMultiViewChannel(ch)
                } else if (multiViewPickSlot != null) {
                    vm.changeSlotChannel(multiViewPickSlot!!, ch)
                } else {
                    vm.playChannel(ch)
                }
                multiViewPickSlot = null
                listVisible = false
                showOverlay()
            }
            val closeList = {
                listVisible = false
                multiViewPickSlot = null
            }
            if (variant != null) {
                variant.channels(ChannelPanelArgs(groupTitle, channels, nowMap, ui.channel?.uid, settings.showNumbers, pickChannel, closeList))
            } else {
                ChannelListPanel(
                    title = groupTitle, channels = channels, nowMap = nowMap, currentUid = ui.channel?.uid,
                    showNumbers = settings.showNumbers, tv = tv, onPick = pickChannel, onClose = closeList,
                )
            }
        }

        // Mini-guide : programmes de la chaîne, ◀ ▶ pour regarder ceux des autres sans zapper
        AnimatedVisibility(
            visible = guideVisible && ui.channel != null, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            ui.channel?.let { ch ->
                val watch: (ChannelEntity) -> Unit = { other -> guideVisible = false; if (other.uid != ch.uid) vm.playChannel(other); showOverlay() }
                val replay: (ChannelEntity, ProgramEntity) -> Unit = { other, p -> guideVisible = false; onCatchup(other.uid, p.id) }
                val closeGuide = { guideVisible = false }
                if (variant != null) variant.guide(GuidePanelArgs(vm, ch, settings.showNumbers, watch, replay, closeGuide))
                else MiniGuidePanel(vm = vm, start = ch, tv = tv, showNumbers = settings.showNumbers, onWatch = watch, onCatchup = replay, onClose = closeGuide)
            }
        }

        if (digits.isNotEmpty()) {
            Text(
                digits, fontSize = 54.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        message?.let {
            Text(
                it, color = Color.White, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        if (settings.playerClock && !overlay && !inPip && !isMultiView && (tv || !statsVisible)) {
            Text(
                Fmt.time(clock),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(top = 24.dp, end = 32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        AnimatedVisibility(
            visible = statsVisible,
            enter = fadeIn() + slideInVertically { -it / 4 },
            exit = fadeOut() + slideOutVertically { -it / 4 },
            modifier = Modifier.align(if (tv) Alignment.TopStart else Alignment.TopEnd)
                .padding(top = if (!tv || desktop) 64.dp else 32.dp, start = 24.dp, end = 24.dp),
        ) {
            StreamInfoOverlay(info = streamInfo, stats = playbackStats, onClose = { statsVisible = false })
        }

        // Bouton rapide : Passer le récap ou l'intro (séries)
        AnimatedVisibility(
            visible = showSkip && !isOutro,
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut() + slideOutHorizontally { it / 2 },
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = if (tv) 48.dp else 20.dp, bottom = if (overlay) 140.dp else 36.dp),
        ) {
            Button(
                onClick = { skipPassage() },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color.Black.copy(alpha = 0.85f),
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(20.dp)),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = null, tint = VyzoColors.Cyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                // Sur les données et non sur la position : le texte ne doit pas changer pendant que le bouton disparaît.
                val blindSkip = knownSkip == null && showSkip
                val skipLabel = when {
                    blindSkip -> null
                    lastSkipKind == SkipKind.RECAP -> "Passer le récap"
                    markers.hasIntroData -> "Passer l'intro"
                    else -> null
                }
                Text(
                    skipLabel?.let { if (remote) "$it · OK" else it } ?: "+85s (Passer l'intro)",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        // Carte de transition : Épisode suivant (Outro)
        AnimatedVisibility(
            visible = outroCardVisible,
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut() + slideOutHorizontally { it / 2 },
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = if (tv) 48.dp else 20.dp, bottom = if (overlay) 140.dp else 36.dp),
        ) {
            OutroTransitionCard(
                nextEpisodeTitle = ui.nextEpisodeTitle,
                autoNext = autoNextArmed,
                countdown = outroCountdown,
                remote = remote,
                onPlayNext = { outroDismissed = true; vm.nextEpisode() },
                onDismiss = { stayOnEpisode() },
            )
        }

        // Indicateur visuel de zapping tactile (Bord Gauche / Bord Droit)
        AnimatedVisibility(
            visible = zapIndicator != null && !zapIndicator!!.isNext,
            enter = fadeIn() + slideInHorizontally { -it / 2 },
            exit = fadeOut() + slideOutHorizontally { -it / 2 },
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp),
        ) {
            ZapIndicatorBadge(text = "⏮ Chaîne précédente : ${zapIndicator?.channelName.orEmpty()}")
        }

        AnimatedVisibility(
            visible = zapIndicator != null && zapIndicator!!.isNext,
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut() + slideOutHorizontally { it / 2 },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp),
        ) {
            ZapIndicatorBadge(text = "⏭ Chaîne suivante : ${zapIndicator?.channelName.orEmpty()}")
        }
    }

    // --- Menus
    if (inPip) return
    if (optionsVisible) {
        // Les actions de tous les jours d'abord ; le reste est rangé dans « Plus d'options ».
        val actions = buildList {
            if (ui.isLive) {
                val ts = ui.timeshift
                when {
                    ts == null -> add(ActionItem("Mettre le direct en pause", Icons.Default.Pause) { vm.toggleLivePause(); showOverlay() })
                    ts.paused -> add(ActionItem("Reprendre (en différé)", Icons.Default.PlayArrow) { vm.toggleLivePause(); showOverlay() })
                    else -> add(ActionItem("Pause", Icons.Default.Pause) { vm.toggleLivePause(); showOverlay() })
                }
                if (ts != null) add(ActionItem("Revenir au direct", Icons.Default.LiveTv) { vm.backToLive(); showOverlay() })
                if (vm.canCatchupNow()) add(ActionItem("Revoir depuis le début", Icons.Default.History) {
                    val ch = ui.channel; val p = ui.now
                    if (ch != null && p != null && p.id > 0) onCatchup(ch.uid, p.id)
                })
                ui.previous?.let { prev ->
                    add(ActionItem("Chaîne précédente", Icons.Default.SwapHoriz, detail = prev.name) { vm.playPrevious(); showOverlay() })
                }
                add(ActionItem("Guide de la chaîne", Icons.Default.CalendarViewDay) { guideVisible = true })
                add(ActionItem("Liste des chaînes", Icons.AutoMirrored.Filled.List) { listVisible = true })
                add(ActionItem(if (isFav) "Retirer des favoris" else "Ajouter aux favoris", if (isFav) Icons.Default.Star else Icons.Default.StarBorder) { vm.toggleFavorite() })
                add(ActionItem("Enregistrer", Icons.Default.FiberManualRecord, tint = VyzoColors.Red) { vm.record() })
                if (isMultiView) {
                    add(ActionItem("Quitter le multi-écran", Icons.Default.Close) {
                        vm.exitMultiView()
                        optionsVisible = false
                    })
                    if (multiViewSlots.size < 4) {
                        add(ActionItem("Ajouter un écran", Icons.Default.Add) {
                            multiViewPickSlot = -1
                            listVisible = true
                            optionsVisible = false
                        })
                    }
                }
            } else if (ui.hasNextEpisode) {
                add(ActionItem("Épisode suivant", Icons.Default.SkipNext) { vm.nextEpisode() })
            }
            add(ActionItem("Piste audio", Icons.Default.Audiotrack) { trackType = TrackType.AUDIO })
            add(ActionItem("Sous-titres", Icons.Default.Subtitles) { trackType = TrackType.TEXT })
            if (windowControls != null) {
                add(ActionItem(if (fullscreen) "Quitter le plein écran" else "Plein écran", if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, detail = "F") {
                    windowControls.toggleFullscreen()
                })
                add(ActionItem("Mini-lecteur", Icons.Default.PictureInPictureAlt, detail = "P") { windowControls.openMiniPlayer() })
            }
            add(ActionItem("Plus d'options…", Icons.Default.Tune, detail = "Qualité, format d'image, cast" + if (ui.isLive && !isMultiView) ", multi-écran" else "") {
                moreOptionsVisible = true
            })
        }
        PlayerMenu(variant, ui.title, "Options de lecture", actions) { optionsVisible = false }
    }
    if (moreOptionsVisible) {
        val actions = buildList {
            add(ActionItem("Qualité vidéo", Icons.Default.HighQuality) { trackType = TrackType.VIDEO })
            add(ActionItem("Format d'image", Icons.Default.AspectRatio, detail = resizeModes[resizeIndex].label) {
                resizeIndex = (resizeIndex + 1) % resizeModes.size
                vm.message.value = "Format : ${resizeModes[resizeIndex].label}"
            })
            add(ActionItem("Caster sur un écran", Icons.Default.Cast) {
                castDialogVisible = true
            })
            if (ui.isLive && !isMultiView) {
                add(ActionItem("Multi-écran", Icons.Default.GridView, detail = "Plusieurs chaînes à la fois") {
                    vm.enterMultiView()
                })
            }
            if (c.player.capabilities.audioDelay) {
                add(ActionItem("Décalage audio", Icons.Default.GraphicEq, detail = audioDelayLabel(audioDelayMs)) {
                    audioDelayMenuVisible = true
                })
            }
            add(ActionItem(
                "Minuteur de veille",
                Icons.Default.Timer,
                detail = if (sleepEndsAt != null) "Arrêt dans ${c.sleepTimer.remainingMinutes()} min" else "Désactivé",
            ) { sleepMenuVisible = true })
            add(ActionItem(
                label = if (statsVisible) "Masquer les informations du flux" else "Informations du flux",
                icon = Icons.Default.Info,
                detail = streamInfo?.resolutionLabel ?: "",
            ) {
                statsVisible = !statsVisible
            })
        }
        PlayerMenu(variant, ui.title, "Plus d'options", actions) { moreOptionsVisible = false }
    }
    if (audioDelayMenuVisible) {
        PlayerPickerMenu(
            variant, "Décalage audio", AUDIO_DELAY_CHOICES.map(::audioDelayLabel), AUDIO_DELAY_CHOICES.indexOf(audioDelayMs),
            onPick = { vm.setAudioDelay(AUDIO_DELAY_CHOICES[it]) },
            onDismiss = { audioDelayMenuVisible = false },
        )
    }
    if (sleepMenuVisible) {
        PlayerPickerMenu(
            variant, "Minuteur de veille", listOf("Désactivé") + SLEEP_TIMER_MINUTES.map { "$it min" }, if (sleepEndsAt == null) 0 else -1,
            onPick = { index ->
                if (index == 0) {
                    c.sleepTimer.cancel()
                    vm.message.value = "Minuteur désactivé"
                } else {
                    c.sleepTimer.start(SLEEP_TIMER_MINUTES[index - 1])
                    vm.message.value = "Arrêt de la lecture dans ${SLEEP_TIMER_MINUTES[index - 1]} min"
                }
            },
            onDismiss = { sleepMenuVisible = false },
        )
    }
    slotForMenu?.let { slot ->
        val actions = buildList {
            add(ActionItem("Mettre en plein écran seul", Icons.Default.Fullscreen) {
                vm.expandSlotToFullScreen(slot.index)
                slotForMenu = null
            })
            add(ActionItem("Changer cette chaîne", Icons.Default.SwapHoriz) {
                multiViewPickSlot = slot.index
                listVisible = true
                slotForMenu = null
            })
            if (multiViewSlots.size < 4) {
                add(ActionItem("Ajouter un écran", Icons.Default.Add) {
                    multiViewPickSlot = -1
                    listVisible = true
                    slotForMenu = null
                })
            }
            if (multiViewSlots.size > 1) {
                add(ActionItem("Fermer cet écran", Icons.Default.Close, tint = VyzoColors.Red) {
                    vm.removeSlot(slot.index)
                    slotForMenu = null
                })
            }
            add(ActionItem("Quitter le multi-écran", Icons.Default.FullscreenExit) {
                vm.exitMultiView()
                slotForMenu = null
            })
        }
        PlayerMenu(
            variant, slot.channel.name,
            "Écran ${slot.index + 1} sur ${multiViewSlots.size} · " + (if (slot.index == activeAudioSlot) "Son actif" else "Son muet"),
            actions,
        ) { slotForMenu = null }
    }
    trackType?.let { type ->
        if (type == TrackType.TEXT) {
            val textTracks = remember { c.player.tracks(TrackType.TEXT) }
            val sel = if (c.player.textEnabled()) textTracks.firstOrNull { it.selected } else null
            SubtitlesMenuDialog(
                internalTracks = textTracks,
                internalSelected = sel,
                externalSubtitleName = externalSubtitleName,
                delayMs = subtitleDelayMs,
                onSelectInternal = { opt ->
                    if (opt != null) {
                        vm.clearExternalSubtitles()
                        c.player.select(opt)
                    } else {
                        c.player.disable(TrackType.TEXT)
                    }
                    vm.rememberTrack(TrackType.TEXT, opt)
                },
                onClearExternal = {
                    vm.clearExternalSubtitles()
                },
                onAdjustDelay = { delta ->
                    vm.adjustSubtitleDelay(delta)
                },
                onResetDelay = {
                    vm.resetSubtitleDelay()
                },
                onSearchOnline = {
                    trackType = null
                    subtitleSearchOpen = true
                },
                onLoadCustom = {
                    trackType = null
                    subtitleCustomOpen = true
                },
                onDismiss = { trackType = null },
            )
        } else {
            val options = remember(type) { c.player.tracks(type) }
            val title = when (type) {
                TrackType.AUDIO -> "Piste audio"
                TrackType.TEXT -> "Sous-titres"
                TrackType.VIDEO -> "Qualité vidéo"
            }
            val extra = when (type) {
                TrackType.TEXT -> Choice<TrackOption?>(null, "Désactivés")
                TrackType.VIDEO -> Choice<TrackOption?>(null, "Automatique")
                TrackType.AUDIO -> null
            }
            val choices = listOfNotNull(extra) + options.map { Choice<TrackOption?>(it, it.label) }
            val selected = when {
                type == TrackType.TEXT && !c.player.textEnabled() -> null
                type == TrackType.VIDEO && options.none { it.selected } -> null
                else -> options.firstOrNull { it.selected }
            }
            if (choices.isEmpty()) {
                LaunchedEffect(type) { vm.message.value = "Aucune piste disponible"; trackType = null }
            } else {
                val select: (TrackOption?) -> Unit = { opt ->
                    when {
                        opt != null -> c.player.select(opt)
                        type == TrackType.TEXT -> c.player.disable(TrackType.TEXT)
                        type == TrackType.VIDEO -> c.player.clearOverrides(TrackType.VIDEO)
                    }
                    vm.rememberTrack(type, opt)
                }
                if (variant != null) {
                    variant.choice(PlayerChoiceArgs(title, choices.map { it.label }, choices.indexOfFirst { it.value == selected }, { select(choices[it].value) }, { trackType = null }))
                } else {
                    ChoiceDialog(title = title, choices = choices, selected = selected, onSelect = select, onDismiss = { trackType = null })
                }
            }
        }
    }
    if (subtitleSearchOpen) {
        SubtitleSearchDialog(
            initialQuery = ui.title,
            season = vm.currentSeason,
            episode = vm.currentEpisodeNumber,
            subtitleService = vm.subtitleService,
            onApply = { title, content ->
                vm.loadExternalSubtitles(title, content)
            },
            onDismiss = { subtitleSearchOpen = false },
        )
    }
    if (subtitleCustomOpen) {
        SubtitleUrlOrFileDialog(
            subtitleService = vm.subtitleService,
            onApply = { title, content ->
                vm.loadExternalSubtitles(title, content)
            },
            onDismiss = { subtitleCustomOpen = false },
        )
    }
    if (castDialogVisible) {
        CastDialog(
            onSend = {
                vm.castToMyScreens()
                castDialogVisible = false
            },
            onDismiss = { castDialogVisible = false },
        )
    }
}

/** Une liste de choix, à l'habillage de la variante. [selected] vaut -1 quand aucune ligne n'est cochée. */
@Composable
private fun PlayerPickerMenu(variant: VariantPlayer?, title: String, labels: List<String>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    if (variant != null) {
        variant.choice(PlayerChoiceArgs(title, labels, selected, onPick, onDismiss))
    } else {
        ChoiceDialog(title = title, choices = labels.mapIndexed { i, label -> Choice(i, label) }, selected = selected, onSelect = onPick, onDismiss = onDismiss)
    }
}

@Composable
private fun PlayerMenu(variant: VariantPlayer?, title: String, subtitle: String, actions: List<ActionItem>, onDismiss: () -> Unit) {
    if (variant != null) variant.menu(PlayerMenuArgs(title, subtitle, actions, onDismiss))
    else ActionDialog(title = title, subtitle = subtitle, actions = actions, onDismiss = onDismiss)
}

@Composable
private fun ErrorPanel(message: String, explanation: String?, isLive: Boolean, onRetry: () -> Unit, onNext: () -> Unit, takeFocus: Boolean, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(message, takeFocus) {
        if (takeFocus) { delay(100); runCatching { focus.requestFocus() } }
    }
    Column(
        modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.78f)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.ErrorOutline, null, Modifier.size(40.dp), tint = VyzoColors.Red)
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        if (explanation != null) Text(explanation, color = VyzoColors.Amber, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text("Réessayer") }
            if (isLive) OutlinedButton(onClick = onNext) { Text("Chaîne suivante") }
        }
    }
}

@Composable
private fun LiveInfoBar(
    ui: PlayerUi,
    clock: Long,
    showNumbers: Boolean,
    tv: Boolean,
    desktop: Boolean,
    positionMs: Long,
    delayMs: Long,
    canPause: Boolean,
    onPrevious: () -> Unit,
    onTogglePause: () -> Unit,
    onGuide: () -> Unit,
    onLive: () -> Unit,
    /** Revoir l'émission depuis le début (replay) ; null quand ce n'est pas possible. */
    onRestart: (() -> Unit)? = null,
) {
    val ch = ui.channel
    val ts = ui.timeshift
    // Tactile : un bandeau resserré qui laisse voir l'image. Les infos du flux, le multi-écran et le cast sont déjà
    // dans la barre du haut ; il ne garde que les boutons du direct, à droite des infos.
    val compact = !tv
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.95f))))
            .padding(horizontal = if (tv) 48.dp else 16.dp, vertical = if (tv) 28.dp else 8.dp),
    ) {
        Spacer(Modifier.height(if (compact) 20.dp else 36.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ch != null) {
                Box(
                    Modifier.size(width = if (compact) 60.dp else 96.dp, height = if (compact) 40.dp else 64.dp)
                        .clip(RoundedCornerShape(if (compact) 6.dp else 8.dp)).background(Color.White.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    NetImage(ch.logo, Modifier.fillMaxSize().padding(if (compact) 4.dp else 6.dp), fallback = ch.name)
                }
                Spacer(Modifier.width(if (compact) 12.dp else 18.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showNumbers && (ch?.number ?: 0) > 0) {
                        Text(
                            "${ch!!.number}", style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = if (compact) 6.dp else 8.dp, vertical = 1.dp),
                        )
                        Spacer(Modifier.width(if (compact) 8.dp else 10.dp))
                    }
                    Text(
                        ui.title, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (ui.isCatchupMode) {
                        Badge("REPLAY", VyzoColors.Cyan)
                        Spacer(Modifier.width(10.dp))
                    } else if (ts != null) {
                        Badge(timeshiftLabel(ts, delayMs), if (ts.paused) VyzoColors.Amber else VyzoColors.Cyan)
                        Spacer(Modifier.width(12.dp))
                    } else {
                        Badge("DIRECT", VyzoColors.Red)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(Fmt.time(clock), style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f))
                }
                // Replay ou différé : la barre et le temps restant suivent ce qui est à l'écran, pas le direct.
                val p = if (ui.isCatchupMode) ui.catchupProgram ?: ui.now else ui.now
                val watched = when {
                    ui.isCatchupMode && ui.catchupProgram != null -> ui.catchupProgram.start + positionMs
                    ts != null -> clock - delayMs
                    else -> clock
                }
                if (p != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = if (compact) 2.dp else 6.dp)) {
                        Text(
                            p.title, style = if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                            color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        // Tactile : la pastille se range au bout du titre au lieu de prendre une ligne.
                        if (compact && onRestart != null) {
                            Spacer(Modifier.width(10.dp))
                            RestartButton(onRestart, compact = true)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = if (compact) 2.dp else 4.dp)) {
                        val small = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
                        Text(Fmt.range(p.start, p.stop), style = small, color = VyzoColors.TextDim)
                        Spacer(Modifier.width(12.dp))
                        ProgressLine(Fmt.progress(p.start, p.stop, watched), Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        val left = ((p.stop - watched) / 60_000).coerceAtLeast(0)
                        Text("encore $left min", style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim)
                    }
                    // Dans le bandeau, sous le titre : superposée à l'image, la pastille cachait le logo et le titre,
                    // et changeait de place à l'apparition du bandeau.
                    if (!compact && onRestart != null) RestartButton(onRestart, compact = false)
                    if (tv && p.description.isNotBlank()) {
                        Text(p.description, style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                } else {
                    Text("Pas d'information de programme", style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim, modifier = Modifier.padding(top = if (compact) 2.dp else 6.dp))
                }
                ui.next?.let { n ->
                    Text(
                        "À suivre · ${Fmt.time(n.start)}  ${n.title}", style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = if (compact) 2.dp else 6.dp),
                    )
                }
            }
            if (compact) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onPrevious, enabled = ui.previous != null) {
                    Icon(Icons.Default.SwapHoriz, "Chaîne précédente", tint = if (ui.previous != null) Color.White else Color.White.copy(alpha = 0.3f))
                }
                if (canPause || ts != null) {
                    IconButton(onClick = onTogglePause) {
                        Icon(
                            if (ts == null || !ts.paused) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (ts?.paused == true) "Reprendre" else "Mettre en pause", tint = Color.White, modifier = Modifier.size(32.dp),
                        )
                    }
                }
                IconButton(onClick = onGuide) { Icon(Icons.Default.CalendarViewDay, "Guide de la chaîne", tint = Color.White) }
                if (ts != null || ui.isCatchupMode) {
                    OutlinedButton(onClick = onLive, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        Text("🔴 Direct", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        if (tv) {
            Text(
                when {
                    desktop && (ts != null || ui.isCatchupMode) -> "Espace : lecture/pause   ◀ ▶ : reculer/avancer   Échap : revenir au direct   ▲▼ : zapper"
                    desktop -> "Entrée : chaînes   ▲▼ : zapper   ▶ : guide   Espace : pause du direct   0 : chaîne précédente   F : plein écran   P : mini-lecteur"
                    ts != null || ui.isCatchupMode -> "▶❚❚ : lecture/pause   ◀◀ ▶▶ : reculer/avancer   ■ ou Menu : revenir au direct   ▲▼ : zapper"
                    else -> "OK : chaînes   ▲▼ : zapper   ▶ : guide   ▶❚❚ : pause du direct   0 : chaîne précédente   Menu : options"
                },
                style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

/** Revoir l'émission en cours depuis son début (replay). */
@Composable
private fun RestartButton(onClick: () -> Unit, compact: Boolean) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, VyzoColors.Cyan.copy(alpha = 0.6f)),
        contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 0.dp else 4.dp),
        modifier = if (compact) Modifier.height(30.dp) else Modifier.padding(top = 8.dp),
    ) {
        Icon(Icons.Default.History, contentDescription = null, tint = VyzoColors.Cyan, modifier = Modifier.size(if (compact) 16.dp else 18.dp))
        Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
        Text("Revoir depuis le début", style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
    }
}

/** La barre de la TV, à laquelle le doigt ou la souris ajoute le geste : on la touche ou on la fait glisser pour se déplacer dans la vidéo. */
@Composable
private fun TouchProgressLine(progress: Float, onChange: (Float) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    Box(
        modifier.height(40.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics {
                contentDescription = "Position de lecture"
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                setProgress { change(it); done(); true }
            }
            .pointerInput(Unit) {
                fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    change(at(down.position.x))
                    horizontalDrag(down.id) { change(at(it.position.x)); it.consume() }
                    done()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        ProgressLine(progress, Modifier.fillMaxWidth().height(4.dp))
    }
}

@Composable
private fun VodControls(
    ui: PlayerUi,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    tv: Boolean,
    desktop: Boolean,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onBack10: () -> Unit,
    onFwd10: () -> Unit,
    onNext: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.95f))))
            .padding(horizontal = if (tv) 48.dp else 20.dp, vertical = if (tv) 28.dp else 12.dp),
    ) {
        Spacer(Modifier.height(36.dp))
        Text(ui.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (ui.subtitle.isNotBlank()) Text(ui.subtitle, style = MaterialTheme.typography.bodyMedium, color = VyzoColors.TextDim, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            var dragging by remember { mutableStateOf<Float?>(null) }
            // Pendant un glissement, on affiche l'endroit visé : la barre n'a pas de curseur pour le montrer.
            Text(Fmt.duration(dragging?.let { (it * duration).toLong() } ?: position), style = MaterialTheme.typography.labelLarge, color = Color.White)
            Spacer(Modifier.width(12.dp))
            if ((tv && !desktop) || duration <= 0) {
                ProgressLine(if (duration > 0) position.toFloat() / duration else 0f, Modifier.weight(1f).height(4.dp))
            } else {
                TouchProgressLine(
                    progress = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f),
                    onChange = { dragging = it },
                    onDone = { dragging?.let { onSeek((it * duration).toLong()) }; dragging = null },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(if (duration > 0) Fmt.duration(duration) else "--:--", style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
        if (!tv || desktop) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack10, modifier = noFocus) { Icon(Icons.Default.Replay10, "Reculer", tint = Color.White, modifier = Modifier.size(32.dp)) }
                Spacer(Modifier.width(24.dp))
                IconButton(onClick = onTogglePlay, modifier = noFocus.size(56.dp)) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Lecture", tint = Color.White, modifier = Modifier.size(44.dp))
                }
                Spacer(Modifier.width(24.dp))
                IconButton(onClick = onFwd10, modifier = noFocus) { Icon(Icons.Default.Forward10, "Avancer", tint = Color.White, modifier = Modifier.size(32.dp)) }
                if (onNext != null) {
                    Spacer(Modifier.width(24.dp))
                    IconButton(onClick = onNext, modifier = noFocus) { Icon(Icons.Default.SkipNext, "Épisode suivant", tint = Color.White, modifier = Modifier.size(32.dp)) }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White)
                Text(
                    "   OK : lecture/pause   ◀▶ : reculer/avancer (maintenir pour accélérer)   ▲ : options",
                    style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun PhoneTopBar(
    ui: PlayerUi,
    isFav: Boolean,
    onBack: () -> Unit,
    onList: () -> Unit,
    onOptions: () -> Unit,
    onFavorite: () -> Unit,
    statsVisible: Boolean = false,
    onToggleStats: () -> Unit = {},
    onCast: () -> Unit = {},
    isMultiView: Boolean = false,
    onToggleMultiView: (() -> Unit)? = null,
    fullscreen: Boolean? = null,
    onFullscreen: () -> Unit = {},
    onMiniPlayer: (() -> Unit)? = null,
    onPip: (() -> Unit)? = null,
    onAlwaysOnTop: (() -> Unit)? = null,
    isAlwaysOnTop: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = noFocus) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Color.White) }
        Column(Modifier.weight(1f)) {
            // Multi-écran : le nom de chaque écran est déjà affiché sur lui ; celui du 1er ferait doublon.
            Text(if (isMultiView) "Multi-écran" else ui.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ui.isLive && !isMultiView) ui.now?.let { Text(it.title, color = VyzoColors.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
        }
        if (ui.isLive) {
            if (!isMultiView) {
                IconButton(onClick = onFavorite, modifier = noFocus) { Icon(if (isFav) Icons.Default.Star else Icons.Default.StarBorder, "Favori", tint = if (isFav) VyzoColors.Amber else Color.White) }
            }
            IconButton(onClick = onList, modifier = noFocus) { Icon(Icons.AutoMirrored.Filled.List, "Chaînes", tint = Color.White) }
            if (onToggleMultiView != null) {
                IconButton(onClick = onToggleMultiView, modifier = noFocus) {
                    Icon(Icons.Default.GridView, "Multi-écran", tint = if (isMultiView) VyzoColors.Cyan else Color.White)
                }
            }
        }
        IconButton(onClick = onToggleStats, modifier = noFocus) {
            Icon(Icons.Default.Info, "Informations du flux", tint = if (statsVisible) VyzoColors.Cyan else Color.White)
        }
        IconButton(onClick = onCast, modifier = noFocus) {
            Icon(Icons.Default.Cast, "Caster", tint = Color.White)
        }
        if (onAlwaysOnTop != null) {
            IconButton(onClick = onAlwaysOnTop, modifier = noFocus) {
                Icon(
                    if (isAlwaysOnTop) Icons.Default.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (isAlwaysOnTop) "Désépingler du premier plan" else "Épingler au premier plan",
                    tint = if (isAlwaysOnTop) VyzoColors.Cyan else Color.White,
                )
            }
        }
        if (onPip != null) {
            IconButton(onClick = onPip, modifier = noFocus) {
                Icon(Icons.Default.PictureInPicture, "Incrustation (PiP)", tint = Color.White)
            }
        }
        if (onMiniPlayer != null) {
            IconButton(onClick = onMiniPlayer, modifier = noFocus) {
                Icon(Icons.Default.PictureInPictureAlt, "Mini-lecteur", tint = Color.White)
            }
        }
        if (fullscreen != null) IconButton(onClick = onFullscreen, modifier = noFocus) {
            Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Plein écran", tint = Color.White)
        }
        IconButton(onClick = onOptions, modifier = noFocus) { Icon(Icons.Default.Tune, "Options", tint = Color.White) }
    }
}

@Composable
private fun ChannelListPanel(
    title: String,
    channels: List<fr.yannickuhrig.vyzo.data.db.ChannelEntity>,
    nowMap: Map<String, fr.yannickuhrig.vyzo.data.db.ProgramEntity>,
    currentUid: String?,
    showNumbers: Boolean,
    tv: Boolean,
    onPick: (fr.yannickuhrig.vyzo.data.db.ChannelEntity) -> Unit,
    onClose: () -> Unit,
) {
    val state = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val idx = channels.indexOfFirst { it.uid == currentUid }.coerceAtLeast(0)
    LaunchedEffect(Unit) {
        state.scrollToItem((idx - 3).coerceAtLeast(0))
        delay(80)
        runCatching { focus.requestFocus() }
    }
    Column(
        Modifier.fillMaxHeight().width(if (tv) 420.dp else 320.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .padding(top = 16.dp, start = 10.dp, end = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp, bottom = 8.dp))
            if (!tv) IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Fermer") }
        }
        LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(channels, key = { _, ch -> ch.uid }) { i, ch ->
                ChannelRow(
                    ch = ch, program = nowMap[ch.epgKey], playing = ch.uid == currentUid, showNumber = showNumbers,
                    onClick = { onPick(ch) }, onLongClick = {},
                    modifier = if (i == idx) Modifier.focusRequester(focus) else Modifier,
                )
            }
        }
    }
}

fun timeshiftLabel(ts: TimeshiftState, delayMs: Long): String {
    val sec = delayMs / 1000
    val d = if (sec < 60) "$sec s" else "${sec / 60} min"
    return when {
        ts.paused && !ts.started -> "EN PAUSE · $d"
        ts.paused -> "EN PAUSE · −$d"
        else -> "DIFFÉRÉ · −$d"
    }
}

@Composable
private fun MiniGuidePanel(
    vm: PlayerViewModel,
    start: ChannelEntity,
    tv: Boolean,
    showNumbers: Boolean,
    onWatch: (ChannelEntity) -> Unit,
    onCatchup: (ChannelEntity, ProgramEntity) -> Unit,
    onClose: () -> Unit,
) {
    var ch by remember { mutableStateOf(start) }
    var programs by remember { mutableStateOf<List<ProgramEntity>?>(null) }
    var menu by remember { mutableStateOf<ProgramEntity?>(null) }
    val state = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val now = remember(programs) { System.currentTimeMillis() }
    LaunchedEffect(ch.uid) {
        programs = null
        programs = vm.guideFor(ch)
    }
    val list = programs.orEmpty()
    val nowIdx = list.indexOfFirst { it.start <= now && it.stop > now }.takeIf { it >= 0 }
        ?: list.indexOfFirst { it.start > now }.coerceAtLeast(0)
    LaunchedEffect(programs) {
        if (programs == null) return@LaunchedEffect
        if (list.isNotEmpty()) state.scrollToItem((nowIdx - 1).coerceAtLeast(0))
        delay(60)
        runCatching { focus.requestFocus() }
    }
    fun browse(delta: Int) {
        vm.neighbour(ch, delta)?.let { ch = it }
    }

    Column(
        Modifier.fillMaxHeight().width(if (tv) 460.dp else 340.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> { browse(-1); true }
                    Key.DirectionRight -> { browse(1); true }
                    Key.ChannelUp -> { browse(-1); true }
                    Key.ChannelDown -> { browse(1); true }
                    else -> false
                }
            }
            .padding(top = 16.dp, start = 12.dp, end = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { browse(-1) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Chaîne précédente dans la liste") }
            Box(Modifier.size(width = 54.dp, height = 36.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.05f)), contentAlignment = Alignment.Center) {
                NetImage(ch.logo, Modifier.fillMaxSize().padding(3.dp), fallback = ch.name)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (showNumbers && ch.number > 0) "${ch.number}  " else "") + ch.name,
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (ch.uid != start.uid) Text("OK sur un programme pour regarder", style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim, maxLines = 1)
            }
            IconButton(onClick = { browse(1) }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Chaîne suivante dans la liste") }
        }
        if (tv) Text("◀ ▶ : autres chaînes   OK : regarder, replay, rappel, enregistrer", style = MaterialTheme.typography.labelSmall, color = VyzoColors.TextDim, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onClose) { Text("Fermer") }
        }
        when {
            programs == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            list.isEmpty() -> {
                Text("Pas de programme pour cette chaîne.", color = VyzoColors.TextDim, modifier = Modifier.padding(12.dp))
                // Garder le focus dans le panneau pour pouvoir changer de chaîne.
                Tile(onClick = { onWatch(ch) }, modifier = Modifier.fillMaxWidth().focusRequester(focus), focusScale = 1f) {
                    Text("Regarder ${ch.name}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(12.dp))
                }
            }
            else -> LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(list, key = { _, p -> p.id }) { i, p ->
                    val live = p.start <= now && p.stop > now
                    val replay = vm.canCatchup(ch, p)
                    Tile(
                        onClick = { menu = p },
                        selected = live,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).then(if (i == nowIdx) Modifier.focusRequester(focus) else Modifier),
                        focusScale = 1.01f,
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.Top) {
                            Text(
                                Fmt.time(p.start), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(52.dp),
                                color = if (p.stop <= now) VyzoColors.TextDim else MaterialTheme.colorScheme.primary,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    p.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = if (p.stop <= now && !replay) VyzoColors.TextDim else Color.Unspecified,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                                    if (live) Badge("EN COURS", VyzoColors.Red)
                                    if (replay) Badge("REPLAY", VyzoColors.Cyan)
                                }
                                if (live) ProgressLine(Fmt.progress(p.start, p.stop, now), Modifier.fillMaxWidth().padding(top = 5.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    menu?.let { p ->
        ProgramMenu(
            ch = ch, p = p, onDismiss = { menu = null },
            onWatchLive = { menu = null; onWatch(ch) },
            onCatchup = { menu = null; onCatchup(ch, p) },
        )
    }
}

@Composable
private fun StreamInfoOverlay(
    info: StreamInfo?,
    stats: PlaybackStats?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.85f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(14.dp)
            .widthIn(min = 230.dp, max = 340.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = VyzoColors.Cyan,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Informations du flux",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Fermer",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (info == null || (info.width == 0 && info.height == 0 && info.videoCodec == null)) {
            Text(
                "Analyse du flux en cours…",
                style = MaterialTheme.typography.bodySmall,
                color = VyzoColors.TextDim,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StreamBadge(
                    label = info.resolutionLabel,
                    color = when (info.resolutionLabel) {
                        "4K UHD" -> VyzoColors.Amber
                        "1080p FHD" -> VyzoColors.Cyan
                        "720p HD" -> VyzoColors.Violet
                        else -> Color.White.copy(alpha = 0.8f)
                    },
                )
                if (info.frameRate > 0f) {
                    val fpsRounded = (info.frameRate * 10).roundToInt() / 10f
                    StreamBadge(
                        label = "$fpsRounded fps",
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            if (info.width > 0 && info.height > 0) {
                StreamInfoRow("Résolution", "${info.width} × ${info.height}")
            }
            info.videoCodec?.takeIf { it.isNotBlank() }?.let {
                StreamInfoRow("Codec vidéo", it)
            }
            info.audioCodec?.takeIf { it.isNotBlank() }?.let {
                StreamInfoRow("Codec audio", it)
            }
            if (info.audioChannels > 0) {
                val chanText = when (info.audioChannels) {
                    1 -> "Mono (1.0)"
                    2 -> "Stéréo (2.0)"
                    6 -> "5.1 Surround"
                    8 -> "7.1 Surround"
                    else -> "${info.audioChannels} canaux"
                }
                StreamInfoRow("Canaux audio", chanText)
            }
        }
        stats?.let { s ->
            if (s.bitrateBps > 0) StreamInfoRow("Débit reçu", "%.1f Mb/s".format(java.util.Locale.FRANCE, s.bitrateBps / 1_000_000.0))
            if (s.bufferedMs > 0) StreamInfoRow("Mémoire tampon", "%.1f s".format(java.util.Locale.FRANCE, s.bufferedMs / 1000.0))
            StreamInfoRow("Images perdues", s.droppedFrames.toString())
            s.videoDecoder?.let { StreamInfoRow("Décodeur vidéo", it) }
            s.audioOutput?.let { StreamInfoRow("Sortie audio", it) }
            if (s.tunneled) StreamInfoRow("Tunnel", "Actif")
            if (s.displayHz > 0f) StreamInfoRow("Écran", "%.2f Hz".format(java.util.Locale.FRANCE, s.displayHz))
        }
    }
}

@Composable
private fun StreamBadge(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

@Composable
private fun StreamInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = VyzoColors.TextDim)
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun OutroTransitionCard(
    nextEpisodeTitle: String?,
    autoNext: Boolean,
    countdown: Int,
    remote: Boolean,
    onPlayNext: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.9f))
            .border(1.dp, VyzoColors.Cyan.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .width(216.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = null,
                tint = VyzoColors.Cyan,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (autoNext && countdown > 0) "Épisode suivant ($countdown s)" else "Épisode suivant",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        if (!nextEpisodeTitle.isNullOrBlank()) {
            Text(
                nextEpisodeTitle,
                style = MaterialTheme.typography.bodySmall,
                color = VyzoColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        // Sans cela, Material réserve 48 dp de haut autour de chaque bouton.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Text(if (remote) "Rester (Retour)" else "Rester", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                Button(
                    onClick = onPlayNext,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (remote) "Lire (OK)" else "Lire", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

private data class ZapFeedback(val isNext: Boolean, val channelName: String)

@Composable
private fun ZapIndicatorBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.85f))
            .border(1.dp, VyzoColors.Cyan.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MultiViewGrid(
    slots: List<MultiViewSlot>,
    activeAudioSlot: Int,
    onSelectAudioSlot: (Int) -> Unit,
    onSlotClick: (MultiViewSlot) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(Color.Black)) {
        when (slots.size) {
            1 -> {
                MultiViewCell(
                    slot = slots[0],
                    isActiveAudio = activeAudioSlot == 0,
                    onFocus = { onSelectAudioSlot(0) },
                    onClick = { onSlotClick(slots[0]) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            2 -> {
                Row(Modifier.fillMaxSize()) {
                    MultiViewCell(
                        slot = slots[0],
                        isActiveAudio = activeAudioSlot == 0,
                        onFocus = { onSelectAudioSlot(0) },
                        onClick = { onSlotClick(slots[0]) },
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(2.dp),
                    )
                    MultiViewCell(
                        slot = slots[1],
                        isActiveAudio = activeAudioSlot == 1,
                        onFocus = { onSelectAudioSlot(1) },
                        onClick = { onSlotClick(slots[1]) },
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(2.dp),
                    )
                }
            }
            3 -> {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        MultiViewCell(
                            slot = slots[0],
                            isActiveAudio = activeAudioSlot == 0,
                            onFocus = { onSelectAudioSlot(0) },
                            onClick = { onSlotClick(slots[0]) },
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(2.dp),
                        )
                        MultiViewCell(
                            slot = slots[1],
                            isActiveAudio = activeAudioSlot == 1,
                            onFocus = { onSelectAudioSlot(1) },
                            onClick = { onSlotClick(slots[1]) },
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(2.dp),
                        )
                    }
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        MultiViewCell(
                            slot = slots[2],
                            isActiveAudio = activeAudioSlot == 2,
                            onFocus = { onSelectAudioSlot(2) },
                            onClick = { onSlotClick(slots[2]) },
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(2.dp),
                        )
                        AddSlotCell(
                            onClick = onAddClick,
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(2.dp),
                        )
                    }
                }
            }
            else -> {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        MultiViewCell(
                            slot = slots[0],
                            isActiveAudio = activeAudioSlot == 0,
                            onFocus = { onSelectAudioSlot(0) },
                            onClick = { onSlotClick(slots[0]) },
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(2.dp),
                        )
                        MultiViewCell(
                            slot = slots[1],
                            isActiveAudio = activeAudioSlot == 1,
                            onFocus = { onSelectAudioSlot(1) },
                            onClick = { onSlotClick(slots[1]) },
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(2.dp),
                        )
                    }
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        MultiViewCell(
                            slot = slots[2],
                            isActiveAudio = activeAudioSlot == 2,
                            onFocus = { onSelectAudioSlot(2) },
                            onClick = { onSlotClick(slots[2]) },
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(2.dp),
                        )
                        MultiViewCell(
                            slot = slots[3],
                            isActiveAudio = activeAudioSlot == 3,
                            onFocus = { onSelectAudioSlot(3) },
                            onClick = { onSlotClick(slots[3]) },
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiViewCell(
    slot: MultiViewSlot,
    isActiveAudio: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isActiveAudio) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.2f)
    val borderWidth = if (isActiveAudio) 3.dp else 1.dp
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .border(borderWidth, borderColor, shape)
            .background(Color.Black)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .clickable(onClick = onClick)
            .focusable(),
    ) {
        VideoSurface(
            player = slot.player,
            modifier = Modifier.fillMaxSize(),
            resizeMode = ResizeMode.FIT,
        )
        val error by slot.player.error.collectAsState()
        val httpCode by slot.player.httpCode.collectAsState()
        error?.let { msg ->
            Text(
                if (httpCode != null) "Connexion refusée par le serveur ($httpCode) : la limite de connexions de l'abonnement est peut-être atteinte." else msg,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (slot.channel.number > 0) {
                    Text(
                        text = "${slot.channel.number}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.25f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = slot.channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isActiveAudio) {
                Badge("🔊 SON ACTIF", MaterialTheme.colorScheme.primary)
            } else {
                Badge("🔇 MUET", VyzoColors.TextDim)
            }
        }
    }
}

@Composable
private fun AddSlotCell(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.2f), shape)
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(8.dp))
            Text("Ajouter un 4ème écran", style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
    }
}
