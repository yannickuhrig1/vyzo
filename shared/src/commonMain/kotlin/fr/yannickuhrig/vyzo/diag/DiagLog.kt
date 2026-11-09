package fr.yannickuhrig.vyzo.diag

import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.net.redactCredentials
import fr.yannickuhrig.vyzo.platform.Platform
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal de diagnostic en mémoire (2000 dernières lignes) : échanges avec les portails Stalker et les serveurs
 * Xtream, erreurs de lecture, synchro. Les adresses MAC, jetons, identifiants et mots de passe sont masqués.
 * Activé dans Réglages → Diagnostic, puis exporté pour être joint à un signalement.
 */
object DiagLog {
    @Volatile var enabled: Boolean = false

    private const val MAX = 2000
    private val lines = ArrayDeque<String>()
    private val fmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.FRANCE)
    }

    fun log(tag: String, message: String) {
        if (!enabled) return
        val line = "${fmt.get()!!.format(Date())} [$tag] ${redact(message)}"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
    }

    fun error(tag: String, message: String, e: Throwable?) {
        if (!enabled) return
        log(tag, message + (e?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    fun dump(): String = synchronized(lines) { lines.joinToString("\n") }

    fun size(): Int = synchronized(lines) { lines.size }

    fun clear() = synchronized(lines) { lines.clear() }

    /** Masque tout ce qui permettrait d'utiliser l'abonnement. */
    fun redact(s: String): String = s.redactCredentials()

    /** Rapport complet : appareil, version, sources (sans identifiants), puis le journal. */
    suspend fun report(platform: Platform, db: AppDatabase): String = buildString {
        appendLine("Vyzo ${platform.appVersion} (${platform.appVersionCode})")
        appendLine("Appareil : ${platform.deviceModel} · ${platform.systemVersion}")
        appendLine("Généré le ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date())}")
        appendLine()
        appendLine("Playlists :")
        db.playlists().all().forEach { p ->
            appendLine(
                " - ${p.name} · ${p.type} · ${redact(p.url.substringBefore('?'))}" +
                    (if (p.lastError.isNotBlank()) " · erreur : ${redact(p.lastError)}" else "") +
                    (if (p.maxConnections > 0) " · connexions ${p.activeConnections}/${p.maxConnections}" else ""),
            )
        }
        appendLine()
        appendLine("Journal (${size()} lignes) :")
        append(dump().ifBlank { "(vide : active le journal détaillé dans les réglages, reproduis le problème, puis exporte)" })
    }
}
