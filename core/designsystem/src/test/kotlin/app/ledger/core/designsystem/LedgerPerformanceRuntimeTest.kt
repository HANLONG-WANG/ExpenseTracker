package app.ledger.core.designsystem

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LedgerPerformanceRuntimeTest {
    @AfterEach
    fun cleanUp() {
        LedgerPerformanceRuntime.clear()
    }

    @Test
    fun `aggregates closed scenes without business values`() {
        LedgerPerformanceRuntime.enter(LedgerPerformanceScene.JOURNAL)
        LedgerPerformanceRuntime.record(16_000_000L, false)
        LedgerPerformanceRuntime.record(40_000_000L, true)

        assertEquals(
            LedgerFrameAggregate(frames = 2L, jankyFrames = 1L, maximumDurationNanos = 40_000_000L),
            LedgerPerformanceRuntime.snapshot().getValue(LedgerPerformanceScene.JOURNAL),
        )
    }
}
