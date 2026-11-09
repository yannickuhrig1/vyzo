package fr.yannickuhrig.vyzo.ui.common

import androidx.compose.ui.Modifier
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import fr.yannickuhrig.vyzo.data.backup.hasSharedStorageAccess

fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

/** Certaines TV déclarent un faux écran système (sélecteur de fichiers…), qui répond « You don't have an app that can do this » sans rien ouvrir. */
private fun Context.isFilePickerStub(intent: Intent): Boolean =
    packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == "com.android.tv.frameworkpackagestubs"

@Composable
actual fun rememberOpenFile(mimeTypes: List<String>, keepAccess: Boolean, onResult: (String?) -> Unit): FileLauncher {
    val ctx = LocalContext.current
    val current by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && keepAccess) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        current(uri?.toString())
    }
    return remember(launcher) {
        FileLauncher {
            if (ctx.isFilePickerStub(ActivityResultContracts.OpenDocument().createIntent(ctx, mimeTypes.toTypedArray()))) return@FileLauncher false
            try {
                launcher.launch(mimeTypes.toTypedArray())
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
        }
    }
}

@Composable
actual fun rememberPickFolder(onResult: (uri: String?, name: String) -> Unit): FileLauncher {
    val ctx = LocalContext.current
    val current by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) {
            current(null, "")
            return@rememberLauncherForActivityResult
        }
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val name = runCatching { androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, uri)?.name }.getOrNull()
            ?: uri.lastPathSegment.orEmpty()
        current(uri.toString(), name)
    }
    return remember(launcher) {
        FileLauncher {
            if (ctx.isFilePickerStub(ActivityResultContracts.OpenDocumentTree().createIntent(ctx, null))) return@FileLauncher false
            try {
                launcher.launch(null)
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
        }
    }
}

@Composable
actual fun rememberSaveFile(mimeType: String, onResult: (String?) -> Unit): SaveFileLauncher {
    val ctx = LocalContext.current
    val current by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri -> current(uri?.toString()) }
    return remember(launcher) {
        SaveFileLauncher { name ->
            if (ctx.isFilePickerStub(ActivityResultContracts.CreateDocument(mimeType).createIntent(ctx, name))) return@SaveFileLauncher false
            try {
                launcher.launch(name)
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
        }
    }
}

@Composable
actual fun rememberVoiceSearch(onResult: (String) -> Unit): (() -> Unit)? {
    val current by rememberUpdatedState(onResult)
    var available by remember { mutableStateOf(true) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { current(it) }
    }
    if (!available) return null
    return {
        try {
            launcher.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Que veux-tu regarder ?"),
            )
        } catch (e: ActivityNotFoundException) {
            available = false
        }
    }
}

@Composable
actual fun PlayerWindowEffect(tv: Boolean) {
    val view = LocalView.current
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val activity = ctx.findActivity()
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        val previousOrientation = activity?.requestedOrientation
        if (!tv) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (!tv) activity?.requestedOrientation = previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

actual val KeyEvent.repeatCount: Int get() = nativeKeyEvent.repeatCount

actual fun Modifier.hideCursor(hidden: Boolean): Modifier = this

@Composable
actual fun rememberNotificationPermission(onResult: (granted: Boolean) -> Unit): PermissionRequest {
    val ctx = LocalContext.current
    val current by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { current(it) }
    return remember(launcher) {
        PermissionRequest(
            isNeeded = {
                Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            },
            launch = { if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else current(true) },
        )
    }
}

@Composable
actual fun rememberStorageAccess(onResult: () -> Unit): StorageAccess? {
    val ctx = LocalContext.current
    val current by rememberUpdatedState(onResult)
    val allFiles = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { current() }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { current() }
    return remember(allFiles, permissions) {
        StorageAccess(
            isGranted = { ctx.hasSharedStorageAccess() },
            neededToWrite = Build.VERSION.SDK_INT < 30,
            request = {
                if (Build.VERSION.SDK_INT >= 30) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    !ctx.isFilePickerStub(intent) && try {
                        allFiles.launch(intent)
                        true
                    } catch (e: ActivityNotFoundException) {
                        false
                    }
                } else {
                    permissions.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                    true
                }
            },
        )
    }
}

@Composable
actual fun rememberOverlaySetting(): OverlaySetting? {
    val ctx = LocalContext.current
    val granted = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)
    return OverlaySetting(granted) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }.onFailure { Toast.makeText(ctx, "Réglage indisponible sur cet appareil", Toast.LENGTH_SHORT).show() }
    }
}
