package app.ledger.feature.planning

import app.ledger.core.common.StableId
import app.ledger.finance.application.GoalCompletionStrategy
import app.ledger.finance.domain.GoalMovementKind
import app.ledger.finance.domain.TransactionKind

public data class ProjectGoalActions(
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
    val onProjectTransactionKindChanged: (TransactionKind?) -> Unit = {},
)
