package fr.yannickuhrig.vyzo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SourceType { M3U, XTREAM, STALKER }

enum class ContentKind { LIVE, MOVIE, SERIES }

@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: SourceType,
    /** URL M3U, serveur Xtream ou portail Stalker. */
    val url: String,
    val username: String = "",
    val password: String = "",
    val mac: String = "",
    val userAgent: String = "",
    /** Fuseau horaire du serveur (Xtream), utilisé pour le replay. */
    val serverTimezone: String = "",
    val expiresAt: Long = 0,
    val maxConnections: Int = 0,
    /** Connexions en cours au moment de la dernière vérification du compte (Xtream). */
    val activeConnections: Int = 0,
    /** État du compte renvoyé par le fournisseur (Active, Expired, Banned…). */
    val accountStatus: String = "",
    val accountCheckedAt: Long = 0,
    val lastSync: Long = 0,
    val lastError: String = "",
    val position: Int = 0,
)

@Entity(
    tableName = "category",
    indices = [Index("playlistId", "kind")],
)
data class CategoryEntity(
    @PrimaryKey val uid: String,
    val playlistId: Long,
    val kind: ContentKind,
    val remoteId: String,
    val name: String,
    val position: Int,
    val syncToken: Long,
    /** Catégorie pour adultes, détectée à la synchro (nom ou contenu marqué « adulte » par le fournisseur). */
    val adult: Boolean = false,
)

/** Préférences utilisateur sur une catégorie : conservées entre deux synchros. */
@Entity(tableName = "category_pref")
data class CategoryPrefEntity(
    @PrimaryKey val uid: String,
    val hidden: Boolean = false,
    val position: Int? = null,
    /**
     * Contrôle parental : true = verrouillée, false = jamais verrouillée,
     * null = automatique (verrouillée si la catégorie est détectée « adulte »).
     */
    val locked: Boolean? = null,
    /** Nom choisi par l'utilisateur, vide = nom de la playlist. */
    val name: String = "",
)

/** Personnalisation d'une chaîne, conservée entre deux synchros (et réappliquée après chacune). */
@Entity(tableName = "channel_pref")
data class ChannelPrefEntity(
    @PrimaryKey val uid: String,
    /** Nom affiché à la place de celui de la playlist, vide = inchangé. */
    val name: String = "",
    /** Numéro choisi, 0 = celui de la playlist. */
    val number: Int = 0,
    /** Identifiant du guide choisi à la main, vide = association automatique. */
    val epgKey: String = "",
    val locked: Boolean = false,
    val hidden: Boolean = false,
)

@Entity(
    tableName = "channel",
    indices = [
        Index("playlistId"), Index("categoryUid"), Index("epgKey"), Index("nameNorm"), Index("matchKey"),
    ],
)
data class ChannelEntity(
    @PrimaryKey val uid: String,
    val playlistId: Long,
    val remoteId: String,
    val name: String,
    val nameNorm: String,
    val matchKey: String,
    val number: Int,
    val logo: String,
    val categoryUid: String,
    val epgId: String,
    val epgKey: String,
    /** URL directe (M3U), commande (Stalker) ou vide (Xtream : construite à la volée). */
    val streamUrl: String,
    val catchupType: String = "",
    val catchupDays: Int = 0,
    val catchupSource: String = "",
    val userAgent: String = "",
    val referer: String = "",
    val drmType: String = "",
    val drmKey: String = "",
    val position: Int,
    val syncToken: Long,
    /** Nom et numéro d'origine (playlist), quand l'utilisateur les a changés. */
    val origName: String = "",
    val origNumber: Int = 0,
    /** Masquée par l'utilisateur (reste visible dans l'écran de personnalisation). */
    val hidden: Boolean = false,
    /** Verrouillée par le contrôle parental. */
    val locked: Boolean = false,
)

@Entity(
    tableName = "movie",
    indices = [Index("playlistId"), Index("categoryUid"), Index("nameNorm")],
)
data class MovieEntity(
    @PrimaryKey val uid: String,
    val playlistId: Long,
    val remoteId: String,
    val name: String,
    val nameNorm: String,
    val categoryUid: String,
    val poster: String,
    val backdrop: String = "",
    val rating: Float = 0f,
    val year: String = "",
    val releaseDate: String = "",
    val plot: String = "",
    val genre: String = "",
    val actors: String = "",
    val director: String = "",
    val durationSecs: Int = 0,
    val containerExt: String = "",
    val streamUrl: String = "",
    val addedAt: Long = 0L,
    val position: Int,
    val syncToken: Long,
) {
    val yearInt: Int get() = year.toIntOrNull() ?: 0
}

typealias VodEntity = MovieEntity

@Entity(
    tableName = "series",
    indices = [Index("playlistId"), Index("categoryUid"), Index("nameNorm")],
)
data class SeriesEntity(
    @PrimaryKey val uid: String,
    val playlistId: Long,
    val remoteId: String,
    val name: String,
    val nameNorm: String,
    val categoryUid: String,
    val poster: String,
    val backdrop: String = "",
    val rating: Float = 0f,
    val year: String = "",
    val releaseDate: String = "",
    val plot: String = "",
    val genre: String = "",
    val actors: String = "",
    val addedAt: Long = 0L,
    val position: Int,
    val syncToken: Long,
) {
    val yearInt: Int get() = year.toIntOrNull() ?: 0
}

@Entity(
    tableName = "episode",
    indices = [Index("seriesUid")],
)
data class EpisodeEntity(
    @PrimaryKey val uid: String,
    val seriesUid: String,
    val playlistId: Long,
    val remoteId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val plot: String = "",
    val poster: String = "",
    val durationSecs: Int = 0,
    val containerExt: String = "",
    val streamUrl: String = "",
    val syncToken: Long,
)

/** Réponse de TMDB ou d'OMDb gardée sur l'appareil : une fiche déjà ouverte ne refait aucune requête. */
@Entity(tableName = "metadata_cache")
data class MetadataCacheEntity(
    @PrimaryKey val cacheKey: String,
    val body: String,
    val fetchedAt: Long,
)

/** Définition réelle d'un film, d'une série ou d'un épisode, relevée chez le fournisseur ou à la lecture. */
@Entity(tableName = "stream_quality")
data class StreamQualityEntity(
    @PrimaryKey val uid: String,
    val width: Int,
    val height: Int,
)

@Entity(
    tableName = "program",
    indices = [Index("channelKey", "start"), Index("sourceKey"), Index("title")],
)
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelKey: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String = "",
    val category: String = "",
    val icon: String = "",
    /** Identifiant distant (portail Stalker : nécessaire pour le replay). */
    val remoteId: String = "",
    val sourceKey: String,
    val generation: Long,
)

@Entity(tableName = "epg_source")
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 0 = source ajoutée à la main. */
    val playlistId: Long = 0,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val auto: Boolean = false,
    val lastSync: Long = 0,
    val lastError: String = "",
    val programCount: Int = 0,
    /** Rang de la source : la plus petite valeur passe en premier. */
    val priority: Int = 0,
    /** Voir [EpgMode]. */
    val mode: String = EpgMode.FILL,
)

/** Ce qu'une source apporte aux chaînes déjà couvertes par une source mieux classée. */
object EpgMode {
    /** Rien : elle ne sert que pour les chaînes encore sans guide. */
    const val FILL = "FILL"

    /** Ses programmes s'ajoutent dans les créneaux restés vides. */
    const val GAPS = "GAPS"
}

/** Correspondance nom de chaîne normalisé → identifiant XMLTV, pour les chaînes sans tvg-id. */
@Entity(tableName = "epg_name_map")
data class EpgNameMapEntity(
    @PrimaryKey val matchKey: String,
    val xmlKey: String,
)

/**
 * Groupe de l'utilisateur. Le groupe Favoris ([FAVORITES_GROUP_ID]) est commun à tous les profils
 * (chaque profil y a ses propres éléments) ; les autres groupes appartiennent à un profil.
 */
@Entity(tableName = "user_group", indices = [Index("profileId")])
data class UserGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int = 0,
    /** Profil propriétaire, vide pour le groupe Favoris. */
    val profileId: String = "",
    /** Identifiant stable d'un appareil à l'autre (UUID, « fav » pour les favoris). */
    val syncId: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
)

@Entity(
    tableName = "user_group_item",
    primaryKeys = ["profileId", "groupId", "contentKey"],
    indices = [Index("itemUid"), Index("profileId", "itemUid"), Index("dirty")],
)
data class UserGroupItemEntity(
    val profileId: String,
    val groupId: Long,
    /** Clé stable d'un appareil à l'autre (voir [PlaylistKeys]). */
    val contentKey: String,
    /** uid local, vide si la playlist correspondante n'existe pas (encore) sur cet appareil. */
    val itemUid: String,
    val kind: ContentKind,
    val position: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
)

/** Suppression locale en attente d'envoi au compte Vyzo (élément de groupe, groupe…). */
@Entity(tableName = "sync_tombstone", primaryKeys = ["entity", "profileId", "key"])
data class SyncTombstoneEntity(
    val entity: String,
    val profileId: String,
    val key: String,
    val deletedAt: Long = System.currentTimeMillis(),
)

/** Profil d'une personne du foyer : chacun a sa progression et ses chaînes récentes. */
@Entity(tableName = "profile")
data class ProfileEntity(
    /** UUID, identique sur tous les appareils du compte. */
    @PrimaryKey val id: String,
    val name: String,
    val colorIndex: Int = 0,
    /** Profil enfant : n'affiche pas les catégories verrouillées par le contrôle parental. */
    val isKid: Boolean = false,
    /** Empreinte du code PIN du profil, vide = pas de code. */
    val pinHash: String = "",
    val position: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Profil supprimé : gardé pour que la suppression atteigne les autres appareils. */
    val deleted: Boolean = false,
    /** Modifié depuis le dernier envoi au compte Vyzo. */
    val dirty: Boolean = true,
    /** Profil local : l'appareil auquel il appartient (voir [fr.yannickuhrig.vyzo.data.profile.LocalProfiles]). Vide = suit le compte. */
    // Valeur par défaut dans la table : le profil créé à l'installation est inséré en SQL, sans cette colonne.
    @ColumnInfo(defaultValue = "") val localDeviceId: String = "",
) {
    /** Reste sur son appareil : jamais envoyé au compte, à une TV ni dans un fichier de sauvegarde. */
    val localOnly: Boolean get() = localDeviceId.isNotBlank()
}

@Entity(
    tableName = "watch_progress",
    primaryKeys = ["profileId", "contentKey"],
    indices = [Index("profileId", "uid"), Index("profileId", "parentUid"), Index("dirty")],
)
data class WatchProgressEntity(
    val profileId: String,
    /** Clé stable d'un appareil à l'autre : « clé de playlist » + fin de l'uid (voir [PlaylistKeys]). */
    val contentKey: String,
    /** uid local, vide si la playlist correspondante n'existe pas (encore) sur cet appareil. */
    val uid: String,
    val kind: ContentKind,
    /** Pour un épisode : uid de la série. */
    val parentUid: String = "",
    val parentKey: String = "",
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
)

@Entity(tableName = "channel_history", primaryKeys = ["profileId", "channelUid"])
data class ChannelHistoryEntity(
    val profileId: String,
    val channelUid: String,
    val watchedAt: Long,
)

@Entity(tableName = "reminder", indices = [Index("start")])
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelUid: String,
    val channelName: String,
    val title: String,
    val start: Long,
    val stop: Long,
)

enum class RecordingStatus { SCHEDULED, RECORDING, DONE, FAILED, CANCELLED }

@Entity(tableName = "recording", indices = [Index("start")])
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelUid: String,
    val channelName: String,
    val title: String,
    val start: Long,
    val stop: Long,
    val status: RecordingStatus = RecordingStatus.SCHEDULED,
    val filePath: String = "",
    val bytes: Long = 0,
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** Règle « toute la série » qui a créé cet enregistrement, 0 sinon. */
    val ruleId: Long = 0,
)

/** Enregistrement d'une série : toutes les diffusions d'un même titre sur une chaîne. */
@Entity(tableName = "recording_rule")
data class RecordingRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelUid: String,
    val channelName: String,
    val title: String,
    /** Titre normalisé utilisé pour reconnaître les diffusions suivantes. */
    val titleNorm: String,
    val createdAt: Long = System.currentTimeMillis(),
)

object DownloadStatus {
    const val QUEUED = "QUEUED"
    const val DOWNLOADING = "DOWNLOADING"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
}

/** Téléchargement local de film ou d'épisode pour le visionnage hors-ligne. */
@Entity(
    tableName = "download",
    indices = [
        Index("itemUid"),
        Index("status"),
    ],
)
data class DownloadEntity(
    @PrimaryKey val id: String, // ex: movie:{uid} ou episode:{uid}
    val itemType: String, // "movie" ou "episode"
    val itemUid: String,
    val seriesUid: String? = null,
    val title: String,
    val subtitle: String = "",
    val posterUrl: String = "",
    val remoteUrl: String,
    val localFilePath: String,
    val status: String = DownloadStatus.QUEUED,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val progress: Float
        get() = when {
            isCompleted -> 1f
            totalBytes > 0L -> (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
            else -> 0f
        }

    val isCompleted: Boolean get() = status == DownloadStatus.COMPLETED
    val isDownloading: Boolean get() = status == DownloadStatus.DOWNLOADING
    val isQueued: Boolean get() = status == DownloadStatus.QUEUED
    val isPaused: Boolean get() = status == DownloadStatus.PAUSED
    val isFailed: Boolean get() = status == DownloadStatus.FAILED
}
