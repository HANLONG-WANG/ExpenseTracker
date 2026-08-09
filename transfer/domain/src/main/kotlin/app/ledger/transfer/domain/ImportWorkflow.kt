@file:Suppress("MagicNumber")

package app.ledger.transfer.domain

import app.ledger.core.common.DomainError
import app.ledger.core.common.DomainResult
import java.io.InputStream
import java.time.Instant

/** The nine user-visible stages frozen by UI contract 12.25 (IMP-001..IMP-009). */
enum class ImportWizardStage {
    SOURCE,
    STRUCTURE,
    FIELD_MAPPING,
    ENTITY_MAPPING,
    FX,
    VALIDATION,
    CONFIRMATION,
    EXECUTION,
    RESULT,
}

enum class StructuredEntityKind(val canonicalSheetName: String, val dependencyOrder: Int) {
    ACCOUNT("accounts", 0),
    CARD("cards", 1),
    CATEGORY("categories", 2),
    MERCHANT("merchants", 3),
    PLACE("places", 4),
    GOAL("goals", 5),
    PROJECT("projects", 6),
    SETTLEMENT_ACTIVITY("settlement_activities", 7),
    LOCATION("locations", 8),
    RECURRENCE("recurrences", 9),
    TRANSACTION("transactions", 10),
    CREDIT_STATEMENT("credit_statements", 11),
    INSTALLMENT("installments", 12),
    LOAN("loans", 13),
    BUDGET("budgets", 14),
    ;

    companion object {
        fun fromSheetName(value: String): StructuredEntityKind? = entries.singleOrNull {
            it.canonicalSheetName.equals(value.trim(), ignoreCase = true)
        }
    }
}

enum class ImportCellKind { EMPTY, TEXT, INTEGER, DECIMAL, DATE, INSTANT, BOOLEAN, ERROR }

data class ImportCell(
    val columnIndex: Int,
    val columnName: String,
    val kind: ImportCellKind,
    val canonicalValue: String?,
    val formula: String? = null,
) {
    init {
        require(columnIndex >= 0)
        require(columnName.isNotBlank())
        require(canonicalValue != null || kind == ImportCellKind.EMPTY || kind == ImportCellKind.ERROR)
    }
}

data class ImportStreamRow(
    val sheetName: String,
    val rowNumber: Long,
    val cells: List<ImportCell>,
) {
    init {
        require(sheetName.isNotBlank())
        require(rowNumber > 0L)
        require(cells.map(ImportCell::columnIndex).toSet().size == cells.size)
    }
}

data class ImportSheet(
    val name: String,
    val index: Int,
    val structuredKind: StructuredEntityKind?,
) {
    init {
        require(name.isNotBlank())
        require(index >= 0)
    }
}

data class ImportReadSummary(
    val sheets: List<ImportSheet>,
    val rowCount: Long,
    val peakBufferedRows: Int,
    val selectedCharset: String?,
    val bomCharset: String?,
) {
    init {
        require(rowCount >= 0L)
        require(peakBufferedRows in 0..MAX_STREAM_BUFFER_ROWS)
        require(sheets.map(ImportSheet::index).toSet().size == sheets.size)
    }

    private companion object {
        const val MAX_STREAM_BUFFER_ROWS: Int = 512
    }
}

fun interface ImportInput {
    fun open(): InputStream
}

fun interface ImportCancellationSignal {
    fun isCancellationRequested(): Boolean

    companion object {
        val NEVER: ImportCancellationSignal = ImportCancellationSignal { false }
    }
}

fun interface ImportRowConsumer {
    suspend fun accept(row: ImportStreamRow)
}

data class ImportReadRequest(
    val input: ImportInput,
    val selectedSheetNames: Set<String>? = null,
    val headerRowNumber: Long = 1L,
    val userCharset: String? = null,
    val cancellation: ImportCancellationSignal = ImportCancellationSignal.NEVER,
) {
    init {
        require(headerRowNumber > 0L)
        require(selectedSheetNames == null || selectedSheetNames.none(String::isBlank))
        require(userCharset == null || userCharset.isNotBlank())
    }
}

fun interface ImportStreamReader {
    suspend fun read(request: ImportReadRequest, consumer: ImportRowConsumer): DomainResult<ImportReadSummary>
}

enum class ImportValidationSeverity { ERROR, WARNING }

data class ImportValidationIssue(
    val rowNumber: Long?,
    val field: ImportTargetField?,
    val code: String,
    val severity: ImportValidationSeverity,
) {
    init {
        require(rowNumber == null || rowNumber > 0L)
        require(code.matches(Regex("[A-Z0-9_]{2,80}")))
    }
}

data class ImportValidationReport(
    val issues: List<ImportValidationIssue>,
    val totalErrorCount: Long = issues.count { it.severity == ImportValidationSeverity.ERROR }.toLong(),
    val totalWarningCount: Long = issues.count { it.severity == ImportValidationSeverity.WARNING }.toLong(),
) {
    init {
        require(totalErrorCount >= issues.count { it.severity == ImportValidationSeverity.ERROR })
        require(totalWarningCount >= issues.count { it.severity == ImportValidationSeverity.WARNING })
    }
    val errors: List<ImportValidationIssue> = issues.filter { it.severity == ImportValidationSeverity.ERROR }
    val warnings: List<ImportValidationIssue> = issues.filter { it.severity == ImportValidationSeverity.WARNING }
    val canCommit: Boolean = totalErrorCount == 0L
}

data class ImportProgressSnapshot(
    val stage: ImportWizardStage,
    val phaseCode: String,
    val current: Long,
    val total: Long?,
    val pauseAllowed: Boolean,
    val cancelAllowed: Boolean,
    val safeBoundaryDescriptionCode: String,
) {
    init {
        require(phaseCode.matches(Regex("[A-Z0-9_]{2,80}")))
        require(current >= 0L)
        require(total == null || total >= current)
        require(safeBoundaryDescriptionCode.matches(Regex("[A-Z0-9_]{2,80}")))
    }
}

data class ImportBatchAudit(
    val operationId: BackgroundOperationId,
    val sourceFingerprint: String,
    val importedRows: Long,
    val committedAt: Instant,
    val reversible: Boolean,
    val reversedAt: Instant?,
) {
    init {
        require(sourceFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(importedRows > 0L)
        require((reversedAt != null) != reversible)
    }
}

/** Closed failures surfaced by CSV/XLSX readers and the import workflow. */
sealed interface ImportFailure : DomainError {
    data object Cancelled : ImportFailure {
        override val code: String = "IMPORT_CANCELLED"
    }
    data object CorruptSource : ImportFailure {
        override val code: String = "IMPORT_CORRUPT_SOURCE"
    }
    data object UnsupportedSource : ImportFailure {
        override val code: String = "IMPORT_UNSUPPORTED_SOURCE"
    }
    data object InvalidEncoding : ImportFailure {
        override val code: String = "IMPORT_INVALID_ENCODING"
    }
    data object MissingRequiredMapping : ImportFailure {
        override val code: String = "IMPORT_MISSING_REQUIRED_MAPPING"
    }
    data object ValidationFailed : ImportFailure {
        override val code: String = "IMPORT_VALIDATION_FAILED"
    }
    data object LiveLedgerChanged : ImportFailure {
        override val code: String = "IMPORT_LIVE_LEDGER_CHANGED"
    }
    data object IntegrityFailed : ImportFailure {
        override val code: String = "IMPORT_INTEGRITY_FAILED"
    }
    data object DuplicateSource : ImportFailure {
        override val code: String = "IMPORT_DUPLICATE_SOURCE"
    }
    data object SeparateTransactionsRequired : ImportFailure {
        override val code: String = "IMPORT_SEPARATE_TRANSACTIONS_REQUIRED"
    }
}

object ImportBoundaryPolicy {
    /** Split categories/payers stay outside the product model: a source row must be expanded before preparation. */
    fun validateSingleTransaction(rowNumber: Long, categoryCount: Int, payerCount: Int): ImportValidationReport {
        require(rowNumber > 0L)
        require(categoryCount >= 0)
        require(payerCount >= 0)
        return if (categoryCount <= 1 && payerCount <= 1) {
            ImportValidationReport(emptyList())
        } else {
            ImportValidationReport(
                listOf(
                    ImportValidationIssue(
                        rowNumber,
                        null,
                        ImportFailure.SeparateTransactionsRequired.code,
                        ImportValidationSeverity.ERROR,
                    ),
                ),
            )
        }
    }
}
