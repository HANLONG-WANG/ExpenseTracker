@file:Suppress("LongParameterList")

package app.ledger.finance.application

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.DependencyResolution
import java.time.Instant

/** Closed P24 row types. Each subtype keeps the same typed scalar write request used by its editor. */
public sealed interface BatchEntryRowWriteRequest {
    public val rowId: StableId
    public val transactionId: StableId

    public data class Ordinary(
        override val rowId: StableId,
        val request: OrdinaryTransactionWriteRequest,
    ) : BatchEntryRowWriteRequest {
        override val transactionId: StableId = request.ids.transactionId
    }

    public data class Refund(
        override val rowId: StableId,
        val request: RefundWriteRequest,
    ) : BatchEntryRowWriteRequest {
        override val transactionId: StableId = request.ids.transactionId
    }
}

public enum class BatchEntryField {
    CATEGORY,
    AMOUNT,
    ACCOUNT_AND_CARD,
    MERCHANT,
    DATE,
    PROJECT,
    ATTACHMENTS,
    SETTLEMENT,
    LOCATION,
    FX,
    INSTALLMENT,
    REFUND_RELATION,
    BATCH,
}

public enum class BatchValidationSeverity { ERROR, WARNING }

public data class BatchValidationIssue(
    val rowId: StableId?,
    val field: BatchEntryField,
    val code: String,
    val severity: BatchValidationSeverity,
) {
    init {
        require(code.matches(Regex("[A-Z0-9_]{2,80}")))
    }
}

public data class BatchValidationReport(
    val issues: List<BatchValidationIssue>,
) {
    public val errors: List<BatchValidationIssue> = issues.filter { it.severity == BatchValidationSeverity.ERROR }
    public val warnings: List<BatchValidationIssue> = issues.filter { it.severity == BatchValidationSeverity.WARNING }
    public val canCommit: Boolean = errors.isEmpty()
}

public data class BatchEntrySubmitRequest(
    val bookId: StableId,
    val commandId: CommandId,
    val commitId: StableId,
    val deviceInstanceId: StableId,
    val createdAt: Instant,
    val rows: List<BatchEntryRowWriteRequest>,
    val warningsConfirmed: Boolean,
) {
    init {
        require(rows.isNotEmpty())
        require(rows.map(BatchEntryRowWriteRequest::rowId).toSet().size == rows.size)
        require(rows.map(BatchEntryRowWriteRequest::transactionId).toSet().size == rows.size)
        require(
            rows.all { row ->
                when (row) {
                    is BatchEntryRowWriteRequest.Ordinary ->
                        row.request.ids.bookId == bookId &&
                            row.request.ids.commitId == commitId &&
                            row.request.ids.deviceInstanceId == deviceInstanceId &&
                            row.request.createdAt == createdAt &&
                            row.request.expectedRevisionId == null
                    is BatchEntryRowWriteRequest.Refund ->
                        row.request.ids.bookId == bookId &&
                            row.request.ids.commitId == commitId &&
                            row.request.ids.deviceInstanceId == deviceInstanceId &&
                            row.request.createdAt == createdAt
                }
            },
        )
    }
}

public data class BatchEntryCommitResult(
    val receipt: CommandReceipt,
    val transactionIds: List<StableId>,
)

public data class BatchAuditView(
    val batchCommandId: CommandId,
    val commitId: StableId,
    val createdAt: Instant,
    val transactionIds: List<StableId>,
    val fullyReversed: Boolean,
)

public data class BatchUndoRowIds(
    val transactionId: StableId,
    val revisionId: StableId,
    val factIds: List<StableId>,
    val dependencyResolutions: List<DependencyResolution> = emptyList(),
) {
    init {
        require(factIds.isNotEmpty())
    }
}

public data class BatchUndoRequest(
    val bookId: StableId,
    val originalBatchCommandId: CommandId,
    val commandId: CommandId,
    val commitId: StableId,
    val deviceInstanceId: StableId,
    val createdAt: Instant,
    val rows: List<BatchUndoRowIds>,
) {
    init {
        require(rows.isNotEmpty())
        require(rows.map(BatchUndoRowIds::transactionId).toSet().size == rows.size)
        val generated = rows.flatMap { listOf(it.revisionId) + it.factIds } + listOf(commandId.stableId, commitId, deviceInstanceId)
        require(generated.toSet().size == generated.size)
        require(commandId != originalBatchCommandId)
    }
}

/** The only application boundary for P24 validation, atomic submit, audit and legal reversal. */
public interface BatchEntryApplicationPort {
    public suspend fun validate(request: BatchEntrySubmitRequest): DomainResult<BatchValidationReport>
    public suspend fun submit(request: BatchEntrySubmitRequest): DomainResult<BatchEntryCommitResult>
    public suspend fun audit(bookId: StableId, batchCommandId: CommandId): DomainResult<BatchAuditView?>
    public suspend fun undo(request: BatchUndoRequest): DomainResult<CommandReceipt>
}
