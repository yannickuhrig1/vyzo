package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.player.PlaybackState.BUFFERING
import fr.yannickuhrig.vyzo.player.PlaybackState.READY
import fr.yannickuhrig.vyzo.player.QualityVariants
import fr.yannickuhrig.vyzo.player.StallDetector
import fr.yannickuhrig.vyzo.util.TextNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qualité automatique : repérer les saccades et choisir la version juste en dessous. */
class AutoQualityTest {

    private var pos = 0

    private fun ch(name: String, playlistId: Long = 1) = ChannelEntity(
        uid = "$playlistId:$name", playlistId = playlistId, remoteId = name, name = name, nameNorm = TextNorm.search(name),
        matchKey = TextNorm.matchKey(name), number = 0, logo = "", categoryUid = "", epgId = "", epgKey = "", streamUrl = "",
        position = pos++, syncToken = 0,
    )

    @Test
    fun rankReadsQualityFromTheName() {
        assertEquals(QualityVariants.UHD, QualityVariants.rank("FR: TF1 4K"))
        assertEquals(QualityVariants.UHD, QualityVariants.rank("TF1 UHD"))
        assertEquals(QualityVariants.FHD, QualityVariants.rank("TF1 FHD"))
        assertEquals(QualityVariants.FHD, QualityVariants.rank("TF1 Full HD"))
        assertEquals(QualityVariants.FHD, QualityVariants.rank("TF1 (1080p)"))
        assertEquals(QualityVariants.HD, QualityVariants.rank("TF1 HD"))
        assertEquals(QualityVariants.SD, QualityVariants.rank("TF1 SD"))
        // Sans mention : supposée HD.
        assertEquals(QualityVariants.HD, QualityVariants.rank("TF1"))
        // Le codec n'est pas une qualité.
        assertEquals(QualityVariants.HD, QualityVariants.rank("TF1 HEVC"))
    }

    @Test
    fun lowerPicksTheNextStepDown() {
        val uhd = ch("FR: TF1 4K")
        val fhd = ch("FR: TF1 FHD")
        val hd = ch("FR: TF1 HD")
        val sd = ch("FR: TF1 SD")
        val all = listOf(uhd, fhd, hd, sd, ch("FR: France 2 HD"))
        assertEquals(fhd.uid, QualityVariants.lower(uhd, all)?.uid)
        assertEquals(hd.uid, QualityVariants.lower(fhd, all)?.uid)
        assertEquals(sd.uid, QualityVariants.lower(hd, all)?.uid)
        assertNull(QualityVariants.lower(sd, all))
        // Une chaîne sans mention compte comme du HD : au-dessus du SD seulement.
        val plain = ch("FR: TF1")
        assertEquals(sd.uid, QualityVariants.lower(plain, all + plain)?.uid)
    }

    @Test
    fun lowerStaysInTheSameCountryAndPlaylist() {
        val fhd = ch("FR: TF1 FHD")
        assertNull(QualityVariants.lower(fhd, listOf(ch("BE: TF1 HD"), ch("FR: TF1 HD", playlistId = 2))))
        // Intertitre du fournisseur : jamais choisi.
        val sep = ch("##### TF1 #####").copy(matchKey = fhd.matchKey)
        assertNull(QualityVariants.lower(fhd, listOf(sep)))
    }

    @Test
    fun startupBufferingAndShortHiccupsDoNotCount() {
        val d = StallDetector()
        assertFalse(d.onState(BUFFERING, 0))
        assertFalse(d.tick(20_000)) // chargement du début, même long
        assertFalse(d.onState(READY, 20_000))
        repeat(5) { i ->
            val t = 30_000L + i * 5_000
            d.onState(BUFFERING, t)
            assertFalse(d.onState(READY, t + 500)) // micro-coupures
        }
    }

    @Test
    fun repeatedStallsTriggerADowngrade() {
        val d = StallDetector()
        d.onState(READY, 0)
        d.onState(BUFFERING, 10_000); assertFalse(d.onState(READY, 12_000))
        d.onState(BUFFERING, 20_000); assertFalse(d.onState(READY, 22_000))
        d.onState(BUFFERING, 30_000)
        assertFalse(d.tick(31_000))
        assertTrue(d.tick(31_600)) // troisième coupure en cours depuis plus de 1,5 s
    }

    @Test
    fun oldStallsExpireAndALongStallTriggersAlone() {
        val d = StallDetector()
        d.onState(READY, 0)
        d.onState(BUFFERING, 1_000); d.onState(READY, 3_000)
        d.onState(BUFFERING, 5_000); d.onState(READY, 7_000)
        // Plus d'une minute après : les deux premières sont oubliées.
        d.onState(BUFFERING, 70_000)
        assertFalse(d.onState(READY, 72_000))
        d.onState(BUFFERING, 80_000)
        assertFalse(d.tick(91_000))
        assertTrue(d.tick(92_000)) // 12 s sans image
        d.reset()
        assertFalse(d.tick(200_000))
    }
}
