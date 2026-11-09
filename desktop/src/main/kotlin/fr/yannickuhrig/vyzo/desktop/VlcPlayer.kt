package fr.yannickuhrig.vyzo.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.net.redactCredentials
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.data.repo.StreamSpec
import fr.yannickuhrig.vyzo.player.PlaybackState
import fr.yannickuhrig.vyzo.player.PlaybackStats
import fr.yannickuhrig.vyzo.player.PlayerCapabilities
import fr.yannickuhrig.vyzo.player.PlayerStatus
import fr.yannickuhrig.vyzo.player.ResizeMode
import fr.yannickuhrig.vyzo.player.StreamInfo
import fr.yannickuhrig.vyzo.player.TrackOption
import fr.yannickuhrig.vyzo.player.TrackType
import fr.yannickuhrig.vyzo.player.VideoPlayer
import fr.yannickuhrig.vyzo.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import uk.co.caprica.vlcj.media.callback.seekable.SeekableCallbackMedia
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lecteur de Vyzo PC : libVLC 3 (via vlcj) avec rendu dans Compose.
 *
 * VLC décode et écrit chaque image dans un tampon (RV32) ; on la copie dans une image Skia que Compose dessine.
 * Ainsi les bandeaux, menus et listes de Vyzo s'affichent par-dessus la vidéo, comme sur Android,
 * et la même image peut être montrée dans le mini-lecteur.
 */
class VlcPlayer(
    private val settings: SettingsStore,
    private val isSecondary: Boolean = false,
) : VideoPlayer {

    private val _current = MutableStateFlow<StreamSpec?>(null)
    override val current: StateFlow<StreamSpec?> = _current.asStateFlow()
    private val _tag = MutableStateFlow<String?>(null)
    override val tag: StateFlow<String?> = _tag.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()
    private val _status = MutableStateFlow(PlayerStatus())
    override val status: StateFlow<PlayerStatus> = _status.asStateFlow()

    /** VLC ne remonte pas le code HTTP d'un refus : l'explication « limite de connexions » reste générique sur PC. */
    override val httpCode: StateFlow<Int?> = MutableStateFlow(null)

    private val _videoSize = MutableStateFlow<Pair<Int, Int>?>(null)
    override val videoSize: StateFlow<Pair<Int, Int>?> = _videoSize.asStateFlow()

    private val _streamInfo = MutableStateFlow<StreamInfo?>(null)
    override val streamInfo: StateFlow<StreamInfo?> = _streamInfo.asStateFlow()

    var onToggleAlwaysOnTop: (() -> Boolean)? = null
    var onEnterPip: (() -> Boolean)? = null
    private val _isAlwaysOnTop = MutableStateFlow(false)
    override val isAlwaysOnTop: StateFlow<Boolean> = _isAlwaysOnTop.asStateFlow()

    private val _subtitleDelayMs = MutableStateFlow(0L)
    override val subtitleDelayMs: StateFlow<Long> = _subtitleDelayMs.asStateFlow()

    override fun adjustSubtitleDelay(deltaMs: Long) {
        val next = _subtitleDelayMs.value + deltaMs
        _subtitleDelayMs.value = next
        control.execute {
            runCatching {
                mp?.subpictures()?.setDelay(next * 1000L)
            }
        }
    }

    override val capabilities = PlayerCapabilities(audioDelay = true, loudnessLeveling = true)

    private val _audioDelayMs = MutableStateFlow(0L)
    override val audioDelayMs: StateFlow<Long> = _audioDelayMs.asStateFlow()

    override fun setAudioDelay(ms: Long) {
        _audioDelayMs.value = ms
        control.execute { runCatching { mp?.audio()?.setDelay(ms * 1000L) } }
    }

    @Volatile
    private var lastStats: PlaybackStats? = null

    /** VLC ne se lit que sur son fil : on renvoie la mesure précédente et on prépare la suivante. */
    override fun playbackStats(): PlaybackStats? {
        control.execute {
            lastStats = runCatching {
                mp?.media()?.info()?.statistics()?.let {
                    PlaybackStats(bitrateBps = (it.inputBitrate() * 8_000_000).toLong(), droppedFrames = it.picturesLost().toLong())
                }
            }.getOrNull()
        }
        return lastStats
    }

    override fun resetSubtitleDelay() {
        _subtitleDelayMs.value = 0L
        control.execute {
            runCatching {
                mp?.subpictures()?.setDelay(0L)
            }
        }
    }

    fun setAlwaysOnTopState(active: Boolean) {
        _isAlwaysOnTop.value = active
    }

    override fun toggleAlwaysOnTop(): Boolean {
        val next = onToggleAlwaysOnTop?.invoke() ?: false
        _isAlwaysOnTop.value = next
        return next
    }

    override fun enterPictureInPicture(): Boolean {
        return onEnterPip?.invoke() ?: toggleAlwaysOnTop()
    }

    /** Lecture d'un fichier qui se remplit encore (pause du direct) : fichier et « il grossit encore ». */
    @Volatile private var growing: Pair<File, () -> Boolean>? = null
    private var callbackMedia: Any? = null

    /** Toutes les commandes VLC passent par ce fil : l'interface ne se fige jamais (stop() peut prendre du temps). */
    private val control = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "vlc-control").apply { isDaemon = true } }

    private var factory: MediaPlayerFactory? = null
    private var mp: EmbeddedMediaPlayer? = null
    /** Lecteur libéré : les tâches différées (format d'image, nouvel essai) ne doivent plus le toucher ni le recréer. */
    @Volatile private var released = false
    private var retries = 0
    private var pendingRetry: ScheduledFuture<*>? = null
    private var watchdog: ScheduledFuture<*>? = null
    private var bufferingSince = 0L
    /** Dernière fois que la position a avancé : sert à repérer un direct figé. */
    @Volatile private var lastProgressAt = 0L
    @Volatile private var startedAt = 0L
    /** Nombre d'images affichées au lancement du flux, pour savoir si quelque chose a été montré depuis. */
    @Volatile private var framesAtStart = 0L
    private var desiredVolume = 100
    private var muted = false

    @Volatile private var lastTime = 0L
    @Volatile private var lastLength = 0L

    override val hasPlayer: Boolean get() = mp != null
    override val currentPosition: Long get() = lastTime
    override val duration: Long get() = if (_current.value?.isLive == true) 0 else lastLength

    // ------------------------------------------------------------ Images

    private val frameLock = Any()
    private var frameBytes = ByteArray(0)
    private var frameW = 0
    private var frameH = 0
    /** Rapport largeur/hauteur à l'affichage (pixels non carrés des flux SD pris en compte). */
    @Volatile private var displayAspect = 16f / 9f
    private var image: Image? = null
    private var imageVersion = -1L
    @Volatile private var frameVersion = 0L
    /** Lu pendant le dessin : chaque nouvelle image redessine la surface, sans recomposer l'écran. */
    private val frameTick = mutableLongStateOf(0L)

    private val bufferFormat = object : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            // Au-delà de 1080p, VLC réduit l'image : moins de mémoire à recopier, aucune différence visible dans une fenêtre.
            var w = sourceWidth
            var h = sourceHeight
            val scale = minOf(1f, 1920f / max(1, w), 1080f / max(1, h))
            if (scale < 1f) {
                w = (w * scale).roundToInt() and 1.inv()
                h = (h * scale).roundToInt() and 1.inv()
            }
            displayAspect = if (sourceHeight > 0) sourceWidth.toFloat() / sourceHeight else 16f / 9f
            synchronized(frameLock) {
                frameW = w
                frameH = h
                frameBytes = ByteArray(w * h * 4)
            }
            return RV32BufferFormat(w, h)
        }

        override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
    }

    private val render = object : RenderCallback {
        override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat) {
            val src = nativeBuffers.firstOrNull() ?: return
            synchronized(frameLock) {
                val n = minOf(frameBytes.size, src.capacity())
                src.position(0)
                src.get(frameBytes, 0, n)
                src.position(0)
            }
            frameVersion++
            frameTick.longValue = frameVersion
        }
    }

    /**
     * Pixels non carrés (SD 720×576 en 16/9…) : le rapport d'affichage vient de la piste vidéo,
     * connue seulement une fois le décodage commencé (d'où l'appel sur « playing » et « videoOutput »).
     */
    private fun updateAspect(p: MediaPlayer) {
        // Appelé aussi 1,5 s après le début de la lecture : le lecteur natif a pu être libéré entre-temps.
        if (p !== mp) return
        runCatching {
            val tracks = p.media().info().videoTracks()
            val selected = p.video().track()
            val t = tracks.firstOrNull { it.id() == selected } ?: tracks.firstOrNull() ?: return
            if (t.width() <= 0 || t.height() <= 0) return
            val sar = if (t.sampleAspectRatio() > 0 && t.sampleAspectRatioBase() > 0) t.sampleAspectRatio().toFloat() / t.sampleAspectRatioBase() else 1f
            Log.d("VyzoVlc", "piste vidéo ${t.width()}×${t.height()}, pixels ${t.sampleAspectRatio()}:${t.sampleAspectRatioBase()} (${tracks.size} piste(s))")
            val aspect = t.width() * sar / t.height()
            _videoSize.value = (t.width() * sar).roundToInt() to t.height()
            if (aspect in 0.3f..4f && kotlin.math.abs(aspect - displayAspect) > 0.01f) {
                displayAspect = aspect
                frameTick.longValue = ++frameVersion
            }
        }
    }

    private fun updateStreamInfo(p: MediaPlayer) {
        if (p !== mp) return
        runCatching {
            val info = p.media().info() ?: return@runCatching
            val videoTracks = info.videoTracks()
            val audioTracks = info.audioTracks()
            val selectedVideo = p.video().track()
            val vt = videoTracks.firstOrNull { it.id() == selectedVideo } ?: videoTracks.firstOrNull()
            val selectedAudio = p.audio().track()
            val at = audioTracks.firstOrNull { it.id() == selectedAudio } ?: audioTracks.firstOrNull()

            if (vt == null && at == null) return@runCatching

            val w = vt?.width() ?: 0
            val h = vt?.height() ?: 0
            val fps = if (vt != null && vt.frameRate() > 0) {
                val base = vt.frameRateBase()
                if (base > 0) vt.frameRate().toFloat() / base else vt.frameRate().toFloat()
            } else 0f

            val vCodec = vt?.let { t ->
                cleanVlcCodec(t.codecDescription(), t.codecName())
            }
            val aCodec = at?.let { t ->
                cleanVlcCodec(t.codecDescription(), t.codecName())
            }
            val channels = at?.channels() ?: 0

            _streamInfo.value = StreamInfo(
                videoCodec = vCodec,
                width = w,
                height = h,
                frameRate = fps,
                audioCodec = aCodec,
                audioChannels = channels,
            )
        }
    }

    private fun cleanVlcCodec(desc: String?, name: String?): String? {
        val d = desc?.trim()?.takeIf { it.isNotBlank() }
        if (d != null) return d
        val n = name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when (n.lowercase(Locale.ROOT)) {
            "h264" -> "H.264 / AVC"
            "hevc" -> "H.265 / HEVC"
            "mp4v" -> "MPEG-4"
            "mpgv", "mp2v" -> "MPEG-2"
            "a52" -> "AC3"
            "eac3" -> "E-AC3"
            "dts" -> "DTS"
            "mpga" -> "MP2/MP3"
            "mp4a" -> "AAC"
            else -> n.uppercase(Locale.ROOT)
        }
    }

    /** Image Skia de la dernière image décodée (recréée seulement quand une nouvelle image arrive). */
    private fun currentImage(): Image? {
        val v = frameVersion
        if (v == imageVersion && image != null) return image
        synchronized(frameLock) {
            if (frameW <= 0 || frameH <= 0 || frameBytes.isEmpty()) return image
            val info = ImageInfo(frameW, frameH, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            val next = Image.makeRaster(info, frameBytes, frameW * 4)
            image?.close()
            image = next
            imageVersion = v
        }
        return image
    }

    private fun clearFrame() {
        synchronized(frameLock) {
            image?.close()
            image = null
            imageVersion = -1
            frameW = 0
            frameH = 0
        }
        frameVersion++
        frameTick.longValue = frameVersion
    }

    private val paint = Paint()
    private val sampling = SamplingMode.CATMULL_ROM

    private fun DrawScope.drawFrame(resizeMode: ResizeMode) {
        frameTick.longValue // abonnement : redessiner à chaque nouvelle image
        val img = currentImage() ?: return
        val cw = size.width
        val ch = size.height
        if (cw <= 0 || ch <= 0) return
        val aspect = displayAspect.takeIf { it > 0f } ?: (img.width.toFloat() / img.height)
        val dst = when (resizeMode) {
            ResizeMode.FILL -> Rect.makeWH(cw, ch)
            ResizeMode.FIT, ResizeMode.ZOOM -> {
                val fitByWidth = (cw / ch < aspect) == (resizeMode == ResizeMode.FIT)
                val (w, h) = if (fitByWidth) cw to cw / aspect else ch * aspect to ch
                Rect.makeXYWH((cw - w) / 2f, (ch - h) / 2f, w, h)
            }
        }
        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            c.save()
            c.clipRect(Rect.makeWH(cw, ch))
            c.drawImageRect(img, Rect.makeWH(img.width.toFloat(), img.height.toFloat()), dst, sampling, paint, true)
            c.restore()
        }
    }

    @Composable
    override fun Surface(modifier: Modifier, resizeMode: ResizeMode) {
        Canvas(modifier.background(Color.Black)) { drawFrame(resizeMode) }
    }

    // ------------------------------------------------------------ VLC

    private fun vlcArgs(): List<String> {
        val s = settings.state.value
        val cache = when (s.bufferMode) {
            "SHORT" -> 800
            "LONG" -> 5000
            else -> 2000
        }
        return buildList {
            add("--intf=dummy")
            add("--no-video-title-show")
            add("--no-snapshot-preview")
            add("--no-osd")
            add("--no-sub-autodetect-file")
            add("--network-caching=$cache")
            add("--live-caching=$cache")
            add("--file-caching=1000")
            add("--http-reconnect")
            add("--avcodec-hw=" + if (s.decoderMode == "SOFTWARE") "none" else "any")
            if (s.loudnessLeveling) add("--audio-filter=normvol")
            if (isLinux) add("--no-xlib")
            add("--quiet")
        }
    }

    private fun ensurePlayer(): EmbeddedMediaPlayer? {
        mp?.let { return it }
        if (!Vlc.available) {
            _error.value = Vlc.missingMessage
            return null
        }
        _audioDelayMs.value = settings.state.value.audioDelayMs.toLong()
        return try {
            val f = MediaPlayerFactory(vlcArgs())
            val p = f.mediaPlayers().newEmbeddedMediaPlayer()
            p.videoSurface().set(f.videoSurfaces().newVideoSurface(bufferFormat, render, true))
            p.events().addMediaPlayerEventListener(events)
            factory = f
            mp = p
            p
        } catch (t: Throwable) {
            Log.e("VyzoVlc", "création du lecteur", t)
            _error.value = Vlc.missingMessage
            null
        }
    }

    private fun publish(playing: Boolean, state: PlaybackState) {
        _status.value = PlayerStatus(isPlaying = playing, state = state)
    }

    private val events = object : MediaPlayerEventAdapter() {
        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
            if (newCache < 100f) {
                if (_status.value.state != PlaybackState.BUFFERING) bufferingSince = System.currentTimeMillis()
                publish(false, PlaybackState.BUFFERING)
            } else {
                publish(mediaPlayer.status().isPlaying, PlaybackState.READY)
            }
        }

        override fun playing(mediaPlayer: MediaPlayer) {
            Log.i("VyzoVlc", "lecture")
            retries = 0
            _error.value = null
            lastProgressAt = System.currentTimeMillis()
            publish(true, PlaybackState.READY)
            control.execute {
                applyAudio(mediaPlayer)
                updateAspect(mediaPlayer)
                updateStreamInfo(mediaPlayer)
            }
            // Le format exact (pixels non carrés) n'est parfois connu qu'après les premières images.
            control.schedule({
                updateAspect(mediaPlayer)
                updateStreamInfo(mediaPlayer)
            }, 1500, TimeUnit.MILLISECONDS)
        }

        override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) {
            if (newCount > 0) control.execute {
                updateAspect(mediaPlayer)
                updateStreamInfo(mediaPlayer)
            }
        }

        override fun paused(mediaPlayer: MediaPlayer) = publish(false, PlaybackState.READY)

        override fun stopped(mediaPlayer: MediaPlayer) {
            // vlcj signale « stopped » quelques millisecondes après « finished » : garder ENDED, sinon l'écran ne voit
            // jamais la fin et l'épisode suivant n'est pas enchaîné. Un arrêt demandé par l'appli publie IDLE lui-même.
            if (_status.value.state != PlaybackState.ENDED) publish(false, PlaybackState.IDLE)
        }

        override fun finished(mediaPlayer: MediaPlayer) {
            Log.i("VyzoVlc", "fin du flux")
            // Un direct ne devrait jamais finir : le serveur a coupé, on rouvre le flux.
            val spec = _current.value
            if (spec != null && spec.isLive && retries < 4) {
                retries++
                bufferingSince = System.currentTimeMillis()
                publish(false, PlaybackState.BUFFERING)
                pendingRetry = control.schedule({ start(spec, 0) }, 800L, TimeUnit.MILLISECONDS)
            } else if (spec != null && !spec.isLive && frameVersion == framesAtStart && lastTime < 1_000) {
                // Fini sans avoir montré une seule image : le film n'a pas pu être lu.
                publish(false, PlaybackState.IDLE)
                _error.value = "Impossible de lire ce contenu : le serveur refuse la lecture ou le format n'est pas reconnu."
            } else {
                publish(false, PlaybackState.ENDED)
            }
        }

        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
            if (newTime != lastTime) lastProgressAt = System.currentTimeMillis()
            lastTime = newTime
            if (_status.value.state == PlaybackState.BUFFERING && mediaPlayer.status().isPlaying) publish(true, PlaybackState.READY)
        }

        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
            lastLength = newLength.coerceAtLeast(0)
        }

        override fun error(mediaPlayer: MediaPlayer) {
            Log.w("VyzoVlc", "erreur de lecture : ${_current.value?.url?.redactCredentials()}")
            val spec = _current.value ?: return
            if (spec.isLive && retries < 4) {
                retries++
                _error.value = "Connexion perdue, nouvelle tentative ($retries/4)…"
                pendingRetry = control.schedule({ start(spec, 0) }, 1500L * retries, TimeUnit.MILLISECONDS)
            } else {
                publish(false, PlaybackState.IDLE)
                _error.value = "Lecture impossible : le serveur ne répond pas, refuse le flux (abonnement, nombre de connexions) ou le format n'est pas reconnu."
            }
        }
    }

    /** Langue audio et sous-titres préférés, volume et coupure du son, une fois le flux ouvert. */
    private fun applyAudio(p: MediaPlayer) {
        runCatching {
            p.audio().setVolume(desiredVolume)
            p.audio().setMute(muted)
            // VLC remet le décalage à zéro à chaque nouveau média.
            p.audio().setDelay(_audioDelayMs.value * 1000L)
            val s = settings.state.value
            if (s.subtitleLang.isBlank()) p.subpictures().setTrack(-1)
        }
    }

    override fun play(spec: StreamSpec, startPositionMs: Long, tag: String?) {
        growing = null
        playInternal(spec, startPositionMs, tag)
    }

    override fun playGrowingFile(file: File, isGrowing: () -> Boolean, tag: String?) {
        growing = file to isGrowing
        playInternal(StreamSpec(url = file.toURI().toString(), userAgent = "", isLive = false), 0, tag)
    }

    /** Coupe la connexion au flux en gardant la dernière image à l'écran (pause du direct). */
    override fun releaseConnection() {
        publish(false, PlaybackState.IDLE)
        control.execute {
            pendingRetry?.cancel(false)
            pendingRetry = null
            runCatching { mp?.controls()?.stop() }
        }
    }

    private fun playInternal(spec: StreamSpec, startPositionMs: Long, tag: String?) {
        retries = 0
        _tag.value = tag
        _current.value = spec
        _error.value = null
        _streamInfo.value = null
        lastTime = startPositionMs.coerceAtLeast(0)
        lastLength = 0
        startedAt = System.currentTimeMillis()
        lastProgressAt = startedAt
        publish(false, PlaybackState.BUFFERING)
        bufferingSince = System.currentTimeMillis()
        control.execute { start(spec, startPositionMs) }
    }

    private fun mediaOptions(spec: StreamSpec, startPositionMs: Long): Array<String> {
        val s = settings.state.value
        return buildList {
            add(":http-user-agent=" + spec.userAgent.ifBlank { Http.DEFAULT_UA })
            spec.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let { add(":http-referrer=${it.value}") }
            if (s.audioLang.isNotBlank()) add(":audio-language=${s.audioLang}")
            if (s.subtitleLang.isNotBlank()) add(":sub-language=${s.subtitleLang}")
            if (startPositionMs > 0) add(":start-time=${startPositionMs / 1000.0}".replace(',', '.'))
            if (s.fastZap && spec.isLive && s.bufferMode != "LONG") add(":network-caching=500")
        }.toTypedArray()
    }

    private fun start(spec: StreamSpec, startPositionMs: Long) {
        pendingRetry?.cancel(false)
        pendingRetry = null
        if (released) return
        if (spec.drmType.isNotBlank()) {
            _error.value = "Contenu protégé (DRM) : il n'est pas lisible sur PC. Regarde cette chaîne sur la TV."
            publish(false, PlaybackState.IDLE)
            return
        }
        val p = ensurePlayer() ?: run { publish(false, PlaybackState.IDLE); return }
        if (_current.value != spec) return // une autre chaîne a été demandée entre-temps
        startedAt = System.currentTimeMillis()
        lastProgressAt = startedAt
        framesAtStart = frameVersion
        val g = growing
        val ok = if (g != null && spec.url == g.first.toURI().toString()) {
            // Référence gardée : les fonctions de rappel natives ne doivent pas être libérées pendant la lecture.
            GrowingFileMedia(g.first, g.second).also { callbackMedia = it }.let { p.media().play(it, *mediaOptions(spec, startPositionMs)) }
        } else {
            p.media().play(spec.url, *mediaOptions(spec, startPositionMs))
        }
        if (!ok) _error.value = "Adresse du flux invalide."
        startWatchdog()
    }

    /**
     * Direct bloqué (chargement interminable ou image figée sans que VLC signale d'erreur) : on rouvre le flux,
     * comme ExoPlayer le fait sur Android. Au-delà de 4 essais, on affiche l'erreur.
     */
    private fun startWatchdog() {
        if (watchdog != null) return
        watchdog = control.scheduleWithFixedDelay({
            val spec = _current.value ?: return@scheduleWithFixedDelay
            if (!spec.isLive) return@scheduleWithFixedDelay
            val now = System.currentTimeMillis()
            val state = _status.value.state
            val stuckBuffering = state == PlaybackState.BUFFERING && now - bufferingSince > 20_000
            val frozen = state == PlaybackState.READY && _status.value.isPlaying && now - lastProgressAt > 15_000 && now - startedAt > 15_000
            if ((stuckBuffering || frozen) && retries < 4) {
                retries++
                Log.w("VyzoVlc", "direct bloqué (${if (frozen) "image figée" else "chargement"}), nouvelle tentative $retries/4")
                bufferingSince = now
                _error.value = "Le flux ne répond plus, nouvelle tentative ($retries/4)…"
                start(spec, 0)
            } else if ((stuckBuffering || frozen) && retries >= 4 && _error.value?.startsWith("Lecture impossible") != true) {
                _error.value = "Lecture impossible : le flux ne répond plus. Réessaie ou choisis une autre chaîne."
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    override fun stop() {
        growing = null
        _videoSize.value = null
        _streamInfo.value = null
        _current.value = null
        _tag.value = null
        _error.value = null
        lastTime = 0
        lastLength = 0
        publish(false, PlaybackState.IDLE)
        clearFrame()
        control.execute {
            pendingRetry?.cancel(false)
            pendingRetry = null
            runCatching { mp?.controls()?.stop() }
        }
    }

    override fun retry() {
        val spec = _current.value ?: return
        // Différé : on reprend là où la lecture s'était arrêtée dans le fichier tampon.
        playInternal(spec, if (spec.isLive) 0 else lastTime, _tag.value)
    }

    override fun recreate() {
        val spec = _current.value
        val pos = lastTime
        val tag = _tag.value
        control.execute {
            runCatching { mp?.controls()?.stop() }
            runCatching { mp?.release() }
            runCatching { factory?.release() }
            mp = null
            factory = null
        }
        if (spec != null) playInternal(spec, if (spec.isLive) 0 else pos, tag)
    }

    override fun pause() {
        control.execute { runCatching { mp?.controls()?.setPause(true) } }
    }

    override fun resume() {
        control.execute {
            val p = mp ?: return@execute
            runCatching {
                if (_status.value.state == PlaybackState.ENDED || !p.status().isPlayable) {
                    _current.value?.let { start(it, 0) }
                } else {
                    p.controls().setPause(false)
                }
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        lastTime = positionMs
        control.execute { runCatching { mp?.controls()?.setTime(positionMs) } }
    }

    override fun toggleMute(): Boolean {
        muted = !muted
        control.execute { runCatching { mp?.audio()?.setMute(muted) } }
        return muted
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        control.execute { runCatching { mp?.audio()?.setMute(muted) } }
    }

    override fun adjustVolume(deltaPercent: Int): Int {
        desiredVolume = (desiredVolume + deltaPercent).coerceIn(0, 100)
        if (muted && deltaPercent > 0) muted = false
        val v = desiredVolume
        control.execute {
            runCatching {
                mp?.audio()?.setVolume(v)
                if (!muted) mp?.audio()?.setMute(false)
            }
        }
        return v
    }

    // ------------------------------------------------------------ Pistes

    private fun descriptions(type: TrackType): Pair<List<uk.co.caprica.vlcj.player.base.TrackDescription>, Int> {
        val p = mp ?: return emptyList<uk.co.caprica.vlcj.player.base.TrackDescription>() to -1
        return runCatching {
            when (type) {
                TrackType.AUDIO -> p.audio().trackDescriptions() to p.audio().track()
                TrackType.TEXT -> p.subpictures().trackDescriptions() to p.subpictures().track()
                TrackType.VIDEO -> p.video().trackDescriptions() to p.video().track()
            }
        }.getOrElse { emptyList<uk.co.caprica.vlcj.player.base.TrackDescription>() to -1 }
    }

    override fun tracks(type: TrackType): List<TrackOption> {
        val (list, selected) = descriptions(type)
        return list.filter { it.id() >= 0 }.mapIndexed { i, d ->
            TrackOption(type, d.id(), i, prettyTrack(d.description(), i + 1), d.id() == selected)
        }.let { if (type == TrackType.VIDEO && it.size < 2) emptyList() else it }
    }

    override fun textEnabled(): Boolean = runCatching { (mp?.subpictures()?.track() ?: -1) >= 0 }.getOrDefault(false)

    override fun select(option: TrackOption) {
        val id = option.ref as? Int ?: return
        control.execute {
            runCatching {
                when (option.type) {
                    TrackType.AUDIO -> mp?.audio()?.setTrack(id)
                    TrackType.TEXT -> mp?.subpictures()?.setTrack(id)
                    TrackType.VIDEO -> mp?.video()?.setTrack(id)
                }
            }
        }
    }

    override fun disable(type: TrackType) {
        if (type == TrackType.TEXT) control.execute { runCatching { mp?.subpictures()?.setTrack(-1) } }
    }

    /** Une seule piste vidéo par flux avec VLC : la qualité reste automatique. */
    override fun clearOverrides(type: TrackType) = Unit

    /** « Track 1 - [Français] » → « Français ». */
    private fun prettyTrack(raw: String?, n: Int): String {
        val t = raw.orEmpty().trim()
        val lang = Regex("\\[([^\\]]+)]").find(t)?.groupValues?.get(1)
        val base = t.replace(Regex("^(Track|Piste)\\s*\\d+\\s*-?\\s*", RegexOption.IGNORE_CASE), "").replace(Regex("\\[[^\\]]+]"), "").trim()
        val label = listOfNotNull(base.ifBlank { null }?.let { languageName(it) }, lang?.let { languageName(it) }).distinct().joinToString(" · ")
        return label.ifBlank { "Piste $n" }
    }

    /** Nom de langue anglais renvoyé par VLC (« French ») → nom français (« Français »). */
    private val englishLanguageNames: Map<String, String> by lazy {
        Locale.getISOLanguages().associate { iso ->
            val l = Locale.forLanguageTag(iso)
            l.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) to l.getDisplayLanguage(Locale.FRENCH).replaceFirstChar { it.uppercase() }
        }
    }

    private fun languageName(code: String): String {
        englishLanguageNames[code.trim().lowercase(Locale.ROOT)]?.let { return it }
        val c = code.trim().lowercase(Locale.ROOT)
        if (c.length !in 2..3) return code
        val name = Locale.forLanguageTag(c).getDisplayLanguage(Locale.FRENCH)
        return if (name.isBlank() || name.equals(c, true)) code else name.replaceFirstChar { it.uppercase() }
    }

    /** Arrêt de l'appli ou fermeture du lecteur. */
    override fun release() {
        released = true
        control.execute {
            pendingRetry?.cancel(false)
            watchdog?.cancel(false)
            runCatching { mp?.controls()?.stop() }
            runCatching { mp?.release() }
            runCatching { factory?.release() }
            mp = null
            factory = null
        }
        control.shutdown()
        runCatching { control.awaitTermination(3, TimeUnit.SECONDS) }
    }

    companion object {
        val isLinux = System.getProperty("os.name").lowercase(Locale.ROOT).contains("linux")
    }
}

/**
 * Fichier qui grossit encore (pause du direct) lu par VLC : en fin de fichier, on attend la suite tant que
 * l'enregistrement continue, au lieu de signaler la fin du flux.
 */
private class GrowingFileMedia(private val file: File, private val growing: () -> Boolean) : SeekableCallbackMedia() {
    private var raf: RandomAccessFile? = null

    /** Taille inconnue (le fichier grossit). */
    override fun onGetSize(): Long = -1L

    override fun onOpen(): Boolean = runCatching { raf = RandomAccessFile(file, "r") }.isSuccess

    override fun onRead(buffer: ByteArray, bufferSize: Int): Int {
        val r = raf ?: return -1
        var waited = 0L
        while (true) {
            val n = r.read(buffer, 0, bufferSize)
            if (n > 0) return n
            if (!growing()) return -1
            try {
                Thread.sleep(40)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
            waited += 40
            if (waited > 30_000) throw IOException("Le flux en pause ne reçoit plus de données")
        }
    }

    override fun onSeek(offset: Long): Boolean = runCatching { raf?.seek(offset) }.isSuccess

    override fun onClose() {
        runCatching { raf?.close() }
        raf = null
    }
}
