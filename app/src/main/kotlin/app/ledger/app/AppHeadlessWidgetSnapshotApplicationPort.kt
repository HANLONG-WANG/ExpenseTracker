package app.ledger.app

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.ActiveBookSessionRuntime
import app.ledger.core.security.HeadlessLeaseCapability
import app.ledger.core.security.withHeadlessDatabaseAccess
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.WidgetQuickTarget
import app.ledger.finance.application.WidgetSnapshotApplicationPort
import app.ledger.finance.application.WidgetSnapshotBundle
import kotlinx.coroutines.CancellationException

/** Gives Glance a capability-limited path while preserving the process-wide database owner. */
internal class AppHeadlessWidgetSnapshotApplicationPort(
    private val sessionRuntime: ActiveBookSessionRuntime,
    private val delegate: WidgetSnapshotApplicationPort,
) : WidgetSnapshotApplicationPort {
    override suspend fun read(bookId: StableId): DomainResult<WidgetSnapshotBundle> = withWidgetRead(bookId) { delegate.read(bookId) }

    override suspend fun quickTargets(bookId: StableId): DomainResult<List<WidgetQuickTarget>> = withWidgetRead(bookId) { delegate.quickTargets(bookId) }

    private suspend fun <T> withWidgetRead(
        bookId: StableId,
        block: suspend () -> DomainResult<T>,
    ): DomainResult<T> {
        if (sessionRuntime.readyGeneration(bookId) != null) return block()
        return try {
            sessionRuntime.withHeadlessDatabaseAccess(
                bookId,
                operationId = bookId,
                capability = HeadlessLeaseCapability.WIDGET_SNAPSHOT_READ,
            ) { block() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
        }
    }
}
