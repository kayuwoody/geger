package com.geger.trial.recording

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.geger.positioning.log.RecordType
import com.geger.positioning.log.TimebaseCheck
import com.geger.positioning.log.record
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Listens to the phone's motion and environment sensors and turns every reading into a log line.
 *
 * Concept: Android delivers sensor readings ("events") to a listener at roughly the rate we ask
 * for. We receive them on a dedicated background thread (a HandlerThread) so they never compete
 * with the screen, stamp each line with the event's own timestamp (when it was measured, not when
 * we got it), and hand the line to the log writer. Per-stream counters feed the live display and
 * the end-of-session rate check.
 *
 * Streams (SPEC.md, "Logging"): imu (accelerometer "a" and gyroscope "g", 100 Hz), rotation (game
 * rotation vector "game" and rotation vector "rot", 50 Hz), magnetometer (50 Hz), pressure, step,
 * proximity_light (on change).
 */
class SensorRecorder(private val sensors: SensorManager, private val sink: (type: String, line: String) -> Unit) {

    /** Readings received per stream (e.g. "imu:a"), for live rates and the summary. */
    val counts = ConcurrentHashMap<String, AtomicLong>()

    /** Compares accelerometer event timestamps with receipt time to confirm the sensor clock. */
    val timebase = TimebaseCheck()

    /** Which sensors this phone lacks (reported in the session log). */
    val missing = mutableListOf<String>()

    private var thread: HandlerThread? = null
    private val quat = FloatArray(4)
    private var lastNear: Boolean? = null
    private var lastDistanceCm = Float.NaN
    private var lastLux = Float.NaN

    /** Registers all listeners. Requested periods are hints; Android may deliver faster or slower. */
    fun start() {
        val t = HandlerThread("geger-sensors").apply { start() }
        thread = t
        val handler = Handler(t.looper)
        register(Sensor.TYPE_ACCELEROMETER, 10_000, handler, "accelerometer")
        register(Sensor.TYPE_GYROSCOPE, 10_000, handler, "gyroscope")
        register(Sensor.TYPE_GAME_ROTATION_VECTOR, 20_000, handler, "game rotation vector")
        register(Sensor.TYPE_ROTATION_VECTOR, 20_000, handler, "rotation vector")
        register(Sensor.TYPE_MAGNETIC_FIELD, 20_000, handler, "magnetometer")
        register(Sensor.TYPE_PRESSURE, 40_000, handler, "barometer")
        register(Sensor.TYPE_STEP_DETECTOR, SensorManager.SENSOR_DELAY_NORMAL, handler, "step detector")
        register(Sensor.TYPE_PROXIMITY, SensorManager.SENSOR_DELAY_NORMAL, handler, "proximity")
        register(Sensor.TYPE_LIGHT, SensorManager.SENSOR_DELAY_NORMAL, handler, "light")
    }

    /** Unregisters everything and stops the sensor thread. */
    fun stop() {
        sensors.unregisterListener(listener)
        thread?.quitSafely()
        thread?.join(1_000)
        thread = null
    }

    private fun register(type: Int, periodUs: Int, handler: Handler, name: String) {
        val sensor = sensors.getDefaultSensor(type)
        if (sensor == null) {
            missing += name
            return
        }
        sensors.registerListener(listener, sensor, periodUs, handler)
    }

    private fun count(stream: String) = counts.computeIfAbsent(stream) { AtomicLong() }.incrementAndGet()

    /** Converts each event into one log line. Runs on the sensor thread. */
    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val t = e.timestamp
            val v = e.values
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    timebase.add(t, SystemClock.elapsedRealtimeNanos(), System.nanoTime())
                    emit("imu:a", record(t, RecordType.IMU).str("s", "a").num("x", v[0]).num("y", v[1]).num("z", v[2]))
                }
                Sensor.TYPE_GYROSCOPE ->
                    emit("imu:g", record(t, RecordType.IMU).str("s", "g").num("x", v[0]).num("y", v[1]).num("z", v[2]))
                Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                    // Android gives (w, x, y, z); the log uses ARCore's (x, y, z, w) layout.
                    SensorManager.getQuaternionFromVector(quat, v)
                    val game = e.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR
                    val b = record(t, RecordType.ROTATION).str("s", if (game) "game" else "rot")
                        .nums("q", quat[1].toDouble(), quat[2].toDouble(), quat[3].toDouble(), quat[0].toDouble())
                    // The compass-based rotation vector also reports its heading accuracy (radians).
                    if (!game && v.size > 4) b.num("acc", v[4])
                    emit(if (game) "rotation:game" else "rotation:rot", b)
                }
                Sensor.TYPE_MAGNETIC_FIELD ->
                    emit("magnetometer", record(t, RecordType.MAGNETOMETER).num("x", v[0]).num("y", v[1]).num("z", v[2]))
                Sensor.TYPE_PRESSURE -> emit("pressure", record(t, RecordType.PRESSURE).num("hpa", v[0]))
                Sensor.TYPE_STEP_DETECTOR -> emit("step", record(t, RecordType.STEP))
                Sensor.TYPE_PROXIMITY -> {
                    lastDistanceCm = v[0]
                    lastNear = v[0] < e.sensor.maximumRange
                    emitProximityLight(t)
                }
                Sensor.TYPE_LIGHT -> {
                    lastLux = v[0]
                    emitProximityLight(t)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    /** One combined record whenever proximity or light changes, carrying the latest of both. */
    private fun emitProximityLight(t: Long) {
        val b = record(t, RecordType.PROXIMITY_LIGHT).num("cm", lastDistanceCm).num("lux", lastLux)
        lastNear?.let { b.bool("near", it) }
        emit("proximity_light", b)
    }

    private fun emit(stream: String, b: com.geger.positioning.log.RecordBuilder) {
        count(stream)
        sink(stream.substringBefore(':'), b.build())
    }
}
