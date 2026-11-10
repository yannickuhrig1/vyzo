package fr.yannickuhrig.vyzo.data.sync

import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.net.HttpStatusException
import fr.yannickuhrig.vyzo.data.repo.StreamSpec
import fr.yannickuhrig.vyzo.util.ChannelSeparator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

enum class ProbeResult {
    OK,
    DEAD,

    /** Le fournisseur refuse la connexion (limite atteinte, blocage) : cela ne dit rien de la chaîne. */
    REFUSED,

    /** Adresse qu'on ne sait pas interroger (rtsp, udp…). */
    UNKNOWN,
}

fun interface StreamProbe {
    suspend fun probe(spec: StreamSpec): ProbeResult
}

/** Ouvre le flux, lit ses premiers octets et referme aussitôt la connexion. */
class HttpStreamProbe : StreamProbe {
    private val client by lazy {
        Http.streamingClient.newBuilder().readTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    }

    override suspend fun probe(spec: StreamSpec): ProbeResult {
        if (!spec.url.startsWith("http://") && !spec.url.startsWith("https://")) return ProbeResult.UNKNOWN
        return try {
            Http.execute(client, Http.request(spec.url, spec.userAgent, spec.headers)) { response ->
                val received = response.body?.byteStream()?.read(ByteArray(BYTES_TO_READ)) ?: -1
                if (received > 0) ProbeResult.OK else ProbeResult.DEAD
            }
        } catch (e: HttpStatusException) {
            if (e.code in REFUSAL_CODES) ProbeResult.REFUSED else ProbeResult.DEAD
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ProbeResult.DEAD
        }
    }

    private companion object {
        const val BYTES_TO_READ = 4096
        val REFUSAL_CODES = setOf(401, 403, 429, 456, 458, 503)
    }
}

enum class CheckOutcome { COMPLETE, STOPPED, PLAYBACK, CONNECTION_BUSY, PROVIDER_REFUSED, TOO_MANY_FAILURES, BACKGROUND, EMPTY }

data class CheckedChannel(val uid: String, val name: String)

data class PlaylistCheckState(
    val running: Boolean = false,
    val checked: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val dead: List<CheckedChannel> = emptyList(),
    val outcome: CheckOutcome? = null,
)

/**
 * Vérifie les chaînes à la demande, une seule à la fois et avec une pause entre chaque :
 * un abonnement IPTV limite les connexions, et une rafale peut le faire bloquer.
 */
class PlaylistChecker(
    private val scope: CoroutineScope,
    private val channels: suspend (String) -> List<ChannelEntity>,
    private val resolve: suspend (ChannelEntity) -> StreamSpec,
    private val isPlaying: () -> Boolean,
    private val isConnectionBusy: suspend () -> Boolean,
    private val probe: StreamProbe = HttpStreamProbe(),
    private val pauseMs: Long = PAUSE_MS,
) {
    private val mutableState = MutableStateFlow(PlaylistCheckState())
    val state: StateFlow<PlaylistCheckState> = mutableState.asStateFlow()

    private var job: Job? = null

    fun start(groupKey: String) {
        if (job?.isActive == true) return
        mutableState.value = PlaylistCheckState(running = true)
        job = scope.launch { finish(run(groupKey)) }
    }

    fun stop(outcome: CheckOutcome = CheckOutcome.STOPPED) {
        if (job?.isActive != true) return
        job?.cancel()
        finish(outcome)
    }

    private fun finish(outcome: CheckOutcome) = mutableState.update { it.copy(running = false, current = "", outcome = outcome) }

    private suspend fun run(groupKey: String): CheckOutcome {
        val list = channels(groupKey).filterNot { it.hidden || ChannelSeparator.matches(it.name) }.distinctBy { it.uid }
        if (list.isEmpty()) return CheckOutcome.EMPTY
        mutableState.update { it.copy(total = list.size) }

        var refusals = 0
        val failureStreak = ArrayList<CheckedChannel>()
        list.forEachIndexed { index, channel ->
            if (index > 0) delay(pauseMs)
            if (isPlaying()) return CheckOutcome.PLAYBACK
            if (isConnectionBusy()) return CheckOutcome.CONNECTION_BUSY
            mutableState.update { it.copy(current = channel.name) }

            val result = try {
                probe.probe(resolve(channel))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ProbeResult.DEAD
            }
            when (result) {
                ProbeResult.DEAD -> {
                    val failed = CheckedChannel(channel.uid, channel.name)
                    failureStreak += failed
                    refusals = 0
                    mutableState.update { it.copy(dead = it.dead + failed) }
                }
                ProbeResult.REFUSED -> {
                    refusals++
                    failureStreak.clear()
                }
                ProbeResult.OK, ProbeResult.UNKNOWN -> {
                    refusals = 0
                    failureStreak.clear()
                }
            }
            mutableState.update { it.copy(checked = index + 1) }

            if (refusals >= MAX_REFUSALS) return CheckOutcome.PROVIDER_REFUSED
            if (failureStreak.size >= MAX_FAILURE_STREAK) {
                // Autant d'échecs de suite viennent plutôt d'une coupure ou d'un blocage : ces chaînes ne sont pas jugées.
                val suspect = failureStreak.map { it.uid }.toSet()
                mutableState.update { state -> state.copy(dead = state.dead.filterNot { it.uid in suspect }) }
                return CheckOutcome.TOO_MANY_FAILURES
            }
        }
        return CheckOutcome.COMPLETE
    }

    companion object {
        const val PAUSE_MS = 2_000L
        const val MAX_REFUSALS = 3
        const val MAX_FAILURE_STREAK = 15

        /** Temps moyen par chaîne, pause et réponse du serveur comprises. */
        const val AVERAGE_MS_PER_CHANNEL = 3_000L
    }
}
