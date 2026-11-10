package fr.yannickuhrig.vyzo.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import java.util.UUID

const val FAVORITES_GROUP_ID = 1L

/** Nom du fichier de base (identique sur Android et sur PC). */
const val DATABASE_NAME = "vyzo.db"

/** Identifiant de synchro du groupe Favoris (le même sur tous les appareils). */
const val FAVORITES_SYNC_ID = "fav"

/** Nom du profil créé automatiquement (à l'installation ou lors de la mise à jour vers les profils). */
const val DEFAULT_PROFILE_NAME = "Principal"

@Database(
    entities = [
        PlaylistEntity::class, CategoryEntity::class, CategoryPrefEntity::class, ChannelEntity::class,
        MovieEntity::class, SeriesEntity::class, EpisodeEntity::class, ProgramEntity::class,
        EpgSourceEntity::class, EpgNameMapEntity::class, UserGroupEntity::class, UserGroupItemEntity::class,
        WatchProgressEntity::class, ChannelHistoryEntity::class, ReminderEntity::class, RecordingEntity::class,
        ProfileEntity::class, ChannelPrefEntity::class, SyncTombstoneEntity::class, RecordingRuleEntity::class,
        DownloadEntity::class, StreamQualityEntity::class, MetadataCacheEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun categories(): CategoryDao
    abstract fun channels(): ChannelDao
    abstract fun movies(): MovieDao
    abstract fun series(): SeriesDao
    abstract fun programs(): ProgramDao
    abstract fun epg(): EpgDao
    abstract fun groups(): UserGroupDao
    abstract fun progress(): ProgressDao
    abstract fun profiles(): ProfileDao
    abstract fun channelPrefs(): ChannelPrefDao
    abstract fun tombstones(): TombstoneDao
    abstract fun reminders(): ReminderDao
    abstract fun recordings(): RecordingDao
    abstract fun recordingRules(): RecordingRuleDao
    abstract fun downloads(): DownloadDao
    abstract fun streamQuality(): StreamQualityDao
    abstract fun metadataCache(): MetadataCacheDao

    companion object {
        /** Termine la configuration commune d'une base créée par la plateforme (fichier, pilote SQLite). */
        fun build(builder: Builder<AppDatabase>): AppDatabase =
            builder
                .addCallback(object : Callback() {
                    override fun onCreate(connection: SQLiteConnection) {
                        insertFavoritesGroup(connection)
                        insertDefaultProfile(connection)
                    }
                })
                .addMigrations(*MIGRATIONS)
                // Uniquement pour une base plus récente que l'appli (retour à une ancienne version).
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()

        val MIGRATIONS: Array<Migration> get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)

        private fun insertFavoritesGroup(db: SQLiteConnection) {
            db.execSQL(
                "INSERT OR IGNORE INTO user_group (id, name, position, profileId, syncId, updatedAt, dirty) " +
                    "VALUES ($FAVORITES_GROUP_ID, 'Favoris', 0, '', '$FAVORITES_SYNC_ID', 0, 0)",
            )
        }

        internal fun insertDefaultProfile(db: SQLiteConnection): String {
            val id = UUID.randomUUID().toString()
            db.exec(
                "INSERT OR IGNORE INTO profile (id, name, colorIndex, isKid, pinHash, position, updatedAt, deleted, dirty) " +
                    "VALUES (?, ?, 0, 0, '', 0, ?, 0, 1)",
                id, DEFAULT_PROFILE_NAME, System.currentTimeMillis(),
            )
            return id
        }

        /**
         * 1.2 → 1.3 : profils (progression, chaînes récentes, favoris et groupes par profil, synchronisables),
         * contrôle parental, personnalisation des chaînes et catégories, infos du compte, enregistrements de série.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                val db = connection
                val now = System.currentTimeMillis()
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `profile` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `colorIndex` INTEGER NOT NULL, " +
                        "`isKid` INTEGER NOT NULL, `pinHash` TEXT NOT NULL, `position` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`deleted` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                val profileId = insertDefaultProfile(db)

                // Infos du compte fournisseur
                db.execSQL("ALTER TABLE `playlist` ADD COLUMN `activeConnections` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `playlist` ADD COLUMN `accountStatus` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `playlist` ADD COLUMN `accountCheckedAt` INTEGER NOT NULL DEFAULT 0")

                // Contrôle parental et personnalisation
                db.execSQL("ALTER TABLE `category` ADD COLUMN `adult` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `category_pref` ADD COLUMN `locked` INTEGER")
                db.execSQL("ALTER TABLE `category_pref` ADD COLUMN `name` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `channel` ADD COLUMN `origName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `channel` ADD COLUMN `origNumber` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `channel` ADD COLUMN `hidden` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `channel` ADD COLUMN `locked` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `channel_pref` (`uid` TEXT NOT NULL, `name` TEXT NOT NULL, `number` INTEGER NOT NULL, " +
                        "`epgKey` TEXT NOT NULL, `locked` INTEGER NOT NULL, `hidden` INTEGER NOT NULL, PRIMARY KEY(`uid`))",
                )

                // Clés stables des playlists existantes, pour convertir les uid locaux.
                val keys = HashMap<Long, String>()
                db.query("SELECT id, type, url, username, mac FROM playlist") { c ->
                    val type = runCatching { SourceType.valueOf(c.getText(1)) }.getOrDefault(SourceType.M3U)
                    keys[c.getLong(0)] = PlaylistKeys.of(type, c.getText(2), c.getText(3), c.getText(4))
                }
                fun contentKeyOf(uid: String): String? {
                    val pid = PlaylistKeys.playlistIdOf(uid) ?: return null
                    return PlaylistKeys.contentKey(uid, pid, keys[pid] ?: return null)
                }

                // Groupes : les groupes perso existants vont au profil créé, les favoris deviennent les siens.
                db.execSQL("ALTER TABLE `user_group` ADD COLUMN `profileId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `user_group` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `user_group` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `user_group` ADD COLUMN `dirty` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_group_profileId` ON `user_group` (`profileId`)")
                val groupIds = ArrayList<Long>()
                db.query("SELECT id FROM user_group") { c -> groupIds += c.getLong(0) }
                for (gid in groupIds) {
                    if (gid == FAVORITES_GROUP_ID) {
                        db.exec("UPDATE user_group SET profileId = '', syncId = ?, updatedAt = 0, dirty = 0 WHERE id = ?", FAVORITES_SYNC_ID, gid)
                    } else {
                        db.exec(
                            "UPDATE user_group SET profileId = ?, syncId = ?, updatedAt = ?, dirty = 1 WHERE id = ?",
                            profileId, UUID.randomUUID().toString(), now, gid,
                        )
                    }
                }
                insertFavoritesGroup(db)

                db.execSQL("ALTER TABLE `user_group_item` RENAME TO `user_group_item_old`")
                db.execSQL("DROP INDEX IF EXISTS `index_user_group_item_itemUid`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_group_item` (`profileId` TEXT NOT NULL, `groupId` INTEGER NOT NULL, " +
                        "`contentKey` TEXT NOT NULL, `itemUid` TEXT NOT NULL, `kind` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`profileId`, `groupId`, `contentKey`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_group_item_itemUid` ON `user_group_item` (`itemUid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_group_item_profileId_itemUid` ON `user_group_item` (`profileId`, `itemUid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_group_item_dirty` ON `user_group_item` (`dirty`)")
                val oldItems = ArrayList<Array<Any?>>()
                db.query("SELECT groupId, itemUid, kind, position, addedAt FROM user_group_item_old") { c ->
                    oldItems += arrayOf(c.getLong(0), c.getText(1), c.getText(2), c.getLong(3), c.getLong(4))
                }
                for ((groupId, uid, kind, position, addedAt) in oldItems.map { it.toList() }) {
                    val key = contentKeyOf(uid as String) ?: continue
                    db.exec(
                        "INSERT OR REPLACE INTO user_group_item (profileId, groupId, contentKey, itemUid, kind, position, addedAt, updatedAt, dirty) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)",
                        profileId, groupId, key, uid, kind, position, addedAt, addedAt,
                    )
                }
                db.execSQL("DROP TABLE `user_group_item_old`")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_tombstone` (`entity` TEXT NOT NULL, `profileId` TEXT NOT NULL, `key` TEXT NOT NULL, " +
                        "`deletedAt` INTEGER NOT NULL, PRIMARY KEY(`entity`, `profileId`, `key`))",
                )

                // Progression : par profil, avec une clé stable d'un appareil à l'autre.
                db.execSQL("ALTER TABLE `watch_progress` RENAME TO `watch_progress_old`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watch_progress` (`profileId` TEXT NOT NULL, `contentKey` TEXT NOT NULL, " +
                        "`uid` TEXT NOT NULL, `kind` TEXT NOT NULL, `parentUid` TEXT NOT NULL, `parentKey` TEXT NOT NULL, " +
                        "`positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`dirty` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `contentKey`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_progress_profileId_uid` ON `watch_progress` (`profileId`, `uid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_progress_profileId_parentUid` ON `watch_progress` (`profileId`, `parentUid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_progress_dirty` ON `watch_progress` (`dirty`)")
                val oldProgress = ArrayList<List<Any?>>()
                db.query("SELECT uid, kind, parentUid, positionMs, durationMs, updatedAt FROM watch_progress_old") { c ->
                    oldProgress += listOf(c.getText(0), c.getText(1), if (c.isNull(2)) "" else c.getText(2), c.getLong(3), c.getLong(4), c.getLong(5))
                }
                for (row in oldProgress) {
                    val uid = row[0] as String
                    val key = contentKeyOf(uid) ?: continue
                    val parentUid = row[2] as String
                    db.exec(
                        "INSERT OR REPLACE INTO watch_progress (profileId, contentKey, uid, kind, parentUid, parentKey, positionMs, durationMs, updatedAt, dirty) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                        profileId, key, uid, row[1], parentUid, contentKeyOf(parentUid).orEmpty(), row[3], row[4], row[5],
                    )
                }
                db.execSQL("DROP TABLE `watch_progress_old`")

                db.execSQL("ALTER TABLE `channel_history` RENAME TO `channel_history_old`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `channel_history` (`profileId` TEXT NOT NULL, `channelUid` TEXT NOT NULL, " +
                        "`watchedAt` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `channelUid`))",
                )
                db.exec("INSERT INTO channel_history (profileId, channelUid, watchedAt) SELECT ?, channelUid, watchedAt FROM channel_history_old", profileId)
                db.execSQL("DROP TABLE `channel_history_old`")

                // Enregistrements de série
                db.execSQL("ALTER TABLE `recording` ADD COLUMN `ruleId` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recording_rule` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `channelUid` TEXT NOT NULL, " +
                        "`channelName` TEXT NOT NULL, `title` TEXT NOT NULL, `titleNorm` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
            }
        }

        /**
         * 2 → 3 : ajout du champ releaseDate (date de publication/sortie) sur les films et les séries.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `movie` ADD COLUMN `releaseDate` TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE `series` ADD COLUMN `releaseDate` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * 3 → 4 : ajout de la table download (mode hors-ligne et gestionnaire de téléchargements).
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """CREATE TABLE IF NOT EXISTS `download` (
                        `id` TEXT NOT NULL,
                        `itemType` TEXT NOT NULL,
                        `itemUid` TEXT NOT NULL,
                        `seriesUid` TEXT,
                        `title` TEXT NOT NULL,
                        `subtitle` TEXT NOT NULL DEFAULT '',
                        `posterUrl` TEXT NOT NULL DEFAULT '',
                        `remoteUrl` TEXT NOT NULL,
                        `localFilePath` TEXT NOT NULL,
                        `status` TEXT NOT NULL DEFAULT 'QUEUED',
                        `bytesDownloaded` INTEGER NOT NULL DEFAULT 0,
                        `totalBytes` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )""".trimIndent(),
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_download_itemUid` ON `download` (`itemUid`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_download_status` ON `download` (`status`)")
            }
        }

        /**
         * 4 → 5 : profils locaux, qui restent sur leur appareil.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `profile` ADD COLUMN `localDeviceId` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 6 → 7 : cache des réponses de TMDB et d'OMDb. */
        /** 7 → 8 : rang et mode de chaque source du guide. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `epg_source` ADD COLUMN `priority` INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE `epg_source` ADD COLUMN `mode` TEXT NOT NULL DEFAULT 'FILL'")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `metadata_cache` (`cacheKey` TEXT NOT NULL, `body` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`cacheKey`))",
                )
            }
        }

        /**
         * 5 → 6 : définition réelle des films et épisodes, dans sa propre table pour survivre aux synchros.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stream_quality` (`uid` TEXT NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, PRIMARY KEY(`uid`))",
                )
            }
        }
    }
}

/** Exécute une requête SQL avec des paramètres (API SQLiteConnection, commune à Android et au PC). */
internal fun SQLiteConnection.exec(sql: String, vararg args: Any?) {
    prepare(sql).use { st ->
        st.bindAll(args)
        st.step()
    }
}

/** Parcourt les lignes d'une requête. */
internal fun SQLiteConnection.query(sql: String, vararg args: Any?, onRow: (SQLiteStatement) -> Unit) {
    prepare(sql).use { st ->
        st.bindAll(args)
        while (st.step()) onRow(st)
    }
}

private fun SQLiteStatement.bindAll(args: Array<out Any?>) {
    args.forEachIndexed { i, a ->
        val idx = i + 1
        when (a) {
            null -> bindNull(idx)
            is String -> bindText(idx, a)
            is Long -> bindLong(idx, a)
            is Int -> bindLong(idx, a.toLong())
            is Boolean -> bindBoolean(idx, a)
            is Double -> bindDouble(idx, a)
            else -> bindText(idx, a.toString())
        }
    }
}

/** Implémentation générée par Room pour chaque plateforme. */
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
