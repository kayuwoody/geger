/*
 * Gradle settings for the Phase 0 positioning trial.
 *
 * Concept: this is a Gradle "multi-project build" — several modules that share one build and
 * one set of dependency versions (see gradle/libs.versions.toml):
 *
 *  - :positioning-core  Plain Kotlin/JVM library holding ALL positioning logic: the map model,
 *                       geometry, routing, coordinate frames and (later) the estimators.
 *  - :replay-cli        JVM command-line tool. Validates maps today; replays recorded sessions
 *                       through positioning-core from milestone M7.
 *  - :app               The Android test app (sensors, AR, screens). It uses positioning-core.
 *
 * Keeping the logic in a plain JVM module means it runs in fast desktop unit tests and in the
 * replay CLI, not only on a phone.
 */

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Every module must use the repositories declared here, so builds stay reproducible.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "geger-trial"

include(":positioning-core", ":replay-cli")

/*
 * The Android app module needs the Android SDK. It is only included when an SDK can be found
 * (local.properties `sdk.dir`, or the ANDROID_HOME / ANDROID_SDK_ROOT environment variables).
 * Concept: this keeps `./gradlew test` working for the pure-JVM modules on machines without the
 * SDK (for example a laptop that only runs the replay CLI), while Android Studio and any machine
 * with the SDK build everything.
 */
val localProperties = java.util.Properties().apply {
    val file = file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val androidSdkAvailable = localProperties.getProperty("sdk.dir") != null ||
    System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null

if (androidSdkAvailable) {
    include(":app")
} else {
    logger.warn("Android SDK not found: building without the :app module. Set sdk.dir in local.properties to include it.")
}
