package app.ledger.finance.domain

import app.ledger.core.common.DomainResult
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DomainInvariantPropertyTest {
    @Test
    fun `balanced journal preserves exact totals for generated positive amounts`() = runTest {
        checkAll(iterations = 1_000, Arb.long(1L, 1_000_000_000L)) { amount ->
            val entryId = JournalEntryId(stableId(200))
            val entry = JournalEntry.create(
                id = entryId,
                sourceRevisionId = TransactionRevisionId(stableId(201)),
                appliesRevisionId = TransactionRevisionId(stableId(201)),
                role = JournalEntryRole.APPLY,
                reversesEntryId = null,
                effectiveAt = effective(),
                baseCurrency = currency("JPY"),
                postings = listOf(
                    posting(entryId, 1, DebitCredit.DEBIT, amount),
                    posting(entryId, 2, DebitCredit.CREDIT, amount),
                ),
                ruleSetVersion = ruleSetVersion(),
                createdCommitId = BookCommitId(stableId(202)),
                contentHash = ContentHash(hash(20)),
            ).success()
            entry.baseDebitTotalMinor shouldBe amount
            entry.baseCreditTotalMinor shouldBe amount
        }
    }

    @Test
    fun `budget hierarchy accepts generated child within parent and rejects excess`() = runTest {
        checkAll(
            iterations = 1_000,
            Arb.long(1L, 1_000_000L),
            Arb.long(0L, 1_000_000L),
        ) { parent, candidate ->
            val child = candidate.coerceAtMost(parent)
            val rootId = CategoryId(stableId(210))
            val childId = CategoryId(stableId(211))
            val limits = listOf(
                CategoryBudgetLimit(rootId, rootId, null, 1, parent),
                CategoryBudgetLimit(childId, rootId, rootId, 2, child),
            )
            BudgetHierarchyPolicy.validate(parent, limits).success() shouldBe Unit
            (BudgetHierarchyPolicy.validate(parent - 1L, limits) is DomainResult.Failure).shouldBeTrue()
        }
    }

    @Test
    fun `settlement positions conserve for every generated two party split`() = runTest {
        checkAll(
            iterations = 1_000,
            Arb.long(1L, 1_000_000L),
            Arb.long(0L, 1_000_000L),
        ) { total, candidate ->
            val selfOwed = candidate.coerceAtMost(total)
            val friendOwed = Math.subtractExact(total, selfOwed)
            SettlementSharePolicy.validate(
                total,
                listOf(
                    SettlementShare(
                        ParticipantId(stableId(220)),
                        paidMinor = total,
                        owedMinor = selfOwed,
                        weight = null,
                        roundingAdjustmentMinor = 0L,
                    ),
                    SettlementShare(
                        ParticipantId(stableId(221)),
                        paidMinor = 0L,
                        owedMinor = friendOwed,
                        weight = null,
                        roundingAdjustmentMinor = 0L,
                    ),
                ),
            ).success() shouldBe Unit
        }
    }

    private fun posting(
        entryId: JournalEntryId,
        line: Int,
        side: DebitCredit,
        amount: Long,
    ): Posting = Posting.create(
        id = PostingId(stableId(230L + line)),
        journalEntryId = entryId,
        lineNumber = line,
        ledgerAccount = LedgerAccountSnapshot(
            id = LedgerAccountId(stableId(240L + line)),
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
