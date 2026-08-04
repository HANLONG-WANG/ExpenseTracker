package app.ledger.feature.planning

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.BudgetCategoryLimitDraft
import app.ledger.finance.application.BudgetCategoryReference
import app.ledger.finance.application.BudgetProjectionReadiness
import app.ledger.finance.application.BudgetRevisionView
import app.ledger.finance.application.BudgetSnapshot
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.LocalRevision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

class BudgetPolicyTest {
    @Test
    fun `root and child base constraints are live and exclude rollover or adjustment`() {
        var state = BudgetPolicy.create(snapshot(), BudgetPresentation.EDITING)
        assertTrue(state.validation.valid)
        state = BudgetPolicy.updateCategory(state, ROOT, "4000")
        assertFalse(state.validation.valid)
        assertEquals(1_000L, state.validation.report?.parents?.single()?.excessBaseMinor)
        state = BudgetPolicy.updateCategory(state, CHILD, "4000")
        assertTrue(state.validation.valid)
    }

    @Test
    fun `authoritative amount parsing uses currency minor units and exact overflow rejection`() {
        var state = BudgetPolicy.create(snapshot(), BudgetPresentation.EDITING)
        state = BudgetPolicy.updateTotal(state, "1234")
        assertEquals(1_234L, state.validation.totalMinor)
        state = BudgetPolicy.updateTotal(state, "999999999999999999999999")
        assertEquals(null, state.validation.totalMinor)
        assertEquals(BudgetPresentation.CONSTRAINT_ERROR, state.presentation)
    }

    @Test
    fun `adjustment requires a positive exact amount and cycles distinct categories`() {
        var state = BudgetPolicy.create(snapshot(), BudgetPresentation.EDITING)
        state = BudgetPolicy.updateAdjustmentAmount(state, "0")
        assertEquals(null, BudgetPolicy.adjustmentMinor(state))
        state = BudgetPolicy.updateAdjustmentAmount(state, "250")
        assertEquals(250L, BudgetPolicy.adjustmentMinor(state))
        val target = state.adjustmentTargetCategoryId
        state = BudgetPolicy.selectNextAdjustmentSource(state)
        assertTrue(state.adjustmentSourceCategoryId != target)
    }

    private fun snapshot(): BudgetSnapshot {
        val revision = BudgetRevisionView(
            id(20),
            1,
            10_000L,
            listOf(BudgetCategoryLimitDraft(ROOT, 7_000L), BudgetCategoryLimitDraft(CHILD, 5_000L)),
            null,
            Instant.EPOCH,
        )
        return BudgetSnapshot(
            id(1), (CurrencyCode.parse("JPY") as DomainResult.Success).value, YearMonth.of(2026, 8), LocalDate.of(2026, 8, 4),
            (LocalRevision.of(1) as DomainResult.Success).value, BudgetProjectionReadiness.CURRENT, id(2), revision, listOf(revision),
            listOf(
                BudgetCategoryReference(ROOT, "Food", ROOT, null, 1, EntityStatus.ACTIVE),
                BudgetCategoryReference(CHILD, "Lunch", ROOT, ROOT, 2, EntityStatus.ACTIVE),
            ),
            emptyList(), emptyList(), emptyList(), null,
        )
    }

    private companion object {
        fun id(value: Long): StableId = StableId.fromUuid(UUID(0x1719L, value))
        val ROOT = id(10)
        val CHILD = id(11)
    }
}
