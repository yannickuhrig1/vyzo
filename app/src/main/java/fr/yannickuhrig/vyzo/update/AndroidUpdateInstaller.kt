package fr.yannickuhrig.vyzo.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import java.io.File

/** Mises à jour Android : APK installé par le PackageInstaller (même clé de signature obligatoire). */
class AndroidUpdateInstaller(private val context: Context) : UpdateInstaller {

    override fun packageFor(info: UpdateInfo): UpdatePackage? =
        info.apk.takeIf { it.isNotBlank() }?.let { UpdatePackage(it, info.sha256, "vyzo-${info.versionCode}.apk") }

    override fun isCompatible(info: UpdateInfo): Boolean = Build.VERSION.SDK_INT >= info.minSdk

    /** Android 8+ : réglage « sources inconnues » propre à l'appli. */
    override fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    override fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("vyzo.apk", 0, file.length()).use { os ->
                    input.copyTo(os, 256 * 1024)
                    session.fsync(os)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "fr.yannickuhrig.vyzo.INSTALL_RESULT"

        /** Message d'échec d'installation, null si l'installation a réussi ou attend l'utilisateur. */
        fun failureMessage(status: Int, message: String?): String? = when (status) {
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_PENDING_USER_ACTION -> null
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation annulée"
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "Installation refusée : l'APK n'est pas signé avec la même clé que l'appli installée"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Pas assez de place pour installer la mise à jour"
            else -> "Installation impossible" + (message?.let { " : $it" } ?: "")
        }
    }
}

/** Résultat de l'installation (et demande de confirmation à l'utilisateur quand Android l'exige). */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(confirm) }
                .onFailure { Toast.makeText(context, "Ouvre Vyzo pour terminer la mise à jour", Toast.LENGTH_LONG).show() }
        }
        val app = context.applicationContext as? fr.yannickuhrig.vyzo.VyzoApp ?: return
        AndroidUpdateInstaller.failureMessage(status, message)?.let { app.container.updater.fail(it) }
    }
}
