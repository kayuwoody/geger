package com.geger.positioning.log

/*
 * Session log record format (SPEC.md, "Logging").
 *
 * Concept: a session log is a JSON Lines file — one JSON object per line, each with
 *   "t":    a timestamp in nanoseconds on the SystemClock.elapsedRealtimeNanos() time base
 *   "type": what kind of record it is (see RecordType)
 * plus fields that depend on the type. One line per record means a crash loses at most the last
 * unflushed lines, files can be streamed without loading them whole, and any tool can read them.
 */

/** Record type names, exactly as written in the "type" field. */
object RecordType {
    const val SESSION_START = "session_start"
    const val SESSION_END = "session_end"
    const val ARCORE_FRAME = "arcore_frame"
    /** Raw accelerometer or gyroscope reading; field "s" = "a" (accelerometer) or "g" (gyroscope). */
    const val IMU = "imu"
    /** Rotation vector as a quaternion; field "s" = "game" (no compass) or "rot" (with compass). */
    const val ROTATION = "rotation"
    const val MAGNETOMETER = "magnetometer"
    const val PRESSURE = "pressure"
    const val STEP = "step"
    const val PROXIMITY_LIGHT = "proximity_light"
    const val NOTE = "note"
    /** How ARCore's frame timestamps relate to the sensor time base (written at session end). */
    const val TIMEBASE = "timebase"
}

/**
 * Builds one JSON log line by hand, quickly and without allocating intermediate objects.
 *
 * Concept: several hundred records per second are written while the phone is also running AR, so
 * the writer avoids a general-purpose JSON library here. Numbers use Kotlin's locale-independent
 * toString (always a "." decimal point); NaN and infinities, which JSON can't represent, become null.
 * Strings are escaped per the JSON spec.
 */
class RecordBuilder(t: Long, type: String) {
    private val sb = StringBuilder(160).append("{\"t\":").append(t).append(",\"type\":").also { appendString(it, type) }

    /** Adds a number field (null if not finite). */
    fun num(name: String, v: Double): RecordBuilder {
        key(name)
        if (v.isFinite()) sb.append(v) else sb.append("null")
        return this
    }

    /** Adds a float field (sensor values arrive as floats). */
    fun num(name: String, v: Float): RecordBuilder = num(name, v.toDouble())

    /** Adds a whole-number field. */
    fun int(name: String, v: Long): RecordBuilder { key(name); sb.append(v); return this }

    /** Adds a whole-number field. */
    fun int(name: String, v: Int): RecordBuilder = int(name, v.toLong())

    /** Adds a text field (null writes JSON null). */
    fun str(name: String, v: String?): RecordBuilder {
        key(name)
        if (v == null) sb.append("null") else appendString(sb, v)
        return this
    }

    /** Adds a true/false field. */
    fun bool(name: String, v: Boolean): RecordBuilder { key(name); sb.append(v); return this }

    /** Adds an array of numbers, e.g. a position [x, y, z] or a quaternion [x, y, z, w]. */
    fun nums(name: String, vararg v: Double): RecordBuilder {
        key(name)
        sb.append('[')
        v.forEachIndexed { i, d -> if (i > 0) sb.append(','); if (d.isFinite()) sb.append(d) else sb.append("null") }
        sb.append(']')
        return this
    }

    /**
     * Adds a field whose value is already valid JSON text (e.g. the whole config object).
     * The caller guarantees validity; used only for values produced by a JSON library.
     */
    fun rawJson(name: String, json: String): RecordBuilder { key(name); sb.append(json); return this }

    /** The finished line, without a trailing newline. */
    fun build(): String = sb.append('}').toString()

    private fun key(name: String) {
        sb.append(',')
        appendString(sb, name)
        sb.append(':')
    }

    private companion object {
        /** Appends a JSON string literal: quotes, backslashes and control characters escaped. */
        fun appendString(sb: StringBuilder, s: String) {
            sb.append('"')
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c < ' ' -> sb.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", c.code))
                    else -> sb.append(c)
                }
            }
            sb.append('"')
        }
    }
}

/** Starts a record line: `record(t, RecordType.PRESSURE).num("hpa", 1012.3).build()`. */
fun record(t: Long, type: String) = RecordBuilder(t, type)
