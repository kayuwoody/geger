package com.geger.trial.recording

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import com.geger.positioning.config.TrialConfig
import com.geger.positioning.log.AsyncLogWriter
import com.geger.positioning.log.RateCheck
import com.geger.positioning.log.RateResult
import com.geger.positioning.log.RecordType
import com.geger.positioning.log.TimeBase
import com.geger.positioning.log.TimebaseCheck
import com.geger.positioning.log.TimebaseResult
import com.geger.positioning.log.record
import com.geger.positioning.qr.QrFix
import com.geger.trial.ar.CameraInfo
import com.geger.trial.qr.CalibSpot
import com.geger.trial.qr.QrDetector
import com.geger.trial.qr.calibPointLine
import com.geger.trial.qr.qrDetectionLine
import com.geger.trial.settings.LoadedMap
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/**
 * What the tester chose on the setup screen; logged in session_start.
 * [plateSizeM] is the frame size used for plates that aren't on the chosen map (e.g. a desk test).
 */
data class SessionSetup(val runType: String, val carryMode: String, val tester: String, val notes: String, val plateSizeM: Double = 0.18)

/** Progress of a calibration capture at one marked spot (shown live, logged as calib_point). */
data class CalibStatus(val spot: CalibSpot, val measured: List<Double>, val done: Boolean, val gaveUp: Boolean) {
    /** Mean measured distance, or NaN before any fix. */
    val meanDistanceM: Double get() = if (measured.isEmpty()) Double.NaN else measured.average()
}

/** Numbers for the live display while recording. */
data class LiveStats(
    val elapsedS: Double,
    val counts: Map<String, Long>,
    val dropped: Long,
    val trackingState: String,
    val failureReason: String,
    /** The most recent QR sighting's result (accepted or not), and how long ago it was. */
    val lastFix: QrFix? = null,
    val lastFixAgeS: Double = Double.NaN,
    val fixes: Long = 0,
    val acceptedFixes: Long = 0,
    val calib: CalibStatus? = null,
)

/**
 * The end-of-session report: sensor rates against target, clock checks, drops and file size.
 * [passed] is the M3 desk check: every rate within 10%, nothing dropped, both clocks identified.
 */
data class SessionSummary(
    val id: String,
    val startedAt: String,
    val durationS: Double,
    val runType: String,
    val carryMode: String,
    val rates: List<RateResult>,
    val camera: TimebaseResult,
    val sensors: TimebaseResult,
    val dropped: Long,
    val records: Long,
    val fileBytes: Long,
    val notes: Int,
    val missingSensors: List<String>,
    val error: String?,
    val qrFixes: Long = 0,
    val qrAccepted: Long = 0,
) {
    val passed: Boolean
        get() = error == null && dropped == 0L && rates.all { it.ok } &&
            camera.base != TimeBase.UNKNOWN && sensors.base != TimeBase.UNKNOWN

    /** The summary as JSON (saved next to the log so the sessions list can show it). */
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("started_at", startedAt); put("duration_s", durationS)
        put("run_type", runType); put("carry_mode", carryMode)
        put("rates", buildJsonArray {
            rates.forEach { r ->
                add(buildJsonObject { put("stream", r.stream); put("count", r.count); put("hz", r.hz); put("target_hz", r.targetHz); put("ok", r.ok) })
            }
        })
        put("camera_timebase", timebaseJson(camera)); put("sensor_timebase", timebaseJson(sensors))
        put("dropped", dropped); put("records", records); put("file_bytes", fileBytes); put("notes", notes)
        put("qr_fixes", qrFixes); put("qr_accepted", qrAccepted)
        put("missing_sensors", buildJsonArray { missingSensors.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        error?.let { put("error", it) }
        put("passed", passed)
    }

    private fun timebaseJson(r: TimebaseResult) = buildJsonObject {
        put("base", r.base.name); put("median_delay_ms", if (r.medianDelayMs.isFinite()) r.medianDelayMs else null)
        put("offset_ns", r.offsetToElapsedNs); put("samples", r.samples)
    }
}

/**
 * Records one session: opens the log, writes session_start, runs the sensor recorder, logs every
 * ARCore frame, and on [stop] writes the checks and session_end, compresses the log and saves a
 * summary.
 *
 * Concept: everything ends up in one time-ordered-by-arrival JSON Lines file whose timestamps all
 * share the elapsedRealtime clock (sensor events carry their own measurement time; camera frames
 * carry the receipt time in "t" plus ARCore's raw capture time in "ft", with the "timebase" record
 * saying how to convert "ft"). The session folder also keeps the exact map and config used.
 */
class SessionRecorder(
    private val context: Context,
    private val setup: SessionSetup,
    private val config: TrialConfig,
    private val map: LoadedMap?,
    private val camera: CameraInfo?,
) {
    val id: String = "session-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
    private val startedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(Date())
    val dir = File(context.filesDir, "sessions/$id")
    private val jsonl = File(dir, "$id.jsonl")

    private val startNs = SystemClock.elapsedRealtimeNanos()
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val log: AsyncLogWriter
    private val sensors: SensorRecorder
    private val cameraTimebase = TimebaseCheck()
    private val frames = AtomicLong()
    private val notes = AtomicInteger()
    private var lastFrameStamp = 0L
    @Volatile private var trackingState = "starting"
    @Volatile private var failureReason = ""
    @Volatile private var stopped = false

    // QR plates (M4): every sighting is logged; the latest is shown on screen.
    private val fixIds = AtomicLong()
    private val acceptedFixes = AtomicLong()
    @Volatile private var lastFix: QrFix? = null
    @Volatile private var lastFixNs = 0L
    private val qr = QrDetector(config, map?.map, setup.plateSizeM) { fix, sighting, tNs, stamp -> onFix(fix, sighting, tNs, stamp) }

    // Calibration capture in progress (guarded by calibLock).
    private val calibLock = Any()
    private var calibSpot: CalibSpot? = null
    private var calibIds = mutableListOf<Long>()
    private var calibMeasured = mutableListOf<Double>()
    private var calibDeadlineNs = 0L
    private var calibStatus: CalibStatus? = null

    init {
        dir.mkdirs()
        File(dir, "config.json").writeText(config.toJson())
        map?.let { File(dir, "map.json").writeText(it.text) }
        log = AsyncLogWriter(BufferedWriter(OutputStreamWriter(FileOutputStream(jsonl), Charsets.UTF_8), 1 shl 16))
        val missing = missingSensors()
        log.offer(RecordType.SESSION_START, sessionStartRecord(missing))
        sensors = SensorRecorder(sensorManager) { type, line -> log.offer(type, line) }
        sensors.start() // also fills sensors.missing with the same list
    }

    /** Sensors this phone doesn't have (checked before starting so session_start can list them). */
    private fun missingSensors(): List<String> = listOf(
        Sensor.TYPE_ACCELEROMETER to "accelerometer", Sensor.TYPE_GYROSCOPE to "gyroscope",
        Sensor.TYPE_GAME_ROTATION_VECTOR to "game rotation vector", Sensor.TYPE_ROTATION_VECTOR to "rotation vector",
        Sensor.TYPE_MAGNETIC_FIELD to "magnetometer", Sensor.TYPE_PRESSURE to "barometer",
        Sensor.TYPE_STEP_DETECTOR to "step detector", Sensor.TYPE_PROXIMITY to "proximity", Sensor.TYPE_LIGHT to "light",
    ).filter { sensorManager.getDefaultSensor(it.first) == null }.map { it.second }

    /** session_start: device, app, map, full config, camera config and the tester's choices. */
    private fun sessionStartRecord(missing: List<String>): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val b = record(startNs, RecordType.SESSION_START)
            .str("session", id).str("started_at", startedAt)
            .str("app_version", version)
            .str("device", "${Build.MANUFACTURER} ${Build.MODEL}").str("android", Build.VERSION.RELEASE).int("sdk", Build.VERSION.SDK_INT)
            .str("map_venue", map?.map?.venue).int("map_version", map?.map?.version ?: -1)
            .str("run_type", setup.runType).str("carry_mode", setup.carryMode).str("tester", setup.tester).str("notes", setup.notes)
            .num("default_plate_size_m", setup.plateSizeM)
            .rawJson("config", config.toCompactJson())
            .rawJson("missing_sensors", missing.joinToString(",", "[", "]") { "\"$it\"" })
        camera?.let {
            b.nums("cpu_image", it.cpuWidth.toDouble(), it.cpuHeight.toDouble())
                .nums("texture", it.textureWidth.toDouble(), it.textureHeight.toDouble())
                .nums("fps_range", it.fpsMin.toDouble(), it.fpsMax.toDouble())
        }
        return b.build()
    }

    /**
     * Logs one ARCore frame (called on the GL thread for every redraw; repeats of the same camera
     * image are skipped). Fields: ft = ARCore capture timestamp, ts/fr = tracking state and failure
     * reason, p/q = sensor-oriented camera pose, dq = display-oriented orientation (for the
     * phone-heading rule).
     */
    fun onFrame(frame: Frame) {
        if (stopped) return
        val ft = frame.timestamp
        if (ft == 0L || ft == lastFrameStamp) return
        lastFrameStamp = ft
        val now = SystemClock.elapsedRealtimeNanos()
        cameraTimebase.add(ft, now, System.nanoTime())
        val cam = frame.camera
        val state = cam.trackingState
        val reason = cam.trackingFailureReason
        trackingState = state.name
        failureReason = if (state == TrackingState.TRACKING) "" else reason.name
        val p = cam.pose
        val dp = cam.displayOrientedPose
        val line = record(now, RecordType.ARCORE_FRAME)
            .int("ft", ft).str("ts", state.name).str("fr", reason.name)
            .nums("p", p.tx().toDouble(), p.ty().toDouble(), p.tz().toDouble())
            .nums("q", p.qx().toDouble(), p.qy().toDouble(), p.qz().toDouble(), p.qw().toDouble())
            .nums("dq", dp.qx().toDouble(), dp.qy().toDouble(), dp.qz().toDouble(), dp.qw().toDouble())
            .build()
        log.offer(RecordType.ARCORE_FRAME, line)
        frames.incrementAndGet()
        qr.onFrame(frame)
    }

    /**
     * One QR sighting's result (called on the QR worker thread): log it, remember it for the
     * display, and feed an active calibration capture.
     */
    private fun onFix(fix: QrFix, sighting: com.geger.positioning.qr.QrSighting, tNs: Long, stamp: Long) {
        if (stopped) return
        val id = fixIds.incrementAndGet()
        log.offer(RecordType.QR_DETECTION, qrDetectionLine(id, tNs, stamp, fix, sighting))
        if (fix.accepted) acceptedFixes.incrementAndGet()
        lastFix = fix
        lastFixNs = SystemClock.elapsedRealtimeNanos()
        synchronized(calibLock) {
            val spot = calibSpot ?: return
            if (!fix.accepted || (spot.anchorId != null && spot.anchorId != fix.anchorId)) return
            calibIds += id
            calibMeasured += fix.distanceM
            if (calibIds.size >= 3) finishCalibration(gaveUp = false)
            else calibStatus = CalibStatus(spot, calibMeasured.toList(), done = false, gaveUp = false)
        }
    }

    /**
     * Starts collecting fixes at a tape-marked spot: the next 3 accepted fixes (of [spot]'s plate,
     * if named) within 10 seconds are logged together as a calib_point.
     */
    fun startCalibration(spot: CalibSpot) {
        synchronized(calibLock) {
            calibSpot = spot
            calibIds = mutableListOf()
            calibMeasured = mutableListOf()
            calibDeadlineNs = SystemClock.elapsedRealtimeNanos() + 10_000_000_000L
            calibStatus = CalibStatus(spot, emptyList(), done = false, gaveUp = false)
        }
    }

    /** Logs the calib_point and ends the capture (caller holds calibLock). */
    private fun finishCalibration(gaveUp: Boolean) {
        val spot = calibSpot ?: return
        log.offer(RecordType.CALIB_POINT, calibPointLine(SystemClock.elapsedRealtimeNanos(), spot, calibIds, calibMeasured, gaveUp))
        calibStatus = CalibStatus(spot, calibMeasured.toList(), done = true, gaveUp = gaveUp)
        calibSpot = null
    }

    /** Logs a free-text note from the tester ("crowd", "shiny floor"...). */
    fun note(text: String) {
        if (stopped || text.isBlank()) return
        log.offer(RecordType.NOTE, record(SystemClock.elapsedRealtimeNanos(), RecordType.NOTE).str("text", text.trim()).build())
        notes.incrementAndGet()
    }

    /** Current counts for the live display (also times out a calibration capture after 10 s). */
    fun snapshot(): LiveStats {
        val now = SystemClock.elapsedRealtimeNanos()
        val calib = synchronized(calibLock) {
            if (calibSpot != null && now > calibDeadlineNs) finishCalibration(gaveUp = true)
            calibStatus
        }
        val counts = sensors.counts.mapValues { it.value.get() } + ("arcore_frame" to frames.get())
        return LiveStats(
            (now - startNs) / 1e9, counts, log.droppedSoFar(), trackingState, failureReason,
            lastFix, if (lastFixNs == 0L) Double.NaN else (now - lastFixNs) / 1e9, fixIds.get(), acceptedFixes.get(), calib,
        )
    }

    /**
     * Ends the session: stops sensors, writes the timebase checks and session_end, closes and
     * gzips the log, saves summary.json and returns the summary. Slow (compression): call it off
     * the main thread. [reason] is logged ("user", "app closed"...).
     */
    fun stop(reason: String): SessionSummary {
        stopped = true
        qr.close()
        sensors.stop()
        val endNs = SystemClock.elapsedRealtimeNanos()
        val durationS = (endNs - startNs) / 1e9
        val counts = sensors.counts.mapValues { it.value.get() } + ("arcore_frame" to frames.get())
        val rates = RateCheck.check(counts, durationS)
        val cam = cameraTimebase.result()
        val sen = sensors.timebase.result()
        for ((stream, r) in listOf("arcore_frame" to cam, "sensors" to sen)) {
            log.offer(RecordType.TIMEBASE, record(endNs, RecordType.TIMEBASE)
                .str("stream", stream).str("base", r.base.name).num("median_delay_ms", r.medianDelayMs)
                .int("offset_ns", r.offsetToElapsedNs).int("samples", r.samples).build())
        }
        val countsJson = counts.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
        log.offer(RecordType.SESSION_END, record(endNs, RecordType.SESSION_END)
            .num("duration_s", durationS).str("reason", reason).rawJson("counts", countsJson)
            .int("dropped_so_far", log.droppedSoFar()).int("notes", notes.get())
            .int("qr_fixes", fixIds.get()).int("qr_accepted", acceptedFixes.get()).build())
        val stats = log.close()

        var error = log.error()?.let { "Writing the log failed: ${it.message}" }
        val gz = File(dir, "$id.jsonl.gz")
        try {
            jsonl.inputStream().use { input -> GZIPOutputStream(FileOutputStream(gz)).use { out -> input.copyTo(out, 1 shl 16) } }
            jsonl.delete()
        } catch (e: Exception) {
            error = error ?: "Compressing the log failed (the uncompressed log is kept): ${e.message}"
        }
        val summary = SessionSummary(
            id, startedAt, durationS, setup.runType, setup.carryMode, rates, cam, sen,
            stats.dropped, stats.total, dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            notes.get(), sensors.missing.toList(), error, fixIds.get(), acceptedFixes.get(),
        )
        File(dir, "summary.json").writeText(summary.toJson().toString())
        return summary
    }
}
