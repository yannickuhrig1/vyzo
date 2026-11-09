package fr.yannickuhrig.vyzo.data.home

import fr.yannickuhrig.vyzo.data.vod.Genres

/**
 * Rangées de l'écran d'accueil (tableau de bord), dans l'ordre choisi par l'utilisateur.
 * Une rangée est un identifiant : « continue_series », « fav_channels »…, ou un genre :
 * « genre_movies:action », « genre_series:comedie ».
 */
object HomeRows {
    /** Grand widget : programme en cours (et suivant) des chaînes favorites, sinon des chaînes récentes. */
    const val ON_AIR = "on_air"
    /** Matchs en cours sur les chaînes de sport (d'après le guide). */
    const val SPORT_LIVE = "sport_live"
    const val CONTINUE_SERIES = "continue_series"
    const val CONTINUE_MOVIES = "continue_movies"
    const val FAV_CHANNELS = "fav_channels"
    const val RECENT_CHANNELS = "recent_channels"
    const val ADDED_MOVIES = "added_movies"
    const val ADDED_SERIES = "added_series"
    const val TOP_MOVIES = "top_movies"
    const val TOP_SERIES = "top_series"
    const val TRENDING_MOVIES = "trending_movies"
    const val TRENDING_SERIES = "trending_series"
    const val FAV_MOVIES = "fav_movies"
    const val FAV_SERIES = "fav_series"
    const val APPS = "apps"
    const val GENRE_MOVIES = "genre_movies:"
    const val GENRE_SERIES = "genre_series:"

    /** Rangées proposées, dans l'ordre par défaut. */
    val BUILT_IN = listOf(
        ON_AIR, SPORT_LIVE, CONTINUE_SERIES, CONTINUE_MOVIES, FAV_CHANNELS, RECENT_CHANNELS, ADDED_MOVIES, TOP_MOVIES,
        ADDED_SERIES, TOP_SERIES, TRENDING_MOVIES, TRENDING_SERIES, FAV_MOVIES, FAV_SERIES, APPS,
    )

    /** Rangées affichées au premier lancement. */
    val DEFAULT = listOf(ON_AIR, CONTINUE_SERIES, CONTINUE_MOVIES, FAV_CHANNELS, RECENT_CHANNELS, ADDED_MOVIES, TOP_MOVIES, ADDED_SERIES, APPS)

    fun isGenre(id: String) = id.startsWith(GENRE_MOVIES) || id.startsWith(GENRE_SERIES)

    fun title(id: String): String = when {
        id == ON_AIR -> "À l'antenne maintenant"
        id == SPORT_LIVE -> "Sport en direct"
        id == CONTINUE_SERIES -> "Reprendre — séries"
        id == CONTINUE_MOVIES -> "Reprendre — films"
        id == FAV_CHANNELS -> "Chaînes favorites"
        id == RECENT_CHANNELS -> "Chaînes récentes"
        id == ADDED_MOVIES -> "Films ajoutés récemment"
        id == ADDED_SERIES -> "Séries ajoutées récemment"
        id == TOP_MOVIES -> "Films les mieux notés"
        id == TOP_SERIES -> "Séries les mieux notées"
        id == TRENDING_MOVIES -> "Films en tendance"
        id == TRENDING_SERIES -> "Séries en tendance"
        id == FAV_MOVIES -> "Films favoris"
        id == FAV_SERIES -> "Séries favorites"
        id == APPS -> "Applications"
        id.startsWith(GENRE_MOVIES) -> "Films · ${Genres.label(id.removePrefix(GENRE_MOVIES))}"
        id.startsWith(GENRE_SERIES) -> "Séries · ${Genres.label(id.removePrefix(GENRE_SERIES))}"
        else -> id
    }

    /** Monte ([delta] = -1) ou descend (+1) une rangée. */
    fun move(rows: List<String>, id: String, delta: Int): List<String> {
        val i = rows.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in rows.indices) return rows
        return rows.toMutableList().apply { add(j, removeAt(i)) }
    }

    /** Affiche ou masque une rangée ; une rangée ajoutée va à la fin. */
    fun toggle(rows: List<String>, id: String, on: Boolean): List<String> =
        if (on) (if (id in rows) rows else rows + id) else rows - id
}
