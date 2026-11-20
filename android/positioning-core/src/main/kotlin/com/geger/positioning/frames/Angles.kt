package com.geger.positioning.frames

import com.geger.positioning.geometry.Vec2
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Angle helpers using the project's heading convention (see SPEC.md, "Coordinate frames"):
 * degrees, 0 = map +y ("up" on the plan), clockwise positive, range [0, 360).
 *
 * Concept: headings wrap around — 359° and 1° are only 2° apart — so every comparison of two
 * headings must go through [angleDiffDeg], never plain subtraction.
 */
object Angles {

    /** Wraps any angle into [0, 360). Example: −90 → 270, 360 → 0, 725 → 5. */
    fun normalizeDeg(deg: Double): Double {
        val r = deg % 360.0
        val n = if (r < 0) r + 360.0 else r
        // `r + 360` can round to exactly 360.0 for tiny negative r; fold that (and −0.0) back to 0.
        return if (n >= 360.0 || n == 0.0) 0.0 else n
    }

    /**
     * Signed smallest difference a − b, in (−180, 180].
     * Example: angleDiffDeg(10, 350) = 20 (turn 20° clockwise from 350 to reach 10).
     */
    fun angleDiffDeg(a: Double, b: Double): Double {
        val d = normalizeDeg(a - b)
        return if (d > 180.0) d - 360.0 else d
    }

    /** Degrees → radians (maths code works in radians; files and screens use degrees). */
    fun toRad(deg: Double) = Math.toRadians(deg)

    /** Radians → degrees. */
    fun toDeg(rad: Double) = Math.toDegrees(rad)

    /**
     * Heading of a map-frame direction vector.
     * Concept: with 0 = +y and clockwise positive, heading = atan2(x, y) (note the swapped
     * arguments compared with the usual maths angle atan2(y, x), which starts at +x and turns
     * anticlockwise).
     */
    fun headingOfMapVector(v: Vec2): Double = normalizeDeg(toDeg(atan2(v.x, v.y)))

    /** Unit map-frame vector pointing along heading [deg]: 0 → (0, 1), 90 → (1, 0). */
    fun unitVectorForHeading(deg: Double): Vec2 {
        val r = toRad(deg)
        return Vec2(sin(r), cos(r))
    }

    /**
     * Rotates a map-frame vector clockwise (as seen on the plan) by [deg].
     * Concept: turning a direction clockwise by α adds α to its heading, which is what aligning
     * ARCore's arbitrary yaw to the map requires.
     */
    fun rotateClockwise(v: Vec2, deg: Double): Vec2 {
        val r = toRad(deg)
        val c = cos(r)
        val s = sin(r)
        return Vec2(v.x * c + v.y * s, -v.x * s + v.y * c)
    }
}
