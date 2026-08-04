@file:Suppress("MagicNumber", "MaxLineLength")

package app.ledger.feature.planning

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.GoalMovementView
import app.ledger.finance.application.GoalTrendPoint
import app.ledger.finance.application.GoalView
import app.ledger.finance.application.PlanningAccountView
import app.ledger.finance.application.PlanningProjectionReadiness
import app.ledger.finance.application.ProjectCashflowPoint
import app.ledger.finance.application.ProjectGoalSnapshot
import app.ledger.finance.application.ProjectSettlementView
import app.ledger.finance.application.ProjectTransactionView
import app.ledger.finance.application.ProjectView
import app.ledger.finance.domain.GoalMovementKind
import app.ledger.finance.domain.GoalStatus
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.ProjectStatus
import app.ledger.finance.domain.TransactionKind
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal object ProjectGoalDeviceFixtures {
    val projectId: StableId = id(10)
    val archivedProjectId: StableId = id(11)
    val goalId: StableId = id(20)
    val accountId: StableId = id(30)
    val today: LocalDate = LocalDate.of(2026, 8, 4)
    private val currency = (CurrencyCode.parse("JPY") as DomainResult.Success).value
    private val revision = (LocalRevision.of(18) as DomainResult.Success).value

    val actions = ProjectGoalActions(
        onRetry = {},
        onNavigate = { _, _, _ -> },
        onProjectStatusTabSelected = {},
        onProjectNameChanged = {},
        onProjectDescriptionChanged = {},
        onProjectStartDateChanged = {},
        onProjectEndDateChanged = {},
        onProjectBudgetChanged = {},
        onProjectMonthlyBudgetChanged = {},
        onProjectGoalChanged = {},
        onSaveProject = {},
        onChangeProjectStatus = {},
        onGoalNameChanged = {},
        onGoalTargetChanged = {},
        onGoalSuggestedChanged = {},
        onGoalDueDateChanged = {},
        onGoalAccountChanged = {},
        onSaveGoal = {},
        onMovementAmountChanged = {},
        onMovementDateChanged = {},
        onSaveMovement = {},
        onCompleteGoal = {},
    )

    fun snapshot(
        projects: List<ProjectView> = listOf(project(), project(archivedProjectId, status = ProjectStatus.ARCHIVED, name = "Archived trip")),
        goals: List<GoalView> = listOf(goal()),
    ) = ProjectGoalSnapshot(
        id(1),
        currency,
        revision,
        PlanningProjectionReadiness.CURRENT,
        listOf(PlanningAccountView(accountId, "Wallet", currency, 800L, 1_000L, -200L, true)),
        projects,
        goals,
    )

    fun state(
        presentation: ProjectGoalPresentation,
        project: StableId? = projectId,
        goal: StableId? = goalId,
        snapshot: ProjectGoalSnapshot = snapshot(),
    ): ProjectGoalFeatureState = ProjectGoalPolicy.create(snapshot, today, project, goal, presentation)

    fun project(
        id: StableId = projectId,
        status: ProjectStatus = ProjectStatus.ACTIVE,
        name: String = "Japan trip",
        transactions: List<ProjectTransactionView> = listOf(ProjectTransactionView(ProjectGoalDeviceFixtures.id(101), ProjectGoalDeviceFixtures.id(102), Instant.parse("2026-08-03T03:00:00Z"), LocalDate.of(2026, 8, 3), TransactionKind.EXPENSE, 1_200L, false)),
        cashflow: List<ProjectCashflowPoint> = listOf(
            ProjectCashflowPoint(LocalDate.of(2026, 8, 2), 600L, 0L, -600L),
            ProjectCashflowPoint(LocalDate.of(2026, 8, 3), 600L, 200L, -400L),
        ),
    ) = ProjectView(
        id,
        name,
        "Food and rail",
        LocalDate.of(2026, 8, 1),
        LocalDate.of(2026, 8, 31),
        1_000L,
        true,
        goalId,
        "Emergency",
        status,
        3L,
        1_200L,
        100L,
        -200L,
        200L,
        1_200L,
        transactions,
        cashflow,
        listOf(ProjectSettlementView(ProjectGoalDeviceFixtures.id(103), "Trip group", true)),
    )

    fun goal(
        status: GoalStatus = GoalStatus.ACTIVE,
        movements: List<GoalMovementView> = listOf(GoalMovementView(id(201), GoalMovementKind.ALLOCATE, 1_000L, Instant.parse("2026-08-02T03:00:00Z"), null)),
        trend: List<GoalTrendPoint> = listOf(GoalTrendPoint(LocalDate.of(2026, 8, 2), 1_000L), GoalTrendPoint(LocalDate.of(2026, 8, 3), 800L)),
    ) = GoalView(
        goalId,
        accountId,
        "Wallet",
        "Emergency",
        2_000L,
        currency,
        LocalDate.of(2027, 8, 1),
        200L,
        status,
        4L,
        1_000L,
        800L,
        1_000L,
        -200L,
        true,
        listOf(projectId, archivedProjectId),
        movements,
        trend,
    )

    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x1819L, value))
}
