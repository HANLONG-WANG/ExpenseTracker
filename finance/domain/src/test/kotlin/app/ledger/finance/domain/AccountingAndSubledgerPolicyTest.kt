package app.ledger.finance.domain

import app.ledger.core.common.DomainResult
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class AccountingAndSubledgerPolicyTest {
    @Test
    fun `posting rejects ledger currency mismatch`() {
        val jpy = currency("JPY")
        val usd = currency("USD")
        val result = Posting.create(
            id = PostingId(stableId(30)),
            journalEntryId = JournalEntryId(stableId(31)),
            lineNumber = 1,
            ledgerAccount = LedgerAccountSnapshot(
                id = LedgerAccountId(stableId(32)),
                accountClass = LedgerAccountClass.ASSET,
                normalSide = DebitCredit.DEBIT,
                currency = jpy,
                status = EntityStatus.ACTIVE,
            ),
            side = DebitCredit.DEBIT,
            accountAmount = positive(100L, usd),
            baseAmount = positive(100L, jpy),
            baseCurrency = jpy,
            valuationRate = BigDecimal.ONE,
            role = PostingRole.ASSET,
            reversalOfPostingId = null,
        )

        (result is DomainResult.Failure).shouldBeTrue()
    }

    @Test
    fun `journal factory rejects unbalanced base postings`() {
        val jpy = currency("JPY")
        val entryId = JournalEntryId(stableId(33))
        val debit = posting(entryId, 1, DebitCredit.DEBIT, 100L)
        val credit = posting(entryId, 2, DebitCredit.CREDIT, 99L)
        val result = JournalEntry.create(
            id = entryId,
            sourceRevisionId = TransactionRevisionId(stableId(34)),
            appliesRevisionId = TransactionRevisionId(stableId(34)),
            role = JournalEntryRole.APPLY,
            reversesEntryId = null,
            effectiveAt = effective(),
            baseCurrency = jpy,
            postings = listOf(debit, credit),
            ruleSetVersion = ruleSetVersion(),
            createdCommitId = BookCommitId(stableId(35)),
            contentHash = ContentHash(hash(35)),
        )

        (result is DomainResult.Failure).shouldBeTrue()
    }

    @Test
    fun `journal factory records exact checked totals`() {
        val entryId = JournalEntryId(stableId(36))
        val journal = JournalEntry.create(
            id = entryId,
            sourceRevisionId = TransactionRevisionId(stableId(37)),
            appliesRevisionId = TransactionRevisionId(stableId(37)),
            role = JournalEntryRole.APPLY,
            reversesEntryId = null,
            effectiveAt = effective(),
            baseCurrency = currency("JPY"),
            postings = listOf(
                posting(entryId, 1, DebitCredit.DEBIT, 100L),
                posting(entryId, 2, DebitCredit.CREDIT, 100L),
            ),
            ruleSetVersion = ruleSetVersion(),
            createdCommitId = BookCommitId(stableId(38)),
            contentHash = ContentHash(hash(38)),
        ).success()

        journal.baseDebitTotalMinor shouldBe 100L
        journal.baseCreditTotalMinor shouldBe 100L
        journal.postingCount shouldBe 2
    }

    @Test
    fun `budget hierarchy validates both frozen limits and overflow`() {
        val root = CategoryId(stableId(40))
        val child = CategoryId(stableId(41))
        val valid = listOf(
            CategoryBudgetLimit(root, root, null, 1, 800L),
            CategoryBudgetLimit(child, root, root, 2, 500L),
        )
        BudgetHierarchyPolicy.validate(1_000L, valid).success() shouldBe Unit
        (BudgetHierarchyPolicy.validate(700L, valid) is DomainResult.Failure).shouldBeTrue()
        val invalidChild = valid.toMutableList().also {
            it[1] = CategoryBudgetLimit(child, root, root, 2, 900L)
        }
        (BudgetHierarchyPolicy.validate(1_000L, invalidChild) is DomainResult.Failure).shouldBeTrue()
    }

    @Test
    fun `loan and installment principal schedules are conserved`() {
        val installmentItems = listOf(
            InstallmentScheduleItem(
                InstallmentScheduleItemId(stableId(50)),
                1,
                LocalDate.of(2026, 8, 1),
                60L,
                2L,
                1L,
                40L,
            ),
            InstallmentScheduleItem(
                InstallmentScheduleItemId(stableId(51)),
                2,
                LocalDate.of(2026, 9, 1),
                40L,
                1L,
                1L,
                0L,
            ),
        )
        InstallmentSchedulePolicy.validate(100L, installmentItems).success() shouldBe Unit
        (InstallmentSchedulePolicy.validate(99L, installmentItems) is DomainResult.Failure).shouldBeTrue()

        val loanItems = listOf(
            LoanScheduleItem(
                LoanScheduleItemId(stableId(52)),
                1,
                LocalDate.of(2026, 8, 1),
                60L,
                2L,
                0L,
                40L,
                false,
            ),
            LoanScheduleItem(
                LoanScheduleItemId(stableId(53)),
                2,
                LocalDate.of(2026, 9, 1),
                40L,
                1L,
                0L,
                0L,
                true,
            ),
        )
        LoanSchedulePolicy.validate(100L, loanItems).success() shouldBe Unit
        (LoanSchedulePolicy.validate(101L, loanItems) is DomainResult.Failure).shouldBeTrue()
    }

    @Test
    fun `settlement paid owed and signed effects conserve exactly`() {
        val self = ParticipantId(stableId(60))
        val friend = ParticipantId(stableId(61))
        SettlementSharePolicy.validate(
            200L,
            listOf(
                SettlementShare(self, paidMinor = 200L, owedMinor = 100L, weight = null, roundingAdjustmentMinor = 0L),
                SettlementShare(friend, paidMinor = 0L, owedMinor = 100L, weight = null, roundingAdjustmentMinor = 0L),
            ),
        ).success() shouldBe Unit
        (
            SettlementSharePolicy.validate(
                200L,
                listOf(
                    SettlementShare(self, paidMinor = 200L, owedMinor = 120L, weight = null, roundingAdjustmentMinor = 0L),
                    SettlementShare(friend, paidMinor = 0L, owedMinor = 100L, weight = null, roundingAdjustmentMinor = 0L),
                ),
            ) is DomainResult.Failure
            ).shouldBeTrue()
    }

    private fun posting(
        entryId: JournalEntryId,
        line: Int,
        side: DebitCredit,
        amount: Long,
    ): Posting = Posting.create(
        id = PostingId(stableId(100L + line)),
        journalEntryId = entryId,
        lineNumber = line,
        ledgerAccount = LedgerAccountSnapshot(
            id = LedgerAccountId(stableId(110L + line)),
            accountClass = LedgerAccountClass.ASSET,
            normalSide = DebitCredit.DEBIT,
            currency = currency("JPY"),
            status = EntityStatus.ACTIVE,
        ),
        side = side,
        accountAmount = positive(amount, currency("JPY")),
        baseAmount = positive(amount, currency("JPY")),
        baseCurrency = currency("JPY"),
        valuationRate = null,
        role = PostingRole.ASSET,
        reversalOfPostingId = null,
    ).success()
}
