import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.protobuf")
}

val abis = (project.findProperty("abis") as String? ?: "arm64-v8a,x86_64").split(",")
val splitAbi = project.hasProperty("splitAbi")

android {
    namespace = "dev.moku.mobile"
    // Extensions are built against okhttp 5, which needs compileSdk 36.
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.moku.mobile"
        minSdk = 26
        targetSdk = 36
        // The in-app updater and Android both compare versionCode, so it's derived from the name and only ever grows.
        versionName = "0.1.0"
        versionCode = versionName!!.split(".").map(String::toInt).let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
        resValue("string", "app_name", "Moku")

        // Must match the ABIs scripts/build-tsunagu.sh built, or Android picks one with no Go core.
        // -PsplitAbi builds one APK per ABI instead (what releases ship); AGP won't take both filters at once.
        if (!splitAbi) ndk { abiFilters += abis }
    }

    // Kept out of git; without it a release build stays unsigned.
    val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    splits {
        abi {
            isEnable = splitAbi
            reset()
            include(*abis.toTypedArray())
            isUniversalApk = true
        }
    }

    buildTypes {
        // A separate package, so a debug build never collides with an installed release (different signing keys
        // can't update each other, and the only way out would be uninstalling and losing the library).
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Moku Debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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

    packaging {
        // okhttp and okhttp-brotli both ship this; nothing reads it on Android.
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

val grpcVersion = "1.84.2"
val protobufVersion = "4.36.2"

dependencies {
    implementation(files("libs/tsunagu.aar"))
    implementation("io.grpc:grpc-okhttp:$grpcVersion")
    implementation("io.grpc:grpc-protobuf-lite:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("com.google.protobuf:protobuf-javalite:$protobufVersion")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Runtime classes a Mihon/Tachiyomi-format extension's classloader resolves against
    // (vendored from Mihon's source-api under app/src/.../eu/kanade/tachiyomi/...).
    implementation("uy.kohesive.injekt:injekt-core:1.16.1")
    implementation("io.reactivex:rxjava:1.3.8")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("com.squareup.okhttp3:okhttp-brotli:5.4.0")
    implementation("com.squareup.okhttp3:okhttp-zstd:5.4.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:5.4.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-okio:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("app.cash.quickjs:quickjs-android:0.9.2")

    // Encrypted local storage for the self-hosted Tsunagu server URL + bearer token.
    implementation("androidx.security:security-crypto:1.1.0")

    // Tracker sign-in opens in a browser tab, not a WebView.
    implementation("androidx.browser:browser:1.8.0")

    // Downloads run as WorkManager jobs so they survive the app being killed and retry on their own.
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    testImplementation("junit:junit:4.13.2")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") { option("lite") }
            }
            task.plugins {
                create("grpc") { option("lite") }
            }
        }
    }
}
