package com.geger.trial.recording

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A recorded session as shown in the sessions list. [passed] is null for interrupted sessions. */
data class StoredSession(
    val id: String,
    val runType: String,
    val durationS: Double?,
    val bytes: Long,
    val passed: Boolean?,
)

/**
 * The phone's recorded sessions: list, export as zip, delete.
 *
 * Concept: each session is a folder in the app's private storage (sessions/<id>/) holding the
 * log, the map and config used, and a summary. Exporting zips the folder into the app's cache and
 * hands it to Android's share sheet through a FileProvider, which grants the receiving app (Drive,
 * Gmail, Quick Share) temporary read access to just that file.
 */
class SessionStore(private val context: Context) {

    private val root = File(context.filesDir, "sessions")

    /** All sessions, newest first. A session without summary.json was interrupted (app killed). */
    fun list(): List<StoredSession> {
        val dirs = root.listFiles { f -> f.isDirectory }.orEmpty().sortedByDescending { it.name }
        return dirs.map { dir ->
            val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val summary = File(dir, "summary.json").takeIf { it.isFile }?.let {
                runCatching { Json.parseToJsonElement(it.readText()).jsonObject }.getOrNull()
            }
            StoredSession(
                id = dir.name,
                runType = summary?.string("run_type") ?: "interrupted",
                durationS = summary?.get("duration_s")?.jsonPrimitive?.doubleOrNull,
                bytes = bytes,
                passed = summary?.get("passed")?.jsonPrimitive?.booleanOrNull,
            )
        }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    /** Zips session [id] into the cache folder and returns a shareable content:// address for it. */
    fun exportZip(id: String): Uri {
        val dir = File(root, id)
        require(dir.isDirectory) { "No session $id" }
        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() } // keep only the latest export
        val zip = File(outDir, "$id.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            // The log is already gzipped, so spend little time compressing it again.
            out.setLevel(java.util.zip.Deflater.BEST_SPEED)
            dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { f ->
                out.putNextEntry(ZipEntry("$id/${f.name}"))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
    }

    /** The Android share sheet for an exported zip. */
    fun shareIntent(uri: Uri, id: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Geger trial $id")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Export $id")
    }

    /** Permanently deletes session [id] from the phone. */
    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }
}
