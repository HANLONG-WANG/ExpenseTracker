@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.feature.planning.ProjectGoalActions
import app.ledger.feature.planning.ProjectGoalDestination
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
    val state by viewModel.projectGoal.collectAsStateWithLifecycle()
    LaunchedEffect(screenId, projectId, goalId, movementKind) {
        viewModel.loadProjectGoal(screenId, projectId, goalId, movementKind)
    }
    val actions = ProjectGoalActions(
            onRetry = { viewModel.loadProjectGoal(screenId, projectId, goalId, movementKind) },
            onNavigate = { target, stable, kind ->
                if (target in setOf("PRJ-003", "PRJ-004", "PRJ-005") && stable != null && screenId in setOf("PRJ-003", "PRJ-004", "PRJ-005")) {
                    viewModel.switchProjectView(target, stable)
                } else {
                    viewModel.navigateProjectGoal(target, stable, kind)
                }
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
            onProjectTransactionKindChanged = viewModel::setProjectTransactionKind,
    )
    Box(Modifier.fillMaxSize()) {
        if (screenId == "GOL-005") {
            ProjectGoalDestination("GOL-003", state, encodedArguments, actions, projectPages = viewModel.projectTransactionPages)
        }
        GovernedDestinationModal(
            screenId,
            projectGoalDestinationTitleOrNull(screenId).orEmpty(),
            onDismiss = {
                viewModel.requestRootBack()
                onNavigationChanged()
            },
        ) {
            ProjectGoalDestination(screenId, state, encodedArguments, actions, projectPages = viewModel.projectTransactionPages)
        }
    }
}

private fun Map<String, String>.stableId(name: String): StableId? = get(name)?.let { StableId.parse(it).getOrNull() }
