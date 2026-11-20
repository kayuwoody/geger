package com.geger.trial

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.ContextCompat
import com.geger.positioning.config.TrialConfig
import com.geger.trial.ar.ArHost
import com.geger.trial.ar.ArStatus
import com.geger.trial.recording.LiveStats
import com.geger.trial.recording.SessionRecorder
import com.geger.trial.recording.SessionSetup
import com.geger.trial.recording.SessionStore
import com.geger.trial.recording.SessionSummary
import com.geger.trial.recording.StoredSession
import com.geger.trial.settings.ConfigStore
import com.geger.trial.settings.LoadedMap
import com.geger.trial.settings.MapStore
import com.geger.trial.ui.BusyScreen
import com.geger.trial.ui.RecordingScreen
import com.geger.trial.ui.SessionsScreen
import com.geger.trial.ui.SetupActions
import com.geger.trial.ui.SetupScreen
import com.geger.trial.ui.SetupState
import com.geger.trial.ui.SummaryScreen
import kotlinx.coroutines.delay

/** Which screen is showing. */
private sealed class Screen {
    data object Setup : Screen()
    data object Recording : Screen()
    data class Busy(val text: String) : Screen()
    data class Summary(val summary: SessionSummary) : Screen()
    data object Sessions : Screen()
}

/**
 * The app's only activity: owns the state, the AR session and the current recording, and picks
 * the screen.
 *
 * Concept: Android pauses and resumes an activity whenever it leaves or returns to the screen, and
 * the camera must be released while paused. So the AR session follows onResume/onPause, while a
 * recording (sensors + log) keeps running through a pause and simply logs that it happened.
 */
class MainActivity : ComponentActivity() {

    private lateinit var ar: ArHost
    private lateinit var configStore: ConfigStore
    private lateinit var mapStore: MapStore
    private lateinit var sessionStore: SessionStore

    // ---- UI state (Compose redraws when these change) ----
    private var screen by mutableStateOf<Screen>(Screen.Setup)
    private var arStatus by mutableStateOf<ArStatus>(ArStatus.NotChecked)
    private var permissionsOk by mutableStateOf(false)
    private var runType by mutableStateOf("desk_test")
    private var carryMode by mutableStateOf("flat")
    private var tester by mutableStateOf("")
    private var notes by mutableStateOf("")
    private var config by mutableStateOf(TrialConfig.defaults())
    private var configInfo by mutableStateOf("Built-in defaults")
    private var map by mutableStateOf<LoadedMap?>(null)
    private var message by mutableStateOf<String?>(null)
    private var live by mutableStateOf<LiveStats?>(null)
    private var sessions by mutableStateOf<List<StoredSession>>(emptyList())
    private var recorder: SessionRecorder? = null

    /** Asks for camera + activity-recognition permission; the answer arrives in the callback. */
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // ARCore itself is started in onResume, which Android calls right after the permission dialog closes.
        permissionsOk = hasPermissions()
    }

    /** File pickers for map.json and config.json (any file type: Drive often hides JSON types). */
    private val pickMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importMap) }
    private val pickConfig = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importConfig) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ar = ArHost(this)
        configStore = ConfigStore(this)
        mapStore = MapStore(this)
        sessionStore = SessionStore(this)
        configStore.load().let { (c, info) -> config = c; configInfo = info }
        map = mapStore.load()
        permissionsOk = hasPermissions()
        if (!permissionsOk) requestPermissions()

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Content() }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Content() {
        when (val s = screen) {
            Screen.Setup -> SetupScreen(setupState(), setupActions())
            Screen.Recording -> {
                BackHandler { message = "Use Stop to end the recording." }
                LaunchedEffect(Unit) {
                    while (true) {
                        live = recorder?.snapshot()
                        delay(500)
                    }
                }
                RecordingScreen(ar.view, live, onNote = { recorder?.note(it) }, onStop = { stopRecording("user") })
            }
            is Screen.Busy -> BusyScreen(s.text)
            is Screen.Summary -> {
                BackHandler { screen = Screen.Setup }
                SummaryScreen(s.summary, onExport = { export(s.summary.id) }, onDone = { screen = Screen.Setup })
            }
            Screen.Sessions -> {
                BackHandler { screen = Screen.Setup }
                SessionsScreen(
                    sessions,
                    onExport = ::export,
                    onDelete = { sessionStore.delete(it); sessions = sessionStore.list() },
                    onBack = { screen = Screen.Setup },
                )
            }
        }
    }

    private fun setupState() = SetupState(
        arStatus = when (val a = arStatus) {
            ArStatus.NotChecked -> "checking…"
            ArStatus.Installing -> "installing Google Play Services for AR…"
            ArStatus.Ready -> "ready" + (ar.cameraInfo?.let { " · camera image ${it.cpuWidth}×${it.cpuHeight}" } ?: "")
            is ArStatus.Failed -> a.message
        },
        arReady = arStatus == ArStatus.Ready,
        permissionsOk = permissionsOk,
        runType = runType, carryMode = carryMode, tester = tester, notes = notes,
        mapInfo = map?.let { "${it.map.venue} v${it.map.version} · ${it.map.levels.size} level(s), ${it.map.anchors.size} plate(s), ${it.map.checkpoints.size} checkpoint(s)" }
            ?: "No map (not needed for a desk test)",
        mapWarnings = map?.warnings.orEmpty(),
        configInfo = "$configInfo · ${config.filter.particleCount} particles, step ${config.steps.stepLengthM} m",
        message = message,
    )

    private fun setupActions() = SetupActions(
        onRunType = { runType = it },
        onCarryMode = { carryMode = it },
        onTester = { tester = it },
        onNotes = { notes = it },
        onGrantPermissions = ::requestPermissions,
        onChooseMap = { message = null; pickMap.launch(arrayOf("*/*")) },
        onClearMap = { mapStore.clear(); map = null },
        onLoadConfig = { message = null; pickConfig.launch(arrayOf("*/*")) },
        onResetConfig = { configStore.reset(); configStore.load().let { (c, info) -> config = c; configInfo = info } },
        onStart = ::startRecording,
        onSessions = { sessions = sessionStore.list(); screen = Screen.Sessions },
    )

    // ---- Lifecycle -------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        permissionsOk = hasPermissions()
        if (permissionsOk) arStatus = ar.ensureSession()
        if (screen == Screen.Recording) ar.resume()
    }

    override fun onPause() {
        super.onPause()
        if (recorder != null) recorder?.note("[app paused: camera tracking stops until the app is back on screen]")
        ar.pause()
    }

    override fun onDestroy() {
        recorder?.let { r -> recorder = null; Thread { r.stop("app closed") }.start() }
        ar.close()
        super.onDestroy()
    }

    // ---- Actions ---------------------------------------------------------------------------

    private fun hasPermissions() = listOf(Manifest.permission.CAMERA, Manifest.permission.ACTIVITY_RECOGNITION)
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPermissions() =
        permissionRequest.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACTIVITY_RECOGNITION))

    /** Starts a session: recorder first (so the first frames are logged), then the camera. */
    private fun startRecording() {
        message = null
        if (arStatus != ArStatus.Ready) { message = "AR is not ready yet."; return }
        val r = try {
            SessionRecorder(this, SessionSetup(runType, carryMode, tester.trim(), notes.trim()), config, map, ar.cameraInfo)
        } catch (e: Exception) {
            message = "Could not start recording: ${e.message}"
            return
        }
        recorder = r
        ar.onFrame = r::onFrame
        live = null
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screen = Screen.Recording
        ar.resume()
    }

    /** Stops the session; finishing and compressing the log happens on a background thread. */
    private fun stopRecording(reason: String) {
        val r = recorder ?: return
        recorder = null
        ar.onFrame = null
        ar.pause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screen = Screen.Busy("Saving session…")
        Thread {
            val summary = r.stop(reason)
            runOnUiThread { screen = Screen.Summary(summary) }
        }.start()
    }

    /** Zips a session on a background thread (logs can be tens of MB), then opens the share sheet. */
    private fun export(id: String) {
        val back = screen
        screen = Screen.Busy("Preparing $id for export…")
        Thread {
            val result = runCatching { sessionStore.exportZip(id) }
            runOnUiThread {
                screen = back
                result
                    .onSuccess { uri -> startActivity(sessionStore.shareIntent(uri, id)) }
                    .onFailure { e -> message = "Export failed: ${e.message}"; screen = Screen.Setup }
            }
        }.start()
    }

    /** Reads a chosen file as text. */
    private fun readText(uri: Uri): String? =
        runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()

    private fun importMap(uri: Uri) {
        val text = readText(uri) ?: run { message = "Could not read that file."; return }
        mapStore.import(text)
            .onSuccess { map = it; message = null }
            .onFailure { message = "Map rejected:\n${it.message}" }
    }

    private fun importConfig(uri: Uri) {
        val text = readText(uri) ?: run { message = "Could not read that file."; return }
        val error = configStore.import(text)
        if (error != null) message = "Config rejected: $error"
        configStore.load().let { (c, info) -> config = c; configInfo = info }
    }
}
