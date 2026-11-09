package fr.yannickuhrig.vyzo.data.cloud

import fr.yannickuhrig.vyzo.data.net.await
import fr.yannickuhrig.vyzo.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Session du compte Vyzo (jetons Supabase Auth). */
@Serializable
data class CloudSession(
    val accessToken: String,
    val refreshToken: String,
    /** Expiration du jeton d'accès, en secondes depuis 1970. */
    val expiresAt: Long,
    val userId: String,
    val email: String,
) {
    fun expiresSoon(nowSec: Long = System.currentTimeMillis() / 1000): Boolean = expiresAt - nowSec < 120
}

/** Erreur renvoyée par le serveur, avec un message en français prêt à afficher. */
class CloudException(message: String, val code: String = "", val status: Int = 0) : IOException(message) {
    val isAuth get() = status == 401 || code == "invalid_grant" || code == "refresh_token_not_found" ||
        code == "refresh_token_already_used" || code == "session_not_found" || code == "bad_jwt"
}

/** Résultat d'une inscription : soit une session, soit un e-mail de confirmation envoyé. */
sealed interface SignUpResult {
    data class SignedIn(val session: CloudSession) : SignUpResult
    data object ConfirmationSent : SignUpResult
}

/** Petit client REST pour Supabase (Auth + PostgREST), sans dépendance supplémentaire. */
class CloudApi(
    val baseUrl: String,
    val apiKey: String,
    val client: OkHttpClient,
) {
    private val http = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    val configured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    // ------------------------------------------------------------------ Auth

    suspend fun signIn(email: String, password: String): CloudSession {
        val body = buildJsonObject { put("email", email.trim()); put("password", password) }
        return parseSession(post("/auth/v1/token?grant_type=password", body, token = null) as JsonObject)
    }

    suspend fun signUp(email: String, password: String): SignUpResult {
        val body = buildJsonObject { put("email", email.trim()); put("password", password) }
        val o = post("/auth/v1/signup", body, token = null) as JsonObject
        return if (o.str("access_token").isNotBlank()) SignUpResult.SignedIn(parseSession(o)) else SignUpResult.ConfirmationSent
    }

    suspend fun refresh(refreshToken: String): CloudSession {
        val body = buildJsonObject { put("refresh_token", refreshToken) }
        return parseSession(post("/auth/v1/token?grant_type=refresh_token", body, token = null) as JsonObject)
    }

    suspend fun signOut(accessToken: String) {
        runCatching { post("/auth/v1/logout", buildJsonObject {}, token = accessToken) }
    }

    suspend fun resendConfirmation(email: String) {
        post("/auth/v1/resend", buildJsonObject { put("type", "signup"); put("email", email.trim()) }, token = null)
    }

    suspend fun sendPasswordReset(email: String) {
        post("/auth/v1/recover", buildJsonObject { put("email", email.trim()) }, token = null)
    }

    private fun parseSession(o: JsonObject): CloudSession {
        val user = o["user"] as? JsonObject ?: throw CloudException("Réponse inattendue du serveur Vyzo")
        val expiresIn = o.str("expires_in").toLongOrNull() ?: 3600
        val expiresAt = o.str("expires_at").toLongOrNull() ?: (System.currentTimeMillis() / 1000 + expiresIn)
        return CloudSession(
            accessToken = o.str("access_token"),
            refreshToken = o.str("refresh_token"),
            expiresAt = expiresAt,
            userId = user.str("id"),
            email = user.str("email"),
        )
    }

    // ------------------------------------------------------------------ Données

    /** GET /rest/v1/[table]?[query] : lignes JSON. */
    suspend fun select(table: String, query: String, token: String): JsonArray =
        call(Request.Builder().url("$baseUrl/rest/v1/$table?$query").get(), token) as? JsonArray ?: JsonArray(emptyList())

    /** Insère ou met à jour des lignes (clé de conflit [onConflict]). */
    suspend fun upsert(table: String, onConflict: String, rows: JsonArray, token: String) {
        if (rows.isEmpty()) return
        val req = Request.Builder()
            .url("$baseUrl/rest/v1/$table?on_conflict=${enc(onConflict)}")
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(rows.toString().toRequestBody(jsonType))
        call(req, token)
    }

    /** DELETE /rest/v1/[table]?[filter]. */
    suspend fun delete(table: String, filter: String, token: String) {
        call(Request.Builder().url("$baseUrl/rest/v1/$table?$filter").header("Prefer", "return=minimal").delete(), token)
    }

    // ------------------------------------------------------------------ HTTP

    private suspend fun post(path: String, body: JsonObject, token: String?): JsonElement? =
        call(Request.Builder().url(baseUrl + path).post(body.toString().toRequestBody(jsonType)), token)

    private suspend fun call(builder: Request.Builder, token: String?): JsonElement? {
        if (!configured) throw CloudException("Le compte Vyzo n'est pas configuré dans cette version de l'appli")
        builder.header("apikey", apiKey)
        if (token != null) builder.header("Authorization", "Bearer $token")
        val req = builder.build()
        val resp = try {
            http.newCall(req).await()
        } catch (e: IOException) {
            throw CloudException("Impossible de joindre le serveur Vyzo. Vérifie la connexion Internet.")
        }
        return withContext(Dispatchers.IO) {
            resp.use {
                val text = it.body?.string().orEmpty()
                val parsed = if (text.isBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()
                if (!it.isSuccessful) throw errorFrom(it.code, parsed as? JsonObject)
                parsed
            }
        }
    }

    private fun errorFrom(status: Int, o: JsonObject?): CloudException {
        val code = o?.str("error_code")?.ifBlank { null } ?: o?.str("error")?.ifBlank { null } ?: o?.str("code").orEmpty()
        val raw = listOfNotNull(o?.str("msg"), o?.str("error_description"), o?.str("message")).firstOrNull { it.isNotBlank() }.orEmpty()
        val message = when {
            code == "invalid_credentials" || raw.contains("Invalid login credentials", true) -> "E-mail ou mot de passe incorrect"
            code == "email_not_confirmed" || raw.contains("Email not confirmed", true) ->
                "Adresse pas encore confirmée : ouvre le lien reçu par e-mail, puis reconnecte-toi"
            code == "user_already_exists" || raw.contains("already registered", true) -> "Un compte existe déjà avec cette adresse"
            code == "weak_password" || raw.contains("Password should be", true) -> "Mot de passe trop court (6 caractères minimum)"
            code == "email_address_invalid" || raw.contains("invalid format", true) || raw.contains("is invalid", true) -> "Adresse e-mail invalide"
            code.startsWith("over_") || status == 429 -> "Trop de tentatives, réessaie dans quelques minutes"
            code == "signup_disabled" -> "La création de compte est désactivée sur le serveur"
            status == 401 || status == 403 -> "Session expirée : reconnecte-toi au compte Vyzo"
            status >= 500 -> "Le serveur Vyzo ne répond pas correctement (HTTP $status)"
            raw.isNotBlank() -> raw
            else -> "Erreur du serveur Vyzo (HTTP $status)"
        }
        return CloudException(message, code, status)
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
