package app.ledger.transfer.domain

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.PurgeTombstone
import app.ledger.finance.domain.StableEntityReference
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OperationAndMergeContractTest {
    @Test
    fun `operation follows durable transition graph and rejects jumps`() {
        val queued = BackgroundOperation.queued(
            id = BackgroundOperationId(id(1)),
            type = BackgroundOperationType.IMPORT,
            createdAt = Instant.EPOCH,
            parameters = OperationParameters.Import(id(2), ImportFormat.CSV, null),
        )
        val preparing = queued.transition(BackgroundOperationState.PREPARING, Instant.ofEpochSecond(1)).success()
        val running = preparing.transition(BackgroundOperationState.RUNNING, Instant.ofEpochSecond(2)).success()
        val committing = running.transition(BackgroundOperationState.COMMITTING, Instant.ofEpochSecond(3)).success()
        val succeeded = committing.transition(BackgroundOperationState.SUCCEEDED, Instant.ofEpochSecond(4)).success()

        succeeded.state shouldBe BackgroundOperationState.SUCCEEDED
        (queued.transition(BackgroundOperationState.SUCCEEDED, Instant.ofEpochSecond(1)) is DomainResult.Failure).shouldBeTrue()
    }

    @Test
    fun `worker launch token has only opaque operation id`() {
        OperationLaunchToken::class.java.declaredFields.count { !it.isSynthetic } shouldBe 1
        OperationLaunchToken::class.java.declaredFields.none { field ->
            field.name.contains("amount", true) ||
                field.name.contains("path", true) ||
                field.name.contains("note", true) ||
                field.name.contains("location", true)
        }.shouldBeTrue()
    }

    @Test
    fun `purge tombstone is the only merge resolution for purged entity`() {
        val transactionId = id(10)
        val tombstone = PurgeTombstone(
            entity = StableEntityReference(EntityType.TRANSACTION, transactionId),
            purgeCommitId = BookCommitId(id(11)),
            purgedAt = Instant.ofEpochSecond(10),
            purgeGeneration = 2L,
        )
        val conflict = MergeConflict(
            id = MergeConflictId(id(12)),
            sessionId = MergeSessionId(id(13)),
            kind = MergeConflictKind.PURGED_ENTITY,
            ancestor = null,
            local = null,
            incoming = null,
            purgeTombstone = tombstone,
            resolution = null,
        )

        (conflict.resolve(MergeResolution.KeepIncoming) is DomainResult.Failure).shouldBeTrue()
        conflict.resolve(MergeResolution.KeepPurgeTombstone).success().resolution shouldBe
            MergeResolution.KeepPurgeTombstone
    }

    @Test
    fun `shadow validation keeps every atomic exchange invariant explicit`() {
        val report = ShadowValidationReport(
            sqlCipherReadable = true,
            integrityCheckPassed = true,
            foreignKeyCheckPassed = true,
            journalsBalanced = true,
            projectionsAligned = true,
            subtypeDetailsComplete = true,
        )
        report.projectionsAligned.shouldBeTrue()
        report.copy(journalsBalanced = false).journalsBalanced.shouldBeFalse()
    }

    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0L, value))

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }
}
