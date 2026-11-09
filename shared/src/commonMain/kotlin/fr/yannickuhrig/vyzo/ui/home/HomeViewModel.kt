package fr.yannickuhrig.vyzo.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.ContentKind
import fr.yannickuhrig.vyzo.data.db.MovieEntity
import fr.yannickuhrig.vyzo.data.db.ProgramEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import fr.yannickuhrig.vyzo.data.home.HomeRows
import fr.yannickuhrig.vyzo.data.tmdb.TmdbKind
import fr.yannickuhrig.vyzo.data.repo.Repository
import fr.yannickuhrig.vyzo.data.sport.SportLineup
import fr.yannickuhrig.vyzo.data.vod.VodQualityIndex
import fr.yannickuhrig.vyzo.ui.sport.toOnAir
import fr.yannickuhrig.vyzo.data.weather.Weather
import fr.yannickuhrig.vyzo.data.weather.WeatherPlace
import fr.yannickuhrig.vyzo.platform.InstalledApp
import fr.yannickuhrig.vyzo.ui.live.tickerFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Élément d'une rangée de l'accueil. */
sealed interface HomeItem {
    val key: String
}

data class VodItem(
    val kind: ContentKind,
    val uid: String,
    val title: String,
    val poster: String,
    val backdrop: String,
    val rating: Float,
    val year: String,
    val genre: String,
    val plot: String,
    /** 4K annoncée par le titre ou la section, ou relevée sur le flux. */
    val uhd: Boolean = false,
    /** Avancement de 0 à 1 sur les cartes à reprendre. */
    val progress: Float = 0f,
) : HomeItem {
    override val key get() = "${kind.name}:$uid"
}

data class ChannelItem(val channel: ChannelEntity, val now: ProgramEntity?, val group: String) : HomeItem {
    override val key get() = "ch:${channel.uid}"
}

/** Carte du grand widget « À l'antenne maintenant » : programme en cours et suivant d'une chaîne. */
data class OnAirItem(
    val channel: ChannelEntity,
    val now: ProgramEntity,
    val next: ProgramEntity?,
    val group: String,
    /** Nombre d'autres chaînes qui diffusent le même programme (un match sur plusieurs chaînes). */
    val alsoOn: Int = 0,
) : HomeItem {
    override val key get() = "air:${channel.uid}"
}

data class AppItem(val app: InstalledApp) : HomeItem {
    override val key get() = "app:${app.id}"
}

data class HomeRow(val id: String, val title: String, val items: List<HomeItem>)

private fun MovieEntity.toItem(q: VodQualityIndex) =
    VodItem(ContentKind.MOVIE, uid, name, poster, backdrop, rating, year, genre, plot, q.isUhd(uid, name, categoryUid))
private fun SeriesEntity.toItem(q: VodQualityIndex) =
    VodItem(ContentKind.SERIES, uid, name, poster, backdrop, rating, year, genre, plot, q.isUhd(uid, name, categoryUid))

/** Écran d'accueil : rangées choisies par l'utilisateur, arrière-plan qui suit la sélection, horloge et météo. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val apps = MutableStateFlow<List<InstalledApp>>(emptyList())

    init {
        refreshApps()
    }

    /** Relit les applications installées (au retour sur l'accueil, une appli a pu être ajoutée). */
    fun refreshApps() {
        viewModelScope.launch { apps.value = withContext(Dispatchers.IO) { c.platform.installedApps() } }
    }

    // Partagé entre les rangées : une seule lecture des sections, quel que soit le nombre de rangées.
    private val quality = c.repo.vodQuality().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VodQualityIndex())

    private fun movies(key: String): Flow<List<HomeItem>> =
        combine(c.repo.movies(key, MAX_ITEMS), quality) { l, q -> l.take(MAX_ITEMS).map { it.toItem(q) } }
    private fun series(key: String): Flow<List<HomeItem>> =
        combine(c.repo.series(key, MAX_ITEMS), quality) { l, q -> l.take(MAX_ITEMS).map { it.toItem(q) } }

    /** Tendances de TMDB, limitées à ce que le catalogue visible contient. */
    private fun trendingMovies(): Flow<List<HomeItem>> =
        combine(c.repo.viewScope.mapLatest { c.repo.moviesMatching(c.tmdb.trending(TmdbKind.MOVIE)) }, quality) { l, q -> l.map { it.toItem(q) } }
    private fun trendingSeries(): Flow<List<HomeItem>> =
        combine(c.repo.viewScope.mapLatest { c.repo.seriesMatching(c.tmdb.trending(TmdbKind.SERIES)) }, quality) { l, q -> l.map { it.toItem(q) } }

    private fun Flow<List<HomeItem>>.withProgress(): Flow<List<HomeItem>> =
        combine(c.repo.resumeFractions()) { items, done ->
            items.map { if (it is VodItem) it.copy(progress = done[it.uid] ?: 0f) else it }
        }

    private fun channels(group: String): Flow<List<HomeItem>> =
        combine(c.repo.channels(group), tickerFlow(60_000)) { list, t -> list.take(MAX_ITEMS) to t }
            .mapLatest { (list, t) ->
                val now = c.repo.nowPrograms(list.map { it.epgKey }, t)
                list.map { ChannelItem(it, now[it.epgKey], group) }
            }

    /**
     * « À l'antenne maintenant » : chaînes favorites (sinon récentes) qui ont un programme en cours,
     * avec le programme suivant. Rafraîchi chaque minute.
     */
    private fun onAir(): Flow<List<HomeItem>> =
        combine(c.repo.channels(Repository.KEY_FAVORITES), c.repo.channels(Repository.KEY_RECENT), tickerFlow(60_000)) { fav, recent, t ->
            Triple(if (fav.isNotEmpty()) fav else recent, if (fav.isNotEmpty()) Repository.KEY_FAVORITES else Repository.KEY_RECENT, t)
        }.mapLatest { (list, group, t) ->
            val candidates = list.take(ON_AIR_SCAN)
            val now = c.repo.nowPrograms(candidates.map { it.epgKey }, t)
            candidates.mapNotNull { ch ->
                val p = now[ch.epgKey] ?: return@mapNotNull null
                val next = c.repo.upcoming(ch.epgKey, 3).firstOrNull { it.start >= p.stop }
                OnAirItem(ch, p, next, group)
            }.take(ON_AIR_MAX)
        }

    /** « Sport en direct » : un match par carte, sur la première chaîne qui le diffuse. */
    private fun sportLive(): Flow<List<HomeItem>> =
        combine(c.repo.sportChannels(), tickerFlow(60_000)) { chs, t -> chs to t }
            .mapLatest { (chs, t) ->
                SportLineup.live(c.repo.sportEvents(chs, t, t + 1), t).take(ON_AIR_MAX).map { it.toOnAir() }
            }

    private fun rowFlow(id: String): Flow<List<HomeItem>> = when {
        id == HomeRows.ON_AIR -> onAir()
        id == HomeRows.SPORT_LIVE -> sportLive()
        id == HomeRows.CONTINUE_SERIES -> series(Repository.KEY_CONTINUE).withProgress()
        id == HomeRows.CONTINUE_MOVIES -> movies(Repository.KEY_CONTINUE).withProgress()
        id == HomeRows.FAV_CHANNELS -> channels(Repository.KEY_FAVORITES)
        id == HomeRows.RECENT_CHANNELS -> channels(Repository.KEY_RECENT)
        id == HomeRows.ADDED_MOVIES -> movies(Repository.KEY_RECENT_ADDED)
        id == HomeRows.ADDED_SERIES -> series(Repository.KEY_RECENT_ADDED)
        id == HomeRows.TOP_MOVIES -> movies(Repository.KEY_TOP_RATED)
        id == HomeRows.TOP_SERIES -> series(Repository.KEY_TOP_RATED)
        id == HomeRows.TRENDING_MOVIES -> trendingMovies()
        id == HomeRows.TRENDING_SERIES -> trendingSeries()
        id == HomeRows.FAV_MOVIES -> movies(Repository.KEY_FAVORITES)
        id == HomeRows.FAV_SERIES -> series(Repository.KEY_FAVORITES)
        id == HomeRows.APPS -> apps.map { l -> l.map { AppItem(it) } }
        id.startsWith(HomeRows.GENRE_MOVIES) -> movies(Repository.GENRE_PREFIX + id.removePrefix(HomeRows.GENRE_MOVIES))
        id.startsWith(HomeRows.GENRE_SERIES) -> series(Repository.GENRE_PREFIX + id.removePrefix(HomeRows.GENRE_SERIES))
        else -> flowOf(emptyList())
    }

    /**
     * Rangées non vides, dans l'ordre choisi. null tant qu'aucune n'est chargée.
     * Chaque rangée s'affiche dès qu'elle est prête : une rangée lente (un genre sur un gros catalogue)
     * ne retient plus tout l'accueil.
     */
    val rows: StateFlow<List<HomeRow>?> = c.settings.flow.map { it.homeRowsFor(it.activeProfileId) }.distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList())
            else combine(
                ids.map { id ->
                    rowFlow(id).map<List<HomeItem>, HomeRow?> { HomeRow(id, HomeRows.title(id), it) }.onStart { emit(null) }
                },
            ) { all ->
                if (all.all { it == null }) null else all.filterNotNull().filter { it.items.isNotEmpty() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ------------------------------------------------------------ Arrière-plan (« hero »)

    private val focused = MutableStateFlow<VodItem?>(null)
    private var lastFocusAt = 0L
    private val rotation = MutableStateFlow(0)

    /** Titre mis en avant : celui qu'on vient de sélectionner, sinon les affiches défilent. */
    val hero: StateFlow<VodItem?> = combine(rows, focused, rotation) { list, f, i ->
        f ?: list.orEmpty().flatMap { r -> r.items.filterIsInstance<VodItem>() }
            .filter { it.backdrop.isNotBlank() || it.poster.isNotBlank() }
            .distinctBy { it.key }
            .take(ROTATION_SIZE)
            .let { pool -> if (pool.isEmpty()) null else pool[i % pool.size] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onFocus(item: HomeItem) {
        if (item !is VodItem) return
        lastFocusAt = System.currentTimeMillis()
        focused.value = item
    }

    init {
        viewModelScope.launch {
            while (true) {
                delay(ROTATE_MS)
                val s = c.settings.state.value
                // Défilement seulement quand on ne parcourt plus les rangées depuis un moment.
                if (s.homeRotateBackdrop && System.currentTimeMillis() - lastFocusAt > IDLE_MS) {
                    focused.value = null
                    rotation.value = rotation.value + 1
                }
            }
        }
    }

    // ------------------------------------------------------------ Météo

    val weatherPlace: StateFlow<WeatherPlace?> = c.settings.flow
        .map { s -> if (s.weatherCity.isBlank()) null else WeatherPlace(s.weatherCity, s.weatherLat, s.weatherLon) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val weather: StateFlow<Weather?> = weatherPlace.flatMapLatest { place ->
        if (place == null) flowOf<Weather?>(null)
        else flow<Weather?> {
            // En cas d'échec (réseau pas encore prêt au démarrage de la TV…), on garde la dernière météo connue
            // et on réessaie vite, au lieu d'attendre 30 minutes avec un widget vide.
            var last: Weather? = null
            while (true) {
                val w = runCatching { c.weather.forecast(place) }.getOrNull()
                if (w != null) last = w
                emit(last)
                delay(if (w != null) WEATHER_REFRESH_MS else WEATHER_RETRY_MS)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        const val MAX_ITEMS = 30
        private const val ON_AIR_SCAN = 40
        private const val ON_AIR_MAX = 12
        private const val ROTATION_SIZE = 12
        private const val ROTATE_MS = 10_000L
        private const val IDLE_MS = 20_000L
        private const val WEATHER_REFRESH_MS = 30 * 60_000L
        private const val WEATHER_RETRY_MS = 2 * 60_000L
    }
}
