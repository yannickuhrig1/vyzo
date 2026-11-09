package fr.yannickuhrig.vyzo.tv

import fr.yannickuhrig.vyzo.data.db.ChannelEntity
import fr.yannickuhrig.vyzo.data.db.EpisodeEntity
import fr.yannickuhrig.vyzo.data.db.MovieEntity
import fr.yannickuhrig.vyzo.data.db.SeriesEntity

/*
 * Passage des données de Vyzo (Room) aux modèles des écrans TV.
 * [Item.id] reste l'uid Room : c'est lui qui sert à relire l'entité et à demander le flux à Repository au lancement
 * (resolveChannel, resolveMovie…). [Item.url] peut être vide : chez Xtream, l'adresse est construite à la volée.
 */

private fun String.orNull(): String? = takeIf { it.isNotBlank() }

fun ChannelEntity.toItem(group: String, src: Int = 0) = Item(
    id = uid,
    kind = Kind.LIVE,
    title = name,
    year = null,
    url = streamUrl,
    logo = logo.orNull(),
    group = group,
    tvgId = epgId.orNull(),
    src = src,
)

fun MovieEntity.toItem(group: String, src: Int = 0) = Item(
    id = uid,
    kind = Kind.MOVIE,
    title = name,
    year = yearInt.takeIf { it > 0 },
    url = streamUrl,
    logo = poster.orNull(),
    group = group,
    src = src,
)

fun SeriesEntity.toItem(group: String, episodes: List<EpisodeEntity> = emptyList(), src: Int = 0) = Item(
    id = uid,
    kind = Kind.SERIES,
    title = name,
    year = yearInt.takeIf { it > 0 },
    url = "",
    logo = poster.orNull(),
    group = group,
    episodes = episodes.map { it.toEpisode() },
    src = src,
)

fun EpisodeEntity.toEpisode() = Episode(
    season = season,
    num = episode,
    title = title.ifBlank { "Épisode $episode" },
    url = streamUrl,
)
