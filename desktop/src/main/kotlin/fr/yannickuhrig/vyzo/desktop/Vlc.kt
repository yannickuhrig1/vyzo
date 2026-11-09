package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.util.Log
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import java.io.File

/**
 * Recherche de libVLC.
 * - Windows : la copie de VLC livrée avec l'installeur (dossier « vlc » des ressources), sinon un VLC installé.
 * - macOS : VLC dans /Applications/VLC.app/Contents/MacOS/lib ou Homebrew (/opt/homebrew/lib, /usr/local/lib).
 * - Linux : le VLC du système (paquets libvlc5 et vlc-plugin-base, dépendances du .deb).
 */
object Vlc {
    @Volatile
    var available = false
        private set

    var libraryPath: String? = null
        private set

    val missingMessage: String
        get() = when {
            Os.isWindows -> "Le lecteur vidéo (VLC) est introuvable. Réinstalle Vyzo, ou installe VLC 64 bits depuis videolan.org."
            Os.isMac -> "Le lecteur vidéo (VLC) est introuvable. Installe VLC dans /Applications (videolan.org) ou via Homebrew : brew install --cask vlc"
            else -> "Le lecteur vidéo (VLC) est introuvable. Installe-le avec : sudo apt install vlc"
        }

    /** Dossier de VLC livré avec Vyzo (Windows), null s'il n'existe pas. */
    fun bundledDir(): File? {
        val res = System.getProperty("compose.application.resources.dir") ?: return null
        return File(res, "vlc").takeIf {
            File(it, "libvlc.dll").isFile || File(it, "libvlc.so").isFile || File(it, "libvlc.dylib").isFile
        }
    }

    fun init() {
        bundledDir()?.let { dir ->
            // vlcj cherche d'abord dans jna.library.path ; sous Windows il règle aussi VLC_PLUGIN_PATH sur dir\plugins.
            val prev = System.getProperty("jna.library.path")
            System.setProperty("jna.library.path", listOfNotNull(dir.absolutePath, prev).joinToString(File.pathSeparator))
        }

        if (Os.isMac) {
            val macPaths = listOf(
                "/Applications/VLC.app/Contents/MacOS/lib",
                "/opt/homebrew/lib",
                "/usr/local/lib",
            )
            val prev = System.getProperty("jna.library.path")
            val existing = macPaths.filter { File(it).isDirectory }
            if (existing.isNotEmpty()) {
                val combined = (existing + listOfNotNull(prev)).joinToString(File.pathSeparator)
                System.setProperty("jna.library.path", combined)
            }
            val pluginsDir = File("/Applications/VLC.app/Contents/MacOS/plugins")
            if (pluginsDir.isDirectory && System.getProperty("VLC_PLUGIN_PATH") == null) {
                System.setProperty("VLC_PLUGIN_PATH", pluginsDir.absolutePath)
            }
        }

        available = runCatching {
            val d = NativeDiscovery()
            val found = d.discover()
            libraryPath = d.discoveredPath()
            found
        }.onFailure { Log.e("VyzoVlc", "recherche de VLC", it) }.getOrDefault(false)
        Log.i("VyzoVlc", if (available) "libVLC trouvé dans $libraryPath" else "libVLC introuvable")
    }
}
