package fr.yannickuhrig.vyzo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.yannickuhrig.vyzo.data.cloud.CloudApi
import fr.yannickuhrig.vyzo.data.cloud.CloudException
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
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Deux appareils du même foyer, reliés par un faux Supabase : profils, favoris, groupes et progression circulent. */
@RunWith(AndroidJUnit4::class)
class CloudSyncTest {
    private lateinit var iptv: MockWebServer
    private lateinit var supa: MockWebServer
    private lateinit var fake: FakeSupabase
    private val devices = ArrayList<Device>()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    /** Un appareil = sa base, ses réglages, son compte. */
    inner class Device(name: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db = testDatabase(ctx)
        val settings = testSettings(ctx, scope, "settings-$name")
        val sync = SyncManager(AndroidPlatform(ctx), db, settings, scope)
        val profiles = ProfileManager(db, settings, scope)
        val parental = ParentalControl(db, settings, profiles, scope)
        val repo = Repository(db, settings, sync, profiles, parental)
        val cloud = CloudSync(CloudApi(supa.url("/").toString().trimEnd('/'), "cle-test", OkHttpClient()), testCloudStore(ctx, "cloud-$name"), db, repo, profiles, settings, scope)

        suspend fun use(profileId: String) {
            profiles.select(profileId)
            withTimeout(5_000) { profiles.active.first { it?.id == profileId } }
            withTimeout(5_000) { repo.viewScope.first { it.profileId == profileId } }
        }

        suspend fun addXtream(label: String): Long {
            val id = repo.addPlaylist(PlaylistEntity(name = label, type = SourceType.XTREAM, url = iptv.url("/").toString(), username = "bob", password = "secret"))
            sync.syncPlaylist(id).getOrThrow()
            return id
        }
    }

    @Before
    fun setUp() {
        iptv = MockWebServer().apply { dispatcher = Fixtures.dispatcher(this); start() }
        fake = FakeSupabase()
        supa = MockWebServer().apply { dispatcher = fake; start() }
    }

    @After
    fun tearDown() {
        devices.forEach { closeForTest(it.scope, it.db) }
        iptv.shutdown()
        supa.shutdown()
    }

    private fun device(name: String) = Device(name).also { devices += it }

    @Test
    fun twoDevicesShareProfilesFavoritesGroupsAndProgress() = runBlocking {
        val a = device("a")
        val b = device("b")

        // Appareil A : une playlist, un profil enfant « Léa » avec un favori, un groupe et un film entamé.
        val pa = a.addXtream("Abonnement")
        val principalA = a.profiles.activeNow()
        val lea = a.profiles.create("Léa", isKid = true)
        a.use(lea.id)
        val tf1A = a.repo.channel("$pa:L:101")!!
        a.repo.toggleFavorite(tf1A.uid, ContentKind.LIVE)
        val sport = a.repo.createGroup("Dessins animés")
        a.repo.setInGroup(sport, "$pa:L:102", ContentKind.LIVE, true)
        a.repo.saveProgress("$pa:M:502", ContentKind.MOVIE, "", 600_000, 6_000_000)

        a.cloud.signIn("foyer@example.fr", "motdepasse")
        assertEquals(a.cloud.status.value.error, null)
        assertEquals(2, fake.rows("profiles").size)
        assertEquals(1, fake.rows("watch_progress").size)
        assertEquals(2, fake.rows("list_items").size)
        assertEquals(1, fake.rows("user_lists").size)

        // Appareil B : même abonnement. Son profil « Principal » encore vierge rejoint celui du compte.
        val pb = b.addXtream("Mon IPTV")
        b.cloud.signIn("foyer@example.fr", "motdepasse")
        val profilesB = b.db.profiles().all()
        assertEquals(setOf(principalA.id, lea.id), profilesB.map { it.id }.toSet())
        assertTrue(profilesB.first { it.id == lea.id }.isKid)

        b.use(lea.id)
        assertEquals(listOf("$pb:M:502"), b.repo.movies(Repository.KEY_CONTINUE).first().map { it.uid })
        assertEquals(600_000L, b.repo.progressNow("$pb:M:502")?.positionMs)
        assertTrue(b.repo.isFavorite("$pb:L:101").first())
        val groupB = b.repo.userGroups().first().single { it.name == "Dessins animés" }
        assertEquals(listOf("$pb:L:102"), b.repo.channels("ug:${groupB.id}").first().map { it.uid })
        // Le profil principal de B n'a rien hérité de Léa.
        b.use(principalA.id)
        assertFalse(b.repo.isFavorite("$pb:L:101").first())

        // B avance dans le film (profil Léa) : A le récupère.
        b.use(lea.id)
        b.repo.saveProgress("$pb:M:502", ContentKind.MOVIE, "", 1_800_000, 6_000_000)
        b.cloud.sync().getOrThrow()
        a.cloud.sync().getOrThrow()
        assertEquals(1_800_000L, a.repo.progressNow("$pa:M:502")?.positionMs)

        // A retire le favori : il disparaît aussi sur B.
        a.repo.toggleFavorite(tf1A.uid, ContentKind.LIVE)
        a.cloud.sync().getOrThrow()
        b.cloud.sync().getOrThrow()
        assertFalse(b.repo.isFavorite("$pb:L:101").first())
        assertEquals("true", fake.rows("list_items").single { it["content_key"]!!.jsonPrimitive.content.endsWith(":L:101") }["deleted"]!!.jsonPrimitive.content)

        // B supprime le groupe : il disparaît sur A.
        b.repo.deleteGroup(groupB.id)
        b.cloud.sync().getOrThrow()
        a.cloud.sync().getOrThrow()
        a.use(lea.id)
        assertTrue(a.repo.userGroups().first().none { it.name == "Dessins animés" })

        // A supprime le profil Léa : B le perd aussi et repasse sur le profil principal.
        a.use(principalA.id)
        assertTrue(a.profiles.delete(lea.id))
        a.cloud.sync().getOrThrow()
        b.cloud.sync().getOrThrow()
        assertEquals(listOf(principalA.id), b.db.profiles().all().map { it.id })
        assertEquals(principalA.id, withTimeout(5_000) { b.profiles.active.first { it?.id != lea.id } }!!.id)
        assertEquals(0, fake.rows("watch_progress").size)
    }

    @Test
    fun sameNameProfilesAreMergedAndNewestProgressWins() = runBlocking {
        val a = device("c")
        val b = device("d")
        val pa = a.addXtream("A")
        val pb = b.addXtream("B")
        // Chacun a créé « Yannick » de son côté, et regardé le même film.
        val ya = a.profiles.create("Yannick")
        a.use(ya.id)
        a.repo.saveProgress("$pa:M:502", ContentKind.MOVIE, "", 300_000, 6_000_000)
        val yb = b.profiles.create("yannick ")
        b.use(yb.id)
        Thread.sleep(5)
        b.repo.saveProgress("$pb:M:502", ContentKind.MOVIE, "", 900_000, 6_000_000)

        a.cloud.signIn("foyer@example.fr", "motdepasse")
        b.cloud.signIn("foyer@example.fr", "motdepasse")
        a.cloud.sync().getOrThrow()

        // Un seul « Yannick » au final, sur les deux appareils, avec la progression la plus récente.
        assertEquals(1, fake.rows("profiles").count { it["name"]!!.jsonPrimitive.content.trim().equals("yannick", true) })
        assertEquals(b.db.profiles().all().map { it.id }.toSet(), a.db.profiles().all().map { it.id }.toSet())
        b.use(ya.id)
        assertEquals(900_000L, b.repo.progressNow("$pb:M:502")?.positionMs)
        a.use(ya.id)
        assertEquals(900_000L, a.repo.progressNow("$pa:M:502")?.positionMs)
    }

    @Test
    fun wrongPasswordGivesAFrenchMessage() = runBlocking {
        val a = device("e")
        val e = runCatching { a.cloud.signIn("foyer@example.fr", "faux") }.exceptionOrNull()
        assertTrue(e is CloudException)
        assertEquals("E-mail ou mot de passe incorrect", e!!.message)
        assertFalse(a.cloud.status.value.signedIn)
    }
}
