package fr.yannickuhrig.vyzo.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalPlayersTest {
    @Test
    fun acceptsStreamAddresses() {
        assertEquals("http://h:8080/live/a/b/1.ts", ExternalPlayers.safeUrl(" http://h:8080/live/a/b/1.ts "))
        assertEquals("rtsp://h/x", ExternalPlayers.safeUrl("rtsp://h/x"))
        assertEquals("HTTPS://h/x.m3u8", ExternalPlayers.safeUrl("HTTPS://h/x.m3u8"))
    }

    @Test
    fun refusesOptionsAndOddSchemes() {
        // Venue d'une playlist piégée, cette valeur serait lue comme une option de mpv.
        assertNull(ExternalPlayers.safeUrl("--script=/tmp/x.lua"))
        assertNull(ExternalPlayers.safeUrl("-http://h/x"))
        assertNull(ExternalPlayers.safeUrl("javascript:alert(1)"))
        assertNull(ExternalPlayers.safeUrl(""))
    }
}
