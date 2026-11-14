package fr.yannickuhrig.vyzo.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.DATABASE_NAME
import fr.yannickuhrig.vyzo.data.db.MetadataCacheEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEpisodeAlertEntity
import fr.yannickuhrig.vyzo.data.db.SeriesScheduleEntity
import fr.yannickuhrig.vyzo.data.db.StreamQualityEntity
import fr.yannickuhrig.vyzo.data.db.record
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DbMigrationTest {
    /** Crée une base telle que l'a laissée une version donnée, d'après le schéma exporté par Room. */
    private fun createFromSchema(file: File, version: Int) {
        val schemas = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { it.resolve("shared/schemas/fr.yannickuhrig.vyzo.data.db.AppDatabase") }.first { it.isDirectory }
        val database = Json.parseToJsonElement(schemas.resolve("$version.json").readText()).jsonObject["database"]!!.jsonObject
        val hash = database["identityHash"]!!.jsonPrimitive.content
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        connection.use { db ->
            database["entities"]!!.jsonArray.map { it.jsonObject }.forEach { entity ->
                val table = entity["tableName"]!!.jsonPrimitive.content
                db.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                (entity["indices"] as? JsonArray).orEmpty().forEach {
                    db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$hash')")
            db.execSQL("PRAGMA user_version = $version")
        }
    }

    @Test
    fun uneBaseEnVersion5PasseEnVersion6EtGardeSesDefinitions() = runBlocking {
        val file = Files.createTempDirectory("vyzo-migration").toFile().resolve(DATABASE_NAME)
        createFromSchema(file, 5)
        // Room compare le résultat de la migration au schéma attendu et lève une erreur au moindre écart.
        val db = AppDatabase.build(
            Room.databaseBuilder<AppDatabase>(name = file.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO),
        )
        db.streamQuality().record(listOf(StreamQualityEntity("s:1", 1920, 1080)), "s")
        db.streamQuality().record(listOf(StreamQualityEntity("s:2", 3840, 2160)), "s")
        db.streamQuality().record(listOf(StreamQualityEntity("s:3", 1280, 720)), "s")
        val saved = db.streamQuality().observeAll().first().associateBy { it.uid }
        assertEquals(3840, saved.getValue("s").width, "la série garde son meilleur épisode")
        assertEquals(720, saved.getValue("s:3").height)
        db.close()
    }

    @Test
    fun uneBaseEnVersion6GagneLeCacheDeMetadonnees() = runBlocking {
        val file = Files.createTempDirectory("vyzo-migration").toFile().resolve(DATABASE_NAME)
        createFromSchema(file, 6)
        val db = AppDatabase.build(
            Room.databaseBuilder<AppDatabase>(name = file.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO),
        )
        db.metadataCache().upsert(MetadataCacheEntity("cle", "{}", 1L))
        assertEquals("{}", db.metadataCache().get("cle")?.body)
        db.close()
    }

    @Test
    fun uneBaseEnVersion7GardeSesGuidesEtGagneRangEtMode() = runBlocking {
        val file = Files.createTempDirectory("vyzo-migration").toFile().resolve(DATABASE_NAME)
        createFromSchema(file, 7)
        BundledSQLiteDriver().open(file.absolutePath).use {
            it.execSQL("INSERT INTO epg_source (id, playlistId, name, url, enabled, auto, lastSync, lastError, programCount) VALUES (5, 0, 'Perso', 'http://x', 1, 0, 0, '', 0)")
        }
        val db = AppDatabase.build(
            Room.databaseBuilder<AppDatabase>(name = file.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO),
        )
        val source = db.epg().sources().single()
        assertEquals("Perso", source.name)
        assertEquals(0, source.priority)
        assertEquals("FILL", source.mode)
        db.close()
    }

    @Test
    fun uneBaseEnVersion8GagneAvatarsEtSuiviSeries() = runBlocking {
        val file = Files.createTempDirectory("vyzo-migration").toFile().resolve(DATABASE_NAME)
        createFromSchema(file, 8)
        BundledSQLiteDriver().open(file.absolutePath).use {
            it.execSQL(
                "INSERT INTO profile (id, name, colorIndex, isKid, pinHash, position, updatedAt, deleted, dirty, localDeviceId) " +
                    "VALUES ('p1', 'Yannick', 0, 0, '', 0, 100, 0, 0, '')",
            )
        }
        val db = AppDatabase.build(
            Room.databaseBuilder<AppDatabase>(name = file.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO),
        )
        val profile = db.profiles().get("p1")!!
        assertEquals("Yannick", profile.name)
        assertEquals("", profile.avatar)

        db.seriesTracking().upsertAlert(SeriesEpisodeAlertEntity("1:S:10", 5, 8, 2, "5:7|5:8", 200L))
        assertEquals("5:7|5:8", db.seriesTracking().alert("1:S:10")?.freshSlots)

        db.seriesTracking().upsertSchedule(
            SeriesScheduleEntity("1:S:10", "Returning Series", 5, 9, "Le Départ", "2026-11-19", 0, "", 0, 42, 300L),
        )
        assertEquals("2026-11-19", db.seriesTracking().schedule("1:S:10")?.nextAirDate)
        db.close()
    }
}
