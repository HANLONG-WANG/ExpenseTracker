@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "MagicNumber",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
)

package app.ledger.feature.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.designsystem.AmountSize
import app.ledger.core.designsystem.AmountText
import app.ledger.core.designsystem.FormSection
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChip
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerProgressIndicator
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.finance.application.BudgetCompositionView
import app.ledger.finance.domain.BudgetAdjustmentKind
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
public fun BudgetDestination(
    screenId: String,
    state: BudgetLoadState,
    encodedArguments: Map<String, String>,
    onAction: (BudgetScreenAction) -> Unit,
) {
    val actions = budgetActions(onAction)
    if (state === BudgetLoadState.Loading) {
        LedgerLoadingState(Modifier.fillMaxSize(), stringResource(R.string.budget_loading))
        return
    }
    if (state is BudgetLoadState.Failure) {
        LedgerErrorState(
            UiErrorCode(state.code),
            stringResource(R.string.budget_load_failed),
            actions.onRetry,
            Modifier.fillMaxSize(),
        )
        return
    }
    val content = (state as BudgetLoadState.Content).state
    when (screenId) {
        "BUD-001" -> BudgetHome(content, actions)
        "BUD-002" -> BudgetEditor(content, actions, false)
        "BUD-003" -> BudgetCategoryEditor(content, encodedArguments["categoryId"], actions)
        "BUD-004" -> BudgetAdjustments(content, actions)
        "BUD-005" -> BudgetAdjustmentEditor(content, encodedArguments["type"], actions)
        "BUD-006" -> BudgetHistory(content, actions)
        "BUD-007" -> BudgetTemplates(content, actions)
        "BUD-008" -> BudgetEditor(content, actions, true)
        else -> LedgerErrorState(
            UiErrorCode("BUDGET_SCREEN_UNKNOWN"),
            stringResource(R.string.budget_load_failed),
            actions.onRetry,
        )
    }
}

@Composable
private fun BudgetHome(state: BudgetFeatureState, actions: BudgetActions) {
    val locale = LocalLocale.current.platformLocale
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.BUDGET_HOME),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LedgerButton(
                    stringResource(R.string.budget_previous_month),
                    { actions.onMonth(state.snapshot.month.minusMonths(1)) },
                    variant = LedgerButtonVariant.TEXT,
                )
                LedgerText(state.snapshot.month.toString(), LedgerTextRole.TITLE)
                LedgerButton(
                    stringResource(R.string.budget_next_month),
                    { actions.onMonth(state.snapshot.month.plusMonths(1)) },
                    variant = LedgerButtonVariant.TEXT,
                )
            }
        }
        if (state.presentation == BudgetPresentation.RECALCULATING) {
            item { LedgerBanner(stringResource(R.string.budget_recalculating), LedgerBannerVariant.INFO) }
        }
        if (state.presentation == BudgetPresentation.FAILED) {
            item {
                LedgerBanner(
                    stringResource(R.string.budget_recalculation_failed),
                    LedgerBannerVariant.DANGER,
                    actionLabel = stringResource(R.string.budget_operation_center),
                    onAction = actions.onOperations,
                )
            }
        }
        if (state.snapshot.historical) {
            item { LedgerBanner(stringResource(R.string.budget_historical), LedgerBannerVariant.NEUTRAL) }
        }
        if (state.snapshot.future) {
            item { LedgerBanner(stringResource(R.string.budget_future), LedgerBannerVariant.INFO) }
        }
        if (!state.snapshot.configured) {
            item {
                LedgerEmptyState(
                    stringResource(R.string.budget_not_configured),
                    stringResource(R.string.budget_not_configured_body),
                    stringResource(R.string.budget_create),
                    actions.onEdit,
                )
            }
        } else if (state.presentation != BudgetPresentation.FAILED) {
            val total = state.snapshot.composition.singleOrNull { it.categoryId == null }
            if (total != null) {
                item { BudgetHero(state, total) }
                item {
                    val daily = state.snapshot.dailyAvailable
                    LedgerCard(Modifier.fillMaxWidth().testTag(LedgerTestTags.BUDGET_DAILY_AVAILABLE)) {
                        Column(
                            Modifier.padding(LedgerTheme.spacing.md),
                            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
                        ) {
                            LedgerText(stringResource(R.string.budget_daily_available), LedgerTextRole.SECTION)
                            if (daily == null) {
                                LedgerText(stringResource(R.string.budget_daily_unavailable), LedgerTextRole.SUPPORTING)
                            } else {
                                AmountText(
                                    BudgetPolicy.money(state, daily.dailyAvailableBaseMinor, locale),
                                    AmountSize.LARGE,
                                )
                                LedgerText(
                                    stringResource(
                                        R.string.budget_reserved_formula,
                                        BudgetPolicy.money(
                                            state,
                                            daily.reservedRecurrenceBaseMinor,
                                            locale,
                                        ).formatted,
                                        daily.remainingDayCount,
                                    ),
                                    LedgerTextRole.SUPPORTING,
                                )
                            }
                        }
                    }
                }
            }
            item { LedgerText(stringResource(R.string.budget_categories), LedgerTextRole.SECTION) }
            items(
                state.snapshot.composition.filter { it.categoryId != null },
                key = { it.categoryId.toString() },
            ) { row ->
                BudgetCategoryRow(state, row, actions)
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
                    LedgerButton(stringResource(R.string.budget_edit), actions.onEdit, Modifier.weight(1f))
                    LedgerButton(
                        stringResource(R.string.budget_adjustments),
                        { actions.onNavigate("BUD-004", null, null) },
                        Modifier.weight(1f),
                        LedgerButtonVariant.SECONDARY,
                    )
                }
            }
            item {
                LedgerButton(
                    stringResource(R.string.budget_history),
                    { actions.onNavigate("BUD-006", null, null) },
                    Modifier.fillMaxWidth(),
                    LedgerButtonVariant.TEXT,
                )
            }
            item {
                LedgerButton(
                    stringResource(R.string.budget_templates),
                    { actions.onNavigate("BUD-007", null, null) },
                    Modifier.fillMaxWidth(),
                    LedgerButtonVariant.TEXT,
                )
            }
        }
    }
}

@Composable
private fun BudgetHero(state: BudgetFeatureState, total: BudgetCompositionView) {
    val locale = LocalLocale.current.platformLocale
    LedgerCard(Modifier.fillMaxWidth().testTag(LedgerTestTags.BUDGET_COMPOSITION)) {
        Column(
            Modifier.padding(LedgerTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
        ) {
            val title = if (total.remainingMinor >= 0L) {
                stringResource(R.string.budget_remaining)
            } else {
                stringResource(R.string.budget_exceeded)
            }
            LedgerText(title, LedgerTextRole.SECTION)
            AmountText(BudgetPolicy.money(state, total.remainingMinor, locale), AmountSize.HERO)
            CompositionLine(R.string.budget_base_row, total.baseMinor, state)
            CompositionLine(R.string.budget_rollover_row, total.rolloverMinor, state)
            CompositionLine(R.string.budget_adjustment_row, total.adjustmentMinor, state)
            CompositionLine(R.string.budget_used_row, total.usedMinor, state)
            val progress = if (total.availableMinor <= 0L) {
                null
            } else {
                total.usedMinor.toFloat() / total.availableMinor.toFloat()
            }
            LedgerProgressIndicator(
                progress,
                accessibleText = stringResource(
                    R.string.budget_progress_accessible,
                    total.usedMinor,
                    total.availableMinor,
                ),
            )
            if (total.exceededMinor > 0L) {
                LedgerBanner(
                    stringResource(
                        R.string.budget_exceeded_amount,
                        BudgetPolicy.money(state, total.exceededMinor, locale).formatted,
                    ),
                    LedgerBannerVariant.DANGER,
                )
            }
        }
    }
}

@Composable
private fun CompositionLine(resourceId: Int, amount: Long, state: BudgetFeatureState) {
    val formatted = BudgetPolicy.money(state, amount, LocalLocale.current.platformLocale).formatted
    LedgerText(stringResource(resourceId, formatted), LedgerTextRole.BODY)
}

@Composable
private fun BudgetCategoryRow(state: BudgetFeatureState, row: BudgetCompositionView, actions: BudgetActions) {
    val locale = LocalLocale.current.platformLocale
    LedgerCard(
        Modifier.fillMaxWidth(),
        onClick = { row.categoryId?.let { actions.onNavigate("BUD-003", it, null) } },
    ) {
        Column(
            Modifier.padding(LedgerTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
        ) {
            LedgerText(row.categoryName.orEmpty(), if (row.depth == 1) LedgerTextRole.SECTION else LedgerTextRole.BODY)
            LedgerText(
                stringResource(
                    R.string.budget_used_available,
                    BudgetPolicy.money(state, row.usedMinor, locale).formatted,
                    BudgetPolicy.money(state, row.availableMinor, locale).formatted,
                ),
                LedgerTextRole.BODY,
            )
            if (row.baseMinor == 0L) {
                LedgerText(stringResource(R.string.budget_unallocated_category), LedgerTextRole.SUPPORTING)
            }
            if (row.depth == 1) LedgerText(stringResource(R.string.budget_includes_children), LedgerTextRole.SUPPORTING)
            if (row.exceededMinor > 0L) {
                LedgerBanner(
                    stringResource(
                        R.string.budget_exceeded_amount,
                        BudgetPolicy.money(state, row.exceededMinor, locale).formatted,
                    ),
                    LedgerBannerVariant.DANGER,
                )
            }
        }
    }
}

@Composable
private fun BudgetEditor(state: BudgetFeatureState, actions: BudgetActions, template: Boolean) {
    val taggedModifier = if (template) {
        Modifier.fillMaxSize().testTag(LedgerTestTags.BUDGET_TEMPLATE_EDITOR)
    } else {
        Modifier.fillMaxSize().testTag(LedgerTestTags.BUDGET_EDITOR)
    }
    LazyColumn(
        taggedModifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        if (template) {
            item {
                LedgerTextField(
                    state.editor.templateName,
                    actions.onTemplateNameChanged,
                    stringResource(R.string.budget_template_name),
                    required = true,
                )
            }
        }
        if (state.presentation == BudgetPresentation.HISTORY_RECALCULATION_WARNING) {
            item {
                LedgerBanner(
                    stringResource(R.string.budget_history_recalc_warning),
                    LedgerBannerVariant.WARNING,
                )
            }
        }
        item {
            LedgerTextField(
                state.editor.totalText,
                actions.onTotalChanged,
                stringResource(R.string.budget_total),
                required = true,
                keyboardType = KeyboardType.Decimal,
                errorText = stringResource(R.string.budget_invalid_amount)
                    .takeIf { state.validation.totalMinor == null },
            )
        }
        item { ConstraintMeters(state) }
        items(
            state.snapshot.categories.filter { it.status == app.ledger.finance.domain.EntityStatus.ACTIVE },
            key = { it.id.toString() },
        ) { category ->
            val label = if (category.depth == 1) {
                category.name
            } else {
                stringResource(R.string.budget_child_label, category.name)
            }
            LedgerTextField(
                state.editor.categoryTexts[category.id].orEmpty(),
                { actions.onCategoryChanged(category.id, it) },
                label,
                keyboardType = KeyboardType.Decimal,
                errorText = stringResource(R.string.budget_invalid_amount)
                    .takeIf { category.id in state.validation.invalidCategoryIds },
            )
        }
        item { LedgerBanner(stringResource(R.string.budget_rollover_excluded_constraint), LedgerBannerVariant.NEUTRAL) }
        item {
            val label = if (state.presentation == BudgetPresentation.SAVING) {
                stringResource(R.string.budget_saving)
            } else {
                stringResource(R.string.budget_save)
            }
            LedgerButton(
                label,
                if (template) actions.onSaveTemplate else actions.onSaveMonth,
                Modifier.fillMaxWidth(),
                enabled = state.presentation != BudgetPresentation.SAVING,
            )
        }
    }
}

@Composable
private fun ConstraintMeters(state: BudgetFeatureState) {
    val report = state.validation.report ?: return
    LedgerCard(Modifier.fillMaxWidth().testTag(LedgerTestTags.BUDGET_CONSTRAINT_METERS)) {
        Column(
            Modifier.padding(LedgerTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
        ) {
            LedgerText(
                stringResource(
                    R.string.budget_allocation_total,
                    report.total.allocatedBaseMinor,
                    report.total.limitBaseMinor,
                ),
                LedgerTextRole.BODY,
            )
            if (report.total.excessBaseMinor > 0L) {
                LedgerBanner(
                    stringResource(R.string.budget_over_limit, report.total.excessBaseMinor),
                    LedgerBannerVariant.DANGER,
                )
            }
            report.parents.forEach { meter ->
                LedgerText(
                    stringResource(
                        R.string.budget_child_allocation,
                        meter.allocatedBaseMinor,
                        meter.limitBaseMinor,
                    ),
                    LedgerTextRole.SUPPORTING,
                )
                if (meter.excessBaseMinor > 0L) {
                    LedgerBanner(
                        stringResource(R.string.budget_over_limit, meter.excessBaseMinor),
                        LedgerBannerVariant.DANGER,
                    )
                }
            }
            LedgerText(
                stringResource(
                    R.string.budget_unallocated,
                    maxOf(report.total.limitBaseMinor - report.total.allocatedBaseMinor, 0L),
                ),
                LedgerTextRole.SUPPORTING,
            )
        }
    }
}

@Composable
private fun BudgetCategoryEditor(state: BudgetFeatureState, encodedCategoryId: String?, actions: BudgetActions) {
    val categoryId = encodedCategoryId?.let { StableId.parse(it).getOrNull() } ?: state.selectedCategoryId
    val category = state.snapshot.categories.singleOrNull { it.id == categoryId }
    if (category == null) {
        LedgerErrorState(
            UiErrorCode("BUDGET_CATEGORY_NOT_FOUND"),
            stringResource(R.string.budget_load_failed),
            actions.onRetry,
        )
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(LedgerTheme.spacing.md)
            .testTag(LedgerTestTags.BUDGET_CATEGORY_EDITOR),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        FormSection(category.name, description = stringResource(R.string.budget_parent_limit_summary)) {
            LedgerTextField(
                state.editor.categoryTexts[category.id].orEmpty(),
                { actions.onCategoryChanged(category.id, it) },
                stringResource(R.string.budget_category_limit),
                keyboardType = KeyboardType.Decimal,
            )
            state.snapshot.categories.filter { it.parentCategoryId == category.id }.forEach { child ->
                LedgerTextField(
                    state.editor.categoryTexts[child.id].orEmpty(),
                    { actions.onCategoryChanged(child.id, it) },
                    child.name,
                    keyboardType = KeyboardType.Decimal,
                )
            }
        }
        ConstraintMeters(state)
        LedgerButton(stringResource(R.string.budget_save), actions.onSaveMonth, Modifier.fillMaxWidth())
    }
}

@Composable
private fun BudgetAdjustments(state: BudgetFeatureState, actions: BudgetActions) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(LedgerTheme.spacing.md)
            .testTag(LedgerTestTags.BUDGET_ADJUSTMENTS),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        item { LedgerBanner(stringResource(R.string.budget_no_account_impact), LedgerBannerVariant.INFO) }
        item { BudgetAdjustmentChoices(actions) }
        if (state.snapshot.adjustments.isEmpty()) {
            item {
                LedgerEmptyState(
                    stringResource(R.string.budget_no_adjustments),
                    stringResource(R.string.budget_no_adjustments_body),
                    stringResource(R.string.budget_add_adjustment),
                    {
                        actions.onNavigate(
                            "BUD-005",
                            null,
                            BudgetAdjustmentKind.INCREASE_AVAILABLE,
                        )
                    },
                )
            }
        }
        items(state.snapshot.adjustments, key = { it.id.toString() }) { value ->
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                    LedgerText(kindLabel(value.kind), LedgerTextRole.SECTION)
                    LedgerText(value.amountBaseMinor.toString(), LedgerTextRole.BODY)
                    LedgerText(stringResource(R.string.budget_adjustment_immutable), LedgerTextRole.SUPPORTING)
                }
            }
        }
    }
}

@Composable
private fun BudgetAdjustmentChoices(actions: BudgetActions) {
    val kinds = listOf(
        BudgetAdjustmentKind.CLEAR_ROLLOVER,
        BudgetAdjustmentKind.INCREASE_AVAILABLE,
        BudgetAdjustmentKind.DECREASE_AVAILABLE,
        BudgetAdjustmentKind.TRANSFER_IN,
    )
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
    ) {
        kinds.forEach { kind ->
            LedgerChip(kindLabel(kind), { actions.onNavigate("BUD-005", null, kind) })
        }
    }
}

@Composable
private fun BudgetAdjustmentEditor(state: BudgetFeatureState, encodedKind: String?, actions: BudgetActions) {
    val kind = when (encodedKind) {
        "CLEAR_ROLLOVER" -> BudgetAdjustmentKind.CLEAR_ROLLOVER
        "SUBTRACT" -> BudgetAdjustmentKind.DECREASE_AVAILABLE
        "TRANSFER" -> BudgetAdjustmentKind.TRANSFER_IN
        else -> BudgetAdjustmentKind.INCREASE_AVAILABLE
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.BUDGET_ADJUSTMENT_EDITOR),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        item { LedgerBanner(stringResource(R.string.budget_no_account_impact), LedgerBannerVariant.INFO) }
        item { LedgerText(kindLabel(kind), LedgerTextRole.TITLE) }
        if (kind != BudgetAdjustmentKind.CLEAR_ROLLOVER) {
            item {
                LedgerTextField(
                    state.adjustmentAmountText,
                    actions.onAdjustmentAmountChanged,
                    stringResource(R.string.budget_adjustment_amount),
                    keyboardType = KeyboardType.Decimal,
                    errorText = stringResource(R.string.budget_invalid_amount)
                        .takeIf { BudgetPolicy.adjustmentMinor(state) == null },
                )
            }
        }
        if (kind != BudgetAdjustmentKind.INCREASE_AVAILABLE) {
            item {
                LedgerButton(
                    stringResource(
                        R.string.budget_source_category,
                        categoryName(state, state.adjustmentSourceCategoryId),
                    ),
                    actions.onAdjustmentSource,
                    Modifier.fillMaxWidth(),
                    LedgerButtonVariant.SECONDARY,
                )
            }
        }
        if (kind.requiresTargetCategory()) {
            item {
                LedgerButton(
                    stringResource(
                        R.string.budget_target_category,
                        categoryName(state, state.adjustmentTargetCategoryId),
                    ),
                    actions.onAdjustmentTarget,
                    Modifier.fillMaxWidth(),
                    LedgerButtonVariant.SECONDARY,
                )
            }
        }
        item { LedgerText(stringResource(R.string.budget_adjustment_category_hint), LedgerTextRole.SUPPORTING) }
        item {
            LedgerButton(
                stringResource(R.string.budget_save),
                { actions.onSaveAdjustment(kind) },
                Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun BudgetAdjustmentKind.requiresTargetCategory(): Boolean = this == BudgetAdjustmentKind.TRANSFER_IN ||
    this == BudgetAdjustmentKind.ARCHIVED_CATEGORY_TRANSFER ||
    this == BudgetAdjustmentKind.INCREASE_AVAILABLE

private fun categoryName(state: BudgetFeatureState, id: StableId?): String = state.snapshot.categories.singleOrNull { it.id == id }?.name.orEmpty()

@Composable
private fun BudgetHistory(state: BudgetFeatureState, actions: BudgetActions) {
    val locale = LocalLocale.current.platformLocale
    val dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(LedgerTheme.spacing.md)
            .testTag(LedgerTestTags.BUDGET_HISTORY),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        items(state.snapshot.revisionHistory, key = { it.id.toString() }) { revision ->
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                    LedgerText(
                        stringResource(R.string.budget_revision, revision.revisionNumber),
                        LedgerTextRole.SECTION,
                    )
                    LedgerText(revision.totalBaseMinor.toString(), LedgerTextRole.BODY)
                    LedgerText(revision.createdAt.atZone(ZoneId.systemDefault()).format(dateTimeFormatter), LedgerTextRole.SUPPORTING)
                }
            }
        }
        if (state.snapshot.revisionHistory.size > 1) {
            item {
                LedgerButton(
                    stringResource(R.string.budget_compare),
                    actions.onRetry,
                    Modifier.fillMaxWidth(),
                    LedgerButtonVariant.SECONDARY,
                )
            }
        }
    }
}

@Composable
private fun BudgetTemplates(state: BudgetFeatureState, actions: BudgetActions) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(LedgerTheme.spacing.md)
            .testTag(LedgerTestTags.BUDGET_TEMPLATES),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        if (state.snapshot.templates.isEmpty()) {
            item {
                LedgerEmptyState(
                    stringResource(R.string.budget_no_templates),
                    stringResource(R.string.budget_no_templates_body),
                    stringResource(R.string.budget_add_template),
                    { actions.onNavigate("BUD-008", null, null) },
                )
            }
        }
        items(state.snapshot.templates, key = { it.id.toString() }) { template ->
            LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onNavigate("BUD-008", template.id, null) }) {
                Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                    LedgerText(template.name, LedgerTextRole.SECTION)
                    LedgerText(template.revision.totalBaseMinor.toString(), LedgerTextRole.BODY)
                }
            }
        }
        if (state.snapshot.templates.isNotEmpty()) {
            item {
                LedgerButton(
                    stringResource(R.string.budget_add_template),
                    { actions.onNavigate("BUD-008", null, null) },
                    Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun kindLabel(kind: BudgetAdjustmentKind): String = when (kind) {
    BudgetAdjustmentKind.CLEAR_ROLLOVER -> stringResource(R.string.budget_clear_rollover)
    BudgetAdjustmentKind.INCREASE_AVAILABLE -> stringResource(R.string.budget_increase)
    BudgetAdjustmentKind.DECREASE_AVAILABLE -> stringResource(R.string.budget_decrease)
    BudgetAdjustmentKind.TRANSFER_IN, BudgetAdjustmentKind.TRANSFER_OUT -> stringResource(R.string.budget_transfer)
    BudgetAdjustmentKind.ARCHIVED_CATEGORY_TRANSFER -> stringResource(R.string.budget_archive_transfer)
}
