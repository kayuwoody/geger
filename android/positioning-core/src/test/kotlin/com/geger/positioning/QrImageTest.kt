package com.geger.positioning

import com.geger.positioning.config.TrialConfig
import com.geger.positioning.frames.Angles
import com.geger.positioning.geometry.Vec2
import com.geger.positioning.qr.FrameRefiner
import com.geger.positioning.qr.QrFixSolver
import com.geger.positioning.qr.QrSighting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Image-level tests (milestone M4): frame-edge fitting, QR orientation and the whole pipeline
 * from pixels, on rendered camera images of a printed plate.
 */
class QrImageTest {

    private val config = TrialConfig.defaults()

    /** Simulates the QR reader: true QR corners with reader-like errors (the 4th corner worst). */
    private fun readerCorners(scene: SyntheticPlates.Scene, rng: Random): List<Vec2> {
        val c = SyntheticPlates.jitter(scene.qrCorners(), 0.8, rng).toMutableList()
        c[2] = Vec2(c[2].x + 2.0, c[2].y - 1.5) // the reader guesses the corner without a finder pattern
        return c
    }

    @Test
    fun `fitted frame corners are within a few tenths of a pixel`() {
        val rng = Random(3)
        for ((d, a, roll) in listOf(Triple(1.5, 0.0, 0.0), Triple(2.5, 35.0, 90.0), Triple(4.0, 50.0, 200.0), Triple(6.0, 20.0, 300.0))) {
            val scene = SyntheticPlates.scene(distanceM = d, viewAngleDeg = a, rollDeg = roll, sizeM = 0.27)
            val img = SyntheticPlates.render(scene)
            val refined = requireNotNull(FrameRefiner.refine(img, readerCorners(scene, rng))) { "refine failed" }
            // Refined corners are clockwise in the image; compare with the true corners as a set.
            val truth = scene.frameCorners()
            val worst: Double = refined.corners.maxOf { p -> truth.minOf { t -> hypot(t.x - p.x, t.y - p.y) } }
            assertTrue(worst < 0.35, "worst corner error $worst px at $d m, $a°, roll $roll° (side rms ${refined.sideRmsPx})")
        }
    }

    @Test
    fun `orientation finds the code's top-left whichever way the plate is mounted`() {
        val rng = Random(5)
        for (inPlane in listOf(0.0, 90.0, 180.0, 270.0)) {
            val scene = SyntheticPlates.scene(distanceM = 2.0, viewAngleDeg = 25.0, rollDeg = 30.0, sizeM = 0.18, inPlaneDeg = inPlane)
            val img = SyntheticPlates.render(scene)
            val refined = requireNotNull(FrameRefiner.refine(img, readerCorners(scene, rng))) { "refine failed" }
            val o = requireNotNull(FrameRefiner.orientation(img, refined.corners)) { "orientation unclear at $inPlane°" }
            val br = refined.corners[o.bottomRight]
            val trueBr = scene.frameCorners()[2]
            assertTrue(hypot(br.x - trueBr.x, br.y - trueBr.y) < 1.0, "bottom-right found at the wrong corner for in-plane $inPlane°")
        }
    }

    @Test
    fun `whole pipeline from a rendered image gives the phone's map pose`() {
        val rng = Random(9)
        for ((d, a) in listOf(1.5 to 20.0, 3.0 to 40.0, 5.0 to 30.0)) {
            val scene = SyntheticPlates.scene(distanceM = d, viewAngleDeg = a, rollDeg = 90.0, sizeM = 0.27)
            val img = SyntheticPlates.render(scene)
            val s = QrSighting("WF1:A01", readerCorners(scene, rng), img, SyntheticPlates.K, scene.cameraWorld, scene.cameraWorld.rotation)
            val fix = QrFixSolver.solve(s, scene.sizeM, scene.anchor, 0.0, config.qr, config.phoneMode)
            assertTrue(fix.accepted, "rejected at $d m: ${fix.rejectReason}")
            assertEquals("frame", fix.cornerSource)
            val p = fix.phone!!
            val posErr = hypot(p.x - scene.truePhone.x, p.y - scene.truePhone.y)
            val headErr = abs(Angles.angleDiffDeg(p.headingDeg, scene.trueHeadingDeg))
            assertTrue(posErr < 0.02 * d, "position error $posErr m at $d m")
            assertTrue(headErr < 2.0, "heading error $headErr° at $d m")
            assertEquals(d, fix.distanceM, 0.02 * d)
            assertEquals(1.4, p.heightM, 0.05, "phone height above the floor")
        }
    }

    @Test
    fun `plate mounted sideways is reported but rejected as not upright`() {
        val scene = SyntheticPlates.scene(distanceM = 2.0, viewAngleDeg = 20.0, sizeM = 0.18, inPlaneDeg = 90.0)
        val img = SyntheticPlates.render(scene)
        val s = QrSighting("WF1:A01", scene.qrCorners(), img, SyntheticPlates.K, scene.cameraWorld, scene.cameraWorld.rotation)
        val fix = QrFixSolver.solve(s, scene.sizeM, scene.anchor, 0.0, config.qr, config.phoneMode)
        assertFalse(fix.accepted)
        assertTrue(fix.rejectReason!!.startsWith("plate not upright"), fix.rejectReason)
        assertEquals(2.0, fix.distanceM, 0.04, "distance is still measured")
    }

    @Test
    fun `in-plane rotation follows the plate`() {
        val angles = listOf(0.0, 90.0, 180.0, 270.0).map { inPlane ->
            val scene = SyntheticPlates.scene(distanceM = 1.5, viewAngleDeg = 0.0, sizeM = 0.18, inPlaneDeg = inPlane)
            val s = QrSighting("WF1:A01", scene.qrCorners(), SyntheticPlates.render(scene), SyntheticPlates.K, scene.cameraWorld, scene.cameraWorld.rotation)
            QrFixSolver.solve(s, scene.sizeM, null, 0.0, config.qr, config.phoneMode).inPlaneRotationDeg
        }
        // Mounting the plate a quarter turn further turns its up arrow a quarter turn in the image.
        for (i in 1 until 4) assertEquals(90.0, abs(Angles.angleDiffDeg(angles[i], angles[i - 1])), 2.0, "angles $angles")
    }

    @Test
    fun `a payload that isn't ours has no anchor id`() {
        assertEquals("A01", com.geger.positioning.qr.PlateLayout.anchorId("WF1:A01"))
        assertEquals(null, com.geger.positioning.qr.PlateLayout.anchorId("https://example.com"))
        assertEquals(null, com.geger.positioning.qr.PlateLayout.anchorId("WF1:"))
    }
}
