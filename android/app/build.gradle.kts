plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.ladepilot"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.ladepilot"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "1.0.$runNumber"
        // Only 64-bit ARM (all current phones) – keeps the map engine's native code from bloating the APK
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // One fixed key, so new APKs install over the old one without uninstalling.
    signingConfigs {
        create("shared") {
            storeFile = file("ladepilot.keystore")
            storePassword = "ladepilot"
            keyAlias = "ladepilot"
            keyPassword = "ladepilot"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("shared") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")

    // Vector map on the phone (MapLibre + OpenFreeMap styles – no API key)
    implementation("org.maplibre.gl:android-sdk:11.5.2")

    // Android Auto (Car App Library)
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")
}
