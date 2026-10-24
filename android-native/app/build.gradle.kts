plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.streambox.nativeapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.streambox.nativeapp"
        minSdk = 24
        targetSdk = 34
        // versionCode is FROZEN at 50 (0.50 request): every preview build
        // installs over every other one - forward updates AND rolling back
        // to an older APK both work from the package installer. The feature
        // version lives in versionName. (Before this, each CI run minted a
        // fresh debug key AND versionCode only moved up, so 'update' errored
        // and older builds could never be reinstalled.)
        versionCode = 50
        versionName = "0.76"
        // Backend: Cloudflare Worker (catalog proxy + anime mapping).
        // Provider keys live in the Worker as Wrangler secrets - never here.
        buildConfigField("String", "API_BASE", "\"https://streambox-api.dazoarmando.workers.dev\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // focusProperties (sheet focus traps) is experimental-but-stable in
        // the pinned BOM - opt in once here so it never breaks the build.
        // BringIntoViewRequester (caption/hero reveal on focus, 0.49) rides
        // the same pattern: experimental-but-stable foundation API.
        freeCompilerArgs += "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi"
        freeCompilerArgs += "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
    }

    // ONE preview key for every build, local and CI (0.51): the runner used
    // to mint a fresh debug keystore per run, so every APK carried a new
    // signature and 'update' failed with an install error. The keystore is
    // committed ON PURPOSE - it is the preview channel's identity, not a
    // secret (provider keys stay in Cloudflare, OAuth stays out of the app).
    signingConfigs {
        create("preview") {
            storeFile = rootProject.file("preview.keystore")
            storePassword = "streambox"
            keyAlias = "streambox"
            keyPassword = "streambox"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("preview")
        }
        debug {
            signingConfig = signingConfigs.getByName("preview")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("io.coil-kt:coil-compose:2.6.0")
    // Direct HLS slot (pre-build for a future authorized source): native
    // hardware-decoded HLS. Pinned to 1.4.1 - the Java-era line matching
    // this toolchain (AGP 8.5.2 / Kotlin 2.0.20 / compileSdk 34); newer
    // lines pull Kotlin metadata + core versions past compileSdk 34.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
