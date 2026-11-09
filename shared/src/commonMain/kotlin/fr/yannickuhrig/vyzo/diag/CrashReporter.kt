package fr.yannickuhrig.vyzo.diag

import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.net.await
import fr.yannickuhrig.vyzo.platform.Platform
import fr.yannickuhrig.vyzo.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Rapports de plantage : écrits sur l'appareil au moment du plantage, envoyés au lancement suivant
 * (table crash_reports du projet Supabase de Vyzo, consultable dans son tableau de bord).
 * Contenu : version, modèle d'appareil, pile d'appel et fin du journal de diagnostic (déjà expurgé).
 */
object CrashReporter {
    private const val DIR = "crashes"
    private const val MAX_FILES = 20

    fun install(platform: Platform) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(platform, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun dir(platform: Platform) = File(platform.filesDir, DIR).apply { mkdirs() }

    private fun write(platform: Platform, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val json = buildJsonObject {
            put("app_version", platform.appVersion)
            put("version_code", platform.appVersionCode)
            // Colonne « android » de la table : version du système (Android, ou Linux/Windows pour Vyzo PC).
            put("android", platform.systemVersion)
            put("device", platform.deviceModel)
            put("thread", thread.name)
            put("stack", DiagLog.redact(sw.toString()).take(60_000))
            put("log", DiagLog.dump().takeLast(20_000))
            put("happened_at", System.currentTimeMillis())
        }
        val d = dir(platform)
        File(d, "crash-${System.currentTimeMillis()}.json").writeText(json.toString())
        d.listFiles()?.sortedByDescending { it.name }?.drop(MAX_FILES)?.forEach { it.delete() }
    }

    fun pendingCount(platform: Platform): Int = dir(platform).listFiles()?.size ?: 0

    fun clear(platform: Platform) {
        dir(platform).listFiles()?.forEach { it.delete() }
    }

    /** Envoie les rapports en attente ; ceux qui partent sont supprimés de l'appareil. */
    suspend fun sendPending(platform: Platform, enabled: Boolean) = withContext(Dispatchers.IO) {
        val files = dir(platform).listFiles()?.sortedBy { it.name }.orEmpty()
        if (!enabled || files.isEmpty() || platform.cloudUrl.isBlank()) return@withContext
        val type = "application/json; charset=utf-8".toMediaType()
        for (f in files) {
            val body = runCatching { f.readText() }.getOrNull() ?: continue
            val req = Request.Builder()
                .url("${platform.cloudUrl}/rest/v1/crash_reports")
                .header("apikey", platform.cloudKey)
                .header("Prefer", "return=minimal")
                .post(body.toRequestBody(type))
                .build()
            val ok = runCatching { Http.client.newCall(req).await().use { it.isSuccessful } }.getOrElse { false }
            if (ok) f.delete() else {
                Log.w("VyzoCrash", "envoi impossible, nouvel essai au prochain lancement")
                break
            }
        }
    }
}
