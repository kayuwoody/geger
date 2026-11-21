package com.geger.positioning.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/*
 * The single config file for every tunable number (SPEC.md rule 7).
 *
 * Concept: each value here is a starting guess from the spec, to be tuned from logged walks.
 * Keeping them in one JSON file — with these defaults in code — means a tester can change a
 * threshold on the phone without a rebuild, the replay CLI can sweep values, and every session
 * logs exactly which values it ran with. A JSON file only needs the fields it changes; everything
 * missing keeps its default. Unknown field names are rejected so a typo can't silently do nothing.
 */

/** QR detection and fix gating (SPEC.md, "QR plates and the QR fix"). */
@Serializable
data class QrConfig(
    /** How often the CPU image is scanned for plates, per second. */
    val detectionHz: Double = 5.0,
    /** Reject a fix whose pose reprojects the frame corners worse than this, in pixels. */
    val maxReprojectionPx: Double = 2.0,
    /** Reject a fix viewed more obliquely than this (angle between plate normal and line of sight). */
    val maxViewAngleDeg: Double = 65.0,
    /** Reject a fix whose frame side spans fewer pixels than this in the image. */
    val minFrameSidePx: Double = 40.0,
    /** Reject a fix whose leftover tilt (after reducing to yaw + translation) exceeds this. */
    val maxTiltDeg: Double = 10.0,
    /** Gravity check: the chosen pose's plate +y must be within this of world up. */
    val gravityUpToleranceDeg: Double = 20.0,
    /**
     * Mirror ambiguity gate: reject a fix when the mirror-image vertical pose fits nearly as well —
     * its extra squared corner error is under minMirrorChi2 × cornerNoisePx² — AND puts the camera
     * further than maxMirrorSeparationM (or 5% of the distance) from the chosen pose.
     */
    val minMirrorChi2: Double = 16.0,
    val maxMirrorSeparationM: Double = 0.3,
    /** Assumed 1σ error of each fitted frame corner, in pixels (feeds each fix's uncertainty and the mirror gate). */
    val cornerNoisePx: Double = 0.5,
    /** Fix position uncertainty floor: σ = base + perMetre × distance (combined with the per-fix estimate). */
    val sigmaPosBaseM: Double = 0.05,
    val sigmaPosPerMetre: Double = 0.03,
    /** Fix heading uncertainty: σ = base + perMetre × distance. */
    val sigmaHeadingBaseDeg: Double = 2.0,
    val sigmaHeadingPerMetreDeg: Double = 0.5,
)

/** Step counting (E2 and E3's step mode). */
@Serializable
data class StepConfig(
    /** Default stride in metres, replaced by the tester's calibration. */
    val stepLengthM: Double = 0.70,
)

/** E3 particle filter (SPEC.md, "E3: fused particle filter"). */
@Serializable
data class FilterConfig(
    val particleCount: Int = 1000,
    /** Random seed, so replays are repeatable. */
    val seed: Long = 42,
    val stepScaleSigma: Double = 0.1,
    /** ARCore move noise: fraction of the distance moved, plus a fixed amount per update. */
    val arMoveNoiseFraction: Double = 0.02,
    val arMoveNoiseBaseM: Double = 0.02,
    val yawDriftDegPerUpdate: Double = 0.1,
    val stepLengthNoiseFraction: Double = 0.10,
    val stepDirectionNoiseDeg: Double = 5.0,
    /** Weight multiplier for a particle whose move crosses a wall (small, never zero). */
    val wallPenalty: Double = 1e-3,
    /** Redraw half the particles around a QR fix if the effective count falls below this fraction. */
    val reseedEffectiveFraction: Double = 0.10,
    /** Particles within this distance of a matching escalator/lift/stairs edge may change level. */
    val floorChangeReachM: Double = 15.0,
    /** Weight multiplier for particles that cannot explain a detected floor change. */
    val floorChangeOtherPenalty: Double = 0.01,
    /** Resample when the effective particle count falls below this fraction. */
    val resampleEffectiveFraction: Double = 0.5,
)

/** Barometer floor detection (SPEC.md, "Floor detection"). */
@Serializable
data class FloorConfig(
    val medianWindowS: Double = 2.0,
    /** Height change per hPa of pressure drop, near sea level. */
    val metresPerHpa: Double = 8.3,
    /** A floor change needs this fraction of the gap to the adjacent level... */
    val levelGapFraction: Double = 0.6,
    /** ...within this time window... */
    val changeWindowS: Double = 60.0,
    /** ...and then a steady height for this long. */
    val holdS: Double = 3.0,
)

/** Phone mode detection (SPEC.md, "Phone mode"). */
@Serializable
data class PhoneModeConfig(
    val upMaxFromHorizontalDeg: Double = 35.0,
    val flatMaxFromDownDeg: Double = 40.0,
    val pocketMaxLux: Double = 10.0,
    val pocketHoldS: Double = 1.0,
    val hysteresisS: Double = 0.5,
    /** Phone-heading rule: within this of straight down, use the top edge instead of the camera axis. */
    val headingFlatThresholdDeg: Double = 30.0,
    /** Assumed camera height above the floor when no QR fix has measured it. */
    val defaultPhoneHeightM: Double = 1.4,
)

/** Route search and the AR overlay (SPEC.md, "AR overlay"). */
@Serializable
data class DisplayConfig(
    /** Extra cost in metres added to lift edges, standing in for waiting time. */
    val liftPenaltyM: Double = 20.0,
    val replanDistanceM: Double = 5.0,
    val replanIntervalS: Double = 5.0,
    val chevronSpacingM: Double = 1.0,
    val chevronLookaheadM: Double = 20.0,
    val pinHeightM: Double = 2.0,
    /** Time constant of the low-pass filter that steadies the AR overlay. */
    val overlaySmoothingS: Double = 1.0,
)

/** Debug logging switches (SPEC.md, "Logging"). */
@Serializable
data class LoggingConfig(
    val particleSnapshots: Boolean = false,
    /** Save one low-resolution camera frame per second. Off by default (PDPA). */
    val debugFrames: Boolean = false,
)

/** The whole config file. */
@Serializable
data class TrialConfig(
    val qr: QrConfig = QrConfig(),
    val steps: StepConfig = StepConfig(),
    val filter: FilterConfig = FilterConfig(),
    val floor: FloorConfig = FloorConfig(),
    val phoneMode: PhoneModeConfig = PhoneModeConfig(),
    val display: DisplayConfig = DisplayConfig(),
    val logging: LoggingConfig = LoggingConfig(),
) {
    companion object {
        /** Strict reader: unknown keys are errors; missing keys keep their defaults. */
        private val reader = Json { ignoreUnknownKeys = false }

        /** Writer: always writes every field, so a saved file shows all current values. */
        private val writer = Json { encodeDefaults = true; prettyPrint = true }

        /** All defaults. */
        fun defaults() = TrialConfig()

        /**
         * Parses a config JSON [text]. Throws [IllegalArgumentException] with a readable message
         * if the JSON is malformed or names a field that doesn't exist.
         */
        fun fromJson(text: String): TrialConfig = try {
            reader.decodeFromString(serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid config JSON: ${e.message}", e)
        }
    }

    /** The full config as pretty-printed JSON (every field, including defaults). */
    fun toJson(): String = writer.encodeToString(serializer(), this)

    /** The full config as single-line JSON, for embedding in a log record. */
    fun toCompactJson(): String = compactWriter.encodeToString(serializer(), this)
}

/** Single-line writer for [TrialConfig.toCompactJson] (every field, no line breaks). */
private val compactWriter = Json { encodeDefaults = true }
