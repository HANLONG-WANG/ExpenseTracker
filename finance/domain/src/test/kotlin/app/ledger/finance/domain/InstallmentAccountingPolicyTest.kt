package app.ledger.finance.domain

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random

class InstallmentAccountingPolicyTest {
    @Test
    fun `generated principal always conserves and final term absorbs every tail`() {
        val random = Random(20)
        repeat(2_000) { index ->
            val principal = random.nextLong(1L, 9_000_000_000L)
            val terms = random.nextInt(1, 61)
            val schedule = generate(principal, terms, feeRevision(InstallmentFeeRateType.NONE), index.toLong()).success()
            schedule.items.sumOf { it.principalMinor } shouldBe principal
            schedule.items.last().remainingPrincipalMinor shouldBe 0L
            schedule.items.dropLast(1).all { it.principalMinor == principal / terms } shouldBe true
            schedule.items.last().principalMinor shouldBe principal - (principal / terms) * (terms - 1)
            InstallmentSchedulePolicy.validate(principal, schedule.items) shouldBe DomainResult.Success(Unit)
        }
    }

    @Test
    fun `all frozen fee models are exact deterministic and separated from principal`() {
        val fixed = generate(120_001, 12, feeRevision(InstallmentFeeRateType.FIXED_PER_TERM, fixed = 99), 10).success()
        fixed.items.sumOf { it.feeMinor } shouldBe 1_188L
        fixed.items.sumOf { it.interestMinor } shouldBe 0L

        val first = generate(120_001, 12, feeRevision(InstallmentFeeRateType.FIRST_TERM_FIXED, first = 800), 20).success()
        first.items.map { it.feeMinor } shouldBe listOf(800L) + List(11) { 0L }

        val remaining = generate(
            120_000,
            12,
            feeRevision(InstallmentFeeRateType.REMAINING_PRINCIPAL_RATE, remaining = rate("0.12")),
            30,
        ).success()
        remaining.items.first().feeMinor shouldBe 1_200L
        remaining.items.last().feeMinor shouldBe 100L

        val annual = generate(
            120_000,
            12,
            feeRevision(InstallmentFeeRateType.EFFECTIVE_ANNUAL_RATE, annual = rate("0.12")),
            40,
        ).success()
        annual.items.first().interestMinor shouldBe 1_200L
        annual.items.all { it.feeMinor == 0L } shouldBe true
        InstallmentAccountingPolicy.summarize(annual).success().actualAnnualRate.annualDecimal.signum() shouldBe 1
    }

    @Test
    fun `settlement is a pure comparison and never mutates the schedule`() {
        val terms = feeRevision(
            InstallmentFeeRateType.FIXED_PER_TERM,
            fixed = 100,
        ).copy(
            prepaymentPolicy = InstallmentPrepaymentPolicy.ALLOWED_WITH_FEE,
            prepaymentFeeMinor = 150,
        )
        val schedule = generate(12_001, 6, terms, 50).success()
        val before = schedule.copy(items = schedule.items.toList())
        val plan = plan(12_001, 6, terms)
        val simulation = InstallmentAccountingPolicy.simulateSettlement(
            plan,
            terms,
            schedule,
            LocalDate.of(2026, 9, 15),
        ).success()
        simulation.allowed shouldBe true
        simulation.paymentMinor shouldBe simulation.outstandingPrincipalMinor + 150L
        simulation.savedCostMinor shouldBe simulation.futureFeeMinor - 150L
        schedule shouldBe before
        plan.status shouldBe InstallmentStatus.ACTIVE
    }

    @Test
    fun `refund schedule remains versioned and conserves reduced principal`() {
        val terms = feeRevision(InstallmentFeeRateType.REMAINING_PRINCIPAL_RATE, remaining = rate("0.06"))
        val allocation = InstallmentRefundAllocation(
            TransactionId(stable(71)),
            TransactionRevisionId(stable(72)),
            terms.planId,
            2_345,
            25,
            null,
        )
        val request = request(10_000, 4, terms, 80)
        val result = InstallmentAccountingPolicy.recalculateAfterRefund(request, allocation).success()
        result.currentPrincipalMinor shouldBe 7_655L
        result.replacementSchedule.reason shouldBe ScheduleRevisionReason.REFUND
        result.replacementSchedule.items.sumOf { it.principalMinor } shouldBe 7_655L
        result.replacementSchedule.id shouldBe request.scheduleRevisionId
    }

    @Test
    fun `overflow and corrupt remaining chain fail closed`() {
        val terms = feeRevision(InstallmentFeeRateType.FIXED_PER_TERM, fixed = Long.MAX_VALUE)
        (InstallmentAccountingPolicy.summarize(generate(10, 2, terms, 90).success()) is DomainResult.Failure) shouldBe true
        val bad = listOf(
            InstallmentScheduleItem(InstallmentScheduleItemId(stable(91)), 1, LocalDate.of(2026, 1, 1), 5, 0, 0, 6),
            InstallmentScheduleItem(InstallmentScheduleItemId(stable(92)), 2, LocalDate.of(2026, 2, 1), 5, 0, 0, 0),
        )
        (InstallmentSchedulePolicy.validate(10, bad) is DomainResult.Failure) shouldBe true
    }

    private fun generate(
        principal: Long,
        terms: Int,
        revision: InstallmentPlanRevision,
        seed: Long,
    ): DomainResult<InstallmentScheduleRevision> = InstallmentAccountingPolicy.generate(request(principal, terms, revision, seed))

    private fun request(
        principal: Long,
        terms: Int,
        revision: InstallmentPlanRevision,
        seed: Long,
    ) = InstallmentScheduleRequest(
        revision.planId,
        InstallmentScheduleRevisionId(stable(seed + 2)),
        (1..terms).map { InstallmentScheduleItemId(stable(seed + 100 + it)) },
        revision.revisionNumber,
        ScheduleRevisionReason.INITIAL,
        Instant.parse("2026-08-04T00:00:00Z"),
        revision.createdCommitId,
        principal,
        terms,
        LocalDate.of(2026, 8, 31),
        revision,
    )

    private fun feeRevision(
        type: InstallmentFeeRateType,
        fixed: Long? = null,
        first: Long? = null,
        remaining: InterestRate? = null,
        annual: InterestRate? = null,
    ) = InstallmentPlanRevision(
        InstallmentPlanRevisionId(stable(2)),
        InstallmentPlanId(stable(1)),
        1,
        type,
        fixed,
        first,
        remaining,
        annual,
        InstallmentPrepaymentPolicy.ALLOWED_WITHOUT_FEE,
        null,
        InstallmentRefundPolicy.REBUILD_SCHEDULE,
        RoundingMode.HALF_EVEN,
        BookCommitId(stable(3)),
    )

    private fun plan(principal: Long, terms: Int, revision: InstallmentPlanRevision) = InstallmentPlan(
        revision.planId,
        TransactionId(stable(4)),
        UserAccountId(stable(5)),
        principal,
        PlannerFixtures.jpy,
        terms,
        revision.id,
        InstallmentStatus.ACTIVE,
    )

    private fun rate(value: String): InterestRate = InterestRate.of(BigDecimal(value)).success()

    private fun stable(value: Long): StableId = StableId.fromBytes(
        ByteBuffer.allocate(StableId.BYTE_COUNT).putLong(20L).putLong(value).array(),
    ).success()

    private fun <T> DomainResult<T>.success(): T = (this as DomainResult.Success<T>).value
}
