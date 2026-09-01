@file:Suppress("MagicNumber", "TooManyFunctions")

package app.ledger.feature.planning

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.AmountSemantic
import app.ledger.core.money.AmountVisibility
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.LocaleCurrencyFormatter
import app.ledger.core.money.Money
import app.ledger.core.money.MoneyFormatRequest
import app.ledger.core.money.MoneyUiModel
import app.ledger.finance.application.BudgetCategoryLimitDraft
import app.ledger.finance.application.BudgetSnapshot
import app.ledger.finance.domain.BudgetConstraintPolicy
import app.ledger.finance.domain.BudgetConstraintReport
import app.ledger.finance.domain.CategoryBudgetLimit
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

public enum class BudgetPresentation {
    CONFIGURED,
    NOT_CONFIGURED,
    RECALCULATING,
    HISTORICAL,
    FUTURE,
    EDITING,
    CONSTRAINT_ERROR,
    SAVING,
    HISTORY_RECALCULATION_WARNING,
    CONTENT,
    EMPTY,
    INVALID,
    SINGLE_REVISION,
    CREATE,
    EDIT,
    FAILED,
}

public data class BudgetEditorDraft(
    val totalText: String,
    val categoryTexts: Map<StableId, String>,
    val templateName: String = "",
    val dirty: Boolean = false,
)

public data class BudgetEditorValidation(
    val totalMinor: Long?,
    val limits: List<BudgetCategoryLimitDraft>,
    val report: BudgetConstraintReport?,
    val invalidCategoryIds: Set<StableId>,
) {
    public val valid: Boolean = totalMinor != null && invalidCategoryIds.isEmpty() && report?.valid == true
}

public data class BudgetFeatureState(
    val snapshot: BudgetSnapshot,
    val presentation: BudgetPresentation,
    val editor: BudgetEditorDraft,
    val validation: BudgetEditorValidation,
    val selectedCategoryId: StableId? = null,
    val selectedTemplateId: StableId? = null,
    val adjustmentAmountText: String = "",
    val adjustmentSourceCategoryId: StableId? = snapshot.categories.firstOrNull()?.id,
    val adjustmentTargetCategoryId: StableId? = snapshot.categories.drop(1).firstOrNull()?.id,
    val failureCode: String? = null,
)

public sealed interface BudgetLoadState {
    public data object Loading : BudgetLoadState
    public data class Content(val state: BudgetFeatureState) : BudgetLoadState
    public data class Failure(val code: String) : BudgetLoadState
}

public object BudgetPolicy {
    private val catalog = JvmLegalTenderCurrencyCatalog.create()
    private val formatter = LocaleCurrencyFormatter(catalog)

    public fun create(snapshot: BudgetSnapshot, presentation: BudgetPresentation = homePresentation(snapshot)): BudgetFeatureState {
        val total = snapshot.currentRevision?.totalBaseMinor
        val limits = snapshot.currentRevision?.limits.orEmpty().associate { it.categoryId to minorText(it.amountBaseMinor, snapshot) }
        val editor = BudgetEditorDraft(total?.let { minorText(it, snapshot) }.orEmpty(), limits)
        val initial = BudgetFeatureState(snapshot, presentation, editor, BudgetEditorValidation(null, emptyList(), null, emptySet()))
        return validate(initial)
    }

    public fun edit(state: BudgetFeatureState): BudgetFeatureState = state.copy(
        presentation = if (state.snapshot.historical) BudgetPresentation.HISTORY_RECALCULATION_WARNING else BudgetPresentation.EDITING,
    )

    public fun updateTotal(state: BudgetFeatureState, value: String): BudgetFeatureState = validate(
        state.copy(editor = state.editor.copy(totalText = value.take(MAX_AMOUNT_TEXT), dirty = true), presentation = BudgetPresentation.EDITING),
    )

    public fun updateCategory(state: BudgetFeatureState, categoryId: StableId, value: String): BudgetFeatureState = validate(
        state.copy(
            editor = state.editor.copy(categoryTexts = state.editor.categoryTexts + (categoryId to value.take(MAX_AMOUNT_TEXT)), dirty = true),
            selectedCategoryId = categoryId,
            presentation = BudgetPresentation.EDITING,
        ),
    )

    public fun updateTemplateName(state: BudgetFeatureState, value: String): BudgetFeatureState = state.copy(
        editor = state.editor.copy(templateName = value.take(MAX_NAME), dirty = true),
    )

    public fun updateAdjustmentAmount(state: BudgetFeatureState, value: String): BudgetFeatureState = state.copy(
        adjustmentAmountText = value.take(MAX_AMOUNT_TEXT),
        presentation = if (parseMinor(value, state.snapshot) == null) BudgetPresentation.INVALID else BudgetPresentation.EDITING,
    )

    public fun selectNextAdjustmentSource(state: BudgetFeatureState, archivedOnly: Boolean = false): BudgetFeatureState = state.copy(
        adjustmentSourceCategoryId = nextCategory(state, state.adjustmentSourceCategoryId, state.adjustmentTargetCategoryId, archivedOnly),
    )

    public fun selectNextAdjustmentTarget(state: BudgetFeatureState): BudgetFeatureState = state.copy(
        adjustmentTargetCategoryId = nextCategory(state, state.adjustmentTargetCategoryId, state.adjustmentSourceCategoryId),
    )

    public fun selectAdjustmentSource(state: BudgetFeatureState, categoryId: StableId): BudgetFeatureState = state.copy(
        adjustmentSourceCategoryId = categoryId,
        adjustmentTargetCategoryId = state.adjustmentTargetCategoryId.takeUnless { it == categoryId },
    )

    public fun selectAdjustmentTarget(state: BudgetFeatureState, categoryId: StableId): BudgetFeatureState = state.copy(
        adjustmentTargetCategoryId = categoryId,
    )

    public fun adjustmentMinor(state: BudgetFeatureState): Long? = parseMinor(state.adjustmentAmountText, state.snapshot)?.takeIf { it > 0L }

    public fun money(state: BudgetFeatureState, minor: Long, locale: Locale): MoneyUiModel {
        val request = MoneyFormatRequest(
            Money(minor, state.snapshot.baseCurrency),
            locale,
            AmountSemantic.NEUTRAL,
            AmountVisibility.VISIBLE,
        )
        return (formatter.format(request) as DomainResult.Success).value
    }

    public fun validate(state: BudgetFeatureState): BudgetFeatureState {
        val total = parseMinor(state.editor.totalText, state.snapshot)
        val invalid = mutableSetOf<StableId>()
        val drafts = state.editor.categoryTexts.mapNotNull { (id, text) ->
            val amount = parseMinor(text, state.snapshot)
            if (amount == null) {
                invalid += id
                null
            } else {
                BudgetCategoryLimitDraft(id, amount)
            }
        }
        val refs = state.snapshot.categories.associateBy { it.id }
        val limits = drafts.mapNotNull { draft ->
            val ref = refs[draft.categoryId] ?: return@mapNotNull null
            CategoryBudgetLimit(
                app.ledger.finance.domain.CategoryId(ref.id),
                app.ledger.finance.domain.CategoryId(ref.rootCategoryId),
                ref.parentCategoryId?.let { app.ledger.finance.domain.CategoryId(it) },
                ref.depth,
                draft.amountBaseMinor,
            )
        }
        val report = total?.let { value ->
            when (val result = BudgetConstraintPolicy.evaluate(value, limits)) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> constraintReport(value, limits)
            }
        }
        val validation = BudgetEditorValidation(total, drafts, report, invalid)
        val presentation = if (state.presentation in setOf(BudgetPresentation.EDITING, BudgetPresentation.CONSTRAINT_ERROR) && !validation.valid) {
            BudgetPresentation.CONSTRAINT_ERROR
        } else {
            state.presentation
        }
        return state.copy(validation = validation, presentation = presentation)
    }

    private fun constraintReport(total: Long, limits: List<CategoryBudgetLimit>): BudgetConstraintReport {
        val roots = limits.filter { it.depth == 1 }
        val rootSum = roots.fold(0L) { acc, value -> Math.addExact(acc, value.amountBaseMinor) }
        val totalMeter = app.ledger.finance.domain.BudgetConstraintMeter(null, rootSum, total, maxOf(rootSum - total, 0L))
        val parents = roots.map { root ->
            val sum = limits.filter { it.parentCategoryId == root.categoryId }.fold(0L) { acc, value -> Math.addExact(acc, value.amountBaseMinor) }
            app.ledger.finance.domain.BudgetConstraintMeter(root.categoryId, sum, root.amountBaseMinor, maxOf(sum - root.amountBaseMinor, 0L))
        }
        return BudgetConstraintReport(totalMeter, parents)
    }

    private fun parseMinor(text: String, snapshot: BudgetSnapshot): Long? = runCatching {
        val metadata = requireNotNull(catalog.find(snapshot.baseCurrency))
        val normalized = text.trim().replace(',', '.')
        if (normalized.isEmpty()) return null
        BigDecimal(normalized).movePointRight(metadata.fractionDigits).setScale(0, RoundingMode.HALF_EVEN).longValueExact().takeIf { it >= 0L }
    }.getOrNull()

    private fun minorText(minor: Long, snapshot: BudgetSnapshot): String {
        val scale = requireNotNull(catalog.find(snapshot.baseCurrency)).fractionDigits
        return BigDecimal.valueOf(minor, scale).stripTrailingZeros().toPlainString()
    }

    private fun homePresentation(snapshot: BudgetSnapshot): BudgetPresentation = when {
        snapshot.projectionReadiness == app.ledger.finance.application.BudgetProjectionReadiness.RECALCULATING -> BudgetPresentation.RECALCULATING
        snapshot.projectionReadiness == app.ledger.finance.application.BudgetProjectionReadiness.FAILED -> BudgetPresentation.FAILED
        !snapshot.configured -> BudgetPresentation.NOT_CONFIGURED
        snapshot.historical -> BudgetPresentation.HISTORICAL
        snapshot.future -> BudgetPresentation.FUTURE
        else -> BudgetPresentation.CONFIGURED
    }

    private fun nextCategory(
        state: BudgetFeatureState,
        current: StableId?,
        excluded: StableId?,
        archivedOnly: Boolean = false,
    ): StableId? {
        val targetStatus = if (archivedOnly) {
            app.ledger.finance.domain.EntityStatus.ARCHIVED
        } else {
            app.ledger.finance.domain.EntityStatus.ACTIVE
        }
        val ids = state.snapshot.categories.filter { it.status == targetStatus && it.id != excluded }.map { it.id }
        if (ids.isEmpty()) return null
        val index = ids.indexOf(current)
        return ids[(if (index < 0) 0 else index + 1) % ids.size]
    }

    private const val MAX_AMOUNT_TEXT = 32
    private const val MAX_NAME = 80
}
