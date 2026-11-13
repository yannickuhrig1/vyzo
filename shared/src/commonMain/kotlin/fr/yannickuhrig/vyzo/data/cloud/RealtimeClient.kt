package fr.yannickuhrig.vyzo.data.cloud

import fr.yannickuhrig.vyzo.util.Log
import fr.yannickuhrig.vyzo.diag.DiagLog
import fr.yannickuhrig.vyzo.data.remote.CastCommand
import fr.yannickuhrig.vyzo.data.remote.ScheduleSyncEvent
import fr.yannickuhrig.vyzo.data.remote.HistoryClearEvent
import fr.yannickuhrig.vyzo.util.str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Session utilisée pour s'abonner : jeton d'accès et identifiant du compte. */
data class RealtimeAuth(val accessToken: String, val userId: String)

/**
 * Notifications instantanées du compte Vyzo (Supabase Realtime, protocole Phoenix sur WebSocket).
 *
 * L'appareil s'abonne aux changements des tables synchronisées pour son compte (les règles d'accès du serveur
 * ne lui transmettent que ses lignes). Le canal est privé et propre au compte ([topicFor]) : seul un jeton de ce
 * compte peut y lire ou y diffuser (règles sur `realtime.messages`, voir `supabase/migrations`). Il ne lit pas le contenu des notifications : chacune déclenche une synchro
 * ordinaire ([onChange]), qui applique la règle « la modification la plus récente l'emporte ». À chaque
 * (re)connexion réussie, [onChange] est aussi appelé pour rattraper ce qui a changé pendant la coupure.
 */
class RealtimeClient(
    baseUrl: String,
    private val apiKey: String,
    client: OkHttpClient,
    private val scope: CoroutineScope,
    private val tables: List<String>,
    /** Jeton valide et compte, ou null si plus personne n'est connecté. */
    private val auth: suspend () -> RealtimeAuth?,
    private val onChange: (table: String, record: JsonObject?) -> Unit,
    private val onCastCommand: ((CastCommand) -> Unit)? = null,
    private val onScheduleSync: ((ScheduleSyncEvent) -> Unit)? = null,
    private val onHistoryClear: ((HistoryClearEvent) -> Unit)? = null,
    /** Contenus retirés de l'historique d'un profil (site du compte) : profil, clés de contenu. */
    private val onHistoryRemove: ((String, List<String>) -> Unit)? = null,
    private val heartbeatMs: Long = HEARTBEAT_MS,
    private val tokenRefreshMs: Long = TOKEN_REFRESH_MS,
) {
    constructor(
        baseUrl: String,
        apiKey: String,
        client: OkHttpClient,
        scope: CoroutineScope,
        tables: List<String>,
        auth: suspend () -> RealtimeAuth?,
        onChange: (table: String) -> Unit,
        heartbeatMs: Long = HEARTBEAT_MS,
        tokenRefreshMs: Long = TOKEN_REFRESH_MS,
    ) : this(
        baseUrl = baseUrl,
        apiKey = apiKey,
        client = client,
        scope = scope,
        tables = tables,
        auth = auth,
        onChange = { table, _ -> onChange(table) },
        onCastCommand = null,
        onScheduleSync = null,
        onHistoryClear = null,
        heartbeatMs = heartbeatMs,
        tokenRefreshMs = tokenRefreshMs,
    )

    enum class State { OFF, CONNECTING, ON }

    private val socketUrl: String = baseUrl.trimEnd('/')
        .replaceFirst(Regex("^http"), "ws") + "/realtime/v1/websocket?apikey=" + URLEncoder.encode(apiKey, "UTF-8") + "&vsn=1.0.0"
    private val http = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val ref = AtomicInteger(0)

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var wanted = false
    @Volatile private var socket: WebSocket? = null
    /** Canal du compte connecté, celui de la dernière connexion. */
    @Volatile private var topic: String? = null
    private var loop: Job? = null

    /** Ouvre la connexion (et la rouvre en cas de coupure) jusqu'à [stop]. */
    @Synchronized
    fun start() {
        wanted = true
        if (loop?.isActive == true) return
        loop = scope.launch { run() }
    }

    @Synchronized
    fun stop() {
        wanted = false
        loop?.cancel()
        loop = null
        socket?.close(1000, "arrêt")
        socket = null
        _state.value = State.OFF
    }

    private suspend fun run() {
        var backoff = MIN_BACKOFF_MS
        while (scope.isActive && wanted) {
            val a = runCatching { auth() }.getOrNull()
            if (a == null) {
                _state.value = State.OFF
                return
            }
            _state.value = State.CONNECTING
            val closed = CompletableDeferred<Unit>()
            val joined = CompletableDeferred<Boolean>()
            val joinRef = ref.incrementAndGet().toString()
            val channel = topicFor(a.userId)
            topic = channel
            val ws = http.newWebSocket(Request.Builder().url(socketUrl).build(), Listener(a, joinRef, joined, closed))
            socket = ws
            val heartbeat = scope.launch {
                while (isActive) {
                    delay(heartbeatMs)
                    ws.send(message("phoenix", "heartbeat", buildJsonObject {}))
                }
            }
            // Le jeton d'accès expire au bout d'une heure : on transmet le nouveau avant.
            val refresh = scope.launch {
                while (isActive) {
                    delay(tokenRefreshMs)
                    val fresh = runCatching { auth() }.getOrNull() ?: break
                    ws.send(message(channel, "access_token", buildJsonObject { put("access_token", fresh.accessToken) }))
                }
            }
            val ok = joined.await()
            if (ok) {
                backoff = MIN_BACKOFF_MS
                _state.value = State.ON
                onChange("*", null)
            }
            closed.await()
            heartbeat.cancel()
            refresh.cancel()
            if (socket === ws) socket = null
            if (!wanted) break
            _state.value = State.CONNECTING
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        _state.value = State.OFF
    }

    private inner class Listener(
        private val a: RealtimeAuth,
        private val joinRef: String,
        private val joined: CompletableDeferred<Boolean>,
        private val closed: CompletableDeferred<Unit>,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val config = buildJsonObject {
                putJsonObject("broadcast") { put("self", false); put("ack", false) }
                putJsonObject("presence") { put("key", ""); put("enabled", false) }
                putJsonArray("postgres_changes") {
                    tables.forEach { t ->
                        addJsonObject {
                            put("event", "*")
                            put("schema", "public")
                            put("table", t)
                            put("filter", "user_id=eq.${a.userId}")
                        }
                    }
                }
                put("private", true)
            }
            webSocket.send(
                message(
                    topicFor(a.userId), "phx_join",
                    buildJsonObject {
                        put("config", config)
                        put("access_token", a.accessToken)
                    },
                    ref = joinRef, joinRef = joinRef,
                ),
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
            val topic = m.str("topic")
            val event = m.str("event")
            val payload = m["payload"] as? JsonObject
            when {
                event == "phx_reply" && m.str("ref") == joinRef -> {
                    val ok = payload?.str("status") == "ok"
                    if (!ok) {
                        DiagLog.log("Temps réel", "abonnement refusé : ${payload?.get("response")}")
                        webSocket.close(1000, "abonnement refusé")
                    }
                    joined.complete(ok)
                }
                event == "broadcast" -> {
                    val p = payload?.get("payload") as? JsonObject ?: payload
                    val type = p?.str("type")
                    if (type == "cast_command") {
                        val cmdObj = p.get("command") as? JsonObject
                        if (cmdObj != null) {
                            runCatching {
                                val cmd = json.decodeFromJsonElement(CastCommand.serializer(), cmdObj)
                                onCastCommand?.invoke(cmd)
                            }
                        }
                    } else if (type == "schedule_sync") {
                        val cmdObj = p.get("command") as? JsonObject
                        if (cmdObj != null) {
                            runCatching {
                                val ev = json.decodeFromJsonElement(ScheduleSyncEvent.serializer(), cmdObj)
                                onScheduleSync?.invoke(ev)
                            }
                        }
                    } else if (type == "history_remove") {
                        // Pas « history_clear » : une ancienne version, qui ne connaît pas les clés, viderait tout l'historique.
                        val cmdObj = p.get("command") as? JsonObject
                        val profileId = cmdObj?.str("profileId").orEmpty()
                        val keys = (cmdObj?.get("contentKeys") as? kotlinx.serialization.json.JsonArray)
                            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { k -> k.isNotBlank() } }.orEmpty()
                        if (profileId.isNotBlank() && keys.isNotEmpty()) onHistoryRemove?.invoke(profileId, keys)
                    } else if (type == "history_clear") {
                        val cmdObj = p.get("command") as? JsonObject
                        if (cmdObj != null) {
                            runCatching {
                                val ev = json.decodeFromJsonElement(HistoryClearEvent.serializer(), cmdObj)
                                onHistoryClear?.invoke(ev)
                            }
                        }
                    }
                }
                event == "postgres_changes" -> {
                    val data = payload?.get("data") as? JsonObject
                    val table = data?.str("table").orEmpty()
                    val record = (data?.get("record") as? JsonObject) ?: (data?.get("new") as? JsonObject)
                    onChange(table.ifBlank { "*" }, record)
                }
                event == "system" && payload?.str("status") == "error" -> {
                    // Abonnement aux changements refusé, ou jeton expiré : on repart de zéro.
                    DiagLog.log("Temps réel", "erreur : ${payload.str("message")}")
                    webSocket.close(1000, "erreur")
                }
                topic == topicFor(a.userId) && (event == "phx_error" || event == "phx_close") -> webSocket.close(1000, event)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            joined.complete(false)
            closed.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.d(TAG, "connexion temps réel perdue", t)
            DiagLog.error("Temps réel", "connexion perdue", t)
            joined.complete(false)
            closed.complete(Unit)
        }
    }

    /** Diffuse une commande Cast vers les autres appareils du compte. */
    fun broadcastCast(command: CastCommand): Boolean {
        val ws = socket ?: return false
        val cmdJson = json.encodeToJsonElement(CastCommand.serializer(), command) as JsonObject
        val payload = buildJsonObject {
            put("type", "broadcast")
            put("event", "broadcast")
            putJsonObject("payload") {
                put("type", "cast_command")
                put("command", cmdJson)
            }
        }
        val msg = message(topic ?: return false, "broadcast", payload)
        return ws.send(msg)
    }

    /** Diffuse un événement de purge d'historique vers les autres appareils du compte. */
    fun broadcastHistoryClear(event: HistoryClearEvent): Boolean {
        val ws = socket ?: return false
        val evJson = json.encodeToJsonElement(HistoryClearEvent.serializer(), event) as JsonObject
        val payload = buildJsonObject {
            put("type", "broadcast")
            put("event", "broadcast")
            putJsonObject("payload") {
                put("type", "history_clear")
                put("command", evJson)
            }
        }
        val msg = message(topic ?: return false, "broadcast", payload)
        return ws.send(msg)
    }

    private fun message(topic: String, event: String, payload: JsonObject, ref: String = this.ref.incrementAndGet().toString(), joinRef: String? = null): String =
        buildJsonObject {
            put("topic", topic)
            put("event", event)
            put("payload", payload)
            put("ref", ref)
            if (joinRef != null) put("join_ref", joinRef)
        }.toString()

    companion object {
        private const val TAG = "VyzoRealtime"
        /** Canal privé du compte : `realtime.topic()` vaut alors `user:<uid>` côté serveur. */
        fun topicFor(userId: String): String = "realtime:user:$userId"
        private const val HEARTBEAT_MS = 25_000L
        /** Le fournisseur de jetons renouvelle ceux qui expirent dans moins d'un quart d'heure. */
        private const val TOKEN_REFRESH_MS = 10 * 60_000L
        private const val MIN_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 120_000L
    }
}
