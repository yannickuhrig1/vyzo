package fr.yannickuhrig.vyzo.schedule

import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.db.RecordingEntity
import fr.yannickuhrig.vyzo.data.db.RecordingStatus
import fr.yannickuhrig.vyzo.data.remote.ScheduleSyncEvent
import fr.yannickuhrig.vyzo.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Exécute un enregistrement : ouvre le flux de la chaîne et l'écrit dans un fichier jusqu'à l'heure de fin.
 * Utilisé par le service Android et par Vyzo PC.
 */
class RecordingRunner(private val c: AppContainer) {

    /**
     * Enregistre [id]. [onStarted] reçoit le titre une fois l'enregistrement lancé.
     * Les mises à jour intermédiaires de taille sont lancées dans [scope].
     */
    suspend fun record(id: Long, scope: CoroutineScope, onStarted: (String) -> Unit = {}) {
        val dao = c.db.recordings()
        val r = dao.get(id) ?: return
        if (r.status != RecordingStatus.SCHEDULED && r.status != RecordingStatus.RECORDING) return
        if (r.stop <= System.currentTimeMillis()) {
            dao.setStatus(id, RecordingStatus.FAILED, "Émission déjà terminée")
            return
        }
        onStarted(r.title)
        var bytes = r.bytes
        var note = ""
        val path = r.filePath.ifBlank {
            // Lancé en retard (appareil éteint à l'heure prévue) : le nom donne l'heure réelle du début.
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(maxOf(r.start, System.currentTimeMillis()))
            val safe = r.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "enregistrement" }
            val (p, n) = c.recordings.newRecordingPath("${safe}_$stamp.ts")
            note = n
            p
        }
        val recEntity = r.copy(status = RecordingStatus.RECORDING, filePath = path, error = note)
        dao.update(recEntity)
        notifyStatus(recEntity, RecordingStatus.RECORDING)
        try {
            val ch = c.repo.channel(r.channelUid) ?: throw IllegalStateException("Chaîne introuvable")
            val spec = c.repo.resolveChannel(ch)
            var lastSave = 0L
            StreamRecorder(
                spec,
                openOutput = { c.recordings.openAppend(path) },
                stopAt = r.stop,
                freeSpace = { c.recordings.freeSpace(path) },
            ) { total ->
                bytes = r.bytes + total
                val now = System.currentTimeMillis()
                if (now - lastSave > 5_000) {
                    lastSave = now
                    scope.launch { dao.setBytes(id, bytes) }
                }
            }.run()
            dao.setBytes(id, bytes)
            val finalStatus = if (bytes > 0) RecordingStatus.DONE else RecordingStatus.FAILED
            val finalNote = if (bytes > 0) note else "Aucune donnée reçue"
            dao.setStatus(id, finalStatus, finalNote)
            notifyStatus(recEntity, finalStatus, finalNote)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                dao.setBytes(id, bytes)
                val cancelStatus = if (bytes > 0) RecordingStatus.DONE else RecordingStatus.CANCELLED
                val cancelNote = if (bytes > 0) "Arrêté manuellement" else ""
                dao.setStatus(id, cancelStatus, cancelNote)
                notifyStatus(recEntity, cancelStatus, cancelNote)
            }
            throw e
        } catch (e: Exception) {
            Log.w("VyzoRec", "recording $id", e)
            dao.setBytes(id, bytes)
            val errStatus = if (bytes > 1_000_000) RecordingStatus.DONE else RecordingStatus.FAILED
            val errNote = e.message ?: e.toString()
            dao.setStatus(id, errStatus, errNote)
            notifyStatus(recEntity, errStatus, errNote)
        }
    }

    private fun notifyStatus(r: RecordingEntity, status: RecordingStatus, error: String? = null) {
        c.cloud.sendScheduleSync(
            ScheduleSyncEvent(
                action = "status",
                channelUid = r.channelUid,
                channelName = r.channelName,
                title = r.title,
                start = r.start,
                stop = r.stop,
                status = status.name,
                error = error,
                senderDeviceId = c.platform.deviceModel,
            )
        )
    }
}
