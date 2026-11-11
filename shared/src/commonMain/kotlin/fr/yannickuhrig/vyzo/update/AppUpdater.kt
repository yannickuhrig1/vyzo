package fr.yannickuhrig.vyzo.update

import fr.yannickuhrig.vyzo.data.net.Http
import fr.yannickuhrig.vyzo.data.net.HttpStatusException
import fr.yannickuhrig.vyzo.data.prefs.AppSettings
import fr.yannickuhrig.vyzo.data.prefs.SettingsStore
import fr.yannickuhrig.vyzo.platform.Platform
import fr.yannickuhrig.vyzo.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Contenu de latest.json (publié par scripts/publier-mise-a-jour.sh) ou issu de GitHub Releases. */
@Serializable
data class GitHubRelease(
    val id: Long = 0,
    val tag_name: String = "",
    val name: String = "",
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val body: String = "",
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

@Serializable
data class GitHubReleaseAsset(
    val id: Long = 0,
    val name: String = "",
    val url: String = "",
    val browser_download_url: String = "",
    val digest: String = "",
    val size: Long = 0,
    val content_type: String = "",
)

@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    /** Adresse de l'APK (absolue, ou relative au fichier latest.json). */
    val apk: String = "",
    val sha256: String = "",
    val notes: String = "",
    val minSdk: Int = 23,
    /**
     * Vyzo PC : paquet Linux (.deb) et installeur Windows (.msi), absents si la version n'en a pas.
     * [debParts]/[msiParts] > 0 : le fichier est publié en morceaux « nom.000 », « nom.001 »… (limite de taille de
     * l'hébergement) que l'appli remet bout à bout ; l'empreinte est celle du fichier entier.
     */
    val deb: String = "",
    val debSha256: String = "",
    val debParts: Int = 0,
    val msi: String = "",
    val msiSha256: String = "",
    val msiParts: Int = 0,
    /** Version lue sur GitHub : [versionCode] y est calculé depuis le nom, pas le vrai numéro de l'appli. */
    @Transient val fromGitHub: Boolean = false,
) {
    /** Adresses à télécharger dans l'ordre pour obtenir [base] (en morceaux si [parts] > 0). */
    fun partsOf(base: String, parts: Int): List<String> =
        if (parts <= 0) listOf(base) else (0 until parts).map { "$base.${it.toString().padStart(3, '0')}" }
}

/** APK de la variante [variant] (« vyzo-tv-1.2.0.apk »), ou premier APK si [variant] est vide. */
fun List<GitHubReleaseAsset>.apkFor(variant: String): GitHubReleaseAsset? = firstOrNull {
    it.name.endsWith(".apk", ignoreCase = true) && (variant.isEmpty() || it.name.contains("-$variant", ignoreCase = true))
}

/** Fichier à télécharger pour une plateforme (une ou plusieurs adresses mises bout à bout). */
data class UpdatePackage(val urls: List<String>, val sha256: String, val fileName: String) {
    constructor(url: String, sha256: String, fileName: String) : this(listOf(url), sha256, fileName)
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Ce qui change d'un système à l'autre : quel fichier prendre et comment l'installer. */
interface UpdateInstaller {
    /** Fichier de cette plateforme dans [info], null si cette version n'en propose pas. */
    fun packageFor(info: UpdateInfo): UpdatePackage?

    /** La version peut s'installer sur cet appareil (version d'Android minimale…). */
    fun isCompatible(info: UpdateInfo): Boolean = true

    /** L'appli a le droit d'installer la mise à jour. */
    fun canInstall(): Boolean = true

    /** Ouvre le réglage système qui donne ce droit. */
    fun openInstallPermissionSettings() {}

    /** Lance l'installation du fichier téléchargé. Une erreur est remontée par exception ou par [AppUpdater.fail]. */
    fun install(file: File)
}

/**
 * Mises à jour sans magasin d'applications : lit latest.json, télécharge le fichier de la plateforme (APK, .deb, .msi),
 * vérifie son empreinte puis le confie au système. Sur Android, l'APK doit être signé avec la même clé que l'appli.
 */
class AppUpdater(
    private val settings: SettingsStore,
    private val platform: Platform,
    private val installer: UpdateInstaller,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun manifestUrl(): String = settings.get().updateUrl.ifBlank { platform.updateUrl }

    /**
     * Cherche une nouvelle version. [automatic] : vérification au lancement, au retour dans l'appli et pendant qu'elle
     * reste ouverte (au plus une par [AUTO_CHECK_INTERVAL_MS], silencieuse en cas d'erreur, sans reproposer une version refusée).
     */
    suspend fun check(automatic: Boolean = false): UpdateInfo? {
        val s = settings.get()
        if (automatic) {
            if (!s.autoCheckUpdates || System.currentTimeMillis() - s.lastUpdateCheck < AUTO_CHECK_INTERVAL_MS) return null
        }
        _state.value = UpdateState.Checking
        return try {
            val info = if (s.includeBetaUpdates) {
                checkGitHub(s) ?: checkManifest(s)
            } else {
                checkManifest(s) ?: checkGitHub(s)
            }
            settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
            if (info == null) {
                _state.value = UpdateState.UpToDate
                return null
            }
            val newer = isNewer(info) && installer.isCompatible(info) && installer.packageFor(info) != null
            when {
                !newer -> { _state.value = UpdateState.UpToDate; null }
                automatic && (info.versionCode == s.skippedVersionCode || info.versionName == s.skippedVersionName) -> {
                    _state.value = UpdateState.Idle
                    null
                }
                else -> { _state.value = UpdateState.Available(info); info }
            }
        } catch (e: Exception) {
            Log.w(TAG, "check", e)
            _state.value = if (automatic) UpdateState.Idle else UpdateState.Failed(
                if (e is HttpStatusException && (e.code == 404 || e.code == 400)) "Aucune mise à jour publiée pour l'instant"
                else "Vérification impossible : ${e.message}",
            )
            null
        }
    }

    private fun isNewer(info: UpdateInfo): Boolean = isNewer(info, platform.appVersion, platform.appVersionCode)

    private suspend fun checkGitHub(s: AppSettings): UpdateInfo? = runCatching {
        val headers = mutableMapOf(
            "Accept" to "application/vnd.github+json",
            "User-Agent" to "Vyzo",
        )
        // Aucun jeton n'est livré dans l'application : sans jeton saisi, un dépôt privé répond 404 et on se rabat sur latest.json.
        val token = s.githubToken
        if (token.isNotBlank()) headers["Authorization"] = "Bearer $token"
        val req = Http.request(GITHUB_RELEASES_URL, Http.DEFAULT_UA, headers)
        val text = Http.execute(request = req) { it.body!!.string() }
        val releases = json.decodeFromString<List<GitHubRelease>>(text)
        val filtered = releases.filter { !it.draft }.filter { rel ->
            if (s.includeBetaUpdates) {
                true
            } else {
                !rel.prerelease && !VersionCompare.isBetaOrRc(rel.tag_name)
            }
        }
        val best = filtered.maxWithOrNull { a, b -> VersionCompare.compare(a.tag_name, b.tag_name) } ?: return null
        val vName = VersionCompare.normalize(best.tag_name)
        val apkAsset = best.assets.apkFor(platform.apkVariant)
        val debAsset = best.assets.firstOrNull { it.name.endsWith(".deb", ignoreCase = true) }
        val msiAsset = best.assets.firstOrNull { it.name.endsWith(".msi", ignoreCase = true) }
        val sha = apkAsset?.digest?.removePrefix("sha256:").orEmpty()
        val debSha = debAsset?.digest?.removePrefix("sha256:").orEmpty()
        val msiSha = msiAsset?.digest?.removePrefix("sha256:").orEmpty()

        fun downloadUrl(asset: GitHubReleaseAsset?): String {
            if (asset == null) return ""
            return if (token.isNotBlank() && asset.url.isNotBlank()) asset.url else asset.browser_download_url
        }

        UpdateInfo(
            versionCode = VersionCompare.toSyntheticCode(best.tag_name),
            versionName = vName,
            apk = downloadUrl(apkAsset),
            sha256 = sha,
            deb = downloadUrl(debAsset),
            debSha256 = debSha,
            msi = downloadUrl(msiAsset),
            msiSha256 = msiSha,
            notes = plainNotes(best.body).ifBlank { best.name },
            fromGitHub = true,
        )
    }.getOrNull()

    private suspend fun checkManifest(s: AppSettings): UpdateInfo? = runCatching {
        val url = manifestUrl()
        if (url.isBlank()) return null
        val text = Http.getString("$url?t=${System.currentTimeMillis() / 60_000}", Http.DEFAULT_UA)
        val info = json.decodeFromString(UpdateInfo.serializer(), text)
        info.copy(
            apk = info.apk.takeIf(String::isNotBlank)?.let { a -> resolve(url, a) }.orEmpty(),
            deb = info.deb.takeIf(String::isNotBlank)?.let { a -> resolve(url, a) }.orEmpty(),
            msi = info.msi.takeIf(String::isNotBlank)?.let { a -> resolve(url, a) }.orEmpty(),
        )
    }.getOrNull()

    suspend fun skip(info: UpdateInfo) {
        settings.update { it.copy(skippedVersionCode = info.versionCode, skippedVersionName = info.versionName) }
        _state.value = UpdateState.Idle
    }

    fun dismiss() {
        if (_state.value !is UpdateState.Downloading && _state.value !is UpdateState.Installing) _state.value = UpdateState.Idle
    }

    fun canInstall(): Boolean = installer.canInstall()

    fun openInstallPermissionSettings() = installer.openInstallPermissionSettings()

    /** Échec signalé après coup par le système (résultat de l'installeur Android). */
    fun fail(message: String) {
        _state.value = UpdateState.Failed(message)
    }

    /** Télécharge, vérifie et lance l'installation. */
    suspend fun downloadAndInstall(info: UpdateInfo) {
        try {
            val pkg = installer.packageFor(info) ?: throw IOException("Cette version n'est pas proposée pour cet appareil")
            _state.value = UpdateState.Downloading(info, 0f)
            val file = download(info, pkg)
            _state.value = UpdateState.Installing(info)
            withContext(Dispatchers.IO) { installer.install(file) }
        } catch (e: Exception) {
            Log.w(TAG, "update", e)
            _state.value = UpdateState.Failed(e.message ?: e.toString())
        }
    }

    private suspend fun download(info: UpdateInfo, pkg: UpdatePackage): File = withContext(Dispatchers.IO) {
        val dir = File(platform.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, pkg.fileName)
        val digest = MessageDigest.getInstance("SHA-256")
        out.outputStream().use { os ->
            val token = settings.get().githubToken
            pkg.urls.forEachIndexed { part, url ->
                val headers = mutableMapOf<String, String>()
                if (url.startsWith("https://api.github.com/")) {
                    headers["Accept"] = "application/octet-stream"
                    if (token.isNotBlank()) headers["Authorization"] = "Bearer $token"
                }
                Http.execute(client = Http.streamingClient, request = Http.request(url, Http.DEFAULT_UA, headers)) { resp ->
                    val total = resp.body!!.contentLength()
                    resp.body!!.byteStream().use { input ->
                        val buf = ByteArray(128 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (total > 0 && done - lastReport > 512 * 1024) {
                                lastReport = done
                                val partDone = (done.toFloat() / total).coerceIn(0f, 1f)
                                _state.value = UpdateState.Downloading(info, (part + partDone) / pkg.urls.size)
                            }
                        }
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val sum = digest.digest().joinToString("") { "%02x".format(it) }
        if (pkg.sha256.isNotBlank() && !pkg.sha256.equals(sum, ignoreCase = true)) {
            out.delete()
            throw IOException("Fichier téléchargé corrompu (empreinte différente), réessaie")
        }
        out
    }

    companion object {
        private const val TAG = "VyzoUpdate"

        /** latest.json ne pèse que quelques centaines d'octets : une heure suffit pour qu'une version publiée arrive vite partout. */
        const val AUTO_CHECK_INTERVAL_MS = 3_600_000L
        const val GITHUB_RELEASES_URL = "https://api.github.com/repos/yannickuhrig1/vyzo/releases"

        /**
         * [info] est plus récente que la version installée ([appVersion], [appVersionCode]). À nom égal, seul le vrai
         * numéro de latest.json départage : celui calculé depuis un nom GitHub ne se compare pas à celui de l'appli.
         */
        fun isNewer(info: UpdateInfo, appVersion: String, appVersionCode: Int): Boolean {
            val cmp = VersionCompare.compare(info.versionName, appVersion)
            if (cmp != 0) return cmp > 0
            return !info.fromGitHub && info.versionCode > appVersionCode
        }

        /** Notes d'une release GitHub, écrites en markdown, remises en texte simple pour la fenêtre de mise à jour. */
        fun plainNotes(markdown: String): String = markdown.replace("\r\n", "\n").lines().joinToString("\n") { raw ->
            var line = raw.trimEnd()
            line = line.replace(Regex("^#{1,6}\\s+"), "")
            line = line.replace(Regex("^(\\s*)[-*+]\\s+"), "$1• ")
            line = line.replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            line = line.replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
            line = line.replace(Regex("(?<![\\w*])[*_](\\S(?:.*?\\S)?)[*_](?![\\w*])"), "$1")
            line.replace("`", "")
        }.replace(Regex("\n{3,}"), "\n\n").trim()

        fun resolve(base: String, ref: String): String = when {
            ref.startsWith("http://") || ref.startsWith("https://") -> ref
            else -> base.substringBeforeLast('/') + "/" + ref.removePrefix("/")
        }
    }
}
