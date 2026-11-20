package com.geger.positioning

import com.geger.positioning.frames.Alignment
import com.geger.positioning.frames.Angles
import com.geger.positioning.frames.Frames
import com.geger.positioning.frames.Pose
import com.geger.positioning.frames.Quat
import com.geger.positioning.frames.Vec3
import com.geger.positioning.geometry.Vec2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.random.Random

/**
 * Tests for angles, rotations, poses and frame conversions (M1 check: "every conversion
 * round-trips").
 *
 * Concept: each conversion is tested two ways — a hand-worked example that pins down the
 * convention (which way is clockwise, which axis is forward), and a randomised round trip
 * (convert there and back, expect the original) that catches algebra slips.
 */
class FramesTest {

    private val rng = Random(42)
    private val tol = 1e-9

    private fun assertVec(expected: Vec3, actual: Vec3, eps: Double = tol) {
        assertEquals(expected.x, actual.x, eps, "x of $actual")
        assertEquals(expected.y, actual.y, eps, "y of $actual")
        assertEquals(expected.z, actual.z, eps, "z of $actual")
    }

    private fun assertAngle(expected: Double, actual: Double, eps: Double = 1e-6) {
        assertEquals(0.0, Angles.angleDiffDeg(expected, actual), eps, "expected $expected°, got $actual°")
    }

    private fun randomVec(scale: Double = 50.0) =
        Vec3(rng.nextDouble(-scale, scale), rng.nextDouble(-scale, scale), rng.nextDouble(-scale, scale))

    private fun randomQuat() =
        Quat.fromAxisAngle(randomVec(1.0) + Vec3(1e-3, 0.0, 0.0), rng.nextDouble(-PI, PI))

    // ---- Angles ---------------------------------------------------------------------------

    @Test
    fun `normalize wraps into 0 to 360`() {
        assertEquals(270.0, Angles.normalizeDeg(-90.0))
        assertEquals(0.0, Angles.normalizeDeg(360.0))
        assertEquals(5.0, Angles.normalizeDeg(725.0), tol)
        assertEquals(0.0, Angles.normalizeDeg(-0.0))
        assertEquals(0.0, Angles.normalizeDeg(-1e-15))
    }

    @Test
    fun `angle difference takes the short way round`() {
        assertEquals(20.0, Angles.angleDiffDeg(10.0, 350.0), tol)
        assertEquals(-20.0, Angles.angleDiffDeg(350.0, 10.0), tol)
        assertEquals(180.0, Angles.angleDiffDeg(0.0, 180.0), tol)
        assertEquals(180.0, Angles.angleDiffDeg(180.0, 0.0), tol)
    }

    @Test
    fun `map headings are clockwise from plus y`() {
        assertAngle(0.0, Angles.headingOfMapVector(Vec2(0.0, 1.0)))
        assertAngle(90.0, Angles.headingOfMapVector(Vec2(1.0, 0.0)))
        assertAngle(180.0, Angles.headingOfMapVector(Vec2(0.0, -1.0)))
        assertAngle(270.0, Angles.headingOfMapVector(Vec2(-1.0, 0.0)))
        repeat(50) {
            val h = rng.nextDouble(0.0, 360.0)
            assertAngle(h, Angles.headingOfMapVector(Angles.unitVectorForHeading(h)))
        }
    }

    @Test
    fun `rotating clockwise adds to the heading`() {
        repeat(50) {
            val h = rng.nextDouble(0.0, 360.0)
            val d = rng.nextDouble(-400.0, 400.0)
            val turned = Angles.rotateClockwise(Angles.unitVectorForHeading(h), d)
            assertAngle(h + d, Angles.headingOfMapVector(turned))
        }
    }

    // ---- Quaternions and poses --------------------------------------------------------------

    @Test
    fun `quaternion rotates by the right-hand rule`() {
        val q = Quat.fromAxisAngle(Vec3(0.0, 0.0, 1.0), PI / 2) // +90° about z
        assertVec(Vec3(0.0, 1.0, 0.0), q.rotate(Vec3(1.0, 0.0, 0.0)))
        val q2 = Quat.fromAxisAngle(Vec3(0.0, 1.0, 0.0), PI / 2) // +90° about y takes −z to −x
        assertVec(Vec3(-1.0, 0.0, 0.0), q2.rotate(Vec3(0.0, 0.0, -1.0)))
    }

    @Test
    fun `pose inverse and compose round-trip`() {
        repeat(100) {
            val a = Pose(randomQuat(), randomVec())
            val b = Pose(randomQuat(), randomVec())
            val p = randomVec()
            assertVec(p, a.inverse().transformPoint(a.transformPoint(p)), 1e-9)
            assertVec(a.transformPoint(b.transformPoint(p)), a.compose(b).transformPoint(p), 1e-9)
            assertVec(p, a.compose(a.inverse()).transformPoint(p), 1e-9)
        }
    }

    // ---- OpenCV camera ↔ ARCore camera ------------------------------------------------------

    @Test
    fun `a point in front of the OpenCV camera is in front of the ARCore camera`() {
        // OpenCV: 2 m forward (+z) and 0.5 m below (+y down). ARCore: forward is −z, down is −y.
        val inCv = Pose(Quat.IDENTITY, Vec3(0.3, 0.5, 2.0))
        val inAr = Frames.openCvCamToArCoreCam(inCv)
        assertVec(Vec3(0.3, -0.5, -2.0), inAr.translation)
        // The plate's own +z axis (facing the camera in OpenCV: towards −z_cv) must face the ARCore camera (+z_ar).
        val plateNormalInCv = Pose(Quat.fromAxisAngle(Vec3(0.0, 1.0, 0.0), PI), Vec3.ZERO).rotation.rotate(Vec3(0.0, 0.0, 1.0))
        assertVec(Vec3(0.0, 0.0, -1.0), plateNormalInCv)
        val plateNormalInAr = Frames.openCvCamToArCoreCam(Pose(Quat.fromAxisAngle(Vec3(0.0, 1.0, 0.0), PI), Vec3.ZERO))
            .rotation.rotate(Vec3(0.0, 0.0, 1.0))
        assertVec(Vec3(0.0, 0.0, 1.0), plateNormalInAr)
    }

    @Test
    fun `OpenCV to ARCore camera conversion round-trips`() {
        repeat(100) {
            val pose = Pose(randomQuat(), randomVec())
            val back = Frames.arCoreCamToOpenCvCam(Frames.openCvCamToArCoreCam(pose))
            val p = randomVec(2.0)
            assertVec(pose.transformPoint(p), back.transformPoint(p), 1e-9)
        }
    }

    // ---- ARCore world ↔ map -----------------------------------------------------------------

    @Test
    fun `at zero yaw ARCore forward is map up and ARCore right is map right`() {
        val a = Alignment.IDENTITY
        assertVec(Vec3(0.0, 1.0, 0.0), a.arCoreWorldToMap(Vec3(0.0, 0.0, -1.0)))
        assertVec(Vec3(1.0, 0.0, 0.0), a.arCoreWorldToMap(Vec3(1.0, 0.0, 0.0)))
        assertVec(Vec3(0.0, 0.0, 1.5), a.arCoreWorldToMap(Vec3(0.0, 1.5, 0.0)), tol)
        assertAngle(0.0, Frames.arWorldHeadingDeg(Vec3(0.0, 0.0, -1.0)))
        assertAngle(90.0, Frames.arWorldHeadingDeg(Vec3(1.0, 0.0, 0.0)))
    }

    @Test
    fun `alignment yaw turns headings and positions consistently`() {
        val a = Alignment(90.0, Vec3(10.0, 20.0, 0.0))
        // ARCore forward (heading 0) becomes map heading 90 (+x): 1 m forward lands 1 m to +x.
        assertVec(Vec3(11.0, 20.0, 0.0), a.arCoreWorldToMap(Vec3(0.0, 0.0, -1.0)))
        assertAngle(90.0, a.worldHeadingToMap(0.0))
    }

    @Test
    fun `ARCore world to map round-trips`() {
        repeat(100) {
            val a = Alignment(rng.nextDouble(0.0, 360.0), randomVec())
            val p = randomVec()
            assertVec(p, a.mapToArCoreWorld(a.arCoreWorldToMap(p)), 1e-9)
            val h = rng.nextDouble(0.0, 360.0)
            assertAngle(h, a.mapHeadingToWorld(a.worldHeadingToMap(h)))
        }
    }

    @Test
    fun `alignment from correspondence reproduces the known pose`() {
        repeat(100) {
            val worldPos = randomVec()
            val worldHeading = rng.nextDouble(0.0, 360.0)
            val mapPos = randomVec()
            val mapHeading = rng.nextDouble(0.0, 360.0)
            val a = Alignment.fromCorrespondence(worldPos, worldHeading, mapPos, mapHeading)
            assertVec(mapPos, a.arCoreWorldToMap(worldPos), 1e-9)
            assertAngle(mapHeading, a.worldHeadingToMap(worldHeading))
            // A point 1 m ahead in ARCore lands 1 m ahead along the map heading.
            val ahead = Vec3(
                worldPos.x + kotlin.math.sin(Angles.toRad(worldHeading)),
                worldPos.y,
                worldPos.z - kotlin.math.cos(Angles.toRad(worldHeading)),
            )
            val expected = Angles.unitVectorForHeading(mapHeading)
            val got = a.arCoreWorldToMap(ahead) - mapPos
            assertEquals(expected.x, got.x, 1e-9)
            assertEquals(expected.y, got.y, 1e-9)
        }
    }

    // ---- Phone heading rule -----------------------------------------------------------------

    @Test
    fun `phone heading follows the camera when held up`() {
        val a = Alignment(30.0, Vec3.ZERO)
        assertAngle(30.0, Frames.phoneHeadingDeg(Quat.IDENTITY, a)) // identity camera looks along world −z
        // Turning left (anticlockwise from above, +90° about world y) reduces the heading by 90.
        val left = Quat.fromAxisAngle(Vec3(0.0, 1.0, 0.0), PI / 2)
        assertAngle(300.0, Frames.phoneHeadingDeg(left, a))
    }

    @Test
    fun `phone heading follows the top edge when held flat`() {
        // Pitch the camera down 90° (rotate −90° about x): it looks at the floor, top edge points along world −z.
        val flat = Quat.fromAxisAngle(Vec3(1.0, 0.0, 0.0), -PI / 2)
        assertVec(Vec3(0.0, -1.0, 0.0), flat.rotate(Vec3(0.0, 0.0, -1.0)))
        assertAngle(0.0, Frames.phoneHeadingDeg(flat, Alignment.IDENTITY))
        // Same flat phone turned to face +x (−90° about world y applied after the pitch).
        val flatEast = Quat.fromAxisAngle(Vec3(0.0, 1.0, 0.0), -PI / 2) * flat
        assertAngle(90.0, Frames.phoneHeadingDeg(flatEast, Alignment.IDENTITY))
    }

    @Test
    fun `slightly tilted camera still uses the camera axis`() {
        // Pitched down 45°: beyond the 30° flat threshold, so the camera axis rules.
        val tilted = Quat.fromAxisAngle(Vec3(0.0, 1.0, 0.0), -PI / 2) * Quat.fromAxisAngle(Vec3(1.0, 0.0, 0.0), -PI / 4)
        assertAngle(90.0, Frames.phoneHeadingDeg(tilted, Alignment.IDENTITY))
    }
}
