package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.data.db.CategoryEntity
import fr.yannickuhrig.vyzo.data.db.CategoryPrefEntity
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.PlaylistEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.db.SourceType
import fr.yannickuhrig.vyzo.util.TextNorm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Vérifie que l'espace sport détecte aussi le rugby et les grands matchs sur les chaînes généralistes (TF1…). */
class SportEventsTest {
    private var pos = 0

    private fun ch(pid: Long, catUid: String, name: String, epg: String) = ChannelEntity(
        uid = "$pid:$name", playlistId = pid, remoteId = name, name = name, nameNorm = TextNorm.search(name),
        matchKey = TextNorm.matchKey(name), number = pos + 1, logo = "", categoryUid = catUid,
        epgId = epg, epgKey = epg, streamUrl = "http://test/$name", position = pos++, syncToken = 1L,
    )

    private fun prog(key: String, title: String, start: Long, stop: Long, category: String = "", desc: String = "") = ProgramEntity(
        channelKey = key, start = start, stop = stop, title = title,
        description = desc, category = category, sourceKey = "xml", generation = 1L,
    )

    @Test
    fun generalistChannelRugbyMatchAppearsWithoutPollutingSportChannels() = runBlocking {
        val c = testContainer()
        c.start()
        val pid = c.db.playlists().insert(PlaylistEntity(name = "P", type = SourceType.M3U, url = "http://test/p.m3u"))
        val catTnt = "$pid:LIVE:tnt"
        val catSport = "$pid:LIVE:sport"
        val catHidden = "$pid:LIVE:hidden"
        c.db.categories().upsert(
            listOf(
                CategoryEntity(uid = catTnt, playlistId = pid, kind = ContentKind.LIVE, remoteId = "tnt", name = "FR | TNT", position = 0, syncToken = 1L),
                CategoryEntity(uid = catSport, playlistId = pid, kind = ContentKind.LIVE, remoteId = "sport", name = "FR | SPORTS", position = 1, syncToken = 1L),
                CategoryEntity(uid = catHidden, playlistId = pid, kind = ContentKind.LIVE, remoteId = "hidden", name = "FR | TEST", position = 2, syncToken = 1L),
            ),
        )
        c.db.categories().upsertPrefs(listOf(CategoryPrefEntity(uid = catHidden, hidden = true)))

        val tf1Fhd = ch(pid, catTnt, "TF1 FHD", "tf1.fr")
        val tf1Hd = ch(pid, catTnt, "TF1 HD", "tf1.fr")
        val m6 = ch(pid, catTnt, "M6", "m6.fr")
        val bein = ch(pid, catSport, "beIN SPORTS 1", "bein1.fr")
        val hiddenCh = ch(pid, catHidden, "TF1 Backup", "hidden.fr")
        c.db.channels().upsert(listOf(tf1Fhd, tf1Hd, m6, bein, hiddenCh))

        val now = 1_800_000_000_000L
        val hour = 3_600_000L
        c.db.programs().insertAll(
            listOf(
                prog("tf1.fr", "Nations Championship : Pays de Galles / Nouvelle-Zélande", now - hour / 2, now + hour, category = "Sport"),
                prog("tf1.fr", "Joséphine, ange gardien - Un ange", now + 2 * hour, now + 4 * hour),
                prog("m6.fr", "Les Bronzés font du ski", now + 2 * hour, now + 4 * hour, category = "Film"),
                prog("m6.fr", "Angleterre - Japon", now + 2 * hour, now + 4 * hour, desc = "Rugby. Nations Championship."),
                prog("bein1.fr", "PSG - OM", now - hour / 2, now + hour),
                prog("hidden.fr", "Rugby : France - Fidji", now - hour / 2, now + hour, category = "Rugby"),
            ),
        )

        val sportChannels = c.repo.sportChannels().first()
        assertEquals(listOf(bein.uid), sportChannels.map { it.uid })

        val events = c.repo.sportEvents(sportChannels, now, now + 24 * hour)
        val byTitle = events.associateBy { it.program.title }
        assertEquals(
            setOf("Nations Championship : Pays de Galles / Nouvelle-Zélande", "PSG - OM", "Angleterre - Japon"),
            byTitle.keys,
        )
        assertEquals(
            listOf(tf1Fhd.uid, tf1Hd.uid),
            byTitle.getValue("Nations Championship : Pays de Galles / Nouvelle-Zélande").channels.map { it.uid },
        )
        assertEquals(listOf(m6.uid), byTitle.getValue("Angleterre - Japon").channels.map { it.uid })
    }
}
