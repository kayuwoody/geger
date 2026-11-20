package com.geger.trial.settings

import android.content.Context
import com.geger.positioning.config.TrialConfig
import com.geger.positioning.map.MapLoadResult
import com.geger.positioning.map.MapLoader
import com.geger.positioning.map.VenueMap
import java.io.File

/** A validated map plus its original text (copied into each session folder as-is). */
data class LoadedMap(val text: String, val map: VenueMap, val warnings: List<String>)

/**
 * The config file on the phone (SPEC.md rule 7: editable without rebuilding).
 *
 * Concept: the app runs with the defaults built into positioning-core unless the tester has
 * loaded a config.json, which is then kept in the app's private storage and used for every session
 * until replaced or reset. Only the fields the file names change; the rest keep their defaults.
 */
class ConfigStore(context: Context) {
    private val file = File(context.filesDir, "config.json")

    /** The config to use, and whether it is custom. A broken saved file falls back to defaults. */
    fun load(): Pair<TrialConfig, String> {
        if (!file.isFile) return TrialConfig.defaults() to "Built-in defaults"
        return try {
            TrialConfig.fromJson(file.readText()) to "Custom config.json"
        } catch (e: IllegalArgumentException) {
            TrialConfig.defaults() to "Saved config was invalid, using defaults (${e.message})"
        }
    }

    /** Checks and saves a new config file's [text]. Returns an error message, or null on success. */
    fun import(text: String): String? = try {
        TrialConfig.fromJson(text)
        file.writeText(text)
        null
    } catch (e: IllegalArgumentException) {
        e.message
    }

    /** Goes back to the built-in defaults. */
    fun reset() {
        file.delete()
    }
}

/**
 * The map chosen for the next sessions, kept in the app's private storage.
 * A map with errors is refused (with the loader's messages); warnings are allowed and shown.
 */
class MapStore(context: Context) {
    private val file = File(context.filesDir, "maps/current.json")

    /** The saved map, or null if none is chosen (or the saved one no longer validates). */
    fun load(): LoadedMap? {
        if (!file.isFile) return null
        val text = file.readText()
        return (MapLoader.parse(text) as? MapLoadResult.Ok)?.let { LoadedMap(text, it.map, it.warnings) }
    }

    /** Validates and saves a map's [text]. Returns the loaded map, or the error messages. */
    fun import(text: String): Result<LoadedMap> = when (val r = MapLoader.parse(text)) {
        is MapLoadResult.Ok -> {
            file.parentFile?.mkdirs()
            file.writeText(text)
            Result.success(LoadedMap(text, r.map, r.warnings))
        }
        is MapLoadResult.Failed -> Result.failure(IllegalArgumentException(r.errors.joinToString("\n")))
    }

    /** Forgets the chosen map. */
    fun clear() {
        file.delete()
    }
}
