package fr.yannickuhrig.vyzo

import android.content.Context
import androidx.room.Room
import fr.yannickuhrig.vyzo.data.cloud.CloudStore
import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import java.io.File

/** Base de test : en mémoire si [name] est null, sinon fichier de l'appli (migrations). */
fun testDatabase(ctx: Context, name: String? = null): AppDatabase = AppDatabase.build(
    if (name == null) Room.inMemoryDatabaseBuilder<AppDatabase>(ctx)
    else Room.databaseBuilder<AppDatabase>(ctx, ctx.getDatabasePath(name).absolutePath),
)

fun testSettings(ctx: Context, scope: CoroutineScope, name: String) =
    SettingsStore(File(ctx.filesDir, "datastore/$name.preferences_pb"), scope)

fun testCloudStore(ctx: Context, name: String) = CloudStore(File(ctx.filesDir, "datastore/$name.preferences_pb"))
