package app.ledger.feature.planning

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.GoalView
import app.ledger.finance.application.PlanningProjectionReadiness
import app.ledger.finance.application.ProjectGoalSnapshot
import app.ledger.finance.application.ProjectView
import app.ledger.finance.domain.GoalStatus
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.ProjectStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class ProjectGoalPolicyTest {
    @Test
    fun `editor routes distinguish create from edit`() {
        val snapshot = snapshot(project(), goal())
        assertEquals(ProjectGoalPresentation.CREATE, ProjectGoalPolicy.presentationFor("PRJ-002", snapshot))
        assertEquals(ProjectGoalPresentation.EDIT, ProjectGoalPolicy.presentationFor("PRJ-002", snapshot, PROJECT_ID))
        assertEquals(ProjectGoalPresentation.CREATE, ProjectGoalPolicy.presentationFor("GOL-002", snapshot))
        assertEquals(ProjectGoalPresentation.EDIT, ProjectGoalPolicy.presentationFor("GOL-002", snapshot, goalId = GOAL_ID))
    }

    @Test
    fun `project route derives archived over-budget and empty-history states`() {
        assertEquals(
            ProjectGoalPresentation.ARCHIVED,
            ProjectGoalPolicy.presentationFor("PRJ-003", snapshot(project(status = ProjectStatus.ARCHIVED), goal()), PROJECT_ID),
        )
        assertEquals(
            ProjectGoalPresentation.OVER_BUDGET,
            ProjectGoalPolicy.presentationFor("PRJ-003", snapshot(project(remaining = -1L), goal()), PROJECT_ID),
        )
        assertEquals(
            ProjectGoalPresentation.NO_TRANSACTIONS,
            ProjectGoalPolicy.presentationFor("PRJ-003", snapshot(project(), goal()), PROJECT_ID),
        )
    }

    @Test
    fun `goal route derives completed underfunded and empty-history states`() {
        assertEquals(
            ProjectGoalPresentation.COMPLETED,
            ProjectGoalPolicy.presentationFor("GOL-003", snapshot(project(), goal(status = GoalStatus.COMPLETED)), goalId = GOAL_ID),
        )
        assertEquals(
            ProjectGoalPresentation.UNDERFUNDED,
            ProjectGoalPolicy.presentationFor("GOL-003", snapshot(project(), goal(underfunded = true)), goalId = GOAL_ID),
        )
        assertEquals(
            ProjectGoalPresentation.EMPTY_HISTORY,
            ProjectGoalPolicy.presentationFor("GOL-003", snapshot(project(), goal()), goalId = GOAL_ID),
        )
    }

    private fun snapshot(project: ProjectView, goal: GoalView) = ProjectGoalSnapshot(
        id(1),
        CURRENCY,
        (LocalRevision.of(18) as DomainResult.Success).value,
        PlanningProjectionReadiness.CURRENT,
        emptyList(),
        listOf(project),
        listOf(goal),
    )

    private fun project(status: ProjectStatus = ProjectStatus.ACTIVE, remaining: Long = 1L) = ProjectView(
        PROJECT_ID,
        "Project",
        null,
        LocalDate.of(2026, 8, 1),
        null,
        100L,
        true,
        GOAL_ID,
        "Goal",
        status,
        1L,
        0L,
        0L,
        remaining,
        0L,
        0L,
        emptyList(),
        emptyList(),
        emptyList(),
    )

    private fun goal(status: GoalStatus = GoalStatus.ACTIVE, underfunded: Boolean = false) = GoalView(
        GOAL_ID,
        id(3),
        "Account",
        "Goal",
        100L,
        CURRENCY,
        null,
        null,
        status,
        1L,
        0L,
        0L,
        0L,
        0L,
        underfunded,
        listOf(PROJECT_ID),
        emptyList(),
        emptyList(),
    )

    private companion object {
        val PROJECT_ID: StableId = id(10)
        val GOAL_ID: StableId = id(20)
        val CURRENCY: CurrencyCode = (CurrencyCode.parse("JPY") as DomainResult.Success).value

        fun id(value: Long): StableId = StableId.fromUuid(UUID(0x1819L, value))
    }
}
