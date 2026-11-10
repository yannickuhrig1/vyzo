package fr.yannickuhrig.vyzo.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.player.ResizeMode
import fr.yannickuhrig.vyzo.player.VideoPlayer
import java.net.URLEncoder

@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val c = LocalContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(c) } })
}

/** Surface vidéo sans contrôles (nos propres surcouches Compose s'affichent par-dessus). */
@Composable
fun VideoSurface(player: VideoPlayer?, modifier: Modifier = Modifier, resizeMode: ResizeMode = ResizeMode.FIT) {
    player?.Surface(modifier, resizeMode)
}

/** Encodage d'un argument de route (équivalent d'Uri.encode). */
fun encodeArg(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

object Routes {
    const val HOME = "home"
    const val HOME_EDIT = "settings/home"
    const val LIVE = "live"
    const val GUIDE = "guide"
    const val SPORT = "sport"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val RECORDINGS = "recordings"
    const val DOWNLOADS = "downloads"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val PLAYLISTS = "settings/playlists"
    const val EPG = "settings/epg"
    const val EPG_CATALOG = "settings/epg/catalog"
    const val PLAYLIST_CHECK = "settings/check"
    const val TRAKT = "settings/trakt"
    const val REMOTE_KEYS = "settings/remote"
    const val GROUPS = "settings/groups"
    const val GROUP_ORDER = "settings/group_order"
    const val PROFILES = "settings/profiles"
    const val ACCOUNT = "settings/account"
    const val CHANNELS_EDIT = "settings/channels/{cat}"
    const val MOVIE = "movie/{uid}"
    const val SERIES_DETAIL = "seriesDetail/{uid}"
    const val PLAYLIST_EDIT = "playlist/{id}"
    const val PLAYER = "player?kind={kind}&uid={uid}&group={group}&program={program}&start={start}"

    fun movie(uid: String) = "movie/${encodeArg(uid)}"
    fun series(uid: String) = "seriesDetail/${encodeArg(uid)}"
    fun playlistEdit(id: Long) = "playlist/$id"
    fun channelsEdit(categoryUid: String) = "settings/channels/${encodeArg(categoryUid)}"

    fun player(kind: String, uid: String, group: String = "", program: Long = 0, start: Long = -1): String =
        "player?kind=$kind&uid=${encodeArg(uid)}&group=${encodeArg(group)}&program=$program&start=$start"

    fun channel(uid: String, group: String) = player(PlayKind.CHANNEL, uid, group)
    fun catchup(channelUid: String, programId: Long) = player(PlayKind.CATCHUP, channelUid, program = programId)
}

/** Position de départ demandée par « Depuis le début » : on ignore la reprise enregistrée. */
const val START_FROM_BEGINNING = -2L

object PlayKind {
    const val CHANNEL = "channel"
    const val CATCHUP = "catchup"
    const val MOVIE = "movie"
    const val EPISODE = "episode"
    const val RECORDING = "recording"
}
