package fr.yannickuhrig.vyzo.remote

import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.cloud.CloudStore
import fr.yannickuhrig.vyzo.data.cloud.SealedSync
import fr.yannickuhrig.vyzo.data.cloud.UsageDay
import fr.yannickuhrig.vyzo.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Calendar

/** Ce qui est à l'écran, tiré de l'étiquette du lecteur : titre, précision (programme, épisode), nature. */
data class NowPlaying(val title: String, val detail: String, val kind: String)

/** Étiquettes du lecteur : `ch:<uid>`, `movie:<uid>`, `episode:<uid>`, `catchup:<uid>:<id>`, `rec:<id>`. */
suspend fun AppContainer.describePlaying(tag: String): NowPlaying {
    val now = System.currentTimeMillis()
    return when (tag.substringBefore(':')) {
        "ch" -> {
            val ch = db.channels().get(tag.substringAfter(':'))
            val program = ch?.epgKey?.takeIf { it.isNotBlank() }?.let { db.programs().nowFor(listOf(it), now).firstOrNull()?.title }
            NowPlaying(ch?.name ?: "Chaîne en direct", program.orEmpty(), KIND_LIVE)
        }
        "catchup" -> {
            val ch = db.channels().get(tag.substringAfter(':').substringBeforeLast(':'))
            NowPlaying(ch?.name ?: "Replay", "Replay", KIND_LIVE)
        }
        "movie" -> NowPlaying(db.movies().get(tag.substringAfter(':'))?.name ?: "Film", "", KIND_MOVIE)
        "episode" -> {
            val e = db.series().episode(tag.substringAfter(':'))
            val s = e?.let { db.series().get(it.seriesUid) }
            val detail = e?.let { "S${it.season} · É${it.episode}" + (it.title.takeIf { t -> t.isNotBlank() }?.let { t -> " · $t" } ?: "") }
            NowPlaying(s?.name ?: e?.title ?: "Épisode", detail.orEmpty(), KIND_EPISODE)
        }
        "rec" -> NowPlaying(tag.substringAfter(':').toLongOrNull()?.let { db.recordings().get(it)?.title } ?: "Enregistrement", "Enregistrement", KIND_OTHER)
        else -> NowPlaying("Lecture", "", KIND_OTHER)
    }
}

const val KIND_LIVE = "live"
const val KIND_MOVIE = "movie"
const val KIND_EPISODE = "episode"
const val KIND_OTHER = "other"

/**
 * Règles de temps d'écran d'un profil, posées depuis le site du compte. Heures « HH:MM » ; vides = pas de plage.
 * [dailyMinutes] = 0 : pas de durée maximale.
 */
@Serializable
data class ScreenTimeRule(
    val enabled: Boolean = true,
    val dailyMinutes: Int = 0,
    val from: String = "",
    val to: String = "",
    val weekendFrom: String = "",
    val weekendTo: String = "",
) {
    /** Raison du blocage, ou null si la lecture est permise. */
    fun blockReason(minuteOfDay: Int, weekend: Boolean, usedMs: Long): String? {
        if (!enabled) return null
        val (f, t) = if (weekend && weekendFrom.isNotBlank() && weekendTo.isNotBlank()) weekendFrom to weekendTo else from to to
        val start = minutes(f)
        val end = minutes(t)
        if (start != null && end != null && start != end) {
            val inside = if (start < end) minuteOfDay in start until end else minuteOfDay >= start || minuteOfDay < end
            if (!inside) return "Pas d'écran à cette heure-ci : c'est permis de $f à $t."
        }
        if (dailyMinutes > 0 && usedMs >= dailyMinutes * 60_000L) return "Le temps d'écran du jour (${label(dailyMinutes)}) est écoulé."
        return null
    }

    companion object {
        fun minutes(hhmm: String): Int? {
            val m = Regex("""(\d{1,2}):(\d{2})""").matchEntire(hhmm.trim()) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            return if (h in 0..24 && min in 0..59 && h * 60 + min <= 24 * 60) h * 60 + min else null
        }

        fun label(minutes: Int): String = when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0 -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"
        }
    }
}

/** Lecture arrêtée par les règles de temps d'écran. */
data class ScreenBlock(val profileId: String, val profileName: String, val reason: String)

/**
 * Temps passé devant Vyzo, par profil et par jour : lu par le site du compte (statistiques) et par les règles de temps
 * d'écran, qui arrêtent la lecture d'un profil hors de sa plage horaire ou au-delà de sa durée du jour (tous appareils).
 * Le code parental prolonge d'une heure.
 */
class UsageTracker(private val c: AppContainer, private val store: CloudStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var days: MutableList<UsageDay>? = null
    private var dirty = false
    private var lastFlush = 0L
    private var lastTick = 0L
    private var titleOf: Pair<String, NowPlaying>? = null
    private val unlockedUntil = HashMap<String, Long>()

    private val _blocked = MutableSharedFlow<ScreenBlock>(extraBufferCapacity = 1)
    val blocked: SharedFlow<ScreenBlock> = _blocked.asSharedFlow()

    fun start() {
        // Lecteur obtenu ici, sur le fil qui démarre l'appli, plutôt que dans une coroutine.
        val player = c.player
        c.scope.launch {
            while (true) {
                delay(TICK_MS)
                runCatching { tick() }.onFailure { Log.w(TAG, "temps d'écran", it) }
            }
        }
        // Au lancement d'une lecture, les règles sont vérifiées tout de suite ; à l'arrêt, le temps compté est gardé.
        c.scope.launch {
            player.tag.collect { tag ->
                if (tag != null) {
                    delay(1_500)
                    runCatching { enforce() }
                } else {
                    runCatching { flush() }
                }
            }
        }
    }

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        val previous = lastTick
        lastTick = now
        val tag = c.player.tag.value
        val playing = tag != null && c.player.current.value != null && c.player.status.value.isPlaying
        if (playing && previous > 0) {
            val profile = c.profiles.currentProfileId
            if (profile.isNotBlank()) add(profile, tag!!, (now - previous).coerceIn(0, TICK_MS * 2), now)
        }
        if (playing) enforce()
        if (dirty && now - lastFlush >= FLUSH_MS) flush()
    }

    private suspend fun add(profile: String, tag: String, ms: Long, now: Long) {
        val info = titleOf?.takeIf { it.first == tag }?.second ?: c.describePlaying(tag).also { titleOf = tag to it }
        val day = SealedSync.dayOf(now)
        val title = if (info.kind == KIND_LIVE || info.detail.isBlank() || info.kind == KIND_EPISODE) info.title else "${info.title} · ${info.detail}"
        mutex.withLock {
            val list = load()
            val i = list.indexOfFirst { it.profile == profile && it.day == day }
            val u = if (i >= 0) list[i] else UsageDay(profile, day)
            val next = u.copy(
                totalMs = u.totalMs + ms,
                liveMs = u.liveMs + if (info.kind == KIND_LIVE) ms else 0,
                movieMs = u.movieMs + if (info.kind == KIND_MOVIE) ms else 0,
                episodeMs = u.episodeMs + if (info.kind == KIND_EPISODE) ms else 0,
                otherMs = u.otherMs + if (info.kind == KIND_OTHER) ms else 0,
                items = (u.items + (title to (u.items[title] ?: 0) + ms)).let { items ->
                    // Pas plus de 60 titres par jour : les moins regardés partent.
                    if (items.size <= 60) items else items.entries.sortedByDescending { it.value }.take(60).associate { it.key to it.value }
                },
            )
            if (i >= 0) list[i] = next else list += next
            dirty = true
        }
    }

    private suspend fun load(): MutableList<UsageDay> = days ?: store.get().usage.toMutableList().also { days = it }

    suspend fun flush() {
        val snapshot = mutex.withLock {
            if (!dirty) return
            dirty = false
            val oldest = SealedSync.dayOf(System.currentTimeMillis() - SealedSync.USAGE_KEEP_DAYS * DAY_MS)
            load().apply { removeAll { it.day < oldest } }.toList()
        }
        lastFlush = System.currentTimeMillis()
        store.update { it.copy(usage = snapshot) }
    }

    /** Temps du jour pour ce profil, cet appareil et les autres compris. */
    suspend fun usedToday(profile: String, now: Long = System.currentTimeMillis()): Long {
        val day = SealedSync.dayOf(now)
        val here = mutex.withLock { load().firstOrNull { it.profile == profile && it.day == day }?.totalMs ?: 0 }
        val elsewhere = store.get().remoteUsage.filterKeys { it.startsWith("$profile|") && it.endsWith("|$day") }.values.sum()
        return here + elsewhere
    }

    suspend fun rule(profile: String): ScreenTimeRule? =
        store.get().screenTime[profile]?.let { runCatching { json.decodeFromString(ScreenTimeRule.serializer(), it) }.getOrNull() }

    /** Raison pour laquelle le profil actif ne peut pas regarder maintenant, ou null. */
    suspend fun blockReason(): ScreenBlock? {
        val profile = c.profiles.active.value ?: return null
        val now = System.currentTimeMillis()
        if ((unlockedUntil[profile.id] ?: 0) > now) return null
        val rule = rule(profile.id) ?: return null
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val weekday = cal.get(Calendar.DAY_OF_WEEK)
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val reason = rule.blockReason(minute, weekday == Calendar.SATURDAY || weekday == Calendar.SUNDAY, usedToday(profile.id, now))
            ?: return null
        return ScreenBlock(profile.id, profile.name, reason)
    }

    private suspend fun enforce() {
        if (c.player.current.value == null) return
        val block = blockReason() ?: return
        withContext(Dispatchers.Main) { c.player.stop() }
        _blocked.tryEmit(block)
    }

    /** Code parental juste : une heure de plus pour ce profil, sur cet appareil. */
    fun extend(profileId: String, pin: String): Boolean {
        if (!c.parental.checkPin(pin)) return false
        unlockedUntil[profileId] = System.currentTimeMillis() + EXTEND_MS
        return true
    }

    companion object {
        private const val TAG = "UsageTracker"
        private const val TICK_MS = 20_000L
        private const val FLUSH_MS = 3 * 60_000L
        private const val EXTEND_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
    }
}
