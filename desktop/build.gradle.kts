import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Vyzo PC : Linux (.deb) et Windows (.msi), même code que l'appli Android (module :shared).
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// Version et compte Vyzo : voir gradle.properties, communs avec l'appli Android.
val appVersion = findProperty("vyzo.versionName") as String
val appVersionCode = (findProperty("vyzo.versionCode") as String).toInt()
val supabaseUrl = findProperty("vyzo.supabaseUrl") as String
val supabaseKey = findProperty("vyzo.supabaseKey") as String
val tmdbKey = findProperty("vyzo.tmdbKey") as String? ?: ""

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    // Lecteur : libVLC 3 via vlcj (GPL). Linux : VLC du système ; Windows : VLC fourni avec l'installeur.
    implementation("uk.co.caprica:vlcj:4.8.3")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Tests JVM communs à Android et à Vyzo PC (parseurs, faux serveur IPTV).
sourceSets.test { kotlin.srcDir("../shared/src/sharedTest/kotlin") }

tasks.test {
    maxHeapSize = "2g"
    // Captures d'écran des tests d'interface : desktop/screenshots/
    systemProperty("vyzo.screenshots", rootProject.file("desktop/screenshots").absolutePath)
}

// Version et dossier de données transmis à l'appli au lancement (gradle run).
val generateBuildInfo by tasks.registering {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    outputs.dir(out)
    inputs.property("version", appVersion)
    inputs.property("versionCode", appVersionCode)
    inputs.property("supabase", "$supabaseUrl|$supabaseKey")
    inputs.property("tmdbKey", tmdbKey)
    doLast {
        val f = out.get().file("fr/yannickuhrig/vyzo/desktop/BuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            """
            |package fr.yannickuhrig.vyzo.desktop
            |
            |object BuildInfo {
            |    const val VERSION = "$appVersion"
            |    const val VERSION_CODE = $appVersionCode
            |    const val SUPABASE_URL = "$supabaseUrl"
            |    const val SUPABASE_KEY = "$supabaseKey"
            |    const val TMDB_KEY = "$tmdbKey"
            |    /** Fichier décrivant la dernière version publiée (voir scripts/publier-mise-a-jour.sh). */
            |    const val UPDATE_URL = "$supabaseUrl/storage/v1/object/public/releases/latest.json"
            |}
            """.trimMargin() + "\n",
        )
    }
}
sourceSets.main { kotlin.srcDir(generateBuildInfo) }

compose.desktop {
    application {
        mainClass = "fr.yannickuhrig.vyzo.desktop.MainKt"
        // ExplicitGCInvokesConcurrent : les nettoyages de NativeMemory ne figent pas l'écran.
        jvmArgs += listOf("-Xmx1g", "-XX:+ExplicitGCInvokesConcurrent", "-Dfile.encoding=UTF-8", "-Dsun.java2d.uiScale.enabled=true")

        buildTypes.release.proguard {
            // Réflexion de JNA / vlcj, Room et kotlinx.serialization : pas de réduction du code.
            isEnabled.set(false)
        }

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "vyzo"
            packageVersion = appVersion.replace("-", ".")
            description = "Lecteur IPTV : TV en direct, guide, replay, films et séries"
            vendor = "Yannick Uhrig"
            copyright = "© 2026 Yannick Uhrig"
            licenseFile.set(project.file("LICENSE.txt"))
            // Modules Java utilisés (sql pour SQLite, net.http non, management pour la mise en veille, jdk.unsupported pour JNA).
            modules("java.sql", "java.naming", "java.management", "jdk.unsupported", "java.net.http", "jdk.crypto.ec")
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            linux {
                packageName = "vyzo"
                debMaintainer = "254835393+yannickuhrig1@users.noreply.github.com"
                menuGroup = "AudioVideo;Video;TV"
                appCategory = "video"
                iconFile.set(project.file("packaging/vyzo.png"))
                shortcut = true
            }
            windows {
                packageVersion = appVersion.substringBefore('-')
                packageName = "Vyzo"
                // Même identifiant à chaque version : l'installeur met à jour au lieu d'installer en double.
                upgradeUuid = "6c1f6a3e-6f0b-4f4a-9b8e-2b6f3a9d7e15"
                menuGroup = "Vyzo"
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true
                iconFile.set(project.file("packaging/vyzo.ico"))
            }
            macOS {
                bundleID = "fr.yannickuhrig.vyzo"
                packageName = "Vyzo"
                appCategory = "public.app-category.video"
                val icns = project.file("packaging/vyzo.icns")
                if (icns.exists()) {
                    iconFile.set(icns)
                }
            }
        }
    }
}
