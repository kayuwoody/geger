package com.geger.positioning.qr

import com.geger.positioning.frames.Pose
import com.geger.positioning.frames.Vec3
import com.geger.positioning.geometry.Vec2
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pinhole camera intrinsics for one image (ARCore's `camera.imageIntrinsics` for the CPU image).
 * Focal lengths and principal point are in pixels; image u runs right, v runs down.
 */
data class CameraIntrinsics(val fx: Double, val fy: Double, val cx: Double, val cy: Double, val width: Int, val height: Int)

/**
 * A plate's pose in the OpenCV camera frame: T_cvcam←plate.
 * [r] rotates plate axes (x right, y up, z out of the plate) into camera axes (x right, y down,
 * z forward); [t] is the plate centre in camera coordinates, metres.
 */
data class PlatePose(val r: Mat3, val t: Vec3) {
    /** Camera coordinates of a point given in plate coordinates. */
    fun toCamera(p: Vec3): Vec3 = r * p + t

    /** Pixel position of a plate point (pinhole projection). */
    fun project(p: Vec3, k: CameraIntrinsics): Vec2 {
        val c = toCamera(p)
        return Vec2(k.fx * c.x / c.z + k.cx, k.fy * c.y / c.z + k.cy)
    }

    /** As a [Pose] (quaternion + translation) for the frame conversions in Frames.kt. */
    fun toPose() = Pose(r.toQuat(), t)

    /** Direction the plate faces, in camera coordinates (its +z axis). */
    val normal: Vec3 get() = r.col(2)
}

/** A pose hypothesis and how well it explains the observed corners (RMS reprojection error, pixels). */
data class PoseCandidate(val pose: PlatePose, val rmsPx: Double)

/**
 * The plate pose after enforcing "the plate hangs vertically", with its uncertainty.
 *
 * [params] = (ψ, tx, ty, tz): ψ turns the plate about the vertical; [covariance] (4×4) is the
 * parameters' covariance for the given pixel noise. [poseAt] rebuilds a pose from parameters, so
 * callers can propagate the uncertainty to anything computed from the pose.
 */
class GravityPose(
    val pose: PlatePose,
    val rmsPx: Double,
    val params: DoubleArray,
    val covariance: Array<DoubleArray>?,
    val poseAt: (DoubleArray) -> PlatePose,
)

/**
 * Solves the camera-to-plate pose from the plate frame's four corners in the image.
 *
 * Concept — perspective-n-point for a square: we know the frame's real corner positions on the
 * plate (±size/2) and where they appear in the image; the pose is whatever placement of the plate
 * in front of the camera projects them onto those pixels. Steps:
 *  1. A homography (the projective map from the flat plate to the image) gives a first guess.
 *  2. Levenberg–Marquardt refines rotation and position to minimise the pixel error.
 *  3. A flat square seen at a distance can look almost the same tilted left or right by the same
 *     amount (the planar ambiguity), so a mirrored guess is refined too, and both are returned.
 *  4. Plates hang vertically, so the caller picks the candidate whose "up" matches gravity and
 *     re-solves with the plate held exactly vertical ([refineWithGravity]), leaving 4 unknowns:
 *     the turn about the vertical and the 3D position.
 */
object PlanarPoseSolver {

    /** Frame corners in plate coordinates, in the order TL, TR, BR, BL (as seen facing the plate). */
    fun objectCorners(sizeM: Double): List<Vec3> {
        val h = sizeM / 2
        return listOf(Vec3(-h, h, 0.0), Vec3(h, h, 0.0), Vec3(h, -h, 0.0), Vec3(-h, -h, 0.0))
    }

    /** Pixel residuals (predicted − observed) for the 4 corners: [du0, dv0, du1, dv1, ...]. */
    fun residuals(pose: PlatePose, image: List<Vec2>, sizeM: Double, k: CameraIntrinsics): DoubleArray {
        val obj = objectCorners(sizeM)
        val out = DoubleArray(8)
        for (i in 0 until 4) {
            val c = pose.toCamera(obj[i])
            if (c.z <= 1e-6) { out[2 * i] = 1e6; out[2 * i + 1] = 1e6; continue } // behind the camera
            out[2 * i] = k.fx * c.x / c.z + k.cx - image[i].x
            out[2 * i + 1] = k.fy * c.y / c.z + k.cy - image[i].y
        }
        return out
    }

    /** Root-mean-square pixel error over the 4 corners. */
    fun rmsPx(pose: PlatePose, image: List<Vec2>, sizeM: Double, k: CameraIntrinsics): Double =
        sqrt(residuals(pose, image, sizeM, k).sumOf { it * it } / 4)

    /**
     * First guess from the homography between the plate plane and normalised image coordinates.
     *
     * Concept: for points on the plane z = 0, projection reduces to a 3×3 homography
     * H = λ·[r1 r2 t] (the first two rotation columns and the translation). Solving H from the 4
     * correspondences (8 equations) and normalising gives r1, r2 and t; r3 = r1 × r2, then the
     * columns are re-orthogonalised.
     */
    fun fromHomography(image: List<Vec2>, sizeM: Double, k: CameraIntrinsics): PlatePose? {
        val obj = objectCorners(sizeM).map { Vec2(it.x, it.y) }
        val norm = image.map { Vec2((it.x - k.cx) / k.fx, (it.y - k.cy) / k.fy) }
        val h = Homography.fromPoints(obj, norm) ?: return null
        var h1 = Vec3(h[0], h[3], h[6])
        var h2 = Vec3(h[1], h[4], h[7])
        var h3 = Vec3(h[2], h[5], h[8])
        val lambda = 2.0 / (h1.length() + h2.length())
        h1 *= lambda; h2 *= lambda; h3 *= lambda
        if (h3.z < 0) { h1 = -h1; h2 = -h2; h3 = -h3 } // the plate must be in front of the camera
        val r1 = h1.normalized()
        val r2 = (h2 - r1 * r1.dot(h2)).normalized()
        val r3 = r1.cross(r2)
        return PlatePose(Mat3.fromCols(r1, r2, r3), h3)
    }

    /**
     * Refines all 6 pose parameters (rotation as a small rotation vector applied to the starting
     * rotation, plus the translation) to minimise pixel error.
     */
    fun refine6(init: PlatePose, image: List<Vec2>, sizeM: Double, k: CameraIntrinsics): PoseCandidate {
        val build = { p: DoubleArray -> PlatePose(Mat3.rodrigues(Vec3(p[0], p[1], p[2])) * init.r, Vec3(p[3], p[4], p[5])) }
        val res = levenbergMarquardt(doubleArrayOf(0.0, 0.0, 0.0, init.t.x, init.t.y, init.t.z), { p -> residuals(build(p), image, sizeM, k) })
        val pose = build(res.params)
        return PoseCandidate(pose, rmsPx(pose, image, sizeM, k))
    }

    /**
     * The mirrored hypothesis: the plate normal reflected about the line of sight to the plate.
     * Concept: this is the other local solution of the planar ambiguity; refining from here finds
     * it if it exists (for strongly tilted or close views it doesn't, and refinement falls back
     * onto the first solution).
     */
    fun mirrored(pose: PlatePose): PlatePose {
        val n = pose.normal
        val v = pose.t.normalized()
        val n2 = v * (2 * n.dot(v)) - n
        val axis = n.cross(n2)
        val len = axis.length()
        if (len < 1e-9) return pose
        val angle = acos(n.dot(n2).coerceIn(-1.0, 1.0))
        return PlatePose(Mat3.rodrigues(axis * (angle / len)) * pose.r, pose.t)
    }

    /**
     * Up to two pose candidates for the observed corners (TL, TR, BR, BL), best first.
     * The second is dropped if it converges to the same pose as the first.
     */
    fun candidates(image: List<Vec2>, sizeM: Double, k: CameraIntrinsics): List<PoseCandidate> {
        val init = fromHomography(image, sizeM, k) ?: return emptyList()
        val first = refine6(init, image, sizeM, k)
        val second = refine6(mirrored(first.pose), image, sizeM, k)
        val same = angleBetweenDeg(first.pose.normal, second.pose.normal) < 0.5
        return (if (same) listOf(first) else listOf(first, second)).sortedBy { it.rmsPx }
    }

    /**
     * Re-solves with the plate exactly vertical: its +y axis is fixed to [upCv] (world up in OpenCV
     * camera coordinates, from ARCore's gravity), leaving ψ (turn about the vertical) and the
     * position. Also returns the parameter covariance for corner noise [noisePx] (pixels, 1σ).
     *
     * Concept: knowing gravity removes two of the six unknowns and the tilt ambiguity altogether;
     * the covariance (σ²·(JᵀJ)⁻¹ from the solution's Jacobian) says how well the remaining four
     * are pinned down by this particular view — distant, head-on views pin the turn down poorly.
     * Returns null if the plate normal is nearly vertical (no plate on a wall looks like that).
     */
    fun refineWithGravity(init: PlatePose, upCv: Vec3, image: List<Vec2>, sizeM: Double, k: CameraIntrinsics, noisePx: Double): GravityPose? {
        val u = upCv.normalized()
        val n0 = init.normal
        val horizontal = n0 - u * n0.dot(u)
        if (horizontal.length() < 0.2) return null
        val e1 = horizontal.normalized()
        val e2 = u.cross(e1)
        val build = { p: DoubleArray ->
            val n = e1 * cos(p[0]) + e2 * sin(p[0])
            PlatePose(Mat3.fromCols(u.cross(n), u, n), Vec3(p[1], p[2], p[3]))
        }
        val res = levenbergMarquardt(doubleArrayOf(0.0, init.t.x, init.t.y, init.t.z), { p -> residuals(build(p), image, sizeM, k) })
        val pose = build(res.params)
        val jtj = Array(4) { i -> DoubleArray(4) { j -> res.jacobian.sumOf { row -> row[i] * row[j] } } }
        val cov = invert(jtj)?.let { inv -> Array(4) { i -> DoubleArray(4) { j -> inv[i][j] * noisePx * noisePx } } }
        return GravityPose(pose, rmsPx(pose, image, sizeM, k), res.params, cov, build)
    }

    /** Angle between two directions, in degrees. */
    fun angleBetweenDeg(a: Vec3, b: Vec3): Double = Math.toDegrees(acos((a.dot(b) / (a.length() * b.length())).coerceIn(-1.0, 1.0)))
}

/** Homography helpers (the projective map between two planes). */
object Homography {

    /**
     * The homography (row-major 3×3, h33 = 1) mapping the 4 [src] points onto the 4 [dst] points,
     * or null if degenerate.
     *
     * Concept: each correspondence (x, y) → (u, v) gives two linear equations in the 8 unknowns
     * (u = (h11x + h12y + h13)/(h31x + h32y + 1), likewise v); four points give exactly 8.
     */
    fun fromPoints(src: List<Vec2>, dst: List<Vec2>): DoubleArray? {
        val a = Array(8) { DoubleArray(8) }
        val b = DoubleArray(8)
        for (i in 0 until 4) {
            val (x, y) = src[i]
            val (u, v) = dst[i]
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y); b[2 * i] = u
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y); b[2 * i + 1] = v
        }
        val h = solveLinear(a, b) ?: return null
        return doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
    }

    /** Applies homography [h] to point [p]. */
    fun apply(h: DoubleArray, p: Vec2): Vec2 {
        val w = h[6] * p.x + h[7] * p.y + h[8]
        return Vec2((h[0] * p.x + h[1] * p.y + h[2]) / w, (h[3] * p.x + h[4] * p.y + h[5]) / w)
    }
}

