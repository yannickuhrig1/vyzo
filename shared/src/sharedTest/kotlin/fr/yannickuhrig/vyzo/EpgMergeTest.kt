package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.db.ProgramSlot
import fr.yannickuhrig.vyzo.data.epg.EpgCatalog
import fr.yannickuhrig.vyzo.data.epg.programsFittingGaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgMergeTest {
    private fun program(start: Long, stop: Long) = ProgramEntity(channelKey = "a", start = start, stop = stop, title = "$start", sourceKey = "s", generation = 1)
    private fun slot(start: Long, stop: Long) = ProgramSlot("a", start, stop)
    private fun titles(list: List<ProgramEntity>) = list.map { it.title }

    @Test
    fun keepsOnlyWhatFitsInFreeSlots() {
        val taken = listOf(slot(100, 200), slot(300, 400))
        val incoming = listOf(program(0, 100), program(150, 250), program(200, 300), program(250, 350), program(400, 500), program(50, 450))
        assertEquals(listOf("0", "200", "400"), titles(programsFittingGaps(taken, incoming)))
    }

    @Test
    fun overlappingEarlierSourcesCountAsOneBusySlot() {
        val taken = listOf(slot(200, 300), slot(100, 250), slot(120, 130))
        assertEquals(listOf("300"), titles(programsFittingGaps(taken, listOf(program(260, 280), program(300, 320)))))
    }

    @Test
    fun emptyGuideKeepsEverything() {
        assertEquals(2, programsFittingGaps(emptyList(), listOf(program(0, 1), program(1, 2))).size)
    }

    private val listing = """
        <a href="epg_ripper_FR1.pdf">epg_ripper_FR1.pdf</a>      09-Nov-2026 05:36   123189
        <a href="epg_ripper_FR1.xml.gz">epg_ripper_FR1.xml.gz</a>      09-Nov-2026 05:38   5371037
        <a href="epg_ripper_UK1.xml.gz">epg_ripper_UK1.xml.gz</a>      09-Nov-2026 05:38   3139821
        <a href="epg_ripper_BR1.xml.gz">epg_ripper_BR1.xml.gz</a>      09-Nov-2026 05:38   100
        <a href="epg_ripper_BR2.xml.gz">epg_ripper_BR2.xml.gz</a>      09-Nov-2026 05:38   200
        <a href="epg_ripper_US2.xml.gz">epg_ripper_US2.xml.gz</a>      09-Nov-2026 05:38   300
        <a href="epg_ripper_BEIN1.xml.gz">epg_ripper_BEIN1.xml.gz</a>      09-Nov-2026 05:38   400
        <a href="epg_ripper_ALL_SOURCES1.xml.gz">epg_ripper_ALL_SOURCES1.xml.gz</a>      09-Nov-2026 05:38   202174096
        <a href="epg_ripper_DUMMY_CHANNELS.xml.gz">epg_ripper_DUMMY_CHANNELS.xml.gz</a>      09-Nov-2026 05:38   500
    """.trimIndent()

    @Test
    fun catalogueListsGuidesWithTheirSize() {
        val entries = EpgCatalog.parseListing(listing)
        assertFalse(entries.any { it.code == "ALL_SOURCES1" || it.code == "DUMMY_CHANNELS" })
        val france = entries.single { it.code == "FR1" }
        assertEquals("France", france.label)
        assertEquals(5371037L, france.sizeBytes)
        assertEquals("https://epgshare01.online/epgshare01/epg_ripper_FR1.xml.gz", france.url)
        assertEquals("Royaume-Uni", entries.single { it.code == "UK1" }.label)
        assertEquals(listOf("Brésil 1", "Brésil 2"), entries.filter { it.code.startsWith("BR") }.map { it.label })
        assertEquals("BEIN", entries.last().label)
    }

    @Test
    fun catalogueSearchIgnoresAccentsAndPunctuation() {
        val entries = EpgCatalog.parseListing(listing)
        val unitedStates = entries.single { it.code == "US2" }
        assertTrue(EpgCatalog.matches(unitedStates, "etats unis"))
        assertTrue(EpgCatalog.matches(entries.single { it.code == "BR1" }, "BRÉSIL"))
        assertTrue("code du pays", EpgCatalog.matches(unitedStates, "us"))
        assertTrue("recherche vide", EpgCatalog.matches(unitedStates, " "))
        assertFalse(EpgCatalog.matches(unitedStates, "france"))
    }
}
