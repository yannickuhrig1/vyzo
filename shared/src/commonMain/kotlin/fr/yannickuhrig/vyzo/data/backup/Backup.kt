package fr.yannickuhrig.vyzo.data.backup

import fr.yannickuhrig.vyzo.data.db.withTransaction
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.db.CategoryPrefEntity
import fr.yannickuhrig.vyzo.data.db.ChannelPrefEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.DEFAULT_PROFILE_NAME
import fr.yannickuhrig.vyzo.data.db.EpgMode
import fr.yannickuhrig.vyzo.data.db.EpgSourceEntity
import fr.yannickuhrig.vyzo.data.db.FAVORITES_GROUP_ID
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.PlaylistKeys
import fr.yannickuhrig.vyzo.data.db.ProfileEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.db.SyncTombstoneEntity
import fr.yannickuhrig.vyzo.data.db.UserGroupEntity
import fr.yannickuhrig.vyzo.data.db.UserGroupItemEntity
import fr.yannickuhrig.vyzo.data.prefs.AppSettings
import fr.yannickuhrig.vyzo.data.remote.ScheduleSyncEvent
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.platform.BackupFile
import fr.yannickuhrig.vyzo.platform.Platform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class BackupPlaylist(
    val id: Long, val name: String, val type: String, val url: String,
    val username: String = "", val password: String = "", val mac: String = "", val userAgent: String = "",
)

@Serializable
data class BackupEpg(
    val name: String, val url: String, val enabled: Boolean,
    val priority: Int = 0, val mode: String = EpgMode.FILL,
)

@Serializable
data class BackupProfile(
    val id: String, val name: String, val colorIndex: Int = 0, val isKid: Boolean = false,
    val pinHash: String = "", val position: Int = 0,
)

/** [profileId] vide : sauvegarde d'avant les profils (groupe rattaché au profil actif). */
@Serializable
data class BackupGroup(val id: Long, val name: String, val position: Int, val profileId: String = "", val syncId: String = "")

@Serializable
data class BackupItem(val groupId: Long, val itemUid: String, val kind: String, val position: Int, val profileId: String = "")

@Serializable
data class BackupPref(val uid: String, val hidden: Boolean, val position: Int? = null, val locked: Boolean? = null, val name: String = "")

@Serializable
data class BackupChannelPref(
    val uid: String, val name: String = "", val number: Int = 0, val epgKey: String = "",
    val locked: Boolean = false, val hidden: Boolean = false,
)

/** Enregistrement programmé, à venir, envoyé à une TV. */
@Serializable
data class BackupRecording(val channelUid: String, val channelName: String, val title: String, val start: Long, val stop: Long)

/** Ce qu'on peut cocher en envoyant à une TV, en plus des playlists. */
@Serializable
enum class BackupPart(val label: String, val detail: String) {
    PROFILES("Profils, favoris et groupes", "Remplace ceux des mêmes profils sur la TV"),
    EPG("Sources de guide TV", "Tes guides ajoutés à la main"),
    CHANNEL_PREFS("Chaînes et catégories", "Masquées, verrouillées, renommées, renumérotées"),
    SETTINGS("Réglages", "Lecture, affichage… sauf le code parental"),
    RECORDINGS("Enregistrements programmés", "Ceux à venir, sur les playlists présentes sur la TV"),
}

/** Choix fait sur le téléphone avant l'envoi. */
data class BackupSelection(val playlistIds: Set<Long>, val parts: Set<BackupPart>) {
    val isEmpty: Boolean get() = playlistIds.isEmpty() && parts.isEmpty()
}

@Serializable
data class Backup(
    val app: String = APP_ID,
    val version: Int = 2,
    val createdAt: Long = System.currentTimeMillis(),
    val settings: AppSettings,
    val playlists: List<BackupPlaylist>,
    val epgSources: List<BackupEpg>,
    val groups: List<BackupGroup>,
    val items: List<BackupItem>,
    val categoryPrefs: List<BackupPref>,
    val profiles: List<BackupProfile> = emptyList(),
    val channelPrefs: List<BackupChannelPref> = emptyList(),
    /** Envoi sélectif : seuls les éléments cochés sont présents, et le reste de la TV doit être gardé. */
    val partial: Boolean = false,
    val parts: Set<BackupPart> = emptySet(),
    /** Clé stable de chaque playlist du téléphone, cochée ou non : aucun mot de passe, mais de quoi retrouver favoris et chaînes. */
    val playlistKeys: Map<Long, String> = emptyMap(),
    val recordings: List<BackupRecording> = emptyList(),
)

/** Identifiant écrit dans les sauvegardes ; "Zapette" = ancien nom de l'appli, toujours accepté à l'import. */
const val APP_ID = "Vyzo"
val ACCEPTED_IDS = setOf(APP_ID, "Zapette")

/** Sauvegarde valide trouvée sur l'appareil. */
class FoundBackup(val file: BackupFile, val playlists: Int, val text: String)

/** Où une sauvegarde sans sélecteur de fichier a été écrite ; [survives] : elle reste après une désinstallation. */
data class SavedBackup(val places: List<String>, val survives: Boolean)

/** Sauvegarde/restauration de la configuration (pas du contenu, qui se recharge depuis les sources). */
object BackupManager {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun export(c: AppContainer): String = json.encodeToString(Backup.serializer(), snapshot(c))

    /** Sauvegarde limitée à ce qui est coché : ce qui ne l'est pas ne quitte pas le téléphone. */
    suspend fun exportSelection(c: AppContainer, selection: BackupSelection): String {
        val all = snapshot(c)
        val parts = selection.parts
        val profiles = BackupPart.PROFILES in parts
        val prefs = BackupPart.CHANNEL_PREFS in parts
        val b = Backup(
            settings = if (BackupPart.SETTINGS in parts) all.settings else AppSettings(),
            playlists = all.playlists.filter { it.id in selection.playlistIds },
            epgSources = if (BackupPart.EPG in parts) all.epgSources else emptyList(),
            groups = if (profiles) all.groups else emptyList(),
            items = if (profiles) all.items else emptyList(),
            categoryPrefs = if (prefs) all.categoryPrefs else emptyList(),
            profiles = if (profiles) all.profiles else emptyList(),
            channelPrefs = if (prefs) all.channelPrefs else emptyList(),
            partial = true,
            parts = parts,
            playlistKeys = c.db.playlists().all().associate { it.id to PlaylistKeys.of(it) },
            recordings = if (BackupPart.RECORDINGS in parts) {
                c.db.recordings().scheduled(System.currentTimeMillis()).map { BackupRecording(it.channelUid, it.channelName, it.title, it.start, it.stop) }
            } else emptyList(),
        )
        return json.encodeToString(Backup.serializer(), b)
    }

    /** Ce que contient un envoi sélectif, pour la demande de confirmation sur la TV. */
    fun describeSelection(text: String): List<String> = runCatching {
        val b = json.decodeFromString(Backup.serializer(), text)
        b.playlists.map { "Playlist « ${it.name} »" } + b.parts.map { it.label }
    }.getOrDefault(emptyList())

    private suspend fun snapshot(c: AppContainer): Backup {
        val db = c.db
        // Les profils locaux ont leur propre sauvegarde, qui ne quitte pas l'appareil.
        val local = db.profiles().all().filter { it.localOnly }.map { it.id }.toSet()
        return Backup(
            // Le profil actif, le code parental et le jeton GitHub restent propres à chaque appareil.
            settings = c.settings.get().copy(activeProfileId = "", parentalPinHash = "", githubToken = "", traktClientId = "", traktClientSecret = "", traktAccessToken = "", traktRefreshToken = "", traktTokenExpiresAt = 0, traktProfileId = "", traktLastSync = 0, multiViewPresets = emptyList(), lastUpdateCheck = 0, skippedVersionCode = 0, lastPlaces = emptyMap()),
            playlists = db.playlists().all().map { BackupPlaylist(it.id, it.name, it.type.name, it.url, it.username, it.password, it.mac, it.userAgent) },
            epgSources = db.epg().sources().filter { !it.auto }.map { BackupEpg(it.name, it.url, it.enabled, it.priority, it.mode) },
            groups = db.groups().allGroups().filter { it.profileId !in local }.map { BackupGroup(it.id, it.name, it.position, it.profileId, it.syncId) },
            items = db.groups().allItems().filter { it.itemUid.isNotBlank() && it.profileId !in local }
                .map { BackupItem(it.groupId, it.itemUid, it.kind.name, it.position, it.profileId) },
            categoryPrefs = db.categories().allPrefs().map { BackupPref(it.uid, it.hidden, it.position, it.locked, it.name) },
            profiles = db.profiles().all().filter { !it.localOnly }.map { BackupProfile(it.id, it.name, it.colorIndex, it.isKid, it.pinHash, it.position) },
            channelPrefs = db.channelPrefs().all().map { BackupChannelPref(it.uid, it.name, it.number, it.epgKey, it.locked, it.hidden) },
        )
    }

    /** Vérifie qu'un texte est une sauvegarde Vyzo (ou Zapette) ; renvoie son nombre de playlists, ou null. */
    fun inspect(text: String): Int? = runCatching {
        val b = json.decodeFromString(Backup.serializer(), text)
        if (b.app !in ACCEPTED_IDS) null else b.playlists.size
    }.getOrNull()

    /** Sans sélecteur de fichier : hors de l'appli si possible, sinon dans son dossier, qui part avec elle. */
    fun saveOnDevice(platform: Platform, text: String): SavedBackup {
        val places = runCatching { platform.writeSharedBackup(text) }.getOrDefault(emptyList())
        if (places.isNotEmpty()) return SavedBackup(places, survives = true)
        platform.localBackupFile.writeText(text)
        return SavedBackup(listOf(platform.localBackupFile.absolutePath), survives = false)
    }

    /** Sauvegardes valides présentes sur l'appareil, la plus récente d'abord ; un fichier abîmé ou d'une autre appli est ignoré. */
    fun findOnDevice(platform: Platform): List<FoundBackup> {
        val local = platform.localBackupFile.takeIf { it.isFile }?.let { BackupFile(it.absolutePath, "Dossier de l'appli", it.lastModified()) }
        return (platform.sharedBackups() + listOfNotNull(local)).mapNotNull { f ->
            val text = runCatching { platform.openInput(f.uri)?.use { it.readBytes().decodeToString() } }.getOrNull() ?: return@mapNotNull null
            inspect(text)?.let { FoundBackup(f, it, text) }
        }.sortedByDescending { it.file.modifiedAt }
    }

    /**
     * Remplace la configuration actuelle. Renvoie le nombre de playlists restaurées.
     * Les profils de la sauvegarde sont ajoutés (ou mis à jour) ; les profils locaux absents de la sauvegarde
     * sont gardés tels quels, avec leurs favoris et leur progression.
     */
    suspend fun import(c: AppContainer, text: String): Int {
        val b = json.decodeFromString(Backup.serializer(), text)
        // Avant de toucher à quoi que ce soit : un fichier d'une autre appli n'efface pas la configuration.
        require(b.app in ACCEPTED_IDS) { "Pas une sauvegarde Vyzo" }
        val db = c.db
        val now = System.currentTimeMillis()
        val activeProfile = c.profiles.activeIdNow()

        db.playlists().all().forEach { c.repo.deletePlaylist(it.id) }
        // Avec leurs programmes : une source recréée change d'identifiant, le guide les afficherait en double.
        db.epg().sources().filter { !it.auto }.forEach { c.repo.deleteEpgSource(it) }
        b.epgSources.distinctBy { it.url }.forEach { db.epg().insertSource(EpgSourceEntity(name = it.name, url = it.url, enabled = it.enabled, priority = it.priority, mode = it.mode)) }

        val idMap = HashMap<Long, Long>()
        b.playlists.forEachIndexed { i, p ->
            val type = runCatching { SourceType.valueOf(p.type) }.getOrDefault(SourceType.M3U)
            idMap[p.id] = db.playlists().insert(
                PlaylistEntity(name = p.name, type = type, url = p.url, username = p.username, password = p.password, mac = p.mac, userAgent = p.userAgent, position = i),
            )
        }
        fun remap(uid: String): String {
            val old = uid.substringBefore(':').toLongOrNull() ?: return uid
            val new = idMap[old] ?: return uid
            return "$new:" + uid.substringAfter(':')
        }

        restoreGroups(c, b, activeProfile, now) { item ->
            val uid = remap(item.itemUid)
            c.repo.contentKey(uid)?.let { key -> uid to key }
        }

        db.categories().clearPrefs()
        db.categories().upsertPrefs(b.categoryPrefs.map { CategoryPrefEntity(remap(it.uid), it.hidden, it.position, it.locked, it.name) })
        db.channelPrefs().clear()
        db.channelPrefs().upsertAll(b.channelPrefs.map { ChannelPrefEntity(remap(it.uid), it.name, it.number, it.epgKey, it.locked, it.hidden) })

        val active = idMap[b.settings.activePlaylistId] ?: idMap.values.firstOrNull() ?: 0
        c.settings.update { cur ->
            b.settings.copy(
                activePlaylistId = active,
                lastChannelUid = remap(b.settings.lastChannelUid),
                lastGroupKey = b.settings.lastGroupKey.let { k -> if (k.startsWith("cat:")) "cat:" + remap(k.removePrefix("cat:")) else k },
                // Réglages propres à cet appareil : on garde les siens.
                activeProfileId = cur.activeProfileId,
                parentalPinHash = cur.parentalPinHash,
                githubToken = cur.githubToken,
                traktClientId = cur.traktClientId,
                traktClientSecret = cur.traktClientSecret,
                traktAccessToken = cur.traktAccessToken,
                traktRefreshToken = cur.traktRefreshToken,
                traktTokenExpiresAt = cur.traktTokenExpiresAt,
                traktProfileId = cur.traktProfileId,
                traktLastSync = cur.traktLastSync,
                multiViewPresets = cur.multiViewPresets,
                recFolderUri = cur.recFolderUri,
                recFolderName = cur.recFolderName,
                lastUpdateCheck = cur.lastUpdateCheck,
                skippedVersionCode = cur.skippedVersionCode,
                lastPlaces = cur.lastPlaces,
            )
        }
        c.repo.attachToPlaylists()
        adoptDefaultProfile(c, b, activeProfile)
        return idMap.size
    }

    /**
     * Applique un envoi sélectif du téléphone : seuls les éléments cochés sont touchés et le reste de la TV ne bouge pas.
     * Les identifiants de playlist, propres à chaque appareil, sont traduits grâce à leur clé stable.
     * Renvoie le nombre de playlists reçues.
     */
    suspend fun importSelection(c: AppContainer, text: String): Int {
        val b = json.decodeFromString(Backup.serializer(), text)
        require(b.app in ACCEPTED_IDS && b.partial) { "Pas un envoi sélectif Vyzo" }
        val db = c.db
        val current = db.playlists().all()
        val idMap = HashMap<Long, Long>()
        b.playlistKeys.forEach { (id, key) -> current.firstOrNull { PlaylistKeys.of(it) == key }?.let { idMap[id] = it.id } }
        b.playlists.forEach { p ->
            val type = runCatching { SourceType.valueOf(p.type) }.getOrDefault(SourceType.M3U)
            val known = idMap[p.id]?.let { id -> current.firstOrNull { it.id == id } }
            if (known != null) {
                c.repo.updatePlaylist(known.copy(name = p.name, type = type, url = p.url, username = p.username, password = p.password, mac = p.mac, userAgent = p.userAgent))
            } else {
                idMap[p.id] = c.repo.addPlaylist(PlaylistEntity(name = p.name, type = type, url = p.url, username = p.username, password = p.password, mac = p.mac, userAgent = p.userAgent))
            }
        }
        // uid du téléphone → uid sur cette TV, null si sa playlist n'y est pas.
        fun localUid(uid: String): String? {
            val old = uid.substringBefore(':').toLongOrNull() ?: return uid
            val new = idMap[old] ?: return null
            return "$new:" + uid.substringAfter(':')
        }

        if (BackupPart.PROFILES in b.parts) {
            val activeProfile = c.profiles.activeIdNow()
            // Favori d'une playlist absente de la TV : gardé sous sa clé stable, il se rattachera quand elle arrivera.
            restoreGroups(c, b, activeProfile, System.currentTimeMillis()) { item ->
                val old = item.itemUid.substringBefore(':').toLongOrNull()
                val key = old?.let { b.playlistKeys[it] }
                if (key == null) null else localUid(item.itemUid).orEmpty() to "$key:" + item.itemUid.substringAfter(':')
            }
            adoptDefaultProfile(c, b, activeProfile)
        }
        if (BackupPart.EPG in b.parts) {
            val known = db.epg().sources().filter { !it.auto }
            b.epgSources.forEach { e ->
                val cur = known.firstOrNull { it.url == e.url }
                if (cur != null) db.epg().updateSource(cur.copy(name = e.name, enabled = e.enabled, priority = e.priority, mode = e.mode))
                else db.epg().insertSource(EpgSourceEntity(name = e.name, url = e.url, enabled = e.enabled, priority = e.priority, mode = e.mode))
            }
        }
        if (BackupPart.CHANNEL_PREFS in b.parts) {
            db.categories().upsertPrefs(b.categoryPrefs.mapNotNull { p -> localUid(p.uid)?.let { CategoryPrefEntity(it, p.hidden, p.position, p.locked, p.name) } })
            db.channelPrefs().upsertAll(b.channelPrefs.mapNotNull { p -> localUid(p.uid)?.let { ChannelPrefEntity(it, p.name, p.number, p.epgKey, p.locked, p.hidden) } })
        }
        if (BackupPart.SETTINGS in b.parts) {
            c.settings.update { cur ->
                b.settings.copy(
                    // La TV garde sa position et ce qui lui est propre.
                    activePlaylistId = cur.activePlaylistId,
                    lastChannelUid = cur.lastChannelUid,
                    lastGroupKey = cur.lastGroupKey,
                    activeProfileId = cur.activeProfileId,
                    parentalPinHash = cur.parentalPinHash,
                    githubToken = cur.githubToken,
                    traktClientId = cur.traktClientId,
                    traktClientSecret = cur.traktClientSecret,
                    traktAccessToken = cur.traktAccessToken,
                    traktRefreshToken = cur.traktRefreshToken,
                    traktTokenExpiresAt = cur.traktTokenExpiresAt,
                    traktProfileId = cur.traktProfileId,
                    traktLastSync = cur.traktLastSync,
                    multiViewPresets = cur.multiViewPresets,
                    recFolderUri = cur.recFolderUri,
                    recFolderName = cur.recFolderName,
                    lastUpdateCheck = cur.lastUpdateCheck,
                    skippedVersionCode = cur.skippedVersionCode,
                    lastPlaces = cur.lastPlaces,
                )
            }
        }
        if (BackupPart.RECORDINGS in b.parts) {
            b.recordings.forEach { r ->
                localUid(r.channelUid)?.let { uid ->
                    c.recordings.applyRemote(ScheduleSyncEvent(action = "upsert", channelUid = uid, channelName = r.channelName, title = r.title, start = r.start, stop = r.stop))
                }
            }
        }
        c.repo.attachToPlaylists()
        return b.playlists.size
    }

    /** Remet les profils de la sauvegarde avec leurs groupes et favoris ; [resolve] donne l'uid local et la clé stable d'un favori. */
    private suspend fun restoreGroups(c: AppContainer, b: Backup, activeProfile: String, now: Long, resolve: suspend (BackupItem) -> Pair<String, String>?) {
        // Rien à remettre (appareil qui n'a que des profils locaux) : ceux d'ici ne perdent pas leurs favoris.
        if (b.profiles.isEmpty() && b.items.isEmpty() && b.groups.all { it.id == FAVORITES_GROUP_ID }) return
        val db = c.db
        val imported = b.profiles.map { it.id }.toSet()
        // Profils concernés par la restauration de leurs groupes et favoris.
        val replaced = if (imported.isEmpty()) setOf(activeProfile) else imported
        db.withTransaction {
            b.profiles.forEach { p ->
                db.profiles().upsert(
                    ProfileEntity(p.id, p.name, p.colorIndex, p.isKid, p.pinHash, p.position, updatedAt = now, deleted = false, dirty = true),
                )
            }
            // Groupes et favoris des profils restaurés (la suppression part aussi vers le compte Vyzo)
            val syncIds = db.groups().allGroups().associate { it.id to it.syncId }
            db.groups().allItems().filter { it.profileId in replaced }.forEach {
                db.groups().removeItem(it.profileId, it.groupId, it.contentKey)
                syncIds[it.groupId]?.let { sid -> db.tombstones().add(SyncTombstoneEntity(Repository.TOMB_ITEM, it.profileId, Repository.itemTombKey(sid, it.contentKey))) }
            }
            db.groups().allGroups().filter { it.id != FAVORITES_GROUP_ID && it.profileId in replaced }.forEach { g ->
                db.groups().clearGroup(g.id)
                db.groups().deleteGroup(g.id)
                db.tombstones().add(SyncTombstoneEntity(Repository.TOMB_GROUP, g.profileId, g.syncId))
            }
        }
        val fresh = System.currentTimeMillis() + 1
        val groupMap = HashMap<Long, Long>()
        val groupProfile = HashMap<Long, String>()
        b.groups.forEach { g ->
            if (g.id == FAVORITES_GROUP_ID) {
                db.groups().group(FAVORITES_GROUP_ID)?.let { db.groups().updateGroup(it.copy(name = g.name)) }
                groupMap[FAVORITES_GROUP_ID] = FAVORITES_GROUP_ID
            } else {
                val owner = g.profileId.ifBlank { activeProfile }
                val syncId = g.syncId.ifBlank { UUID.randomUUID().toString() }
                groupProfile[g.id] = owner
                db.tombstones().remove(Repository.TOMB_GROUP, owner, syncId)
                groupMap[g.id] = db.groups().insertGroup(
                    UserGroupEntity(name = g.name, position = g.position, profileId = owner, syncId = syncId, updatedAt = fresh),
                )
            }
        }
        val items = b.items.mapNotNull { it ->
            val gid = groupMap[it.groupId] ?: return@mapNotNull null
            val profileId = it.profileId.ifBlank { groupProfile[it.groupId] ?: activeProfile }
            val (uid, key) = resolve(it) ?: return@mapNotNull null
            val kind = runCatching { ContentKind.valueOf(it.kind) }.getOrDefault(ContentKind.LIVE)
            UserGroupItemEntity(profileId, gid, key, uid, kind, it.position, addedAt = now, updatedAt = fresh)
        }
        val syncIds = db.groups().allGroups().associate { it.id to it.syncId }
        items.forEach { i -> syncIds[i.groupId]?.let { db.tombstones().remove(Repository.TOMB_ITEM, i.profileId, Repository.itemTombKey(it, i.contentKey)) } }
        db.groups().upsertItems(items)
    }

    private suspend fun adoptDefaultProfile(c: AppContainer, b: Backup, activeProfile: String) {
        val db = c.db
        // Appareil neuf : son profil par défaut encore vide laisse la place à celui de la sauvegarde, avec ses favoris.
        val local = db.profiles().get(activeProfile)
        if (local != null && !local.localOnly && b.profiles.isNotEmpty() && b.profiles.none { it.id == activeProfile } && local.name == DEFAULT_PROFILE_NAME && !db.profiles().hasData(activeProfile)) {
            val target = b.profiles.firstOrNull { it.name == local.name } ?: b.profiles.first()
            c.profiles.mergeInto(activeProfile, target.id)
        }
    }
}
