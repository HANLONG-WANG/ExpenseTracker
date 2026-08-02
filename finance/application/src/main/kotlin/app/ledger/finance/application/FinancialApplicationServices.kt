package app.ledger.finance.application

import app.ledger.core.common.DomainError
import app.ledger.core.common.DomainResult
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.PurgeTransactionCommand
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-local ordering only; SQLite remains the atomicity authority. */
class DefaultLedgerWriteGate : LedgerWriteGate {
    private val mutex = Mutex()

    override suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock { block() }
}

sealed interface FinanceDataError : DomainError {
    data object DatabaseUnavailable : FinanceDataError {
        override val code: String = "DATA_DATABASE_UNAVAILABLE"
    }

    data object CorruptData : FinanceDataError {
        override val code: String = "DATA_CORRUPT_DATA"
    }

    data object StorageFull : FinanceDataError {
        override val code: String = "DATA_STORAGE_FULL"
    }

    data object MaintenanceRequired : FinanceDataError {
        override val code: String = "DATA_MAINTENANCE_REQUIRED"
    }

    data object ProjectionMismatch : FinanceDataError {
        override val code: String = "DATA_PROJECTION_MISMATCH"
    }

    data object NumericRangeExceeded : FinanceDataError {
        override val code: String = "DATA_NUMERIC_RANGE_EXCEEDED"
    }
}

fun interface SubmitFinancialCommandUseCase {
    suspend fun execute(command: FinancialCommand): DomainResult<CommandReceipt>
}

class DefaultSubmitFinancialCommandUseCase(
    private val coordinator: FinancialMutationCoordinator,
) : SubmitFinancialCommandUseCase {
    override suspend fun execute(command: FinancialCommand): DomainResult<CommandReceipt> = coordinator.execute(command)
}

/**
 * The only application command entry exposed to UI, Worker and import adapters.
 * Physical privacy purge remains maintenance-only and is deliberately routed to P31.
 */
class FinancialCommandHandler(
    private val submit: SubmitFinancialCommandUseCase,
) {
    suspend fun handle(command: FinancialCommand): DomainResult<CommandReceipt> = if (command is PurgeTransactionCommand) {
        DomainResult.Failure(FinanceDataError.MaintenanceRequired)
    } else {
        submit.execute(command)
    }
}
