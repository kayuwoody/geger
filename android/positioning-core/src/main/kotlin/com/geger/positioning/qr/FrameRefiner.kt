package com.geger.positioning.qr

import com.geger.positioning.geometry.Vec2
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt

/** The frame's four outer corners found in the image, plus how cleanly each side was fitted. */
data class RefinedFrame(val corners: List<Vec2>, val sideRmsPx: List<Double>, val pointsPerSide: List<Int>, val edgePoints: List<List<Vec2>> = emptyList())

/**
 * Finds the plate's black frame precisely, and which way up the QR code is.
 *
 * Concept — edge fitting: the QR reader only gives a rough outline of the code (its fourth corner
 * is a guess). From that outline we predict where the frame's four outer edges should be, then
 * along each edge take many short brightness profiles across it. Each profile changes from black
 * (frame) to white (paper) somewhere; the steepest point of that change, interpolated between
 * pixels, is one edge point. A straight line fitted through ~30 such points per side is far more
 * precise than any single pixel, and the corners are where neighbouring lines cross.
 *
 * Coordinates: "module space" has its origin at the plate centre, x right, y DOWN (like an image),
 * one unit per QR module; the frame's outer corners are at (±16.5, ±16.5).
 */
object FrameRefiner {

    private const val SAMPLES_PER_SIDE = 32
    private const val SCAN_IN = 1.5   // modules into the frame
    private const val SCAN_OUT = 2.0  // modules out onto the paper margin
    private const val MIN_CONTRAST = 25.0 // brightness change (0–255) needed to trust an edge point

    /** Module-space corners in clockwise image order, matching a clockwise quad's indices 0..3. */
    private fun moduleCorners(half: Double) = listOf(Vec2(-half, -half), Vec2(half, -half), Vec2(half, half), Vec2(-half, half))

    /**
     * Puts 4 points in clockwise order as seen in the image (y down), keeping their cyclic order.
     * Concept: in image coordinates (y down), a visually clockwise loop has a positive shoelace sum.
     */
    fun orderClockwise(pts: List<Vec2>): List<Vec2> {
        var s = 0.0
        for (i in 0 until 4) s += pts[i].cross(pts[(i + 1) % 4])
        return if (s >= 0) pts else listOf(pts[0], pts[3], pts[2], pts[1])
    }

    /** Homography from module space to image pixels, given the QR code's 4 corners (clockwise). */
    fun moduleToImageFromQr(qrCornersCw: List<Vec2>): DoubleArray? = Homography.fromPoints(moduleCorners(PlateLayout.QR_HALF), qrCornersCw)

    /** Homography from module space to image pixels, given the frame's 4 outer corners (clockwise). */
    fun moduleToImageFromFrame(frameCornersCw: List<Vec2>): DoubleArray? = Homography.fromPoints(moduleCorners(PlateLayout.FRAME_OUTER_HALF), frameCornersCw)

    /** Where the frame's outer corners should be, extrapolated from the QR code's corners (clockwise). */
    fun predictFrameCorners(qrCornersCw: List<Vec2>): List<Vec2>? {
        val h = moduleToImageFromQr(qrCornersCw) ?: return null
        return moduleCorners(PlateLayout.FRAME_OUTER_HALF).map { Homography.apply(h, it) }
    }

    /**
     * Refines the frame's outer corners from the QR code's rough corners (any cyclic order).
     * Returns them clockwise in the image (index i corresponds to the clockwise-ordered input
     * corner i), or null if any side couldn't be found cleanly.
     *
     * Two passes: the first scans around edges predicted from the reader's rough corners (which
     * can be a module off near the guessed fourth corner, so some scans miss); the second scans
     * again around the edges found by the first, so every scan straddles its edge.
     */
    fun refine(img: GrayImage, qrCorners: List<Vec2>): RefinedFrame? {
        val cw = orderClockwise(qrCorners)
        val first = refineAround(img, moduleToImageFromQr(cw) ?: return null) ?: return null
        val again = moduleToImageFromFrame(first.corners)?.let { refineAround(img, it) }
        return again ?: first
    }

    /** One edge-fitting pass around the frame edges predicted by module→image homography [h]. */
    private fun refineAround(img: GrayImage, h: DoubleArray): RefinedFrame? {
        val corners = moduleCorners(PlateLayout.FRAME_OUTER_HALF)
        val lines = ArrayList<Line>(4)
        val rms = ArrayList<Double>(4)
        val counts = ArrayList<Int>(4)
        val allPoints = ArrayList<List<Vec2>>(4)
        for (side in 0 until 4) {
            val a = corners[side]
            val b = corners[(side + 1) % 4]
            val along = (b - a) * (1.0 / a.distanceTo(b))
            val outward = Vec2(along.y, -along.x) // clockwise loop in a y-down space: outward is to the left of travel
            val points = ArrayList<Vec2>(SAMPLES_PER_SIDE)
            for (i in 0 until SAMPLES_PER_SIDE) {
                // Stay clear of the corners, where the neighbouring side's edge interferes.
                val s = 2.0 + (a.distanceTo(b) - 4.0) * i / (SAMPLES_PER_SIDE - 1)
                edgePoint(img, h, a + along * s, outward)?.let { points += it }
            }
            allPoints += points
            val fit = fitLine(points) ?: return null
            lines += fit.line
            rms += fit.rms
            counts += fit.used
        }
        val out = (0 until 4).map { i -> lines[(i + 3) % 4].intersect(lines[i]) ?: return null }
        return RefinedFrame(out, rms, counts, allPoints)
    }

    /**
     * One edge point: brightness profile across the frame's outer edge at module-space [base],
     * moving along [outward]. Returns the pixel position where the profile crosses halfway between
     * the frame's black and the paper's white, or null if the profile leaves the image or has too
     * little contrast.
     *
     * Concept: the steepest point would be the textbook edge, but between pixel centres the image
     * is interpolated linearly, so the slope is flat across each pixel and "steepest" is only
     * known to ±½ pixel. The halfway crossing instead uses the brightness values themselves and
     * interpolates to a small fraction of a pixel. With a symmetric lens blur the halfway point is
     * the true edge.
     */
    private fun edgePoint(img: GrayImage, h: DoubleArray, base: Vec2, outward: Vec2): Vec2? {
        val pIn = Homography.apply(h, base - outward * SCAN_IN)
        val pOut = Homography.apply(h, base + outward * SCAN_OUT)
        val lengthPx = pIn.distanceTo(pOut)
        val n = ceil(lengthPx / 0.25).toInt().coerceIn(12, 400)
        val values = DoubleArray(n + 1)
        for (j in 0..n) {
            val p = pIn + (pOut - pIn) * (j.toDouble() / n)
            values[j] = img.sample(p.x, p.y)
            if (values[j].isNaN()) return null
        }
        // Rough location: steepest rise (moving outward the profile goes dark → light).
        var best = -1
        var bestGrad = 0.0
        for (j in 1 until n) {
            val g = values[j + 1] - values[j - 1]
            if (g > bestGrad) { bestGrad = g; best = j }
        }
        if (best < 2 || best > n - 2) return null
        // Black level just inside, white level just outside (darkest / brightest within ~1.5 px).
        val reach = maxOf(3, (1.5 / (lengthPx / n)).toInt())
        val dark = (maxOf(0, best - 2 * reach)..best).minOf { values[it] }
        val light = (best..minOf(n, best + 2 * reach)).maxOf { values[it] }
        if (light - dark < MIN_CONTRAST) return null
        val level = (dark + light) / 2
        // Halfway crossing nearest the steepest point, interpolated between samples.
        var crossing = Double.NaN
        for (d in 0..reach) {
            for (j in listOf(best - d, best + d - 1)) {
                if (j < 0 || j >= n) continue
                val a = values[j]
                val b = values[j + 1]
                if (a <= level && b > level) { crossing = j + (level - a) / (b - a); break }
            }
            if (!crossing.isNaN()) break
        }
        if (crossing.isNaN()) return null
        return pIn + (pOut - pIn) * (crossing / n)
    }

    /** A straight line through [point] with unit [dir]. */
    class Line(val point: Vec2, val dir: Vec2) {
        /** Distance of [p] from the line. */
        fun distance(p: Vec2) = abs((p - point).cross(dir))

        /** Where two lines cross, or null if (nearly) parallel. */
        fun intersect(o: Line): Vec2? {
            val denom = dir.cross(o.dir)
            if (abs(denom) < 1e-9) return null
            val t = (o.point - point).cross(o.dir) / denom
            return point + dir * t
        }
    }

    private class Fit(val line: Line, val rms: Double, val used: Int)

    /**
     * Total-least-squares line through [pts] (the principal direction of their spread), with one
     * round of outlier removal (points more than max(1 px, 3× median distance) away).
     * Needs at least 8 points.
     */
    private fun fitLine(pts: List<Vec2>): Fit? {
        if (pts.size < 8) return null
        var use = pts
        var line = pcaLine(use) ?: return null
        val dists = use.map { line.distance(it) }.sorted()
        val limit = maxOf(1.0, 3 * dists[dists.size / 2])
        use = use.filter { line.distance(it) <= limit }
        if (use.size < 8) return null
        line = pcaLine(use) ?: return null
        val rms = sqrt(use.sumOf { line.distance(it).let { d -> d * d } } / use.size)
        return Fit(line, rms, use.size)
    }

    /**
     * Line through the centroid along the direction of greatest spread.
     * Concept: for a 2×2 covariance [[sxx, sxy], [sxy, syy]] the main axis angle is
     * ½·atan2(2·sxy, sxx − syy).
     */
    private fun pcaLine(pts: List<Vec2>): Line? {
        val cx = pts.sumOf { it.x } / pts.size
        val cy = pts.sumOf { it.y } / pts.size
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (p in pts) { val dx = p.x - cx; val dy = p.y - cy; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        if (sxx + syy < 1e-9) return null
        val angle = 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)
        return Line(Vec2(cx, cy), Vec2(kotlin.math.cos(angle), kotlin.math.sin(angle)))
    }

    /** Scores per corner from [finderScores] and the chosen bottom-right index. */
    data class Orientation(val bottomRight: Int, val scores: List<Double>)

    /**
     * Which of the frame's 4 corners (clockwise indices) is the QR code's bottom-right, i.e. the
     * one corner WITHOUT a finder pattern. Null if that isn't clear-cut.
     *
     * Concept: a QR code has its big square "finder" targets in three corners. For each corner we
     * read the 7×7 module centres there and compare them with the finder template (dark outer ring,
     * light ring, dark 3×3 centre). Three corners score high; the fourth — the code's bottom-right —
     * scores near zero. That fixes the plate's in-plane rotation regardless of how the reader
     * ordered its corners.
     */
    fun orientation(img: GrayImage, frameCornersCw: List<Vec2>): Orientation? {
        val h = moduleToImageFromFrame(frameCornersCw) ?: return null
        val signs = listOf(Vec2(-1.0, -1.0), Vec2(1.0, -1.0), Vec2(1.0, 1.0), Vec2(-1.0, 1.0))
        val scores = signs.map { s -> finderScore(img, h, s) ?: return null }
        val order = scores.indices.sortedBy { scores[it] }
        val lowest = scores[order[0]]
        val nextLowest = scores[order[1]]
        if (nextLowest < 30.0 || lowest > 0.5 * nextLowest) return null
        return Orientation(order[0], scores)
    }

    /** Light-minus-dark contrast of the finder template at the QR corner in direction [sign]. */
    private fun finderScore(img: GrayImage, h: DoubleArray, sign: Vec2): Double? {
        var dark = 0.0; var nDark = 0
        var light = 0.0; var nLight = 0
        for (i in 0 until 7) for (j in 0 until 7) {
            val m = Vec2(sign.x * (PlateLayout.QR_HALF - (i + 0.5)), sign.y * (PlateLayout.QR_HALF - (j + 0.5)))
            val p = Homography.apply(h, m)
            val v = img.sample(p.x, p.y)
            if (v.isNaN()) return null
            val ring = maxOf(abs(i - 3), abs(j - 3))
            if (ring == 2) { light += v; nLight++ } else { dark += v; nDark++ }
        }
        return light / nLight - dark / nDark
    }

    /** Pixel side lengths of a quad, averaged (how big the frame looks). */
    fun meanSidePx(q: List<Vec2>): Double = (0 until 4).sumOf { hypot(q[it].x - q[(it + 1) % 4].x, q[it].y - q[(it + 1) % 4].y) } / 4
}
