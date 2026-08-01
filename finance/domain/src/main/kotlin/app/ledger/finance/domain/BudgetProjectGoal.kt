package app.ledger.finance.domain

import app.ledger.core.common.CheckedArithmetic
import app.ledger.core.common.DomainResult
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.Money
import app.ledger.core.time.EffectiveTime
import java.time.LocalDate
import java.time.YearMonth

data class CategoryBudgetLimit(
    val categoryId: CategoryId,
    val rootCategoryId: CategoryId,
    val parentCategoryId: CategoryId?,
    val depth: Int,
    val amountBaseMinor: Long,
) {
    init {
        require(depth in 1..2)
        require(amountBaseMinor >= 0L)
        require((depth == 1 && parentCategoryId == null) || (depth == 2 && parentCategoryId != null))
    }
}

data class BudgetTemplate(
    val id: BudgetTemplateId,
    val name: String,
    val currentRevisionId: BudgetTemplateRevisionId,
    val status: EntityStatus,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current
}

data class BudgetTemplateRevision(
    val id: BudgetTemplateRevisionId,
    val templateId: BudgetTemplateId,
    val revisionNumber: Int,
    val totalBaseMinor: Long,
    val categoryLimits: List<CategoryBudgetLimit>,
    val createdCommitId: BookCommitId,
) : LifecycleRecord<RecordLifecycle.Revision> {
    override val lifecycle: RecordLifecycle.Revision = RecordLifecycle.Revision

    init {
        require(revisionNumber > 0)
        require(totalBaseMinor >= 0L)
    }
}

data class BudgetMonth(
    val id: BudgetMonthId,
    val month: YearMonth,
    val currentRevisionId: BudgetMonthRevisionId,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current
}

data class BudgetMonthRevision(
    val id: BudgetMonthRevisionId,
    val budgetMonthId: BudgetMonthId,
    val revisionNumber: Int,
    val totalBaseMinor: Long,
    val categoryLimits: List<CategoryBudgetLimit>,
    val sourceTemplateRevisionId: BudgetTemplateRevisionId?,
    val createdCommitId: BookCommitId,
) : LifecycleRecord<RecordLifecycle.Revision> {
    override val lifecycle: RecordLifecycle.Revision = RecordLifecycle.Revision

    init {
        require(revisionNumber > 0)
        require(totalBaseMinor >= 0L)
    }
}

object BudgetHierarchyPolicy {
    @Suppress("ReturnCount")
    fun validate(totalBaseMinor: Long, limits: List<CategoryBudgetLimit>): DomainResult<Unit> {
        if (totalBaseMinor < 0L || limits.map { it.categoryId }.toSet().size != limits.size) {
            return DomainResult.Failure(DomainViolation.InvalidField("budget.categoryLimits"))
        }
        val roots = limits.filter { it.depth == 1 }
        val rootTotal = CheckedArithmetic.sum(roots.map { it.amountBaseMinor })
        if (rootTotal !is DomainResult.Success || rootTotal.value > totalBaseMinor) {
            return DomainResult.Failure(DomainViolation.Invariant("INV-018"))
        }
        for (root in roots) {
            val childTotal = CheckedArithmetic.sum(
                limits.filter { it.parentCategoryId == root.categoryId }.map { it.amountBaseMinor },
            )
            if (childTotal !is DomainResult.Success || childTotal.value > root.amountBaseMinor) {
                return DomainResult.Failure(DomainViolation.Invariant("INV-019"))
            }
        }
        val knownRootIds = roots.map { it.categoryId }.toSet()
        if (limits.any { it.depth == 2 && it.rootCategoryId !in knownRootIds }) {
            return DomainResult.Failure(DomainViolation.InvalidField("budget.rootCategoryId"))
        }
        return DomainResult.Success(Unit)
    }
}

enum class BudgetAdjustmentScope {
    TOTAL,
    CATEGORY,
}

enum class BudgetAdjustmentKind {
    INCREASE_AVAILABLE,
    DECREASE_AVAILABLE,
    TRANSFER_IN,
    TRANSFER_OUT,
    CLEAR_ROLLOVER,
    ARCHIVED_CATEGORY_TRANSFER,
}

data class BudgetAdjustment(
    val id: BudgetAdjustmentId,
    val month: YearMonth,
    val scope: BudgetAdjustmentScope,
    val categoryId: CategoryId?,
    val amountBaseMinor: Long,
    val kind: BudgetAdjustmentKind,
    val createdCommitId: BookCommitId,
    val reversalOfId: BudgetAdjustmentId?,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact

    init {
        require((scope == BudgetAdjustmentScope.CATEGORY) == (categoryId != null))
    }
}

data class BudgetRollover(
    val fromMonth: YearMonth,
    val toMonth: YearMonth,
    val scope: BudgetAdjustmentScope,
    val categoryId: CategoryId?,
    val amountBaseMinor: Long,
    val asOfLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection

    init {
        require(toMonth == fromMonth.plusMonths(1))
        require((scope == BudgetAdjustmentScope.CATEGORY) == (categoryId != null))
    }
}

data class DailyAvailableBudget(
    val month: YearMonth,
    val remainingDisposableBaseMinor: Long,
    val reservedRecurrenceBaseMinor: Long,
    val remainingDayCount: Int,
    val dailyAvailableBaseMinor: Long,
    val asOfLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection

    init {
        require(remainingDayCount > 0)
    }
}

enum class ProjectStatus {
    ACTIVE,
    ARCHIVED,
    COMPLETED,
}

data class Project(
    val id: ProjectId,
    val name: String,
    val description: String?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val budgetBaseMinor: Long,
    val includedInMonthlyBudget: Boolean,
    val goalId: GoalId?,
    val status: ProjectStatus,
    val lastCommitId: BookCommitId,
    val rowVersion: RowVersion,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current

    init {
        require(endDate == null || endDate >= startDate)
        require(budgetBaseMinor >= 0L)
    }
}

enum class GoalStatus {
    ACTIVE,
    COMPLETED,
    ARCHIVED,
}

data class Goal(
    val id: GoalId,
    val accountId: UserAccountId,
    val name: String,
    val targetAmountMinor: Long,
    val currency: CurrencyCode,
    val dueDate: LocalDate?,
    val suggestedMonthlyAmountMinor: Long?,
    val status: GoalStatus,
    val lastCommitId: BookCommitId,
    val rowVersion: RowVersion,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current

    init {
        require(targetAmountMinor > 0L)
        require(suggestedMonthlyAmountMinor == null || suggestedMonthlyAmountMinor > 0L)
    }
}

enum class GoalMovementKind {
    ALLOCATE,
    RELEASE,
    ADJUST,
}

data class GoalMovement(
    val id: GoalMovementId,
    val goalId: GoalId,
    val kind: GoalMovementKind,
    val amount: PositiveMoney,
    val occurredAt: EffectiveTime,
    val sourceTransactionId: TransactionId?,
    val sourceRecurrenceOccurrenceId: RecurrenceOccurrenceId?,
    val reversalOfId: GoalMovementId?,
    val createdCommitId: BookCommitId,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact
}

data class GoalBalanceProjection(
    val goalId: GoalId,
    val allocatedMinor: Long,
    val releasedMinor: Long,
    val spentMinor: Long,
    val restoredMinor: Long,
    val adjustmentMinor: Long,
    val balanceMinor: Long,
    val currency: CurrencyCode,
    val asOfLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}

data class AccountAvailabilityProjection(
    val accountId: UserAccountId,
    val actualBalance: Money,
    val goalReserved: Money,
    val availableBalance: Money,
    val underfunded: Boolean,
    val asOfLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}
