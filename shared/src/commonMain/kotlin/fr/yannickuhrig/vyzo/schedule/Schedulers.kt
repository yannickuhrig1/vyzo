package fr.yannickuhrig.vyzo.schedule

import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.db.RecordingEntity
import fr.yannickuhrig.vyzo.data.db.RecordingRuleEntity
import fr.yannickuhrig.vyzo.data.db.RecordingStatus
import fr.yannickuhrig.vyzo.data.db.ReminderEntity
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.platform.Platform
import fr.yannickuhrig.vyzo.data.remote.ScheduleSyncEvent
import fr.yannickuhrig.vyzo.util.TextNorm
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

sealed interface AppEvent {
    data class ReminderDue(val reminder: ReminderEntity) : AppEvent
    data class Toast(val message: String) : AppEvent
}

class AppEvents {
    private val _flow = MutableSharedFlow<AppEvent>(extraBufferCapacity = 8)
    val flow: SharedFlow<AppEvent> = _flow.asSharedFlow()
    fun emit(e: AppEvent) { _flow.tryEmit(e) }
}

/**
 * Rappels de programmes. La base de données est commune ; le déclenchement est propre à chaque plateforme
 * (alarme exacte sur Android, minuterie tant que Vyzo tourne sur PC).
 */
abstract class ReminderScheduler(
    protected val db: AppDatabase,
    protected val settings: SettingsStore,
) {
    /** Programme le déclenchement du rappel à [at]. */
    protected abstract fun arm(r: ReminderEntity, at: Long)

    protected abstract fun disarm(id: Long)

    suspend fun isSet(ch: ChannelEntity, p: ProgramEntity): Boolean = db.reminders().find(ch.uid, p.start) != null

    /** Ajoute ou retire un rappel. Renvoie true si le rappel est maintenant actif. */
    suspend fun toggle(ch: ChannelEntity, p: ProgramEntity): Boolean {
        val existing = db.reminders().find(ch.uid, p.start)
        if (existing != null) {
            remove(existing.id)
            return false
        }
        val r = ReminderEntity(channelUid = ch.uid, channelName = ch.name, title = p.title, start = p.start, stop = p.stop)
        val id = db.reminders().insert(r)
        schedule(r.copy(id = id))
        return true
    }

    suspend fun remove(id: Long) {
        disarm(id)
        db.reminders().delete(id)
    }

    suspend fun schedule(r: ReminderEntity) {
        val before = settings.get().reminderMinutesBefore * 60_000L
        val at = (r.start - before).coerceAtLeast(System.currentTimeMillis() + 2_000)
        arm(r, at)
    }

    suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        db.reminders().deleteBefore(now - 3600_000L)
        db.reminders().future(now).forEach { schedule(it) }
    }
}

/** Enregistrements trop nombreux en même temps pour l'abonnement. */
data class RecordingConflict(
    /** Enregistrements prévus ou en cours sur la même période (sans compter le nouveau). */
    val overlapping: List<RecordingEntity>,
    /** Connexions autorisées par l'abonnement (0 = inconnu). */
    val maxConnections: Int,
) {
    fun message(): String = buildString {
        append(if (overlapping.size == 1) "Un autre enregistrement a lieu" else "${overlapping.size} autres enregistrements ont lieu")
        append(" en même temps (").append(overlapping.joinToString(", ") { it.title }.take(80)).append(")")
        if (maxConnections > 0) append(" et ton abonnement n'autorise que ").append(maxConnections).append(" connexion").append(if (maxConnections > 1) "s" else "")
        append(".")
    }
}

/**
 * Enregistrements programmés depuis le guide ou immédiats.
 * La plateforme fournit le dossier, le déclenchement à l'heure dite et l'exécution (service Android, tâche de fond sur PC).
 */
abstract class RecordingScheduler(
    protected val db: AppDatabase,
    protected val settings: SettingsStore,
    protected val platform: Platform? = null,
) {
    /** Callback notifié quand une programmation est ajoutée, modifiée ou supprimée. */
    var onScheduleChanged: ((ScheduleSyncEvent) -> Unit)? = null

    /** Détermine si cet appareil doit exécuter les enregistrements locaux. */
    suspend fun isHost(): Boolean {
        val s = settings.get()
        return s.resolvesRecordingHost(isDesktop = platform?.isDesktop ?: false, isTv = platform?.isTv ?: false)
    }

    abstract val directory: File

    /** Déclenchera [startNow] à [at]. */
    protected abstract fun armAt(id: Long, at: Long)

    protected abstract fun disarmAt(id: Long)

    /** Démarre l'enregistrement tout de suite. Renvoie false si le système l'a refusé. */
    abstract fun startNow(id: Long, retryWithAlarm: Boolean = true): Boolean

    /** Arrête un enregistrement en cours. */
    protected abstract fun stopNow(id: Long)

    /** Supprime le fichier d'un enregistrement (Android : aussi les documents d'un dossier choisi). */
    protected open fun deleteFile(path: String) {
        runCatching { File(path).delete() }
    }

    /**
     * Crée la destination d'un nouvel enregistrement nommé [name].
     * Renvoie son chemin (ou adresse) et une remarque à afficher (vide si tout va bien).
     */
    open suspend fun newRecordingPath(name: String): Pair<String, String> = directory.resolve(name).absolutePath to ""

    /** Ouvre la destination en ajout. */
    open fun openAppend(path: String): OutputStream = File(path).let { it.parentFile?.mkdirs(); FileOutputStream(it, true) }

    /** Place libre à destination, -1 si inconnue. */
    open fun freeSpace(path: String): Long = File(path).absoluteFile.parentFile?.usableSpace ?: -1L

    /** Le fichier d'un enregistrement existe encore. */
    open fun fileExists(path: String): Boolean = File(path).exists()

    /** Adresse lisible par le lecteur. */
    open fun playUri(path: String): String = File(path).toPath().toUri().toString()

    suspend fun findFor(ch: ChannelEntity, p: ProgramEntity): RecordingEntity? =
        db.recordings().activeOnChannel(ch.uid, p.start, p.stop).firstOrNull { it.covers(p.start, p.stop, marginMs()) }

    private suspend fun marginMs(): Long = settings.get().let { recordingMarginMs(it.recPaddingBeforeMin, it.recPaddingAfterMin) }

    suspend fun ruleFor(ch: ChannelEntity, p: ProgramEntity): RecordingRuleEntity? = db.recordingRules().find(ch.uid, titleKey(p.title))

    /**
     * Vérifie si un nouvel enregistrement sur [ch] entre [start] et [stop] dépasse le nombre de connexions de l'abonnement.
     * Renvoie null s'il n'y a pas de conflit.
     */
    suspend fun conflictFor(ch: ChannelEntity, start: Long, stop: Long, ignoreId: Long = 0): RecordingConflict? {
        val others = db.recordings().overlapping(start, stop).filter { it.id != ignoreId }
        if (others.isEmpty()) return null
        val max = db.playlists().get(ch.playlistId)?.maxConnections ?: 0
        // Même playlist uniquement : chaque abonnement a sa propre limite.
        val samePlaylist = others.filter { it.channelUid.substringBefore(':') == ch.uid.substringBefore(':') }
        val limit = if (max > 0) max else 1
        return if (samePlaylist.size + 1 > limit) RecordingConflict(samePlaylist, max) else null
    }

    /** Programme l'enregistrement d'une émission (avec marges). Renvoie l'id. */
    suspend fun recordProgram(ch: ChannelEntity, p: ProgramEntity, ruleId: Long = 0): Long {
        findFor(ch, p)?.let { return it.id }
        val s = settings.get()
        // Émission déjà commencée : l'enregistrement part de maintenant, pas de l'heure prévue (marge comprise).
        val start = maxOf(p.start - s.recPaddingBeforeMin * 60_000L, System.currentTimeMillis())
        return schedule(ch, p.title, start, p.stop + s.recPaddingAfterMin * 60_000L, ruleId)
    }

    suspend fun recordNow(ch: ChannelEntity, title: String, stop: Long): Long =
        schedule(ch, title, System.currentTimeMillis(), stop)

    suspend fun schedule(ch: ChannelEntity, title: String, start: Long, stop: Long, ruleId: Long = 0): Long {
        val r = RecordingEntity(channelUid = ch.uid, channelName = ch.name, title = title, start = start, stop = stop, ruleId = ruleId)
        val id = db.recordings().insert(r)
        arm(r.copy(id = id))
        onScheduleChanged?.invoke(
            ScheduleSyncEvent(
                action = "upsert",
                channelUid = ch.uid,
                channelName = ch.name,
                title = title,
                start = start,
                stop = stop,
                status = RecordingStatus.SCHEDULED.name,
                senderDeviceId = platform?.deviceModel,
            )
        )
        return id
    }

    // ---------------------------------------------------------------- Séries

    /** Enregistre toutes les diffusions de ce titre sur cette chaîne (actuelles et futures). Renvoie le nombre programmé. */
    suspend fun recordSeries(ch: ChannelEntity, p: ProgramEntity): Int {
        val key = titleKey(p.title)
        val rule = db.recordingRules().find(ch.uid, key)
            ?: RecordingRuleEntity(channelUid = ch.uid, channelName = ch.name, title = p.title, titleNorm = key)
                .let { it.copy(id = db.recordingRules().insert(it)) }
        if (p.stop > System.currentTimeMillis()) recordProgram(ch, p, rule.id)
        return applyRule(rule) + if (p.stop > System.currentTimeMillis()) 1 else 0
    }

    /** Arrête l'enregistrement de la série : la règle disparaît, les diffusions à venir sont annulées. */
    suspend fun deleteRule(ruleId: Long) {
        db.recordings().scheduledForRule(ruleId).forEach { cancel(it.id) }
        db.recordingRules().delete(ruleId)
    }

    /** Après chaque mise à jour du guide : programme les nouvelles diffusions des séries suivies. */
    suspend fun applyRules(): Int = db.recordingRules().all().sumOf { runCatching { applyRule(it) }.getOrDefault(0) }

    private suspend fun applyRule(rule: RecordingRuleEntity): Int {
        val ch = db.channels().get(rule.channelUid) ?: return 0
        if (ch.epgKey.isBlank()) return 0
        val now = System.currentTimeMillis()
        var n = 0
        db.programs().future(ch.epgKey, now, 500).filter { titleKey(it.title) == rule.titleNorm }.forEach { p ->
            // Déjà prévu, enregistré, ou annulé à la main : on n'y touche pas.
            val margin = marginMs()
            if (db.recordings().onChannel(ch.uid, p.start, p.stop).any { it.covers(p.start, p.stop, margin) }) return@forEach
            recordProgram(ch, p, rule.id)
            n++
        }
        return n
    }

    protected open suspend fun arm(r: RecordingEntity) {
        if (!isHost()) return
        if (r.start <= System.currentTimeMillis() + 1_000) startNow(r.id) else armAt(r.id, r.start)
    }

    suspend fun cancel(id: Long) {
        val r = db.recordings().get(id) ?: return
        disarmAt(id)
        when (r.status) {
            RecordingStatus.SCHEDULED -> db.recordings().setStatus(id, RecordingStatus.CANCELLED)
            RecordingStatus.RECORDING -> stopNow(id)
            else -> Unit
        }
        onScheduleChanged?.invoke(
            ScheduleSyncEvent(
                action = "cancel",
                channelUid = r.channelUid,
                channelName = r.channelName,
                title = r.title,
                start = r.start,
                stop = r.stop,
                status = RecordingStatus.CANCELLED.name,
                senderDeviceId = platform?.deviceModel,
            )
        )
    }

    suspend fun delete(id: Long) {
        val r = db.recordings().get(id) ?: return
        if (r.status == RecordingStatus.RECORDING || r.status == RecordingStatus.SCHEDULED) cancel(id)
        if (r.filePath.isNotBlank()) deleteFile(r.filePath)
        db.recordings().delete(id)
        onScheduleChanged?.invoke(
            ScheduleSyncEvent(
                action = "delete",
                channelUid = r.channelUid,
                channelName = r.channelName,
                title = r.title,
                start = r.start,
                stop = r.stop,
                senderDeviceId = platform?.deviceModel,
            )
        )
    }

    /**
     * Programmation venue d'un autre appareil du compte. L'appareil qui enregistre l'arme (ou l'annule) ;
     * les autres tiennent seulement leur liste à jour.
     */
    suspend fun applyRemote(ev: ScheduleSyncEvent) {
        val host = isHost()
        val existing = db.recordings().findActive(ev.channelUid, ev.start, ev.stop)
        val status = runCatching { RecordingStatus.valueOf(ev.status) }.getOrNull()
        when (ev.action) {
            "upsert" -> {
                if (existing == null) {
                    db.recordings().insert(
                        RecordingEntity(
                            channelUid = ev.channelUid, channelName = ev.channelName, title = ev.title,
                            start = ev.start, stop = ev.stop, status = status ?: RecordingStatus.SCHEDULED,
                        ),
                    )
                } else if (!host && status != null && status != existing.status) {
                    db.recordings().setStatus(existing.id, status, ev.error.orEmpty())
                }
                if (host) rescheduleAll()
            }
            "cancel" -> if (existing != null) {
                if (host) cancel(existing.id) else db.recordings().setStatus(existing.id, RecordingStatus.CANCELLED)
            }
            "delete" -> if (existing != null) {
                if (host) delete(existing.id) else db.recordings().delete(existing.id)
            }
            "status" -> if (existing != null && status != null) db.recordings().setStatus(existing.id, status, ev.error.orEmpty())
            "sync" -> if (host) rescheduleAll()
        }
    }

    suspend fun rescheduleAll() {
        if (!isHost()) return
        val now = System.currentTimeMillis()
        db.recordings().inProgress().forEach { r ->
            // Le processus a été arrêté pendant l'enregistrement : on reprend si l'émission n'est pas finie.
            if (r.stop > now) {
                db.recordings().setStatus(r.id, RecordingStatus.SCHEDULED)
                startNow(r.id)
            } else {
                db.recordings().setStatus(r.id, if (r.bytes > 0) RecordingStatus.DONE else RecordingStatus.FAILED, "Interrompu")
            }
        }
        db.recordings().scheduled(now).forEach { arm(it) }
    }

    companion object {
        /** Titre normalisé : les diffusions d'une même série sont reconnues malgré la casse, les accents et la ponctuation. */
        fun titleKey(title: String): String =
            TextNorm.search(title).replace(Regex("\\((s\\d+\\s*e\\d+|\\d+/\\d+|rediffusion|inedit)\\)"), " ")
                .replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}
