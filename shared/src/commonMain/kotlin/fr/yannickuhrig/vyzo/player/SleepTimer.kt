package fr.yannickuhrig.vyzo.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Durées proposées par le minuteur de veille, en minutes. */
val SLEEP_TIMER_MINUTES = listOf(15, 30, 45, 60, 90, 120)

/** Minuteur de veille : il vit dans le conteneur pour continuer quand on zappe ou qu'on passe au mini-lecteur. */
class SleepTimer(
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val onExpire: () -> Unit,
) {
    enum class Event { ENDING_SOON, EXPIRED }

    private val _endsAt = MutableStateFlow<Long?>(null)

    /** Heure d'arrêt prévue, null si le minuteur est coupé. */
    val endsAt: StateFlow<Long?> = _endsAt.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 2)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var job: Job? = null

    fun start(minutes: Int) {
        cancel()
        val total = minutes * 60_000L
        _endsAt.value = now() + total
        job = scope.launch {
            if (total > WARNING_MS) {
                delay(total - WARNING_MS)
                _events.tryEmit(Event.ENDING_SOON)
                delay(WARNING_MS)
            } else {
                delay(total)
            }
            _endsAt.value = null
            // L'écran du lecteur se ferme d'abord : il enregistre la position, que l'arrêt effacerait.
            _events.tryEmit(Event.EXPIRED)
            delay(EXIT_GRACE_MS)
            onExpire()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _endsAt.value = null
    }

    /** Minutes restantes, arrondies au-dessus (jamais « 0 min » tant que le minuteur tourne). */
    fun remainingMinutes(): Int? = _endsAt.value?.let { end -> (((end - now()).coerceAtLeast(0) + 59_999L) / 60_000L).toInt() }

    private companion object {
        const val WARNING_MS = 60_000L
        const val EXIT_GRACE_MS = 500L
    }
}
