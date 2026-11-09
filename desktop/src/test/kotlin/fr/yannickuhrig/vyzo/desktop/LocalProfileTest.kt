package fr.yannickuhrig.vyzo.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.FakeSupabase
import fr.yannickuhrig.vyzo.Fixtures
import fr.yannickuhrig.vyzo.closeForTest
import fr.yannickuhrig.vyzo.data.backup.BackupManager
import fr.yannickuhrig.vyzo.data.backup.BackupPart
import fr.yannickuhrig.vyzo.data.backup.BackupSelection
import fr.yannickuhrig.vyzo.data.cloud.CloudApi
import fr.yannickuhrig.vyzo.data.cloud.CloudStore
import fr.yannickuhrig.vyzo.data.cloud.CloudSync
import fr.yannickuhrig.vyzo.data.db.ChannelHistoryEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.ProfileEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.platform.Platform
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.LocalDesktop
import fr.yannickuhrig.vyzo.ui.profile.ProfilesScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

/** Un profil local reste sur son appareil : ni compte, ni TV, ni fichier exporté, et sa sauvegarde ne s'ouvre pas ailleurs. */
class LocalProfileTest {
    @get:Rule
    val rule = sqliteThenComposeRule()

    private lateinit var iptv: MockWebServer
    private lateinit var supa: MockWebServer
    private lateinit var fake: FakeSupabase
    private val devices = ArrayList<Device>()

    private inner class Device {
        val dir = Files.createTempDirectory("vyzo-local").toFile()
        val c: AppContainer = testContainer(dir) { real ->
            object : Platform by real {
                override val localProfileDir = dir.resolve("profils-locaux")
            }
        }
        val cloud = CloudSync(
            CloudApi(supa.url("/").toString().trimEnd('/'), "cle-test", OkHttpClient()),
            CloudStore(dir.resolve("cloud-test.preferences_pb")), c.db, c.repo, c.profiles, c.settings, c.scope,
        )

        init { devices += this }

        suspend fun use(profileId: String) {
            c.profiles.select(profileId)
            withTimeout(5_000) { c.profiles.active.first { it?.id == profileId } }
            withTimeout(5_000) { c.repo.viewScope.first { it.profileId == profileId } }
        }

        suspend fun addXtream(): Long {
            val id = c.repo.addPlaylist(PlaylistEntity(name = "Abonnement", type = SourceType.XTREAM, url = iptv.url("/").toString(), username = "bob", password = "secret"))
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
        devices.forEach { d -> runCatching { d.c.closeForTest() }; d.dir.deleteRecursively() }
        iptv.shutdown()
        supa.shutdown()
    }

    /** Un blocage fait échouer le test en montrant où il attend, au lieu de figer toute la suite. */
    private fun scenario(body: suspend () -> Unit) = runBlocking { withTimeout(45_000) { body() } }

    private val sent: String get() = listOf("profiles", "watch_progress", "user_lists", "list_items").flatMap { fake.rows(it) }.joinToString("\n")

    @Test
    fun rienNePartVersLeCompte() = scenario {
        val pc = Device()
        val pid = pc.addXtream()
        val secret = pc.c.localProfiles.create("Secret")
        val partage = pc.c.profiles.create("Partagé")
        pc.cloud.signIn("foyer@example.fr", "motdepasse")

        pc.use(secret.id)
        pc.c.repo.toggleFavorite("$pid:L:101", ContentKind.LIVE)
        pc.c.repo.saveProgress("$pid:M:502", ContentKind.MOVIE, "", 600_000, 6_000_000)
        val groupe = pc.c.repo.createGroup("Mes films")
        pc.c.repo.deleteGroup(pc.c.repo.createGroup("Éphémère"))
        pc.c.profiles.rename(secret.id, "Très secret")
        pc.use(partage.id)
        pc.c.repo.toggleFavorite("$pid:L:101", ContentKind.LIVE)
        pc.cloud.sync().getOrThrow()

        assertTrue("le profil partagé part bien", sent.contains(partage.id))
        assertFalse("rien du profil local sur le serveur", sent.contains(secret.id))
        assertFalse(sent.contains("secret", ignoreCase = true))
        assertFalse(sent.contains("Mes films"))
        assertTrue("ses données restent ici", pc.c.db.groups().group(groupe) != null)

        // Se déconnecter marque tout à renvoyer : au compte suivant non plus, il ne part pas.
        pc.cloud.signOut()
        pc.cloud.signIn("foyer@example.fr", "motdepasse")
        pc.cloud.sync().getOrThrow()
        assertFalse(sent.contains(secret.id))

        // L'autre appareil du compte n'en sait rien.
        val tv = Device()
        tv.cloud.signIn("foyer@example.fr", "motdepasse")
        assertTrue(tv.c.db.profiles().all().any { it.id == partage.id })
        assertNull(tv.c.db.profiles().get(secret.id))
    }

    /** Les suppressions en attente d'un profil local ne doivent pas bloquer celles des autres profils. */
    @Test
    fun sesSuppressionsNeBloquentPasLesAutres() = scenario {
        val pc = Device()
        val pid = pc.addXtream()
        val secret = pc.c.localProfiles.create("Secret")
        val partage = pc.c.profiles.create("Partagé")
        pc.cloud.signIn("foyer@example.fr", "motdepasse")
        pc.use(partage.id)
        pc.c.repo.toggleFavorite("$pid:L:101", ContentKind.LIVE)
        pc.cloud.sync().getOrThrow()

        pc.use(secret.id)
        repeat(3) { pc.c.repo.deleteGroup(pc.c.repo.createGroup("g$it")) }
        pc.use(partage.id)
        pc.c.repo.toggleFavorite("$pid:L:101", ContentKind.LIVE)
        pc.cloud.sync().getOrThrow()

        assertTrue(fake.rows("list_items").single().toString().contains("\"deleted\":true"))
        assertEquals(emptyList<Any>(), pc.c.db.tombstones().pending(10))
    }

    /** Même nom qu'un profil du compte : la première connexion fusionne les autres, jamais celui-ci. */
    @Test
    fun pasDeFusionAvecUnProfilDuCompte() = scenario {
        val tv = Device()
        val lea = tv.c.profiles.create("Léa")
        tv.cloud.signIn("foyer@example.fr", "motdepasse")

        val pc = Device()
        val pid = pc.addXtream()
        val locale = pc.c.localProfiles.create("Léa")
        pc.use(locale.id)
        pc.c.repo.toggleFavorite("$pid:L:101", ContentKind.LIVE)
        pc.cloud.signIn("foyer@example.fr", "motdepasse")

        assertTrue(pc.c.db.profiles().get(locale.id)!!.localOnly)
        assertNotNull(pc.c.db.profiles().get(lea.id))
        assertTrue(pc.c.repo.isFavorite("$pid:L:101").first())
        assertFalse(sent.contains(locale.id))
    }

    @Test
    fun absentDesExportsEtDesEnvoisALaTv() = scenario {
        val pc = Device()
        val pid = pc.addXtream()
        val secret = pc.c.localProfiles.create("Secret")
        val partage = pc.c.profiles.create("Partagé")
        pc.use(secret.id)
        pc.c.repo.toggleFavorite("$pid:L:101", ContentKind.LIVE)
        pc.c.repo.createGroup("Mes films")
        pc.use(partage.id)

        for (text in listOf(BackupManager.export(pc.c), BackupManager.exportSelection(pc.c, BackupSelection(setOf(pid), setOf(BackupPart.PROFILES))))) {
            assertTrue(text.contains(partage.id))
            assertFalse(text.contains(secret.id))
            assertFalse(text.contains("Mes films"))
        }

        // Restaurer un fichier sur le même appareil laisse le profil local tel quel.
        BackupManager.import(pc.c, BackupManager.export(pc.c))
        assertTrue(pc.c.db.profiles().get(secret.id)!!.localOnly)
        assertEquals(1, pc.c.db.groups().allItems().count { it.profileId == secret.id })
    }

    /** Appareil qui n'a plus que des profils locaux : son envoi à la TV ne vide pas les favoris de celle-ci. */
    @Test
    fun envoiSansProfilPartageNeVideRienSurLaTv() = scenario {
        val pc = Device()
        val principal = pc.c.profiles.activeNow()
        val secret = pc.c.localProfiles.create("Secret")
        pc.use(secret.id)
        pc.c.profiles.delete(principal.id)
        val text = BackupManager.exportSelection(pc.c, BackupSelection(emptySet(), setOf(BackupPart.PROFILES)))

        val tv = Device()
        val pid = tv.addXtream()
        tv.c.repo.toggleFavorite("$pid:L:101", ContentKind.LIVE)
        BackupManager.importSelection(tv.c, text)
        assertTrue(tv.c.repo.isFavorite("$pid:L:101").first())
    }

    @Test
    fun sauvegardeEtRestaurationSurCetAppareil() = scenario {
        val pc = Device()
        val pid = pc.addXtream()
        val secret = pc.c.localProfiles.create("Secret", isKid = true)
        pc.c.profiles.setPin(secret.id, "1234")
        pc.use(secret.id)
        pc.c.repo.toggleFavorite("$pid:L:101", ContentKind.LIVE)
        val groupe = pc.c.repo.createGroup("Mes films")
        pc.c.repo.setInGroup(groupe, "$pid:M:502", ContentKind.MOVIE, true)
        pc.c.repo.saveProgress("$pid:M:502", ContentKind.MOVIE, "", 600_000, 6_000_000)
        pc.c.db.progress().touchChannel(ChannelHistoryEntity(secret.id, "$pid:L:101", 1000))
        pc.c.localProfiles.backup(secret.id)
        pc.cloud.signIn("foyer@example.fr", "motdepasse")

        // Ce qui a changé depuis disparaît à la restauration.
        pc.c.repo.toggleFavorite("$pid:L:101", ContentKind.LIVE)
        pc.c.repo.saveProgress("$pid:M:502", ContentKind.MOVIE, "", 900_000, 6_000_000)
        pc.c.repo.deleteGroup(groupe)
        pc.c.localProfiles.restore(secret.id)
        assertTrue(pc.c.repo.isFavorite("$pid:L:101").first())
        assertEquals(600_000L, pc.c.repo.progressNow("$pid:M:502")?.positionMs)
        assertEquals(1, pc.c.db.groups().allGroups().count { it.name == "Mes films" && it.profileId == secret.id })
        assertEquals(listOf("$pid:L:101"), pc.c.db.progress().history(secret.id).map { it.channelUid })

        // Profil supprimé : il revient en entier, code et type compris, toujours local.
        pc.use(pc.c.db.profiles().all().first { it.id != secret.id }.id)
        assertTrue(pc.c.profiles.delete(secret.id))
        assertNull("supprimé tout de suite, sans pierre tombale", pc.c.db.profiles().get(secret.id))
        assertEquals(emptyList<Any>(), pc.c.db.progress().forProfile(secret.id))
        assertEquals(listOf(secret.id), pc.c.localProfiles.backups().map { it.id })
        pc.c.localProfiles.restore(secret.id)
        val back = pc.c.db.profiles().get(secret.id)!!
        assertTrue(back.localOnly && back.isKid)
        assertTrue(pc.c.profiles.checkPin(back, "1234"))
        pc.use(secret.id)
        assertTrue(pc.c.repo.isFavorite("$pid:L:101").first())

        pc.cloud.sync().getOrThrow()
        assertFalse("la restauration n'envoie rien", sent.contains(secret.id))

        pc.c.localProfiles.deleteBackup(secret.id)
        assertEquals(emptyList<Any>(), pc.c.localProfiles.backups())
    }

    /** Playlist recréée entre-temps (autre identifiant local) : tout se retrouve par la clé stable. */
    @Test
    fun laRestaurationRetrouveUnePlaylistRecreee() = scenario {
        val pc = Device()
        val pid = pc.addXtream()
        val secret = pc.c.localProfiles.create("Secret")
        pc.use(secret.id)
        pc.c.repo.toggleFavorite("$pid:L:101", ContentKind.LIVE)
        pc.c.db.progress().touchChannel(ChannelHistoryEntity(secret.id, "$pid:L:101", 1000))
        pc.c.localProfiles.backup(secret.id)
        pc.c.repo.deletePlaylist(pid)
        val nouvelle = pc.addXtream()
        assertTrue(nouvelle != pid)

        pc.c.localProfiles.restore(secret.id)
        assertTrue(pc.c.repo.isFavorite("$nouvelle:L:101").first())
        assertEquals(listOf("$nouvelle:L:101"), pc.c.db.progress().history(secret.id).map { it.channelUid })
    }

    @Test
    fun laSauvegardeNeSOuvrePasAilleurs() = scenario {
        val pc = Device()
        val secret = pc.c.localProfiles.create("Secret")
        pc.c.localProfiles.backup(secret.id)

        val autre = Device()
        autre.c.localProfiles.create("Sien")
        val copie = autre.dir.resolve("profils-locaux/${secret.id}.json")
        pc.dir.resolve("profils-locaux/${secret.id}.json").copyTo(copie)

        assertEquals(emptyList<Any>(), autre.c.localProfiles.backups())
        assertTrue(runCatching { autre.c.localProfiles.restore(secret.id) }.isFailure)
        assertNull(autre.c.db.profiles().get(secret.id))
    }

    /** Base recopiée sur un nouveau téléphone par la sauvegarde d'Android : le profil de l'ancien disparaît au démarrage. */
    @Test
    fun unProfilLocalVenuDUnAutreAppareilEstEfface() = scenario {
        val tel = Device()
        val pid = tel.addXtream()
        val mien = tel.c.localProfiles.create("Mien")
        val venu = ProfileEntity("venu-d-ailleurs", "Ancien", dirty = false, localDeviceId = "autre-appareil")
        tel.c.db.profiles().upsert(venu)
        tel.use(venu.id)
        tel.c.repo.toggleFavorite("$pid:L:101", ContentKind.LIVE)

        tel.c.localProfiles.removeForeign()

        assertNull(tel.c.db.profiles().get(venu.id))
        assertEquals(0, tel.c.db.groups().allItems().count { it.profileId == venu.id })
        assertNotNull("celui de cet appareil reste", tel.c.db.profiles().get(mien.id))
        assertEquals("", tel.c.settings.get().activeProfileId)
    }

    @Test
    fun creationDepuisLEcranDesProfils() {
        val pc = Device()
        rule.setContent {
            CompositionLocalProvider(LocalContainer provides pc.c, LocalDesktop provides true) {
                MaterialTheme { ProfilesScreen() }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Ajouter un profil")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Ajouter un profil").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Secret")
        rule.onNode(hasText("Valider")).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Profil adulte")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Profil adulte").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Uniquement sur cet appareil")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Uniquement sur cet appareil").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("LOCAL")).fetchSemanticsNodes().isNotEmpty() }

        val cree = runBlocking { pc.c.db.profiles().all().first { it.name == "Secret" } }
        assertTrue(cree.localOnly)

        // Le menu propose la sauvegarde, puis la restauration une fois sauvegardé.
        rule.onNodeWithText("Secret").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Sauvegarder sur cet appareil")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Sauvegarder sur cet appareil").performClick()
        rule.waitUntil(5_000) { runBlocking { pc.c.localProfiles.backups().isNotEmpty() } }
        rule.waitForIdle()
        rule.onNodeWithText("Secret").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Restaurer la sauvegarde")).fetchSemanticsNodes().isNotEmpty() }
    }
}
