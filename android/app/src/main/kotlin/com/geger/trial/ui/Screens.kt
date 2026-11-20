package com.geger.trial.ui

import android.opengl.GLSurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.geger.positioning.log.RateCheck
import com.geger.positioning.log.TimeBase
import com.geger.positioning.log.TimebaseResult
import com.geger.trial.recording.LiveStats
import com.geger.trial.recording.SessionSummary
import com.geger.trial.recording.StoredSession
import java.util.Locale

/*
 * The app's screens (Jetpack Compose).
 *
 * Concept: each screen is a function of the state it is given plus callbacks for what the user
 * does; it holds no logic of its own. MainActivity owns the state and decides which screen shows.
 */

/** Formats a number with a fixed number of decimals and a "." decimal point. */
private fun f(v: Double, decimals: Int = 1) = if (v.isFinite()) String.format(Locale.ROOT, "%.${decimals}f", v) else "–"

/** Formats seconds as m:ss. */
private fun mmss(s: Double) = String.format(Locale.ROOT, "%d:%02d", (s / 60).toInt(), (s % 60).toInt())

/** Formats a byte count as kB/MB. */
private fun size(bytes: Long) = if (bytes > 1_000_000) "${f(bytes / 1e6)} MB" else "${f(bytes / 1e3, 0)} kB"

private val Good = Color(0xFF2E7D32)
private val Bad = Color(0xFFB3261E)

/** A heading for a block of the setup screen. */
@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

/** A row of choice chips; [selected] is highlighted. */
@Composable
private fun Choices(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

/** Everything the setup screen shows. */
data class SetupState(
    val arStatus: String,
    val arReady: Boolean,
    val permissionsOk: Boolean,
    val runType: String,
    val carryMode: String,
    val tester: String,
    val notes: String,
    val mapInfo: String,
    val mapWarnings: List<String>,
    val configInfo: String,
    val message: String?,
)

/** What the setup screen can ask for. */
class SetupActions(
    val onRunType: (String) -> Unit,
    val onCarryMode: (String) -> Unit,
    val onTester: (String) -> Unit,
    val onNotes: (String) -> Unit,
    val onGrantPermissions: () -> Unit,
    val onChooseMap: () -> Unit,
    val onClearMap: () -> Unit,
    val onLoadConfig: () -> Unit,
    val onResetConfig: () -> Unit,
    val onStart: () -> Unit,
    val onSessions: () -> Unit,
)

/** First screen: what kind of run, who, which map and config; then Start. */
@Composable
fun SetupScreen(s: SetupState, a: SetupActions) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text("Geger positioning trial", style = MaterialTheme.typography.headlineSmall)
        Text("Milestone M3 · sensor recorder", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionTitle("Phone")
        Text("AR: ${s.arStatus}", color = if (s.arReady) Good else MaterialTheme.colorScheme.onSurface)
        if (!s.permissionsOk) {
            Text("Camera and step-counter permissions are needed.", color = Bad)
            OutlinedButton(onClick = a.onGrantPermissions) { Text("Grant permissions") }
        }

        SectionTitle("Run type")
        Choices(listOf("desk_test" to "Desk test", "route_walk" to "Route", "free_walk" to "Free walk"), s.runType, a.onRunType)
        SectionTitle("Carry mode (planned)")
        Choices(listOf("up" to "Up", "flat" to "Flat", "pocket" to "Pocket", "mixed" to "Mixed"), s.carryMode, a.onCarryMode)

        SectionTitle("Tester and notes")
        OutlinedTextField(s.tester, { a.onTester(it.take(8)) }, label = { Text("Initials") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(s.notes, a.onNotes, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())

        SectionTitle("Map")
        Text(s.mapInfo)
        s.mapWarnings.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A5A00)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = a.onChooseMap) { Text("Choose map.json") }
            TextButton(onClick = a.onClearMap) { Text("No map") }
        }

        SectionTitle("Settings")
        Text(s.configInfo)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = a.onLoadConfig) { Text("Load config.json") }
            TextButton(onClick = a.onResetConfig) { Text("Use defaults") }
        }

        s.message?.let { Text(it, color = Bad, modifier = Modifier.padding(top = 12.dp)) }

        Spacer(Modifier.height(20.dp))
        Button(onClick = a.onStart, enabled = s.arReady && s.permissionsOk, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Start recording", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(onClick = a.onSessions, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Sessions") }
    }
}

/**
 * Recording: the camera view (proof that ARCore is tracking) with live counters on top, and
 * Note / Stop buttons at the bottom.
 */
@Composable
fun RecordingScreen(view: GLSurfaceView, live: LiveStats?, onNote: (String) -> Unit, onStop: () -> Unit) {
    var noteOpen by remember { mutableStateOf(false) }
    var stopOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { view.also { (it.parent as? ViewGroup)?.removeView(it) } },
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(8.dp)
                .background(Color(0xCC000000), MaterialTheme.shapes.medium).padding(12.dp),
        ) {
            val l = live
            if (l == null) {
                Text("Starting…", color = Color.White)
            } else {
                Text("Recording ${mmss(l.elapsedS)}", color = Color.White, fontWeight = FontWeight.Bold)
                val tracking = if (l.failureReason.isEmpty()) l.trackingState else "${l.trackingState} (${l.failureReason})"
                Text("AR: $tracking", color = if (l.trackingState == "TRACKING") Color(0xFF8BE28F) else Color(0xFFFFC107))
                val secs = l.elapsedS.coerceAtLeast(0.001)
                RateCheck.TARGETS.forEach { (stream, target) ->
                    val hz = (l.counts[stream] ?: 0L) / secs
                    Text("$stream ${f(hz)} Hz (target ${f(target, 0)})", color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
                listOf("pressure", "step", "proximity_light").forEach { s ->
                    Text("$s: ${l.counts[s] ?: 0}", color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
                if (l.dropped > 0) Text("Dropped records: ${l.dropped}", color = Color(0xFFFF8A80))
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = { noteOpen = true }, modifier = Modifier.weight(1f).height(56.dp)
                .background(Color.White, MaterialTheme.shapes.extraLarge)) { Text("Note") }
            Button(onClick = { stopOpen = true }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Stop") }
        }
    }
    if (noteOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { noteOpen = false },
            title = { Text("Add a note") },
            text = { OutlinedTextField(text, { text = it }, placeholder = { Text("e.g. crowd, shiny floor") }) },
            confirmButton = { TextButton(onClick = { onNote(text); noteOpen = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { noteOpen = false }) { Text("Cancel") } },
        )
    }
    if (stopOpen) {
        AlertDialog(
            onDismissRequest = { stopOpen = false },
            title = { Text("Stop recording?") },
            confirmButton = { TextButton(onClick = { stopOpen = false; onStop() }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { stopOpen = false }) { Text("Keep recording") } },
        )
    }
}

/** Shown while the log is being finished and compressed. */
@Composable
fun BusyScreen(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

/** Describes a clock check result in plain words. */
private fun timebaseText(r: TimebaseResult): String = when (r.base) {
    TimeBase.ELAPSED_REALTIME -> "same clock as the log (delay ${f(r.medianDelayMs)} ms)"
    TimeBase.MONOTONIC -> "phone uptime clock, converted (delay ${f(r.medianDelayMs)} ms)"
    TimeBase.UNKNOWN -> "UNKNOWN (${r.samples} samples)"
}

/**
 * After a recording: the M3 desk-check results (rates vs target, clocks, drops), then Export.
 */
@Composable
fun SummaryScreen(s: SessionSummary, onExport: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(if (s.passed) "Checks passed" else "Checks need attention", style = MaterialTheme.typography.headlineSmall,
            color = if (s.passed) Good else Bad)
        Text("${s.id} · ${mmss(s.durationS)} · ${size(s.fileBytes)}", style = MaterialTheme.typography.bodySmall)
        s.error?.let { Text(it, color = Bad, modifier = Modifier.padding(top = 8.dp)) }

        SectionTitle("Sensor rates (within 10% of target)")
        s.rates.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(if (r.ok) "✓" else "✗", color = if (r.ok) Good else Bad, modifier = Modifier.padding(end = 8.dp))
                Text(r.stream, modifier = Modifier.weight(1f))
                Text("${f(r.hz)} / ${f(r.targetHz, 0)} Hz")
            }
        }
        SectionTitle("Clocks")
        Text("Camera: ${timebaseText(s.camera)}", color = if (s.camera.base == TimeBase.UNKNOWN) Bad else MaterialTheme.colorScheme.onSurface)
        Text("Sensors: ${timebaseText(s.sensors)}", color = if (s.sensors.base == TimeBase.UNKNOWN) Bad else MaterialTheme.colorScheme.onSurface)
        SectionTitle("Log")
        Text("${s.records} records, ${s.dropped} dropped, ${s.notes} notes", color = if (s.dropped > 0) Bad else MaterialTheme.colorScheme.onSurface)
        if (s.missingSensors.isNotEmpty()) Text("Missing sensors: ${s.missingSensors.joinToString()}", color = Bad)

        Spacer(Modifier.height(20.dp))
        Button(onClick = onExport, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Export session") }
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Done") }
    }
}

/** All recorded sessions with Export and Delete. */
@Composable
fun SessionsScreen(sessions: List<StoredSession>, onExport: (String) -> Unit, onDelete: (String) -> Unit, onBack: () -> Unit) {
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sessions", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }
        if (sessions.isEmpty()) Text("No sessions yet.", modifier = Modifier.padding(top = 16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(sessions, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.id, fontWeight = FontWeight.Bold)
                        val result = when (s.passed) { true -> "checks passed"; false -> "checks need attention"; null -> "interrupted" }
                        Text("${s.runType} · ${s.durationS?.let { mmss(it) } ?: "?"} · ${size(s.bytes)} · $result",
                            style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onExport(s.id) }) { Text("Export") }
                            TextButton(onClick = { confirmDelete = s.id }) { Text("Delete", color = Bad) }
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete $id?") },
            text = { Text("This removes the recording from the phone. Export it first if you need it.") },
            confirmButton = { TextButton(onClick = { onDelete(id); confirmDelete = null }) { Text("Delete", color = Bad) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
