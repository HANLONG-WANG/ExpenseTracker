@file:Suppress("LongParameterList", "MagicNumber", "MaxLineLength", "TooManyFunctions", "ReturnCount")

package app.ledger.feature.record

import app.ledger.core.common.CheckedArithmetic
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.AmountSemantic
import app.ledger.core.money.AmountVisibility
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.FxConverter
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
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.application.SpecializedAccountAmountDraft
import app.ledger.finance.application.SpecializedTransactionEditView
import app.ledger.finance.application.SpecializedFxQuote
import app.ledger.finance.domain.BalanceAdjustmentDirection
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.FxValuationPolicy
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

public enum class SpecializedTransactionKind { TRANSFER, BALANCE_ADJUSTMENT, FX_EXCHANGE, OPENING_BALANCE }

public enum class SpecializedPresentation { LOADING, EDITING, SAVING, SAVE_ERROR }

public enum class SpecializedField { FROM_ACCOUNT, TO_ACCOUNT, OUTGOING_AMOUNT, INCOMING_AMOUNT, RATE, CHECKPOINT }

public data class SpecializedValidationError(val field: SpecializedField, val code: String)

/** All amounts, notes, and immutable FX evidence remain in memory and never enter route/SavedState. */
public data class SpecializedTransactionDraft(
    val fromAccountId: StableId?,
    val toAccountId: StableId?,
    val outgoingExpression: String,
    val outgoingNormalized: String = "",
    val outgoingMinor: Long? = null,
    val outgoingFormatted: MoneyUiModel? = null,
    val incomingExpression: String = "",
    val incomingNormalized: String = "",
    val incomingMinor: Long? = null,
    val incomingFormatted: MoneyUiModel? = null,
    val manualFromBaseRate: String = "",
    val manualToBaseRate: String = "",
    val direction: BalanceAdjustmentDirection = BalanceAdjustmentDirection.INCREASE,
    val checkpointId: StableId? = null,
    val occurredAt: Instant,
    val zoneId: ZoneId,
    val localDate: LocalDate,
    val note: String = "",
    val attachmentIds: List<StableId> = emptyList(),
    val dirty: Boolean = false,
)

public data class SpecializedTransactionEditorState(
    val kind: SpecializedTransactionKind,
    val snapshot: ReferenceDataSnapshot,
    val draft: SpecializedTransactionDraft,
    val transactionId: StableId? = null,
    val expectedRevisionId: StableId? = null,
    val quotesToBase: Map<CurrencyCode, SpecializedFxQuote> = emptyMap(),
    val quotePending: Set<CurrencyCode> = emptySet(),
    val presentation: SpecializedPresentation = SpecializedPresentation.EDITING,
    val errors: List<SpecializedValidationError> = emptyList(),
    val failureCode: String? = null,
    val attachmentImporting: Boolean = false,
    val attachmentFailureCode: String? = null,
    val uncommittedAttachmentIds: Set<StableId> = emptySet(),
)

public sealed interface SpecializedTransactionLoadState {
    public data object Loading : SpecializedTransactionLoadState
    public data class Content(val editor: SpecializedTransactionEditorState) : SpecializedTransactionLoadState
    public data class Failure(val code: String) : SpecializedTransactionLoadState
}

public data class PreparedSpecializedAmounts(
    val outgoing: SpecializedAccountAmountDraft,
    val incoming: SpecializedAccountAmountDraft?,
    val valuationPolicy: FxValuationPolicy,
    val spreadCostBaseMinor: Long?,
)

public data class SpecializedFxDisplaySummary(
    val effectiveRate: BigDecimal?,
    val referenceRate: BigDecimal?,
    val spreadCostBaseMinor: Long?,
)

public object SpecializedTransactionPolicy {
    private val catalog = JvmLegalTenderCurrencyCatalog.create()
    private val evaluator = MoneyExpressionEvaluator()
    private val formatter = LocaleCurrencyFormatter(catalog)
    private val converter = FxConverter()

    public fun create(
        kind: SpecializedTransactionKind,
        snapshot: ReferenceDataSnapshot,
        presetAccountId: StableId?,
        now: Instant,
        zoneId: ZoneId,
        locale: Locale,
        editing: SpecializedTransactionEditView? = null,
    ): SpecializedTransactionEditorState {
        val edit = editing?.takeIf { it.kind.toSpecializedKind() == kind }
        val accounts = activeAccounts(snapshot)
        // Historical revisions may still reference an archived account. Keep that exact account
        // visible while editing; only account changes cycle through the currently active set.
        val from = snapshot.accounts.singleOrNull { it.id == edit?.fromAccountId }
            ?: accounts.singleOrNull { it.id == presetAccountId }
            ?: accounts.firstOrNull()
        val to = snapshot.accounts.singleOrNull { it.id == edit?.toAccountId }
            ?: accounts.firstOrNull { it.id != from?.id }
        val draft = SpecializedTransactionDraft(
            fromAccountId = from?.id,
            toAccountId = to?.id,
            outgoingExpression = edit?.amountExpression?.takeIf(String::isNotBlank)
                ?: edit?.let { minorExpression(it.outgoingMinor, requireNotNull(from).currency) }.orEmpty(),
            incomingExpression = edit?.incomingMinor?.let { minor -> minorExpression(minor, requireNotNull(to).currency) }.orEmpty(),
            manualFromBaseRate = edit?.let { manualRate(it.outgoingMinor, requireNotNull(from).currency, it.outgoingBaseMinor, snapshot.baseCurrency) }.orEmpty(),
            manualToBaseRate = edit?.incomingMinor?.let { minor ->
                manualRate(minor, requireNotNull(to).currency, requireNotNull(edit.incomingBaseMinor), snapshot.baseCurrency)
            }.orEmpty(),
            direction = edit?.direction ?: BalanceAdjustmentDirection.INCREASE,
            checkpointId = edit?.checkpointId ?: if (kind == SpecializedTransactionKind.BALANCE_ADJUSTMENT && presetAccountId != null) {
                snapshot.checkpoints.firstOrNull { it.accountId == from?.id && it.adjustmentTransactionId == null }?.id
            } else {
                null
            },
            occurredAt = edit?.occurredAt ?: now,
            zoneId = edit?.zoneId ?: zoneId,
            localDate = edit?.localDate ?: now.atZone(zoneId).toLocalDate(),
            note = edit?.note.orEmpty(),
            attachmentIds = edit?.attachmentIds.orEmpty(),
        )
        return evaluate(
            SpecializedTransactionEditorState(
                kind,
                snapshot,
                draft,
                transactionId = edit?.transactionId,
                expectedRevisionId = edit?.revisionId,
            ),
            locale,
        )
    }

    public fun updateExpression(
        state: SpecializedTransactionEditorState,
        incoming: Boolean,
        value: String,
        locale: Locale,
    ): SpecializedTransactionEditorState {
        val draft = if (incoming) state.draft.copy(incomingExpression = value.take(MAX_EXPRESSION), dirty = true) else state.draft.copy(outgoingExpression = value.take(MAX_EXPRESSION), dirty = true)
        return evaluate(state.copy(draft = draft, presentation = SpecializedPresentation.EDITING, errors = emptyList()), locale)
    }

    public fun appendOperator(
        state: SpecializedTransactionEditorState,
        incoming: Boolean,
        operator: String,
        locale: Locale,
    ): SpecializedTransactionEditorState {
        val current = if (incoming) state.draft.incomingExpression else state.draft.outgoingExpression
        val value = if (operator == "DELETE") current.dropLast(1) else current + operator.replace('−', '-').replace('×', '*').replace('÷', '/')
        return updateExpression(state, incoming, value, locale)
    }

    public fun selectAccount(state: SpecializedTransactionEditorState, incoming: Boolean): SpecializedTransactionEditorState {
        val accounts = activeAccounts(state.snapshot)
        if (accounts.isEmpty()) return state
        val current = if (incoming) state.draft.toAccountId else state.draft.fromAccountId
        val currentIndex = accounts.indexOfFirst { it.id == current }
        val next = accounts[if (currentIndex < 0) 0 else (currentIndex + 1) % accounts.size]
        val draft = if (incoming) state.draft.copy(toAccountId = next.id, incomingExpression = "", dirty = true) else state.draft.copy(fromAccountId = next.id, outgoingExpression = "", dirty = true)
        return state.copy(draft = draft, errors = emptyList(), presentation = SpecializedPresentation.EDITING)
    }

    public fun setDirection(state: SpecializedTransactionEditorState, direction: BalanceAdjustmentDirection): SpecializedTransactionEditorState = state.copy(draft = state.draft.copy(direction = direction, dirty = true))

    public fun setCheckpoint(state: SpecializedTransactionEditorState, checkpointId: StableId?): SpecializedTransactionEditorState = state.copy(draft = state.draft.copy(checkpointId = checkpointId, dirty = true))

    public fun setManualRate(state: SpecializedTransactionEditorState, incoming: Boolean, value: String): SpecializedTransactionEditorState {
        val filtered = value.filter { it.isDigit() || it == '.' }.take(MAX_RATE)
        val draft = if (incoming) state.draft.copy(manualToBaseRate = filtered, dirty = true) else state.draft.copy(manualFromBaseRate = filtered, dirty = true)
        return state.copy(draft = draft, errors = state.errors.filterNot { it.field == SpecializedField.RATE })
    }

    public fun setNote(state: SpecializedTransactionEditorState, value: String): SpecializedTransactionEditorState = state.copy(draft = state.draft.copy(note = value.take(MAX_NOTE), dirty = true))

    public fun changeDate(state: SpecializedTransactionEditorState, date: LocalDate): SpecializedTransactionEditorState {
        val localTime = state.draft.occurredAt.atZone(state.draft.zoneId).toLocalTime()
        return state.copy(draft = state.draft.copy(localDate = date, occurredAt = date.atTime(localTime).atZone(state.draft.zoneId).toInstant(), dirty = true))
    }

    public fun withQuote(state: SpecializedTransactionEditorState, currency: CurrencyCode, quote: SpecializedFxQuote?): SpecializedTransactionEditorState = state.copy(
        quotesToBase = if (quote == null) state.quotesToBase - currency else state.quotesToBase + (currency to quote),
        quotePending = state.quotePending - currency,
    )

    public fun validate(state: SpecializedTransactionEditorState): SpecializedTransactionEditorState {
        val draft = state.draft
        val errors = buildList {
            if (draft.fromAccountId == null) add(SpecializedValidationError(SpecializedField.FROM_ACCOUNT, "ACCOUNT_REQUIRED"))
            if (draft.outgoingMinor == null) add(SpecializedValidationError(SpecializedField.OUTGOING_AMOUNT, "AMOUNT_INVALID"))
            if (state.kind in setOf(SpecializedTransactionKind.TRANSFER, SpecializedTransactionKind.FX_EXCHANGE)) {
                if (draft.toAccountId == null) add(SpecializedValidationError(SpecializedField.TO_ACCOUNT, "ACCOUNT_REQUIRED"))
                if (draft.fromAccountId == draft.toAccountId) add(SpecializedValidationError(SpecializedField.TO_ACCOUNT, "SAME_ACCOUNT"))
                val from = account(state, draft.fromAccountId)
                val to = account(state, draft.toAccountId)
                if (state.kind == SpecializedTransactionKind.FX_EXCHANGE && from?.currency == to?.currency) add(SpecializedValidationError(SpecializedField.TO_ACCOUNT, "SAME_CURRENCY"))
                if (state.kind == SpecializedTransactionKind.FX_EXCHANGE || from?.currency != to?.currency) {
                    if (draft.incomingMinor == null) add(SpecializedValidationError(SpecializedField.INCOMING_AMOUNT, "AMOUNT_INVALID"))
                }
            }
            if (runCatching { prepareAmounts(state) }.isFailure) add(SpecializedValidationError(SpecializedField.RATE, "FX_EVIDENCE_REQUIRED"))
        }.distinctBy { it.field }
        return state.copy(errors = errors, presentation = SpecializedPresentation.EDITING)
    }

    public fun prepareAmounts(state: SpecializedTransactionEditorState): PreparedSpecializedAmounts {
        val draft = state.draft
        val from = requireNotNull(account(state, draft.fromAccountId))
        val outgoingMinor = requireNotNull(draft.outgoingMinor)
        val outgoing = amountToBase(state, from, outgoingMinor, draft.manualFromBaseRate)
        if (state.kind !in setOf(SpecializedTransactionKind.TRANSFER, SpecializedTransactionKind.FX_EXCHANGE)) {
            return PreparedSpecializedAmounts(outgoing, null, FxValuationPolicy.PROVIDED_RATE, null)
        }
        val to = requireNotNull(account(state, draft.toAccountId))
        val incomingMinor = if (from.currency == to.currency && state.kind == SpecializedTransactionKind.TRANSFER) outgoingMinor else requireNotNull(draft.incomingMinor)
        val incoming = if (state.kind == SpecializedTransactionKind.TRANSFER) {
            impliedAmount(to, incomingMinor, outgoing.baseMinor, state.snapshot.baseCurrency)
        } else {
            amountToBase(state, to, incomingMinor, draft.manualToBaseRate)
        }
        val spread = if (state.kind == SpecializedTransactionKind.FX_EXCHANGE) {
            val difference = BigInteger.valueOf(outgoing.baseMinor).subtract(BigInteger.valueOf(incoming.baseMinor)).abs()
            when (val exact = CheckedArithmetic.toLongExact(difference)) {
                is DomainResult.Success -> exact.value.takeIf { it > 0L }
                is DomainResult.Failure -> throw ArithmeticException("FX spread exceeds Long minor-unit range")
            }
        } else {
            null
        }
        val policy = if (state.kind == SpecializedTransactionKind.FX_EXCHANGE) FxValuationPolicy.EXPLICIT_ACCOUNT_AMOUNTS else FxValuationPolicy.IMPLIED_RATE
        return PreparedSpecializedAmounts(outgoing, incoming, policy, spread)
    }

    public fun requiredQuoteCurrencies(state: SpecializedTransactionEditorState): Set<CurrencyCode> = buildSet {
        account(state, state.draft.fromAccountId)?.currency?.takeIf { it != state.snapshot.baseCurrency }?.let(::add)
        if (state.kind == SpecializedTransactionKind.FX_EXCHANGE) account(state, state.draft.toAccountId)?.currency?.takeIf { it != state.snapshot.baseCurrency }?.let(::add)
    }

    public fun fxDisplaySummary(state: SpecializedTransactionEditorState): SpecializedFxDisplaySummary {
        val from = account(state, state.draft.fromAccountId)
        val to = account(state, state.draft.toAccountId)
        val hasBothAmounts = state.draft.outgoingMinor != null && state.draft.incomingMinor != null
        val effectiveRate = if (from != null && to != null && hasBothAmounts) {
            val fromMetadata = catalog.find(from.currency)
            val toMetadata = catalog.find(to.currency)
            if (fromMetadata == null || toMetadata == null) {
                null
            } else {
                BigDecimal.valueOf(state.draft.incomingMinor, toMetadata.fractionDigits)
                    .divide(BigDecimal.valueOf(state.draft.outgoingMinor, fromMetadata.fractionDigits), MATH_CONTEXT)
                    .stripTrailingZeros()
            }
        } else {
            null
        }
        val referenceRate = if (from != null && to != null) {
            val fromBase = if (from.currency == state.snapshot.baseCurrency) BigDecimal.ONE else state.quotesToBase[from.currency]?.evidence?.rate
            val toBase = if (to.currency == state.snapshot.baseCurrency) BigDecimal.ONE else state.quotesToBase[to.currency]?.evidence?.rate
            if (fromBase != null && toBase != null) fromBase.divide(toBase, MATH_CONTEXT).stripTrailingZeros() else null
        } else {
            null
        }
        val spread = if (state.kind == SpecializedTransactionKind.FX_EXCHANGE) {
            runCatching { prepareAmounts(state).spreadCostBaseMinor }.getOrNull()
        } else {
            null
        }
        return SpecializedFxDisplaySummary(effectiveRate, referenceRate, spread)
    }

    public fun account(state: SpecializedTransactionEditorState, id: StableId?): AccountReferenceView? = state.snapshot.accounts.singleOrNull { it.id == id }

    private fun evaluate(state: SpecializedTransactionEditorState, locale: Locale): SpecializedTransactionEditorState {
        val from = account(state, state.draft.fromAccountId)
        val to = account(state, state.draft.toAccountId)
        val outgoing = from?.let { evaluateExpression(state.draft.outgoingExpression, it.currency, locale) }
        val incoming = to?.let { evaluateExpression(state.draft.incomingExpression, it.currency, locale) }
        return state.copy(
            draft = state.draft.copy(
                outgoingNormalized = outgoing?.first.orEmpty(),
                outgoingMinor = outgoing?.second,
                outgoingFormatted = outgoing?.third,
                incomingNormalized = incoming?.first.orEmpty(),
                incomingMinor = incoming?.second,
                incomingFormatted = incoming?.third,
            ),
        )
    }

    private fun evaluateExpression(value: String, currency: CurrencyCode, locale: Locale): Triple<String, Long, MoneyUiModel>? {
        val metadata = catalog.find(currency) ?: return null
        val evaluated = (evaluator.evaluate(value, locale, metadata) as? DomainResult.Success)?.value ?: return null
        val formatted = (formatter.format(MoneyFormatRequest(evaluated.roundedMoney, locale, AmountSemantic.TRANSFER, AmountVisibility.VISIBLE)) as DomainResult.Success).value
        return Triple(evaluated.expression.normalized, evaluated.roundedMoney.minor, formatted)
    }

    private fun amountToBase(
        state: SpecializedTransactionEditorState,
        account: AccountReferenceView,
        minor: Long,
        manualRateText: String,
    ): SpecializedAccountAmountDraft {
        val base = state.snapshot.baseCurrency
        if (account.currency == base) return SpecializedAccountAmountDraft(account.id, minor, minor, null)
        val manualRate = manualRateText.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
        val evidence = if (manualRate != null) manualEvidence(account.currency, base, manualRate) else requireNotNull(state.quotesToBase[account.currency]).evidence
        val sourceMetadata = requireNotNull(catalog.find(account.currency))
        val targetMetadata = requireNotNull(catalog.find(base))
        val conversion = (converter.convert(Money(minor, account.currency), targetMetadata, evidence, sourceMetadata) as DomainResult.Success).value
        require(conversion.target.minor > 0L)
        return SpecializedAccountAmountDraft(account.id, minor, conversion.target.minor, evidence)
    }

    private fun impliedAmount(account: AccountReferenceView, minor: Long, baseMinor: Long, base: CurrencyCode): SpecializedAccountAmountDraft {
        if (account.currency == base) {
            require(minor == baseMinor)
            return SpecializedAccountAmountDraft(account.id, minor, baseMinor, null)
        }
        val sourceMeta = requireNotNull(catalog.find(account.currency))
        val baseMeta = requireNotNull(catalog.find(base))
        val rate = BigDecimal.valueOf(baseMinor, baseMeta.fractionDigits)
            .divide(BigDecimal.valueOf(minor, sourceMeta.fractionDigits), MATH_CONTEXT)
        return SpecializedAccountAmountDraft(account.id, minor, baseMinor, impliedEvidence(account.currency, base, rate))
    }

    private fun manualEvidence(source: CurrencyCode, target: CurrencyCode, rate: BigDecimal): FxEvidence = evidence(source, target, rate, "manual", FxRateSource.MANUAL, true)

    private fun impliedEvidence(source: CurrencyCode, target: CurrencyCode, rate: BigDecimal): FxEvidence = evidence(source, target, rate, "dual-account-amounts", FxRateSource.IMPLIED_FROM_ACTUAL_AMOUNTS, false)

    private fun evidence(source: CurrencyCode, target: CurrencyCode, rate: BigDecimal, provider: String, rateSource: FxRateSource, manual: Boolean): FxEvidence = (FxEvidence.create(FxEvidenceInput(source, target, rate, (FxProvider.of(provider) as DomainResult.Success).value, null, null, rateSource, manual)) as DomainResult.Success).value

    private fun activeAccounts(snapshot: ReferenceDataSnapshot): List<AccountReferenceView> = snapshot.accounts.filter { it.status == EntityStatus.ACTIVE }.sortedBy(AccountReferenceView::sortOrder)

    private const val MAX_EXPRESSION = 256
    private const val MAX_RATE = 48
    private const val MAX_NOTE = 2_000
    private val MATH_CONTEXT = MathContext(34, RoundingMode.HALF_EVEN)
}

private fun app.ledger.finance.domain.TransactionKind.toSpecializedKind(): SpecializedTransactionKind? = when (this) {
    app.ledger.finance.domain.TransactionKind.TRANSFER -> SpecializedTransactionKind.TRANSFER
    app.ledger.finance.domain.TransactionKind.BALANCE_ADJUSTMENT -> SpecializedTransactionKind.BALANCE_ADJUSTMENT
    app.ledger.finance.domain.TransactionKind.FX_EXCHANGE -> SpecializedTransactionKind.FX_EXCHANGE
    app.ledger.finance.domain.TransactionKind.OPENING_BALANCE -> SpecializedTransactionKind.OPENING_BALANCE
    else -> null
}

private fun minorExpression(minor: Long, currency: CurrencyCode): String {
    val digits = requireNotNull(JvmLegalTenderCurrencyCatalog.create().find(currency)).fractionDigits
    return BigDecimal.valueOf(minor, digits).stripTrailingZeros().toPlainString()
}

private fun manualRate(accountMinor: Long, accountCurrency: CurrencyCode, baseMinor: Long, baseCurrency: CurrencyCode): String {
    if (accountCurrency == baseCurrency) return ""
    val catalog = JvmLegalTenderCurrencyCatalog.create()
    val accountDigits = requireNotNull(catalog.find(accountCurrency)).fractionDigits
    val baseDigits = requireNotNull(catalog.find(baseCurrency)).fractionDigits
    return BigDecimal.valueOf(baseMinor, baseDigits)
        .divide(BigDecimal.valueOf(accountMinor, accountDigits), MathContext.DECIMAL128)
        .stripTrailingZeros()
        .toPlainString()
}
