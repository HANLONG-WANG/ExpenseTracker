package com.yohaku.expensetracker.ui.features.scan

import org.junit.Assert.*
import org.junit.Test

class ScanFrameFailuresTest {
    @Test fun unexpectedFramesAreDroppedLogsAreThrottledAndNextFrameStillRuns() {
        val logged = mutableListOf<Int>()
        val failures = ScanFrameFailures { count, _ -> logged.add(count) }
        for (time in listOf(100L, 101L)) {
            assertNull(failures.dropping<Int>(time) { error("Unexpected frame error") })
        }
        assertEquals(42, failures.dropping(200L) { 42 })
        repeat(2) { assertNull(failures.dropping<Int>(5100L) { error("failure") }) }
        assertThrows(IllegalStateException::class.java) { failures.dropping<Int>(5101L) { error("persistent") } }
        assertEquals(listOf(1, 3), logged)
        assertEquals(5, failures.count)
        assertEquals(42, failures.dropping(5200L) { 42 })
        assertEquals(5, failures.count)
        // VM failures are not ordinary analysis exceptions.
        assertThrows(AssertionError::class.java) { failures.dropping(5300L) { throw AssertionError("bug") } }
    }
}
