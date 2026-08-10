package app.ledger.finance.application

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.ContentHash
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.MergeEntitySelection

data class FinanceMergeCommitVertex(
    val id: BookCommitId,
    val parents: List<BookCommitId>,
)

data class FinanceMergeEntityVersion(
    val entityType: EntityType,
    val entityId: StableId,
    val contentHash: ContentHash,
    val commitId: BookCommitId,
    val generation: Long,
)

data class FinanceMergeTombstone(
    val entityType: EntityType,
    val entityId: StableId,
    val purgeCommitId: BookCommitId,
    val purgedAtEpochMillis: Long,
    val generation: Long,
)

data class FinanceMergeInspection(
    val bookId: StableId,
    val baseCurrency: String,
    val localHead: BookCommitId,
    val incomingHead: BookCommitId,
    val graph: List<FinanceMergeCommitVertex>,
    val ancestorVersions: Map<Pair<EntityType, StableId>, FinanceMergeEntityVersion>,
    val localVersions: Map<Pair<EntityType, StableId>, FinanceMergeEntityVersion>,
    val incomingVersions: Map<Pair<EntityType, StableId>, FinanceMergeEntityVersion>,
    val localTombstones: Map<Pair<EntityType, StableId>, FinanceMergeTombstone>,
    val incomingTombstones: Map<Pair<EntityType, StableId>, FinanceMergeTombstone>,
)

interface MergeRestoreApplicationPort {
    suspend fun inspect(value: MaterializedRestorePackage): DomainResult<FinanceMergeInspection>

    suspend fun prepareResolved(
        operationId: StableId,
        commonAncestor: BookCommitId,
        incomingHead: BookCommitId,
        selections: List<MergeEntitySelection>,
    ): DomainResult<PreparedRestoreLedger>

    fun cleanup(operationId: StableId)
}
