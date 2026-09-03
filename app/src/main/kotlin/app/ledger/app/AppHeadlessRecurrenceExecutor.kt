package app.ledger.app

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.ActiveBookSessionRuntime
import app.ledger.core.security.BookSessionState
import app.ledger.core.security.HeadlessBookLease
import app.ledger.core.security.HeadlessLeaseCapability
import app.ledger.core.security.withHeadlessLedgerAccess
import app.ledger.finance.application.AutomationApplicationPort
import app.ledger.finance.application.CatchUpResult
import app.ledger.finance.application.FinanceDataError
import java.time.Instant

internal fun interface HeadlessRecurrenceExecutor {
    suspend fun catchUp(operationId: StableId, through: Instant): DomainResult<CatchUpResult>
}

/** Owns the only Worker-to-database path and proves the recurrence capability before delegation. */
internal class AppHeadlessRecurrenceExecutor(
    private val sessionRuntime: ActiveBookSessionRuntime,
    private val automation: AutomationApplicationPort,
) : HeadlessRecurrenceExecutor {
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    override suspend fun catchUp(operationId: StableId, through: Instant): DomainResult<CatchUpResult> {
        val manager = sessionRuntime.activate(operationId)
        var lease: HeadlessBookLease? = null
        return try {
            if (manager.state.value == BookSessionState.Uninitialized) manager.initialize()
            lease = manager.acquireHeadlessLease(operationId, HeadlessLeaseCapability.RECURRENCE_WRITE)
            withHeadlessLedgerAccess(lease) { automation.catchUp(operationId, through) }
        } catch (_: Exception) {
            DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
        } finally {
            try {
                lease?.release()
            } finally {
                if (manager.state.value !is BookSessionState.Ready) manager.close()
            }
        }
    }
}
