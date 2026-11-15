package fr.yannickuhrig.vyzo.cast

import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.net.redactCredentials
import fr.yannickuhrig.vyzo.data.remote.LanAddress
import fr.yannickuhrig.vyzo.data.repo.StreamSpec
import fr.yannickuhrig.vyzo.diag.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Relais du Cast : le Chromecast lit le flux à travers le téléphone.
 *
 * Le récepteur standard de Google ne peut ni envoyer le User-Agent ou le Referer qu'attend le fournisseur, ni lire du HLS
 * servi sans en-têtes CORS (le cas de presque tous les serveurs IPTV). Le relais va chercher le flux comme le ferait le
 * lecteur du téléphone, ajoute ces en-têtes, réécrit les listes HLS pour que chaque morceau passe aussi par lui, et fait
 * suivre les requêtes Range (avance rapide dans un film).
 *
 * Chaque flux publié reçoit un jeton aléatoire : sans lui, le relais ne sert rien. Ce n'est pas un proxy ouvert du réseau
 * local, et l'adresse du fournisseur (avec les identifiants) ne quitte pas le téléphone en clair.
 */
class CastRelay(
    private val client: OkHttpClient = Http.streamingClient,
    private val hostProvider: () -> String? = { LanAddress.ipv4() },
) {
    private class Entry(val spec: StreamSpec) {
        /**
         * Adresses trouvées dans les listes HLS de ce flux, par identifiant opaque. Le relais ne sert qu'elles : on ne
         * peut pas lui faire télécharger une adresse de son choix, même en connaissant le jeton (il circule sur le Wi-Fi).
         * Les plus anciennes sont oubliées (un direct HLS ajoute des morceaux sans fin).
         */
        val links = object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_LINKS
        }
    }

    private class Target(val token: String, val spec: StreamSpec, val url: String)

    private val entries = LinkedHashMap<String, Entry>()
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    private val random = SecureRandom()

    private val _lastUpstreamError = MutableStateFlow<Int?>(null)

    /** Dernier refus du fournisseur (code HTTP), pour expliquer un échec de lecture (limite de connexions…). */
    val lastUpstreamError: StateFlow<Int?> = _lastUpstreamError

    val isRunning: Boolean @Synchronized get() = server?.isClosed == false

    /**
     * Rend l'adresse à donner au Chromecast pour [spec], ou null si le téléphone n'est pas sur un réseau local.
     * Les flux publiés avant restent servis un moment (le Chromecast finit ses requêtes en cours au changement de chaîne).
     */
    @Synchronized
    fun publish(spec: StreamSpec): String? {
        val host = hostProvider() ?: return null
        val ss = ensureStarted()
        val token = ByteArray(16).also(random::nextBytes).toByteString().base64Url().trimEnd('=')
        entries[token] = Entry(spec)
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        _lastUpstreamError.value = null
        return "http://$host:${ss.localPort}/c/$token/m/${fileName(spec.url)}"
    }

    /** Arrête le relais et oublie tous les flux publiés. */
    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        pool?.shutdownNow()
        pool = null
        entries.clear()
    }

    private fun ensureStarted(): ServerSocket {
        server?.takeIf { !it.isClosed }?.let { return it }
        val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(0)) }
        // Nombre de connexions servies en même temps borné : au-delà, la connexion est fermée tout de suite.
        val executor = ThreadPoolExecutor(
            0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, SynchronousQueue(),
        ) { r -> Thread(r, "cast-relay").apply { isDaemon = true } }
        server = ss
        pool = executor
        // Boucle d'accueil sur son propre fil : elle ne prend pas une des places réservées aux connexions.
        Thread({
            while (!ss.isClosed) {
                val socket = try { ss.accept() } catch (e: IOException) { break }
                runCatching { executor.execute { serve(socket) } }.onFailure { runCatching { socket.close() } }
            }
        }, "cast-relay-accept").apply { isDaemon = true }.start()
        return ss
    }

    @Synchronized
    private fun lookup(token: String): Entry? = entries[token]

    // ------------------------------------------------------------------ HTTP

    private fun serve(socket: Socket) {
        try {
            socket.use { s ->
                // Requête lue en temps et en taille bornés : un client lent ou bavard ne garde pas une place du relais.
                s.soTimeout = HEADER_READ_TIMEOUT_MS
                val deadline = System.currentTimeMillis() + HEADER_DEADLINE_MS
                val input = BufferedInputStream(s.getInputStream())
                val out = BufferedOutputStream(s.getOutputStream(), BUFFER)
                var bytes = 0
                val requestLine = readLine(input, deadline) ?: return
                bytes += requestLine.length
                // Seul « Range » sert ; lignes, octets et durée sont bornés.
                var range: String? = null
                var count = 0
                while (true) {
                    val line = readLine(input, deadline) ?: break
                    if (line.isEmpty()) break
                    bytes += line.length
                    if (++count > MAX_HEADER_LINES || bytes > MAX_HEADER_BYTES) {
                        writeHead(out, 431, listOf("Content-Length" to "0"))
                        out.flush()
                        return
                    }
                    val i = line.indexOf(':')
                    if (i > 0 && line.substring(0, i).trim().equals("range", ignoreCase = true)) range = line.substring(i + 1).trim()
                }
                s.soTimeout = READ_TIMEOUT_MS
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                when {
                    method == "OPTIONS" -> writeHead(out, 204, emptyList())
                    method != "GET" && method != "HEAD" -> writeHead(out, 405, listOf("Content-Length" to "0"))
                    else -> {
                        val target = resolve(parts[1])
                        if (target == null) writeHead(out, 404, listOf("Content-Length" to "0"))
                        else proxy(target, method == "HEAD", range, out)
                    }
                }
                out.flush()
            }
        } catch (_: IOException) {
            // Le Chromecast a fermé la connexion (changement de chaîne, avance rapide) : rien à signaler.
        }
    }

    /** /c/<jeton>/m/<nom> = flux publié ; /c/<jeton>/u/<adresse en base64>/<nom> = adresse tirée d'une liste HLS. */
    private fun resolve(rawPath: String): Target? {
        val seg = rawPath.substringBefore('?').split('/')
        if (seg.size < 5 || seg[1] != "c") return null
        val token = seg[2]
        val entry = lookup(token) ?: return null
        return when (seg[3]) {
            "m" -> Target(token, entry.spec, entry.spec.url)
            "u" -> synchronized(entry.links) { entry.links[seg[4]] }?.let { Target(token, entry.spec, it) }
            else -> null
        }
    }

    private fun proxy(target: Target, headOnly: Boolean, range: String?, out: OutputStream) {
        val request = Http.request(target.url, target.spec.userAgent, target.spec.headers).newBuilder()
            // Corps transmis tel quel : la longueur et les plages annoncées au Chromecast doivent rester exactes.
            .header("Accept-Encoding", "identity")
            .apply { if (range != null) header("Range", range) }
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            DiagLog.error("Cast", "relais ${target.url.redactCredentials()} → échec", e)
            writeHead(out, 502, listOf("Content-Length" to "0"))
            return
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                DiagLog.log("Cast", "relais ${target.url.redactCredentials()} → ${resp.code}")
                _lastUpstreamError.value = resp.code
                writeHead(out, resp.code, listOf("Content-Length" to "0"))
                return
            }
            val body = resp.body ?: run { writeHead(out, 502, listOf("Content-Length" to "0")); return }
            val stream = BufferedInputStream(body.byteStream(), BUFFER)
            val type = resp.header("Content-Type").orEmpty()
            val finalUrl = resp.request.url.toString()
            if (resp.code == 200 && isPlaylist(type, stream)) {
                val text = readLimited(stream, MAX_PLAYLIST_BYTES).toString(Charsets.UTF_8)
                val bytes = HlsRewriter.rewrite(text, finalUrl) { subPath(target.token, it) ?: it }.toByteArray(Charsets.UTF_8)
                writeHead(
                    out, 200,
                    listOf(
                        "Content-Type" to "application/vnd.apple.mpegurl",
                        "Content-Length" to bytes.size.toString(),
                        "Cache-Control" to "no-cache",
                    ),
                )
                if (!headOnly) out.write(bytes)
            } else {
                val passed = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges")
                    .mapNotNull { name -> resp.header(name)?.let { name to it } }
                writeHead(out, resp.code, passed)
                if (!headOnly) stream.copyTo(out, BUFFER)
            }
        }
    }

    private fun subPath(token: String, absoluteUrl: String): String? {
        val entry = lookup(token) ?: return null
        // Même adresse, même identifiant : une liste de direct rechargée garde des adresses stables pour le récepteur.
        val id = absoluteUrl.encodeUtf8().sha256().hex().take(24)
        synchronized(entry.links) { entry.links[id] = absoluteUrl }
        return "/c/$token/u/$id/${fileName(absoluteUrl)}"
    }

    private fun writeHead(out: OutputStream, code: Int, headers: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\n")
        (headers + CORS + ("Connection" to "close")).forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    companion object {
        private const val MAX_ENTRIES = 4
        private const val MAX_LINKS = 4096
        private const val MAX_HEADER_LINES = 64
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val HEADER_READ_TIMEOUT_MS = 5_000
        private const val HEADER_DEADLINE_MS = 10_000L
        /** Le Chromecast ouvre quelques connexions à la fois (liste, morceaux, piste audio) : large marge. */
        private const val MAX_CONNECTIONS = 32
        private const val BUFFER = 64 * 1024
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024

        private val CORS = listOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
            "Access-Control-Allow-Headers" to "Range, Content-Type, Accept, Origin",
            "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges, Content-Type",
        )

        /** Dernier élément du chemin, gardé pour son extension : certains récepteurs s'en servent pour deviner le format. */
        internal fun fileName(url: String): String {
            val last = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty()
            return last.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "flux" }
        }

        /** Liste HLS : annoncée comme telle, ou commençant par #EXTM3U (beaucoup de serveurs IPTV répondent text/plain). */
        internal fun isPlaylist(contentType: String, stream: BufferedInputStream): Boolean {
            val t = contentType.lowercase()
            if ("mpegurl" in t) return true
            if (t.startsWith("video/") || t.startsWith("audio/")) return false
            stream.mark(16)
            val head = ByteArray(10)
            var n = 0
            while (n < head.size) {
                val r = stream.read(head, n, head.size - n)
                if (r < 0) break
                n += r
            }
            stream.reset()
            val text = String(head, 0, n, Charsets.UTF_8).removePrefix("﻿")
            return text.startsWith("#EXTM3U")
        }

        private fun readLimited(input: InputStream, max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(BUFFER)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
                if (out.size() > max) throw IOException("liste HLS trop longue")
            }
            return out.toByteArray()
        }

        /** Une ligne d'en-tête ; [deadline] (heure limite) est vérifiée à chaque octet, pas seulement entre deux lignes. */
        private fun readLine(input: InputStream, deadline: Long): String? {
            val sb = StringBuilder()
            while (true) {
                if (System.currentTimeMillis() > deadline) throw IOException("requête trop lente")
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                if (sb.length > MAX_HEADER_BYTES) throw IOException("en-tête trop long")
                sb.append(b.toChar())
            }
        }

        private fun reason(code: Int) = when (code) {
            200 -> "OK"
            204 -> "No Content"
            206 -> "Partial Content"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            416 -> "Range Not Satisfiable"
            431 -> "Request Header Fields Too Large"
            502 -> "Bad Gateway"
            else -> "Status"
        }
    }
}

/** Réécrit une liste HLS pour que chaque adresse (variantes, morceaux, clés, pistes) passe par le relais. */
object HlsRewriter {
    private val uriAttribute = Regex("""URI="([^"]*)"""")

    fun rewrite(playlist: String, baseUrl: String, map: (String) -> String): String {
        val base = baseUrl.toHttpUrlOrNull()
        fun relay(ref: String): String {
            val abs = base?.resolve(ref.trim())?.toString() ?: return ref
            return map(abs)
        }
        return playlist.lineSequence().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> uriAttribute.replace(line) { m ->
                    val ref = m.groupValues[1]
                    // Clés « skd:// », « data: »… : pas une adresse web, laissée telle quelle.
                    if (ref.contains("://") && !ref.startsWith("http")) m.value
                    else if (ref.startsWith("data:")) m.value
                    else "URI=\"${relay(ref)}\""
                }
                else -> relay(t)
            }
        }
    }
}
