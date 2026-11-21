package com.geger.positioning.qr

import com.geger.positioning.frames.Quat
import com.geger.positioning.frames.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Small dense linear algebra for the plate pose solver.
 *
 * Concept: the QR fix needs only tiny systems (at most 8 unknowns), so a few hand-written,
 * well-tested routines are simpler and more portable than a matrix library.
 */

/** A 3×3 matrix stored row by row. Rotation matrices map plate axes into camera axes. */
class Mat3(val m: DoubleArray) {
    init { require(m.size == 9) }

    operator fun get(r: Int, c: Int) = m[r * 3 + c]

    /** Matrix product (this · o). */
    operator fun times(o: Mat3): Mat3 = Mat3(DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        this[r, 0] * o[0, c] + this[r, 1] * o[1, c] + this[r, 2] * o[2, c]
    })

    /** Matrix × vector. */
    operator fun times(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    fun transpose() = Mat3(doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]))

    /** Column [c] as a vector (for a rotation matrix: where plate axis c points in the camera frame). */
    fun col(c: Int) = Vec3(m[c], m[3 + c], m[6 + c])

    /**
     * The same rotation as a unit quaternion (x, y, z, w).
     * Concept (Shepperd's method): pick the largest of four candidate expressions to divide by, so
     * the conversion stays accurate for every rotation, including 180° turns.
     */
    fun toQuat(): Quat {
        val tr = m[0] + m[4] + m[8]
        return when {
            tr > 0 -> {
                val s = sqrt(tr + 1.0) * 2
                Quat((m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s, 0.25 * s)
            }
            m[0] > m[4] && m[0] > m[8] -> {
                val s = sqrt(1.0 + m[0] - m[4] - m[8]) * 2
                Quat(0.25 * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s, (m[7] - m[5]) / s)
            }
            m[4] > m[8] -> {
                val s = sqrt(1.0 + m[4] - m[0] - m[8]) * 2
                Quat((m[1] + m[3]) / s, 0.25 * s, (m[5] + m[7]) / s, (m[2] - m[6]) / s)
            }
            else -> {
                val s = sqrt(1.0 + m[8] - m[0] - m[4]) * 2
                Quat((m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25 * s, (m[3] - m[1]) / s)
            }
        }.normalized()
    }

    companion object {
        val IDENTITY = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        /** The matrix whose columns are [a], [b], [c]. */
        fun fromCols(a: Vec3, b: Vec3, c: Vec3) = Mat3(doubleArrayOf(a.x, b.x, c.x, a.y, b.y, c.y, a.z, b.z, c.z))

        /**
         * Rotation by the rotation vector [w] (axis = direction of w, angle = its length, radians).
         * Concept (Rodrigues' formula): R = I + sinθ·K + (1 − cosθ)·K², where K is the cross-product
         * matrix of the unit axis. Used for small corrections while refining a pose.
         */
        fun rodrigues(w: Vec3): Mat3 {
            val theta = w.length()
            if (theta < 1e-12) {
                return Mat3(doubleArrayOf(1.0, -w.z, w.y, w.z, 1.0, -w.x, -w.y, w.x, 1.0))
            }
            val k = w * (1.0 / theta)
            val kx = Mat3(doubleArrayOf(0.0, -k.z, k.y, k.z, 0.0, -k.x, -k.y, k.x, 0.0))
            val kx2 = kx * kx
            val s = sin(theta)
            val c1 = 1 - cos(theta)
            return Mat3(DoubleArray(9) { i -> IDENTITY.m[i] + s * kx.m[i] + c1 * kx2.m[i] })
        }

        /** Rotation matrix of a unit quaternion. */
        fun fromQuat(q: Quat): Mat3 {
            val (x, y, z, w) = q
            return Mat3(doubleArrayOf(
                1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
            ))
        }
    }
}

/**
 * Solves the square system a·x = b by Gaussian elimination with partial pivoting.
 * Returns null if the matrix is (numerically) singular. Does not modify its inputs.
 *
 * Concept: eliminate unknowns row by row, always dividing by the largest available entry in the
 * column (the "pivot") to keep rounding errors small, then back-substitute.
 */
fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
    val n = b.size
    val m = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) a[r][c] else b[r] } }
    for (col in 0 until n) {
        var pivot = col
        for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
        if (abs(m[pivot][col]) < 1e-14) return null
        val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
        for (r in col + 1 until n) {
            val f = m[r][col] / m[col][col]
            if (f == 0.0) continue
            for (c in col..n) m[r][c] -= f * m[col][c]
        }
    }
    val x = DoubleArray(n)
    for (r in n - 1 downTo 0) {
        var s = m[r][n]
        for (c in r + 1 until n) s -= m[r][c] * x[c]
        x[r] = s / m[r][r]
    }
    return x
}

/** The inverse of a small square matrix, or null if singular (columns solved one at a time). */
fun invert(a: Array<DoubleArray>): Array<DoubleArray>? {
    val n = a.size
    val cols = (0 until n).map { j -> solveLinear(a, DoubleArray(n) { if (it == j) 1.0 else 0.0 }) ?: return null }
    return Array(n) { r -> DoubleArray(n) { c -> cols[c][r] } }
}

/**
 * Result of [levenbergMarquardt]: the best parameters, the final sum of squared residuals and the
 * residual Jacobian at the solution (for uncertainty estimates).
 */
class LmResult(val params: DoubleArray, val cost: Double, val jacobian: Array<DoubleArray>, val residuals: DoubleArray)

/**
 * Minimises the sum of squares of [residual](params) with the Levenberg–Marquardt method.
 *
 * Concept: Gauss–Newton linearises the residuals around the current guess and jumps to the best
 * point of that straight-line model; when the model is poor that jump can overshoot. LM blends in
 * a cautious "small steepest-descent step" through the damping factor λ: shrink λ after a good
 * step (trust the model more), grow it after a bad one. Derivatives are taken numerically
 * (central differences), which is plenty for 4–6 parameters.
 */
fun levenbergMarquardt(p0: DoubleArray, residual: (DoubleArray) -> DoubleArray, maxIterations: Int = 100): LmResult {
    var p = p0.copyOf()
    var r = residual(p)
    var cost = r.sumOf { it * it }
    var lambda = 1e-3
    var jac = jacobian(p, r.size, residual)
    for (iter in 0 until maxIterations) {
        val n = p.size
        val jtj = Array(n) { i -> DoubleArray(n) { j -> jac.sumOf { row -> row[i] * row[j] } } }
        val jtr = DoubleArray(n) { i -> jac.indices.sumOf { k -> jac[k][i] * r[k] } }
        var improved = false
        while (lambda < 1e12) {
            val damped = Array(n) { i -> DoubleArray(n) { j -> jtj[i][j] + if (i == j) lambda * maxOf(jtj[i][i], 1e-12) else 0.0 } }
            val step = solveLinear(damped, DoubleArray(n) { -jtr[it] }) ?: break
            val pNew = DoubleArray(n) { p[it] + step[it] }
            val rNew = residual(pNew)
            val costNew = rNew.sumOf { it * it }
            if (costNew.isFinite() && costNew < cost) {
                val done = cost - costNew < 1e-15 * (1 + cost) || step.all { abs(it) < 1e-13 }
                p = pNew; r = rNew; cost = costNew
                lambda = maxOf(lambda / 10, 1e-12)
                improved = true
                jac = jacobian(p, r.size, residual)
                if (done) return LmResult(p, cost, jac, r)
                break
            }
            lambda *= 10
        }
        if (!improved) break
    }
    return LmResult(p, cost, jac, r)
}

/** Numerical Jacobian of [f] at [p] by central differences (rows = residuals, columns = parameters). */
fun jacobian(p: DoubleArray, m: Int, f: (DoubleArray) -> DoubleArray): Array<DoubleArray> {
    val jac = Array(m) { DoubleArray(p.size) }
    for (j in p.indices) {
        val h = 1e-7 * maxOf(1.0, abs(p[j]))
        val plus = p.copyOf().also { it[j] += h }
        val minus = p.copyOf().also { it[j] -= h }
        val fp = f(plus)
        val fm = f(minus)
        for (i in 0 until m) jac[i][j] = (fp[i] - fm[i]) / (2 * h)
    }
    return jac
}
