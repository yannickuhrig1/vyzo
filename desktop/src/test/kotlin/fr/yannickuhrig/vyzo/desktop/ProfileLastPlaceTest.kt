package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.closeForTest
import fr.yannickuhrig.vyzo.data.db.CategoryEntity
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.data.repo.Repository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Chaque profil retrouve son dernier groupe de chaînes et sa dernière chaîne, même après avoir quitté l'appli. */
class ProfileLastPlaceTest {

    @Test
    fun chaqueProfilGardeSonDernierGroupe() = runBlocking {
        val c = testContainer()
        val pid = c.repo.addPlaylist(PlaylistEntity(name = "IPTV", type = SourceType.M3U, url = "file:///dev/null"))
        val fhd = CategoryEntity(uid = "$pid:LIVE:fhd", playlistId = pid, kind = ContentKind.LIVE, remoteId = "fhd", name = "FHD", position = 0, syncToken = 1L)
        c.db.categories().upsert(listOf(fhd))
        fun channel(n: Int) = ChannelEntity(
            uid = "$pid:L:$n", playlistId = pid, remoteId = "$n", name = "Chaîne $n", nameNorm = "chaine $n", matchKey = "chaine$n",
            number = n, logo = "", categoryUid = fhd.uid, epgId = "", epgKey = "", streamUrl = "http://srv/$n", position = n, syncToken = 1L,
        )
        val ch1 = channel(1)
        val ch2 = channel(2)
        c.db.channels().upsert(listOf(ch1, ch2))

        val papa = c.profiles.activeNow()
        val lea = c.profiles.create("Léa")
        c.profiles.select(papa.id)
        c.repo.touchChannel(ch1, "cat:${fhd.uid}")

        // Léa n'arrive pas dans le groupe FHD de papa.
        c.profiles.select(lea.id)
        assertEquals("", c.settings.get().lastGroupKey)
        assertEquals("", c.settings.get().lastChannelUid)
        c.repo.touchChannel(ch2, Repository.KEY_ALL)

        c.profiles.select(papa.id)
        assertEquals("cat:${fhd.uid}", c.settings.get().lastGroupKey)
        assertEquals(ch1.uid, c.settings.get().lastChannelUid)

        // On quitte l'appli sur le profil de papa : les places de chacun sont dans le fichier des réglages, relu au lancement.
        val saved = c.settings.get()
        assertEquals(saved, c.settings.decode(c.settings.encode(saved)))
        assertEquals(ch2.uid, saved.lastPlaces[lea.id]?.channelUid)
        c.profiles.select(lea.id)
        assertEquals(Repository.KEY_ALL, c.settings.get().lastGroupKey)
        assertEquals(ch2.uid, c.settings.get().lastChannelUid)

        // Effacer l'historique de Léa depuis le profil de papa n'efface que la dernière chaîne de Léa.
        c.profiles.select(papa.id)
        c.repo.clearWatchHistory(profileId = lea.id)
        assertEquals(ch1.uid, c.settings.get().lastChannelUid)
        c.profiles.select(lea.id)
        assertEquals("", c.settings.get().lastChannelUid)
        assertEquals(Repository.KEY_ALL, c.settings.get().lastGroupKey)

        // Profil supprimé : sa place n'est plus gardée.
        c.profiles.delete(lea.id)
        assertEquals(papa.id, c.settings.get().activeProfileId)
        assertEquals("cat:${fhd.uid}", c.settings.get().lastGroupKey)
        assertFalse(lea.id in c.settings.get().lastPlaces)
        c.closeForTest()
    }

    /** Juste après un changement de profil, effacer l'historique de l'autre profil ne touche pas à la place du profil actif. */
    @Test
    fun effacerJusteApresUnChangementDeProfilGardeLaPlaceDuProfilActif() = runBlocking {
        val c = testContainer()
        val pid = c.repo.addPlaylist(PlaylistEntity(name = "IPTV", type = SourceType.M3U, url = "file:///dev/null"))
        val ch = ChannelEntity(
            uid = "$pid:L:1", playlistId = pid, remoteId = "1", name = "Chaîne 1", nameNorm = "chaine 1", matchKey = "chaine1",
            number = 1, logo = "", categoryUid = "", epgId = "", epgKey = "", streamUrl = "http://srv/1", position = 1, syncToken = 1L,
        )
        c.db.channels().upsert(listOf(ch))
        val papa = c.profiles.activeNow()
        val lea = c.profiles.create("Léa")
        c.profiles.select(papa.id)
        c.repo.touchChannel(ch, Repository.KEY_ALL)

        repeat(200) {
            c.profiles.select(lea.id)
            c.profiles.select(papa.id)
            c.repo.clearWatchHistory(profileId = lea.id)
            assertEquals("Essai $it", ch.uid, c.settings.get().lastChannelUid)
        }
        c.closeForTest()
    }
}
