package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.prefs.MultiViewPreset
import fr.yannickuhrig.vyzo.data.prefs.MultiViewPresets
import fr.yannickuhrig.vyzo.ui.player.RemoteAction
import fr.yannickuhrig.vyzo.ui.player.RemoteButton
import fr.yannickuhrig.vyzo.ui.player.RemoteKeymap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKeymapTest {
    @Test
    fun defaultKeysMatchTheHistoricBehaviour() {
        assertEquals(RemoteAction.ZAP_UP, RemoteKeymap.action(emptyMap(), RemoteButton.UP))
        assertEquals(RemoteAction.CHANNEL_LIST, RemoteKeymap.action(emptyMap(), RemoteButton.LEFT))
        assertEquals(RemoteAction.BANNER_THEN_GUIDE, RemoteKeymap.action(emptyMap(), RemoteButton.RIGHT))
        assertEquals(RemoteAction.NONE, RemoteKeymap.action(emptyMap(), RemoteButton.RED))
        assertTrue(RemoteKeymap.isDefault(emptyMap()))
    }

    @Test
    fun tivimateStylePresetChangesOnlyWhatItMustChange() {
        val preset = RemoteKeymap.TIVIMATE_STYLE
        assertEquals(RemoteAction.GUIDE, RemoteKeymap.action(preset, RemoteButton.LEFT_HELD))
        assertEquals(RemoteAction.CHANNEL_LIST, RemoteKeymap.action(preset, RemoteButton.LEFT))
        assertEquals(RemoteAction.BANNER, RemoteKeymap.action(preset, RemoteButton.RIGHT))
        assertEquals(RemoteAction.ZAP_DOWN, RemoteKeymap.action(preset, RemoteButton.DOWN))
        assertFalse(RemoteKeymap.isDefault(preset))
    }

    @Test
    fun onlyDifferencesAreSaved() {
        val changed = RemoteKeymap.assign(emptyMap(), RemoteButton.RED, RemoteAction.GUIDE)
        assertEquals(mapOf("RED" to "GUIDE"), changed)
        assertEquals(emptyMap<String, String>(), RemoteKeymap.assign(changed, RemoteButton.RED, RemoteAction.NONE))
    }

    @Test
    fun anUnknownSavedActionFallsBackToTheDefault() {
        assertEquals(RemoteAction.ZAP_UP, RemoteKeymap.action(mapOf("UP" to "RETIRE"), RemoteButton.UP))
    }

    @Test
    fun presetsBelongToTheirProfile() {
        val saved = MultiViewPresets.save(emptyList(), "a", "Foot", listOf("1", "2"))
        val both = MultiViewPresets.save(saved, "b", "Foot", listOf("3", "4"))
        assertEquals(listOf("1", "2"), MultiViewPresets.forProfile(both, "a").single().channelUids)
        assertEquals(listOf("3", "4"), MultiViewPresets.forProfile(both, "b").single().channelUids)
    }

    @Test
    fun savingUnderTheSameNameReplacesAndScreensAreLimited() {
        val first = MultiViewPresets.save(emptyList(), "a", "Foot", listOf("1", "2"))
        val again = MultiViewPresets.save(first, "a", "foot ", listOf("1", "2", "3", "4", "5"))
        assertEquals(listOf(MultiViewPreset("a", "foot", listOf("1", "2", "3", "4"))), again)
        assertEquals(emptyList<MultiViewPreset>(), MultiViewPresets.remove(again, again.single()))
    }
}
