package app.ledger.finance.domain

import app.ledger.core.common.CheckedArithmetic
import app.ledger.core.common.DomainResult
import java.math.BigDecimal
import java.math.RoundingMode

/** Closed allocation modes from the frozen settlement contract. */
enum class SettlementSplitMethod {
    EQUAL,
    FIXED_AMOUNT,
    PERCENTAGE,
    WEIGHT,
}

enum class SettlementChargeDistribution {
    SAME_AS_BASE,
    EQUAL,
    PAYER,
    SPECIFIED,
}

enum class SettlementRoundingRule {
    PARTICIPANT_ORDER,
    PAYER,
    SELF,
    LARGEST_SHARE,
}

data class SettlementParticipantAllocation(
    val participantId: ParticipantId,
    val included: Boolean = true,
    val fixedMinor: Long? = null,
    val percentage: BigDecimal? = null,
    val weight: BigDecimal? = null,
    val chargeMinor: Long? = null,
) {
    init {
        require(fixedMinor == null || fixedMinor >= 0L)
        require(percentage == null || percentage.signum() >= 0)
        require(weight == null || weight.signum() > 0)
        require(chargeMinor == null || chargeMinor >= 0L)
    }
}

data class SettlementAllocationRequest(
    val totalMinor: Long,
    val payerParticipantId: ParticipantId,
    val selfParticipantId: ParticipantId,
    val participants: List<SettlementParticipantAllocation>,
    val method: SettlementSplitMethod,
    val taxMinor: Long = 0L,
    val serviceFeeMinor: Long = 0L,
    val chargeDistribution: SettlementChargeDistribution = SettlementChargeDistribution.SAME_AS_BASE,
    val roundingRule: SettlementRoundingRule = SettlementRoundingRule.PARTICIPANT_ORDER,
)

data class SettlementAllocationResult(
    val shares: List<SettlementShare>,
    val baseMinor: Long,
    val chargeMinor: Long,
    val selfOwedMinor: Long,
    val othersOwedMinor: Long,
)

/**
 * Exact-minor allocation. BigDecimal is used only to express percentages/weights; every emitted
 * amount is an integer minor unit and every remainder is deterministically assigned.
 */
object SettlementAllocationPolicy {
    @Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun allocate(request: SettlementAllocationRequest): DomainResult<SettlementAllocationResult> = try {
        if (
            request.totalMinor <= 0L || request.taxMinor < 0L || request.serviceFeeMinor < 0L ||
            request.participants.size < 2 ||
            request.participants.map { it.participantId }.toSet().size != request.participants.size ||
            request.participants.none { it.participantId == request.payerParticipantId } ||
            request.participants.none { it.participantId == request.selfParticipantId }
        ) {
            return invalid("settlement.allocation.participants")
        }
        val included = request.participants.filter(SettlementParticipantAllocation::included)
        if (included.isEmpty()) return invalid("settlement.allocation.exclusions")
        val charges = Math.addExact(request.taxMinor, request.serviceFeeMinor)
        val base = Math.subtractExact(request.totalMinor, charges)
        if (base < 0L) return invalid("settlement.allocation.charges")

        val baseWeights = when (request.method) {
            SettlementSplitMethod.EQUAL -> included.map { BigDecimal.ONE }
            SettlementSplitMethod.FIXED_AMOUNT -> emptyList()
            SettlementSplitMethod.PERCENTAGE -> included.map { it.percentage ?: return invalid("settlement.allocation.percentage") }
            SettlementSplitMethod.WEIGHT -> included.map { it.weight ?: return invalid("settlement.allocation.weight") }
        }
        val baseAllocation = if (request.method == SettlementSplitMethod.FIXED_AMOUNT) {
            val fixed = included.map { it.fixedMinor ?: return invalid("settlement.allocation.fixed") }
            if (CheckedArithmetic.sum(fixed).successOrNull() != base) return invalid("settlement.allocation.fixedTotal")
            ExactAllocation(fixed, List(fixed.size) { 0L })
        } else {
            if (request.method == SettlementSplitMethod.PERCENTAGE && baseWeights.sumBigDecimal().compareTo(HUNDRED) != 0) {
                return invalid("settlement.allocation.percentageTotal")
            }
            proportional(base, baseWeights, residualOrder(request, included, baseWeights))
        }

        val chargeAllocation = when (request.chargeDistribution) {
            SettlementChargeDistribution.SAME_AS_BASE -> proportional(
                charges,
                baseAllocation.amounts.map { BigDecimal.valueOf(it) }.takeIf { values -> values.any { it.signum() > 0 } }
                    ?: included.map { BigDecimal.ONE },
                residualOrder(request, included, baseAllocation.amounts.map { BigDecimal.valueOf(it) }),
            )
            SettlementChargeDistribution.EQUAL -> proportional(
                charges,
                included.map { BigDecimal.ONE },
                residualOrder(request, included, included.map { BigDecimal.ONE }),
            )
            SettlementChargeDistribution.PAYER -> ExactAllocation(
                included.map { if (it.participantId == request.payerParticipantId) charges else 0L },
                List(included.size) { 0L },
            ).also {
                if (included.none { participant -> participant.participantId == request.payerParticipantId }) {
                    return invalid("settlement.allocation.excludedPayer")
                }
            }
            SettlementChargeDistribution.SPECIFIED -> {
                val specified = included.map { it.chargeMinor ?: return invalid("settlement.allocation.specifiedCharge") }
                if (CheckedArithmetic.sum(specified).successOrNull() != charges) {
                    return invalid("settlement.allocation.specifiedChargeTotal")
                }
                ExactAllocation(specified, List(specified.size) { 0L })
            }
        }

        val owedByParticipant = included.indices.associate { index ->
            included[index].participantId to Math.addExact(baseAllocation.amounts[index], chargeAllocation.amounts[index])
        }
        val roundingByParticipant = included.indices.associate { index ->
            included[index].participantId to Math.addExact(baseAllocation.rounding[index], chargeAllocation.rounding[index])
        }
        val shares = request.participants.map { participant ->
            SettlementShare(
                participant.participantId,
                if (participant.participantId == request.payerParticipantId) request.totalMinor else 0L,
                owedByParticipant[participant.participantId] ?: 0L,
                participant.weight?.stripTrailingZeros(),
                roundingByParticipant[participant.participantId] ?: 0L,
            )
        }
        val validation = SettlementSharePolicy.validate(request.totalMinor, shares)
        if (validation is DomainResult.Failure) return validation
        val selfOwed = shares.single { it.participantId == request.selfParticipantId }.owedMinor
        DomainResult.Success(
            SettlementAllocationResult(
                shares,
                base,
                charges,
                selfOwed,
                Math.subtractExact(request.totalMinor, selfOwed),
            ),
        )
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("settlement.allocation"))
    }

    private fun proportional(amount: Long, weights: List<BigDecimal>, order: List<Int>): ExactAllocation {
        require(amount >= 0L && weights.isNotEmpty() && weights.all { it.signum() >= 0 })
        val totalWeight = weights.sumBigDecimal()
        require(totalWeight.signum() > 0 || amount == 0L)
        if (amount == 0L) return ExactAllocation(List(weights.size) { 0L }, List(weights.size) { 0L })
        val floors = weights.map { weight ->
            BigDecimal.valueOf(amount).multiply(weight).divide(totalWeight, 0, RoundingMode.DOWN).longValueExact()
        }.toMutableList()
        val floorTotal = CheckedArithmetic.sum(floors).successOrThrow()
        var residual = Math.subtractExact(amount, floorTotal)
        val rounding = MutableList(weights.size) { 0L }
        var cursor = 0
        while (residual > 0L) {
            val index = order[cursor % order.size]
            floors[index] = Math.addExact(floors[index], 1L)
            rounding[index] = Math.addExact(rounding[index], 1L)
            residual--
            cursor++
        }
        return ExactAllocation(floors, rounding)
    }

    private fun residualOrder(
        request: SettlementAllocationRequest,
        included: List<SettlementParticipantAllocation>,
        weights: List<BigDecimal>,
    ): List<Int> {
        val preferred = when (request.roundingRule) {
            SettlementRoundingRule.PARTICIPANT_ORDER -> null
            SettlementRoundingRule.PAYER -> request.payerParticipantId
            SettlementRoundingRule.SELF -> request.selfParticipantId
            SettlementRoundingRule.LARGEST_SHARE -> null
        }
        return when {
            request.roundingRule == SettlementRoundingRule.LARGEST_SHARE -> listOf(
                included.indices.maxWith(compareBy<Int> { weights.getOrElse(it) { BigDecimal.ZERO } }.thenByDescending { -it }),
            )
            preferred != null -> listOf(included.indexOfFirst { it.participantId == preferred }).filter { it >= 0 }
            else -> included.indices.toList()
        }
    }

    private data class ExactAllocation(val amounts: List<Long>, val rounding: List<Long>)

    private val HUNDRED = BigDecimal("100")
}

data class SettlementTransferSuggestion(
    val payerParticipantId: ParticipantId,
    val payeeParticipantId: ParticipantId,
    val amountMinor: Long,
)

/** Domain-owned deterministic suggestion; UI may display/prefill it but never writes its own math. */
object SettlementSuggestionPolicy {
    fun suggest(netPositions: Map<ParticipantId, Long>): DomainResult<List<SettlementTransferSuggestion>> = try {
        val total = CheckedArithmetic.sum(netPositions.values).successOrNull()
            ?: return DomainResult.Failure(DomainViolation.NumericOverflow("settlement.suggestion"))
        if (total != 0L) return invalid("settlement.suggestion.balance")
        val debtors = netPositions.filterValues { it < 0L }.entries
            .sortedBy { it.key.value.toString() }
            .map { MutablePosition(it.key, Math.negateExact(it.value)) }
        val creditors = netPositions.filterValues { it > 0L }.entries
            .sortedBy { it.key.value.toString() }
            .map { MutablePosition(it.key, it.value) }
        val suggestions = mutableListOf<SettlementTransferSuggestion>()
        var debtorIndex = 0
        var creditorIndex = 0
        while (debtorIndex < debtors.size && creditorIndex < creditors.size) {
            val debtor = debtors[debtorIndex]
            val creditor = creditors[creditorIndex]
            val amount = minOf(debtor.remaining, creditor.remaining)
            if (amount > 0L) suggestions += SettlementTransferSuggestion(debtor.id, creditor.id, amount)
            debtor.remaining = Math.subtractExact(debtor.remaining, amount)
            creditor.remaining = Math.subtractExact(creditor.remaining, amount)
            if (debtor.remaining == 0L) debtorIndex++
            if (creditor.remaining == 0L) creditorIndex++
        }
        DomainResult.Success(suggestions)
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("settlement.suggestion"))
    }

    private data class MutablePosition(val id: ParticipantId, var remaining: Long)
}

private fun List<BigDecimal>.sumBigDecimal(): BigDecimal = fold(BigDecimal.ZERO, BigDecimal::add)

private fun <T> DomainResult<T>.successOrNull(): T? = (this as? DomainResult.Success<T>)?.value

private fun <T> DomainResult<T>.successOrThrow(): T = (this as? DomainResult.Success<T>)?.value
    ?: throw ArithmeticException("checked arithmetic failed")

private fun <T> invalid(field: String): DomainResult<T> = DomainResult.Failure(DomainViolation.InvalidField(field))
