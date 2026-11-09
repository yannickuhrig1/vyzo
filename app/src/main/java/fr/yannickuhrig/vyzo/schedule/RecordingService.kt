package fr.yannickuhrig.vyzo.schedule

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import fr.yannickuhrig.vyzo.MainActivity
import fr.yannickuhrig.vyzo.R
import fr.yannickuhrig.vyzo.VyzoApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Service au premier plan qui écrit les flux en direct dans des fichiers. */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val titles = ConcurrentHashMap<Long, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(AlarmReceiver.EXTRA_ID, 0) ?: 0
        goForeground()
        when (intent?.action) {
            ACTION_START -> if (id > 0 && jobs[id] == null) {
                jobs[id] = scope.launch { record(id) }
            }
            ACTION_STOP -> jobs[id]?.cancel()
        }
        if (jobs.isEmpty()) stopIfIdle()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        Notif.ensureChannels(this)
        val type = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun buildNotification(): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = titles.values.joinToString(", ").ifBlank { "Préparation…" }
        val b = NotificationCompat.Builder(this, Notif.CHANNEL_RECORDINGS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (titles.size > 1) "${titles.size} enregistrements en cours" else "Enregistrement en cours")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        titles.keys.firstOrNull()?.let { first ->
            val stop = PendingIntent.getService(
                this, first.toInt(),
                Intent(this, RecordingService::class.java).setAction(ACTION_STOP).putExtra(AlarmReceiver.EXTRA_ID, first),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(0, "Arrêter", stop)
        }
        return b.build()
    }

    private fun refreshNotification() {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification())
        }
    }

    private suspend fun record(id: Long) {
        val c = (application as VyzoApp).container
        try {
            RecordingRunner(c).record(id, scope) { title ->
                titles[id] = title
                refreshNotification()
            }
        } catch (e: CancellationException) {
            // Arrêt demandé : l'état a déjà été enregistré par RecordingRunner.
        } finally {
            withContext(NonCancellable) { finish(id) }
        }
    }

    private suspend fun finish(id: Long) = withContext(Dispatchers.Main) {
        jobs.remove(id)
        titles.remove(id)
        if (jobs.isEmpty()) stopIfIdle() else refreshNotification()
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
        const val ACTION_START = "fr.yannickuhrig.vyzo.REC_START"
        const val ACTION_STOP = "fr.yannickuhrig.vyzo.REC_STOP"
        private const val NOTIF_ID = 4242
    }
}
