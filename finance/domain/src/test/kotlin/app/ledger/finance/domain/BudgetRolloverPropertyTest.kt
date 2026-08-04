package app.ledger.finance.domain

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.YearMonth
import java.util.UUID

class BudgetRolloverPropertyTest {
    @Test
    fun `generated hierarchy enforces only base limits and reports exact excess`() = runTest {
        checkAll(iterations = 1_000, Arb.long(0L..1_000_000L), Arb.long(0L..1_000_000L), Arb.long(0L..1_000_000L)) { total, root, child ->
            val limits = listOf(
                CategoryBudgetLimit(ROOT, ROOT, null, 1, root),
                CategoryBudgetLimit(CHILD, ROOT, ROOT, 2, child),
            )
            val result = BudgetConstraintPolicy.evaluate(total, limits)
            if (root <= total && child <= root) {
                (result as DomainResult.Success).value.valid.shouldBeTrue()
            } else {
                (result is DomainResult.Failure).shouldBeTrue()
            }
        }
    }

    @Test
    fun `positive and negative rollover are uncapped never expire and parent usage does not double total`() {
        val months = (0L..121L).map { offset ->
            val base = if (offset == 0L) 1_000L else 500L
            val categoryBase = if (offset == 0L) 800L else 500L
            BudgetMonthComputationInput(
                month = START.plusMonths(offset),
                totalBaseMinor = base,
                categoryLimits = listOf(
                    CategoryBudgetLimit(ROOT, ROOT, null, 1, categoryBase),
                    CategoryBudgetLimit(CHILD, ROOT, ROOT, 2, categoryBase),
                ),
                directUsageByCategory = if (offset == 0L) mapOf(CHILD to 1_200L) else emptyMap(),
                adjustmentsByCategory = emptyMap(),
            )
        }
        val output = (BudgetRolloverEngine.rebuild(months) as DomainResult.Success).value
        output shouldHaveSize 122
        output.first().total.usedMinor shouldBe 1_200L
        output.first().total.remainingMinor shouldBe -200L
        output.first().scopes.single { it.categoryId == ROOT }.usedMinor shouldBe 1_200L
        output.first().scopes.single { it.categoryId == CHILD }.usedMinor shouldBe 1_200L
        output[1].total.rolloverMinor shouldBe -200L
        output.last().total.rolloverMinor shouldBe 59_800L
    }

    @Test
    fun `history edit deterministically replaces every later rollover and adjustments remain separate`() {
        val original = listOf(
            input(START, 1_000L, used = 1_200L, adjustment = 100L),
            input(START.plusMonths(1), 1_000L),
        )
        val edited = original.toMutableList().also { it[0] = input(START, 2_000L, used = 1_200L, adjustment = 100L) }
        val before = (BudgetRolloverEngine.rebuild(original) as DomainResult.Success).value
        val after = (BudgetRolloverEngine.rebuild(edited) as DomainResult.Success).value
        before[0].total.run { listOf(baseMinor, rolloverMinor, adjustmentMinor, usedMinor, remainingMinor) } shouldBe listOf(1_000L, 0L, 100L, 1_200L, -100L)
        before[1].total.rolloverMinor shouldBe -100L
        after[1].total.rolloverMinor shouldBe 900L
        BudgetRolloverEngine.rebuild(edited) shouldBe BudgetRolloverEngine.rebuild(edited)
    }

    @Test
    fun `daily available subtracts future fixed reservations before exact integer division`() {
        val value = (DailyAvailableBudgetPolicy.calculate(START, 3_100L, 310L, 31, REVISION) as DomainResult.Success).value
        value.dailyAvailableBaseMinor shouldBe 90L
        (DailyAvailableBudgetPolicy.calculate(START, 100L, 0L, 0, REVISION) is DomainResult.Failure).shouldBeTrue()
        (DailyAvailableBudgetPolicy.calculate(START, Long.MIN_VALUE, 1L, 1, REVISION) is DomainResult.Failure).shouldBeTrue()
    }

    @Test
    fun `category model rejects split depth and mismatched root at construction or policy boundary`() {
        shouldThrow<IllegalArgumentException> { CategoryBudgetLimit(ROOT, ROOT, ROOT, 1, 1L) }
        val wrong = listOf(
            CategoryBudgetLimit(ROOT, ROOT, null, 1, 100L),
            CategoryBudgetLimit(CHILD, OTHER, ROOT, 2, 50L),
        )
        (BudgetHierarchyPolicy.validate(100L, wrong) is DomainResult.Failure).shouldBeTrue()
        (BudgetConstraintPolicy.evaluate(100L, wrong) is DomainResult.Failure).shouldBeTrue()
        BudgetConstraintReport(
            BudgetConstraintMeter(null, 0, 0, 0),
            emptyList(),
        ).valid.shouldBeTrue()
        BudgetConstraintReport(
            BudgetConstraintMeter(null, 1, 0, 1),
            emptyList(),
        ).valid.shouldBeFalse()
    }

    private fun input(month: YearMonth, total: Long, used: Long = 0L, adjustment: Long = 0L) = BudgetMonthComputationInput(
        month,
        total,
        listOf(CategoryBudgetLimit(ROOT, ROOT, null, 1, total)),
        if (used == 0L) emptyMap() else mapOf(ROOT to used),
        if (adjustment == 0L) emptyMap() else mapOf(null to adjustment),
    )

    private companion object {
        val START: YearMonth = YearMonth.of(2016, 1)
        val ROOT = CategoryId(StableId.fromUuid(UUID(0x1717L, 1)))
        val CHILD = CategoryId(StableId.fromUuid(UUID(0x1717L, 2)))
        val OTHER = CategoryId(StableId.fromUuid(UUID(0x1717L, 3)))
        val REVISION = (LocalRevision.of(0L) as DomainResult.Success).value
    }
}
