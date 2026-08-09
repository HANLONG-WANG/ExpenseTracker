package app.ledger.finance.application

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.TransactionFilter

data class LedgerExportCursor(
    val orderValue: Long,
    val stableTieBreaker: StableId,
)

data class LedgerExportPage(
    val headers: List<String>,
    val rows: List<List<String>>,
    val nextCursor: LedgerExportCursor?,
) {
    init {
        require(headers.isNotEmpty() && headers.distinct().size == headers.size)
        require(rows.all { it.size == headers.size })
    }
}

enum class LedgerWorkbookSheet(val worksheetName: String) {
    ACCOUNTS("accounts"),
    CARDS("cards"),
    CATEGORIES("categories"),
    MERCHANTS("merchants"),
    PLACES("places"),
    PROJECTS("projects"),
    SETTLEMENTS("settlements"),
    TRANSACTIONS("transactions"),
    CREDIT_STATEMENTS("credit_statements"),
    INSTALLMENTS("installments"),
    LOANS("loans"),
    BUDGETS("budgets"),
    GOALS("goals"),
    RECURRENCES("recurrences"),
    LOCATIONS("locations"),
}

data class LedgerExportBookMetadata(
    val localRevision: Long,
    val valuationRevision: Long,
    val schemaVersion: Int,
) {
    init {
        require(localRevision >= 0L && valuationRevision >= 0L && schemaVersion > 0)
    }
}

interface LedgerExportQueryPort {
    suspend fun metadata(bookId: StableId): DomainResult<LedgerExportBookMetadata>

    suspend fun currentTransactions(
        bookId: StableId,
        filter: TransactionFilter,
        headers: List<String>,
        cursor: LedgerExportCursor?,
        limit: Int,
    ): DomainResult<LedgerExportPage>

    suspend fun workbookSheet(
        bookId: StableId,
        sheet: LedgerWorkbookSheet,
        includeLocationCoordinates: Boolean,
        afterInternalId: Long,
        limit: Int,
    ): DomainResult<LedgerExportPage>
}
