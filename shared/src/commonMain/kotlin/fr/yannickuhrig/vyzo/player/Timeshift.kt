package fr.yannickuhrig.vyzo.player

import fr.yannickuhrig.vyzo.data.repo.StreamSpec
import fr.yannickuhrig.vyzo.schedule.StreamRecorder
import fr.yannickuhrig.vyzo.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Pause du direct sans replay chez le fournisseur : pendant la pause, le flux est écrit dans un fichier temporaire
 * (une seule connexion, celle du lecteur ayant été libérée) ; à la reprise, on lit ce fichier pendant qu'il continue
 * de se remplir, avec le décalage choisi par la pause.
 */
class TimeshiftBuffer(private val cacheDir: File) {
    private var job: Job? = null

    @Volatile var file: File? = null
        private set
    @Volatile var recording: Boolean = false
        private set
    /** Heure (horloge murale) correspondant au début du fichier. */
    @Volatile var startedAt: Long = 0
        private set
    @Volatile var error: String? = null
        private set

    val active: Boolean get() = file != null

    /** Démarre l'enregistrement du direct. Renvoie false s'il n'y a pas assez de place. */
    fun start(scope: CoroutineScope, spec: StreamSpec, maxMinutes: Int): Boolean {
        stop()
        val dir = File(cacheDir, "timeshift").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        if (dir.usableSpace < MIN_FREE_BYTES) {
            error = "Pas assez de place sur l'appareil pour mettre le direct en pause"
            return false
        }
        val f = File(dir, "direct-${System.currentTimeMillis()}.ts")
        file = f
        startedAt = System.currentTimeMillis()
        recording = true
        error = null
        job = scope.launch(Dispatchers.IO) {
            try {
                StreamRecorder(spec, f, startedAt + maxMinutes * 60_000L) { total ->
                    if (total % (8L shl 20) < 65_536 && f.parentFile!!.usableSpace < MIN_FREE_BYTES / 2) {
                        throw IOException("Mémoire de l'appareil presque pleine")
                    }
                }.run()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.w("VyzoTimeshift", "tampon", e)
                    error = e.message
                }
            } finally {
                recording = false
            }
        }
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        recording = false
        file?.let { f -> runCatching { f.delete() } }
        file = null
        startedAt = 0
    }

    companion object {
        private const val MIN_FREE_BYTES = 400L shl 20
    }
}
