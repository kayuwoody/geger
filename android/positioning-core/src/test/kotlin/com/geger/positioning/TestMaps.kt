package com.geger.positioning

import com.geger.positioning.map.MapLoadResult
import com.geger.positioning.map.MapLoader
import com.geger.positioning.map.VenueMap
import java.io.File

/**
 * Shared helpers for tests that need a map.
 *
 * Concept: most tests use the hand-written sample map in the repo (maps/sample/map.json), whose
 * path Gradle passes in as the `geger.sampleMap` system property. Tests that need a broken or
 * special-purpose map build one from a small JSON string instead.
 */
object TestMaps {

    /** Path of the shared sample map, as configured in positioning-core/build.gradle.kts. */
    val sampleFile: File
        get() = File(requireNotNull(System.getProperty("geger.sampleMap")) { "geger.sampleMap not set" })

    /** The sample map's raw JSON text. */
    fun sampleText(): String = sampleFile.readText()

    /** The sample map, loaded and validated; fails the test if it doesn't load cleanly. */
    fun sample(): VenueMap = loadOk(sampleText())

    /** Loads [json] and returns the map, failing the test with the loader's errors if it fails. */
    fun loadOk(json: String): VenueMap = when (val r = MapLoader.parse(json)) {
        is MapLoadResult.Ok -> r.map
        is MapLoadResult.Failed -> throw AssertionError("expected map to load, got errors: ${r.errors}")
    }

    /**
     * A minimal valid one-level map wrapped around the given JSON [sections] (e.g. extra
     * "walkable", "nodes", "edges" arrays). Sections not given default to a 10 × 10 m room.
     */
    fun minimal(
        walkable: String = """[{"level": "L1", "polygon": [[0,0],[10,0],[10,10],[0,10]]}]""",
        obstacles: String = "[]",
        nodes: String = "[]",
        edges: String = "[]",
        levels: String = """[{"id": "L1", "name": "Level 1", "elevation_m": 0}]""",
        extra: String = "",
    ): String = """
        {
          "schema": 1, "venue": "test", "version": 1, "units": "m",
          "levels": $levels,
          "walkable": $walkable,
          "obstacles": $obstacles,
          "nodes": $nodes,
          "edges": $edges
          $extra
        }
    """.trimIndent()
}
