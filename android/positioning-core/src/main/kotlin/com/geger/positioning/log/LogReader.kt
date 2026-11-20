package com.geger.positioning.log

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** One parsed log line: its timestamp, type and the whole JSON object (fields included). */
data class LogEntry(val t: Long, val type: String, val fields: JsonObject)

/**
 * Reads session logs written by the app (plain .jsonl or gzipped .jsonl.gz).
 *
 * Concept: the reader is the other half of [RecordBuilder]/[AsyncLogWriter] and lives in the same
 * module, so the replay CLI and the tests read exactly the format the phone writes. Gzip is
 * detected from the file's first two bytes (0x1f 0x8b), not its name.
 */
object LogReader {

    private val json = Json { ignoreUnknownKeys = true }

    /** Opens [file], unzipping transparently if it is gzip-compressed. */
    fun open(file: File): InputStream = open(file.inputStream())

    /** Wraps [input], unzipping transparently if it starts with the gzip magic bytes. */
    fun open(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input)
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered) else buffered
    }

    /**
     * Calls [onEntry] for every line in order. Blank lines are skipped. A line that isn't valid
     * JSON (e.g. half-written when the phone died) throws IllegalArgumentException naming its line
     * number, unless it is the very last line, which is skipped.
     */
    fun forEach(input: InputStream, onEntry: (LogEntry) -> Unit) {
        input.bufferedReader().useLines { lines ->
            val it = lines.iterator()
            var n = 0
            while (it.hasNext()) {
                val line = it.next()
                n++
                if (line.isBlank()) continue
                val entry = try {
                    parse(line)
                } catch (e: Exception) {
                    if (!it.hasNext()) break // truncated final line from an interrupted session
                    throw IllegalArgumentException("line $n is not a valid log record: ${e.message}", e)
                }
                onEntry(entry)
            }
        }
    }

    /** Parses one line into a [LogEntry]. */
    fun parse(line: String): LogEntry {
        val obj = json.parseToJsonElement(line).jsonObject
        val t = (obj["t"] as? JsonPrimitive)?.long ?: throw IllegalArgumentException("missing \"t\"")
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("missing \"type\"")
        return LogEntry(t, type, obj)
    }

    /**
     * Counts records per stream. Streams are the record type, refined by the "s" field for types
     * that carry several sensors (imu → imu:a, imu:g; rotation → rotation:game, rotation:rot).
     */
    fun countStreams(input: InputStream): Map<String, Long> {
        val counts = sortedMapOf<String, Long>()
        forEach(input) { e -> counts.merge(streamOf(e), 1L, Long::plus) }
        return counts
    }

    /** The stream name of an entry (see [countStreams]). */
    fun streamOf(e: LogEntry): String {
        val s = (e.fields["s"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (s != null) "${e.type}:$s" else e.type
    }
}
