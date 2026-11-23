package com.geger.replay

import com.geger.positioning.config.TrialConfig
import com.geger.positioning.map.MapLoadResult
import com.geger.positioning.map.MapLoader
import java.io.File
import kotlin.system.exitProcess

/**
 * Entry point of the replay CLI.
 *
 * Commands:
 *  - `validate-map <map.json>`    load a map, print errors and warnings, exit 1 if unusable
 *  - `validate-config <config.json>` check a config file and print the full resulting config
 *  - `check-session <zip|log>`    read a recorded session and confirm its counts and rates (M3 check)
 *  - `replay ...`                 replays a recorded session (arrives in milestone M7)
 *
 * Concept: the CLI is a thin shell. All real work happens in positioning-core, so what the CLI
 * reports is exactly what the phone app would conclude from the same files.
 */
fun main(args: Array<String>) {
    val code = runCli(args.toList(), out = ::println, err = { System.err.println(it) })
    exitProcess(code)
}

/**
 * Runs one CLI command and returns the process exit code (0 = success).
 * Output goes through [out] and [err] so tests can capture it without spawning a process.
 */
fun runCli(args: List<String>, out: (String) -> Unit, err: (String) -> Unit): Int {
    val command = args.firstOrNull()
    return when (command) {
        "validate-map" -> validateMap(args.drop(1), out, err)
        "validate-config" -> validateConfig(args.drop(1), out, err)
        "check-session" -> checkSession(args.drop(1), out, err)
        "replay" -> {
            err("replay is not implemented yet (milestone M7).")
            2
        }
        else -> {
            err(USAGE)
            2
        }
    }
}

private const val USAGE = """Usage:
  replay-cli validate-map <map.json>
  replay-cli validate-config <config.json>
  replay-cli check-session <session.zip | session.jsonl.gz | session.jsonl>
  replay-cli replay ...            (milestone M7)"""

/**
 * Loads and validates a map file, printing a short summary, every warning and every error.
 * Exit code 0 if the map is usable (warnings allowed), 1 if it has errors, 2 for bad arguments.
 */
private fun validateMap(args: List<String>, out: (String) -> Unit, err: (String) -> Unit): Int {
    val path = args.singleOrNull() ?: run { err(USAGE); return 2 }
    return when (val r = MapLoader.loadFile(File(path))) {
        is MapLoadResult.Ok -> {
            val m = r.map
            out("OK: ${m.venue} v${m.version} - ${m.levels.size} levels, ${m.nodes.size} nodes, ${m.edges.size} edges, " +
                "${m.anchors.size} anchors, ${m.checkpoints.size} checkpoints, ${m.destinations.size} destinations, ${m.beacons.size} beacons")
            r.warnings.forEach { out("WARNING: $it") }
            0
        }
        is MapLoadResult.Failed -> {
            r.errors.forEach { err("ERROR: $it") }
            r.warnings.forEach { err("WARNING: $it") }
            err("Map rejected: ${r.errors.size} error(s).")
            1
        }
    }
}

/** Parses a config file and prints the complete config it produces (defaults filled in). */
private fun validateConfig(args: List<String>, out: (String) -> Unit, err: (String) -> Unit): Int {
    val path = args.singleOrNull() ?: run { err(USAGE); return 2 }
    val file = File(path)
    if (!file.isFile) {
        err("Config file not found: $path")
        return 1
    }
    return try {
        out(TrialConfig.fromJson(file.readText()).toJson())
        0
    } catch (e: IllegalArgumentException) {
        err("ERROR: ${e.message}")
        1
    }
}
