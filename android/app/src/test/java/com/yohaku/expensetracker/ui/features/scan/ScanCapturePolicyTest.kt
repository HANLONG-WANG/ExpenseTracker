package com.yohaku.expensetracker.ui.features.scan

import com.yohaku.expensetracker.receipt.scan.*
import org.junit.Assert.*
import org.junit.Test

class ScanCapturePolicyTest {
    private val outline = listOf(ScanPoint(.3, .1), ScanPoint(.3, .9), ScanPoint(.7, .9), ScanPoint(.7, .1))
    private val preview = ScanPreview(outline, .4, ScanPoint(360.0, 480.0), .98, 2.0, .02, false, true, true, ScanDiagnostics(emptyMap(), emptyMap(), emptyMap()))

    @Test fun stableFramesFireOnceAfterEightHundredMilliseconds() {
        val gate = AutoCaptureGate()
        assertEquals(0f, gate.update(0, true, true, true).progress)
        assertEquals(.5f, gate.update(400, true, true, true).progress)
        assertTrue(gate.update(800, true, true, true).capture)
        assertFalse(gate.update(1800, true, true, true).capture)
        gate.interrupt()
        assertFalse(gate.update(2600, true, true, true).capture)
    }
    @Test fun unstableImageMotionDisabledAndMissingFramesResetProgress() {
        for (reason in 0..3) {
            val gate = AutoCaptureGate()
            gate.update(0, true, true, true)
            gate.update(600, true, true, true)
            val interrupted = gate.update(if (reason == 3) 2500 else 700, reason != 0, reason != 1, reason != 2)
            assertEquals(0f, interrupted.progress)
            assertFalse(gate.update(2600, true, true, true).capture)
            assertTrue(gate.update(3400, true, true, true).capture)
        }
        val manual = AutoCaptureGate(); manual.captured()
        assertFalse(manual.update(1000, true, true, true).capture)
    }
    @Test fun hintsCoverMissingPositionAreaReliabilityAndStability() {
        assertEquals(ScanHint.NONE, scanHint(preview, 720, 960, true))
        assertEquals(ScanHint.NOT_FOUND, scanHint(preview.copy(outline=emptyList()), 720, 960, true))
        assertEquals(ScanHint.CENTER, scanHint(preview.copy(center=ScanPoint(50.0, 480.0)), 720, 960, true))
        assertEquals(ScanHint.CLOSER, scanHint(preview.copy(areaRatio=.08), 720, 960, true))
        assertEquals(ScanHint.STEADY, scanHint(preview, 720, 960, false))
        for (weak in listOf(preview.copy(modelScore=.2), preview.copy(fitResidualPx=40.0), preview.copy(fitOutlierFraction=.6), preview.copy(relaxedEnds=true))) {
            assertEquals(ScanHint.STEADY, scanHint(weak, 720, 960, true))
        }
    }
    @Test fun smoothingDoesNotHideMotionAndResetsAfterJumpsOrMissingContours() {
        val smoother = ContourSmoother()
        assertFalse(smoother.update(outline).stable)
        val jitter = outline.map { it.copy(x=it.x+.005) }
        val smooth = smoother.update(jitter)
        assertTrue(smooth.stable)
        assertEquals(outline[0].x + .005 * ScanThresholds.SMOOTH_ALPHA, smooth.points[0].x, 1e-9)
        val moving = jitter.map { it.copy(x=it.x+.03) }
        assertFalse(smoother.update(moving).stable)
        val jumped = outline.map { it.copy(x=it.x+.2) }
        assertEquals(jumped, smoother.update(jumped).points)
        assertTrue(smoother.update(emptyList()).points.isEmpty())
        assertFalse(smoother.update(jumped).stable)
    }
}
