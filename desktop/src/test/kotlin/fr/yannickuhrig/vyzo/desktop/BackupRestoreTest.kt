package fr.yannickuhrig.vyzo.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.yannickuhrig.vyzo.closeForTest
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.FakeSupabase
import fr.yannickuhrig.vyzo.Fixtures
import fr.yannickuhrig.vyzo.data.backup.BackupManager
import fr.yannickuhrig.vyzo.data.backup.BackupPart
import fr.yannickuhrig.vyzo.data.backup.BackupSelection
import fr.yannickuhrig.vyzo.data.db.CategoryPrefEntity
import fr.yannickuhrig.vyzo.data.db.ChannelPrefEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.platform.BackupFile
import fr.yannickuhrig.vyzo.platform.Platform
import fr.yannickuhrig.vyzo.ui.ProfileGate
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.StorageAccess
import fr.yannickuhrig.vyzo.ui.settings.DeviceBackupsDialog
import fr.yannickuhrig.vyzo.ui.settings.PlaylistEditScreen
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

/** Sauvegarde et restauration vues de l'utilisateur : autre appareil, fichier abîmé, compte Vyzo connecté. */
@OptIn(ExperimentalTestApi::class)
class BackupRestoreTest {
    @get:Rule val rule = sqliteThenComposeRule()

    private lateinit var iptv: MockWebServer
    private lateinit var supa: MockWebServer
    private val dirs = ArrayList<java.io.File>()
    private val containers = ArrayList<AppContainer>()

    private fun device(): AppContainer {
        val d = Files.createTempDirectory("vyzo-backup").toFile()
        dirs += d
        return testContainer(d, supa.url("/").toString().trimEnd('/')).also { containers += it }
    }

    /** Un appareil dont le dossier privé est propre, avec un dossier partagé qui reste quand l'appli part ([visible] : Android laisse voir). */
    private fun deviceWithShared(shared: java.io.File?, visible: () -> Boolean = { true }): AppContainer {
        val d = Files.createTempDirectory("vyzo-backup").toFile()
        dirs += d
        val c = testContainer(d, supa.url("/").toString().trimEnd('/')) { real ->
            object : Platform by real {
                override val localBackupFile = d.resolve("vyzo-sauvegarde.json")
                override fun writeSharedBackup(text: String): List<String> {
                    if (shared == null) return emptyList()
                    shared.resolve("vyzo-sauvegarde.json").writeText(text)
                    return listOf("Téléchargements/Vyzo")
                }
                override fun sharedBackups(): List<BackupFile> =
                    if (shared == null || !visible()) emptyList()
                    else shared.listFiles().orEmpty().map { BackupFile(it.absolutePath, it.name, it.lastModified()) }
            }
        }
        containers += c
        return c
    }

    private fun sharedFolder(): java.io.File = Files.createTempDirectory("vyzo-partage").toFile().also { dirs += it }

    private suspend fun AppContainer.addXtream(): Long {
        val id = repo.addPlaylist(PlaylistEntity(name = "Abonnement", type = SourceType.XTREAM, url = iptv.url("/").toString(), username = "bob", password = "secret"))
        sync.syncPlaylist(id).getOrThrow()
        return id
    }

    @Before
    fun setUp() {
        iptv = MockWebServer().apply { dispatcher = Fixtures.dispatcher(this); start() }
        supa = MockWebServer().apply { dispatcher = FakeSupabase(); start() }
    }

    @After
    fun tearDown() {
        containers.forEach { runCatching { it.closeForTest() } }
        dirs.forEach { it.deleteRecursively() }
        iptv.shutdown()
        supa.shutdown()
    }

    /** Remplacer une TV : tout ce que promet l'écran « Sauvegarde » revient sur un appareil neuf, profil actif compris. */
    @Test
    fun restaurationSurUneTvNeuve() = runBlocking {
        val a = device()
        a.db.playlists().insert(PlaylistEntity(name = "Vieille", type = SourceType.M3U, url = "file:///vieille"))
        val pa = a.addXtream()
        a.repo.toggleFavorite("$pa:L:101", ContentKind.LIVE)
        val groupId = a.repo.createGroup("Sport")
        a.repo.setInGroup(groupId, "$pa:L:101", ContentKind.LIVE, true)
        val lea = a.profiles.create("Léa", isKid = true)
        a.profiles.select(lea.id)
        withTimeout(5_000) { a.profiles.active.first { it?.id == lea.id } }
        a.repo.toggleFavorite("$pa:M:502", ContentKind.MOVIE)
        a.profiles.select(a.db.profiles().all().first { it.name == "Principal" }.id)
        a.db.categories().upsertPrefs(listOf(CategoryPrefEntity(a.db.categories().all().first().uid, hidden = true)))
        a.db.channelPrefs().upsertAll(listOf(ChannelPrefEntity("$pa:L:101", name = "Ma Une", number = 42)))
        a.settings.update { it.copy(autoNextEpisodeOnOutro = true, githubToken = "ghp_temoin") }
        val json = BackupManager.export(a)

        val b = device()
        val activeBefore = b.profiles.activeIdNow()
        assertEquals(2, BackupManager.import(b, json))
        val pb = b.db.playlists().all().first { it.type == SourceType.XTREAM }.id
        b.sync.syncPlaylist(pb).getOrThrow()

        assertEquals("secret", b.db.playlists().get(pb)?.password)
        assertTrue("réglage restauré", b.settings.get().autoNextEpisodeOnOutro)
        val names = b.db.profiles().all().map { it.name }
        assertEquals("pas de profil « Principal » en double : $names", 1, names.count { it == "Principal" })
        assertTrue("Léa restaurée", "Léa" in names)
        assertTrue("favori visible dans le profil actif", b.repo.isFavorite("$pb:L:101").first())
        assertEquals("groupe restauré", listOf("Sport"), b.repo.userGroups().first().map { it.name }.filter { it == "Sport" })
        assertTrue("catégorie masquée restaurée", b.db.categories().allPrefs().any { it.hidden })
        assertEquals("chaîne renommée", "Ma Une", b.db.channels().get("$pb:L:101")?.name)
        assertEquals("chaîne renumérotée", 42, b.db.channels().get("$pb:L:101")?.number)
    }

    /** Envoi sélectif : la TV ne reçoit que le coché et garde tout le reste. */
    @Test
    fun envoiSelectifGardeLeResteDeLaTv() = runBlocking {
        val a = device()
        val pa = a.addXtream()
        a.repo.toggleFavorite("$pa:L:101", ContentKind.LIVE)
        a.repo.addPlaylist(PlaylistEntity(name = "Seconde", type = SourceType.M3U, url = "file:///seconde"))
        a.settings.update { it.copy(autoNextEpisodeOnOutro = true) }
        val json = BackupManager.exportSelection(a, BackupSelection(setOf(pa), setOf(BackupPart.PROFILES)))
        assertFalse("playlist décochée absente de l'envoi", json.contains("file:///seconde"))

        val b = device()
        b.repo.addPlaylist(PlaylistEntity(name = "Sur la TV", type = SourceType.M3U, url = "file:///tv"))
        assertEquals(1, BackupManager.importSelection(b, json))
        BackupManager.importSelection(b, json)

        assertEquals("pas de doublon au second envoi", listOf("Abonnement", "Sur la TV"), b.db.playlists().all().map { it.name }.sorted())
        assertFalse("réglages non cochés inchangés", b.settings.get().autoNextEpisodeOnOutro)
        val pb = b.db.playlists().all().first { it.type == SourceType.XTREAM }.id
        b.sync.syncPlaylist(pb).getOrThrow()
        assertTrue("favori visible", b.repo.isFavorite("$pb:L:101").first())
    }

    /** Favoris cochés sans leur playlist : pas de mot de passe envoyé, et ils se rattachent quand la playlist arrive. */
    @Test
    fun favoriSansSaPlaylistSeRattachePlusTard() = runBlocking {
        val a = device()
        val pa = a.addXtream()
        a.repo.toggleFavorite("$pa:L:101", ContentKind.LIVE)
        val json = BackupManager.exportSelection(a, BackupSelection(emptySet(), setOf(BackupPart.PROFILES)))
        assertFalse(json.contains("secret"))

        val b = device()
        BackupManager.importSelection(b, json)
        assertTrue(b.db.playlists().all().isEmpty())
        val pb = b.addXtream()
        assertTrue("favori rattaché à la playlist arrivée ensuite", b.repo.isFavorite("$pb:L:101").first())
    }

    /** Les guides TV ajoutés à la main reviennent avec leur état, et une seconde restauration ne les double pas. */
    @Test
    fun restaurationRendLesSourcesDeGuide() = runBlocking {
        val a = device()
        a.repo.addEpgSource("Guide actif", "https://exemple.fr/actif.xml")
        a.repo.addEpgSource("Guide en pause", "https://exemple.fr/pause.xml")
        a.repo.setEpgSourceEnabled(a.db.epg().sources().first { it.name == "Guide en pause" }, false)
        val json = BackupManager.export(a)
        val attendu = listOf(
            Triple("Guide actif", "https://exemple.fr/actif.xml", true),
            Triple("Guide en pause", "https://exemple.fr/pause.xml", false),
        )
        suspend fun AppContainer.guides() = db.epg().sources().map { Triple(it.name, it.url, it.enabled) }

        val b = device()
        BackupManager.import(b, json)
        assertEquals("sources revenues sur l'appareil neuf", attendu, b.guides())
        assertFalse("une source restaurée reste une source manuelle", b.db.epg().sources().any { it.auto })

        val ancienne = "src:${b.db.epg().sources().first().id}"
        b.db.programs().insertAll(listOf(ProgramEntity(channelKey = "une", start = 1, stop = 2, title = "JT", sourceKey = ancienne, generation = 1)))
        BackupManager.import(b, json)
        assertEquals("pas de doublon après une seconde restauration", attendu, b.guides())
        assertEquals("pas de programmes en double une fois le guide rechargé", 0, b.db.programs().countSource(ancienne))

        // Ajouter deux fois la même adresse est possible : la restauration n'en garde qu'une.
        a.repo.addEpgSource("Guide actif bis", "https://exemple.fr/actif.xml")
        val c = device()
        BackupManager.import(c, BackupManager.export(a))
        assertEquals("une adresse en double dans le fichier", attendu, c.guides())
    }

    /** Restaurer sur la même TV : la reprise de lecture ne doit pas disparaître. */
    @Test
    fun restaurationGardeLesReprises() = runBlocking {
        val a = device()
        val p = a.addXtream()
        a.repo.saveProgress("$p:M:502", ContentKind.MOVIE, "", 600_000, 6_000_000)
        BackupManager.import(a, BackupManager.export(a))
        val np = a.db.playlists().all().single().id
        a.sync.syncPlaylist(np).getOrThrow()
        assertEquals(600_000L, a.repo.progressNow("$np:M:502")?.positionMs)
        assertEquals(listOf("$np:M:502"), a.repo.movies(Repository.KEY_CONTINUE).first().map { it.uid })
    }

    /** Fichier abîmé ou d'une autre appli : rien ne doit être effacé. */
    @Test
    fun fichierInvalideNEffaceRien() = runBlocking {
        val a = device()
        val p = a.addXtream()
        a.repo.toggleFavorite("$p:L:101", ContentKind.LIVE)
        val json = BackupManager.export(a)
        val cases = mapOf(
            "tronqué" to json.take(json.length / 2),
            "texte" to "pas une sauvegarde",
            "autre appli" to json.replace("\"app\": \"Vyzo\"", "\"app\": \"Autre\""),
        )
        for ((label, text) in cases) {
            val r = runCatching { BackupManager.import(a, text) }
            assertTrue("$label doit être refusé", r.isFailure)
            assertEquals("$label : la source doit rester", p, a.db.playlists().all().single().id)
            assertTrue("$label : le favori doit rester", a.repo.isFavorite("$p:L:101").first())
        }
    }

    /** Compte connecté : restaurer sur une TV garde les favoris sur l'autre. */
    @Test
    fun restaurationAvecCompteGardeLesFavorisAilleurs() = runBlocking {
        val a = device()
        val b = device()
        val pa = a.addXtream()
        val pb = b.addXtream()
        a.repo.toggleFavorite("$pa:L:101", ContentKind.LIVE)
        a.cloud.signIn("foyer@example.fr", "motdepasse")
        b.cloud.signIn("foyer@example.fr", "motdepasse")
        assertTrue(b.repo.isFavorite("$pb:L:101").first())
        val json = BackupManager.export(a)

        BackupManager.import(a, json)
        val na = a.db.playlists().all().single().id
        a.sync.syncPlaylist(na).getOrThrow()
        a.cloud.sync().getOrThrow()
        b.cloud.sync().getOrThrow()
        assertTrue("favori toujours là sur la TV qui a restauré", a.repo.isFavorite("$na:L:101").first())
        assertTrue("favori toujours là sur l'autre TV", b.repo.isFavorite("$pb:L:101").first())
    }

    /** Une sauvegarde d'avant les profils (version 1) reste lisible : le favori revient dans le profil actif. */
    @Test
    fun ancienneSauvegardeSansProfils() = runBlocking {
        val a = device()
        val p = a.addXtream()
        val active = a.profiles.activeIdNow()
        a.repo.toggleFavorite("$p:L:101", ContentKind.LIVE)
        val json = BackupManager.export(a)
            .replace(Regex("\"profiles\": \\[.*?\\],", RegexOption.DOT_MATCHES_ALL), "\"profiles\": [],")
            .replace("\"profileId\": \"$active\"", "\"profileId\": \"\"")
        val b = device()
        BackupManager.import(b, json)
        val np = b.db.playlists().all().single().id
        b.sync.syncPlaylist(np).getOrThrow()
        assertTrue(b.repo.isFavorite("$np:L:101").first())
    }

    /** La sauvegarde ne doit pas contenir de secret de développeur. */
    @Test
    fun laSauvegardeNExportePasDeJeton() = runBlocking {
        val a = device()
        a.settings.update { it.copy(githubToken = "ghp_temoin") }
        assertFalse(BackupManager.export(a).contains("ghp_temoin"))
    }

    /**
     * Sur une TV neuve, la restauration ajoute un profil : l'écran « Qui regarde ? » prenait la place des réglages en
     * pleine session, ce qui annulait la restauration à moitié (sources sans chaînes, favoris perdus).
     */
    @Test
    fun unProfileArriveNInterromptPasLaSession() {
        val tv = device()
        runBlocking { tv.profiles.ensureOne() }
        var coupe = false
        rule.setContent {
            CompositionLocalProvider(LocalContainer provides tv) {
                MaterialTheme {
                    ProfileGate {
                        LaunchedEffect(Unit) { try { awaitCancellation() } finally { coupe = true } }
                        Text("Réglages")
                    }
                }
            }
        }
        rule.waitUntilAtLeastOneExists(hasText("Réglages"), 10_000)
        runBlocking { tv.profiles.create("Léa") }
        rule.waitUntil(10_000) { tv.profiles.profiles.value?.size == 2 }
        rule.waitForIdle()
        rule.onNodeWithText("Réglages").assertExists()
        assertFalse("la session ne doit pas être coupée", coupe)
    }

    /** La question reste posée au lancement quand il y a plusieurs profils. */
    @Test
    fun plusieursProfilsAuLancementPoseLaQuestion() {
        val tv = device()
        runBlocking { tv.profiles.ensureOne(); tv.profiles.create("Léa") }
        rule.setContent {
            CompositionLocalProvider(LocalContainer provides tv) {
                MaterialTheme { ProfileGate { Text("Réglages") } }
            }
        }
        rule.waitUntilAtLeastOneExists(hasText("Qui regarde ?"), 10_000)
        rule.onNodeWithText("Réglages").assertDoesNotExist()
    }

    /** Le scénario complet sans sélecteur de fichier : export, désinstallation, réinstallation, restauration. */
    @Test
    fun laSauvegardeSurvitALaDesinstallation() = runBlocking {
        val shared = sharedFolder()
        val avant = deviceWithShared(shared)
        val p = avant.addXtream()
        avant.repo.toggleFavorite("$p:L:101", ContentKind.LIVE)
        val saved = BackupManager.saveOnDevice(avant.platform, BackupManager.export(avant))
        assertEquals(listOf("Téléchargements/Vyzo"), saved.places)
        assertTrue(saved.survives)
        assertFalse("rien dans le dossier de l'appli, qui part avec elle", avant.platform.localBackupFile.exists())

        val apres = deviceWithShared(shared)
        val found = BackupManager.findOnDevice(apres.platform).single()
        assertEquals(1, found.playlists)
        BackupManager.import(apres, found.text)
        val np = apres.db.playlists().all().single().id
        apres.sync.syncPlaylist(np).getOrThrow()
        assertEquals("secret", apres.db.playlists().get(np)?.password)
        assertTrue("favori revenu", apres.repo.isFavorite("$np:L:101").first())
    }

    /** Rien d'écrit hors de l'appli : on écrit quand même, et on le dit, car ce dossier disparaît à la désinstallation. */
    @Test
    fun sansDossierPartageLaSauvegardePrevient() = runBlocking {
        val a = deviceWithShared(null)
        a.addXtream()
        val saved = BackupManager.saveOnDevice(a.platform, BackupManager.export(a))
        assertFalse(saved.survives)
        assertEquals(listOf(a.platform.localBackupFile.absolutePath), saved.places)
        assertEquals("relue depuis le dossier de l'appli", 1, BackupManager.findOnDevice(a.platform).single().playlists)
    }

    /** Dans le dossier partagé : la plus récente d'abord, et ni fichier abîmé ni fichier d'une autre appli. */
    @Test
    fun laRechercheNeGardeQueLesSauvegardesValides() = runBlocking {
        val shared = sharedFolder()
        val a = deviceWithShared(shared)
        a.addXtream()
        val json = BackupManager.export(a)
        shared.resolve("vyzo-sauvegarde (1).json").apply { writeText(json); setLastModified(1_000_000) }
        shared.resolve("vyzo-sauvegarde (2).json").apply { writeText(json); setLastModified(2_000_000) }
        shared.resolve("vyzo-sauvegarde (3).json").writeText(json.take(json.length / 2))
        shared.resolve("vyzo-sauvegarde (4).json").writeText(json.replace("\"app\": \"Vyzo\"", "\"app\": \"Autre\""))
        assertEquals(
            listOf("vyzo-sauvegarde (2).json", "vyzo-sauvegarde (1).json"),
            BackupManager.findOnDevice(a.platform).map { it.file.location },
        )
    }

    /** Comme sur Android TV : Android cache le fichier tant que l'accès n'est pas donné, la recherche reprend au retour du réglage. */
    @Test
    fun laccesDemandeRetrouveLaSauvegarde() {
        val shared = sharedFolder()
        val avant = deviceWithShared(shared)
        runBlocking {
            avant.addXtream()
            BackupManager.saveOnDevice(avant.platform, BackupManager.export(avant))
        }
        var granted = false
        var requests = 0
        val apres = deviceWithShared(shared) { granted }
        var search by mutableIntStateOf(0)
        var restored = 0
        val access = StorageAccess(isGranted = { granted }, neededToWrite = false, request = { requests++; granted = true; search++; true })
        rule.setContent {
            CompositionLocalProvider(LocalContainer provides apres) {
                MaterialTheme {
                    DeviceBackupsDialog(
                        access, search, onPhone = {},
                        onRestore = { restored = it.playlists },
                        onDismiss = {},
                    )
                }
            }
        }
        rule.waitUntilAtLeastOneExists(hasText("Retrouver ta sauvegarde"), 10_000)
        rule.onNodeWithText("Autoriser l'accès aux fichiers").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Restaurer une sauvegarde"), 10_000)
        assertEquals(1, requests)
        rule.onNodeWithText("Autoriser l'accès aux fichiers").assertDoesNotExist()
        rule.onNodeWithText("vyzo-sauvegarde.json").performClick()
        rule.waitUntil(10_000) { restored == 1 }
    }

    /** Une TV sans ce réglage : plus de bouton mort, on propose le téléphone. */
    @Test
    fun sansReglageDAccesOnProposeLeTelephone() {
        val tv = deviceWithShared(sharedFolder()) { false }
        var phone = 0
        val access = StorageAccess(isGranted = { false }, neededToWrite = false, request = { false })
        rule.setContent {
            CompositionLocalProvider(LocalContainer provides tv) {
                MaterialTheme { DeviceBackupsDialog(access, 0, onPhone = { phone++ }, onRestore = {}, onDismiss = {}) }
            }
        }
        rule.waitUntilAtLeastOneExists(hasText("Autoriser l'accès aux fichiers"), 10_000)
        rule.onNodeWithText("Autoriser l'accès aux fichiers").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Aucune sauvegarde trouvée"), 10_000)
        rule.onNodeWithText("Envoyer le fichier depuis le téléphone").performClick()
        rule.waitUntil(10_000) { phone == 1 }
    }

    /** Écran de bienvenue : restauration et compte sont proposés avant d'ajouter une source. */
    @Test
    fun laBienvenueProposeRestaurationEtCompte() {
        val tv = device()
        rule.setContent {
            CompositionLocalProvider(LocalContainer provides tv) {
                MaterialTheme { PlaylistEditScreen(0, firstRun = true, onDone = {}) }
            }
        }
        rule.waitUntilAtLeastOneExists(hasText("Restaurer une sauvegarde"), 10_000)
        rule.onNodeWithText("Compte Vyzo").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Se connecter", substring = true), 10_000)
        rule.onNodeWithText("Retour").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Restaurer une sauvegarde"), 10_000)
    }

    /** Ailleurs que sur la bienvenue, l'écran d'ajout de source reste tel quel. */
    @Test
    fun lAjoutDeSourceNeProposePasLaRestauration() {
        val tv = device()
        rule.setContent {
            CompositionLocalProvider(LocalContainer provides tv) {
                MaterialTheme { PlaylistEditScreen(0, firstRun = false, onDone = {}) }
            }
        }
        rule.waitUntilAtLeastOneExists(hasText("Ajouter une playlist"), 10_000)
        rule.onNodeWithText("Restaurer une sauvegarde").assertDoesNotExist()
        rule.onNodeWithText("Compte Vyzo").assertDoesNotExist()
    }
}
