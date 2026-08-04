package app.ledger.finance.domain

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.time.EffectiveTime
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class RefundPlannerPropertyTest {
    @Test
    fun `linked refunds are balanced contra expense facts across partial full and cross month amounts`() = runTest {
        checkAll(iterations = 500, Arb.long(1L, 1_000_000_000_000L)) { amount ->
            val gross = Math.multiplyExact(amount, 2L)
            val originalId = TransactionId(stableId(171_000L))
            val command = refundCommand(amount, originalId, 171_100L)
            val snapshot = refundSnapshot(amount, gross, 0L, originalId, 171_500L)
            val plan = DeterministicFinancialPlanner.plan(command, snapshot).success()

            plan.journalBundles.all { it.entry.baseDebitTotalMinor == it.entry.baseCreditTotalMinor }.shouldBeTrue()
            plan.economicEffects.single().nature shouldBe EconomicNature.CONTRA_EXPENSE
            plan.economicEffects.single().accrualDate shouldBe LocalDate.of(2026, 7, 8)
            plan.budgetEffects.single().targetMonth shouldBe YearMonth.of(2026, 7)
            plan.projectEffects.single().kind shouldBe ProjectEffectKind.RESTORE
            plan.projectEffects.single().baseAmount.minor.value shouldBe amount
            plan.goalEffects.single().kind shouldBe GoalEffectKind.RESTORE
            plan.goalEffects.single().amount.minor.value shouldBe amount
            plan.refundAllocations.single().amountInOriginalCurrency.minor.value shouldBe amount
            plan.refundAllocations.single().amountInBaseCurrency.minor.value shouldBe amount
        }
    }

    @Test
    fun `refund trash reverses allocation cash budget project goal and economic facts to zero`() {
        val originalId = TransactionId(stableId(172_000L))
        val createSnapshot = refundSnapshot(600L, 1_000L, 400L, originalId, 172_500L)
        val create = DeterministicFinancialPlanner.plan(refundCommand(600L, originalId, 172_100L), createSnapshot).success()
        val trashUnsigned = MoveTransactionToTrashCommand(
            CommandId(stableId(173_000L)),
            create.revisions.single().id,
            hash(0),
            create.transactions.single().id,
            Instant.parse("2026-10-01T00:00:00Z"),
            emptyList(),
        )
        val trash = trashUnsigned.copy(payloadHash = CanonicalFinancialHash.command(trashUnsigned))
        val trashSnapshot = PlannerFixtures.snapshot(
            amountEvidence = listOf(PlannerFixtures.sameCurrencyEvidence(AmountRole.REFUND, 600L, PlannerFixtures.bankJpyId)),
            seed = 173_500L,
            currentTransaction = create.transactions.single(),
            currentRevision = create.revisions.single(),
            currentFacts = PlannerFixtures.currentFacts(create),
            sourceBook = PlannerFixtures.nextBook(create, createSnapshot.book),
        )
        val reversal = DeterministicFinancialPlanner.plan(trash, trashSnapshot).success()

        reversal.refundAllocations.single().reversalOf shouldBe RefundAllocationReference(
            create.revisions.single().id,
            originalId,
        )
        reversal.economicEffects.single().polarity shouldBe EffectPolarity.REVERSE
        reversal.budgetEffects.single().polarity shouldBe EffectPolarity.REVERSE
        reversal.projectEffects.single().polarity shouldBe EffectPolarity.REVERSE
        reversal.goalEffects.single().polarity shouldBe EffectPolarity.REVERSE
        val postingNet = FinancialFactNetting.postings(PlannerFixtures.currentFacts(create).journalBundles + reversal.journalBundles).success()
        postingNet.all { it.accountDebitMinusCreditMinor == 0L && it.baseDebitMinusCreditMinor == 0L }.shouldBeTrue()
    }

    @Test
    fun `multiple refunds enforce cumulative remaining and preserve explicit excess evidence`() {
        val originalId = TransactionId(stableId(174_000L))
        val denied = refundCommand(601L, originalId, 174_100L)
        DeterministicFinancialPlanner.plan(denied, refundSnapshot(601L, 1_000L, 400L, originalId, 174_500L)) shouldBe
            DomainResult.Failure(DomainViolation.Invariant("INV-010"))

        val overrideUnsigned = denied.copy(
            commandId = CommandId(stableId(174_101L)),
            payloadHash = hash(0),
            input = denied.input.copy(payload = denied.input.payload.copy(allowExcessOverride = true)),
        )
        val override = overrideUnsigned.copy(payloadHash = CanonicalFinancialHash.command(overrideUnsigned))
        val plan = DeterministicFinancialPlanner.plan(
            override,
            refundSnapshot(601L, 1_000L, 400L, originalId, 174_600L),
        ).success()
        plan.refundAllocations.single().amountInOriginalCurrency.minor.value shouldBe 601L
        plan.revisions.single().payload.let { it as RefundPayload }.allowExcessOverride.shouldBeTrue()
    }

    private fun refundCommand(amount: Long, originalId: TransactionId, seed: Long): RecordRefundCommand {
        val references = PlannerFixtures.references()
        val payload = RefundPayload(
            CategoryAssignment(PlannerFixtures.expenseCategoryId, CategoryDirection.EXPENSE, StatisticalNature.CONSUMPTION_EXPENSE),
            PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, amount, references),
            null,
            listOf(RefundAllocation(originalId, TransactionRevisionId(stableId(171_001L)), positive(amount, PlannerFixtures.jpy), positive(amount, PlannerFixtures.jpy))),
            independent = false,
            allowExcessOverride = false,
            budgetPolicy = RefundBudgetPolicy.RESTORE_ORIGINAL_MONTH,
            projectPolicy = RefundProjectPolicy.RESTORE_ORIGINAL_PROJECT,
            goalPolicy = RefundGoalPolicy.RESTORE_ORIGINAL_GOAL,
            accrualPolicy = RefundAccrualPolicy.ORIGINAL_TRANSACTION_DATE,
        )
        val context = PlannerFixtures.inputContext(
            occurredAt = EffectiveTime.fromInstant(Instant.parse("2026-09-12T03:00:00Z"), ZoneId.of("Asia/Tokyo")),
            project = PlannerFixtures.projectId,
            goal = PlannerFixtures.goalId,
        ).copy(accrualDate = LocalDate.of(2026, 7, 8), budgetMonth = YearMonth.of(2026, 7))
        val unsigned = RecordRefundCommand(CommandId(stableId(seed)), hash(0), NewTransactionInput(context, payload))
        return unsigned.copy(payloadHash = CanonicalFinancialHash.command(unsigned))
    }

    private fun refundSnapshot(amount: Long, gross: Long, refunded: Long, originalId: TransactionId, seed: Long): PlanningSnapshot = PlannerFixtures.snapshot(
        listOf(PlannerFixtures.sameCurrencyEvidence(AmountRole.REFUND, amount, PlannerFixtures.bankJpyId)),
        seed = seed,
    ).copy(
        refundStatuses = listOf(RefundStatusProjection(originalId, positive(gross, PlannerFixtures.jpy), refunded, gross - refunded, localRevision(1L))),
    )

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }
}
