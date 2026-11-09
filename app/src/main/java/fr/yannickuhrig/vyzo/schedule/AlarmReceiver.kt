package fr.yannickuhrig.vyzo.schedule

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import fr.yannickuhrig.vyzo.MainActivity
import fr.yannickuhrig.vyzo.R
import fr.yannickuhrig.vyzo.VyzoApp
import fr.yannickuhrig.vyzo.data.db.RecordingStatus
import fr.yannickuhrig.vyzo.data.db.ReminderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as VyzoApp
        val c = app.container
        val id = intent.getLongExtra(EXTRA_ID, 0)
        val pending = goAsync()
        c.scope.launch {
            try {
                when (intent.action) {
                    ACTION_REMINDER -> {
                        val r = c.db.reminders().get(id) ?: return@launch
                        val foreground = withContext(Dispatchers.Main) {
                            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                        }
                        if (foreground) {
                            c.events.emit(AppEvent.ReminderDue(r))
                        } else {
                            notifyReminder(context, r)
                            if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)) {
                                runCatching {
                                    context.startActivity(
                                        MainActivity.reminderIntent(context, r.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }
                        }
                    }
                    ACTION_RECORD -> {
                        if (!c.recordings.startNow(id, retryWithAlarm = false)) {
                            c.db.recordings().setStatus(id, RecordingStatus.FAILED, "Démarrage refusé par le système")
                        }
                    }
                    Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON", Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        c.reminders.rescheduleAll()
                        c.recordings.rescheduleAll()
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun notifyReminder(context: Context, r: ReminderEntity) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        Notif.ensureChannels(context)
        val watch = PendingIntent.getActivity(
            context, r.id.toInt(), MainActivity.playChannelIntent(context, r.channelUid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(r.start))
        val n = NotificationCompat.Builder(context, Notif.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(r.title)
            .setContentText("$time sur ${r.channelName}")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(watch)
            .addAction(0, "Regarder", watch)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_REMINDER_BASE + r.id.toInt(), n) }
    }

    companion object {
        const val ACTION_REMINDER = "fr.yannickuhrig.vyzo.REMINDER"
        const val ACTION_RECORD = "fr.yannickuhrig.vyzo.RECORD"
        const val EXTRA_ID = "id"
        private const val NOTIF_REMINDER_BASE = 10_000
    }
}
