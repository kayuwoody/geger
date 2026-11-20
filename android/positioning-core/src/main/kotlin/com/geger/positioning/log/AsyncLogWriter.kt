package com.geger.positioning.log

import java.io.Writer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * Counts reported when a log is closed.
 * [written] = lines written per record type; [dropped] = lines that didn't fit in the queue.
 */
data class LogStats(val written: Map<String, Long>, val dropped: Long, val chars: Long) {
    val total: Long get() = written.values.sum()
}

/**
 * Writes log lines on its own background thread so sensor and camera callbacks never wait for
 * storage.
 *
 * Concept — producer/consumer queue: callers (sensor thread, AR render thread) hand finished lines
 * to a bounded in-memory queue and return immediately. One writer thread takes lines off the queue
 * and writes them, flushing about once a second. If storage ever stalls long enough for the queue
 * to fill, new lines are dropped and counted rather than blocking the AR frame loop; the count is
 * reported at the end so a test run with drops can be spotted. With the default capacity
 * (262 144 lines, about a quarter of an hour of data) drops should never happen in practice.
 *
 * Thread-safe: [offer] may be called from any number of threads.
 */
class AsyncLogWriter(
    private val out: Writer,
    capacity: Int = 262_144,
    private val flushIntervalMs: Long = 1_000,
) {
    /** One queued line and its record type (kept for the per-type counts). */
    private class Item(val type: String, val line: String)

    private val queue = ArrayBlockingQueue<Item>(capacity)
    private val written = ConcurrentHashMap<String, LongAdder>()
    private val dropped = AtomicLong()
    private val chars = AtomicLong()
    @Volatile private var closing = false
    @Volatile private var failure: Throwable? = null

    private val thread = Thread({ runLoop() }, "geger-log-writer").apply { isDaemon = true; start() }

    /**
     * Queues one finished line of [type]. Returns false (and counts a drop) if the queue is full or
     * the log is closed. Never blocks.
     */
    fun offer(type: String, line: String): Boolean {
        if (closing || !queue.offer(Item(type, line))) {
            dropped.incrementAndGet()
            return false
        }
        return true
    }

    /** Lines dropped so far (for a live display). */
    fun droppedSoFar(): Long = dropped.get()

    /** The first write error, if any (e.g. storage full). Writing stops after an error. */
    fun error(): Throwable? = failure

    /**
     * Writer thread body: take lines as they arrive, write them, and flush at most once per
     * [flushIntervalMs] — flushing every line would make storage the bottleneck.
     */
    private fun runLoop() {
        var lastFlush = System.nanoTime()
        try {
            while (true) {
                val item = queue.poll(100, TimeUnit.MILLISECONDS)
                if (item != null) {
                    out.write(item.line)
                    out.write('\n'.code)
                    written.computeIfAbsent(item.type) { LongAdder() }.increment()
                    chars.addAndGet(item.line.length + 1L)
                    // Drain whatever else is waiting without sleeping between lines.
                    while (true) {
                        val next = queue.poll() ?: break
                        out.write(next.line)
                        out.write('\n'.code)
                        written.computeIfAbsent(next.type) { LongAdder() }.increment()
                        chars.addAndGet(next.line.length + 1L)
                    }
                } else if (closing) {
                    break
                }
                val now = System.nanoTime()
                if (now - lastFlush > flushIntervalMs * 1_000_000) {
                    out.flush()
                    lastFlush = now
                }
            }
            out.flush()
        } catch (t: Throwable) {
            failure = t
        }
    }

    /**
     * Stops accepting lines, writes everything still queued, flushes and closes the output, and
     * returns the counts. Safe to call once; blocks until the writer thread finishes.
     */
    fun close(): LogStats {
        closing = true
        thread.join()
        try { out.close() } catch (t: Throwable) { if (failure == null) failure = t }
        return LogStats(written.mapValues { it.value.sum() }, dropped.get(), chars.get())
    }
}
