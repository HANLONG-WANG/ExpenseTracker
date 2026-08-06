package app.ledger.app

import android.content.Context
import android.os.SystemClock
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.BookSessionManager
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.HeadlessBookLease
import app.ledger.core.security.HeadlessLeaseCapability
import app.ledger.core.security.SqlCipherBookDatabaseResourceFactory
import app.ledger.core.security.VaultExposureRegistry
import app.ledger.finance.application.AutomationApplicationPort
import app.ledger.finance.application.CatchUpResult
import app.ledger.finance.application.FinanceDataError
import java.time.Instant

internal fun interface HeadlessRecurrenceExecutor {
    suspend fun catchUp(operationId: StableId, through: Instant): DomainResult<CatchUpResult>
}

/** Owns the only Worker-to-database path and proves the recurrence capability before delegation. */
internal class AppHeadlessRecurrenceExecutor(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val automation: AutomationApplicationPort,
) : HeadlessRecurrenceExecutor {
    private val applicationContext = context.applicationContext

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    override suspend fun catchUp(operationId: StableId, through: Instant): DomainResult<CatchUpResult> {
        val manager = BookSessionManager(
            operationId,
            keyProvider,
            SqlCipherBookDatabaseResourceFactory(applicationContext),
            VaultExposureRegistry(SystemClock::elapsedRealtime),
        )
        var lease: HeadlessBookLease? = null
        return try {
            manager.initialize()
            lease = manager.acquireHeadlessLease(operationId, HeadlessLeaseCapability.RECURRENCE_WRITE)
            automation.catchUp(operationId, through)
        } catch (_: Exception) {
            DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
        } finally {
            try {
                lease?.release()
            } finally {
                manager.close()
            }
        }
    }
}
