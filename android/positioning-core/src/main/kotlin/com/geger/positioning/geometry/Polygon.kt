package com.geger.positioning.geometry

import kotlin.math.abs

/**
 * A closed polygon on a floor plan (walkable area or obstacle), in map-frame metres.
 * The last point connects back to the first; do not repeat the first point at the end.
 *
 * Concept: maps describe space as polygons — "you can walk inside these, but not inside those".
 * Any winding order (clockwise or anticlockwise) is accepted.
 */
class Polygon(val points: List<Vec2>) {

    /** The polygon's sides as (start, end) pairs, including the closing side last → first. */
    val edges: List<Pair<Vec2, Vec2>> = points.indices.map { i -> points[i] to points[(i + 1) % points.size] }

    /**
     * True if [p] lies on the polygon's outline (within [Segments.EPS]).
     * Concept: a point on the boundary is ambiguous for ray casting, so it is checked first.
     */
    fun onBoundary(p: Vec2): Boolean = edges.any { (a, b) -> Segments.distanceToSegment(p, a, b) <= Segments.EPS }

    /**
     * True if [p] is inside the polygon OR on its outline.
     * Used for walkable areas: standing exactly on a corridor's edge still counts as walkable.
     */
    fun containsInclusive(p: Vec2): Boolean = onBoundary(p) || rayCastInside(p)

    /**
     * True only if [p] is strictly inside (not on the outline).
     * Used for obstacles: brushing along a pillar's surface is allowed, entering it is not.
     */
    fun containsStrict(p: Vec2): Boolean = !onBoundary(p) && rayCastInside(p)

    /**
     * Even–odd ray casting.
     *
     * Concept: shoot a ray from p towards +x and count how many sides it crosses. An odd count
     * means p is inside (every crossing flips inside/outside). The half-open test on y
     * (`(a.y > p.y) != (b.y > p.y)`) makes a ray that passes exactly through a vertex count once,
     * not twice. Boundary points must be handled by the caller (see [onBoundary]).
     */
    private fun rayCastInside(p: Vec2): Boolean {
        var inside = false
        for ((a, b) in edges) {
            if ((a.y > p.y) != (b.y > p.y)) {
                val xAtY = a.x + (p.y - a.y) * (b.x - a.x) / (b.y - a.y)
                if (p.x < xAtY) inside = !inside
            }
        }
        return inside
    }

    /**
     * Signed area (shoelace formula); positive for anticlockwise points.
     * Concept: sums the cross products of consecutive points. Used to reject flat (zero-area) shapes.
     */
    fun signedArea(): Double {
        var sum = 0.0
        for ((a, b) in edges) sum += a.cross(b)
        return sum / 2.0
    }

    /**
     * Returns a description of why the polygon is invalid, or null if it is a valid simple polygon.
     *
     * Concept: a "simple" polygon has at least 3 points, no repeated consecutive points, a real
     * area, and sides that only meet their two neighbours at shared corners. A self-crossing shape
     * (a bow-tie) has no clear inside, so walkability tests on it would be meaningless.
     */
    fun problem(): String? {
        if (points.size < 3) return "needs at least 3 points, has ${points.size}"
        if (points.any { !it.isFinite() }) return "has a coordinate that is not a finite number"
        for (i in points.indices) {
            if (points[i].distanceTo(points[(i + 1) % points.size]) <= Segments.EPS) {
                return "has a repeated point at index ${(i + 1) % points.size}"
            }
        }
        if (abs(signedArea()) <= Segments.EPS) return "has zero area"
        val n = edges.size
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val adjacent = j == i + 1 || (i == 0 && j == n - 1)
                val (a, b) = edges[i]
                val (c, d) = edges[j]
                val params = Segments.crossingParams(a, b, c, d)
                if (params.isEmpty()) continue
                if (!adjacent) return "sides $i and $j cross or touch"
                // Adjacent sides must only share their common corner, not fold back over each other.
                if (params.size > 1 && abs(params[0] - params[1]) > Segments.EPS) {
                    return "sides $i and $j overlap"
                }
            }
        }
        return null
    }
}
