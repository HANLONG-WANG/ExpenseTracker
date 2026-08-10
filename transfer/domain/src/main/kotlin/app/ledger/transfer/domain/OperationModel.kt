@file:Suppress("ComplexCondition", "LongParameterList", "ReturnCount")

package app.ledger.transfer.domain

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.AttachmentId
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.LifecycleRecord
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.RecordLifecycle
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.UserAccountId
import java.time.Instant

@JvmInline value class BackgroundOperationId(val value: StableId)

@JvmInline value class ImportRecordId(val value: StableId)

@JvmInline value class ImportBatchId(val value: StableId)

@JvmInline value class BackupRepositoryId(val value: StableId)

@JvmInline value class BackupSnapshotId(val value: StableId)

@JvmInline value class BackupObjectId(val value: StableId)

@JvmInline value class RestoreRecordId(val value: StableId)

@JvmInline value class MergeSessionId(val value: StableId)

@JvmInline value class MergeConflictId(val value: StableId)

enum class BackgroundOperationType {
    IMPORT,
    EXPORT,
    FULL_BACKUP,
    DRIVE_UPLOAD,
    RESTORE_REPLACE,
    RESTORE_MERGE,
    ATTACHMENT_MIGRATION,
    DATABASE_MAINTENANCE,
    BACKUP_KEY_ROTATION,
}

enum class BackgroundOperationState {
    QUEUED,
    PREPARING,
    RUNNING,
    PAUSED,
    CANCEL_REQUESTED,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    COMMITTING,
    ROLLING_BACK,
    SUCCEEDED,
}

data class OperationProgress(
    val current: Long,
    val total: Long?,
) {
    init {
        require(current >= 0L)
        require(total == null || total >= current)
    }
}

sealed interface OperationParameters {
    data class Import(
        val sourceHandleId: StableId,
        val format: ImportFormat,
        val defaultAccountId: UserAccountId?,
        val userCharset: String? = null,
        val headerRowNumber: Long = 1L,
        val commit: ImportCommitParameters? = null,
    ) : OperationParameters {
        init {
            require(userCharset == null || userCharset.isNotBlank())
            require(headerRowNumber > 0L)
        }
    }

    data class Export(
        val destinationHandleId: StableId,
        val descriptor: ExportDescriptor,
    ) : OperationParameters

    data class FullBackup(
        val repositoryId: BackupRepositoryId,
        val portable: Boolean,
        val destinationHandleId: StableId? = null,
    ) : OperationParameters

    data class DriveUpload(
        val snapshotId: BackupSnapshotId,
        val repositoryId: BackupRepositoryId,
    ) : OperationParameters

    data class BackupRecoveryReencryption(
        val repositoryId: BackupRepositoryId,
    ) : OperationParameters

    data class Restore(
        val sourceHandleId: StableId,
        val mode: RestoreMode,
    ) : OperationParameters

    data class AttachmentMigration(val attachmentIds: List<AttachmentId>) : OperationParameters

    data class DatabaseMaintenance(val kind: MaintenanceKind) : OperationParameters
}

data class ImportCommitParameters(
    val importRecordId: StableId,
    val batchId: StableId,
    val baseCurrency: String,
    val zoneId: String,
    val totalPreparedRows: Long,
    val transactionRows: Long,
    val sourceFingerprint: Hash256,
    val firstSourceRowNumber: Long,
    val useStructuredUndo: Boolean,
) {
    init {
        require(importRecordId != batchId)
        require(baseCurrency.matches(Regex("[A-Z]{3}")))
        require(zoneId.isNotBlank())
        require(totalPreparedRows > 0L)
        require(transactionRows in 0L..totalPreparedRows)
        require(firstSourceRowNumber > 0L)
    }
}

enum class ImportFormat {
    CSV,
    XLSX,
    STRUCTURED_WORKBOOK,
    FULL_BACKUP,
}

enum class ExportFormat {
    CSV,
    XLSX,
    PDF,
    IMAGE,
    PORTABLE_BACKUP,
}

enum class RestoreMode {
    REPLACE,
    MERGE,
}

enum class MaintenanceKind {
    INTEGRITY_AUDIT,
    PROJECTION_REBUILD,
    ATTACHMENT_GC,
    PRIVACY_PURGE,
}

@ConsistentCopyVisibility
data class BackgroundOperation private constructor(
    val id: BackgroundOperationId,
    val type: BackgroundOperationType,
    val state: BackgroundOperationState,
    val createdAt: Instant,
    val startedAt: Instant?,
    val updatedAt: Instant,
    val progress: OperationProgress,
    val checkpointVersion: Long,
    val errorCode: String?,
    val cancelRequested: Boolean,
    val parameters: OperationParameters,
) : LifecycleRecord<RecordLifecycle.Operation> {
    override val lifecycle: RecordLifecycle.Operation = RecordLifecycle.Operation

    @Suppress("ReturnCount")
    fun transition(
        next: BackgroundOperationState,
        at: Instant,
        progress: OperationProgress = this.progress,
        errorCode: String? = this.errorCode,
    ): DomainResult<BackgroundOperation> {
        if (next !in ALLOWED_TRANSITIONS.getValue(state) || at < updatedAt) {
            return DomainResult.Failure(OperationError.InvalidTransition(state, next))
        }
        val failure = next == BackgroundOperationState.FAILED_RETRYABLE || next == BackgroundOperationState.FAILED_FINAL
        if (failure != (errorCode != null)) {
            return DomainResult.Failure(OperationError.InvalidErrorState)
        }
        return DomainResult.Success(
            copy(
                state = next,
                startedAt = startedAt ?: at.takeIf { next != BackgroundOperationState.QUEUED },
                updatedAt = at,
                progress = progress,
                checkpointVersion = Math.addExact(checkpointVersion, 1L),
                errorCode = errorCode,
                cancelRequested = next == BackgroundOperationState.CANCEL_REQUESTED || cancelRequested,
            ),
        )
    }

    fun advance(progress: OperationProgress, at: Instant): DomainResult<BackgroundOperation> {
        if (state !in CHECKPOINTABLE_STATES || at < updatedAt) {
            return DomainResult.Failure(OperationError.InvalidTransition(state, state))
        }
        return DomainResult.Success(
            copy(
                updatedAt = at,
                progress = progress,
                checkpointVersion = Math.addExact(checkpointVersion, 1L),
            ),
        )
    }

    fun configureImportCommit(parameters: OperationParameters.Import, at: Instant): DomainResult<BackgroundOperation> {
        val current = this.parameters as? OperationParameters.Import
            ?: return DomainResult.Failure(OperationError.InvalidParameters)
        if (state != BackgroundOperationState.RUNNING || at < updatedAt ||
            current.sourceHandleId != parameters.sourceHandleId || current.format != parameters.format || parameters.commit == null
        ) {
            return DomainResult.Failure(OperationError.InvalidParameters)
        }
        return DomainResult.Success(copy(parameters = parameters, updatedAt = at, checkpointVersion = Math.addExact(checkpointVersion, 1L)))
    }

    fun configureRestoreMode(mode: RestoreMode, at: Instant): DomainResult<BackgroundOperation> {
        val current = parameters as? OperationParameters.Restore
            ?: return DomainResult.Failure(OperationError.InvalidParameters)
        if (state != BackgroundOperationState.RUNNING || at < updatedAt) {
            return DomainResult.Failure(OperationError.InvalidParameters)
        }
        return DomainResult.Success(
            copy(
                type = if (mode == RestoreMode.REPLACE) BackgroundOperationType.RESTORE_REPLACE else BackgroundOperationType.RESTORE_MERGE,
                parameters = current.copy(mode = mode),
                updatedAt = at,
                checkpointVersion = Math.addExact(checkpointVersion, 1L),
            ),
        )
    }

    companion object {
        fun queued(
            id: BackgroundOperationId,
            type: BackgroundOperationType,
            createdAt: Instant,
            parameters: OperationParameters,
        ): BackgroundOperation = BackgroundOperation(
            id = id,
            type = type,
            state = BackgroundOperationState.QUEUED,
            createdAt = createdAt,
            startedAt = null,
            updatedAt = createdAt,
            progress = OperationProgress(0L, null),
            checkpointVersion = 0L,
            errorCode = null,
            cancelRequested = false,
            parameters = parameters,
        )

        /** Persistence-only reconstruction; runtime and persisted states share the same invariants. */
        fun restore(
            id: BackgroundOperationId,
            type: BackgroundOperationType,
            state: BackgroundOperationState,
            createdAt: Instant,
            startedAt: Instant?,
            updatedAt: Instant,
            progress: OperationProgress,
            checkpointVersion: Long,
            errorCode: String?,
            cancelRequested: Boolean,
            parameters: OperationParameters,
        ): BackgroundOperation {
            require(updatedAt >= createdAt)
            require(startedAt == null || startedAt in createdAt..updatedAt)
            require(checkpointVersion >= 0L)
            val failed = state == BackgroundOperationState.FAILED_RETRYABLE || state == BackgroundOperationState.FAILED_FINAL
            require(failed == (errorCode != null))
            require(
                !cancelRequested || state in setOf(
                    BackgroundOperationState.CANCEL_REQUESTED,
                    BackgroundOperationState.ROLLING_BACK,
                    BackgroundOperationState.FAILED_FINAL,
                ),
            )
            return BackgroundOperation(
                id, type, state, createdAt, startedAt, updatedAt, progress, checkpointVersion,
                errorCode, cancelRequested, parameters,
            )
        }

        private val ALLOWED_TRANSITIONS: Map<BackgroundOperationState, Set<BackgroundOperationState>> = mapOf(
            BackgroundOperationState.QUEUED to setOf(
                BackgroundOperationState.PREPARING,
                BackgroundOperationState.CANCEL_REQUESTED,
            ),
            BackgroundOperationState.PREPARING to setOf(
                BackgroundOperationState.RUNNING,
                BackgroundOperationState.FAILED_RETRYABLE,
                BackgroundOperationState.FAILED_FINAL,
                BackgroundOperationState.CANCEL_REQUESTED,
            ),
            BackgroundOperationState.RUNNING to setOf(
                BackgroundOperationState.PAUSED,
                BackgroundOperationState.CANCEL_REQUESTED,
                BackgroundOperationState.FAILED_RETRYABLE,
                BackgroundOperationState.FAILED_FINAL,
                BackgroundOperationState.COMMITTING,
            ),
            BackgroundOperationState.PAUSED to setOf(
                BackgroundOperationState.RUNNING,
                BackgroundOperationState.CANCEL_REQUESTED,
            ),
            BackgroundOperationState.CANCEL_REQUESTED to setOf(
                BackgroundOperationState.ROLLING_BACK,
                BackgroundOperationState.FAILED_FINAL,
            ),
            BackgroundOperationState.FAILED_RETRYABLE to setOf(
                BackgroundOperationState.QUEUED,
                BackgroundOperationState.ROLLING_BACK,
                BackgroundOperationState.FAILED_FINAL,
            ),
            BackgroundOperationState.FAILED_FINAL to emptySet(),
            BackgroundOperationState.COMMITTING to setOf(
                BackgroundOperationState.SUCCEEDED,
                BackgroundOperationState.FAILED_RETRYABLE,
                BackgroundOperationState.ROLLING_BACK,
                BackgroundOperationState.FAILED_FINAL,
            ),
            BackgroundOperationState.ROLLING_BACK to setOf(
                BackgroundOperationState.FAILED_FINAL,
                BackgroundOperationState.SUCCEEDED,
            ),
            BackgroundOperationState.SUCCEEDED to emptySet(),
        )
        private val CHECKPOINTABLE_STATES = setOf(
            BackgroundOperationState.PREPARING,
            BackgroundOperationState.RUNNING,
            BackgroundOperationState.COMMITTING,
            BackgroundOperationState.ROLLING_BACK,
        )
    }
}

sealed interface OperationError : app.ledger.core.common.DomainError {
    data object InvalidParameters : OperationError {
        override val code: String = "OPERATION_INVALID_PARAMETERS"
    }

    data class InvalidTransition(
        val from: BackgroundOperationState,
        val to: BackgroundOperationState,
    ) : OperationError {
        override val code: String = "OPERATION_INVALID_TRANSITION"
    }

    data object InvalidErrorState : OperationError {
        override val code: String = "OPERATION_INVALID_ERROR_STATE"
    }

    data object LiveHeadChanged : OperationError {
        override val code: String = "OPERATION_LIVE_HEAD_CHANGED"
    }

    data object PurgedEntityCannotBeRestored : OperationError {
        override val code: String = "OPERATION_PURGED_ENTITY_CANNOT_BE_RESTORED"
    }
}

class EncryptedCheckpoint private constructor(bytes: ByteArray) {
    private val stored = bytes.copyOf()

    val bytes: ByteArray
        get() = stored.copyOf()

    override fun equals(other: Any?): Boolean = other is EncryptedCheckpoint && stored.contentEquals(other.stored)

    override fun hashCode(): Int = stored.contentHashCode()

    companion object {
        fun of(bytes: ByteArray): DomainResult<EncryptedCheckpoint> = if (bytes.isNotEmpty()) {
            DomainResult.Success(EncryptedCheckpoint(bytes))
        } else {
            DomainResult.Failure(OperationError.InvalidErrorState)
        }
    }
}

data class OperationCheckpoint(
    val operationId: BackgroundOperationId,
    val sequence: Long,
    val phase: BackgroundOperationState,
    val checkpoint: EncryptedCheckpoint,
    val createdAt: Instant,
) : LifecycleRecord<RecordLifecycle.Operation> {
    override val lifecycle: RecordLifecycle.Operation = RecordLifecycle.Operation

    init {
        require(sequence >= 0L)
    }
}

/** The only value permitted in Worker/UIDT/service launch payloads. */
@JvmInline value class OperationLaunchToken(val operationId: BackgroundOperationId)

data class ImportRecord(
    val id: ImportRecordId,
    val operationId: BackgroundOperationId,
    val format: ImportFormat,
    val sourceFingerprint: Hash256,
    val importedAt: Instant?,
    val committedLocalRevision: LocalRevision?,
) : LifecycleRecord<RecordLifecycle.Operation> {
    override val lifecycle: RecordLifecycle.Operation = RecordLifecycle.Operation
}

data class ImportBatchCommit(
    val importRecordId: ImportRecordId,
    val batchId: ImportBatchId,
    val commitId: BookCommitId,
    val firstRowNumber: Long,
    val lastRowNumber: Long,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact

    init {
        require(firstRowNumber > 0L && lastRowNumber >= firstRowNumber)
    }
}

data class ImportSourceReference(
    val importRecordId: ImportRecordId,
    val rowNumber: Long,
    val transactionId: TransactionId,
    val sourceRowHash: Hash256,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact
}
