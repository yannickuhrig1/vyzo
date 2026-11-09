package fr.yannickuhrig.vyzo.data.remote

import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.net.await
import fr.yannickuhrig.vyzo.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class RemoteStatus(val state: String, val message: String) {
    val finished get() = state == "done" || state == "error" || state == "rejected"
}

/** Côté téléphone : envoie une playlist ou toute la configuration à une TV qui affiche son QR code. */
object ConfigSender {
    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private suspend fun call(req: Request): JsonObject {
        val resp = try {
            client.newCall(req).await()
        } catch (e: IOException) {
            throw IOException("Impossible de joindre la TV. Vérifie que le téléphone et la TV sont sur le même réseau Wi-Fi.", e)
        }
        return withContext(Dispatchers.IO) {
            resp.use {
                val body = it.body?.string().orEmpty()
                val o = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
                    ?: throw IOException("Réponse inattendue de la TV (HTTP ${it.code})")
                if (!it.isSuccessful || o.str("ok") != "true") throw IOException(o.str("error").ifBlank { "Refusé par la TV (HTTP ${it.code})" })
                o
            }
        }
    }

    private fun url(d: ScannedCode.VyzoDevice, path: String) = "${d.baseUrl}$path?k=${d.token}"

    suspend fun deviceName(d: ScannedCode.VyzoDevice): String =
        call(Request.Builder().url(url(d, "/api/info")).build()).str("device").ifBlank { "la TV" }

    suspend fun sendPlaylist(d: ScannedCode.VyzoDevice, p: RemotePlaylist) {
        val body = json.encodeToString(RemotePlaylist.serializer(), p).toRequestBody(jsonType)
        call(Request.Builder().url(url(d, "/api/playlist")).post(body).build())
    }

    suspend fun sendConfig(d: ScannedCode.VyzoDevice, backupJson: String) {
        call(Request.Builder().url(url(d, "/api/config")).post(backupJson.toRequestBody(jsonType)).build())
    }

    /** Envoie ce qui a été coché. Une TV trop ancienne ne connaît pas cette adresse et n'écrase donc rien. */
    suspend fun sendSelection(d: ScannedCode.VyzoDevice, selectionJson: String) {
        try {
            call(Request.Builder().url(url(d, "/api/selection")).post(selectionJson.toRequestBody(jsonType)).build())
        } catch (e: IOException) {
            throw if (e.message == "Introuvable") IOException("Mets d'abord à jour Vyzo sur la TV pour choisir ce que tu lui envoies.", e) else e
        }
    }

    /** Demande à la TV de se connecter au compte et attend sa réponse. */
    suspend fun sendAccount(d: ScannedCode.VyzoDevice, email: String, password: String) {
        val body = buildJsonObject { put("email", email); put("password", password) }.toString().toRequestBody(jsonType)
        call(Request.Builder().url(url(d, "/api/account")).post(body).build())
        repeat(60) {
            delay(1_000)
            val s = status(d)
            if (s.state == "done") return
            if (s.state == "error") throw IOException(s.message)
        }
        throw IOException("La TV ne répond pas à la demande de connexion au compte.")
    }

    suspend fun status(d: ScannedCode.VyzoDevice): RemoteStatus {
        val o = call(Request.Builder().url(url(d, "/api/status")).build())
        return RemoteStatus(o.str("state"), o.str("message"))
    }
}
