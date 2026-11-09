package fr.yannickuhrig.vyzo

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

/** Faux serveur IPTV (M3U, XMLTV, API Xtream) pour les tests d'intégration et les captures d'écran. */
object Fixtures {
    private const val HOUR = 3600_000L

    fun xmlTime(ms: Long): String =
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms) + " +0000"

    private val shows = mapOf(
        "TF1.fr" to listOf("Le 13h", "Petits plats en équilibre", "Les feux de l'amour", "Grey's Anatomy", "Le juste prix", "Journal de 20h", "Koh-Lanta", "Esprits criminels", "Météo", "Les experts", "Sept à huit"),
        "France2.fr" to listOf("Télématin", "Tout le monde veut prendre sa place", "Ça commence aujourd'hui", "N'oubliez pas les paroles", "Journal 20h", "Envoyé spécial", "Complément d'enquête", "Un si grand soleil", "Faites entrer l'accusé", "Ce soir (ou jamais !)", "Rugby : Tournoi"),
        "Eurosport1.fr" to listOf("Cyclisme : Tour de France", "Tennis : Roland-Garros", "Snooker", "Ski alpin : Coupe du monde", "Football : Ligue des champions", "Biathlon", "Athlétisme", "Moto GP", "Natation", "JO : Résumé", "Basket"),
        "Arte.fr" to listOf("Invitation au voyage", "28 minutes", "Arte journal", "Les grands documentaires", "Cinéma : Le Mépris", "Tracks", "Court-circuit", "Karambolage", "Arte Reportage", "Square idée", "Personne ne bouge !"),
    )

    fun xmltv(now: Long = System.currentTimeMillis()): String {
        val base = now - (now % HOUR) - 5 * HOUR
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tv SYSTEM "xmltv.dtd">
<tv generator-info-name="vyzo-test">
<channel id="TF1.fr"><display-name>TF1</display-name></channel>
<channel id="France2.fr"><display-name>France 2</display-name></channel>
<channel id="Eurosport1.fr"><display-name>Eurosport 1</display-name></channel>
<channel id="Arte.fr"><display-name>Arte</display-name></channel>
<channel id="Inconnue.fr"><display-name>Chaîne sans abonnés</display-name></channel>
""")
        shows.forEach { (id, titles) ->
            var t = base
            titles.forEachIndexed { i, title ->
                val len = if (i % 3 == 0) 90 * 60_000L else if (i % 3 == 1) 45 * 60_000L else 60 * 60_000L
                sb.append("<programme start=\"${xmlTime(t)}\" stop=\"${xmlTime(t + len)}\" channel=\"$id\">")
                sb.append("<title lang=\"fr\">${title.replace("&", "&amp;").replace("'", "&apos;")}</title>")
                sb.append("<desc lang=\"fr\">Résumé de « ${title.replace("&", "&amp;")} » : un programme de test avec&nbsp;une description assez longue pour tenir sur plusieurs lignes dans le guide.</desc>")
                sb.append("<category lang=\"fr\">${if (id.startsWith("Euro")) "Sport" else "Divertissement"}</category></programme>\n")
                t += len
            }
        }
        sb.append("<programme start=\"${xmlTime(base)}\" stop=\"${xmlTime(base + HOUR)}\" channel=\"Inconnue.fr\"><title>Ignoré</title></programme>\n")
        sb.append("</tv>")
        return sb.toString()
    }

    fun gzip(text: String): Buffer {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(text.toByteArray()) }
        return Buffer().write(bos.toByteArray())
    }

    fun m3u(server: MockWebServer): String = """
        #EXTM3U url-tvg="${server.url("/epg.xml.gz")}"
        #EXTINF:-1 tvg-id="TF1.fr" tvg-chno="1" group-title="TNT" catchup="default" catchup-days="7" catchup-source="?utc={utc}&lutc={lutc}",TF1 HD
        ${server.url("/live/tf1.m3u8")}
        #EXTINF:-1 tvg-chno="2" group-title="TNT",France 2 HD
        ${server.url("/live/f2.ts")}
        #EXTINF:-1 tvg-id="Arte.fr" tvg-chno="7" group-title="TNT",Arte
        ${server.url("/live/arte.ts")}
        #EXTINF:-1 tvg-id="Eurosport1.fr" tvg-chno="20" group-title="Sport",Eurosport 1
        ${server.url("/live/euro.ts")}
        #EXTINF:-1 group-title="Films",Le Grand Film (2024)
        ${server.url("/movie/u/p/1.mkv")}
        #EXTINF:-1 group-title="Séries",Ma Série S01 E01
        ${server.url("/series/u/p/2.mp4")}
    """.trimIndent()

    private val liveStreams = """[
      {"num":1,"name":"TF1 HD","stream_id":101,"stream_icon":"","epg_channel_id":"TF1.fr","category_id":"1","tv_archive":1,"tv_archive_duration":"7"},
      {"num":2,"name":"France 2 HD","stream_id":102,"stream_icon":"","epg_channel_id":null,"category_id":"1","tv_archive":0},
      {"num":3,"name":"FR: Arte","stream_id":103,"stream_icon":"","epg_channel_id":"Arte.fr","category_id":"1"},
      {"num":4,"name":"France 3","stream_id":104,"stream_icon":"","epg_channel_id":"","category_id":"1"},
      {"num":5,"name":"M6","stream_id":105,"stream_icon":"","epg_channel_id":"","category_id":"1"},
      {"num":6,"name":"Canal+","stream_id":106,"stream_icon":"","epg_channel_id":"","category_id":"1"},
      {"num":20,"name":"Eurosport 1","stream_id":120,"stream_icon":"","epg_channel_id":"Eurosport1.fr","category_id":"2","tv_archive":1,"tv_archive_duration":3},
      {"num":21,"name":"Eurosport 2","stream_id":121,"stream_icon":"","epg_channel_id":"","category_id":"2"},
      {"num":22,"name":"beIN Sports 1","stream_id":122,"stream_icon":"","epg_channel_id":"","category_id":"2"},
      {"num":99,"name":"Radio test","stream_id":199,"stream_icon":"","epg_channel_id":"","category_id":"77"}
    ]"""

    private val vodStreams = """[
      {"num":1,"name":"Le Grand Bleu (1988)","stream_id":501,"stream_icon":"","rating":"7.8","added":"1700000000","category_id":"10","container_extension":"mkv"},
      {"num":2,"name":"Intouchables","stream_id":502,"stream_icon":"","rating_5based":4.3,"added":"1710000000","category_id":"10","container_extension":"mp4"},
      {"num":3,"name":"La Haine","stream_id":503,"stream_icon":"","rating":"8.1","added":"1690000000","category_id":"10","container_extension":"mp4"},
      {"num":4,"name":"Le Dîner de cons","stream_id":504,"stream_icon":"","rating":"7.6","added":"1695000000","category_id":"11","container_extension":"avi"},
      {"num":5,"name":"OSS 117 : Le Caire, nid d'espions","stream_id":505,"stream_icon":"","rating":"7.0","added":"1699000000","category_id":"11","container_extension":"mkv"}
    ]"""

    private val series = """[
      {"num":1,"name":"Le Bureau des légendes","series_id":701,"cover":"","plot":"Les agents de la DGSE vivent sous légende.","cast":"Mathieu Kassovitz, Sara Giraudeau","genre":"Espionnage","releaseDate":"2015-04-27","rating":"8.6","category_id":"30","backdrop_path":[]},
      {"num":2,"name":"Kaamelott","series_id":702,"cover":"","plot":"Le roi Arthur et ses chevaliers.","genre":"Comédie","releaseDate":"2005-01-03","rating":"8.4","category_id":"30"}
    ]"""

    private val seriesInfo = """{
      "info":{"name":"Le Bureau des légendes","plot":"Les agents de la DGSE vivent sous légende.","cast":"Mathieu Kassovitz","genre":"Espionnage","backdrop_path":[""]},
      "episodes":{
        "1":[{"id":"9001","episode_num":1,"title":"Épisode 1","container_extension":"mkv","info":{"duration_secs":3120,"plot":"Malotru rentre de Syrie."},"season":1},
             {"id":"9002","episode_num":2,"title":"Épisode 2","container_extension":"mkv","info":{"duration_secs":3060},"season":1}],
        "2":[{"id":"9101","episode_num":1,"title":"Retour à Paris","container_extension":"mkv","info":{"duration_secs":3300},"season":2}]
      }
    }"""

    fun dispatcher(server: MockWebServer) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl!!
            val json = { body: String -> MockResponse().setHeader("Content-Type", "application/json").setBody(body) }
            return when (url.encodedPath) {
                "/list.m3u" -> MockResponse().setBody(m3u(server))
                "/epg.xml.gz" -> MockResponse().setHeader("Content-Type", "application/gzip").setBody(gzip(xmltv()))
                "/xmltv.php" -> MockResponse().setHeader("Content-Type", "application/xml").setBody(xmltv())
                "/player_api.php" -> {
                    if (url.queryParameter("password") != "secret") return json("""{"user_info":{"auth":0}}""")
                    when (url.queryParameter("action")) {
                        null -> json(
                            """{"user_info":{"username":"bob","auth":1,"status":"Active","exp_date":"1893456000","max_connections":"2","active_cons":"0","allowed_output_formats":["m3u8","ts"]},
                               "server_info":{"url":"localhost","timezone":"Europe/Paris","timestamp_now":1700000000}}""",
                        )
                        "get_live_categories" -> json("""[{"category_id":"1","category_name":"France | TNT"},{"category_id":"2","category_name":"Sport"}]""")
                        "get_live_streams" -> json(liveStreams)
                        "get_vod_categories" -> json("""[{"category_id":"10","category_name":"Films français"},{"category_id":"11","category_name":"Comédies"}]""")
                        "get_vod_streams" -> json(vodStreams)
                        "get_series_categories" -> json("""[{"category_id":"30","category_name":"Séries françaises"}]""")
                        "get_series" -> json(series)
                        "get_series_info" -> json(seriesInfo)
                        "get_vod_info" -> json("""{"info":{"plot":"Jacques Mayol et Enzo Molinari, rivaux en apnée.","duration":"02:48:00","director":"Luc Besson","cast":"Jean-Marc Barr, Jean Reno","genre":"Drame"}}""")
                        else -> json("[]")
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }
}
