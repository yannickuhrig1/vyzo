package fr.yannickuhrig.vyzo.data.remote

import fr.yannickuhrig.vyzo.util.Log
import fr.yannickuhrig.vyzo.data.sync.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

sealed interface RemoteEvent {
    data class PlaylistReceived(val playlist: RemotePlaylist) : RemoteEvent
    /** [selective] : seuls les éléments cochés sur le téléphone sont présents, le reste de la TV est gardé. */
    data class ConfigReceived(val json: String, val playlists: Int, val selective: Boolean = false) : RemoteEvent
    /** Identifiants du compte Vyzo saisis sur le téléphone, pour connecter la TV. */
    data class AccountReceived(val email: String, val password: String) : RemoteEvent
}

/** Où en est la réception, du point de vue du téléphone qui interroge /api/status. */
enum class RemotePhase { WAITING, PENDING_CONFIRM, REJECTED, LOADING }

/**
 * Petit serveur HTTP local, actif uniquement pendant que l'écran « Configurer depuis le téléphone » est ouvert.
 * Le téléphone (navigateur ou appli Vyzo) y envoie une playlist ou toute une configuration.
 * Chaque session a son propre jeton, présent dans le QR code : sans lui, toutes les requêtes sont refusées.
 */
class ConfigReceiver(
    private val deviceName: String,
    private val syncState: () -> SyncState,
    /** Renvoie le nombre de playlists d'une sauvegarde valide, ou null si le JSON est invalide. */
    private val inspectConfig: (String) -> Int?,
    private val ipProvider: () -> String? = { LanAddress.ipv4() },
    private val preferredPort: Int = DEFAULT_PORT,
    /** Écran « Se connecter depuis le téléphone » : seule la connexion au compte y est traitée, le reste est refusé. */
    private val accountOnly: Boolean = false,
) {
    val token: String = newToken()

    private val _url = MutableStateFlow<String?>(null)
    /** Adresse à mettre dans le QR code (null tant que le serveur n'écoute pas). */
    val url: StateFlow<String?> = _url.asStateFlow()

    private val _noNetwork = MutableStateFlow(false)
    val noNetwork: StateFlow<Boolean> = _noNetwork.asStateFlow()

    private val _events = MutableSharedFlow<RemoteEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<RemoteEvent> = _events.asSharedFlow()

    @Volatile var phase: RemotePhase = RemotePhase.WAITING
        private set
    @Volatile var acceptedAt = 0L
        private set

    /** Résultat de la connexion au compte Vyzo demandée depuis le téléphone (null = en cours ou pas demandée). */
    @Volatile private var accountResult: Pair<Boolean, String>? = null
    @Volatile private var accountPending = false

    fun setAccountResult(ok: Boolean, message: String) {
        accountResult = ok to message
        accountPending = false
    }

    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null

    val isRunning: Boolean get() = server?.isClosed == false

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            val ip = ipProvider()
            if (ip == null) {
                _noNetwork.value = true
                return@launch
            }
            val ss = try {
                ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(preferredPort)) }
            } catch (e: IOException) {
                ServerSocket(0)
            }
            server = ss
            _url.value = QrContent.deviceUrl(ip, ss.localPort, token)
            while (isActive && !ss.isClosed) {
                val client = try { ss.accept() } catch (e: IOException) { break }
                launch { runCatching { handle(client) }.onFailure { Log.w(TAG, "requête", it) } }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        scope?.cancel()
        scope = null
        _url.value = null
    }

    /** Appelé par l'interface quand une playlist ou une configuration commence à être chargée. */
    fun markLoading() {
        acceptedAt = System.currentTimeMillis()
        phase = RemotePhase.LOADING
    }

    fun markRejected() {
        phase = RemotePhase.REJECTED
    }

    // ------------------------------------------------------------------ HTTP

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private class Response(val code: Int, val type: String, val body: ByteArray)

    private fun handle(socket: Socket) = socket.use { s ->
        s.soTimeout = 15_000
        val input = BufferedInputStream(s.getInputStream())
        val req = readRequest(input) ?: return@use
        write(s.getOutputStream(), route(req))
    }

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (head.size() > MAX_HEADER) return null
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 2) return null
        val headers = lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':', "").trim() }
        val target = parts[1]
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY) return Request(parts[0], "__too_large__", emptyMap(), headers, ByteArray(0))
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return Request(parts[0].uppercase(), target.substringBefore('?'), parseQuery(target.substringAfter('?', "")), headers, body)
    }

    private fun parseQuery(q: String): Map<String, String> =
        if (q.isBlank()) emptyMap() else q.split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }

    private fun json(code: Int, ok: Boolean, error: String? = null, extra: Map<String, String> = emptyMap()): Response {
        val o = buildJsonObject {
            put("ok", ok)
            error?.let { put("error", it) }
            extra.forEach { (k, v) -> put(k, v) }
        }
        return Response(code, "application/json; charset=utf-8", o.toString().toByteArray())
    }

    private fun html(code: Int, page: String) = Response(code, "text/html; charset=utf-8", page.toByteArray())

    private fun route(r: Request): Response {
        if (r.path == "__too_large__") return json(413, false, "Données trop volumineuses")
        if (r.method == "OPTIONS") return Response(204, "text/plain", ByteArray(0))
        if (r.path == "/favicon.ico") return Response(404, "text/plain", ByteArray(0))
        val key = r.query["k"] ?: r.headers["x-vyzo-key"] ?: r.headers["x-zapette-key"]
        if (key != token) {
            return if (r.path == "/" && r.method == "GET") html(403, WebPages.expired())
            else json(403, false, "Code invalide ou expiré : scanne à nouveau le QR code de la TV")
        }
        return when {
            r.method == "GET" && (r.path == "/" || r.path == "/index.html") -> html(200, if (accountOnly) WebPages.accountForm(token, deviceName) else WebPages.form(token, deviceName))
            r.method == "GET" && r.path == "/compte" -> html(200, WebPages.accountForm(token, deviceName))
            r.method == "POST" && r.path == "/api/account" -> receiveAccount(r)
            r.method == "GET" && r.path == "/api/info" -> json(200, true, extra = mapOf("app" to "Vyzo", "device" to deviceName))
            r.method == "GET" && r.path == "/api/status" -> status()
            accountOnly && r.method == "POST" && (r.path == "/api/playlist" || r.path == "/api/config" || r.path == "/api/selection") ->
                json(409, false, "Cette TV attend la connexion au compte : scanne son QR code avec l'appareil photo du téléphone")
            r.method == "POST" && r.path == "/api/playlist" -> receivePlaylist(r)
            r.method == "POST" && r.path == "/api/config" -> receiveConfig(r)
            r.method == "POST" && r.path == "/api/selection" -> receiveConfig(r, selective = true)
            else -> json(404, false, "Introuvable")
        }
    }

    private fun receivePlaylist(r: Request): Response {
        val text = r.body.decodeToString()
        val p = runCatching { lenient.decodeFromString(RemotePlaylist.serializer(), text) }.getOrNull()
            ?: return json(400, false, "Requête invalide")
        p.validate()?.let { return json(400, false, it) }
        markLoading()
        _events.tryEmit(RemoteEvent.PlaylistReceived(p))
        return json(200, true)
    }

    private fun receiveConfig(r: Request, selective: Boolean = false): Response {
        val text = r.body.decodeToString()
        val count = inspectConfig(text) ?: return json(400, false, "Ce n'est pas une configuration Vyzo valide")
        phase = RemotePhase.PENDING_CONFIRM
        // Le téléphone suit maintenant l'import, plus la connexion au compte qui a pu le précéder.
        accountResult = null
        accountPending = false
        _events.tryEmit(RemoteEvent.ConfigReceived(text, count, selective))
        return json(200, true, extra = mapOf("pending" to "true"))
    }

    private fun receiveAccount(r: Request): Response {
        val o = runCatching { lenient.parseToJsonElement(r.body.decodeToString()) as? kotlinx.serialization.json.JsonObject }.getOrNull()
            ?: return json(400, false, "Requête invalide")
        val email = (o["email"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
        val password = (o["password"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        if (!email.contains('@') || password.isEmpty()) return json(400, false, "Adresse e-mail et mot de passe obligatoires")
        accountResult = null
        accountPending = true
        _events.tryEmit(RemoteEvent.AccountReceived(email, password))
        return json(200, true)
    }

    private fun status(): Response {
        if (accountPending || accountResult != null) {
            val res = accountResult
            val (state, message) = when {
                res == null -> "loading" to "Connexion de la TV au compte…"
                res.first -> "done" to res.second
                else -> "error" to res.second
            }
            return json(200, true, extra = mapOf("state" to state, "message" to message))
        }
        val sync = syncState()
        val (state, message) = when (phase) {
            RemotePhase.WAITING -> "waiting" to "En attente d'une configuration"
            RemotePhase.PENDING_CONFIRM -> "pending" to "Accepte l'import sur l'écran de la TV"
            RemotePhase.REJECTED -> "rejected" to "Import refusé sur la TV"
            RemotePhase.LOADING -> when {
                sync.running -> "loading" to sync.message.ifBlank { "Chargement…" }
                sync.lastFinished >= acceptedAt && sync.error != null -> "error" to "Échec du chargement : ${sync.error}"
                sync.lastFinished >= acceptedAt -> "done" to "C'est prêt sur la TV !"
                else -> "loading" to "Préparation…"
            }
        }
        return json(200, true, extra = mapOf("state" to state, "message" to message))
    }

    private fun write(out: OutputStream, r: Response) {
        val reason = when (r.code) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 413 -> "Payload Too Large"
            else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 ${r.code} $reason\r\n")
            append("Content-Type: ${r.type}\r\n")
            append("Content-Length: ${r.body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Content-Type, X-Vyzo-Key\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(r.body)
        out.flush()
    }

    companion object {
        const val DEFAULT_PORT = 8642
        private const val TAG = "VyzoRemote"
        private const val MAX_HEADER = 32 * 1024
        private const val MAX_BODY = 8 * 1024 * 1024
        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        fun newToken(): String {
            val r = SecureRandom()
            return (1..6).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
        }
    }
}
