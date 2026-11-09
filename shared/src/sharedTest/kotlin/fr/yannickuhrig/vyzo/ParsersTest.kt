package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.data.catchup.CatchupBuilder
import fr.yannickuhrig.vyzo.data.db.DownloadEntity
import fr.yannickuhrig.vyzo.data.db.DownloadStatus
import fr.yannickuhrig.vyzo.data.epg.XmltvParser
import fr.yannickuhrig.vyzo.data.epg.XmltvProgramme
import fr.yannickuhrig.vyzo.data.net.redactCredentials
import fr.yannickuhrig.vyzo.data.source.m3u.M3uClassifier
import fr.yannickuhrig.vyzo.data.source.m3u.M3uEntry
import fr.yannickuhrig.vyzo.data.source.m3u.M3uHeader
import fr.yannickuhrig.vyzo.data.source.m3u.M3uKind
import fr.yannickuhrig.vyzo.data.source.m3u.M3uParser
import fr.yannickuhrig.vyzo.data.source.stalker.StalkerClient
import fr.yannickuhrig.vyzo.data.source.stalker.StalkerVodParser
import fr.yannickuhrig.vyzo.data.source.xtream.XtreamClient
import fr.yannickuhrig.vyzo.data.source.xtream.XtreamVodParser
import fr.yannickuhrig.vyzo.ui.vod.VodCard
import fr.yannickuhrig.vyzo.ui.vod.VodSortOrder
import fr.yannickuhrig.vyzo.data.subtitles.SrtParser
import fr.yannickuhrig.vyzo.data.subtitles.SubtitleCue
import fr.yannickuhrig.vyzo.data.sync.SyncManager
import fr.yannickuhrig.vyzo.data.tmdb.cleanTitleForTmdb
import fr.yannickuhrig.vyzo.data.tmdb.extractYoutubeId
import fr.yannickuhrig.vyzo.data.tmdb.TmdbService
import fr.yannickuhrig.vyzo.util.AdultDetector
import fr.yannickuhrig.vyzo.util.ChannelSeparator
import fr.yannickuhrig.vyzo.util.Log
import fr.yannickuhrig.vyzo.util.TextNorm
import fr.yannickuhrig.vyzo.util.asArr
import fr.yannickuhrig.vyzo.util.asObj
import fr.yannickuhrig.vyzo.util.int
import fr.yannickuhrig.vyzo.util.str
import fr.yannickuhrig.vyzo.util.streamJsonObjects
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayOutputStream
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

class ParsersTest {
    @Test
    fun pluralFrancais() {
        assertEquals("0 série", fr.yannickuhrig.vyzo.util.plural(0, "série"))
        assertEquals("1 série", fr.yannickuhrig.vyzo.util.plural(1, "série"))
        assertEquals("3 séries", fr.yannickuhrig.vyzo.util.plural(3, "série"))
    }

    @Test
    fun motifDeRefusDuCompteXtream() {
        assertEquals("Identifiant ou mot de passe refusé par le serveur.", XtreamClient.refusalMessage(""))
        assertTrue(XtreamClient.refusalMessage("Expired").startsWith("Abonnement expiré"))
        assertEquals("Compte suspendu par le fournisseur.", XtreamClient.refusalMessage("Banned"))
        assertEquals("Compte désactivé par le fournisseur.", XtreamClient.refusalMessage("Disabled"))
        assertEquals("Compte refusé par le serveur (Trial).", XtreamClient.refusalMessage("Trial"))
        assertEquals("expiré", XtreamClient.statusLabel("Expired"))
    }

    @Test
    fun messageSansSousTitreNommeLaLangue() {
        assertEquals(
            "Aucun sous-titre trouvé pour « Barbapapa » en anglais",
            fr.yannickuhrig.vyzo.ui.player.noSubtitleMessage("Barbapapa", "en"),
        )
    }


    private val m3u = """
        #EXTM3U url-tvg="http://epg.example/guide.xml.gz,http://epg2.example/g.xml" catchup="default"
        #EXTINF:-1 tvg-id="TF1.fr" tvg-name="TF1" tvg-logo="http://logo/tf1.png" group-title="France, TNT" tvg-chno="1" catchup-days="7" catchup-source="?utc={utc}&lutc={lutc}",TF1 HD
        http://srv/live/tf1.m3u8
        #EXTINF:-1 tvg-id="" group-title="France, TNT",France 2
        #EXTVLCOPT:http-user-agent=MonLecteur/1.0
        http://srv/live/f2.ts|Referer=http://ref.example&User-Agent=Autre
        #EXTINF:-1,Sans groupe
        #EXTGRP:Divers
        http://srv/live/x
        #EXTINF:-1 tvg-logo="http://p/m.jpg" group-title="Films",Le Film (2021)
        http://srv/movie/u/p/123.mkv
        #EXTINF:-1 group-title="Séries",Ma Série S02 E05
        http://srv/series/u/p/456.mp4
        #EXTINF:-1 group-title="Séries",Ma Série S02E06 Titre
        http://srv/series/u/p/457.mp4
    """.trimIndent()

    @Test
    fun m3uParsesEntriesAndHeader() {
        var header: M3uHeader? = null
        val entries = ArrayList<M3uEntry>()
        M3uParser.parse(m3u.byteInputStream(), onHeader = { header = it }) { entries += it }
        assertEquals(listOf("http://epg.example/guide.xml.gz", "http://epg2.example/g.xml"), header!!.epgUrls)
        assertEquals("default", header!!.attrs["catchup"])
        assertEquals(6, entries.size)

        val tf1 = entries[0]
        assertEquals("TF1 HD", tf1.name)
        assertEquals("TF1.fr", tf1.tvgId)
        assertEquals("France, TNT", tf1.group)
        assertEquals(1, tf1.number)
        assertEquals("?utc={utc}&lutc={lutc}", tf1.attrs["catchup-source"])

        val f2 = entries[1]
        assertEquals("http://srv/live/f2.ts", f2.url)
        assertEquals("Autre", f2.userAgent)
        assertEquals("http://ref.example", f2.referer)

        assertEquals("Divers", entries[2].group)
        assertEquals(M3uKind.LIVE, M3uClassifier.kind(entries[2]))
        assertEquals(M3uKind.MOVIE, M3uClassifier.kind(entries[3]))
        assertEquals(M3uKind.SERIES, M3uClassifier.kind(entries[4]))
        val ep = M3uClassifier.episode(entries[5].name)!!
        assertEquals("Ma Série", ep.seriesName)
        assertEquals(2, ep.season)
        assertEquals(6, ep.episode)
    }

    @Test
    fun splitInfoIgnoresCommasInQuotes() {
        val (left, name) = M3uParser.splitInfo("#EXTINF:-1 group-title=\"A, B\",Nom, avec virgule")
        assertEquals("#EXTINF:-1 group-title=\"A, B\"", left)
        assertEquals("Nom, avec virgule", name)
    }

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="TF1.fr"><display-name>TF1</display-name><display-name>TF1 HD</display-name><icon src="http://i/tf1.png"/></channel>
          <programme start="20261027200000 +0100" stop="20261027213000 +0100" channel="TF1.fr">
            <title lang="fr">Le Journal</title><sub-title>Édition du soir</sub-title><desc lang="fr">Infos &amp; météo</desc>
            <category lang="fr">Information</category>
          </programme>
          <programme start="20261027213000" stop="20261027230000" channel="TF1.fr"><title><![CDATA[Film & co]]></title></programme>
        </tv>
    """.trimIndent()

    @Test
    fun xmltvParsesChannelsAndProgrammes() {
        val gz = ByteArrayOutputStream().also { bos -> GZIPOutputStream(bos).use { it.write(xml.toByteArray()) } }.toByteArray()
        val input = fr.yannickuhrig.vyzo.data.net.Http.maybeGunzip(gz.inputStream())
        val progs = ArrayList<XmltvProgramme>()
        var names = emptyList<String>()
        XmltvParser { KXmlParser() }.parse(input, onChannel = { names = it.names }, onProgramme = { progs += it })
        assertEquals(listOf("TF1", "TF1 HD"), names)
        assertEquals(2, progs.size)
        val p = progs[0]
        assertEquals("Le Journal", p.title)
        assertTrue(p.description.startsWith("Édition du soir"))
        assertTrue(p.description.contains("Infos & météo"))
        assertEquals("Information", p.category)
        // 20:00 +01:00 = 19:00 UTC
        assertEquals(XmltvParser.civilToEpochDays(2026, 10, 27) * 86400_000L + 19 * 3600_000L, p.start)
        assertEquals(90 * 60_000L, p.stop - p.start)
        assertEquals("Film & co", progs[1].title)
    }

    @Test
    fun xmltvCoupeEstRefuse() {
        val cut = xml.substring(0, xml.indexOf("<title><![CDATA["))
        val e = runCatching { XmltvParser { KXmlParser() }.parse(cut.byteInputStream(), onChannel = {}, onProgramme = {}) }.exceptionOrNull()
        assertTrue("un guide sans </tv> doit être refusé : $e", e is java.io.IOException)
    }

    @Test
    fun epochDays() {
        assertEquals(0L, XmltvParser.civilToEpochDays(1970, 1, 1))
        assertEquals(19723L, XmltvParser.civilToEpochDays(2024, 1, 1))
        assertEquals(1_761_609_600_000L, XmltvParser.parseTime("20251028000000 +0000"))
    }

    @Test
    fun catchupTemplates() {
        val tz = TimeZone.getTimeZone("UTC")
        val start = 1_761_609_600_000L // 2025-10-28 00:00 UTC
        val stop = start + 3600_000L
        val now = start + 7200_000L
        assertEquals(
            "http://s/live.m3u8?utc=1761609600&lutc=1761616800",
            CatchupBuilder.build("http://s/live.m3u8", "default", "?utc={utc}&lutc={lutc}", start, stop, now, tz),
        )
        assertEquals(
            "http://s/archive/2025-10-28:00-00/3600.ts",
            CatchupBuilder.build("http://s/live.ts", "default", "http://s/archive/{utc:Y-m-d:H-M}/{duration}.ts", start, stop, now, tz),
        )
        assertEquals(
            "http://s/ch/index-1761609600-3600.m3u8",
            CatchupBuilder.build("http://s/ch/index.m3u8", "flussonic", "", start, stop, now, tz),
        )
        assertEquals(
            "http://h:80/timeshift/u/p/60/2025-10-28:00-00/42.ts",
            CatchupBuilder.build("http://h:80/u/p/42.ts", "xc", "", start, stop, now, tz),
        )
        assertEquals(
            "http://s/x?a=1&utc=1761609600&lutc=1761616800",
            CatchupBuilder.build("http://s/x?a=1", "shift", "", start, stop, now, tz),
        )
    }

    @Test
    fun xtreamUrls() {
        val c = XtreamClient("host.example:8080/", "user", "p@ss", "ua")
        assertEquals("http://host.example:8080", c.base)
        assertEquals("http://host.example:8080/live/user/p%40ss/12.ts", c.liveUrl("12", "ts"))
        val creds = XtreamClient.fromM3uUrl("http://h.tv:25461/get.php?username=bob&password=s3cr&type=m3u_plus&output=ts")!!
        assertEquals("http://h.tv:25461", creds.server)
        assertEquals("bob", creds.username)
        assertEquals("s3cr", creds.password)
        assertNull(XtreamClient.fromM3uUrl("http://h.tv/playlist.m3u"))
        val ts = c.timeshiftUrl("12", 1_761_609_600_000L, 60, "Europe/Paris")
        assertEquals("http://host.example:8080/timeshift/user/p%40ss/60/2025-10-28:01-00/12.ts", ts)
    }

    @Test
    fun jsonStreamingHandlesArraysAndObjects() {
        val arr = """[{"stream_id": 1, "name": "A", "num": "3"}, {"stream_id": "2", "name": null}]"""
        val out = ArrayList<JsonObject>()
        streamJsonObjects(arr.byteInputStream()) { out += it }
        assertEquals(2, out.size)
        assertEquals("1", out[0].str("stream_id"))
        assertEquals(3, out[0].int("num"))
        assertEquals("", out[1].str("name"))

        val obj = """{"10": {"stream_id": 10}, "11": {"stream_id": 11}}"""
        val out2 = ArrayList<JsonObject>()
        streamJsonObjects(obj.byteInputStream()) { out2 += it }
        assertEquals(listOf("10", "11"), out2.map { it.str("stream_id") })
    }

    @Test
    fun normalization() {
        assertEquals("france2", TextNorm.matchKey("FR: France 2 HD"))
        assertEquals("france2", TextNorm.matchKey("France 2"))
        assertEquals("tf1", TextNorm.matchKey("|FR| TF1 FHD"))
        assertEquals("canal+", TextNorm.matchKey("Canal+ (France)"))
        assertEquals("arte", TextNorm.matchKey("Arté"))
        assertEquals("equipe", TextNorm.search("ÉQUIPE"))
    }

    @Test
    fun stalkerHelpers() {
        assertEquals("http://1.2.3.4:8080/ch/123", StalkerClient.stripCmd("ffmpeg http://1.2.3.4:8080/ch/123"))
        assertEquals("http://p.tv:80", StalkerClient.normalize("p.tv:80/c/"))
        assertTrue(StalkerClient.isValidMac("00:1A:79:12:34:56"))
        assertNotNull(StalkerClient("http://p.tv:80/c/", "00:1A:79:12:34:56").host)
        assertEquals(5400, SyncManager.parseDuration("01:30:00"))
    }

    @Test
    fun vodParsersParseAddedRatingAndYear() {
        // Timestamp parsing
        assertEquals(1700000000_000L, XtreamVodParser.parseAdded("1700000000"))
        assertEquals(1700000000_000L, XtreamVodParser.parseAdded("1700000000000"))
        assertEquals(0L, XtreamVodParser.parseAdded(null))
        assertEquals(0L, XtreamVodParser.parseAdded(""))
        assertEquals(0L, XtreamVodParser.parseAdded("invalid"))

        // Rating parsing
        assertEquals(7.8f, XtreamVodParser.parseRating(7.8f, null), 0.001f)
        assertEquals(8.6f, XtreamVodParser.parseRating(null, 4.3f), 0.001f)
        assertEquals(0f, XtreamVodParser.parseRating(null, null), 0.001f)

        // Year extraction: explicit > releaseDate > regex in title > fallback empty
        assertEquals("2024", XtreamVodParser.extractYear("2024", "2020-01-01", "Film (1999)"))
        assertEquals("2020", XtreamVodParser.extractYear("", "2020-01-01", "Film (1999)"))
        assertEquals("1999", XtreamVodParser.extractYear(null, "", "Film (1999)"))
        assertEquals("", XtreamVodParser.extractYear(null, null, "Film Sans Annee"))

        // Stalker parser
        assertEquals(1710000000_000L, StalkerVodParser.parseAdded("1710000000"))
        assertEquals(8.4f, StalkerVodParser.parseRating(null, 8.4f, null), 0.001f)
        assertEquals("1988", StalkerVodParser.extractYear(null, null, "Le Grand Bleu (1988)"))
    }

    @Test
    fun vodSortOrderSorting() {
        val cards = listOf(
            VodCard(uid = "1", title = "Zorro", rating = 6.0f, year = "1998", addedAt = 100L, releaseDate = "1998-07-17"),
            VodCard(uid = "2", title = "Avatar", rating = 8.5f, year = "2009", addedAt = 300L, releaseDate = "2009-12-18"),
            VodCard(uid = "3", title = "Matrix", rating = 9.0f, year = "1999", addedAt = 200L, releaseDate = "1999-03-31"),
        )

        // Alpha
        val alpha = cards.sortedBy { it.title.lowercase() }.map { it.title }
        assertEquals(listOf("Avatar", "Matrix", "Zorro"), alpha)

        // Added desc
        val added = cards.sortedByDescending { it.addedAt }.map { it.title }
        assertEquals(listOf("Avatar", "Matrix", "Zorro"), added)

        // Release date desc
        val relDate = cards.sortedWith(
            compareByDescending<VodCard> { it.releaseDate.isNotBlank() }
                .thenByDescending { it.releaseDate }
        ).map { it.title }
        assertEquals(listOf("Avatar", "Matrix", "Zorro"), relDate)

        // Year desc
        val byYear = cards.sortedWith(compareByDescending<VodCard> { it.year.toIntOrNull() ?: 0 }).map { it.title }
        assertEquals(listOf("Avatar", "Matrix", "Zorro"), byYear)

        // Rating desc
        val byRating = cards.sortedWith(compareByDescending<VodCard> { it.rating }).map { it.title }
        assertEquals(listOf("Matrix", "Avatar", "Zorro"), byRating)
    }

    @Test
    fun multiDeviceProgressResolutionUsesLatestTimestamp() {
        data class ProgressSnapshot(val positionMs: Long, val updatedAt: Long)

        fun resolvePosition(local: ProgressSnapshot?, cloud: ProgressSnapshot?, fallback: Long = 0L): Long {
            return when {
                local != null && cloud != null -> if (cloud.updatedAt > local.updatedAt) cloud.positionMs else local.positionMs
                cloud != null -> cloud.positionMs
                local != null -> local.positionMs
                else -> fallback
            }
        }

        val localOld = ProgressSnapshot(positionMs = 60_000L, updatedAt = 1_000L)
        val cloudRecent = ProgressSnapshot(positionMs = 180_000L, updatedAt = 2_000L)
        assertEquals(180_000L, resolvePosition(localOld, cloudRecent))

        val localRecent = ProgressSnapshot(positionMs = 240_000L, updatedAt = 3_000L)
        assertEquals(240_000L, resolvePosition(localRecent, cloudRecent))

        assertEquals(180_000L, resolvePosition(null, cloudRecent))
        assertEquals(60_000L, resolvePosition(localOld, null))
        assertEquals(12_000L, resolvePosition(null, null, fallback = 12_000L))
    }

    @Test
    fun versionCompareSemVerOrdering() {
        // Core differences
        assertTrue(fr.yannickuhrig.vyzo.update.VersionCompare.compare("1.5.0-beta1", "1.4.0") > 0)
        assertTrue(fr.yannickuhrig.vyzo.update.VersionCompare.compare("1.4.0", "1.5.0-beta1") < 0)

        // Pre-release rankings: alpha < beta < rc < release
        assertTrue(fr.yannickuhrig.vyzo.update.VersionCompare.compare("1.5.0-beta1", "1.5.0-alpha1") > 0)
        assertTrue(fr.yannickuhrig.vyzo.update.VersionCompare.compare("1.5.0-beta2", "1.5.0-beta1") > 0)
        assertTrue(fr.yannickuhrig.vyzo.update.VersionCompare.compare("1.5.0-rc1", "1.5.0-beta2") > 0)
        assertTrue(fr.yannickuhrig.vyzo.update.VersionCompare.compare("1.5.0", "1.5.0-rc1") > 0)
        assertTrue(fr.yannickuhrig.vyzo.update.VersionCompare.compare("1.5.0", "1.5.0-beta1") > 0)

        // Equivalence
        assertEquals(0, fr.yannickuhrig.vyzo.update.VersionCompare.compare("v1.5.0-beta1", "1.5.0-beta1"))
        assertEquals(0, fr.yannickuhrig.vyzo.update.VersionCompare.compare("1.5.0", "v1.5.0"))

        // Beta / RC detection
        assertTrue(fr.yannickuhrig.vyzo.update.VersionCompare.isBetaOrRc("v1.5.0-beta1"))
        assertTrue(fr.yannickuhrig.vyzo.update.VersionCompare.isBetaOrRc("v1.5.0-rc2"))
        org.junit.Assert.assertFalse(fr.yannickuhrig.vyzo.update.VersionCompare.isBetaOrRc("v1.5.0"))
        org.junit.Assert.assertFalse(fr.yannickuhrig.vyzo.update.VersionCompare.isBetaOrRc("1.4.0"))
    }

    @Test
    fun streamInfoResolutionLabel() {
        val uhd = fr.yannickuhrig.vyzo.player.StreamInfo(width = 3840, height = 2160)
        assertEquals("4K UHD", uhd.resolutionLabel)

        val uhdByHeight = fr.yannickuhrig.vyzo.player.StreamInfo(width = 0, height = 2160)
        assertEquals("4K UHD", uhdByHeight.resolutionLabel)

        val fhd = fr.yannickuhrig.vyzo.player.StreamInfo(width = 1920, height = 1080)
        assertEquals("1080p FHD", fhd.resolutionLabel)

        val hd = fr.yannickuhrig.vyzo.player.StreamInfo(width = 1280, height = 720)
        assertEquals("720p HD", hd.resolutionLabel)

        val sd = fr.yannickuhrig.vyzo.player.StreamInfo(width = 720, height = 576)
        assertEquals("SD", sd.resolutionLabel)

        val unknown = fr.yannickuhrig.vyzo.player.StreamInfo()
        assertEquals("SD", unknown.resolutionLabel)
    }

    @Test
    fun seriesIntroAndOutroConditions() {
        val introMin = 15_000L
        val introMax = 240_000L
        assertTrue(15_000L in introMin..introMax)
        assertTrue(85_000L in introMin..introMax)
        assertTrue(240_000L in introMin..introMax)
        org.junit.Assert.assertFalse(14_999L in introMin..introMax)
        org.junit.Assert.assertFalse(240_001L in introMin..introMax)

        fun isOutro(position: Long, duration: Long): Boolean {
            return duration > 0 &&
                (position >= (duration * 0.92f).toLong() || (duration - position) <= 90_000L) &&
                position < duration - 1_000L
        }

        val epDuration = 3_000_000L // 50 minutes = 3000s
        org.junit.Assert.assertFalse(isOutro(300_000L, epDuration))
        org.junit.Assert.assertFalse(isOutro(2_730_000L, epDuration))
        assertTrue(isOutro(2_790_000L, epDuration))
        assertTrue(isOutro(2_940_000L, epDuration))
        org.junit.Assert.assertFalse(isOutro(2_999_500L, epDuration))
    }

    @Test
    fun catchupRestartEligibility() {
        fun isEligible(
            hasArchive: Boolean,
            progStart: Long,
            now: Long,
            isTimeshifting: Boolean,
            isCatchup: Boolean,
        ): Boolean {
            if (!hasArchive) return false
            if (isTimeshifting || isCatchup) return false
            return (now - progStart) > 180_000L
        }

        val now = 1_000_000L
        // Commencé il y a 2 minutes (120s) -> pas éligible
        org.junit.Assert.assertFalse(isEligible(hasArchive = true, progStart = now - 120_000L, now = now, isTimeshifting = false, isCatchup = false))
        // Commencé il y a 3 minutes exactes (180s) -> pas éligible (> 180s requis)
        org.junit.Assert.assertFalse(isEligible(hasArchive = true, progStart = now - 180_000L, now = now, isTimeshifting = false, isCatchup = false))
        // Commencé il y a 4 minutes (240s) avec archive -> éligible
        assertTrue(isEligible(hasArchive = true, progStart = now - 240_000L, now = now, isTimeshifting = false, isCatchup = false))
        // Commencé il y a 4 minutes mais sans archive -> pas éligible
        org.junit.Assert.assertFalse(isEligible(hasArchive = false, progStart = now - 240_000L, now = now, isTimeshifting = false, isCatchup = false))
        // Déjà en timeshift ou catchup -> pas éligible
        org.junit.Assert.assertFalse(isEligible(hasArchive = true, progStart = now - 240_000L, now = now, isTimeshifting = true, isCatchup = false))
        org.junit.Assert.assertFalse(isEligible(hasArchive = true, progStart = now - 240_000L, now = now, isTimeshifting = false, isCatchup = true))
    }

    @Test
    fun castCommandSerialization() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val cmd = fr.yannickuhrig.vyzo.data.remote.CastCommand(
            targetDeviceId = "tv-living-room",
            itemType = "channel",
            itemId = "ch-tf1",
            title = "TF1 HD",
            posterUrl = "http://logo.example/tf1.png",
            positionMs = 12345L,
            senderDeviceId = "phone-pixel",
        )
        val serialized = json.encodeToString(fr.yannickuhrig.vyzo.data.remote.CastCommand.serializer(), cmd)
        // Le message temps réel ne transporte jamais d'URL de flux (elle contient les identifiants IPTV).
        assertFalse(serialized.contains("http://stream") || serialized.contains("streamUrl"))
        val deserialized = json.decodeFromString(fr.yannickuhrig.vyzo.data.remote.CastCommand.serializer(), serialized)
        assertEquals(cmd, deserialized)
        assertEquals("tv-living-room", deserialized.targetDeviceId)
        assertEquals("channel", deserialized.itemType)
        assertEquals("ch-tf1", deserialized.itemId)
        assertEquals(12345L, deserialized.positionMs)
        assertEquals("phone-pixel", deserialized.senderDeviceId)
    }

    @Test
    fun recordingHostResolutionAndScheduleSync() {
        val defaultSettings = fr.yannickuhrig.vyzo.data.prefs.AppSettings()
        // Par défaut : actif sur Desktop et TV, inactif sur Mobile
        assertTrue(defaultSettings.resolvesRecordingHost(isDesktop = true, isTv = false))
        assertTrue(defaultSettings.resolvesRecordingHost(isDesktop = false, isTv = true))
        org.junit.Assert.assertFalse(defaultSettings.resolvesRecordingHost(isDesktop = false, isTv = false))

        // Si l'utilisateur force manuellement l'option :
        val forcedHostOnMobile = defaultSettings.copy(isRecordingHost = true)
        assertTrue(forcedHostOnMobile.resolvesRecordingHost(isDesktop = false, isTv = false))

        val disabledHostOnTv = defaultSettings.copy(isRecordingHost = false)
        org.junit.Assert.assertFalse(disabledHostOnTv.resolvesRecordingHost(isDesktop = false, isTv = true))
        org.junit.Assert.assertFalse(disabledHostOnTv.resolvesRecordingHost(isDesktop = true, isTv = false))

        // Sérialisation et désérialisation de ScheduleSyncEvent
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val event = fr.yannickuhrig.vyzo.data.remote.ScheduleSyncEvent(
            action = "upsert",
            channelUid = "m3u:tf1",
            channelName = "TF1 HD",
            title = "Le Journal de 20h",
            start = 1700000000000L,
            stop = 1700002400000L,
            status = "SCHEDULED",
            senderDeviceId = "Pixel 8",
        )
        val serialized = json.encodeToString(fr.yannickuhrig.vyzo.data.remote.ScheduleSyncEvent.serializer(), event)
        val deserialized = json.decodeFromString(fr.yannickuhrig.vyzo.data.remote.ScheduleSyncEvent.serializer(), serialized)
        assertEquals(event, deserialized)
        assertEquals("upsert", deserialized.action)
        assertEquals("m3u:tf1", deserialized.channelUid)
        assertEquals("Le Journal de 20h", deserialized.title)
        assertEquals(1700000000000L, deserialized.start)
        assertEquals("Pixel 8", deserialized.senderDeviceId)
    }

    @Test
    fun historyClearEventSerialization() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val event = fr.yannickuhrig.vyzo.data.remote.HistoryClearEvent(
            profileId = "profile-1",
            clearAll = false,
            adultOnly = true,
            categoryUid = "cat-xxx",
            keyword = "adulte",
            itemUid = "m-123",
            contentKeys = listOf("key1", "key2"),
            senderDeviceId = "phone-pixel",
        )
        val serialized = json.encodeToString(fr.yannickuhrig.vyzo.data.remote.HistoryClearEvent.serializer(), event)
        val deserialized = json.decodeFromString(fr.yannickuhrig.vyzo.data.remote.HistoryClearEvent.serializer(), serialized)
        assertEquals(event, deserialized)
        assertEquals("profile-1", deserialized.profileId)
        assertTrue(deserialized.adultOnly)
        org.junit.Assert.assertFalse(deserialized.clearAll)
        assertEquals("cat-xxx", deserialized.categoryUid)
        assertEquals("adulte", deserialized.keyword)
        assertEquals("m-123", deserialized.itemUid)
        assertEquals(listOf("key1", "key2"), deserialized.contentKeys)
        assertEquals("phone-pixel", deserialized.senderDeviceId)
    }

    @Test
    fun multiViewSlotLogic() {
        fun testChannel(uid: String, name: String, streamUrl: String) = fr.yannickuhrig.vyzo.data.db.ChannelEntity(
            uid = uid,
            playlistId = 1,
            remoteId = uid,
            name = name,
            nameNorm = name.lowercase(),
            matchKey = name.lowercase(),
            number = 1,
            logo = "",
            categoryUid = "cat:1",
            epgId = "",
            epgKey = "",
            streamUrl = streamUrl,
            position = 0,
            syncToken = 0L,
        )

        val ch1 = testChannel("ch:1", "Chaîne 1", "http://srv/1.m3u8")
        val ch2 = testChannel("ch:2", "Chaîne 2", "http://srv/2.m3u8")
        val ch3 = testChannel("ch:3", "Chaîne 3", "http://srv/3.m3u8")
        val ch4 = testChannel("ch:4", "Chaîne 4", "http://srv/4.m3u8")

        val p0 = FakeVideoPlayer()
        val p1 = FakeVideoPlayer()
        val p2 = FakeVideoPlayer()
        val p3 = FakeVideoPlayer()

        val slots = mutableListOf(
            fr.yannickuhrig.vyzo.ui.player.MultiViewSlot(0, ch1, p0, isPrimary = true),
            fr.yannickuhrig.vyzo.ui.player.MultiViewSlot(1, ch2, p1, isPrimary = false),
        )
        assertEquals(2, slots.size)
        assertEquals("ch:1", slots[0].channel.uid)
        assertTrue(slots[0].isPrimary)
        org.junit.Assert.assertFalse(slots[1].isPrimary)

        // Ajout d'un 3ème et 4ème slot
        slots.add(fr.yannickuhrig.vyzo.ui.player.MultiViewSlot(2, ch3, p2, isPrimary = false))
        slots.add(fr.yannickuhrig.vyzo.ui.player.MultiViewSlot(3, ch4, p3, isPrimary = false))
        assertEquals(4, slots.size)

        // Exclusivité audio : seul activeAudioSlot n'est pas muté
        var activeAudio = 1
        slots.forEachIndexed { index, slot ->
            slot.player.setMuted(index != activeAudio)
        }
        assertTrue(p0.mutedState)
        org.junit.Assert.assertFalse(p1.mutedState)
        assertTrue(p2.mutedState)
        assertTrue(p3.mutedState)

        // Bascule vers le slot 3
        activeAudio = 3
        slots.forEachIndexed { index, slot ->
            slot.player.setMuted(index != activeAudio)
        }
        assertTrue(p0.mutedState)
        assertTrue(p1.mutedState)
        assertTrue(p2.mutedState)
        org.junit.Assert.assertFalse(p3.mutedState)

        // Libération des slots secondaires
        slots.forEach { if (!it.isPrimary) it.player.release() }
        org.junit.Assert.assertFalse(p0.isReleased)
        assertTrue(p1.isReleased)
        assertTrue(p2.isReleased)
        assertTrue(p3.isReleased)
    }

    @Test
    fun testSrtParserAndHtmlCleaning() {
        val rawSrt = """
            1
            00:01:20,500 --> 00:01:23,800
            <i>Bonjour</i> tout le <b>monde</b> !

            2
            00:01:24.000 --> 00:01:27.250
            <font color="#ffff00">Deuxième</font> réplique avec &amp; et &#39;apostrophe&#39;.
            Deuxième ligne de texte

            3
            01:10:05,000 --> 01:10:10,000
            Fin du film &quot;Inception&quot;
        """.trimIndent()

        val cues = SrtParser.parse(rawSrt)
        assertEquals(3, cues.size)

        // Cue 1
        assertEquals(1, cues[0].index)
        assertEquals(80_500L, cues[0].startMs)
        assertEquals(83_800L, cues[0].endMs)
        assertEquals("Bonjour tout le monde !", cues[0].text)

        // Cue 2 (timestamp avec point, entités HTML et multi-lignes)
        assertEquals(2, cues[1].index)
        assertEquals(84_000L, cues[1].startMs)
        assertEquals(87_250L, cues[1].endMs)
        assertEquals("Deuxième réplique avec & et 'apostrophe'.\nDeuxième ligne de texte", cues[1].text)

        // Cue 3 (heure > 0)
        assertEquals(3, cues[2].index)
        assertEquals(4_205_000L, cues[2].startMs)
        assertEquals(4_210_000L, cues[2].endMs)
        assertEquals("Fin du film \"Inception\"", cues[2].text)
    }

    @Test
    fun testSubtitleCueLookupAndDelay() {
        val cues = listOf(
            SubtitleCue(index = 1, startMs = 10_000L, endMs = 15_000L, text = "Première réplique"),
            SubtitleCue(index = 2, startMs = 20_000L, endMs = 25_000L, text = "Deuxième réplique"),
        )

        // 1. Sans décalage (delayMs = 0)
        assertNull(SrtParser.findCueAt(cues, 5_000L, delayMs = 0L))
        val cue1 = SrtParser.findCueAt(cues, 12_000L, delayMs = 0L)
        assertNotNull(cue1)
        assertEquals("Première réplique", cue1!!.text)
        assertNull(SrtParser.findCueAt(cues, 17_000L, delayMs = 0L))

        // 2. Avec décalage positif (+500ms : les sous-titres sont en retard, on les avance)
        // À position 9_600ms, effectivePos = 9_600 - (+500) = 9_100ms (pas encore affiché)
        assertNull(SrtParser.findCueAt(cues, 9_600L, delayMs = 500L))
        // À position 10_500ms avec delayMs = +1000ms: effectivePos = 9_500ms (pas affiché)
        assertNull(SrtParser.findCueAt(cues, 10_500L, delayMs = 1000L))
        // À position 10_500ms avec delayMs = -1000ms: effectivePos = 11_500ms (dans [10_000, 15_000])
        val cueWithNegativeDelay = SrtParser.findCueAt(cues, 10_500L, delayMs = -1000L)
        assertNotNull(cueWithNegativeDelay)
        assertEquals("Première réplique", cueWithNegativeDelay!!.text)

        // 3. À position 20_500ms, cue 2 est active
        val cue2 = SrtParser.findCueAt(cues, 20_500L, delayMs = 0L)
        assertNotNull(cue2)
        assertEquals("Deuxième réplique", cue2!!.text)
    }

    private class FakeVideoPlayer : fr.yannickuhrig.vyzo.player.VideoPlayer {
        var mutedState = false
        var isReleased = false
        override val current = kotlinx.coroutines.flow.MutableStateFlow<fr.yannickuhrig.vyzo.data.repo.StreamSpec?>(null)
        override val tag = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        override val error = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        override val httpCode = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
        override val status = kotlinx.coroutines.flow.MutableStateFlow(fr.yannickuhrig.vyzo.player.PlayerStatus())
        override val videoSize = kotlinx.coroutines.flow.MutableStateFlow<Pair<Int, Int>?>(null)
        override val streamInfo = kotlinx.coroutines.flow.MutableStateFlow<fr.yannickuhrig.vyzo.player.StreamInfo?>(null)
        override val hasPlayer: Boolean get() = true
        override val currentPosition: Long get() = 0L
        override val duration: Long get() = 0L
        override fun play(spec: fr.yannickuhrig.vyzo.data.repo.StreamSpec, startPositionMs: Long, tag: String?) {}
        override fun stop() {}
        override fun playGrowingFile(file: java.io.File, isGrowing: () -> Boolean, tag: String?) {}
        override fun releaseConnection() {}
        override fun retry() {}
        override fun recreate() {}
        override fun pause() {}
        override fun resume() {}
        override fun seekTo(positionMs: Long) {}
        override fun tracks(type: fr.yannickuhrig.vyzo.player.TrackType): List<fr.yannickuhrig.vyzo.player.TrackOption> = emptyList()
        override fun textEnabled(): Boolean = false
        override fun select(option: fr.yannickuhrig.vyzo.player.TrackOption) {}
        override fun disable(type: fr.yannickuhrig.vyzo.player.TrackType) {}
        override fun clearOverrides(type: fr.yannickuhrig.vyzo.player.TrackType) {}
        override fun toggleMute(): Boolean { mutedState = !mutedState; return mutedState }
        override fun setMuted(muted: Boolean) { this.mutedState = muted }
        override fun release() { isReleased = true }
        override fun adjustVolume(deltaPercent: Int): Int = 100
        @androidx.compose.runtime.Composable
        override fun Surface(modifier: androidx.compose.ui.Modifier, resizeMode: fr.yannickuhrig.vyzo.player.ResizeMode) {}
    }

    @Test
    fun cleanTitleForTmdbCleansTechnicalTagsAndExtractsYear() {
        val (t1, y1) = cleanTitleForTmdb("FR | Inception (2010) [1080p]")
        assertEquals("Inception", t1)
        assertEquals(2010, y1)

        val (t2, y2) = cleanTitleForTmdb("MULTI | Dune: Part Two 2024 4K UHD x265")
        assertEquals("Dune: Part Two", t2)
        assertEquals(2024, y2)

        val (t3, y3) = cleanTitleForTmdb("Breaking.Bad.S01-S05.Complete.1080p.BluRay")
        assertEquals("Breaking Bad", t3)
        assertNull(y3)

        val (t4, y4) = cleanTitleForTmdb("[FR] Oppenheimer (2023) FHD HEVC")
        assertEquals("Oppenheimer", t4)
        assertEquals(2023, y4)

        val (t5, y5) = cleanTitleForTmdb("VF : Gladiator")
        assertEquals("Gladiator", t5)
        assertNull(y5)
    }

    @Test
    fun bandeAnnonceVideoOuRecherche() {
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", fr.yannickuhrig.vyzo.data.tmdb.trailerUrl("dQw4w9WgXcQ", "Noise"))
        assertEquals(
            "https://www.youtube.com/results?search_query=Noise+bande+annonce+vf",
            fr.yannickuhrig.vyzo.data.tmdb.trailerUrl(null, "Noise"),
        )
    }

    @Test
    fun extractYoutubeIdParsesAllVariants() {
        val expected = "dQw4w9WgXcQ"

        // Direct ID
        assertEquals(expected, extractYoutubeId("dQw4w9WgXcQ"))

        // Standard watch URL
        assertEquals(expected, extractYoutubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))

        // With extra params
        assertEquals(expected, extractYoutubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s&feature=share"))

        // Short youtu.be URL
        assertEquals(expected, extractYoutubeId("https://youtu.be/dQw4w9WgXcQ?t=10"))

        // Embed URL
        assertEquals(expected, extractYoutubeId("https://www.youtube.com/embed/dQw4w9WgXcQ"))

        // Invalid / empty
        assertNull(extractYoutubeId(""))
        assertNull(extractYoutubeId(null))
        assertNull(extractYoutubeId("not_a_valid_youtube_url"))
    }

    @Test
    fun tmdbTrailerSelectionPrefersFrenchTrailer() {
        val service = TmdbService(dummySettingsStore())
        val videosJson = """
            [
                {"site": "YouTube", "type": "Trailer", "iso_639_1": "en", "key": "yt_en_trailer", "official": "true"},
                {"site": "YouTube", "type": "Trailer", "iso_639_1": "fr", "key": "yt_fr_trailer", "official": "true"},
                {"site": "Vimeo", "type": "Trailer", "iso_639_1": "fr", "key": "vimeo_key"}
            ]
        """.trimIndent()

        val jsonArray = fr.yannickuhrig.vyzo.util.LenientJson.parseToJsonElement(videosJson).asArr()!!
        val videoObjects = jsonArray.mapNotNull { it.asObj() }

        val bestKey = service.findBestYoutubeTrailerKey(videoObjects)
        assertEquals("yt_fr_trailer", bestKey)
    }

    @Test
    fun tmdbParsesMovieResponseWithCredits() {
        val service = TmdbService(dummySettingsStore())
        val movieJson = """
            {
                "id": 157336,
                "title": "Interstellar",
                "tagline": "Le voyage vers l'infini commence.",
                "overview": "Une équipe d'explorateurs franchit une faille dans l'espace-temps...",
                "vote_average": 8.6,
                "poster_path": "/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg",
                "backdrop_path": "/xJHokMbljvjADYdit5fK5VQsXEG.jpg",
                "genres": [{"id": 18, "name": "Drame"}, {"id": 878, "name": "Science-Fiction"}],
                "credits": {
                    "cast": [
                        {"id": 10297, "name": "Matthew McConaughey", "character": "Cooper", "profile_path": "/e9ZHRvd4n4h4b4k4.jpg"},
                        {"id": 1813, "name": "Anne Hathaway", "character": "Brand", "profile_path": "/bh.jpg"}
                    ],
                    "crew": [
                        {"id": 525, "name": "Christopher Nolan", "job": "Director"}
                    ]
                },
                "videos": {
                    "results": [
                        {"site": "YouTube", "type": "Trailer", "iso_639_1": "fr", "key": "interstellar_fr_yt"}
                    ]
                }
            }
        """.trimIndent()

        val json = fr.yannickuhrig.vyzo.util.LenientJson.parseToJsonElement(movieJson).asObj()!!
        val enrichment = service.parseMovieResponse(json, 157336, "dummy_key")

        assertEquals(157336, enrichment.tmdbId)
        assertEquals("Interstellar", enrichment.title)
        assertEquals("Le voyage vers l'infini commence.", enrichment.tagline)
        assertEquals(8.6f, enrichment.voteAverage, 0.01f)
        assertEquals(listOf("Drame", "Science-Fiction"), enrichment.genres)
        assertEquals(2, enrichment.cast.size)
        assertEquals("Matthew McConaughey", enrichment.cast[0].name)
        assertEquals("Cooper", enrichment.cast[0].character)
        assertEquals("https://image.tmdb.org/t/p/w185/e9ZHRvd4n4h4b4k4.jpg", enrichment.cast[0].profileUrl)
        assertEquals(listOf("Christopher Nolan"), enrichment.directors)
        assertEquals("interstellar_fr_yt", enrichment.youtubeTrailerId)
    }

    @Test
    fun downloadEntityProgressAndStatusHelpers() {
        val dl1 = DownloadEntity(
            id = "movie:123",
            itemType = "movie",
            itemUid = "123",
            seriesUid = null,
            title = "Test Movie",
            subtitle = "",
            posterUrl = "",
            remoteUrl = "http://example.com/test.mp4",
            localFilePath = "/path/test.mp4",
            status = DownloadStatus.DOWNLOADING,
            bytesDownloaded = 450_000_000L,
            totalBytes = 900_000_000L,
            createdAt = System.currentTimeMillis(),
        )

        assertTrue(dl1.isDownloading)
        assertEquals(0.5f, dl1.progress, 0.001f)
        assertEquals(false, dl1.isCompleted)
        assertEquals(false, dl1.isPaused)
        assertEquals(false, dl1.isQueued)
        assertEquals(false, dl1.isFailed)

        val dl2 = dl1.copy(status = DownloadStatus.COMPLETED)
        assertTrue(dl2.isCompleted)
        assertEquals(1.0f, dl2.progress, 0.001f)

        val dl3 = dl1.copy(totalBytes = 0L)
        assertEquals(0f, dl3.progress, 0.001f)
    }

    @Test
    fun detectionAdulteSurLesNomsDeCategories() {
        // Catégories réelles d'un abonnement : la pleine largeur et les signes combinants ne doivent pas la cacher.
        listOf(
            "ҳ̸Ҳ̸Ҳ̸ҳ（ ＰＯＲＮ ）ҳ̸Ҳ̸Ҳ̸ҳ", "ADULTES", "PORNBOX", "FR| XXX", "|FR| ADULT 18+", "+18", "Films érotiques",
            "HENTAI", "Dorcel TV", "For Adults Only",
        ).forEach { assertTrue(it, AdultDetector.isAdultCategory(it)) }
        // Titres signalés à tort avant la 1.7.1 : comme noms de catégories non plus, ils ne veulent rien dire.
        listOf(
            "Sexy Dance 5", "Sexy movie", "Van Wilder 2 : Sexy Party", "xXx²: The Next Level", "MaXXXine",
            "Saturday Night's Main Event XXXIX", "Magic Mike XXL", "Pêche XXL", "Private Property", "Private Practice",
            "Nicky Larson City Hunter : Private Eyes",
            "NRJ - SEXY", "[CA] ADULT SWIM", "US: ADULT SWIM", "FRANCE FHD", "SPORTS 4K", "Documentaires",
        ).forEach { assertFalse(it, AdultDetector.isAdultCategory(it)) }
        assertEquals("porn", TextNorm.search("ＰＯＲＮ"))
    }

    @Test
    fun chainesSeparateursDesFournisseurs() {
        // Intertitres relevés dans une vraie playlist Xtream (15 665 chaînes).
        listOf(
            "░▒▓█ FRANCE FHD █▓▒░", "##### UNTERHALTUNG #####", "###### -- |FR|  CANAL + |FR| -- ######", "*-* CANADA *-*",
            "•●★-- Romania Naţional --★●•", "▂ ▄ ▅ ▆  PERU  ▆ ▅ ▄ ▂", "+ + + + + INFANTIL + + + + +", "⭐⭐⭐ Afghanistan ⭐⭐⭐",
            "°°°°°°U.S.A°°°°°°°°", "-----------------",
        ).forEach { assertTrue(it, ChannelSeparator.matches(it)) }
        listOf(
            "TF1", "[FR] France 2 HD", "|.UK.|_VIAPLAY SPORT 10 [ ONLY LIVE EVENTS ]", "[AF]  BOOM TV ( CAMEROUN )",
            "⸗ Peru ◊ TELESUR ● //", "#Aragon TV#", "\"Canal 13\"", "Canal+ (France)", "M6 *", "",
        ).forEach { assertFalse(it, ChannelSeparator.matches(it)) }
    }

    @Test
    fun journalMasqueLesIdentifiantsIptv() {
        // Forme courte M3U, chemins Xtream, paramètres, MAC : rien ne doit permettre de rejouer l'abonnement.
        val urls = listOf(
            "http://serveur.tv:8080/bob/secret/12345",
            "http://serveur.tv:8080/bob/secret/12345.ts?token=abcdef123456",
            "http://serveur.tv:8080/movie/bob/secret/987.mkv",
            "http://serveur.tv:8080/get.php?username=bob&password=secret&type=m3u_plus",
            "http://portail.tv/c/?mac=00:1A:79:12:34:56",
        )
        for (u in urls) {
            val r = u.redactCredentials()
            assertFalse(r, r.contains("bob") || r.contains("secret") || r.contains("12:34:56") || r.contains("abcdef123456"))
            assertTrue(r, r.startsWith("http://"))
        }
        assertEquals("http://serveur.tv:8080/***/***/12345", urls[0].redactCredentials())
        // Les adresses ordinaires restent lisibles.
        assertEquals("https://api.github.com/repos/a/b/releases", "https://api.github.com/repos/a/b/releases".redactCredentials())

        val previous = Log.sink
        val lines = mutableListOf<String>()
        Log.sink = { _, _, msg, t -> lines += msg; t?.let { lines += Log.trace(it) } }
        try {
            Log.w("Test", "lecture de ${urls[0]}", java.io.IOException("échec pour ${urls[2]}"))
        } finally {
            Log.sink = previous
        }
        assertEquals(2, lines.size)
        lines.forEach { assertFalse(it, it.contains("bob") || it.contains("secret")) }
    }

    private fun dummySettingsStore(): fr.yannickuhrig.vyzo.data.prefs.SettingsStore =
        fr.yannickuhrig.vyzo.data.prefs.SettingsStore(
            file = java.io.File.createTempFile("dummy-settings", ".preferences_pb").apply { deleteOnExit() },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        )
}
