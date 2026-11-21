package com.geger.positioning

import com.geger.positioning.frames.Alignment
import com.geger.positioning.frames.Frames
import com.geger.positioning.frames.Pose
import com.geger.positioning.frames.Quat
import com.geger.positioning.frames.Vec3
import com.geger.positioning.geometry.Vec2
import com.geger.positioning.map.Anchor
import com.geger.positioning.qr.CameraIntrinsics
import com.geger.positioning.qr.GrayImage
import com.geger.positioning.qr.Mat3
import com.geger.positioning.qr.PlanarPoseSolver
import com.geger.positioning.qr.PlatePose
import com.geger.positioning.qr.PlateLayout
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Test helper: builds known camera/plate geometries and renders what the camera would see.
 *
 * Concept: with a synthetic scene we know the true answer exactly — where the phone is, where the
 * frame corners project — so the solver can be checked to the millimetre before any phone exists.
 * The renderer traces a ray through every pixel (4×4 sub-samples plus a light lens blur),
 * finds where it hits the plate plane, and paints frame, gap, QR modules, white paper margin or a
 * grey wall, then adds sensor noise.
 */
object SyntheticPlates {

    /** A typical 1920×1080 CPU-image camera (horizontal field of view about 67°). */
    val K = CameraIntrinsics(1450.0, 1450.0, 959.5, 539.5, 1920, 1080)

    /** QR module grid for a payload from test resources (21 rows of '#'/'.'). */
    fun qrMatrix(name: String = "WF1-A01"): List<String> =
        requireNotNull(javaClass.getResource("/qr/$name.txt")) { "missing qr/$name.txt" }.readText().trim().lines()

    /**
     * One scene: a plate on a wall in ARCore's world, a camera looking at it, and the matching map.
     * [cameraWorld] is ARCore's sensor-oriented camera pose; [plateInCv] is T_cvcam←plate (truth);
     * [anchor] puts the plate on a map with [alignment] as the true ARCore→map alignment.
     */
    class Scene(
        val cameraWorld: Pose,
        val plateInCv: PlatePose,
        val anchor: Anchor,
        val alignment: Alignment,
        val sizeM: Double,
        val distanceM: Double,
        val viewAngleDeg: Double,
    ) {
        /** True phone map position and heading. */
        val truePhone: Vec3 get() = alignment.arCoreWorldToMap(cameraWorld.translation)
        val trueHeadingDeg: Double get() = Frames.phoneHeadingDeg(cameraWorld.rotation, alignment)

        /** True pixel positions of the frame's outer corners, plate order TL, TR, BR, BL. */
        fun frameCorners(): List<Vec2> = PlanarPoseSolver.objectCorners(sizeM).map { plateInCv.project(it, K) }

        /** True pixel positions of the QR code's corners (as a reader would report them), TL, TR, BR, BL. */
        fun qrCorners(): List<Vec2> {
            val q = sizeM * PlateLayout.QR_HALF / PlateLayout.TOTAL
            return listOf(Vec3(-q, q, 0.0), Vec3(q, q, 0.0), Vec3(q, -q, 0.0), Vec3(-q, -q, 0.0)).map { plateInCv.project(it, K) }
        }
    }

    /**
     * Builds a scene. The plate faces world heading [plateHeadingDeg] with its centre at 1.5 m;
     * the camera stands [distanceM] away at [viewAngleDeg] to the side of the plate's normal, at
     * height [cameraHeightM], aims at the plate with an aiming error of [aimJitterDeg] (so the plate
     * isn't always centred), and is rolled by [rollDeg] about its viewing axis. [inPlaneDeg] mounts
     * the plate rotated about its own normal (0 = upright). The map has the plate at (5, 2) facing 90°.
     */
    fun scene(
        distanceM: Double,
        viewAngleDeg: Double,
        rollDeg: Double = 0.0,
        plateHeadingDeg: Double = 30.0,
        cameraHeightM: Double = 1.4,
        aimJitterDeg: Vec2 = Vec2(0.0, 0.0),
        sizeM: Double = 0.18,
        inPlaneDeg: Double = 0.0,
    ): Scene {
        val h = Math.toRadians(plateHeadingDeg)
        val normal = Vec3(sin(h), 0.0, -cos(h)) // world heading convention: 0 = −z, clockwise from above
        val up = Vec3(0.0, 1.0, 0.0)
        val plateCentre = Vec3(2.0, 1.5, -3.0)
        var px = up.cross(normal)
        var py = up
        if (inPlaneDeg != 0.0) {
            val a = Math.toRadians(inPlaneDeg)
            val x2 = px * cos(a) + py * sin(a)
            val y2 = py * cos(a) - px * sin(a)
            px = x2; py = y2
        }
        val plateWorld = Pose(Mat3.fromCols(px, py, normal).toQuat(), plateCentre)

        // Camera position: rotate the normal about the vertical by the view angle, step out.
        val va = Math.toRadians(viewAngleDeg)
        val dir = Vec3(normal.x * cos(va) - normal.z * sin(va), 0.0, normal.x * sin(va) + normal.z * cos(va))
        val horiz = kotlin.math.sqrt(maxOf(0.0, distanceM * distanceM - (cameraHeightM - 1.5) * (cameraHeightM - 1.5)))
        val camPos = Vec3(plateCentre.x + dir.x * horiz, cameraHeightM, plateCentre.z + dir.z * horiz)

        // Look at the plate (with aiming error), then roll.
        var forward = (plateCentre - camPos).normalized()
        val yawJ = Math.toRadians(aimJitterDeg.x)
        val pitchJ = Math.toRadians(aimJitterDeg.y)
        forward = Vec3(forward.x * cos(yawJ) - forward.z * sin(yawJ), forward.y, forward.x * sin(yawJ) + forward.z * cos(yawJ))
        forward = (forward + up * pitchJ).normalized()
        val right = forward.cross(up).normalized()
        val camUp = right.cross(forward)
        val r = Math.toRadians(rollDeg)
        val xAr = right * cos(r) + camUp * sin(r)
        val yAr = camUp * cos(r) - right * sin(r)
        val zAr = -forward
        val cameraWorld = Pose(Mat3.fromCols(xAr, yAr, zAr).toQuat(), camPos)

        val plateInArCam = cameraWorld.inverse().compose(plateWorld)
        val inCv = Frames.arCoreCamToOpenCvCam(plateInArCam)
        val plateInCv = PlatePose(Mat3.fromQuat(inCv.rotation), inCv.translation)

        val anchor = Anchor("A01", "L1", Vec2(5.0, 2.0), 1.5, 90.0, sizeM)
        val alignment = Alignment.fromCorrespondence(plateCentre, Frames.arWorldHeadingDeg(normal), Vec3(5.0, 2.0, 1.5), 90.0)
        return Scene(cameraWorld, plateInCv, anchor, alignment, sizeM, distanceM, viewAngleDeg)
    }

    /**
     * Renders the scene's camera image (grayscale). Brightness: wall 90, paper 225, ink 25, plus
     * Gaussian noise of [noise] levels. A white paper margin of [marginModules] surrounds the frame.
     */
    fun render(scene: Scene, matrix: List<String> = qrMatrix(), noise: Double = 2.0, marginModules: Double = 2.0, seed: Int = 1): GrayImage {
        val k = K
        val w = k.width
        val hgt = k.height
        val data = ByteArray(w * hgt) { 90.toByte() }
        val module = scene.sizeM / PlateLayout.TOTAL
        val half = scene.sizeM / 2 + marginModules * module
        // Only trace pixels inside the projected paper's bounding box.
        val box = listOf(Vec3(-half, half, 0.0), Vec3(half, half, 0.0), Vec3(half, -half, 0.0), Vec3(-half, -half, 0.0)).map { scene.plateInCv.project(it, k) }
        val x0 = max(0, floor(box.minOf { it.x }).toInt() - 2)
        val x1 = min(w - 1, floor(box.maxOf { it.x }).toInt() + 2)
        val y0 = max(0, floor(box.minOf { it.y }).toInt() - 2)
        val y1 = min(hgt - 1, floor(box.maxOf { it.y }).toInt() + 2)
        val rt = scene.plateInCv.r.transpose()
        val origin = -(rt * scene.plateInCv.t) // camera centre in plate coordinates
        fun shade(u: Double, v: Double): Int {
            val d = rt * Vec3((u - k.cx) / k.fx, (v - k.cy) / k.fy, 1.0)
            if (d.z == 0.0) return 90
            val lambda = -origin.z / d.z
            if (lambda <= 0) return 90
            val px = origin.x + lambda * d.x
            val py = origin.y + lambda * d.y
            val mx = px / module + PlateLayout.TOTAL / 2.0
            val my = -py / module + PlateLayout.TOTAL / 2.0
            val edge = PlateLayout.TOTAL.toDouble()
            if (mx < -marginModules || my < -marginModules || mx > edge + marginModules || my > edge + marginModules) return 90
            if (mx < 0 || my < 0 || mx >= edge || my >= edge) return 225
            if (mx < PlateLayout.FRAME || my < PlateLayout.FRAME || mx >= edge - PlateLayout.FRAME || my >= edge - PlateLayout.FRAME) return 25
            val qx = mx - PlateLayout.FRAME - PlateLayout.GAP
            val qy = my - PlateLayout.FRAME - PlateLayout.GAP
            if (qx < 0 || qy < 0 || qx >= PlateLayout.QR || qy >= PlateLayout.QR) return 225
            return if (matrix[qy.toInt()][qx.toInt()] == '#') 25 else 225
        }
        // 4×4 sub-samples per pixel (area coverage), then a light [1 2 1] blur standing in for the
        // lens, then sensor noise. Without the blur, axis-aligned edges get a staircase profile
        // that real cameras don't produce.
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        val sharp = DoubleArray(bw * bh)
        for (y in y0..y1) for (x in x0..x1) {
            var sum = 0
            for (sy in 0 until 4) for (sx in 0 until 4) sum += shade(x - 0.375 + sx * 0.25, y - 0.375 + sy * 0.25)
            sharp[(y - y0) * bw + (x - x0)] = sum / 16.0
        }
        fun at(ix: Int, iy: Int) = sharp[iy.coerceIn(0, bh - 1) * bw + ix.coerceIn(0, bw - 1)]
        val rowBlur = DoubleArray(bw * bh) { i -> val ix = i % bw; val iy = i / bw; (at(ix - 1, iy) + 2 * at(ix, iy) + at(ix + 1, iy)) / 4 }
        fun rb(ix: Int, iy: Int) = rowBlur[iy.coerceIn(0, bh - 1) * bw + ix]
        val rng = Random(seed)
        for (iy in 0 until bh) for (ix in 0 until bw) {
            val v = (rb(ix, iy - 1) + 2 * rb(ix, iy) + rb(ix, iy + 1)) / 4 + gaussian(rng) * noise
            data[(iy + y0) * w + ix + x0] = v.coerceIn(0.0, 255.0).toInt().toByte()
        }
        return GrayImage(w, hgt, data)
    }

    /** Standard normal random number (Box–Muller). */
    fun gaussian(rng: Random): Double {
        val u1 = rng.nextDouble(1e-12, 1.0)
        val u2 = rng.nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * Math.PI * u2)
    }

    /** Adds Gaussian pixel noise of [sigmaPx] to each point. */
    fun jitter(points: List<Vec2>, sigmaPx: Double, rng: Random) = points.map { Vec2(it.x + gaussian(rng) * sigmaPx, it.y + gaussian(rng) * sigmaPx) }

    /** Unit quaternion helper for tests that need the display orientation = sensor orientation. */
    fun same(q: Quat) = q
}
