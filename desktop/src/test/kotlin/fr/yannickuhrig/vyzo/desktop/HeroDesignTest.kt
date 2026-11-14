package fr.yannickuhrig.vyzo.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import fr.yannickuhrig.vyzo.closeForTest
import fr.yannickuhrig.vyzo.data.db.CategoryEntity
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.MovieEntity
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.ui.common.FormFactor
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.LocalDesktop
import fr.yannickuhrig.vyzo.ui.common.LocalFormFactor
import fr.yannickuhrig.vyzo.ui.home.HeroHeader
import fr.yannickuhrig.vyzo.ui.live.LiveScreen
import fr.yannickuhrig.vyzo.ui.theme.VyzoTheme
import fr.yannickuhrig.vyzo.ui.vod.VodBrowser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalTestApi::class)
class HeroDesignTest {
    @get:Rule
    val rule = sqliteThenComposeRule()

    private val dir = Files.createTempDirectory("vyzo-hero-ui").toFile()
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

    @Test
    fun heroHeaderShowsVodMetadata() {
        rule.setContent {
            VyzoTheme {
                Box(Modifier.size(960.dp, 540.dp)) {
                    HeroHeader(
                        kicker = "Action · 2 titres",
                        title = "Dune : Deuxième Partie",
                        tv = true,
                        rating = 8.6f,
                        uhd = true,
                        meta = listOf("2024", "Science-Fiction"),
                        plot = "Paul Atréides s'unit aux Fremen.",
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("ACTION · 2 TITRES").assertExists()
        rule.onNodeWithText("Dune : Deuxième Partie").assertExists()
        rule.onNodeWithText("4K").assertExists()
        rule.onNodeWithText("2024 · Science-Fiction").assertExists()
    }

    @Test
    fun heroHeaderShowsLiveMetadata() {
        rule.setContent {
            VyzoTheme {
                Box(Modifier.size(412.dp, 700.dp)) {
                    HeroHeader(
                        kicker = "Généraliste · 10 chaînes",
                        title = "Journal de 20h",
                        tv = false,
                        meta = listOf("20:00 – 20:45", "encore 25 min"),
                        plot = "Les titres du soir.",
                        live = true,
                        liveChannel = "France 2 HD",
                        replay = true,
                        progress = 0.45f,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("EN DIRECT").assertExists()
        rule.onNodeWithText("France 2 HD").assertExists()
        rule.onNodeWithText("Journal de 20h").assertExists()
        rule.onNodeWithText("Replay").assertExists()
    }

    @Test
    fun vodBrowserRendersUnifiedHeroOnDesktop() {
        runBlocking<Unit> {
            val pid = c.db.playlists().insert(PlaylistEntity(name = "Locale", type = SourceType.M3U, url = "file:///dev/null"))
            c.db.categories().upsert(listOf(CategoryEntity("$pid:M:act", pid, ContentKind.MOVIE, "1", "Action", 0, 1)))
            c.db.movies().upsert(
                listOf(
                    MovieEntity(
                        uid = "$pid:M:1",
                        playlistId = pid,
                        remoteId = "1",
                        name = "Le Grand Bleu",
                        nameNorm = "le grand bleu",
                        categoryUid = "$pid:M:act",
                        poster = "",
                        rating = 8.2f,
                        year = "1988",
                        genre = "Aventure",
                        plot = "Deux apnéistes rivaux.",
                        position = 0,
                        syncToken = 1,
                    ),
                ),
            )
        }

        rule.setContent {
            CompositionLocalProvider(
                LocalContainer provides c,
                LocalDesktop provides true,
                LocalFormFactor provides FormFactor.TV,
                LocalViewModelStoreOwner provides owner,
            ) {
                VyzoTheme {
                    Box(Modifier.size(1280.dp, 720.dp)) { VodBrowser(ContentKind.MOVIE, onOpen = {}) }
                }
            }
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText("Le Grand Bleu")).fetchSemanticsNodes().size >= 2
        }
        assertTrue(rule.onAllNodes(hasText("AFFICHE", substring = true, ignoreCase = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun liveScreenRendersUnifiedHeroOnPhone() {
        val now = System.currentTimeMillis()
        runBlocking<Unit> {
            val pid = c.db.playlists().insert(PlaylistEntity(name = "Locale", type = SourceType.M3U, url = "file:///dev/null"))
            c.db.categories().upsert(listOf(CategoryEntity("$pid:L:gen", pid, ContentKind.LIVE, "1", "Généraliste", 0, 1)))
            c.db.channels().upsert(
                listOf(
                    ChannelEntity(
                        uid = "$pid:L:1",
                        playlistId = pid,
                        remoteId = "1",
                        name = "Arte HD",
                        nameNorm = "arte hd",
                        matchKey = "arte",
                        number = 7,
                        logo = "",
                        categoryUid = "$pid:L:gen",
                        epgId = "arte.fr",
                        epgKey = "arte.fr",
                        streamUrl = "http://localhost/arte.ts",
                        catchupDays = 7,
                        position = 0,
                        syncToken = 1,
                    ),
                ),
            )
            c.db.programs().insertAll(
                listOf(
                    ProgramEntity(
                        channelKey = "arte.fr",
                        start = now - 600_000L,
                        stop = now + 1_800_000L,
                        title = "Karambolage",
                        description = "Culture franco-allemande.",
                        sourceKey = "test",
                        generation = 1,
                    ),
                ),
            )
        }

        rule.setContent {
            CompositionLocalProvider(
                LocalContainer provides c,
                LocalDesktop provides false,
                LocalFormFactor provides FormFactor.PHONE,
                LocalViewModelStoreOwner provides owner,
            ) {
                VyzoTheme {
                    Box(Modifier.size(412.dp, 800.dp)) { LiveScreen(onOpenPlayer = { _, _ -> }) }
                }
            }
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText("Karambolage")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("EN DIRECT").assertExists()
    }
}
