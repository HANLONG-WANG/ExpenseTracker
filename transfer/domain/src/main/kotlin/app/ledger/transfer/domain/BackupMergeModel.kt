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
import java.time.Instant

enum class BackupRepositoryKind {
    APP_PRIVATE,
    USER_SELECTED_DIRECTORY,
    GOOGLE_DRIVE_APP_DATA,
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
    DATABASE,
    ATTACHMENT,
    MANIFEST,
    KEY_ENVELOPE,
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
    VALIDATING,
    READY_TO_EXCHANGE,
    EXCHANGING,
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
