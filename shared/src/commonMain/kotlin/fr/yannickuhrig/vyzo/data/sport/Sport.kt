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
        "premier", "bundesliga", "seriea", "ppv", "top14", "prod2", "sixnations",
    )

    /** Mots qui, seuls, ne prouvent rien (« Arena » peut être un cinéma, « Premier » une chaîne de films). */
    private val weakWords = setOf("arena", "premier", "ppv", "racing", "fight")

    /** Groupes de mots reconnus tels quels. */
    private val sportPhrases = listOf(
        "canal sport", "canal plus sport", "rmc sport", "sky sport", "bt sport", "tnt sports", "movistar deportes",
        "premier league", "champions league", "ligue des champions", "foot plus", "golf plus", "sport plus", "rugby plus",
        "top 14", "pro d2", "six nations", "6 nations", "nations championship",
    )

    /** Compétitions et sports sans ambiguïté (utilisables même sur les chaînes généralistes comme TF1 ou France 2). */
    private val generalEventPhrases = listOf(
        "ligue 1", "ligue 2", "ligue des champions", "champions league", "europa league", "conference league",
        "premier league", "la liga", "serie a", "bundesliga", "eredivisie", "coupe de france", "coupe du monde",
        "world cup", "copa america", "copa del rey", "supercoupe", "super bowl", "nba", "nfl", "nhl", "mlb", "ufc", "wwe",
        "formule 1", "formula 1", "f1", "motogp", "moto gp", "rallye", "nascar", "indycar",
        "grand prix de formule 1", "grand prix de f1", "grand prix moto", "grand prix cycliste",
        "top 14", "pro d2", "six nations", "6 nations", "vi nations", "nations championship", "autumn nations",
        "autumn internationals", "test match", "test matchs", "super rugby", "rugby championship", "four nations",
        "coupe d europe", "champions cup", "challenge cup", "united rugby championship", "premiership rugby",
        "gallagher prem", "gallagher premiership", "british and irish lions", "super league", "xv de france",
        "roland garros", "wimbledon", "us open", "open d australie", "australian open", "atp", "wta", "masters 1000",
        "tour de france", "giro", "vuelta", "jeux olympiques", "handball", "basket", "basketball", "rugby",
        "football", "tennis", "boxe", "boxing", "mma", "cyclisme", "golf", "volley", "hockey", "biathlon",
        "ski alpin", "ski de fond", "saut a ski", "coupe du monde de ski",
        "athletisme", "natation", "snooker", "darts", "flechettes", "classico", "clasico",
    )

    /** Compétitions et sports dans un titre de programme sur une chaîne de sport. */
    private val eventPhrases = generalEventPhrases + listOf("liga", "copa", "grand prix", "ski", "match", "derby")

    /** Ce qui n'est pas un événement à suivre en direct. */
    private val notLive = listOf(
        "rediffusion", "rediff", "replay", "resume", "resumes", "highlights", "best of", "le meilleur de", "temps forts",
        "programme non communique", "fin des programmes", "pas de programme", "no information", "to be announced",
        "avant match", "apres match", "debrief", "le mag", "magazine", "telefoot", "auto moto", "automoto",
        "stade 2", "tout le sport", "after foot", "jour de foot", "jour de rugby", "canal football club",
        "canal rugby club", "journal", "jt", "meteo", "edition speciale",
    )

    private val sportCategoryWords = setOf(
        "sport", "sports", "sportif", "sportive", "sportifs", "sportives",
        "rugby", "football", "soccer", "tennis", "basket", "basketball", "handball", "volley", "volleyball",
        "cyclisme", "cycling", "golf", "boxe", "boxing", "mma", "hockey", "athletisme", "natation", "biathlon",
        "ski", "motorsport", "motorsports", "moto", "equitation", "judo", "escrime", "aviron", "voile",
    )

    private val nonSportCategoryWords = setOf(
        "film", "films", "cinema", "movie", "movies", "serie", "series", "telefilm", "telefilms", "feuilleton",
        "documentaire", "documentaires", "documentary", "magazine", "magazines", "jeunesse", "animation",
        "information", "informations", "journal", "meteo", "musique", "music", "spectacle", "theatre",
    )
    private val nonSportCategoryPhrases = listOf("dessin anime", "talk show")

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

    fun isNonSportCategory(category: String): Boolean {
        if (category.isBlank()) return false
        val w = words(category)
        if (w.any { it in nonSportCategoryWords }) return true
        val j = joined(w)
        return nonSportCategoryPhrases.any { j.contains(" $it ") }
    }

    fun isSportCategory(category: String): Boolean {
        if (category.isBlank() || isNonSportCategory(category)) return false
        return words(category).any { it in sportCategoryWords }
    }

    fun hasVersus(text: String): Boolean = versus.containsMatchIn(text)

    private fun hasSportSignal(description: String): Boolean {
        if (description.isBlank()) return false
        val j = joined(words(description))
        return generalEventPhrases.any { j.contains(" $it ") }
    }

    /** Programme qui ressemble à un événement sportif (et pas à une rediffusion ou à un résumé). */
    fun isEvent(title: String, category: String = "", description: String = "", strict: Boolean = false): Boolean {
        if (title.isBlank()) return false
        val titleJoined = joined(words(title))
        if (notLive.any { titleJoined.contains(" $it ") }) return false
        if (isNonSportCategory(category)) return false
        if (!strict) return eventPhrases.any { titleJoined.contains(" $it ") } || versus.containsMatchIn(title)
        if (generalEventPhrases.any { titleJoined.contains(" $it ") }) return true
        if (!versus.containsMatchIn(title)) return false
        return isSportCategory(category) || hasSportSignal(description)
    }

    /** Indique si la description peut trancher pour un titre « Équipe A - Équipe B » sur une chaîne généraliste. */
    fun needsDescriptionCheck(title: String, category: String): Boolean {
        if (title.isBlank() || isNonSportCategory(category) || isSportCategory(category)) return false
        val titleJoined = joined(words(title))
        if (notLive.any { titleJoined.contains(" $it ") }) return false
        if (generalEventPhrases.any { titleJoined.contains(" $it ") }) return false
        return versus.containsMatchIn(title)
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
     * Regroupe les programmes des chaînes en événements sportifs : un seul par match, même s'il passe sur dix chaînes.
     * [programs] : programmes candidats, [channels] : chaînes visibles dans l'ordre de la playlist,
     * [sportChannelUids] : chaînes dédiées au sport (sur les autres, détection stricte pour éviter les faux positifs).
     */
    fun events(
        channels: List<ChannelEntity>,
        programs: List<ProgramEntity>,
        sportChannelUids: Set<String> = channels.mapTo(HashSet()) { it.uid },
    ): List<SportEvent> {
        val byKey = channels.groupBy { it.epgKey }
        val out = LinkedHashMap<String, Pair<ProgramEntity, MutableList<ChannelEntity>>>()
        for (p in programs.sortedBy { it.start }) {
            val chans = byKey[p.channelKey] ?: continue
            val onSportChannel = chans.any { it.uid in sportChannelUids }
            if (!SportDetector.isEvent(p.title, p.category, p.description, strict = !onSportChannel)) continue
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
