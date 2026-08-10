package app.ledger.app

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.application.FinanceMergeEntityVersion
import app.ledger.finance.application.FinanceMergeInspection
import app.ledger.finance.application.FinanceMergeTombstone
import app.ledger.finance.application.MaterializedRestorePackage
import app.ledger.finance.application.MergeRestoreApplicationPort
import app.ledger.finance.application.PreparedRestoreLedger
import app.ledger.finance.domain.MergeEntitySelection
import app.ledger.finance.domain.MergeEntitySource
import app.ledger.finance.domain.PurgeTombstone
import app.ledger.finance.domain.StableEntityReference
import app.ledger.transfer.data.MergeLedgerPort
import app.ledger.transfer.data.RestoreMaterializationResult
import app.ledger.transfer.domain.MergeBookIdentity
import app.ledger.transfer.domain.MergeCommitVertex
import app.ledger.transfer.domain.MergeDecision
import app.ledger.transfer.domain.MergeEntityVersion
import app.ledger.transfer.domain.MergeResolution
import app.ledger.transfer.domain.ThreeWayMergeInput
import app.ledger.transfer.domain.ThreeWayMergePlan
import java.io.File
import java.time.Instant

internal class AndroidMergeLedgerPort(
    private val delegate: MergeRestoreApplicationPort,
) : MergeLedgerPort {
    override fun cleanup(operationId: StableId) = delegate.cleanup(operationId)

    override suspend fun inspect(
        bookId: StableId,
        operationId: StableId,
        materialized: RestoreMaterializationResult,
    ): DomainResult<ThreeWayMergeInput> = when (
        val result = delegate.inspect(materialized.toFinancePackage(bookId, operationId))
    ) {
        is DomainResult.Failure -> result
        is DomainResult.Success -> DomainResult.Success(result.value.toDomainInput())
    }

    override suspend fun applyToShadow(
        bookId: StableId,
        operationId: StableId,
        plan: ThreeWayMergePlan,
        resolutions: Map<StableId, MergeResolution>,
    ): DomainResult<PreparedRestoreLedger> {
        val selections = plan.automaticDecisions + plan.conflicts.map { conflict ->
            when (resolutions[conflict.id.value]) {
                MergeResolution.KeepLocal -> MergeDecision.KeepLocal(conflict.entity(), conflict.local)
                MergeResolution.KeepIncoming -> MergeDecision.KeepIncoming(conflict.entity(), conflict.incoming)
                MergeResolution.KeepPurgeTombstone -> MergeDecision.KeepPurgeTombstone(
                    conflict.entity(),
                    requireNotNull(conflict.purgeTombstone),
                )
                null -> error("unresolved merge conflict")
            }
        }
        return delegate.prepareResolved(
            operationId,
            plan.commonAncestor,
            plan.incomingHead,
            selections.map(MergeDecision::toFinanceSelection),
        )
    }

    private fun app.ledger.transfer.domain.MergeConflict.entity(): StableEntityReference {
        val version = local ?: incoming ?: ancestor
        return version?.let { StableEntityReference(it.entityType, it.entityId) }
            ?: requireNotNull(purgeTombstone).entity
    }
}

private fun MergeDecision.toFinanceSelection(): MergeEntitySelection = when (this) {
    is MergeDecision.KeepLocal -> MergeEntitySelection(
        entity,
        MergeEntitySource.LOCAL,
        version?.contentHash,
        version?.generation ?: 0L,
    )
    is MergeDecision.KeepIncoming -> MergeEntitySelection(
        entity,
        MergeEntitySource.INCOMING,
        version?.contentHash,
        version?.generation ?: 0L,
    )
    is MergeDecision.KeepPurgeTombstone -> MergeEntitySelection(
        entity,
        MergeEntitySource.PURGE_TOMBSTONE,
        null,
        tombstone.purgeGeneration,
    )
}

private fun FinanceMergeInspection.toDomainInput(): ThreeWayMergeInput = ThreeWayMergeInput(
    MergeBookIdentity(bookId, baseCurrency),
    MergeBookIdentity(bookId, baseCurrency),
    graph.map { MergeCommitVertex(it.id, it.parents) },
    localHead,
    incomingHead,
    ancestorVersions.mapKeys { StableEntityReference(it.key.first, it.key.second) }.mapValues { it.value.toDomain() },
    localVersions.mapKeys { StableEntityReference(it.key.first, it.key.second) }.mapValues { it.value.toDomain() },
    incomingVersions.mapKeys { StableEntityReference(it.key.first, it.key.second) }.mapValues { it.value.toDomain() },
    localTombstones.mapKeys { StableEntityReference(it.key.first, it.key.second) }.mapValues { it.value.toDomain() },
    incomingTombstones.mapKeys { StableEntityReference(it.key.first, it.key.second) }.mapValues { it.value.toDomain() },
)

private fun FinanceMergeEntityVersion.toDomain() = MergeEntityVersion(entityType, entityId, contentHash, commitId, generation)
private fun FinanceMergeTombstone.toDomain() = PurgeTombstone(
    StableEntityReference(entityType, entityId),
    purgeCommitId,
    Instant.ofEpochMilli(purgedAtEpochMillis),
    generation,
)

private fun RestoreMaterializationResult.toFinancePackage(bookId: StableId, operationId: StableId): MaterializedRestorePackage = MaterializedRestorePackage(
    bookId,
    operationId,
    targetDirectory.resolve("database/ledger.db").absolutePath,
    targetDirectory.resolve("settings").listFiles()?.singleOrNull()?.absolutePath,
    targetDirectory.resolve("attachments").takeIf(File::isDirectory)?.absolutePath,
    targetDirectory.resolve("keys/portable-key-material.envelope").absolutePath,
    targetDirectory.resolve("keys/vault-recovery.envelope").takeIf(File::isFile)?.absolutePath,
    databaseSchemaVersion,
    logicalBytes,
)
