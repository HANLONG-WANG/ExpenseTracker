@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.feature.planning.ProjectGoalDestination
import app.ledger.feature.planning.ProjectGoalScreenAction
import app.ledger.finance.domain.GoalMovementKind
import app.ledger.feature.planning.R as PlanningR

@Composable
internal fun projectGoalDestinationTitleOrNull(screenId: String): String? = when (screenId) {
    "PRJ-001" -> stringResource(PlanningR.string.project_screen_list)
    "PRJ-002" -> stringResource(PlanningR.string.project_screen_editor)
    "PRJ-003" -> stringResource(PlanningR.string.project_screen_detail)
    "PRJ-004" -> stringResource(PlanningR.string.project_screen_transactions)
    "PRJ-005" -> stringResource(PlanningR.string.project_screen_cashflow)
    "PRJ-006" -> stringResource(PlanningR.string.project_screen_status)
    "GOL-001" -> stringResource(PlanningR.string.goal_screen_list)
    "GOL-002" -> stringResource(PlanningR.string.goal_screen_editor)
    "GOL-003" -> stringResource(PlanningR.string.goal_screen_detail)
    "GOL-004" -> stringResource(PlanningR.string.goal_screen_movement)
    "GOL-005" -> stringResource(PlanningR.string.goal_screen_complete)
    else -> null
}

@Composable
internal fun ProjectGoalRootDestination(
    screenId: String,
    encodedArguments: Map<String, String>,
    viewModel: AppRootViewModel,
    onNavigationChanged: () -> Unit,
) {
    val projectId = encodedArguments.stableId("projectId")
    val goalId = encodedArguments.stableId("goalId")
    val movementKind = encodedArguments["kind"]?.let { value -> GoalMovementKind.entries.singleOrNull { it.name == value } }
    val uiState by viewModel.projectGoalUiState.collectAsStateWithLifecycle()
    LaunchedEffect(screenId, projectId, goalId, movementKind) {
        viewModel.loadProjectGoal(screenId, projectId, goalId, movementKind)
    }
    ProjectGoalDestination(
        screenId,
        uiState.loadState,
        encodedArguments,
        ProjectGoalActions(
            onRetry = { viewModel.loadProjectGoal(screenId, projectId, goalId, movementKind) },
            onNavigate = { target, stable, kind ->
                viewModel.navigateProjectGoal(target, stable, kind)
                onNavigationChanged()
            },
            onProjectStatusTabSelected = viewModel::selectProjectStatusTab,
            onProjectNameChanged = viewModel::updateProjectName,
            onProjectDescriptionChanged = viewModel::updateProjectDescription,
            onProjectStartDateChanged = viewModel::updateProjectStartDate,
            onProjectEndDateChanged = viewModel::updateProjectEndDate,
            onProjectBudgetChanged = viewModel::updateProjectBudget,
            onProjectMonthlyBudgetChanged = viewModel::toggleProjectMonthlyBudget,
            onProjectGoalChanged = viewModel::selectProjectGoal,
            onSaveProject = viewModel::saveProject,
            onOpenTransaction = { transactionId ->
                viewModel.openProjectTransaction(transactionId)
                onNavigationChanged()
            },
            onChangeProjectStatus = viewModel::changeProjectStatus,
            onGoalNameChanged = viewModel::updateGoalName,
            onGoalTargetChanged = viewModel::updateGoalTarget,
            onGoalSuggestedChanged = viewModel::updateGoalSuggested,
            onGoalDueDateChanged = viewModel::updateGoalDueDate,
            onGoalAccountChanged = viewModel::selectGoalAccount,
            onSaveGoal = viewModel::saveGoal,
            onMovementAmountChanged = viewModel::updateGoalMovementAmount,
            onMovementDateChanged = viewModel::updateGoalMovementDate,
            onSaveMovement = viewModel::saveGoalMovement,
            onCompleteGoal = viewModel::completeGoal,
        ),
        projectPages = viewModel.projectTransactionPages,
    )
}

private fun Map<String, String>.stableId(name: String): StableId? = get(name)?.let { StableId.parse(it).getOrNull() }
