package com.geger.positioning.log

import kotlin.math.abs

/*
 * The M3 self-checks the app shows at the end of a recording (SPEC.md, milestone M3):
 * sensor rates within 10% of target, and which clock ARCore's frame timestamps use.
 */

/** Which clock a timestamp stream turned out to use. */
enum class TimeBase {
    /** SystemClock.elapsedRealtimeNanos() — the base the whole log uses. No conversion needed. */
    ELAPSED_REALTIME,
    /** System.nanoTime() (stops while the phone sleeps). Convert by adding the measured offset. */
    MONOTONIC,
    /** Neither matched (or too few samples). Timestamps must not be compared across streams. */
    UNKNOWN,
}

/** Result of [TimebaseCheck]: the base, the median delivery delay, and the offset to add to raw stamps. */
data class TimebaseResult(val base: TimeBase, val medianDelayMs: Double, val offsetToElapsedNs: Long, val samples: Int)

/**
 * Works out which clock a stream of event timestamps uses, by comparing each event's own
 * timestamp with the moment the app received it, on both candidate clocks.
 *
 * Concept: an event is always received a little AFTER it happened — typically a few to a few tens
 * of milliseconds. So on the right clock, (received − stamped) is a small positive delay; on the
 * wrong clock it is huge or negative (the two clocks differ by however long the phone has slept
 * since boot). The median over many events ignores occasional hiccups.
 */
class TimebaseCheck(private val maxSamples: Int = 600) {
    private val delaysElapsed = ArrayList<Long>()
    private val delaysMono = ArrayList<Long>()
    private var offset = 0L

    /**
     * Records one event: [stampNs] is the event's own timestamp, [receivedElapsedNs] and
     * [receivedMonoNs] are SystemClock.elapsedRealtimeNanos() and System.nanoTime() read together
     * when the app got it.
     */
    @Synchronized
    fun add(stampNs: Long, receivedElapsedNs: Long, receivedMonoNs: Long) {
        if (delaysElapsed.size >= maxSamples) return
        delaysElapsed += receivedElapsedNs - stampNs
        delaysMono += receivedMonoNs - stampNs
        offset = receivedElapsedNs - receivedMonoNs
    }

    /**
     * The verdict. A clock "matches" if its median delay is between 0 and 1 second.
     * Needs at least 10 samples; otherwise [TimeBase.UNKNOWN].
     */
    @Synchronized
    fun result(): TimebaseResult {
        val n = delaysElapsed.size
        if (n < 10) return TimebaseResult(TimeBase.UNKNOWN, Double.NaN, 0, n)
        val dE = median(delaysElapsed)
        val dM = median(delaysMono)
        val plausible = { d: Long -> d in 0..1_000_000_000L }
        return when {
            plausible(dE) -> TimebaseResult(TimeBase.ELAPSED_REALTIME, dE / 1e6, 0, n)
            plausible(dM) -> TimebaseResult(TimeBase.MONOTONIC, dM / 1e6, offset, n)
            else -> TimebaseResult(TimeBase.UNKNOWN, minOf(abs(dE), abs(dM)) / 1e6, 0, n)
        }
    }

    private fun median(v: List<Long>): Long = v.sorted()[v.size / 2]
}

/** One stream's measured rate against its target. */
data class RateResult(val stream: String, val count: Long, val hz: Double, val targetHz: Double, val ok: Boolean)

/**
 * Compares measured record rates with the targets in SPEC.md's Logging table.
 *
 * Concept: Android treats a requested sensor rate as a hint, and phones differ; the M3 check is
 * that each stream arrives within ±10% of its target over a desk session. Streams without a
 * fixed rate (steps, pressure, notes) are reported but not judged.
 */
object RateCheck {

    /** Target rates in Hz per stream, as in SPEC.md ("arcore_frame" is the camera's ~30 Hz). */
    val TARGETS: Map<String, Double> = linkedMapOf(
        "arcore_frame" to 30.0,
        "imu:a" to 100.0,
        "imu:g" to 100.0,
        "rotation:game" to 50.0,
        "rotation:rot" to 50.0,
        "magnetometer" to 50.0,
    )

    /** Allowed relative error (10%). */
    const val TOLERANCE = 0.10

    /** Rates for every targeted stream over [durationS] seconds, from per-stream [counts]. */
    fun check(counts: Map<String, Long>, durationS: Double): List<RateResult> = TARGETS.map { (stream, target) ->
        val count = counts[stream] ?: 0L
        val hz = if (durationS > 0) count / durationS else 0.0
        RateResult(stream, count, hz, target, abs(hz - target) <= TOLERANCE * target)
    }
}
