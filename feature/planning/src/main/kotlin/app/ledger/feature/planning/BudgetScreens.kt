@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "MagicNumber",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
)

package app.ledger.feature.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalConfiguration
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
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerIcon
import app.ledger.core.designsystem.LedgerIconView
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerModalDialog
import app.ledger.core.designsystem.LedgerProgressIndicator
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerDateFormatterRuntime
import app.ledger.core.designsystem.LocalLedgerAmountsVisible
import app.ledger.core.designsystem.LocalLedgerScrollToTopRequest
import app.ledger.core.designsystem.LocalLedgerRestoredScrollState
import app.ledger.core.designsystem.LocalLedgerScrollStateReporter
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.core.designsystem.rememberLedgerRetainedState
import app.ledger.finance.application.BudgetCompositionView
import app.ledger.finance.application.BudgetRevisionView
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
    if (
        screenId == "BUD-008" &&
        encodedArguments["templateId"] != null &&
        content.selectedTemplateId?.toString() != encodedArguments["templateId"]
    ) {
        LedgerErrorState(
            UiErrorCode("BUDGET_TEMPLATE_NOT_FOUND"),
            stringResource(R.string.budget_template_not_found),
            actions.onRetry,
        )
        return
    }
    when (screenId) {
        "BUD-001" -> BudgetHome(content, actions)
        "BUD-002" -> BudgetEditor(content, actions, false)
        "BUD-003" -> BudgetCategoryEditor(content, encodedArguments["categoryId"], actions)
        "BUD-004" -> BudgetAdjustments(content, actions)
        "BUD-005" -> BudgetAdjustmentEditor(content, encodedArguments["type"], actions)
        "BUD-006" -> BudgetHistory(content)
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
    val locale = LocalConfiguration.current.locales[0]
    var expandedRootIds by remember(state.snapshot.month) { mutableStateOf(emptySet<StableId>()) }
    val categoryRows = state.snapshot.composition.filter { row ->
        row.categoryId != null && (row.depth == 1 || row.parentCategoryId in expandedRootIds)
    }
    val restoredScroll = LocalLedgerRestoredScrollState.current
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = restoredScroll?.first?.removePrefix("index_")?.toIntOrNull() ?: 0,
        initialFirstVisibleItemScrollOffset = restoredScroll?.second ?: 0,
    )
    val scrollToTopRequest = LocalLedgerScrollToTopRequest.current
    val scrollReporter = LocalLedgerScrollStateReporter.current
    LaunchedEffect(scrollToTopRequest) { if (scrollToTopRequest > 0) listState.scrollToItem(0) }
    LaunchedEffect(listState, scrollReporter) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, offset) -> scrollReporter("index_$index", offset) }
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.BUDGET_HOME),
        state = listState,
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
                LedgerText(
                    state.snapshot.month.format(
                        DateTimeFormatter.ofPattern(
                            if (locale.language in setOf("zh", "ja")) "y年M月" else "LLLL y",
                            locale,
                        ),
                    ),
                    LedgerTextRole.TITLE,
                )
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
                                CompositionLine(
                                    R.string.budget_reserved_recurrence_row,
                                    daily.reservedRecurrenceBaseMinor,
                                    state,
                                )
                                LedgerText(
                                    stringResource(
                                        R.string.budget_reserved_formula,
                                        if (LocalLedgerAmountsVisible.current) BudgetPolicy.money(state, daily.reservedRecurrenceBaseMinor, locale).formatted else "••••",
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
                categoryRows,
                key = { it.categoryId.toString() },
            ) { row ->
                val expanded = row.categoryId in expandedRootIds
                BudgetCategoryRow(
                    state,
                    row,
                    actions,
                    expanded,
                    onToggleExpanded = {
                        row.categoryId?.let { id ->
                            expandedRootIds = if (expanded) expandedRootIds - id else expandedRootIds + id
                        }
                    },
                )
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
            CompositionLine(R.string.budget_available_total_row, total.availableMinor, state)
            CompositionLine(R.string.budget_base_row, total.baseMinor, state)
            CompositionLine(R.string.budget_rollover_row, total.rolloverMinor, state)
            CompositionLine(R.string.budget_adjustment_row, total.adjustmentMinor, state)
            CompositionLine(R.string.budget_used_row, total.usedMinor, state)
            LedgerProgressIndicator(
                BudgetPolicy.progressFraction(total.usedMinor, total.availableMinor),
                accessibleText = stringResource(
                    R.string.budget_progress_accessible,
                    BudgetPolicy.money(state, total.usedMinor, locale).formatted,
                    BudgetPolicy.money(state, total.availableMinor, locale).formatted,
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
    LedgerText(stringResource(resourceId, if (LocalLedgerAmountsVisible.current) formatted else "••••"), LedgerTextRole.BODY)
}

@Composable
private fun BudgetCategoryRow(
    state: BudgetFeatureState,
    row: BudgetCompositionView,
    actions: BudgetActions,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    val category = state.snapshot.categories.singleOrNull { it.id == row.categoryId }
    val icon = LedgerIcon.entries.firstOrNull { it.name.equals(category?.iconKey, ignoreCase = true) } ?: LedgerIcon.BUDGET
    LedgerCard(
        Modifier.fillMaxWidth(),
        onClick = { row.categoryId?.let { actions.onNavigate("BUD-003", it, null) } },
    ) {
        Column(
            Modifier.padding(LedgerTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
            ) {
                if (row.depth > 1) Spacer(Modifier.size(LedgerTheme.spacing.md))
                LedgerIconView(icon, contentDescription = null)
                LedgerText(
                    row.categoryName.orEmpty(),
                    if (row.depth == 1) LedgerTextRole.SECTION else LedgerTextRole.BODY,
                    Modifier.weight(1f),
                )
                if (row.depth == 1 && state.snapshot.categories.any { it.parentCategoryId == row.categoryId }) {
                    LedgerButton(
                        stringResource(if (expanded) R.string.budget_hide_children else R.string.budget_show_children),
                        onToggleExpanded,
                        variant = LedgerButtonVariant.TEXT,
                        compact = true,
                    )
                }
            }
            LedgerText(
                stringResource(
                    R.string.budget_used_available,
                    if (LocalLedgerAmountsVisible.current) BudgetPolicy.money(state, row.usedMinor, locale).formatted else "••••",
                    if (LocalLedgerAmountsVisible.current) BudgetPolicy.money(state, row.availableMinor, locale).formatted else "••••",
                ),
                LedgerTextRole.BODY,
            )
            LedgerProgressIndicator(
                BudgetPolicy.progressFraction(row.usedMinor, row.availableMinor),
                accessibleText = stringResource(
                    R.string.budget_progress_accessible,
                    BudgetPolicy.money(state, row.usedMinor, locale).formatted,
                    BudgetPolicy.money(state, row.availableMinor, locale).formatted,
                ),
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
    val saving = state.presentation == BudgetPresentation.SAVING
    LedgerScaffold(
        modifier = taggedModifier,
        formContent = true,
        fixedAction = {
            BudgetStickySaveBar(
                if (template) actions.onSaveTemplate else actions.onSaveMonth,
                enabled = !saving,
                saving = saving,
            )
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
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
                        errorText = stringResource(R.string.budget_required).takeIf { state.editor.templateName.isBlank() },
                    )
                }
            }
            if (!template && state.snapshot.templates.isNotEmpty()) {
                item { LedgerText(stringResource(R.string.budget_apply_template), LedgerTextRole.SECTION) }
                items(state.snapshot.templates, key = { "apply-${it.id}" }) { availableTemplate ->
                    LedgerButton(
                        stringResource(R.string.budget_apply_named_template, availableTemplate.name),
                        { actions.onApplyTemplate(availableTemplate.id) },
                        Modifier.fillMaxWidth(),
                        LedgerButtonVariant.SECONDARY,
                    )
                }
            }
            if (state.presentation == BudgetPresentation.HISTORY_RECALCULATION_WARNING) {
                item { LedgerBanner(stringResource(R.string.budget_history_recalc_warning), LedgerBannerVariant.WARNING) }
            }
            if (state.presentation == BudgetPresentation.CONSTRAINT_ERROR) {
                item { LedgerBanner(stringResource(R.string.budget_constraint_error), LedgerBannerVariant.DANGER) }
            }
            item {
                LedgerTextField(
                    state.editor.totalText,
                    actions.onTotalChanged,
                    stringResource(R.string.budget_total),
                    required = true,
                    keyboardType = KeyboardType.Decimal,
                    errorText = stringResource(R.string.budget_invalid_amount).takeIf { state.validation.totalMinor == null },
                )
            }
            item { ConstraintMeters(state) }
            items(
                state.snapshot.categories.filter { it.status == app.ledger.finance.domain.EntityStatus.ACTIVE },
                key = { it.id.toString() },
            ) { category ->
                val label = if (category.depth == 1) category.name else stringResource(R.string.budget_child_label, category.name)
                LedgerTextField(
                    state.editor.categoryTexts[category.id].orEmpty(),
                    { actions.onCategoryChanged(category.id, it) },
                    label,
                    keyboardType = KeyboardType.Decimal,
                    errorText = budgetCategoryFieldError(state, category.id),
                )
            }
            item { LedgerBanner(stringResource(R.string.budget_rollover_excluded_constraint), LedgerBannerVariant.NEUTRAL) }
        }
    }
}

@Composable
private fun ConstraintMeters(state: BudgetFeatureState) {
    val report = state.validation.report ?: return
    val locale = LocalLocale.current.platformLocale
    LedgerCard(Modifier.fillMaxWidth().testTag(LedgerTestTags.BUDGET_CONSTRAINT_METERS)) {
        Column(
            Modifier.padding(LedgerTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
        ) {
            LedgerText(
                stringResource(
                    R.string.budget_allocation_total,
                    BudgetPolicy.money(state, report.total.allocatedBaseMinor, locale).formatted,
                    BudgetPolicy.money(state, report.total.limitBaseMinor, locale).formatted,
                ),
                LedgerTextRole.BODY,
            )
            if (report.total.excessBaseMinor > 0L) {
                LedgerBanner(
                    stringResource(R.string.budget_over_limit, BudgetPolicy.money(state, report.total.excessBaseMinor, locale).formatted),
                    LedgerBannerVariant.DANGER,
                )
            }
            report.parents.forEach { meter ->
                LedgerText(
                    stringResource(
                        R.string.budget_child_allocation,
                        BudgetPolicy.money(state, meter.allocatedBaseMinor, locale).formatted,
                        BudgetPolicy.money(state, meter.limitBaseMinor, locale).formatted,
                    ),
                    LedgerTextRole.SUPPORTING,
                )
                if (meter.excessBaseMinor > 0L) {
                    LedgerBanner(
                        stringResource(R.string.budget_over_limit, BudgetPolicy.money(state, meter.excessBaseMinor, locale).formatted),
                        LedgerBannerVariant.DANGER,
                    )
                }
            }
            LedgerText(
                stringResource(
                    R.string.budget_unallocated,
                    BudgetPolicy.money(state, maxOf(report.total.limitBaseMinor - report.total.allocatedBaseMinor, 0L), locale).formatted,
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
    val saving = state.presentation == BudgetPresentation.SAVING
    LedgerScaffold(
        modifier = Modifier.fillMaxSize().testTag(LedgerTestTags.BUDGET_CATEGORY_EDITOR),
        formContent = true,
        fixedAction = { BudgetStickySaveBar(actions.onSaveMonth, !saving, saving) },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(LedgerTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
        ) {
            if (state.presentation == BudgetPresentation.CONSTRAINT_ERROR) {
                item { LedgerBanner(stringResource(R.string.budget_constraint_error), LedgerBannerVariant.DANGER) }
            }
            item {
                FormSection(category.name, description = stringResource(R.string.budget_parent_limit_summary)) {
                    LedgerTextField(
                        state.editor.categoryTexts[category.id].orEmpty(),
                        { actions.onCategoryChanged(category.id, it) },
                        stringResource(R.string.budget_category_limit),
                        keyboardType = KeyboardType.Decimal,
                        errorText = budgetCategoryFieldError(state, category.id),
                    )
                    state.snapshot.categories.filter { it.parentCategoryId == category.id }.forEach { child ->
                        LedgerTextField(
                            state.editor.categoryTexts[child.id].orEmpty(),
                            { actions.onCategoryChanged(child.id, it) },
                            child.name,
                            keyboardType = KeyboardType.Decimal,
                            errorText = budgetCategoryFieldError(state, child.id),
                        )
                    }
                    LedgerButton(
                        stringResource(R.string.budget_clear_category),
                        { actions.onClearCategory(category.id) },
                        Modifier.fillMaxWidth(),
                        LedgerButtonVariant.SECONDARY,
                    )
                }
            }
            item { ConstraintMeters(state) }
            if (saving) {
                item { LedgerLoadingState(Modifier.fillMaxWidth(), stringResource(R.string.budget_saving)) }
            }
        }
    }
}

@Composable
private fun BudgetAdjustments(state: BudgetFeatureState, actions: BudgetActions) {
    val locale = LocalConfiguration.current.locales[0]
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
        itemsIndexed(state.snapshot.adjustments, key = { _, value -> value.id.toString() }) { index, value ->
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                    LedgerText(kindLabel(value.kind), LedgerTextRole.SECTION)
                    LedgerText(BudgetPolicy.money(state, value.amountBaseMinor, locale).formatted, LedgerTextRole.BODY)
                    LedgerText(
                        stringResource(R.string.budget_adjustment_version, state.snapshot.adjustments.size - index),
                        LedgerTextRole.SUPPORTING,
                    )
                    LedgerText(value.createdAt.localizedDateTime(locale), LedgerTextRole.SUPPORTING)
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
        BudgetAdjustmentKind.ARCHIVED_CATEGORY_TRANSFER,
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
    var categoryPicker by remember { mutableStateOf<BudgetAdjustmentCategoryPicker?>(null) }
    var amountTouched by rememberLedgerRetainedState("adjustment-amount-touched") { false }
    val kind = when (encodedKind) {
        "CLEAR_ROLLOVER" -> BudgetAdjustmentKind.CLEAR_ROLLOVER
        "SUBTRACT" -> BudgetAdjustmentKind.DECREASE_AVAILABLE
        "TRANSFER" -> if (
            state.snapshot.categories.singleOrNull { it.id == state.adjustmentSourceCategoryId }?.status ==
            app.ledger.finance.domain.EntityStatus.ARCHIVED
        ) {
            BudgetAdjustmentKind.ARCHIVED_CATEGORY_TRANSFER
        } else {
            BudgetAdjustmentKind.TRANSFER_IN
        }
        else -> BudgetAdjustmentKind.INCREASE_AVAILABLE
    }
    val amountValid = kind == BudgetAdjustmentKind.CLEAR_ROLLOVER || BudgetPolicy.adjustmentMinor(state) != null
    val source = state.snapshot.categories.singleOrNull { it.id == state.adjustmentSourceCategoryId }
    val target = state.snapshot.categories.singleOrNull { it.id == state.adjustmentTargetCategoryId }
    val sourceValid = kind == BudgetAdjustmentKind.INCREASE_AVAILABLE || source?.status == if (
        kind == BudgetAdjustmentKind.ARCHIVED_CATEGORY_TRANSFER
    ) {
        app.ledger.finance.domain.EntityStatus.ARCHIVED
    } else {
        app.ledger.finance.domain.EntityStatus.ACTIVE
    }
    val targetValid = !kind.requiresTargetCategory() || target?.status == app.ledger.finance.domain.EntityStatus.ACTIVE
    val saving = state.presentation == BudgetPresentation.SAVING
    LedgerScaffold(
        modifier = Modifier.fillMaxSize().testTag(LedgerTestTags.BUDGET_ADJUSTMENT_EDITOR),
        formContent = true,
        fixedAction = {
            BudgetStickySaveBar(
                { actions.onSaveAdjustment(kind) },
                enabled = !saving,
                saving = saving,
            )
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(LedgerTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
        ) {
            item { LedgerBanner(stringResource(R.string.budget_no_account_impact), LedgerBannerVariant.INFO) }
            if (state.presentation == BudgetPresentation.INVALID) {
                item { LedgerBanner(stringResource(R.string.budget_adjustment_invalid), LedgerBannerVariant.DANGER) }
            }
            if (saving) {
                item { LedgerLoadingState(Modifier.fillMaxWidth(), stringResource(R.string.budget_saving)) }
            }
            item { LedgerText(kindLabel(kind), LedgerTextRole.TITLE) }
            if (kind != BudgetAdjustmentKind.CLEAR_ROLLOVER) {
                item {
                    LedgerTextField(
                        state.adjustmentAmountText,
                        {
                            amountTouched = true
                            actions.onAdjustmentAmountChanged(it)
                        },
                        stringResource(R.string.budget_adjustment_amount),
                        keyboardType = KeyboardType.Decimal,
                        errorText = stringResource(R.string.budget_invalid_amount).takeIf {
                            !amountValid && (amountTouched || state.presentation == BudgetPresentation.INVALID)
                        },
                    )
                }
            }
            if (kind != BudgetAdjustmentKind.INCREASE_AVAILABLE) {
                item {
                    LedgerButton(
                        stringResource(R.string.budget_source_category, categoryName(state, state.adjustmentSourceCategoryId)),
                        { categoryPicker = BudgetAdjustmentCategoryPicker.SOURCE },
                        Modifier.fillMaxWidth(),
                        LedgerButtonVariant.SECONDARY,
                    )
                    if (!sourceValid) LedgerText(stringResource(R.string.budget_category_required), LedgerTextRole.SUPPORTING)
                }
            }
            if (kind.requiresTargetCategory()) {
                item {
                    LedgerButton(
                        stringResource(R.string.budget_target_category, categoryName(state, state.adjustmentTargetCategoryId)),
                        { categoryPicker = BudgetAdjustmentCategoryPicker.TARGET },
                        Modifier.fillMaxWidth(),
                        LedgerButtonVariant.SECONDARY,
                    )
                    if (!targetValid) LedgerText(stringResource(R.string.budget_category_required), LedgerTextRole.SUPPORTING)
                }
            }
            item { LedgerText(stringResource(R.string.budget_adjustment_category_hint), LedgerTextRole.SUPPORTING) }
        }
    }
    categoryPicker?.let { picker ->
        val archivedSource = kind == BudgetAdjustmentKind.ARCHIVED_CATEGORY_TRANSFER
        val candidates = state.snapshot.categories.filter { category ->
            when (picker) {
                BudgetAdjustmentCategoryPicker.SOURCE -> category.id != state.adjustmentTargetCategoryId &&
                    category.status == if (archivedSource) app.ledger.finance.domain.EntityStatus.ARCHIVED else app.ledger.finance.domain.EntityStatus.ACTIVE
                BudgetAdjustmentCategoryPicker.TARGET -> category.id != state.adjustmentSourceCategoryId &&
                    category.status == app.ledger.finance.domain.EntityStatus.ACTIVE
            }
        }
        LedgerModalDialog(
            title = stringResource(if (picker == BudgetAdjustmentCategoryPicker.SOURCE) R.string.budget_source_category_title else R.string.budget_target_category_title),
            onDismiss = { categoryPicker = null },
        ) {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(candidates, key = { it.id.toString() }) { category ->
                    LedgerChoiceRow(
                        category.name,
                        selected = category.id == if (picker == BudgetAdjustmentCategoryPicker.SOURCE) state.adjustmentSourceCategoryId else state.adjustmentTargetCategoryId,
                        onClick = {
                            if (picker == BudgetAdjustmentCategoryPicker.SOURCE) actions.onAdjustmentSource(category.id) else actions.onAdjustmentTarget(category.id)
                            categoryPicker = null
                        },
                    )
                }
            }
        }
    }
}

private enum class BudgetAdjustmentCategoryPicker { SOURCE, TARGET }

private fun BudgetAdjustmentKind.requiresTargetCategory(): Boolean = this == BudgetAdjustmentKind.TRANSFER_IN ||
    this == BudgetAdjustmentKind.ARCHIVED_CATEGORY_TRANSFER

@Composable
private fun categoryName(state: BudgetFeatureState, id: StableId?): String =
    state.snapshot.categories.singleOrNull { it.id == id }?.name ?: stringResource(R.string.budget_choose_category)

@Composable
private fun BudgetHistory(state: BudgetFeatureState) {
    val locale = LocalConfiguration.current.locales[0]
    val history = state.snapshot.revisionHistory.sortedByDescending { it.revisionNumber }
    var comparisonVisible by remember(state.snapshot.month) { mutableStateOf(false) }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(LedgerTheme.spacing.md)
            .testTag(LedgerTestTags.BUDGET_HISTORY),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        itemsIndexed(history, key = { _, revision -> revision.id.toString() }) { index, revision ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LedgerIconView(LedgerIcon.INFO, contentDescription = stringResource(R.string.budget_timeline_marker))
                    if (index < history.lastIndex) {
                        Spacer(Modifier.size(LedgerTheme.spacing.md))
                    }
                }
                LedgerCard(Modifier.weight(1f)) {
                    Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                        LedgerText(stringResource(R.string.budget_revision, revision.revisionNumber), LedgerTextRole.SECTION)
                        LedgerText(BudgetPolicy.money(state, revision.totalBaseMinor, locale).formatted, LedgerTextRole.BODY)
                        LedgerText(revision.createdAt.localizedDateTime(locale), LedgerTextRole.SUPPORTING)
                    }
                }
            }
        }
        if (history.size > 1) {
            item {
                LedgerButton(
                    stringResource(if (comparisonVisible) R.string.budget_close_comparison else R.string.budget_compare),
                    { comparisonVisible = !comparisonVisible },
                    Modifier.fillMaxWidth(),
                    LedgerButtonVariant.SECONDARY,
                )
            }
            if (comparisonVisible) {
                item { BudgetRevisionComparison(state, history[1], history[0]) }
            }
        }
    }
}

@Composable
private fun BudgetRevisionComparison(state: BudgetFeatureState, older: BudgetRevisionView, newer: BudgetRevisionView) {
    val locale = LocalLocale.current.platformLocale
    val olderLimits = older.limits.associate { it.categoryId to it.amountBaseMinor }
    val newerLimits = newer.limits.associate { it.categoryId to it.amountBaseMinor }
    val changedIds = (olderLimits.keys + newerLimits.keys).filter { olderLimits[it] != newerLimits[it] }
    LedgerCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(LedgerTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
        ) {
            LedgerText(stringResource(R.string.budget_comparison_title, older.revisionNumber, newer.revisionNumber), LedgerTextRole.SECTION)
            LedgerText(
                stringResource(
                    R.string.budget_comparison_total,
                    BudgetPolicy.money(state, older.totalBaseMinor, locale).formatted,
                    BudgetPolicy.money(state, newer.totalBaseMinor, locale).formatted,
                ),
                LedgerTextRole.BODY,
            )
            if (changedIds.isEmpty()) {
                LedgerText(stringResource(R.string.budget_comparison_no_category_changes), LedgerTextRole.SUPPORTING)
            } else {
                changedIds.forEach { id ->
                    LedgerText(
                        stringResource(
                            R.string.budget_comparison_category,
                            state.snapshot.categories.singleOrNull { it.id == id }?.name.orEmpty(),
                            BudgetPolicy.money(state, olderLimits[id] ?: 0L, locale).formatted,
                            BudgetPolicy.money(state, newerLimits[id] ?: 0L, locale).formatted,
                        ),
                        LedgerTextRole.BODY,
                    )
                }
            }
        }
    }
}

@Composable
private fun BudgetTemplates(state: BudgetFeatureState, actions: BudgetActions) {
    val locale = LocalLocale.current.platformLocale
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
                    LedgerText(BudgetPolicy.money(state, template.revision.totalBaseMinor, locale).formatted, LedgerTextRole.BODY)
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

@Composable
private fun BudgetStickySaveBar(onSave: () -> Unit, enabled: Boolean, saving: Boolean) {
    LedgerCard(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
            LedgerButton(
                stringResource(if (saving) R.string.budget_saving else R.string.budget_save),
                onSave,
                Modifier.fillMaxWidth(),
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun budgetCategoryFieldError(state: BudgetFeatureState, categoryId: StableId): String? {
    if (categoryId in state.validation.invalidCategoryIds) return stringResource(R.string.budget_invalid_amount)
    val category = state.snapshot.categories.singleOrNull { it.id == categoryId } ?: return null
    val report = state.validation.report ?: return null
    if (category.depth == 1 && report.total.excessBaseMinor > 0L) {
        return stringResource(R.string.budget_field_over_total)
    }
    if (
        category.depth > 1 &&
        report.parents.any { meter -> meter.scopeCategoryId?.value == category.parentCategoryId && meter.excessBaseMinor > 0L }
    ) {
        return stringResource(R.string.budget_field_over_parent)
    }
    return null
}

@Composable
private fun java.time.Instant.localizedDateTime(locale: java.util.Locale): String =
    LedgerDateFormatterRuntime.dateTimeFormatter(locale)
        .withZone(LedgerTheme.timeZone)
        .format(this)
