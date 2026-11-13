package fr.yannickuhrig.vyzo.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import fr.yannickuhrig.vyzo.FlavorScreens
import fr.yannickuhrig.vyzo.ScreensBase
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.sport.SportEvent
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.ui.common.PlayKind
import fr.yannickuhrig.vyzo.ui.LocalVariantNav
import fr.yannickuhrig.vyzo.ui.player.PlayerChoiceArgs
import fr.yannickuhrig.vyzo.ui.player.PlayerScreen
import fr.yannickuhrig.vyzo.ui.player.PlayerUi
import fr.yannickuhrig.vyzo.ui.player.VodBarArgs
import fr.yannickuhrig.vyzo.ui.AppRoot
import fr.yannickuhrig.vyzo.ui.LaunchRequest
import fr.yannickuhrig.vyzo.ui.NavPosition
import fr.yannickuhrig.vyzo.ui.SearchActions
import fr.yannickuhrig.vyzo.ui.common.FormFactor
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.Fmt
import fr.yannickuhrig.vyzo.ui.common.LocalFormFactor
import fr.yannickuhrig.vyzo.ui.home.ChannelItem
import fr.yannickuhrig.vyzo.ui.sport.SportState
import fr.yannickuhrig.vyzo.ui.theme.VyzoTheme
import fr.yannickuhrig.vyzo.ui.vod.VodCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import fr.yannickuhrig.vyzo.data.link.DeepLinkKind
import fr.yannickuhrig.vyzo.data.link.DeepLink
import androidx.compose.ui.test.assertCountEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

class TvNavLogicTest {
    @Test fun `depuis le menu on entre dans l ecran en s eloignant du bord`() {
        assertEquals(FocusDirection.Right, menuExitDirection(NavPosition.LEFT))
        assertEquals(FocusDirection.Left, menuExitDirection(NavPosition.RIGHT))
        assertEquals(FocusDirection.Down, menuExitDirection(NavPosition.TOP))
        assertEquals(FocusDirection.Up, menuExitDirection(NavPosition.BOTTOM))
    }

    @Test fun `periodes de sortie`() {
        assertTrue(inPeriod("", 0))
        assertFalse(inPeriod("", 1))
        assertTrue(inPeriod("2024", 1))
        assertTrue(inPeriod("2016-05-01", 2))
        assertTrue(inPeriod("1999", 4))
        assertTrue(inPeriod("1988", 5))
        assertFalse(inPeriod("1990", 5))
    }

    @Test fun `filtres sur la note et l annee`() {
        val a = VodCard("a", "A", rating = 8.5f, year = "2021")
        val b = VodCard("b", "B", rating = 6f, year = "2021")
        val c = VodCard("c", "C", rating = 9f, year = "1980")
        val all = listOf(a, b, c)
        assertSame("Sans filtre, pas de copie d'un catalogue qui peut compter 100 000 titres", all, filterCards(all, 0f, 0))
        assertEquals(listOf(a, c), filterCards(all, 8f, 0))
        assertEquals(listOf(a), filterCards(all, 8f, 1))
    }

    @Test fun `titre en grand meme sans image de fond ni resume`() {
        val nu = VodCard("n", "Sans rien", rating = 9f)
        val affiche = VodCard("a", "Affiche", poster = "p", rating = 7f)
        val autre = VodCard("b", "Affiche moins notée", poster = "p", rating = 6f)
        val fond = VodCard("f", "Fond", backdrop = "b", rating = 5f)
        // Liste de films Xtream : ni fond ni résumé, le mieux noté qui a une affiche passe devant.
        assertEquals("a", tvHeroPick(listOf(nu, autre, affiche), null)?.uid)
        // Dès qu'un titre a une image de fond, le choix du bandeau reste prioritaire.
        assertEquals("f", tvHeroPick(listOf(nu, affiche, fond), null)?.uid)
        // Sans affiche non plus, le premier titre ; une catégorie vide garde l'en-tête simple.
        assertEquals("n", tvHeroPick(listOf(nu), null)?.uid)
        assertEquals(null, tvHeroPick(emptyList(), null))
    }

    @Test fun `legende d une affiche`() {
        assertEquals("2016 · Drame", fr.yannickuhrig.vyzo.ui.vod.vodCardMeta("2016-05-01", "Drame"))
        assertEquals("2016", fr.yannickuhrig.vyzo.ui.vod.vodCardMeta("2016", ""))
        assertEquals("", fr.yannickuhrig.vyzo.ui.vod.vodCardMeta("", ""))
    }

    @Test fun `titres d un acteur`() {
        assertEquals("Aucun titre dans ton abonnement", personCount(0, 0))
        assertEquals("1 titre dans ton abonnement", personCount(0, 1))
        assertEquals("3 titres dans ton abonnement", personCount(2, 1))
    }

    @Test fun `aide du bandeau selon le mode`() {
        assertTrue(liveHint(PlayerUi()).startsWith("OK : chaînes"))
        assertTrue(liveHint(PlayerUi(isCatchupMode = true)).contains("revenir au direct"))
    }

    @Test fun `heure de fin d une video`() {
        val now = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 20)
            set(java.util.Calendar.MINUTE, 0)
        }.timeInMillis
        assertEquals("Se termine à 20:30", endsAtLabel(600_000, 2_400_000, now))
        assertEquals("Durée inconnue : rien à annoncer", null, endsAtLabel(600_000, 0, now))
    }

    @Test fun `nombre de chaines`() {
        assertEquals("1 chaîne", channelCount(1))
        assertEquals("12 chaînes", channelCount(12))
    }

    private fun kind(title: String, channel: String = "") = SportKinds.name(SportKinds.of(title, channel))

    @Test fun `discipline d un programme`() {
        assertEquals("Football", kind("Football : Ligue des champions"))
        assertEquals("Football américain", kind("Football américain : Super Bowl"))
        assertEquals("Tennis", kind("Tennis : Roland-Garros"))
        assertEquals("Cyclisme", kind("Cyclisme : Tour de France"))
        assertEquals("Sports d'hiver", kind("Ski alpin : Coupe du monde"))
        assertEquals("Le titre l'emporte sur la chaîne", "Rugby", kind("Rugby : Top 14", "Golf Channel"))
        assertEquals("Golf", kind("Magazine", "Golf Channel"))
        assertEquals("Sports mécaniques", kind("Moto GP"))
        assertEquals("Autres", kind("Magazine", "Eurosport 1"))
    }

    private val noon = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 12)
        set(java.util.Calendar.MINUTE, 0)
    }.timeInMillis
    private val hour = 3_600_000L

    @Test fun `fenetres de temps du sport`() {
        assertTrue(SportWindow.ALL.matches(noon + 30 * hour, noon))
        assertTrue(SportWindow.SOON.matches(noon + 3 * hour, noon))
        assertFalse(SportWindow.SOON.matches(noon + 4 * hour, noon))
        assertTrue(SportWindow.TODAY.matches(noon + 11 * hour, noon))
        assertFalse(SportWindow.TODAY.matches(noon + 13 * hour, noon))
        assertTrue(SportWindow.LATER.matches(noon + 13 * hour, noon))
        assertFalse(SportWindow.LATER.matches(noon + 11 * hour, noon))
        assertFalse("Un match en cours n'est pas « plus tard »", SportWindow.LATER.showsLive)
        assertEquals(Fmt.dayIndex(noon) + 1, Fmt.dayIndex(noon + 24 * hour))
    }

    private fun channel(name: String) = ChannelEntity(
        uid = name, playlistId = 1, remoteId = name, name = name, nameNorm = name, matchKey = name, number = 0, logo = "",
        categoryUid = "", epgId = "", epgKey = name, streamUrl = "", position = 0, syncToken = 0,
    )

    private fun event(title: String, start: Long, ch: String = "beIN Sports 1") =
        SportEvent(ProgramEntity(channelKey = ch, start = start, stop = start + hour, title = title, sourceKey = "x", generation = 0), listOf(channel(ch)))

    @Test fun `filtres de l ecran sport`() {
        val foot = event("Football : Ligue 1", noon - hour / 2)
        val tennis = event("Tennis : ATP", noon + hour)
        val rugby = event("Rugby : Top 14", noon + 26 * hour)
        val state = SportState(
            live = listOf(foot), upcoming = listOf(tennis, rugby),
            channels = listOf(ChannelItem(channel("beIN Sports 1"), null, "all"), ChannelItem(channel("Golf Channel"), null, "all")),
        )

        val all = sportView(state, SportWindow.ALL, -1, noon)
        assertEquals(3, all.total)
        assertEquals(2, all.channels.size)

        val later = sportView(state, SportWindow.LATER, -1, noon)
        assertTrue(later.live.isEmpty())
        assertEquals(listOf(rugby), later.upcoming)
        assertEquals(1, later.total)

        val footIndex = SportKinds.of("Football", "")
        val onlyFoot = sportView(state, SportWindow.ALL, footIndex, noon)
        assertEquals(listOf(foot), onlyFoot.live)
        assertTrue(onlyFoot.upcoming.isEmpty())
        assertTrue("Aucune chaîne de ce nom ne parle de football", onlyFoot.channels.isEmpty())

        val gone = sportView(state, SportWindow.LATER, footIndex, noon)
        assertEquals("La discipline choisie reste affichée même sans match", footIndex to 0, gone.kinds.first { it.first == footIndex })
    }

    @Test fun `la variante TV fournit sa navigation`() {
        assertNotNull(FlavorScreens.nav)
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class TvNavScreenTest : ScreensBase() {
    /** Un téléviseur est en mode télécommande ; le simulateur démarre en mode tactile. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote(content: @Composable () -> Unit) {
        val input = LocalInputModeManager.current
        SideEffect { input.requestInputMode(InputMode.Keyboard) }
        content()
    }

    /** L'appli entière : menu, écrans et fiches, avec l'accueil comme écran de démarrage. */
    @Composable
    private fun WholeApp() = Remote {
        AppRoot(app.container, MutableStateFlow<LaunchRequest?>(null), FlavorScreens.home, FlavorScreens.details, FlavorScreens.nav)
    }

    private fun startOnHome() = runBlocking { app.container.settings.update { it.copy(startScreen = "HOME") } }

    private fun frames(n: Int = 60) = repeat(n) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** Laisse passer les attentes réelles : anti-rebond de la recherche, lectures de la base. */
    private fun settle(ms: Long = 1200) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            Thread.sleep(30)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        frames()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        frames()
    }

    /** Ce que la télécommande sélectionne : texte, ou nom de l'icône pour une entrée du menu. */
    private fun focused(): String {
        val config = compose.onNode(isFocused()).fetchSemanticsNode().config
        val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        return "$text $description".trim()
    }

    private fun focusedLeft(): Float = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.left

    /** Retour ouvre le menu sur l'écran affiché ; ▶ revient sur la carte quittée ; les neuf entrées tiennent en hauteur. */
    @Test fun menuOuvertPuisRetourSurLaCarte() {
        startOnHome()
        var card = 0f
        shoot("tv_23_menu_ouvert", FormFactor.TV, 4000, after = {
            press(Key.DirectionDown)
            settle()
            card = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.top
            back()
            assertEquals("Accueil", focused())
        }) { WholeApp() }

        val height = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val lastEntry = compose.onNodeWithText("Réglages").fetchSemanticsNode().boundsInRoot
        assertTrue("« Réglages » doit tenir à l'écran : ${lastEntry.bottom} / $height", lastEntry.bottom <= height)

        press(Key.DirectionRight)
        settle()
        assertEquals(card, compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.top, 30f)
    }

    /** OK sur « Films » entre dans le catalogue ; la fiche s'ouvre sans menu ; Retour retrouve l'affiche. */
    private fun firstMovie() = runBlocking { app.container.db.movies().recentlyAdded(listOf(playlistId), false).first().first() }

    private fun openFromLink(uid: String): MutableStateFlow<LaunchRequest?> {
        val launch = MutableStateFlow<LaunchRequest?>(LaunchRequest.Open(DeepLink(DeepLinkKind.MOVIE, uid)))
        shoot("", FormFactor.TV, 4000, capture = false) {
            Remote { AppRoot(app.container, launch, FlavorScreens.home, FlavorScreens.details, FlavorScreens.nav) }
        }
        return launch
    }

    @Test fun lienVersUnFilmVerrouilleDemandeLeCode() {
        val movie = firstMovie()
        runBlocking {
            app.container.db.categories().markAdult(listOf(movie.categoryUid))
            app.container.parental.setPin("1234")
            withTimeout(5_000) { app.container.parental.pinSet.first { it } }
        }
        val launch = openFromLink(movie.uid)
        assertEquals(null, launch.value)
        compose.onNodeWithText("Ce contenu est verrouillé").assertExists()
    }

    @Test fun lienInconnuNOuvreRien() {
        val launch = openFromLink("inconnu")
        assertEquals(null, launch.value)
        compose.onAllNodesWithText("Ce contenu est verrouillé").assertCountEquals(0)
    }

    @Test fun duMenuAuCatalogueEtRetourDeLaFiche() {
        startOnHome()
        var poster = 0f
        shoot("tv_25_films", FormFactor.TV, 4000, after = {
            back()
            repeat(4) { press(Key.DirectionDown) }
            assertEquals("Films", focused())
            press(Key.DirectionCenter)
            settle(2500)
            assertFalse("Le focus doit quitter le menu : ${focused()}", focused() == "Films")
            press(Key.DirectionRight)
            settle()
            poster = focusedLeft()
        }) { WholeApp() }
        compose.onNodeWithContentDescription("Réglages").fetchSemanticsNode()

        press(Key.DirectionCenter)
        settle(2500)
        assertEquals("Le menu ne s'affiche pas sur une fiche", 0, compose.onAllNodesWithContentDescriptionCount("Réglages"))

        back()
        settle(2500)
        assertEquals(poster, focusedLeft(), 2f)
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithContentDescriptionCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(text)).fetchSemanticsNodes().size

    /** Le clavier ne s'ouvre qu'avec OK ; les résultats arrivent pendant la saisie ; ▼ y descend, OK ouvre le film. */
    @Test fun rechercheALaTelecommande() {
        var opened: String? = null
        val actions = SearchActions({}, { opened = it }, {}, { _, _ -> })
        shoot("tv_24_recherche", FormFactor.TV, 2000, after = {
            assertTrue(focused(), focused().contains("OK pour écrire"))
            assertEquals("Pas de clavier sans OK", 0, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
            press(Key.DirectionCenter)
            settle()
            compose.onNode(hasSetTextAction()).performTextInput("Grand")
            settle(1500)
            compose.onNodeWithText("Films").fetchSemanticsNode()
        }) { Remote { TvSearch(actions) } }

        press(Key.DirectionDown)
        settle()
        press(Key.DirectionCenter)
        settle(300)
        assertTrue("$opened", opened?.endsWith(":M:501") == true)
    }

    /** Les filtres restreignent la grille ; le contrôle se fait sur les titres qui restent. */
    @Test fun filtreParNote() {
        shoot("tv_26_films_filtre", FormFactor.TV, 3000, after = {
            compose.onNodeWithText("Toutes les notes").performClick()
            settle()
            compose.onNodeWithText("8 et plus").performClick()
            settle()
        }) { Remote { TvBrowse(ContentKind.MOVIE, {}) } }
        // Le nombre de titres est dans la petite ligne au-dessus du titre en grand.
        compose.onNodeWithText("2 TITRES", substring = true).fetchSemanticsNode()
    }

    /** Avec image et résumé, Films reprend le haut de l'accueil : la carte sélectionnée passe en grand au-dessus de la grille. */
    @Test fun filmsTitreMisEnAvant() {
        runBlocking {
            val bmp = android.graphics.Bitmap.createBitmap(640, 360, android.graphics.Bitmap.Config.ARGB_8888)
            val paint = android.graphics.Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, 640f, 360f, 0xFFE0566B.toInt(), 0xFF3F8EE8.toInt(), android.graphics.Shader.TileMode.CLAMP)
            }
            android.graphics.Canvas(bmp).drawRect(0f, 0f, 640f, 360f, paint)
            val file = java.io.File.createTempFile("fond", ".png")
            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            // Simulateur : la bibliothèque native s'initialise ici, car sur le fil de décodage des images elle provoque un arrêt brutal.
            bmp.copyPixelsToBuffer(java.nio.IntBuffer.allocate(640 * 360))
            val url = "file://${file.absolutePath}"
            val movies = app.container.db.movies().recentlyAdded(listOf(playlistId), false).first()
            app.container.db.movies().upsert(movies.map {
                it.copy(backdrop = url, plot = "Un ancien détenu devient l'aide à domicile d'un riche tétraplégique : une amitié improbable, drôle et touchante.")
            })
        }
        shoot("tv_38_films_titre_en_avant", FormFactor.TV, 3000, after = {
            press(Key.DirectionRight)
            settle(2500)
        }) { Remote { TvBrowse(ContentKind.MOVIE, {}) } }
        compose.onNodeWithText("Plus d'infos").assertDoesNotExist()
        compose.onAllNodesWithText("Intouchables").assertCountEquals(2)
    }

    /** La page d'un acteur liste ses titres de l'abonnement. */
    @Test fun pageActeur() {
        var opened: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides app.container, LocalFormFactor provides FormFactor.TV) {
                VyzoTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Remote { TvPersonPage("Mathieu Kassovitz", null, {}, { opened = it }, {}) }
                    }
                }
            }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("1 titre dans ton abonnement").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Le Bureau des légendes").fetchSemanticsNode()
        // Sans image, l'affiche est dessinée avec le titre en capitales : c'est elle qui se valide, pas le titre écrit dessous.
        compose.onNodeWithText("LE BUREAU DES LÉGENDES").performClick()
        compose.waitForIdle()
        assertTrue("$opened", opened?.endsWith(":S:701") == true)
        compose.onAllNodes(isRoot()).onLast().captureRoboImage("screenshots/tv_27_acteur.png")
    }

    /** Arrivée sur une chaîne ; OK lance l'aperçu, OK encore ouvre le plein écran ; les groupes se parcourent et ► revient aux chaînes. */
    @Test fun directALaTelecommande() {
        var opened: String? = null
        shoot("tv_28_direct", FormFactor.TV, 4000, after = {
            settle()
            val first = focused()
            assertTrue("Le direct doit s'ouvrir sur une chaîne : $first", first.contains("TF1 HD"))
            press(Key.DirectionDown)
            settle(600)
            val second = focused()
            assertTrue("▼ doit descendre d'une chaîne : $second", second.contains("France 2 HD"))

            press(Key.DirectionCenter)
            settle(1500)
            assertEquals("Le premier OK ne fait que l'aperçu", null, opened)
            press(Key.DirectionCenter)
            settle(800)
            assertEquals("France 2 HD", opened)

            press(Key.DirectionLeft)
            settle(600)
            val group = focused()
            press(Key.DirectionDown)
            settle(1000)
            assertFalse("▼ doit changer de groupe : $group", focused() == group)
            press(Key.DirectionUp)
            settle(1000)
            assertEquals(group, focused())
            press(Key.DirectionRight)
            settle(1000)
            assertTrue("► doit revenir sur les chaînes : ${focused()}", focused().contains("France 2 HD"))
            assertTrue("Le panneau doit suivre la chaîne retrouvée", compose.onAllNodesWithText("France 2 HD").fetchSemanticsNodes().size >= 2)
        }) { Remote { TvLive { ch, _ -> opened = ch.name } } }
    }

    /** Le premier match reçoit la sélection, ▲ monte aux filtres, un filtre vide propose de tout remettre. */
    @Test fun sportALaTelecommande() {
        var watched: String? = null
        shoot("tv_29_sport", FormFactor.TV, 4000, after = {
            settle()
            val first = focused()
            assertTrue("Le sport doit s'ouvrir sur un match : $first", first.contains("Eurosport 1"))
            press(Key.DirectionCenter)
            settle(800)
            assertEquals("Eurosport 1", watched)

            press(Key.DirectionUp)
            settle(600)
            assertEquals("Tout", focused())
            press(Key.DirectionRight)
            settle(600)
            assertEquals("Prochaines 3 h", focused())
            press(Key.DirectionCenter)
            settle(600)
            compose.onNodeWithText("Chaînes de sport").fetchSemanticsNode()
            press(Key.DirectionLeft)
            press(Key.DirectionCenter)
            settle(600)
        }) { Remote { TvSport({ watched = it.name }, { _, _ -> }) } }
    }

    /** Dans l'appli entière, le direct et le sport passent bien par les écrans TV. */
    @Test fun routesDuDirectEtDuSport() {
        var seen = 0
        shoot("tv_30_direct_appli", FormFactor.TV, 4500, after = {
            settle()
            seen = compose.onAllNodesWithText("EN DIRECT").fetchSemanticsNodes().size
            assertTrue("Le panneau TV doit être affiché", seen > 0)
            back()
            settle(600)
            repeat(10) { if (focused() != "Sport") press(Key.DirectionDown) }
            assertEquals("Sport", focused())
            press(Key.DirectionCenter)
            settle(3000)
            compose.onNodeWithText("Chaînes de sport").fetchSemanticsNode()
        }) { WholeApp() }
    }

    /** Le lecteur tel que la variante TV l'habille. */
    @Composable
    private fun TvPlayerOn(kind: String, uid: String) = Remote {
        CompositionLocalProvider(LocalVariantNav provides FlavorScreens.nav) {
            PlayerScreen(kind, uid, Repository.KEY_ALL, 0, -1, onExit = {}, onCatchup = { _, _ -> })
        }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun bandeauDuDirect() {
        shoot("tv_31_lecteur_direct", FormFactor.TV, 1500) { TvPlayerOn(PlayKind.CHANNEL, "$playlistId:L:101") }
        assertTrue("L'aide du bandeau TV doit être affichée", shown("▲▼ : zapper"))
    }

    /** ◀ ouvre la liste sur la chaîne en cours, ▶ la ferme, OK change de chaîne. */
    @Test fun listeDesChainesDansLeLecteur() {
        shoot("tv_32_lecteur_chaines", FormFactor.TV, 1500, after = {
            press(Key.DirectionLeft)
            settle(800)
            assertTrue(shown("OK : regarder"))
            assertTrue("La liste s'ouvre sur la chaîne en cours : ${focused()}", focused().contains("TF1 HD"))
            press(Key.DirectionRight)
            settle(800)
            assertFalse("▶ doit fermer la liste", shown("OK : regarder"))

            press(Key.DirectionLeft)
            settle(800)
            press(Key.DirectionDown)
            settle(400)
            assertTrue("▼ doit descendre : ${focused()}", focused().contains("France 2 HD"))
            press(Key.DirectionCenter)
            settle(1200)
            assertFalse("Choisir une chaîne doit fermer la liste", shown("OK : regarder"))
            press(Key.DirectionLeft)
            settle(800)
            assertTrue("La liste suit la nouvelle chaîne : ${focused()}", focused().contains("France 2 HD"))
        }) { TvPlayerOn(PlayKind.CHANNEL, "$playlistId:L:101") }
    }

    /** ▶ ouvre le mini-guide (après le bandeau), ◀ ▶ y parcourent les autres chaînes sans zapper. */
    @Test fun miniGuideDansLeLecteur() {
        shoot("tv_33_lecteur_guide", FormFactor.TV, 1500, after = {
            press(Key.DirectionRight)
            if (!shown("autres chaînes")) press(Key.DirectionRight)
            settle(800)
            assertTrue(shown("◀ ▶ : autres chaînes"))
            press(Key.DirectionRight)
            settle(800)
            assertTrue("▶ doit passer à la chaîne suivante", shown("France 2 HD"))
        }) { TvPlayerOn(PlayKind.CHANNEL, "$playlistId:L:101") }
    }

    /** Menu ouvre le panneau d'options sur sa première ligne ; ◀ le referme et rend les touches au lecteur. */
    @Test fun optionsDuLecteur() {
        shoot("tv_34_lecteur_options_fond", FormFactor.TV, 1500, after = {
            press(Key.Menu)
            settle(800)
            assertTrue(shown("Options de lecture"))
            compose.onNodeWithText("Liste des chaînes").performClick()
            settle(800)
            assertFalse("Une action doit fermer le panneau", shown("Options de lecture"))
            assertTrue("L'action doit s'exécuter", shown("OK : regarder"))
        }, capture = false) { TvPlayerOn(PlayKind.CHANNEL, "$playlistId:L:101") }
    }

    @Test fun choixDePiste() {
        var picked = -1
        var closed by androidx.compose.runtime.mutableStateOf(false)
        shoot("tv_35_lecteur_piste_fond", FormFactor.TV, 1200, after = {
            compose.onNodeWithText("Français").performClick()
            frames()
            assertEquals(0, picked)
            assertTrue(closed)
        }, capture = false) {
            Remote {
                if (!closed) TvPlayerChoice(PlayerChoiceArgs("Piste audio", listOf("Français", "Anglais"), 1, { picked = it }, { closed = true }))
            }
        }
    }

    @Test fun panneauDeChoixAffiche() {
        shoot("tv_35_lecteur_piste_base", FormFactor.TV, 1200, capture = false) {
            Remote { TvPlayerChoice(PlayerChoiceArgs("Piste audio", listOf("Français", "Anglais", "Audiodescription"), 1, {}, {})) }
        }
        compose.onAllNodes(isRoot()).onLast().captureRoboImage("screenshots/tv_36_lecteur_piste.png")
    }

    private fun miniatures() = compose.onAllNodesWithContentDescription("Miniature de l'épisode").fetchSemanticsNodes().size

    @Test fun laBarreMontreLaMiniatureDeLEpisode() {
        shoot("tv_vod_miniature", FormFactor.TV, 800, capture = false) {
            Remote {
                TvVodBar(VodBarArgs(PlayerUi(title = "Mr. Robot", subtitle = "S3 É1", artwork = "https://exemple.test/s3e1.jpg", isLive = false), 60_000, 600_000, true))
            }
        }
        assertEquals(1, miniatures())
    }

    @Test fun sansImageLaBarreNAffichePasDeMiniature() {
        shoot("tv_vod_sans_miniature", FormFactor.TV, 800, capture = false) {
            Remote { TvVodBar(VodBarArgs(PlayerUi(title = "Le Mépris", subtitle = "1963", isLive = false), 60_000, 600_000, true)) }
        }
        assertEquals(0, miniatures())
    }

    @Test fun barreDeLecture() {
        shoot("tv_37_lecteur_film", FormFactor.TV, 1200) {
            Remote {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), androidx.compose.ui.Alignment.BottomCenter) {
                    TvVodBar(VodBarArgs(PlayerUi(title = "Le Mépris", subtitle = "1963", isLive = false), 1_800_000, 6_000_000, true))
                }
            }
        }
        assertTrue(shown("30:00"))
        assertTrue(shown("1:40:00"))
        assertTrue(shown("Se termine à"))
    }
}
