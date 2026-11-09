package fr.yannickuhrig.vyzo.data.cloud

import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.CategoryPrefEntity
import fr.yannickuhrig.vyzo.data.db.ChannelPrefEntity
import fr.yannickuhrig.vyzo.data.db.EpgSourceEntity
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.PlaylistKeys
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.prefs.AppSettings
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.util.Log
import fr.yannickuhrig.vyzo.util.bool
import fr.yannickuhrig.vyzo.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.GeneralSecurityException

/** État de la clé qui ouvre les données chiffrées du compte, sur cet appareil. */
enum class VaultState {
    /** Pas de compte connecté. */
    NONE,
    /** Clé présente : playlists, guide, réglages et chaînes suivent le compte. */
    OPEN,
    /** Pas encore de clé ici : il faut saisir le mot de passe du compte une fois. */
    LOCKED,
    /** Clé chiffrée avec un ancien mot de passe, et aucun appareil pour la reconstituer ici. */
    LOST,
}

@Serializable
internal data class SealedPlaylist(
    val name: String, val type: String, val url: String,
    val username: String = "", val password: String = "", val mac: String = "", val userAgent: String = "",
)

@Serializable
internal data class SealedEpg(val name: String, val url: String, val enabled: Boolean)

/** [rest] : uid sans l'identifiant local de la playlist, qui change d'un appareil à l'autre. */
@Serializable
internal data class SealedCategoryPref(val rest: String, val hidden: Boolean, val position: Int? = null, val locked: Boolean? = null, val name: String = "")

@Serializable
internal data class SealedChannelPref(val rest: String, val name: String = "", val number: Int = 0, val epgKey: String = "", val locked: Boolean = false, val hidden: Boolean = false)

@Serializable
internal data class SealedPrefs(val categories: List<SealedCategoryPref> = emptyList(), val channels: List<SealedChannelPref> = emptyList())

/**
 * Réglages qui suivent le compte. Liste fermée : un nouveau réglage reste propre à chaque appareil tant qu'il n'est pas
 * ajouté ici. Restent sur l'appareil : mode d'affichage, décodeur, code parental, dossiers, mises à jour, position…
 */
@Serializable
internal data class SealedSettings(
    val epgShiftMinutes: Int, val epgDaysAhead: Int, val epgDaysBehind: Int, val xtreamFormat: String, val userAgent: String,
    val audioLang: String, val subtitleLang: String, val autoplayLast: Boolean, val invertZap: Boolean, val showNumbers: Boolean,
    val recPaddingBeforeMin: Int, val recPaddingAfterMin: Int, val reminderMinutesBefore: Int, val showAllPlaylists: Boolean,
    val continuePlaybackInBackground: Boolean, val openSubtitlesApiKey: String, val tmdbApiKey: String, val timeshiftMinutes: Int,
    val expiryWarnDays: Int, val autoSkipIntro: Boolean, val autoNextEpisodeOnOutro: Boolean,
    // Valeur par défaut obligatoire : les données déjà envoyées au compte n'ont pas ce champ.
    val autoSkipRecap: Boolean = false,
) {
    fun applyTo(s: AppSettings) = s.copy(
        epgShiftMinutes = epgShiftMinutes, epgDaysAhead = epgDaysAhead, epgDaysBehind = epgDaysBehind, xtreamFormat = xtreamFormat, userAgent = userAgent,
        audioLang = audioLang, subtitleLang = subtitleLang, autoplayLast = autoplayLast, invertZap = invertZap, showNumbers = showNumbers,
        recPaddingBeforeMin = recPaddingBeforeMin, recPaddingAfterMin = recPaddingAfterMin, reminderMinutesBefore = reminderMinutesBefore, showAllPlaylists = showAllPlaylists,
        continuePlaybackInBackground = continuePlaybackInBackground,
        // Les clés ont leurs propres lignes (SealedKeys). Ici, seulement pour reprendre celles envoyées par une ancienne version.
        openSubtitlesApiKey = s.openSubtitlesApiKey.ifBlank { openSubtitlesApiKey }, tmdbApiKey = s.tmdbApiKey.ifBlank { tmdbApiKey },
        timeshiftMinutes = timeshiftMinutes,
        expiryWarnDays = expiryWarnDays, autoSkipIntro = autoSkipIntro, autoNextEpisodeOnOutro = autoNextEpisodeOnOutro,
        autoSkipRecap = autoSkipRecap,
    )

    companion object {
        fun of(s: AppSettings) = SealedSettings(
            s.epgShiftMinutes, s.epgDaysAhead, s.epgDaysBehind, s.xtreamFormat, s.userAgent,
            s.audioLang, s.subtitleLang, s.autoplayLast, s.invertZap, s.showNumbers,
            s.recPaddingBeforeMin, s.recPaddingAfterMin, s.reminderMinutesBefore, s.showAllPlaylists,
            s.continuePlaybackInBackground, s.openSubtitlesApiKey, s.tmdbApiKey, s.timeshiftMinutes,
            s.expiryWarnDays, s.autoSkipIntro, s.autoNextEpisodeOnOutro, s.autoSkipRecap,
        )
    }
}

/**
 * Clés personnelles qui suivent le compte, une ligne chiffrée par clé. Une clé vide n'a pas de ligne : un appareil qui ne
 * l'a pas encore ne peut donc pas l'effacer chez les autres, alors que la retirer exprès laisse une trace qui se propage.
 */
internal object SealedKeys {
    private const val TMDB = "tmdb"
    private const val OMDB = "omdb"
    private const val OPEN_SUBTITLES = "opensubtitles"
    private const val GITHUB = "github"

    fun of(s: AppSettings): Map<String, String> =
        mapOf(TMDB to s.tmdbApiKey, OMDB to s.omdbApiKey, OPEN_SUBTITLES to s.openSubtitlesApiKey, GITHUB to s.githubToken).filterValues { it.isNotBlank() }

    /** Null pour une clé inconnue : elle vient d'une version plus récente de l'appli, et doit rester sur le compte. */
    fun set(s: AppSettings, id: String, value: String): AppSettings? = when (id) {
        TMDB -> s.copy(tmdbApiKey = value)
        OMDB -> s.copy(omdbApiKey = value)
        OPEN_SUBTITLES -> s.copy(openSubtitlesApiKey = value)
        GITHUB -> s.copy(githubToken = value)
        else -> null
    }
}

@Serializable
private data class VaultRecord(val salt: String, val iterations: Int, val wrapped: String, val keyId: String)

/** Accès à la table des données chiffrées, avec le jeton du compte. */
internal interface SealedRemote {
    suspend fun select(query: String): List<JsonObject>
    suspend fun upsert(rows: JsonArray)
    suspend fun deleteAll()
}

/**
 * Playlists, sources de guide, réglages et personnalisations de chaînes qui suivent le compte, chiffrés sur l'appareil :
 * le serveur ne garde que des blocs illisibles.
 *
 * Ces données n'ont ni date de modification ni marque « à envoyer » dans la base locale. On compare donc l'état actuel à
 * [CloudData.sealedKnown], l'empreinte de chaque élément au dernier échange avec le serveur : une empreinte différente est
 * une modification ici, une empreinte sans élément est une suppression ici.
 */
internal class SealedSync(
    private val remote: SealedRemote,
    private val store: CloudStore,
    private val db: AppDatabase,
    private val repo: Repository,
    private val settings: SettingsStore,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private class Item(val kind: String, val id: String, val plain: String) {
        val slot get() = slot(kind, id)
    }

    /** Émet à chaque modification locale de ce qui suit le compte. */
    fun localChanges(): Flow<Unit> = merge(
        db.playlists().observeAll().map { all -> all.map { it.toSealed() } }.distinctUntilChanged().drop(1).map { },
        settings.flow.map { SealedSettings.of(it) }.distinctUntilChanged().drop(1).map { },
        settings.flow.map { SealedKeys.of(it) }.distinctUntilChanged().drop(1).map { },
        db.epg().observeSources().map { all -> all.filter { !it.auto }.map { SealedEpg(it.name, it.url, it.enabled) } }.distinctUntilChanged().drop(1).map { },
        db.categories().observePrefs().distinctUntilChanged().drop(1).map { },
        db.channelPrefs().observeAll().distinctUntilChanged().drop(1).map { },
    )

    suspend fun state(): VaultState {
        val d = store.get()
        return when {
            d.session == null -> VaultState.NONE
            d.vaultKey.isNotBlank() && d.vaultUserId == d.session.userId -> VaultState.OPEN
            d.vaultLost -> VaultState.LOST
            else -> VaultState.LOCKED
        }
    }

    // ------------------------------------------------------------------ Clé

    /** Appelé à la connexion, seul moment où le mot de passe est connu. Il n'est jamais conservé. */
    suspend fun unlock(session: CloudSession, password: String): VaultState {
        val data = store.get()
        val local = if (data.vaultUserId == session.userId) VaultCrypto.decode(data.vaultKey)?.takeIf { it.size == VaultCrypto.KEY_BYTES } else null
        val record = readVault(session)
        val key: ByteArray? = when {
            record == null -> createVault(session, local ?: VaultCrypto.randomBytes(VaultCrypto.KEY_BYTES), password)
            else -> unwrap(record, password, session) ?: local?.takeIf { VaultCrypto.keyId(it) == record.keyId }?.also {
                // Mot de passe réinitialisé : cet appareil a encore la clé, il la protège avec le nouveau mot de passe.
                writeVault(session, it, password)
            }
        }
        store.update {
            if (key != null) it.copy(vaultKey = VaultCrypto.encode(key), vaultUserId = session.userId, vaultLost = false)
            else it.copy(vaultKey = "", vaultUserId = session.userId, vaultLost = true)
        }
        return if (key != null) VaultState.OPEN else VaultState.LOST
    }

    /** Clé perdue : nouvelle clé, et les anciens blocs illisibles sont effacés. Cet appareil renvoie ensuite ce qu'il a. */
    suspend fun reset(session: CloudSession, password: String) {
        val key = VaultCrypto.randomBytes(VaultCrypto.KEY_BYTES)
        remote.deleteAll()
        writeVault(session, key, password)
        store.update {
            it.copy(
                vaultKey = VaultCrypto.encode(key), vaultUserId = session.userId, vaultLost = false,
                sealedKnown = emptyMap(), cursors = it.cursors - CloudSync.T_SEALED,
            )
        }
    }

    private suspend fun readVault(session: CloudSession): VaultRecord? =
        remote.select("select=*&user_id=eq.${session.userId}&kind=eq.$KIND_VAULT&id=eq.$VAULT_ID&limit=1").firstOrNull()?.toVaultRecord()

    private fun JsonObject.toVaultRecord(): VaultRecord? = runCatching { json.decodeFromString(VaultRecord.serializer(), str("payload")) }.getOrNull()

    /** Deux appareils peuvent créer la clé en même temps : on relit, et tout le monde adopte celle restée sur le serveur. */
    private suspend fun createVault(session: CloudSession, key: ByteArray, password: String): ByteArray? {
        writeVault(session, key, password)
        val stored = readVault(session) ?: return key
        return if (stored.keyId == VaultCrypto.keyId(key)) key else unwrap(stored, password, session)
    }

    private suspend fun writeVault(session: CloudSession, key: ByteArray, password: String) {
        val salt = VaultCrypto.randomBytes(VaultCrypto.SALT_BYTES)
        val kek = derive(password, salt, VaultCrypto.ITERATIONS)
        val record = VaultRecord(
            salt = VaultCrypto.encode(salt), iterations = VaultCrypto.ITERATIONS,
            wrapped = VaultCrypto.seal(kek, key, context(session, KIND_VAULT, VAULT_ID)), keyId = VaultCrypto.keyId(key),
        )
        remote.upsert(JsonArray(listOf(row(KIND_VAULT, VAULT_ID, json.encodeToString(VaultRecord.serializer(), record), System.currentTimeMillis(), false))))
    }

    private suspend fun unwrap(record: VaultRecord, password: String, session: CloudSession): ByteArray? {
        val salt = VaultCrypto.decode(record.salt) ?: return null
        val kek = derive(password, salt, record.iterations)
        return try {
            VaultCrypto.open(kek, record.wrapped, context(session, KIND_VAULT, VAULT_ID))
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    private suspend fun derive(password: String, salt: ByteArray, iterations: Int) =
        withContext(Dispatchers.Default) { VaultCrypto.deriveKey(password, salt, iterations) }

    private suspend fun currentKey(session: CloudSession): ByteArray? {
        val d = store.get()
        return if (d.vaultUserId == session.userId) VaultCrypto.decode(d.vaultKey)?.takeIf { it.size == VaultCrypto.KEY_BYTES } else null
    }

    // ------------------------------------------------------------------ Envoi

    suspend fun push(session: CloudSession) {
        val key = currentKey(session) ?: return
        val known = store.get().sealedKnown
        val current = snapshot().associateBy { it.slot }
        // Toutes les playlists disparues d'un coup : base vidée (retour à une version antérieure de l'appli), pas un choix.
        // On ne propage rien, et la prochaine réception les ramène depuis le compte.
        if (known.keys.count { it.startsWith("$KIND_PLAYLIST|") } >= 2 && current.keys.none { it.startsWith("$KIND_PLAYLIST|") }) {
            Log.w(TAG, "playlists disparues, suppression non propagée")
            store.update { it.copy(sealedKnown = emptyMap(), cursors = it.cursors - CloudSync.T_SEALED) }
            return
        }
        val now = System.currentTimeMillis()
        val rows = ArrayList<JsonObject>()
        val next = HashMap(known)
        for (item in current.values) {
            val hash = VaultCrypto.sha256(item.plain)
            if (known[item.slot] == hash) continue
            rows += row(item.kind, item.id, VaultCrypto.seal(key, item.plain.toByteArray(), context(session, item.kind, item.id)), now, false)
            next[item.slot] = hash
        }
        for (slot in known.keys - current.keys) {
            rows += row(slot.substringBefore('|'), slot.substringAfter('|'), "", now, true)
            next.remove(slot)
        }
        if (rows.isEmpty()) return
        rows.chunked(BATCH).forEach { remote.upsert(JsonArray(it)) }
        store.update { it.copy(sealedKnown = next) }
    }

    private suspend fun snapshot(): List<Item> = buildList {
        val playlists = db.playlists().all().filter { portable(it.url) }.distinctBy { PlaylistKeys.of(it) }
        val categories = db.categories().allPrefs()
        val channels = db.channelPrefs().all()
        for (p in playlists) {
            val id = PlaylistKeys.of(p)
            add(Item(KIND_PLAYLIST, id, encode(SealedPlaylist.serializer(), p.toSealed())))
            val prefix = "${p.id}:"
            val prefs = SealedPrefs(
                categories.filter { it.uid.startsWith(prefix) }.sortedBy { it.uid }
                    .map { SealedCategoryPref(it.uid.removePrefix(prefix), it.hidden, it.position, it.locked, it.name) },
                channels.filter { it.uid.startsWith(prefix) }.sortedBy { it.uid }
                    .map { SealedChannelPref(it.uid.removePrefix(prefix), it.name, it.number, it.epgKey, it.locked, it.hidden) },
            )
            if (prefs.categories.isNotEmpty() || prefs.channels.isNotEmpty()) add(Item(KIND_PREFS, id, encode(SealedPrefs.serializer(), prefs)))
        }
        db.epg().sources().filter { !it.auto && portable(it.url) }.distinctBy { it.url }.forEach {
            add(Item(KIND_EPG, VaultCrypto.sha256(it.url).take(24), encode(SealedEpg.serializer(), SealedEpg(it.name, it.url, it.enabled))))
        }
        add(Item(KIND_SETTINGS, SETTINGS_ID, encode(SealedSettings.serializer(), SealedSettings.of(settings.get()))))
        SealedKeys.of(settings.get()).forEach { (id, value) -> add(Item(KIND_KEY, id, value)) }
    }

    // ------------------------------------------------------------------ Réception

    /** Les lignes changées sur le serveur s'appliquent ici. Le serveur l'emporte sur un changement local pas encore envoyé. */
    suspend fun apply(session: CloudSession, rows: List<JsonObject>) {
        rows.firstOrNull { it.str("kind") == KIND_VAULT }?.toVaultRecord()?.let { record ->
            val mine = currentKey(session)
            if (mine != null && VaultCrypto.keyId(mine) != record.keyId) {
                // Clé remplacée depuis un autre appareil : celle-ci n'ouvre plus rien, il faudra le mot de passe.
                store.update { it.copy(vaultKey = "") }
                return
            }
        }
        val key = currentKey(session) ?: return
        val known = HashMap(store.get().sealedKnown)
        // Identifiant local -> avec le guide. Lancé une seule fois à la fin : les fournisseurs n'aiment pas les connexions répétées.
        val reloads = HashMap<Long, Boolean>()
        // Les playlists d'abord : les personnalisations de chaînes ont besoin de leur identifiant local.
        for (kind in listOf(KIND_PLAYLIST, KIND_PREFS, KIND_EPG, KIND_SETTINGS, KIND_KEY)) {
            for (r in rows.filter { it.str("kind") == kind }) {
                runCatching { applyRow(session, key, r, known, reloads) }.onFailure { Log.w(TAG, "élément chiffré ignoré", it) }
            }
        }
        store.update { it.copy(sealedKnown = known) }
        reloads.forEach { (id, withEpg) -> repo.sync.launchPlaylist(id, withEpg) }
    }

    private suspend fun applyRow(session: CloudSession, key: ByteArray, r: JsonObject, known: MutableMap<String, String>, reloads: MutableMap<Long, Boolean>) {
        val kind = r.str("kind")
        val id = r.str("id")
        val slot = slot(kind, id)
        if (r.bool("deleted")) {
            // Jamais échangé avec le compte : première connexion, on ne retire rien de ce qui est sur l'appareil.
            if (slot in known) {
                removeLocal(kind, id)
                known.remove(slot)
            }
            return
        }
        val plain = VaultCrypto.open(key, r.str("payload"), context(session, kind, id)).decodeToString()
        val hash = VaultCrypto.sha256(plain)
        if (known[slot] == hash) return
        val applied = when (kind) {
            KIND_PLAYLIST -> applyPlaylist(id, json.decodeFromString(SealedPlaylist.serializer(), plain), reloads)
            KIND_PREFS -> applyPrefs(id, json.decodeFromString(SealedPrefs.serializer(), plain), reloads)
            KIND_EPG -> applyEpg(json.decodeFromString(SealedEpg.serializer(), plain))
            KIND_SETTINGS -> {
                val shared = json.decodeFromString(SealedSettings.serializer(), plain)
                settings.update { shared.applyTo(it) }
                // L'état obtenu, pas le bloc reçu : sinon une version plus ancienne, qui n'a pas les mêmes champs, et celle-ci
                // se renverraient le bloc sans fin, chaque envoi réveillant l'autre appareil.
                known[slot] = VaultCrypto.sha256(encode(SealedSettings.serializer(), SealedSettings.of(settings.get())))
                return
            }
            KIND_KEY -> applyKey(id, plain)
            else -> false
        }
        if (applied) known[slot] = hash
    }

    private suspend fun applyKey(id: String, value: String): Boolean {
        if (SealedKeys.set(settings.get(), id, value) == null) return false
        settings.update { SealedKeys.set(it, id, value) ?: it }
        return true
    }

    private suspend fun applyPlaylist(id: String, p: SealedPlaylist, reloads: MutableMap<Long, Boolean>): Boolean {
        val type = runCatching { SourceType.valueOf(p.type) }.getOrDefault(SourceType.M3U)
        val local = db.playlists().all().firstOrNull { PlaylistKeys.of(it) == id }
        if (local == null) {
            val newId = repo.addPlaylist(PlaylistEntity(name = p.name, type = type, url = p.url, username = p.username, password = p.password, mac = p.mac, userAgent = p.userAgent))
            reloads[newId] = true
        } else if (local.toSealed() != p) {
            val updated = local.copy(name = p.name, type = type, url = p.url, username = p.username, password = p.password, mac = p.mac, userAgent = p.userAgent)
            repo.updatePlaylist(updated)
            // Seul le nom a changé : inutile de recharger toute la playlist.
            if (local.copy(name = p.name) != updated) reloads[local.id] = true
        }
        return true
    }

    private suspend fun applyPrefs(id: String, prefs: SealedPrefs, reloads: MutableMap<Long, Boolean>): Boolean {
        val local = db.playlists().all().firstOrNull { PlaylistKeys.of(it) == id } ?: return false
        val prefix = "${local.id}:"
        db.categories().deletePrefsWithPrefix(prefix)
        db.channelPrefs().deleteWithPrefix(prefix)
        db.categories().upsertPrefs(prefs.categories.map { CategoryPrefEntity(prefix + it.rest, it.hidden, it.position, it.locked, it.name) })
        db.channelPrefs().upsertAll(prefs.channels.map { ChannelPrefEntity(prefix + it.rest, it.name, it.number, it.epgKey, it.locked, it.hidden) })
        // Noms et numéros de chaînes sont recopiés dans la liste au chargement de la playlist.
        reloads.getOrPut(local.id) { false }
        return true
    }

    private suspend fun applyEpg(e: SealedEpg): Boolean {
        val local = db.epg().sources().firstOrNull { !it.auto && it.url == e.url }
        if (local == null) db.epg().insertSource(EpgSourceEntity(name = e.name, url = e.url, enabled = e.enabled))
        else if (local.name != e.name || local.enabled != e.enabled) db.epg().updateSource(local.copy(name = e.name, enabled = e.enabled))
        return true
    }

    private suspend fun removeLocal(kind: String, id: String) {
        when (kind) {
            KIND_PLAYLIST -> db.playlists().all().firstOrNull { PlaylistKeys.of(it) == id }?.let { repo.deletePlaylist(it.id) }
            KIND_PREFS -> db.playlists().all().firstOrNull { PlaylistKeys.of(it) == id }?.let {
                db.categories().deletePrefsWithPrefix("${it.id}:")
                db.channelPrefs().deleteWithPrefix("${it.id}:")
            }
            KIND_EPG -> db.epg().sources().firstOrNull { !it.auto && VaultCrypto.sha256(it.url).take(24) == id }?.let { repo.deleteEpgSource(it) }
            KIND_KEY -> settings.update { SealedKeys.set(it, id, "") ?: it }
        }
    }

    // ------------------------------------------------------------------ Outils

    private fun PlaylistEntity.toSealed() = SealedPlaylist(name, type.name, url, username, password, mac, userAgent)

    /** Un fichier sur cet appareil (content://, file://) n'a pas de sens sur les autres. */
    private fun portable(url: String) = url.startsWith("http://", true) || url.startsWith("https://", true)

    private fun <T> encode(serializer: KSerializer<T>, value: T) = json.encodeToString(serializer, value)

    private fun context(session: CloudSession, kind: String, id: String) = "vyzo|${session.userId}|$kind|$id"

    private fun row(kind: String, id: String, payload: String, updatedAt: Long, deleted: Boolean) = buildJsonObject {
        put("kind", kind); put("id", id); put("payload", payload); put("updated_at", updatedAt); put("deleted", deleted)
    }

    companion object {
        private const val TAG = "SealedSync"
        private const val BATCH = 200
        const val KIND_VAULT = "vault"
        const val VAULT_ID = "key"
        const val KIND_PLAYLIST = "playlist"
        const val KIND_PREFS = "prefs"
        const val KIND_EPG = "epg"
        const val KIND_SETTINGS = "settings"
        const val SETTINGS_ID = "main"
        const val KIND_KEY = "key"

        private fun slot(kind: String, id: String) = "$kind|$id"
    }
}
