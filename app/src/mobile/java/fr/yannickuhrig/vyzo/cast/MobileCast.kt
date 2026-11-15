package fr.yannickuhrig.vyzo.cast

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import fr.yannickuhrig.vyzo.diag.DiagLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Google Cast sur téléphone : recherche des appareils (MediaRouter), session et lecture (SDK Cast, récepteur standard
 * de Google). Le flux passe par le relais du téléphone (voir CastRelay) : le téléphone garde le processeur et le Wi-Fi
 * éveillés tant qu'une session est ouverte, écran éteint compris.
 */
class MobileCast(private val app: Application) : CastSupport {

    override val available: Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) == ConnectionResult.SUCCESS

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    private val _connection = MutableStateFlow(CastConnection.DISCONNECTED)
    private val _deviceName = MutableStateFlow<String?>(null)
    private val _playback = MutableStateFlow(CastPlayback())
    private val _volume = MutableStateFlow(1f)
    override val devices: StateFlow<List<CastDevice>> = _devices
    override val connection: StateFlow<CastConnection> = _connection
    override val deviceName: StateFlow<String?> = _deviceName
    override val playback: StateFlow<CastPlayback> = _playback
    override val volume: StateFlow<Float> = _volume

    private val router by lazy { MediaRouter.getInstance(app) }
    private val selector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
    }
    private var castContext: CastContext? = null
    private var session: CastSession? = null
    private var pendingConnect: CompletableDeferred<Boolean>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    init {
        if (available) {
            CastContext.getSharedInstance(app, Executors.newSingleThreadExecutor())
                .addOnSuccessListener { ctx ->
                    castContext = ctx
                    ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
                    // Session reprise au redémarrage de l'appli (le SDK la reconnecte seul).
                    ctx.sessionManager.currentCastSession?.let { if (it.isConnected) attach(it) }
                }
                .addOnFailureListener { DiagLog.error("Cast", "initialisation du SDK Cast impossible", it) }
        }
    }

    // ------------------------------------------------------------ Recherche des appareils

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
    }

    private fun refreshRoutes() {
        _devices.value = router.routes
            .filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
            .map { CastDevice(it.id, it.name, it.description.orEmpty()) }
            .sortedBy { it.name.lowercase() }
    }

    override fun startDiscovery() {
        if (!available) return
        router.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refreshRoutes()
    }

    override fun stopDiscovery() {
        if (!available) return
        router.removeCallback(routerCallback)
    }

    // ------------------------------------------------------------ Session

    override suspend fun connect(device: CastDevice): Boolean {
        if (!available) return false
        if (castContext == null) withTimeoutOrNull(5_000) { while (castContext == null) kotlinx.coroutines.delay(100) }
        if (castContext == null) return false
        val current = session
        if (current != null && current.isConnected && current.castDevice?.friendlyName == device.name) return true
        val route = router.routes.firstOrNull { it.id == device.id } ?: return false
        val wait = CompletableDeferred<Boolean>()
        pendingConnect = wait
        _connection.value = CastConnection.CONNECTING
        _deviceName.value = device.name
        router.selectRoute(route)
        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { wait.await() } ?: false
        pendingConnect = null
        if (!ok && session?.isConnected != true) {
            _connection.value = CastConnection.DISCONNECTED
            _deviceName.value = null
            router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
        }
        return ok
    }

    override fun disconnect() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, sessionId: String) = attach(s)
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) = attach(s)
        override fun onSessionStartFailed(s: CastSession, error: Int) {
            DiagLog.log("Cast", "connexion refusée (code $error)")
            pendingConnect?.complete(false)
            detach()
        }
        override fun onSessionEnded(s: CastSession, error: Int) = detach()
        override fun onSessionSuspended(s: CastSession, reason: Int) = Unit
        override fun onSessionStarting(s: CastSession) {
            _connection.value = CastConnection.CONNECTING
        }
        override fun onSessionEnding(s: CastSession) = Unit
        override fun onSessionResuming(s: CastSession, sessionId: String) = Unit
        override fun onSessionResumeFailed(s: CastSession, error: Int) = detach()
    }

    private val castListener = object : com.google.android.gms.cast.Cast.Listener() {
        override fun onVolumeChanged() {
            session?.let { _volume.value = it.volume.toFloat() }
        }
    }

    private fun attach(s: CastSession) {
        session = s
        _connection.value = CastConnection.CONNECTED
        _deviceName.value = s.castDevice?.friendlyName
        _volume.value = s.volume.toFloat()
        s.addCastListener(castListener)
        s.remoteMediaClient?.let { client ->
            client.registerCallback(mediaCallback)
            client.addProgressListener(progressListener, 1_000)
            updatePlayback(client)
        }
        acquireLocks()
        pendingConnect?.complete(true)
    }

    private fun detach() {
        session?.let { s ->
            s.removeCastListener(castListener)
            s.remoteMediaClient?.unregisterCallback(mediaCallback)
            s.remoteMediaClient?.removeProgressListener(progressListener)
        }
        session = null
        _connection.value = CastConnection.DISCONNECTED
        _deviceName.value = null
        _playback.value = CastPlayback()
        releaseLocks()
    }

    // ------------------------------------------------------------ Lecture

    override suspend fun load(media: CastMedia): String? {
        val client = session?.remoteMediaClient ?: return "L'appareil Cast n'est plus connecté."
        val metadata = MediaMetadata(if (media.isLive) MediaMetadata.MEDIA_TYPE_GENERIC else MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, media.title)
            if (media.subtitle.isNotBlank()) putString(MediaMetadata.KEY_SUBTITLE, media.subtitle)
            media.imageUrl?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val info = MediaInfo.Builder(media.url)
            .setStreamType(if (media.isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(media.contentType)
            .setMetadata(metadata)
            .setCustomData(JSONObject().put("tag", media.tag))
            .apply {
                // Les flux HLS IPTV découpent presque toujours en MPEG-TS : le récepteur doit le savoir d'avance.
                if (media.contentType == CastFormat.HLS.contentType) {
                    setHlsSegmentFormat(HlsSegmentFormat.TS)
                    setHlsVideoSegmentFormat(HlsVideoSegmentFormat.MPEG2_TS)
                }
            }
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(media.startMs)
            .build()
        _playback.value = _playback.value.copy(state = CastPlayState.LOADING, tag = media.tag, isLive = media.isLive, positionMs = media.startMs)
        val error = suspendCancellableCoroutine { cont ->
            client.load(request).setResultCallback { result ->
                val status = result.status
                cont.resume(
                    if (status.isSuccess) null
                    else {
                        DiagLog.log("Cast", "chargement refusé : ${status.statusCode} ${status.statusMessage.orEmpty()}")
                        "L'appareil Cast n'a pas pu lire ce flux (format vidéo ou son non pris en charge)."
                    },
                )
            }
        }
        if (error != null) _playback.value = _playback.value.copy(state = CastPlayState.ERROR)
        return error
    }

    override fun play() {
        session?.remoteMediaClient?.play()
    }

    override fun pause() {
        session?.remoteMediaClient?.pause()
    }

    override fun seekTo(positionMs: Long) {
        session?.remoteMediaClient?.seek(
            com.google.android.gms.cast.MediaSeekOptions.Builder().setPosition(positionMs).build(),
        )
    }

    override fun setVolume(volume: Float) {
        runCatching { session?.volume = volume.toDouble().coerceIn(0.0, 1.0) }
        _volume.value = volume.coerceIn(0f, 1f)
    }

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            session?.remoteMediaClient?.let(::updatePlayback)
        }
    }

    private val progressListener = RemoteMediaClient.ProgressListener { _, _ ->
        session?.remoteMediaClient?.let(::updatePlayback)
    }

    private fun updatePlayback(client: RemoteMediaClient) {
        val status = client.mediaStatus
        val prev = _playback.value
        val tag = client.mediaInfo?.customData?.optString("tag")?.takeIf { it.isNotBlank() } ?: prev.tag
        val state = when (client.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> CastPlayState.PLAYING
            MediaStatus.PLAYER_STATE_PAUSED -> CastPlayState.PAUSED
            MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING -> CastPlayState.BUFFERING
            MediaStatus.PLAYER_STATE_IDLE -> when (client.idleReason) {
                MediaStatus.IDLE_REASON_FINISHED -> CastPlayState.ENDED
                MediaStatus.IDLE_REASON_ERROR -> CastPlayState.ERROR
                // Pas encore de statut après un chargement : on garde « chargement ».
                MediaStatus.IDLE_REASON_NONE -> if (prev.state == CastPlayState.LOADING) CastPlayState.LOADING else CastPlayState.IDLE
                else -> CastPlayState.IDLE
            }
            else -> prev.state
        }
        _playback.value = CastPlayback(
            state = state,
            positionMs = client.approximateStreamPosition.coerceAtLeast(0),
            durationMs = client.streamDuration.coerceAtLeast(0),
            isLive = client.isLiveStream || (status == null && prev.isLive),
            tag = tag,
        )
    }

    // ------------------------------------------------------------ Téléphone éveillé pour le relais

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock?.isHeld == true) return
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vyzo:cast-relay").apply { setReferenceCounted(false); acquire() }
        val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "vyzo:cast-relay").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
