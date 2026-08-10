package app.ledger.finance.application

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.CommandReceipt
import java.time.Instant

/** Identifiers are supplied by the application layer; the repository still rechecks every fact. */
data class ControlledPurgeRequest(
    val bookId: StableId,
    val commandId: StableId,
    val transactionId: StableId,
    val expectedRevisionId: StableId,
    val purgeCommitId: StableId,
    val deviceInstanceId: StableId,
    val evaluatedAt: Instant,
) {
    init {
        require(
            listOf(bookId, commandId, transactionId, expectedRevisionId, purgeCommitId, deviceInstanceId)
                .distinct().size == PURGE_IDENTITY_COUNT,
        )
    }

    private companion object {
        const val PURGE_IDENTITY_COUNT = 6
    }
}

data class ControlledPurgeResult(
    val receipt: CommandReceipt,
    val detachedAttachmentCount: Int,
    val queuedBlobGcCount: Int,
)

interface ControlledPurgeApplicationPort {
    suspend fun assess(bookId: StableId, transactionId: StableId, now: Instant): DomainResult<JournalPurgeAssessment>
    suspend fun purge(request: ControlledPurgeRequest): DomainResult<ControlledPurgeResult>
}
