package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.prefs.AppSettings
import fr.yannickuhrig.vyzo.data.prefs.PlayerOrientation
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerOrientationTest {
    @Test
    fun theOptionCyclesThroughEveryOrientation() {
        assertEquals(PlayerOrientation.LANDSCAPE, PlayerOrientation.next(PlayerOrientation.AUTO))
        assertEquals(PlayerOrientation.PORTRAIT, PlayerOrientation.next(PlayerOrientation.LANDSCAPE))
        assertEquals(PlayerOrientation.AUTO, PlayerOrientation.next(PlayerOrientation.PORTRAIT))
    }

    @Test
    fun anUnknownValueBehavesLikeAutomatic() {
        assertEquals("Automatique", PlayerOrientation.label("???"))
        assertEquals(PlayerOrientation.AUTO, PlayerOrientation.next("???"))
    }

    @Test
    fun thePhoneIsFollowedByDefault() {
        assertEquals(PlayerOrientation.AUTO, AppSettings().playerOrientation)
    }
}
