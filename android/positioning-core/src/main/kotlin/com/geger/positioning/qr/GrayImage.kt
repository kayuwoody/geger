package com.geger.positioning.qr

import kotlin.math.floor

/**
 * An 8-bit grayscale image (the brightness "Y" plane of the camera image).
 * [data] holds rows of [rowStride] bytes; pixel (x, y) is data[y·rowStride + x], 0 = black.
 *
 * Concept: the QR fix only needs brightness, and keeping the image as a plain byte array keeps
 * this code free of Android types, so it runs in desktop tests and in the replay tool.
 */
class GrayImage(val width: Int, val height: Int, val data: ByteArray, val rowStride: Int = width) {
    init { require(data.size >= (height - 1) * rowStride + width) { "image buffer too small" } }

    /** Brightness 0–255 of pixel (x, y); no bounds check. */
    fun at(x: Int, y: Int): Int = data[y * rowStride + x].toInt() and 0xFF

    /**
     * Brightness at a non-integer position by bilinear interpolation, or NaN outside the image.
     * Concept: blends the four surrounding pixels by how close the point is to each, which lets
     * edge positions be measured to a fraction of a pixel.
     */
    fun sample(x: Double, y: Double): Double {
        if (x < 0 || y < 0 || x > width - 1 || y > height - 1) return Double.NaN
        val x0 = floor(x).toInt().coerceAtMost(width - 2)
        val y0 = floor(y).toInt().coerceAtMost(height - 2)
        val fx = x - x0
        val fy = y - y0
        val a = at(x0, y0); val b = at(x0 + 1, y0)
        val c = at(x0, y0 + 1); val d = at(x0 + 1, y0 + 1)
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }
}

/**
 * The printed plate's layout, in QR modules (must match tools/plates/make_plates.py).
 *
 * From the outside in: black frame 2 modules, white gap 4, QR code 21, white gap 4, frame 2
 * = 33 modules across. The frame's outer side is the plate size (size_m in map.json).
 */
object PlateLayout {
    const val FRAME = 2
    const val GAP = 4
    const val QR = 21
    const val TOTAL = 2 * FRAME + 2 * GAP + QR

    /** Half-widths, in modules, measured from the plate centre. */
    const val QR_HALF = QR / 2.0          // 10.5: the QR code's outer edge
    const val FRAME_OUTER_HALF = TOTAL / 2.0 // 16.5: the frame's outer edge (the corners we measure)

    const val PAYLOAD_PREFIX = "WF1:"

    /** The anchor id in a plate's QR payload ("WF1:A01" → "A01"), or null if it isn't one of ours. */
    fun anchorId(payload: String?): String? =
        payload?.takeIf { it.startsWith(PAYLOAD_PREFIX) && it.length > PAYLOAD_PREFIX.length }?.substring(PAYLOAD_PREFIX.length)
}
