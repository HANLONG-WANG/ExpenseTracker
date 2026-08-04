package app.ledger.feature.record

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.AccountReferenceView
import app.ledger.finance.application.CategoryReferenceView
import app.ledger.finance.application.MerchantReferenceView
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.application.RefundNamedReference
import app.ledger.finance.application.RefundSnapshot
import app.ledger.finance.application.RefundableTransactionView
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CategoryStatus
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.RefundAccrualPolicy
import app.ledger.finance.domain.RefundBudgetPolicy
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.TransactionLifecycleState
import app.ledger.finance.domain.UserAccountType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

class RefundPolicyTest {
    @Test
    fun `linked partial refund inherits adjustable fields and preserves three date dimensions`() {
        var editor = RefundPolicy.create(snapshot(), ORIGINAL, NOW, ZONE, Locale.ENGLISH)
        assertEquals(CATEGORY, editor.draft.categoryId)
        assertEquals(MERCHANT, editor.draft.merchantId)
        assertEquals(PROJECT, editor.draft.projectId)
        assertEquals(GOAL, editor.draft.goalId)

        editor = RefundPolicy.updateExpression(editor, "400", Locale.ENGLISH)
        editor = RefundPolicy.selectAccount(editor, SECOND_ACCOUNT, Locale.ENGLISH)
        val prepared = RefundPolicy.prepare(editor)

        assertEquals(400L, prepared.amount.inputMinor)
        assertEquals(400L, prepared.amount.receivingAccountMinor)
        assertEquals(400L, prepared.allocations.single().originalCurrencyMinor)
        assertEquals(LocalDate.of(2026, 7, 8), prepared.accrualDate)
        assertEquals(YearMonth.of(2026, 7), prepared.budgetTargetMonth)
        assertEquals(SECOND_ACCOUNT, prepared.amount.receivingAccountId)
    }

    @Test
    fun `excess override requires a separate high risk confirmation`() {
        var editor = RefundPolicy.create(snapshot(), ORIGINAL, NOW, ZONE, Locale.ENGLISH)
        editor = RefundPolicy.updateExpression(editor, "601", Locale.ENGLISH)
        assertTrue(RefundPolicy.validate(editor).errors.any { it.field == RefundField.EXCESS_CONFIRMATION })

        editor = RefundPolicy.requestExcessOverride(editor, true)
        assertFalse(editor.draft.excessRiskConfirmed)
        assertTrue(RefundPolicy.validate(editor).errors.any { it.field == RefundField.EXCESS_CONFIRMATION })

        editor = RefundPolicy.confirmExcessRisk(editor)
        assertTrue(editor.draft.excessRiskConfirmed)
        assertTrue(RefundPolicy.validate(editor).errors.isEmpty())
    }

    @Test
    fun `refund month policy changes budget month without changing cash date or original accrual policy`() {
        var editor = RefundPolicy.create(snapshot(), ORIGINAL, NOW, ZONE, Locale.ENGLISH)
        editor = RefundPolicy.updateExpression(editor, "100", Locale.ENGLISH)
        editor = RefundPolicy.setBudgetPolicy(editor, RefundBudgetPolicy.RESTORE_REFUND_MONTH)
        val prepared = RefundPolicy.prepare(editor)
        assertEquals(LocalDate.of(2026, 8, 4), editor.draft.localDate)
        assertEquals(LocalDate.of(2026, 7, 8), prepared.accrualDate)
        assertEquals(YearMonth.of(2026, 8), prepared.budgetTargetMonth)
    }

    @Test
    fun `independent refund has no allocation and uses refund date`() {
        var editor = RefundPolicy.create(snapshot(), ORIGINAL, NOW, ZONE, Locale.ENGLISH)
        editor = RefundPolicy.setIndependent(editor, true, Locale.ENGLISH)
        editor = RefundPolicy.updateExpression(editor, "250", Locale.ENGLISH)
        val prepared = RefundPolicy.prepare(editor)
        assertTrue(editor.draft.independent)
        assertTrue(prepared.allocations.isEmpty())
        assertEquals(RefundAccrualPolicy.REFUND_DATE, editor.draft.accrualPolicy)
        assertEquals(editor.draft.localDate, prepared.accrualDate)
        assertEquals(null, prepared.budgetTargetMonth)
    }

    private fun snapshot(): RefundSnapshot {
        val account = AccountReferenceView(
            ACCOUNT, UserAccountType.CASH, "Wallet", JPY, EntityStatus.ACTIVE, null, null, null,
            "account", 0, 0, 1, 0, 0, NOW, true, 0,
        )
        val second = account.copy(id = SECOND_ACCOUNT, name = "Bank", sortOrder = 1)
        val category = CategoryReferenceView(
            CATEGORY, CategoryDirection.EXPENSE, null, 1, "Food", "record", 0, 0, CategoryStatus.ACTIVE,
            StatisticalNature.CONSUMPTION_EXPENSE, ACCOUNT, null, MERCHANT, 1, 1, 0,
        )
        val merchant = MerchantReferenceView(MERCHANT, "Cafe", emptyList(), EntityStatus.ACTIVE, null, 1, 1, 0)
        val references = ReferenceDataSnapshot(
            BOOK, JPY, 9, listOf(account, second), emptyList(), listOf(category), listOf(merchant),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0, 0, false,
        )
        val original = RefundableTransactionView(
            ORIGINAL, ORIGINAL_REVISION, TransactionLifecycleState.ACTIVE, 1_000, JPY, 1_000, JPY, 400, 600, 0,
            Instant.parse("2026-07-08T01:00:00Z"), LocalDate.of(2026, 7, 8), ACCOUNT, null, CATEGORY, "Food",
            StatisticalNature.CONSUMPTION_EXPENSE, MERCHANT, "Cafe", PROJECT, "Trip", GOAL, "Camera", null, emptyList(), null,
        )
        return RefundSnapshot(references, listOf(original), listOf(RefundNamedReference(PROJECT, "Trip")), listOf(RefundNamedReference(GOAL, "Camera")))
    }

    private companion object {
        fun id(value: Long): StableId = StableId.fromUuid(UUID(0x16, value))
        fun currency(value: String): CurrencyCode = (CurrencyCode.parse(value) as DomainResult.Success).value
        val BOOK = id(1)
        val ACCOUNT = id(2)
        val SECOND_ACCOUNT = id(3)
        val CATEGORY = id(4)
        val MERCHANT = id(5)
        val PROJECT = id(6)
        val GOAL = id(7)
        val ORIGINAL = id(8)
        val ORIGINAL_REVISION = id(9)
        val JPY = currency("JPY")
        val NOW: Instant = Instant.parse("2026-08-04T03:00:00Z")
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
