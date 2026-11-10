package fr.yannickuhrig.vyzo

import fr.yannickuhrig.vyzo.update.GitHubReleaseAsset
import fr.yannickuhrig.vyzo.update.apkFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Une release contient un APK par appli : chacune doit prendre le sien, jamais celui de l'autre. */
class ApkVariantTest {
    private val release = listOf(
        GitHubReleaseAsset(name = "vyzo_1.11.0_amd64.deb"),
        GitHubReleaseAsset(name = "vyzo-mobile-1.11.0.apk"),
        GitHubReleaseAsset(name = "vyzo-tv-1.11.0.apk"),
    )

    @Test fun `chaque appli prend son APK`() {
        assertEquals("vyzo-tv-1.11.0.apk", release.apkFor("tv")?.name)
        assertEquals("vyzo-mobile-1.11.0.apk", release.apkFor("mobile")?.name)
    }

    @Test fun `les noms avec la plateforme restent reconnus`() {
        val named = listOf(
            GitHubReleaseAsset(name = "Vyzo-1.12.0-macOS-Apple-Silicon.dmg"),
            GitHubReleaseAsset(name = "Vyzo-1.12.0-Android-mobile.apk"),
            GitHubReleaseAsset(name = "Vyzo-1.12.0-Android-TV.apk"),
            GitHubReleaseAsset(name = "Vyzo-1.12.0-Linux.deb"),
            GitHubReleaseAsset(name = "Vyzo-1.12.0-Windows.msi"),
        )
        assertEquals("Vyzo-1.12.0-Android-TV.apk", named.apkFor("tv")?.name)
        assertEquals("Vyzo-1.12.0-Android-mobile.apk", named.apkFor("mobile")?.name)
    }

    @Test fun `une release a APK unique n'est pas proposee aux applis separees`() {
        assertNull(listOf(GitHubReleaseAsset(name = "Vyzo-1.10.4.apk")).apkFor("tv"))
    }

    @Test fun `sans variante le premier APK convient`() {
        assertEquals("vyzo-mobile-1.11.0.apk", release.apkFor("")?.name)
    }
}
