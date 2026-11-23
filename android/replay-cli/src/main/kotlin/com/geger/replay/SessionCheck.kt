package com.geger.replay

import com.geger.positioning.log.LogReader
import com.geger.positioning.log.RateCheck
import com.geger.positioning.log.RecordType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile

/**
 * `check-session`: reads a recorded session exactly as the replay will, and reports whether it is
 * complete and healthy (milestone M3's desk check, done on the laptop).
 *
 * Concept: the phone writes its own counts into the final session_end record. Re-counting every
 * record from the file and comparing proves nothing was lost between the phone's memory and the
 * exported file. The rates and clock results are printed alongside, with the same 10% rule the
 * phone's summary screen uses.
 *
 * Accepts the exported zip (the log inside is found automatically), a .jsonl.gz or a plain .jsonl.
 * Exit code 0 = complete and every check passed, 1 = a problem, 2 = bad arguments.
 */
internal fun checkSession(args: List<String>, out: (String) -> Unit, err: (String) -> Unit): Int {
    val path = args.singleOrNull() ?: run { err("Usage: replay-cli check-session <session.zip | session.jsonl.gz>"); return 2 }
    val file = File(path)
    if (!file.isFile) { err("File not found: $path"); return 1 }

    var start: JsonObject? = null
    var end: JsonObject? = null
    val timebases = mutableListOf<JsonObject>()
    val calibPoints = mutableListOf<JsonObject>()
    var qrSightings = 0
    var qrAccepted = 0
    val qrReasons = sortedMapOf<String, Int>()
    val beaconRssi = sortedMapOf<String, MutableList<Int>>()
    val wifiIds = HashSet<String>()
    val rttIds = HashSet<String>()
    var rttOk = 0
    var bestGnss = Double.POSITIVE_INFINITY
    var maxSatsUsed = 0
    var radioStatus: JsonObject? = null
    val counts = sortedMapOf<String, Long>()
    var firstT = Long.MAX_VALUE
    var lastT = Long.MIN_VALUE
    try {
        openLog(file).use { input ->
            LogReader.forEach(input) { e ->
                counts.merge(LogReader.streamOf(e), 1L, Long::plus)
                firstT = minOf(firstT, e.t)
                lastT = maxOf(lastT, e.t)
                when (e.type) {
                    RecordType.SESSION_START -> start = e.fields
                    RecordType.SESSION_END -> end = e.fields
                    RecordType.TIMEBASE -> timebases += e.fields
                    RecordType.CALIB_POINT -> calibPoints += e.fields
                    RecordType.RADIO_STATUS -> radioStatus = e.fields
                    RecordType.BLE -> {
                        val name = e.fields["beacon"]?.jsonPrimitive?.contentOrNull ?: e.fields["key"]?.jsonPrimitive?.content ?: "?"
                        e.fields["rssi"]?.jsonPrimitive?.intOrNull?.let { beaconRssi.getOrPut(name) { mutableListOf() } += it }
                    }
                    RecordType.WIFI_SCAN -> (e.fields["aps"] as? JsonArray)?.forEach { ap ->
                        (ap as? JsonObject)?.get("id")?.jsonPrimitive?.content?.let(wifiIds::add)
                    }
                    RecordType.WIFI_RTT -> (e.fields["results"] as? JsonArray)?.forEach { r ->
                        val o = r as? JsonObject ?: return@forEach
                        if (o["ok"]?.jsonPrimitive?.content == "true") { rttOk++; o["id"]?.jsonPrimitive?.content?.let(rttIds::add) }
                    }
                    RecordType.GNSS_FIX -> e.fields["accuracy_m"]?.jsonPrimitive?.doubleOrNull?.let { bestGnss = minOf(bestGnss, it) }
                    RecordType.GNSS_STATUS -> e.fields["used"]?.jsonPrimitive?.intOrNull?.let { maxSatsUsed = maxOf(maxSatsUsed, it) }
                    RecordType.QR_DETECTION -> {
                        qrSightings++
                        if (e.fields["accepted"]?.jsonPrimitive?.content == "true") qrAccepted++
                        else (e.fields["reason"]?.jsonPrimitive?.content ?: "?").substringBefore(" (").let { r ->
                            // Group by the reason's wording without its numbers.
                            val key = r.replace(Regex("[0-9.]+"), "#")
                            qrReasons.merge(key, 1, Int::plus)
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        err("ERROR: could not read the log: ${e.message}")
        return 1
    }

    val problems = mutableListOf<String>()
    val s = start
    if (s == null) problems += "no session_start record"
    else out("Session ${s.str("session")} on ${s.str("device")} (Android ${s.str("android")}), run type ${s.str("run_type")}, app ${s.str("app_version")}")

    val e = end
    val duration = e?.get("duration_s")?.jsonPrimitive?.doubleOrNull ?: ((lastT - firstT) / 1e9)
    out(String.format(Locale.ROOT, "Duration %.1f s, %d records", duration, counts.values.sum()))
    if (e == null) problems += "no session_end record (the recording was interrupted)"

    // Compare our count of each stream with the phone's own count in session_end.
    val phoneCounts = (e?.get("counts") as? JsonObject)?.mapValues { it.value.jsonPrimitive.longOrNull ?: -1L }.orEmpty()
    for ((stream, phone) in phoneCounts) {
        val ours = counts[stream] ?: 0L
        if (ours != phone) problems += "stream $stream: phone counted $phone, file has $ours"
    }
    if (e != null) out(if (phoneCounts.isNotEmpty() && problems.none { it.startsWith("stream") }) "Counts match the phone's own totals for ${phoneCounts.size} streams." else "Counts checked.")
    val dropped = e?.get("dropped_so_far")?.jsonPrimitive?.longOrNull ?: 0L
    if (dropped > 0) problems += "$dropped records were dropped on the phone"

    out("Rates (target +/-10%):")
    for (r in RateCheck.check(counts, duration)) {
        out(String.format(Locale.ROOT, "  %s %-14s %7.1f Hz (target %.0f)", if (r.ok) "OK  " else "FAIL", r.stream, r.hz, r.targetHz))
        if (!r.ok) problems += "rate ${r.stream} is ${"%.1f".format(Locale.ROOT, r.hz)} Hz, target ${r.targetHz.toInt()}"
    }
    for (stream in listOf("pressure", "step", "proximity_light", "note")) out("  $stream: ${counts[stream] ?: 0}")

    for (tb in timebases) {
        val base = tb.str("base")
        val delay = tb["median_delay_ms"]?.jsonPrimitive?.doubleOrNull
        out("Clock for ${tb.str("stream")}: $base (median delay ${if (delay != null) String.format(Locale.ROOT, "%.1f", delay) else "?"} ms)")
        if (base == "UNKNOWN") problems += "clock for ${tb.str("stream")} could not be identified"
    }
    if (timebases.isEmpty() && e != null) problems += "no timebase records"

    if (qrSightings > 0) {
        out("QR sightings: $qrSightings, accepted $qrAccepted")
        qrReasons.forEach { (r, n) -> out("  rejected $n× : $r") }
    }
    radioStatus?.let { rs ->
        val off = listOf("bluetooth_on" to "Bluetooth off", "location_on" to "Location off", "wifi_on" to "Wi-Fi off",
            "bluetooth_permitted" to "Bluetooth not permitted", "location_permitted" to "Location not permitted")
            .filter { rs[it.first]?.jsonPrimitive?.content == "false" }.map { it.second }
        out("Radios started: ${rs["started"] ?: "[]"}" + if (off.isEmpty()) "" else " (${off.joinToString()})")
    }
    if (beaconRssi.isNotEmpty() || (counts["ble"] ?: 0) > 0) {
        out("Beacons heard: ${beaconRssi.size}")
        for ((name, rssi) in beaconRssi) {
            val sorted = rssi.sorted()
            out(String.format(Locale.ROOT, "  %-28s %6d broadcasts, %5.1f/s, median RSSI %d dBm (%d to %d)",
                name, rssi.size, rssi.size / duration, sorted[sorted.size / 2], sorted.first(), sorted.last()))
        }
    }
    if ((counts["wifi_scan"] ?: 0) > 0) out("Wi-Fi: ${counts["wifi_scan"]} scans, ${wifiIds.size} distinct access points; ranging: ${counts["wifi_rtt"] ?: 0} rounds, $rttOk distances from ${rttIds.size} access points")
    if ((counts["gnss_status"] ?: 0) > 0 || (counts["gnss_fix"] ?: 0) > 0) {
        out("GPS: ${counts["gnss_fix"] ?: 0} fixes" + (if (bestGnss.isFinite()) String.format(Locale.ROOT, " (best ±%.0f m)", bestGnss) else "") + ", at most $maxSatsUsed satellites used")
    }
    if (calibPoints.isNotEmpty()) out("Calibration spots (measured distance vs tape):")
    for (c in calibPoints) {
        val grid = c["grid_distance_m"]?.jsonPrimitive?.doubleOrNull ?: Double.NaN
        val angle = c["grid_angle_deg"]?.jsonPrimitive?.doubleOrNull ?: Double.NaN
        val measured = (c["measured_distance_m"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.doubleOrNull }.orEmpty()
        val gaveUp = c["gave_up"]?.jsonPrimitive?.content == "true"
        if (measured.isEmpty()) { out(String.format(Locale.ROOT, "  %5.1f m at %3.0f°: no accepted fixes%s", grid, angle, if (gaveUp) " (gave up)" else "")); continue }
        val mean = measured.average()
        out(String.format(Locale.ROOT, "  %5.1f m at %3.0f°: measured %.3f m (%+.1f%%) from %d fix(es)%s", grid, angle, mean, (mean - grid) / grid * 100, measured.size, if (gaveUp) ", gave up early" else ""))
    }

    return if (problems.isEmpty()) {
        out("RESULT: PASS")
        0
    } else {
        problems.forEach { err("PROBLEM: $it") }
        err("RESULT: ${problems.size} problem(s)")
        1
    }
}

/** A text field of a JSON object, or "?" if missing. */
private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content ?: "?"

/** Opens the session log inside [file]: a zip (first *.jsonl.gz or *.jsonl entry) or the log itself. */
private fun openLog(file: File): InputStream {
    if (!file.name.endsWith(".zip")) return LogReader.open(file)
    val zip = ZipFile(file)
    val entry = zip.entries().asSequence().filter { !it.isDirectory }
        .firstOrNull { it.name.endsWith(".jsonl.gz") } ?: zip.entries().asSequence().firstOrNull { it.name.endsWith(".jsonl") }
        ?: run { zip.close(); throw IllegalArgumentException("no .jsonl or .jsonl.gz log inside ${file.name}") }
    val stream = LogReader.open(zip.getInputStream(entry))
    return object : java.io.FilterInputStream(stream) {
        override fun close() { super.close(); zip.close() }
    }
}
