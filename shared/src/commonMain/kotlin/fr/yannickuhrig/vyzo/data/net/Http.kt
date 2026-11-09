package fr.yannickuhrig.vyzo.data.net

import fr.yannickuhrig.vyzo.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code pour ${url.redactCredentials()}")

private val passwordParam = Regex("(password=)[^&]*")
private val usernameParam = Regex("(username=)[^&]*")
private val macAddress = Regex("(?i)\\b([0-9a-f]{2}[:%3A-]{1,3}[0-9a-f]{2}[:%3A-]{1,3}[0-9a-f]{2})[:%3A-]{1,3}[0-9a-f]{2}[:%3A-]{1,3}[0-9a-f]{2}[:%3A-]{1,3}[0-9a-f]{2}\\b")
private val bearer = Regex("(?i)(bearer\\s+)[A-Za-z0-9._-]+")
private val tokenParam = Regex("(?i)((?:token|play_token|access_token|refresh_token|sn|device_id2?|signature)[\"=:]+\\s*\"?)[A-Za-z0-9._%-]{6,}")
private val pathCredentials = Regex("(/(?:live|movie|series|timeshift)/)[^/]+/[^/]+/")
// Forme courte des listes M3U Xtream : http://serveur:port/<identifiant>/<mot de passe>/<numéro de chaîne>
private val shortPathCredentials = Regex("(https?://[^/\\s]+/)[^/\\s]+/[^/\\s]+/(\\d+(?:\\.\\w+)?)(?=[\\s?#\"']|$)")

/** Masque tout ce qui permettrait d'utiliser l'abonnement : identifiants, mots de passe, MAC, jetons. */
fun String.redactCredentials(): String = this
    .replace(passwordParam, "$1***")
    .replace(usernameParam, "$1***")
    .replace(macAddress) { "${it.groupValues[1]}:**:**:**" }
    .replace(bearer, "$1***")
    .replace(tokenParam, "$1***")
    .replace(pathCredentials, "$1***/***/")
    .replace(shortPathCredentials, "$1***/***/$2")

object Http {
    const val DEFAULT_UA = "VLC/3.0.21 LibVLC/3.0.21"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Client pour les flux longs (enregistrements) : pas de délai global. */
    val streamingClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(30, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    }

    fun request(url: String, userAgent: String, headers: Map<String, String> = emptyMap()): Request =
        Request.Builder().url(url).apply {
            header("User-Agent", userAgent.ifBlank { DEFAULT_UA })
            headers.forEach { (k, v) -> header(k, v) }
        }.build()

    /** Exécute une requête de manière annulable et passe la réponse (déjà vérifiée 2xx) au bloc. */
    suspend fun <T> execute(client: OkHttpClient = this.client, request: Request, block: (Response) -> T): T {
        val started = System.currentTimeMillis()
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            DiagLog.error("HTTP", "${request.method} ${request.url} → échec", e)
            throw e
        }
        DiagLog.log("HTTP", "${request.method} ${request.url} → ${response.code} (${System.currentTimeMillis() - started} ms)")
        return withContext(Dispatchers.IO) {
            response.use {
                if (!it.isSuccessful) throw HttpStatusException(it.code, request.url.toString())
                block(it)
            }
        }
    }

    suspend fun getString(url: String, userAgent: String, headers: Map<String, String> = emptyMap()): String =
        execute(request = request(url, userAgent, headers)) { it.body!!.string() }

    /** Ouvre le corps en détectant un éventuel fichier .gz (sans Content-Encoding). */
    fun maybeGunzip(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(4)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024)) else buffered
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, _, _ -> response.close() }
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
