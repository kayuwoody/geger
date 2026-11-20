package com.geger.positioning

import com.geger.positioning.log.AsyncLogWriter
import com.geger.positioning.log.LogReader
import com.geger.positioning.log.RateCheck
import com.geger.positioning.log.RecordType
import com.geger.positioning.log.TimeBase
import com.geger.positioning.log.TimebaseCheck
import com.geger.positioning.log.record
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.CountDownLatch
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

/**
 * Tests for session logging (M3 check: "the export reads back through LogReader with matching
 * counts"; zero drops under normal load).
 */
class LogTest {

    @Test
    fun `record lines are valid JSON with escaping and null for non-finite numbers`() {
        val line = record(123, RecordType.NOTE)
            .str("text", "say \"hi\"\n\\ok\u0001")
            .num("nan", Double.NaN)
            .nums("q", 0.1, 0.2, Double.POSITIVE_INFINITY, 1.0)
            .int("n", 7)
            .bool("b", true)
            .str("missing", null)
            .build()
        val e = LogReader.parse(line)
        assertEquals(123L, e.t)
        assertEquals("note", e.type)
        assertEquals("say \"hi\"\n\\ok\u0001", e.fields["text"]!!.jsonPrimitive.content)
        assertEquals("null", e.fields["nan"].toString())
        assertEquals(listOf("0.1", "0.2", "null", "1.0"), e.fields["q"]!!.jsonArray.map { it.toString() })
    }

    @Test
    fun `numbers use a dot decimal point whatever the default locale`() {
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val e = LogReader.parse(record(1, "x").num("v", 1012.25).build())
            assertEquals(1012.25, e.fields["v"]!!.jsonPrimitive.double)
        } finally {
            java.util.Locale.setDefault(old)
        }
    }

    @Test
    fun `concurrent writers lose nothing and counts match what was read back`() {
        val bytes = ByteArrayOutputStream()
        val log = AsyncLogWriter(OutputStreamWriter(GZIPOutputStream(bytes), Charsets.UTF_8))
        val perThread = 20_000
        val writers = listOf("imu" to "a", "imu" to "g", "rotation" to "game").map { (type, s) ->
            thread { repeat(perThread) { i -> log.offer(type, record(i.toLong(), type).str("s", s).num("x", i * 0.5).build()) } }
        }
        writers.forEach { it.join() }
        val stats = log.close()
        assertNull(log.error())
        assertEquals(0L, stats.dropped)
        assertEquals(2L * perThread, stats.written["imu"])
        assertEquals(perThread.toLong(), stats.written["rotation"])

        val counts = LogReader.countStreams(LogReader.open(ByteArrayInputStream(bytes.toByteArray())))
        assertEquals(mapOf("imu:a" to perThread.toLong(), "imu:g" to perThread.toLong(), "rotation:game" to perThread.toLong()), counts)
    }

    @Test
    fun `plain and gzipped logs read the same`() {
        val lines = (1..5).joinToString("\n") { record(it.toLong(), "step").build() } + "\n"
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(lines.toByteArray()) } }.toByteArray()
        val plain = LogReader.countStreams(LogReader.open(ByteArrayInputStream(lines.toByteArray())))
        val zipped = LogReader.countStreams(LogReader.open(ByteArrayInputStream(gz)))
        assertEquals(mapOf("step" to 5L), plain)
        assertEquals(plain, zipped)
    }

    @Test
    fun `a truncated last line is skipped but a broken middle line is an error`() {
        val ok = record(1, "step").build()
        val truncated = "$ok\n$ok\n{\"t\":3,\"ty"
        assertEquals(mapOf("step" to 2L), LogReader.countStreams(ByteArrayInputStream(truncated.toByteArray())))
        val broken = "$ok\n{oops\n$ok\n"
        val e = assertThrows<IllegalArgumentException> { LogReader.countStreams(ByteArrayInputStream(broken.toByteArray())) }
        assertTrue(e.message!!.contains("line 2"))
    }

    @Test
    fun `a full queue drops and counts instead of blocking`() {
        val gate = CountDownLatch(1)
        // A sink that blocks on the first write until released, so the queue fills up.
        val blocking = object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) { gate.await() }
            override fun flush() {}
            override fun close() {}
        }
        val log = AsyncLogWriter(blocking, capacity = 10)
        var accepted = 0
        repeat(100) { if (log.offer("x", "{}")) accepted++ }
        assertTrue(accepted in 10..12, "about the queue capacity should be accepted, got $accepted")
        assertTrue(log.droppedSoFar() >= 88)
        gate.countDown()
        val stats = log.close()
        assertEquals(100L, stats.total + stats.dropped)
        assertFalse(log.offer("x", "{}"), "closed log refuses new lines")
    }

    // ---- Self-checks shown after a recording ---------------------------------------------------

    @Test
    fun `timebase check recognises each clock`() {
        val bootGap = 3_600_000_000_000L // elapsedRealtime runs 1 h ahead of nanoTime (time asleep)
        val elapsed = TimebaseCheck()
        val mono = TimebaseCheck()
        for (i in 0 until 100) {
            val happenedElapsed = 10_000_000_000_000L + i * 33_000_000L
            val delay = 20_000_000L + (i % 7) * 1_000_000L
            elapsed.add(happenedElapsed, happenedElapsed + delay, happenedElapsed + delay - bootGap)
            mono.add(happenedElapsed - bootGap, happenedElapsed + delay, happenedElapsed + delay - bootGap)
        }
        val e = elapsed.result()
        assertEquals(TimeBase.ELAPSED_REALTIME, e.base)
        assertEquals(23.0, e.medianDelayMs, 1.0)
        val m = mono.result()
        assertEquals(TimeBase.MONOTONIC, m.base)
        assertEquals(bootGap, m.offsetToElapsedNs)
        assertEquals(TimeBase.UNKNOWN, TimebaseCheck().also { it.add(0, 1, 1) }.result().base, "too few samples")
    }

    @Test
    fun `rate check passes within 10 percent and fails outside`() {
        val results = RateCheck.check(mapOf("arcore_frame" to 9_000L, "imu:a" to 26_000L, "imu:g" to 30_000L), durationS = 300.0)
        val byStream = results.associateBy { it.stream }
        assertTrue(byStream.getValue("arcore_frame").ok, "30 Hz exactly")
        assertFalse(byStream.getValue("imu:a").ok, "86.7 Hz is more than 10% low")
        assertTrue(byStream.getValue("imu:g").ok, "100 Hz exactly")
        assertFalse(byStream.getValue("magnetometer").ok, "missing stream fails")
    }
}
