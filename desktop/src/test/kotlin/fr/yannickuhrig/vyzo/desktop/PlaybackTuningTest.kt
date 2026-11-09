package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.player.AUDIO_DELAY_CHOICES
import fr.yannickuhrig.vyzo.player.AudioLeveler
import fr.yannickuhrig.vyzo.player.SleepTimer
import fr.yannickuhrig.vyzo.player.ZapPacer
import fr.yannickuhrig.vyzo.player.audioDelayLabel
import fr.yannickuhrig.vyzo.player.bestRefreshRate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

private const val RATE = 48_000
private const val CHUNK_FRAMES = RATE / 50

class AudioLevelerTest {
    /** Passe [seconds] de sinus stéréo dans [leveler] et renvoie la dernière seconde, canaux entrelacés. */
    private fun run(leveler: AudioLeveler, seconds: Int, left: Double, right: Double = left): ShortArray {
        val last = ArrayList<Short>(RATE * 2)
        val total = RATE * seconds
        var frame = 0
        while (frame < total) {
            val chunk = ShortArray(CHUNK_FRAMES * 2) { i ->
                val amplitude = if (i % 2 == 0) left else right
                (amplitude * 32767 * sin(2 * PI * 440 * (frame + i / 2) / RATE)).toInt().toShort()
            }
            leveler.process(chunk, chunk.size)
            if (frame >= total - RATE) chunk.forEach { last.add(it) }
            frame += CHUNK_FRAMES
        }
        return last.toShortArray()
    }

    private fun rms(samples: ShortArray, channel: Int? = null): Double {
        val picked = samples.filterIndexed { i, _ -> channel == null || i % 2 == channel }
        return sqrt(picked.sumOf { (it / 32768.0) * (it / 32768.0) } / picked.size)
    }

    @Test
    fun `une chaine faible remonte`() {
        val out = rms(run(AudioLeveler(RATE, 2), 12, 0.02))
        assertTrue("sortie $out", out > 0.014 * 3)
    }

    @Test
    fun `une chaine forte descend`() {
        val out = rms(run(AudioLeveler(RATE, 2), 12, 0.9))
        assertTrue("sortie $out", out < 0.636 / 3)
    }

    @Test
    fun `deux chaines differentes finissent au meme niveau`() {
        val quiet = rms(run(AudioLeveler(RATE, 2), 15, 0.05))
        val loud = rms(run(AudioLeveler(RATE, 2), 15, 0.4))
        assertTrue("$quiet contre $loud", maxOf(quiet, loud) / minOf(quiet, loud) < 1.26) // 2 dB
    }

    @Test
    fun `le souffle n'est pas amplifie`() {
        val out = rms(run(AudioLeveler(RATE, 2), 10, 0.0005))
        assertTrue("sortie $out", out <= 0.0005 / sqrt(2.0) * 1.1)
    }

    @Test
    fun `un pic soudain ne deborde pas`() {
        val leveler = AudioLeveler(RATE, 2)
        run(leveler, 12, 0.02) // gain au maximum
        val burst = ShortArray(CHUNK_FRAMES * 2) { i -> (32767 * sin(2 * PI * 440 * (i / 2) / RATE)).toInt().toShort() }
        val source = burst.copyOf()
        leveler.process(burst, burst.size)
        burst.indices.forEach { i ->
            assertTrue("signe inversé en $i", burst[i].toInt() == 0 || source[i].toInt() == 0 || (burst[i] > 0) == (source[i] > 0))
        }
    }

    @Test
    fun `l'image stereo est conservee`() {
        val out = run(AudioLeveler(RATE, 2), 10, 0.2, 0.1)
        assertEquals(2.0, rms(out, 0) / rms(out, 1), 0.1)
    }

    @Test
    fun `seuls les echantillons demandes sont touches`() {
        val samples = ShortArray(100) { 10_000 }
        AudioLeveler(RATE, 2).process(samples, 50)
        assertTrue(samples.drop(50).all { it.toInt() == 10_000 })
    }
}

class RefreshRateTest {
    private val tv = listOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)

    @Test
    fun `film en 24 i par s`() = assertEquals(24f, bestRefreshRate(tv, 24f))

    @Test
    fun `film NTSC, 24 Hz ne convient pas a 23,976`() = assertEquals(23.976f, bestRefreshRate(tv, 23.976f))

    @Test
    fun `25 i par s preferent 50 Hz`() = assertEquals(50f, bestRefreshRate(tv, 25f))

    @Test
    fun `30 i par s preferent 60 Hz`() = assertEquals(60f, bestRefreshRate(tv, 30f))

    @Test
    fun `59,94 ne prend pas 60`() = assertEquals(59.94f, bestRefreshRate(tv, 59.94f))

    @Test
    fun `une frequence annoncee arrondie reste reconnue`() = assertEquals(23.976f, bestRefreshRate(tv, 23.98f))

    @Test
    fun `sans multiple exact on ne change rien`() = assertNull(bestRefreshRate(listOf(60f), 25f))

    @Test
    fun `frequence de la video inconnue`() = assertNull(bestRefreshRate(tv, 0f))
}

class ZapPacerTest {
    @Test
    fun `une pression isolee ouvre tout de suite`() {
        val pacer = ZapPacer(350)
        assertEquals(0L, pacer.waitBeforeOpening(1_000))
        assertEquals(0L, pacer.waitBeforeOpening(5_000))
    }

    @Test
    fun `en rafale on attend le calme`() {
        val pacer = ZapPacer(350)
        assertEquals(0L, pacer.waitBeforeOpening(1_000))
        assertEquals(350L, pacer.waitBeforeOpening(1_200))
        assertEquals(350L, pacer.waitBeforeOpening(1_400))
    }

    @Test
    fun `la pause suivante repart de zero`() {
        val pacer = ZapPacer(350)
        pacer.waitBeforeOpening(1_000)
        pacer.waitBeforeOpening(1_200)
        assertEquals(0L, pacer.waitBeforeOpening(1_550))
    }
}

class AudioDelayChoicesTest {
    @Test
    fun `zero et les extremes sont proposes`() {
        assertTrue(0L in AUDIO_DELAY_CHOICES)
        assertEquals(-1000L, AUDIO_DELAY_CHOICES.first())
        assertEquals(1000L, AUDIO_DELAY_CHOICES.last())
    }

    @Test
    fun `libelles`() {
        assertEquals("Aucun", audioDelayLabel(0))
        assertEquals("+150 ms (son retardé)", audioDelayLabel(150))
        assertEquals("−150 ms (son avancé)", audioDelayLabel(-150))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    private class Fixture(scope: TestScope) {
        var stops = 0
        val events = ArrayList<SleepTimer.Event>()
        val timer = SleepTimer(scope.backgroundScope, now = { scope.testScheduler.currentTime }, onExpire = { stops++ })

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { timer.events.toList(events) }
        }
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `previent une minute avant puis arrete`() = runTest {
        val f = Fixture(this)
        f.timer.start(15)
        advance(14 * 60_000L - 1)
        assertTrue(f.events.isEmpty())
        advance(1)
        assertEquals(listOf(SleepTimer.Event.ENDING_SOON), f.events)
        assertEquals(1, f.timer.remainingMinutes())
        advance(60_000L)
        assertEquals(listOf(SleepTimer.Event.ENDING_SOON, SleepTimer.Event.EXPIRED), f.events)
        assertNull(f.timer.endsAt.value)
    }

    @Test
    fun `l'ecran se ferme avant l'arret du lecteur`() = runTest {
        val f = Fixture(this)
        f.timer.start(15)
        advance(15 * 60_000L)
        assertEquals(0, f.stops)
        advance(500)
        assertEquals(1, f.stops)
    }

    @Test
    fun `annuler ne stoppe rien`() = runTest {
        val f = Fixture(this)
        f.timer.start(15)
        f.timer.cancel()
        advance(20 * 60_000L)
        assertEquals(0, f.stops)
        assertNull(f.timer.endsAt.value)
    }

    @Test
    fun `relancer remplace le minuteur en cours`() = runTest {
        val f = Fixture(this)
        f.timer.start(15)
        advance(10 * 60_000L)
        f.timer.start(30)
        advance(25 * 60_000L)
        assertEquals(0, f.stops)
        advance(5 * 60_000L + 500)
        assertEquals(1, f.stops)
    }

    @Test
    fun `les minutes restantes sont arrondies au-dessus`() = runTest {
        val f = Fixture(this)
        assertNull(f.timer.remainingMinutes())
        f.timer.start(15)
        assertEquals(15, f.timer.remainingMinutes())
        advance(30_000)
        assertEquals(15, f.timer.remainingMinutes())
        advance(30_000)
        assertEquals(14, f.timer.remainingMinutes())
        assertTrue(abs((f.timer.endsAt.value ?: 0L) - 15 * 60_000L) < 1)
    }
}
