package fr.yannickuhrig.vyzo.data.download

import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.DownloadEntity
import fr.yannickuhrig.vyzo.data.db.DownloadStatus
import fr.yannickuhrig.vyzo.data.db.PlaylistKeys
import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.net.await
import fr.yannickuhrig.vyzo.data.repo.StreamSpec
import fr.yannickuhrig.vyzo.diag.DiagLog
import fr.yannickuhrig.vyzo.platform.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Gestionnaire de téléchargements en arrière-plan pour les films et épisodes de séries.
 * Permet le visionnage 100% hors-ligne avec reprise automatique sur coupure réseau (HTTP Range).
 */
class DownloadManager(
    private val platform: Platform,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    /** Flux à jour d'un téléchargement (URL et User-Agent de sa playlist), ou null pour garder l'URL enregistrée. */
    private val resolve: suspend (DownloadEntity) -> StreamSpec? = { null },
) {
    val downloadsDir: File
        get() = platform.filesDir.resolve("downloads").apply { mkdirs() }

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val queueTrigger = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch(Dispatchers.IO) {
            // Remettre en attente les téléchargements interrompus brutalement
            val interrupted = db.downloads().activeDownloads()
            for (d in interrupted) {
                db.downloads().updateStatus(d.id, DownloadStatus.QUEUED)
            }
            processQueueLoop()
        }
    }

    private suspend fun processQueueLoop() {
        while (scope.isActive) {
            try {
                processNextQueue()
            } catch (e: Exception) {
                DiagLog.error("DownloadManager", "Erreur boucle de téléchargement", e)
            }
            queueTrigger.receive()
        }
    }

    private suspend fun processNextQueue() = withContext(Dispatchers.IO) {
        val pending = db.downloads().pendingQueue()
        // Chaque téléchargement occupe une connexion de l'abonnement : on en garde une pour regarder.
        val slots = HashMap<Long?, Int>()
        for (item in pending) {
            if (activeJobs.size >= MAX_CONCURRENT_DOWNLOADS) break
            if (activeJobs.containsKey(item.id)) continue
            val pid = playlistOf(item.id)
            val max = slots.getOrPut(pid) { slotsFor(pid?.let { db.playlists().get(it) }?.maxConnections ?: 0) }
            if (activeJobs.keys.count { playlistOf(it) == pid } >= max) continue
            startDownloadJob(item)
        }
    }

    private fun triggerQueue() {
        queueTrigger.trySend(Unit)
    }

    private fun startDownloadJob(item: DownloadEntity) {
        // Démarrage différé : le job est inscrit avant de tourner, et ne retire en sortie que sa propre entrée
        // (une reprise rapide après une pause en a peut-être déjà inscrit un autre).
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                executeDownload(item)
            } finally {
                activeJobs.remove(item.id, coroutineContext.job)
                triggerQueue()
            }
        }
        activeJobs[item.id] = job
        job.start()
    }

    private suspend fun executeDownload(item: DownloadEntity) = withContext(Dispatchers.IO) {
        val file = File(item.localFilePath)
        file.parentFile?.mkdirs()

        db.downloads().updateStatus(item.id, DownloadStatus.DOWNLOADING)

        // Flux résolu à nouveau : User-Agent de la playlist, et lien neuf pour les sources dont les liens expirent (Stalker).
        val spec = runCatching { resolve(item) }.getOrNull()
        val url = spec?.url?.takeIf { it.isNotBlank() } ?: item.remoteUrl
        val ua = spec?.userAgent.orEmpty()
        val headers = spec?.headers.orEmpty()

        try {
            val existingBytes = if (file.exists()) file.length() else 0L
            val ranged = if (existingBytes > 0L) headers + ("Range" to "bytes=$existingBytes-") else headers
            Http.streamingClient.newCall(Http.request(url, ua, ranged)).await().use { response ->
                when {
                    response.code == 416 -> {
                        // Reprise refusée : on recommence de zéro.
                        file.delete()
                        Http.streamingClient.newCall(Http.request(url, ua, headers)).await().use { retry ->
                            if (retry.isSuccessful) readResponseBody(item, retry, file, 0L, contentLength(retry, 0L))
                            else db.downloads().updateStatus(item.id, DownloadStatus.FAILED)
                        }
                    }
                    !response.isSuccessful -> db.downloads().updateStatus(item.id, DownloadStatus.FAILED)
                    // 206 : le serveur reprend là où on s'était arrêté ; 200 : il renvoie tout, le fichier est réécrit.
                    response.code == 206 -> readResponseBody(item, response, file, existingBytes, contentLength(response, existingBytes))
                    else -> readResponseBody(item, response, file, 0L, contentLength(response, 0L))
                }
            }
        } catch (e: CancellationException) {
            // Pause ou suppression : pauseDownload/deleteDownload écrivent eux-mêmes le statut.
            throw e
        } catch (e: Exception) {
            // Une pause peut aussi interrompre la connexion par une IOException : ce n'est pas un échec.
            currentCoroutineContext().ensureActive()
            DiagLog.error("DownloadManager", "Échec téléchargement ${item.id}", e)
            db.downloads().updateStatus(item.id, DownloadStatus.FAILED)
        }
    }

    /** Taille totale attendue, ou 0 si le serveur ne l'annonce pas. */
    private fun contentLength(response: okhttp3.Response, startBytes: Long): Long {
        val len = response.body?.contentLength() ?: -1L
        return if (len > 0L) startBytes + len else 0L
    }

    private suspend fun readResponseBody(
        item: DownloadEntity,
        response: okhttp3.Response,
        file: File,
        startBytes: Long,
        totalBytes: Long,
    ) {
        val stream = response.body?.byteStream() ?: throw IllegalStateException("Flux vide")
        val ctx = currentCoroutineContext()

        var downloaded = startBytes
        var lastDbUpdate = System.currentTimeMillis()
        val buffer = ByteArray(65536)

        FileOutputStream(file, startBytes > 0L).use { out ->
            stream.use { input ->
                while (true) {
                    // Le job de CE téléchargement, et non le scope du gestionnaire : c'est lui que la pause annule.
                    ctx.ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    downloaded += read

                    val now = System.currentTimeMillis()
                    if (now - lastDbUpdate >= 600L) {
                        lastDbUpdate = now
                        db.downloads().updateBytes(item.id, downloaded, totalBytes)
                    }
                }
            }
        }
        ctx.ensureActive()

        val finalLength = file.length()
        if (totalBytes > 0L && finalLength != totalBytes) {
            // Taille différente de celle annoncée (coupure, reprise mal servie) : jamais « terminé ». Le fichier reste
            // en place et « Reprendre » repart de là (HTTP Range).
            DiagLog.log("DownloadManager", "Téléchargement ${item.id} incomplet : $finalLength / $totalBytes octets")
            db.downloads().updateProgress(item.id, finalLength, totalBytes, DownloadStatus.FAILED)
            return
        }
        db.downloads().updateProgress(item.id, finalLength, finalLength, DownloadStatus.COMPLETED)
    }

    /**
     * Ajoute ou met en file d'attente un nouveau téléchargement.
     */
    suspend fun startOrQueueDownload(
        itemType: String,
        itemUid: String,
        seriesUid: String? = null,
        title: String,
        subtitle: String = "",
        posterUrl: String = "",
        remoteUrl: String,
    ) = withContext(Dispatchers.IO) {
        val id = "$itemType:$itemUid"
        val existing = db.downloads().get(id)

        if (existing != null && existing.isCompleted && File(existing.localFilePath).exists()) {
            return@withContext
        }

        val localFile = existing?.localFilePath?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: localFileFor(id, title, remoteUrl)

        val entity = DownloadEntity(
            id = id,
            itemType = itemType,
            itemUid = itemUid,
            seriesUid = seriesUid,
            title = title,
            subtitle = subtitle,
            posterUrl = posterUrl,
            remoteUrl = remoteUrl,
            localFilePath = localFile.absolutePath,
            status = DownloadStatus.QUEUED,
            bytesDownloaded = if (localFile.exists()) localFile.length() else 0L,
            totalBytes = existing?.totalBytes ?: 0L,
            createdAt = System.currentTimeMillis(),
        )

        db.downloads().upsert(entity)
        triggerQueue()
    }

    /**
     * Met en pause un téléchargement en cours ou en attente.
     */
    suspend fun pauseDownload(id: String) = withContext(Dispatchers.IO) {
        // Statut écrit avant l'arrêt du job : à sa sortie, il relance la file, qui ne doit plus le voir comme en cours.
        db.downloads().updateStatus(id, DownloadStatus.PAUSED)
        activeJobs.remove(id)?.cancelAndJoin()
        triggerQueue()
    }

    /**
     * Reprend un téléchargement en pause ou en échec.
     */
    suspend fun resumeDownload(id: String) = withContext(Dispatchers.IO) {
        db.downloads().updateStatus(id, DownloadStatus.QUEUED)
        triggerQueue()
    }

    /**
     * Annule et supprime un téléchargement ainsi que son fichier sur le disque.
     */
    suspend fun deleteDownload(id: String) = withContext(Dispatchers.IO) {
        val d = db.downloads().get(id)
        // Ligne supprimée avant l'arrêt du job, pour que la file ne le relance pas ; fichier effacé une fois l'écriture finie.
        db.downloads().delete(id)
        activeJobs.remove(id)?.cancelAndJoin()
        if (d != null) runCatching { File(d.localFilePath).delete() }
        triggerQueue()
    }

    /**
     * Renvoie le fichier local terminé s'il existe et est complet, ou null.
     */
    suspend fun getCompletedFile(itemUid: String): File? = withContext(Dispatchers.IO) {
        val d = db.downloads().getByItemUid(itemUid) ?: return@withContext null
        if (d.isCompleted && d.localFilePath.isNotBlank()) {
            val file = File(d.localFilePath)
            if (file.exists() && file.length() > 0L) file else null
        } else null
    }

    fun observeDownload(id: String): Flow<DownloadEntity?> = db.downloads().observe(id)

    fun observeDownloadByItemUid(itemUid: String): Flow<DownloadEntity?> = db.downloads().observeByItemUid(itemUid)

    fun observeSeriesDownloads(seriesUid: String): Flow<List<DownloadEntity>> = db.downloads().observeBySeriesUid(seriesUid)

    fun observeAll(): Flow<List<DownloadEntity>> = db.downloads().observeAll()

    fun localFileFor(id: String, title: String, remoteUrl: String): File {
        val safe = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").replace(Regex("\\s+"), " ").trim().take(50).ifBlank { "download" }
        val ext = when {
            remoteUrl.contains(".mkv", ignoreCase = true) -> "mkv"
            remoteUrl.contains(".mp4", ignoreCase = true) -> "mp4"
            remoteUrl.contains(".ts", ignoreCase = true) -> "ts"
            remoteUrl.contains(".avi", ignoreCase = true) -> "avi"
            else -> "mp4"
        }
        val safeId = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return downloadsDir.resolve("${safe}_${safeId}.$ext")
    }

    companion object {
        const val MAX_CONCURRENT_DOWNLOADS = 2

        /** Téléchargements simultanés pour un abonnement à [maxConnections] connexions (0 : inconnu, sans limite annoncée). */
        fun slotsFor(maxConnections: Int): Int =
            if (maxConnections <= 0) MAX_CONCURRENT_DOWNLOADS else (maxConnections - 1).coerceIn(1, MAX_CONCURRENT_DOWNLOADS)

        /** Playlist d'un téléchargement, d'après son id « movie:{uid} » ou « episode:{uid} ». */
        private fun playlistOf(id: String): Long? = PlaylistKeys.playlistIdOf(id.substringAfter(':'))

        /** URI d'un fichier local pour le lecteur : `file:///…`, la forme courte `file:/…` de File.toURI() n'est pas lue par libVLC. */
        fun playUri(file: File): String = file.toPath().toUri().toString()
    }
}
