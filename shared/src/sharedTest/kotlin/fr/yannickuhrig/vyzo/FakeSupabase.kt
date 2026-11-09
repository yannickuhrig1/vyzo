package fr.yannickuhrig.vyzo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Imitation minimale de Supabase (Auth + PostgREST) pour tester la synchro : mêmes règles que les tables réelles
 * (un compte ne voit que ses lignes, « dernier écrit gagnant » sur updated_at, horodatage synced_at du serveur,
 * suppression en cascade d'un profil ou d'une liste).
 */
class FakeSupabase : Dispatcher() {
    private val users = HashMap(mapOf("foyer@example.fr" to ("u-1111" to "motdepasse")))

    /** Réinitialisation du mot de passe par e-mail. */
    fun setPassword(email: String, password: String) = synchronized(this) { users[email] = users.getValue(email).first to password }
    private val keys = mapOf(
        "profiles" to listOf("user_id", "id"),
        "watch_progress" to listOf("user_id", "profile_id", "content_key"),
        "user_lists" to listOf("user_id", "profile_id", "id"),
        "list_items" to listOf("user_id", "profile_id", "list_id", "content_key"),
        "recordings" to listOf("user_id", "id"),
        "sealed_items" to listOf("user_id", "kind", "id"),
    )
    val tables = HashMap<String, LinkedHashMap<List<String>, JsonObject>>()
    /** Tables pas encore créées sur ce serveur (migration non appliquée) : PostgREST répond 404. */
    @Volatile var missingTables: Set<String> = emptySet()
    private var clock = 1_800_000_000_000L
    private val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'000+00:00'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun rows(table: String): List<JsonObject> = synchronized(this) { tables[table]?.values?.toList().orEmpty() }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this) {
        val url = request.requestUrl!!
        val path = url.encodedPath
        when {
            path == "/auth/v1/token" -> token(url.queryParameter("grant_type"), request.body.readUtf8())
            path == "/auth/v1/logout" -> MockResponse().setResponseCode(204)
            path.startsWith("/rest/v1/") -> {
                val user = request.getHeader("Authorization")?.removePrefix("Bearer ")?.removePrefix("tok-")
                    ?: return json(401, """{"message":"JWT manquant"}""")
                val table = path.removePrefix("/rest/v1/")
                if (table in missingTables) return json(404, """{"code":"PGRST205","message":"Could not find the table"}""")
                when (request.method) {
                    "GET" -> select(table, user, url)
                    "POST" -> upsert(table, user, request.body.readUtf8())
                    "DELETE" -> {
                        tables[table]?.entries?.removeAll { it.value["user_id"]?.jsonPrimitive?.content == user }
                        MockResponse().setResponseCode(204)
                    }
                    else -> json(405, "{}")
                }
            }
            else -> json(404, "{}")
        }
    }

    private fun token(grant: String?, body: String): MockResponse {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(body) as JsonObject
        val (uid, email) = when (grant) {
            "password" -> {
                val email = o["email"]!!.jsonPrimitive.content
                val (uid, pass) = users[email] ?: return json(400, """{"error_code":"invalid_credentials","msg":"Invalid login credentials"}""")
                if (pass != o["password"]!!.jsonPrimitive.content) return json(400, """{"error_code":"invalid_credentials","msg":"Invalid login credentials"}""")
                uid to email
            }
            "refresh_token" -> {
                val uid = o["refresh_token"]!!.jsonPrimitive.content.removePrefix("ref-")
                uid to users.entries.first { it.value.first == uid }.key
            }
            else -> return json(400, "{}")
        }
        return json(
            200,
            """{"access_token":"tok-$uid","refresh_token":"ref-$uid","expires_in":3600,"user":{"id":"$uid","email":"$email"}}""",
        )
    }

    private fun filters(url: okhttp3.HttpUrl): List<Pair<String, String>> =
        url.queryParameterNames.filter { it !in setOf("select", "order", "limit", "on_conflict") }
            .flatMap { name -> url.queryParameterValues(name).map { name to it.orEmpty() } }

    private fun select(table: String, user: String, url: okhttp3.HttpUrl): MockResponse {
        val limit = url.queryParameter("limit")?.toInt() ?: 1000
        val rows = tables[table]?.values.orEmpty().filter { r ->
            r["user_id"]?.jsonPrimitive?.content == user && filters(url).all { (col, f) ->
                val v = r[col]?.jsonPrimitive?.content.orEmpty()
                when {
                    f.startsWith("eq.") -> v == f.removePrefix("eq.")
                    f.startsWith("gt.") -> v > f.removePrefix("gt.").let { normalize(it) }
                    else -> true
                }
            }
        }.sortedBy { it["synced_at"]!!.jsonPrimitive.content }.take(limit)
        return json(200, JsonArray(rows).toString())
    }

    /** Les curseurs envoyés par l'appli peuvent être au format court (millisecondes, « Z »). */
    private fun normalize(ts: String): String = if (ts.endsWith("Z")) ts.removeSuffix("Z") + "000+00:00" else ts

    private fun upsert(table: String, user: String, body: String): MockResponse {
        val key = keys[table] ?: return json(404, "{}")
        val rows = kotlinx.serialization.json.Json.parseToJsonElement(body) as JsonArray
        val t = tables.getOrPut(table) { LinkedHashMap() }
        for (el in rows) {
            val incoming = el as JsonObject
            val row = buildJsonObject {
                incoming.forEach { (k, v) -> put(k, v) }
                put("user_id", user)
                put("synced_at", fmt.format(clock++))
            }
            val pk = key.map { row[it]!!.jsonPrimitive.content }
            val old = t[pk]
            if (old != null && (row.long("updated_at") < old.long("updated_at"))) continue
            t[pk] = row
            // Cascades du vrai schéma
            if (table == "profiles" && row.bool("deleted") && old?.bool("deleted") != true) {
                val id = row["id"]!!.jsonPrimitive.content
                listOf("watch_progress", "list_items", "user_lists").forEach { other ->
                    tables[other]?.entries?.removeAll { it.value["user_id"]?.jsonPrimitive?.content == user && it.value["profile_id"]?.jsonPrimitive?.content == id }
                }
            }
            if (table == "user_lists" && row.bool("deleted") && old?.bool("deleted") != true) {
                val id = row["id"]!!.jsonPrimitive.content
                val profile = row["profile_id"]!!.jsonPrimitive.content
                tables["list_items"]?.entries?.removeAll { it.value["list_id"]?.jsonPrimitive?.content == id && it.value["profile_id"]?.jsonPrimitive?.content == profile }
            }
        }
        return MockResponse().setResponseCode(201)
    }

    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.content == "true"

    @Suppress("unused")
    private fun JsonElement.str() = jsonPrimitive.content
}
