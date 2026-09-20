plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.moku.mobile"
    // Bumped from 34: okhttp 5.x (which Keiyoushi extensions are compiled against,
    // libs.versions.toml pins okhttp = "5.4.0") requires compileSdk 36+ per its AAR
    // metadata. Extensions crashed with NoClassDefFoundError on okhttp3.internal
    // ._UtilCommonKt when the host only provided okhttp 4.12 — same class of bug as
    // the kotlinx-serialization version pin, just a different library.
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.moku.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Runtime classes a Keiyoushi/Tachiyomi extension's classloader resolves against
    // (vendored from Mihon's source-api under app/src/.../eu/kanade/tachiyomi/...).
    implementation("uy.kohesive.injekt:injekt-core:1.16.1")
    implementation("io.reactivex:rxjava:1.3.8")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-okio:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jsoup:jsoup:1.23.2")

    // Encrypted local storage for the self-hosted Tsunagu server URL + bearer token.
    implementation("androidx.security:security-crypto:1.1.0")

    // Chrome Custom Tabs — for opening a tracker's OAuth authUrl (AniList/MAL) in a real
    // browser tab rather than the app's own WebView, since these are third-party login
    // pages (see RemoteTrackingRepository's kdoc for why there's no deep-link callback).
    implementation("androidx.browser:browser:1.8.0")

    // Durable download queue for local-mode: a download survives the app/process being
    // killed mid-fetch and retries automatically, unlike the original in-process-only
    // DownloadManager. WorkManager auto-initializes via its own ContentProvider, no custom
    // Application class needed.
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Media3 ExoPlayer — only for HeaderMediaSourceFactory (attaching extension-provided
    // request headers to HLS/DASH/progressive playback); no player screen exists yet, this
    // is the plumbing a future one will use.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    // Local-mode persistence (library/folders/reading-progress/downloads/tracking) — see
    // dev.moku.mobile.library. Deliberately separate from anything remote/Tsunagu-related;
    // a connected server owns its own persistence server-side. Hand-rolled SQLiteOpenHelper
    // rather than Room: Room 2.8.5 (latest published) bundles a kotlin-metadata-jvm reader
    // capped at metadata version 2.3.0, and our Kotlin plugin (2.4.10) emits 2.4.0 metadata —
    // kaptDebugKotlin fails with "Provided Metadata instance has version 2.4.0, while maximum
    // supported version is 2.3.0" (same class of host/extension version-pin bug as the okhttp
    // compileSdk issue, just Room-vs-Kotlin instead of extension-vs-host). No KSP release
    // matches 2.4.10 either. A handful of tables with plain SQL doesn't need an ORM anyway.
}
