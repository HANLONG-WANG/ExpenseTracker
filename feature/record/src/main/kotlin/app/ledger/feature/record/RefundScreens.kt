@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "MagicNumber", "MaxLineLength", "TooManyFunctions", "ktlint:standard:function-naming")

package app.ledger.feature.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.DateTimeZoneField
import app.ledger.core.designsystem.FormSection
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChip
import app.ledger.core.designsystem.LedgerCheckboxRow
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerDatePickerFlow
import app.ledger.core.designsystem.LedgerDialog
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerDateFormatterRuntime
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.MoneyExpressionField
import app.ledger.core.designsystem.SearchField
import app.ledger.core.designsystem.SelectorField
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.finance.application.RefundableTransactionView
import app.ledger.finance.domain.RefundAccrualPolicy
import app.ledger.finance.domain.RefundBudgetPolicy
import app.ledger.finance.domain.RefundGoalPolicy
import app.ledger.finance.domain.RefundProjectPolicy
import app.ledger.finance.domain.CategoryStatus
import app.ledger.finance.domain.EntityStatus
import java.time.LocalDate
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

public sealed interface RefundScreenAction {
    public data object Retry : RefundScreenAction
    public data object PickOriginal : RefundScreenAction
    public data class IndependentChanged(val independent: Boolean) : RefundScreenAction
    public data class ExpressionChanged(val value: String) : RefundScreenAction
    public data class Operator(val value: String) : RefundScreenAction
    public data class Account(val id: StableId) : RefundScreenAction
    public data class Card(val id: StableId?) : RefundScreenAction
    public data class Category(val id: StableId) : RefundScreenAction
    public data object CreateCategory : RefundScreenAction
    public data class Merchant(val id: StableId?) : RefundScreenAction
    public data class Project(val id: StableId?) : RefundScreenAction
    public data class Goal(val id: StableId?) : RefundScreenAction
    public data class DateChanged(val date: LocalDate) : RefundScreenAction
    public data class AccrualPolicyChanged(val policy: RefundAccrualPolicy) : RefundScreenAction
    public data class BudgetPolicyChanged(val policy: RefundBudgetPolicy) : RefundScreenAction
    public data class ProjectPolicyChanged(val policy: RefundProjectPolicy) : RefundScreenAction
    public data class GoalPolicyChanged(val policy: RefundGoalPolicy) : RefundScreenAction
    public data class NoteChanged(val value: String) : RefundScreenAction
    public data class RequestExcess(val requested: Boolean) : RefundScreenAction
    public data object ConfirmExcess : RefundScreenAction
}

public sealed interface RefundPickerScreenAction {
    public data object Retry : RefundPickerScreenAction
    public data class QueryChanged(val query: String) : RefundPickerScreenAction
    public data class PartialOnlyChanged(val enabled: Boolean) : RefundPickerScreenAction
    public data class Choose(val transactionId: StableId) : RefundPickerScreenAction
    public data object Independent : RefundPickerScreenAction
}

internal class RefundActions(
    val onRetry: () -> Unit,
    val onPickOriginal: () -> Unit,
    val onIndependent: (Boolean) -> Unit,
    val onExpression: (String) -> Unit,
    val onOperator: (String) -> Unit,
    val onAccount: (StableId) -> Unit,
    val onCard: (StableId?) -> Unit,
    val onCategory: (StableId) -> Unit,
    val onCreateCategory: () -> Unit,
    val onMerchant: (StableId?) -> Unit,
    val onProject: (StableId?) -> Unit,
    val onGoal: (StableId?) -> Unit,
    val onDate: (LocalDate) -> Unit,
    val onAccrualPolicy: (RefundAccrualPolicy) -> Unit,
    val onBudgetPolicy: (RefundBudgetPolicy) -> Unit,
    val onProjectPolicy: (RefundProjectPolicy) -> Unit,
    val onGoalPolicy: (RefundGoalPolicy) -> Unit,
    val onNote: (String) -> Unit,
    val onRequestExcess: (Boolean) -> Unit,
    val onConfirmExcess: () -> Unit,
)

internal class RefundPickerActions(
    val onRetry: () -> Unit,
    val onQuery: (String) -> Unit,
    val onPartialOnly: (Boolean) -> Unit,
    val onChoose: (StableId) -> Unit,
    val onIndependent: () -> Unit,
)

internal fun refundActions(onAction: (RefundScreenAction) -> Unit): RefundActions = RefundActions(
    onRetry = { onAction(RefundScreenAction.Retry) },
    onPickOriginal = { onAction(RefundScreenAction.PickOriginal) },
    onIndependent = { onAction(RefundScreenAction.IndependentChanged(it)) },
    onExpression = { onAction(RefundScreenAction.ExpressionChanged(it)) },
    onOperator = { onAction(RefundScreenAction.Operator(it)) },
    onAccount = { onAction(RefundScreenAction.Account(it)) },
    onCard = { onAction(RefundScreenAction.Card(it)) },
    onCategory = { onAction(RefundScreenAction.Category(it)) },
    onCreateCategory = { onAction(RefundScreenAction.CreateCategory) },
    onMerchant = { onAction(RefundScreenAction.Merchant(it)) },
    onProject = { onAction(RefundScreenAction.Project(it)) },
    onGoal = { onAction(RefundScreenAction.Goal(it)) },
    onDate = { onAction(RefundScreenAction.DateChanged(it)) },
    onAccrualPolicy = { onAction(RefundScreenAction.AccrualPolicyChanged(it)) },
    onBudgetPolicy = { onAction(RefundScreenAction.BudgetPolicyChanged(it)) },
    onProjectPolicy = { onAction(RefundScreenAction.ProjectPolicyChanged(it)) },
    onGoalPolicy = { onAction(RefundScreenAction.GoalPolicyChanged(it)) },
    onNote = { onAction(RefundScreenAction.NoteChanged(it)) },
    onRequestExcess = { onAction(RefundScreenAction.RequestExcess(it)) },
    onConfirmExcess = { onAction(RefundScreenAction.ConfirmExcess) },
)

internal fun refundPickerActions(onAction: (RefundPickerScreenAction) -> Unit): RefundPickerActions = RefundPickerActions(
    onRetry = { onAction(RefundPickerScreenAction.Retry) },
    onQuery = { onAction(RefundPickerScreenAction.QueryChanged(it)) },
    onPartialOnly = { onAction(RefundPickerScreenAction.PartialOnlyChanged(it)) },
    onChoose = { onAction(RefundPickerScreenAction.Choose(it)) },
    onIndependent = { onAction(RefundPickerScreenAction.Independent) },
)

@Composable
public fun RefundDestination(
    state: RefundLoadState,
    onAction: (RefundScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = refundActions(onAction)
    Column(modifier.fillMaxSize().testTag(LedgerTestTags.REFUND_ROOT)) {
        when (state) {
            RefundLoadState.Loading -> LedgerLoadingState(Modifier.fillMaxSize())
            is RefundLoadState.Failure -> LedgerErrorState(UiErrorCode(state.code), stringResource(R.string.refund_load_failed), actions.onRetry)
            is RefundLoadState.Content -> RefundEditor(state.editor, actions)
        }
    }
}

@Composable
private fun RefundEditor(state: RefundEditorState, actions: RefundActions) {
    val original = RefundPolicy.original(state)
    val locale = LocalLocale.current.platformLocale
    var picker by remember { mutableStateOf<RefundReferencePicker?>(null) }
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.REFUND_FORM).padding(horizontal = LedgerTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        item {
            LedgerBanner(
                stringResource(R.string.refund_contra_expense_explanation),
                LedgerBannerVariant.INFO,
            )
        }
        item {
            FormSection(stringResource(R.string.refund_link_mode)) {
                LedgerChoiceRow(stringResource(R.string.refund_linked), !state.draft.independent, { actions.onIndependent(false) })
                LedgerChoiceRow(stringResource(R.string.refund_independent), state.draft.independent, { actions.onIndependent(true) }, supportingText = stringResource(R.string.refund_independent_explanation))
            }
        }
        if (!state.draft.independent) {
            item {
                SelectorField(
                    label = stringResource(R.string.refund_original_transaction),
                    selectedText = original?.let { "${it.categoryName} · ${it.localDate.localized(locale)}" } ?: stringResource(R.string.refund_select_original),
                    onClick = actions.onPickOriginal,
                    modifier = Modifier.testTag(LedgerTestTags.REFUND_ORIGINAL),
                    supportingText = state.errors.fieldError(RefundField.ORIGINAL),
                )
            }
            if (original != null) {
                item { RefundAmountSummary(original, state, locale) }
            }
        }
        item {
            FormSection(stringResource(R.string.refund_this_amount)) {
                MoneyExpressionField(
                    expression = state.draft.expression,
                    normalizedExpression = state.draft.normalizedExpression,
                    result = state.draft.result,
                    onExpressionChange = actions.onExpression,
                    currencyCode = RefundPolicy.currency(state).value,
                    errorText = state.errors.fieldError(RefundField.AMOUNT),
                    onOperator = actions.onOperator,
                )
            }
        }
        item {
            ReceivingFields(
                state = state,
                onAccount = { picker = RefundReferencePicker.ACCOUNT },
                onCard = { picker = RefundReferencePicker.CARD },
            )
        }
        item {
            InheritedFields(
                state = state,
                onCategory = { picker = RefundReferencePicker.CATEGORY },
                onCreateCategory = actions.onCreateCategory,
                onMerchant = { picker = RefundReferencePicker.MERCHANT },
                onProject = { picker = RefundReferencePicker.PROJECT },
                onGoal = { picker = RefundReferencePicker.GOAL },
            )
        }
        item { RefundTimeAndPolicies(state, original, actions) }
        if (RefundPolicy.exceedsRemaining(state)) {
            item { ExcessConfirmation(state, actions) }
        }
        item {
            LedgerTextField(
                state.draft.note,
                actions.onNote,
                stringResource(R.string.refund_note),
                singleLine = false,
                hideValueFromSemantics = true,
            )
        }
        if (original?.settlementActivityId != null) {
            item { LedgerBanner(stringResource(R.string.refund_settlement_inherited, original.settlementShares.size), LedgerBannerVariant.NEUTRAL) }
        }
        if (original?.installmentPlanId != null) {
            item { LedgerBanner(stringResource(R.string.refund_installment_relation), LedgerBannerVariant.NEUTRAL) }
        }
        if (state.presentation == RefundPresentation.SAVE_ERROR) {
            item { LedgerBanner(stringResource(R.string.refund_save_failed, state.failureCode.orEmpty()), LedgerBannerVariant.DANGER) }
        }
    }
    picker?.let { activePicker ->
        RefundReferencePickerDialog(
            picker = activePicker,
            state = state,
            onDismiss = { picker = null },
            onSelected = { id ->
                when (activePicker) {
                    RefundReferencePicker.ACCOUNT -> id?.let(actions.onAccount)
                    RefundReferencePicker.CARD -> actions.onCard(id)
                    RefundReferencePicker.CATEGORY -> id?.let(actions.onCategory)
                    RefundReferencePicker.MERCHANT -> actions.onMerchant(id)
                    RefundReferencePicker.PROJECT -> actions.onProject(id)
                    RefundReferencePicker.GOAL -> actions.onGoal(id)
                }
                picker = null
            },
        )
    }
}

@Composable
private fun RefundAmountSummary(original: RefundableTransactionView, state: RefundEditorState, locale: Locale) {
    val current = state.draft.resultMinor ?: 0L
    LedgerCard(Modifier.fillMaxWidth().testTag(LedgerTestTags.REFUND_AMOUNT_SUMMARY)) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
            LedgerText(stringResource(R.string.refund_original_amount, RefundPolicy.format(original.originalMinor, original.originalCurrency, locale).formatted), LedgerTextRole.BODY)
            LedgerText(stringResource(R.string.refund_refunded_amount, RefundPolicy.format(original.refundedMinor, original.originalCurrency, locale).formatted), LedgerTextRole.BODY)
            LedgerText(stringResource(R.string.refund_remaining_amount, RefundPolicy.format(original.remainingMinor, original.originalCurrency, locale).formatted), LedgerTextRole.BODY)
            LedgerText(stringResource(R.string.refund_current_amount, RefundPolicy.format(current, original.originalCurrency, locale).formatted), LedgerTextRole.TITLE)
            if (original.excessRefundedMinor > 0L) LedgerText(stringResource(R.string.refund_existing_excess, RefundPolicy.format(original.excessRefundedMinor, original.originalCurrency, locale).formatted), LedgerTextRole.SUPPORTING)
        }
    }
}

@Composable
private fun ReceivingFields(state: RefundEditorState, onAccount: () -> Unit, onCard: () -> Unit) {
    val account = RefundPolicy.account(state, state.draft.receivingAccountId)
    val card = state.snapshot.references.cards.singleOrNull { it.id == state.draft.receivingCardId }
    FormSection(stringResource(R.string.refund_receiving_section)) {
        SelectorField(stringResource(R.string.refund_receiving_account), account?.let { "${it.name} · ${it.currency.value}" } ?: stringResource(R.string.refund_select_account), onAccount, supportingText = state.errors.fieldError(RefundField.ACCOUNT))
        SelectorField(stringResource(R.string.refund_receiving_card), card?.displayName ?: stringResource(R.string.refund_no_card), onCard)
    }
}

@Composable
private fun InheritedFields(
    state: RefundEditorState,
    onCategory: () -> Unit,
    onCreateCategory: () -> Unit,
    onMerchant: () -> Unit,
    onProject: () -> Unit,
    onGoal: () -> Unit,
) {
    val category = state.snapshot.references.categories.singleOrNull { it.id == state.draft.categoryId }
    val expenseCategories = state.snapshot.references.categories.filter {
        it.status.name == "ACTIVE" && it.direction.name == "EXPENSE"
    }
    val merchant = state.snapshot.references.merchants.singleOrNull { it.id == state.draft.merchantId }
    val project = state.snapshot.projects.singleOrNull { it.id == state.draft.projectId }
    val goal = state.snapshot.goals.singleOrNull { it.id == state.draft.goalId }
    FormSection(
        stringResource(if (state.draft.independent) R.string.refund_independent_fields else R.string.refund_inherited_fields),
        description = stringResource(if (state.draft.independent) R.string.refund_independent_fields_explanation else R.string.refund_inherited_adjustable),
    ) {
        if (expenseCategories.isEmpty()) {
            LedgerEmptyState(
                stringResource(R.string.record_no_categories),
                stringResource(R.string.record_no_categories_body),
                stringResource(R.string.record_create_expense_category),
                onCreateCategory,
            )
            if (state.errors.any { it.field == RefundField.CATEGORY }) {
                LedgerBanner(stringResource(R.string.refund_category_required), LedgerBannerVariant.DANGER)
            }
        } else {
            SelectorField(
                stringResource(R.string.refund_category),
                category?.name ?: stringResource(R.string.refund_select_category),
                onCategory,
                supportingText = state.errors.fieldError(RefundField.CATEGORY),
            )
        }
        SelectorField(stringResource(R.string.refund_merchant), merchant?.name ?: stringResource(R.string.refund_none), onMerchant)
        SelectorField(stringResource(R.string.refund_project), project?.name ?: stringResource(R.string.refund_none), onProject)
        SelectorField(stringResource(R.string.refund_goal), goal?.name ?: stringResource(R.string.refund_none), onGoal)
    }
}

private enum class RefundReferencePicker { ACCOUNT, CARD, CATEGORY, MERCHANT, PROJECT, GOAL }

private data class RefundPickerOption(val id: StableId?, val label: String)

@Composable
private fun RefundReferencePickerDialog(
    picker: RefundReferencePicker,
    state: RefundEditorState,
    onDismiss: () -> Unit,
    onSelected: (StableId?) -> Unit,
) {
    var query by remember(picker) { mutableStateOf("") }
    var selectedId by remember(picker) {
        mutableStateOf(
            when (picker) {
                RefundReferencePicker.ACCOUNT -> state.draft.receivingAccountId
                RefundReferencePicker.CARD -> state.draft.receivingCardId
                RefundReferencePicker.CATEGORY -> state.draft.categoryId
                RefundReferencePicker.MERCHANT -> state.draft.merchantId
                RefundReferencePicker.PROJECT -> state.draft.projectId
                RefundReferencePicker.GOAL -> state.draft.goalId
            },
        )
    }
    val noneLabel = stringResource(R.string.refund_none)
    val options = when (picker) {
        RefundReferencePicker.ACCOUNT -> state.snapshot.references.accounts
            .filter { it.status == EntityStatus.ACTIVE }
            .sortedBy { it.sortOrder }
            .map { RefundPickerOption(it.id, "${it.name} · ${it.currency.value}") }
        RefundReferencePicker.CARD -> listOf(RefundPickerOption(null, noneLabel)) + state.snapshot.references.cards
            .filter { it.status == EntityStatus.ACTIVE && it.accountId == state.draft.receivingAccountId }
            .sortedBy { it.sortOrder }
            .map { RefundPickerOption(it.id, it.displayName) }
        RefundReferencePicker.CATEGORY -> state.snapshot.references.categories
            .filter { it.status == CategoryStatus.ACTIVE && it.direction.name == "EXPENSE" }
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
            .map { RefundPickerOption(it.id, it.name) }
        RefundReferencePicker.MERCHANT -> listOf(RefundPickerOption(null, noneLabel)) + state.snapshot.references.merchants
            .filter { it.status == EntityStatus.ACTIVE }
            .sortedBy { it.name }
            .map { RefundPickerOption(it.id, it.name) }
        RefundReferencePicker.PROJECT -> listOf(RefundPickerOption(null, noneLabel)) + state.snapshot.projects
            .sortedBy { it.name }
            .map { RefundPickerOption(it.id, it.name) }
        RefundReferencePicker.GOAL -> listOf(RefundPickerOption(null, noneLabel)) + state.snapshot.goals
            .sortedBy { it.name }
            .map { RefundPickerOption(it.id, it.name) }
    }.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
    val title = stringResource(
        when (picker) {
            RefundReferencePicker.ACCOUNT -> R.string.refund_receiving_account
            RefundReferencePicker.CARD -> R.string.refund_receiving_card
            RefundReferencePicker.CATEGORY -> R.string.refund_category
            RefundReferencePicker.MERCHANT -> R.string.refund_merchant
            RefundReferencePicker.PROJECT -> R.string.refund_project
            RefundReferencePicker.GOAL -> R.string.refund_goal
        },
    )
    LedgerDialog(
        title = title,
        message = null,
        confirmLabel = stringResource(R.string.refund_apply_selection),
        onConfirm = { onSelected(selectedId) },
        onDismiss = onDismiss,
        confirmEnabled = selectedId != null || picker in setOf(RefundReferencePicker.CARD, RefundReferencePicker.MERCHANT, RefundReferencePicker.PROJECT, RefundReferencePicker.GOAL),
    ) {
        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.refund_search_references),
            onClear = { query = "" },
            autoFocus = false,
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(options, key = { it.id?.toString() ?: "none" }) { option ->
                LedgerChoiceRow(option.label, selectedId == option.id, { selectedId = option.id })
            }
        }
    }
}

@Composable
private fun RefundTimeAndPolicies(state: RefundEditorState, original: RefundableTransactionView?, actions: RefundActions) {
    val locale = LocalLocale.current.platformLocale
    var showDatePicker by remember { mutableStateOf(false) }
    FormSection(stringResource(R.string.refund_three_time_dimensions), modifier = Modifier.testTag(LedgerTestTags.REFUND_TIME_DIMENSIONS), description = stringResource(R.string.refund_three_time_explanation)) {
        DateTimeZoneField(stringResource(R.string.refund_cash_date), state.draft.localDate.localized(locale), state.draft.zoneId.id, { showDatePicker = true })
        LedgerText(stringResource(R.string.refund_accrual_date, if (state.draft.accrualPolicy == RefundAccrualPolicy.ORIGINAL_TRANSACTION_DATE) original?.localDate?.localized(locale).orEmpty() else state.draft.localDate.localized(locale)), LedgerTextRole.SUPPORTING)
        PolicyChoices(
            labels = RefundAccrualPolicy.entries.map { stringResource(it.label()) },
            selected = RefundAccrualPolicy.entries.indexOf(state.draft.accrualPolicy),
            onSelected = { actions.onAccrualPolicy(RefundAccrualPolicy.entries[it]) },
        )
        LedgerText(stringResource(R.string.refund_budget_month, budgetMonthText(state, original, locale)), LedgerTextRole.SUPPORTING)
        PolicyChoices(
            labels = RefundBudgetPolicy.entries.map { stringResource(it.label()) },
            selected = RefundBudgetPolicy.entries.indexOf(state.draft.budgetPolicy),
            onSelected = { actions.onBudgetPolicy(RefundBudgetPolicy.entries[it]) },
        )
        PolicyChoices(
            labels = RefundProjectPolicy.entries.map { stringResource(it.label()) },
            selected = RefundProjectPolicy.entries.indexOf(state.draft.projectPolicy),
            onSelected = { actions.onProjectPolicy(RefundProjectPolicy.entries[it]) },
        )
        PolicyChoices(
            labels = RefundGoalPolicy.entries.map { stringResource(it.label()) },
            selected = RefundGoalPolicy.entries.indexOf(state.draft.goalPolicy),
            onSelected = { actions.onGoalPolicy(RefundGoalPolicy.entries[it]) },
        )
    }
    if (showDatePicker) {
        LedgerDatePickerFlow(
            state.draft.localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            { millis ->
                actions.onDate(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                showDatePicker = false
            },
            { showDatePicker = false },
        )
    }
}

@Composable
private fun PolicyChoices(labels: List<String>, selected: Int, onSelected: (Int) -> Unit) {
    Column {
        labels.forEachIndexed { index, label -> LedgerChoiceRow(label, selected == index, { onSelected(index) }) }
    }
}

@Composable
private fun ExcessConfirmation(state: RefundEditorState, actions: RefundActions) {
    Column(Modifier.fillMaxWidth().testTag(LedgerTestTags.REFUND_EXCESS_CONFIRMATION), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        LedgerBanner(stringResource(R.string.refund_excess_warning), LedgerBannerVariant.DANGER)
        LedgerCheckboxRow(stringResource(R.string.refund_excess_override), state.draft.excessOverrideRequested, actions.onRequestExcess, supportingText = stringResource(R.string.refund_excess_audit))
        if (state.draft.excessOverrideRequested && !state.draft.excessRiskConfirmed) {
            LedgerButton(stringResource(R.string.refund_confirm_excess), actions.onConfirmExcess, variant = LedgerButtonVariant.DANGER)
        } else if (state.draft.excessRiskConfirmed) {
            LedgerBanner(stringResource(R.string.refund_excess_confirmed), LedgerBannerVariant.WARNING)
        }
    }
}

@Composable
public fun RefundOriginalPickerDestination(
    state: RefundPickerState,
    onAction: (RefundPickerScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = refundPickerActions(onAction)
    Column(modifier.fillMaxSize().testTag(LedgerTestTags.REFUND_PICKER)) {
        when (state) {
            RefundPickerState.Loading -> LedgerLoadingState(Modifier.fillMaxSize())
            is RefundPickerState.Failure -> LedgerErrorState(UiErrorCode(state.code), stringResource(R.string.refund_load_failed), actions.onRetry)
            is RefundPickerState.Content -> {
                Column(Modifier.fillMaxSize().padding(horizontal = LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
                    LedgerTextField(state.query.text, actions.onQuery, stringResource(R.string.refund_search_original))
                    if (state.searching) LedgerBanner(stringResource(R.string.refund_searching), LedgerBannerVariant.NEUTRAL)
                    Row(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                        LedgerChip(stringResource(R.string.refund_partially_refunded), { actions.onPartialOnly(!state.query.partiallyRefundedOnly) }, selected = state.query.partiallyRefundedOnly)
                        LedgerChip(stringResource(R.string.refund_independent), actions.onIndependent)
                    }
                    if (state.snapshot.originals.isEmpty()) {
                        LedgerEmptyState(stringResource(R.string.refund_no_originals), stringResource(R.string.refund_no_originals_explanation), stringResource(R.string.refund_independent), actions.onIndependent)
                    } else {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                            items(state.snapshot.originals, key = { it.transactionId.toString() }) { original -> RefundOriginalRow(original, actions.onChoose) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RefundOriginalRow(original: RefundableTransactionView, onChoose: (StableId) -> Unit) {
    val locale = LocalLocale.current.platformLocale
    LedgerCard(Modifier.fillMaxWidth(), onClick = { onChoose(original.transactionId) }) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
            LedgerText(original.categoryName, LedgerTextRole.TITLE)
            LedgerText(original.localDate.localized(locale), LedgerTextRole.SUPPORTING)
            LedgerText(stringResource(R.string.refund_original_amount, RefundPolicy.format(original.originalMinor, original.originalCurrency, locale).formatted), LedgerTextRole.BODY)
            LedgerText(stringResource(R.string.refund_remaining_amount, RefundPolicy.format(original.remainingMinor, original.originalCurrency, locale).formatted), LedgerTextRole.BODY)
        }
    }
}

@Composable
private fun List<RefundValidationError>.fieldError(field: RefundField): String? = firstOrNull { it.field == field }?.let { stringResource(R.string.refund_invalid_field) }

private fun budgetMonthText(state: RefundEditorState, original: RefundableTransactionView?, locale: Locale): String = when (state.draft.budgetPolicy) {
    RefundBudgetPolicy.RESTORE_ORIGINAL_MONTH -> original?.localDate?.let(YearMonth::from)?.localized(locale).orEmpty()
    RefundBudgetPolicy.RESTORE_REFUND_MONTH -> YearMonth.from(state.draft.localDate).localized(locale)
    RefundBudgetPolicy.DO_NOT_RESTORE -> "—"
}

private fun LocalDate.localized(locale: Locale): String =
    format(LedgerDateFormatterRuntime.formatter(locale))

private fun YearMonth.localized(locale: Locale): String =
    format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))

private fun RefundAccrualPolicy.label(): Int = when (this) {
    RefundAccrualPolicy.ORIGINAL_TRANSACTION_DATE -> R.string.refund_accrual_original
    RefundAccrualPolicy.REFUND_DATE -> R.string.refund_accrual_refund
}

private fun RefundBudgetPolicy.label(): Int = when (this) {
    RefundBudgetPolicy.RESTORE_ORIGINAL_MONTH -> R.string.refund_budget_original
    RefundBudgetPolicy.RESTORE_REFUND_MONTH -> R.string.refund_budget_refund
    RefundBudgetPolicy.DO_NOT_RESTORE -> R.string.refund_budget_none
}

private fun RefundProjectPolicy.label(): Int = when (this) {
    RefundProjectPolicy.RESTORE_ORIGINAL_PROJECT -> R.string.refund_project_original
    RefundProjectPolicy.USE_SELECTED_PROJECT -> R.string.refund_project_selected
    RefundProjectPolicy.DO_NOT_RESTORE -> R.string.refund_project_none
}

private fun RefundGoalPolicy.label(): Int = when (this) {
    RefundGoalPolicy.RESTORE_ORIGINAL_GOAL -> R.string.refund_goal_original
    RefundGoalPolicy.USE_SELECTED_GOAL -> R.string.refund_goal_selected
    RefundGoalPolicy.DO_NOT_RESTORE -> R.string.refund_goal_none
}
