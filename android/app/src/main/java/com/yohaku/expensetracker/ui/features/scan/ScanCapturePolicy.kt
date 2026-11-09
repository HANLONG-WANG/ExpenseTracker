package com.yohaku.expensetracker.ui.features.scan

import com.yohaku.expensetracker.receipt.scan.ScanPoint
import com.yohaku.expensetracker.receipt.scan.ScanPreview
import kotlin.math.hypot

/** All distances below are fractions of the upright frame (not display pixels). */
internal object ScanThresholds {
    const val HOLD_MS = 800L
    const val MAX_FRAME_GAP_MS = 1500L
    const val STABLE_DISTANCE = .012
    const val JUMP_DISTANCE = .09
    const val SMOOTH_ALPHA = .45
    const val MIN_AREA = .16
    const val MAX_AREA = .78
    const val CENTER_X = .16
    const val CENTER_Y = .19
    const val MIN_SCORE = .80
    const val MAX_RESIDUAL = .018
    const val MAX_OUTLIERS = .25
    const val GYRO_RAD_S = .12
    const val ACCEL_M_S2 = .35
    const val MOTION_SETTLE_MS = 400L
}

internal enum class ScanHint { NONE, CENTER, CLOSER, STEADY, NOT_FOUND }
internal data class SmoothedContour(val points: List<ScanPoint>, val stable: Boolean)

/** Compare unsmoothed frames so smoothing cannot disguise motion from the shutter policy. */
internal class ContourSmoother {
    private var previous = emptyList<ScanPoint>()
    private var displayed = emptyList<ScanPoint>()
    fun reset() { previous = emptyList(); displayed = emptyList() }
    fun update(points: List<ScanPoint>): SmoothedContour {
        if (points.isEmpty()) { reset(); return SmoothedContour(emptyList(), false) }
        val displacement = if (previous.size != points.size) Double.POSITIVE_INFINITY else
            points.indices.maxOf { hypot(points[it].x - previous[it].x, points[it].y - previous[it].y) }
        displayed = if (displacement > ScanThresholds.JUMP_DISTANCE) points.toList() else points.indices.map { i ->
            val a = ScanThresholds.SMOOTH_ALPHA
            ScanPoint(displayed[i].x * (1 - a) + points[i].x * a, displayed[i].y * (1 - a) + points[i].y * a)
        }
        previous = points.toList()
        return SmoothedContour(displayed, displacement < ScanThresholds.STABLE_DISTANCE)
    }
}

internal fun scanHint(preview: ScanPreview, width: Int, height: Int, stable: Boolean): ScanHint {
    if (!preview.detected) return ScanHint.NOT_FOUND
    val center = preview.center ?: return ScanHint.NOT_FOUND
    if (kotlin.math.abs(center.x / width - .5) > ScanThresholds.CENTER_X ||
        kotlin.math.abs(center.y / height - .5) > ScanThresholds.CENTER_Y || preview.areaRatio > ScanThresholds.MAX_AREA) return ScanHint.CENTER
    if (preview.areaRatio < ScanThresholds.MIN_AREA) return ScanHint.CLOSER
    val reliable = (preview.modelScore ?: 0.0) >= ScanThresholds.MIN_SCORE &&
        (preview.fitResidualPx ?: Double.POSITIVE_INFINITY) / minOf(width, height) <= ScanThresholds.MAX_RESIDUAL &&
        (preview.fitOutlierFraction ?: 1.0) <= ScanThresholds.MAX_OUTLIERS && !preview.relaxedEnds
    return if (!stable || !reliable) ScanHint.STEADY else ScanHint.NONE
}

internal data class AutoCaptureDecision(val progress: Float = 0f, val capture: Boolean = false)

/** Monotonic time, consecutive qualifying frames, one shutter per camera session. Pure JVM. */
internal class AutoCaptureGate {
    private var start: Long? = null
    private var last: Long? = null
    private var fired = false
    fun interrupt() { start = null; last = null }
    fun captured() { fired = true; interrupt() }
    fun update(nowMs: Long, enabled: Boolean, eligible: Boolean, phoneStill: Boolean): AutoCaptureDecision {
        if (fired || !enabled || !eligible || !phoneStill) { interrupt(); return AutoCaptureDecision() }
        if (last?.let { nowMs - it > ScanThresholds.MAX_FRAME_GAP_MS || nowMs < it } == true) interrupt()
        last = nowMs
        val began = start ?: nowMs.also { start = it }
        val progress = ((nowMs - began).toFloat() / ScanThresholds.HOLD_MS).coerceIn(0f, 1f)
        if (progress == 1f) captured()
        return AutoCaptureDecision(progress, progress == 1f)
    }
}
