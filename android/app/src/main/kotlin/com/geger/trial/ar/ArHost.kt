package com.geger.trial.ar

import android.app.Activity
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import java.util.EnumSet
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** What the app knows about ARCore on this phone, for the setup screen. */
sealed class ArStatus {
    data object NotChecked : ArStatus()
    /** ARCore asked the user to install or update Google Play Services for AR. */
    data object Installing : ArStatus()
    data object Ready : ArStatus()
    data class Failed(val message: String) : ArStatus()
}

/** The camera configuration ARCore chose, logged at session start. */
data class CameraInfo(val cpuWidth: Int, val cpuHeight: Int, val textureWidth: Int, val textureHeight: Int, val fpsMin: Int, val fpsMax: Int)

/**
 * Owns the ARCore session and the OpenGL view that drives it.
 *
 * Concept — the AR frame loop: ARCore produces one tracking update per camera frame. On every
 * redraw, the GL thread calls session.update(), which returns the newest frame (camera pose,
 * tracking state, timestamp); we draw its camera image as the background and pass the frame to
 * [onFrame] for logging. The session must be paused whenever the activity pauses, because the
 * camera is shared with the rest of the phone.
 *
 * Threading: the session is created and paused/resumed on the main thread; update() and all
 * drawing happen on the GL thread, so [onFrame] is called on the GL thread.
 */
class ArHost(private val activity: Activity) {

    var session: Session? = null
        private set
    var status: ArStatus = ArStatus.NotChecked
        private set
    var cameraInfo: CameraInfo? = null
        private set

    /** Called on the GL thread with each new frame (set by the recorder while recording). */
    @Volatile var onFrame: ((Frame) -> Unit)? = null

    private var installRequested = false
    /** Set on the main thread, read on the GL thread, hence volatile. */
    @Volatile private var resumed = false
    private val background = BackgroundRenderer()

    /** The view that shows the camera and runs the frame loop. Added to the recording screen. */
    val view: GLSurfaceView = GLSurfaceView(activity).apply {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(FrameRenderer())
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        setWillNotDraw(false)
    }

    /**
     * Makes sure Google Play Services for AR is installed and creates the session.
     * Call from onResume with camera permission granted. Returns the new status.
     *
     * Concept: requestInstall may send the user to the Play Store; it returns INSTALL_REQUESTED
     * and we try again on the next onResume, when the user comes back.
     */
    fun ensureSession(): ArStatus {
        if (session != null) return status
        try {
            when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    status = ArStatus.Installing
                    return status
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
            val s = Session(activity)
            configure(s)
            session = s
            status = ArStatus.Ready
        } catch (e: UnavailableException) {
            status = ArStatus.Failed("ARCore is not available: ${e.javaClass.simpleName}. ${e.message ?: ""}".trim())
        } catch (e: Exception) {
            status = ArStatus.Failed("ARCore could not start: ${e.message}")
        }
        return status
    }

    /**
     * Chooses the back-camera configuration at 30 fps with the largest CPU image (the image the QR
     * detector will read in M4 — distant plates need pixels), then sets tracking options.
     */
    private fun configure(s: Session) {
        val filter = CameraConfigFilter(s)
            .setFacingDirection(CameraConfig.FacingDirection.BACK)
            .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
        val configs = s.getSupportedCameraConfigs(filter)
        val best = configs.maxWithOrNull(compareBy({ it.imageSize.width.toLong() * it.imageSize.height }, { it.fpsRange.upper }))
        if (best != null) {
            s.cameraConfig = best
            cameraInfo = CameraInfo(
                best.imageSize.width, best.imageSize.height, best.textureSize.width, best.textureSize.height,
                best.fpsRange.lower, best.fpsRange.upper,
            )
        }
        val config = Config(s).apply {
            focusMode = Config.FocusMode.AUTO
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            updateMode = Config.UpdateMode.BLOCKING
            lightEstimationMode = Config.LightEstimationMode.DISABLED
            depthMode = Config.DepthMode.DISABLED
        }
        s.configure(config)
    }

    /** Starts the camera and frame loop (activity onResume, while on the recording screen). */
    fun resume() {
        val s = session ?: return
        if (resumed) return
        try {
            s.resume()
            view.onResume()
            resumed = true
        } catch (e: CameraNotAvailableException) {
            status = ArStatus.Failed("The camera is in use by another app. Close it and try again.")
        }
    }

    /** Stops the frame loop, then the camera (GL thread first, so update() isn't mid-call). */
    fun pause() {
        if (!resumed) return
        view.onPause()
        session?.pause()
        resumed = false
    }

    /** Frees the session when the activity is destroyed. */
    fun close() {
        pause()
        session?.close()
        session = null
    }

    /** The screen's current rotation, which ARCore needs to orient the camera image. */
    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display?.rotation ?: Surface.ROTATION_0
        else (activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation

    /** The GL thread's work: create the texture once, then update ARCore and draw every frame. */
    private inner class FrameRenderer : GLSurfaceView.Renderer {
        private var width = 0
        private var height = 0
        private var geometryChanged = true
        private var textureSetOn: Session? = null

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1f)
            background.createOnGlThread()
            textureSetOn = null
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            GLES20.glViewport(0, 0, w, h)
            width = w
            height = h
            geometryChanged = true
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val s = session ?: return
            if (!resumed) return
            if (textureSetOn !== s) {
                s.setCameraTextureName(background.textureId)
                textureSetOn = s
            }
            if (geometryChanged) {
                s.setDisplayGeometry(displayRotation(), width, height)
                geometryChanged = false
            }
            val frame = try {
                s.update()
            } catch (e: Exception) {
                return // paused or camera lost; the next resume restarts the loop
            }
            background.draw(frame)
            onFrame?.invoke(frame)
        }
    }
}
