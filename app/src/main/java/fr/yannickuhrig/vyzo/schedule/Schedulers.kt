package fr.yannickuhrig.vyzo.schedule

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.ReminderEntity
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.util.Log
import java.io.File
import java.io.OutputStream

object Notif {
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_RECORDINGS = "recordings"
    const val CHANNEL_DOWNLOADS = "downloads"
    const val CHANNEL_EPISODES = "episodes"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Rappels de programmes", NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDINGS, "Enregistrements", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, "Téléchargements", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EPISODES, "Nouveaux épisodes", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }
}

internal fun setExactAlarm(context: Context, at: Long, pi: PendingIntent) {
    val am = context.getSystemService(AlarmManager::class.java)
    val exactAllowed = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    if (exactAllowed) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
}

internal fun alarmIntent(context: Context, action: String, id: Long): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        (action.hashCode() * 31 + id).toInt(),
        Intent(context, AlarmReceiver::class.java).setAction(action).putExtra(AlarmReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

/** Rappels : alarme exacte, reçue par AlarmReceiver. */
class AndroidReminderScheduler(
    private val context: Context,
    db: AppDatabase,
    settings: SettingsStore,
) : ReminderScheduler(db, settings) {
    override fun arm(r: ReminderEntity, at: Long) {
        setExactAlarm(context, at, alarmIntent(context, AlarmReceiver.ACTION_REMINDER, r.id))
    }

    override fun disarm(id: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, AlarmReceiver.ACTION_REMINDER, id))
    }
}

/** Enregistrements : alarme exacte au début, puis service au premier plan (RecordingService). */
class AndroidRecordingScheduler(
    private val context: Context,
    db: AppDatabase,
    settings: SettingsStore,
    platform: fr.yannickuhrig.vyzo.platform.Platform? = null,
) : RecordingScheduler(db, settings, platform) {
    override val directory: File
        get() = (context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: context.filesDir)
            .resolve("Enregistrements").apply { mkdirs() }

    override fun armAt(id: Long, at: Long) {
        setExactAlarm(context, at, alarmIntent(context, AlarmReceiver.ACTION_RECORD, id))
    }

    override fun disarmAt(id: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, AlarmReceiver.ACTION_RECORD, id))
    }

    /** Démarre le service d'enregistrement. Renvoie false si le système l'a refusé (app en arrière-plan). */
    override fun startNow(id: Long, retryWithAlarm: Boolean): Boolean {
        val i = Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START).putExtra(AlarmReceiver.EXTRA_ID, id)
        return try {
            ContextCompat.startForegroundService(context, i)
            true
        } catch (e: Exception) {
            // Démarrage interdit depuis l'arrière-plan : une alarme exacte, elle, y est autorisée.
            if (retryWithAlarm) setExactAlarm(context, System.currentTimeMillis() + 3_000, alarmIntent(context, AlarmReceiver.ACTION_RECORD, id))
            false
        }
    }

    override fun stopNow(id: Long) {
        context.startService(
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP).putExtra(AlarmReceiver.EXTRA_ID, id),
        )
    }

    override fun deleteFile(path: String) = RecordingFiles.delete(context, path)

    /** Dossier choisi dans les réglages (clé USB, carte SD…) s'il y en a un, sinon le dossier de l'appli. */
    override suspend fun newRecordingPath(name: String): Pair<String, String> {
        val tree = settings.get().recFolderUri
        if (tree.isBlank()) return directory.resolve(name).absolutePath to ""
        return runCatching { RecordingFiles.createInTree(context, tree, name) to "" }.getOrElse { e ->
            Log.w("VyzoRec", "dossier choisi", e)
            directory.resolve(name).absolutePath to "Dossier choisi inaccessible : enregistré dans le dossier de Vyzo"
        }
    }

    override fun openAppend(path: String): OutputStream = RecordingFiles.openAppend(context, path)

    override fun freeSpace(path: String): Long = if (RecordingFiles.isDocument(path)) -1L else super.freeSpace(path)

    override fun fileExists(path: String): Boolean = RecordingFiles.exists(context, path)

    override fun playUri(path: String): String = RecordingFiles.playUri(path).toString()
}
