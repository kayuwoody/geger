/*
 * :app — the Android test app for the positioning trial.
 *
 * In milestone M1 this is only a skeleton that proves the toolchain works end to end: Kotlin,
 * Jetpack Compose, and positioning-core running on Android. Sensors, ARCore and the screens
 * arrive in later milestones (see SPEC.md, "Milestones").
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
        versionCode = 1
        versionName = "0.1.0-m1"
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

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
}
