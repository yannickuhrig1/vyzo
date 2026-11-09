package fr.yannickuhrig.vyzo

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import fr.yannickuhrig.vyzo.player.PlayerManager
import fr.yannickuhrig.vyzo.player.bestRefreshRate
import fr.yannickuhrig.vyzo.ui.AppRoot
import fr.yannickuhrig.vyzo.ui.LaunchRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val launchRequest = MutableStateFlow<LaunchRequest?>(null)

    private val container get() = (application as VyzoApp).container

    private val pipSupported by lazy {
        Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (savedInstanceState == null) handleIntent(intent)
        setContent { AppRoot(container, launchRequest, FlavorScreens.home, FlavorScreens.details, FlavorScreens.nav) }
        askNotificationsOnce()

        // Mise à jour au lancement si les données sont plus anciennes que l'intervalle choisi.
        lifecycleScope.launch {
            val s = container.settings.get()
            val stale = System.currentTimeMillis() - s.lastFullSync > s.refreshHours * 3_600_000L
            if (s.refreshHours > 0 && stale && container.db.playlists().all().isNotEmpty()) container.sync.launchAll()
        }

        // Android 12+ : la fenêtre d'incrustation s'ouvre d'elle-même en quittant l'appli pendant une vidéo.
        if (Build.VERSION.SDK_INT >= 31 && pipSupported) {
            lifecycleScope.launch {
                combine(
                    container.pip.screenActive,
                    container.settings.state.map { it.autoPip }.distinctUntilChanged(),
                    container.pip.aspect,
                ) { active, auto, _ -> active && auto }
                    .collect { enabled -> runCatching { setPictureInPictureParams(pipParams(enabled)) } }
            }
        }

        // Fréquence de l'écran : tant qu'un zapping n'a pas livré le format de la chaîne suivante, on garde la fréquence actuelle.
        lifecycleScope.launch {
            combine(
                container.settings.state.map { it.matchFrameRate }.distinctUntilChanged(),
                container.pip.screenActive,
                container.player.streamInfo,
            ) { wanted, active, info ->
                val fps = info?.frameRate ?: 0f
                when {
                    !wanted || !active -> 0f
                    fps > 0f -> fps
                    else -> null
                }
            }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { applyRefreshRate(it) }
        }

        (container.player as? PlayerManager)?.onEnterPip = {
            if (pipSupported && Build.VERSION.SDK_INT >= 26) {
                runCatching {
                    enterPictureInPictureMode(pipParams(autoEnter = false))
                }.getOrDefault(false)
            } else false
        }
    }

    /** Passe l'écran à la fréquence la mieux accordée à [fps], ou rend la main au système si [fps] vaut 0. */
    private fun applyRefreshRate(fps: Float) {
        val display = window.decorView.display ?: return
        val current = display.mode
        val modes = display.supportedModes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        val rate = bestRefreshRate(modes.map { it.refreshRate }, fps)
        val modeId = modes.firstOrNull { it.refreshRate == rate }?.modeId ?: 0
        if (window.attributes.preferredDisplayModeId != modeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = modeId }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_PLAY_CHANNEL)?.let { launchRequest.value = LaunchRequest.PlayChannel(it) }
        intent.getLongExtra(EXTRA_REMINDER, 0).takeIf { it > 0 }?.let { launchRequest.value = LaunchRequest.Reminder(it) }
        intent.getStringExtra(EXTRA_OPEN_SERIES)?.let { launchRequest.value = LaunchRequest.OpenSeries(it) }
    }

    /**
     * Android 13+ : sans cette autorisation, ni rappels ni alertes de nouvel épisode. Demandée une seule fois ;
     * un refus se rattrape dans les réglages d'Android.
     */
    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("vyzo-ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED_NOTIFICATIONS, false)) return
        prefs.edit().putBoolean(ASKED_NOTIFICATIONS, true).apply()
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    /** Une vidéo plein écran est en lecture et l'incrustation est activée dans les réglages. */
    private fun pipWanted(): Boolean {
        if (!pipSupported || !container.settings.state.value.autoPip || !container.pip.screenActive.value) return false
        val p = container.player as? PlayerManager ?: return false
        return p.hasPlayer && p.player.playWhenReady && p.current.value != null
    }

    @RequiresApi(26)
    private fun pipParams(autoEnter: Boolean): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(16, 9))
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setAutoEnterEnabled(autoEnter)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()

    /** Android 8 à 11 : on entre en incrustation quand l'utilisateur quitte l'appli (touche Accueil…). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in 26..30 && pipWanted()) {
            runCatching { enterPictureInPictureMode(pipParams(autoEnter = false)) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        container.pip.setInPip(isInPictureInPictureMode)
    }

    override fun onStop() {
        super.onStop()
        val inPip = if (Build.VERSION.SDK_INT >= 24) isInPictureInPictureMode else false
        container.pip.setInPip(inPip)
        if (!isChangingConfigurations && !inPip) {
            val p = container.player
            if (p.hasPlayer) {
                if (p.current.value?.isLive == true) p.stop() else p.pause()
            }
        }
    }

    companion object {
        const val EXTRA_PLAY_CHANNEL = "play_channel"
        const val EXTRA_REMINDER = "reminder_id"
        const val EXTRA_OPEN_SERIES = "open_series"
        private const val ASKED_NOTIFICATIONS = "asked_notifications"

        fun openSeriesIntent(context: Context, seriesUid: String): Intent =
            Intent(context, MainActivity::class.java)
                .setAction("fr.yannickuhrig.vyzo.SERIES.$seriesUid")
                .putExtra(EXTRA_OPEN_SERIES, seriesUid)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun playChannelIntent(context: Context, channelUid: String): Intent =
            Intent(context, MainActivity::class.java)
                .setAction("fr.yannickuhrig.vyzo.PLAY.$channelUid")
                .putExtra(EXTRA_PLAY_CHANNEL, channelUid)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun reminderIntent(context: Context, reminderId: Long): Intent =
            Intent(context, MainActivity::class.java)
                .setAction("fr.yannickuhrig.vyzo.REMINDER.$reminderId")
                .putExtra(EXTRA_REMINDER, reminderId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
