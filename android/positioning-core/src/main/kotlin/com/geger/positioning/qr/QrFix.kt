package com.geger.positioning.qr

import com.geger.positioning.config.PhoneModeConfig
import com.geger.positioning.config.QrConfig
import com.geger.positioning.frames.Alignment
import com.geger.positioning.frames.Angles
import com.geger.positioning.frames.Frames
import com.geger.positioning.frames.Pose
import com.geger.positioning.frames.Quat
import com.geger.positioning.frames.Vec3
import com.geger.positioning.geometry.Vec2
import com.geger.positioning.map.Anchor
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Everything needed to turn one QR sighting into a fix.
 *
 * [detectorCorners] = the QR reader's 4 corner points of the code (any order, image pixels);
 * [image] = the brightness image they came from (null → use the reader's corners only);
 * [cameraWorld] = ARCore's sensor-oriented camera pose for that image (T_world←camera);
 * [displayCameraOrientation] = ARCore's display-oriented camera orientation (for the heading rule).
 */
data class QrSighting(
    val payload: String,
    val detectorCorners: List<Vec2>,
    val image: GrayImage?,
    val intrinsics: CameraIntrinsics,
    val cameraWorld: Pose,
    val displayCameraOrientation: Quat,
)

/** The phone's map pose from a fix, with 1σ uncertainties, and the ARCore→map alignment it implies. */
data class PhoneFix(
    val levelId: String,
    val x: Double,
    val y: Double,
    val heightM: Double,
    val headingDeg: Double,
    val sigmaPosM: Double,
    val sigmaHeadingDeg: Double,
    val alignment: Alignment,
)

/**
 * The full result of one QR sighting, accepted or not (rejections are logged too).
 *
 * Corner data: [frameCorners] in plate order TL, TR, BR, BL (pixels); [cornerSource] = "frame"
 * when the black frame was fitted, "detector" when only the reader's corners were usable.
 * Pose data: [candidates] (1–2, best first), [chosen] = the one matching gravity, [finalPose] =
 * the gravity-constrained pose. Quality: [rmsPx] reprojection error of the chosen candidate,
 * [distanceM], [viewAngleDeg] (0 = head-on), [tiltDeg] (chosen plate "up" vs gravity),
 * [ambiguity] (second candidate's error ÷ first's; ∞ with one), [frameSidePx], [inPlaneRotationDeg]
 * (direction of the plate's up arrow in the image, 0 = image up, clockwise).
 *
 * Mirror ambiguity: a flat square seen from afar fits two vertical poses almost equally well —
 * the viewer slightly left of the plate's normal or slightly right. Both are solved; the better
 * fit is [phone], the other [alternate]. When the image barely prefers one ([mirrorDeltaChi2]
 * below the config threshold) yet they put the phone far apart, the fix is rejected rather than guessed.
 */
data class QrFix(
    val payload: String,
    val anchorId: String?,
    val sizeM: Double,
    val cornerSource: String,
    val frameCorners: List<Vec2>?,
    val candidates: List<PoseCandidate>,
    val chosen: Int,
    val finalPose: PlatePose?,
    val rmsPx: Double,
    val distanceM: Double,
    val viewAngleDeg: Double,
    val tiltDeg: Double,
    val ambiguity: Double,
    val frameSidePx: Double,
    val inPlaneRotationDeg: Double,
    val accepted: Boolean,
    val rejectReason: String?,
    val phone: PhoneFix?,
    /** The mirror-image vertical solution, if one fits too (see [QrFixSolver.solveOrdered]). */
    val alternate: PhoneFix? = null,
    /** RMS pixel error of the alternate vertical solution ÷ the chosen one's (∞ if none). */
    val mirrorRatio: Double = Double.POSITIVE_INFINITY,
    /** Distance between the chosen and alternate phone positions, metres (0 if none). */
    val mirrorSeparationM: Double = 0.0,
    /**
     * How much better the chosen pose fits than its mirror, in units of the expected corner noise:
     * Δχ² = (sum of squared pixel errors of the mirror − of the chosen) ÷ σ². ∞ if there is no mirror.
     */
    val mirrorDeltaChi2: Double = Double.POSITIVE_INFINITY,
)

/**
 * Turns a sighting into a fix (SPEC.md, "Detection pipeline", "Alignment maths", "Quality gating").
 *
 * Concept: find the frame's corners precisely, decide which corner is which (finder patterns),
 * solve the plate's pose relative to the camera, pick the solution that hangs vertically
 * (gravity), and re-solve with the plate exactly vertical. Chaining that pose with ARCore's camera
 * pose places the plate in ARCore's world; the map says where the plate really is, so the
 * difference is the ARCore→map alignment, which in turn places the phone on the map.
 */
object QrFixSolver {

    /**
     * Solves one sighting. [sizeM] = the plate's frame size (from the map, or typed in for a desk
     * test); [anchor] and [levelElevationM] place it on the map (null anchor → no map pose, only
     * the camera-relative measurements, as in the plate test).
     */
    fun solve(
        s: QrSighting,
        sizeM: Double,
        anchor: Anchor?,
        levelElevationM: Double,
        qr: QrConfig,
        phone: PhoneModeConfig,
    ): QrFix {
        val anchorId = PlateLayout.anchorId(s.payload)
        fun reject(reason: String, source: String = "none", corners: List<Vec2>? = null) = QrFix(
            s.payload, anchorId, sizeM, source, corners, emptyList(), -1, null, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, corners?.let { FrameRefiner.meanSidePx(it) } ?: Double.NaN, Double.NaN, false, reason, null,
        )
        if (s.detectorCorners.size != 4) return reject("reader gave ${s.detectorCorners.size} corners")

        // 1. Frame corners: fitted edges if possible, else extrapolated from the reader's corners.
        val qrCw = FrameRefiner.orderClockwise(s.detectorCorners)
        val refined = s.image?.let { FrameRefiner.refine(it, qrCw) }
        val frameCw = refined?.corners ?: FrameRefiner.predictFrameCorners(qrCw) ?: return reject("degenerate corners")
        val source = if (refined != null) "frame" else "detector"

        // 2. Which corner is which: the corner without a finder pattern is the code's bottom-right.
        val br = if (s.image != null) {
            FrameRefiner.orientation(s.image, frameCw)?.bottomRight ?: return reject("QR orientation unclear", source)
        } else {
            qrCw.indexOf(s.detectorCorners[2]).takeIf { it >= 0 } ?: 2 // trust the reader's TL, TR, BR, BL order
        }
        val ordered = listOf(frameCw[(br + 2) % 4], frameCw[(br + 3) % 4], frameCw[br], frameCw[(br + 1) % 4])
        return solveOrdered(s, ordered, source, sizeM, anchor, levelElevationM, qr, phone)
    }

    /**
     * Steps 3–7 of [solve], starting from frame corners already in plate order (TL, TR, BR, BL).
     * Used by [solve], by tests with exactly known corners, and by the replay of logged sightings.
     */
    fun solveOrdered(
        s: QrSighting,
        ordered: List<Vec2>,
        source: String,
        sizeM: Double,
        anchor: Anchor?,
        levelElevationM: Double,
        qr: QrConfig,
        phone: PhoneModeConfig,
    ): QrFix {
        val anchorId = PlateLayout.anchorId(s.payload)
        fun reject(reason: String) = QrFix(
            s.payload, anchorId, sizeM, source, ordered, emptyList(), -1, null, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, FrameRefiner.meanSidePx(ordered), Double.NaN, false, reason, null,
        )
        // 3. Pose candidates, then the one that agrees with gravity.
        val cands = PlanarPoseSolver.candidates(ordered, sizeM, s.intrinsics)
        if (cands.isEmpty()) return reject("pose could not be solved")
        val upAr = s.cameraWorld.rotation.inverse().rotate(Vec3(0.0, 1.0, 0.0))
        val upCv = Vec3(upAr.x, -upAr.y, -upAr.z)
        val tilts = cands.map { PlanarPoseSolver.angleBetweenDeg(it.pose.r.col(1), upCv) }
        val chosen = tilts.indices.minBy { tilts[it] }
        val cand = cands[chosen]
        val other = cands.getOrNull(1 - chosen)
        val ambiguity = if (other == null) Double.POSITIVE_INFINITY else other.rmsPx / maxOf(cand.rmsPx, 1e-6)

        // 4. Measurements that don't need the map.
        val distance = cand.pose.t.length()
        val viewAngle = PlanarPoseSolver.angleBetweenDeg(cand.pose.normal, -cand.pose.t)
        val sidePx = FrameRefiner.meanSidePx(ordered)
        val c0 = cand.pose.project(Vec3.ZERO, s.intrinsics)
        val c1 = cand.pose.project(Vec3(0.0, sizeM / 4, 0.0), s.intrinsics)
        val inPlane = Angles.normalizeDeg(Math.toDegrees(atan2(c1.x - c0.x, -(c1.y - c0.y))))

        // 5. Gravity-constrained poses. Each candidate (and the chosen one's mirror image) is
        //    re-solved with the plate exactly vertical; the best-fitting vertical pose wins.
        val verticals = if (tilts[chosen] <= qr.gravityUpToleranceDeg) {
            val starts = cands.map { it.pose } + PlanarPoseSolver.mirrored(cand.pose)
            val solved = starts.mapNotNull { PlanarPoseSolver.refineWithGravity(it, upCv, ordered, sizeM, s.intrinsics, qr.cornerNoisePx) }
            solved.fold(mutableListOf<GravityPose>()) { acc, g ->
                if (acc.none { PlanarPoseSolver.angleBetweenDeg(it.pose.normal, g.pose.normal) < 0.5 }) acc += g
                acc
            }.sortedBy { it.rmsPx }
        } else emptyList()
        val gravity = verticals.firstOrNull()
        val runnerUp = verticals.getOrNull(1)
        val mirrorRatio = if (gravity != null && runnerUp != null) runnerUp.rmsPx / maxOf(gravity.rmsPx, 1e-6) else Double.POSITIVE_INFINITY
        // Evidence for the chosen pose over its mirror: extra squared error (4 corners) ÷ noise².
        // Concept: if picking the wrong pose would only cost about as much error as noise alone
        // produces, the image can't tell them apart and the pick is a coin toss.
        val deltaChi2 = if (gravity != null && runnerUp != null)
            4 * (runnerUp.rmsPx * runnerUp.rmsPx - gravity.rmsPx * gravity.rmsPx) / (qr.cornerNoisePx * qr.cornerNoisePx)
        else Double.POSITIVE_INFINITY

        // 6. Gates (every reason is reported, the first decides).
        val reasons = buildList {
            if (tilts[chosen] > qr.gravityUpToleranceDeg) add("plate not upright (tilt ${fmt(tilts[chosen])}°)")
            else if (tilts[chosen] > qr.maxTiltDeg) add("tilt ${fmt(tilts[chosen])}° > ${fmt(qr.maxTiltDeg)}°")
            if (cand.rmsPx > qr.maxReprojectionPx) add("reprojection ${fmt(cand.rmsPx)} px > ${fmt(qr.maxReprojectionPx)}")
            if (viewAngle > qr.maxViewAngleDeg) add("view angle ${fmt(viewAngle)}° > ${fmt(qr.maxViewAngleDeg)}°")
            if (sidePx < qr.minFrameSidePx) add("frame ${fmt(sidePx)} px < ${fmt(qr.minFrameSidePx)} px")
            if (gravity == null && tilts[chosen] <= qr.gravityUpToleranceDeg) add("vertical solve failed")
        }

        // 7. Map pose of the phone (only for plates on the map), for the chosen and the mirror pose.
        fun mapFix(g: GravityPose): PhoneFix? = if (anchor == null) null else
            phoneFix(g, g.params, s, anchor, levelElevationM, phone)?.let { base ->
                val (sigPos, sigHead) = propagate(g, s, anchor, levelElevationM, phone, base)
                val floorPos = qr.sigmaPosBaseM + qr.sigmaPosPerMetre * distance
                val floorHead = qr.sigmaHeadingBaseDeg + qr.sigmaHeadingPerMetreDeg * distance
                base.copy(sigmaPosM = hypot(sigPos, floorPos), sigmaHeadingDeg = hypot(sigHead, floorHead))
            }
        val phoneFix = gravity?.let(::mapFix)
        val alternate = runnerUp?.let(::mapFix)
        val separation = if (phoneFix != null && alternate != null) hypot(phoneFix.x - alternate.x, phoneFix.y - alternate.y) else 0.0
        // Camera-relative separation (works without a map too): where the camera sits relative to each pose.
        val camSeparation = if (gravity != null && runnerUp != null) {
            val a = gravity.pose.r.transpose() * gravity.pose.t
            val b = runnerUp.pose.r.transpose() * runnerUp.pose.t
            (a - b).length()
        } else 0.0
        val ambiguous = deltaChi2 < qr.minMirrorChi2 && camSeparation > maxOf(qr.maxMirrorSeparationM, 0.05 * distance)
        val allReasons = if (ambiguous) reasons + "mirror ambiguity (two poses fit, ${fmt(camSeparation)} m apart)" else reasons

        return QrFix(
            s.payload, anchorId, sizeM, source, ordered, cands, chosen, gravity?.pose, cand.rmsPx, distance, viewAngle,
            tilts[chosen], ambiguity, sidePx, inPlane, allReasons.isEmpty(), allReasons.firstOrNull(), phoneFix,
            alternate, mirrorRatio, separation, deltaChi2,
        )
    }

    /**
     * The phone's map pose for gravity-pose parameters [p] (see SPEC.md, "Alignment maths").
     * T_world←plate = T_world←cam · T_arcam←plate; the plate's world position and facing,
     * matched with its map position and facing, give the alignment; the alignment then maps the
     * camera's world position and heading onto the map.
     */
    private fun phoneFix(g: GravityPose, p: DoubleArray, s: QrSighting, anchor: Anchor, elevation: Double, phone: PhoneModeConfig): PhoneFix? {
        val plateInCam = Frames.openCvCamToArCoreCam(g.poseAt(p).toPose())
        val plateInWorld = s.cameraWorld.compose(plateInCam)
        val normalWorld = plateInWorld.rotation.rotate(Vec3(0.0, 0.0, 1.0))
        if (hypot(normalWorld.x, normalWorld.z) < 1e-6) return null
        val plateMap = Vec3(anchor.position.x, anchor.position.y, elevation + anchor.mountHeightM)
        val alignment = Alignment.fromCorrespondence(plateInWorld.translation, Frames.arWorldHeadingDeg(normalWorld), plateMap, anchor.facingDeg)
        val phonePos = alignment.arCoreWorldToMap(s.cameraWorld.translation)
        val heading = Frames.phoneHeadingDeg(s.displayCameraOrientation, alignment, phone.headingFlatThresholdDeg)
        return PhoneFix(anchor.levelId, phonePos.x, phonePos.y, phonePos.z - elevation, heading, 0.0, 0.0, alignment)
    }

    /**
     * 1σ position (m) and heading (°) uncertainty of the phone fix, from the gravity pose's
     * parameter covariance.
     * Concept: nudge each of the 4 parameters slightly, see how the phone's map x, y and heading
     * move (a numerical Jacobian G), and transform the covariance: Σ_out = G·Σ·Gᵀ.
     */
    private fun propagate(g: GravityPose, s: QrSighting, anchor: Anchor, elevation: Double, phone: PhoneModeConfig, base: PhoneFix): Pair<Double, Double> {
        val cov = g.covariance ?: return Pair(Double.NaN, Double.NaN)
        val gmat = Array(3) { DoubleArray(4) }
        for (j in 0 until 4) {
            val h = 1e-6 * maxOf(1.0, kotlin.math.abs(g.params[j]))
            val plus = phoneFix(g, g.params.copyOf().also { it[j] += h }, s, anchor, elevation, phone) ?: return Pair(Double.NaN, Double.NaN)
            val minus = phoneFix(g, g.params.copyOf().also { it[j] -= h }, s, anchor, elevation, phone) ?: return Pair(Double.NaN, Double.NaN)
            gmat[0][j] = (plus.x - minus.x) / (2 * h)
            gmat[1][j] = (plus.y - minus.y) / (2 * h)
            gmat[2][j] = Angles.angleDiffDeg(plus.headingDeg, minus.headingDeg) / (2 * h)
        }
        fun outCov(a: Int, b: Int): Double {
            var sum = 0.0
            for (i in 0 until 4) for (j in 0 until 4) sum += gmat[a][i] * cov[i][j] * gmat[b][j]
            return sum
        }
        return Pair(sqrt(maxOf(0.0, outCov(0, 0) + outCov(1, 1))), sqrt(maxOf(0.0, outCov(2, 2))))
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v)
}
