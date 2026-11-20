package com.geger.replay

import com.geger.positioning.log.AsyncLogWriter
import com.geger.positioning.log.RecordType
import com.geger.positioning.log.record
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests `check-session` on synthetic sessions written in the phone's format: a healthy one, one
 * whose file lost records, and one that was interrupted (no session_end).
 */
class SessionCheckTest {

    private val out = mutableListOf<String>()
    private val err = mutableListOf<String>()
    private fun cli(vararg args: String) = runCli(args.toList(), { out += it }, { err += it })

    /**
     * Writes a 30-second session zip like the app exports. [skipImu] leaves out that many imu
     * records (simulating loss); [withEnd] = false simulates an interrupted recording.
     */
    private fun syntheticSession(skipImu: Int = 0, withEnd: Boolean = true): File {
        val dir = Files.createTempDirectory("session").toFile().apply { deleteOnExit() }
        val gz = File(dir, "session-test.jsonl.gz")
        val log = AsyncLogWriter(OutputStreamWriter(GZIPOutputStream(gz.outputStream()), Charsets.UTF_8))
        val t0 = 1_000_000_000_000L
        val seconds = 30
        log.offer(RecordType.SESSION_START, record(t0, RecordType.SESSION_START).str("session", "session-test").str("device", "test phone").build())
        val counts = sortedMapOf<String, Long>()
        fun stream(name: String, hz: Int, emit: (Long) -> String) {
            val n = seconds * hz
            for (i in 0 until n) {
                val t = t0 + i * (1_000_000_000L / hz)
                if (name.startsWith("imu") && i < skipImu) continue
                log.offer(name.substringBefore(':'), emit(t))
            }
            counts[name] = n.toLong()
        }
        stream("arcore_frame", 30) { record(it, RecordType.ARCORE_FRAME).int("ft", it).str("ts", "TRACKING").build() }
        stream("imu:a", 100) { record(it, RecordType.IMU).str("s", "a").num("x", 0.1).build() }
        stream("imu:g", 100) { record(it, RecordType.IMU).str("s", "g").num("x", 0.1).build() }
        stream("rotation:game", 50) { record(it, RecordType.ROTATION).str("s", "game").nums("q", 0.0, 0.0, 0.0, 1.0).build() }
        stream("rotation:rot", 50) { record(it, RecordType.ROTATION).str("s", "rot").nums("q", 0.0, 0.0, 0.0, 1.0).build() }
        stream("magnetometer", 50) { record(it, RecordType.MAGNETOMETER).num("x", 30.0).build() }
        val tEnd = t0 + seconds * 1_000_000_000L
        if (withEnd) {
            for (s in listOf("arcore_frame", "sensors")) {
                log.offer(RecordType.TIMEBASE, record(tEnd, RecordType.TIMEBASE).str("stream", s).str("base", "ELAPSED_REALTIME").num("median_delay_ms", 12.0).build())
            }
            counts["session_start"] = 1L
            val json = counts.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
            log.offer(RecordType.SESSION_END, record(tEnd, RecordType.SESSION_END).num("duration_s", seconds.toDouble()).rawJson("counts", json).int("dropped_so_far", 0).build())
        }
        log.close()
        val zip = File(dir, "session-test.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("session-test/session-test.jsonl.gz"))
            gz.inputStream().use { it.copyTo(z) }
            z.closeEntry()
        }
        return zip
    }

    @Test
    fun `healthy session passes`() {
        assertEquals(0, cli("check-session", syntheticSession().path), "err: $err")
        assertTrue(out.any { it.startsWith("Counts match") }, out.toString())
        assertTrue(out.last() == "RESULT: PASS")
    }

    @Test
    fun `lost records are reported`() {
        assertEquals(1, cli("check-session", syntheticSession(skipImu = 50).path))
        assertTrue(err.any { it.contains("stream imu:a: phone counted 3000, file has 2950") }, err.toString())
    }

    @Test
    fun `interrupted session is reported`() {
        assertEquals(1, cli("check-session", syntheticSession(withEnd = false).path))
        assertTrue(err.any { it.contains("interrupted") }, err.toString())
    }
}
