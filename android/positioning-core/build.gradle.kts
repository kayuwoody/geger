/*
 * positioning-core: plain Kotlin/JVM library with all positioning logic.
 *
 * Concept: no Android imports are allowed here. That lets the same code run in three places —
 * desktop unit tests, the replay CLI and the Android app — so whatever we tune at a desk is
 * exactly what runs on the phone.
 */
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Compile to Java 17 bytecode so the Android app (which targets Java 17) can consume this library.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // JSON parsing for map.json and the config file; exposed to modules that use the core.
    api(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Tests load the shared sample map from the repo's maps/ folder.
    systemProperty("geger.sampleMap", rootProject.file("../maps/sample/map.json").absolutePath)
    // Fixtures shared with the browser map editor's tests (see FixtureParityTest).
    systemProperty("geger.fixturesDir", rootProject.file("../maps/fixtures").absolutePath)
    inputs.dir(rootProject.file("../maps"))
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
