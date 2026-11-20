package com.geger.positioning.geometry

import kotlin.math.hypot

/**
 * A 2D point or vector on a floor plan, in metres, in the MAP frame
 * (x = right on the plan, y = up on the plan).
 *
 * Concept: almost all positioning happens "on the floor" — which corridor, how far along it —
 * so most geometry works in 2D per level. Height and level changes are handled separately.
 */
data class Vec2(val x: Double, val y: Double) {

    /** Vector addition: moves this point by [o]. */
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)

    /** Vector subtraction: the vector that points from [o] to this point. */
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)

    /** Scales the vector by [k] (e.g. half-way point = a + (b - a) * 0.5). */
    operator fun times(k: Double) = Vec2(x * k, y * k)

    /**
     * Dot product. Concept: measures how much two vectors point the same way;
     * dot(r, r) is the squared length, and dot(p - a, r) / dot(r, r) projects p onto a line.
     */
    fun dot(o: Vec2) = x * o.x + y * o.y

    /**
     * 2D cross product (the z component of the 3D cross product).
     * Concept: its sign says whether [o] is to the left (+) or right (−) of this vector, and it is
     * zero when the two are parallel. Segment-intersection tests are built on this.
     */
    fun cross(o: Vec2) = x * o.y - y * o.x

    /** Length of the vector in metres. */
    fun length() = hypot(x, y)

    /** Straight-line distance between two points in metres. */
    fun distanceTo(o: Vec2) = hypot(x - o.x, y - o.y)

    /** True if both coordinates are real numbers (not NaN or infinite). */
    fun isFinite() = x.isFinite() && y.isFinite()
}
