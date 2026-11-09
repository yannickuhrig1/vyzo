package fr.yannickuhrig.vyzo.tv

import fr.yannickuhrig.vyzo.data.link.DeepLink
import fr.yannickuhrig.vyzo.data.link.DeepLinkKind
import fr.yannickuhrig.vyzo.data.link.LauncherItem
import fr.yannickuhrig.vyzo.data.link.LauncherKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeChannelsPlanTest {
    private fun item(uid: String, title: String = uid) =
        LauncherItem(uid, LauncherKind.MOVIE, title, "", "https://img/$uid.jpg", DeepLink(DeepLinkKind.MOVIE, uid))

    private fun published(rowId: Long, item: LauncherItem, rank: Int) = PublishedProgram(rowId, item.uid, item.fingerprint(rank))

    @Test
    fun unchangedRowTouchesNothing() {
        val a = item("a")
        val b = item("b")
        val plan = planPrograms(listOf(published(1, a, 0), published(2, b, 1)), listOf(a, b))
        assertTrue(plan.delete.isEmpty() && plan.insert.isEmpty() && plan.update.isEmpty())
    }

    @Test
    fun addsRemovesAndUpdates() {
        val kept = item("kept")
        val plan = planPrograms(
            listOf(published(1, item("gone"), 0), published(2, kept, 1)),
            listOf(item("new"), kept.copy(title = "Nouveau titre")),
        )
        assertEquals(listOf(1L), plan.delete)
        assertEquals(listOf("new"), plan.insert.map { it.value.uid })
        assertEquals(listOf(2L), plan.update.map { it.first })
    }

    @Test
    fun movedCardIsUpdatedAndDuplicatesRemoved() {
        val a = item("a")
        val b = item("b")
        val plan = planPrograms(listOf(published(1, a, 0), published(2, a, 0), published(3, b, 1)), listOf(b, a))
        assertEquals(listOf(2L), plan.delete)
        assertEquals(setOf(1L, 3L), plan.update.map { it.first }.toSet())
    }

    @Test
    fun saverWaitsFromLastActionOrFromWhenItBecameAllowed() {
        val minute = 60_000L
        assertEquals(10 * minute, saverDelayMs(lastActivityAt = 0, armedAt = 0, minutes = 10, now = 0))
        assertEquals(4 * minute, saverDelayMs(lastActivityAt = 0, armedAt = 0, minutes = 10, now = 6 * minute))
        assertEquals("déjà échu", 0L, saverDelayMs(lastActivityAt = 0, armedAt = 0, minutes = 10, now = 99 * minute))
        assertEquals("film terminé sans toucher la télécommande", 10 * minute, saverDelayMs(0, armedAt = 90 * minute, minutes = 10, now = 90 * minute))
    }
}
