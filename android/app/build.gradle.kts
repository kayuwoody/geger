/*
 * :app — the Android test app for the positioning trial.
 *
 * Milestones M3–M4: reads QR plates and computes position fixes from them (M4), and records every sensor the positioning needs (ARCore camera pose, IMU, rotation
 * vectors, magnetometer, barometer, steps, proximity/light) into a session log, checks sensor rates
 * and clocks at the end, and exports sessions as zip files. Later milestones add the QR fix (M4),
 * the estimators and map (M5) and the AR overlay (M6). See SPEC.md, "Milestones".
 *
 * Note: Android Gradle Plugin 9 compiles Kotlin itself ("built-in Kotlin"), so the separate
 * org.jetbrains.kotlin.android plugin is not applied here.
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.geger.trial"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.geger.trial"
        // Android 10+. The S22/S23 Ultra run far newer; this just keeps ARCore and the camera APIs simple.
        minSdk = 29
        targetSdk = 37
        versionCode = 4
        versionName = "0.4.0-m4"
        // Both test phones (S22/S23 Ultra) are 64-bit ARM; leaving out other chip types roughly
        // halves the APK (ARCore and ML Kit ship native code per type).
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":positioning-core"))

    implementation(libs.arcore)
    // QR reading, with the model bundled in the app (works offline).
    implementation(libs.mlkit.barcode)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
}
