package fr.yannickuhrig.vyzo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.yannickuhrig.vyzo.data.cloud.CloudApi
import fr.yannickuhrig.vyzo.data.cloud.CloudStore
import fr.yannickuhrig.vyzo.data.cloud.CloudSync
import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.data.profile.ParentalControl
import fr.yannickuhrig.vyzo.data.profile.ProfileManager
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.data.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Essai réel contre le projet Supabase (lancé à la main : VYZO_LIVE_EMAIL / VYZO_LIVE_PASSWORD). */
@RunWith(AndroidJUnit4::class)
class LiveSupabaseTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    inner class Device(name: String, val iptv: MockWebServer) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db = testDatabase(ctx)
        val settings = testSettings(ctx, scope, "settings-live-$name")
        val sync = SyncManager(AndroidPlatform(ctx), db, settings, scope)
        val profiles = ProfileManager(db, settings, scope)
        val parental = ParentalControl(db, settings, profiles, scope)
        val repo = Repository(db, settings, sync, profiles, parental)
        val cloud = CloudSync(CloudApi(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, OkHttpClient()), testCloudStore(ctx, "cloud-live-$name"), db, repo, profiles, settings, scope)
        suspend fun use(id: String) {
            profiles.select(id)
            withTimeout(5_000) { profiles.active.first { it?.id == id } }
            withTimeout(5_000) { repo.viewScope.first { it.profileId == id } }
        }
    }

    @Test
    fun realServerRoundTrip() = runBlocking {
        val email = System.getenv("VYZO_LIVE_EMAIL")
        val password = System.getenv("VYZO_LIVE_PASSWORD")
        assumeTrue(!email.isNullOrBlank() && !password.isNullOrBlank())
        val iptv = MockWebServer().apply { dispatcher = Fixtures.dispatcher(this); start() }
        val a = Device("a", iptv)
        val b = Device("b", iptv)
        val pa = a.repo.addPlaylist(PlaylistEntity(name = "A", type = SourceType.XTREAM, url = iptv.url("/").toString(), username = "bob", password = "secret"))
        a.sync.syncPlaylist(pa).getOrThrow()
        val pb = b.repo.addPlaylist(PlaylistEntity(name = "B", type = SourceType.XTREAM, url = iptv.url("/").toString(), username = "bob", password = "secret"))
        b.sync.syncPlaylist(pb).getOrThrow()

        val lea = a.profiles.create("Léa", isKid = true)
        a.use(lea.id)
        a.repo.toggleFavorite("$pa:L:101", ContentKind.LIVE)
        a.repo.saveProgress("$pa:M:502", ContentKind.MOVIE, "", 600_000, 6_000_000)
        a.cloud.signIn(email, password)
        assertEquals(null, a.cloud.status.value.error)

        b.cloud.signIn(email, password)
        assertEquals(null, b.cloud.status.value.error)
        assertTrue(b.db.profiles().all().any { it.id == lea.id && it.isKid })
        b.use(lea.id)
        assertEquals(600_000L, b.repo.progressNow("$pb:M:502")?.positionMs)
        assertTrue(b.repo.isFavorite("$pb:L:101").first())

        b.repo.saveProgress("$pb:M:502", ContentKind.MOVIE, "", 1_500_000, 6_000_000)
        b.cloud.sync().getOrThrow()
        a.cloud.sync().getOrThrow()
        assertEquals(1_500_000L, a.repo.progressNow("$pa:M:502")?.positionMs)

        // Une version plus ancienne envoyée en retard ne doit pas écraser la plus récente (garde côté serveur).
        val stale = a.db.progress().byKey(lea.id, a.repo.contentKey("$pa:M:502")!!)!!
        a.db.progress().upsert(stale.copy(positionMs = 1_000, updatedAt = stale.updatedAt - 60_000, dirty = true))
        a.cloud.sync().getOrThrow()
        b.cloud.sync().getOrThrow()
        assertEquals(1_500_000L, b.repo.progressNow("$pb:M:502")?.positionMs)

        a.repo.toggleFavorite("$pa:L:101", ContentKind.LIVE)
        a.cloud.sync().getOrThrow()
        b.cloud.sync().getOrThrow()
        assertFalse(b.repo.isFavorite("$pb:L:101").first())

        // Nettoyage : suppression du profil (et, côté serveur, de sa progression et de ses listes).
        a.use(a.db.profiles().all().first { it.id != lea.id }.id)
        a.profiles.delete(lea.id)
        a.cloud.sync().getOrThrow()
        b.cloud.sync().getOrThrow()
        assertTrue(b.db.profiles().all().none { it.id == lea.id })
        a.cloud.signOut()
        b.cloud.signOut()
        iptv.shutdown()
    }
}
