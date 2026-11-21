package com.geger.trial.qr

import com.geger.positioning.geometry.Vec2
import com.geger.positioning.log.RecordType
import com.geger.positioning.log.record
import com.geger.positioning.qr.PhoneFix
import com.geger.positioning.qr.PlatePose
import com.geger.positioning.qr.QrFix
import com.geger.positioning.qr.QrSighting
import java.util.Locale

/*
 * Log records for QR sightings and calibration spots (SPEC.md, "Logging": qr_detection, calib_point).
 *
 * Concept: every sighting is logged with everything needed to re-solve it offline — the reader's
 * corners, the fitted frame corners, the camera intrinsics and pose — plus the result, accepted or
 * not. The replay tool can then retune the gates or the solver without going back to the mall.
 */

/** Short JSON number text ("null" if not finite). */
private fun n(v: Double) = if (v.isFinite()) String.format(Locale.ROOT, "%.6g", v) else "null"

/** Flattened [x0, y0, x1, y1, ...] JSON array of points. */
private fun points(ps: List<Vec2>?) = ps?.joinToString(",", "[", "]") { "${n(it.x)},${n(it.y)}" } ?: "null"

/** A plate pose as [tx, ty, tz, qx, qy, qz, qw] (OpenCV camera frame). */
private fun pose(p: PlatePose): String {
    val q = p.r.toQuat()
    return listOf(p.t.x, p.t.y, p.t.z, q.x, q.y, q.z, q.w).joinToString(",", "[", "]") { n(it) }
}

/** A phone fix as a JSON object. */
private fun phone(f: PhoneFix?) = f?.let {
    "{\"level\":\"${it.levelId}\",\"x\":${n(it.x)},\"y\":${n(it.y)},\"height\":${n(it.heightM)},\"heading\":${n(it.headingDeg)}," +
        "\"sigma_pos\":${n(it.sigmaPosM)},\"sigma_heading\":${n(it.sigmaHeadingDeg)}," +
        "\"align\":[${n(it.alignment.yawDeg)},${n(it.alignment.translation.x)},${n(it.alignment.translation.y)},${n(it.alignment.translation.z)}]}"
} ?: "null"

/** The qr_detection log line for one sighting [id] received at [tNs]. */
fun qrDetectionLine(id: Long, tNs: Long, frameStampNs: Long, fix: QrFix, s: QrSighting): String {
    val k = s.intrinsics
    val cw = s.cameraWorld
    val b = record(tNs, RecordType.QR_DETECTION)
        .int("id", id).int("ft", frameStampNs)
        .str("anchor", fix.anchorId).str("payload", fix.payload).num("size_m", fix.sizeM)
        .rawJson("reader_corners", points(s.detectorCorners))
        .rawJson("frame_corners", points(fix.frameCorners)).str("corner_source", fix.cornerSource)
        .nums("intrinsics", k.fx, k.fy, k.cx, k.cy, k.width.toDouble(), k.height.toDouble())
        .nums("cam_p", cw.translation.x, cw.translation.y, cw.translation.z)
        .nums("cam_q", cw.rotation.x, cw.rotation.y, cw.rotation.z, cw.rotation.w)
        .nums("display_q", s.displayCameraOrientation.x, s.displayCameraOrientation.y, s.displayCameraOrientation.z, s.displayCameraOrientation.w)
        .rawJson("candidates", fix.candidates.joinToString(",", "[", "]") { c -> "{\"pose\":${pose(c.pose)},\"rms_px\":${n(c.rmsPx)}}" })
        .int("chosen", fix.chosen)
        .rawJson("final_pose", fix.finalPose?.let(::pose) ?: "null")
        .num("rms_px", fix.rmsPx).num("distance_m", fix.distanceM).num("view_angle_deg", fix.viewAngleDeg)
        .num("tilt_deg", fix.tiltDeg).num("ambiguity", fix.ambiguity).num("mirror_chi2", fix.mirrorDeltaChi2)
        .num("mirror_sep_m", fix.mirrorSeparationM).num("frame_side_px", fix.frameSidePx).num("in_plane_deg", fix.inPlaneRotationDeg)
        .bool("accepted", fix.accepted).str("reason", fix.rejectReason)
        .rawJson("phone", phone(fix.phone)).rawJson("alternate", phone(fix.alternate))
    return b.build()
}

/** The calib_point log line: the marked spot and the fixes collected there. */
fun calibPointLine(tNs: Long, spot: CalibSpot, fixIds: List<Long>, measured: List<Double>, gaveUp: Boolean): String =
    record(tNs, RecordType.CALIB_POINT)
        .str("anchor", spot.anchorId).num("grid_distance_m", spot.distanceM).num("grid_angle_deg", spot.angleDeg)
        .rawJson("fix_ids", fixIds.joinToString(",", "[", "]"))
        .rawJson("measured_distance_m", measured.joinToString(",", "[", "]") { n(it) })
        .bool("gave_up", gaveUp)
        .build()

/** A tape-marked calibration spot: the true distance from the plate and angle from head-on. */
data class CalibSpot(val anchorId: String?, val distanceM: Double, val angleDeg: Double)
