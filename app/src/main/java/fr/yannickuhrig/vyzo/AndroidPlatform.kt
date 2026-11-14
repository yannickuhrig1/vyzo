package fr.yannickuhrig.vyzo

import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Xml
import android.widget.Toast
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.yannickuhrig.vyzo.data.backup.SharedBackupStore
import fr.yannickuhrig.vyzo.data.profile.ProfileManager
import fr.yannickuhrig.vyzo.data.sync.SyncWorker
import fr.yannickuhrig.vyzo.platform.BackupFile
import fr.yannickuhrig.vyzo.platform.Platform
import fr.yannickuhrig.vyzo.schedule.Notif
import fr.yannickuhrig.vyzo.ui.common.FormFactor
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Ce qui est propre à Android, vu du code commun. */
class AndroidPlatform(private val context: Context) : Platform {
    private val main = Handler(Looper.getMainLooper())
    private val sharedBackups = SharedBackupStore(context)

    override val isDesktop = false
    override val appVersion: String = BuildConfig.VERSION_NAME
    override val appVersionCode: Int = BuildConfig.VERSION_CODE
    override val deviceModel: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"
    override val stableDeviceId: String by lazy {
        val raw = runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull().orEmpty().trim()
        // L'identifiant factice des vieux émulateurs ne permet pas de distinguer les appareils.
        if (raw.isBlank() || raw.equals("9774d56d682e549c", ignoreCase = true)) ""
        else "and-${ProfileManager.sha256("vyzo-android:$raw").take(32)}"
    }
    override val systemVersion: String get() = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    override val filesDir: File get() = context.filesDir
    override val cloudUrl: String = BuildConfig.SUPABASE_URL
    override val cloudKey: String = BuildConfig.SUPABASE_KEY
    override val tmdbBuiltInKey: String = BuildConfig.TMDB_KEY
    override val updateUrl: String = BuildConfig.UPDATE_URL
    override val supportsPip: Boolean
        get() = Build.VERSION.SDK_INT >= 26 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    override val deviceName: String
        get() = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: Build.MODEL.orEmpty().ifBlank { "Vyzo" }
    override val hasCamera: Boolean get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    override val hasVoiceSearch = true
    override val stopsLiveInBackground = true
    override val cacheDir: File get() = context.cacheDir
    override val localBackupFile: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "vyzo-sauvegarde.json")
    // Ce dossier est le seul que la sauvegarde automatique d'Android ne reprend jamais.
    override val localProfileDir: File get() = File(context.noBackupFilesDir, "profils-locaux")

    override fun writeSharedBackup(text: String): List<String> = sharedBackups.write(text)

    override fun sharedBackups(): List<BackupFile> = sharedBackups.find()

    // L'interface dépend de l'appli installée, plus de l'appareil ni du réglage.
    override val hasFixedFormFactor = true
    override val apkVariant: String = BuildConfig.FLAVOR

    override fun formFactor(pref: String): FormFactor = if (BuildConfig.FLAVOR == "tv") FormFactor.TV else FormFactor.PHONE

    override fun openInput(uri: String): InputStream? = when {
        uri.startsWith("content://") || uri.startsWith("file://") -> context.contentResolver.openInputStream(Uri.parse(uri))
        uri.startsWith("/") -> FileInputStream(uri)
        else -> null
    }

    override fun openOutput(uri: String): OutputStream? = when {
        uri.startsWith("content://") || uri.startsWith("file://") -> context.contentResolver.openOutputStream(Uri.parse(uri))
        uri.startsWith("/") -> FileOutputStream(uri)
        else -> null
    }

    override fun newPullParser(): XmlPullParser = Xml.newPullParser()

    override fun toast(message: String, long: Boolean) {
        main.post { Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }

    override fun scheduleAutoSync(hours: Int) = SyncWorker.schedule(context, hours)

    override fun notifyNewEpisodes(title: String, body: String, seriesUid: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        Notif.ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, seriesUid.hashCode(), MainActivity.openSeriesIntent(context, seriesUid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, Notif.CHANNEL_EPISODES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_EPISODES_BASE + Math.floorMod(seriesUid.hashCode(), 50_000), n) }
    }

    override fun openUrl(url: String): Boolean = runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrElse { false }

    override fun installedApps(): List<fr.yannickuhrig.vyzo.platform.InstalledApp> = runCatching {
        val pm = context.packageManager
        // Android TV : applis du lanceur TV (avec leur bannière). Téléphone : applis du lanceur classique.
        val tv = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER)
        val phone = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val found = pm.queryIntentActivities(tv, 0).ifEmpty { pm.queryIntentActivities(phone, 0) }
        found.asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { info ->
                val banner = runCatching { info.loadBanner(pm) }.getOrNull()
                val drawable = banner ?: runCatching { info.loadIcon(pm) }.getOrNull()
                val bitmap = drawable?.let { d ->
                    val w = if (banner != null) 320 else 128
                    val h = if (banner != null) 180 else 128
                    runCatching { d.toBitmap(w, h).asImageBitmap() }.getOrNull()
                }
                fr.yannickuhrig.vyzo.platform.InstalledApp(info.packageName, info.loadLabel(pm).toString(), bitmap, banner = banner != null)
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }.getOrElse { emptyList() }

    override fun launchApp(id: String): Boolean = runCatching {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(id) ?: pm.getLaunchIntentForPackage(id) ?: return@runCatching false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrElse { false }

    override fun openInExternalPlayer(
        url: String,
        title: String,
        userAgent: String,
        headers: Map<String, String>,
        mimeHint: String?,
        startMs: Long,
    ): Boolean = runCatching {
        val type = when {
            mimeHint?.contains("mpegurl", ignoreCase = true) == true -> "application/x-mpegURL"
            else -> "video/*"
        }
        // Extras compris par VLC, MX Player, Just Player et Kodi (les autres lecteurs les ignorent).
        val allHeaders = buildMap {
            if (userAgent.isNotBlank()) put("User-Agent", userAgent)
            putAll(headers)
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), type)
            putExtra("title", title)
            putExtra(android.content.Intent.EXTRA_TITLE, title)
            if (allHeaders.isNotEmpty()) {
                putExtra("headers", allHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray())
            }
            if (userAgent.isNotBlank()) putExtra("http-user-agent", userAgent)
            if (startMs > 0) {
                putExtra("position", startMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                putExtra("from_start", false)
            }
        }
        val chooser = android.content.Intent.createChooser(intent, "Ouvrir avec").apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Aucun lecteur vidéo installé : on le dit plutôt que d'ouvrir un sélecteur vide.
        val handlers = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName != context.packageName }
        if (handlers.isEmpty()) return@runCatching false
        context.startActivity(chooser)
        true
    }.getOrElse { false }
}

private const val NOTIF_EPISODES_BASE = 20_000
