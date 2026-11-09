package fr.yannickuhrig.vyzo.data.backup

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import fr.yannickuhrig.vyzo.platform.BackupFile
import fr.yannickuhrig.vyzo.util.Log
import java.io.File

/** L'appli peut lire les fichiers partagés qu'elle n'a pas écrits (ceux d'une installation précédente, par exemple). */
fun Context.hasSharedStorageAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

/**
 * Sauvegardes hors du dossier de l'appli, pour les appareils sans sélecteur de fichier (Android TV) :
 * Téléchargements/Vyzo et clés USB. Ces fichiers restent après une désinstallation, mais Android les cache ensuite à
 * l'appli réinstallée : sans [hasSharedStorageAccess], on ne voit que ceux écrits par l'installation en cours.
 */
class SharedBackupStore(private val context: Context) {

    fun write(text: String): List<String> {
        val bytes = text.toByteArray()
        if (Build.VERSION.SDK_INT < 29) {
            return runCatching {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
                File(dir, NAME).writeBytes(bytes)
                listOf(PRIMARY_LABEL)
            }.getOrElse {
                Log.w(TAG, "Écriture dans Téléchargements impossible", it)
                emptyList()
            }
        }
        return MediaStore.getExternalVolumeNames(context).sortedBy { it != MediaStore.VOLUME_EXTERNAL_PRIMARY }.mapNotNull { volume ->
            runCatching {
                writeTo(volume, bytes)
                labelOf(volume)
            }.onFailure { Log.w(TAG, "Écriture sur le volume $volume impossible", it) }.getOrNull()
        }
    }

    fun find(): List<BackupFile> = when {
        context.hasSharedStorageAccess() -> scanFiles()
        Build.VERSION.SDK_INT >= 29 -> MediaStore.getExternalVolumeNames(context).flatMap { ownRows(it) }
        else -> emptyList()
    }

    @RequiresApi(29)
    private fun writeTo(volume: String, bytes: ByteArray) {
        val collection = MediaStore.Downloads.getContentUri(volume)
        val resolver = context.contentResolver
        val existing = resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(NAME, RELATIVE_PATH), null,
        )?.use { if (it.moveToFirst()) ContentUris.withAppendedId(collection, it.getLong(0)) else null }
        val uri: Uri = existing ?: resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            },
        ) ?: error("Insertion refusée")
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Fichier inaccessible")
    }

    @RequiresApi(29)
    private fun ownRows(volume: String): List<BackupFile> = runCatching {
        val collection = MediaStore.Downloads.getContentUri(volume)
        context.contentResolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("$PREFIX%$SUFFIX"), null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(BackupFile(ContentUris.withAppendedId(collection, cursor.getLong(0)).toString(), labelOf(volume), cursor.getLong(2) * 1000))
                }
            }
        }.orEmpty()
    }.getOrElse {
        Log.w(TAG, "Lecture du volume $volume impossible", it)
        emptyList()
    }

    /** Tous les volumes montés : la racine se déduit du dossier de l'appli sur chacun. */
    private fun scanFiles(): List<BackupFile> = context.getExternalFilesDirs(null).filterNotNull().flatMap { appDir ->
        val root = File(appDir.absolutePath.substringBefore("/Android/"))
        listOf(File(root, "Download/$FOLDER"), File(root, "Download"), root).flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }.orEmpty().asList()
        }.map { BackupFile(it.absolutePath, labelOf(root, it), it.lastModified()) }
    }

    private fun storage() = context.getSystemService(StorageManager::class.java)

    @RequiresApi(29)
    private fun labelOf(volume: String): String {
        if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) return PRIMARY_LABEL
        val name = storage()?.storageVolumes?.firstOrNull { it.uuid.equals(volume, ignoreCase = true) }?.getDescription(context) ?: "Clé USB"
        return "$name · Download/$FOLDER"
    }

    private fun labelOf(root: File, file: File): String {
        val folder = file.parentFile?.relativeTo(root)?.path.orEmpty()
        if (root.absolutePath == Environment.getExternalStorageDirectory().absolutePath) {
            return folder.replaceFirst("Download", "Téléchargements").ifEmpty { "Stockage interne" }
        }
        val name = if (Build.VERSION.SDK_INT >= 24) storage()?.getStorageVolume(file)?.getDescription(context) else null
        return listOfNotNull(name ?: "Clé USB", folder.ifEmpty { null }).joinToString(" · ")
    }

    private companion object {
        const val TAG = "VyzoBackup"
        const val FOLDER = "Vyzo"
        const val NAME = "vyzo-sauvegarde.json"
        const val PREFIX = "vyzo-sauvegarde"
        const val SUFFIX = ".json"
        const val RELATIVE_PATH = "Download/$FOLDER/"
        const val PRIMARY_LABEL = "Téléchargements/$FOLDER"
    }
}
