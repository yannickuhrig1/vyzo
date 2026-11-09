package fr.yannickuhrig.vyzo

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.DEFAULT_PROFILE_NAME
import fr.yannickuhrig.vyzo.data.db.FAVORITES_GROUP_ID
import fr.yannickuhrig.vyzo.data.db.FAVORITES_SYNC_ID
import fr.yannickuhrig.vyzo.data.db.PlaylistKeys
import fr.yannickuhrig.vyzo.data.db.SourceType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Une base créée par Vyzo 1.2 (schéma 1) est migrée sans perte vers le schéma 2. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun createV1(name: String) {
        val schema = Json.parseToJsonElement(File("../shared/schemas/fr.yannickuhrig.vyzo.data.db.AppDatabase/1.json").readText()) as JsonObject
        val database = schema["database"] as JsonObject
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs(); delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        (database["entities"] as JsonArray).forEach { e ->
            e as JsonObject
            val table = e["tableName"]!!.jsonPrimitive.content
            db.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            (e["indices"] as? JsonArray)?.forEach { i -> db.execSQL((i as JsonObject)["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        }
        (database["setupQueries"] as JsonArray).forEach { db.execSQL(it.jsonPrimitive.content) }
        // Données d'un utilisateur de la 1.2 : une playlist Xtream, des favoris, un groupe, de la progression.
        db.execSQL(
            "INSERT INTO playlist (id, name, type, url, username, password, mac, userAgent, serverTimezone, expiresAt, maxConnections, lastSync, lastError, position) " +
                "VALUES (3, 'Mon IPTV', 'XTREAM', 'http://serveur:8080', 'bob', 'secret', '', '', '', 0, 1, 0, '', 0)",
        )
        db.execSQL("INSERT INTO category (uid, playlistId, kind, remoteId, name, position, syncToken) VALUES ('3:LIVE:1', 3, 'LIVE', '1', 'Adultes XXX', 0, 1)")
        db.execSQL("INSERT INTO category_pref (uid, hidden, position) VALUES ('3:LIVE:1', 0, 4)")
        db.execSQL(
            "INSERT INTO channel (uid, playlistId, remoteId, name, nameNorm, matchKey, number, logo, categoryUid, epgId, epgKey, streamUrl, catchupType, catchupDays, catchupSource, userAgent, referer, drmType, drmKey, position, syncToken) " +
                "VALUES ('3:L:101', 3, '101', 'TF1', 'tf1', 'tf1', 1, '', '3:LIVE:1', 'tf1.fr', 'tf1.fr', '', '', 0, '', '', '', '', '', 0, 1)",
        )
        db.execSQL("INSERT INTO user_group (id, name, position) VALUES (1, 'Favoris', 0), (2, 'Sport', 1)")
        db.execSQL("INSERT INTO user_group_item (groupId, itemUid, kind, position, addedAt) VALUES (1, '3:L:101', 'LIVE', 1, 1000), (2, '3:L:101', 'LIVE', 1, 2000), (1, '99:L:5', 'LIVE', 2, 3000)")
        db.execSQL("INSERT INTO watch_progress (uid, kind, parentUid, positionMs, durationMs, updatedAt) VALUES ('3:M:502', 'MOVIE', '', 600000, 6000000, 5000), ('3:S:701:9001', 'SERIES', '3:S:701', 1000, 2000, 6000)")
        db.execSQL("INSERT INTO channel_history (channelUid, watchedAt) VALUES ('3:L:101', 7000)")
        db.execSQL("INSERT INTO recording (channelUid, channelName, title, start, stop, status, filePath, bytes, error, createdAt) VALUES ('3:L:101', 'TF1', 'JT', 1, 2, 'DONE', '/x.ts', 10, '', 3)")
        db.version = 1
        db.close()
    }

    @Test
    fun version1DatabaseIsMigrated() = runBlocking {
        createV1("migration.db")
        val db = testDatabase(ctx, "migration.db")
        try {
            // Room vérifie le schéma à l'ouverture : une migration incomplète lèverait une exception ici.
            val profiles = db.profiles().all()
            assertEquals(listOf(DEFAULT_PROFILE_NAME), profiles.map { it.name })
            assertEquals("un profil existant reste sur le compte", false, profiles.single().localOnly)
            val pid = profiles.single().id
            val key = PlaylistKeys.of(SourceType.XTREAM, "http://serveur:8080", "bob", "")

            val progress = db.progress().forProfile(pid).associateBy { it.uid }
            assertEquals(600_000L, progress["3:M:502"]!!.positionMs)
            assertEquals("$key:M:502", progress["3:M:502"]!!.contentKey)
            assertEquals("$key:S:701", progress["3:S:701:9001"]!!.parentKey)
            assertTrue(progress.values.all { it.dirty })

            // Favoris et groupe perso rattachés au profil ; l'élément d'une playlist disparue est abandonné.
            val items = db.groups().allItems()
            assertEquals(2, items.size)
            assertTrue(items.all { it.profileId == pid && it.contentKey == "$key:L:101" })
            val groups = db.groups().allGroups().associateBy { it.id }
            assertEquals(FAVORITES_SYNC_ID, groups[FAVORITES_GROUP_ID]!!.syncId)
            assertEquals("", groups[FAVORITES_GROUP_ID]!!.profileId)
            assertEquals(pid, groups[2L]!!.profileId)
            assertTrue(groups[2L]!!.syncId.length > 10)

            // Préférence de catégorie conservée, verrouillage en mode automatique.
            val pref = db.categories().pref("3:LIVE:1")!!
            assertEquals(4, pref.position)
            assertEquals(null, pref.locked)
            assertEquals(1, db.progress().history(pid).size)
            assertEquals(0L, db.recordings().get(1)!!.ruleId)
            assertEquals(1, db.playlists().get(3)!!.maxConnections)
        } finally {
            db.close()
        }
    }
}
