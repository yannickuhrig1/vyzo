package fr.yannickuhrig.vyzo.data.cloud

import fr.yannickuhrig.vyzo.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import okio.Path.Companion.toOkioPath
import fr.yannickuhrig.vyzo.data.db.withTransaction
import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.platform.Platform
import fr.yannickuhrig.vyzo.data.db.DEFAULT_PROFILE_NAME
import fr.yannickuhrig.vyzo.data.db.FAVORITES_GROUP_ID
import fr.yannickuhrig.vyzo.data.db.FAVORITES_SYNC_ID
import fr.yannickuhrig.vyzo.data.db.PlaylistKeys
import fr.yannickuhrig.vyzo.data.db.ProfileEntity
import fr.yannickuhrig.vyzo.data.db.UserGroupEntity
import fr.yannickuhrig.vyzo.data.db.UserGroupItemEntity
import fr.yannickuhrig.vyzo.data.db.WatchProgressEntity
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.data.profile.ProfileManager
import fr.yannickuhrig.vyzo.data.remote.CastCommand
import fr.yannickuhrig.vyzo.data.remote.HistoryClearEvent
import fr.yannickuhrig.vyzo.data.remote.ScheduleSyncEvent
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.util.TextNorm
import fr.yannickuhrig.vyzo.util.bool
import fr.yannickuhrig.vyzo.util.int
import fr.yannickuhrig.vyzo.util.long
import fr.yannickuhrig.vyzo.util.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Ce qui est gardé sur l'appareil pour le compte Vyzo : session et curseurs de synchro. */
@Serializable
data class CloudData(
    val session: CloudSession? = null,
    /** Dernier synced_at reçu, par table. */
    val cursors: Map<String, String> = emptyMap(),
    val lastSyncAt: Long = 0,
    /** Programmations faites ici, pas encore envoyées au compte (hors ligne, serveur injoignable). */
    val recordingOutbox: List<ScheduleSyncEvent> = emptyList(),
    /** Clé commune aux appareils du compte (voir [SealedSync]), comme les jetons : gardée dans le dossier privé de l'appli. */
    val vaultKey: String = "",
    val vaultUserId: String = "",
    /** La clé du serveur ne s'ouvre plus avec le mot de passe du compte (réinitialisé). */
    val vaultLost: Boolean = false,
    /** Empreinte de chaque élément chiffré au dernier échange avec le serveur. */
    val sealedKnown: Map<String, String> = emptyMap(),
    /** Identifiant de cette installation dans la table « devices » (tiré au hasard à la première synchro). */
    val deviceId: String = "",
    /** Dernière demande de mise à jour à distance déjà prise en compte (devices.update_requested_at). */
    val updateHandledAt: Long = 0,
    /** Titres chiffrés déjà envoyés pour la progression et les listes antérieures (compte [metaUserId]). */
    val metaUserId: String = "",
)

/** [file] doit se terminer par ".preferences_pb" (Android : filesDir/datastore/cloud.preferences_pb). */
class CloudStore(file: java.io.File) {
    private val ds: DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { file.absoluteFile.toOkioPath() },
    )
    private val key = stringPreferencesKey("cloud")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val flow: Flow<CloudData> = ds.data.catch { emit(emptyPreferences()) }.map { decode(it[key]) }

    suspend fun get(): CloudData = flow.first()

    suspend fun update(transform: (CloudData) -> CloudData) {
        ds.edit { it[key] = json.encodeToString(CloudData.serializer(), transform(decode(it[key]))) }
    }

    private fun decode(raw: String?): CloudData =
        if (raw.isNullOrBlank()) CloudData() else runCatching { json.decodeFromString(CloudData.serializer(), raw) }.getOrDefault(CloudData())
}

data class CloudStatus(
    /** L'appli a été compilée avec l'adresse du serveur Vyzo. */
    val configured: Boolean = false,
    val email: String? = null,
    val running: Boolean = false,
    val lastSyncAt: Long = 0,
    val error: String? = null,
    val vault: VaultState = VaultState.NONE,
    /** Bilan de la dernière connexion : playlists IPTV récupérées depuis le compte, ou pourquoi il n'y en a pas. */
    val restored: String? = null,
) {
    val signedIn: Boolean get() = email != null
}

/**
 * Synchronise profils, progression de lecture, favoris et groupes avec le compte Vyzo (Supabase).
 *
 * Chaque ligne porte une date de modification : la version la plus récente l'emporte, côté serveur comme côté appareil.
 * Envoi : lignes marquées « dirty » et suppressions en attente. Réception : lignes modifiées depuis le dernier passage.
 * Les contenus sont identifiés par une clé stable ([PlaylistKeys]) : une progression enregistrée sur la TV retrouve son
 * film sur le téléphone tant que les deux ont la même playlist (même compte Xtream, même portail, même adresse M3U).
 */
@OptIn(FlowPreview::class)
class CloudSync(
    private val api: CloudApi,
    private val store: CloudStore,
    private val db: AppDatabase,
    private val repo: Repository,
    private val profiles: ProfileManager,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    /** Notifications instantanées des autres appareils (désactivées dans les tests sur faux serveur). */
    realtimeEnabled: Boolean = false,
    val platform: Platform? = null,
) {
    private val _status = MutableStateFlow(CloudStatus(configured = api.configured))
    val status: StateFlow<CloudStatus> = _status.asStateFlow()

    private val mutex = Mutex()
    private val tokenMutex = Mutex()
    private val pushRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val pullRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var lastFullSync = 0L
    @Volatile private var foreground = false
    /** Arrière-plan où l'appli reste joignable (Vyzo PC réduit dans la zone de notification, qui enregistre). */
    @Volatile private var keepConnected = false

    /**
     * Programmation reçue d'un autre appareil du compte (table « recordings », ou ancien message temps réel).
     * Branché par le conteneur sur le planificateur d'enregistrements.
     */
    var onRemoteRecording: (suspend (ScheduleSyncEvent) -> Unit)? = null

    /** Événements Cast reçus en temps réel depuis un autre appareil. */
    val incomingCastCommands = kotlinx.coroutines.flow.MutableSharedFlow<CastCommand>(extraBufferCapacity = 8)

    /** Événements de purge d'historique reçus en temps réel depuis un autre appareil. */
    val incomingHistoryClear = kotlinx.coroutines.flow.MutableSharedFlow<HistoryClearEvent>(extraBufferCapacity = 8)

    /**
     * Mise à jour de l'appli demandée à distance (site de gestion du compte). Branché par le conteneur sur le programme
     * de mise à jour, qui rend compte avec [reportUpdateStatus].
     */
    val updateRequests = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    @Volatile private var myDeviceId = ""
    @Volatile private var lastDeviceReport = 0L
    private val titles = ContentTitles(db)

    /**
     * Pendant que l'appli est à l'écran, une connexion permanente au serveur prévient cet appareil dès qu'un autre
     * modifie le compte : la progression ou un favori ajouté sur le téléphone arrive aussitôt sur la TV.
     */
    private val realtime: RealtimeClient? =
        if (realtimeEnabled && api.configured) {
            RealtimeClient(
                baseUrl = api.baseUrl, apiKey = api.apiKey, client = api.client, scope = scope,
                tables = listOf(T_PROFILES, T_PROGRESS, T_LISTS, T_ITEMS, T_RECORDINGS, T_SEALED, T_DEVICES),
                auth = { realtimeAuth() },
                onChange = onChange@{ table, record ->
                    // Les appareils se signalent à chaque synchro : on ne regarde que les demandes adressées à celui-ci,
                    // sans relancer de synchro (sinon chaque signalement réveillerait tous les autres).
                    if (table == T_DEVICES) {
                        if (record != null && record.str("id") == myDeviceId) {
                            scope.launch { runCatching { onUpdateRequested(record.long("update_requested_at")) } }
                        }
                        return@onChange
                    }
                    if (table == T_PROGRESS && record != null) {
                        scope.launch {
                            runCatching {
                                db.withTransaction { applyProgress(listOf(record)) }
                            }
                        }
                    }
                    pullRequests.tryEmit(Unit)
                },
                onCastCommand = { cmd ->
                    incomingCastCommands.tryEmit(cmd)
                },
                // Ancien envoi des versions 1.8.0 et antérieures (message seul, sans ligne dans la table).
                onScheduleSync = { ev ->
                    if (ev.senderDeviceId == null || ev.senderDeviceId != platform?.deviceModel) {
                        scope.launch { runCatching { onRemoteRecording?.invoke(ev) } }
                    }
                },
                onHistoryClear = { ev ->
                    incomingHistoryClear.tryEmit(ev)
                },
                onHistoryRemove = { profileId, keys ->
                    scope.launch { runCatching { repo.removeProgress(profileId, keys) }.onFailure { Log.w(TAG, "retrait d'historique", it) } }
                },
            )
        } else {
            null
        }

    /** État de la connexion instantanée (OFF sans compte, en arrière-plan ou sans réseau). */
    val realtimeState: StateFlow<RealtimeClient.State> = realtime?.state ?: MutableStateFlow(RealtimeClient.State.OFF)

    private val sealed = SealedSync(
        object : SealedRemote {
            override suspend fun select(query: String) = authed { api.select(T_SEALED, query, it) }.mapNotNull { it as? JsonObject }
            override suspend fun upsert(rows: JsonArray) = authed { api.upsert(T_SEALED, "user_id,kind,id", rows, it) }
            override suspend fun deleteAll() {
                val userId = store.get().session?.userId ?: return
                authed { api.delete(T_SEALED, "user_id=eq.$userId", it) }
            }
        },
        store, db, repo, settings,
    )

    fun start() {
        scope.launch {
            val d = store.get()
            _status.value = _status.value.copy(email = d.session?.email, lastSyncAt = d.lastSyncAt, vault = sealed.state())
            if (d.session != null) {
                if (foreground) realtime?.start()
                sync()
            }
        }
        scope.launch { merge(repo.localChanges, profiles.changed, sealed.localChanges()).collect { pushRequests.tryEmit(Unit) } }
        scope.launch { pushRequests.debounce(PUSH_DELAY_MS).collect { if (_status.value.signedIn) sync(pull = false) } }
        // Plusieurs notifications rapprochées (un épisode enregistre sa position, puis la série…) : une seule synchro.
        scope.launch { pullRequests.debounce(PULL_DELAY_MS).collect { if (_status.value.signedIn) sync() } }
        // Réduit dans la zone de notification : relève régulière, au cas où la connexion instantanée aurait décroché.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(BACKGROUND_PULL_MS)
                if (_status.value.signedIn && !foreground && keepConnected) sync()
            }
        }
        scope.launch {
            incomingHistoryClear.collect { ev ->
                val myId = platform?.deviceModel
                if (ev.senderDeviceId != null && ev.senderDeviceId == myId) return@collect
                repo.clearWatchHistory(
                    profileId = ev.profileId,
                    adultOnly = ev.adultOnly,
                    categoryUid = ev.categoryUid,
                    keyword = ev.keyword,
                    itemUid = ev.itemUid,
                )
            }
        }
    }

    /** Retour au premier plan : connexion instantanée, et on récupère ce qui a changé ailleurs. */
    fun onForeground() {
        foreground = true
        if (!_status.value.signedIn) return
        realtime?.start()
        if (System.currentTimeMillis() - lastFullSync < 60_000) return
        scope.launch { sync() }
    }

    /**
     * Passage en arrière-plan : on envoie tout de suite ce qui reste et on ferme la connexion instantanée,
     * sauf si [stayConnected] (Vyzo PC dans la zone de notification doit recevoir les programmations du téléphone).
     */
    fun onBackground(stayConnected: Boolean = false) {
        foreground = false
        keepConnected = stayConnected
        if (!stayConnected) realtime?.stop()
        if (_status.value.signedIn) scope.launch { sync(pull = false) }
    }

    private suspend fun realtimeAuth(): RealtimeAuth? {
        val s = store.get().session ?: return null
        return runCatching { RealtimeAuth(token(minValiditySec = 15 * 60L), s.userId) }.getOrNull()
    }

    // ------------------------------------------------------------------ Compte

    suspend fun signIn(email: String, password: String) {
        val s = api.signIn(email, password)
        onSignedIn(s, password)
    }

    suspend fun signUp(email: String, password: String): SignUpResult {
        val r = api.signUp(email, password)
        if (r is SignUpResult.SignedIn) onSignedIn(r.session, password)
        return r
    }

    suspend fun resendConfirmation(email: String) = api.resendConfirmation(email)

    suspend fun sendPasswordReset(email: String) = api.sendPasswordReset(email)

    /**
     * Appareil déjà connecté au compte, sans la clé : le mot de passe la lui donne. Le reste de la synchro ne repart pas de
     * zéro. Ce qui a été reçu sans la clé n'a pas été appliqué : on le reprend, sans rien supprimer ici.
     */
    suspend fun unlockVault(password: String) {
        val email = store.get().session?.email ?: throw CloudException("Pas de compte Vyzo connecté")
        val s = api.signIn(email, password)
        store.update { it.copy(session = s, sealedKnown = emptyMap(), cursors = it.cursors - T_SEALED) }
        val vault = sealed.unlock(s, password)
        _status.value = _status.value.copy(vault = vault, error = null)
        val before = playlistKeys()
        sync()
        reportNothingRestored(before)
    }

    /**
     * Clé perdue après une réinitialisation du mot de passe : cet appareil repart avec une nouvelle clé et renvoie ses
     * playlists, son guide, ses réglages et ses chaînes. Le mot de passe est vérifié d'abord.
     */
    suspend fun resetVault(password: String) {
        val email = store.get().session?.email ?: throw CloudException("Pas de compte Vyzo connecté")
        val s = api.signIn(email, password)
        store.update { it.copy(session = s) }
        sealed.reset(s, password)
        _status.value = _status.value.copy(vault = sealed.state(), error = null)
        sync()
    }

    private suspend fun onSignedIn(s: CloudSession, password: String) {
        val data = store.get()
        val previous = data.session
        // Autre compte que le précédent : tout ce qui est local doit partir vers le nouveau.
        if (previous != null && previous.userId != s.userId) markAllDirty()
        // Même compte : la clé reste, c'est elle qui permet de survivre à une réinitialisation du mot de passe.
        store.update {
            if (data.vaultUserId == s.userId) CloudData(session = s, vaultKey = data.vaultKey, vaultUserId = s.userId) else CloudData(session = s)
        }
        // Un serveur sans la table chiffrée (ou injoignable) n'empêche pas la connexion au compte.
        val vault = runCatching { sealed.unlock(s, password) }.onFailure { Log.w(TAG, "clé du compte", it) }.getOrNull() ?: sealed.state()
        _status.value = _status.value.copy(email = s.email, error = null, lastSyncAt = 0, vault = vault, restored = null)
        if (foreground) realtime?.start()
        val before = playlistKeys()
        sync()
        reportNothingRestored(before)
    }

    private suspend fun playlistKeys(): Set<String> = db.playlists().all().mapTo(HashSet()) { PlaylistKeys.of(it) }

    /** Playlists IPTV arrivées du compte pendant une synchro (à la connexion, ou plus tard depuis un autre appareil). */
    private suspend fun reportRestored(before: Set<String>) {
        val restored = db.playlists().all().filter { PlaylistKeys.of(it) !in before }.map { it.name }
        if (restored.isEmpty()) return
        announce(restoredMessage(restored, before.isNotEmpty(), sealed.state()) ?: return)
    }

    /**
     * Après une connexion, sur un appareil sans playlist : rien n'est arrivé du compte, on dit pourquoi
     * (le plus souvent, l'appareil qui a la playlist n'a jamais reçu la clé du compte).
     */
    private suspend fun reportNothingRestored(before: Set<String>) {
        if (before.isNotEmpty() || db.playlists().all().isNotEmpty()) return
        announce(restoredMessage(emptyList(), hadPlaylists = false, vault = sealed.state()) ?: return)
    }

    private fun announce(msg: String) {
        _status.value = _status.value.copy(restored = msg)
        platform?.toast(msg, long = true)
    }

    suspend fun signOut() {
        realtime?.stop()
        val s = store.get().session
        mutex.withLock {
            store.update { CloudData() }
            markAllDirty()
        }
        _status.value = CloudStatus(configured = api.configured)
        if (s != null) runCatching { api.signOut(s.accessToken) }
    }

    /** Diffuse une commande Cast (lecture distante) aux autres appareils du compte. */
    fun sendCastCommand(command: CastCommand): Boolean {
        return realtime?.broadcastCast(command) ?: false
    }

    /**
     * Programmation ajoutée, annulée, supprimée ou changée d'état ici : elle part dans la table « recordings » du compte,
     * où l'appareil qui enregistre la retrouvera même s'il était éteint ou en arrière-plan à ce moment-là.
     */
    fun sendScheduleSync(event: ScheduleSyncEvent) {
        scope.launch {
            if (store.get().session == null) return@launch
            val stamped = event.copy(updatedAt = event.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
            store.update { it.copy(recordingOutbox = it.recordingOutbox + stamped) }
            sync(pull = false)
        }
    }

    /**
     * Purge l'historique de visionnage localement, sur Supabase et diffuse un événement Realtime aux autres appareils.
     */
    suspend fun purgeHistory(
        profileId: String? = null,
        clearAll: Boolean = true,
        adultOnly: Boolean = false,
        categoryUid: String? = null,
        keyword: String? = null,
        itemUid: String? = null,
    ) {
        val targetProfile = profileId ?: profiles.activeIdNow()
        val affectedKeys = repo.clearWatchHistory(
            profileId = targetProfile,
            adultOnly = adultOnly,
            categoryUid = categoryUid,
            keyword = keyword,
            itemUid = itemUid,
        )
        // Suppression sur Supabase si connecté
        if (_status.value.signedIn && db.profiles().get(targetProfile)?.localOnly != true) {
            runCatching {
                deleteCloudHistory(targetProfile, if (clearAll) emptyList() else affectedKeys)
            }
        }
        // Un profil local n'existe ni sur le compte ni sur les autres appareils.
        if (db.profiles().get(targetProfile)?.localOnly == true) return
        // Diffusion temps réel vers les autres appareils
        val event = HistoryClearEvent(
            profileId = targetProfile,
            clearAll = clearAll,
            adultOnly = adultOnly,
            categoryUid = categoryUid,
            keyword = keyword,
            itemUid = itemUid,
            contentKeys = affectedKeys,
            senderDeviceId = platform?.deviceModel,
        )
        realtime?.broadcastHistoryClear(event)
    }

    private suspend fun deleteCloudHistory(profileId: String?, contentKeys: List<String>) {
        val s = store.get().session ?: return
        val filter = buildString {
            append("user_id=eq.").append(s.userId)
            if (!profileId.isNullOrBlank()) {
                append("&profile_id=eq.").append(profileId)
            }
            if (contentKeys.isNotEmpty()) {
                val inFilter = contentKeys.joinToString(",") { CloudApi.enc(it) }
                append("&content_key=in.(").append(inFilter).append(")")
            }
        }
        val tables = listOf(T_PROGRESS, "watch_history", "playback_positions")
        for (t in tables) {
            runCatching {
                authed { token -> api.delete(t, filter, token) }
            }
        }
    }

    private suspend fun markAllDirty() {
        db.profiles().markAllDirty()
        db.progress().markAllDirty()
        db.groups().markAllItemsDirty()
        db.groups().markAllGroupsDirty()
    }

    // ------------------------------------------------------------------ Synchro

    /** Synchronise maintenant. [pull] = false : envoi seulement (après une modification locale). */
    suspend fun sync(pull: Boolean = true): Result<Unit> = mutex.withLock {
        val data = store.get()
        val session = data.session ?: return@withLock Result.failure(CloudException("Pas de compte Vyzo connecté"))
        _status.value = _status.value.copy(running = true, error = null)
        val before = if (pull) playlistKeys() else emptySet()
        val res = runCatching {
            if (pull && data.cursors.isEmpty()) reconcileProfiles(session)
            // Avant l'envoi : à la première connexion, ce qui est déjà sur le compte l'emporte sur la version de cet appareil.
            if (pull) runCatching { pullSealed(session) }.onFailure { Log.w(TAG, "réception des données chiffrées", it) }
            runCatching { sealed.push(session) }.onFailure { Log.w(TAG, "envoi des données chiffrées", it) }
            backfillTitles(session)
            push(session)
            if (pull) {
                pullAll(session)
                repo.attachToPlaylists()
                profiles.ensureOne()
                lastFullSync = System.currentTimeMillis()
            }
            // Table absente d'un ancien serveur, réseau coupé… : le reste de la synchro n'en dépend pas.
            runCatching { reportDevice(session, force = false) }.onFailure { Log.w(TAG, "signalement de l'appareil", it) }
            Unit
        }
        res.onSuccess {
            val now = System.currentTimeMillis()
            if (pull) store.update { it.copy(lastSyncAt = now) }
            _status.value = _status.value.copy(running = false, error = null, lastSyncAt = if (pull) now else _status.value.lastSyncAt, vault = sealed.state())
            if (pull) runCatching { reportRestored(before) }
        }.onFailure { e ->
            Log.w(TAG, "sync", e)
            if ((e as? CloudException)?.code == CODE_SESSION_EXPIRED) {
                // Session révoquée ou expirée : il faut se reconnecter.
                realtime?.stop()
                store.update { it.copy(session = null) }
                _status.value = CloudStatus(configured = api.configured, error = e.message)
            } else {
                _status.value = _status.value.copy(running = false, error = e.message ?: e.toString())
            }
        }
        res
    }

    /**
     * Envoie immédiatement la progression de lecture vers Supabase sans attendre le délai de debounce.
     */
    suspend fun pushWatchProgressImmediate(): Result<Unit> {
        if (!_status.value.signedIn) return Result.success(Unit)
        return sync(pull = false)
    }

    /**
     * Récupère la progression de lecture stockée sur le Cloud pour comparer la fraîcheur avec la base locale.
     */
    suspend fun fetchCloudProgress(profileId: String, contentKey: String): WatchProgressEntity? {
        if (!_status.value.signedIn || db.profiles().get(profileId)?.localOnly == true) return null
        return runCatching {
            val s = store.get().session ?: return null
            val q = "user_id=eq.${s.userId}&profile_id=eq.$profileId&content_key=eq.${CloudApi.enc(contentKey)}&select=*&limit=1"
            val rows = authed { api.select(T_PROGRESS, q, it) }
            val r = rows.firstOrNull() as? JsonObject ?: return null
            val resolver = UidResolver(db)
            val key = r.str("content_key")
            val parentKey = r.str("parent_key")
            WatchProgressEntity(
                profileId = r.str("profile_id"),
                contentKey = key,
                uid = resolver.uid(key),
                kind = kindOf(r.str("kind")),
                parentUid = if (parentKey.isBlank()) "" else resolver.uid(parentKey),
                parentKey = parentKey,
                positionMs = r.long("position_ms"),
                durationMs = r.long("duration_ms"),
                updatedAt = r.long("updated_at"),
                dirty = false,
            )
        }.getOrNull()
    }

    /**
     * Jeton d'accès valide au moins [minValiditySec] secondes (rafraîchi si besoin). Un seul rafraîchissement à la fois :
     * la synchro et la connexion instantanée peuvent en demander un en même temps.
     */
    private suspend fun token(force: Boolean = false, minValiditySec: Long = 120): String = tokenMutex.withLock {
        val s = store.get().session ?: throw CloudException("Pas de compte Vyzo connecté")
        val nowSec = System.currentTimeMillis() / 1000
        if (!force && s.expiresAt - nowSec >= minValiditySec) return@withLock s.accessToken
        val fresh = try {
            api.refresh(s.refreshToken)
        } catch (e: CloudException) {
            if (e.isAuth || e.status == 400) throw CloudException("Session expirée : reconnecte-toi au compte Vyzo", CODE_SESSION_EXPIRED, 401)
            throw e
        }
        store.update { it.copy(session = fresh.copy(email = fresh.email.ifBlank { s.email })) }
        fresh.accessToken
    }

    private suspend fun <T> authed(block: suspend (String) -> T): T = try {
        block(token())
    } catch (e: CloudException) {
        if (e.status == 401) block(token(force = true)) else throw e
    }

    // ------------------------------------------------------------------ Envoi

    private suspend fun push(session: CloudSession) {
        // Un profil local ne quitte jamais l'appareil : ni lui, ni ce qui lui appartient.
        db.profiles().allWithDeleted().filter { it.localOnly }.forEach { db.tombstones().deleteProfile(it.id) }

        // Profils (y compris supprimés : la suppression doit atteindre les autres appareils)
        val dirtyProfiles = db.profiles().dirty().filter { !it.localOnly }
        if (dirtyProfiles.isNotEmpty()) {
            authed { api.upsert(T_PROFILES, "user_id,id", JsonArray(dirtyProfiles.map { it.toJson() }), it) }
            dirtyProfiles.forEach { p ->
                if (p.deleted) db.profiles().hardDelete(p.id) else db.profiles().markClean(p.id, p.updatedAt)
            }
        }
        val known = db.profiles().allWithDeleted().filter { !it.localOnly }.map { it.id }.toSet()

        // Groupes perso
        val dirtyGroups = db.groups().dirtyGroups().filter { it.profileId in known && it.syncId.isNotBlank() }
        if (dirtyGroups.isNotEmpty()) {
            authed { api.upsert(T_LISTS, "user_id,profile_id,id", JsonArray(dirtyGroups.map { it.toJson() }), it) }
            dirtyGroups.forEach { db.groups().markGroupClean(it.id, it.updatedAt) }
        }

        // Suppressions en attente
        while (true) {
            val tombs = db.tombstones().pending(BATCH).filter { it.profileId in known }
            if (tombs.isEmpty()) break
            val lists = tombs.filter { it.entity == Repository.TOMB_GROUP }
            val items = tombs.filter { it.entity == Repository.TOMB_ITEM }
            if (lists.isNotEmpty()) authed {
                api.upsert(
                    T_LISTS, "user_id,profile_id,id",
                    JsonArray(lists.map { t -> listJson(t.profileId, t.key, "", 0, t.deletedAt, deleted = true) }), it,
                )
            }
            if (items.isNotEmpty()) authed {
                api.upsert(
                    T_ITEMS, "user_id,profile_id,list_id,content_key",
                    JsonArray(
                        items.map { t ->
                            itemJson(t.profileId, t.key.substringBefore('|'), t.key.substringAfter('|'), "", 0, 0, t.deletedAt, deleted = true)
                        },
                    ),
                    it,
                )
            }
            tombs.forEach { db.tombstones().done(it.entity, it.profileId, it.key, it.deletedAt) }
            if (tombs.size < BATCH) break
        }

        // Éléments des groupes et favoris
        val groupSync = db.groups().allGroups().associate { it.id to it.syncId }
        val vaultKey = vaultKey(session)
        while (true) {
            val items = db.groups().dirtyItems(BATCH)
            if (items.isEmpty()) break
            val rows = items.filter { it.profileId in known && !groupSync[it.groupId].isNullOrBlank() }.map {
                withMeta(
                    itemJson(it.profileId, groupSync.getValue(it.groupId), it.contentKey, it.kind.name, it.position, it.addedAt, it.updatedAt, false),
                    session, vaultKey, it.contentKey, it.itemUid, it.kind,
                )
            }
            upsertUniform(T_ITEMS, "user_id,profile_id,list_id,content_key", rows)
            items.forEach { db.groups().markItemClean(it.profileId, it.groupId, it.contentKey, it.updatedAt) }
            if (items.size < BATCH) break
        }

        // Progression
        while (true) {
            val rows = db.progress().dirty(BATCH)
            if (rows.isEmpty()) break
            val send = rows.filter { it.profileId in known }.map { withMeta(it.toJson(), session, vaultKey, it.contentKey, it.uid, it.kind, it.parentUid) }
            upsertUniform(T_PROGRESS, "user_id,profile_id,content_key", send)
            rows.forEach { db.progress().markClean(it.profileId, it.contentKey, it.updatedAt) }
            if (rows.size < BATCH) break
        }

        // Programmations : un échec (table absente d'un ancien serveur…) ne bloque pas le reste de la synchro.
        runCatching { pushRecordings() }.onFailure { Log.w(TAG, "envoi des programmations", it) }
    }

    // ------------------------------------------------------------------ Titres chiffrés

    /** Serveur sans la colonne « meta » (migration pas encore passée) : on envoie sans titre. */
    @Volatile private var metaUnsupported = false

    private suspend fun vaultKey(session: CloudSession): ByteArray? {
        val d = store.get()
        return if (d.vaultUserId == session.userId) VaultCrypto.decode(d.vaultKey)?.takeIf { it.size == VaultCrypto.KEY_BYTES } else null
    }

    /** Ajoute le titre chiffré quand la clé du compte est là et que le contenu est connu ici ; sinon la ligne part sans. */
    private suspend fun withMeta(
        row: JsonObject, session: CloudSession, key: ByteArray?, contentKey: String, uid: String, kind: ContentKind, parentUid: String = "",
    ): JsonObject {
        if (key == null || metaUnsupported) return row
        val meta = runCatching { titles.of(uid, kind, parentUid) }.getOrNull() ?: return row
        return JsonObject(row + ("meta" to kotlinx.serialization.json.JsonPrimitive(titles.seal(key, session.userId, contentKey, meta))))
    }

    /**
     * PostgREST veut les mêmes colonnes sur toutes les lignes d'un envoi groupé : celles avec titre et celles sans partent
     * séparément (une ligne sans « meta » garde le titre déjà connu du serveur).
     */
    private suspend fun upsertUniform(table: String, onConflict: String, rows: List<JsonObject>) {
        for (group in rows.groupBy { "meta" in it }.values) {
            try {
                authed { api.upsert(table, onConflict, JsonArray(group), it) }
            } catch (e: CloudException) {
                if (e.status != 400 || group.none { "meta" in it } || e.message?.contains("meta") != true) throw e
                metaUnsupported = true
                authed { api.upsert(table, onConflict, JsonArray(group.map { JsonObject(it - "meta") }), it) }
            }
        }
    }

    /** Une fois par compte : renvoie la progression et les listes existantes, cette fois avec leurs titres. */
    private suspend fun backfillTitles(session: CloudSession) {
        val d = store.get()
        if (d.metaUserId == session.userId || vaultKey(session) == null) return
        db.progress().markAllDirty()
        db.groups().markAllItemsDirty()
        store.update { it.copy(metaUserId = session.userId) }
    }

    // ------------------------------------------------------------------ Appareils

    /** Identifiant de cet appareil dans la table « devices » (celui que vise le site du compte). */
    suspend fun thisDeviceId(): String = deviceId()

    private suspend fun deviceId(): String {
        if (myDeviceId.isNotBlank()) return myDeviceId
        var id = store.get().deviceId
        if (id.isBlank()) {
            id = java.util.UUID.randomUUID().toString()
            store.update { it.copy(deviceId = id) }
        }
        myDeviceId = id
        return id
    }

    /**
     * Signale cet appareil au compte (nom, type, version, dernière activité) au plus toutes les [DEVICE_REPORT_MS], et
     * relit une éventuelle demande de mise à jour faite pendant qu'il était éteint.
     */
    private suspend fun reportDevice(session: CloudSession, force: Boolean) {
        val p = platform ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastDeviceReport < DEVICE_REPORT_MS) return
        val id = deviceId()
        val row = buildJsonObject {
            put("id", id)
            put("name", p.deviceName.take(120))
            put("kind", if (p.isDesktop) "pc" else if (p.isTv) "tv" else "mobile")
            put("model", p.deviceModel.take(120))
            put("system", p.systemVersion.take(120))
            put("app_version", p.appVersion.take(40))
            put("version_code", p.appVersionCode)
            put("last_seen_at", now)
        }
        authed { api.upsert(T_DEVICES, "user_id,id", JsonArray(listOf(row)), it) }
        lastDeviceReport = now
        val mine = authed { api.select(T_DEVICES, "select=update_requested_at&user_id=eq.${session.userId}&id=eq.${CloudApi.enc(id)}&limit=1", it) }
        (mine.firstOrNull() as? JsonObject)?.let { onUpdateRequested(it.long("update_requested_at")) }
    }

    private suspend fun onUpdateRequested(requestedAt: Long) {
        if (requestedAt <= 0) return
        // Une demande vieille de plus d'une journée (appareil resté éteint) n'est plus d'actualité.
        if (System.currentTimeMillis() - requestedAt > UPDATE_REQUEST_TTL_MS) return
        var fresh = false
        store.update {
            if (requestedAt > it.updateHandledAt) { fresh = true; it.copy(updateHandledAt = requestedAt) } else it
        }
        if (fresh) updateRequests.tryEmit(requestedAt)
    }

    /** Où en est la mise à jour demandée à distance : affiché sur le site du compte. */
    suspend fun reportUpdateStatus(text: String) {
        if (store.get().session == null) return
        val row = buildJsonObject {
            put("id", deviceId())
            put("update_status", text.take(300))
            put("update_status_at", System.currentTimeMillis())
        }
        runCatching { authed { api.upsert(T_DEVICES, "user_id,id", JsonArray(listOf(row)), it) } }
            .onFailure { Log.w(TAG, "état de la mise à jour", it) }
    }

    private suspend fun pushRecordings() {
        val outbox = store.get().recordingOutbox
        if (outbox.isEmpty()) return
        val keys = db.playlists().all().associate { it.id to PlaylistKeys.of(it) }
        // Une seule ligne par programmation dans un même envoi : la dernière version.
        val rows = outbox.mapNotNull { ev -> recordingJson(ev, keys) }.associateBy { it.str("id") }.values
        if (rows.isNotEmpty()) authed { api.upsert(T_RECORDINGS, "user_id,id", JsonArray(rows.toList()), it) }
        // Les programmations ajoutées pendant l'envoi restent dans la file.
        store.update { it.copy(recordingOutbox = it.recordingOutbox.drop(outbox.size)) }
    }

    // ------------------------------------------------------------------ Réception

    private suspend fun pullAll(session: CloudSession) {
        pullTable(session, T_PROFILES) { applyProfiles(it) }
        pullTable(session, T_LISTS) { applyLists(it) }
        pullTable(session, T_ITEMS) { applyItems(it) }
        pullTable(session, T_PROGRESS) { applyProgress(it) }
        runCatching { pullRecordings(session) }.onFailure { Log.w(TAG, "réception des programmations", it) }
    }

    private suspend fun pullSealed(session: CloudSession) {
        if (sealed.state() != VaultState.OPEN) return
        val received = ArrayList<JsonObject>()
        pullTable(session, T_SEALED) { received += it }
        if (received.isNotEmpty()) sealed.apply(session, received)
    }

    private suspend fun pullRecordings(session: CloudSession) {
        val received = ArrayList<JsonObject>()
        pullTable(session, T_RECORDINGS) { received += it }
        val handler = onRemoteRecording ?: return
        val me = platform?.deviceModel
        val resolver = UidResolver(db)
        val now = System.currentTimeMillis()
        for (r in received) {
            if (me != null && r.str("sender") == me) continue
            val ev = r.toScheduleEvent(resolver.uid(r.str("channel_key"))) ?: continue
            // Une programmation passée n'a plus à être ajoutée (premier passage d'un appareil sur un vieux compte).
            if (ev.action == "upsert" && ev.stop <= now) continue
            runCatching { handler(ev) }.onFailure { Log.w(TAG, "programmation reçue", it) }
        }
    }

    private suspend fun pullTable(session: CloudSession, table: String, apply: suspend (List<JsonObject>) -> Unit) {
        val start = store.get().cursors[table]
        var after = start?.let { overlap(it) }
        var newest = start
        while (true) {
            val q = buildString {
                append("select=*&user_id=eq.").append(session.userId).append("&order=synced_at.asc&limit=").append(PAGE)
                if (after != null) append("&synced_at=gt.").append(CloudApi.enc(after!!))
            }
            val rows = authed { api.select(table, q, it) }.mapNotNull { it as? JsonObject }
            if (rows.isEmpty()) break
            db.withTransaction { apply(rows) }
            val last = rows.last().str("synced_at")
            if (newest == null || last > newest!!) newest = last
            after = last
            if (rows.size < PAGE) break
        }
        if (newest != null && newest != start) store.update { it.copy(cursors = it.cursors + (table to newest!!)) }
    }

    private suspend fun applyProfiles(rows: List<JsonObject>) {
        for (r in rows) {
            val id = r.str("id")
            val updated = r.long("updated_at")
            val local = db.profiles().get(id)
            if (local?.localOnly == true || (local != null && local.updatedAt > updated)) continue
            if (r.bool("deleted")) {
                if (local != null) removeProfileLocally(id)
            } else {
                db.profiles().upsert(r.toProfile())
            }
        }
    }

    private suspend fun removeProfileLocally(id: String) {
        db.progress().deleteProfile(id)
        db.progress().deleteHistory(id)
        db.groups().deleteProfileItems(id)
        db.groups().deleteProfileGroups(id)
        db.profiles().hardDelete(id)
        if (settings.get().activeProfileId == id) {
            settings.update { s -> s.copy(activeProfileId = "") }
        }
    }

    private suspend fun applyLists(rows: List<JsonObject>) {
        for (r in rows) {
            val profileId = r.str("profile_id")
            if (db.profiles().get(profileId)?.deleted != false) continue
            val syncId = r.str("id")
            val updated = r.long("updated_at")
            val local = db.groups().bySyncId(profileId, syncId)
            if (local != null && local.updatedAt > updated) continue
            if (r.bool("deleted")) {
                if (local != null) {
                    db.groups().clearGroup(local.id)
                    db.groups().deleteGroup(local.id)
                }
            } else if (local == null) {
                db.groups().insertGroup(
                    UserGroupEntity(name = r.str("name"), position = r.int("position"), profileId = profileId, syncId = syncId, updatedAt = updated, dirty = false),
                )
            } else {
                db.groups().updateGroup(local.copy(name = r.str("name"), position = r.int("position"), updatedAt = updated, dirty = false))
            }
        }
    }

    private suspend fun applyItems(rows: List<JsonObject>) {
        val resolver = UidResolver(db)
        // Suppressions locales pas encore envoyées : si elles sont plus récentes, elles l'emportent.
        val pendingDeletes = db.tombstones().pending(Int.MAX_VALUE)
            .filter { it.entity == Repository.TOMB_ITEM }
            .associate { (it.profileId + "/" + it.key) to it.deletedAt }
        for (r in rows) {
            val profileId = r.str("profile_id")
            if (db.profiles().get(profileId)?.deleted != false) continue
            val listId = r.str("list_id")
            val groupId = if (listId == FAVORITES_SYNC_ID) FAVORITES_GROUP_ID else db.groups().bySyncId(profileId, listId)?.id ?: continue
            val key = r.str("content_key")
            val updated = r.long("updated_at")
            val local = db.groups().item(profileId, groupId, key)
            if (local != null && local.updatedAt > updated) continue
            if (r.bool("deleted")) {
                if (local != null) db.groups().removeItem(profileId, groupId, key)
            } else {
                val deletedAt = pendingDeletes[profileId + "/" + Repository.itemTombKey(listId, key)]
                if (deletedAt != null && deletedAt > updated) continue
                db.groups().upsertItem(
                    UserGroupItemEntity(
                        profileId = profileId, groupId = groupId, contentKey = key, itemUid = resolver.uid(key),
                        kind = kindOf(r.str("kind")), position = r.int("position"), addedAt = r.long("added_at"),
                        updatedAt = updated, dirty = false,
                    ),
                )
            }
        }
    }

    private suspend fun applyProgress(rows: List<JsonObject>) {
        val resolver = UidResolver(db)
        val out = ArrayList<WatchProgressEntity>()
        for (r in rows) {
            val profileId = r.str("profile_id")
            if (db.profiles().get(profileId)?.deleted != false) continue
            val key = r.str("content_key")
            val updated = r.long("updated_at")
            val local = db.progress().byKey(profileId, key)
            if (local != null && local.updatedAt >= updated) {
                if (local.updatedAt == updated && local.dirty) db.progress().markClean(profileId, key, updated)
                continue
            }
            val parentKey = r.str("parent_key")
            out += WatchProgressEntity(
                profileId = profileId, contentKey = key, uid = resolver.uid(key), kind = kindOf(r.str("kind")),
                parentUid = if (parentKey.isBlank()) "" else resolver.uid(parentKey), parentKey = parentKey,
                positionMs = r.long("position_ms"), durationMs = r.long("duration_ms"), updatedAt = updated, dirty = false,
            )
        }
        if (out.isNotEmpty()) db.progress().upsertAll(out)
    }

    /**
     * Première synchro de cet appareil avec le compte : les profils locaux qui correspondent à un profil du compte
     * (même nom, ou profil « Principal » jamais utilisé) sont fusionnés avec lui au lieu d'être dupliqués.
     */
    private suspend fun reconcileProfiles(session: CloudSession) {
        val remote = ArrayList<ProfileEntity>()
        var after: String? = null
        while (true) {
            val q = buildString {
                append("select=*&user_id=eq.").append(session.userId).append("&deleted=eq.false&order=synced_at.asc&limit=").append(PAGE)
                if (after != null) append("&synced_at=gt.").append(CloudApi.enc(after!!))
            }
            val rows = authed { api.select(T_PROFILES, q, it) }.mapNotNull { it as? JsonObject }
            remote += rows.map { it.toProfile() }
            if (rows.size < PAGE) break
            after = rows.last().str("synced_at")
        }
        if (remote.isEmpty()) return
        val matched = HashSet<String>()
        for (lp in db.profiles().all().filter { !it.localOnly }) {
            if (remote.any { it.id == lp.id }) { matched += lp.id; continue }
            val byName = remote.firstOrNull { it.id !in matched && norm(it.name) == norm(lp.name) }
            val untouchedDefault = lp.name == DEFAULT_PROFILE_NAME && !db.profiles().hasData(lp.id)
            val target = byName ?: if (untouchedDefault) remote.firstOrNull { it.id !in matched } else null
            if (target != null) {
                if (db.profiles().get(target.id) == null) db.profiles().upsert(target)
                profiles.mergeInto(lp.id, target.id)
                matched += target.id
            }
        }
    }

    // ------------------------------------------------------------------ Conversions

    private fun ProfileEntity.toJson() = buildJsonObject {
        put("id", id); put("name", name); put("color_index", colorIndex); put("is_kid", isKid); put("pin_hash", pinHash)
        put("position", position); put("updated_at", updatedAt); put("deleted", deleted)
    }

    private fun JsonObject.toProfile() = ProfileEntity(
        id = str("id"), name = str("name").ifBlank { "Profil" }, colorIndex = int("color_index"), isKid = bool("is_kid"),
        pinHash = str("pin_hash"), position = int("position"), updatedAt = long("updated_at"), deleted = false, dirty = false,
    )

    private fun UserGroupEntity.toJson() = listJson(profileId, syncId, name, position, updatedAt, deleted = false)

    private fun listJson(profileId: String, id: String, name: String, position: Int, updatedAt: Long, deleted: Boolean) = buildJsonObject {
        put("profile_id", profileId); put("id", id); put("name", name); put("position", position)
        put("updated_at", updatedAt); put("deleted", deleted)
    }

    private fun itemJson(
        profileId: String, listId: String, key: String, kind: String, position: Int, addedAt: Long, updatedAt: Long, deleted: Boolean,
    ) = buildJsonObject {
        put("profile_id", profileId); put("list_id", listId); put("content_key", key); put("kind", kind)
        put("position", position); put("added_at", addedAt); put("updated_at", updatedAt); put("deleted", deleted)
    }

    private fun WatchProgressEntity.toJson() = buildJsonObject {
        put("profile_id", profileId); put("content_key", contentKey); put("kind", kind.name); put("parent_key", parentKey)
        put("position_ms", positionMs); put("duration_ms", durationMs); put("updated_at", updatedAt)
    }

    /** Ligne de la table « recordings » ; null si la chaîne n'appartient à aucune playlist connue. */
    private fun recordingJson(ev: ScheduleSyncEvent, keys: Map<Long, String>): JsonObject? {
        val pid = PlaylistKeys.playlistIdOf(ev.channelUid) ?: return null
        val channelKey = PlaylistKeys.contentKey(ev.channelUid, pid, keys[pid] ?: return null) ?: return null
        val status = when (ev.action) {
            "cancel" -> "CANCELLED"
            else -> ev.status
        }
        return buildJsonObject {
            put("id", recordingId(channelKey, ev.start, ev.stop)); put("channel_key", channelKey); put("channel_name", ev.channelName)
            put("title", ev.title); put("start_ms", ev.start); put("stop_ms", ev.stop); put("status", status)
            put("error", ev.error.orEmpty()); put("sender", ev.senderDeviceId.orEmpty()); put("updated_at", ev.updatedAt)
            put("deleted", ev.action == "delete")
        }
    }

    /** Ligne reçue → événement à appliquer ([uid] : la chaîne sur cet appareil, vide si sa playlist n'y est pas). */
    private fun JsonObject.toScheduleEvent(uid: String): ScheduleSyncEvent? {
        if (uid.isBlank()) return null
        val status = str("status")
        val action = when {
            bool("deleted") -> "delete"
            status == "CANCELLED" -> "cancel"
            status == "SCHEDULED" || status == "RECORDING" -> "upsert"
            else -> "status"
        }
        return ScheduleSyncEvent(
            action = action, channelUid = uid, channelName = str("channel_name"), title = str("title"),
            start = long("start_ms"), stop = long("stop_ms"), status = status, error = str("error").ifBlank { null },
            senderDeviceId = str("sender").ifBlank { null }, updatedAt = long("updated_at"),
        )
    }

    /** Clé de contenu → uid local (vide si la playlist n'existe pas sur cet appareil). */
    private class UidResolver(private val db: AppDatabase) {
        private var map: Map<String, Long>? = null

        suspend fun uid(contentKey: String): String {
            val m = map ?: db.playlists().all().associate { PlaylistKeys.of(it) to it.id }.also { map = it }
            val pk = contentKey.substringBefore(':', "")
            val pid = m[pk] ?: return ""
            return PlaylistKeys.localUid(contentKey, pid, pk).orEmpty()
        }
    }

    companion object {
        private const val TAG = "VyzoCloud"
        const val CODE_SESSION_EXPIRED = "session_expired"
        const val T_PROFILES = "profiles"
        const val T_PROGRESS = "watch_progress"
        const val T_LISTS = "user_lists"
        const val T_ITEMS = "list_items"
        const val T_RECORDINGS = "recordings"
        const val T_SEALED = "sealed_items"
        const val T_DEVICES = "devices"
        private const val DEVICE_REPORT_MS = 5 * 60_000L
        private const val UPDATE_REQUEST_TTL_MS = 24 * 3600_000L

        /** Texte du bilan de connexion (null : rien à dire, l'appareil avait déjà ses playlists). */
        fun restoredMessage(restored: List<String>, hadPlaylists: Boolean, vault: VaultState): String? = when {
            restored.isNotEmpty() ->
                (if (restored.size == 1) "Playlist récupérée depuis ton compte : " else "Playlists récupérées depuis ton compte : ") +
                    restored.joinToString(", ") { "« ${it.trim()} »" } + ". Chargement des chaînes en cours…"
            hadPlaylists || vault != VaultState.OPEN -> null
            else -> "Aucune playlist IPTV sur ton compte pour l'instant. Ouvre Vyzo sur l'appareil où ton abonnement est configuré : " +
                "il te demandera une fois le mot de passe du compte, puis la playlist arrivera ici toute seule."
        }
        private const val BACKGROUND_PULL_MS = 10 * 60_000L

        fun recordingId(channelKey: String, start: Long, stop: Long) = "$channelKey|$start|$stop"
        private const val BATCH = 500
        private const val PAGE = 1000
        private const val PUSH_DELAY_MS = 8_000L
        private const val PULL_DELAY_MS = 1_500L
        private const val OVERLAP_MS = 30_000L

        private fun kindOf(s: String) = runCatching { ContentKind.valueOf(s) }.getOrDefault(ContentKind.MOVIE)

        private fun norm(s: String) = TextNorm.search(s).replace(Regex("\\s+"), " ")

        /**
         * Recule le curseur de quelques secondes : une écriture validée juste après notre dernier passage peut porter
         * une date légèrement antérieure. Les lignes reçues deux fois sont sans effet.
         */
        fun overlap(cursor: String): String {
            val ms = parseTimestamp(cursor) ?: return cursor
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return f.format(ms - OVERLAP_MS)
        }

        /** « 2026-10-28T07:12:33.123456+00:00 » → millisecondes UTC. */
        fun parseTimestamp(s: String): Long? {
            val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}(?::?\\d{2})?)?$").find(s.trim())
                ?: return null
            val g = m.groupValues
            val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
            }
            val frac = g[7].padEnd(3, '0').take(3).toIntOrNull() ?: 0
            var ms = cal.timeInMillis + frac
            val tz = g[8]
            if (tz.isNotEmpty() && tz != "Z") {
                val sign = if (tz[0] == '-') -1 else 1
                val digits = tz.substring(1).replace(":", "")
                val h = digits.take(2).toInt()
                val mn = digits.drop(2).take(2).toIntOrNull() ?: 0
                ms -= sign * (h * 3_600_000L + mn * 60_000L)
            }
            return ms
        }
    }
}
