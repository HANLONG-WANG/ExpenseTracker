@file:Suppress("MagicNumber")

package app.ledger.finance.application

import app.ledger.core.common.DomainError
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.Hash256
import java.time.Instant

data class ImportSourceRow(
    val sourceRowNumber: Long,
    val transactionId: StableId,
    val sourceRowHash: Hash256,
) {
    init {
        require(sourceRowNumber > 0L)
    }
}

data class ImportFinancialPage(
    val firstRowNumber: Long,
    val lastRowNumber: Long,
    val submitRequest: BatchEntrySubmitRequest,
    val sourceRows: List<ImportSourceRow>,
    val lastPage: Boolean,
) {
    init {
        require(firstRowNumber > 0L && lastRowNumber >= firstRowNumber)
        require(sourceRows.size == submitRequest.rows.size)
        require(sourceRows.map(ImportSourceRow::transactionId) == submitRequest.rows.map(BatchEntryRowWriteRequest::transactionId))
    }
}

fun interface ImportFinancialPageSource {
    suspend fun load(afterRowNumber: Long, maximumRows: Int): DomainResult<ImportFinancialPage?>
}

data class ImportCommitMetadata(
    val operationId: StableId,
    val importRecordId: StableId,
    val batchId: StableId,
    val formatCode: Int,
    val sourceFingerprint: Hash256,
    val totalRows: Long,
    val requestedAt: Instant,
) {
    init {
        require(formatCode >= 0)
        require(totalRows > 0L)
        require(setOf(operationId, importRecordId, batchId).size == 3)
    }
}

data class ImportFinancialCommitRequest(
    val bookId: StableId,
    val metadata: ImportCommitMetadata,
    val pages: ImportFinancialPageSource,
    val shadowThresholdRows: Long = 5_000L,
) {
    init {
        require(shadowThresholdRows > 0L)
    }
}

data class ImportFinancialCommitResult(
    val importedRows: Long,
    val pageCount: Int,
    val usedShadowLedger: Boolean,
    val replayed: Boolean,
)

data class ImportFinancialUndoRequest(
    val bookId: StableId,
    val batchId: StableId,
    val operationId: StableId,
    val requestedAt: Instant,
) {
    init {
        require(setOf(bookId, batchId, operationId).size == 3)
    }
}

data class ImportFinancialUndoResult(
    val reversedRows: Long,
    val reversedPageCount: Int,
    val replayed: Boolean,
)

data class ImportFinancialAuditView(
    val importRecordId: StableId,
    val batchId: StableId,
    val operationId: StableId,
    val importedAt: Instant,
    val importedRows: Long,
    val sourceFingerprint: Hash256,
    val reversed: Boolean,
)

data class ImportFinancialHistoryItem(
    val importRecordId: StableId,
    val batchId: StableId,
    val operationId: StableId,
    val importedAt: Instant,
    val importedRows: Long,
    val reversed: Boolean,
)

interface ImportFinancialApplicationPort {
    suspend fun commit(request: ImportFinancialCommitRequest): DomainResult<ImportFinancialCommitResult>
    suspend fun undo(request: ImportFinancialUndoRequest): DomainResult<ImportFinancialUndoResult>
    suspend fun audit(bookId: StableId, batchId: StableId): DomainResult<ImportFinancialAuditView?>
    suspend fun history(bookId: StableId): DomainResult<List<ImportFinancialHistoryItem>>
}

sealed interface ImportFinancialError : DomainError {
    data object EmptyPreparedSet : ImportFinancialError {
        override val code: String = "IMPORT_PREPARED_SET_EMPTY"
    }
    data object PageSequenceInvalid : ImportFinancialError {
        override val code: String = "IMPORT_PAGE_SEQUENCE_INVALID"
    }
    data object LiveHeadChanged : ImportFinancialError {
        override val code: String = "IMPORT_LIVE_HEAD_CHANGED"
    }
    data object ShadowValidationFailed : ImportFinancialError {
        override val code: String = "IMPORT_SHADOW_VALIDATION_FAILED"
    }
    data object AtomicExchangeUnavailable : ImportFinancialError {
        override val code: String = "IMPORT_ATOMIC_EXCHANGE_UNAVAILABLE"
    }
    data object NotFound : ImportFinancialError {
        override val code: String = "IMPORT_BATCH_NOT_FOUND"
    }
    data object AlreadyReversed : ImportFinancialError {
        override val code: String = "IMPORT_BATCH_ALREADY_REVERSED"
    }
}
