package com.geger.positioning.geometry

import kotlin.math.abs

/**
 * Low-level segment maths shared by polygons and wall checks.
 *
 * Concept: a segment from a to b is written as P(t) = a + t·(b − a) with t in [0, 1].
 * Every question we ask ("does a path cross a wall?", "is this polygon valid?") reduces to
 * finding the t values where one segment meets another.
 */
object Segments {

    /** Tolerance in metres for "touching" tests. 1 nanometre: far below any real map precision. */
    const val EPS = 1e-9

    /**
     * Returns the parameters t (in [0, 1]) along segment a→b where it meets segment c→d.
     *
     * Concept: write both segments in parametric form, a + t·r and c + u·s. If they are not
     * parallel, solving the 2×2 system with cross products gives one (t, u); the segments meet if
     * both lie in [0, 1]. If they are parallel AND on the same line (collinear), they can overlap
     * along a stretch; we then return the t values of c and d projected onto a→b (clamped to the
     * segment), which mark where the overlap starts and ends.
     *
     * The result may contain 0, 1 or 2 values. Callers only need where crossings happen, not how.
     */
    fun crossingParams(a: Vec2, b: Vec2, c: Vec2, d: Vec2): List<Double> {
        val r = b - a
        val s = d - c
        val denom = r.cross(s)
        val ca = c - a
        if (abs(denom) > EPS) {
            val t = ca.cross(s) / denom
            val u = ca.cross(r) / denom
            return if (t >= -EPS && t <= 1 + EPS && u >= -EPS && u <= 1 + EPS) {
                listOf(t.coerceIn(0.0, 1.0))
            } else {
                emptyList()
            }
        }
        // Parallel. Only collinear segments (c lies on the line through a and b) can touch.
        if (abs(ca.cross(r)) > EPS * maxOf(1.0, r.length())) return emptyList()
        val rr = r.dot(r)
        if (rr < EPS * EPS) return emptyList() // a == b: a point, handled by callers separately
        val tc = ca.dot(r) / rr
        val td = (d - a).dot(r) / rr
        val lo = minOf(tc, td)
        val hi = maxOf(tc, td)
        if (hi < -EPS || lo > 1 + EPS) return emptyList()
        return listOf(lo.coerceIn(0.0, 1.0), hi.coerceIn(0.0, 1.0))
    }

    /** True if segments a→b and c→d share at least one point (crossing, touching or overlapping). */
    fun intersects(a: Vec2, b: Vec2, c: Vec2, d: Vec2): Boolean = crossingParams(a, b, c, d).isNotEmpty()

    /**
     * Shortest distance from point [p] to segment a→b, in metres.
     *
     * Concept: project p onto the infinite line through a and b, clamp the projection to the
     * segment (t in [0, 1]), and measure the distance to that closest point.
     */
    fun distanceToSegment(p: Vec2, a: Vec2, b: Vec2): Double {
        val r = b - a
        val rr = r.dot(r)
        if (rr < EPS * EPS) return p.distanceTo(a)
        val t = ((p - a).dot(r) / rr).coerceIn(0.0, 1.0)
        return p.distanceTo(a + r * t)
    }
}
