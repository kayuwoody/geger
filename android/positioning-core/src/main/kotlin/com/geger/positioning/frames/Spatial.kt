package com.geger.positioning.frames

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 3D point or vector, in metres. Which frame it belongs to (ARCore world, camera, plate, map)
 * is stated by whoever holds it — see SPEC.md, "Coordinate frames and conventions".
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    /** Dot product: |a||b|cos(angle between them). */
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z

    /** Cross product: a vector at right angles to both, following the right-hand rule. */
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    /** Length in metres. */
    fun length() = sqrt(dot(this))

    /** Same direction, length 1. */
    fun normalized(): Vec3 {
        val l = length()
        return Vec3(x / l, y / l, z / l)
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

/**
 * A rotation stored as a unit quaternion (x, y, z, w) — the same layout ARCore uses.
 *
 * Concept: a quaternion encodes "rotate by angle θ about unit axis n" as
 * (n·sin(θ/2), cos(θ/2)). It avoids the gimbal-lock problems of Euler angles and composes by
 * multiplication: (a * b) means "apply b first, then a".
 */
data class Quat(val x: Double, val y: Double, val z: Double, val w: Double) {

    /**
     * Hamilton product: the rotation "apply [o] first, then this".
     * Concept: this is how chained rotations combine, e.g. camera-in-world × plate-in-camera.
     */
    operator fun times(o: Quat) = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    /** The opposite rotation (for a unit quaternion, the conjugate). */
    fun inverse() = Quat(-x, -y, -z, w)

    /**
     * Rotates vector [v].
     * Concept: the textbook form is q·v·q⁻¹; this is the equivalent cheaper formula
     * v' = v + w·t + u × t, with u = (x, y, z) and t = 2·(u × v).
     */
    fun rotate(v: Vec3): Vec3 {
        val u = Vec3(x, y, z)
        val t = u.cross(v) * 2.0
        return v + t * w + u.cross(t)
    }

    /** Rescales to length 1, removing tiny rounding drift after many multiplications. */
    fun normalized(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return Quat(x / l, y / l, z / l, w / l)
    }

    companion object {
        /** No rotation. */
        val IDENTITY = Quat(0.0, 0.0, 0.0, 1.0)

        /** Rotation by [angleRad] (right-hand rule) about [axis] (need not be unit length). */
        fun fromAxisAngle(axis: Vec3, angleRad: Double): Quat {
            val n = axis.normalized()
            val s = sin(angleRad / 2)
            return Quat(n.x * s, n.y * s, n.z * s, cos(angleRad / 2))
        }
    }
}

/**
 * A rigid transform T_parent←child: [rotation] then [translation].
 *
 * Concept: a pose says where a child frame (e.g. the camera) sits inside a parent frame (e.g. the
 * ARCore world). It maps a point given in child coordinates to parent coordinates:
 * p_parent = R·p_child + t. Chaining poses is how we get from "plate seen by camera" to "phone on
 * the map" (SPEC.md, "Alignment maths").
 */
data class Pose(val rotation: Quat, val translation: Vec3) {

    /** Maps a point from child coordinates into parent coordinates. */
    fun transformPoint(p: Vec3): Vec3 = rotation.rotate(p) + translation

    /**
     * Chains two transforms: (T_a←b).compose(T_b←c) = T_a←c.
     * Concept: first move a point from c into b with [o], then from b into a with this.
     */
    fun compose(o: Pose): Pose = Pose((rotation * o.rotation).normalized(), rotation.rotate(o.translation) + translation)

    /** The reverse transform: (T_a←b).inverse() = T_b←a. */
    fun inverse(): Pose {
        val inv = rotation.inverse()
        return Pose(inv, -inv.rotate(translation))
    }

    companion object {
        val IDENTITY = Pose(Quat.IDENTITY, Vec3.ZERO)
    }
}
