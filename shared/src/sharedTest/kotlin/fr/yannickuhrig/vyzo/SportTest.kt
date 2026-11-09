package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.sport.SportDetector
import fr.yannickuhrig.vyzo.data.sport.SportLineup
import fr.yannickuhrig.vyzo.util.TextNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Espace sport : chaînes et catégories reconnues, matchs repérés dans le guide et regroupés. */
class SportTest {

    @Test
    fun sportChannelsAndCategories() {
        listOf("FR| SPORTS", "beIN SPORTS 1 FHD", "RMC Sport 2", "Eurosport 1 HD", "L'Équipe", "DAZN 1", "Canal+ Sport", "Foot+ 24/24", "ESPN 2", "Movistar Deportes")
            .forEach { assertTrue(it, SportDetector.isSportName(it)) }
        listOf("TF1 HD", "Arena (cinéma)", "Premier Films", "NETFLIX FR", "##### SPORTS #####", "")
            .forEach { assertFalse(it, SportDetector.isSportName(it)) }
    }

    @Test
    fun eventsInTheGuide() {
        listOf("Ligue 1 : Paris SG - Marseille", "Football - Ligue des champions", "Lakers vs Celtics", "Arsenal v Chelsea", "Formule 1 : Grand Prix de Monaco", "Top 14 : Toulouse / Racing 92", "NBA")
            .forEach { assertTrue(it, SportDetector.isEvent(it)) }
        listOf("L'After Foot", "Rediffusion : Ligue 1 PSG - OM", "Résumé de la soirée de NBA", "Programme non communiqué", "Journal")
            .forEach { assertFalse(it, SportDetector.isEvent(it)) }
        assertTrue(SportDetector.isAnnouncedLive("PSG - OM (Direct)"))
        assertFalse(SportDetector.isAnnouncedLive("PSG - OM"))
    }

    private var pos = 0

    private fun ch(name: String, epg: String) = ChannelEntity(
        uid = name, playlistId = 1, remoteId = name, name = name, nameNorm = TextNorm.search(name), matchKey = TextNorm.matchKey(name),
        number = 0, logo = "", categoryUid = "", epgId = epg, epgKey = epg, streamUrl = "", position = pos++, syncToken = 0,
    )

    private fun p(key: String, title: String, start: Long, stop: Long) =
        ProgramEntity(channelKey = key, start = start, stop = stop, title = title, sourceKey = "t", generation = 1)

    @Test
    fun sameMatchOnSeveralChannelsIsOneEvent() {
        val bein1 = ch("beIN SPORTS 1 FHD", "bein1")
        val bein1hd = ch("beIN SPORTS 1 HD", "bein1")
        val rmc = ch("RMC Sport 1", "rmc1")
        val h = 3_600_000L
        val programs = listOf(
            p("bein1", "Ligue 1 : PSG - OM", 10 * h, 12 * h),
            p("rmc1", "Ligue 1 : PSG - OM (Direct)", 10 * h, 12 * h),
            p("rmc1", "L'After Foot", 12 * h, 14 * h),
            p("bein1", "NBA : Lakers - Celtics", 20 * h, 22 * h),
        )
        val events = SportLineup.events(listOf(bein1, bein1hd, rmc), programs)
        assertEquals(2, events.size)
        assertEquals(listOf(bein1.uid, bein1hd.uid, rmc.uid), events[0].channels.map { it.uid })
        assertEquals(1, SportLineup.live(events, 11 * h).size)
        assertEquals(listOf("NBA : Lakers - Celtics"), SportLineup.upcoming(events, 11 * h, 36 * h).map { it.program.title })
    }
}
