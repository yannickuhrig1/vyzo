package fr.yannickuhrig.vyzo.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import fr.yannickuhrig.vyzo.closeForTest
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.download.DownloadManager
import fr.yannickuhrig.vyzo.ui.common.FormFactor
import fr.yannickuhrig.vyzo.ui.common.LocalContainer
import fr.yannickuhrig.vyzo.ui.common.LocalDesktop
import fr.yannickuhrig.vyzo.ui.common.LocalFormFactor
import fr.yannickuhrig.vyzo.ui.common.PlayKind
import fr.yannickuhrig.vyzo.ui.player.PlayerScreen
import fr.yannickuhrig.vyzo.ui.player.PlayerViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Zapping et multi-écran avec de vraies lectures libVLC, sur des chaînes locales (vidéos générées par ffmpeg). */
@OptIn(ExperimentalTestApi::class)
class DesktopConnexionsTest {
    @get:Rule
    val rule = sqliteThenComposeRule()
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
    private val resumed = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
    private lateinit var dir: File
    private lateinit var c: AppContainer
    private var vm: PlayerViewModel? = null

    @Before
    fun setUp() {
        Vlc.init()
        assumeTrue("libVLC absent", Vlc.available)
        assumeTrue("ffmpeg absent", runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false))
        dir = Files.createTempDirectory("vyzo-connexions").toFile()
        c = testContainer(dir)
    }

    @After
    fun tearDown() {
        if (!::c.isInitialized) return
        vm?.let {
            it.exitMultiView()
            it.viewModelScope.cancel()
        }
        owner.viewModelStore.clear()
        c.player.release()
        c.closeForTest()
        dir.deleteRecursively()
    }

    private fun clip(name: String): File = File(dir, name).also {
        val p = ProcessBuilder(
            "ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=160x120:rate=25:duration=20",
            "-f", "lavfi", "-i", "sine=duration=20", "-shortest", it.absolutePath,
        ).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        assertEquals(0, p.waitFor())
    }

    private fun waitUntil(timeoutMs: Long = 20_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(100)
        }
        return cond()
    }

    /** TF1, un intertitre du fournisseur, France 2, France 3, sur un abonnement à [maxConnections] connexions. */
    private suspend fun channels(maxConnections: Int): List<ChannelEntity> {
        val pid = c.db.playlists().insert(PlaylistEntity(name = "Locale", type = SourceType.M3U, url = "file:///dev/null", maxConnections = maxConnections))
        val list = listOf("TF1", "░▒▓█ FRANCE FHD █▓▒░", "France 2", "France 3").mapIndexed { i, name ->
            val separator = i == 1
            ChannelEntity(
                uid = "$pid:c$i", playlistId = pid, remoteId = "c$i", name = name, nameNorm = name.lowercase(), matchKey = "",
                number = 749 + i, logo = "", categoryUid = "", epgId = "", epgKey = "",
                streamUrl = if (separator) "" else DownloadManager.playUri(clip("c$i.mkv")), position = i, syncToken = 1,
            )
        }
        c.db.channels().upsert(list)
        return list
    }

    private fun open(ch: ChannelEntity): PlayerViewModel {
        val v = PlayerViewModel(c, PlayKind.CHANNEL, ch.uid, "", 0, 0)
        vm = v
        assertTrue("la chaîne doit démarrer", waitUntil { c.player.tag.value == "ch:${ch.uid}" })
        assertTrue(waitUntil { v.channels.value.size == 4 })
        return v
    }

    @Test
    fun leZappingSauteLesIntertitres() = runBlocking {
        val list = channels(maxConnections = 3)
        val v = open(list[0])

        assertEquals(list[2].uid, v.nextChannel()?.uid)
        assertTrue(waitUntil { c.player.tag.value == "ch:${list[2].uid}" && v.ui.value.channel?.uid == list[2].uid })
        assertEquals(list[0].uid, v.previousChannel()?.uid)
        assertTrue(waitUntil { v.ui.value.channel?.uid == list[0].uid })

        // Choisi dans la liste, l'intertitre ne coupe pas la chaîne en cours.
        v.playChannel(list[1])
        assertTrue(v.message.value.orEmpty().contains("intertitre"))
        assertEquals(list[0].uid, v.ui.value.channel?.uid)
    }

    @Test
    fun multiEcranRefuseAuDelaDeLaLimiteDeConnexions() = runBlocking {
        val list = channels(maxConnections = 1)
        val v = open(list[0])

        v.enterMultiView()
        assertTrue(waitUntil { v.message.value != null })
        assertTrue(v.message.value!!, v.message.value!!.contains("en autorise 1"))
        assertFalse(v.isMultiViewActive.value)
    }

    @Test
    fun multiEcranDeuxiemeEcranSurUneVraieChaine() = runBlocking {
        val list = channels(maxConnections = 2)
        val v = open(list[0])

        v.enterMultiView()
        assertTrue(waitUntil { v.isMultiViewActive.value })
        val slots = v.multiViewSlots.value
        assertEquals(2, slots.size)
        assertNotEquals("pas l'intertitre voisin", list[1].uid, slots[1].channel.uid)
        assertNotEquals(list[0].uid, slots[1].channel.uid)
        assertTrue("le 2e écran doit être lu", waitUntil { slots[1].player.tag.value == "ch:${slots[1].channel.uid}" })

        // Les 2 connexions sont prises : pas de 3e écran.
        v.message.value = null
        v.addMultiViewChannel(list[3])
        assertTrue(waitUntil { v.message.value != null })
        assertTrue(v.message.value!!, v.message.value!!.contains("2 écrans"))
        assertEquals(2, v.multiViewSlots.value.size)
    }

    @Test
    fun unPrereglageOuvreSesChainesEtIgnoreLesIntertitres() = runBlocking {
        val list = channels(maxConnections = 3)
        val v = open(list[0])

        v.openMultiViewChannels(listOf(list[2].uid, list[1].uid, list[3].uid))

        assertTrue(waitUntil { v.isMultiViewActive.value && v.multiViewSlots.value.size == 2 })
        assertEquals(listOf(list[2].uid, list[3].uid), v.multiViewSlots.value.map { it.channel.uid })
        assertEquals("la 1re chaîne remplace celle en cours", list[2].uid, v.ui.value.channel?.uid)
    }

    @Test
    fun unPrereglageEnregistreAppartientAuProfilActif() = runBlocking {
        val list = channels(maxConnections = 2)
        val v = open(list[0])
        v.enterMultiView()
        assertTrue(waitUntil { v.isMultiViewActive.value })

        v.saveMultiViewPreset("Soirée")

        assertTrue(waitUntil { c.settings.state.value.multiViewPresets.isNotEmpty() })
        val preset = c.settings.state.value.multiViewPresets.single()
        assertEquals("Soirée", preset.name)
        assertEquals(c.settings.state.value.activeProfileId, preset.profileId)
        assertEquals(v.multiViewSlots.value.map { it.channel.uid }, preset.channelUids)

        v.deleteMultiViewPreset(preset)
        assertTrue(waitUntil { c.settings.state.value.multiViewPresets.isEmpty() })
    }

    /** Capture 148 du rapport : en multi-écran, le bandeau d'infos du 1er écran recouvrait toute la grille. */
    @Test
    fun multiEcranSansLeBandeauDuPremierEcran() = runBlocking {
        val list = channels(maxConnections = 2)
        rule.setContent {
            CompositionLocalProvider(
                LocalContainer provides c, LocalDesktop provides true, LocalFormFactor provides FormFactor.TV, LocalViewModelStoreOwner provides owner,
                LocalLifecycleOwner provides resumed,
            ) {
                MaterialTheme {
                    Box(Modifier.size(1280.dp, 720.dp)) {
                        PlayerScreen(PlayKind.CHANNEL, list[0].uid, "", 0, 0, onExit = {}, onCatchup = { _, _ -> })
                    }
                }
            }
        }
        fun shown(text: String) = rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        rule.waitUntil(20_000) { c.player.tag.value == "ch:${list[0].uid}" && shown("Entrée : chaînes") }

        rule.onNodeWithContentDescription("Multi-écran").performClick()
        rule.waitUntil(20_000) { shown("MUET") }
        // La souris fait réapparaître les commandes : la barre du haut oui, le bandeau du 1er écran non.
        rule.onRoot().performMouseInput { moveTo(center); moveTo(topLeft) }
        rule.waitForIdle()
        assertFalse("bandeau du 1er écran sur la grille", shown("Entrée : chaînes"))
        assertTrue(shown("Multi-écran"))
        assertFalse("titre du 1er écran en doublon dans la barre", rule.onAllNodes(hasText("TF1")).fetchSemanticsNodes().size > 1)
    }

    /** Replay de l'émission en cours : pastille dans le bandeau, barre et temps restant de ce qui est à l'écran. */
    @Test
    fun replayDepuisLeDebutSuitLaLecture() = runBlocking {
        val pid = c.db.playlists().insert(PlaylistEntity(name = "Locale", type = SourceType.M3U, url = "file:///dev/null", maxConnections = 2))
        val replay = DownloadManager.playUri(clip("replay.mkv"))
        val ch = ChannelEntity(
            uid = "$pid:c0", playlistId = pid, remoteId = "c0", name = "TF1", nameNorm = "tf1", matchKey = "", number = 1, logo = "",
            categoryUid = "", epgId = "", epgKey = "tf1", streamUrl = DownloadManager.playUri(clip("live.mkv")),
            catchupType = "custom", catchupDays = 7, catchupSource = replay, position = 0, syncToken = 1,
        )
        c.db.channels().upsert(listOf(ch))
        val now = System.currentTimeMillis()
        // Commencée il y a 20 min, finit dans 40 min : en direct, « encore 39 min » ; en replay, ~60 min restantes.
        c.db.programs().insertAll(listOf(ProgramEntity(channelKey = "tf1", start = now - 20 * 60_000, stop = now + 40 * 60_000, title = "Grands reportages", sourceKey = "t", generation = 1)))
        rule.setContent {
            CompositionLocalProvider(
                LocalContainer provides c, LocalDesktop provides true, LocalFormFactor provides FormFactor.TV, LocalViewModelStoreOwner provides owner,
                LocalLifecycleOwner provides resumed,
            ) {
                MaterialTheme {
                    Box(Modifier.size(1280.dp, 720.dp)) {
                        PlayerScreen(PlayKind.CHANNEL, ch.uid, "", 0, 0, onExit = {}, onCatchup = { _, _ -> })
                    }
                }
            }
        }
        fun shown(text: String) = rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        rule.waitUntil(20_000) { shown("Grands reportages") && shown("Revoir depuis le début") }
        assertTrue(shown("encore 39 min"))

        rule.onNodeWithText("Revoir depuis le début").performClick()
        rule.waitUntil(20_000) { c.player.tag.value?.startsWith("catchup:") == true && shown("REPLAY") }
        rule.onRoot().performMouseInput { moveTo(center); moveTo(topLeft) }
        rule.waitUntil(5_000) { shown("encore 59 min") || shown("encore 60 min") }
        assertFalse("le temps restant ne suit plus le direct", shown("encore 39 min"))
        assertFalse("pastille retirée une fois le replay lancé", shown("Revoir depuis le début"))
    }
}
