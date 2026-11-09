package fr.yannickuhrig.vyzo

import androidx.compose.runtime.Composable
import fr.yannickuhrig.vyzo.tv.TvBrowse
import fr.yannickuhrig.vyzo.tv.TvHome
import fr.yannickuhrig.vyzo.tv.TvLive
import fr.yannickuhrig.vyzo.tv.TvLiveBar
import fr.yannickuhrig.vyzo.tv.TvMenu
import fr.yannickuhrig.vyzo.tv.TvMovieDetail
import fr.yannickuhrig.vyzo.tv.TvPlayerChannels
import fr.yannickuhrig.vyzo.tv.TvPlayerChoice
import fr.yannickuhrig.vyzo.tv.TvPlayerGuide
import fr.yannickuhrig.vyzo.tv.TvPlayerMenu
import fr.yannickuhrig.vyzo.tv.TvScreensaver
import fr.yannickuhrig.vyzo.tv.TvVodBar
import fr.yannickuhrig.vyzo.tv.TvSearch
import fr.yannickuhrig.vyzo.tv.TvSeriesDetail
import fr.yannickuhrig.vyzo.tv.TvSport
import fr.yannickuhrig.vyzo.ui.VariantNav
import fr.yannickuhrig.vyzo.ui.home.HomeActions
import fr.yannickuhrig.vyzo.ui.player.VariantPlayer
import fr.yannickuhrig.vyzo.ui.vod.VariantDetails

/** Écrans propres à Vyzo TV. */
object FlavorScreens {
    val home: (@Composable (HomeActions) -> Unit)? = { TvHome(it) }

    val details: VariantDetails? = VariantDetails(
        movie = { uid, onPlay, onOpenMovie, onOpenSeries -> TvMovieDetail(uid, onPlay, onOpenMovie, onOpenSeries) },
        series = { uid, onPlayEpisode, onOpenMovie, onOpenSeries -> TvSeriesDetail(uid, onPlayEpisode, onOpenMovie, onOpenSeries) },
    )

    val nav: VariantNav? = VariantNav(
        menu = { TvMenu(it) },
        search = { TvSearch(it) },
        browse = { kind, onOpen -> TvBrowse(kind, onOpen) },
        live = { onOpenPlayer -> TvLive(onOpenPlayer) },
        sport = { onWatch, onCatchup -> TvSport(onWatch, onCatchup) },
        player = VariantPlayer(
            liveBar = { TvLiveBar(it) },
            vodBar = { TvVodBar(it) },
            channels = { TvPlayerChannels(it) },
            guide = { TvPlayerGuide(it) },
            menu = { TvPlayerMenu(it) },
            choice = { TvPlayerChoice(it) },
        ),
        screensaver = { TvScreensaver(it) },
    )
}
