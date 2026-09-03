package app.ledger.core.security

import androidx.tracing.Trace
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Fixed operation classes accepted by P37 traces. No user-provided trace labels are permitted. */
enum class LedgerInteractionOperation(internal val traceLabel: String) {
    UNLOCK("unlock"),
    UNLOCK_TO_CONTENT("unlock_to_content"),
    ROUTE_REQUEST("route_request"),
    ROUTE_CONTENT("route_content"),
    JOURNAL_METADATA("journal_metadata"),
    JOURNAL_PAGE("journal_page"),
    JOURNAL_PRESENTATION("journal_presentation"),
    BLOCKING_PROGRESS_VISIBLE("blocking_progress_visible"),
    SAVE_REQUEST("save_request"),
    SAVE_COMMIT("save_commit"),
    SAVE_SETTLED("save_settled"),
    SEARCH_REQUEST("search_request"),
    SEARCH_CONTENT("search_content"),
    FULL_REFERENCE_SNAPSHOT("full_reference_snapshot"),
}

/** Privacy-safe process counters for key and session lifecycle gates. */
object LedgerSessionPerformance {
    private val databaseKeyUnwrapCount = AtomicLong()
    private val sessionAcquisitionCount = AtomicLong()
    private val nextTraceCookie = AtomicInteger()
    private val blockingProgressTrace = AtomicReference<LedgerInteractionTrace?>()

    fun recordDatabaseKeyUnwrap() {
        databaseKeyUnwrapCount.incrementAndGet()
    }

    fun recordSessionAcquisition() {
        sessionAcquisitionCount.incrementAndGet()
    }

    fun snapshot(): LedgerSessionPerformanceSnapshot = LedgerSessionPerformanceSnapshot(
        databaseKeyUnwrapCount = databaseKeyUnwrapCount.get(),
        sessionAcquisitionCount = sessionAcquisitionCount.get(),
    )

    fun begin(operation: LedgerInteractionOperation): LedgerInteractionTrace {
        val cookie = nextTraceCookie.updateAndGet { current -> if (current == Int.MAX_VALUE) 1 else current + 1 }
        val started = runCatching { Trace.beginAsyncSection("P37/${operation.traceLabel}", cookie) }.isSuccess
        return LedgerInteractionTrace(operation, cookie, started)
    }

    fun beginBlockingProgress() {
        blockingProgressTrace.getAndSet(begin(LedgerInteractionOperation.BLOCKING_PROGRESS_VISIBLE))?.close()
    }

    fun completeBlockingProgress() {
        blockingProgressTrace.getAndSet(null)?.close()
    }

    fun resetForTest() {
        completeBlockingProgress()
        databaseKeyUnwrapCount.set(0L)
        sessionAcquisitionCount.set(0L)
    }
}

data class LedgerSessionPerformanceSnapshot(
    val databaseKeyUnwrapCount: Long,
    val sessionAcquisitionCount: Long,
) {
    operator fun minus(earlier: LedgerSessionPerformanceSnapshot): LedgerSessionPerformanceSnapshot = LedgerSessionPerformanceSnapshot(
        databaseKeyUnwrapCount = databaseKeyUnwrapCount - earlier.databaseKeyUnwrapCount,
        sessionAcquisitionCount = sessionAcquisitionCount - earlier.sessionAcquisitionCount,
    )
}

class LedgerInteractionTrace internal constructor(
    private val operation: LedgerInteractionOperation,
    private val cookie: Int,
    private val started: Boolean,
) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (started && closed.compareAndSet(false, true)) {
            runCatching { Trace.endAsyncSection("P37/${operation.traceLabel}", cookie) }
        }
    }
}
