package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.trakt.TraktMedia
import fr.yannickuhrig.vyzo.data.trakt.TraktScrobbler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TraktScrobblerTest {
    private val sent = ArrayList<String>()
    private val scrobbler = TraktScrobbler { action, media, progress -> sent += "$action ${media.title} ${progress.toInt()}" }
    private val film = TraktMedia("Dune", 2021, 438631)
    private val other = TraktMedia("Alien", 1979, 348)

    @Test
    fun aSameStateIsNotSentTwice() = runBlocking {
        scrobbler.play(film, 1.0)
        scrobbler.play(film, 2.0)
        scrobbler.pause(3.0)
        scrobbler.pause(4.0)
        scrobbler.play(film, 5.0)
        scrobbler.stop(90.0)
        assertEquals(listOf("start Dune 1", "pause Dune 3", "start Dune 5", "stop Dune 90"), sent)
    }

    @Test
    fun anotherTitleEndsThePreviousOne() = runBlocking {
        scrobbler.play(film, 10.0)
        scrobbler.play(other, 0.0)
        assertEquals(listOf("start Dune 10", "stop Dune 10", "start Alien 0"), sent)
    }

    @Test
    fun theLastKnownProgressClosesThePreviousTitle() = runBlocking {
        scrobbler.play(film, 10.0)
        scrobbler.note(97.0)
        scrobbler.play(other, 0.0)
        assertEquals(listOf("start Dune 10", "stop Dune 97", "start Alien 0"), sent)
    }

    @Test
    fun stopWithoutPlaybackSendsNothing() = runBlocking {
        scrobbler.stop(50.0)
        scrobbler.pause(50.0)
        assertEquals(emptyList<String>(), sent)
    }
}
