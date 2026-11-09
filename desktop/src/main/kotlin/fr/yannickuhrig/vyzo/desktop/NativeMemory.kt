package fr.yannickuhrig.vyzo.desktop

import coil3.EventListener
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.sun.jna.Function
import fr.yannickuhrig.vyzo.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Mémoire native des images (affiches, logos). Skia ne la rend qu'au passage du ramasse-miettes sur les images
 * sorties du cache, or le tas reste presque vide : sans aide, Vyzo PC montait à plus de 2 Go après une heure.
 * Toutes les [budget] octets décodés : un ramasse-miettes (concurrent, sans figer l'écran), puis sous Linux
 * malloc_trim, qui rend au système la mémoire libérée au lieu de la garder en réserve.
 */
class NativeMemory(private val budget: Long = 128L shl 20) {
    private val pending = AtomicLong()
    private val running = AtomicBoolean()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "native-memory").apply { isDaemon = true } }

    /** Nombre de nettoyages lancés (tests). */
    val collections = AtomicLong()

    val listener = object : EventListener() {
        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            if (result.dataSource != DataSource.MEMORY_CACHE) allocated(result.image.size)
        }
    }

    fun allocated(bytes: Long) {
        if (pending.addAndGet(bytes) < budget || !running.compareAndSet(false, true)) return
        pending.set(0)
        collections.incrementAndGet()
        worker.execute {
            System.gc()
            // Les images Skia sont libérées par leur Cleaner juste après : on rend la mémoire un peu plus tard.
            worker.schedule({
                if (Os.isLinux) runCatching { Function.getFunction("c", "malloc_trim").invokeInt(arrayOf(0)) }
                    .onFailure { Log.w("VyzoMemory", "malloc_trim indisponible : ${it.message}") }
                running.set(false)
            }, 1, TimeUnit.SECONDS)
        }
    }
}
