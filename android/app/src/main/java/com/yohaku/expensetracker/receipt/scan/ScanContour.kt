package com.yohaku.expensetracker.receipt.scan

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Coordinates always refer to the EXIF-upright image, before D7a's principal-axis rotation. */
data class ScanPoint(val x: Double, val y: Double)
data class ScanEndLine(val left: ScanPoint, val right: ScanPoint, val inFrame: Boolean)
data class ScanMask(val width: Int, val height: Int, val pixels: ByteArray) {
    init { require(width > 0 && height > 0 && pixels.size == width * height) }
    internal fun mat(s: CvScope) = s.own(Mat(height, width, CvType.CV_8U)).also { it.put(0, 0, pixels) }
}

/** Five points per side, ordered from top to bottom; the end lines join their endpoints.
 * Use copy(left=..., right=...) to move side handles and withEnd() to move an end line.
 * Invalid (out-of-image or self-intersecting) edits are rejected rather than silently repaired.
 */
data class ScanContour(
    val width: Int, val height: Int,
    val left: List<ScanPoint>, val right: List<ScanPoint>,
    val topInFrame: Boolean, val bottomInFrame: Boolean,
) {
    val top get() = ScanEndLine(left.first(), right.first(), topInFrame)
    val bottom get() = ScanEndLine(left.last(), right.last(), bottomInFrame)
    init {
        require(width > 1 && height > 1 && left.size == 5 && right.size == 5)
        require((left + right).all { it.x.isFinite() && it.y.isFinite() && it.x in 0.0..(width - 1.0) && it.y in 0.0..(height - 1.0) }) { "Contour points must be in the image" }
        val polygon = outline()
        require(abs(polygon.indices.sumOf { i -> val p = polygon[i]; val q = polygon[(i + 1) % polygon.size]; p.x * q.y - p.y * q.x }) > 2) { "Empty contour" }
        for (i in polygon.indices) for (j in i + 2 until polygon.size) {
            if (i == 0 && j == polygon.lastIndex) continue
            require(!intersects(polygon[i], polygon[(i + 1) % polygon.size], polygon[j], polygon[(j + 1) % polygon.size])) { "Contour crosses itself" }
        }
    }
    fun withEnd(isTop: Boolean, line: ScanEndLine): ScanContour {
        val index = if (isTop) 0 else 4
        return copy(left = left.toMutableList().also { it[index] = line.left }, right = right.toMutableList().also { it[index] = line.right },
            topInFrame = if (isTop) line.inFrame else topInFrame, bottomInFrame = if (!isTop) line.inFrame else bottomInFrame)
    }
    /** Smooth interpolating cubic, not-a-knot endpoints: reproduces D7a's cubic sides exactly. */
    fun outline(samplesPerSide: Int = 64): List<ScanPoint> {
        require(samplesPerSide >= 5)
        fun side(points: List<ScanPoint>): List<ScanPoint> {
            val xs = Cubic(points.map { it.x }.toDoubleArray()); val ys = Cubic(points.map { it.y }.toDoubleArray())
            return List(samplesPerSide) { i ->
                val t = 4.0 * i / (samplesPerSide - 1)
                ScanPoint(xs.at(t).coerceIn(0.0, width - 1.0), ys.at(t).coerceIn(0.0, height - 1.0))
            }
        }
        return side(left) + side(right).reversed()
    }
    fun toMask(): ScanMask {
        OpenCvRuntime.ensure()
        CvScope().use { s ->
            val polygon = s.own(MatOfPoint(*outline().map { Point(Numerics.round(it.x).toDouble(), Numerics.round(it.y).toDouble()) }.toTypedArray()))
            val mask = s.own(Mat.zeros(height, width, CvType.CV_8U))
            Imgproc.fillPoly(mask, listOf(polygon), Scalar.all(255.0))
            return ScanMask(width, height, ReceiptGeometry.bytes(mask))
        }
    }
    companion object {
        /** The mask is fitted in its principal-axis space, so sideways receipts also work. */
        fun fromMask(mask: ScanMask): ScanContour {
            OpenCvRuntime.ensure()
            CvScope().use { s -> return fromSource(mask.width, mask.height, ReceiptGeometry.source(s, mask.mat(s))) }
        }
        internal fun fromSource(width: Int, height: Int, source: ReceiptGeometry.Source): ScanContour {
            fun point(index: Double): ScanPoint {
                val i = floor(index).toInt(); val j = min(i + 1, 127); val f = index - i
                return ScanPoint((source.outline[2 * i] * (1 - f) + source.outline[2 * j] * f).coerceIn(0.0, width - 1.0),
                    (source.outline[2 * i + 1] * (1 - f) + source.outline[2 * j + 1] * f).coerceIn(0.0, height - 1.0))
            }
            return ScanContour(width, height, List(5) { point(it * 63.0 / 4) }, List(5) { point(127 - it * 63.0 / 4) }, source.topVisible, source.bottomVisible)
        }
        fun centered(width: Int, height: Int) = ScanContour(width, height,
            List(5) { ScanPoint((width - 1) * .25, (height - 1) * (.1 + .2 * it)) },
            List(5) { ScanPoint((width - 1) * .75, (height - 1) * (.1 + .2 * it)) }, true, true)
        private fun intersects(a: ScanPoint, b: ScanPoint, c: ScanPoint, d: ScanPoint): Boolean {
            fun cross(p: ScanPoint, q: ScanPoint, r: ScanPoint) = (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)
            fun on(p: ScanPoint, q: ScanPoint, r: ScanPoint) = r.x in min(p.x, q.x)..max(p.x, q.x) && r.y in min(p.y, q.y)..max(p.y, q.y)
            val abC = cross(a, b, c); val abD = cross(a, b, d); val cdA = cross(c, d, a); val cdB = cross(c, d, b)
            return (abC * abD < 0 && cdA * cdB < 0) || (abC == 0.0 && on(a, b, c)) || (abD == 0.0 && on(a, b, d)) || (cdA == 0.0 && on(c, d, a)) || (cdB == 0.0 && on(c, d, b))
        }
    }
    private class Cubic(private val values: DoubleArray) {
        private val second: DoubleArray
        init {
            // Five fixed knots, solve the not-a-knot spline's 5x5 system.
            val a = Array(5) { DoubleArray(6) }
            a[0][0] = 1.0; a[0][1] = -2.0; a[0][2] = 1.0
            a[4][2] = 1.0; a[4][3] = -2.0; a[4][4] = 1.0
            for (i in 1..3) { a[i][i - 1] = 1.0; a[i][i] = 4.0; a[i][i + 1] = 1.0; a[i][5] = 6 * (values[i - 1] - 2 * values[i] + values[i + 1]) }
            for (i in 0..4) {
                val pivot = a[i][i]
                for (j in i..5) a[i][j] /= pivot
                for (k in 0..4) if (k != i) { val factor = a[k][i]; for (j in i..5) a[k][j] -= factor * a[i][j] }
            }
            second = DoubleArray(5) { a[it][5] }
        }
        fun at(t: Double): Double {
            val i = min(t.toInt(), 3); val b = t - i; val a = 1 - b
            return a * values[i] + b * values[i + 1] + ((a * a * a - a) * second[i] + (b * b * b - b) * second[i + 1]) / 6
        }
    }
}
