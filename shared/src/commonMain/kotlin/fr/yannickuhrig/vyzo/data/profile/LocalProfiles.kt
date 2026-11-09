package fr.yannickuhrig.vyzo.data.profile

import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.ChannelHistoryEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.FAVORITES_GROUP_ID
import fr.yannickuhrig.vyzo.data.db.PlaylistKeys
import fr.yannickuhrig.vyzo.data.db.ProfileEntity
import fr.yannickuhrig.vyzo.data.db.UserGroupEntity
import fr.yannickuhrig.vyzo.data.db.UserGroupItemEntity
import fr.yannickuhrig.vyzo.data.db.WatchProgressEntity
import fr.yannickuhrig.vyzo.data.db.withTransaction
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class LocalBackupGroup(val id: Long, val name: String, val position: Int, val syncId: String)

@Serializable
data class LocalBackupItem(val groupId: Long, val contentKey: String, val kind: String, val position: Int, val addedAt: Long, val updatedAt: Long)

@Serializable
data class LocalBackupProgress(
    val contentKey: String, val kind: String, val parentKey: String = "",
    val positionMs: Long, val durationMs: Long, val updatedAt: Long,
)

@Serializable
data class LocalBackupChannel(val contentKey: String, val watchedAt: Long)

/** Sauvegarde d'un profil local : elle ne s'ouvre que sur l'appareil [deviceId]. */
@Serializable
data class LocalProfileBackup(
    val deviceId: String,
    val createdAt: Long,
    val id: String,
    val name: String,
    val colorIndex: Int = 0,
    val isKid: Boolean = false,
    val pinHash: String = "",
    val groups: List<LocalBackupGroup> = emptyList(),
    val items: List<LocalBackupItem> = emptyList(),
    val progress: List<LocalBackupProgress> = emptyList(),
    val channels: List<LocalBackupChannel> = emptyList(),
)

/**
 * Profils qui restent sur leur appareil : création, sauvegarde et restauration sur place.
 *
 * L'appareil est reconnu à un identifiant tiré au sort, gardé dans [dir]. Android copie la base de données vers un nouveau
 * téléphone, mais jamais ce dossier : un profil local arrivé ainsi porte un autre identifiant et il est effacé
 * au démarrage ([removeForeign]). Les sauvegardes sont dans le même dossier, hors de portée des autres appareils.
 */
class LocalProfiles(
    private val db: AppDatabase,
    private val profiles: ProfileManager,
    private val settings: SettingsStore,
    private val dir: File,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Identifiant de cet appareil, créé à sa première utilisation. */
    private suspend fun deviceId(): String = withContext(Dispatchers.IO) {
        val f = File(dir, DEVICE_FILE)
        synchronized(this@LocalProfiles) {
            if (f.isFile) f.readText().trim().also { check(it.isNotEmpty()) { "Identifiant de l'appareil vide" } }
            else UUID.randomUUID().toString().also { write(f, it) }
        }
    }

    suspend fun create(name: String, isKid: Boolean = false): ProfileEntity =
        profiles.create(name, isKid = isKid, localDeviceId = deviceId())

    /** Enregistre l'état actuel du profil, à la place de sa sauvegarde précédente. */
    suspend fun backup(id: String): LocalProfileBackup {
        val p = db.profiles().get(id)
        require(p != null && p.localOnly) { "Pas un profil local" }
        val keys = db.playlists().all().associate { it.id to PlaylistKeys.of(it) }
        val backup = LocalProfileBackup(
            deviceId = deviceId(),
            createdAt = System.currentTimeMillis(),
            id = p.id, name = p.name, colorIndex = p.colorIndex, isKid = p.isKid, pinHash = p.pinHash,
            groups = db.groups().allGroups().filter { it.profileId == id }.map { LocalBackupGroup(it.id, it.name, it.position, it.syncId) },
            items = db.groups().allItems().filter { it.profileId == id }.map { LocalBackupItem(it.groupId, it.contentKey, it.kind.name, it.position, it.addedAt, it.updatedAt) },
            progress = db.progress().forProfile(id).map { LocalBackupProgress(it.contentKey, it.kind.name, it.parentKey, it.positionMs, it.durationMs, it.updatedAt) },
            // Les chaînes récentes sont rangées par uid local : on les retrouve par leur clé stable, si leur playlist a été recréée entre-temps.
            channels = db.progress().history(id).mapNotNull { h ->
                val pid = PlaylistKeys.playlistIdOf(h.channelUid) ?: return@mapNotNull null
                val key = PlaylistKeys.contentKey(h.channelUid, pid, keys[pid] ?: return@mapNotNull null) ?: return@mapNotNull null
                LocalBackupChannel(key, h.watchedAt)
            },
        )
        withContext(Dispatchers.IO) { write(fileOf(id), json.encodeToString(LocalProfileBackup.serializer(), backup)) }
        return backup
    }

    /** Sauvegardes de cet appareil, la plus récente d'abord ; un fichier abîmé ou venu d'ailleurs est ignoré. */
    suspend fun backups(): List<LocalProfileBackup> {
        val files = withContext(Dispatchers.IO) { dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty().toList() }
        if (files.isEmpty()) return emptyList()
        val me = deviceId()
        return withContext(Dispatchers.IO) {
            files.mapNotNull { f -> runCatching { json.decodeFromString(LocalProfileBackup.serializer(), f.readText()) }.getOrNull() }
                .filter { it.deviceId == me }.sortedByDescending { it.createdAt }
        }
    }

    /** Remet le profil tel qu'il était à sa sauvegarde, ou le recrée s'il a été supprimé. */
    suspend fun restore(id: String) {
        val me = deviceId()
        val b = withContext(Dispatchers.IO) { json.decodeFromString(LocalProfileBackup.serializer(), fileOf(id).readText()) }
        require(b.deviceId == me) { "Cette sauvegarde a été faite sur un autre appareil" }
        val current = db.profiles().get(b.id)
        require(current == null || current.localOnly) { "Pas un profil local" }
        val pids = db.playlists().all().associate { PlaylistKeys.of(it) to it.id }
        fun uid(key: String): String? {
            val pk = key.substringBefore(':', "")
            return PlaylistKeys.localUid(key, pids[pk] ?: return null, pk)
        }
        fun kind(s: String) = runCatching { ContentKind.valueOf(s) }.getOrDefault(ContentKind.MOVIE)
        db.withTransaction {
            db.progress().deleteProfile(id)
            db.progress().deleteHistory(id)
            db.groups().deleteProfileItems(id)
            db.groups().deleteProfileGroups(id)
            db.tombstones().deleteProfile(id)
            db.profiles().upsert(
                ProfileEntity(
                    b.id, b.name, b.colorIndex, b.isKid, b.pinHash, position = current?.position ?: (db.profiles().maxPosition() + 1),
                    dirty = false, localDeviceId = me,
                ),
            )
            val groupIds = b.groups.associate { g ->
                g.id to db.groups().insertGroup(UserGroupEntity(name = g.name, position = g.position, profileId = id, syncId = g.syncId, dirty = false))
            } + (FAVORITES_GROUP_ID to FAVORITES_GROUP_ID)
            // Contenu d'une playlist absente aujourd'hui : uid vide, il se rattachera quand elle reviendra.
            db.groups().upsertItems(
                b.items.mapNotNull { i ->
                    val gid = groupIds[i.groupId] ?: return@mapNotNull null
                    UserGroupItemEntity(id, gid, i.contentKey, uid(i.contentKey).orEmpty(), kind(i.kind), i.position, i.addedAt, i.updatedAt, dirty = false)
                },
            )
            db.progress().upsertAll(
                b.progress.map { r ->
                    WatchProgressEntity(
                        id, r.contentKey, uid(r.contentKey).orEmpty(), kind(r.kind),
                        parentUid = if (r.parentKey.isBlank()) "" else uid(r.parentKey).orEmpty(), parentKey = r.parentKey,
                        positionMs = r.positionMs, durationMs = r.durationMs, updatedAt = r.updatedAt, dirty = false,
                    )
                },
            )
            b.channels.forEach { h -> uid(h.contentKey)?.let { db.progress().touchChannel(ChannelHistoryEntity(id, it, h.watchedAt)) } }
        }
    }

    suspend fun deleteBackup(id: String) {
        withContext(Dispatchers.IO) { fileOf(id).delete() }
    }

    /** Efface les profils locaux d'un autre appareil, arrivés ici avec une copie de la base. */
    suspend fun removeForeign() {
        val local = db.profiles().allWithDeleted().filter { it.localOnly }
        if (local.isEmpty()) return
        val me = deviceId()
        val foreign = local.filter { it.localDeviceId != me }
        if (foreign.isEmpty()) return
        db.withTransaction {
            foreign.forEach { p ->
                db.progress().deleteProfile(p.id)
                db.progress().deleteHistory(p.id)
                db.groups().deleteProfileItems(p.id)
                db.groups().deleteProfileGroups(p.id)
                db.tombstones().deleteProfile(p.id)
                db.profiles().hardDelete(p.id)
            }
        }
        if (foreign.any { it.id == settings.get().activeProfileId }) settings.update { it.copy(activeProfileId = "") }
        profiles.ensureOne()
    }

    private fun fileOf(id: String): File {
        // Vient de notre base ou de nos fichiers, mais un nom de fichier ne doit jamais sortir du dossier.
        require(id.all { it.isLetterOrDigit() || it == '-' }) { "Identifiant de profil invalide" }
        return File(dir, id + SUFFIX)
    }

    /** Écrit à côté puis remplace : une coupure en plein enregistrement laisse la sauvegarde précédente intacte. */
    private fun write(f: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val DEVICE_FILE = "appareil.id"
        const val SUFFIX = ".json"
    }
}
