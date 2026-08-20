@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber", "MaxLineLength", "TooManyFunctions", "ktlint:standard:function-naming")

package app.ledger.feature.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.designsystem.AccessibleDataTable
import app.ledger.core.designsystem.AccessibleTableUiModel
import app.ledger.core.designsystem.AmountSize
import app.ledger.core.designsystem.AmountText
import app.ledger.core.designsystem.ChartCard
import app.ledger.core.designsystem.FormSection
import app.ledger.core.designsystem.JournalTransactionRow
import app.ledger.core.designsystem.JournalTransactionUiModel
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerChartSeries
import app.ledger.core.designsystem.LedgerChartType
import app.ledger.core.designsystem.LedgerChartUiModel
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerDatePickerFlow
import app.ledger.core.designsystem.LedgerIcon
import app.ledger.core.designsystem.LedgerLineChart
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerProgressIndicator
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerStatusVariant
import app.ledger.core.designsystem.LedgerTabRow
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerToggleRow
import app.ledger.core.designsystem.LedgerVicoLineRenderer
import app.ledger.core.designsystem.MetricCard
import app.ledger.core.designsystem.MetricCardVariant
import app.ledger.core.designsystem.SelectorField
import app.ledger.core.designsystem.StatusBadge
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.finance.application.GoalCompletionStrategy
import app.ledger.finance.application.GoalView
import app.ledger.finance.application.ProjectSettlementView
import app.ledger.finance.application.ProjectTransactionView
import app.ledger.finance.application.ProjectView
import app.ledger.finance.domain.GoalMovementKind
import app.ledger.finance.domain.GoalStatus
import app.ledger.finance.domain.ProjectStatus
import app.ledger.finance.domain.TransactionKind
import app.ledger.core.money.AmountSemantic
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.Flow

@Composable
public fun ProjectGoalDestination(
    screenId: String,
    state: ProjectGoalLoadState,
    encodedArguments: Map<String, String>,
    actions: ProjectGoalActions,
    projectPages: Flow<PagingData<ProjectTransactionView>>? = null,
) {
    when (state) {
        ProjectGoalLoadState.Loading -> LedgerLoadingState(Modifier.fillMaxSize(), stringResource(R.string.planning_loading))
        is ProjectGoalLoadState.Failure -> LedgerErrorState(UiErrorCode(state.code), stringResource(R.string.planning_load_failed), actions.onRetry)
        is ProjectGoalLoadState.Content -> {
            val projectId = encodedArguments.stableId("projectId") ?: state.state.selectedProjectId
            val goalId = encodedArguments.stableId("goalId") ?: state.state.selectedGoalId
            val movement = encodedArguments["kind"]?.let { value -> GoalMovementKind.entries.singleOrNull { it.name == value } }
            val content = state.state.copy(
                selectedProjectId = projectId,
                selectedGoalId = goalId,
                movementKind = movement ?: state.state.movementKind,
            )
            when (screenId) {
                "PRJ-001" -> ProjectList(content, actions)
                "PRJ-002" -> ProjectEditor(content, actions)
                "PRJ-003" -> ProjectDetail(content, actions)
                "PRJ-004" -> ProjectTransactions(content, actions, projectPages)
                "PRJ-005" -> ProjectCashflow(content, actions)
                "PRJ-006" -> ProjectStatusEditor(content, actions)
                "GOL-001" -> GoalList(content, actions)
                "GOL-002" -> GoalEditor(content, actions)
                "GOL-003" -> GoalDetail(content, actions)
                "GOL-004" -> GoalMovementEditor(content, actions)
                "GOL-005" -> GoalCompletion(content, actions)
                else -> LedgerErrorState(UiErrorCode("PLANNING_SCREEN_UNKNOWN"), stringResource(R.string.planning_load_failed), actions.onRetry)
            }
        }
    }
}

@Composable
private fun ProjectList(state: ProjectGoalFeatureState, actions: ProjectGoalActions) {
    val active = state.snapshot.projects.filter { it.status == ProjectStatus.ACTIVE }
    val archived = state.snapshot.projects.filter { it.status == ProjectStatus.ARCHIVED }
    val showingArchived = state.presentation == ProjectGoalPresentation.ARCHIVED_ONLY
    val visibleProjects = if (showingArchived) archived else active
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.PROJECT_LIST),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        item {
            LedgerTabRow(
                if (showingArchived) 1 else 0,
                listOf(stringResource(R.string.project_active), stringResource(R.string.project_archived)),
                { actions.onProjectStatusTabSelected(it == 1) },
            )
        }
        if (visibleProjects.isEmpty()) {
            item {
                LedgerEmptyState(
                    if (showingArchived) stringResource(R.string.project_no_archived) else stringResource(R.string.project_empty),
                    stringResource(R.string.project_empty_body),
                    stringResource(R.string.project_create),
                    { actions.onNavigate("PRJ-002", null, null) },
                )
            }
        } else {
            items(visibleProjects, key = { it.id.toString() }) { ProjectRow(it, state, actions) }
        }
        item { LedgerButton(stringResource(R.string.project_create), { actions.onNavigate("PRJ-002", null, null) }, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun ProjectRow(project: ProjectView, state: ProjectGoalFeatureState, actions: ProjectGoalActions) {
    val locale = LocalLocale.current.platformLocale
    LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onNavigate("PRJ-003", project.id, null) }) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LedgerText(project.name, LedgerTextRole.SECTION)
                StatusBadge(
                    if (project.status == ProjectStatus.ACTIVE) stringResource(R.string.project_active) else stringResource(R.string.project_archived),
                    if (project.status == ProjectStatus.ACTIVE) LedgerStatusVariant.POSITIVE else LedgerStatusVariant.ARCHIVED,
                )
            }
            AmountText(ProjectGoalPolicy.money(project.remainingBaseMinor, state.snapshot.baseCurrency, locale), AmountSize.MEDIUM)
            LedgerText(stringResource(R.string.project_used_budget, money(state, project.usedBaseMinor), money(state, project.budgetBaseMinor)), LedgerTextRole.SUPPORTING)
            LedgerText(stringResource(R.string.project_period, project.startDate.localizedDate(), project.endDate?.localizedDate() ?: stringResource(R.string.project_no_end)), LedgerTextRole.SUPPORTING)
            project.goalName?.let { LedgerText(stringResource(R.string.project_linked_goal, it), LedgerTextRole.SUPPORTING) }
            if (project.settlements.any(ProjectSettlementView::requiresAdditionalSettlement)) {
                LedgerText(stringResource(R.string.project_settlement_required), LedgerTextRole.SUPPORTING)
            }
            ProjectProgress(project, state.snapshot.baseCurrency)
        }
    }
}

@Composable
private fun ProjectEditor(state: ProjectGoalFeatureState, actions: ProjectGoalActions) {
    val draft = state.projectDraft
    val selectedGoal = state.snapshot.goals.singleOrNull { it.id == draft.goalId }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showGoalChoices by remember { mutableStateOf(false) }
    val saving = state.presentation == ProjectGoalPresentation.SAVING
    val valid = ProjectGoalPolicy.validateProject(state).projectErrors.isEmpty()
    LedgerScaffold(
        modifier = Modifier.fillMaxSize().testTag(LedgerTestTags.PROJECT_EDITOR),
        formContent = true,
        fixedAction = { PlanningStickySaveBar(actions.onSaveProject, valid && !saving, saving) },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(LedgerTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
        ) {
            if (state.projectErrors.isNotEmpty()) item { LedgerBanner(stringResource(R.string.planning_validation_error), LedgerBannerVariant.DANGER) }
            if (saving) item { LedgerLoadingState(Modifier.fillMaxWidth(), stringResource(R.string.planning_saving)) }
            item {
                FormSection(stringResource(R.string.project_basics)) {
                    LedgerTextField(draft.name, actions.onProjectNameChanged, stringResource(R.string.project_name), required = true, errorText = error(state, "name"))
                    LedgerTextField(draft.description, actions.onProjectDescriptionChanged, stringResource(R.string.project_description), singleLine = false)
                    SelectorField(stringResource(R.string.project_start_date), draft.startDate.localizedDate(), { showStartDatePicker = true })
                    SelectorField(
                        stringResource(R.string.project_end_date),
                        draft.endDate?.localizedDate() ?: stringResource(R.string.project_no_end),
                        { showEndDatePicker = true },
                        supportingText = error(state, "endDate"),
                    )
                    if (draft.endDate != null) {
                        LedgerButton(
                            stringResource(R.string.project_clear_end_date),
                            { actions.onProjectEndDateChanged("") },
                            variant = LedgerButtonVariant.TEXT,
                            compact = true,
                        )
                    }
                }
            }
            item {
                FormSection(stringResource(R.string.project_budget)) {
                    LedgerTextField(
                        draft.budgetText,
                        actions.onProjectBudgetChanged,
                        stringResource(R.string.project_budget_amount, state.snapshot.baseCurrency.value),
                        required = true,
                        keyboardType = KeyboardType.Decimal,
                        errorText = error(state, "budget"),
                    )
                    LedgerToggleRow(
                        stringResource(R.string.project_include_monthly),
                        draft.includedInMonthlyBudget,
                        { actions.onProjectMonthlyBudgetChanged() },
                        Modifier.testTag(LedgerTestTags.PROJECT_MONTHLY_SNAPSHOT),
                        stringResource(R.string.project_include_snapshot_explanation),
                    )
                    LedgerBanner(stringResource(R.string.project_include_snapshot_banner), LedgerBannerVariant.INFO)
                }
            }
            item {
                FormSection(stringResource(R.string.project_goal_relation), description = stringResource(R.string.project_one_goal)) {
                    SelectorField(
                        stringResource(R.string.project_goal_relation),
                        selectedGoal?.name ?: stringResource(R.string.project_no_goal),
                        { showGoalChoices = !showGoalChoices },
                    )
                    if (showGoalChoices) {
                        LedgerChoiceRow(
                            stringResource(R.string.project_no_goal),
                            draft.goalId == null,
                            { actions.onProjectGoalChanged(null); showGoalChoices = false },
                        )
                        state.snapshot.goals.filter { it.status != GoalStatus.ARCHIVED }.forEach { goal ->
                            LedgerChoiceRow(
                                goal.name,
                                draft.goalId == goal.id,
                                { actions.onProjectGoalChanged(goal.id); showGoalChoices = false },
                            )
                        }
                    }
                }
            }
        }
    }
    if (showStartDatePicker) {
        LedgerDatePickerFlow(
            draft.startDate.utcDateMillis(),
            { millis -> actions.onProjectStartDateChanged(millis.utcLocalDate().toString()); showStartDatePicker = false },
            { showStartDatePicker = false },
        )
    }
    if (showEndDatePicker) {
        LedgerDatePickerFlow(
            (draft.endDate ?: draft.startDate).utcDateMillis(),
            { millis -> actions.onProjectEndDateChanged(millis.utcLocalDate().toString()); showEndDatePicker = false },
            { showEndDatePicker = false },
        )
    }
}

@Composable
private fun ProjectDetail(state: ProjectGoalFeatureState, actions: ProjectGoalActions) {
    val project = state.project ?: return PlanningNotFound(actions)
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.PROJECT_DETAIL),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        item { ProjectTabRow(0, project, actions) }
        if (project.status == ProjectStatus.ARCHIVED || state.presentation == ProjectGoalPresentation.ARCHIVED) {
            item { LedgerBanner(stringResource(R.string.project_archived_new_blocked), LedgerBannerVariant.NEUTRAL) }
        }
        if (project.overBudget || state.presentation == ProjectGoalPresentation.OVER_BUDGET) {
            item { LedgerBanner(stringResource(R.string.project_over_budget, money(state, -project.remainingBaseMinor)), LedgerBannerVariant.WARNING) }
        }
        item {
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LedgerTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    LedgerText(project.name, LedgerTextRole.TITLE)
                    project.description?.let { LedgerText(it, LedgerTextRole.BODY) }
                    LedgerText(stringResource(R.string.project_period, project.startDate.localizedDate(), project.endDate?.localizedDate() ?: stringResource(R.string.project_no_end)), LedgerTextRole.SUPPORTING)
                    LedgerText(stringResource(R.string.project_used_budget, money(state, project.usedBaseMinor), money(state, project.budgetBaseMinor)), LedgerTextRole.BODY)
                    if (project.restoredBaseMinor != 0L) {
                        LedgerText(stringResource(R.string.project_refund_restored, money(state, project.restoredBaseMinor)), LedgerTextRole.BODY)
                    }
                    LedgerText(stringResource(R.string.project_remaining, money(state, project.remainingBaseMinor)), LedgerTextRole.BODY)
                    ProjectProgress(project, state.snapshot.baseCurrency)
                    LedgerText(
                        if (project.includedInMonthlyBudget) stringResource(R.string.project_snapshot_included) else stringResource(R.string.project_snapshot_excluded),
                        LedgerTextRole.SUPPORTING,
                        Modifier.testTag(LedgerTestTags.PROJECT_MONTHLY_SNAPSHOT),
                    )
                }
            }
        }
        item {
            LedgerBanner(stringResource(R.string.project_self_share_rule), LedgerBannerVariant.INFO)
            LedgerBanner(stringResource(R.string.project_exclusion_rule), LedgerBannerVariant.NEUTRAL)
        }
        if (project.goalId != null) {
            item { LedgerButton(stringResource(R.string.project_linked_goal, project.goalName.orEmpty()), { actions.onNavigate("GOL-003", project.goalId, null) }, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY) }
        }
        if (project.settlements.isNotEmpty()) {
            item { LedgerText(stringResource(R.string.project_settlements), LedgerTextRole.SECTION) }
            items(project.settlements, key = { it.id.toString() }) { settlement ->
                LedgerCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                        LedgerText(settlement.name, LedgerTextRole.BODY)
                        if (settlement.requiresAdditionalSettlement) LedgerText(stringResource(R.string.project_settlement_required), LedgerTextRole.SUPPORTING)
                    }
                }
            }
        }
        if (project.transactions.isNotEmpty()) {
            item { LedgerText(stringResource(R.string.project_recent_transactions), LedgerTextRole.SECTION) }
            items(project.transactions.take(3), key = { "recent-${it.revisionId}" }) { transaction ->
                ProjectTransactionRow(transaction, state, project, actions)
            }
        } else {
            item {
                LedgerEmptyState(
                    stringResource(R.string.project_no_transactions),
                    stringResource(R.string.project_no_transactions_body),
                    stringResource(R.string.project_view_transaction_filter),
                    { actions.onNavigate("PRJ-004", project.id, null) },
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                LedgerButton(stringResource(R.string.project_transactions), { actions.onNavigate("PRJ-004", project.id, null) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
                LedgerButton(stringResource(R.string.project_cashflow), { actions.onNavigate("PRJ-005", project.id, null) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY)
            }
            LedgerButton(stringResource(R.string.project_edit), { actions.onNavigate("PRJ-002", project.id, null) }, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT)
            LedgerButton(
                if (project.status == ProjectStatus.ACTIVE) stringResource(R.string.project_archive) else stringResource(R.string.project_reopen),
                { actions.onNavigate("PRJ-006", project.id, null) },
                Modifier.fillMaxWidth(),
                if (project.status == ProjectStatus.ACTIVE) LedgerButtonVariant.DANGER else LedgerButtonVariant.PRIMARY,
            )
        }
    }
}

@Composable
private fun ProjectTransactions(
    state: ProjectGoalFeatureState,
    actions: ProjectGoalActions,
    projectPages: Flow<PagingData<ProjectTransactionView>>?,
) {
    val project = state.project ?: return PlanningNotFound(actions)
    val paged = projectPages?.collectAsLazyPagingItems()
    var selectedFilter by remember(project.id) { mutableIntStateOf(0) }
    val transactions = project.transactions.filter { transaction ->
        selectedFilter == 0 || selectedFilter == 1 && transaction.kind == TransactionKind.EXPENSE ||
            selectedFilter == 2 && transaction.kind == TransactionKind.INCOME
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.PROJECT_TRANSACTIONS),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        item { ProjectTabRow(1, project, actions) }
        item { ProjectFilterRow(project) }
        item {
            LedgerTabRow(
                selectedFilter,
                listOf(stringResource(R.string.project_filter_all), stringResource(R.string.project_filter_expense), stringResource(R.string.project_filter_income)),
                { selectedFilter = it },
            )
        }
        item { LedgerBanner(stringResource(R.string.project_transactions_rule), LedgerBannerVariant.INFO) }
        if (paged == null && (transactions.isEmpty() || state.presentation == ProjectGoalPresentation.NO_TRANSACTIONS)) {
            item { LedgerEmptyState(stringResource(R.string.project_no_transactions), stringResource(R.string.project_no_transactions_body), stringResource(R.string.project_back), { actions.onNavigate("PRJ-003", project.id, null) }) }
        } else if (paged == null) {
            items(transactions, key = { it.revisionId.toString() }) { transaction -> ProjectTransactionRow(transaction, state, project, actions) }
        } else {
            if (paged.loadState.refresh is LoadState.Loading) {
                item { LedgerLoadingState(Modifier.fillMaxWidth(), stringResource(R.string.planning_loading)) }
            }
            if (paged.loadState.refresh is LoadState.Error) {
                item { LedgerErrorState(UiErrorCode("PROJECT_PAGE_LOAD_FAILED"), stringResource(R.string.planning_load_failed), paged::retry) }
            }
            items(paged.itemCount, key = { index -> paged.peek(index)?.revisionId?.toString() ?: "project-placeholder-$index" }) { index ->
                paged[index]?.takeIf { transaction ->
                    selectedFilter == 0 || selectedFilter == 1 && transaction.kind == TransactionKind.EXPENSE ||
                        selectedFilter == 2 && transaction.kind == TransactionKind.INCOME
                }?.let { ProjectTransactionRow(it, state, project, actions) }
            }
            if (paged.loadState.append is LoadState.Loading) {
                item { LedgerLoadingState(Modifier.fillMaxWidth(), stringResource(R.string.planning_loading)) }
            }
            if (paged.loadState.append is LoadState.Error) {
                item { LedgerErrorState(UiErrorCode("PROJECT_PAGE_APPEND_FAILED"), stringResource(R.string.planning_load_failed), paged::retry) }
            }
        }
    }
}

@Composable
private fun ProjectFilterRow(project: ProjectView) {
    LedgerCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs),
        ) {
            LedgerText(stringResource(R.string.project_filter_scope, project.name), LedgerTextRole.SECTION)
            LedgerText(
                stringResource(
                    R.string.project_period,
                    project.startDate.localizedDate(),
                    project.endDate?.localizedDate() ?: stringResource(R.string.project_no_end),
                ),
                LedgerTextRole.SUPPORTING,
            )
        }
    }
}

@Composable
private fun ProjectCashflow(state: ProjectGoalFeatureState, actions: ProjectGoalActions) {
    val project = state.project ?: return PlanningNotFound(actions)
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.PROJECT_CASHFLOW),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        item { ProjectTabRow(2, project, actions) }
        if (project.cashflow.isEmpty()) {
            item {
                LedgerEmptyState(
                    stringResource(R.string.project_no_cashflow),
                    stringResource(R.string.project_no_cashflow_body),
                    stringResource(R.string.project_back),
                    { actions.onNavigate("PRJ-003", project.id, null) },
                )
            }
        } else {
            item { ProjectCashflowChart(state, project) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    MetricCard(stringResource(R.string.project_cash_in), ProjectGoalPolicy.money(project.cashInflowBaseMinor, state.snapshot.baseCurrency, LocalLocale.current.platformLocale), Modifier.weight(1f))
                    MetricCard(stringResource(R.string.project_cash_out), ProjectGoalPolicy.money(project.cashOutflowBaseMinor, state.snapshot.baseCurrency, LocalLocale.current.platformLocale), Modifier.weight(1f))
                }
            }
            item {
                MetricCard(
                    stringResource(R.string.project_net),
                    ProjectGoalPolicy.money(
                        Math.subtractExact(project.cashInflowBaseMinor, project.cashOutflowBaseMinor),
                        state.snapshot.baseCurrency,
                        LocalLocale.current.platformLocale,
                    ),
                    Modifier.fillMaxWidth(),
                    MetricCardVariant.EMPHASIZED,
                )
            }
        }
    }
}

@Composable
private fun ProjectCashflowChart(state: ProjectGoalFeatureState, project: ProjectView) {
    var expanded by remember(project.id) { mutableStateOf(false) }
    val model = LedgerChartUiModel(
        stringResource(R.string.project_cashflow),
        stringResource(R.string.project_cashflow_scope),
        stringResource(R.string.project_cashflow_summary, money(state, project.cashInflowBaseMinor), money(state, project.cashOutflowBaseMinor)),
        LedgerChartType.LINE,
        listOf(
            LedgerChartSeries(
                "project_income",
                stringResource(R.string.project_cash_in),
                project.cashflow.map { it.incomeBaseMinor.toDouble() },
                project.cashflow.map { it.date.localizedDate() },
                project.cashflow.map { money(state, it.incomeBaseMinor) },
            ),
            LedgerChartSeries(
                "project_expense",
                stringResource(R.string.project_cash_out),
                project.cashflow.map { it.expenseBaseMinor.toDouble() },
                project.cashflow.map { it.date.localizedDate() },
                project.cashflow.map { money(state, it.expenseBaseMinor) },
            ),
        ),
    )
    ChartCard(
        model,
        { LedgerLineChart(model, LedgerVicoLineRenderer, Modifier.fillMaxWidth()) },
        AccessibleTableUiModel(
            stringResource(R.string.project_cashflow_table),
            listOf(stringResource(R.string.planning_date), stringResource(R.string.project_cash_in), stringResource(R.string.project_cash_out), stringResource(R.string.project_net)),
            project.cashflow.map { listOf(it.date.localizedDate(), money(state, it.incomeBaseMinor), money(state, it.expenseBaseMinor), money(state, it.netBaseMinor)) },
        ),
        tableExpanded = expanded,
        onToggleTable = { expanded = !expanded },
    )
}

@Composable
private fun ProjectStatusEditor(state: ProjectGoalFeatureState, actions: ProjectGoalActions) {
    val project = state.project ?: return PlanningNotFound(actions)
    Column(
        Modifier.fillMaxSize().testTag(LedgerTestTags.PROJECT_STATUS).padding(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        LedgerText(if (project.status == ProjectStatus.ACTIVE) stringResource(R.string.project_archive) else stringResource(R.string.project_reopen), LedgerTextRole.TITLE)
        LedgerBanner(
            if (project.status == ProjectStatus.ACTIVE) stringResource(R.string.project_archive_explanation) else stringResource(R.string.project_reopen_explanation),
            if (project.status == ProjectStatus.ACTIVE) LedgerBannerVariant.WARNING else LedgerBannerVariant.INFO,
        )
        LedgerText(stringResource(R.string.project_history_retained), LedgerTextRole.BODY)
        LedgerButton(
            if (project.status == ProjectStatus.ACTIVE) stringResource(R.string.project_confirm_archive) else stringResource(R.string.project_confirm_reopen),
            actions.onChangeProjectStatus,
            Modifier.fillMaxWidth(),
            if (project.status == ProjectStatus.ACTIVE) LedgerButtonVariant.DANGER else LedgerButtonVariant.PRIMARY,
        )
    }
}

@Composable
private fun GoalList(state: ProjectGoalFeatureState, actions: ProjectGoalActions) {
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.GOAL_LIST),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        if (state.snapshot.goals.isEmpty()) {
            item { LedgerEmptyState(stringResource(R.string.goal_empty), stringResource(R.string.goal_empty_body), stringResource(R.string.goal_create), { actions.onNavigate("GOL-002", null, null) }) }
        } else {
            state.snapshot.accounts.forEach { account ->
                val goals = state.snapshot.goals.filter { it.accountId == account.id }
                if (goals.isNotEmpty()) {
                    item(key = "account-${account.id}") {
                        Column(Modifier.testTag(LedgerTestTags.ACCOUNT_AVAILABILITY)) {
                            LedgerText(account.name, LedgerTextRole.SECTION)
                            LedgerText(stringResource(R.string.goal_account_formula, money(account.actualBalanceMinor, account.currency), money(account.reservedMinor, account.currency), money(account.availableMinor, account.currency)), LedgerTextRole.SUPPORTING)
                            if (account.underfunded || state.presentation == ProjectGoalPresentation.UNDERFUNDED) LedgerBanner(stringResource(R.string.goal_underfunded_warning), LedgerBannerVariant.WARNING)
                        }
                    }
                    items(goals, key = { it.id.toString() }) { goal -> GoalRow(goal, actions) }
                }
            }
        }
        item { LedgerButton(stringResource(R.string.goal_create), { actions.onNavigate("GOL-002", null, null) }, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun GoalRow(goal: GoalView, actions: ProjectGoalActions) {
    LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onNavigate("GOL-003", goal.id, null) }) {
        Column(Modifier.padding(LedgerTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LedgerText(goal.name, LedgerTextRole.SECTION)
                StatusBadge(goal.status.goalStatusLabel(), goal.status.goalStatusVariant())
            }
            AmountText(ProjectGoalPolicy.money(goal.balanceMinor, goal.currency, LocalLocale.current.platformLocale), AmountSize.MEDIUM)
            GoalProgress(goal)
            LedgerText(stringResource(R.string.goal_target_text, money(goal.targetAmountMinor, goal.currency)), LedgerTextRole.SUPPORTING)
            goal.dueDate?.let { LedgerText(stringResource(R.string.goal_due_text, it.localizedDate()), LedgerTextRole.SUPPORTING) }
            LedgerText(stringResource(R.string.goal_account_available_text, goal.accountName, money(goal.accountAvailableMinor, goal.currency)), LedgerTextRole.SUPPORTING)
        }
    }
}

@Composable
private fun GoalEditor(state: ProjectGoalFeatureState, actions: ProjectGoalActions) {
    val draft = state.goalDraft
    val account = state.snapshot.accounts.singleOrNull { it.id == draft.accountId }
    var showAccounts by remember { mutableStateOf(false) }
    var showDueDatePicker by remember { mutableStateOf(false) }
    val saving = state.presentation == ProjectGoalPresentation.SAVING
    val valid = ProjectGoalPolicy.validateGoal(state).goalErrors.isEmpty()
    LedgerScaffold(
        modifier = Modifier.fillMaxSize().testTag(LedgerTestTags.GOAL_EDITOR),
        formContent = true,
        fixedAction = { PlanningStickySaveBar(actions.onSaveGoal, valid && !saving, saving) },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(LedgerTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
        ) {
            if (state.goalErrors.isNotEmpty()) item { LedgerBanner(stringResource(R.string.planning_validation_error), LedgerBannerVariant.DANGER) }
            if (state.goal != null || state.presentation == ProjectGoalPresentation.CURRENCY_LOCKED) item { LedgerBanner(stringResource(R.string.goal_account_locked), LedgerBannerVariant.INFO) }
            if (saving) item { LedgerLoadingState(Modifier.fillMaxWidth(), stringResource(R.string.planning_saving)) }
            item {
                LedgerTextField(draft.name, actions.onGoalNameChanged, stringResource(R.string.goal_name), required = true, errorText = goalError(state, "name"))
                SelectorField(
                    stringResource(R.string.goal_choose_account),
                    account?.name ?: stringResource(R.string.goal_choose_account),
                    { if (state.goal == null) showAccounts = !showAccounts },
                    enabled = state.goal == null,
                    supportingText = goalError(state, "account"),
                )
                if (showAccounts && state.goal == null) {
                    state.snapshot.accounts.forEach { option ->
                        LedgerChoiceRow(
                            option.name,
                            draft.accountId == option.id,
                            { actions.onGoalAccountChanged(option.id); showAccounts = false },
                            supportingText = option.currency.value,
                        )
                    }
                }
                LedgerTextField(draft.targetText, actions.onGoalTargetChanged, stringResource(R.string.goal_target_amount, account?.currency?.value.orEmpty()), required = true, keyboardType = KeyboardType.Decimal, errorText = goalError(state, "target"))
                SelectorField(
                    stringResource(R.string.goal_due_date),
                    draft.dueDate?.localizedDate() ?: stringResource(R.string.goal_no_due_date),
                    { showDueDatePicker = true },
                    supportingText = goalError(state, "dueDate"),
                )
                if (draft.dueDate != null) {
                    LedgerButton(stringResource(R.string.goal_clear_due_date), { actions.onGoalDueDateChanged("") }, variant = LedgerButtonVariant.TEXT, compact = true)
                }
                LedgerTextField(draft.suggestedText, actions.onGoalSuggestedChanged, stringResource(R.string.goal_suggested_monthly), keyboardType = KeyboardType.Decimal, errorText = goalError(state, "suggested"), supportingText = stringResource(R.string.goal_suggested_explanation))
            }
        }
    }
    if (showDueDatePicker) {
        LedgerDatePickerFlow(
            (draft.dueDate ?: state.movementDate).utcDateMillis(),
            { millis -> actions.onGoalDueDateChanged(millis.utcLocalDate().toString()); showDueDatePicker = false },
            { showDueDatePicker = false },
        )
    }
}

@Composable
private fun GoalDetail(state: ProjectGoalFeatureState, actions: ProjectGoalActions) {
    val goal = state.goal ?: return PlanningNotFound(actions)
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.GOAL_DETAIL),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        if (goal.accountUnderfunded || state.presentation == ProjectGoalPresentation.UNDERFUNDED) item { LedgerBanner(stringResource(R.string.goal_underfunded_warning), LedgerBannerVariant.WARNING) }
        if (goal.status == GoalStatus.COMPLETED || state.presentation == ProjectGoalPresentation.COMPLETED) item { LedgerBanner(stringResource(R.string.goal_completed), LedgerBannerVariant.INFO) }
        item {
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LedgerTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    LedgerText(goal.name, LedgerTextRole.TITLE)
                    AmountText(ProjectGoalPolicy.money(goal.balanceMinor, goal.currency, LocalLocale.current.platformLocale), AmountSize.HERO)
                    LedgerText(stringResource(R.string.goal_target_text, money(goal.targetAmountMinor, goal.currency)), LedgerTextRole.BODY)
                    GoalProgress(goal)
                    goal.dueDate?.let { LedgerText(stringResource(R.string.goal_due_text, it.localizedDate()), LedgerTextRole.SUPPORTING) }
                    goal.suggestedMonthlyAmountMinor?.let { LedgerText(stringResource(R.string.goal_suggested_text, money(it, goal.currency)), LedgerTextRole.SUPPORTING) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().testTag(LedgerTestTags.ACCOUNT_AVAILABILITY), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                MetricCard(stringResource(R.string.goal_actual), ProjectGoalPolicy.money(goal.actualAccountBalanceMinor, goal.currency, LocalLocale.current.platformLocale), Modifier.weight(1f))
                MetricCard(stringResource(R.string.goal_reserved), ProjectGoalPolicy.money(goal.totalAccountReservedMinor, goal.currency, LocalLocale.current.platformLocale), Modifier.weight(1f))
            }
            MetricCard(stringResource(R.string.goal_available), ProjectGoalPolicy.money(goal.accountAvailableMinor, goal.currency, LocalLocale.current.platformLocale), Modifier.fillMaxWidth(), MetricCardVariant.EMPHASIZED, explanation = stringResource(R.string.goal_available_formula))
        }
        if (goal.trend.isEmpty() || state.presentation == ProjectGoalPresentation.EMPTY_HISTORY) {
            item { LedgerEmptyState(stringResource(R.string.goal_no_history), stringResource(R.string.goal_no_history_body), stringResource(R.string.goal_allocate), { actions.onNavigate("GOL-004", goal.id, GoalMovementKind.ALLOCATE) }) }
        } else {
            item { GoalTrendChart(goal) }
        }
        if (goal.boundProjects.isNotEmpty()) {
            item { LedgerText(stringResource(R.string.goal_bound_projects), LedgerTextRole.SECTION) }
            items(goal.boundProjects, key = { it.toString() }) { projectId ->
                val project = state.snapshot.projects.singleOrNull { it.id == projectId }
                LedgerButton(project?.name ?: stringResource(R.string.project_unavailable), { actions.onNavigate("PRJ-003", projectId, null) }, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT)
            }
        }
        if (goal.movements.isNotEmpty()) {
            item {
                AccessibleDataTable(
                    AccessibleTableUiModel(
                        stringResource(R.string.goal_movement_history),
                        listOf(stringResource(R.string.planning_date), stringResource(R.string.goal_movement_type), stringResource(R.string.goal_movement_amount)),
                        goal.movements.map { listOf(it.occurredAt.localizedDateTime(), movementLabel(it.kind), money(it.amountMinor, goal.currency)) },
                    ),
                )
            }
        }
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                GoalMovementKind.entries.forEach { kind -> LedgerButton(movementLabel(kind), { actions.onNavigate("GOL-004", goal.id, kind) }, variant = LedgerButtonVariant.SECONDARY, compact = true) }
            }
            LedgerButton(stringResource(R.string.goal_edit), { actions.onNavigate("GOL-002", goal.id, null) }, Modifier.fillMaxWidth(), LedgerButtonVariant.TEXT)
            LedgerButton(stringResource(R.string.goal_complete_action), { actions.onNavigate("GOL-005", goal.id, null) }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun GoalTrendChart(goal: GoalView) {
    var expanded by remember(goal.id) { mutableStateOf(false) }
    val model = LedgerChartUiModel(
        stringResource(R.string.goal_trend),
        goal.accountName,
        stringResource(R.string.goal_trend_summary, money(goal.trend.first().balanceMinor, goal.currency), money(goal.trend.last().balanceMinor, goal.currency)),
        LedgerChartType.LINE,
        listOf(
            LedgerChartSeries(
                "goal_balance",
                stringResource(R.string.goal_reserved),
                goal.trend.map { it.balanceMinor.toDouble() },
                goal.trend.map { it.date.localizedDate() },
                goal.trend.map { money(it.balanceMinor, goal.currency) },
            ),
        ),
    )
    ChartCard(
        model,
        { LedgerLineChart(model, LedgerVicoLineRenderer, Modifier.fillMaxWidth()) },
        AccessibleTableUiModel(stringResource(R.string.goal_trend_table), listOf(stringResource(R.string.planning_date), stringResource(R.string.goal_reserved)), goal.trend.map { listOf(it.date.localizedDate(), money(it.balanceMinor, goal.currency)) }),
        tableExpanded = expanded,
        onToggleTable = { expanded = !expanded },
    )
}

@Composable
private fun GoalMovementEditor(state: ProjectGoalFeatureState, actions: ProjectGoalActions) {
    val goal = state.goal ?: return PlanningNotFound(actions)
    var showDatePicker by remember { mutableStateOf(false) }
    val saving = state.presentation == ProjectGoalPresentation.SAVING
    val valid = ProjectGoalPolicy.movementMinor(state) != null && "movementDate" !in state.goalErrors
    LedgerScaffold(
        modifier = Modifier.fillMaxSize().testTag(LedgerTestTags.GOAL_MOVEMENT),
        formContent = true,
        fixedAction = { PlanningStickySaveBar(actions.onSaveMovement, valid && !saving, saving) },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(LedgerTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
        ) {
            item { LedgerText(movementLabel(state.movementKind), LedgerTextRole.TITLE) }
            item { LedgerTextField(state.movementAmountText, actions.onMovementAmountChanged, stringResource(R.string.goal_movement_amount), required = true, keyboardType = KeyboardType.Decimal) }
            item {
                SelectorField(
                    stringResource(R.string.goal_movement_date),
                    state.movementDate.localizedDate(),
                    { showDatePicker = true },
                    supportingText = goalError(state, "movementDate"),
                )
            }
            if (state.presentation == ProjectGoalPresentation.INSUFFICIENT_ACTUAL_BALANCE_WARNING) {
                item { LedgerBanner(stringResource(R.string.goal_movement_warning), LedgerBannerVariant.WARNING) }
            }
            if (saving) item { LedgerLoadingState(Modifier.fillMaxWidth(), stringResource(R.string.planning_saving)) }
            item { LedgerBanner(stringResource(R.string.goal_movement_no_balance_change), LedgerBannerVariant.INFO) }
            item { LedgerText(stringResource(R.string.goal_account_formula, money(goal.actualAccountBalanceMinor, goal.currency), money(goal.totalAccountReservedMinor, goal.currency), money(goal.accountAvailableMinor, goal.currency)), LedgerTextRole.BODY) }
        }
    }
    if (showDatePicker) {
        LedgerDatePickerFlow(
            state.movementDate.utcDateMillis(),
            { millis -> actions.onMovementDateChanged(millis.utcLocalDate().toString()); showDatePicker = false },
            { showDatePicker = false },
        )
    }
}

@Composable
private fun ProjectTabRow(selectedIndex: Int, project: ProjectView, actions: ProjectGoalActions) {
    LedgerTabRow(
        selectedIndex,
        listOf(stringResource(R.string.project_overview), stringResource(R.string.project_transactions), stringResource(R.string.project_cashflow)),
        { index ->
            actions.onNavigate(listOf("PRJ-003", "PRJ-004", "PRJ-005")[index], project.id, null)
        },
        Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProjectTransactionRow(
    transaction: ProjectTransactionView,
    state: ProjectGoalFeatureState,
    project: ProjectView,
    actions: ProjectGoalActions,
) {
    val kind = transaction.kind.projectTransactionLabel()
    val date = transaction.localDate.localizedDate()
    val amount = ProjectGoalPolicy.money(
        transaction.amountBaseMinor,
        state.snapshot.baseCurrency,
        LocalLocale.current.platformLocale,
        transaction.kind.amountSemantic(),
    )
    val scope = stringResource(R.string.project_filter_scope, project.name)
    val badges = listOfNotNull(stringResource(R.string.project_restored_badge).takeIf { transaction.restored })
    JournalTransactionRow(
        JournalTransactionUiModel(
            stableKey = transaction.id.toString(),
            categoryOrType = project.name,
            summary = date,
            accountAndCard = scope,
            amount = amount,
            typeLabel = kind,
            icon = transaction.kind.projectTransactionIcon(),
            badges = badges,
            accessibleText = listOf(kind, project.name, date, amount.fullAccessibleText, badges.joinToString()).filter(String::isNotBlank).joinToString(". "),
        ),
        onClick = { actions.onOpenTransaction(transaction.id) },
        onLongClick = { actions.onOpenTransaction(transaction.id) },
    )
}

@Composable
private fun GoalCompletion(state: ProjectGoalFeatureState, actions: ProjectGoalActions) {
    val goal = state.goal ?: return PlanningNotFound(actions)
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.GOAL_COMPLETION),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        item { LedgerText(stringResource(R.string.goal_completion_title, goal.name), LedgerTextRole.TITLE) }
        item { CompletionCard(stringResource(R.string.goal_completion_release), stringResource(R.string.goal_completion_release_body), GoalCompletionStrategy.RELEASE, actions) }
        item { CompletionCard(stringResource(R.string.goal_completion_keep), stringResource(R.string.goal_completion_keep_body), GoalCompletionStrategy.KEEP, actions) }
        item { CompletionCard(stringResource(R.string.goal_completion_continue), stringResource(R.string.goal_completion_continue_body), GoalCompletionStrategy.CONTINUE, actions) }
    }
}

@Composable
private fun CompletionCard(title: String, body: String, strategy: GoalCompletionStrategy, actions: ProjectGoalActions) {
    LedgerCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LedgerTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
            LedgerText(title, LedgerTextRole.SECTION)
            LedgerText(body, LedgerTextRole.BODY)
            LedgerButton(stringResource(R.string.goal_choose_strategy), { actions.onCompleteGoal(strategy) }, Modifier.fillMaxWidth(), LedgerButtonVariant.SECONDARY)
        }
    }
}

@Composable
private fun ProjectProgress(project: ProjectView, currency: app.ledger.core.money.CurrencyCode) {
    val progress = if (project.budgetBaseMinor == 0L) null else project.usedBaseMinor.toFloat() / project.budgetBaseMinor.toFloat()
    LedgerProgressIndicator(
        progress,
        accessibleText = stringResource(
            R.string.project_progress_accessible,
            ProjectGoalPolicy.money(project.usedBaseMinor, currency, LocalLocale.current.platformLocale).formatted,
            ProjectGoalPolicy.money(project.budgetBaseMinor, currency, LocalLocale.current.platformLocale).formatted,
        ),
    )
}

@Composable
private fun GoalProgress(goal: GoalView) {
    val progress = if (goal.targetAmountMinor == 0L) null else goal.balanceMinor.toFloat() / goal.targetAmountMinor.toFloat()
    LedgerProgressIndicator(
        progress,
        accessibleText = stringResource(
            R.string.goal_progress_accessible,
            ProjectGoalPolicy.money(goal.balanceMinor, goal.currency, LocalLocale.current.platformLocale).formatted,
            ProjectGoalPolicy.money(goal.targetAmountMinor, goal.currency, LocalLocale.current.platformLocale).formatted,
        ),
    )
}

@Composable
private fun PlanningNotFound(actions: ProjectGoalActions) {
    LedgerErrorState(UiErrorCode("PLANNING_ITEM_NOT_FOUND"), stringResource(R.string.planning_not_found), actions.onRetry)
}

@Composable
private fun movementLabel(kind: GoalMovementKind): String = stringResource(
    when (kind) {
        GoalMovementKind.ALLOCATE -> R.string.goal_allocate
        GoalMovementKind.RELEASE -> R.string.goal_release
        GoalMovementKind.ADJUST -> R.string.goal_adjust
    },
)

@Composable
private fun PlanningStickySaveBar(onSave: () -> Unit, enabled: Boolean, saving: Boolean) {
    LedgerCard(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
            LedgerButton(
                stringResource(if (saving) R.string.planning_saving else R.string.planning_save),
                onSave,
                Modifier.fillMaxWidth().testTag(LedgerTestTags.SAVE),
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun LocalDate.localizedDate(): String =
    format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalLocale.current.platformLocale))

private fun LocalDate.utcDateMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.utcLocalDate(): LocalDate = java.time.Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun java.time.Instant.localizedDateTime(): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(LocalLocale.current.platformLocale)
        .withZone(LedgerTheme.timeZone)
        .format(this)

@Composable
private fun GoalStatus.goalStatusLabel(): String = stringResource(
    when (this) {
        GoalStatus.ACTIVE -> R.string.goal_status_active
        GoalStatus.COMPLETED -> R.string.goal_status_completed
        GoalStatus.ARCHIVED -> R.string.goal_status_archived
    },
)

private fun GoalStatus.goalStatusVariant(): LedgerStatusVariant = when (this) {
    GoalStatus.ACTIVE -> LedgerStatusVariant.POSITIVE
    GoalStatus.COMPLETED -> LedgerStatusVariant.INFO
    GoalStatus.ARCHIVED -> LedgerStatusVariant.ARCHIVED
}

@Composable
private fun TransactionKind.projectTransactionLabel(): String = stringResource(
    when (this) {
        TransactionKind.EXPENSE -> R.string.project_kind_expense
        TransactionKind.INCOME -> R.string.project_kind_income
        TransactionKind.TRANSFER -> R.string.project_kind_transfer
        TransactionKind.REFUND -> R.string.project_kind_refund
        TransactionKind.CREDIT_PAYMENT -> R.string.project_kind_credit_payment
        TransactionKind.LOAN_DISBURSEMENT -> R.string.project_kind_loan_disbursement
        TransactionKind.LOAN_PAYMENT -> R.string.project_kind_loan_payment
        TransactionKind.BALANCE_ADJUSTMENT -> R.string.project_kind_balance_adjustment
        TransactionKind.FX_EXCHANGE -> R.string.project_kind_fx_exchange
        TransactionKind.SETTLEMENT_PAYMENT -> R.string.project_kind_settlement_payment
        TransactionKind.OPENING_BALANCE -> R.string.project_kind_opening_balance
    },
)

private fun TransactionKind.amountSemantic(): AmountSemantic = when (this) {
    TransactionKind.EXPENSE, TransactionKind.CREDIT_PAYMENT, TransactionKind.LOAN_PAYMENT, TransactionKind.SETTLEMENT_PAYMENT -> AmountSemantic.OUTFLOW
    TransactionKind.INCOME, TransactionKind.LOAN_DISBURSEMENT -> AmountSemantic.INFLOW
    TransactionKind.REFUND -> AmountSemantic.REFUND
    TransactionKind.TRANSFER, TransactionKind.FX_EXCHANGE -> AmountSemantic.TRANSFER
    TransactionKind.BALANCE_ADJUSTMENT, TransactionKind.OPENING_BALANCE -> AmountSemantic.NEUTRAL
}

private fun TransactionKind.projectTransactionIcon(): LedgerIcon = when (this) {
    TransactionKind.TRANSFER, TransactionKind.FX_EXCHANGE -> LedgerIcon.TRANSFER
    TransactionKind.REFUND -> LedgerIcon.REFUND
    else -> LedgerIcon.JOURNAL
}

@Composable
private fun money(state: ProjectGoalFeatureState, minor: Long): String = money(minor, state.snapshot.baseCurrency)

@Composable
private fun money(minor: Long, currency: app.ledger.core.money.CurrencyCode): String = ProjectGoalPolicy.money(minor, currency, LocalLocale.current.platformLocale).formatted

@Composable
private fun error(state: ProjectGoalFeatureState, field: String): String? = stringResource(R.string.planning_invalid_field).takeIf { field in state.projectErrors }

@Composable
private fun goalError(state: ProjectGoalFeatureState, field: String): String? = stringResource(R.string.planning_invalid_field).takeIf { field in state.goalErrors }

private fun Map<String, String>.stableId(name: String): StableId? = get(name)?.let { StableId.parse(it).getOrNull() }
