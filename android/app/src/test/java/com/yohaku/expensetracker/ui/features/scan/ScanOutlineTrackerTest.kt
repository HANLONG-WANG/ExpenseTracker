package com.yohaku.expensetracker.ui.features.scan

import com.yohaku.expensetracker.receipt.scan.ScanPoint
import org.junit.Assert.*
import org.junit.Test

class ScanOutlineTrackerTest {
    private fun outline(x: Double) = listOf(ScanPoint(x, .2), ScanPoint(x, .8))
    private val tracker = ScanOutlineTracker()

    @Test fun appearsAtTargetThenFadesInAndOutInPlace() {
        val shape = outline(.4)
        tracker.setTarget(shape)
        assertEquals(ScanOutlineDrawing(shape, 0f), tracker.drawing)
        tracker.step(75.0)
        assertEquals(ScanOutlineDrawing(shape, .5f), tracker.drawing)
        assertFalse(tracker.converged)
        tracker.step(75.0)
        assertTrue(tracker.converged)
        tracker.setTarget(emptyList())
        tracker.step(75.0)
        assertEquals(ScanOutlineDrawing(shape, .5f), tracker.drawing)
        tracker.step(75.0)
        assertEquals(ScanOutlineDrawing(), tracker.drawing)
        assertTrue(tracker.converged)
    }

    @Test fun retargetsFromDisplayedPositionAndConverges() {
        tracker.setTarget(outline(.4))
        tracker.step(150.0)
        tracker.setTarget(outline(.48))
        tracker.step(32.0)
        var previous = tracker.drawing.points.first().x
        assertTrue(previous > .4 && previous < .48)
        val newTarget = outline(.38)
        tracker.setTarget(newTarget)
        assertEquals(previous, tracker.drawing.points.first().x, 0.0)
        repeat(30) {
            tracker.step(16.0)
            val x = tracker.drawing.points.first().x
            assertTrue(x in .38..previous)
            previous = x
        }
        assertTrue(tracker.converged)
        assertEquals(newTarget, tracker.drawing.points)
    }

    @Test fun anyMoveArrivesExactlyWithinTheFixedDuration() {
        for (distance in listOf(.004, .05, .3)) {
            val moving = ScanOutlineTracker()
            moving.setTarget(outline(0.0))
            moving.step(150.0)
            val next = outline(distance)
            moving.setTarget(next)
            moving.step(ScanOutlineTracker.MOVE_MS / 2)
            assertTrue(moving.drawing.points.first().x in distance * .5..distance * .9)
            moving.step(ScanOutlineTracker.MOVE_MS / 2)
            assertSame(next, moving.drawing.points)
            assertTrue(moving.converged)
        }
    }

    @Test fun differentPointCountsFadeThroughEmpty() {
        val old = outline(.4)
        val next = old + ScanPoint(.7, .3)
        tracker.setTarget(old)
        tracker.step(150.0)
        tracker.setTarget(next)
        tracker.step(75.0)
        assertEquals(ScanOutlineDrawing(old, .5f), tracker.drawing)
        tracker.step(150.0)
        assertEquals(ScanOutlineDrawing(next, .5f), tracker.drawing)
        tracker.step(75.0)
        assertTrue(tracker.converged)
    }
}
