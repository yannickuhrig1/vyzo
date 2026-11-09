package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.FakeSupabase
import fr.yannickuhrig.vyzo.Fixtures
import fr.yannickuhrig.vyzo.data.cloud.CloudApi
import fr.yannickuhrig.vyzo.data.cloud.CloudStore
import fr.yannickuhrig.vyzo.data.cloud.CloudSync
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.repo.Repository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/** Compte Vyzo sur PC : profils, favoris et progression passent d'un appareil à l'autre (faux Supabase). */
class DesktopCloudSyncTest {
    private lateinit var iptv: MockWebServer
    private lateinit var supa: MockWebServer
    private lateinit var fake: FakeSupabase

    private inner class Device {
        val dir = Files.createTempDirectory("vyzo-cloud").toFile()
        val c: AppContainer = testContainer(dir)
        val cloud = CloudSync(
            CloudApi(supa.url("/").toString().trimEnd('/'), "cle-test", OkHttpClient()),
            CloudStore(dir.resolve("cloud-test.preferences_pb")), c.db, c.repo, c.profiles, c.settings, c.scope,
        )

        init {
            // Comme AppContainer.start(), mais vers le CloudSync de ce faux appareil.
            c.recordings.onScheduleChanged = { cloud.sendScheduleSync(it) }
            cloud.onRemoteRecording = { c.recordings.applyRemote(it) }
        }

        suspend fun use(profileId: String) {
            c.profiles.select(profileId)
            withTimeout(5_000) { c.profiles.active.first { it?.id == profileId } }
            withTimeout(5_000) { c.repo.viewScope.first { it.profileId == profileId } }
        }

        suspend fun addXtream(label: String): Long {
            val id = c.repo.addPlaylist(PlaylistEntity(name = label, type = SourceType.XTREAM, url = iptv.url("/").toString(), username = "bob", password = "secret"))
            c.sync.syncPlaylist(id).getOrThrow()
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
        iptv.shutdown()
        supa.shutdown()
    }

    @Test
    fun profilesFavoritesAndProgressFollowTheAccount() = runBlocking {
        val tv = Device()
        val pc = Device()

        val pt = tv.addXtream("Abonnement")
        val lea = tv.c.profiles.create("Léa", isKid = true)
        tv.use(lea.id)
        tv.c.repo.toggleFavorite("$pt:L:101", ContentKind.LIVE)
        tv.c.repo.saveProgress("$pt:M:502", ContentKind.MOVIE, "", 600_000, 6_000_000)
        tv.cloud.signIn("foyer@example.fr", "motdepasse")
        assertEquals(null, tv.cloud.status.value.error)

        // Vyzo PC se connecte au même compte : il retrouve Léa, son favori et son film entamé.
        val pp = pc.addXtream("Mon IPTV")
        pc.cloud.signIn("foyer@example.fr", "motdepasse")
        assertTrue(pc.c.db.profiles().all().any { it.id == lea.id && it.isKid })
        pc.use(lea.id)
        assertTrue(pc.c.repo.isFavorite("$pp:L:101").first())
        assertEquals(listOf("$pp:M:502"), pc.c.repo.movies(Repository.KEY_CONTINUE).first().map { it.uid })

        // On avance le film sur le PC : la TV reprend au même endroit.
        pc.c.repo.saveProgress("$pp:M:502", ContentKind.MOVIE, "", 1_800_000, 6_000_000)
        pc.cloud.sync().getOrThrow()
        tv.cloud.sync().getOrThrow()
        assertEquals(1_800_000L, tv.c.repo.progressNow("$pt:M:502")?.positionMs)

        // Favori retiré sur la TV : il disparaît aussi du PC.
        tv.c.repo.toggleFavorite("$pt:L:101", ContentKind.LIVE)
        tv.cloud.sync().getOrThrow()
        pc.cloud.sync().getOrThrow()
        assertFalse(pc.c.repo.isFavorite("$pp:L:101").first())
    }

    private suspend fun waitUntil(what: String, cond: suspend () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError(what)
            kotlinx.coroutines.delay(50)
        }
    }

    /**
     * Rapport § 3.11 (programmations à distance) : une programmation faite sur le téléphone pendant que Vyzo PC est
     * réduit dans la zone de notification n'était qu'un message, perdu. Elle est maintenant gardée par le compte.
     */
    @Test
    fun uneProgrammationDuTelephoneAttendLePc() = runBlocking {
        val phone = Device()
        val pc = Device()
        phone.c.settings.update { it.copy(isRecordingHost = false) }
        // Le PC a une autre playlist avant : la même chaîne n'y a pas le même identifiant local.
        pc.c.db.playlists().insert(PlaylistEntity(name = "Autre", type = SourceType.M3U, url = "file:///dev/null"))
        val pt = phone.addXtream("Abonnement")
        val pp = pc.addXtream("Mon IPTV")
        assertTrue(pt != pp)
        phone.cloud.signIn("foyer@example.fr", "motdepasse")
        pc.cloud.signIn("foyer@example.fr", "motdepasse")
        pc.cloud.onBackground(stayConnected = true)

        val start = System.currentTimeMillis() + 3_600_000
        val stop = start + 5_400_000
        val ch = phone.c.db.channels().get("$pt:L:101")!!
        val id = phone.c.recordings.schedule(ch, "Le film du soir", start, stop)
        waitUntil("programmation envoyée au compte") { fake.rows("recordings").size == 1 }
        assertFalse(fake.rows("recordings")[0].toString().contains("\"$pt:L:101\""))

        // Le PC relève le compte (synchro périodique ou retour de la fenêtre) : il programme l'enregistrement.
        pc.cloud.sync().getOrThrow()
        val onPc = pc.c.db.recordings().findActive("$pp:L:101", start, stop)
        assertEquals("Le film du soir", onPc?.title)
        assertEquals(fr.yannickuhrig.vyzo.data.db.RecordingStatus.SCHEDULED, onPc?.status)

        // Annulée sur le téléphone : annulée sur le PC.
        phone.c.recordings.cancel(id)
        waitUntil("annulation envoyée") { fake.rows("recordings")[0].toString().contains("CANCELLED") }
        pc.cloud.sync().getOrThrow()
        assertEquals(fr.yannickuhrig.vyzo.data.db.RecordingStatus.CANCELLED, pc.c.db.recordings().get(onPc!!.id)?.status)
    }

    /** Serveur sans la table « recordings » (migration pas encore appliquée) : le reste de la synchro continue. */
    @Test
    fun sansLaTableLaProgrammationAttendSansBloquerLaSynchro() = runBlocking {
        val phone = Device()
        val pc = Device()
        phone.c.settings.update { it.copy(isRecordingHost = false) }
        fake.missingTables = setOf("recordings")
        val pt = phone.addXtream("Abonnement")
        val pp = pc.addXtream("Mon IPTV")
        phone.cloud.signIn("foyer@example.fr", "motdepasse")
        pc.cloud.signIn("foyer@example.fr", "motdepasse")

        val start = System.currentTimeMillis() + 3_600_000
        phone.c.recordings.schedule(phone.c.db.channels().get("$pt:L:101")!!, "Journal", start, start + 1_800_000)
        phone.c.repo.saveProgress("$pt:M:502", ContentKind.MOVIE, "", 600_000, 6_000_000)
        assertEquals(null, phone.cloud.sync().exceptionOrNull())
        assertTrue("la progression passe quand même", fake.rows("watch_progress").isNotEmpty())

        // La table arrive : la programmation gardée sur le téléphone part à la synchro suivante.
        fake.missingTables = emptySet()
        phone.cloud.sync().getOrThrow()
        assertEquals(1, fake.rows("recordings").size)
        pc.cloud.sync().getOrThrow()
        assertEquals("Journal", pc.c.db.recordings().findActive("$pp:L:101", start, start + 1_800_000)?.title)
    }
}
