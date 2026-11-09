package fr.yannickuhrig.vyzo.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.jvm.optionals.getOrNull

class SystemIntegrationTest {
    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return cond()
    }

    @Test
    fun instanceUniqueRetireSesFichiersALArret() {
        val dir = Files.createTempDirectory("vyzo-instance").toFile()
        try {
            var shown = 0
            val first = SingleInstance(dir)
            assertTrue(first.acquire { shown++ })
            assertTrue(dir.resolve("vyzo.lock").exists() && dir.resolve("vyzo.port").exists())

            // Deuxième lancement : refusé, et la fenêtre existante est ramenée au premier plan.
            assertFalse(SingleInstance(dir).acquire {})
            assertTrue(waitUntil { shown == 1 })

            first.release()
            first.release()
            assertFalse(dir.resolve("vyzo.lock").exists())
            assertFalse(dir.resolve("vyzo.port").exists())

            val next = SingleInstance(dir)
            assertTrue(next.acquire {})
            next.release()
        } finally {
            dir.deleteRecursively()
        }
    }

    // Le « tail » lui-même : la ligne de commande de systemd-inhibit contient aussi « tail --pid=… ».
    private fun inhibitorChildren() = ProcessHandle.current().descendants().filter { p ->
        p.info().command().getOrNull()?.substringAfterLast('/') == "tail" &&
            p.info().arguments().getOrNull()?.contains("--pid=${ProcessHandle.current().pid()}") == true
    }.count()

    @Test
    fun inhibiteurDeVeilleSuitLeProcessusDeVyzo() {
        assumeTrue("systemd-inhibit absent", Os.isLinux && Os.hasCommand("systemd-inhibit"))
        assumeTrue("pas de session systemd", runCatching { ProcessBuilder("systemd-inhibit", "--list").start().waitFor() == 0 }.getOrDefault(false))
        // Machine d'intégration continue : la liste se lit, mais bloquer la veille est refusé (pas de session ouverte).
        assumeTrue(
            "blocage de la veille refusé",
            runCatching {
                val p = ProcessBuilder("systemd-inhibit", "--what=sleep:idle", "--who=test", "--why=test", "--mode=block", "true").start()
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
                !p.isAlive && p.exitValue() == 0
            }.getOrDefault(false),
        )
        KeepAwake.acquire("test")
        try {
            assertTrue("l'inhibiteur doit attendre la fin de ce processus (tail --pid)", waitUntil { inhibitorChildren() == 1L })
        } finally {
            KeepAwake.release("test")
        }
        assertTrue(waitUntil { inhibitorChildren() == 0L })
        assertEquals(0L, inhibitorChildren())
    }
}
