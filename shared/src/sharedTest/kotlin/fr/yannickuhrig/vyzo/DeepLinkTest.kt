package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.link.DeepLink
import fr.yannickuhrig.vyzo.data.link.DeepLinkKind
import fr.yannickuhrig.vyzo.ui.common.ScreenIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkTest {
    @Test
    fun eachKindRoundTripsAnEncodedUid() {
        DeepLinkKind.entries.forEach { kind ->
            val link = DeepLink(kind, "3:M:ab c+d/é")
            assertEquals(link, DeepLink.parse(link.uri))
        }
    }

    @Test
    fun hostIsCaseInsensitiveButUidIsKept() {
        assertEquals(DeepLink(DeepLinkKind.MOVIE, "1:M:AbC"), DeepLink.parse("VYZO://Movie/1%3AM%3AAbC"))
    }

    @Test
    fun rejectsWhatIsNotAVyzoLink() {
        listOf(
            null, "", "vyzo://", "vyzo://play", "vyzo://play/", "vyzo://settings/1", "http://play/1", "vyzo://play/1/2",
            "vyzo://play/1?x=2", "vyzo://play/%20", "vyzo://play/%zz", "vyzo://play/" + "a".repeat(600),
        ).forEach { assertNull("accepté à tort : $it", DeepLink.parse(it)) }
    }

    @Test
    fun wakingKeyGoesNoFurtherUntilReleased() {
        val idle = ScreenIdle().apply { showSaver() }
        assertTrue(idle.onKey(isDown = true, audio = false))
        assertFalse(idle.saverShown.value)
        assertTrue("répétition de la touche maintenue", idle.onKey(isDown = true, audio = false))
        assertTrue(idle.onKey(isDown = false, audio = false))
        assertFalse("touche suivante", idle.onKey(isDown = true, audio = false))
    }

    @Test
    fun releaseAloneDoesNotSwallowTheNextKey() {
        val idle = ScreenIdle().apply { showSaver() }
        assertTrue(idle.onKey(isDown = false, audio = false))
        assertFalse(idle.onKey(isDown = true, audio = false))
    }

    @Test
    fun audioKeysPassThroughAndKeepTheSaver() {
        val idle = ScreenIdle().apply { showSaver() }
        assertFalse(idle.onKey(isDown = true, audio = true))
        assertTrue(idle.saverShown.value)
    }
}
