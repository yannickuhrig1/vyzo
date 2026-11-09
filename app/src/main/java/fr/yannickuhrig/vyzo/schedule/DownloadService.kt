package fr.yannickuhrig.vyzo.schedule

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fr.yannickuhrig.vyzo.MainActivity
import fr.yannickuhrig.vyzo.R
import fr.yannickuhrig.vyzo.VyzoApp
import fr.yannickuhrig.vyzo.data.db.DownloadEntity
import fr.yannickuhrig.vyzo.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Service au premier plan tant qu'un téléchargement est en attente ou en cours : sans lui, Android arrête Vyzo
 * peu après qu'on a quitté l'appli ou éteint l'écran. Les téléchargements eux-mêmes restent dans DownloadManager.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(emptyList())
        if (watching == null) {
            val c = (application as VyzoApp).container
            watching = scope.launch {
                c.downloads.observeAll()
                    .map { l -> l.filter { it.isDownloading || it.isQueued } }
                    // Une ligne par pour cent : la notification n'est pas redessinée à chaque bloc reçu.
                    .distinctUntilChanged { a, b -> a.map { it.id to (it.progress * 100).toInt() } == b.map { it.id to (it.progress * 100).toInt() } }
                    .collect { active ->
                        if (active.isEmpty()) stopIfIdle()
                        else runCatching { NotificationManagerCompat.from(this@DownloadService).notify(NOTIF_ID, buildNotification(active)) }
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(active: List<DownloadEntity>) {
        Notif.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(active), type)
    }

    private fun buildNotification(active: List<DownloadEntity>): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val current = active.firstOrNull { it.isDownloading } ?: active.firstOrNull()
        val b = NotificationCompat.Builder(this, Notif.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (active.size > 1) "${active.size} téléchargements" else "Téléchargement en cours")
            .setContentText(current?.let { listOf(it.title, it.subtitle).filter { s -> s.isNotBlank() }.joinToString(" · ") } ?: "Préparation…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (current != null) b.setProgress(100, (current.progress * 100).toInt(), current.totalBytes <= 0L)
        return b.build()
    }

    private fun stopIfIdle() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 4243

        /** Refusé par Android si l'appli est en arrière-plan (Android 12+) : les téléchargements continuent alors sans lui. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
                .onFailure { Log.w("VyzoDownloads", "service au premier plan refusé : ${it.message}") }
        }
    }
}
