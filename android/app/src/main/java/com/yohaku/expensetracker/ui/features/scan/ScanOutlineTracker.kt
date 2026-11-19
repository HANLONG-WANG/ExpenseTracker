package com.yohaku.expensetracker.ui.features.scan

import com.yohaku.expensetracker.receipt.scan.ScanPoint

internal data class ScanOutlineDrawing(val points: List<ScanPoint> = emptyList(), val alpha: Float = 0f)

/** Display-space tracking only. The shutter uses the original analysis frames.
 * Each new target is reached in a fixed MOVE_MS with ease-out, so the line never creeps asymptotically.
 */
internal class ScanOutlineTracker {
    var drawing = ScanOutlineDrawing()
        private set
    private var target = emptyList<ScanPoint>()
    private var from = emptyList<ScanPoint>()
    private var movedMs = MOVE_MS
    val converged get() = drawing.points === target && drawing.alpha == (if (target.isEmpty()) 0f else 1f)

    fun setTarget(points: List<ScanPoint>) {
        target = points
        if (drawing.points.isEmpty() || drawing.alpha == 0f) {
            drawing = ScanOutlineDrawing(target, 0f)
        } else if (drawing.points.size == target.size) {
            from = drawing.points
            movedMs = 0.0
        }
    }

    fun step(dtMs: Double) {
        var remainingMs = dtMs
        var (points, alpha) = drawing
        if (points.size != target.size) {
            // Hold the old shape until invisible, even if a different point count is waiting.
            val fadeOutMs = alpha * FADE_MS
            if (remainingMs < fadeOutMs) {
                drawing = drawing.copy(alpha = (alpha - remainingMs / FADE_MS).toFloat())
                return
            }
            remainingMs -= fadeOutMs
            points = target
            alpha = 0f
        } else if (points !== target) {
            movedMs = (movedMs + remainingMs).coerceAtMost(MOVE_MS)
            val progress = movedMs / MOVE_MS
            val eased = 1 - (1 - progress) * (1 - progress)
            points = if (progress >= 1) target else from.mapIndexed { i, p ->
                ScanPoint(p.x + (target[i].x - p.x) * eased, p.y + (target[i].y - p.y) * eased)
            }
        }
        if (target.isNotEmpty()) alpha = (alpha + remainingMs / FADE_MS).coerceAtMost(1.0).toFloat()
        drawing = ScanOutlineDrawing(points, alpha)
    }

    companion object {
        const val MOVE_MS = 110.0
        private const val FADE_MS = 150.0
    }
}
