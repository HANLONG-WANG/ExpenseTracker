package com.yohaku.expensetracker.receipt.scan

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Rigid display transform only. Rendering still samples the original photo once in D7a.
 * Rotation is fixed for the editing session, so dragging never makes the photograph jump.
 */
@Serializable
data class ScanEditFrame(val sourceWidth: Int, val sourceHeight: Int, val radians: Double = 0.0) {
    private val c get() = cos(radians)
    private val s get() = sin(radians)
    private fun rotate(p: ScanPoint) = ScanPoint(c * p.x - s * p.y, s * p.x + c * p.y)
    private val corners get() = listOf(ScanPoint(0.0, 0.0), ScanPoint(sourceWidth - 1.0, 0.0),
        ScanPoint(0.0, sourceHeight - 1.0), ScanPoint(sourceWidth - 1.0, sourceHeight - 1.0)).map(::rotate)
    val origin get() = ScanPoint(corners.minOf { it.x }, corners.minOf { it.y })
    val width get() = ceil(corners.maxOf { it.x } - origin.x).toInt() + 1
    val height get() = ceil(corners.maxOf { it.y } - origin.y).toInt() + 1
    fun toDisplay(p: ScanPoint): ScanPoint = rotate(p).let { ScanPoint(it.x - origin.x, it.y - origin.y) }
    fun toSource(p: ScanPoint): ScanPoint {
        val x = p.x + origin.x; val y = p.y + origin.y
        return ScanPoint(c * x + s * y, -s * x + c * y)
    }
    fun contains(p: ScanPoint): Boolean = toSource(p).let { it.x in -0.001..sourceWidth - .999 && it.y in -0.001..sourceHeight - .999 }
    fun clamp(p: ScanPoint): ScanPoint = toSource(p).let {
        toDisplay(ScanPoint(it.x.coerceIn(0.0, sourceWidth - 1.0), it.y.coerceIn(0.0, sourceHeight - 1.0)))
    }
    fun display(contour: ScanContour) = contour.copy(width = width, height = height,
        left = contour.left.map(::toDisplay), right = contour.right.map(::toDisplay))
    fun source(contour: ScanContour) = contour.copy(width = sourceWidth, height = sourceHeight,
        left = contour.left.map { sourcePoint(it) }, right = contour.right.map { sourcePoint(it) })
    private fun sourcePoint(p: ScanPoint) = toSource(p).let {
        ScanPoint(it.x.coerceIn(0.0, sourceWidth - 1.0), it.y.coerceIn(0.0, sourceHeight - 1.0))
    }
    companion object {
        fun forContour(contour: ScanContour): ScanEditFrame {
            val dx = contour.left.last().x + contour.right.last().x - contour.left.first().x - contour.right.first().x
            val dy = contour.left.last().y + contour.right.last().y - contour.left.first().y - contour.right.first().y
            return ScanEditFrame(contour.width, contour.height, ScanOrientation.deskewRadians(atan2(dx, dy)))
        }
    }
}

/** Constraints apply to the interpolated sides as well as handles, in the upright editing frame. */
object ScanContourEditor {
    // A manually placed end touching any original-image edge is cut off. The rotated canvas's
    // empty corners are NOT image edges. Matches inFrame=false = paper continues beyond photo.
    const val END_EDGE_FRACTION = 0.01
    private const val MIN_GAP = 1.0

    fun endInFrame(frame: ScanEditFrame, a: ScanPoint, b: ScanPoint): Boolean {
        val threshold = min(frame.sourceWidth, frame.sourceHeight) * END_EDGE_FRACTION
        return listOf(a, b).map(frame::toSource).all {
            minOf(it.x, it.y, frame.sourceWidth - 1 - it.x, frame.sourceHeight - 1 - it.y) > threshold
        }
    }
    fun visibility(contour: ScanContour, frame: ScanEditFrame) = contour.copy(
        topInFrame = endInFrame(frame, contour.left.first(), contour.right.first()),
        bottomInFrame = endInFrame(frame, contour.left.last(), contour.right.last()))

    /** Move to the nearest feasible point along the drag, instead of throwing on an invalid edit. */
    fun move(contour: ScanContour, frame: ScanEditFrame, leftSide: Boolean, index: Int, target: ScanPoint): ScanContour {
        require(index in 0..4)
        val points = if (leftSide) contour.left else contour.right
        val start = points[index]; val end = frame.clamp(target)
        fun candidate(fraction: Double): ScanContour? {
            val point = ScanPoint(start.x + (end.x - start.x) * fraction, start.y + (end.y - start.y) * fraction)
            val changed = points.toMutableList().also { it[index] = point }
            return try {
                val next = if (leftSide) contour.copy(left = changed) else contour.copy(right = changed)
                next.takeIf { valid(it, frame) }
            } catch (_: IllegalArgumentException) { null } // Constructor rejects self intersections.
        }
        val full = candidate(1.0)
        val moved = if (full != null) full else {
            var low = 0.0; var high = 1.0; var best = contour
            repeat(12) {
                val mid = (low + high) / 2
                val next = candidate(mid)
                if (next == null) high = mid else { low = mid; best = next }
            }
            best
        }
        return visibility(moved, frame)
    }
    internal fun valid(contour: ScanContour, frame: ScanEditFrame): Boolean {
        val outline = contour.outline(clampToImage = false)
        val left = outline.take(64); val right = outline.drop(64).reversed()
        if (!(contour.left + contour.right).all(frame::contains)) return false
        if (listOf(left, right).any { side -> side.zipWithNext().any { (a, b) -> b.y <= a.y } }) return false
        if (left.indices.any { left[it].x + MIN_GAP > right[it].x }) return false
        // Separating horizontal strips for the two end lines also prevent end/side crossings.
        if (max(contour.left.first().y, contour.right.first().y) + MIN_GAP >= min(contour.left.last().y, contour.right.last().y)) return false
        return true // ScanContour's polygon validation additionally rejects curve/curve crossings.
    }
}
