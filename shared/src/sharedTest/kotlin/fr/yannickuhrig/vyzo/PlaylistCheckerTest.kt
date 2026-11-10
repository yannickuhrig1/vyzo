package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.repo.StreamSpec
import fr.yannickuhrig.vyzo.data.sync.CheckOutcome
import fr.yannickuhrig.vyzo.data.sync.PlaylistCheckState
import fr.yannickuhrig.vyzo.data.sync.PlaylistChecker
import fr.yannickuhrig.vyzo.data.sync.ProbeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PlaylistCheckerTest {
    private fun channel(uid: String, name: String = uid, hidden: Boolean = false) = ChannelEntity(
        uid = uid, playlistId = 1, remoteId = uid, name = name, nameNorm = name, matchKey = name, number = 1, logo = "",
        categoryUid = "c", epgId = "", epgKey = "", streamUrl = "", position = 0, syncToken = 1, hidden = hidden,
    )

    private fun check(
        list: List<ChannelEntity>,
        playing: () -> Boolean = { false },
        busy: () -> Boolean = { false },
        concurrency: AtomicInteger? = null,
        result: (String) -> ProbeResult,
    ): PlaylistCheckState = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val checker = PlaylistChecker(
            scope = scope, channels = { list },
            resolve = { StreamSpec(url = it.uid, userAgent = "", isLive = true) },
            isPlaying = playing, isConnectionBusy = { busy() },
            probe = { spec ->
                concurrency?.let { assertEquals("une seule connexion à la fois", 1, it.incrementAndGet()) }
                delay(1)
                concurrency?.decrementAndGet()
                result(spec.url)
            },
            pauseMs = 1,
        )
        checker.start("all")
        val end = withTimeout(10_000) { checker.state.first { !it.running } }
        scope.cancel()
        end
    }

    @Test
    fun listsDeadChannelsAndSkipsHiddenOnes() {
        val state = check(listOf(channel("a"), channel("b"), channel("c", hidden = true), channel("d"))) { if (it == "b") ProbeResult.DEAD else ProbeResult.OK }
        assertEquals(CheckOutcome.COMPLETE, state.outcome)
        assertEquals(3, state.total)
        assertEquals(3, state.checked)
        assertEquals(listOf("b"), state.dead.map { it.uid })
    }

    @Test
    fun opensOneStreamAtATime() {
        val inFlight = AtomicInteger()
        val state = check((1..20).map { channel("c$it") }, concurrency = inFlight) { ProbeResult.OK }
        assertEquals(20, state.checked)
    }

    @Test
    fun stopsWhenTheProviderRefuses() {
        val state = check((1..10).map { channel("c$it") }) { ProbeResult.REFUSED }
        assertEquals(CheckOutcome.PROVIDER_REFUSED, state.outcome)
        assertEquals(PlaylistChecker.MAX_REFUSALS, state.checked)
        assertTrue("un refus ne dit rien de la chaîne", state.dead.isEmpty())
    }

    @Test
    fun aLongFailureStreakIsNotBlamedOnTheChannels() {
        val list = listOf(channel("mort"), channel("bon")) + (1..30).map { channel("c$it") }
        val state = check(list) { if (it == "bon") ProbeResult.OK else ProbeResult.DEAD }
        assertEquals(CheckOutcome.TOO_MANY_FAILURES, state.outcome)
        assertEquals(listOf("mort"), state.dead.map { it.uid })
    }

    @Test
    fun givesWayToPlaybackAndOtherConnections() {
        assertEquals(CheckOutcome.PLAYBACK, check(listOf(channel("a")), playing = { true }) { ProbeResult.OK }.outcome)
        assertEquals(CheckOutcome.CONNECTION_BUSY, check(listOf(channel("a")), busy = { true }) { ProbeResult.OK }.outcome)
    }

    @Test
    fun emptyListEndsCleanly() {
        assertEquals(CheckOutcome.EMPTY, check(emptyList()) { ProbeResult.OK }.outcome)
    }
}
