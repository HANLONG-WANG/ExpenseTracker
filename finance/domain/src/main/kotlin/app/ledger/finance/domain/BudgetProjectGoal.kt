package app.ledger.finance.domain

import app.ledger.core.common.CheckedArithmetic
import app.ledger.core.common.DomainResult
import app.ledger.core.common.flatMap
import app.ledger.core.common.map
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
        if (roots.any { it.rootCategoryId != it.categoryId } || hasInvalidChildHierarchy(limits, roots)) {
            return DomainResult.Failure(DomainViolation.InvalidField("budget.categoryHierarchy"))
        }
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

    private fun hasInvalidChildHierarchy(
        limits: List<CategoryBudgetLimit>,
        roots: List<CategoryBudgetLimit>,
    ): Boolean = limits.any { limit ->
        limit.depth == 2 &&
            (limit.parentCategoryId != limit.rootCategoryId || roots.none { it.categoryId == limit.rootCategoryId })
    }
}

data class BudgetConstraintMeter(
    val scopeCategoryId: CategoryId?,
    val allocatedBaseMinor: Long,
    val limitBaseMinor: Long,
    val excessBaseMinor: Long,
) {
    init {
        require(allocatedBaseMinor >= 0L)
        require(limitBaseMinor >= 0L)
        require(excessBaseMinor >= 0L)
    }
}

data class BudgetConstraintReport(
    val total: BudgetConstraintMeter,
    val parents: List<BudgetConstraintMeter>,
) {
    val valid: Boolean = total.excessBaseMinor == 0L && parents.all { it.excessBaseMinor == 0L }
}

object BudgetConstraintPolicy {
    fun evaluate(totalBaseMinor: Long, limits: List<CategoryBudgetLimit>): DomainResult<BudgetConstraintReport> {
        val validated = BudgetHierarchyPolicy.validate(totalBaseMinor, limits)
        val roots = limits.filter { it.depth == 1 }
        return validated.flatMap {
            CheckedArithmetic.sum(roots.map(CategoryBudgetLimit::amountBaseMinor)).flatMap { allocatedRoots ->
                parentMeters(limits, roots).map { parents ->
                    BudgetConstraintReport(meter(null, allocatedRoots, totalBaseMinor), parents)
                }
            }
        }
    }

    private fun parentMeters(
        limits: List<CategoryBudgetLimit>,
        roots: List<CategoryBudgetLimit>,
    ): DomainResult<List<BudgetConstraintMeter>> {
        val meters = mutableListOf<BudgetConstraintMeter>()
        roots.forEach { root ->
            val children = CheckedArithmetic.sum(
                limits.filter { it.parentCategoryId == root.categoryId }
                    .map(CategoryBudgetLimit::amountBaseMinor),
            )
            if (children is DomainResult.Failure) return children
            meters += meter(root.categoryId, (children as DomainResult.Success).value, root.amountBaseMinor)
        }
        return DomainResult.Success(meters)
    }

    private fun meter(categoryId: CategoryId?, allocated: Long, limit: Long): BudgetConstraintMeter {
        val excess = if (allocated > limit) Math.subtractExact(allocated, limit) else 0L
        return BudgetConstraintMeter(categoryId, allocated, limit, excess)
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

data class BudgetMonthMutation(
    val month: BudgetMonth,
    val revision: BudgetMonthRevision,
    val expectedRevisionId: BudgetMonthRevisionId?,
) {
    init {
        require(month.currentRevisionId == revision.id)
        require(month.id == revision.budgetMonthId)
        require((revision.revisionNumber == 1) == (expectedRevisionId == null))
    }
}

data class BudgetTemplateMutation(
    val template: BudgetTemplate,
    val revision: BudgetTemplateRevision,
    val expectedRevisionId: BudgetTemplateRevisionId?,
) {
    init {
        require(template.currentRevisionId == revision.id)
        require(template.id == revision.templateId)
        require((revision.revisionNumber == 1) == (expectedRevisionId == null))
    }
}

data class BudgetMonthComputationInput(
    val month: YearMonth,
    val totalBaseMinor: Long,
    val categoryLimits: List<CategoryBudgetLimit>,
    val directUsageByCategory: Map<CategoryId?, Long>,
    val adjustmentsByCategory: Map<CategoryId?, Long>,
) {
    init {
        require(totalBaseMinor >= 0L)
        require(directUsageByCategory.values.all { it >= 0L })
    }
}

data class BudgetScopeComputation(
    val categoryId: CategoryId?,
    val baseMinor: Long,
    val rolloverMinor: Long,
    val adjustmentMinor: Long,
    val usedMinor: Long,
    val remainingMinor: Long,
)

data class BudgetMonthComputation(
    val month: YearMonth,
    val scopes: List<BudgetScopeComputation>,
) {
    val total: BudgetScopeComputation = scopes.single { it.categoryId == null }
}

/** Rebuildable, unbounded positive/negative rollover chain. It never mutates a budget fact. */
object BudgetRolloverEngine {
    fun rebuild(inputs: List<BudgetMonthComputationInput>): DomainResult<List<BudgetMonthComputation>> = try {
        val ordered = inputs.sortedBy(BudgetMonthComputationInput::month)
        require(ordered.map(BudgetMonthComputationInput::month).toSet().size == ordered.size)
        val carried = mutableMapOf<CategoryId?, Long>()
        DomainResult.Success(
            ordered.map { input ->
                BudgetHierarchyPolicy.validate(input.totalBaseMinor, input.categoryLimits).orThrowBudget()
                val roots = input.categoryLimits.filter { it.depth == 1 }
                val children = input.categoryLimits.filter { it.depth == 2 }
                val allDirectUsed = CheckedArithmetic.sum(input.directUsageByCategory.values).orThrowBudget()
                val totalAdjustment = input.adjustmentsByCategory[null] ?: 0L
                val total = scope(
                    null,
                    input.totalBaseMinor,
                    carried[null] ?: 0L,
                    totalAdjustment,
                    allDirectUsed,
                )
                val categoryScopes = input.categoryLimits.map { limit ->
                    val used = if (limit.depth == 1) {
                        val ids = children.filter { it.parentCategoryId == limit.categoryId }.map { it.categoryId }.toSet()
                        CheckedArithmetic.sum(
                            input.directUsageByCategory.filterKeys { it == limit.categoryId || it in ids }.values,
                        ).orThrowBudget()
                    } else {
                        input.directUsageByCategory[limit.categoryId] ?: 0L
                    }
                    scope(
                        limit.categoryId,
                        limit.amountBaseMinor,
                        carried[limit.categoryId] ?: 0L,
                        input.adjustmentsByCategory[limit.categoryId] ?: 0L,
                        used,
                    )
                }
                val scopes = listOf(total) + categoryScopes
                carried.clear()
                scopes.forEach { carried[it.categoryId] = it.remainingMinor }
                BudgetMonthComputation(input.month, scopes)
            },
        )
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("budget.rollover"))
    } catch (_: IllegalArgumentException) {
        DomainResult.Failure(DomainViolation.InvalidField("budget.rolloverChain"))
    } catch (failure: BudgetComputationFailure) {
        DomainResult.Failure(failure.error)
    }

    private fun scope(categoryId: CategoryId?, base: Long, rollover: Long, adjustment: Long, used: Long): BudgetScopeComputation {
        val available = Math.addExact(Math.addExact(base, rollover), adjustment)
        return BudgetScopeComputation(categoryId, base, rollover, adjustment, used, Math.subtractExact(available, used))
    }

    private class BudgetComputationFailure(val error: app.ledger.core.common.DomainError) : RuntimeException()
    private fun <T> DomainResult<T>.orThrowBudget(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> throw BudgetComputationFailure(error)
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

object DailyAvailableBudgetPolicy {
    fun calculate(
        month: YearMonth,
        remainingDisposableBaseMinor: Long,
        reservedRecurrenceBaseMinor: Long,
        remainingDayCount: Int,
        asOfLocalRevision: LocalRevision,
    ): DomainResult<DailyAvailableBudget> = try {
        if (remainingDayCount <= 0 || reservedRecurrenceBaseMinor < 0L) {
            DomainResult.Failure(DomainViolation.InvalidField("budget.dailyAvailable"))
        } else {
            val distributable = Math.subtractExact(remainingDisposableBaseMinor, reservedRecurrenceBaseMinor)
            DomainResult.Success(
                DailyAvailableBudget(
                    month,
                    remainingDisposableBaseMinor,
                    reservedRecurrenceBaseMinor,
                    remainingDayCount,
                    distributable / remainingDayCount,
                    asOfLocalRevision,
                ),
            )
        }
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("budget.dailyAvailable"))
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

data class GoalBalanceDelta(
    val kind: GoalEffectKind,
    val amountMinor: Long,
    val polarity: EffectPolarity,
) {
    init {
        require(amountMinor > 0L)
    }
}

/** Exact reconstruction rule shared by projection tests and presentation mappers. */
object GoalBalancePolicy {
    fun rebuild(deltas: List<GoalBalanceDelta>): DomainResult<Long> = try {
        val signedDeltas = deltas.map { delta ->
            when (delta.kind) {
                GoalEffectKind.ALLOCATE,
                GoalEffectKind.RESTORE,
                GoalEffectKind.ADJUST,
                -> delta.amountMinor
                GoalEffectKind.RELEASE,
                GoalEffectKind.SPEND,
                -> Math.negateExact(delta.amountMinor)
            }.let { if (delta.polarity == EffectPolarity.APPLY) it else Math.negateExact(it) }
        }
        when (val sum = CheckedArithmetic.sum(signedDeltas)) {
            is DomainResult.Success -> sum
            is DomainResult.Failure -> DomainResult.Failure(DomainViolation.NumericOverflow("goal.balance"))
        }
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("goal.balance"))
    }

    fun availability(actualBalanceMinor: Long, reservedMinor: Long): DomainResult<Pair<Long, Boolean>> = try {
        val available = Math.subtractExact(actualBalanceMinor, reservedMinor)
        DomainResult.Success(available to (available < 0L))
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("goal.availability"))
    }
}

object ProjectStatusPolicy {
    fun canSelectForNewTransaction(status: ProjectStatus): Boolean = status == ProjectStatus.ACTIVE

    fun transition(from: ProjectStatus, to: ProjectStatus): DomainResult<ProjectStatus> = when {
        from == to -> DomainResult.Success(to)
        from == ProjectStatus.ACTIVE && to == ProjectStatus.ARCHIVED -> DomainResult.Success(to)
        from == ProjectStatus.ARCHIVED && to == ProjectStatus.ACTIVE -> DomainResult.Success(to)
        else -> DomainResult.Failure(DomainViolation.InvalidStateTransition("project.status"))
    }
}
