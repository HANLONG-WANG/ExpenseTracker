package app.ledger.core.geo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundLocationRetryRunnerTest {
    @Test
    fun timeoutsRetrySeriallyUntilAResultIsTerminal() = runTest {
        val results = ArrayDeque(
            listOf(
                LocationSaveResult(null, LocationSaveDisposition.TIMED_OUT),
                LocationSaveResult(null, LocationSaveDisposition.TIMED_OUT),
                LocationSaveResult(null, LocationSaveDisposition.UNAVAILABLE),
            ),
        )
        var attempts = 0
        var timeouts = 0
        val runner = ForegroundLocationRetryRunner(
            captureAttempt = {
                attempts++
                results.removeFirst()
            },
            retryDelayMillis = 0L,
        )

        val terminal = runner.captureUntilTerminal(onAttemptTimedOut = { timeouts++ })

        assertEquals(LocationSaveDisposition.UNAVAILABLE, terminal.disposition)
        assertEquals(3, attempts)
        assertEquals(2, timeouts)
    }

    @Test
    fun cancellationStopsAnActiveAttemptAndPreventsFurtherRetries() = runTest {
        val waiting = CompletableDeferred<LocationSaveResult>()
        var attempts = 0
        val runner = ForegroundLocationRetryRunner(
            captureAttempt = {
                attempts++
                waiting.await()
            },
            retryDelayMillis = 0L,
        )
        val job = async { runner.captureUntilTerminal() }
        runCurrent()

        job.cancelAndJoin()
        runCurrent()

        assertEquals(1, attempts)
    }
}
