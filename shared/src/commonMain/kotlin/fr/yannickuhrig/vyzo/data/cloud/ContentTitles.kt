package fr.yannickuhrig.vyzo.data.cloud

import fr.yannickuhrig.vyzo.data.db.AppDatabase
import fr.yannickuhrig.vyzo.data.db.ContentKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Titre lisible d'un contenu, joint à sa progression et à ses entrées de listes sur le compte (colonne « meta »).
 * Chiffré avec la clé du compte, comme les playlists : le serveur ne voit pas ce qui est regardé, le site de gestion du
 * compte l'affiche une fois le mot de passe saisi.
 */
@Serializable
internal data class SealedMeta(
    val title: String,
    /** Épisode : « S2 · É5 · Titre ». Film : année. */
    val subtitle: String = "",
)

internal class ContentTitles(private val db: AppDatabase) {
    private val json = Json { encodeDefaults = true }
    private val cache = HashMap<String, SealedMeta?>()

    /** Null si le contenu n'est pas (encore) dans les playlists de cet appareil. */
    suspend fun of(uid: String, kind: ContentKind, parentUid: String = ""): SealedMeta? {
        if (uid.isBlank()) return null
        return cache.getOrPut("$kind|$uid") { lookup(uid, kind, parentUid) }
    }

    private suspend fun lookup(uid: String, kind: ContentKind, parentUid: String): SealedMeta? = when (kind) {
        ContentKind.LIVE -> db.channels().get(uid)?.let { SealedMeta(it.name.trim()) }
        ContentKind.MOVIE -> db.movies().get(uid)?.let { SealedMeta(it.name.trim(), it.year.trim()) }
        ContentKind.SERIES -> {
            val episode = db.series().episode(uid)
            if (episode != null) {
                val series = db.series().get(episode.seriesUid.ifBlank { parentUid })
                val number = "S${episode.season} · É${episode.episode}"
                val title = episode.title.trim()
                SealedMeta(
                    title = series?.name?.trim() ?: title.ifBlank { number },
                    subtitle = if (series != null && title.isNotBlank() && !title.equals(series.name.trim(), true)) "$number · $title" else number,
                )
            } else {
                db.series().get(uid)?.let { SealedMeta(it.name.trim(), it.year.trim()) }
            }
        }
    }?.takeIf { it.title.isNotBlank() }

    /** Bloc chiffré pour la colonne « meta », lié au compte et à la clé du contenu. */
    fun seal(key: ByteArray, userId: String, contentKey: String, meta: SealedMeta): String =
        VaultCrypto.seal(key, json.encodeToString(SealedMeta.serializer(), meta).toByteArray(), context(userId, contentKey))

    companion object {
        fun context(userId: String, contentKey: String) = "vyzo|$userId|meta|$contentKey"
    }
}
