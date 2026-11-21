package com.geger.trial.qr

import android.os.SystemClock
import com.geger.positioning.config.TrialConfig
import com.geger.positioning.frames.Pose
import com.geger.positioning.frames.Quat
import com.geger.positioning.frames.Vec3
import com.geger.positioning.geometry.Vec2
import com.geger.positioning.map.VenueMap
import com.geger.positioning.qr.CameraIntrinsics
import com.geger.positioning.qr.GrayImage
import com.geger.positioning.qr.PlateLayout
import com.geger.positioning.qr.QrFix
import com.geger.positioning.qr.QrFixSolver
import com.geger.positioning.qr.QrSighting
import com.google.android.gms.tasks.Tasks
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Looks for QR plates in the camera image a few times a second and turns each sighting into a fix.
 *
 * Concept — split across two threads: on the AR render thread (inside the frame loop) we only grab
 * a copy of the camera image's brightness plane plus the camera pose and intrinsics for that same
 * instant, then return immediately so the AR view stays smooth. A worker thread runs ML Kit's QR
 * reader on the copy and, for each of our plates ("WF1:" codes), the positioning-core solver. Only
 * one image is in flight at a time; frames that arrive meanwhile are skipped.
 *
 * [onFix] is called on the worker thread with each result (accepted or rejected), its sighting and
 * the receipt time on the elapsedRealtime clock.
 */
class QrDetector(
    private val config: TrialConfig,
    private val map: VenueMap?,
    private val defaultPlateSizeM: Double,
    private val onFix: (fix: QrFix, sighting: QrSighting, tNs: Long, frameStampNs: Long) -> Unit,
) {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "geger-qr").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private var lastScanNs = 0L
    private var nv21: ByteArray? = null

    /** Called on the GL thread for every frame; grabs an image copy when it's time to scan. */
    fun onFrame(frame: Frame) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (busy.get() || now - lastScanNs < (1e9 / config.qr.detectionHz).toLong()) return
        val cam = frame.camera
        if (cam.trackingState != TrackingState.TRACKING) return // a fix needs the camera's pose
        val image = try { frame.acquireCameraImage() } catch (e: Exception) { return }
        val width: Int
        val height: Int
        val luma: ByteArray
        try {
            width = image.width
            height = image.height
            val plane = image.planes[0]
            val buf = plane.buffer
            val stride = plane.rowStride
            luma = ByteArray(width * height)
            // Copy row by row: the camera buffer may pad each row (rowStride > width).
            for (row in 0 until height) {
                buf.position(row * stride)
                buf.get(luma, row * width, width)
            }
        } catch (e: Exception) {
            return
        } finally {
            image.close()
        }
        val intr = cam.imageIntrinsics
        val focal = intr.focalLength
        val pp = intr.principalPoint
        val k = CameraIntrinsics(focal[0].toDouble(), focal[1].toDouble(), pp[0].toDouble(), pp[1].toDouble(), width, height)
        val p = cam.pose
        val cameraWorld = Pose(Quat(p.qx().toDouble(), p.qy().toDouble(), p.qz().toDouble(), p.qw().toDouble()),
            Vec3(p.tx().toDouble(), p.ty().toDouble(), p.tz().toDouble()))
        val d = cam.displayOrientedPose
        val display = Quat(d.qx().toDouble(), d.qy().toDouble(), d.qz().toDouble(), d.qw().toDouble())
        val stamp = frame.timestamp
        lastScanNs = now
        busy.set(true)
        worker.execute {
            try { process(luma, width, height, k, cameraWorld, display, now, stamp) } catch (_: Exception) { } finally { busy.set(false) }
        }
    }

    /** Worker thread: read QR codes, then solve a fix for each of our plates. */
    private fun process(luma: ByteArray, w: Int, h: Int, k: CameraIntrinsics, cameraWorld: Pose, display: Quat, tNs: Long, stamp: Long) {
        // ML Kit wants a colour format; NV21 with neutral colour (128) is grey and costs nothing extra.
        val size = w * h * 3 / 2
        val buf = nv21?.takeIf { it.size == size } ?: ByteArray(size).also { nv21 = it }
        System.arraycopy(luma, 0, buf, 0, w * h)
        java.util.Arrays.fill(buf, w * h, size, 128.toByte())
        val codes = Tasks.await(scanner.process(InputImage.fromByteArray(buf, w, h, 0, InputImage.IMAGE_FORMAT_NV21)), 2, TimeUnit.SECONDS)
        val gray = GrayImage(w, h, luma)
        for (code in codes) {
            val payload = code.rawValue ?: continue
            val id = PlateLayout.anchorId(payload) ?: continue
            val corners = code.cornerPoints?.map { Vec2(it.x.toDouble(), it.y.toDouble()) } ?: continue
            val anchor = map?.anchors?.get(id)
            val sizeM = anchor?.sizeM ?: defaultPlateSizeM
            val elevation = anchor?.let { map.levels[it.levelId]?.elevationM } ?: 0.0
            val sighting = QrSighting(payload, corners, gray, k, cameraWorld, display)
            val fix = QrFixSolver.solve(sighting, sizeM, anchor, elevation, config.qr, config.phoneMode)
            onFix(fix, sighting, tNs, stamp)
        }
    }

    /** Stops the worker and frees the reader. */
    fun close() {
        worker.shutdown()
        worker.awaitTermination(2, TimeUnit.SECONDS)
        scanner.close()
    }
}
