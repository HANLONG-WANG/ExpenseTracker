package app.ledger.finance.application

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.GoalMovementKind
import app.ledger.finance.domain.GoalStatus
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.ProjectStatus
import app.ledger.finance.domain.TransactionKind
import java.time.Instant
import java.time.LocalDate

data class PlanningMutationIds(
    val bookId: StableId,
    val expectedLocalRevision: LocalRevision,
    val commitId: StableId,
    val entityRevisionId: StableId,
    val deviceInstanceId: StableId,
) {
    init {
        require(listOf(bookId, commitId, entityRevisionId, deviceInstanceId).toSet().size == PLANNING_MUTATION_ID_COUNT)
    }
}

data class GoalMovementMutationIds(
    val bookId: StableId,
    val commandId: CommandId,
    val commitId: StableId,
    val movementId: StableId,
    val effectId: StableId,
    val deviceInstanceId: StableId,
) {
    init {
        require(
            listOf(bookId, commandId.stableId, commitId, movementId, effectId, deviceInstanceId).toSet().size ==
                GOAL_MOVEMENT_MUTATION_ID_COUNT,
        )
    }
}

data class PlanningAccountView(
    val id: StableId,
    val name: String,
    val currency: CurrencyCode,
    val actualBalanceMinor: Long,
    val reservedMinor: Long,
    val availableMinor: Long,
    val underfunded: Boolean,
)

data class ProjectTransactionView(
    val id: StableId,
    val revisionId: StableId,
    val occurredAt: Instant,
    val localDate: LocalDate,
    val kind: TransactionKind,
    val amountBaseMinor: Long,
    val restored: Boolean,
)

data class ProjectTransactionCursor(
    val occurredAtEpochMilli: Long,
    val transactionId: StableId,
)

data class ProjectTransactionPageRequest(
    val bookId: StableId,
    val projectId: StableId,
    val limit: Int = DEFAULT_PROJECT_TRANSACTION_PAGE_SIZE,
    val cursor: ProjectTransactionCursor? = null,
    val kind: app.ledger.finance.domain.TransactionKind? = null,
) {
    init {
        require(limit in 1..MAX_PROJECT_TRANSACTION_PAGE_SIZE)
    }
}

data class ProjectTransactionPage(
    val items: List<ProjectTransactionView>,
    val nextCursor: ProjectTransactionCursor?,
)

data class ProjectCashflowPoint(
    val date: LocalDate,
    val expenseBaseMinor: Long,
    val incomeBaseMinor: Long,
    val netBaseMinor: Long,
)

data class ProjectSettlementView(
    val id: StableId,
    val name: String,
    val requiresAdditionalSettlement: Boolean,
)

data class ProjectView(
    val id: StableId,
    val name: String,
    val description: String?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val budgetBaseMinor: Long,
    val includedInMonthlyBudget: Boolean,
    val goalId: StableId?,
    val goalName: String?,
    val status: ProjectStatus,
    val rowVersion: Long,
    val usedBaseMinor: Long,
    val restoredBaseMinor: Long,
    val remainingBaseMinor: Long,
    val cashInflowBaseMinor: Long,
    val cashOutflowBaseMinor: Long,
    val transactions: List<ProjectTransactionView>,
    val cashflow: List<ProjectCashflowPoint>,
    val settlements: List<ProjectSettlementView>,
) {
    val overBudget: Boolean get() = remainingBaseMinor < 0L
}

data class GoalMovementView(
    val id: StableId,
    val kind: GoalMovementKind,
    val amountMinor: Long,
    val occurredAt: Instant,
    val reversalOfId: StableId?,
)

data class GoalTrendPoint(val date: LocalDate, val balanceMinor: Long)

data class GoalView(
    val id: StableId,
    val accountId: StableId,
    val accountName: String,
    val name: String,
    val targetAmountMinor: Long,
    val currency: CurrencyCode,
    val dueDate: LocalDate?,
    val suggestedMonthlyAmountMinor: Long?,
    val status: GoalStatus,
    val rowVersion: Long,
    val balanceMinor: Long,
    val actualAccountBalanceMinor: Long,
    val totalAccountReservedMinor: Long,
    val accountAvailableMinor: Long,
    val accountUnderfunded: Boolean,
    val boundProjects: List<StableId>,
    val movements: List<GoalMovementView>,
    val trend: List<GoalTrendPoint>,
)

enum class PlanningProjectionReadiness { CURRENT, FAILED }

data class ProjectGoalSnapshot(
    val bookId: StableId,
    val baseCurrency: CurrencyCode,
    val localRevision: LocalRevision,
    val readiness: PlanningProjectionReadiness,
    val accounts: List<PlanningAccountView>,
    val projects: List<ProjectView>,
    val goals: List<GoalView>,
) {
    init {
        require(readiness == PlanningProjectionReadiness.CURRENT || projects.isEmpty())
        require(readiness == PlanningProjectionReadiness.CURRENT || goals.isEmpty())
    }
}

data class SaveProjectRequest(
    val ids: PlanningMutationIds,
    val projectId: StableId,
    val expectedRowVersion: Long?,
    val name: String,
    val description: String?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val budgetBaseMinor: Long,
    val includedInMonthlyBudget: Boolean,
    val goalId: StableId?,
    val status: ProjectStatus,
    val changedAt: Instant,
) {
    init {
        require(name.isNotBlank())
        require(budgetBaseMinor >= 0L)
        require(endDate == null || endDate >= startDate)
    }
}

data class SaveGoalRequest(
    val ids: PlanningMutationIds,
    val goalId: StableId,
    val expectedRowVersion: Long?,
    val accountId: StableId,
    val name: String,
    val targetAmountMinor: Long,
    val dueDate: LocalDate?,
    val suggestedMonthlyAmountMinor: Long?,
    val status: GoalStatus,
    val changedAt: Instant,
) {
    init {
        require(name.isNotBlank())
        require(targetAmountMinor > 0L)
        require(suggestedMonthlyAmountMinor == null || suggestedMonthlyAmountMinor > 0L)
    }
}

data class ChangeProjectStatusRequest(
    val ids: PlanningMutationIds,
    val projectId: StableId,
    val expectedRowVersion: Long,
    val status: ProjectStatus,
    val changedAt: Instant,
)

data class RecordGoalMovementRequest(
    val ids: GoalMovementMutationIds,
    val goalId: StableId,
    val expectedGoalRowVersion: Long,
    val kind: GoalMovementKind,
    val amountMinor: Long,
    val occurredAt: Instant,
    val changedAt: Instant,
) {
    init {
        require(amountMinor > 0L)
    }
}

enum class GoalCompletionStrategy { RELEASE, KEEP, CONTINUE }

data class CompleteGoalRequest(
    val ids: PlanningMutationIds,
    val movementIds: GoalMovementMutationIds?,
    val goalId: StableId,
    val expectedRowVersion: Long,
    val strategy: GoalCompletionStrategy,
    val changedAt: Instant,
) {
    init {
        require((strategy == GoalCompletionStrategy.RELEASE) == (movementIds != null))
    }
}

/** Typed P18 boundary. Financial goal movements terminate at FinancialMutationCoordinator. */
interface ProjectGoalApplicationPort {
    suspend fun snapshot(bookId: StableId): DomainResult<ProjectGoalSnapshot>

    suspend fun projectTransactionPage(request: ProjectTransactionPageRequest): DomainResult<ProjectTransactionPage>

    suspend fun saveProject(request: SaveProjectRequest): DomainResult<Unit>

    suspend fun changeProjectStatus(request: ChangeProjectStatusRequest): DomainResult<Unit>

    suspend fun saveGoal(request: SaveGoalRequest): DomainResult<Unit>

    suspend fun recordGoalMovement(request: RecordGoalMovementRequest): DomainResult<CommandReceipt>

    suspend fun completeGoal(request: CompleteGoalRequest): DomainResult<Unit>
}

private const val PLANNING_MUTATION_ID_COUNT = 4
private const val GOAL_MOVEMENT_MUTATION_ID_COUNT = 6
private const val DEFAULT_PROJECT_TRANSACTION_PAGE_SIZE = 40
private const val MAX_PROJECT_TRANSACTION_PAGE_SIZE = 200
