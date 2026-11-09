package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.cloud.RealtimeAuth
import fr.yannickuhrig.vyzo.data.cloud.RealtimeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Connexion instantanée au compte : abonnement, notifications, battements de cœur, jeton renouvelé, reconnexion. */
class RealtimeClientTest {
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val received = CopyOnWriteArrayList<JsonObject>()
    private val paths = CopyOnWriteArrayList<String>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val connections = AtomicInteger(0)
    @Volatile private var refuseJoin = false

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path.orEmpty()
                connections.incrementAndGet()
                return MockResponse().withWebSocketUpgrade(FakeRealtime())
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        sockets.forEach { runCatching { it.close(1001, null) } }
        Thread.sleep(300)
        server.shutdown()
    }

    /** Serveur Realtime minimal : accepte l'abonnement puis envoie une modification de la progression. */
    private inner class FakeRealtime : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets += webSocket
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = Json.parseToJsonElement(text) as JsonObject
            received += m
            val event = m["event"]!!.jsonPrimitive.content
            val ref = m["ref"]!!.jsonPrimitive.content
            when (event) {
                "phx_join" -> {
                    val status = if (refuseJoin) "error" else "ok"
                    webSocket.send("""{"topic":"${RealtimeClient.topicFor("user-42")}","event":"phx_reply","payload":{"status":"$status","response":{}},"ref":"$ref"}""")
                    if (!refuseJoin) {
                        webSocket.send(
                            """{"topic":"${RealtimeClient.topicFor("user-42")}","event":"postgres_changes","payload":{"data":{"table":"watch_progress","type":"UPDATE",""" +
                                """"record":{"content_key":"abc:M:1"}},"ids":[1]},"ref":null}""",
                        )
                    }
                }
                "heartbeat" -> webSocket.send("""{"topic":"phoenix","event":"phx_reply","payload":{"status":"ok","response":{}},"ref":"$ref"}""")
            }
        }
    }

    private fun client(changes: Channel<String>, token: () -> String = { "jwt-1" }) = RealtimeClient(
        baseUrl = server.url("/").toString(), apiKey = "sb_publishable_test", client = OkHttpClient(), scope = scope,
        tables = listOf("profiles", "watch_progress", "user_lists", "list_items"),
        auth = { RealtimeAuth(token(), "user-42") },
        onChange = { table: String -> changes.trySend(table) },
        heartbeatMs = 150, tokenRefreshMs = 300,
    )

    @Test
    fun subscribesAndForwardsChanges() = runBlocking {
        val changes = Channel<String>(Channel.UNLIMITED)
        var tokenNo = 1
        val rt = client(changes) { "jwt-${tokenNo++}" }
        rt.start()
        withTimeout(10_000) {
            // Rattrapage à la connexion, puis la modification envoyée par le serveur.
            assertEquals("*", changes.receive())
            assertEquals("watch_progress", changes.receive())
        }
        assertEquals(RealtimeClient.State.ON, rt.state.value)
        assertEquals("/realtime/v1/websocket?apikey=sb_publishable_test&vsn=1.0.0", paths.first())

        val join = received.first { it["event"]!!.jsonPrimitive.content == "phx_join" }
        assertEquals("realtime:user:user-42", join["topic"]!!.jsonPrimitive.content)
        val payload = join["payload"] as JsonObject
        assertEquals("jwt-1", payload["access_token"]!!.jsonPrimitive.content)
        val filters = ((payload["config"] as JsonObject)["postgres_changes"] as JsonArray).map { it as JsonObject }
        assertEquals(listOf("profiles", "watch_progress", "user_lists", "list_items"), filters.map { it["table"]!!.jsonPrimitive.content })
        assertTrue(filters.all { it["filter"]!!.jsonPrimitive.content == "user_id=eq.user-42" })
        // Canal privé : le serveur vérifie le jeton avant de laisser lire ou diffuser.
        assertEquals("true", (payload["config"] as JsonObject)["private"]!!.jsonPrimitive.content)

        // Battements de cœur et jeton renouvelé sur le canal.
        withTimeout(5_000) {
            while (received.none { it["event"]!!.jsonPrimitive.content == "heartbeat" } ||
                received.none { it["event"]!!.jsonPrimitive.content == "access_token" }
            ) delay(50)
        }
        val renewed = received.first { it["event"]!!.jsonPrimitive.content == "access_token" }
        assertTrue((renewed["payload"] as JsonObject)["access_token"]!!.jsonPrimitive.content.startsWith("jwt-"))

        // Coupure côté serveur : l'appareil se reconnecte et rattrape ce qu'il a manqué.
        sockets.first().close(1001, "redémarrage")
        withTimeout(15_000) {
            while (connections.get() < 2) delay(50)
            while (changes.receive() != "*") Unit
        }

        rt.stop()
        assertEquals(RealtimeClient.State.OFF, rt.state.value)
    }

    @Test
    fun refusedSubscriptionDoesNotReportChanges() = runBlocking {
        refuseJoin = true
        val changes = Channel<String>(Channel.UNLIMITED)
        val rt = client(changes)
        rt.start()
        withTimeout(10_000) { while (received.none { it["event"]!!.jsonPrimitive.content == "phx_join" }) delay(50) }
        delay(500)
        assertTrue("aucune notification", changes.tryReceive().isFailure)
        rt.stop()
    }

    @Test
    fun noAccountMeansNoConnection() = runBlocking {
        val changes = Channel<String>(Channel.UNLIMITED)
        val rt = RealtimeClient(
            baseUrl = server.url("/").toString(), apiKey = "k", client = OkHttpClient(), scope = scope,
            tables = listOf("profiles"), auth = { null }, onChange = { table: String -> changes.trySend(table) },
        )
        rt.start()
        delay(500)
        assertEquals(0, connections.get())
        assertEquals(RealtimeClient.State.OFF, rt.state.value)
    }
}
