package fr.yannickuhrig.vyzo.platform

import fr.yannickuhrig.vyzo.ui.common.FormFactor
import org.xmlpull.v1.XmlPullParser
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Fichier de sauvegarde repéré sur l'appareil ([uri] se lit avec [Platform.openInput]). */
data class BackupFile(val uri: String, val location: String, val modifiedAt: Long)

/**
 * Ce qui diffère entre Android et Vyzo PC (Linux, Windows), vu du code commun.
 * Implémenté par l'appli Android (AndroidPlatform) et par Vyzo PC (DesktopPlatform).
 */
interface Platform {
    /** true pour Vyzo PC. */
    val isDesktop: Boolean

    /** true si l'appareil est une télévision (ou émule une TV). */
    val isTv: Boolean get() = formFactor("AUTO") == FormFactor.TV

    /** Version affichée dans « À propos ». */
    val appVersion: String

    /** Numéro de version (le même sur Android et sur PC), comparé à celui de latest.json pour les mises à jour. */
    val appVersionCode: Int

    /** Modèle de l'appareil (rapports de diagnostic et de plantage). */
    val deviceModel: String

    /** Système (« Android 14 (API 34) », « Linux Mint 22.3 »…). */
    val systemVersion: String

    /** Dossier privé de l'appli (rapports de plantage…). */
    val filesDir: File

    /** Projet Supabase de Vyzo (compte, synchro, rapports de plantage) ; vide = désactivé. */
    val cloudUrl: String
    val cloudKey: String

    /** Clé TMDB fournie à la compilation (-Pvyzo.tmdbKey, jamais dans le dépôt), vide sinon. */
    val tmdbBuiltInKey: String get() = ""

    /** Adresse intégrée de latest.json (mises à jour). */
    val updateUrl: String

    /** Incrustation (PiP) Android disponible. */
    val supportsPip: Boolean get() = false

    /** Bascule vers le mode Picture-in-Picture si supporté. */
    fun enterPictureInPicture(): Boolean = false

    /** Nom de l'appareil montré au téléphone pendant l'envoi d'une configuration. */
    val deviceName: String

    /** Une caméra est disponible pour lire un QR code. */
    val hasCamera: Boolean

    /** La recherche vocale du système est disponible. */
    val hasVoiceSearch: Boolean

    /** Le direct est coupé quand l'appli passe en arrière-plan (Android). Sur PC, on continue à jouer. */
    val stopsLiveInBackground: Boolean

    /** Dossier de cache (images). */
    val cacheDir: File

    /** Fichier utilisé quand aucun sélecteur de fichier n'est disponible pour la sauvegarde. */
    val localBackupFile: File

    /** Dossier des profils locaux, jamais copié vers un autre appareil par le système (voir [fr.yannickuhrig.vyzo.data.profile.LocalProfiles]). */
    val localProfileDir: File get() = File(filesDir, "profils-locaux")

    /**
     * Sans sélecteur de fichier : écrit la sauvegarde là où elle reste après une désinstallation (Téléchargements, clé USB).
     * Renvoie les emplacements écrits, vide si aucun.
     */
    fun writeSharedBackup(text: String): List<String> = emptyList()

    /** Sauvegardes visibles par l'appli hors de son dossier, y compris celles d'une installation précédente si l'accès est donné. */
    fun sharedBackups(): List<BackupFile> = emptyList()

    /** Mode d'affichage selon le réglage AUTO, TV ou PHONE. */
    fun formFactor(pref: String): FormFactor

    /** true si l'appli impose son mode d'affichage : le réglage n'est alors pas proposé. */
    val hasFixedFormFactor: Boolean get() = false

    /** Variante de l'APK (« tv », « mobile »), à reconnaître dans le nom des fichiers d'une release ; vide sur PC. */
    val apkVariant: String get() = ""

    /** Lit un fichier choisi par l'utilisateur (content://, file:// ou chemin local), null si illisible. */
    fun openInput(uri: String): InputStream?

    /** Écrit dans un fichier choisi par l'utilisateur. */
    fun openOutput(uri: String): OutputStream?

    /** Analyseur XML (XmlPullParser fourni par Android, kxml2 sur PC). */
    fun newPullParser(): XmlPullParser

    /** Message court (Toast sur Android, bandeau en bas de la fenêtre sur PC). */
    fun toast(message: String, long: Boolean = false)

    /** Notification « nouvel épisode » ; la toucher ouvre la fiche de la série [seriesUid]. */
    fun notifyNewEpisodes(title: String, body: String, seriesUid: String) {}

    /** Programme la mise à jour automatique des playlists et guides (0 = jamais). */
    fun scheduleAutoSync(hours: Int)

    /** Vyzo PC : lancement à l'ouverture de session. Renvoie false si le réglage n'a pas pu être appliqué. */
    fun setLaunchAtLogin(enabled: Boolean): Boolean = false

    /** Ouvre un dossier dans le gestionnaire de fichiers (PC). */
    fun openFolder(dir: File) {}

    /** Ouvre une URL dans le navigateur ou l'application associée (ex: YouTube). */
    fun openUrl(url: String): Boolean = false

    /**
     * Ouvre un flux dans un autre lecteur installé (VLC, MX Player, Kodi, Just Player… sur Android ; VLC ou mpv sur PC).
     * [startMs] : position de départ pour un film ou un épisode (0 = début). Renvoie false si aucun lecteur n'a pu l'ouvrir.
     */
    fun openInExternalPlayer(
        url: String,
        title: String,
        userAgent: String,
        headers: Map<String, String> = emptyMap(),
        mimeHint: String? = null,
        startMs: Long = 0,
    ): Boolean = false

    /** Applications installées qu'on peut lancer depuis l'accueil (Android TV, téléphone) ; vide sur PC. */
    fun installedApps(): List<InstalledApp> = emptyList()

    /** Lance l'application [id] (voir [installedApps]). Renvoie false si elle n'a pas pu s'ouvrir. */
    fun launchApp(id: String): Boolean = false

    /** Commandes de la fenêtre (plein écran, mini-lecteur) : PC uniquement, null sur Android. */
    val window: WindowControls? get() = null
}

/** Application installée : [banner] = bannière Android TV (16:9) si elle en a une, sinon son icône. */
data class InstalledApp(val id: String, val label: String, val icon: androidx.compose.ui.graphics.ImageBitmap?, val banner: Boolean = false)

/** Fenêtre de Vyzo PC. */
interface WindowControls {
    val fullscreen: StateFlow<Boolean>
    val alwaysOnTop: StateFlow<Boolean> get() = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun toggleFullscreen()
    fun toggleAlwaysOnTop(): Boolean = false

    /** Passe la vidéo dans une petite fenêtre toujours au premier plan et réduit la fenêtre principale. */
    fun openMiniPlayer()
}
