/*
 * replay-cli: JVM command-line tool around positioning-core.
 *
 * Today it validates map files (handy after editing a map); from milestone M7 it replays recorded
 * sessions through the estimators. Run with:
 *   ./gradlew :replay-cli:run --args="validate-map ../maps/sample/map.json"
 */
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.geger.replay.MainKt")
}

dependencies {
    implementation(project(":positioning-core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("geger.sampleMap", rootProject.file("../maps/sample/map.json").absolutePath)
}

// `gradlew run` starts in the module folder; run from the repo's android/ folder instead so
// relative paths like ../maps/sample/map.json work as written in the README.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
