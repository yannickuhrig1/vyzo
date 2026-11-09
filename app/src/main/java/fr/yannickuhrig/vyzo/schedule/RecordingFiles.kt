package fr.yannickuhrig.vyzo.schedule

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Fichiers d'enregistrement : soit dans le dossier de l'appli (chemin), soit dans un dossier choisi par l'utilisateur
 * avec le sélecteur Android (clé USB, carte SD, partage réseau…), désigné par une adresse content://.
 */
object RecordingFiles {
    fun isDocument(path: String) = path.startsWith("content://")

    /** Crée un nouveau fichier dans le dossier choisi ([treeUri]) et renvoie son adresse. */
    fun createInTree(context: Context, treeUri: String, displayName: String): String {
        val tree = Uri.parse(treeUri)
        val dir = DocumentFile.fromTreeUri(context, tree) ?: throw IOException("Dossier d'enregistrement inaccessible")
        if (!dir.canWrite()) throw IOException("Le dossier d'enregistrement n'est plus accessible : choisis-le à nouveau dans les réglages")
        val doc = dir.createFile("video/mp2t", displayName) ?: throw IOException("Impossible de créer le fichier dans le dossier choisi")
        return doc.uri.toString()
    }

    fun openAppend(context: Context, path: String): OutputStream =
        if (isDocument(path)) {
            context.contentResolver.openOutputStream(Uri.parse(path), "wa") ?: throw IOException("Fichier d'enregistrement inaccessible")
        } else {
            File(path).also { it.parentFile?.mkdirs() }.let { FileOutputStream(it, true) }
        }

    fun delete(context: Context, path: String) {
        runCatching {
            if (isDocument(path)) DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(path)) else File(path).delete()
        }
    }

    fun exists(context: Context, path: String): Boolean = runCatching {
        if (isDocument(path)) DocumentFile.fromSingleUri(context, Uri.parse(path))?.exists() == true else File(path).exists()
    }.getOrDefault(false)

    /** Adresse lisible par le lecteur. */
    fun playUri(path: String): Uri = if (isDocument(path)) Uri.parse(path) else Uri.fromFile(File(path))

    /** Nom lisible d'un dossier choisi. */
    fun treeName(context: Context, treeUri: String): String =
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.name }.getOrNull().orEmpty()
}
