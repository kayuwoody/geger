package com.geger.positioning.frames

import com.geger.positioning.geometry.Vec2
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Conversions between the frames defined in SPEC.md, "Coordinate frames and conventions".
 *
 * Quick reference:
 *  - Map:            x = right on plan, y = up on plan, z = up (height). Heading 0 = +y, clockwise.
 *  - ARCore world:   gravity-aligned, y = up, x/z horizontal, yaw arbitrary (set when the session starts).
 *  - ARCore camera:  x = right, y = up, z = backward (camera looks along −z).
 *  - OpenCV camera:  x = right, y = down, z = forward (camera looks along +z).
 */
object Frames {

    /** Rotation of 180° about x: turns OpenCV camera axes into ARCore camera axes (and back). */
    private val FLIP_YZ = Quat(1.0, 0.0, 0.0, 0.0)

    /**
     * Converts a pose expressed in the OpenCV camera frame (what `solvePnP` returns, e.g.
     * T_cvcam←plate) into the same pose in the ARCore camera frame (T_arcam←plate).
     *
     * Concept: both cameras sit at the same point and share the x axis; OpenCV's y and z point the
     * opposite way to ARCore's. A 180° turn about x flips y and z, so we apply that turn on the
     * left: T_arcam←obj = Rx(180°) · T_cvcam←obj. Applying it twice gives back the original.
     */
    fun openCvCamToArCoreCam(poseInOpenCvCam: Pose): Pose =
        Pose(FLIP_YZ, Vec3.ZERO).compose(poseInOpenCvCam)

    /** Inverse of [openCvCamToArCoreCam] (the same flip, since it undoes itself). */
    fun arCoreCamToOpenCvCam(poseInArCoreCam: Pose): Pose = openCvCamToArCoreCam(poseInArCoreCam)

    /**
     * Heading, in the ARCore world frame, of a horizontal direction [dir] (its y part is ignored).
     *
     * Concept: we define the ARCore world's own "heading 0" as its −z axis (the direction the
     * camera faced when tracking started) and measure clockwise as seen from above, matching the
     * map convention. With +y up, clockwise-from-above takes −z towards +x, so the heading is
     * atan2(x, −z). An [Alignment] then only has to add a constant yaw to reach map headings.
     */
    fun arWorldHeadingDeg(dir: Vec3): Double = Angles.normalizeDeg(Angles.toDeg(atan2(dir.x, -dir.z)))

    /**
     * The phone's heading on the map, following the "phone heading" rule in SPEC.md.
     *
     * Rule: use the horizontal direction the back camera looks along (camera −z). If the camera
     * points within [flatThresholdDeg] of straight down (phone held flat), use the direction of
     * the phone's top edge (camera +y) instead, because a downward camera has no useful horizontal
     * direction.
     *
     * [displayCameraOrientationWorld] must be the display-oriented camera orientation in the ARCore
     * world (ARCore `camera.displayOrientedPose`), so that +y really is the top edge of the screen in
     * the app's portrait layout. (The sensor-oriented `camera.pose` shares the same −z, so the
     * camera-axis case is identical either way.)
     */
    fun phoneHeadingDeg(
        displayCameraOrientationWorld: Quat,
        alignment: Alignment,
        flatThresholdDeg: Double = 30.0,
    ): Double {
        val view = displayCameraOrientationWorld.rotate(Vec3(0.0, 0.0, -1.0))
        val angleToDownDeg = Angles.toDeg(acos(view.normalized().dot(Vec3(0.0, -1.0, 0.0)).coerceIn(-1.0, 1.0)))
        var dir = if (angleToDownDeg <= flatThresholdDeg) {
            displayCameraOrientationWorld.rotate(Vec3(0.0, 1.0, 0.0))
        } else {
            view
        }
        // Degenerate case (camera pointing straight up): fall back to the top edge.
        if (hypot(dir.x, dir.z) < 1e-6) dir = displayCameraOrientationWorld.rotate(Vec3(0.0, 1.0, 0.0))
        return alignment.worldHeadingToMap(arWorldHeadingDeg(dir))
    }
}

/**
 * The link between the ARCore world frame and the map frame: a yaw turn plus a translation
 * (4 degrees of freedom). Valid because both frames have a vertical axis aligned with gravity, so
 * no tilt is needed.
 *
 * Concept: ARCore tracks the phone in its own private coordinates whose zero heading is wherever
 * the camera pointed at start-up. One QR fix tells us where that private frame sits on the map.
 *  - [yawDeg]: map heading = ARCore-world heading + yawDeg.
 *  - [translation]: where the ARCore world origin lands on the map, as (x, y) on the plan plus a
 *    height offset z (map z = ARCore y + z).
 *
 * The horizontal mapping is: base = (wx, −wz) — ARCore's −z becomes map +y at zero yaw — then turn
 * clockwise by yawDeg, then shift by translation. This is the spec's `arCoreWorldToMap(alignment)`.
 */
data class Alignment(val yawDeg: Double, val translation: Vec3) {

    /** Converts a point in ARCore world coordinates into map coordinates (x, y on plan, z height). */
    fun arCoreWorldToMap(p: Vec3): Vec3 {
        val turned = Angles.rotateClockwise(Vec2(p.x, -p.z), yawDeg)
        return Vec3(turned.x + translation.x, turned.y + translation.y, p.y + translation.z)
    }

    /** The exact inverse of [arCoreWorldToMap]. */
    fun mapToArCoreWorld(m: Vec3): Vec3 {
        val base = Angles.rotateClockwise(Vec2(m.x - translation.x, m.y - translation.y), -yawDeg)
        return Vec3(base.x, m.z - translation.z, -base.y)
    }

    /** ARCore-world heading → map heading. */
    fun worldHeadingToMap(worldHeadingDeg: Double) = Angles.normalizeDeg(worldHeadingDeg + yawDeg)

    /** Map heading → ARCore-world heading. */
    fun mapHeadingToWorld(mapHeadingDeg: Double) = Angles.normalizeDeg(mapHeadingDeg - yawDeg)

    companion object {
        /** The alignment that leaves coordinates unchanged apart from the axis relabelling. */
        val IDENTITY = Alignment(0.0, Vec3.ZERO)

        /**
         * Builds the alignment from one thing whose position and heading are known in both frames
         * (in practice: the phone right after a QR fix).
         *
         * Concept: the yaw is the heading difference; once the yaw is fixed, the translation is
         * whatever moves the turned ARCore position onto the map position.
         */
        fun fromCorrespondence(worldPos: Vec3, worldHeadingDeg: Double, mapPos: Vec3, mapHeadingDeg: Double): Alignment {
            val yaw = Angles.normalizeDeg(mapHeadingDeg - worldHeadingDeg)
            val turned = Angles.rotateClockwise(Vec2(worldPos.x, -worldPos.z), yaw)
            return Alignment(yaw, Vec3(mapPos.x - turned.x, mapPos.y - turned.y, mapPos.z - worldPos.y))
        }
    }
}
