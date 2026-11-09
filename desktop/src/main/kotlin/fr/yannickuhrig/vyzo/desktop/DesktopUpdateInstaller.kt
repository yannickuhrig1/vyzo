package fr.yannickuhrig.vyzo.desktop

import fr.yannickuhrig.vyzo.update.UpdateInfo
import fr.yannickuhrig.vyzo.update.UpdateInstaller
import fr.yannickuhrig.vyzo.update.UpdatePackage
import java.io.File
import java.io.IOException

/**
 * Mises à jour de Vyzo PC : le .deb (Linux) ou le .msi (Windows) de latest.json est téléchargé puis ouvert par le système.
 * Linux : l'installeur de paquets de Mint (mot de passe demandé) ; Windows : l'installeur Windows, qui remplace la version
 * installée (même identifiant de mise à jour). Vyzo se ferme pour laisser l'installation se faire.
 */
class DesktopUpdateInstaller(private val onBeforeInstall: () -> Unit) : UpdateInstaller {

    override fun packageFor(info: UpdateInfo): UpdatePackage? = when {
        Os.isWindows -> info.msi.takeIf { it.isNotBlank() }
            ?.let { UpdatePackage(info.partsOf(it, info.msiParts), info.msiSha256, "Vyzo-${info.versionName}.msi") }
        Os.isLinux -> info.deb.takeIf { it.isNotBlank() }
            ?.let { UpdatePackage(info.partsOf(it, info.debParts), info.debSha256, "vyzo_${info.versionName}_amd64.deb") }
        else -> null
    }

    override fun install(file: File) {
        val started = when {
            Os.isWindows -> runCatching { ProcessBuilder("msiexec", "/i", file.absolutePath).start() }.isSuccess
            Os.isLinux -> listOf("mintinstall-launcher", "xdg-open").firstOrNull { Os.hasCommand(it) }
                ?.let { cmd -> runCatching { ProcessBuilder(cmd, file.absolutePath).start() }.isSuccess } ?: false
            else -> false
        }
        if (!started) throw IOException("Impossible d'ouvrir l'installeur : le fichier est dans ${file.parent}")
        onBeforeInstall()
    }
}
