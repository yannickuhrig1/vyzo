package fr.yannickuhrig.vyzo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import fr.yannickuhrig.vyzo.data.vod.GenreSource
import kotlinx.coroutines.flow.Flow

/** Catégories verrouillées par le contrôle parental (choix de l'utilisateur, sinon détection « adulte »). */
const val LOCKED_CATEGORIES =
    "(SELECT lc.uid FROM category lc LEFT JOIN category_pref lp ON lp.uid = lc.uid WHERE IFNULL(lp.locked, lc.adult) = 1)"

/** Identifiant et nom normalisé d'un élément (recherche tolérante aux fautes de frappe). */
data class NameRow(val uid: String, val name: String)

/** Créneau occupé dans le guide d'une chaîne. */
data class ProgramSlot(val channelKey: String, val start: Long, val stop: Long)

/** Catégories masquées par l'utilisateur. */
const val HIDDEN_CATEGORIES = "(SELECT hp.uid FROM category_pref hp WHERE hp.hidden = 1)"

/** Filtre « chaîne visible » quand le contenu verrouillé doit être caché (:hide). */
const val CH_UNLOCKED = "(:hide = 0 OR (ch.locked = 0 AND ch.categoryUid NOT IN " + LOCKED_CATEGORIES + "))"

private const val CATEGORY_COLUMNS =
    """c.uid, IFNULL(NULLIF(p.name, ''), c.name) AS name, c.name AS origName, c.kind, c.remoteId, c.playlistId,
       IFNULL(p.hidden, 0) AS hidden, IFNULL(p.locked, c.adult) AS locked, c.adult,
       IFNULL(p.position, c.position + IFNULL((SELECT pl.position FROM playlist pl WHERE pl.id = c.playlistId), 0) * 100000) AS sortPos"""

data class CategoryRow(
    val uid: String,
    /** Nom affiché (celui choisi par l'utilisateur, sinon celui de la playlist). */
    val name: String,
    val origName: String,
    val kind: ContentKind,
    val remoteId: String,
    val playlistId: Long,
    val hidden: Boolean,
    /** Verrouillée par le contrôle parental (choix explicite ou détection automatique). */
    val locked: Boolean,
    val adult: Boolean,
    val sortPos: Int,
    val itemCount: Int,
)

data class ChannelEpgRef(
    val epgId: String,
    val matchKey: String,
)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist ORDER BY position, id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist ORDER BY position, id")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlist WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Update
    suspend fun update(p: PlaylistEntity)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE playlist SET lastSync = :ts, lastError = :error WHERE id = :id")
    suspend fun setSyncResult(id: Long, ts: Long, error: String)

    @Query(
        """UPDATE playlist SET expiresAt = :expiresAt, maxConnections = :maxConnections, activeConnections = :activeConnections,
           accountStatus = :status, accountCheckedAt = :checkedAt WHERE id = :id""",
    )
    suspend fun setAccountInfo(id: Long, expiresAt: Long, maxConnections: Int, activeConnections: Int, status: String, checkedAt: Long)
}

@Dao
interface CategoryDao {
    @Upsert
    suspend fun upsert(items: List<CategoryEntity>)

    @Query("DELETE FROM category WHERE playlistId = :pid AND kind = :kind AND syncToken != :token")
    suspend fun deleteStale(pid: Long, kind: ContentKind, token: Long)

    @Query("DELETE FROM category WHERE playlistId = :pid")
    suspend fun deleteForPlaylist(pid: Long)

    @Query("SELECT * FROM category WHERE uid = :uid")
    suspend fun get(uid: String): CategoryEntity?

    @Query("SELECT * FROM category ORDER BY name")
    suspend fun all(): List<CategoryEntity>

    @Query(
        "SELECT " + CATEGORY_COLUMNS + """,
           (SELECT COUNT(*) FROM channel ch WHERE ch.categoryUid = c.uid AND ch.hidden = 0) AS itemCount
           FROM category c LEFT JOIN category_pref p ON p.uid = c.uid
           WHERE c.playlistId IN (:pids) AND c.kind = 'LIVE' ORDER BY sortPos, c.name""",
    )
    fun liveCategories(pids: List<Long>): Flow<List<CategoryRow>>

    @Query(
        "SELECT " + CATEGORY_COLUMNS + """,
           (SELECT COUNT(*) FROM movie m WHERE m.categoryUid = c.uid) AS itemCount
           FROM category c LEFT JOIN category_pref p ON p.uid = c.uid
           WHERE c.playlistId IN (:pids) AND c.kind = 'MOVIE' ORDER BY sortPos, c.name""",
    )
    fun movieCategories(pids: List<Long>): Flow<List<CategoryRow>>

    @Query(
        "SELECT " + CATEGORY_COLUMNS + """,
           (SELECT COUNT(*) FROM series s WHERE s.categoryUid = c.uid) AS itemCount
           FROM category c LEFT JOIN category_pref p ON p.uid = c.uid
           WHERE c.playlistId IN (:pids) AND c.kind = 'SERIES' ORDER BY sortPos, c.name""",
    )
    fun seriesCategories(pids: List<Long>): Flow<List<CategoryRow>>

    /** Verrouillée par le contrôle parental (choix explicite ou détection « adulte »). */
    @Query("SELECT COUNT(*) > 0 FROM category c LEFT JOIN category_pref p ON p.uid = c.uid WHERE c.uid = :uid AND IFNULL(p.locked, c.adult) = 1")
    suspend fun isLocked(uid: String): Boolean

    @Query("UPDATE category SET adult = 0")
    suspend fun clearAdultFlags()

    @Query("SELECT * FROM category WHERE adult = 0")
    suspend fun notAdult(): List<CategoryEntity>

    @Query("UPDATE category SET adult = 1 WHERE uid IN (:uids)")
    suspend fun markAdult(uids: List<String>)

    @Upsert
    suspend fun upsertPrefs(prefs: List<CategoryPrefEntity>)

    @Query("SELECT * FROM category_pref WHERE uid = :uid")
    suspend fun pref(uid: String): CategoryPrefEntity?

    @Query("SELECT * FROM category_pref")
    suspend fun allPrefs(): List<CategoryPrefEntity>

    @Query("UPDATE category SET position = :newPosition WHERE uid = :uid")
    suspend fun updateCategoryPosition(uid: String, newPosition: Int)

    @Query(
        """INSERT INTO category_pref (uid, position, hidden, name)
           VALUES (:uid, :newPosition, 0, '')
           ON CONFLICT(uid) DO UPDATE SET position = :newPosition""",
    )
    suspend fun updateGroupPosition(uid: String, newPosition: Int)

    @Transaction
    suspend fun updateCategoryPositions(positions: Map<String, Int>) {
        positions.forEach { (uid, pos) -> updateGroupPosition(uid, pos) }
    }

    @Query("DELETE FROM category_pref")
    suspend fun clearPrefs()

    @Query("SELECT * FROM category_pref")
    fun observePrefs(): Flow<List<CategoryPrefEntity>>

    @Query("DELETE FROM category_pref WHERE substr(uid, 1, length(:prefix)) = :prefix")
    suspend fun deletePrefsWithPrefix(prefix: String)
}

@Dao
interface ChannelDao {
    @Upsert
    suspend fun upsert(items: List<ChannelEntity>)

    @Query("DELETE FROM channel WHERE playlistId = :pid AND syncToken != :token")
    suspend fun deleteStale(pid: Long, token: Long)

    @Query("DELETE FROM channel WHERE playlistId = :pid")
    suspend fun deleteForPlaylist(pid: Long)

    @Query("SELECT * FROM channel WHERE uid = :uid")
    suspend fun get(uid: String): ChannelEntity?

    @Query("SELECT ch.* FROM channel ch WHERE ch.categoryUid = :categoryUid AND ch.hidden = 0 AND " + CH_UNLOCKED + " ORDER BY ch.position")
    fun byCategory(categoryUid: String, hide: Boolean): Flow<List<ChannelEntity>>

    /** Toutes les chaînes d'une catégorie, y compris masquées (écran de personnalisation). */
    @Query("SELECT * FROM channel WHERE categoryUid = :categoryUid ORDER BY position")
    fun byCategoryAll(categoryUid: String): Flow<List<ChannelEntity>>

    @Query(
        """SELECT ch.* FROM channel ch WHERE ch.playlistId IN (:pids) AND ch.hidden = 0
           AND ch.categoryUid NOT IN """ + HIDDEN_CATEGORIES + " AND " + CH_UNLOCKED + """
           ORDER BY ch.number, ch.playlistId, ch.position""",
    )
    fun allVisible(pids: List<Long>, hide: Boolean): Flow<List<ChannelEntity>>

    @Query(
        """SELECT ch.* FROM channel ch JOIN user_group_item g ON g.itemUid = ch.uid
           WHERE g.groupId = :groupId AND g.profileId = :profileId AND ch.playlistId IN (:pids) AND """ + CH_UNLOCKED + """
           ORDER BY g.position, g.addedAt""",
    )
    fun inUserGroup(groupId: Long, profileId: String, pids: List<Long>, hide: Boolean): Flow<List<ChannelEntity>>

    @Query(
        """SELECT ch.* FROM channel ch JOIN channel_history h ON h.channelUid = ch.uid
           WHERE ch.playlistId IN (:pids) AND h.profileId = :profileId AND """ + CH_UNLOCKED + """
           ORDER BY h.watchedAt DESC LIMIT 40""",
    )
    fun recent(pids: List<Long>, profileId: String, hide: Boolean): Flow<List<ChannelEntity>>

    @Query(
        """SELECT ch.* FROM channel ch WHERE ch.playlistId IN (:pids) AND ch.hidden = 0 AND ch.nameNorm LIKE '%' || :q || '%'
           AND """ + CH_UNLOCKED + """
           ORDER BY CASE
               WHEN ch.nameNorm = :q THEN 0
               WHEN (' ' || ch.nameNorm || ' ') GLOB '*[^a-z0-9]' || :q || '[^a-z0-9]*' THEN 1
               WHEN ch.nameNorm LIKE :q || '%' THEN 2
               WHEN (' ' || ch.nameNorm) GLOB '*[^a-z0-9]' || :q || '*' THEN 3
               ELSE 4 END, ch.number LIMIT :limit""",
    )
    suspend fun search(pids: List<Long>, q: String, limit: Int, hide: Boolean): List<ChannelEntity>

    /** Noms de toutes les chaînes visibles, pour la recherche tolérante aux fautes de frappe. */
    @Query("SELECT ch.uid AS uid, ch.nameNorm AS name FROM channel ch WHERE ch.playlistId IN (:pids) AND ch.hidden = 0 AND " + CH_UNLOCKED)
    suspend fun searchNames(pids: List<Long>, hide: Boolean): List<NameRow>

    /** Comme [searchNames], réémis quand les chaînes ou les verrous changent (cache de la recherche tolérante). */
    @Query("SELECT ch.uid AS uid, ch.nameNorm AS name FROM channel ch WHERE ch.playlistId IN (:pids) AND ch.hidden = 0 AND " + CH_UNLOCKED)
    fun observeSearchNames(pids: List<Long>, hide: Boolean): Flow<List<NameRow>>

    @Query("SELECT * FROM channel WHERE uid IN (:uids)")
    suspend fun byUids(uids: List<String>): List<ChannelEntity>

    /**
     * Autres versions d'une chaîne dans la même playlist (« TF1 FHD », « TF1 SD »…), pour baisser la qualité quand ça saccade.
     * Les chaînes masquées comptent (on masque souvent les doublons), pas celles verrouillées par le contrôle parental.
     */
    @Query("SELECT ch.* FROM channel ch WHERE ch.playlistId = :pid AND ch.matchKey = :key AND ch.uid != :uid AND " + CH_UNLOCKED + " ORDER BY ch.position")
    suspend fun sameChannel(pid: Long, key: String, uid: String, hide: Boolean): List<ChannelEntity>

    /** Chaînes visibles de ces catégories (espace sport). */
    @Query(
        """SELECT ch.* FROM channel ch WHERE ch.categoryUid IN (:cats) AND ch.hidden = 0
           AND ch.categoryUid NOT IN """ + HIDDEN_CATEGORIES + " AND " + CH_UNLOCKED + " ORDER BY ch.position",
    )
    suspend fun inCategories(cats: List<String>, hide: Boolean): List<ChannelEntity>

    /** Noms des chaînes visibles hors catégories masquées (premier tri de l'espace sport, fait dans le code). */
    @Query(
        """SELECT ch.uid AS uid, ch.nameNorm AS name FROM channel ch WHERE ch.playlistId IN (:pids) AND ch.hidden = 0
           AND ch.categoryUid NOT IN """ + HIDDEN_CATEGORIES + " AND " + CH_UNLOCKED,
    )
    suspend fun visibleNames(pids: List<Long>, hide: Boolean): List<NameRow>

    @Query("SELECT ch.* FROM channel ch WHERE ch.playlistId IN (:pids) AND ch.epgKey = :key AND ch.hidden = 0 AND " + CH_UNLOCKED + " LIMIT 1")
    suspend fun byEpgKey(pids: List<Long>, key: String, hide: Boolean): ChannelEntity?

    /** Chaîne de cette playlist portant ce numéro (saisie du numéro au clavier). */
    @Query("SELECT ch.* FROM channel ch WHERE ch.playlistId IN (:pids) AND ch.number = :number AND ch.hidden = 0 AND " + CH_UNLOCKED + " LIMIT 1")
    suspend fun byNumber(pids: List<Long>, number: Int, hide: Boolean): ChannelEntity?

    /** Toutes les chaînes des playlists (pour l'association manuelle au guide). */
    @Query("SELECT DISTINCT channelKey FROM program WHERE channelKey NOT LIKE 'stk:%' ORDER BY channelKey")
    suspend fun guideKeys(): List<String>

    @Query("SELECT COUNT(*) FROM channel WHERE playlistId = :pid")
    suspend fun count(pid: Long): Int

    @Query("SELECT COUNT(*) FROM channel WHERE playlistId = :pid")
    fun observeCount(pid: Long): Flow<Int>

    @Query(
        """SELECT DISTINCT ch.epgId, ch.matchKey FROM channel ch JOIN playlist p ON p.id = ch.playlistId
           WHERE p.type != 'STALKER'""",
    )
    suspend fun epgRefs(): List<ChannelEpgRef>

    @Query("UPDATE channel SET epgKey = LOWER(TRIM(epgId)) WHERE epgId != '' AND epgKey NOT LIKE 'stk:%'")
    suspend fun resetEpgKeys()

    @Query(
        """UPDATE channel SET epgKey = (SELECT m.xmlKey FROM epg_name_map m WHERE m.matchKey = channel.matchKey)
           WHERE epgKey NOT LIKE 'stk:%'
           AND EXISTS (SELECT 1 FROM epg_name_map m WHERE m.matchKey = channel.matchKey)
           AND NOT EXISTS (SELECT 1 FROM program p WHERE p.channelKey = channel.epgKey)""",
    )
    suspend fun remapEpgKeysByName()

    /** Applique les associations au guide choisies à la main (après chaque recalcul automatique). */
    @Query(
        """UPDATE channel SET epgKey = (SELECT p.epgKey FROM channel_pref p WHERE p.uid = channel.uid)
           WHERE uid IN (SELECT uid FROM channel_pref WHERE epgKey != '')""",
    )
    suspend fun applyEpgOverrides()
}

@Dao
interface MovieDao {
    @Upsert
    suspend fun upsert(items: List<MovieEntity>)

    @Query("DELETE FROM movie WHERE playlistId = :pid AND syncToken != :token")
    suspend fun deleteStale(pid: Long, token: Long)

    @Query("DELETE FROM movie WHERE playlistId = :pid")
    suspend fun deleteForPlaylist(pid: Long)

    @Query("SELECT * FROM movie WHERE uid = :uid")
    suspend fun get(uid: String): MovieEntity?

    @Query("SELECT * FROM movie WHERE uid = :uid")
    fun observe(uid: String): Flow<MovieEntity?>

    @Query("SELECT m.* FROM movie m WHERE m.categoryUid = :categoryUid AND (:hide = 0 OR m.categoryUid NOT IN " + LOCKED_CATEGORIES + ") ORDER BY m.position")
    fun byCategory(categoryUid: String, hide: Boolean): Flow<List<MovieEntity>>

    @Query("SELECT COUNT(*) FROM movie WHERE categoryUid = :categoryUid")
    suspend fun countInCategory(categoryUid: String): Int

    @Query("SELECT COUNT(*) FROM movie WHERE playlistId = :pid")
    suspend fun count(pid: Long): Int

    @Query(
        """SELECT m.* FROM movie m WHERE m.playlistId IN (:pids) AND m.categoryUid NOT IN """ + HIDDEN_CATEGORIES + """
           AND (:hide = 0 OR m.categoryUid NOT IN """ + LOCKED_CATEGORIES + """) ORDER BY m.addedAt DESC, m.position LIMIT 200""",
    )
    fun recentlyAdded(pids: List<Long>, hide: Boolean): Flow<List<MovieEntity>>

    /** Les mieux notés (note du fournisseur ou de TMDB), hors catégories masquées. */
    @Query(
        """SELECT m.* FROM movie m WHERE m.playlistId IN (:pids) AND m.rating > 0 AND m.categoryUid NOT IN """ + HIDDEN_CATEGORIES + """
           AND (:hide = 0 OR m.categoryUid NOT IN """ + LOCKED_CATEGORIES + """) ORDER BY m.rating DESC, m.addedAt DESC LIMIT 200""",
    )
    fun topRated(pids: List<Long>, hide: Boolean): Flow<List<MovieEntity>>

    /** Films visibles dont le nom contient [pattern] (rapprochement avec les titres de TMDB). */
    @Query(
        """SELECT m.* FROM movie m WHERE m.playlistId IN (:pids) AND m.nameNorm LIKE :pattern AND m.categoryUid NOT IN """ + HIDDEN_CATEGORIES + """
           AND (:hide = 0 OR m.categoryUid NOT IN """ + LOCKED_CATEGORIES + """) LIMIT 50""",
    )
    suspend fun matchingName(pids: List<Long>, hide: Boolean, pattern: String): List<MovieEntity>

    /** Ce qu'il faut pour les genres automatiques : genre du film et nom de sa catégorie (les plus récents d'abord). */
    @Query(
        """SELECT m.uid AS uid, m.genre AS genre, IFNULL(c.name, '') AS categoryName FROM movie m LEFT JOIN category c ON c.uid = m.categoryUid
           WHERE m.playlistId IN (:pids) AND m.categoryUid NOT IN """ + HIDDEN_CATEGORIES + """
           AND (:hide = 0 OR m.categoryUid NOT IN """ + LOCKED_CATEGORIES + """) ORDER BY m.addedAt DESC, m.position""",
    )
    fun genreSources(pids: List<Long>, hide: Boolean): Flow<List<GenreSource>>

    @Query("SELECT * FROM movie WHERE uid IN (:uids)")
    suspend fun byUids(uids: List<String>): List<MovieEntity>

    @Query(
        """SELECT m.* FROM movie m JOIN user_group_item g ON g.itemUid = m.uid
           WHERE g.groupId = :groupId AND g.profileId = :profileId AND m.playlistId IN (:pids)
           AND (:hide = 0 OR m.categoryUid NOT IN """ + LOCKED_CATEGORIES + ") ORDER BY g.addedAt DESC",
    )
    fun inUserGroup(groupId: Long, profileId: String, pids: List<Long>, hide: Boolean): Flow<List<MovieEntity>>

    @Query(
        """SELECT m.* FROM movie m JOIN watch_progress w ON w.uid = m.uid
           WHERE m.playlistId IN (:pids) AND w.profileId = :profileId
           AND w.positionMs > 0 AND w.positionMs < w.durationMs * 0.95
           AND (:hide = 0 OR m.categoryUid NOT IN """ + LOCKED_CATEGORIES + """)
           ORDER BY w.updatedAt DESC LIMIT 50""",
    )
    fun continueWatching(pids: List<Long>, profileId: String, hide: Boolean): Flow<List<MovieEntity>>

    @Query(
        """SELECT m.* FROM movie m WHERE m.playlistId IN (:pids) AND m.nameNorm LIKE '%' || :q || '%'
           AND (:hide = 0 OR m.categoryUid NOT IN """ + LOCKED_CATEGORIES + ") ORDER BY m.name LIMIT :limit",
    )
    suspend fun search(pids: List<Long>, q: String, limit: Int, hide: Boolean): List<MovieEntity>

    /** Noms de tous les films visibles, pour la recherche tolérante aux fautes de frappe. */
    @Query("SELECT m.uid AS uid, m.nameNorm AS name FROM movie m WHERE m.playlistId IN (:pids) AND (:hide = 0 OR m.categoryUid NOT IN " + LOCKED_CATEGORIES + ")")
    suspend fun searchNames(pids: List<Long>, hide: Boolean): List<NameRow>

    /** Comme [searchNames], réémis quand les films ou les verrous changent (cache de la recherche tolérante). */
    @Query("SELECT m.uid AS uid, m.nameNorm AS name FROM movie m WHERE m.playlistId IN (:pids) AND (:hide = 0 OR m.categoryUid NOT IN " + LOCKED_CATEGORIES + ")")
    fun observeSearchNames(pids: List<Long>, hide: Boolean): Flow<List<NameRow>>

    @Query(
        """SELECT m.* FROM movie m WHERE m.playlistId IN (:pids)
           AND (m.actors LIKE '%' || :name || '%' OR m.director LIKE '%' || :name || '%')
           AND (:hide = 0 OR m.categoryUid NOT IN """ + LOCKED_CATEGORIES + ") ORDER BY m.year DESC, m.addedAt DESC LIMIT :limit",
    )
    suspend fun findByPerson(pids: List<Long>, name: String, limit: Int = 50, hide: Boolean = false): List<MovieEntity>

    @Query(
        """UPDATE movie SET plot = :plot, backdrop = :backdrop, actors = :actors, director = :director,
           genre = :genre, durationSecs = :durationSecs, year = CASE WHEN :year = '' THEN year ELSE :year END,
           releaseDate = CASE WHEN :releaseDate = '' THEN releaseDate ELSE :releaseDate END,
           poster = CASE WHEN :poster = '' THEN poster ELSE :poster END WHERE uid = :uid""",
    )
    suspend fun updateDetails(
        uid: String, plot: String, backdrop: String, actors: String, director: String,
        genre: String, durationSecs: Int, year: String, poster: String, releaseDate: String = "",
    )
}

@Dao
interface SeriesDao {
    @Upsert
    suspend fun upsert(items: List<SeriesEntity>)

    @Query("DELETE FROM series WHERE playlistId = :pid AND syncToken != :token")
    suspend fun deleteStale(pid: Long, token: Long)

    @Query("DELETE FROM series WHERE playlistId = :pid")
    suspend fun deleteForPlaylist(pid: Long)

    @Query("SELECT * FROM series WHERE uid = :uid")
    suspend fun get(uid: String): SeriesEntity?

    @Query("SELECT * FROM series WHERE uid = :uid")
    fun observe(uid: String): Flow<SeriesEntity?>

    @Query("SELECT s.* FROM series s WHERE s.categoryUid = :categoryUid AND (:hide = 0 OR s.categoryUid NOT IN " + LOCKED_CATEGORIES + ") ORDER BY s.position")
    fun byCategory(categoryUid: String, hide: Boolean): Flow<List<SeriesEntity>>

    @Query("SELECT COUNT(*) FROM series WHERE categoryUid = :categoryUid")
    suspend fun countInCategory(categoryUid: String): Int

    @Query("SELECT COUNT(*) FROM series WHERE playlistId = :pid")
    suspend fun count(pid: Long): Int

    @Query(
        """SELECT s.* FROM series s WHERE s.playlistId IN (:pids) AND s.categoryUid NOT IN """ + HIDDEN_CATEGORIES + """
           AND (:hide = 0 OR s.categoryUid NOT IN """ + LOCKED_CATEGORIES + """) ORDER BY s.addedAt DESC, s.position LIMIT 200""",
    )
    fun recentlyAdded(pids: List<Long>, hide: Boolean): Flow<List<SeriesEntity>>

    /** Séries visibles dont le nom contient [pattern] (rapprochement avec les titres de TMDB). */
    @Query(
        """SELECT s.* FROM series s WHERE s.playlistId IN (:pids) AND s.nameNorm LIKE :pattern AND s.categoryUid NOT IN """ + HIDDEN_CATEGORIES + """
           AND (:hide = 0 OR s.categoryUid NOT IN """ + LOCKED_CATEGORIES + """) LIMIT 50""",
    )
    suspend fun matchingName(pids: List<Long>, hide: Boolean, pattern: String): List<SeriesEntity>

    /** Les mieux notées (note du fournisseur ou de TMDB), hors catégories masquées. */
    @Query(
        """SELECT s.* FROM series s WHERE s.playlistId IN (:pids) AND s.rating > 0 AND s.categoryUid NOT IN """ + HIDDEN_CATEGORIES + """
           AND (:hide = 0 OR s.categoryUid NOT IN """ + LOCKED_CATEGORIES + """) ORDER BY s.rating DESC, s.addedAt DESC LIMIT 200""",
    )
    fun topRated(pids: List<Long>, hide: Boolean): Flow<List<SeriesEntity>>

    /** Ce qu'il faut pour les genres automatiques : genre de la série et nom de sa catégorie (les plus récentes d'abord). */
    @Query(
        """SELECT s.uid AS uid, s.genre AS genre, IFNULL(c.name, '') AS categoryName FROM series s LEFT JOIN category c ON c.uid = s.categoryUid
           WHERE s.playlistId IN (:pids) AND s.categoryUid NOT IN """ + HIDDEN_CATEGORIES + """
           AND (:hide = 0 OR s.categoryUid NOT IN """ + LOCKED_CATEGORIES + """) ORDER BY s.addedAt DESC, s.position""",
    )
    fun genreSources(pids: List<Long>, hide: Boolean): Flow<List<GenreSource>>

    @Query("SELECT * FROM series WHERE uid IN (:uids)")
    suspend fun byUids(uids: List<String>): List<SeriesEntity>

    @Query(
        """SELECT s.* FROM series s JOIN user_group_item g ON g.itemUid = s.uid
           WHERE g.groupId = :groupId AND g.profileId = :profileId AND s.playlistId IN (:pids)
           AND (:hide = 0 OR s.categoryUid NOT IN """ + LOCKED_CATEGORIES + ") ORDER BY g.addedAt DESC",
    )
    fun inUserGroup(groupId: Long, profileId: String, pids: List<Long>, hide: Boolean): Flow<List<SeriesEntity>>

    @Query(
        """SELECT s.* FROM series s JOIN
           (SELECT w.parentUid AS puid, MAX(w.updatedAt) AS lastAt FROM watch_progress w
            WHERE w.profileId = :profileId AND w.kind = 'SERIES' AND w.parentUid != '' AND w.positionMs > 0
            GROUP BY w.parentUid) r ON r.puid = s.uid
           WHERE s.playlistId IN (:pids) AND (:hide = 0 OR s.categoryUid NOT IN """ + LOCKED_CATEGORIES + """)
           ORDER BY r.lastAt DESC LIMIT 50""",
    )
    fun continueWatching(pids: List<Long>, profileId: String, hide: Boolean): Flow<List<SeriesEntity>>

    @Query(
        """SELECT s.* FROM series s WHERE s.playlistId IN (:pids) AND s.nameNorm LIKE '%' || :q || '%'
           AND (:hide = 0 OR s.categoryUid NOT IN """ + LOCKED_CATEGORIES + ") ORDER BY s.name LIMIT :limit",
    )
    suspend fun search(pids: List<Long>, q: String, limit: Int, hide: Boolean): List<SeriesEntity>

    /** Noms de toutes les séries visibles, pour la recherche tolérante aux fautes de frappe. */
    @Query("SELECT s.uid AS uid, s.nameNorm AS name FROM series s WHERE s.playlistId IN (:pids) AND (:hide = 0 OR s.categoryUid NOT IN " + LOCKED_CATEGORIES + ")")
    suspend fun searchNames(pids: List<Long>, hide: Boolean): List<NameRow>

    /** Comme [searchNames], réémis quand les séries ou les verrous changent (cache de la recherche tolérante). */
    @Query("SELECT s.uid AS uid, s.nameNorm AS name FROM series s WHERE s.playlistId IN (:pids) AND (:hide = 0 OR s.categoryUid NOT IN " + LOCKED_CATEGORIES + ")")
    fun observeSearchNames(pids: List<Long>, hide: Boolean): Flow<List<NameRow>>

    @Query(
        """SELECT s.* FROM series s WHERE s.playlistId IN (:pids)
           AND s.actors LIKE '%' || :name || '%'
           AND (:hide = 0 OR s.categoryUid NOT IN """ + LOCKED_CATEGORIES + ") ORDER BY s.year DESC, s.addedAt DESC LIMIT :limit",
    )
    suspend fun findByPerson(pids: List<Long>, name: String, limit: Int = 50, hide: Boolean = false): List<SeriesEntity>

    @Query(
        """UPDATE series SET plot = CASE WHEN :plot = '' THEN plot ELSE :plot END,
           backdrop = CASE WHEN :backdrop = '' THEN backdrop ELSE :backdrop END,
           actors = CASE WHEN :actors = '' THEN actors ELSE :actors END,
           genre = CASE WHEN :genre = '' THEN genre ELSE :genre END WHERE uid = :uid""",
    )
    suspend fun updateDetails(uid: String, plot: String, backdrop: String, actors: String, genre: String)

    @Upsert
    suspend fun upsertEpisodes(items: List<EpisodeEntity>)

    @Query("DELETE FROM episode WHERE seriesUid = :seriesUid AND syncToken != :token")
    suspend fun deleteStaleEpisodes(seriesUid: String, token: Long)

    @Query("DELETE FROM episode WHERE playlistId = :pid AND syncToken != :token AND seriesUid IN (SELECT uid FROM series WHERE playlistId = :pid)")
    suspend fun deleteStaleEpisodesForPlaylist(pid: Long, token: Long)

    @Query("DELETE FROM episode WHERE playlistId = :pid")
    suspend fun deleteEpisodesForPlaylist(pid: Long)

    @Query("SELECT * FROM episode WHERE seriesUid = :seriesUid ORDER BY season, episode")
    fun episodes(seriesUid: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episode WHERE seriesUid = :seriesUid ORDER BY season, episode")
    suspend fun episodesList(seriesUid: String): List<EpisodeEntity>

    /**
     * Séries suivies, tous profils : en favoris, ou regardées depuis [since]. Jamais celles verrouillées par le
     * contrôle parental (pour les alertes de nouvel épisode, visibles sur l'écran de verrouillage).
     */
    @Query(
        """SELECT s.* FROM series s WHERE s.categoryUid NOT IN """ + LOCKED_CATEGORIES + """ AND (
               s.uid IN (SELECT g.itemUid FROM user_group_item g WHERE g.groupId = :favorites AND g.kind = 'SERIES')
               OR s.uid IN (SELECT w.parentUid FROM watch_progress w WHERE w.kind = 'SERIES' AND w.parentUid != '' AND w.updatedAt > :since))
           ORDER BY s.name LIMIT :limit""",
    )
    suspend fun followed(favorites: Long, since: Long, limit: Int): List<SeriesEntity>

    @Query("SELECT * FROM episode WHERE uid = :uid")
    suspend fun episode(uid: String): EpisodeEntity?

    /** Épisodes des séries suivies par [profileId] (favoris ou commencées), avec leur progression éventuelle. */
    @Query(
        """SELECT e.seriesUid AS seriesUid, e.uid AS episodeUid, e.season AS season, e.episode AS episode,
           e.title AS episodeTitle, COALESCE(w.positionMs, 0) AS positionMs,
           COALESCE(w.durationMs, 0) AS durationMs, COALESCE(w.updatedAt, 0) AS progressUpdatedAt
           FROM episode e
           JOIN series s ON s.uid = e.seriesUid
           LEFT JOIN watch_progress w ON w.profileId = :profileId AND w.uid = e.uid
           WHERE s.playlistId IN (:pids) AND (:hide = 0 OR s.categoryUid NOT IN """ + LOCKED_CATEGORIES + """)
           AND (
               s.uid IN (SELECT w2.parentUid FROM watch_progress w2 WHERE w2.profileId = :profileId AND w2.kind = 'SERIES' AND w2.parentUid != '')
               OR s.uid IN (SELECT g.itemUid FROM user_group_item g WHERE g.profileId = :profileId AND g.groupId = :favorites AND g.kind = 'SERIES')
           )
           ORDER BY e.seriesUid, e.season, e.episode""",
    )
    fun followedEpisodes(pids: List<Long>, profileId: String, favorites: Long, hide: Boolean): Flow<List<FollowedEpisodeRow>>
}

data class FollowedEpisodeRow(
    val seriesUid: String,
    val episodeUid: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String,
    val positionMs: Long,
    val durationMs: Long,
    val progressUpdatedAt: Long,
)

@Dao
interface ProgramDao {
    @Insert
    suspend fun insertAll(items: List<ProgramEntity>)

    @Query("SELECT * FROM program WHERE channelKey = :key AND stop > :from AND start < :to ORDER BY start")
    suspend fun range(key: String, from: Long, to: Long): List<ProgramEntity>

    @Query("SELECT * FROM program WHERE channelKey = :key AND stop > :now ORDER BY start LIMIT :n")
    suspend fun upcoming(key: String, now: Long, n: Int): List<ProgramEntity>

    @Query("SELECT * FROM program WHERE channelKey IN (:keys) AND start <= :now AND stop > :now")
    suspend fun nowFor(keys: List<String>, now: Long): List<ProgramEntity>

    @Query("SELECT * FROM program WHERE channelKey IN (:keys) AND stop > :from AND start < :to ORDER BY start")
    suspend fun window(keys: List<String>, from: Long, to: Long): List<ProgramEntity>

    @Query("SELECT * FROM program WHERE id = :id")
    suspend fun get(id: Long): ProgramEntity?

    @Query("DELETE FROM program WHERE sourceKey = :sourceKey AND generation != :generation")
    suspend fun deleteOtherGenerations(sourceKey: String, generation: Long)

    /** Génération partielle d'un import interrompu : l'ancienne reste en place. */
    @Query("DELETE FROM program WHERE sourceKey = :sourceKey AND generation = :generation")
    suspend fun deleteGeneration(sourceKey: String, generation: Long)

    @Query("DELETE FROM program WHERE sourceKey = :sourceKey")
    suspend fun deleteSource(sourceKey: String)

    @Query("DELETE FROM program WHERE stop < :before")
    suspend fun deleteBefore(before: Long)

    @Query("DELETE FROM program WHERE sourceKey = :sourceKey AND stop > :from")
    suspend fun deleteSourceAfter(sourceKey: String, from: Long)

    @Query(
        """SELECT channelKey, start, stop FROM program
           WHERE channelKey IN (:keys) AND sourceKey IN (:sources) AND stop > :from AND start < :to""",
    )
    suspend fun slots(keys: List<String>, sources: List<String>, from: Long, to: Long): List<ProgramSlot>

    @Query("SELECT COUNT(*) FROM program WHERE sourceKey = :sourceKey")
    suspend fun countSource(sourceKey: String): Int

    @Query(
        """SELECT * FROM program WHERE title LIKE '%' || :q || '%' AND stop > :from
           ORDER BY start LIMIT :limit""",
    )
    suspend fun search(q: String, from: Long, limit: Int): List<ProgramEntity>

    /** Diffusions à venir d'une chaîne (pour les enregistrements de série). */
    @Query("SELECT * FROM program WHERE channelKey = :key AND start > :from ORDER BY start LIMIT :limit")
    suspend fun future(key: String, from: Long, limit: Int): List<ProgramEntity>
}

@Dao
interface EpgDao {
    @Query("SELECT * FROM epg_source ORDER BY priority, auto DESC, id")
    fun observeSources(): Flow<List<EpgSourceEntity>>

    @Query("SELECT * FROM epg_source ORDER BY priority, auto DESC, id")
    suspend fun sources(): List<EpgSourceEntity>

    @Query("SELECT * FROM epg_source WHERE playlistId = :pid AND auto = 1")
    suspend fun autoSourcesFor(pid: Long): List<EpgSourceEntity>

    @Insert
    suspend fun insertSource(s: EpgSourceEntity): Long

    @Update
    suspend fun updateSource(s: EpgSourceEntity)

    @Query("DELETE FROM epg_source WHERE id = :id")
    suspend fun deleteSource(id: Long)

    @Query("DELETE FROM epg_source WHERE playlistId = :pid")
    suspend fun deleteSourcesFor(pid: Long)

    @Upsert
    suspend fun upsertNameMap(items: List<EpgNameMapEntity>)
}

@Dao
interface UserGroupDao {
    /** Groupe Favoris (commun) + groupes du profil. */
    @Query("SELECT * FROM user_group WHERE id = 1 OR profileId = :profileId ORDER BY position, id")
    fun observeGroups(profileId: String): Flow<List<UserGroupEntity>>

    @Query("SELECT * FROM user_group WHERE id = 1 OR profileId = :profileId ORDER BY position, id")
    suspend fun groups(profileId: String): List<UserGroupEntity>

    @Query("SELECT * FROM user_group ORDER BY position, id")
    suspend fun allGroups(): List<UserGroupEntity>

    @Query("SELECT * FROM user_group WHERE id = :id")
    suspend fun group(id: Long): UserGroupEntity?

    @Query("SELECT * FROM user_group WHERE profileId = :profileId AND syncId = :syncId LIMIT 1")
    suspend fun bySyncId(profileId: String, syncId: String): UserGroupEntity?

    @Insert
    suspend fun insertGroup(g: UserGroupEntity): Long

    @Update
    suspend fun updateGroup(g: UserGroupEntity)

    @Query("UPDATE user_group SET position = :newPosition, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun updateGroupPosition(id: Long, newPosition: Int, updatedAt: Long = System.currentTimeMillis())

    @Transaction
    suspend fun updateUserGroupPositions(positions: Map<Long, Int>) {
        val now = System.currentTimeMillis()
        positions.forEach { (id, pos) -> updateGroupPosition(id, pos, now) }
    }

    @Query("DELETE FROM user_group WHERE id = :id")
    suspend fun deleteGroup(id: Long)

    @Query("DELETE FROM user_group_item WHERE groupId = :id")
    suspend fun clearGroup(id: Long)

    @Query("SELECT * FROM user_group_item WHERE groupId = :id")
    suspend fun itemsOf(id: Long): List<UserGroupItemEntity>

    @Upsert
    suspend fun upsertItem(item: UserGroupItemEntity)

    @Upsert
    suspend fun upsertItems(items: List<UserGroupItemEntity>)

    @Query("SELECT * FROM user_group_item WHERE profileId = :profileId AND groupId = :groupId AND contentKey = :key")
    suspend fun item(profileId: String, groupId: Long, key: String): UserGroupItemEntity?

    @Query("DELETE FROM user_group_item WHERE profileId = :profileId AND groupId = :groupId AND contentKey = :key")
    suspend fun removeItem(profileId: String, groupId: Long, key: String)

    @Query("SELECT groupId FROM user_group_item WHERE profileId = :profileId AND itemUid = :itemUid")
    fun groupsOf(profileId: String, itemUid: String): Flow<List<Long>>

    @Query("SELECT groupId FROM user_group_item WHERE profileId = :profileId AND itemUid = :itemUid")
    suspend fun groupsOfNow(profileId: String, itemUid: String): List<Long>

    @Query("SELECT * FROM user_group_item")
    suspend fun allItems(): List<UserGroupItemEntity>

    @Query("SELECT * FROM user_group_item WHERE dirty = 1 LIMIT :limit")
    suspend fun dirtyItems(limit: Int): List<UserGroupItemEntity>

    @Query("UPDATE user_group_item SET dirty = 0 WHERE profileId = :profileId AND groupId = :groupId AND contentKey = :key AND updatedAt = :updatedAt")
    suspend fun markItemClean(profileId: String, groupId: Long, key: String, updatedAt: Long)

    @Query("SELECT * FROM user_group WHERE dirty = 1 AND id != 1")
    suspend fun dirtyGroups(): List<UserGroupEntity>

    @Query("UPDATE user_group SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markGroupClean(id: Long, updatedAt: Long)

    @Query("DELETE FROM user_group_item")
    suspend fun clearAllItems()

    @Query("DELETE FROM user_group WHERE id != 1")
    suspend fun deleteCustomGroups()

    @Query("SELECT COALESCE(MAX(position), 0) FROM user_group_item WHERE profileId = :profileId AND groupId = :groupId")
    suspend fun maxPosition(profileId: String, groupId: Long): Int

    @Query("SELECT COALESCE(MAX(position), 0) FROM user_group WHERE profileId = :profileId")
    suspend fun maxGroupPosition(profileId: String): Int

    @Query("SELECT * FROM user_group_item WHERE substr(contentKey, 1, 13) = :keyPrefix")
    suspend fun itemsForPlaylistKey(keyPrefix: String): List<UserGroupItemEntity>

    /** Rattache les éléments reçus d'autres appareils (ou d'une ancienne playlist) à la playlist locale de même clé. */
    @Query(
        """UPDATE user_group_item SET itemUid = :pidPrefix || substr(contentKey, 14)
           WHERE substr(contentKey, 1, 13) = :keyPrefix AND itemUid != :pidPrefix || substr(contentKey, 14)""",
    )
    suspend fun attach(keyPrefix: String, pidPrefix: String)

    @Query("UPDATE user_group_item SET itemUid = '' WHERE substr(itemUid, 1, length(:pidPrefix)) = :pidPrefix")
    suspend fun detach(pidPrefix: String)

    @Query("DELETE FROM user_group_item WHERE profileId = :profileId")
    suspend fun deleteProfileItems(profileId: String)

    @Query("DELETE FROM user_group WHERE profileId = :profileId")
    suspend fun deleteProfileGroups(profileId: String)

    @Query("UPDATE OR REPLACE user_group_item SET profileId = :to, dirty = 1 WHERE profileId = :from")
    suspend fun moveItems(from: String, to: String)

    @Query("UPDATE user_group SET profileId = :to, dirty = 1 WHERE profileId = :from")
    suspend fun moveGroups(from: String, to: String)

    /** Tout renvoyer au compte (connexion à un autre compte). */
    @Query("UPDATE user_group_item SET dirty = 1")
    suspend fun markAllItemsDirty()

    @Query("UPDATE user_group SET dirty = 1 WHERE id != 1")
    suspend fun markAllGroupsDirty()
}

@Dao
interface TombstoneDao {
    @Upsert
    suspend fun add(t: SyncTombstoneEntity)

    @Query("SELECT * FROM sync_tombstone ORDER BY deletedAt LIMIT :limit")
    suspend fun pending(limit: Int): List<SyncTombstoneEntity>

    @Query("DELETE FROM sync_tombstone WHERE entity = :entity AND profileId = :profileId AND `key` = :key AND deletedAt = :deletedAt")
    suspend fun done(entity: String, profileId: String, key: String, deletedAt: Long)

    @Query("DELETE FROM sync_tombstone WHERE entity = :entity AND profileId = :profileId AND `key` = :key")
    suspend fun remove(entity: String, profileId: String, key: String)

    @Query("DELETE FROM sync_tombstone WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: String)

    @Query("DELETE FROM sync_tombstone")
    suspend fun clear()
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE deleted = 0 ORDER BY position, name")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profile WHERE deleted = 0 ORDER BY position, name")
    suspend fun all(): List<ProfileEntity>

    /** Y compris les profils supprimés (pierres tombales en attente d'envoi). */
    @Query("SELECT * FROM profile")
    suspend fun allWithDeleted(): List<ProfileEntity>

    @Query("SELECT * FROM profile WHERE id = :id")
    suspend fun get(id: String): ProfileEntity?

    @Query("SELECT * FROM profile WHERE dirty = 1")
    suspend fun dirty(): List<ProfileEntity>

    @Upsert
    suspend fun upsert(p: ProfileEntity)

    @Query("UPDATE profile SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query("DELETE FROM profile WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("SELECT COALESCE(MAX(position), -1) FROM profile WHERE deleted = 0")
    suspend fun maxPosition(): Int

    @Query("UPDATE profile SET dirty = 1")
    suspend fun markAllDirty()

    /** Le profil a-t-il déjà servi (progression ou favoris) ? */
    @Query(
        """SELECT (SELECT COUNT(*) FROM watch_progress WHERE profileId = :id) +
           (SELECT COUNT(*) FROM user_group_item WHERE profileId = :id) +
           (SELECT COUNT(*) FROM user_group WHERE profileId = :id) > 0""",
    )
    suspend fun hasData(id: String): Boolean
}

@Dao
interface ProgressDao {
    @Upsert
    suspend fun upsert(p: WatchProgressEntity)

    @Upsert
    suspend fun upsertAll(items: List<WatchProgressEntity>)

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND uid = :uid")
    suspend fun get(profileId: String, uid: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND contentKey = :key")
    suspend fun byKey(profileId: String, key: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND uid = :uid")
    fun observe(profileId: String, uid: String): Flow<WatchProgressEntity?>

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND parentUid = :seriesUid")
    fun forSeries(profileId: String, seriesUid: String): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND parentUid = :seriesUid ORDER BY updatedAt DESC LIMIT 1")
    suspend fun lastForSeries(profileId: String, seriesUid: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId")
    suspend fun forProfile(profileId: String): List<WatchProgressEntity>

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId")
    fun observeForProfile(profileId: String): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE dirty = 1 LIMIT :limit")
    suspend fun dirty(limit: Int): List<WatchProgressEntity>

    @Query("UPDATE watch_progress SET dirty = 0 WHERE profileId = :profileId AND contentKey = :key AND updatedAt = :updatedAt")
    suspend fun markClean(profileId: String, key: String, updatedAt: Long)

    @Query("SELECT * FROM watch_progress WHERE substr(contentKey, 1, 13) = :keyPrefix")
    suspend fun forPlaylistKey(keyPrefix: String): List<WatchProgressEntity>

    /** Rattache les progressions reçues d'autres appareils à la playlist locale de même clé. */
    @Query(
        """UPDATE watch_progress SET uid = :pidPrefix || substr(contentKey, 14),
           parentUid = CASE WHEN parentKey = '' THEN '' ELSE :pidPrefix || substr(parentKey, 14) END
           WHERE substr(contentKey, 1, 13) = :keyPrefix AND uid != :pidPrefix || substr(contentKey, 14)""",
    )
    suspend fun attach(keyPrefix: String, pidPrefix: String)

    @Query("UPDATE watch_progress SET uid = '', parentUid = '' WHERE substr(uid, 1, length(:pidPrefix)) = :pidPrefix")
    suspend fun detach(pidPrefix: String)

    @Query("DELETE FROM watch_progress WHERE profileId = :profileId AND contentKey = :key")
    suspend fun delete(profileId: String, key: String)

    @Query("DELETE FROM watch_progress WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: String)

    @Upsert
    suspend fun touchChannel(h: ChannelHistoryEntity)

    @Query("SELECT * FROM channel_history WHERE profileId = :profileId")
    suspend fun history(profileId: String): List<ChannelHistoryEntity>

    @Upsert
    suspend fun upsertHistory(items: List<ChannelHistoryEntity>)

    @Query("DELETE FROM channel_history WHERE profileId = :profileId")
    suspend fun deleteHistory(profileId: String)

    /** Fusion de deux profils : la progression la plus récente l'emporte. */
    @Query(
        """INSERT OR REPLACE INTO watch_progress (profileId, contentKey, uid, kind, parentUid, parentKey, positionMs, durationMs, updatedAt, dirty)
           SELECT :to, f.contentKey, f.uid, f.kind, f.parentUid, f.parentKey, f.positionMs, f.durationMs, f.updatedAt, 1
           FROM watch_progress f WHERE f.profileId = :from AND NOT EXISTS
           (SELECT 1 FROM watch_progress t WHERE t.profileId = :to AND t.contentKey = f.contentKey AND t.updatedAt >= f.updatedAt)""",
    )
    suspend fun mergeInto(from: String, to: String)

    @Query("INSERT OR REPLACE INTO channel_history (profileId, channelUid, watchedAt) SELECT :to, channelUid, watchedAt FROM channel_history WHERE profileId = :from")
    suspend fun mergeHistoryInto(from: String, to: String)

    @Query("UPDATE watch_progress SET dirty = 1")
    suspend fun markAllDirty()

    @Query("DELETE FROM watch_progress WHERE (:profileId IS NULL OR profileId = :profileId)")
    suspend fun clearWatchProgress(profileId: String? = null)

    @Query("DELETE FROM channel_history WHERE (:profileId IS NULL OR profileId = :profileId)")
    suspend fun clearChannelHistory(profileId: String? = null)

    @Query(
        """DELETE FROM channel_history WHERE (:profileId IS NULL OR profileId = :profileId)
           AND channelUid IN (SELECT uid FROM channel WHERE categoryUid = :categoryUid)""",
    )
    suspend fun deleteChannelHistoryByCategory(categoryUid: String, profileId: String? = null)

    @Query(
        """DELETE FROM watch_progress WHERE (:profileId IS NULL OR profileId = :profileId)
           AND (
               uid IN (SELECT uid FROM movie WHERE categoryUid = :categoryUid)
               OR parentUid IN (SELECT uid FROM series WHERE categoryUid = :categoryUid)
           )""",
    )
    suspend fun deleteWatchProgressByCategory(categoryUid: String, profileId: String? = null)

    @Query(
        """SELECT contentKey FROM watch_progress WHERE (:profileId IS NULL OR profileId = :profileId)
           AND (
               uid IN (SELECT uid FROM movie WHERE categoryUid = :categoryUid)
               OR parentUid IN (SELECT uid FROM series WHERE categoryUid = :categoryUid)
           )""",
    )
    suspend fun contentKeysByCategory(categoryUid: String, profileId: String? = null): List<String>

    @Query(
        """DELETE FROM channel_history WHERE (:profileId IS NULL OR profileId = :profileId)
           AND channelUid IN (
               SELECT ch.uid FROM channel ch LEFT JOIN category c ON c.uid = ch.categoryUid
               WHERE (c.adult = 1 OR c.uid IN (SELECT lp.uid FROM category_pref lp WHERE lp.locked = 1)
                      OR LOWER(c.name) LIKE '%xxx%' OR LOWER(c.name) LIKE '%adult%' OR LOWER(c.name) LIKE '%porn%'
                      OR LOWER(c.name) LIKE '%18+%' OR LOWER(c.name) LIKE '%+18%' OR LOWER(c.name) LIKE '%erot%'
                      OR LOWER(c.name) LIKE '%charme%' OR LOWER(c.name) LIKE '%sensuel%' OR LOWER(c.name) LIKE '%dorcel%'
                      OR LOWER(c.name) LIKE '%playboy%' OR LOWER(c.name) LIKE '%brazzers%' OR LOWER(c.name) LIKE '%hustler%'
                      OR LOWER(c.name) LIKE '%penthouse%' OR LOWER(c.name) LIKE '%pink x%' OR LOWER(c.name) LIKE '%xxl%'
                      OR LOWER(c.name) LIKE '%colmax%' OR LOWER(c.name) LIKE '%vivid%' OR LOWER(c.name) LIKE '%libertin%')
                  OR (LOWER(ch.name) LIKE '%xxx%' OR LOWER(ch.name) LIKE '%adult%' OR LOWER(ch.name) LIKE '%porn%'
                      OR LOWER(ch.name) LIKE '%18+%' OR LOWER(ch.name) LIKE '%+18%' OR LOWER(ch.name) LIKE '%dorcel%'
                      OR LOWER(ch.name) LIKE '%playboy%' OR LOWER(ch.name) LIKE '%brazzers%' OR LOWER(ch.name) LIKE '%hustler%'
                      OR LOWER(ch.name) LIKE '%penthouse%' OR LOWER(ch.name) LIKE '%pink x%' OR LOWER(ch.name) LIKE '%xxl%'
                      OR LOWER(ch.name) LIKE '%erot%' OR LOWER(ch.name) LIKE '%colmax%' OR LOWER(ch.name) LIKE '%vivid%'
                      OR LOWER(ch.name) LIKE '%libertin%' OR LOWER(ch.name) LIKE '%redlight%' OR LOWER(ch.name) LIKE '%private%')
           )""",
    )
    suspend fun deleteChannelHistoryAdults(profileId: String? = null)

    @Query(
        """DELETE FROM watch_progress WHERE (:profileId IS NULL OR profileId = :profileId)
           AND (
               uid IN (
                   SELECT m.uid FROM movie m LEFT JOIN category c ON c.uid = m.categoryUid
                   WHERE c.adult = 1 OR c.uid IN (SELECT lp.uid FROM category_pref lp WHERE lp.locked = 1)
                      OR LOWER(c.name) LIKE '%xxx%' OR LOWER(c.name) LIKE '%adult%' OR LOWER(c.name) LIKE '%porn%'
                      OR LOWER(c.name) LIKE '%18+%' OR LOWER(c.name) LIKE '%+18%' OR LOWER(c.name) LIKE '%erot%'
                      OR LOWER(c.name) LIKE '%charme%' OR LOWER(m.name) LIKE '%xxx%' OR LOWER(m.name) LIKE '%adult%'
                      OR LOWER(m.name) LIKE '%porn%' OR LOWER(m.name) LIKE '%18+%' OR LOWER(m.name) LIKE '%dorcel%'
                      OR LOWER(m.name) LIKE '%brazzers%'
               )
               OR parentUid IN (
                   SELECT s.uid FROM series s LEFT JOIN category c ON c.uid = s.categoryUid
                   WHERE c.adult = 1 OR c.uid IN (SELECT lp.uid FROM category_pref lp WHERE lp.locked = 1)
                      OR LOWER(c.name) LIKE '%xxx%' OR LOWER(c.name) LIKE '%adult%' OR LOWER(c.name) LIKE '%porn%'
                      OR LOWER(c.name) LIKE '%18+%' OR LOWER(c.name) LIKE '%+18%' OR LOWER(c.name) LIKE '%erot%'
                      OR LOWER(c.name) LIKE '%charme%' OR LOWER(s.name) LIKE '%xxx%' OR LOWER(s.name) LIKE '%adult%'
                      OR LOWER(s.name) LIKE '%porn%' OR LOWER(s.name) LIKE '%18+%' OR LOWER(s.name) LIKE '%dorcel%'
                      OR LOWER(s.name) LIKE '%brazzers%'
               )
           )""",
    )
    suspend fun deleteWatchProgressAdults(profileId: String? = null)

    @Query(
        """SELECT contentKey FROM watch_progress WHERE (:profileId IS NULL OR profileId = :profileId)
           AND (
               uid IN (
                   SELECT m.uid FROM movie m LEFT JOIN category c ON c.uid = m.categoryUid
                   WHERE c.adult = 1 OR c.uid IN (SELECT lp.uid FROM category_pref lp WHERE lp.locked = 1)
                      OR LOWER(c.name) LIKE '%xxx%' OR LOWER(c.name) LIKE '%adult%' OR LOWER(c.name) LIKE '%porn%'
                      OR LOWER(c.name) LIKE '%18+%' OR LOWER(c.name) LIKE '%+18%' OR LOWER(c.name) LIKE '%erot%'
                      OR LOWER(c.name) LIKE '%charme%' OR LOWER(m.name) LIKE '%xxx%' OR LOWER(m.name) LIKE '%adult%'
                      OR LOWER(m.name) LIKE '%porn%' OR LOWER(m.name) LIKE '%18+%' OR LOWER(m.name) LIKE '%dorcel%'
                      OR LOWER(m.name) LIKE '%brazzers%'
               )
               OR parentUid IN (
                   SELECT s.uid FROM series s LEFT JOIN category c ON c.uid = s.categoryUid
                   WHERE c.adult = 1 OR c.uid IN (SELECT lp.uid FROM category_pref lp WHERE lp.locked = 1)
                      OR LOWER(c.name) LIKE '%xxx%' OR LOWER(c.name) LIKE '%adult%' OR LOWER(c.name) LIKE '%porn%'
                      OR LOWER(c.name) LIKE '%18+%' OR LOWER(c.name) LIKE '%+18%' OR LOWER(c.name) LIKE '%erot%'
                      OR LOWER(c.name) LIKE '%charme%' OR LOWER(s.name) LIKE '%xxx%' OR LOWER(s.name) LIKE '%adult%'
                      OR LOWER(s.name) LIKE '%porn%' OR LOWER(s.name) LIKE '%18+%' OR LOWER(s.name) LIKE '%dorcel%'
                      OR LOWER(s.name) LIKE '%brazzers%'
               )
           )""",
    )
    suspend fun contentKeysForAdults(profileId: String? = null): List<String>

    @Query(
        """DELETE FROM channel_history WHERE (:profileId IS NULL OR profileId = :profileId)
           AND channelUid IN (SELECT uid FROM channel WHERE name LIKE '%' || :keyword || '%')""",
    )
    suspend fun deleteChannelHistoryByKeyword(keyword: String, profileId: String? = null)

    @Query(
        """DELETE FROM watch_progress WHERE (:profileId IS NULL OR profileId = :profileId)
           AND (
               uid IN (SELECT uid FROM movie WHERE name LIKE '%' || :keyword || '%')
               OR parentUid IN (SELECT uid FROM series WHERE name LIKE '%' || :keyword || '%')
               OR uid IN (SELECT uid FROM episode WHERE title LIKE '%' || :keyword || '%')
           )""",
    )
    suspend fun deleteWatchProgressByKeyword(keyword: String, profileId: String? = null)

    @Query(
        """SELECT contentKey FROM watch_progress WHERE (:profileId IS NULL OR profileId = :profileId)
           AND (
               uid IN (SELECT uid FROM movie WHERE name LIKE '%' || :keyword || '%')
               OR parentUid IN (SELECT uid FROM series WHERE name LIKE '%' || :keyword || '%')
               OR uid IN (SELECT uid FROM episode WHERE title LIKE '%' || :keyword || '%')
           )""",
    )
    suspend fun contentKeysByKeyword(keyword: String, profileId: String? = null): List<String>

    @Query("DELETE FROM channel_history WHERE (:profileId IS NULL OR profileId = :profileId) AND channelUid = :itemUid")
    suspend fun deleteChannelHistoryByItem(itemUid: String, profileId: String? = null)

    @Query("DELETE FROM watch_progress WHERE (:profileId IS NULL OR profileId = :profileId) AND (uid = :itemUid OR parentUid = :itemUid)")
    suspend fun deleteWatchProgressByItem(itemUid: String, profileId: String? = null)

    @Query("SELECT contentKey FROM watch_progress WHERE (:profileId IS NULL OR profileId = :profileId) AND (uid = :itemUid OR parentUid = :itemUid)")
    suspend fun contentKeysByItem(itemUid: String, profileId: String? = null): List<String>

    @Query("DELETE FROM channel_history WHERE (:profileId IS NULL OR profileId = :profileId) AND channelUid IN (:uids)")
    suspend fun deleteChannelHistoryByUids(uids: List<String>, profileId: String? = null)

    @Query("DELETE FROM watch_progress WHERE (:profileId IS NULL OR profileId = :profileId) AND contentKey IN (:keys)")
    suspend fun deleteWatchProgressByKeys(keys: List<String>, profileId: String? = null)

    suspend fun clearWatchHistory(profileId: String? = null) {
        clearWatchProgress(profileId)
        clearChannelHistory(profileId)
    }

    suspend fun clearAdultHistory(profileId: String? = null): List<String> {
        val keys = contentKeysForAdults(profileId)
        deleteChannelHistoryAdults(profileId)
        deleteWatchProgressAdults(profileId)
        return keys
    }

    suspend fun clearHistoryByCategory(categoryUid: String, profileId: String? = null): List<String> {
        val keys = contentKeysByCategory(categoryUid, profileId)
        deleteChannelHistoryByCategory(categoryUid, profileId)
        deleteWatchProgressByCategory(categoryUid, profileId)
        return keys
    }

    suspend fun clearHistoryByKeyword(keyword: String, profileId: String? = null): List<String> {
        val keys = contentKeysByKeyword(keyword, profileId)
        deleteChannelHistoryByKeyword(keyword, profileId)
        deleteWatchProgressByKeyword(keyword, profileId)
        return keys
    }

    suspend fun clearHistoryByItem(itemUid: String, profileId: String? = null): List<String> {
        val keys = contentKeysByItem(itemUid, profileId)
        deleteChannelHistoryByItem(itemUid, profileId)
        deleteWatchProgressByItem(itemUid, profileId)
        return keys
    }
}

@Dao
interface ChannelPrefDao {
    @Query("SELECT * FROM channel_pref WHERE uid = :uid")
    suspend fun get(uid: String): ChannelPrefEntity?

    @Query("SELECT * FROM channel_pref")
    suspend fun all(): List<ChannelPrefEntity>

    /** Personnalisations des chaînes d'une playlist (uid commençant par « id: »). */
    @Query("SELECT * FROM channel_pref WHERE substr(uid, 1, length(:prefix)) = :prefix")
    suspend fun forPrefix(prefix: String): List<ChannelPrefEntity>

    @Upsert
    suspend fun upsert(p: ChannelPrefEntity)

    @Upsert
    suspend fun upsertAll(items: List<ChannelPrefEntity>)

    @Query("DELETE FROM channel_pref WHERE uid = :uid")
    suspend fun delete(uid: String)

    @Query("DELETE FROM channel_pref")
    suspend fun clear()

    @Query("SELECT * FROM channel_pref")
    fun observeAll(): Flow<List<ChannelPrefEntity>>

    @Query("DELETE FROM channel_pref WHERE substr(uid, 1, length(:prefix)) = :prefix")
    suspend fun deleteWithPrefix(prefix: String)
}

@Dao
interface RecordingRuleDao {
    @Query("SELECT * FROM recording_rule ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecordingRuleEntity>>

    @Query("SELECT * FROM recording_rule")
    suspend fun all(): List<RecordingRuleEntity>

    @Query("SELECT * FROM recording_rule WHERE channelUid = :channelUid AND titleNorm = :titleNorm LIMIT 1")
    suspend fun find(channelUid: String, titleNorm: String): RecordingRuleEntity?

    @Insert
    suspend fun insert(r: RecordingRuleEntity): Long

    @Query("DELETE FROM recording_rule WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminder WHERE stop > :now ORDER BY start")
    fun observeUpcoming(now: Long): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminder WHERE start > :now ORDER BY start")
    suspend fun future(now: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminder WHERE id = :id")
    suspend fun get(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminder WHERE channelUid = :channelUid AND start = :start LIMIT 1")
    suspend fun find(channelUid: String, start: Long): ReminderEntity?

    @Query("SELECT start FROM reminder WHERE stop > :now")
    fun observeStarts(now: Long): Flow<List<Long>>

    @Insert
    suspend fun insert(r: ReminderEntity): Long

    @Query("DELETE FROM reminder WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM reminder WHERE stop < :before")
    suspend fun deleteBefore(before: Long)
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recording ORDER BY start DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recording WHERE status = 'SCHEDULED' AND stop > :now")
    suspend fun scheduled(now: Long): List<RecordingEntity>

    @Query("SELECT * FROM recording WHERE status = 'RECORDING'")
    suspend fun inProgress(): List<RecordingEntity>

    @Query("SELECT * FROM recording WHERE id = :id")
    suspend fun get(id: Long): RecordingEntity?

    @Query(
        """SELECT * FROM recording WHERE channelUid = :channelUid AND start < :stop AND stop > :start
           AND status IN ('SCHEDULED', 'RECORDING') LIMIT 1""",
    )
    suspend fun findActive(channelUid: String, start: Long, stop: Long): RecordingEntity?

    @Query("SELECT * FROM recording WHERE status IN ('SCHEDULED', 'RECORDING')")
    fun observeActive(): Flow<List<RecordingEntity>>

    /** Enregistrements prévus ou en cours qui chevauchent l'intervalle [start, stop). */
    @Query("SELECT * FROM recording WHERE start < :stop AND stop > :start AND status IN ('SCHEDULED', 'RECORDING') ORDER BY start")
    suspend fun overlapping(start: Long, stop: Long): List<RecordingEntity>

    @Query("SELECT * FROM recording WHERE ruleId = :ruleId AND status = 'SCHEDULED'")
    suspend fun scheduledForRule(ruleId: Long): List<RecordingEntity>

    /** Enregistrements (quel que soit leur état) sur cette chaîne qui chevauchent l'intervalle [start, stop). */
    @Query("SELECT * FROM recording WHERE channelUid = :channelUid AND start < :stop AND stop > :start ORDER BY start")
    suspend fun onChannel(channelUid: String, start: Long, stop: Long): List<RecordingEntity>

    /** Enregistrements prévus ou en cours sur cette chaîne qui chevauchent l'intervalle [start, stop). */
    @Query(
        """SELECT * FROM recording WHERE channelUid = :channelUid AND start < :stop AND stop > :start
           AND status IN ('SCHEDULED', 'RECORDING') ORDER BY start""",
    )
    suspend fun activeOnChannel(channelUid: String, start: Long, stop: Long): List<RecordingEntity>

    @Insert
    suspend fun insert(r: RecordingEntity): Long

    @Update
    suspend fun update(r: RecordingEntity)

    @Query("UPDATE recording SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: RecordingStatus, error: String = "")

    @Query("UPDATE recording SET bytes = :bytes WHERE id = :id")
    suspend fun setBytes(id: Long, bytes: Long)

    @Query("DELETE FROM recording WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DownloadDao {
    @Upsert
    suspend fun upsert(item: DownloadEntity)

    @Upsert
    suspend fun upsertAll(items: List<DownloadEntity>)

    @Query("SELECT * FROM download ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM download WHERE id = :id")
    fun observe(id: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM download WHERE itemUid = :itemUid")
    fun observeByItemUid(itemUid: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM download WHERE seriesUid = :seriesUid ORDER BY createdAt ASC")
    fun observeBySeriesUid(seriesUid: String): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM download WHERE id = :id")
    suspend fun get(id: String): DownloadEntity?

    @Query("SELECT * FROM download WHERE itemUid = :itemUid")
    suspend fun getByItemUid(itemUid: String): DownloadEntity?

    @Query("SELECT * FROM download WHERE seriesUid = :seriesUid")
    suspend fun getBySeriesUid(seriesUid: String): List<DownloadEntity>

    @Query("SELECT * FROM download ORDER BY createdAt DESC")
    suspend fun all(): List<DownloadEntity>

    @Query("UPDATE download SET bytesDownloaded = :bytesDownloaded, totalBytes = :totalBytes, status = :status WHERE id = :id")
    suspend fun updateProgress(id: String, bytesDownloaded: Long, totalBytes: Long, status: String)

    @Query("UPDATE download SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    /** Progression seule : ne touche pas au statut, qu'une pause a peut-être changé entre-temps. */
    @Query("UPDATE download SET bytesDownloaded = :bytesDownloaded, totalBytes = :totalBytes WHERE id = :id")
    suspend fun updateBytes(id: String, bytesDownloaded: Long, totalBytes: Long)

    @Query("DELETE FROM download WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM download WHERE itemUid = :itemUid")
    suspend fun deleteByItemUid(itemUid: String)

    @Query("SELECT * FROM download WHERE status = 'DOWNLOADING'")
    suspend fun activeDownloads(): List<DownloadEntity>

    @Query("SELECT * FROM download WHERE status IN ('QUEUED', 'DOWNLOADING') ORDER BY createdAt ASC")
    suspend fun pendingQueue(): List<DownloadEntity>
}

@Dao
interface MetadataCacheDao {
    @Query("SELECT * FROM metadata_cache WHERE cacheKey = :key")
    suspend fun get(key: String): MetadataCacheEntity?

    @Upsert
    suspend fun upsert(item: MetadataCacheEntity)

    @Query("DELETE FROM metadata_cache WHERE fetchedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface SeriesTrackingDao {
    @Query("SELECT * FROM series_episode_alert")
    fun observeAlerts(): Flow<List<SeriesEpisodeAlertEntity>>

    @Query("SELECT * FROM series_episode_alert WHERE seriesUid = :seriesUid")
    fun observeAlert(seriesUid: String): Flow<SeriesEpisodeAlertEntity?>

    @Query("SELECT * FROM series_episode_alert WHERE seriesUid = :seriesUid")
    suspend fun alert(seriesUid: String): SeriesEpisodeAlertEntity?

    @Upsert
    suspend fun upsertAlert(item: SeriesEpisodeAlertEntity)

    @Query("DELETE FROM series_episode_alert WHERE seriesUid = :seriesUid")
    suspend fun deleteAlert(seriesUid: String)

    @Query("DELETE FROM series_episode_alert WHERE detectedAt < :before")
    suspend fun deleteExpiredAlerts(before: Long)

    @Query("DELETE FROM series_episode_alert WHERE substr(seriesUid, 1, length(:pidPrefix)) = :pidPrefix")
    suspend fun deleteAlertsForPlaylist(pidPrefix: String)

    @Query("SELECT * FROM series_schedule")
    fun observeSchedules(): Flow<List<SeriesScheduleEntity>>

    @Query("SELECT * FROM series_schedule WHERE seriesUid = :seriesUid")
    suspend fun schedule(seriesUid: String): SeriesScheduleEntity?

    @Query("SELECT * FROM series_schedule WHERE seriesUid = :seriesUid")
    fun observeSchedule(seriesUid: String): Flow<SeriesScheduleEntity?>

    @Upsert
    suspend fun upsertSchedule(item: SeriesScheduleEntity)

    @Query("DELETE FROM series_schedule WHERE substr(seriesUid, 1, length(:pidPrefix)) = :pidPrefix")
    suspend fun deleteSchedulesForPlaylist(pidPrefix: String)
}

@Dao
interface StreamQualityDao {
    @Query("SELECT * FROM stream_quality")
    fun observeAll(): Flow<List<StreamQualityEntity>>

    @Query("SELECT * FROM stream_quality WHERE uid = :uid")
    suspend fun get(uid: String): StreamQualityEntity?

    @Upsert
    suspend fun upsert(items: List<StreamQualityEntity>)
}

/** Mémorise des définitions relevées. Pour des épisodes, [seriesUid] retient aussi la meilleure au niveau de la série. */
suspend fun StreamQualityDao.record(items: List<StreamQualityEntity>, seriesUid: String = "") {
    if (items.isEmpty()) return
    val known = if (seriesUid.isBlank()) null else get(seriesUid)
    val series = seriesUid.takeIf { it.isNotBlank() }?.let { uid ->
        StreamQualityEntity(uid, maxOf(known?.width ?: 0, items.maxOf { it.width }), maxOf(known?.height ?: 0, items.maxOf { it.height }))
    }
    upsert(items + listOfNotNull(series))
}
