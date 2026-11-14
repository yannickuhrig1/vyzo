package fr.yannickuhrig.vyzo.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.yannickuhrig.vyzo.data.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Path.Companion.toOkioPath
import java.io.File

@Serializable
data class AppSettings(
    val activePlaylistId: Long = 0,
    /** AUTO, TV ou PHONE. */
    val uiMode: String = "AUTO",
    val epgShiftMinutes: Int = 0,
    val epgDaysAhead: Int = 3,
    val epgDaysBehind: Int = 3,
    /** 0 = jamais. */
    val refreshHours: Int = 24,
    /** Format des flux Xtream en direct : ts ou m3u8. */
    val xtreamFormat: String = "ts",
    /** AUTO, SOFTWARE (préférer FFmpeg) ou HARDWARE. */
    val decoderMode: String = "AUTO",
    /** SHORT, NORMAL ou LONG. */
    val bufferMode: String = "NORMAL",
    /** Décalage du son sur cet appareil, en ms (positif : le son arrive plus tard). */
    val audioDelayMs: Int = 0,
    /** Envoyer l'AC3, l'E-AC3 ou le DTS tels quels à l'ampli quand la sortie les accepte. */
    val audioPassthrough: Boolean = true,
    /** Ramener les chaînes à un volume comparable. */
    val loudnessLeveling: Boolean = false,
    /** Caler la fréquence de l'écran sur celle de la vidéo. */
    val matchFrameRate: Boolean = false,
    /** Lecture en tunnel des films et séries (jamais en direct : le MPEG-TS le supporte mal). */
    val tunnelingVod: Boolean = false,
    /** En direct, lancer l'image dès 500 ms de flux reçu. */
    val fastZap: Boolean = false,
    /** Heure affichée en permanence pendant la lecture. */
    val playerClock: Boolean = false,
    /** Orientation du lecteur sur téléphone. Voir [PlayerOrientation]. */
    val playerOrientation: String = PlayerOrientation.AUTO,
    /** Touches du direct qui diffèrent des touches par défaut (voir RemoteKeymap). */
    val remoteBindings: Map<String, String> = emptyMap(),
    val multiViewPresets: List<MultiViewPreset> = emptyList(),
    /** Scores ESPN dans l'espace sport. */
    val sportScores: Boolean = true,
    /** Application Trakt créée par l'utilisateur. */
    val traktClientId: String = "",
    val traktClientSecret: String = "",
    val traktAccessToken: String = "",
    val traktRefreshToken: String = "",
    val traktTokenExpiresAt: Long = 0L,
    /** Seul ce profil envoie ce qu'il regarde au compte Trakt. */
    val traktProfileId: String = "",
    val traktScrobble: Boolean = true,
    val traktLastSync: Long = 0L,
    val userAgent: String = Http.DEFAULT_UA,
    val audioLang: String = "fr",
    /** Vide = sous-titres désactivés par défaut. */
    val subtitleLang: String = "",
    val autoplayLast: Boolean = false,
    /** Dernière chaîne et dernier groupe du profil actif ; ceux des autres profils attendent dans [lastPlaces]. */
    val lastChannelUid: String = "",
    val lastGroupKey: String = "",
    /** Dernière chaîne et dernier groupe de chaque profil inactif, par identifiant de profil. */
    val lastPlaces: Map<String, LastPlace> = emptyMap(),
    val invertZap: Boolean = false,
    val showNumbers: Boolean = true,
    val recPaddingBeforeMin: Int = 1,
    val recPaddingAfterMin: Int = 5,
    /** Exécuter les enregistrements programmés sur cet appareil (hôte d'enregistrement). */
    val isRecordingHost: Boolean? = null,
    val reminderMinutesBefore: Int = 1,
    val lastFullSync: Long = 0,
    /** Profil utilisé sur cet appareil ; vide ou inconnu = premier profil. */
    val activeProfileId: String = "",
    /** Demander « Qui regarde ? » au lancement quand il y a plusieurs profils. */
    val askProfileOnStart: Boolean = true,
    /** Afficher les chaînes, films et séries de toutes les playlists en même temps. */
    val showAllPlaylists: Boolean = false,
    /** Empreinte du code parental, vide = contrôle parental désactivé. */
    val parentalPinHash: String = "",
    /** Verrouiller automatiquement les catégories détectées « adultes ». */
    val parentalAutoAdult: Boolean = true,
    /** Le code parental est demandé pour quitter un profil enfant. */
    val parentalKidExit: Boolean = true,
    /** Mode Furtif : Masquer complètement les catégories verrouillées/adultes (coffre-fort). */
    val stealthModeEnabled: Boolean = false,
    /** Continuer la lecture en arrière-plan dans un mini-lecteur interne lors de la navigation. */
    val continuePlaybackInBackground: Boolean = true,
    /** Clé API OpenSubtitles.com optionnelle pour la recherche et le téléchargement de sous-titres. */
    val openSubtitlesApiKey: String = "",
    /** Clé API TheMovieDB (TMDB) optionnelle pour enrichir les fiches de films/séries et bandes-annonces. */
    val tmdbApiKey: String = "",
    /** Clé OMDb personnelle : notes IMDb, Rotten Tomatoes et Metacritic sur les fiches. */
    val omdbApiKey: String = "",
    /** Dossier choisi pour les enregistrements (Storage Access Framework), vide = dossier de l'appli. */
    val recFolderUri: String = "",
    val recFolderName: String = "",
    /** Passer en incrustation (PiP) quand on quitte l'appli pendant la lecture. */
    val autoPip: Boolean = true,
    /** Durée maximale de la pause du direct, en minutes (0 = désactivée). */
    val timeshiftMinutes: Int = 30,
    /** Passer à une version moins lourde de la chaîne (FHD → HD → SD) quand le direct saccade. */
    val autoQuality: Boolean = true,
    /** Prévenir quand une série suivie (favoris, séries en cours) a de nouveaux épisodes. */
    val newEpisodeAlerts: Boolean = true,
    /** Dernière recherche de nouveaux épisodes. */
    val lastEpisodeCheck: Long = 0,
    /** Ne plus proposer de saisir le mot de passe du compte pour que les playlists le suivent. */
    val vaultPromptOff: Boolean = false,
    /** Prévenir quand l'abonnement expire dans moins de ce nombre de jours (0 = jamais). */
    val expiryWarnDays: Int = 7,
    /** Adresse du fichier de mise à jour (latest.json), vide = adresse intégrée à l'appli. */
    val updateUrl: String = "",
    val autoCheckUpdates: Boolean = true,
    /** Activer/recevoir les versions Bêta / pre-releases (GitHub Releases). */
    val includeBetaUpdates: Boolean = false,
    /** Jeton d'accès GitHub optionnel (si le dépôt est privé). */
    val githubToken: String = "",
    /** Séries : sauter automatiquement le générique d'introduction (~85s). */
    val autoSkipIntro: Boolean = false,
    /** Séries : sauter automatiquement le récap (« précédemment dans… ») quand ses repères sont sûrs. */
    val autoSkipRecap: Boolean = false,
    /** Séries : enchaîner automatiquement sur l'épisode suivant lors du générique de fin. */
    val autoNextEpisodeOnOutro: Boolean = false,
    /** Dernière version proposée et refusée (pour ne pas la reproposer à chaque lancement). */
    val skippedVersionCode: Int = 0,
    val skippedVersionName: String = "",
    /** Envoyer les rapports de plantage (sans données personnelles) au serveur Vyzo. */
    val sendCrashReports: Boolean = true,
    /** Journal détaillé des échanges avec les fournisseurs (pour le diagnostic). */
    val diagnosticLog: Boolean = false,
    /** Dernière vérification automatique des mises à jour. */
    val lastUpdateCheck: Long = 0,
    /** Dernier avertissement d'expiration d'abonnement (un par jour au plus). */
    val lastExpiryWarning: Long = 0,
    /** Films et séries : regrouper les titres par genre (Action, Comédie…) quel que soit le nom des catégories du fournisseur. */
    val autoGenres: Boolean = true,
    /** Films et séries : bandeau du titre mis en avant au-dessus des affiches (sur TV, il suit la sélection). */
    val vodSpotlight: Boolean = true,
    /** Piste audio et sous-titres choisis pour chaque série (identifiant de la série → choix), réappliqués à l'épisode suivant. */
    val seriesTracks: Map<String, SeriesTracks> = emptyMap(),
    /** Écran ouvert au lancement : LIVE (TV en direct) ou HOME (accueil). */
    val startScreen: String = "LIVE",
    /**
     * Rangées de l'accueil, dans l'ordre (voir [fr.yannickuhrig.vyzo.data.home.HomeRows]) : mise en page commune,
     * utilisée par les profils qui n'ont pas personnalisé la leur ([homeRowsByProfile]).
     */
    val homeRows: List<String> = fr.yannickuhrig.vyzo.data.home.HomeRows.DEFAULT,
    /** Accueil propre à un profil : identifiant du profil → ses rangées. */
    val homeRowsByProfile: Map<String, List<String>> = emptyMap(),
    /** La rangée « Pour toi » a déjà été ajoutée une fois aux accueils existants (on ne la remet pas si on l'enlève). */
    val forYouRowAdded: Boolean = false,
    /** La rangée « Nouveaux épisodes à voir » a déjà été ajoutée une fois aux accueils existants. */
    val newEpisodesRowAdded: Boolean = false,
    /** Accueil : horloge et date en haut à droite. */
    val homeClock: Boolean = true,
    /** Accueil : l'arrière-plan fait défiler les affiches tant qu'on ne parcourt pas les rangées. */
    val homeRotateBackdrop: Boolean = true,
    /** TV : rangées Reprendre et Favoris sur l'écran d'accueil du système. */
    val tvHomeRows: Boolean = true,
    /** TV : minutes d'inactivité avant l'économiseur d'écran (0 = désactivé). */
    val screensaverMinutes: Int = 5,
    /** Accueil : ville de la météo (vide = pas de météo). */
    val weatherCity: String = "",
    val weatherLat: Double = 0.0,
    val weatherLon: Double = 0.0,
    /** TV et PC : position du menu principal (LEFT, RIGHT, TOP, BOTTOM). */
    val navPosition: String = "LEFT",
    /** TV et PC : rail replié (AUTO), toujours ouvert (EXPANDED) ou caché (HIDDEN). */
    val navStyle: String = "AUTO",
    /** Taille de la grille du guide TV : COMPACT, NORMAL ou LARGE. */
    val guideSize: String = "NORMAL",
    /** Vyzo PC : rester dans la zone de notification quand on ferme la fenêtre. */
    val keepInTray: Boolean = true,
    /** Vyzo PC : lancer Vyzo (réduit) à l'ouverture de session. */
    val launchAtLogin: Boolean = false,
) {
    /**
     * Passe au profil [id] : il retrouve sa dernière chaîne et son dernier groupe, et ceux du profil quitté sont gardés
     * pour son retour. Sans profil actif connu (première installation), le profil choisi garde ceux en place.
     */
    fun withActiveProfile(id: String): AppSettings {
        if (id == activeProfileId) return this
        val kept = if (activeProfileId.isBlank()) lastPlaces else lastPlaces + (activeProfileId to LastPlace(lastChannelUid, lastGroupKey))
        val next = kept[id] ?: if (activeProfileId.isBlank()) LastPlace(lastChannelUid, lastGroupKey) else LastPlace()
        return copy(activeProfileId = id, lastChannelUid = next.channelUid, lastGroupKey = next.groupKey, lastPlaces = kept - id)
    }

    /** Oublie la dernière chaîne du profil [profileId] ([isActive] : le profil actif) quand [drop] la désigne. */
    fun forgetLastChannel(profileId: String, isActive: Boolean, drop: (String) -> Boolean): AppSettings {
        if (isActive) return if (lastChannelUid.isNotBlank() && drop(lastChannelUid)) copy(lastChannelUid = "") else this
        val place = lastPlaces[profileId]?.takeIf { it.channelUid.isNotBlank() && drop(it.channelUid) } ?: return this
        return copy(lastPlaces = lastPlaces + (profileId to place.copy(channelUid = "")))
    }

    /** Rangées de l'accueil du profil [profileId] (la mise en page commune s'il n'a pas la sienne). */
    fun homeRowsFor(profileId: String): List<String> = homeRowsByProfile[profileId] ?: homeRows

    /** Le profil a-t-il son propre accueil ? */
    fun hasOwnHome(profileId: String): Boolean = profileId.isNotBlank() && profileId in homeRowsByProfile

    /** Change l'accueil du profil [profileId] (sans profil connu : la mise en page commune). */
    fun withHomeRows(profileId: String, transform: (List<String>) -> List<String>): AppSettings =
        if (profileId.isBlank()) copy(homeRows = transform(homeRows))
        else copy(homeRowsByProfile = homeRowsByProfile + (profileId to transform(homeRowsFor(profileId))))

    /** Ajoute une seule fois « Pour toi » à tous les accueils déjà en place. */
    fun withForYouRow(): AppSettings {
        if (forYouRowAdded) return this
        val add = fr.yannickuhrig.vyzo.data.home.HomeRows::withForYou
        return copy(homeRows = add(homeRows), homeRowsByProfile = homeRowsByProfile.mapValues { add(it.value) }, forYouRowAdded = true)
    }

    /** Ajoute une seule fois « Nouveaux épisodes à voir » à tous les accueils déjà en place. */
    fun withNewEpisodesRow(): AppSettings {
        if (newEpisodesRowAdded) return this
        val add = fr.yannickuhrig.vyzo.data.home.HomeRows::withNewEpisodes
        return copy(homeRows = add(homeRows), homeRowsByProfile = homeRowsByProfile.mapValues { add(it.value) }, newEpisodesRowAdded = true)
    }

    /** Donne l'accueil du profil [profileId] à tous les profils (il devient la mise en page commune). */
    fun shareHomeOf(profileId: String): AppSettings = copy(homeRows = homeRowsFor(profileId), homeRowsByProfile = emptyMap())

    /** true si cet appareil doit exécuter les enregistrements programmés. */
    fun resolvesRecordingHost(isDesktop: Boolean, isTv: Boolean): Boolean {
        return isRecordingHost ?: (isDesktop || isTv)
    }
}

/**
 * Pistes choisies pour une série : [audio] et [text] sont les noms des pistes tels que le lecteur les affiche
 * (vides = pas de choix), [textOff] = sous-titres coupés. [at] sert à oublier les séries les plus anciennes.
 */
@Serializable
data class MultiViewPreset(val profileId: String, val name: String, val channelUids: List<String>)

object MultiViewPresets {
    private const val MAX_SCREENS = 4

    fun forProfile(all: List<MultiViewPreset>, profileId: String): List<MultiViewPreset> = all.filter { it.profileId == profileId }

    /** Un préréglage du même nom est remplacé. */
    fun save(all: List<MultiViewPreset>, profileId: String, name: String, channelUids: List<String>): List<MultiViewPreset> {
        val clean = name.trim()
        val others = all.filterNot { it.profileId == profileId && it.name.equals(clean, ignoreCase = true) }
        return others + MultiViewPreset(profileId, clean, channelUids.distinct().take(MAX_SCREENS))
    }

    fun remove(all: List<MultiViewPreset>, preset: MultiViewPreset): List<MultiViewPreset> = all - preset
}

/** Orientation du lecteur sur téléphone. */
object PlayerOrientation {
    /** Suit le téléphone, en respectant le verrouillage de la rotation. */
    const val AUTO = "AUTO"
    const val LANDSCAPE = "LANDSCAPE"
    const val PORTRAIT = "PORTRAIT"

    private val ORDER = listOf(AUTO, LANDSCAPE, PORTRAIT)

    fun next(current: String): String = ORDER[(ORDER.indexOf(current) + 1).mod(ORDER.size)]

    fun label(value: String): String = when (value) {
        LANDSCAPE -> "Paysage"
        PORTRAIT -> "Portrait"
        else -> "Automatique"
    }
}

@Serializable
data class SeriesTracks(val audio: String = "", val text: String = "", val textOff: Boolean = false, val at: Long = 0)

/** Où un profil s'était arrêté dans le direct : sa dernière chaîne et le groupe d'où il l'avait lancée. */
@Serializable
data class LastPlace(val channelUid: String = "", val groupKey: String = "")

/**
 * Réglages de l'appli, sérialisés en JSON dans un DataStore.
 * [file] doit se terminer par ".preferences_pb" (Android : filesDir/datastore/settings.preferences_pb).
 */
class SettingsStore(file: File, scope: CoroutineScope) {
    private val ds: DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { file.absoluteFile.toOkioPath() },
    )
    private val key = stringPreferencesKey("app_settings")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val flow: Flow<AppSettings> = ds.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> decode(prefs[key]) }

    val state: StateFlow<AppSettings> = flow.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun get(): AppSettings = flow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        ds.edit { prefs ->
            val current = decode(prefs[key])
            prefs[key] = json.encodeToString(AppSettings.serializer(), transform(current))
        }
    }

    fun encode(s: AppSettings): String = json.encodeToString(AppSettings.serializer(), s)

    fun decode(raw: String?): AppSettings =
        if (raw.isNullOrBlank()) AppSettings()
        else runCatching { json.decodeFromString(AppSettings.serializer(), raw) }.getOrDefault(AppSettings())
}
