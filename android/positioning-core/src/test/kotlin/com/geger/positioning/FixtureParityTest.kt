package com.geger.positioning

import com.geger.positioning.map.MapLoadResult
import com.geger.positioning.map.MapLoader
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Shared-fixture check between this Kotlin loader and the browser map editor (tools/map-editor/map-core.js).
 *
 * Concept: the editor re-implements map validation in JavaScript so it can warn before export.
 * Two implementations can drift apart, so both run against the same files in maps/fixtures/:
 * every `ok-*.json` must load and every `bad-*.json` must be rejected, in Kotlin (here) and in
 * JavaScript (tools/map-editor/test/map-core.test.js). One dynamic test per fixture file.
 */
class FixtureParityTest {

    @TestFactory
    fun `shared fixtures load or fail as their names say`(): List<DynamicTest> {
        val dir = File(requireNotNull(System.getProperty("geger.fixturesDir")) { "geger.fixturesDir not set" })
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        assertTrue(files.size >= 10) { "expected the fixture set in $dir" }
        return files.map { file ->
            DynamicTest.dynamicTest(file.name) {
                val result = MapLoader.loadFile(file)
                when {
                    file.name.startsWith("ok-") -> assertTrue(result is MapLoadResult.Ok) { "${file.name} should load: $result" }
                    file.name.startsWith("bad-") -> assertTrue(result is MapLoadResult.Failed) { "${file.name} should be rejected" }
                }
            }
        }
    }
}
