package app.ledger.finance.domain

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.money.CurrencyCode
import java.time.Instant
import java.time.ZoneId

enum class BookState {
    READY,
    MAINTENANCE,
    RECOVERY_REQUIRED,
}

data class Book(
    val id: BookId,
    val baseCurrency: CurrencyCode,
    val defaultZoneId: ZoneId,
    val headCommitId: BookCommitId,
    val localRevision: LocalRevision,
    val valuationRevision: LocalRevision,
    val ruleSetVersion: RuleSetVersion,
    val createdAt: Instant,
    val firstFinancialCommitAt: Instant?,
    val state: BookState,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current

    fun canChangeBaseCurrency(): Boolean = firstFinancialCommitAt == null
}

enum class CommitKind {
    USER_MUTATION,
    BATCH_MUTATION,
    IMPORT,
    MERGE,
    RESTORE,
    PURGE,
    REFERENCE_DATA_CHANGE,
    PROJECTION_REBUILD,
    MAINTENANCE,
}

@ConsistentCopyVisibility
data class BookCommit private constructor(
    val id: BookCommitId,
    val localRevision: LocalRevision,
    val kind: CommitKind,
    val parentIds: List<BookCommitId>,
    val createdAt: Instant,
    val commandId: CommandId?,
    val deviceInstanceId: DeviceInstanceId,
    val rootHash: Hash256,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact

    companion object {
        @Suppress("LongParameterList")
        fun create(
            id: BookCommitId,
            localRevision: LocalRevision,
            kind: CommitKind,
            parentIds: List<BookCommitId>,
            createdAt: Instant,
            commandId: CommandId?,
            deviceInstanceId: DeviceInstanceId,
            rootHash: Hash256,
        ): DomainResult<BookCommit> {
            val expectedParentCount = if (kind == CommitKind.MERGE) 2 else 1
            if (parentIds.size != expectedParentCount || parentIds.toSet().size != parentIds.size) {
                return DomainResult.Failure(DomainViolation.InvalidField("bookCommit.parentIds"))
            }
            return DomainResult.Success(
                BookCommit(
                    id = id,
                    localRevision = localRevision,
                    kind = kind,
                    parentIds = parentIds.toList(),
                    createdAt = createdAt,
                    commandId = commandId,
                    deviceInstanceId = deviceInstanceId,
                    rootHash = rootHash,
                ),
            )
        }
    }
}

data class CommandReceipt(
    val commandId: CommandId,
    val commandType: FinancialCommandType,
    val payloadHash: Hash256,
    val commitId: BookCommitId,
    val primaryEntityId: StableEntityReference?,
    val executedAt: Instant,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact
}

data class StableEntityReference(
    val type: EntityType,
    val stableId: app.ledger.core.common.StableId,
)

enum class EntityType {
    BOOK,
    ACCOUNT,
    CARD,
    CATEGORY,
    MERCHANT,
    PLACE,
    TRANSACTION,
    PROJECT,
    GOAL,
    BUDGET,
    CREDIT_STATEMENT,
    INSTALLMENT_PLAN,
    LOAN,
    PARTICIPANT,
    SETTLEMENT_ACTIVITY,
    BLUEPRINT,
    RECURRENCE_SERIES,
    ATTACHMENT,
    BLOB,
    LOCATION_RECORD,
    BUDGET_TEMPLATE,
}

enum class EntityChangeOperation {
    CREATE,
    UPDATE,
    ARCHIVE,
    RESTORE,
    DELETE,
    PURGE,
}

data class EntityChange(
    val commitId: BookCommitId,
    val entity: StableEntityReference,
    val operation: EntityChangeOperation,
    val beforeHash: ContentHash?,
    val afterHash: ContentHash?,
    val entityRevisionId: EntityRevisionId?,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact
}

class CanonicalSnapshot private constructor(bytes: ByteArray) {
    private val stored = bytes.copyOf()

    val bytes: ByteArray
        get() = stored.copyOf()

    override fun equals(other: Any?): Boolean = other is CanonicalSnapshot && stored.contentEquals(other.stored)

    override fun hashCode(): Int = stored.contentHashCode()

    companion object {
        fun of(bytes: ByteArray): DomainResult<CanonicalSnapshot> = if (bytes.isNotEmpty()) {
            DomainResult.Success(CanonicalSnapshot(bytes))
        } else {
            DomainResult.Failure(DomainViolation.InvalidField("canonicalSnapshot"))
        }
    }
}

enum class EntityRevisionAction {
    CREATE,
    EDIT,
    ARCHIVE,
    RESTORE,
    DELETE,
    MERGE,
}

data class EntityRevision(
    val id: EntityRevisionId,
    val entity: StableEntityReference,
    val revisionNumber: Int,
    val action: EntityRevisionAction,
    val commitId: BookCommitId,
    val contentHash: ContentHash,
    val canonicalSnapshot: CanonicalSnapshot,
    val schemaVersion: Int,
) : LifecycleRecord<RecordLifecycle.Revision> {
    override val lifecycle: RecordLifecycle.Revision = RecordLifecycle.Revision

    init {
        require(revisionNumber > 0)
        require(schemaVersion > 0)
    }
}

data class PurgeTombstone(
    val entity: StableEntityReference,
    val purgeCommitId: BookCommitId,
    val purgedAt: Instant,
    val purgeGeneration: Long,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact

    init {
        require(purgeGeneration > 0L)
    }

    /** A purge fact always wins over an entity version at an earlier or equal generation. */
    fun supersedes(entityGeneration: Long): Boolean = purgeGeneration >= entityGeneration
}
