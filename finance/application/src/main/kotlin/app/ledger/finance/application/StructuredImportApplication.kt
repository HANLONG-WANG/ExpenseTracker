package app.ledger.finance.application

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId

/** Canonical structured-workbook entities. Attachments are intentionally outside this format. */
enum class StructuredImportEntityType {
    ACCOUNT,
    CARD,
    CATEGORY,
    MERCHANT,
    PLACE,
    GOAL,
    PROJECT,
    SETTLEMENT_ACTIVITY,
    LOCATION,
    RECURRENCE,
    TRANSACTION,
    CREDIT_STATEMENT,
    INSTALLMENT,
    LOAN,
    BUDGET,
}

class StructuredImportValues(values: Map<String, String>) {
    private val stored: Map<String, String> = values.toMap()
    val entries: Map<String, String> get() = stored

    init {
        require(stored.isNotEmpty())
        require(stored.keys.none(String::isBlank))
    }

    override fun toString(): String = "StructuredImportValues(redacted,count=${stored.size})"
}

data class StructuredImportRow(
    val sourceRowNumber: Long,
    val entityType: StructuredImportEntityType,
    val values: StructuredImportValues,
) {
    init {
        require(sourceRowNumber > 0L)
        require(entityType != StructuredImportEntityType.TRANSACTION)
    }
}

fun interface StructuredImportPageSource {
    suspend fun load(
        phase: StructuredImportPhase,
        afterRowOrdinal: Long,
        maximumRows: Int,
    ): DomainResult<List<StructuredImportRow>>
}

enum class StructuredImportPhase { BEFORE_TRANSACTIONS, AFTER_TRANSACTIONS }

data class StructuredImportCommitRequest(
    val bookId: StableId,
    val metadata: ImportCommitMetadata,
    val entityRows: StructuredImportPageSource,
    val transactionPages: ImportFinancialPageSource?,
    val transactionRowCount: Long,
    val entityRowsContributeToTotal: Boolean = true,
) {
    init {
        require(transactionRowCount >= 0L)
        require(transactionRowCount <= metadata.totalRows)
        require((transactionPages == null) == (transactionRowCount == 0L))
    }
}

interface StructuredImportApplicationPort {
    suspend fun commit(request: StructuredImportCommitRequest): DomainResult<ImportFinancialCommitResult>
    suspend fun undo(request: ImportFinancialUndoRequest): DomainResult<ImportFinancialUndoResult>
}
