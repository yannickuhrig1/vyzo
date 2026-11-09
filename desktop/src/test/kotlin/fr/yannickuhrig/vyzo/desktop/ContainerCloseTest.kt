package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.closeForTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Fermer un conteneur juste après une écriture : aucune tâche de fond ne doit lire la base fermée (échecs au hasard du test suivant). */
class ContainerCloseTest {
    @Test
    fun aucuneTacheNeLitLaBaseFermee() {
        val errors = AtomicInteger()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> errors.incrementAndGet() }
        try {
            repeat(150) { i ->
                val dir = Files.createTempDirectory("vyzo-fermeture").toFile()
                val c = testContainer(dir)
                runBlocking {
                    c.profiles.ensureOne()
                    c.profiles.create("Profil $i")
                }
                c.closeForTest()
                dir.deleteRecursively()
            }
            runBlocking { delay(500) }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertEquals(0, errors.get())
    }
}
