package fr.yannickuhrig.vyzo.remote

import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.db.PlaylistKeys
import fr.yannickuhrig.vyzo.util.Log
import fr.yannickuhrig.vyzo.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Message envoyé depuis le site du compte, affiché sur cet appareil. */
data class RemoteMessage(val text: String, val from: String)

/**
 * Commandes du site du compte adressées aux appareils, sur le canal privé du compte (voir web/src/backend.ts) :
 * - `playback_query` : chaque appareil connecté répond `playback_state` (ce qu'il lit, en pause ou non) ;
 * - `playback_control` : pause, reprise ou arrêt sur l'appareil visé ;
 * - `device_check` : vérification immédiate de l'état des abonnements, renvoyé ensuite au compte ;
 * - `device_message` : message affiché à l'écran ;
 * - `guide_search` : recherche dans le guide de cet appareil, pour programmer un enregistrement depuis le site.
 * Titres, messages et résultats voyagent chiffrés avec la clé du compte ([fr.yannickuhrig.vyzo.data.cloud.CloudSync.sealLive]).
 */
@OptIn(FlowPreview::class)
class DeviceRemote(private val c: AppContainer) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _messages = MutableSharedFlow<RemoteMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<RemoteMessage> = _messages.asSharedFlow()

    /** Lecture arrêtée depuis le site : l'écran du lecteur se ferme. */
    private val _stops = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stops: SharedFlow<Unit> = _stops.asSharedFlow()

    fun start() {
        // Lecteur obtenu ici, sur le fil qui démarre l'appli, plutôt que dans une coroutine.
        val player = c.player
        c.scope.launch {
            c.cloud.incomingBroadcasts.collect { (type, body) ->
                runCatching { handle(type, body) }.onFailure { Log.w(TAG, "commande $type", it) }
            }
        }
        // Le site ouvert voit aussitôt un changement de programme, une pause ou un arrêt.
        c.scope.launch {
            combine(player.tag, player.status) { tag, status -> tag to status.isPlaying }
                .distinctUntilChanged().drop(1).debounce(1_200)
                .collect { runCatching { sendState("") } }
        }
    }

    private suspend fun handle(type: String, body: JsonObject) {
        val forMe = body.str("target").let { it.isNotBlank() && it == c.cloud.thisDeviceId() }
        val req = body.str("req").take(64)
        when (type) {
            "playback_query" -> sendState(req)
            "playback_control" -> if (forMe) {
                control(body.str("action"))
                delay(700)
                sendState(req)
            }
            "device_check" -> if (forMe) {
                ack(req, "check")
                c.db.playlists().all().forEach { runCatching { c.sync.checkAccount(it) } }
                c.cloud.requestPush()
                ack(req, "check_done")
            }
            "device_message" -> if (forMe) {
                val plain = c.cloud.openLive(type, body.str("box")) ?: return
                val o = runCatching { json.parseToJsonElement(plain) as? JsonObject }.getOrNull() ?: return
                val text = o.str("text").trim().take(MESSAGE_MAX)
                if (text.isBlank()) return
                _messages.tryEmit(RemoteMessage(text, o.str("from").trim().take(60)))
                ack(req, "message")
            }
            "guide_search" -> guideSearch(req, body.str("box"))
        }
    }

    private suspend fun ack(req: String, action: String) {
        c.cloud.broadcast("device_ack", buildJsonObject {
            put("device", c.cloud.thisDeviceId()); put("req", req); put("action", action)
        })
    }

    private suspend fun control(action: String) = withContext(Dispatchers.Main) {
        if (c.player.current.value == null) return@withContext
        when (action) {
            "pause" -> c.player.pause()
            "resume" -> c.player.resume()
            "stop" -> {
                _stops.tryEmit(Unit)
                c.player.stop()
            }
        }
    }

    private suspend fun sendState(req: String) {
        val tag = c.player.tag.value
        val active = tag != null && c.player.current.value != null
        val playing = active && c.player.status.value.isPlaying
        val (position, duration) = if (active) withContext(Dispatchers.Main) { c.player.currentPosition to c.player.duration } else 0L to 0L
        val info = if (active) c.describePlaying(tag!!) else null
        val plain = buildJsonObject {
            put("state", if (!active) "idle" else if (playing) "playing" else "paused")
            put("title", info?.title.orEmpty()); put("detail", info?.detail.orEmpty()); put("kind", info?.kind.orEmpty())
            put("positionMs", position.coerceAtLeast(0)); put("durationMs", duration.coerceAtLeast(0))
            put("profile", c.profiles.active.value?.name.orEmpty())
        }.toString()
        val box = c.cloud.sealLive("playback_state", plain) ?: return
        c.cloud.broadcast("playback_state", buildJsonObject {
            put("device", c.cloud.thisDeviceId()); put("req", req); put("box", box)
        })
    }

    private suspend fun guideSearch(req: String, box: String) {
        val plain = c.cloud.openLive("guide_search", box) ?: return
        val q = runCatching { (json.parseToJsonElement(plain) as? JsonObject)?.str("q") }.getOrNull()?.trim().orEmpty()
        if (q.length !in 2..80) return
        val now = System.currentTimeMillis()
        val scope = c.repo.viewScopeNow()
        val keys = c.db.playlists().all().associate { it.id to PlaylistKeys.of(it) }
        val seen = HashSet<String>()
        val results = ArrayList<Triple<fr.yannickuhrig.vyzo.data.db.ProgramEntity, String, String>>()
        for (pr in c.db.programs().search(q, now, GUIDE_SCAN)) {
            if (results.size >= GUIDE_MAX) break
            if (pr.stop <= now) continue
            val ch = c.db.channels().byEpgKey(scope.pids, pr.channelKey, scope.hide) ?: continue
            val key = PlaylistKeys.contentKey(ch.uid, ch.playlistId, keys[ch.playlistId] ?: continue) ?: continue
            if (seen.add("$key|${pr.start}")) results += Triple(pr, ch.name, key)
        }
        val payload = buildJsonObject {
            putJsonArray("items") {
                results.forEach { (pr, channel, key) ->
                    addJsonObject {
                        put("channelKey", key); put("channel", channel.take(120)); put("title", pr.title.take(200))
                        put("start", pr.start); put("stop", pr.stop); put("desc", pr.description.take(240))
                    }
                }
            }
        }.toString()
        val sealed = c.cloud.sealLive("guide_results", payload) ?: return
        c.cloud.broadcast("guide_results", buildJsonObject {
            put("device", c.cloud.thisDeviceId()); put("req", req); put("box", sealed)
        })
    }

    companion object {
        private const val TAG = "DeviceRemote"
        private const val MESSAGE_MAX = 500
        private const val GUIDE_SCAN = 200
        private const val GUIDE_MAX = 40
    }
}
