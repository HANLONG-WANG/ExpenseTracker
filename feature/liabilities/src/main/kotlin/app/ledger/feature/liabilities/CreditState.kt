package app.ledger.feature.liabilities

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.AmountSemantic
import app.ledger.core.money.AmountVisibility
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.LocaleCurrencyFormatter
import app.ledger.core.money.Money
import app.ledger.core.money.MoneyFormatRequest
import app.ledger.core.money.MoneyUiModel
import app.ledger.finance.application.CreditAccountView
import app.ledger.finance.application.CreditSnapshot
import app.ledger.finance.application.CreditStatementView
import app.ledger.finance.domain.AutoGenerationMode
import app.ledger.finance.domain.CreditStatementStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale

public enum class CreditPresentation {
    NORMAL,
    OVERDUE,
    POSITIVE_BALANCE,
    NO_LIMIT,
    NO_STATEMENTS,
    EDITING,
    VALIDATION_ERROR,
    CONTENT,
    EMPTY,
    ESTIMATED_ONLY,
    OFFICIAL,
    SEALED,
    PAID,
    DIFFERENCE,
    SAVING,
    SEALED_WARNING,
    BALANCED,
    MISMATCH,
    ELIGIBLE,
    INELIGIBLE,
    CANDIDATE_MODE,
    OVERPAYMENT_BLOCKED,
    UNALLOCATED,
}

public data class CreditDraft(
    val statementDay: String = "",
    val dueDay: String = "",
    val zoneId: String = "Asia/Tokyo",
    val standardLimit: String = "",
    val temporaryLimit: String = "",
    val temporaryExpires: String = "",
    val amount: String = "",
    val officialAmount: String = "",
    val date: String = "",
    val selectedPaymentAccountId: StableId? = null,
    val selectedStatementId: StableId? = null,
    val allocationMode: CreditAllocationMode = CreditAllocationMode.EARLIEST_UNPAID,
    val autoPaymentMode: AutoGenerationMode = AutoGenerationMode.CONFIRMATION_CANDIDATE,
)

public data class CreditFeatureState(
    val snapshot: CreditSnapshot,
    val selectedAccountId: StableId?,
    val selectedStatementId: StableId?,
    val presentation: CreditPresentation,
    val draft: CreditDraft,
    val validationFields: Set<String> = emptySet(),
    val failureCode: String? = null,
) {
    public val account: CreditAccountView?
        get() = snapshot.accounts.singleOrNull { it.id == selectedAccountId } ?: snapshot.accounts.firstOrNull()

    public val statement: CreditStatementView?
        get() = account?.statements?.singleOrNull { it.id == selectedStatementId } ?: account?.statements?.firstOrNull()
}

public sealed interface CreditLoadState {
    public data object Loading : CreditLoadState
    public data class Content(val state: CreditFeatureState) : CreditLoadState
    public data class Failure(val code: String) : CreditLoadState
}

public object CreditPolicy {
    private val formatter = LocaleCurrencyFormatter(JvmLegalTenderCurrencyCatalog.create())
    private val catalog = JvmLegalTenderCurrencyCatalog.create()

    public fun create(snapshot: CreditSnapshot, screenId: String, accountId: StableId?, statementId: StableId?): CreditFeatureState {
        val account = snapshot.accounts.singleOrNull { it.id == accountId } ?: snapshot.accounts.firstOrNull()
        val statement = account?.statements?.singleOrNull { it.id == statementId } ?: account?.statements?.firstOrNull()
        val presentation = when (screenId) {
            "REC-014" -> CreditPresentation.EDITING
            "CRD-001" -> accountPresentation(account)
            "CRD-002" -> CreditPresentation.EDITING
            "CRD-003" -> if (account?.statements.isNullOrEmpty()) CreditPresentation.EMPTY else CreditPresentation.CONTENT
            "CRD-004" -> statementPresentation(statement)
            "CRD-005" -> if (statement?.differenceMinor != null) CreditPresentation.DIFFERENCE else CreditPresentation.EDITING
            "CRD-006" -> if (statement?.sealed == true) CreditPresentation.SEALED_WARNING else CreditPresentation.CONTENT
            "CRD-007" -> CreditPresentation.EDITING
            "CRD-008" -> autoPresentation(account, statement, account?.profile?.autoPaymentMode ?: AutoGenerationMode.CONFIRMATION_CANDIDATE)
            else -> CreditPresentation.CONTENT
        }
        val profile = account?.profile
        return CreditFeatureState(
            snapshot,
            account?.id,
            statement?.id,
            presentation,
            CreditDraft(
                statementDay = (profile?.statementRule as? app.ledger.finance.domain.StatementDateRule.DayOfMonth)?.day?.toString().orEmpty(),
                dueDay = when (val rule = profile?.dueRule) {
                    is app.ledger.finance.domain.DueDateRule.FixedDay -> rule.day.toString()
                    is app.ledger.finance.domain.DueDateRule.DaysAfterStatement -> rule.days.toString()
                    null -> ""
                },
                zoneId = profile?.statementZoneId?.id ?: "Asia/Tokyo",
                standardLimit = profile?.standardLimitMinor?.let { minor -> minorText(minor, account.currency) }.orEmpty(),
                temporaryLimit = profile?.temporaryLimitMinor?.let { minor -> minorText(minor, account.currency) }.orEmpty(),
                temporaryExpires = profile?.temporaryLimitExpiresOn?.toString().orEmpty(),
                amount = statement?.remainingAmountMinor?.takeIf { it > 0L }?.let { minor -> account?.let { minorText(minor, it.currency) } }.orEmpty(),
                officialAmount = statement?.officialAmountMinor?.let { minor -> account?.let { minorText(minor, it.currency) } }.orEmpty(),
                selectedPaymentAccountId = profile?.defaultPaymentAccountId,
                selectedStatementId = statement?.id,
                allocationMode = if (screenId in setOf("REC-014", "CRD-007")) CreditAllocationMode.EARLIEST_UNPAID else CreditAllocationMode.SPECIFIC,
                autoPaymentMode = profile?.autoPaymentMode ?: AutoGenerationMode.CONFIRMATION_CANDIDATE,
                date = statement?.dueDate?.toString().orEmpty(),
            ),
        )
    }

    public fun updateDraft(state: CreditFeatureState, field: CreditField, value: String): CreditFeatureState {
        val draft = when (field) {
            CreditField.STATEMENT_DAY -> state.draft.copy(statementDay = value.take(MAX_SHORT))
            CreditField.DUE_DAY -> state.draft.copy(dueDay = value.take(MAX_SHORT))
            CreditField.ZONE -> state.draft.copy(zoneId = value.take(MAX_ZONE))
            CreditField.STANDARD_LIMIT -> state.draft.copy(standardLimit = value.take(MAX_AMOUNT))
            CreditField.TEMPORARY_LIMIT -> state.draft.copy(temporaryLimit = value.take(MAX_AMOUNT))
            CreditField.TEMPORARY_EXPIRY -> state.draft.copy(temporaryExpires = value.take(MAX_DATE))
            CreditField.AMOUNT -> state.draft.copy(amount = value.take(MAX_AMOUNT))
            CreditField.OFFICIAL_AMOUNT -> state.draft.copy(officialAmount = value.take(MAX_AMOUNT))
            CreditField.DATE -> state.draft.copy(date = value.take(MAX_DATE))
        }
        return state.copy(draft = draft, presentation = CreditPresentation.EDITING, validationFields = emptySet())
    }

    public fun validatePayment(state: CreditFeatureState): CreditFeatureState {
        val account = state.account ?: return state.copy(presentation = CreditPresentation.VALIDATION_ERROR, validationFields = setOf("creditAccount"))
        val amount = parseMinor(state.draft.amount, account.currency)
        val errors = buildSet {
            if (amount == null || amount <= 0L) add("amount")
            if (state.draft.selectedPaymentAccountId == null) add("paymentAccount")
            if (runCatching { LocalDate.parse(state.draft.date) }.isFailure) add("date")
        }
        val statementOutstanding = account.statements.sumOf { maxOf(0L, it.remainingAmountMinor) }
        val maximumPayment = if (state.draft.allocationMode == CreditAllocationMode.UNALLOCATED_ADVANCE) {
            account.debtMinor
        } else {
            minOf(account.debtMinor, statementOutstanding)
        }
        return when {
            errors.isNotEmpty() -> state.copy(presentation = CreditPresentation.VALIDATION_ERROR, validationFields = errors)
            requireNotNull(amount) > maximumPayment -> state.copy(presentation = CreditPresentation.OVERPAYMENT_BLOCKED, validationFields = setOf("amount"))
            else -> state.copy(
                presentation = if (state.draft.allocationMode == CreditAllocationMode.UNALLOCATED_ADVANCE) {
                    CreditPresentation.UNALLOCATED
                } else {
                    CreditPresentation.EDITING
                },
            )
        }
    }

    public fun money(
        minor: Long,
        currency: CurrencyCode,
        locale: Locale,
        semantic: AmountSemantic = AmountSemantic.NEUTRAL,
    ): MoneyUiModel = (
        formatter.format(
            MoneyFormatRequest(Money(minor, currency), locale, semantic, AmountVisibility.VISIBLE),
        ) as DomainResult.Success<MoneyUiModel>
        ).value

    public fun parseMinor(value: String, currency: CurrencyCode): Long? = runCatching {
        val scale = requireNotNull(catalog.find(currency)).fractionDigits
        BigDecimal(value.trim().replace(',', '.')).movePointRight(scale).setScale(0, RoundingMode.HALF_EVEN).longValueExact()
    }.getOrNull()

    private fun accountPresentation(account: CreditAccountView?): CreditPresentation = when {
        account == null || account.statements.isEmpty() -> CreditPresentation.NO_STATEMENTS
        account.positiveBalanceMinor > 0L -> CreditPresentation.POSITIVE_BALANCE
        account.overdueMinor > 0L -> CreditPresentation.OVERDUE
        account.profile?.standardLimitMinor == null -> CreditPresentation.NO_LIMIT
        else -> CreditPresentation.NORMAL
    }

    private fun statementPresentation(statement: CreditStatementView?): CreditPresentation = when {
        statement == null -> CreditPresentation.EMPTY
        statement.status == CreditStatementStatus.PAID -> CreditPresentation.PAID
        statement.sealed -> CreditPresentation.SEALED
        statement.officialAmountMinor != null -> CreditPresentation.OFFICIAL
        else -> CreditPresentation.ESTIMATED_ONLY
    }

    public fun autoPresentation(
        account: CreditAccountView?,
        statement: CreditStatementView?,
        mode: AutoGenerationMode,
    ): CreditPresentation = when {
        mode == AutoGenerationMode.CONFIRMATION_CANDIDATE -> CreditPresentation.CANDIDATE_MODE
        account == null || account.archived || account.profile?.defaultPaymentAccountId == null ||
            statement?.officialAmountMinor == null || statement.remainingAmountMinor <= 0L || account.debtMinor <= 0L -> CreditPresentation.INELIGIBLE
        else -> CreditPresentation.ELIGIBLE
    }

    private fun minorText(minor: Long, currency: CurrencyCode): String {
        val scale = requireNotNull(catalog.find(currency)).fractionDigits
        return BigDecimal.valueOf(minor, scale).stripTrailingZeros().toPlainString()
    }

    private const val MAX_SHORT = 3
    private const val MAX_AMOUNT = 40
    private const val MAX_DATE = 10
    private const val MAX_ZONE = 64
}

public enum class CreditField { STATEMENT_DAY, DUE_DAY, ZONE, STANDARD_LIMIT, TEMPORARY_LIMIT, TEMPORARY_EXPIRY, AMOUNT, OFFICIAL_AMOUNT, DATE }

public enum class CreditAllocationMode { EARLIEST_UNPAID, SPECIFIC, UNALLOCATED_ADVANCE }
