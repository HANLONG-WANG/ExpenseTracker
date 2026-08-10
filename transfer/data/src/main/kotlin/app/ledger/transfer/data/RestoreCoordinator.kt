@file:Suppress("ReturnCount")

package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.RecoveryPassword
import app.ledger.finance.application.MaterializedRestorePackage
import app.ledger.finance.application.PreparedRestoreLedger
import app.ledger.finance.application.RestoreIntegrityReport
import app.ledger.finance.application.RestoreLedgerApplicationPort
import app.ledger.finance.domain.BookCommitId
import app.ledger.transfer.domain.CommitGraphMergePlanner
import app.ledger.transfer.domain.MergeConflict
import app.ledger.transfer.domain.MergeDecision
import app.ledger.transfer.domain.MergeResolution
import app.ledger.transfer.domain.RestoreFailure
import app.ledger.transfer.domain.RestoreState
import app.ledger.transfer.domain.ThreeWayMergeInput
import app.ledger.transfer.domain.ThreeWayMergePlan
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

fun interface PreRestoreSafetySnapshotPort {
    suspend fun create(bookId: StableId, operationId: StableId): DomainResult<StableId>
}

data class ReplaceRestoreRequest(
    val bookId: StableId,
    val operationId: StableId,
    val source: EncryptedRestoreSource,
    val password: RecoveryPassword,
    val target: DirectoryRestoreTarget,
)

data class ReplaceRestoreResult(
    val resultingHead: BookCommitId,
    val safetySnapshotId: StableId,
    val logicalBytes: Long,
)

/** Replacement restore is cancellable until exchange; exchange and rollback run non-cancellable. */
class ReplaceRestoreCoordinator(
    private val materializer: RestoreMaterializer,
    private val safetySnapshots: PreRestoreSafetySnapshotPort,
    private val ledger: RestoreLedgerApplicationPort,
) {
    suspend fun execute(
        request: ReplaceRestoreRequest,
        cancelled: () -> Boolean = { false },
        progress: RestoreProgressObserver = RestoreProgressObserver { },
    ): DomainResult<ReplaceRestoreResult> {
        val materialized = when (
            val result = materializer.materialize(
                request.source,
                request.password,
                request.target,
                request.bookId,
                cancelled,
                progress,
            )
        ) {
            is DomainResult.Failure -> return result
            is DomainResult.Success -> result.value
        }
        return executeMaterialized(request.bookId, request.operationId, materialized, cancelled, progress)
    }

    suspend fun executeMaterialized(
        bookId: StableId,
        operationId: StableId,
        materialized: RestoreMaterializationResult,
        cancelled: () -> Boolean = { false },
        progress: RestoreProgressObserver = RestoreProgressObserver { },
    ): DomainResult<ReplaceRestoreResult> {
        var safetyId: StableId? = null
        var retainRecoveryArtifacts = false
        try {
            if (cancelled()) return DomainResult.Failure(RestoreFailure.Cancelled)
            safetyId = when (val safety = safetySnapshots.create(bookId, operationId)) {
                is DomainResult.Failure -> return safety
                is DomainResult.Success -> safety.value
            }
            progress.onProgress(RestoreProgress(RestoreState.MIGRATING, materialized.logicalBytes, materialized.logicalBytes))
            val prepared = when (
                val result = ledger.prepareReplacement(materialized.toApplicationPackage(bookId, operationId))
            ) {
                is DomainResult.Failure -> return result
                is DomainResult.Success -> result.value
            }
            if (!prepared.integrity.isValid) return DomainResult.Failure(RestoreFailure.IntegrityFailed)
            progress.onProgress(RestoreProgress(RestoreState.READY_TO_EXCHANGE, materialized.logicalBytes, materialized.logicalBytes))
            if (cancelled()) return DomainResult.Failure(RestoreFailure.Cancelled)
            progress.onProgress(RestoreProgress(RestoreState.EXCHANGING, materialized.logicalBytes, materialized.logicalBytes))
            retainRecoveryArtifacts = true
            return withContext(NonCancellable) {
                val exchanged = when (val result = ledger.exchange(prepared, safetyId)) {
                    is DomainResult.Success -> result.value.also { retainRecoveryArtifacts = false }
                    is DomainResult.Failure -> {
                        if (ledger.rollback(bookId, operationId, safetyId) is DomainResult.Success) {
                            retainRecoveryArtifacts = false
                        }
                        return@withContext DomainResult.Failure(RestoreFailure.RolledBack)
                    }
                }
                retainRecoveryArtifacts = true
                progress.onProgress(RestoreProgress(RestoreState.VERIFYING_LIVE, materialized.logicalBytes, materialized.logicalBytes))
                when (val post = ledger.validateLive(bookId, exchanged.resultingHead)) {
                    is DomainResult.Success -> if (post.value.isValid) {
                        if (ledger.finalizeExchange(bookId, operationId) is DomainResult.Failure) {
                            return@withContext DomainResult.Failure(RestoreFailure.IntegrityFailed)
                        }
                        retainRecoveryArtifacts = false
                        progress.onProgress(RestoreProgress(RestoreState.COMPLETE, materialized.logicalBytes, materialized.logicalBytes))
                        DomainResult.Success(
                            ReplaceRestoreResult(exchanged.resultingHead, exchanged.retainedSafetySnapshotId, materialized.logicalBytes),
                        )
                    } else {
                        if (ledger.rollback(bookId, operationId, safetyId) is DomainResult.Success) {
                            retainRecoveryArtifacts = false
                        }
                        DomainResult.Failure(RestoreFailure.RolledBack)
                    }
                    is DomainResult.Failure -> {
                        if (ledger.rollback(bookId, operationId, safetyId) is DomainResult.Success) {
                            retainRecoveryArtifacts = false
                        }
                        DomainResult.Failure(RestoreFailure.RolledBack)
                    }
                }
            }
        } finally {
            if (!retainRecoveryArtifacts) ledger.cleanup(operationId)
            materialized.targetDirectory.deleteRecursivelyScoped()
        }
    }
}

data class MergeRestorePreview(
    val plan: ThreeWayMergePlan,
    val materialized: RestoreMaterializationResult,
    val safetySnapshotId: StableId,
)

interface MergeLedgerPort {
    suspend fun inspect(bookId: StableId, operationId: StableId, materialized: RestoreMaterializationResult): DomainResult<ThreeWayMergeInput>

    /** Financial entity changes are applied to a shadow ledger through FinancialMutationCoordinator. */
    suspend fun applyToShadow(
        bookId: StableId,
        operationId: StableId,
        plan: ThreeWayMergePlan,
        resolutions: Map<StableId, MergeResolution>,
    ): DomainResult<PreparedRestoreLedger>

    fun cleanup(operationId: StableId)
}

interface MergeSessionStore {
    fun save(bookId: StableId, operationId: StableId, plan: ThreeWayMergePlan): DomainResult<Unit>
    fun resolve(bookId: StableId, conflictId: StableId, resolution: MergeResolution): DomainResult<Unit>
    fun markApplying(bookId: StableId, operationId: StableId): DomainResult<Unit>
    fun markComplete(bookId: StableId, operationId: StableId, resultingCommit: BookCommitId): DomainResult<Unit>
}

private fun RestoreMaterializationResult.toApplicationPackage(
    expectedBookId: StableId,
    operationId: StableId,
): MaterializedRestorePackage {
    require(bookId == expectedBookId)
    val database = targetDirectory.resolve("database/ledger.db")
    val keyMaterial = targetDirectory.resolve("keys/portable-key-material.envelope")
    require(database.isFile && keyMaterial.isFile)
    return MaterializedRestorePackage(
        bookId,
        operationId,
        database.absolutePath,
        targetDirectory.resolve("settings").listFiles()?.singleOrNull()?.absolutePath,
        targetDirectory.resolve("attachments").takeIf(File::isDirectory)?.absolutePath,
        keyMaterial.absolutePath,
        targetDirectory.resolve("keys/vault-recovery.envelope").takeIf(File::isFile)?.absolutePath,
        databaseSchemaVersion,
        logicalBytes,
    )
}

class MergeRestoreCoordinator(
    private val materializer: RestoreMaterializer,
    private val safetySnapshots: PreRestoreSafetySnapshotPort,
    private val mergeLedger: MergeLedgerPort,
    private val restoreLedger: RestoreLedgerApplicationPort,
    private val sessions: MergeSessionStore,
) {
    suspend fun preview(
        request: ReplaceRestoreRequest,
        cancelled: () -> Boolean = { false },
        progress: RestoreProgressObserver = RestoreProgressObserver { },
    ): DomainResult<MergeRestorePreview> {
        val materialized = when (
            val result = materializer.materialize(
                request.source,
                request.password,
                request.target,
                request.bookId,
                cancelled,
                progress,
            )
        ) {
            is DomainResult.Failure -> return result
            is DomainResult.Success -> result.value
        }
        return previewMaterialized(request.bookId, request.operationId, materialized, cancelled)
    }

    suspend fun previewMaterialized(
        bookId: StableId,
        operationId: StableId,
        materialized: RestoreMaterializationResult,
        cancelled: () -> Boolean = { false },
    ): DomainResult<MergeRestorePreview> {
        if (cancelled()) {
            materialized.targetDirectory.deleteRecursivelyScoped()
            return DomainResult.Failure(RestoreFailure.Cancelled)
        }
        val safety = when (val result = safetySnapshots.create(bookId, operationId)) {
            is DomainResult.Failure -> {
                materialized.targetDirectory.deleteRecursivelyScoped()
                return result
            }
            is DomainResult.Success -> result.value
        }
        val input = when (val result = mergeLedger.inspect(bookId, operationId, materialized)) {
            is DomainResult.Failure -> {
                mergeLedger.cleanup(operationId)
                materialized.targetDirectory.deleteRecursivelyScoped()
                return result
            }
            is DomainResult.Success -> result.value
        }
        return when (val planned = CommitGraphMergePlanner.plan(input)) {
            is DomainResult.Failure -> {
                mergeLedger.cleanup(operationId)
                materialized.targetDirectory.deleteRecursivelyScoped()
                planned
            }
            is DomainResult.Success -> when (val saved = sessions.save(bookId, operationId, planned.value)) {
                is DomainResult.Failure -> {
                    materialized.targetDirectory.deleteRecursivelyScoped()
                    mergeLedger.cleanup(operationId)
                    saved
                }
                is DomainResult.Success -> DomainResult.Success(MergeRestorePreview(planned.value, materialized, safety))
            }
        }
    }

    suspend fun prepareResolved(
        bookId: StableId,
        operationId: StableId,
        preview: MergeRestorePreview,
        resolutions: Map<StableId, MergeResolution>,
    ): DomainResult<PreparedRestoreLedger> {
        val conflictIds = preview.plan.conflicts.map { it.id.value }.toSet()
        if (resolutions.keys != conflictIds) return DomainResult.Failure(RestoreFailure.ExplicitResolutionRequired)
        if (!resolutionsRespectPurge(preview.plan.conflicts, resolutions)) {
            return DomainResult.Failure(RestoreFailure.ExplicitResolutionRequired)
        }
        resolutions.forEach { (id, resolution) ->
            if (sessions.resolve(bookId, id, resolution) is DomainResult.Failure) {
                return DomainResult.Failure(RestoreFailure.IntegrityFailed)
            }
        }
        if (sessions.markApplying(bookId, operationId) is DomainResult.Failure) {
            return DomainResult.Failure(RestoreFailure.IntegrityFailed)
        }
        return mergeLedger.applyToShadow(bookId, operationId, preview.plan, resolutions)
    }

    suspend fun executeResolved(
        bookId: StableId,
        operationId: StableId,
        preview: MergeRestorePreview,
        resolutions: Map<StableId, MergeResolution>,
        progress: RestoreProgressObserver = RestoreProgressObserver { },
    ): DomainResult<ReplaceRestoreResult> {
        val prepared = when (val result = prepareResolved(bookId, operationId, preview, resolutions)) {
            is DomainResult.Failure -> return result
            is DomainResult.Success -> result.value
        }
        progress.onProgress(
            RestoreProgress(RestoreState.EXCHANGING, preview.materialized.logicalBytes, preview.materialized.logicalBytes),
        )
        return withContext(NonCancellable) {
            when (val exchanged = restoreLedger.exchange(prepared, preview.safetySnapshotId)) {
                is DomainResult.Failure -> rollbackAndCleanup(bookId, operationId, preview)
                is DomainResult.Success -> when (val validated = restoreLedger.validateLive(bookId, exchanged.value.resultingHead)) {
                    is DomainResult.Failure -> rollbackAndCleanup(bookId, operationId, preview)
                    is DomainResult.Success -> if (validated.value.isValid) {
                        progress.onProgress(
                            RestoreProgress(
                                RestoreState.COMPLETE,
                                preview.materialized.logicalBytes,
                                preview.materialized.logicalBytes,
                            ),
                        )
                        when (sessions.markComplete(bookId, operationId, exchanged.value.resultingHead)) {
                            is DomainResult.Failure -> rollbackAndCleanup(bookId, operationId, preview)
                            is DomainResult.Success -> {
                                if (restoreLedger.finalizeExchange(bookId, operationId) is DomainResult.Failure) {
                                    return@withContext rollbackAndCleanup(bookId, operationId, preview)
                                }
                                restoreLedger.cleanup(operationId)
                                mergeLedger.cleanup(operationId)
                                preview.materialized.targetDirectory.deleteRecursivelyScoped()
                                DomainResult.Success(
                                    ReplaceRestoreResult(
                                        exchanged.value.resultingHead,
                                        preview.safetySnapshotId,
                                        preview.materialized.logicalBytes,
                                    ),
                                )
                            }
                        }
                    } else {
                        rollbackAndCleanup(bookId, operationId, preview)
                    }
                }
            }
        }
    }

    private suspend fun rollbackAndCleanup(
        bookId: StableId,
        operationId: StableId,
        preview: MergeRestorePreview,
    ): DomainResult<ReplaceRestoreResult> = if (restoreLedger.rollback(bookId, operationId, preview.safetySnapshotId) is DomainResult.Success) {
        restoreLedger.cleanup(operationId)
        mergeLedger.cleanup(operationId)
        preview.materialized.targetDirectory.deleteRecursivelyScoped()
        DomainResult.Failure(RestoreFailure.RolledBack)
    } else {
        DomainResult.Failure(RestoreFailure.IntegrityFailed)
    }

    private fun resolutionsRespectPurge(
        conflicts: List<MergeConflict>,
        resolutions: Map<StableId, MergeResolution>,
    ): Boolean = conflicts.all { conflict ->
        conflict.purgeTombstone == null || resolutions[conflict.id.value] == MergeResolution.KeepPurgeTombstone
    }
}

private fun File.deleteRecursivelyScoped() {
    val parent = canonicalFile.parentFile ?: return
    require(canonicalFile.name.startsWith("restore-") && canonicalFile != parent)
    canonicalFile.walkBottomUp().forEach { file -> check(!file.exists() || file.delete()) }
}

internal fun ThreeWayMergePlan.selectedDecisions(resolutions: Map<StableId, MergeResolution>): List<MergeDecision> = automaticDecisions + conflicts.mapNotNull { conflict ->
    when (resolutions[conflict.id.value]) {
        MergeResolution.KeepLocal -> MergeDecision.KeepLocal(
            conflict.local?.let {
                app.ledger.finance.domain.StableEntityReference(it.entityType, it.entityId)
            } ?: conflict.ancestor?.let {
                app.ledger.finance.domain.StableEntityReference(it.entityType, it.entityId)
            } ?: return@mapNotNull null,
            conflict.local,
        )
        MergeResolution.KeepIncoming -> MergeDecision.KeepIncoming(
            conflict.incoming?.let {
                app.ledger.finance.domain.StableEntityReference(it.entityType, it.entityId)
            } ?: conflict.ancestor?.let {
                app.ledger.finance.domain.StableEntityReference(it.entityType, it.entityId)
            } ?: return@mapNotNull null,
            conflict.incoming,
        )
        MergeResolution.KeepPurgeTombstone -> conflict.purgeTombstone?.let {
            MergeDecision.KeepPurgeTombstone(it.entity, it)
        }
        null -> null
    }
}
