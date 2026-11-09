package fr.yannickuhrig.vyzo.desktop

import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import fr.yannickuhrig.vyzo.ui.common.vyzoImageLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/** Mémoire native des affiches (rapport § 3.11) : sans nettoyage, ~2 Mo par affiche décodée restaient pris. */
class NativeMemoryTest {
    private fun rssMb(): Long = File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }.filter { it.isDigit() }.toLong() / 1024

    @Test
    fun unNettoyageParBudgetDepasse() {
        val m = NativeMemory(budget = 100)
        m.allocated(60)
        assertEquals(0, m.collections.get())
        m.allocated(60)
        assertEquals(1, m.collections.get())
    }

    @Test
    fun lesAffichesDecodeesRendentLeurMemoire() {
        assumeTrue("mesure de la mémoire par /proc", Os.isLinux)
        val dir = Files.createTempDirectory("vyzo-affiches").toFile()
        try {
            val posters = (0 until 400).map { i ->
                File(dir, "a$i.jpg").also { f ->
                    val img = BufferedImage(600, 900, BufferedImage.TYPE_INT_RGB)
                    img.createGraphics().apply { color = Color(i * 37 % 256, i * 91 % 256, i * 13 % 256); fillRect(0, 0, 600, 900); dispose() }
                    ImageIO.write(img, "jpg", f)
                }
            }
            val guard = NativeMemory(budget = 64L shl 20)
            val loader = vyzoImageLoader(PlatformContext.INSTANCE, File(dir, "cache"), guard.listener)
            val before = rssMb()
            runBlocking {
                posters.forEach { f ->
                    // Rythme d'un défilement rapide plutôt que tout d'un coup.
                    assertTrue(loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(f).build()) is SuccessResult)
                    Thread.sleep(10)
                }
            }
            Thread.sleep(3_000)
            val grown = rssMb() - before
            // 400 affiches de 600×900 = ~820 Mo décodés ; le cache mémoire en garde au plus ~76.
            assertTrue("nettoyages : ${guard.collections.get()}", guard.collections.get() >= 3)
            assertTrue("la mémoire doit être rendue : +$grown Mo", grown < 350)
        } finally {
            dir.deleteRecursively()
        }
    }
}
