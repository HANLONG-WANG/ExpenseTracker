package app.ledger.feature.planning

import app.ledger.core.common.StableId
import app.ledger.finance.application.GoalCompletionStrategy
import app.ledger.finance.domain.GoalMovementKind

public sealed interface ProjectGoalScreenAction {
    public data object Retry : ProjectGoalScreenAction
    public data class Navigate(val screenId: String, val id: StableId?, val movementKind: GoalMovementKind?) : ProjectGoalScreenAction
    public data class ProjectStatusTabSelected(val active: Boolean) : ProjectGoalScreenAction
    public data class ProjectNameChanged(val value: String) : ProjectGoalScreenAction
    public data class ProjectDescriptionChanged(val value: String) : ProjectGoalScreenAction
    public data class ProjectStartDateChanged(val value: String) : ProjectGoalScreenAction
    public data class ProjectEndDateChanged(val value: String) : ProjectGoalScreenAction
    public data class ProjectBudgetChanged(val value: String) : ProjectGoalScreenAction
    public data object ProjectMonthlyBudgetChanged : ProjectGoalScreenAction
    public data object ProjectGoalChanged : ProjectGoalScreenAction
    public data object SaveProject : ProjectGoalScreenAction
    public data object ChangeProjectStatus : ProjectGoalScreenAction
    public data class GoalNameChanged(val value: String) : ProjectGoalScreenAction
    public data class GoalTargetChanged(val value: String) : ProjectGoalScreenAction
    public data class GoalSuggestedChanged(val value: String) : ProjectGoalScreenAction
    public data class GoalDueDateChanged(val value: String) : ProjectGoalScreenAction
    public data object GoalAccountChanged : ProjectGoalScreenAction
    public data object SaveGoal : ProjectGoalScreenAction
    public data class MovementAmountChanged(val value: String) : ProjectGoalScreenAction
    public data class MovementDateChanged(val value: String) : ProjectGoalScreenAction
    public data object SaveMovement : ProjectGoalScreenAction
    public data class CompleteGoal(val strategy: GoalCompletionStrategy) : ProjectGoalScreenAction
}

internal class ProjectGoalActions(
    val onRetry: () -> Unit,
    val onNavigate: (String, StableId?, GoalMovementKind?) -> Unit,
    val onProjectStatusTabSelected: (Boolean) -> Unit,
    val onProjectNameChanged: (String) -> Unit,
    val onProjectDescriptionChanged: (String) -> Unit,
    val onProjectStartDateChanged: (String) -> Unit,
    val onProjectEndDateChanged: (String) -> Unit,
    val onProjectBudgetChanged: (String) -> Unit,
    val onProjectMonthlyBudgetChanged: () -> Unit,
    val onProjectGoalChanged: (StableId?) -> Unit,
    val onSaveProject: () -> Unit,
    val onOpenTransaction: (StableId) -> Unit,
    val onChangeProjectStatus: () -> Unit,
    val onGoalNameChanged: (String) -> Unit,
    val onGoalTargetChanged: (String) -> Unit,
    val onGoalSuggestedChanged: (String) -> Unit,
    val onGoalDueDateChanged: (String) -> Unit,
    val onGoalAccountChanged: (StableId) -> Unit,
    val onSaveGoal: () -> Unit,
    val onMovementAmountChanged: (String) -> Unit,
    val onMovementDateChanged: (String) -> Unit,
    val onSaveMovement: () -> Unit,
    val onCompleteGoal: (GoalCompletionStrategy) -> Unit,
)

internal fun projectGoalActions(onAction: (ProjectGoalScreenAction) -> Unit): ProjectGoalActions = ProjectGoalActions(
    onRetry = { onAction(ProjectGoalScreenAction.Retry) },
    onNavigate = { screenId, id, kind -> onAction(ProjectGoalScreenAction.Navigate(screenId, id, kind)) },
    onProjectStatusTabSelected = { onAction(ProjectGoalScreenAction.ProjectStatusTabSelected(it)) },
    onProjectNameChanged = { onAction(ProjectGoalScreenAction.ProjectNameChanged(it)) },
    onProjectDescriptionChanged = { onAction(ProjectGoalScreenAction.ProjectDescriptionChanged(it)) },
    onProjectStartDateChanged = { onAction(ProjectGoalScreenAction.ProjectStartDateChanged(it)) },
    onProjectEndDateChanged = { onAction(ProjectGoalScreenAction.ProjectEndDateChanged(it)) },
    onProjectBudgetChanged = { onAction(ProjectGoalScreenAction.ProjectBudgetChanged(it)) },
    onProjectMonthlyBudgetChanged = { onAction(ProjectGoalScreenAction.ProjectMonthlyBudgetChanged) },
    onProjectGoalChanged = { onAction(ProjectGoalScreenAction.ProjectGoalChanged) },
    onSaveProject = { onAction(ProjectGoalScreenAction.SaveProject) },
    onChangeProjectStatus = { onAction(ProjectGoalScreenAction.ChangeProjectStatus) },
    onGoalNameChanged = { onAction(ProjectGoalScreenAction.GoalNameChanged(it)) },
    onGoalTargetChanged = { onAction(ProjectGoalScreenAction.GoalTargetChanged(it)) },
    onGoalSuggestedChanged = { onAction(ProjectGoalScreenAction.GoalSuggestedChanged(it)) },
    onGoalDueDateChanged = { onAction(ProjectGoalScreenAction.GoalDueDateChanged(it)) },
    onGoalAccountChanged = { onAction(ProjectGoalScreenAction.GoalAccountChanged) },
    onSaveGoal = { onAction(ProjectGoalScreenAction.SaveGoal) },
    onMovementAmountChanged = { onAction(ProjectGoalScreenAction.MovementAmountChanged(it)) },
    onMovementDateChanged = { onAction(ProjectGoalScreenAction.MovementDateChanged(it)) },
    onSaveMovement = { onAction(ProjectGoalScreenAction.SaveMovement) },
    onCompleteGoal = { onAction(ProjectGoalScreenAction.CompleteGoal(it)) },
)
