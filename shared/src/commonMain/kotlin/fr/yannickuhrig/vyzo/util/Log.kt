package fr.yannickuhrig.vyzo.util

import fr.yannickuhrig.vyzo.data.net.redactCredentials

/**
 * Journal minimal commun à Android et au PC.
 * Android branche [sink] sur android.util.Log ; Vyzo PC écrit dans la console et dans un fichier.
 * Les messages arrivent au [sink] déjà masqués ; pour une exception, le [sink] passe par [trace].
 */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    @Volatile
    var sink: (Level, String, String, Throwable?) -> Unit = { level, tag, msg, t ->
        System.err.println("$level/$tag: $msg")
        t?.let { System.err.println(trace(it)) }
    }

    fun d(tag: String, msg: String, t: Throwable? = null) = sink(Level.DEBUG, tag, msg.redactCredentials(), t)
    fun i(tag: String, msg: String, t: Throwable? = null) = sink(Level.INFO, tag, msg.redactCredentials(), t)
    fun w(tag: String, msg: String, t: Throwable? = null) = sink(Level.WARN, tag, msg.redactCredentials(), t)
    fun e(tag: String, msg: String, t: Throwable? = null) = sink(Level.ERROR, tag, msg.redactCredentials(), t)

    /** Pile d'appels de [t], masquée : les messages d'erreur réseau contiennent souvent l'URL du flux. */
    fun trace(t: Throwable): String = t.stackTraceToString().redactCredentials()
}
