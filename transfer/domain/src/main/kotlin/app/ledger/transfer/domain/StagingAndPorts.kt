@file:Suppress("TooManyFunctions")

package app.ledger.transfer.domain

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.application.FinancialMutationCoordinator
import app.ledger.finance.domain.AttachmentId
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.FinancialCommandType
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.LifecycleRecord
import app.ledger.finance.domain.RecordLifecycle
import app.ledger.finance.domain.TransactionId
import java.time.Instant

class RawRowPayload private constructor(bytes: ByteArray) {
    private val stored = bytes.copyOf()

    val bytes: ByteArray
        get() = stored.copyOf()

    override fun equals(other: Any?): Boolean = other is RawRowPayload && stored.contentEquals(other.stored)

    override fun hashCode(): Int = stored.contentHashCode()

    companion object {
        fun of(bytes: ByteArray): DomainResult<RawRowPayload> = if (bytes.isNotEmpty()) {
            DomainResult.Success(RawRowPayload(bytes))
        } else {
            DomainResult.Failure(StagingError.InvalidRow)
        }
    }
}

data class StagingRawRow(
    val rowNumber: Long,
    val payload: RawRowPayload,
    val sourceHash: Hash256,
) : LifecycleRecord<RecordLifecycle.Operation> {
    override val lifecycle: RecordLifecycle.Operation = RecordLifecycle.Operation

    init {
        require(rowNumber > 0L)
    }
}

sealed interface StagingValue {
    data class Text(val value: String) : StagingValue

    data class Integer(val value: Long) : StagingValue

    data class Decimal(val value: java.math.BigDecimal) : StagingValue

    data class Date(val value: java.time.LocalDate) : StagingValue

    data class InstantValue(val value: Instant) : StagingValue

    data object Empty : StagingValue
}

data class StagingParsedField(
    val sourceColumn: String,
    val value: StagingValue,
)

data class StagingParsedRow(
    val rowNumber: Long,
    val fields: List<StagingParsedField>,
) : LifecycleRecord<RecordLifecycle.Operation> {
    override val lifecycle: RecordLifecycle.Operation = RecordLifecycle.Operation
}

enum class ImportTargetField {
    TRANSACTION_KIND,
    CATEGORY,
    AMOUNT_EXPRESSION,
    CURRENCY,
    ACCOUNT,
    CARD,
    MERCHANT,
    OCCURRED_AT,
    ZONE_ID,
    PROJECT,
    NOTE,
    ATTACHMENT,
    REFUND_REFERENCE,
    INSTALLMENT,
    SETTLEMENT_SHARE,
    LOCATION,
    PAYER,
    PAYEE,
    FX_RATE,
}

data class StagingMapping(
    val sourceColumn: String,
    val targetField: ImportTargetField,
    val transformation: ImportTransformation,
)

sealed interface ImportTransformation {
    data object Identity : ImportTransformation

    data class DatePattern(val pattern: String) : ImportTransformation

    data class DecimalSeparator(val separator: Char) : ImportTransformation

    data class ClosedValueMap(val entries: Map<String, String>) : ImportTransformation
}

data class StagingValidationError(
    val rowNumber: Long,
    val field: ImportTargetField?,
    val errorCode: String,
) : LifecycleRecord<RecordLifecycle.Operation> {
    override val lifecycle: RecordLifecycle.Operation = RecordLifecycle.Operation
}

enum class DuplicateMatchKind {
    EXACT_SOURCE_REFERENCE,
    CONTENT_HASH,
    DATE_AMOUNT_ACCOUNT,
}

data class StagingDuplicateCandidate(
    val rowNumber: Long,
    val existingTransactionId: TransactionId,
    val kind: DuplicateMatchKind,
    val confidenceBasis: String,
) : LifecycleRecord<RecordLifecycle.Operation> {
    override val lifecycle: RecordLifecycle.Operation = RecordLifecycle.Operation
}

class PreparedCommandPayload private constructor(bytes: ByteArray) {
    private val stored = bytes.copyOf()

    val bytes: ByteArray get() = stored.copyOf()

    override fun equals(other: Any?): Boolean = other is PreparedCommandPayload && stored.contentEquals(other.stored)
    override fun hashCode(): Int = stored.contentHashCode()

    companion object {
        fun of(bytes: ByteArray): DomainResult<PreparedCommandPayload> = if (bytes.isNotEmpty()) {
            DomainResult.Success(PreparedCommandPayload(bytes))
        } else {
            DomainResult.Failure(StagingError.InvalidRow)
        }
    }
}

enum class PreparedCommandValidationState { PENDING, DOMAIN_VALIDATED, DUPLICATE_SKIPPED }

data class StagingPreparedCommand(
    val rowNumber: Long,
    val commandId: CommandId,
    val commandType: FinancialCommandType?,
    val structuredKind: StructuredEntityKind?,
    val payload: PreparedCommandPayload,
    val payloadHash: Hash256,
    val validationState: PreparedCommandValidationState,
) : LifecycleRecord<RecordLifecycle.Operation> {
    override val lifecycle: RecordLifecycle.Operation = RecordLifecycle.Operation

    init {
        require(rowNumber > 0L)
        require((commandType != null) xor (structuredKind != null))
    }
}

data class StagingAttachment(
    val rowNumber: Long,
    val sourceHandleId: StableId,
    val importedAttachmentId: AttachmentId?,
    val contentHash: Hash256?,
) : LifecycleRecord<RecordLifecycle.Operation> {
    override val lifecycle: RecordLifecycle.Operation = RecordLifecycle.Operation
}

sealed interface StagingError : app.ledger.core.common.DomainError {
    data object InvalidRow : StagingError {
        override val code: String = "STAGING_INVALID_ROW"
    }

    data object ValidationFailed : StagingError {
        override val code: String = "STAGING_VALIDATION_FAILED"
    }
}

interface BackgroundOperationRepository {
    suspend fun get(id: BackgroundOperationId): DomainResult<BackgroundOperation?>

    suspend fun save(operation: BackgroundOperation): DomainResult<Unit>

    suspend fun append(checkpoint: OperationCheckpoint): DomainResult<Unit>
}

interface EncryptedStagingRepository {
    suspend fun create(operationId: BackgroundOperationId): DomainResult<Unit>

    /** Clears derived validation, duplicate, mapping, and prepared artifacts before re-prepare. */
    suspend fun clearPreparation(): DomainResult<Unit>

    suspend fun appendRaw(rows: List<StagingRawRow>): DomainResult<Unit>

    suspend fun appendParsed(rows: List<StagingParsedRow>): DomainResult<Unit>

    suspend fun saveMappings(mappings: List<StagingMapping>): DomainResult<Unit>

    suspend fun saveErrors(errors: List<StagingValidationError>): DomainResult<Unit>

    suspend fun saveDuplicates(candidates: List<StagingDuplicateCandidate>): DomainResult<Unit>

    suspend fun savePrepared(commands: List<StagingPreparedCommand>): DomainResult<Unit>

    suspend fun saveAttachments(attachments: List<StagingAttachment>): DomainResult<Unit>

    suspend fun rawRows(offsetExclusive: Long, limit: Int): DomainResult<List<StagingRawRow>>

    suspend fun parsedRows(offsetExclusive: Long, limit: Int): DomainResult<List<StagingParsedRow>>

    suspend fun preparedCommands(offsetExclusive: Long, limit: Int): DomainResult<List<StagingPreparedCommand>>

    suspend fun counts(): DomainResult<StagingCounts>

    suspend fun destroy(): DomainResult<Unit>
}

data class StagingCounts(
    val raw: Long,
    val parsed: Long,
    val errors: Long,
    val duplicates: Long,
    val prepared: Long,
) {
    init {
        require(listOf(raw, parsed, errors, duplicates, prepared).all { it >= 0L })
    }
}

interface ShadowLedgerRepository {
    suspend fun createFromLiveHead(expectedHead: app.ledger.finance.domain.BookCommitId): DomainResult<Unit>

    suspend fun apply(command: FinancialCommand): DomainResult<CommandReceipt>

    suspend fun validateIntegrity(): DomainResult<ShadowValidationReport>

    suspend fun discard(): DomainResult<Unit>
}

data class ShadowValidationReport(
    val sqlCipherReadable: Boolean,
    val integrityCheckPassed: Boolean,
    val foreignKeyCheckPassed: Boolean,
    val journalsBalanced: Boolean,
    val projectionsAligned: Boolean,
    val subtypeDetailsComplete: Boolean,
)

fun interface AtomicLedgerExchangePort {
    suspend fun exchange(expectedLiveHead: app.ledger.finance.domain.BookCommitId): DomainResult<Unit>
}

interface BackupObjectRepositoryPort {
    suspend fun put(objectValue: BackupObject): DomainResult<Unit>

    suspend fun get(id: BackupObjectId): DomainResult<BackupObject?>

    suspend fun link(link: BackupSnapshotObject): DomainResult<Unit>
}

interface RemoteBackupPort {
    suspend fun begin(session: DriveUploadSession): DomainResult<Unit>

    suspend fun uploadObject(objectValue: BackupObject): DomainResult<Unit>

    suspend fun complete(session: DriveUploadSession): DomainResult<Unit>
}

class CoordinatorPreparedCommandPort(private val coordinator: FinancialMutationCoordinator) {
    suspend fun execute(command: FinancialCommand): DomainResult<CommandReceipt> = coordinator.execute(command)
}

data class BackupSnapshotObject(
    val snapshotId: BackupSnapshotId,
    val objectId: BackupObjectId,
    val ordinal: Long,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact

    init {
        require(ordinal >= 0L)
    }
}

enum class DriveUploadState {
    CREATED,
    UPLOADING,
    PAUSED,
    COMPLETE,
    FAILED,
}

data class DriveUploadSession(
    val id: StableId,
    val snapshotId: BackupSnapshotId,
    val repositoryId: BackupRepositoryId,
    val state: DriveUploadState,
    val uploadedObjectIds: Set<BackupObjectId>,
    val createdAt: Instant,
    val updatedAt: Instant,
) : LifecycleRecord<RecordLifecycle.Operation> {
    override val lifecycle: RecordLifecycle.Operation = RecordLifecycle.Operation
}

data class MergeResolutionRecord(
    val conflictId: MergeConflictId,
    val resolution: MergeResolution,
    val resolvedAt: Instant,
) : LifecycleRecord<RecordLifecycle.Operation> {
    override val lifecycle: RecordLifecycle.Operation = RecordLifecycle.Operation
}
