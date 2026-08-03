@file:Suppress("LongParameterList", "MagicNumber", "TooManyFunctions", "MaxLineLength", "ReturnCount", "ComplexCondition")

package app.ledger.feature.record

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.AmountSemantic
import app.ledger.core.money.AmountVisibility
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.LocaleCurrencyFormatter
import app.ledger.core.money.Money
import app.ledger.core.money.MoneyExpressionEvaluator
import app.ledger.core.money.MoneyFormatRequest
import app.ledger.core.money.MoneyUiModel
import app.ledger.finance.application.AccountReferenceView
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryRecentDefaultView
import app.ledger.finance.application.OrdinarySettlementShareDraft
import app.ledger.finance.application.OrdinaryTemplateView
import app.ledger.finance.application.OrdinaryTransactionEditView
import app.ledger.finance.application.OrdinaryTransactionEntrySnapshot
import app.ledger.finance.domain.EntityStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

public enum class RecordTab { EXPENSE, INCOME, OTHER }

public enum class RecordEditorMode { CREATE, EDIT, DUPLICATE, TEMPLATE, CANDIDATE }

public enum class RecordEntryOrigin { CATEGORY_GRID, EDIT_DETAIL, TEMPLATE, CANDIDATE, BATCH_ROW }

public sealed interface RecordReturnTarget {
    public data class CategoryGrid(val direction: OrdinaryDirection) : RecordReturnTarget
    public data class TransactionDetail(val transactionId: StableId) : RecordReturnTarget
    public data object CandidateList : RecordReturnTarget
    public data class BatchRow(val rowId: StableId) : RecordReturnTarget
}

public object RecordReturnPolicy {
    public fun afterSuccess(origin: RecordEntryOrigin, direction: OrdinaryDirection, transactionId: StableId, sourceId: StableId?): RecordReturnTarget = when (origin) {
        RecordEntryOrigin.CATEGORY_GRID, RecordEntryOrigin.TEMPLATE -> RecordReturnTarget.CategoryGrid(direction)
        RecordEntryOrigin.EDIT_DETAIL -> RecordReturnTarget.TransactionDetail(transactionId)
        RecordEntryOrigin.CANDIDATE -> RecordReturnTarget.CandidateList
        RecordEntryOrigin.BATCH_ROW -> RecordReturnTarget.BatchRow(requireNotNull(sourceId))
    }
}

public enum class RecordField {
    CATEGORY,
    AMOUNT,
    ACCOUNT,
    CARD,
    MERCHANT,
    OCCURRED_AT,
    PROJECT,
    SETTLEMENT,
    LOCATION,
    NOTE,
    ATTACHMENTS,
}

public enum class RecordDefaultSource {
    MANUAL,
    EDIT_SNAPSHOT,
    TEMPLATE,
    CATEGORY,
    RECENT_COMPATIBLE,
    CASH_FALLBACK,
    FIRST_ACTIVE_ACCOUNT,
    NONE,
}

public data class RecordFieldOrigin(val source: RecordDefaultSource, val explanationKey: String)

public enum class RecordEditorPresentation { LOADING, EDITING, VALIDATING, SAVING, SAVE_ERROR, REVISION_CONFLICT }

public data class RecordValidationError(val field: RecordField, val code: String)

/** Sensitive values live only in this in-memory model and are never encoded into a route or SavedState. */
public data class OrdinaryRecordDraft(
    val direction: OrdinaryDirection,
    val categoryId: StableId?,
    val expression: String,
    val normalizedExpression: String,
    val resultMinor: Long?,
    val result: MoneyUiModel?,
    val currencyCode: String,
    val accountId: StableId?,
    val cardId: StableId?,
    val merchantId: StableId?,
    val occurredAt: Instant,
    val zoneId: ZoneId,
    val projectId: StableId?,
    val settlementEnabled: Boolean,
    val settlementActivityId: StableId?,
    val settlementShares: List<OrdinarySettlementShareDraft>,
    val locationRecordId: StableId?,
    val note: String,
    val attachmentIds: List<StableId>,
    val origins: Map<RecordField, RecordFieldOrigin>,
    val touched: Set<RecordField>,
) {
    public val dirty: Boolean get() = touched.isNotEmpty()
}

public data class OrdinaryRecordEditorState(
    val mode: RecordEditorMode,
    val transactionId: StableId?,
    val expectedRevisionId: StableId?,
    val sourceReferenceId: StableId?,
    val snapshot: OrdinaryTransactionEntrySnapshot,
    val draft: OrdinaryRecordDraft,
    val presentation: RecordEditorPresentation = RecordEditorPresentation.EDITING,
    val errors: List<RecordValidationError> = emptyList(),
    val sanitizedFailureCode: String? = null,
    val showUnsavedDialog: Boolean = false,
    val attachmentImporting: Boolean = false,
    val attachmentFailureCode: String? = null,
    val uncommittedAttachmentIds: Set<StableId> = emptySet(),
)

public sealed interface OrdinaryRecordLoadState {
    public data object Loading : OrdinaryRecordLoadState
    public data class Content(
        val snapshot: OrdinaryTransactionEntrySnapshot,
        val tab: RecordTab = RecordTab.EXPENSE,
        val search: String = "",
        val selectedCategoryId: StableId? = null,
        val editor: OrdinaryRecordEditorState? = null,
        val expenseScrollIndex: Int = 0,
        val incomeScrollIndex: Int = 0,
    ) : OrdinaryRecordLoadState
    public data class Failure(val code: String) : OrdinaryRecordLoadState
}

public object OrdinaryRecordPolicy {
    private val catalog = JvmLegalTenderCurrencyCatalog.create()
    private val evaluator = MoneyExpressionEvaluator()
    private val formatter = LocaleCurrencyFormatter(catalog)

    public fun createEditor(
        snapshot: OrdinaryTransactionEntrySnapshot,
        mode: RecordEditorMode,
        direction: OrdinaryDirection,
        categoryId: StableId?,
        sourceId: StableId?,
        now: Instant,
        zoneId: ZoneId,
        locale: Locale,
    ): OrdinaryRecordEditorState {
        val edit = snapshot.editing.takeIf { mode == RecordEditorMode.EDIT || mode == RecordEditorMode.DUPLICATE }
        val template = snapshot.templates.singleOrNull { it.id == sourceId }.takeIf { mode == RecordEditorMode.TEMPLATE }
        val actualDirection = edit?.direction ?: template?.direction ?: direction
        val actualCategory = edit?.categoryId ?: template?.categoryId ?: categoryId
        val defaults = defaults(snapshot, actualDirection, actualCategory, template, edit)
        val account = snapshot.references.accounts.singleOrNull { it.id == defaults.accountId }
        val expression = edit?.expression ?: template?.amountExpression.orEmpty()
        val draft = OrdinaryRecordDraft(
            direction = actualDirection,
            categoryId = actualCategory,
            expression = expression,
            normalizedExpression = "",
            resultMinor = edit?.userMinor,
            result = null,
            currencyCode = edit?.userCurrency?.value ?: account?.currency?.value ?: template?.currency?.value ?: snapshot.references.baseCurrency.value,
            accountId = defaults.accountId,
            cardId = defaults.cardId,
            merchantId = edit?.merchantId ?: template?.merchantId ?: defaults.merchantId,
            occurredAt = if (mode == RecordEditorMode.EDIT) edit?.occurredAt ?: now else now,
            zoneId = if (mode == RecordEditorMode.EDIT) edit?.zoneId ?: zoneId else zoneId,
            projectId = edit?.projectId ?: template?.projectId,
            settlementEnabled = edit?.settlementActivityId != null || template?.settlementActivityId != null,
            settlementActivityId = edit?.settlementActivityId ?: template?.settlementActivityId,
            settlementShares = edit?.settlementShares.orEmpty(),
            locationRecordId = edit?.locationRecordId,
            note = edit?.note ?: template?.noteTemplate.orEmpty(),
            attachmentIds = if (mode == RecordEditorMode.EDIT) edit?.attachmentIds.orEmpty() else emptyList(),
            origins = defaults.origins + buildMap {
                if (edit != null) RecordField.entries.forEach { put(it, RecordFieldOrigin(RecordDefaultSource.EDIT_SNAPSHOT, "edit_snapshot")) }
                if (template != null) put(RecordField.AMOUNT, RecordFieldOrigin(RecordDefaultSource.TEMPLATE, "template"))
            },
            touched = emptySet(),
        )
        return OrdinaryRecordEditorState(
            mode,
            if (mode == RecordEditorMode.EDIT) edit?.transactionId else null,
            if (mode == RecordEditorMode.EDIT) edit?.revisionId else null,
            sourceId,
            snapshot,
            evaluate(draft, locale),
        )
    }

    public fun changeExpression(state: OrdinaryRecordEditorState, value: String, locale: Locale): OrdinaryRecordEditorState = state.copy(
        draft = evaluate(state.draft.copy(expression = value.take(MAX_EXPRESSION), touched = state.draft.touched + RecordField.AMOUNT, origins = state.draft.origins + (RecordField.AMOUNT to manualOrigin())), locale),
        presentation = RecordEditorPresentation.EDITING,
        errors = state.errors.filterNot { it.field == RecordField.AMOUNT },
    )

    public fun appendOperator(state: OrdinaryRecordEditorState, operator: String, locale: Locale): OrdinaryRecordEditorState {
        val expression = if (operator == "DELETE") state.draft.expression.dropLast(1) else state.draft.expression + normalizeOperator(operator)
        return changeExpression(state, expression, locale)
    }

    public fun selectCategory(state: OrdinaryRecordEditorState, id: StableId): OrdinaryRecordEditorState {
        val category = state.snapshot.references.categories.single { it.id == id }
        require(category.direction.name == state.draft.direction.name)
        val updated = state.draft.copy(categoryId = id, touched = state.draft.touched + RecordField.CATEGORY, origins = state.draft.origins + (RecordField.CATEGORY to manualOrigin()))
        return applyCompatibleDefaults(state.copy(draft = updated), category.defaultAccountId, category.defaultCardId, category.defaultMerchantId)
    }

    public fun selectAccount(state: OrdinaryRecordEditorState, id: StableId, locale: Locale): OrdinaryRecordEditorState {
        val account = activeAccounts(state.snapshot).single { it.id == id }
        val compatibleCard = state.draft.cardId?.takeIf { card -> state.snapshot.references.cards.any { it.id == card && it.accountId == id && it.status == EntityStatus.ACTIVE } }
            ?: state.snapshot.references.categories.singleOrNull { it.id == state.draft.categoryId }?.defaultCardId?.takeIf { card -> state.snapshot.references.cards.any { it.id == card && it.accountId == id && it.status == EntityStatus.ACTIVE } }
            ?: recent(state.snapshot, state.draft.direction, state.draft.categoryId)?.cardId?.takeIf { card -> state.snapshot.references.cards.any { it.id == card && it.accountId == id && it.status == EntityStatus.ACTIVE } }
        val draft = state.draft.copy(
            accountId = id,
            cardId = compatibleCard,
            currencyCode = account.currency.value,
            touched = state.draft.touched + RecordField.ACCOUNT,
            origins = state.draft.origins + (RecordField.ACCOUNT to manualOrigin()) + (RecordField.CARD to RecordFieldOrigin(if (compatibleCard == null) RecordDefaultSource.NONE else RecordDefaultSource.RECENT_COMPATIBLE, "account_compatible")),
        )
        return state.copy(draft = evaluate(draft, locale), errors = state.errors.filterNot { it.field in setOf(RecordField.ACCOUNT, RecordField.CARD) })
    }

    public fun selectCard(state: OrdinaryRecordEditorState, id: StableId?): OrdinaryRecordEditorState {
        require(id == null || state.snapshot.references.cards.any { it.id == id && it.accountId == state.draft.accountId && it.status == EntityStatus.ACTIVE })
        return state.copy(draft = state.draft.copy(cardId = id, touched = state.draft.touched + RecordField.CARD, origins = state.draft.origins + (RecordField.CARD to manualOrigin())))
    }

    public fun update(state: OrdinaryRecordEditorState, field: RecordField, value: StableId?): OrdinaryRecordEditorState {
        val draft = when (field) {
            RecordField.MERCHANT -> state.draft.copy(merchantId = value)
            RecordField.PROJECT -> state.draft.copy(projectId = value)
            RecordField.LOCATION -> state.draft.copy(locationRecordId = value)
            else -> error("unsupported id field")
        }
        return state.copy(draft = draft.copy(touched = draft.touched + field, origins = draft.origins + (field to manualOrigin())), errors = state.errors.filterNot { it.field == field })
    }

    public fun updateNote(state: OrdinaryRecordEditorState, value: String): OrdinaryRecordEditorState = state.copy(
        draft = state.draft.copy(note = value.take(MAX_NOTE), touched = state.draft.touched + RecordField.NOTE, origins = state.draft.origins + (RecordField.NOTE to manualOrigin())),
    )

    public fun setSettlementEnabled(state: OrdinaryRecordEditorState, enabled: Boolean): OrdinaryRecordEditorState = state.copy(
        draft = state.draft.copy(
            settlementEnabled = enabled,
            settlementActivityId = if (enabled) state.draft.settlementActivityId else null,
            settlementShares = if (enabled) state.draft.settlementShares else emptyList(),
            touched = state.draft.touched + RecordField.SETTLEMENT,
            origins = state.draft.origins + (RecordField.SETTLEMENT to manualOrigin()),
        ),
    )

    public fun selectSettlementActivity(state: OrdinaryRecordEditorState, id: StableId): OrdinaryRecordEditorState {
        val activity = state.snapshot.settlementActivities.single { it.id == id && it.active }
        val total = state.draft.resultMinor ?: 0L
        val self = activity.participants.singleOrNull { it.isSelf }
        val base = if (activity.participants.isEmpty()) 0L else total / activity.participants.size
        val remainder = if (activity.participants.isEmpty()) 0L else total % activity.participants.size
        val shares = activity.participants.mapIndexed { index, participant ->
            val owed = base + if (index.toLong() < remainder) 1L else 0L
            OrdinarySettlementShareDraft(participant.id, if (participant.id == self?.id) total else 0L, owed, BigDecimal.ONE, 0L)
        }
        return state.copy(draft = state.draft.copy(settlementActivityId = id, settlementShares = shares, touched = state.draft.touched + RecordField.SETTLEMENT, origins = state.draft.origins + (RecordField.SETTLEMENT to manualOrigin())))
    }

    public fun updateOccurredAt(state: OrdinaryRecordEditorState, dateMillis: Long, hour: Int, minute: Int): OrdinaryRecordEditorState {
        val localDate = java.time.Instant.ofEpochMilli(dateMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        val occurredAt = localDate.atTime(hour, minute).atZone(state.draft.zoneId).toInstant()
        return state.copy(
            draft = state.draft.copy(
                occurredAt = occurredAt,
                touched = state.draft.touched + RecordField.OCCURRED_AT,
                origins = state.draft.origins + (RecordField.OCCURRED_AT to manualOrigin()),
            ),
            presentation = RecordEditorPresentation.EDITING,
        )
    }

    public fun validate(state: OrdinaryRecordEditorState): OrdinaryRecordEditorState {
        val errors = buildList {
            if (state.draft.categoryId == null) add(RecordValidationError(RecordField.CATEGORY, "CATEGORY_REQUIRED"))
            if (state.draft.resultMinor == null || state.draft.resultMinor <= 0L) add(RecordValidationError(RecordField.AMOUNT, "AMOUNT_INVALID"))
            if (state.draft.accountId == null) add(RecordValidationError(RecordField.ACCOUNT, "ACCOUNT_REQUIRED"))
            if (state.attachmentImporting) add(RecordValidationError(RecordField.ATTACHMENTS, "ATTACHMENT_IMPORTING"))
            if (state.draft.settlementEnabled) {
                val total = state.draft.resultMinor ?: 0L
                val shares = state.draft.settlementShares
                val activity = state.snapshot.settlementActivities.singleOrNull { it.id == state.draft.settlementActivityId }
                if (activity != null && activity.currency.value != state.draft.currencyCode) {
                    add(RecordValidationError(RecordField.SETTLEMENT, "SETTLEMENT_CURRENCY_MISMATCH"))
                }
                if (state.draft.settlementActivityId == null || shares.sumOf { it.paidMinor } != total || shares.sumOf { it.owedMinor } != total || shares.count { it.paidMinor > 0 } != 1) {
                    add(RecordValidationError(RecordField.SETTLEMENT, "SETTLEMENT_IMBALANCED"))
                }
            }
        }
        return state.copy(presentation = if (errors.isEmpty()) RecordEditorPresentation.EDITING else RecordEditorPresentation.VALIDATING, errors = errors)
    }

    private fun evaluate(draft: OrdinaryRecordDraft, locale: Locale): OrdinaryRecordDraft {
        val currency = app.ledger.core.money.CurrencyCode.parse(draft.currencyCode)
        if (currency !is DomainResult.Success || draft.expression.isBlank()) return draft.copy(normalizedExpression = "", resultMinor = null, result = null)
        val metadata = catalog.require(currency.value)
        if (metadata !is DomainResult.Success) return draft.copy(normalizedExpression = "", resultMinor = null, result = null)
        return when (val evaluated = evaluator.evaluate(draft.expression, locale, metadata.value)) {
            is DomainResult.Failure -> draft.copy(normalizedExpression = "", resultMinor = null, result = null)
            is DomainResult.Success -> {
                val formatted = formatter.format(MoneyFormatRequest(evaluated.value.roundedMoney, locale, if (draft.direction == OrdinaryDirection.EXPENSE) AmountSemantic.OUTFLOW else AmountSemantic.INFLOW, AmountVisibility.VISIBLE))
                draft.copy(normalizedExpression = evaluated.value.expression.normalized, resultMinor = evaluated.value.roundedMoney.minor, result = (formatted as? DomainResult.Success)?.value)
            }
        }
    }

    private data class Defaults(val accountId: StableId?, val cardId: StableId?, val merchantId: StableId?, val origins: Map<RecordField, RecordFieldOrigin>)

    private fun defaults(snapshot: OrdinaryTransactionEntrySnapshot, direction: OrdinaryDirection, categoryId: StableId?, template: OrdinaryTemplateView?, edit: OrdinaryTransactionEditView?): Defaults {
        if (edit != null) return Defaults(edit.accountId, edit.cardId, edit.merchantId, emptyMap())
        val active = activeAccounts(snapshot)
        val category = snapshot.references.categories.singleOrNull { it.id == categoryId }
        val recent = recent(snapshot, direction, categoryId)
        val templateAccount = template?.accountId?.takeIf { id -> active.any { it.id == id } }
        val categoryAccount = category?.defaultAccountId?.takeIf { id -> active.any { it.id == id } }
        val recentAccount = recent?.accountId?.takeIf { id -> active.any { it.id == id } }
        val cash = active.firstOrNull { it.type == app.ledger.finance.domain.UserAccountType.CASH }
        val accountId = templateAccount ?: categoryAccount ?: recentAccount ?: cash?.id ?: active.firstOrNull()?.id
        val source = when (accountId) {
            templateAccount -> RecordDefaultSource.TEMPLATE
            categoryAccount -> RecordDefaultSource.CATEGORY
            recentAccount -> RecordDefaultSource.RECENT_COMPATIBLE
            cash?.id -> RecordDefaultSource.CASH_FALLBACK
            null -> RecordDefaultSource.NONE
            else -> RecordDefaultSource.FIRST_ACTIVE_ACCOUNT
        }
        val candidateCard = template?.cardId ?: category?.defaultCardId ?: recent?.cardId
        val cardId = candidateCard?.takeIf { id -> snapshot.references.cards.any { it.id == id && it.accountId == accountId && it.status == EntityStatus.ACTIVE } }
        return Defaults(
            accountId,
            cardId,
            template?.merchantId ?: category?.defaultMerchantId,
            mapOf(
                RecordField.ACCOUNT to RecordFieldOrigin(source, source.name.lowercase()),
                RecordField.CARD to RecordFieldOrigin(if (cardId == null) RecordDefaultSource.NONE else source, if (cardId == null) "no_compatible_card" else source.name.lowercase()),
                RecordField.MERCHANT to RecordFieldOrigin(
                    if (template?.merchantId != null) {
                        RecordDefaultSource.TEMPLATE
                    } else if (category?.defaultMerchantId != null) {
                        RecordDefaultSource.CATEGORY
                    } else {
                        RecordDefaultSource.NONE
                    },
                    "merchant_default",
                ),
            ),
        )
    }

    private fun applyCompatibleDefaults(state: OrdinaryRecordEditorState, categoryAccount: StableId?, categoryCard: StableId?, categoryMerchant: StableId?): OrdinaryRecordEditorState {
        var draft = state.draft
        if (RecordField.ACCOUNT !in draft.touched && categoryAccount != null && activeAccounts(state.snapshot).any { it.id == categoryAccount }) {
            draft = draft.copy(accountId = categoryAccount, origins = draft.origins + (RecordField.ACCOUNT to RecordFieldOrigin(RecordDefaultSource.CATEGORY, "category")))
        }
        if (RecordField.CARD !in draft.touched) {
            val card = categoryCard?.takeIf { id -> state.snapshot.references.cards.any { it.id == id && it.accountId == draft.accountId && it.status == EntityStatus.ACTIVE } }
            draft = draft.copy(cardId = card, origins = draft.origins + (RecordField.CARD to RecordFieldOrigin(if (card == null) RecordDefaultSource.NONE else RecordDefaultSource.CATEGORY, "category_compatible")))
        }
        if (RecordField.MERCHANT !in draft.touched && categoryMerchant != null) {
            draft = draft.copy(merchantId = categoryMerchant, origins = draft.origins + (RecordField.MERCHANT to RecordFieldOrigin(RecordDefaultSource.CATEGORY, "category")))
        }
        return state.copy(draft = draft)
    }

    private fun recent(snapshot: OrdinaryTransactionEntrySnapshot, direction: OrdinaryDirection, categoryId: StableId?): OrdinaryRecentDefaultView? = snapshot.recentDefaults.firstOrNull { it.direction == direction && it.categoryId == categoryId }
    private fun activeAccounts(snapshot: OrdinaryTransactionEntrySnapshot): List<AccountReferenceView> = snapshot.references.accounts.filter { it.status == EntityStatus.ACTIVE }.sortedWith(compareBy(AccountReferenceView::sortOrder, AccountReferenceView::name))
    private fun manualOrigin() = RecordFieldOrigin(RecordDefaultSource.MANUAL, "manual")
    private fun normalizeOperator(value: String): String = when (value) {
        "−" -> "-"
        "×" -> "*"
        "÷" -> "/"
        else -> value
    }

    private const val MAX_EXPRESSION = 256
    private const val MAX_NOTE = 4_000
}
