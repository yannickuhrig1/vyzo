package fr.yannickuhrig.vyzo.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import fr.yannickuhrig.vyzo.closeForTest
import fr.yannickuhrig.vyzo.data.db.CategoryEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.MovieEntity
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.home.HomeRows
import fr.yannickuhrig.vyzo.ui.common.FormFactor
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.LocalDesktop
import fr.yannickuhrig.vyzo.ui.common.LocalFormFactor
import fr.yannickuhrig.vyzo.ui.home.HomeScreen
import fr.yannickuhrig.vyzo.ui.theme.VyzoTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/** Accueil (tableau de bord) : rangées choisies, genre automatique, titre mis en avant, ordre des rangées. */
@OptIn(ExperimentalTestApi::class)
class HomeScreenTest {
    @get:Rule
    val rule = sqliteThenComposeRule()

    private val dir = Files.createTempDirectory("vyzo-home").toFile()
    private val c = testContainer(dir)
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    @After
    fun tearDown() {
        owner.viewModelStore.clear()
        c.closeForTest()
        dir.deleteRecursively()
    }

    /** Image locale unie (affiche ou arrière-plan), pour que l'accueil ait quelque chose à montrer sans réseau. */
    private fun image(name: String, w: Int, h: Int, seed: Int): String {
        val colors = listOf(java.awt.Color(0x8E2DE2), java.awt.Color(0xF2994A), java.awt.Color(0x11998E), java.awt.Color(0xEB5757))
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.paint = java.awt.GradientPaint(0f, 0f, colors[seed % colors.size], w.toFloat(), h.toFloat(), java.awt.Color(0x0A0E1A))
        g.fillRect(0, 0, w, h)
        g.dispose()
        val f = File(dir, "$name.png")
        ImageIO.write(img, "png", f)
        return f.toURI().toString()
    }

    private fun exists(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun rangeesGenreEtTitreMisEnAvant() {
        runBlocking {
            val pid = c.db.playlists().insert(PlaylistEntity(name = "Locale", type = SourceType.M3U, url = "file:///dev/null"))
            val action = "$pid:M:action"
            val comedy = "$pid:M:comedie"
            c.db.categories().upsert(
                listOf(
                    CategoryEntity(action, pid, ContentKind.MOVIE, "1", "FR | ACTION 4K", 0, 1),
                    CategoryEntity(comedy, pid, ContentKind.MOVIE, "2", "VOD - Comédie", 1, 1),
                ),
            )
            fun m(id: String, name: String, cat: String, rating: Float, plot: String = "") = MovieEntity(
                uid = "$pid:M:$id", playlistId = pid, remoteId = id, name = name, nameNorm = name.lowercase(), categoryUid = cat,
                poster = image("poster$id", 300, 450, id.toInt()), backdrop = image("backdrop$id", 1280, 720, id.toInt()),
                rating = rating, year = "2024", plot = plot, addedAt = rating.toLong(), position = 0, syncToken = 1,
            )
            c.db.movies().upsert(
                listOf(
                    m("1", "Course poursuite", action, 7.9f, "Un chauffeur pris dans une course folle à travers la ville."),
                    m("2", "Le dîner de famille", comedy, 6.4f),
                    m("3", "Explosion finale", action, 8.6f, "Le dernier assaut."),
                ),
            )
            c.settings.update { it.copy(homeRows = listOf(HomeRows.TOP_MOVIES, HomeRows.GENRE_MOVIES + "action", HomeRows.CONTINUE_MOVIES), homeClock = true) }
        }
        show()
        rule.waitUntil(10_000) { exists("Films les mieux notés") && exists("Films · Action") }
        afterShow()
    }

    private fun show() {
        rule.setContent {
            CompositionLocalProvider(
                LocalContainer provides c, LocalDesktop provides true, LocalFormFactor provides FormFactor.TV,
                LocalViewModelStoreOwner provides owner,
            ) {
                VyzoTheme {
                    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                        Box(Modifier.size(1280.dp, 720.dp)) { HomeScreen({}, {}, { _, _ -> }, {}) }
                    }
                }
            }
        }
    }

    private fun afterShow() {
        // Rien à reprendre : la rangée vide n'est pas affichée.
        assertFalse(exists("Reprendre — films"))
        // Mis en avant avant toute sélection : le premier titre des rangées (le mieux noté).
        rule.waitUntil(5_000) { exists("Le dernier assaut.") }
        assertTrue(exists("Explosion finale"))

        // Capture pour la revue visuelle (build/home-screenshot.png).
        val img = rule.onRoot().captureToImage().toAwtImage()
        ImageIO.write(img, "png", File("build/home-screenshot.png"))
    }

    @Test
    fun alAntenneMaintenant() {
        val now = System.currentTimeMillis()
        val h = 3_600_000L
        runBlocking {
            val pid = c.db.playlists().insert(PlaylistEntity(name = "Locale", type = SourceType.M3U, url = "file:///dev/null"))
            val channels = listOf("TF1" to "tf1", "France 2" to "f2", "Arte" to "arte").mapIndexed { i, (name, key) ->
                fr.yannickuhrig.vyzo.data.db.ChannelEntity(
                    uid = "$pid:$key", playlistId = pid, remoteId = key, name = name, nameNorm = name.lowercase(), matchKey = key,
                    number = i + 1, logo = "", categoryUid = "", epgId = key, epgKey = key, streamUrl = "file:///dev/null",
                    position = i, syncToken = 1,
                )
            }
            c.db.channels().upsert(channels)
            fun p(key: String, start: Long, stop: Long, title: String, icon: String = "") = fr.yannickuhrig.vyzo.data.db.ProgramEntity(
                channelKey = key, start = start, stop = stop, title = title, icon = icon, sourceKey = "t", generation = 1,
            )
            c.db.programs().insertAll(
                listOf(
                    p("tf1", now - h / 2, now + h / 2, "Le journal de 20h", image("prog1", 640, 360, 0)),
                    p("tf1", now + h / 2, now + 2 * h, "Film du soir"),
                    p("f2", now - h / 4, now + h, "Grand documentaire", image("prog2", 640, 360, 2)),
                    // Arte : rien à l'antenne, la chaîne n'apparaît pas dans le widget.
                    p("arte", now + h, now + 2 * h, "Plus tard"),
                ),
            )
            channels.forEach { ch ->
                c.repo.setInGroup(fr.yannickuhrig.vyzo.data.db.FAVORITES_GROUP_ID, ch.uid, ContentKind.LIVE, true)
            }
            c.settings.update { it.copy(homeRows = listOf(HomeRows.ON_AIR, HomeRows.FAV_CHANNELS), homeRotateBackdrop = false) }
        }
        show()
        rule.waitUntil(10_000) { exists("À l'antenne maintenant") && exists("Le journal de 20h") }
        assertTrue(exists("Grand documentaire"))
        assertTrue("programme suivant", rule.onAllNodes(androidx.compose.ui.test.hasText("Film du soir", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertFalse(exists("Plus tard"))
        val img = rule.onRoot().captureToImage().toAwtImage()
        ImageIO.write(img, "png", File("build/home-on-air.png"))
    }

    @Test
    fun accueilParProfil() {
        val base = fr.yannickuhrig.vyzo.data.prefs.AppSettings(homeRows = listOf("a", "b"))
        // Sans accueil propre : la mise en page commune.
        assertEquals(listOf("a", "b"), base.homeRowsFor("p1"))
        val s1 = base.withHomeRows("p1") { it + "c" }
        assertEquals(listOf("a", "b", "c"), s1.homeRowsFor("p1"))
        assertEquals(listOf("a", "b"), s1.homeRowsFor("p2"))
        assertTrue(s1.hasOwnHome("p1"))
        assertFalse(s1.hasOwnHome("p2"))
        // Sans profil connu : on change la mise en page commune.
        assertEquals(listOf("a"), base.withHomeRows("") { listOf("a") }.homeRows)
        // Partager l'accueil de p1 : il devient celui de tous.
        val shared = s1.withHomeRows("p2") { listOf("z") }.shareHomeOf("p1")
        assertEquals(listOf("a", "b", "c"), shared.homeRowsFor("p2"))
        assertTrue(shared.homeRowsByProfile.isEmpty())
    }

    @Test
    fun ordreDesRangees() {
        val rows = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), HomeRows.move(rows, "b", -1))
        assertEquals(listOf("a", "c", "b"), HomeRows.move(rows, "b", 1))
        assertEquals(rows, HomeRows.move(rows, "a", -1))
        assertEquals(listOf("a", "c"), HomeRows.toggle(rows, "b", false))
        assertEquals(listOf("a", "b", "c", "d"), HomeRows.toggle(rows, "d", true))
        assertEquals(rows, HomeRows.toggle(rows, "a", true))
        assertEquals("Séries · Comédie", HomeRows.title(HomeRows.GENRE_SERIES + "comedie"))
    }

    @Test
    fun meteoOpenMeteo() {
        val w = c.weather
        val places = w.parsePlaces(
            """{"results":[{"name":"Lyon","latitude":45.75,"longitude":4.85,"country":"France","admin1":"Auvergne-Rhône-Alpes"}]}""",
        )
        assertEquals(1, places.size)
        assertEquals("Lyon", places[0].name)
        assertEquals("Auvergne-Rhône-Alpes, France", places[0].country)
        val f = w.parseForecast(
            """{"current":{"temperature_2m":12.4,"weather_code":3},
               "daily":{"time":["2026-11-06","2026-11-07"],"weather_code":[3,61],
               "temperature_2m_max":[14.1,11.0],"temperature_2m_min":[6.2,5.5]}}""",
            now = 1L,
        )
        assertEquals(12.4f, f.temperature, 0.01f)
        assertEquals(3, f.code)
        assertEquals(2, f.days.size)
        assertEquals(61, f.days[1].code)
        assertEquals("Pluie", fr.yannickuhrig.vyzo.data.weather.WeatherService.label(61))
        assertTrue(w.parsePlaces("""{"generationtime_ms":0.1}""").isEmpty())
    }
}
