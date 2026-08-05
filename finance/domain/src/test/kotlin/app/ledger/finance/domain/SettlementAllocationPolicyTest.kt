package app.ledger.finance.domain

import app.ledger.core.common.DomainResult
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.random.Random

@Suppress("LongParameterList")
class SettlementAllocationPolicyTest {
    private val self = ParticipantId(stableId(91_001L))
    private val friend = ParticipantId(stableId(91_002L))
    private val colleague = ParticipantId(stableId(91_003L))

    @Test
    fun `all closed allocation modes conserve paid owed charges and deterministic remainder`() {
        val equal = allocate(
            total = 103L,
            method = SettlementSplitMethod.EQUAL,
            tax = 2L,
            fee = 2L,
            rounding = SettlementRoundingRule.SELF,
        )
        equal.shares.sumOf(SettlementShare::paidMinor) shouldBe 103L
        equal.shares.sumOf(SettlementShare::owedMinor) shouldBe 103L
        equal.shares.single { it.participantId == self }.owedMinor shouldBe 35L

        allocate(
            total = 110L,
            method = SettlementSplitMethod.FIXED_AMOUNT,
            tax = 10L,
            participants = participants(fixed = listOf(40L, 30L, 30L), charges = listOf(5L, 3L, 2L)),
            distribution = SettlementChargeDistribution.SPECIFIED,
        ).shares.map(SettlementShare::owedMinor) shouldBe listOf(45L, 33L, 32L)

        allocate(
            total = 101L,
            method = SettlementSplitMethod.PERCENTAGE,
            participants = participants(percentages = listOf("50", "30", "20")),
            rounding = SettlementRoundingRule.LARGEST_SHARE,
        ).shares.map(SettlementShare::owedMinor) shouldBe listOf(51L, 30L, 20L)

        allocate(
            total = 101L,
            method = SettlementSplitMethod.WEIGHT,
            participants = participants(weights = listOf("3", "2", "1")),
            rounding = SettlementRoundingRule.PAYER,
        ).shares.map(SettlementShare::owedMinor) shouldBe listOf(52L, 33L, 16L)
    }

    @Test
    fun `exclusion and payer charge rules remain exact and invalid combinations are rejected`() {
        val excluded = participants().mapIndexed { index, item -> if (index == 2) item.copy(included = false) else item }
        val result = allocate(
            total = 105L,
            method = SettlementSplitMethod.EQUAL,
            tax = 5L,
            participants = excluded,
            distribution = SettlementChargeDistribution.PAYER,
        )
        result.shares.map(SettlementShare::owedMinor) shouldBe listOf(55L, 50L, 0L)

        val invalidPercentage = SettlementAllocationPolicy.allocate(
            request(
                total = 100L,
                method = SettlementSplitMethod.PERCENTAGE,
                participants = participants(percentages = listOf("50", "25", "20")),
            ),
        )
        (invalidPercentage is DomainResult.Failure).shouldBeTrue()
    }

    @Test
    fun `property allocation and settlement suggestions conserve every participant position`() {
        val random = Random(22_043)
        repeat(2_000) {
            val total = random.nextLong(1L, 1_000_000_000L)
            val result = allocate(total, SettlementSplitMethod.WEIGHT, participants = participants(weights = listOf("7", "3", "2")))
            result.shares.sumOf(SettlementShare::paidMinor) shouldBe total
            result.shares.sumOf(SettlementShare::owedMinor) shouldBe total
            result.shares.sumOf { share -> Math.subtractExact(share.paidMinor, share.owedMinor) } shouldBe 0L
        }

        val suggestions = SettlementSuggestionPolicy.suggest(
            mapOf(self to -80L, friend to 30L, colleague to 50L),
        ).success()
        suggestions shouldHaveSize 2
        suggestions.sumOf(SettlementTransferSuggestion::amountMinor) shouldBe 80L
        suggestions.all { it.payerParticipantId == self }.shouldBeTrue()
    }

    private fun allocate(
        total: Long,
        method: SettlementSplitMethod,
        tax: Long = 0L,
        fee: Long = 0L,
        participants: List<SettlementParticipantAllocation> = participants(),
        distribution: SettlementChargeDistribution = SettlementChargeDistribution.SAME_AS_BASE,
        rounding: SettlementRoundingRule = SettlementRoundingRule.PARTICIPANT_ORDER,
    ): SettlementAllocationResult = SettlementAllocationPolicy.allocate(
        request(total, method, tax, fee, participants, distribution, rounding),
    ).success()

    private fun request(
        total: Long,
        method: SettlementSplitMethod,
        tax: Long = 0L,
        fee: Long = 0L,
        participants: List<SettlementParticipantAllocation> = participants(),
        distribution: SettlementChargeDistribution = SettlementChargeDistribution.SAME_AS_BASE,
        rounding: SettlementRoundingRule = SettlementRoundingRule.PARTICIPANT_ORDER,
    ) = SettlementAllocationRequest(total, self, self, participants, method, tax, fee, distribution, rounding)

    private fun participants(
        fixed: List<Long?> = listOf(null, null, null),
        percentages: List<String?> = listOf(null, null, null),
        weights: List<String?> = listOf(null, null, null),
        charges: List<Long?> = listOf(null, null, null),
    ): List<SettlementParticipantAllocation> = listOf(self, friend, colleague).mapIndexed { index, id ->
        SettlementParticipantAllocation(
            id,
            fixedMinor = fixed[index],
            percentage = percentages[index]?.let(::BigDecimal),
            weight = weights[index]?.let(::BigDecimal),
            chargeMinor = charges[index],
        )
    }
}
