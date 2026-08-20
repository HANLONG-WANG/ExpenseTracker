package app.ledger.finance.application

import app.ledger.core.common.DomainError
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.BookCommitId

data class MaterializedRestorePackage(
    val bookId: StableId,
    val operationId: StableId,
    val databasePath: String,
    val settingsPath: String?,
    val attachmentDirectoryPath: String?,
    val portableKeyMaterialPath: String,
    val vaultEnvelopePath: String?,
    val sourceDatabaseSchemaVersion: Int?,
    val logicalBytes: Long,
)

data class RestoreIntegrityReport(
    val schemaVersionSupported: Boolean,
    val migrationsApplied: Boolean,
    val sqlCipherReadable: Boolean,
    val aeadAndHashesValid: Boolean,
    val foreignKeysValid: Boolean,
    val journalsBalanced: Boolean,
    val projectionsValid: Boolean,
    val transactionSubtypesValid: Boolean,
    val attachmentsValid: Boolean,
    val bookIdentityValid: Boolean,
    val baseCurrencyValid: Boolean,
    /** Fixed, non-sensitive projection-family identifiers for explainable repair diagnostics. */
    val projectionFailureCodes: Set<String> = emptySet(),
) {
    val isValid: Boolean = schemaVersionSupported && migrationsApplied && sqlCipherReadable && aeadAndHashesValid &&
        foreignKeysValid && journalsBalanced && projectionsValid && transactionSubtypesValid && attachmentsValid &&
        bookIdentityValid && baseCurrencyValid
}

data class PreparedRestoreLedger(
    val operationId: StableId,
    val sourceHead: BookCommitId,
    /** Null only when the recovery gate established that the old live database is unreadable. */
    val expectedLiveHead: BookCommitId?,
    val integrity: RestoreIntegrityReport,
)

data class RestoreLedgerExchangeResult(
    val resultingHead: BookCommitId,
    val retainedSafetySnapshotId: StableId,
)

interface RestoreLedgerApplicationPort {
    suspend fun prepareReplacement(value: MaterializedRestorePackage): DomainResult<PreparedRestoreLedger>
    suspend fun exchange(prepared: PreparedRestoreLedger, safetySnapshotId: StableId): DomainResult<RestoreLedgerExchangeResult>
    suspend fun validateLive(bookId: StableId, expectedHead: BookCommitId): DomainResult<RestoreIntegrityReport>
    suspend fun finalizeExchange(bookId: StableId, operationId: StableId): DomainResult<Unit>
    suspend fun rollback(bookId: StableId, operationId: StableId, safetySnapshotId: StableId): DomainResult<Unit>
    suspend fun recoverInterrupted(bookId: StableId, operationId: StableId): DomainResult<Boolean>

    /** Removes a finalized restore's pre-restore database, key and artifact copies after explicit user confirmation. */
    fun confirmSafetySnapshotCleanup(operationId: StableId): DomainResult<Unit>

    /** Discards transient preparation state but must retain finalized pre-restore safety artifacts. */
    fun cleanup(operationId: StableId)
}

interface RestoreArtifactSwapPort {
    fun stage(value: MaterializedRestorePackage)
    fun exchange(operationId: StableId)
    fun rollback(operationId: StableId)
    fun recover(operationId: StableId): Boolean
    fun cleanup(operationId: StableId)
}

sealed interface FinanceRestoreError : DomainError {
    data object UnsupportedVersion : FinanceRestoreError {
        override val code = "FINANCE_RESTORE_UNSUPPORTED_VERSION"
    }
    data object BookMismatch : FinanceRestoreError {
        override val code = "FINANCE_RESTORE_BOOK_MISMATCH"
    }
    data object BaseCurrencyMismatch : FinanceRestoreError {
        override val code = "FINANCE_RESTORE_BASE_CURRENCY_MISMATCH"
    }
    data object MigrationFailed : FinanceRestoreError {
        override val code = "FINANCE_RESTORE_MIGRATION_FAILED"
    }
    data object IntegrityFailed : FinanceRestoreError {
        override val code = "FINANCE_RESTORE_INTEGRITY_FAILED"
    }
    data class ProjectionFailed(val familyCodes: Set<String>) : FinanceRestoreError {
        override val code = "FINANCE_RESTORE_PROJECTION_FAILED"
    }
    data object LiveHeadChanged : FinanceRestoreError {
        override val code = "FINANCE_RESTORE_LIVE_HEAD_CHANGED"
    }
    data object AtomicExchangeUnavailable : FinanceRestoreError {
        override val code = "FINANCE_RESTORE_ATOMIC_EXCHANGE_UNAVAILABLE"
    }
    data object RolledBack : FinanceRestoreError {
        override val code = "FINANCE_RESTORE_ROLLED_BACK"
    }
    data object RecoveryRequired : FinanceRestoreError {
        override val code = "FINANCE_RESTORE_RECOVERY_REQUIRED"
    }
}
