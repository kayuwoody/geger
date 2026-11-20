/*
 * Root build file.
 *
 * Concept: plugins are declared here with `apply false` so Gradle resolves each plugin version
 * once (from gradle/libs.versions.toml); each module then applies only the plugins it needs.
 */
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
