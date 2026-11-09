package fr.yannickuhrig.vyzo.data.sport

import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.util.ChannelSeparator
import fr.yannickuhrig.vyzo.util.TextNorm

/**
 * Reconnaît le sport par mots-clés, comme les genres des films : catégories (« FR | SPORTS », « DAZN »…),
 * chaînes (« beIN SPORTS 1 », « RMC Sport 2 », « L'Équipe »…) et matchs dans le guide (« Ligue 1 : PSG - OM »).
 */
object SportDetector {
    private val separators = Regex("[^a-z0-9]+")

    /** Mots qui suffisent dans un nom de catégorie ou de chaîne. */
    private val sportWords = setOf(
        "sport", "sports", "sporty", "deportes", "deporte", "esporte", "esportes", "spor", "sportive",
        "bein", "beinsports", "eurosport", "eurosport1", "eurosport2", "dazn", "espn", "espn2", "tsn", "supersport",
        "arena", "sportdigital", "sporttv", "sportitalia", "foot", "football", "futbol", "soccer", "rugby", "tennis",
        "golf", "nba", "nfl", "nhl", "mlb", "ufc", "wwe", "f1", "motorsport", "motorsports", "racing", "cyclisme",
        "infosport", "equipe", "lequipe", "automoto", "boxing", "boxe", "fight", "fightbox", "ligue1", "laliga",
        "premier", "bundesliga", "seriea", "ppv",
    )

    /** Mots qui, seuls, ne prouvent rien (« Arena » peut être un cinéma, « Premier » une chaîne de films). */
    private val weakWords = setOf("arena", "premier", "ppv", "racing", "fight")

    /** Groupes de mots reconnus tels quels. */
    private val sportPhrases = listOf(
        "canal sport", "canal plus sport", "rmc sport", "sky sport", "bt sport", "tnt sports", "movistar deportes",
        "premier league", "champions league", "ligue des champions", "foot plus", "golf plus", "sport plus",
    )

    /** Compétitions et sports dans un titre de programme. */
    private val eventPhrases = listOf(
        "ligue 1", "ligue 2", "ligue des champions", "champions league", "europa league", "conference league",
        "premier league", "la liga", "liga", "serie a", "bundesliga", "eredivisie", "coupe de france", "coupe du monde",
        "world cup", "copa", "supercoupe", "super bowl", "nba", "nfl", "nhl", "mlb", "ufc", "wwe",
        "formule 1", "formula 1", "f1", "grand prix", "motogp", "moto gp", "rallye", "nascar", "indycar",
        "top 14", "pro d2", "six nations", "6 nations", "super rugby", "coupe d europe", "champions cup",
        "roland garros", "wimbledon", "us open", "open d australie", "australian open", "atp", "wta", "masters 1000",
        "tour de france", "giro", "vuelta", "jeux olympiques", "handball", "basket", "basketball", "rugby",
        "football", "tennis", "boxe", "boxing", "mma", "cyclisme", "golf", "volley", "hockey", "biathlon", "ski",
        "athletisme", "natation", "snooker", "darts", "flechettes", "match", "derby", "classico", "clasico",
    )

    /** Ce qui n'est pas un événement à suivre en direct. */
    private val notLive = listOf(
        "rediffusion", "rediff", "replay", "resume", "resumes", "highlights", "best of", "le meilleur de", "temps forts",
        "programme non communique", "fin des programmes", "pas de programme", "no information", "to be announced",
    )

    /** « Paris SG - Marseille », « Lakers vs Celtics », « Arsenal v Chelsea », « PSG / OM ». */
    private val versus = Regex("\\S\\s+(-|–|/|vs\\.?|v)\\s+\\S", RegexOption.IGNORE_CASE)

    private fun words(text: String): List<String> = TextNorm.search(text).split(separators).filter { it.isNotEmpty() }

    private fun joined(words: List<String>) = " " + words.joinToString(" ") + " "

    /** Catégorie ou chaîne de sport, d'après son nom. */
    fun isSportName(name: String): Boolean {
        if (name.isBlank() || ChannelSeparator.matches(name)) return false
        val w = words(name)
        if (w.isEmpty()) return false
        val j = joined(w)
        if (sportPhrases.any { j.contains(" $it ") }) return true
        val hits = w.filter { it in sportWords }
        return hits.any { it !in weakWords }
    }

    /** Programme qui ressemble à un événement sportif (et pas à une rediffusion ou à un résumé). */
    fun isEvent(title: String): Boolean {
        if (title.isBlank()) return false
        val w = words(title)
        val j = joined(w)
        if (notLive.any { j.contains(" $it ") }) return false
        return eventPhrases.any { j.contains(" $it ") } || versus.containsMatchIn(title)
    }

    /** Le guide annonce un direct (« (Direct) », « en direct », « LIVE »). */
    fun isAnnouncedLive(title: String): Boolean {
        val j = joined(words(title))
        return j.contains(" direct ") || j.contains(" live ") || j.contains(" en vivo ")
    }

    /** Clé pour regrouper le même match diffusé sur plusieurs chaînes (ou versions FHD, HD, SD de la même). */
    fun eventKey(p: ProgramEntity): String = words(p.title).filter { it != "direct" && it != "live" }.joinToString(" ") + "@" + p.start / 60_000
}

/** Un match ou une course, avec les chaînes qui le diffusent (dans l'ordre de la playlist). */
data class SportEvent(val program: ProgramEntity, val channels: List<ChannelEntity>) {
    val key: String get() = SportDetector.eventKey(program)
    val announcedLive: Boolean get() = SportDetector.isAnnouncedLive(program.title)
}

object SportLineup {
    /**
     * Regroupe les programmes des chaînes sport en événements : un seul par match, même s'il passe sur dix chaînes.
     * [programs] : programmes de ces chaînes (clé = epgKey), [channels] : chaînes sport dans l'ordre de la playlist.
     */
    fun events(channels: List<ChannelEntity>, programs: List<ProgramEntity>): List<SportEvent> {
        val byKey = channels.groupBy { it.epgKey }
        val out = LinkedHashMap<String, Pair<ProgramEntity, MutableList<ChannelEntity>>>()
        for (p in programs.sortedBy { it.start }) {
            if (!SportDetector.isEvent(p.title)) continue
            val chans = byKey[p.channelKey] ?: continue
            val k = SportDetector.eventKey(p)
            val slot = out.getOrPut(k) { p to ArrayList() }
            for (ch in chans) if (slot.second.none { it.uid == ch.uid }) slot.second += ch
        }
        return out.values.map { (p, chans) -> SportEvent(p, chans.sortedBy { it.position }) }
    }

    /** En cours à [now]. */
    fun live(events: List<SportEvent>, now: Long): List<SportEvent> =
        events.filter { it.program.start <= now && it.program.stop > now }

    /** À venir entre [now] et [until], par heure de début. */
    fun upcoming(events: List<SportEvent>, now: Long, until: Long): List<SportEvent> =
        events.filter { it.program.start > now && it.program.start < until }.sortedBy { it.program.start }
}
