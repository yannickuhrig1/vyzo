package fr.yannickuhrig.vyzo.data.link

import fr.yannickuhrig.vyzo.AppContainer
import fr.yannickuhrig.vyzo.data.db.FAVORITES_GROUP_ID
import fr.yannickuhrig.vyzo.data.db.MovieEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEntity
import kotlinx.coroutines.flow.first
import kotlin.random.Random

enum class LauncherKind { CHANNEL, MOVIE, SERIES }

/** Carte d'une rangée Vyzo sur l'écran d'accueil de la TV. */
data class LauncherItem(
    val uid: String,
    val kind: LauncherKind,
    val title: String,
    val description: String,
    val image: String,
    val link: DeepLink,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

data class LauncherRows(val resume: List<LauncherItem>, val favorites: List<LauncherItem>)

/** Image de l'économiseur d'écran. */
data class AmbientPicture(val uid: String, val title: String, val image: String)

const val LAUNCHER_ROW_MAX = 20
private const val AMBIENT_MAX = 40

private fun String.usableByLauncher() = startsWith("http://") || startsWith("https://")

private fun <T> interleave(vararg lists: List<T>): List<T> {
    val longest = lists.maxOfOrNull { it.size } ?: 0
    return (0 until longest).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
}

/**
 * Rangées montrées hors de l'appli, où aucun code parental ne protège l'affichage :
 * le contenu verrouillé en est toujours exclu, même si le code vient d'être saisi.
 */
suspend fun AppContainer.launcherRows(max: Int = LAUNCHER_ROW_MAX): LauncherRows {
    val scope = repo.viewScopeNow()
    if (scope.pids.isEmpty()) return LauncherRows(emptyList(), emptyList())
    val profile = scope.profileId

    val progress = db.progress().observeForProfile(profile).first()
    val movieProgress = progress.associateBy { it.uid }
    val lastEpisode = progress.filter { it.parentUid.isNotBlank() }.groupBy { it.parentUid }
        .mapValues { (_, list) -> list.maxBy { it.updatedAt } }

    fun MovieEntity.resume() = movieProgress[uid].let { w ->
        (w?.updatedAt ?: 0L) to LauncherItem(uid, LauncherKind.MOVIE, name, plot, poster, DeepLink(DeepLinkKind.PLAY, uid), w?.positionMs ?: 0, w?.durationMs ?: 0)
    }
    fun SeriesEntity.resume() = lastEpisode[uid].let { w ->
        (w?.updatedAt ?: 0L) to LauncherItem(uid, LauncherKind.SERIES, name, plot, poster, DeepLink(DeepLinkKind.SERIES, uid), w?.positionMs ?: 0, w?.durationMs ?: 0)
    }
    val resume = (db.movies().continueWatching(scope.pids, profile, true).first().map { it.resume() } +
        db.series().continueWatching(scope.pids, profile, true).first().map { it.resume() })
        .filter { it.second.image.usableByLauncher() }
        .sortedByDescending { it.first }
        .map { it.second }
        .take(max)

    val channels = db.channels().inUserGroup(FAVORITES_GROUP_ID, profile, scope.pids, true).first()
        .map { LauncherItem(it.uid, LauncherKind.CHANNEL, it.name, "", it.logo, DeepLink(DeepLinkKind.PLAY, it.uid)) }
    val movies = db.movies().inUserGroup(FAVORITES_GROUP_ID, profile, scope.pids, true).first()
        .map { LauncherItem(it.uid, LauncherKind.MOVIE, it.name, it.plot, it.poster, DeepLink(DeepLinkKind.MOVIE, it.uid)) }
    val series = db.series().inUserGroup(FAVORITES_GROUP_ID, profile, scope.pids, true).first()
        .map { LauncherItem(it.uid, LauncherKind.SERIES, it.name, it.plot, it.poster, DeepLink(DeepLinkKind.SERIES, it.uid)) }
    val favorites = interleave(channels, movies, series).filter { it.image.usableByLauncher() }.take(max)

    return LauncherRows(resume, favorites)
}

/** Images de l'économiseur : la TV peut rester allumée sans surveillance, le contenu verrouillé n'y passe donc jamais. */
suspend fun AppContainer.ambientPictures(max: Int = AMBIENT_MAX, random: Random = Random.Default): List<AmbientPicture> {
    val scope = repo.viewScopeNow()
    if (scope.pids.isEmpty()) return emptyList()
    val movies = db.movies().continueWatching(scope.pids, scope.profileId, true).first() +
        db.movies().inUserGroup(FAVORITES_GROUP_ID, scope.profileId, scope.pids, true).first() +
        db.movies().recentlyAdded(scope.pids, true).first() +
        db.movies().topRated(scope.pids, true).first()
    val series = db.series().continueWatching(scope.pids, scope.profileId, true).first() +
        db.series().inUserGroup(FAVORITES_GROUP_ID, scope.profileId, scope.pids, true).first() +
        db.series().recentlyAdded(scope.pids, true).first() +
        db.series().topRated(scope.pids, true).first()
    val all = movies.map { Triple(it.uid, it.name, it.backdrop to it.poster) } + series.map { Triple(it.uid, it.name, it.backdrop to it.poster) }
    return all.filter { (_, _, art) -> art.first.isNotBlank() || art.second.isNotBlank() }
        .distinctBy { it.first }
        .shuffled(random)
        .sortedBy { (_, _, art) -> art.first.isBlank() }
        .take(max)
        .map { (uid, title, art) -> AmbientPicture(uid, title, art.first.ifBlank { art.second }) }
}
