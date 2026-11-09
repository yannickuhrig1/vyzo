package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.db.RecordingEntity
import fr.yannickuhrig.vyzo.schedule.covers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Programmation d'enregistrements : heure réelle de début, marges qui débordent sur les émissions voisines. */
class DesktopRecordingTest {
    private val min = 60_000L

    private val ch = ChannelEntity(
        uid = "1:L:1", playlistId = 1, remoteId = "1", name = "TF1", nameNorm = "tf1", matchKey = "tf1",
        number = 1, logo = "", categoryUid = "", epgId = "", epgKey = "tf1", streamUrl = "http://srv/1", position = 0, syncToken = 1L,
    )

    private fun program(start: Long, stop: Long, title: String) =
        ProgramEntity(channelKey = "tf1", start = start, stop = stop, title = title, sourceKey = "test", generation = 1)

    @Test
    fun lesMargesNeMarquentPasLesEmissionsVoisines() {
        val h16 = 1_800_000_000_000L
        // Émission de 16:00 à 17:00, enregistrée de 15:59 à 17:05 (marges de 1 et 5 min).
        val rec = RecordingEntity(channelUid = ch.uid, channelName = ch.name, title = "Journal", start = h16 - min, stop = h16 + 65 * min)
        val margin = 5 * min
        assertTrue(rec.covers(h16, h16 + 60 * min, margin))
        assertFalse("émission précédente (15:00–16:00)", rec.covers(h16 - 60 * min, h16, margin))
        assertFalse("émission suivante (17:00–18:00)", rec.covers(h16 + 60 * min, h16 + 120 * min, margin))
        // Une émission très courte entièrement dans l'enregistrement compte.
        assertTrue(rec.covers(h16 + 10 * min, h16 + 14 * min, margin))
    }

    @Test
    fun emissionDejaCommenceeEnregistreeAPartirDeMaintenant() = runBlocking {
        val c = testContainer(Files.createTempDirectory("vyzo-rec").toFile())
        c.settings.update { it.copy(isRecordingHost = false, recPaddingBeforeMin = 1, recPaddingAfterMin = 5) }
        val now = System.currentTimeMillis()
        val p = program(now - 19 * min, now + 36 * min, "Grands reportages")

        val before = System.currentTimeMillis()
        val r = c.db.recordings().get(c.recordings.recordProgram(ch, p))!!
        assertTrue("début ${r.start} : pas avant le lancement ($before)", r.start >= before)
        assertTrue(r.start <= System.currentTimeMillis())
        assertEquals(p.stop + 5 * min, r.stop)
    }

    @Test
    fun lEnregistrementSuivantNEmpechePasDEnregistrerLEmissionPrecedente() = runBlocking {
        val c = testContainer(Files.createTempDirectory("vyzo-rec").toFile())
        c.settings.update { it.copy(isRecordingHost = false, recPaddingBeforeMin = 1, recPaddingAfterMin = 5) }
        val h = (System.currentTimeMillis() / 3_600_000 + 2) * 3_600_000
        val previous = program(h - 60 * min, h, "Avant")
        val next = program(h, h + 60 * min, "Après")

        val nextId = c.recordings.recordProgram(ch, next)
        assertEquals(null, c.recordings.findFor(ch, previous))
        val previousId = c.recordings.recordProgram(ch, previous)
        assertNotEquals(nextId, previousId)
        assertEquals(nextId, c.recordings.findFor(ch, next)?.id)
        assertEquals(previousId, c.recordings.findFor(ch, previous)?.id)
    }
}
