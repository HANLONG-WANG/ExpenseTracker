@file:Suppress("ComplexCondition", "LongParameterList", "MagicNumber", "MaxLineLength")

package app.ledger.feature.record

import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.MoneyExpressionEvaluator
import app.ledger.finance.application.BatchEntryField
import app.ledger.finance.application.BatchValidationIssue
import app.ledger.finance.application.BatchValidationReport
import app.ledger.finance.application.BatchValidationSeverity
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinarySettlementShareDraft
import app.ledger.finance.application.OrdinaryTransactionEntrySnapshot
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

public enum class BatchRowKind { EXPENSE, INCOME, REFUND }

public enum class BatchRecordPresentation {
    EDITING,
    VALIDATING,
    ERRORS,
    READY_TO_COMMIT,
    COMMITTING,
    COMMITTED,
    COMMIT_FAILED,
}

public enum class BatchSort { ORIGINAL, DATE_ASCENDING, DATE_DESCENDING, AMOUNT_ASCENDING, AMOUNT_DESCENDING }

/** Sensitive P24 row content exists only in the ViewModel's in-memory state. */
public data class BatchRowDraft(
    val rowId: StableId,
    val kind: BatchRowKind,
    val categoryId: StableId?,
    val amountExpression: String,
    val userMinor: Long?,
    val userCurrencyCode: String,
    val accountMinor: Long?,
    val baseMinor: Long?,
    val accountId: StableId?,
    val cardId: StableId?,
    val merchantId: StableId?,
    val occurredAt: Instant,
    val zoneId: ZoneId,
    val projectId: StableId?,
    val attachmentIds: List<StableId>,
    val settlementActivityId: StableId?,
    val settlementShares: List<OrdinarySettlementShareDraft>,
    val locationRecordId: StableId?,
    val installmentPlanId: StableId?,
    val refundOriginalTransactionId: StableId?,
    val note: String,
    val occurredAtInput: String? = null,
) {
    init {
        require(attachmentIds.toSet().size == attachmentIds.size)
        require(kind != BatchRowKind.INCOME || settlementShares.isEmpty())
    }
}

public data class BatchRowSummary(
    val rowId: StableId,
    val category: String,
    val amount: String,
    val accountAndCard: String,
    val merchant: String,
    val date: String,
    val project: String,
    val complexFieldCount: Int,
    val errorCount: Int,
    val warningCount: Int,
)

public data class BatchRecordState(
    val snapshot: OrdinaryTransactionEntrySnapshot,
    val rows: List<BatchRowDraft>,
    val presentation: BatchRecordPresentation = BatchRecordPresentation.EDITING,
    val validation: BatchValidationReport = BatchValidationReport(emptyList()),
    val editingRowId: StableId? = null,
    val warningsConfirmed: Boolean = false,
    val committedBatchCommandId: StableId? = null,
    val sanitizedFailureCode: String? = null,
    val showDiscardConfirmation: Boolean = false,
) {
    init {
        require(rows.map(BatchRowDraft::rowId).toSet().size == rows.size)
        require(editingRowId == null || rows.any { it.rowId == editingRowId })
    }

    public val selectedRow: BatchRowDraft? get() = rows.singleOrNull { it.rowId == editingRowId }
}

public data class BatchPasteResult(
    val rows: List<BatchRowDraft>,
    val rejectedLineNumbers: List<Int>,
)

public object BatchRecordPolicy {
    private val currencyCatalog = JvmLegalTenderCurrencyCatalog.create()
    private val expressionEvaluator = MoneyExpressionEvaluator()

    public fun changeAmount(
        row: BatchRowDraft,
        expression: String,
        locale: Locale,
        snapshot: OrdinaryTransactionEntrySnapshot,
    ): BatchRowDraft {
        val currency = CurrencyCode.parse(row.userCurrencyCode).getOrNull()
        val metadata = currency?.let(currencyCatalog::find)
        val result = metadata?.let { expressionEvaluator.evaluate(expression, locale, it).getOrNull() }
        val minor = result?.roundedMoney?.minor
        val accountCurrency = snapshot.references.accounts.singleOrNull { it.id == row.accountId }?.currency
        return row.copy(
            amountExpression = expression,
            userMinor = minor,
            accountMinor = if (accountCurrency == currency) minor else row.accountMinor,
            baseMinor = if (snapshot.baseCurrency == currency) minor else row.baseMinor,
        )
    }

    public fun parseMajorAmount(expression: String, currency: CurrencyCode, locale: Locale): Long? =
        currencyCatalog.find(currency)?.let { expressionEvaluator.evaluate(expression, locale, it).getOrNull()?.roundedMoney?.minor }

    public fun changeKind(row: BatchRowDraft, kind: BatchRowKind): BatchRowDraft = row.copy(
        kind = kind,
        settlementActivityId = row.settlementActivityId.takeUnless { kind == BatchRowKind.INCOME },
        settlementShares = row.settlementShares.takeUnless { kind == BatchRowKind.INCOME }.orEmpty(),
        installmentPlanId = row.installmentPlanId.takeIf { kind == BatchRowKind.EXPENSE },
        refundOriginalTransactionId = row.refundOriginalTransactionId.takeIf { kind == BatchRowKind.REFUND },
    )

    public fun newRow(
        rowId: StableId,
        snapshot: OrdinaryTransactionEntrySnapshot,
        now: Instant,
        zoneId: ZoneId,
        kind: BatchRowKind = BatchRowKind.EXPENSE,
    ): BatchRowDraft {
        val direction = if (kind == BatchRowKind.INCOME) OrdinaryDirection.INCOME else OrdinaryDirection.EXPENSE
        val category = snapshot.references.categories.firstOrNull { it.direction.name == direction.name && it.status.name == "ACTIVE" }
        val account = snapshot.references.accounts.firstOrNull { it.status.name == "ACTIVE" }
        return BatchRowDraft(
            rowId,
            kind,
            category?.id,
            "",
            null,
            account?.currency?.value ?: snapshot.references.baseCurrency.value,
            null,
            null,
            account?.id,
            null,
            null,
            now,
            zoneId,
            null,
            emptyList(),
            null,
            emptyList(),
            null,
            null,
            null,
            "",
        )
    }

    public fun copyRow(source: BatchRowDraft, newId: StableId): BatchRowDraft = source.copy(rowId = newId)

    public fun insertAfter(rows: List<BatchRowDraft>, afterId: StableId?, row: BatchRowDraft): List<BatchRowDraft> {
        if (afterId == null) return listOf(row) + rows
        val index = rows.indexOfFirst { it.rowId == afterId }
        require(index >= 0)
        return rows.toMutableList().apply { add(index + 1, row) }
    }

    public fun move(rows: List<BatchRowDraft>, rowId: StableId, targetIndex: Int): List<BatchRowDraft> {
        require(targetIndex in rows.indices)
        val current = rows.indexOfFirst { it.rowId == rowId }
        require(current >= 0)
        return rows.toMutableList().apply { add(targetIndex, removeAt(current)) }
    }

    public fun sort(rows: List<BatchRowDraft>, order: BatchSort): List<BatchRowDraft> = when (order) {
        BatchSort.ORIGINAL -> rows
        BatchSort.DATE_ASCENDING -> rows.sortedWith(compareBy(BatchRowDraft::occurredAt, BatchRowDraft::rowId))
        BatchSort.DATE_DESCENDING -> rows.sortedWith(compareByDescending(BatchRowDraft::occurredAt).thenBy(BatchRowDraft::rowId))
        BatchSort.AMOUNT_ASCENDING -> rows.sortedWith(compareBy<BatchRowDraft> { it.userMinor ?: Long.MAX_VALUE }.thenBy(BatchRowDraft::rowId))
        BatchSort.AMOUNT_DESCENDING -> rows.sortedWith(compareByDescending<BatchRowDraft> { it.userMinor ?: Long.MIN_VALUE }.thenBy(BatchRowDraft::rowId))
    }

    public fun validateRows(rows: List<BatchRowDraft>, snapshot: OrdinaryTransactionEntrySnapshot): BatchValidationReport {
        val issues = buildList {
            if (rows.isEmpty()) add(issue(null, BatchEntryField.BATCH, "BATCH_EMPTY"))
            rows.forEach { row ->
                val direction = if (row.kind == BatchRowKind.INCOME) "INCOME" else "EXPENSE"
                if (snapshot.references.categories.none { it.id == row.categoryId && it.status.name == "ACTIVE" && it.direction.name == direction }) {
                    add(issue(row.rowId, BatchEntryField.CATEGORY, "CATEGORY_REQUIRED"))
                }
                if (row.userMinor == null || row.userMinor <= 0L || row.accountMinor == null || row.accountMinor <= 0L || row.baseMinor == null || row.baseMinor <= 0L) {
                    add(issue(row.rowId, BatchEntryField.AMOUNT, "AMOUNT_INVALID"))
                }
                if (row.occurredAtInput != null && parseOccurredAt(row.occurredAtInput, row.zoneId) == null) {
                    add(issue(row.rowId, BatchEntryField.DATE, "DATE_INVALID"))
                }
                val account = snapshot.references.accounts.singleOrNull { it.id == row.accountId && it.status.name == "ACTIVE" }
                if (account == null) add(issue(row.rowId, BatchEntryField.ACCOUNT_AND_CARD, "ACCOUNT_REQUIRED"))
                if (row.cardId != null && snapshot.references.cards.none { it.id == row.cardId && it.accountId == row.accountId && it.status.name == "ACTIVE" }) {
                    add(issue(row.rowId, BatchEntryField.ACCOUNT_AND_CARD, "CARD_INCOMPATIBLE"))
                }
                if ((row.settlementActivityId == null) != row.settlementShares.isEmpty()) {
                    add(issue(row.rowId, BatchEntryField.SETTLEMENT, "SETTLEMENT_INCOMPLETE"))
                }
                if (row.kind == BatchRowKind.REFUND && row.refundOriginalTransactionId == null && row.categoryId == null) {
                    add(issue(row.rowId, BatchEntryField.REFUND_RELATION, "INDEPENDENT_REFUND_CATEGORY_REQUIRED"))
                }
                if (row.installmentPlanId != null && row.kind != BatchRowKind.EXPENSE) {
                    add(issue(row.rowId, BatchEntryField.INSTALLMENT, "INSTALLMENT_EXPENSE_ONLY"))
                }
                if (row.note.length > MAX_NOTE_LENGTH) add(issue(row.rowId, BatchEntryField.BATCH, "NOTE_TOO_LONG"))
                if (row.attachmentIds.size > ATTACHMENT_WARNING_COUNT) {
                    add(issue(row.rowId, BatchEntryField.ATTACHMENTS, "MANY_ATTACHMENTS", BatchValidationSeverity.WARNING))
                }
            }
        }
        return BatchValidationReport(issues)
    }

    /**
     * Phone paste format is TSV: kind, category name, major-unit amount, account name, merchant name,
     * ISO instant, project name. Names are resolved only against the already encrypted snapshot.
     */
    public fun paste(
        text: String,
        snapshot: OrdinaryTransactionEntrySnapshot,
        idAtLine: (Int) -> StableId,
        defaultInstant: Instant,
        zoneId: ZoneId,
    ): BatchPasteResult {
        val rows = mutableListOf<BatchRowDraft>()
        val rejected = mutableListOf<Int>()
        text.lineSequence().filter(String::isNotBlank).take(MAX_PASTE_ROWS + 1).forEachIndexed { index, line ->
            if (index >= MAX_PASTE_ROWS) {
                rejected += index + 1
                return@forEachIndexed
            }
            val cells = line.split('\t')
            val kind = when (cells.getOrNull(0)?.trim()?.uppercase()) {
                "EXPENSE", "支出" -> BatchRowKind.EXPENSE
                "INCOME", "收入", "収入" -> BatchRowKind.INCOME
                "REFUND", "退款", "返金" -> BatchRowKind.REFUND
                else -> null
            }
            val category = snapshot.references.categories.singleOrNull { it.name == cells.getOrNull(1)?.trim() }
            val account = snapshot.references.accounts.singleOrNull { it.name == cells.getOrNull(3)?.trim() && it.status.name == "ACTIVE" }
            val amountExpression = cells.getOrNull(2)?.trim().orEmpty()
            val minor = account?.currency?.let { parseMajorAmount(amountExpression, it, Locale.ROOT) }
            if (kind == null || minor == null || minor <= 0L || category == null || account == null) {
                rejected += index + 1
            } else {
                val merchant = snapshot.references.merchants.singleOrNull { it.name == cells.getOrNull(4)?.trim() }
                val project = snapshot.projects.singleOrNull { it.name == cells.getOrNull(6)?.trim() && it.active }
                rows += newRow(idAtLine(index), snapshot, occurredAt, zoneId, kind).copy(
                    categoryId = category.id,
                    amountExpression = amountExpression,
                    userMinor = minor,
                    accountMinor = minor,
                    baseMinor = minor.takeIf { account.currency == snapshot.references.baseCurrency },
                    accountId = account.id,
                    userCurrencyCode = account.currency.value,
                    merchantId = merchant?.id,
                    projectId = project?.id,
                    occurredAtInput = dateTimeText.takeIf(String::isNotEmpty),
                )
            }
        }
        return BatchPasteResult(rows, rejected)
    }

    public fun mergeValidation(local: BatchValidationReport, authoritative: BatchValidationReport): BatchValidationReport = BatchValidationReport((local.issues + authoritative.issues).distinct())

    public fun majorToMinor(value: String, currencyCode: String): Long? = runCatching {
        val currency = CurrencyCode.parse(currencyCode).getOrNull() ?: return null
        val fractionDigits = currencies.find(currency)?.fractionDigits ?: return null
        BigDecimal(value.trim().replace(',', '.'))
            .movePointRight(fractionDigits)
            .setScale(0, RoundingMode.UNNECESSARY)
            .longValueExact()
            .takeIf { it > 0L }
    }.getOrNull()

    public fun minorToMajor(value: Long?, currencyCode: String): String = value?.let { minor ->
        val currency = CurrencyCode.parse(currencyCode).getOrNull() ?: return@let minor.toString()
        val fractionDigits = currencies.find(currency)?.fractionDigits ?: return@let minor.toString()
        BigDecimal.valueOf(minor, fractionDigits).stripTrailingZeros().toPlainString()
    }.orEmpty()

    public fun parseOccurredAt(value: String, zoneId: ZoneId): Instant? = runCatching {
        Instant.parse(value)
    }.getOrNull() ?: runCatching {
        LocalDateTime.parse(value.trim(), LOCAL_DATE_TIME).atZone(zoneId).toInstant()
    }.getOrNull()

    public fun occurredAtText(value: Instant, zoneId: ZoneId, locale: Locale): String =
        LOCAL_DATE_TIME.withLocale(locale).format(value.atZone(zoneId))

    private fun issue(
        rowId: StableId?,
        field: BatchEntryField,
        code: String,
        severity: BatchValidationSeverity = BatchValidationSeverity.ERROR,
    ): BatchValidationIssue = BatchValidationIssue(rowId, field, code, severity)

    private const val MAX_NOTE_LENGTH = 2_000
    private const val ATTACHMENT_WARNING_COUNT = 8
    private const val MAX_PASTE_ROWS = 10_000
    private val LOCAL_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
}
