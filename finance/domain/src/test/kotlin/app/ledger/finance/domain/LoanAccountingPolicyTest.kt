package app.ledger.finance.domain

import app.ledger.core.common.DomainResult
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random

class LoanAccountingPolicyTest {
    @Test
    fun `all repayment methods conserve principal and close their remaining chain`() {
        val random = Random(21)
        repeat(2_000) { index ->
            val principal = random.nextLong(1_000L, 9_000_000_000L)
            val count = random.nextInt(1, 121)
            val method = LoanRepaymentMethod.entries[index % 4]
            val schedule = generate(principal, count, terms(method, index.toLong()), index.toLong()).success()
            schedule.items.sumOf { it.principalMinor } shouldBe principal
            schedule.items.last().remainingPrincipalMinor shouldBe 0L
            schedule.items.last().finalInstallment shouldBe true
            schedule.items.dropLast(1).none { it.finalInstallment } shouldBe true
            LoanSchedulePolicy.validate(principal, schedule.items) shouldBe DomainResult.Success(Unit)
        }
    }

    @Test
    fun `fixed and floating rate stages are deterministic and overlap or gaps fail closed`() {
        val first = period("0.036", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), "LPR", "0.006")
        val second = period("0.048", LocalDate.of(2026, 7, 1), null, "LPR", "0.006")
        val configured = terms(LoanRepaymentMethod.EQUAL_PRINCIPAL, 3).copy(
            rateType = LoanRateType.FLOATING,
            ratePeriods = listOf(first, second),
        )
        val request = request(120_001L, 12, configured, 3)
        val one = LoanAccountingPolicy.generate(request).success()
        val two = LoanAccountingPolicy.generate(request).success()
        one shouldBe two
        one.items.first().interestMinor shouldBe 360L
        one.items[6].interestMinor shouldBe 240L

        val overlap = listOf(first, second.copy(effectiveFrom = LocalDate.of(2026, 6, 30)))
        (LoanRatePeriodPolicy.validate(overlap) is DomainResult.Failure) shouldBe true
        val gapTerms = configured.copy(ratePeriods = listOf(first, second.copy(effectiveFrom = LocalDate.of(2026, 8, 1))))
        (LoanAccountingPolicy.generate(request.copy(terms = gapTerms)) is DomainResult.Failure) shouldBe true
    }

    @Test
    fun `custom schedule preserves explicit interest and fees while final principal absorbs tail`() {
        val configured = terms(LoanRepaymentMethod.CUSTOM, 8).copy(paymentFrequency = PaymentFrequency.CUSTOM)
        val custom = listOf(
            LoanCustomScheduleLine(LocalDate.of(2026, 2, 1), 3_000L, 100L, 10L),
            LoanCustomScheduleLine(LocalDate.of(2026, 4, 1), 3_000L, 70L, 10L),
            LoanCustomScheduleLine(LocalDate.of(2026, 8, 1), 1L, 30L, 10L),
        )
        val schedule = LoanAccountingPolicy.generate(request(10_001L, 3, configured, 8, custom)).success()
        schedule.items.map { it.principalMinor } shouldBe listOf(3_000L, 3_000L, 4_001L)
        schedule.items.sumOf { it.interestMinor } shouldBe 200L
        schedule.items.sumOf { it.feeMinor } shouldBe 30L
    }

    @Test
    fun `payment components reconcile exactly and principal cannot exceed any tranche`() {
        val first = LoanTrancheId(stableId(21_001))
        val second = LoanTrancheId(stableId(21_002))
        val allocations = listOf(
            allocation(first, LoanPaymentComponent.PRINCIPAL, 4_000L),
            allocation(first, LoanPaymentComponent.INTEREST, 120L),
            allocation(second, LoanPaymentComponent.PRINCIPAL, 2_000L),
            allocation(second, LoanPaymentComponent.FEE, 40L),
            allocation(second, LoanPaymentComponent.PENALTY, 10L),
        )
        LoanAccountingPolicy.validatePayment(
            6_170L,
            mapOf(first to 5_000L, second to 2_000L),
            allocations,
        ).success() shouldBe LoanPaymentComponentTotals(6_000L, 120L, 40L, 10L)
        (LoanAccountingPolicy.validatePayment(6_169L, mapOf(first to 5_000L, second to 2_000L), allocations) is DomainResult.Failure) shouldBe true
        (LoanAccountingPolicy.validatePayment(6_170L, mapOf(first to 3_999L, second to 2_000L), allocations) is DomainResult.Failure) shouldBe true
    }

    @Test
    fun `prepayment simulation is pure and applying strategies produces replacement versions`() {
        val configured = terms(LoanRepaymentMethod.EQUAL_PAYMENT, 30).copy(
            prepaymentPolicy = LoanPrepaymentPolicy.ALLOWED_WITH_PENALTY,
            penaltyRate = rate("0.02"),
        )
        val current = generate(120_000L, 12, configured, 30).success()
        val before = current.copy(items = current.items.toList())
        val scenario = LoanSimulationScenario.PartialPrepayment(
            20_000L,
            PrepaymentRecalculationStrategy.SHORTEN_TERM,
            LocalDate.of(2026, 1, 15),
        )
        val replacement = request(100_000L, 8, configured.copy(id = LoanTermsRevisionId(stableId(21_999))), 31)
        val simulation = LoanAccountingPolicy.simulatePrepayment(
            LoanContractId(stableId(21_100)),
            current,
            configured,
            scenario,
            replacement,
        ).success()
        simulation.penaltyMinor shouldBe 400L
        simulation.paymentNowMinor shouldBe 20_400L
        simulation.after.id shouldBe replacement.scheduleRevisionId
        simulation.after.items.sumOf { it.principalMinor } shouldBe 100_000L
        current shouldBe before
    }

    @Test
    fun `full settlement closes future schedule and overflow fails closed`() {
        val configured = terms(LoanRepaymentMethod.BULLET, 40)
        val current = generate(50_000L, 2, configured, 40).success()
        val simulation = LoanAccountingPolicy.simulatePrepayment(
            LoanContractId(stableId(21_101)),
            current,
            configured,
            LoanSimulationScenario.FullSettlement(LocalDate.of(2026, 1, 5)),
            request(50_000L, 1, configured.copy(id = LoanTermsRevisionId(stableId(21_998))), 41),
        ).success()
        simulation.after.items shouldBe emptyList()
        simulation.afterSummary.principalMinor shouldBe 0L
        simulation.paymentNowMinor shouldBe 50_000L

        val overflow = terms(LoanRepaymentMethod.EQUAL_PRINCIPAL, 50)
        val schedule = generate(Long.MAX_VALUE, 2, overflow, 50).success()
        (LoanAccountingPolicy.summarize(schedule) is DomainResult.Failure) shouldBe true
    }

    private fun generate(
        principal: Long,
        count: Int,
        terms: LoanTermsRevision,
        seed: Long,
    ): DomainResult<LoanScheduleRevision> = LoanAccountingPolicy.generate(request(principal, count, terms, seed))

    private fun request(
        principal: Long,
        count: Int,
        terms: LoanTermsRevision,
        seed: Long,
        customLines: List<LoanCustomScheduleLine> = emptyList(),
    ) = LoanScheduleRequest(
        LoanScheduleRevisionId(stableId(22_000 + seed)),
        (1..count).map { LoanScheduleItemId(stableId(23_000 + seed * 200 + it)) },
        1,
        ScheduleRevisionReason.INITIAL,
        Instant.parse("2026-01-01T00:00:00Z"),
        BookCommitId(stableId(24_000 + seed)),
        terms,
        principal,
        count,
        LocalDate.of(2026, 1, 31),
        0L,
        customLines,
    )

    private fun terms(method: LoanRepaymentMethod, seed: Long) = LoanTermsRevision(
        LoanTermsRevisionId(stableId(25_000 + seed)),
        LoanTrancheId(stableId(26_000 + seed)),
        1,
        method,
        LoanRateType.FIXED,
        PaymentFrequency.MONTHLY,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2036, 12, 31),
        RoundingMode.HALF_EVEN,
        LoanPrepaymentPolicy.ALLOWED,
        PrepaymentRecalculationStrategy.SHORTEN_TERM,
        null,
        BookCommitId(stableId(27_000 + seed)),
        listOf(period("0.036", LocalDate.of(2026, 1, 1), null)),
    )

    private fun period(
        annual: String,
        from: LocalDate,
        to: LocalDate?,
        benchmark: String? = null,
        margin: String? = null,
    ) = LoanRatePeriod(from, to, rate(annual), benchmark, margin?.let(::rate))

    private fun rate(value: String): InterestRate = InterestRate.of(BigDecimal(value)).success()

    private fun allocation(
        trancheId: LoanTrancheId,
        component: LoanPaymentComponent,
        amount: Long,
    ) = LoanActualAllocation(
        TransactionId(stableId(28_000)),
        TransactionRevisionId(stableId(28_001)),
        trancheId,
        null,
        component,
        positive(amount, PlannerFixtures.jpy),
        positive(amount, PlannerFixtures.jpy),
        null,
    )

    private fun <T> DomainResult<T>.success(): T = (this as DomainResult.Success<T>).value
}
