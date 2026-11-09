import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Code commun à l'appli Android (TV + téléphone) et à Vyzo PC (Linux, Windows) :
// sources IPTV, guide, base Room, réglages, synchro, enregistreur, réception depuis le téléphone et interface Compose.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

val room = "2.7.2"

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "kotlinx.serialization.ExperimentalSerializationApi",
        )
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.foundation)
            api(compose.ui)
            api(compose.material3)
            api(compose.materialIconsExtended)
            api("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
            api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
            api("org.jetbrains.androidx.navigation:navigation-compose:2.9.0")
            api("androidx.room:room-runtime:$room")
            api("androidx.datastore:datastore-preferences-core:1.1.7")
            api("com.squareup.okhttp3:okhttp:4.12.0")
            api("io.coil-kt.coil3:coil-compose:3.3.0")
            api("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
            api("com.google.zxing:core:3.5.3")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            // L'API XmlPullParser est fournie par Android ; sur PC, kxml2 l'implémente (voir desktopMain).
            compileOnly("net.sf.kxml:kxml2:2.3.0")
        }
        androidMain.dependencies {
            api("androidx.core:core-ktx:1.16.0")
            api("androidx.activity:activity-compose:1.10.1")
            api("androidx.documentfile:documentfile:1.0.1")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
            val camerax = "1.4.2"
            api("androidx.camera:camera-camera2:$camerax")
            api("androidx.camera:camera-lifecycle:$camerax")
            api("androidx.camera:camera-view:$camerax")
        }
        val desktopMain by getting {
            dependencies {
                api(compose.desktop.common)
                api("androidx.sqlite:sqlite-bundled:2.5.2")
                api("net.sf.kxml:kxml2:2.3.0")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("junit:junit:4.13.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

android {
    namespace = "fr.yannickuhrig.vyzo.shared"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", "androidx.room:room-compiler:$room")
    add("kspDesktop", "androidx.room:room-compiler:$room")
}
