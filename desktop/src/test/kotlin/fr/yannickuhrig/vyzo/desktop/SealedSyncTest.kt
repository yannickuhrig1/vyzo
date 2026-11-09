package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.FakeSupabase
import fr.yannickuhrig.vyzo.Fixtures
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.closeForTest
import fr.yannickuhrig.vyzo.data.cloud.CloudApi
import fr.yannickuhrig.vyzo.data.cloud.CloudStore
import fr.yannickuhrig.vyzo.data.cloud.CloudSync
import fr.yannickuhrig.vyzo.data.cloud.VaultCrypto
import fr.yannickuhrig.vyzo.data.cloud.VaultState
import fr.yannickuhrig.vyzo.data.db.ChannelPrefEntity
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.security.GeneralSecurityException

/** Playlists, guide, réglages et chaînes suivent le compte, chiffrés : le serveur (faux Supabase) ne lit rien. */
class SealedSyncTest {
    private lateinit var iptv: MockWebServer
    private lateinit var supa: MockWebServer
    private lateinit var fake: FakeSupabase
    private val containers = ArrayList<AppContainer>()
    private val email = "foyer@example.fr"
    private val password = "motdepasse"

    private inner class Device {
        val dir = Files.createTempDirectory("vyzo-chiffre").toFile()
        val c: AppContainer = testContainer(dir).also { containers += it }
        val store = CloudStore(dir.resolve("cloud-test.preferences_pb"))
        val cloud = CloudSync(
            CloudApi(supa.url("/").toString().trimEnd('/'), "cle-test", OkHttpClient()),
            store, c.db, c.repo, c.profiles, c.settings, c.scope,
        )

        suspend fun addXtream(name: String = "Abonnement") = c.repo.addPlaylist(
            PlaylistEntity(name = name, type = SourceType.XTREAM, url = iptv.url("/").toString(), username = "bob", password = "mdp-iptv-temoin"),
        )

        suspend fun names() = c.db.playlists().all().map { it.name }.sorted()
    }

    @Before
    fun setUp() {
        iptv = MockWebServer().apply { dispatcher = Fixtures.dispatcher(this); start() }
        fake = FakeSupabase()
        supa = MockWebServer().apply { dispatcher = fake; start() }
    }

    @After
    fun tearDown() {
        containers.forEach { runCatching { it.closeForTest() } }
        iptv.shutdown()
        supa.shutdown()
    }

    private fun serverText() = fake.rows(CloudSync.T_SEALED).joinToString("\n") { it.toString() }

    @Test
    fun laPlaylistArriveSurLAutreAppareilSansQueLeServeurLaLise() = runBlocking {
        val a = Device()
        a.addXtream()
        a.c.db.epg().insertSource(fr.yannickuhrig.vyzo.data.db.EpgSourceEntity(name = "Mon guide", url = "http://guide.example/temoin.xml"))
        a.c.settings.update { it.copy(tmdbApiKey = "cle-tmdb-temoin", uiMode = "PHONE", decoderMode = "SOFTWARE", audioDelayMs = 150, parentalPinHash = "pin-temoin") }
        a.cloud.signIn(email, password)
        assertEquals(VaultState.OPEN, a.cloud.status.value.vault)

        val server = serverText()
        listOf("mdp-iptv-temoin", "bob", "Abonnement", iptv.url("/").toString(), "temoin.xml", "cle-tmdb-temoin", password).forEach {
            assertFalse("« $it » ne doit pas être lisible sur le serveur", server.contains(it))
        }

        val b = Device()
        b.cloud.signIn(email, password)
        val p = b.c.db.playlists().all().single()
        assertEquals("mdp-iptv-temoin", p.password)
        assertEquals("bob", p.username)
        assertTrue("guide reçu", b.c.db.epg().sources().any { it.url == "http://guide.example/temoin.xml" })
        val s = b.c.settings.get()
        assertEquals("cle-tmdb-temoin", s.tmdbApiKey)
        assertEquals("mode d'affichage propre à l'appareil", "AUTO", s.uiMode)
        assertEquals("décodeur propre à l'appareil", "AUTO", s.decoderMode)
        assertEquals("décalage audio propre à l'appareil", 0, s.audioDelayMs)
        assertEquals("code parental propre à l'appareil", "", s.parentalPinHash)
    }

    @Test
    fun modificationsEtSuppressionsSuivent() = runBlocking {
        val a = Device()
        val pa = a.addXtream()
        a.cloud.signIn(email, password)
        val b = Device()
        b.cloud.signIn(email, password)
        val pb = b.c.db.playlists().all().single().id

        // Personnalisation d'une chaîne : les uid locaux diffèrent d'un appareil à l'autre.
        a.c.db.channelPrefs().upsertAll(listOf(ChannelPrefEntity("$pa:L:101", name = "Ma Une", number = 42)))
        a.c.repo.updatePlaylist(a.c.db.playlists().get(pa)!!.copy(name = "Renommée", password = "nouveau-mdp"))
        a.cloud.sync(pull = false).getOrThrow()
        b.cloud.sync().getOrThrow()
        assertEquals(listOf("Renommée"), b.names())
        assertEquals("nouveau-mdp", b.c.db.playlists().get(pb)?.password)
        assertEquals("Ma Une", b.c.db.channelPrefs().get("$pb:L:101")?.name)

        a.c.repo.deletePlaylist(pa)
        a.cloud.sync(pull = false).getOrThrow()
        b.cloud.sync().getOrThrow()
        assertTrue("suppression reçue", b.names().isEmpty())
    }

    @Test
    fun premiereConnexionFusionneSansRienPerdre() = runBlocking {
        val a = Device()
        a.addXtream("Sur A")
        a.c.db.playlists().all().single().let { a.c.repo.deletePlaylist(it.id) }
        a.c.repo.addPlaylist(PlaylistEntity(name = "Liste A", type = SourceType.M3U, url = "http://a.example/liste.m3u"))
        a.cloud.signIn(email, password)

        val b = Device()
        b.c.repo.addPlaylist(PlaylistEntity(name = "Liste B", type = SourceType.M3U, url = "http://b.example/liste.m3u"))
        // Un fichier de cet appareil n'a pas de sens ailleurs.
        b.c.repo.addPlaylist(PlaylistEntity(name = "Fichier local", type = SourceType.M3U, url = "file:///liste.m3u"))
        b.cloud.signIn(email, password)
        assertEquals(listOf("Fichier local", "Liste A", "Liste B"), b.names())

        a.cloud.sync().getOrThrow()
        assertEquals(listOf("Liste A", "Liste B"), a.names())
    }

    @Test
    fun lesClesSuiventLeCompteSansEtreLisibles() = runBlocking {
        val a = Device()
        a.c.settings.update { it.copy(tmdbApiKey = "tmdb-temoin", omdbApiKey = "omdb-temoin", openSubtitlesApiKey = "os-temoin", githubToken = "gh-temoin") }
        a.cloud.signIn(email, password)

        val b = Device()
        b.cloud.signIn(email, password)
        val s = b.c.settings.get()
        assertEquals(listOf("tmdb-temoin", "omdb-temoin", "os-temoin", "gh-temoin"), listOf(s.tmdbApiKey, s.omdbApiKey, s.openSubtitlesApiKey, s.githubToken))
        listOf("tmdb-temoin", "omdb-temoin", "os-temoin", "gh-temoin").forEach { assertFalse(it, it in serverText()) }
    }

    @Test
    fun unAppareilSansCleNEffacePasCellesDesAutres() = runBlocking {
        val b = Device()
        b.cloud.signIn(email, password)
        val a = Device()
        a.c.settings.update { it.copy(tmdbApiKey = "tmdb-temoin", omdbApiKey = "omdb-temoin") }
        // Le compte a déjà un bloc de réglages sans clé : à la connexion, il l'emporte sur le reste mais pas sur les clés.
        a.cloud.signIn(email, password)
        assertEquals("tmdb-temoin", a.c.settings.get().tmdbApiKey)
        assertEquals("omdb-temoin", a.c.settings.get().omdbApiKey)

        b.c.settings.update { it.copy(userAgent = "autre") }
        b.cloud.sync().getOrThrow()
        a.cloud.sync().getOrThrow()
        assertEquals("autre", a.c.settings.get().userAgent)
        assertEquals("tmdb-temoin", a.c.settings.get().tmdbApiKey)
        assertEquals("tmdb-temoin", b.c.settings.get().tmdbApiKey)
    }

    @Test
    fun retirerUneCleLaRetirePartout() = runBlocking {
        val a = Device()
        a.c.settings.update { it.copy(tmdbApiKey = "tmdb-temoin", omdbApiKey = "omdb-temoin") }
        a.cloud.signIn(email, password)
        val b = Device()
        b.cloud.signIn(email, password)

        a.c.settings.update { it.copy(tmdbApiKey = "") }
        a.cloud.sync().getOrThrow()
        b.cloud.sync().getOrThrow()
        assertEquals("", b.c.settings.get().tmdbApiKey)
        assertEquals("omdb-temoin", b.c.settings.get().omdbApiKey)
    }

    @Test
    fun recevoirLesReglagesNeLesRenvoiePas() = runBlocking {
        val a = Device()
        a.cloud.signIn(email, password)
        val b = Device()
        b.cloud.signIn(email, password)
        a.c.settings.update { it.copy(userAgent = "autre") }
        a.cloud.sync().getOrThrow()
        b.cloud.sync().getOrThrow()
        val rows = fake.rows(CloudSync.T_SEALED).toString()
        b.cloud.sync().getOrThrow()
        assertEquals(rows, fake.rows(CloudSync.T_SEALED).toString())
    }

    @Test
    fun rienNeRepartSiRienNeChange() = runBlocking {
        val a = Device()
        a.addXtream()
        a.cloud.signIn(email, password)
        val b = Device()
        b.cloud.signIn(email, password)
        b.cloud.sync().getOrThrow()
        val before = serverText()
        a.cloud.sync().getOrThrow()
        b.cloud.sync().getOrThrow()
        assertEquals("pas de boucle d'envoi entre les appareils", before, serverText())
    }

    @Test
    fun appareilDejaConnecteDemandeLeMotDePasseUneFois() = runBlocking {
        val a = Device()
        a.addXtream()
        a.cloud.signIn(email, password)

        // Une ancienne version connectée au compte n'a pas de clé : elle ne lit ni n'écrase rien.
        val b = Device()
        b.cloud.signIn(email, password)
        b.store.update { it.copy(vaultKey = "", vaultUserId = "", sealedKnown = emptyMap()) }
        b.c.db.playlists().all().forEach { b.c.repo.deletePlaylist(it.id) }
        val before = serverText()
        b.cloud.sync().getOrThrow()
        assertEquals(VaultState.LOCKED, b.cloud.status.value.vault)
        assertEquals(before, serverText())
        assertTrue(b.names().isEmpty())

        // Le curseur a pu avancer sans la clé : les lignes doivent revenir quand même.
        b.cloud.unlockVault(password)
        assertEquals(VaultState.OPEN, b.cloud.status.value.vault)
        assertEquals(listOf("Abonnement"), b.names())
    }

    @Test
    fun compteIptvRetrouveDesQueLAncienAppareilALaCle() = runBlocking {
        // Ancien appareil : connecté au compte avant la 1.9.0, il a l'abonnement IPTV mais pas la clé.
        val a = Device()
        a.cloud.signIn(email, password)
        a.store.update { it.copy(vaultKey = "", vaultUserId = "", sealedKnown = emptyMap()) }
        a.addXtream()
        a.cloud.sync().getOrThrow()
        assertEquals(VaultState.LOCKED, a.cloud.status.value.vault)

        // Nouvel appareil : rien à récupérer, et on lui dit pourquoi.
        val b = Device()
        b.cloud.signIn(email, password)
        assertTrue(b.names().isEmpty())
        assertTrue(b.cloud.status.value.restored.orEmpty().startsWith("Aucune playlist IPTV sur ton compte"))

        // Le mot de passe saisi sur l'ancien appareil suffit : l'abonnement arrive sur le nouveau.
        a.cloud.unlockVault(password)
        b.cloud.sync().getOrThrow()
        assertEquals(listOf("Abonnement"), b.names())
        assertEquals("mdp-iptv-temoin", b.c.db.playlists().all().single().password)
        assertEquals("Playlist récupérée depuis ton compte : « Abonnement ». Chargement des chaînes en cours…", b.cloud.status.value.restored)
    }

    @Test
    fun connexionAnnonceLaPlaylistRecuperee() = runBlocking {
        val a = Device()
        a.addXtream()
        a.cloud.signIn(email, password)
        val b = Device()
        b.cloud.signIn(email, password)
        assertEquals("Playlist récupérée depuis ton compte : « Abonnement ». Chargement des chaînes en cours…", b.cloud.status.value.restored)
        // L'appareil qui avait déjà la playlist n'annonce rien.
        assertNull(a.cloud.status.value.restored)
    }

    @Test
    fun baseVideeNeSupprimePasLesPlaylistsDesAutresAppareils() = runBlocking {
        val a = Device()
        a.c.repo.addPlaylist(PlaylistEntity(name = "Une", type = SourceType.M3U, url = "http://a.example/une.m3u"))
        a.c.repo.addPlaylist(PlaylistEntity(name = "Deux", type = SourceType.M3U, url = "http://a.example/deux.m3u"))
        a.cloud.signIn(email, password)
        val b = Device()
        b.cloud.signIn(email, password)
        assertEquals(listOf("Deux", "Une"), b.names())

        // Retour à une version antérieure de l'appli : Room efface les tables, la mémoire de synchro reste.
        a.c.db.playlists().all().forEach { a.c.db.playlists().delete(it.id) }
        a.cloud.sync(pull = false).getOrThrow()
        b.cloud.sync().getOrThrow()
        assertEquals("rien de supprimé ailleurs", listOf("Deux", "Une"), b.names())
        a.cloud.sync().getOrThrow()
        assertEquals("elles reviennent du compte", listOf("Deux", "Une"), a.names())
    }

    @Test
    fun motDePasseReinitialiseAvecUnAppareilQuiGardeLaCle() = runBlocking {
        val a = Device()
        a.addXtream()
        a.cloud.signIn(email, password)
        fake.setPassword(email, "nouveau-mot-de-passe")

        // A garde sa clé et la protège avec le nouveau mot de passe : rien n'est perdu pour un nouvel appareil.
        a.cloud.signIn(email, "nouveau-mot-de-passe")
        assertEquals(VaultState.OPEN, a.cloud.status.value.vault)
        val b = Device()
        b.cloud.signIn(email, "nouveau-mot-de-passe")
        assertEquals(VaultState.OPEN, b.cloud.status.value.vault)
        assertEquals(listOf("Abonnement"), b.names())
    }

    @Test
    fun motDePasseReinitialiseSansAucuneCle() = runBlocking {
        val a = Device()
        a.addXtream()
        a.cloud.signIn(email, password)
        fake.setPassword(email, "nouveau-mot-de-passe")

        val b = Device()
        b.c.repo.addPlaylist(PlaylistEntity(name = "Liste B", type = SourceType.M3U, url = "http://b.example/liste.m3u"))
        b.cloud.signIn(email, "nouveau-mot-de-passe")
        assertEquals(VaultState.LOST, b.cloud.status.value.vault)
        assertEquals("rien n'est écrasé sans accord", listOf("Liste B"), b.names())

        b.cloud.resetVault("nouveau-mot-de-passe")
        assertEquals(VaultState.OPEN, b.cloud.status.value.vault)
        val d = Device()
        d.cloud.signIn(email, "nouveau-mot-de-passe")
        assertEquals(listOf("Liste B"), d.names())
        // L'ancien appareil voit que la clé a changé : il demandera le mot de passe au lieu d'écraser.
        a.cloud.sync().getOrThrow()
        assertEquals(VaultState.LOCKED, a.cloud.status.value.vault)
    }

    @Test
    fun serveurSansLaTableChiffreeNeBloquePasLeCompte() = runBlocking {
        fake.missingTables = setOf(CloudSync.T_SEALED)
        val a = Device()
        a.addXtream()
        a.cloud.signIn(email, password)
        assertNull(a.cloud.status.value.error)
        assertEquals(email, a.cloud.status.value.email)
    }

    @Test
    fun blocModifieOuDeplaceRefuse() {
        val key = VaultCrypto.randomBytes(VaultCrypto.KEY_BYTES)
        val sealed = VaultCrypto.seal(key, "secret".toByteArray(), "ligne-1")
        assertEquals("secret", VaultCrypto.open(key, sealed, "ligne-1").decodeToString())
        val attempts = listOf(
            Triple(key, sealed, "ligne-2"),
            Triple(VaultCrypto.randomBytes(VaultCrypto.KEY_BYTES), sealed, "ligne-1"),
            Triple(key, sealed.dropLast(4) + "AAA=", "ligne-1"),
        )
        for ((k, s, ctx) in attempts) {
            try {
                VaultCrypto.open(k, s, ctx)
                fail("devait être refusé")
            } catch (e: GeneralSecurityException) {
                // attendu
            }
        }
    }

    /** Valeur publiée de PBKDF2-HMAC-SHA256 (RFC 7914, §11), pour garantir la compatibilité entre appareils. */
    @Test
    fun deriveKeyConformeAuStandard() {
        val out = VaultCrypto.deriveKey("passwd", "salt".toByteArray(), 1)
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc", out.joinToString("") { "%02x".format(it) })
        val spec = javax.crypto.spec.PBEKeySpec("mot de passé".toCharArray(), "sel".toByteArray(), 5_000, 256)
        val jdk = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        assertEquals(jdk.toList(), VaultCrypto.deriveKey("mot de passé", "sel".toByteArray(), 5_000).toList())
    }
}
