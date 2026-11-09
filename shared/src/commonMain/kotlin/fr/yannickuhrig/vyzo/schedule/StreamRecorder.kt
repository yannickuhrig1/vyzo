package fr.yannickuhrig.vyzo.schedule

import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.net.HttpStatusException
import fr.yannickuhrig.vyzo.data.net.await
import fr.yannickuhrig.vyzo.data.repo.StreamSpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Enregistre un flux (MPEG-TS continu ou HLS) dans un fichier jusqu'à [stopAt].
 * Les coupures réseau sont tolérées : on se reconnecte et on continue d'écrire à la suite.
 *
 * Garde-fous :
 * - si le serveur renvoie un fichier court au lieu d'un direct (vidéo « chaîne indisponible »…),
 *   on attend avant de se reconnecter et on abandonne s'il recommence en boucle ;
 * - on s'arrête quand il reste moins de [minFreeBytes] sur le disque.
 */
class StreamRecorder(
    private val spec: StreamSpec,
    /** Ouvre la destination en ajout (fichier de l'appli ou document d'un dossier choisi). */
    private val openOutput: () -> OutputStream,
    private val stopAt: Long,
    /** Place libre à destination en octets, -1 si inconnue (document d'un dossier choisi sur Android). */
    private val freeSpace: () -> Long = { -1L },
    private val minFreeBytes: Long = DEFAULT_MIN_FREE,
    private val reconnectDelayMs: Long = 2_000,
    private val onProgress: (Long) -> Unit,
) {
    companion object {
        const val DEFAULT_MIN_FREE = 512L * 1024 * 1024
        /** Une connexion qui se termine avant ce délai compte comme une fin anormale. */
        private const val SHORT_CONNECTION_MS = 20_000L
        private const val MAX_SHORT_ENDS = 8
        private const val SPACE_CHECK_EVERY = 16L * 1024 * 1024
    }

    constructor(
        spec: StreamSpec,
        out: File,
        stopAt: Long,
        minFreeBytes: Long = DEFAULT_MIN_FREE,
        reconnectDelayMs: Long = 2_000,
        onProgress: (Long) -> Unit,
    ) : this(
        spec,
        { out.parentFile?.mkdirs(); FileOutputStream(out, true) },
        stopAt,
        { out.absoluteFile.parentFile?.usableSpace ?: -1L },
        minFreeBytes,
        reconnectDelayMs,
        onProgress,
    )

    private var total = 0L
    private var nextSpaceCheck = 0L
    private val client = Http.streamingClient

    private fun written(n: Int) {
        total += n
        if (total >= nextSpaceCheck) {
            nextSpaceCheck = total + SPACE_CHECK_EVERY
            if (freeSpace() in 0 until minFreeBytes) {
                throw DiskFullException()
            }
        }
    }

    /** Arrêt volontaire : il ne reste plus assez de place sur le disque. */
    class DiskFullException : IOException("Espace disque insuffisant : enregistrement arrêté")

    private fun headers() = spec.headers

    suspend fun run() {
        openOutput().buffered(256 * 1024).use { os ->
            if (looksHls(spec.url)) recordHls(spec.url, os) else recordRaw(os)
            os.flush()
        }
    }

    private fun looksHls(url: String) = url.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.endsWith(".m3u") }

    private suspend fun recordRaw(os: OutputStream) {
        var failures = 0
        var shortEnds = 0
        while (System.currentTimeMillis() < stopAt) {
            currentCoroutineContext().ensureActive()
            val connectedAt = System.currentTimeMillis()
            try {
                val call = client.newCall(Http.request(spec.url, spec.userAgent, headers()))
                val job = currentCoroutineContext()[kotlinx.coroutines.Job]
                val handle = job?.invokeOnCompletion { call.cancel() }
                val resp = call.await()
                try {
                    if (!resp.isSuccessful) throw HttpStatusException(resp.code, spec.url)
                    val type = resp.header("Content-Type").orEmpty().lowercase()
                    if ("mpegurl" in type) {
                        resp.close()
                        recordHls(resp.request.url.toString(), os)
                        return
                    }
                    val input = resp.body!!.byteStream()
                    val buf = ByteArray(64 * 1024)
                    while (System.currentTimeMillis() < stopAt) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            os.write(buf, 0, n)
                            written(n)
                            onProgress(total)
                            failures = 0
                        }
                        if (!currentCoroutineContext().isActive) break
                    }
                } finally {
                    resp.close()
                    handle?.dispose()
                }
                // Le serveur a terminé la réponse alors que l'émission continue.
                if (System.currentTimeMillis() < stopAt) {
                    if (System.currentTimeMillis() - connectedAt < SHORT_CONNECTION_MS) {
                        shortEnds++
                        if (shortEnds >= MAX_SHORT_ENDS) {
                            throw LoopingStreamException()
                        }
                    } else {
                        shortEnds = 0
                    }
                    delay(reconnectDelayMs)
                }
            } catch (e: DiskFullException) {
                throw e
            } catch (e: LoopingStreamException) {
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                failures++
                if (failures > 40) throw IOException("Flux interrompu : ${e.message}")
                delay(reconnectDelayMs)
            }
        }
    }

    /** Le serveur renvoie sans cesse une vidéo courte au lieu du direct. */
    class LoopingStreamException :
        IOException("Le serveur ne renvoie pas de direct (vidéo courte en boucle) : enregistrement arrêté")

    private data class Segment(
        val uri: String,
        val keyMethod: String,
        val keyUri: String?,
        val iv: ByteArray?,
        val mapUri: String?,
    )

    private data class MediaPlaylist(
        val targetDuration: Int,
        val mediaSequence: Long,
        val segments: List<Segment>,
        val endList: Boolean,
    )

    private suspend fun fetchText(url: String): Pair<String, String> {
        val resp = client.newCall(Http.request(url, spec.userAgent, headers())).await()
        resp.use {
            if (!it.isSuccessful) throw HttpStatusException(it.code, url)
            return it.request.url.toString() to it.body!!.string()
        }
    }

    private suspend fun fetchBytes(url: String): ByteArray {
        val resp = client.newCall(Http.request(url, spec.userAgent, headers())).await()
        resp.use {
            if (!it.isSuccessful) throw HttpStatusException(it.code, url)
            return it.body!!.bytes()
        }
    }

    private fun resolve(base: String, ref: String): String =
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref else URI(base).resolve(ref.trim()).toString()

    private fun attr(line: String, name: String): String? =
        Regex("$name=(\"[^\"]*\"|[^,]*)").find(line)?.groupValues?.get(1)?.trim('"')

    private fun pickVariant(text: String, base: String): String {
        var best: String? = null
        var bestBw = -1L
        val lines = text.lines()
        for (i in lines.indices) {
            val l = lines[i].trim()
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val bw = attr(l, "BANDWIDTH")?.toLongOrNull() ?: 0
                val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: continue
                if (bw > bestBw) { bestBw = bw; best = uri }
            }
        }
        return resolve(base, best ?: throw IOException("Playlist HLS sans variante"))
    }

    private fun parseMedia(text: String, base: String): MediaPlaylist {
        var target = 6
        var seq = 0L
        var end = false
        var keyMethod = "NONE"
        var keyUri: String? = null
        var iv: ByteArray? = null
        var map: String? = null
        val segs = ArrayList<Segment>()
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-TARGETDURATION:") -> target = l.substringAfter(':').trim().toDoubleOrNull()?.toInt() ?: 6
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = l.substringAfter(':').trim().toLongOrNull() ?: 0
                l.startsWith("#EXT-X-ENDLIST") -> end = true
                l.startsWith("#EXT-X-KEY:") -> {
                    keyMethod = attr(l, "METHOD") ?: "NONE"
                    keyUri = attr(l, "URI")?.let { resolve(base, it) }
                    iv = attr(l, "IV")?.removePrefix("0x")?.removePrefix("0X")?.let { hex ->
                        hex.padStart(32, '0').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    }
                }
                l.startsWith("#EXT-X-MAP:") -> map = attr(l, "URI")?.let { resolve(base, it) }
                l.isNotEmpty() && !l.startsWith("#") -> segs += Segment(resolve(base, l), keyMethod, keyUri, iv, map)
            }
        }
        return MediaPlaylist(target, seq, segs, end)
    }

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun seqIv(seq: Long): ByteArray = ByteArray(16).also { b ->
        for (i in 0 until 8) b[15 - i] = (seq ushr (8 * i)).toByte()
    }

    private suspend fun recordHls(startUrl: String, os: OutputStream) {
        var mediaUrl = startUrl
        var lastSeq = Long.MIN_VALUE
        var initWritten: String? = null
        val keys = HashMap<String, ByteArray>()
        var failures = 0
        while (System.currentTimeMillis() < stopAt) {
            currentCoroutineContext().ensureActive()
            try {
                val (finalUrl, text) = fetchText(mediaUrl)
                if (text.contains("#EXT-X-STREAM-INF")) {
                    mediaUrl = pickVariant(text, finalUrl)
                    continue
                }
                val pl = parseMedia(text, finalUrl)
                if (lastSeq == Long.MIN_VALUE) {
                    // Premier passage : on part du direct (3 derniers segments), sauf playlist terminée.
                    lastSeq = if (pl.endList) pl.mediaSequence - 1 else pl.mediaSequence + pl.segments.size - 4
                } else if (pl.mediaSequence + pl.segments.size < lastSeq - 10) {
                    lastSeq = pl.mediaSequence - 1 // le serveur a redémarré sa numérotation
                }
                for ((i, seg) in pl.segments.withIndex()) {
                    val seq = pl.mediaSequence + i
                    if (seq <= lastSeq) continue
                    if (System.currentTimeMillis() >= stopAt) break
                    if (seg.mapUri != null && seg.mapUri != initWritten) {
                        val init = fetchBytes(seg.mapUri)
                        os.write(init); written(init.size)
                        initWritten = seg.mapUri
                    }
                    var data = fetchBytes(seg.uri)
                    if (seg.keyMethod.equals("AES-128", true) && seg.keyUri != null) {
                        val key = keys.getOrPut(seg.keyUri) { fetchBytes(seg.keyUri) }
                        data = decrypt(data, key, seg.iv ?: seqIv(seq))
                    }
                    os.write(data)
                    written(data.size)
                    onProgress(total)
                    lastSeq = seq
                }
                failures = 0
                if (pl.endList) break
                delay((pl.targetDuration * 500L).coerceIn(1_000, 6_000))
            } catch (e: DiskFullException) {
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                failures++
                if (failures > 40) throw IOException("Flux HLS interrompu : ${e.message}")
                delay(2_000)
            }
        }
    }
}
