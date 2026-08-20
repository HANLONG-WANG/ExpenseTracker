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
        { action ->
            when (action) {
                ProjectGoalScreenAction.Retry -> viewModel.loadProjectGoal(screenId, projectId, goalId, movementKind)
                is ProjectGoalScreenAction.Navigate -> {
                    viewModel.navigateProjectGoal(action.screenId, action.id, action.movementKind)
                    onNavigationChanged()
                }
                is ProjectGoalScreenAction.ProjectStatusTabSelected -> viewModel.selectProjectStatusTab(action.active)
                is ProjectGoalScreenAction.ProjectNameChanged -> viewModel.updateProjectName(action.value)
                is ProjectGoalScreenAction.ProjectDescriptionChanged -> viewModel.updateProjectDescription(action.value)
                is ProjectGoalScreenAction.ProjectStartDateChanged -> viewModel.updateProjectStartDate(action.value)
                is ProjectGoalScreenAction.ProjectEndDateChanged -> viewModel.updateProjectEndDate(action.value)
                is ProjectGoalScreenAction.ProjectBudgetChanged -> viewModel.updateProjectBudget(action.value)
                ProjectGoalScreenAction.ProjectMonthlyBudgetChanged -> viewModel.toggleProjectMonthlyBudget()
                ProjectGoalScreenAction.ProjectGoalChanged -> viewModel.selectNextProjectGoal()
                ProjectGoalScreenAction.SaveProject -> viewModel.saveProject()
                ProjectGoalScreenAction.ChangeProjectStatus -> viewModel.changeProjectStatus()
                is ProjectGoalScreenAction.GoalNameChanged -> viewModel.updateGoalName(action.value)
                is ProjectGoalScreenAction.GoalTargetChanged -> viewModel.updateGoalTarget(action.value)
                is ProjectGoalScreenAction.GoalSuggestedChanged -> viewModel.updateGoalSuggested(action.value)
                is ProjectGoalScreenAction.GoalDueDateChanged -> viewModel.updateGoalDueDate(action.value)
                ProjectGoalScreenAction.GoalAccountChanged -> viewModel.selectNextGoalAccount()
                ProjectGoalScreenAction.SaveGoal -> viewModel.saveGoal()
                is ProjectGoalScreenAction.MovementAmountChanged -> viewModel.updateGoalMovementAmount(action.value)
                is ProjectGoalScreenAction.MovementDateChanged -> viewModel.updateGoalMovementDate(action.value)
                ProjectGoalScreenAction.SaveMovement -> viewModel.saveGoalMovement()
                is ProjectGoalScreenAction.CompleteGoal -> viewModel.completeGoal(action.strategy)
            }
        },
        projectPages = viewModel.projectTransactionPages,
    )
}

private fun Map<String, String>.stableId(name: String): StableId? = get(name)?.let { StableId.parse(it).getOrNull() }
