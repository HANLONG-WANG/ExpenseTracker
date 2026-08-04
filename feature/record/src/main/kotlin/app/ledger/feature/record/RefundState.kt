@file:Suppress("LongParameterList", "MagicNumber", "MaxLineLength", "TooManyFunctions", "ReturnCount")

package app.ledger.feature.record

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.AmountSemantic
import app.ledger.core.money.AmountVisibility
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.FxEvidence
import app.ledger.core.money.FxEvidenceInput
import app.ledger.core.money.FxProvider
import app.ledger.core.money.FxRateSource
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.LocaleCurrencyFormatter
import app.ledger.core.money.Money
import app.ledger.core.money.MoneyExpressionEvaluator
import app.ledger.core.money.MoneyFormatRequest
import app.ledger.core.money.MoneyUiModel
import app.ledger.finance.application.AccountReferenceView
import app.ledger.finance.application.RefundAllocationDraft
import app.ledger.finance.application.RefundAmountDraft
import app.ledger.finance.application.RefundSearchQuery
import app.ledger.finance.application.RefundSnapshot
import app.ledger.finance.application.RefundableTransactionView
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.RefundAccrualPolicy
import app.ledger.finance.domain.RefundBudgetPolicy
import app.ledger.finance.domain.RefundGoalPolicy
import app.ledger.finance.domain.RefundProjectPolicy
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

public enum class RefundPresentation { EDITING, SAVING, SAVE_ERROR }
public enum class RefundField { ORIGINAL, AMOUNT, ACCOUNT, CATEGORY, EXCESS_CONFIRMATION }
public data class RefundValidationError(val field: RefundField, val code: String)

/** Refund amounts, note and relationships are deliberately memory-only and never enter routes/SavedState. */
public data class RefundDraft(
    val originalTransactionId: StableId?,
    val expression: String,
    val normalizedExpression: String = "",
    val resultMinor: Long? = null,
    val result: MoneyUiModel? = null,
    val receivingAccountId: StableId?,
    val receivingCardId: StableId?,
    val categoryId: StableId?,
    val merchantId: StableId?,
    val projectId: StableId?,
    val goalId: StableId?,
    val occurredAt: Instant,
    val zoneId: ZoneId,
    val localDate: LocalDate,
    val accrualPolicy: RefundAccrualPolicy = RefundAccrualPolicy.ORIGINAL_TRANSACTION_DATE,
    val budgetPolicy: RefundBudgetPolicy = RefundBudgetPolicy.RESTORE_ORIGINAL_MONTH,
    val projectPolicy: RefundProjectPolicy = RefundProjectPolicy.RESTORE_ORIGINAL_PROJECT,
    val goalPolicy: RefundGoalPolicy = RefundGoalPolicy.RESTORE_ORIGINAL_GOAL,
    val note: String = "",
    val attachmentIds: List<StableId> = emptyList(),
    val excessOverrideRequested: Boolean = false,
    val excessRiskConfirmed: Boolean = false,
    val dirty: Boolean = false,
) {
    public val independent: Boolean get() = originalTransactionId == null
}

public data class RefundEditorState(
    val snapshot: RefundSnapshot,
    val draft: RefundDraft,
    val presentation: RefundPresentation = RefundPresentation.EDITING,
    val errors: List<RefundValidationError> = emptyList(),
    val failureCode: String? = null,
)

public sealed interface RefundLoadState {
    public data object Loading : RefundLoadState
    public data class Content(val editor: RefundEditorState) : RefundLoadState
    public data class Failure(val code: String) : RefundLoadState
}

public sealed interface RefundPickerState {
    public data object Loading : RefundPickerState
    public data class Content(
        val snapshot: RefundSnapshot,
        val query: RefundSearchQuery = RefundSearchQuery(),
        val searching: Boolean = false,
    ) : RefundPickerState
    public data class Failure(val code: String) : RefundPickerState
}

public data class PreparedRefundSubmission(
    val amount: RefundAmountDraft,
    val allocations: List<RefundAllocationDraft>,
    val accrualDate: LocalDate,
    val budgetTargetMonth: YearMonth?,
)

public object RefundPolicy {
    private val catalog = JvmLegalTenderCurrencyCatalog.create()
    private val evaluator = MoneyExpressionEvaluator()
    private val formatter = LocaleCurrencyFormatter(catalog)
    private val math = MathContext(34, RoundingMode.HALF_EVEN)

    public fun create(
        snapshot: RefundSnapshot,
        presetOriginalId: StableId?,
        now: Instant,
        zoneId: ZoneId,
        locale: Locale,
    ): RefundEditorState {
        val original = snapshot.originals.singleOrNull { it.transactionId == presetOriginalId }
            ?: snapshot.originals.firstOrNull()
        val account = activeAccounts(snapshot).singleOrNull { it.id == original?.accountId }
            ?: activeAccounts(snapshot).firstOrNull()
        val draft = RefundDraft(
            originalTransactionId = original?.transactionId,
            expression = "",
            receivingAccountId = account?.id,
            receivingCardId = original?.cardId?.takeIf { card -> compatibleCard(snapshot, account?.id, card) },
            categoryId = original?.categoryId,
            merchantId = original?.merchantId,
            projectId = original?.projectId,
            goalId = original?.goalId,
            occurredAt = now,
            zoneId = zoneId,
            localDate = now.atZone(zoneId).toLocalDate(),
        )
        return evaluate(RefundEditorState(snapshot, draft), locale)
    }

    public fun selectOriginal(state: RefundEditorState, id: StableId, locale: Locale): RefundEditorState {
        val original = state.snapshot.originals.single { it.transactionId == id }
        val account = activeAccounts(state.snapshot).singleOrNull { it.id == original.accountId }
            ?: account(state, state.draft.receivingAccountId)
            ?: activeAccounts(state.snapshot).firstOrNull()
        return evaluate(
            state.copy(
                draft = state.draft.copy(
                    originalTransactionId = id,
                    expression = "",
                    receivingAccountId = account?.id,
                    receivingCardId = original.cardId?.takeIf { compatibleCard(state.snapshot, account?.id, it) },
                    categoryId = original.categoryId,
                    merchantId = original.merchantId,
                    projectId = original.projectId,
                    goalId = original.goalId,
                    accrualPolicy = RefundAccrualPolicy.ORIGINAL_TRANSACTION_DATE,
                    budgetPolicy = RefundBudgetPolicy.RESTORE_ORIGINAL_MONTH,
                    projectPolicy = RefundProjectPolicy.RESTORE_ORIGINAL_PROJECT,
                    goalPolicy = RefundGoalPolicy.RESTORE_ORIGINAL_GOAL,
                    excessOverrideRequested = false,
                    excessRiskConfirmed = false,
                    dirty = true,
                ),
                errors = emptyList(),
            ),
            locale,
        )
    }

    public fun setIndependent(state: RefundEditorState, independent: Boolean, locale: Locale): RefundEditorState {
        if (!independent) {
            val first = state.snapshot.originals.firstOrNull() ?: return state
            return selectOriginal(state, first.transactionId, locale)
        }
        return evaluate(
            state.copy(
                draft = state.draft.copy(
                    originalTransactionId = null,
                    expression = "",
                    accrualPolicy = RefundAccrualPolicy.REFUND_DATE,
                    budgetPolicy = RefundBudgetPolicy.DO_NOT_RESTORE,
                    projectPolicy = RefundProjectPolicy.DO_NOT_RESTORE,
                    goalPolicy = RefundGoalPolicy.DO_NOT_RESTORE,
                    excessOverrideRequested = false,
                    excessRiskConfirmed = false,
                    dirty = true,
                ),
                errors = emptyList(),
            ),
            locale,
        )
    }

    public fun updateExpression(state: RefundEditorState, value: String, locale: Locale): RefundEditorState = evaluate(
        state.copy(
            draft = state.draft.copy(expression = value.take(MAX_EXPRESSION), dirty = true),
            presentation = RefundPresentation.EDITING,
            errors = state.errors.filterNot { it.field == RefundField.AMOUNT || it.field == RefundField.EXCESS_CONFIRMATION },
        ),
        locale,
    )

    public fun appendOperator(state: RefundEditorState, operator: String, locale: Locale): RefundEditorState {
        val next = if (operator == "DELETE") state.draft.expression.dropLast(1) else state.draft.expression + operator.replace('−', '-').replace('×', '*').replace('÷', '/')
        return updateExpression(state, next, locale)
    }

    public fun selectAccount(state: RefundEditorState, id: StableId, locale: Locale): RefundEditorState {
        activeAccounts(state.snapshot).single { it.id == id }
        val card = state.draft.receivingCardId?.takeIf { compatibleCard(state.snapshot, id, it) }
        return evaluate(state.copy(draft = state.draft.copy(receivingAccountId = id, receivingCardId = card, dirty = true)), locale)
    }

    public fun selectCard(state: RefundEditorState, id: StableId?): RefundEditorState {
        require(id == null || compatibleCard(state.snapshot, state.draft.receivingAccountId, id))
        return state.copy(draft = state.draft.copy(receivingCardId = id, dirty = true))
    }

    public fun updateReference(state: RefundEditorState, field: RefundField, id: StableId?): RefundEditorState {
        val draft = when (field) {
            RefundField.CATEGORY -> state.draft.copy(categoryId = id)
            else -> error("unsupported refund reference")
        }
        return state.copy(draft = draft.copy(dirty = true), errors = state.errors.filterNot { it.field == field })
    }

    public fun updateInherited(
        state: RefundEditorState,
        merchantId: StableId? = state.draft.merchantId,
        projectId: StableId? = state.draft.projectId,
        goalId: StableId? = state.draft.goalId,
    ): RefundEditorState = state.copy(draft = state.draft.copy(merchantId = merchantId, projectId = projectId, goalId = goalId, dirty = true))

    public fun setDate(state: RefundEditorState, date: LocalDate): RefundEditorState {
        val time = state.draft.occurredAt.atZone(state.draft.zoneId).toLocalTime()
        return state.copy(draft = state.draft.copy(localDate = date, occurredAt = date.atTime(time).atZone(state.draft.zoneId).toInstant(), dirty = true))
    }

    public fun setAccrualPolicy(state: RefundEditorState, policy: RefundAccrualPolicy): RefundEditorState = state.copy(draft = state.draft.copy(accrualPolicy = policy, dirty = true))
    public fun setBudgetPolicy(state: RefundEditorState, policy: RefundBudgetPolicy): RefundEditorState = state.copy(draft = state.draft.copy(budgetPolicy = policy, dirty = true))
    public fun setProjectPolicy(state: RefundEditorState, policy: RefundProjectPolicy): RefundEditorState = state.copy(draft = state.draft.copy(projectPolicy = policy, dirty = true))
    public fun setGoalPolicy(state: RefundEditorState, policy: RefundGoalPolicy): RefundEditorState = state.copy(draft = state.draft.copy(goalPolicy = policy, dirty = true))
    public fun setNote(state: RefundEditorState, value: String): RefundEditorState = state.copy(draft = state.draft.copy(note = value.take(MAX_NOTE), dirty = true))

    /** Requesting and confirming an excess refund are intentionally two distinct user actions. */
    public fun requestExcessOverride(state: RefundEditorState, requested: Boolean): RefundEditorState = state.copy(
        draft = state.draft.copy(excessOverrideRequested = requested, excessRiskConfirmed = false, dirty = true),
        errors = state.errors.filterNot { it.field == RefundField.EXCESS_CONFIRMATION },
    )

    public fun confirmExcessRisk(state: RefundEditorState): RefundEditorState {
        require(state.draft.excessOverrideRequested && exceedsRemaining(state))
        return state.copy(draft = state.draft.copy(excessRiskConfirmed = true, dirty = true))
    }

    public fun validate(state: RefundEditorState): RefundEditorState {
        val errors = buildList {
            if (!state.draft.independent && original(state) == null) add(RefundValidationError(RefundField.ORIGINAL, "ORIGINAL_REQUIRED"))
            if (state.draft.resultMinor == null) add(RefundValidationError(RefundField.AMOUNT, "AMOUNT_INVALID"))
            if (account(state, state.draft.receivingAccountId) == null) add(RefundValidationError(RefundField.ACCOUNT, "ACCOUNT_REQUIRED"))
            if (state.draft.categoryId == null) add(RefundValidationError(RefundField.CATEGORY, "CATEGORY_REQUIRED"))
            if (exceedsRemaining(state) && (!state.draft.excessOverrideRequested || !state.draft.excessRiskConfirmed)) {
                add(RefundValidationError(RefundField.EXCESS_CONFIRMATION, "EXCESS_CONFIRMATION_REQUIRED"))
            }
            if (runCatching { prepare(state) }.isFailure) add(RefundValidationError(RefundField.AMOUNT, "FX_EVIDENCE_REQUIRED"))
        }.distinctBy(RefundValidationError::field)
        return state.copy(errors = errors, presentation = RefundPresentation.EDITING)
    }

    public fun prepare(state: RefundEditorState): PreparedRefundSubmission {
        val account = requireNotNull(account(state, state.draft.receivingAccountId))
        val inputMinor = requireNotNull(state.draft.resultMinor)
        val inputCurrency = inputCurrency(state)
        val original = original(state)
        val baseMinor = if (original != null) {
            proportional(inputMinor, original.originalBaseMinor, original.originalMinor)
        } else if (account.currency == state.snapshot.references.baseCurrency) {
            inputMinor
        } else {
            convertMinor(inputMinor, account.currency, state.snapshot.references.baseCurrency, requireNotNull(account.currentValuationRate))
        }
        require(baseMinor > 0L)
        val accountMinor = when {
            account.currency == inputCurrency -> inputMinor
            account.currency == state.snapshot.references.baseCurrency -> baseMinor
            else -> convertMinor(baseMinor, state.snapshot.references.baseCurrency, account.currency, BigDecimal.ONE.divide(requireNotNull(account.currentValuationRate), math))
        }
        require(accountMinor > 0L)
        val inputToAccount = exactEvidence(inputMinor, inputCurrency, accountMinor, account.currency)
        val accountToBase = exactEvidence(accountMinor, account.currency, baseMinor, state.snapshot.references.baseCurrency)
        val amount = RefundAmountDraft(inputMinor, inputCurrency, account.id, accountMinor, baseMinor, inputToAccount, accountToBase)
        val allocation = original?.let { listOf(RefundAllocationDraft(it.transactionId, it.revisionId, inputMinor, baseMinor)) }.orEmpty()
        val accrualDate = if (state.draft.accrualPolicy == RefundAccrualPolicy.ORIGINAL_TRANSACTION_DATE) requireNotNull(original).localDate else state.draft.localDate
        val budgetMonth = when (state.draft.budgetPolicy) {
            RefundBudgetPolicy.RESTORE_ORIGINAL_MONTH -> YearMonth.from(requireNotNull(original).localDate)
            RefundBudgetPolicy.RESTORE_REFUND_MONTH -> YearMonth.from(state.draft.localDate)
            RefundBudgetPolicy.DO_NOT_RESTORE -> null
        }
        return PreparedRefundSubmission(amount, allocation, accrualDate, budgetMonth)
    }

    public fun original(state: RefundEditorState): RefundableTransactionView? = state.snapshot.originals.singleOrNull { it.transactionId == state.draft.originalTransactionId }
    public fun account(state: RefundEditorState, id: StableId?): AccountReferenceView? = state.snapshot.references.accounts.singleOrNull { it.id == id && it.status == EntityStatus.ACTIVE }
    public fun exceedsRemaining(state: RefundEditorState): Boolean = original(state)?.let { original -> state.draft.resultMinor?.let { it > original.remainingMinor } } == true
    public fun currency(state: RefundEditorState): CurrencyCode = inputCurrency(state)

    public fun format(minor: Long, currency: CurrencyCode, locale: Locale): MoneyUiModel = (formatter.format(MoneyFormatRequest(Money(minor, currency), locale, AmountSemantic.REFUND, AmountVisibility.VISIBLE)) as DomainResult.Success).value

    private fun evaluate(state: RefundEditorState, locale: Locale): RefundEditorState {
        val currency = inputCurrency(state)
        val metadata = catalog.find(currency)
        val evaluated = metadata?.let { evaluator.evaluate(state.draft.expression, locale, it) as? DomainResult.Success }
        val value = evaluated?.value
        return state.copy(
            draft = state.draft.copy(
                normalizedExpression = value?.expression?.normalized.orEmpty(),
                resultMinor = value?.roundedMoney?.minor,
                result = value?.roundedMoney?.let { format(it.minor, it.currency, locale) },
            ),
        )
    }

    private fun inputCurrency(state: RefundEditorState): CurrencyCode = original(state)?.originalCurrency
        ?: account(state, state.draft.receivingAccountId)?.currency
        ?: state.snapshot.references.baseCurrency

    private fun activeAccounts(snapshot: RefundSnapshot): List<AccountReferenceView> = snapshot.references.accounts.filter { it.status == EntityStatus.ACTIVE }.sortedBy(AccountReferenceView::sortOrder)
    private fun compatibleCard(snapshot: RefundSnapshot, accountId: StableId?, cardId: StableId): Boolean = snapshot.references.cards.any { it.id == cardId && it.accountId == accountId && it.status == EntityStatus.ACTIVE }

    private fun proportional(value: Long, numerator: Long, denominator: Long): Long = BigDecimal.valueOf(value)
        .multiply(BigDecimal.valueOf(numerator), math)
        .divide(BigDecimal.valueOf(denominator), math)
        .setScale(0, RoundingMode.HALF_EVEN)
        .longValueExact()

    private fun convertMinor(value: Long, source: CurrencyCode, target: CurrencyCode, rate: BigDecimal): Long {
        val sourceScale = requireNotNull(catalog.find(source)).fractionDigits
        val targetScale = requireNotNull(catalog.find(target)).fractionDigits
        return BigDecimal.valueOf(value, sourceScale).multiply(rate, math).movePointRight(targetScale)
            .setScale(0, RoundingMode.HALF_EVEN).longValueExact()
    }

    private fun exactEvidence(sourceMinor: Long, source: CurrencyCode, targetMinor: Long, target: CurrencyCode): FxEvidence? {
        if (source == target) {
            require(sourceMinor == targetMinor)
            return null
        }
        val sourceScale = requireNotNull(catalog.find(source)).fractionDigits
        val targetScale = requireNotNull(catalog.find(target)).fractionDigits
        val rate = BigDecimal.valueOf(targetMinor, targetScale).divide(BigDecimal.valueOf(sourceMinor, sourceScale), math)
        return (
            FxEvidence.create(
                FxEvidenceInput(
                    source,
                    target,
                    rate,
                    (FxProvider.of("refund-actual-amounts") as DomainResult.Success).value,
                    null,
                    null,
                    FxRateSource.IMPLIED_FROM_ACTUAL_AMOUNTS,
                    false,
                ),
            ) as DomainResult.Success
            ).value
    }

    private const val MAX_EXPRESSION = 256
    private const val MAX_NOTE = 2_000
}
