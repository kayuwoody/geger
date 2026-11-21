package com.geger.positioning

import com.geger.positioning.config.TrialConfig
import com.geger.positioning.frames.Angles
import com.geger.positioning.geometry.Vec2
import com.geger.positioning.qr.QrFix
import com.geger.positioning.qr.QrFixSolver
import com.geger.positioning.qr.QrSighting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Tests for the QR fix maths (milestone M4) on synthetic scenes with a known true answer.
 */
class QrFixTest {

    private val config = TrialConfig.defaults()

    /** Solves a scene from given frame corners (plate order), bypassing image processing. */
    private fun solveFromCorners(scene: SyntheticPlates.Scene, corners: List<Vec2>): QrFix {
        val s = QrSighting("WF1:A01", scene.qrCorners(), null, SyntheticPlates.K, scene.cameraWorld, scene.cameraWorld.rotation)
        return QrFixSolver.solveOrdered(s, corners, "test", scene.sizeM, scene.anchor, 0.0, config.qr, config.phoneMode)
    }

    /** Position error (m) and heading error (°) of a fix against the scene's truth. */
    private fun errors(scene: SyntheticPlates.Scene, fix: QrFix): Pair<Double, Double> {
        val p = fix.phone ?: return Pair(Double.NaN, Double.NaN)
        val t = scene.truePhone
        return Pair(hypot(p.x - t.x, p.y - t.y), abs(Angles.angleDiffDeg(p.headingDeg, scene.trueHeadingDeg)))
    }

    /** A random scene in the M4 check's range: 1–10 m, view angle up to 60° either side, any roll. */
    private fun randomScene(rng: Random, sizeM: Double): SyntheticPlates.Scene = SyntheticPlates.scene(
        distanceM = rng.nextDouble(1.0, 10.0),
        viewAngleDeg = rng.nextDouble(-60.0, 60.0),
        rollDeg = rng.nextDouble(0.0, 360.0),
        plateHeadingDeg = rng.nextDouble(0.0, 360.0),
        cameraHeightM = rng.nextDouble(1.1, 1.7),
        aimJitterDeg = Vec2(rng.nextDouble(-15.0, 15.0), rng.nextDouble(-8.0, 8.0)),
        sizeM = sizeM,
    )

    @Test
    fun `exact corners give the exact phone pose`() {
        val rng = Random(7)
        repeat(50) {
            val scene = randomScene(rng, 0.18)
            val fix = solveFromCorners(scene, scene.frameCorners())
            val (pos, head) = errors(scene, fix)
            assertTrue(pos < 1e-6 && head < 1e-6, "pos $pos m, heading $head° at ${scene.distanceM} m / ${scene.viewAngleDeg}°")
            assertEquals(scene.distanceM, fix.distanceM, 1e-6)
        }
    }

    /**
     * M4 synthetic check: 50 random poses per plate size (1–10 m, up to 60° to the side, any
     * phone roll), frame corners with 0.5 px noise.
     *
     * What physics allows (see DECISIONS.md): distance from the plate's apparent size is always
     * good; heading and sideways position are weak for distant, near head-on views, where the two
     * mirror-image poses look alike. So the checks are:
     *  1. every accepted fix measures distance within 2%;
     *  2. no accepted fix is badly wrong (> 25% of the distance or > 15°) — the mirror gate's job;
     *  3. every accepted fix is within 3σ of its own reported uncertainty (honest σ for the filter);
     *  4. accepted fixes seen at least 15° from head-on are within 2% / 2° at least 80% of the time;
     *  5. at least 70% of views where the frame spans ≥ 60 px are accepted (the gates aren't too strict).
     */
    @Test
    fun `M4 synthetic accuracy with half-pixel corner noise`() {
        for (size in listOf(0.18, 0.27)) {
            val rng = Random(11)
            var accepted = 0; var oblique = 0; var obliqueGood = 0; var bigFrames = 0; var bigFramesAccepted = 0
            repeat(50) {
                val scene = randomScene(rng, size)
                val fix = solveFromCorners(scene, SyntheticPlates.jitter(scene.frameCorners(), 0.5, rng))
                if (fix.frameSidePx >= 60) { bigFrames++; if (fix.accepted) bigFramesAccepted++ }
                if (!fix.accepted) return@repeat
                accepted++
                val (pos, head) = errors(scene, fix)
                val d = scene.distanceM
                val where = "size $size m at ${"%.2f".format(d)} m, ${"%.1f".format(scene.viewAngleDeg)}°"
                assertTrue(abs(fix.distanceM - d) <= 0.02 * d, "1. distance error ${fix.distanceM - d} m, $where")
                assertTrue(pos <= 0.25 * d && head <= 15.0, "2. bad fix accepted: $pos m, $head°, $where")
                val p = fix.phone!!
                assertTrue(pos <= 3 * p.sigmaPosM && head <= 3 * p.sigmaHeadingDeg,
                    "3. error beyond 3σ: $pos m vs σ ${p.sigmaPosM}, $head° vs σ ${p.sigmaHeadingDeg}, $where")
                if (abs(scene.viewAngleDeg) >= 15) {
                    oblique++
                    if (pos <= 0.02 * d && head <= 2.0) obliqueGood++
                }
            }
            println("size $size m: $accepted/50 accepted; seen ≥15° off head-on: $obliqueGood/$oblique within 2%/2°; frames ≥60 px accepted: $bigFramesAccepted/$bigFrames")
            assertTrue(obliqueGood >= 0.8 * oblique, "4. only $obliqueGood/$oblique oblique fixes within 2%/2° (size $size)")
            assertTrue(bigFramesAccepted >= 0.7 * bigFrames, "5. only $bigFramesAccepted/$bigFrames large-frame views accepted (size $size)")
        }
    }
}
