package fr.yannickuhrig.vyzo.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.data.repo.StreamSpec
import fr.yannickuhrig.vyzo.diag.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private fun TrackType.media3(): Int = when (this) {
    TrackType.AUDIO -> C.TRACK_TYPE_AUDIO
    TrackType.VIDEO -> C.TRACK_TYPE_VIDEO
    TrackType.TEXT -> C.TRACK_TYPE_TEXT
}

/**
 * Décale le son en décalant l'horloge sur laquelle l'image se cale : montrer l'image plus tôt, c'est retarder le son.
 * Fonctionne aussi en passthrough, où les échantillons ne peuvent pas être touchés.
 */
private class DelayedAudioSink(sink: AudioSink, private val delayMs: () -> Long) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        return if (position == AudioSink.CURRENT_POSITION_NOT_SET) position else position + delayMs() * 1000
    }
}

/** Branche [AudioLeveler] sur la chaîne audio ; rien à faire en passthrough, où le son n'est pas décodé. */
private class LevelingAudioProcessor : BaseAudioProcessor() {
    private var leveler: AudioLeveler? = null
    private var levelerFormat: AudioProcessor.AudioFormat? = null
    private var samples = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun onFlush() {
        val format = inputAudioFormat
        if (format.sampleRate > 0 && format.channelCount > 0 && format != levelerFormat) {
            leveler = AudioLeveler(format.sampleRate, format.channelCount)
            levelerFormat = format
        }
    }

    override fun onReset() {
        leveler = null
        levelerFormat = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val count = bytes / 2
        if (samples.size < count) samples = ShortArray(count)
        inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer().get(samples, 0, count)
        inputBuffer.position(inputBuffer.limit())
        leveler?.process(samples, count)
        val output = replaceOutputBuffer(count * 2)
        output.asShortBuffer().put(samples, 0, count)
        output.position(0).limit(count * 2)
    }
}

/** Option « Zapping rapide » : en direct, l'image démarre dès 500 ms de flux, sans toucher au tampon de reprise après coupure. */
private class ZapLoadControl(minBufferMs: Int, maxBufferMs: Int, playMs: Int, rebufferMs: Int) : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    minBufferMs, maxBufferMs, playMs, rebufferMs,
    DEFAULT_TARGET_BUFFER_BYTES, DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS,
    DEFAULT_BACK_BUFFER_DURATION_MS, DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME,
) {
    @Volatile
    var fastStart = false

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        super.shouldStartPlayback(parameters) ||
            (fastStart && !parameters.rebuffering && parameters.bufferedDurationUs >= FAST_START_US)

    private companion object {
        const val FAST_START_US = 500_000L
    }
}

/** Les décodeurs Amlogic tombent en PLATFORM_FAILURE si on les garde pour une autre résolution (chaîne 1080p puis 720p). */
private class AmlogicSafeVideoRenderer(builder: Builder) : MediaCodecVideoRenderer(builder) {
    override fun canReuseCodec(codecInfo: MediaCodecInfo, oldFormat: Format, newFormat: Format): DecoderReuseEvaluation {
        val resolutionChanged = oldFormat.width != newFormat.width || oldFormat.height != newFormat.height
        if (!codecInfo.name.startsWith("c2.amlogic.") || !resolutionChanged) return super.canReuseCodec(codecInfo, oldFormat, newFormat)
        return DecoderReuseEvaluation(
            codecInfo.name, oldFormat, newFormat,
            DecoderReuseEvaluation.REUSE_RESULT_NO, DecoderReuseEvaluation.DISCARD_REASON_VIDEO_RESOLUTION_CHANGED,
        )
    }
}

/** Lecteur ExoPlayer unique partagé entre l'aperçu et le plein écran. */
class PlayerManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val isSecondary: Boolean = false,
) : VideoPlayer {
    private var exo: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var loadControl: ZapLoadControl? = null
    /** Session média : boutons lecture/pause de la fenêtre d'incrustation, touches média, Assistant. */
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())

    private val _current = MutableStateFlow<StreamSpec?>(null)
    override val current: StateFlow<StreamSpec?> = _current.asStateFlow()

    private val _tag = MutableStateFlow<String?>(null)
    override val tag: StateFlow<String?> = _tag.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    private val _status = MutableStateFlow(PlayerStatus())
    override val status: StateFlow<PlayerStatus> = _status.asStateFlow()

    private val _videoSize = MutableStateFlow<Pair<Int, Int>?>(null)
    override val videoSize: StateFlow<Pair<Int, Int>?> = _videoSize.asStateFlow()

    private val _streamInfo = MutableStateFlow<StreamInfo?>(null)
    override val streamInfo: StateFlow<StreamInfo?> = _streamInfo.asStateFlow()

    var onEnterPip: (() -> Boolean)? = null

    private val _subtitleDelayMs = MutableStateFlow(0L)
    override val subtitleDelayMs: StateFlow<Long> = _subtitleDelayMs.asStateFlow()

    override fun adjustSubtitleDelay(deltaMs: Long) {
        _subtitleDelayMs.value += deltaMs
    }

    override fun resetSubtitleDelay() {
        _subtitleDelayMs.value = 0L
    }

    override fun enterPictureInPicture(): Boolean {
        return onEnterPip?.invoke() ?: false
    }

    override val capabilities = PlayerCapabilities(
        audioDelay = true,
        loudnessLeveling = true,
        passthrough = true,
        refreshRate = true,
        tunneling = true,
    )

    private val _audioDelayMs = MutableStateFlow(0L)
    override val audioDelayMs: StateFlow<Long> = _audioDelayMs.asStateFlow()

    override fun setAudioDelay(ms: Long) {
        _audioDelayMs.value = ms
        _current.value?.let { applyTunneling(it) }
    }

    private val receivedBytes = AtomicLong()
    private var statsBytes = 0L
    private var statsAt = 0L
    private var droppedFrames = 0L
    private var videoDecoder: String? = null
    private var audioOutput: String? = null
    private var tunneled = false

    private val transferListener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork) receivedBytes.addAndGet(bytesTransferred.toLong())
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            this@PlayerManager.droppedFrames += droppedFrames
        }

        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            videoDecoder = decoderName
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            audioOutput = if (Util.isEncodingLinearPcm(audioTrackConfig.encoding)) "Décodée (PCM)" else "Passthrough"
            tunneled = audioTrackConfig.tunneling
        }
    }

    override fun playbackStats(): PlaybackStats? {
        val p = exo ?: return null
        val now = android.os.SystemClock.elapsedRealtime()
        val bytes = receivedBytes.get()
        val elapsed = now - statsAt
        val bitrate = if (statsAt > 0 && elapsed > 0) (bytes - statsBytes) * 8_000 / elapsed else 0L
        statsAt = now
        statsBytes = bytes
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        return PlaybackStats(
            bitrateBps = bitrate,
            bufferedMs = p.totalBufferedDuration,
            droppedFrames = droppedFrames,
            videoDecoder = videoDecoder,
            audioOutput = audioOutput,
            tunneled = tunneled,
            displayHz = display?.refreshRate ?: 0f,
        )
    }

    private var volumeBeforeMute = 1f

    /** Code HTTP de la dernière erreur de lecture (403, 509… : souvent la limite de connexions de l'abonnement). */
    private val _httpCode = MutableStateFlow<Int?>(null)
    override val httpCode: StateFlow<Int?> = _httpCode.asStateFlow()

    /** Lecture d'un fichier encore en cours d'écriture (pause du direct). */
    private var growing: (() -> Boolean)? = null

    private var triedHls = false
    private var retries = 0
    private var pendingRetry: Runnable? = null

    private val streamClient: OkHttpClient by lazy {
        Http.client.newBuilder().readTimeout(20, TimeUnit.SECONDS).connectTimeout(12, TimeUnit.SECONDS).build()
    }

    val player: ExoPlayer
        get() = exo ?: create().also { exo = it }

    override val hasPlayer: Boolean get() = exo != null

    override val currentPosition: Long get() = exo?.currentPosition ?: 0

    override val duration: Long get() = exo?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0

    private fun publishStatus() {
        val p = exo
        _status.value = if (p == null) PlayerStatus() else PlayerStatus(
            isPlaying = p.isPlaying,
            state = when (p.playbackState) {
                Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                Player.STATE_READY -> PlaybackState.READY
                Player.STATE_ENDED -> PlaybackState.ENDED
                else -> PlaybackState.IDLE
            },
        )
    }

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _videoSize.value = videoSize.width to (videoSize.height / videoSize.pixelWidthHeightRatio.coerceAtLeast(0.1f)).toInt()
            }
            updateStreamInfo()
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateStreamInfo()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = publishStatus()

        override fun onPlaybackStateChanged(state: Int) {
            publishStatus()
            if (state == Player.STATE_READY) {
                retries = 0
                _error.value = null
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val spec = _current.value ?: return
            _httpCode.value = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
            DiagLog.error("Lecteur", "${error.errorCodeName} (HTTP ${_httpCode.value ?: "-"}) ${spec.url}", error)
            val unrecognized = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                generateSequence(error.cause) { it.cause }.any { it is UnrecognizedInputFormatException }
            when {
                unrecognized && !triedHls -> start(spec, 0, forceHls = true)
                error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    player.seekToDefaultPosition()
                    player.prepare()
                }
                spec.isLive && retries < 4 && isNetwork(error) -> {
                    retries++
                    _error.value = "Connexion perdue, nouvelle tentative ($retries/4)…"
                    val r = Runnable { start(spec, 0, forceHls = triedHls) }
                    pendingRetry = r
                    handler.postDelayed(r, 1500L * retries)
                }
                else -> _error.value = describe(error)
            }
        }
    }

    private fun isNetwork(e: PlaybackException) = e.errorCode in 2000..2999 ||
        e.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
        e.errorCode == PlaybackException.ERROR_CODE_TIMEOUT

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Le serveur a refusé le flux (code HTTP). Vérifie l'abonnement ou le nombre de connexions."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Impossible de joindre le serveur du flux."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "Format de flux non reconnu."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Codec non pris en charge par cet appareil. Essaie le décodeur logiciel dans les réglages."
        in 6000..6999 -> "Contenu protégé (DRM) illisible sur cet appareil."
        else -> "Lecture impossible : ${e.errorCodeName}"
    }

    private fun create(): ExoPlayer {
        val s = settings.state.value
        val extensionMode = when (s.decoderMode) {
            "SOFTWARE" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            "HARDWARE" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }
        _audioDelayMs.value = s.audioDelayMs.toLong()
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>,
            ) {
                super.buildVideoRenderers(
                    context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                    eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
                )
                val index = out.indexOfFirst { it is MediaCodecVideoRenderer }
                if (index < 0) return
                out[index] = AmlogicSafeVideoRenderer(
                    MediaCodecVideoRenderer.Builder(context)
                        .setCodecAdapterFactory(getCodecAdapterFactory())
                        .setMediaCodecSelector(mediaCodecSelector)
                        .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                        .setEnableDecoderFallback(enableDecoderFallback)
                        .setEventHandler(eventHandler)
                        .setEventListener(eventListener)
                        .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
                )
            }

            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                // Sans contexte, le lecteur s'en tient aux capacités fournies ici : du PCM, donc tout est décodé dans l'appareil.
                val builder = if (s.audioPassthrough) DefaultAudioSink.Builder(context)
                else DefaultAudioSink.Builder().setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                builder.setEnableFloatOutput(enableFloatOutput).setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                if (s.loudnessLeveling) builder.setAudioProcessors(arrayOf<AudioProcessor>(LevelingAudioProcessor()))
                return DelayedAudioSink(builder.build()) { _audioDelayMs.value }
            }
        }
            .setExtensionRendererMode(extensionMode)
            .setEnableDecoderFallback(true)
        val (minB, maxB, play, rebuffer) = when (s.bufferMode) {
            "SHORT" -> listOf(4_000, 20_000, 800, 1_500)
            "LONG" -> listOf(30_000, 120_000, 2_500, 5_000)
            else -> listOf(15_000, 50_000, 1_500, 3_000)
        }
        val load = ZapLoadControl(minB, maxB, play, rebuffer).also { loadControl = it }
        val selector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setPreferredAudioLanguage(s.audioLang.ifBlank { null })
                .setPreferredTextLanguage(s.subtitleLang.ifBlank { null })
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, s.subtitleLang.isBlank())
                .build()
        }
        trackSelector = selector
        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(load)
            .setTrackSelector(selector)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { p ->
                p.addListener(listener)
                p.addAnalyticsListener(analyticsListener)
                if (!isSecondary) {
                    session = runCatching { MediaSession.Builder(context, p).setId("vyzo").build() }.getOrNull()
                }
            }
    }

    override fun recreate() {
        val spec = _current.value
        val pos = exo?.currentPosition ?: 0
        session?.release()
        session = null
        exo?.release()
        exo = null
        publishStatus()
        if (spec != null) start(spec, if (spec.isLive) 0 else pos, triedHls)
    }

    override fun play(spec: StreamSpec, startPositionMs: Long, tag: String?) {
        retries = 0
        growing = null
        _tag.value = tag
        start(spec, startPositionMs, forceHls = false)
    }

    /** Lit un fichier qui se remplit encore (pause du direct). */
    override fun playGrowingFile(file: File, isGrowing: () -> Boolean, tag: String?) {
        retries = 0
        growing = isGrowing
        _tag.value = tag
        start(StreamSpec(url = android.net.Uri.fromFile(file).toString(), userAgent = "", isLive = false), 0, forceHls = false)
    }

    /** Coupe la connexion au flux en gardant la dernière image à l'écran (pause du direct). */
    override fun releaseConnection() {
        pendingRetry?.let { handler.removeCallbacks(it) }
        pendingRetry = null
        exo?.pause()
        exo?.stop()
    }

    private fun start(spec: StreamSpec, startPositionMs: Long, forceHls: Boolean) {
        pendingRetry?.let { handler.removeCallbacks(it) }
        pendingRetry = null
        triedHls = forceHls
        _current.value = spec
        _streamInfo.value = null
        if (retries == 0) {
            _error.value = null
            _httpCode.value = null
        }
        DiagLog.log("Lecteur", "lecture ${if (spec.isLive) "direct" else "VOD"} ${spec.url}")
        val p = player
        droppedFrames = 0
        loadControl?.fastStart = settings.state.value.fastZap && spec.isLive
        applyTunneling(spec)
        p.setMediaSource(buildSource(spec, forceHls), startPositionMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = true
    }

    /** Tunnel pour les films et séries seulement ; le décalage audio ne peut pas s'y appliquer, l'horloge étant dans le décodeur. */
    private fun applyTunneling(spec: StreamSpec) {
        val selector = trackSelector ?: return
        val s = settings.state.value
        val wanted = s.tunnelingVod && !spec.isLive && growing == null && !isSecondary &&
            s.decoderMode != "SOFTWARE" && _audioDelayMs.value == 0L
        if (selector.parameters.tunnelingEnabled != wanted) {
            selector.parameters = selector.buildUponParameters().setTunnelingEnabled(wanted).build()
        }
    }

    override fun stop() {
        pendingRetry?.let { handler.removeCallbacks(it) }
        pendingRetry = null
        exo?.stop()
        exo?.clearMediaItems()
        _current.value = null
        _tag.value = null
        _error.value = null
        _httpCode.value = null
        _streamInfo.value = null
        growing = null
        publishStatus()
    }

    override fun pause() {
        exo?.pause()
    }

    override fun resume() {
        exo?.play()
    }

    override fun seekTo(positionMs: Long) {
        exo?.seekTo(positionMs)
    }

    override fun toggleMute(): Boolean {
        val p = player
        return if (p.volume > 0f) {
            volumeBeforeMute = p.volume
            p.volume = 0f
            true
        } else {
            p.volume = volumeBeforeMute.takeIf { it > 0f } ?: 1f
            false
        }
    }

    override fun setMuted(muted: Boolean) {
        val p = exo ?: return
        if (muted) {
            if (p.volume > 0f) volumeBeforeMute = p.volume
            p.volume = 0f
        } else {
            p.volume = volumeBeforeMute.takeIf { it > 0f } ?: 1f
        }
    }

    override fun release() {
        stop()
        session?.release()
        session = null
        exo?.release()
        exo = null
        publishStatus()
    }

    override fun adjustVolume(deltaPercent: Int): Int {
        val p = player
        p.volume = (p.volume + deltaPercent / 100f).coerceIn(0f, 1f)
        return (p.volume * 100).toInt()
    }

    @Composable
    override fun Surface(modifier: Modifier, resizeMode: ResizeMode) {
        val mode = when (resizeMode) {
            ResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            ResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            ResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        val p = player
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    this.player = p
                    this.resizeMode = mode
                }
            },
            update = {
                if (it.player !== p) it.player = p
                it.resizeMode = mode
            },
            onRelease = { it.player = null },
            modifier = modifier,
        )
    }

    override fun retry() {
        val spec = _current.value ?: return
        if (growing != null) {
            // Différé : on reprend là où la lecture s'était arrêtée dans le fichier tampon.
            retries = 0
            start(spec, exo?.currentPosition ?: 0, forceHls = false)
        } else {
            play(spec, if (spec.isLive) 0 else exo?.currentPosition ?: 0, _tag.value)
        }
    }

    private fun buildSource(spec: StreamSpec, forceHls: Boolean): MediaSource {
        growing?.let { g ->
            val file = File(android.net.Uri.parse(spec.url).path.orEmpty())
            val ext = DefaultExtractorsFactory().setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            )
            return ProgressiveMediaSource.Factory({ GrowingFileDataSource(file, g) }, ext).createMediaSource(MediaItem.fromUri(spec.url))
        }
        val http = OkHttpDataSource.Factory(streamClient)
            .setUserAgent(spec.userAgent.ifBlank { Http.DEFAULT_UA })
            .setDefaultRequestProperties(spec.headers)
            .setTransferListener(transferListener)
        val dataSource = DefaultDataSource.Factory(context, http)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            )
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
            .setConstantBitrateSeekingEnabled(true)
        val url = spec.url
        val lower = url.lowercase(Locale.US).substringBefore('?')
        val mime = when {
            forceHls -> MimeTypes.APPLICATION_M3U8
            spec.mimeHint != null -> spec.mimeHint
            lower.endsWith(".m3u8") || lower.endsWith(".m3u") || "m3u8" in url.lowercase(Locale.US) -> MimeTypes.APPLICATION_M3U8
            lower.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        val item = MediaItem.Builder().setUri(url).apply {
            mime?.let { setMimeType(it) }
            drmConfig(spec)?.let { setDrmConfiguration(it) }
        }.build()
        val factory = DefaultMediaSourceFactory(dataSource, extractors)
        if (spec.drmType == "clearkey" && !spec.drmKey.startsWith("http")) {
            clearKeyJson(spec.drmKey)?.let { json ->
                val manager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .build(LocalMediaDrmCallback(json.toByteArray()))
                factory.setDrmSessionManagerProvider { manager }
            }
        }
        return factory.createMediaSource(item)
    }

    private fun drmConfig(spec: StreamSpec): MediaItem.DrmConfiguration? = when (spec.drmType) {
        "widevine" -> MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).apply {
            if (spec.drmKey.startsWith("http")) setLicenseUri(spec.drmKey.substringBefore('|'))
        }.build()
        "playready" -> MediaItem.DrmConfiguration.Builder(C.PLAYREADY_UUID).apply {
            if (spec.drmKey.startsWith("http")) setLicenseUri(spec.drmKey.substringBefore('|'))
        }.build()
        "clearkey" -> MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).apply {
            if (spec.drmKey.startsWith("http")) setLicenseUri(spec.drmKey)
        }.build()
        else -> null
    }

    /** "kid:key" (hex) ou JSON brut → réponse de licence ClearKey locale. */
    private fun clearKeyJson(raw: String): String? {
        val t = raw.trim()
        if (t.startsWith("{")) return t
        val pairs = t.split(',').mapNotNull { pair ->
            val kid = pair.substringBefore(':').trim()
            val key = pair.substringAfter(':', "").trim()
            if (kid.isEmpty() || key.isEmpty()) null else kid to key
        }
        if (pairs.isEmpty()) return null
        fun b64(hex: String): String {
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
        val keys = pairs.joinToString(",") { (k, v) -> "{\"kty\":\"oct\",\"k\":\"${b64(v)}\",\"kid\":\"${b64(k)}\"}" }
        return "{\"keys\":[$keys],\"type\":\"temporary\"}"
    }

    // ------------------------------------------------------------ Pistes

    override fun tracks(type: TrackType): List<TrackOption> {
        val p = exo ?: return emptyList()
        val out = ArrayList<TrackOption>()
        p.currentTracks.groups.filter { it.type == type.media3() && it.isSupported }.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                out += TrackOption(type, g, i, label(g.getTrackFormat(i), out.size + 1), g.isTrackSelected(i))
            }
        }
        return out
    }

    override fun textEnabled(): Boolean = exo?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == false

    override fun select(option: TrackOption) {
        val p = exo ?: return
        val g = option.ref as? Tracks.Group ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(option.type.media3(), false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, option.index))
            .build()
    }

    override fun disable(type: TrackType) {
        val p = exo ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type.media3(), true).build()
    }

    override fun clearOverrides(type: TrackType) {
        val p = exo ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverridesOfType(type.media3()).build()
    }

    private fun updateStreamInfo() {
        val p = exo ?: run {
            _streamInfo.value = null
            return
        }
        val currentTracks = p.currentTracks
        var videoFormat: Format? = null
        var audioFormat: Format? = null

        for (group in currentTracks.groups) {
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> {
                    for (i in 0 until group.length) {
                        if (group.isTrackSelected(i)) {
                            videoFormat = group.getTrackFormat(i)
                            break
                        } else if (videoFormat == null && group.isTrackSupported(i)) {
                            videoFormat = group.getTrackFormat(i)
                        }
                    }
                }
                C.TRACK_TYPE_AUDIO -> {
                    for (i in 0 until group.length) {
                        if (group.isTrackSelected(i)) {
                            audioFormat = group.getTrackFormat(i)
                            break
                        } else if (audioFormat == null && group.isTrackSupported(i)) {
                            audioFormat = group.getTrackFormat(i)
                        }
                    }
                }
            }
        }

        if (videoFormat == null && audioFormat == null) return

        val vs = p.videoSize
        val w = if (vs.width > 0) vs.width else (videoFormat?.width?.coerceAtLeast(0) ?: 0)
        val h = if (vs.height > 0) vs.height else (videoFormat?.height?.coerceAtLeast(0) ?: 0)
        val fps = videoFormat?.frameRate?.takeIf { it > 0f } ?: 0f

        val vCodec = cleanVideoCodec(videoFormat?.sampleMimeType, videoFormat?.codecs)
        val aCodec = cleanAudioCodec(audioFormat?.sampleMimeType, audioFormat?.codecs)
        val channels = audioFormat?.channelCount?.coerceAtLeast(0) ?: 0

        _streamInfo.value = StreamInfo(
            videoCodec = vCodec,
            width = w,
            height = h,
            frameRate = fps,
            audioCodec = aCodec,
            audioChannels = channels,
        )
    }

    private fun cleanVideoCodec(mime: String?, codecs: String?): String? {
        val m = mime?.lowercase(Locale.ROOT) ?: return codecs
        return when {
            m.contains("hevc") || m.contains("h265") -> "HEVC (H.265)"
            m.contains("avc") || m.contains("h264") -> "AVC (H.264)"
            m.contains("av01") || m.contains("av1") -> "AV1"
            m.contains("vp9") -> "VP9"
            m.contains("vp8") -> "VP8"
            m.contains("mpeg2") -> "MPEG-2"
            m.contains("mp4v") -> "MPEG-4"
            else -> mime.substringAfter("video/").uppercase(Locale.ROOT)
        }
    }

    private fun cleanAudioCodec(mime: String?, codecs: String?): String? {
        val m = mime?.lowercase(Locale.ROOT) ?: return codecs
        return when {
            m.contains("eac3") || (m.contains("ac3") && m.contains("e")) -> "E-AC3"
            m.contains("ac3") -> "AC3"
            m.contains("dts") -> "DTS"
            m.contains("mp4a") || m.contains("aac") -> "AAC"
            m.contains("mpeg-l2") -> "MP2"
            m == "audio/mpeg" -> "MP3"
            m.contains("flac") -> "FLAC"
            m.contains("opus") -> "Opus"
            m.contains("vorbis") -> "Vorbis"
            else -> mime.substringAfter("audio/").uppercase(Locale.ROOT)
        }
    }

    private fun label(f: Format, n: Int): String {
        val parts = ArrayList<String>()
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
            Locale.forLanguageTag(it).getDisplayLanguage(Locale.FRENCH).replaceFirstChar { c -> c.uppercase() }
        }
        f.label?.takeIf { it.isNotBlank() }?.let { parts += it }
        if (lang != null && parts.none { it.equals(lang, true) }) parts += lang
        if (f.channelCount > 0) parts += when (f.channelCount) {
            1 -> "mono"; 2 -> "stéréo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${f.channelCount} canaux"
        }
        f.sampleMimeType?.lowercase()?.let { m ->
            when {
                m.contains("ac3") && m.contains("e") -> "E-AC3"
                m.contains("ac3") -> "AC3"
                m.contains("dts") -> "DTS"
                m.contains("mp4a") || m.contains("aac") -> "AAC"
                m.contains("mpeg-l2") -> "MP2"
                m == "audio/mpeg" -> "MP3"
                else -> null
            }
        }?.let { parts += it }
        if (f.height > 0) parts += "${f.height}p"
        return parts.joinToString(" · ").ifBlank { "Piste $n" }
    }
}
