package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.StreamQualityEntity
import fr.yannickuhrig.vyzo.data.db.WatchProgressEntity
import fr.yannickuhrig.vyzo.data.source.xtream.XtreamVodParser
import fr.yannickuhrig.vyzo.data.vod.VodQualityIndex
import fr.yannickuhrig.vyzo.data.vod.announcesUhd
import fr.yannickuhrig.vyzo.data.vod.resolutionShort
import fr.yannickuhrig.vyzo.data.vod.resumeFractions
import fr.yannickuhrig.vyzo.player.QualityVariants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VodCardInfoTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun la4kEstLueDansLeTitreOuLaSection() {
        assertTrue(announcesUhd("1917 (2019) 4K"))
        assertTrue(announcesUhd("*4K-UHD/DOLBY VISION/HDR10+*"))
        assertFalse(announcesUhd("Film.1080p.2022"))
        assertFalse(announcesUhd("*ACTION/AVENTURE*"))
        assertFalse(announcesUhd("4Kids présente"))
    }

    @Test
    fun uneChaineSansMentionResteSupposeeHd() {
        assertEquals(QualityVariants.HD, QualityVariants.rank("TF1"))
        assertEquals(0, QualityVariants.announced("TF1"))
        assertEquals(QualityVariants.UHD, QualityVariants.announced("TF1 4K"))
    }

    @Test
    fun laDefinitionReleveeFaitFoiSurLaSection() {
        val index = VodQualityIndex(
            uhdCategories = setOf("cat4k"),
            measured = mapOf(
                "faux4k" to StreamQualityEntity("faux4k", 1920, 1080),
                "cinemascope" to StreamQualityEntity("cinemascope", 3840, 1600),
            ),
        )
        assertTrue(index.isUhd("a", "Film", "cat4k"))
        assertTrue(index.isUhd("b", "Film 4K", "autre"))
        assertFalse(index.isUhd("c", "Film", "autre"))
        assertFalse(index.isUhd("faux4k", "Film 4K", "cat4k"))
        assertTrue(index.isUhd("cinemascope", "Film", "autre"))
    }

    @Test
    fun leLibelleCourtSuitLaDefinition() {
        assertEquals("4K", resolutionShort(3840, 2160))
        assertEquals("1080p", resolutionShort(1920, 800))
        assertEquals("720p", resolutionShort(1280, 720))
        assertEquals("SD", resolutionShort(720, 576))
    }

    @Test
    fun laTailleVientDesInfosDuFournisseurQuandElleExiste() {
        assertEquals(3840 to 2160, XtreamVodParser.parseVideoSize(obj("""{"video":{"width":3840,"height":2160}}""")))
        assertEquals(1920 to 1080, XtreamVodParser.parseVideoSize(obj("""{"video":{"width":"1920","height":"1080"}}""")))
        assertNull(XtreamVodParser.parseVideoSize(obj("""{"video":[]}""")))
        assertNull(XtreamVodParser.parseVideoSize(obj("""{"plot":"x"}""")))
        assertNull(XtreamVodParser.parseVideoSize(null))
    }

    private fun progress(uid: String, parent: String, pos: Long, dur: Long, at: Long) = WatchProgressEntity(
        profileId = "p", contentKey = uid, uid = uid, kind = ContentKind.SERIES, parentUid = parent, positionMs = pos, durationMs = dur, updatedAt = at,
    )

    @Test
    fun uneSeriePrendLAvancementDeSonDernierEpisode() {
        val done = resumeFractions(
            listOf(progress("e2", "s", 75, 100, 20), progress("e1", "s", 25, 100, 10), progress("sans-duree", "", 10, 0, 30)),
        )
        assertEquals(0.75f, done["s"])
        assertEquals(0.25f, done["e1"])
        assertNull(done["sans-duree"])
    }
}
