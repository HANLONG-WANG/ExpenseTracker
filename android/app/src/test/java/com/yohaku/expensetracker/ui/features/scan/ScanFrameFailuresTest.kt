package com.yohaku.expensetracker.ui.features.scan

import org.junit.Assert.*
import org.junit.Test

class ScanFrameFailuresTest {
    @Test fun unexpectedFramesAreDroppedLogsAreThrottledAndNextFrameStillRuns() {
        val logged = mutableListOf<Int>()
        val failures = ScanFrameFailures { count, _ -> logged.add(count) }
        for (time in listOf(100L, 101L, 5099L, 5100L)) {
            assertNull(failures.dropping<Int>(time) { error("Unexpected frame error") })
        }
        assertEquals(listOf(1, 4), logged)
        assertEquals(4, failures.count)
        assertEquals(42, failures.dropping(5200L) { 42 })
        assertEquals(4, failures.count)
        // VM failures are not ordinary analysis exceptions.
        assertThrows(AssertionError::class.java) { failures.dropping(5300L) { throw AssertionError("bug") } }
    }
}
