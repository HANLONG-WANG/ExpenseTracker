package app.ledger.core.geo

import app.ledger.core.common.DomainResult
import app.ledger.finance.application.CapturedLocation
import app.ledger.finance.application.CapturedLocationProvider
import app.ledger.finance.application.ForegroundLocationPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundLocationSaveSessionTest {
    @Test
    fun prefetchUsesOnlyTheRemainingFifteenSecondBudgetAndNeverSupplementsAfterTimeout() = runTest {
        val response = CompletableDeferred<DomainResult<CapturedLocation?>>()
        val port = CountingLocationPort(response)
        var elapsed = 0L
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = ForegroundLocationSaveSession(port, CLOCK, { elapsed }, dispatcher)
        val scope = TestScope(dispatcher)

        session.prefetch(scope)
        scope.runCurrent()
        elapsed = 14_750L
        val result = async { session.locationForSave() }
        advanceTimeBy(251L)
        runCurrent()

        assertEquals(LocationSaveDisposition.TIMED_OUT, result.await().disposition)
        assertNull(result.await().location)
        assertEquals(1, port.calls)
        response.complete(DomainResult.Success(null))
        runCurrent()
        assertEquals(1, port.calls)
    }

    @Test
    fun completedForegroundFixIsFrozenForTheSave() = runTest {
        val expected = CapturedLocation(
            latitudeE7 = 356_810_000,
            longitudeE7 = 1397_670_000,
            accuracyMillimeters = 4_500,
            capturedAt = CLOCK.instant(),
            provider = CapturedLocationProvider.FUSED,
        )
        val port = ForegroundLocationPort { DomainResult.Success(expected) }
        val session = ForegroundLocationSaveSession(
            port,
            CLOCK,
            { 0L },
            StandardTestDispatcher(testScheduler),
        )

        val result = session.locationForSave()

        assertEquals(LocationSaveDisposition.LOCATED, result.disposition)
        assertEquals(expected, result.location)
    }

    @Test
    fun maximumAttemptWaitIsFifteenSeconds() {
        assertEquals(15_000L, ForegroundLocationSaveSession.MAXIMUM_WAIT_MILLIS)
    }

    private class CountingLocationPort(
        private val response: CompletableDeferred<DomainResult<CapturedLocation?>>,
    ) : ForegroundLocationPort {
        var calls = 0

        override suspend fun capture(deadline: Instant): DomainResult<CapturedLocation?> {
            calls++
            return response.await()
        }
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC)
    }
}
