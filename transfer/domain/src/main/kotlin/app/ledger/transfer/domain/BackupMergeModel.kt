package app.ledger.transfer.domain

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.ContentHash
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.LifecycleRecord
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.PurgeTombstone
import app.ledger.finance.domain.RecordLifecycle
import app.ledger.finance.domain.StableEntityReference
import java.time.Instant

enum class BackupRepositoryKind {
    APP_PRIVATE,
    USER_SELECTED_DIRECTORY,
    GOOGLE_DRIVE,
}

data class BackupRepository(
    val id: BackupRepositoryId,
    val kind: BackupRepositoryKind,
    val handleId: StableId,
    val enabled: Boolean,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current
}

enum class BackupSnapshotState {
    PREPARING,
    COMPLETE,
    FAILED,
    DELETED,
}

data class BackupSnapshot(
    val id: BackupSnapshotId,
    val repositoryId: BackupRepositoryId,
    val headCommitId: BookCommitId,
    val localRevision: LocalRevision,
    val createdAt: Instant,
    val state: BackupSnapshotState,
    val manifestHash: Hash256?,
    val objectIds: List<BackupObjectId>,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact
}

enum class BackupObjectKind {
    DATABASE_CHUNK,
    ATTACHMENT,
    SETTINGS,
    KEY_ENVELOPE,
    VAULT_ENVELOPE,
}

data class BackupObject(
    val id: BackupObjectId,
    val repositoryId: BackupRepositoryId,
    val contentHash: Hash256,
    val size: Long,
    val kind: BackupObjectKind,
    val createdAt: Instant,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact

    init {
        require(size >= 0L)
    }
}

enum class RestoreState {
    READING_SOURCE,
    AUTHENTICATING_PASSWORD,
    VERIFYING_OBJECTS,
    MIGRATING,
    CHECKING_INTEGRITY,
    REBUILDING_PROJECTIONS,
    VALIDATING,
    READY_TO_EXCHANGE,
    EXCHANGING,
    VERIFYING_LIVE,
    ROLLING_BACK,
    COMPLETE,
    FAILED,
}

data class RestoreRecord(
    val id: RestoreRecordId,
    val operationId: BackgroundOperationId,
    val mode: RestoreMode,
    val snapshotId: BackupSnapshotId,
    val state: RestoreState,
    val sourceHeadCommitId: BookCommitId,
    val liveHeadAtStart: BookCommitId,
    val resultingHeadCommitId: BookCommitId?,
    val validatedAt: Instant?,
) : LifecycleRecord<RecordLifecycle.Operation> {
    override val lifecycle: RecordLifecycle.Operation = RecordLifecycle.Operation
}

enum class MergeSessionState {
    FINDING_ANCESTOR,
    COMPARING,
    AWAITING_RESOLUTION,
    APPLYING,
    COMPLETE,
    FAILED,
}

data class MergeSession(
    val id: MergeSessionId,
    val operationId: BackgroundOperationId,
    val commonAncestorCommitId: BookCommitId?,
    val localHeadCommitId: BookCommitId,
    val incomingHeadCommitId: BookCommitId,
    val state: MergeSessionState,
    val conflictIds: List<MergeConflictId>,
) : LifecycleRecord<RecordLifecycle.Operation> {
    override val lifecycle: RecordLifecycle.Operation = RecordLifecycle.Operation
}

data class MergeEntityVersion(
    val entityType: EntityType,
    val entityId: StableId,
    val contentHash: ContentHash,
    val commitId: BookCommitId,
    val generation: Long,
)

/** One immutable commit-graph vertex. Ordering is graph-derived; wall-clock time is intentionally absent. */
data class MergeCommitVertex(
    val id: BookCommitId,
    val parentIds: List<BookCommitId>,
) {
    init {
        require(parentIds.size <= 2)
        require(id !in parentIds)
        require(parentIds.distinct().size == parentIds.size)
    }
}

data class MergeBookIdentity(
    val bookId: StableId,
    val baseCurrencyCode: String,
) {
    init {
        require(baseCurrencyCode.matches(Regex("[A-Z]{3}")))
    }
}

data class ThreeWayMergeInput(
    val localBook: MergeBookIdentity,
    val incomingBook: MergeBookIdentity,
    val graph: List<MergeCommitVertex>,
    val localHead: BookCommitId,
    val incomingHead: BookCommitId,
    val ancestorVersions: Map<StableEntityReference, MergeEntityVersion>,
    val localVersions: Map<StableEntityReference, MergeEntityVersion>,
    val incomingVersions: Map<StableEntityReference, MergeEntityVersion>,
    val localTombstones: Map<StableEntityReference, PurgeTombstone>,
    val incomingTombstones: Map<StableEntityReference, PurgeTombstone>,
)

sealed interface MergeDecision {
    val entity: StableEntityReference

    data class KeepLocal(override val entity: StableEntityReference, val version: MergeEntityVersion?) : MergeDecision
    data class KeepIncoming(override val entity: StableEntityReference, val version: MergeEntityVersion?) : MergeDecision
    data class KeepPurgeTombstone(override val entity: StableEntityReference, val tombstone: PurgeTombstone) : MergeDecision
}

data class ThreeWayMergePlan(
    val commonAncestor: BookCommitId,
    val localHead: BookCommitId,
    val incomingHead: BookCommitId,
    val automaticDecisions: List<MergeDecision>,
    val conflicts: List<MergeConflict>,
) {
    val readyToApply: Boolean = conflicts.isEmpty()
}

sealed interface RestoreFailure : app.ledger.core.common.DomainError {
    data object WrongPassword : RestoreFailure {
        override val code: String = "RESTORE_WRONG_PASSWORD"
    }
    data object CorruptHeader : RestoreFailure {
        override val code: String = "RESTORE_CORRUPT_HEADER"
    }
    data object CorruptObject : RestoreFailure {
        override val code: String = "RESTORE_CORRUPT_OBJECT"
    }
    data object UnsupportedVersion : RestoreFailure {
        override val code: String = "RESTORE_UNSUPPORTED_VERSION"
    }
    data object BookMismatch : RestoreFailure {
        override val code: String = "RESTORE_BOOK_MISMATCH"
    }
    data object BaseCurrencyMismatch : RestoreFailure {
        override val code: String = "RESTORE_BASE_CURRENCY_MISMATCH"
    }
    data object MigrationFailed : RestoreFailure {
        override val code: String = "RESTORE_MIGRATION_FAILED"
    }
    data object IntegrityFailed : RestoreFailure {
        override val code: String = "RESTORE_INTEGRITY_FAILED"
    }
    data object ProjectionFailed : RestoreFailure {
        override val code: String = "RESTORE_PROJECTION_FAILED"
    }
    data object InsufficientSpace : RestoreFailure {
        override val code: String = "RESTORE_INSUFFICIENT_SPACE"
    }
    data object PermissionRevoked : RestoreFailure {
        override val code: String = "RESTORE_PERMISSION_REVOKED"
    }
    data object SafetySnapshotFailed : RestoreFailure {
        override val code: String = "RESTORE_SAFETY_SNAPSHOT_FAILED"
    }
    data object LiveHeadChanged : RestoreFailure {
        override val code: String = "RESTORE_LIVE_HEAD_CHANGED"
    }
    data object ExplicitResolutionRequired : RestoreFailure {
        override val code: String = "RESTORE_EXPLICIT_RESOLUTION_REQUIRED"
    }
    data object Cancelled : RestoreFailure {
        override val code: String = "RESTORE_CANCELLED"
    }
    data object RolledBack : RestoreFailure {
        override val code: String = "RESTORE_ROLLED_BACK"
    }
}

enum class MergeConflictKind {
    BOTH_MODIFIED,
    DELETE_VERSUS_EDIT,
    TRANSACTION_REVISION_FORK,
    PURGED_ENTITY,
}

sealed interface MergeResolution {
    data object KeepLocal : MergeResolution

    data object KeepIncoming : MergeResolution

    data object KeepPurgeTombstone : MergeResolution
}

data class MergeConflict(
    val id: MergeConflictId,
    val sessionId: MergeSessionId,
    val kind: MergeConflictKind,
    val ancestor: MergeEntityVersion?,
    val local: MergeEntityVersion?,
    val incoming: MergeEntityVersion?,
    val purgeTombstone: PurgeTombstone?,
    val resolution: MergeResolution?,
) : LifecycleRecord<RecordLifecycle.Operation> {
    override val lifecycle: RecordLifecycle.Operation = RecordLifecycle.Operation

    fun resolve(requested: MergeResolution): DomainResult<MergeConflict> {
        if (purgeTombstone != null && requested != MergeResolution.KeepPurgeTombstone) {
            return DomainResult.Failure(OperationError.PurgedEntityCannotBeRestored)
        }
        return DomainResult.Success(copy(resolution = requested))
    }
}
