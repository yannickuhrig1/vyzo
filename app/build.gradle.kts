import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?: rootProject.file("keystore/keystore.properties").takeIf { it.exists() }
    if (f != null) f.inputStream().use { load(it) }
}

// Version et compte Vyzo (Supabase) : voir gradle.properties, communs avec Vyzo PC.
val supabaseUrl = findProperty("vyzo.supabaseUrl") as String
val supabaseKey = findProperty("vyzo.supabaseKey") as String
val tmdbKey = findProperty("vyzo.tmdbKey") as String? ?: ""

android {
    namespace = "fr.yannickuhrig.vyzo"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.yannickuhrig.vyzo"
        minSdk = 23
        targetSdk = 35
        versionCode = (findProperty("vyzo.versionCode") as String).toInt()
        versionName = findProperty("vyzo.versionName") as String
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")
        buildConfigField("String", "TMDB_KEY", "\"$tmdbKey\"")
    }

    // Deux applis construites depuis ce module : src/tv et src/mobile ajoutent ce qui est propre à chacune.
    flavorDimensions += "cible"
    productFlavors {
        // La TV garde l'identifiant historique : réinstaller une appli hors magasin est bien plus pénible sur TV.
        create("tv") { dimension = "cible" }
        create("mobile") {
            dimension = "cible"
            applicationIdSuffix = ".mobile"
        }
        // Fichier décrivant la dernière version publiée pour cette appli (voir scripts/publier-mise-a-jour.sh).
        all { buildConfigField("String", "UPDATE_URL", "\"$supabaseUrl/storage/v1/object/public/releases/latest-$name.json\"") }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                val sf = keystoreProps.getProperty("storeFile") ?: "zapette.jks"
                storeFile = rootProject.file(sf).takeIf { it.exists() }
                    ?: rootProject.file("keystore/$sf")
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    lint {
        // Les API "instables" de Media3 sont utilisées volontairement (décodeur, extracteurs TS).
        disable += setOf("UnsafeOptInUsageError")
        abortOnError = false
        checkReleaseBuilds = false
    }

    // Tests JVM communs à Android et à Vyzo PC (parseurs, faux serveur IPTV).
    sourceSets.getByName("test").java.srcDir("../shared/src/sharedTest/kotlin")

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "3g"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

dependencies {
    // Tout le code commun (données, synchro, interface Compose) vit dans :shared.
    implementation(project(":shared"))

    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    val media3 = "1.8.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // Décodeur FFmpeg (AC3, E-AC3, DTS, MP2…) compilé et publié par Jellyfin
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit-ktx:1.2.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.46.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.46.1")
}
