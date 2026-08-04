package app.ledger.finance.domain

import app.ledger.core.common.CheckedArithmetic
import app.ledger.core.common.DomainResult
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.LocalDate

data class InstallmentScheduleRequest(
    val planId: InstallmentPlanId,
    val scheduleRevisionId: InstallmentScheduleRevisionId,
    val scheduleItemIds: List<InstallmentScheduleItemId>,
    val revisionNumber: Int,
    val reason: ScheduleRevisionReason,
    val generatedAt: Instant,
    val createdCommitId: BookCommitId,
    val principalMinor: Long,
    val termCount: Int,
    val firstStatementDate: LocalDate,
    val terms: InstallmentPlanRevision,
) {
    init {
        require(principalMinor > 0L)
        require(termCount > 0)
        require(scheduleItemIds.size == termCount)
        require(scheduleItemIds.toSet().size == scheduleItemIds.size)
        require(terms.planId == planId)
        require(revisionNumber > 0)
    }
}

data class InstallmentCostSummary(
    val principalMinor: Long,
    val interestMinor: Long,
    val feeMinor: Long,
    val totalMinor: Long,
    val actualAnnualRate: InterestRate,
)

data class InstallmentProgress(
    val currentPrincipalMinor: Long,
    val postedPrincipalMinor: Long,
    val unpostedCommittedPrincipalMinor: Long,
    val paidCostMinor: Long,
    val futureCostMinor: Long,
    val nextStatementDate: LocalDate?,
) {
    init {
        require(currentPrincipalMinor >= 0L)
        require(postedPrincipalMinor >= 0L)
        require(unpostedCommittedPrincipalMinor >= 0L)
        require(paidCostMinor >= 0L && futureCostMinor >= 0L)
        require(Math.addExact(postedPrincipalMinor, unpostedCommittedPrincipalMinor) == currentPrincipalMinor)
    }
}

data class InstallmentSettlementSimulation(
    val planId: InstallmentPlanId,
    val scheduleRevisionId: InstallmentScheduleRevisionId,
    val settlementDate: LocalDate,
    val outstandingPrincipalMinor: Long,
    val futureInterestMinor: Long,
    val futureFeeMinor: Long,
    val settlementFeeMinor: Long,
    val paymentMinor: Long,
    val savedCostMinor: Long,
    val allowed: Boolean,
) {
    val futureCostMinor: Long
        get() = Math.addExact(futureInterestMinor, futureFeeMinor)

    init {
        require(outstandingPrincipalMinor >= 0L)
        require(futureInterestMinor >= 0L && futureFeeMinor >= 0L && settlementFeeMinor >= 0L)
        require(paymentMinor == Math.addExact(outstandingPrincipalMinor, settlementFeeMinor))
    }
}

data class InstallmentRefundRecalculation(
    val allocation: InstallmentRefundAllocation,
    val currentPrincipalMinor: Long,
    val replacementSchedule: InstallmentScheduleRevision,
)

data class InstallmentPlanMutation(
    val plan: InstallmentPlan,
    val expectedRevisionId: InstallmentPlanRevisionId?,
    val revision: InstallmentPlanRevision,
    val scheduleRevision: InstallmentScheduleRevision,
    val currentPrincipalMinor: Long,
    val settlementTransactionId: TransactionId? = null,
    val refundAllocation: InstallmentRefundAllocation? = null,
) {
    init {
        require(plan.currentRevisionId == revision.id)
        require(revision.planId == plan.id)
        require(scheduleRevision.planId == plan.id)
        require(currentPrincipalMinor >= 0L && currentPrincipalMinor <= plan.originalPrincipalMinor)
        require((plan.status == InstallmentStatus.SETTLED) == (currentPrincipalMinor == 0L))
        require(refundAllocation == null || refundAllocation.planId == plan.id)
        require(settlementTransactionId == null || plan.status == InstallmentStatus.SETTLED)
        require(InstallmentSchedulePolicy.validate(currentPrincipalMinor, scheduleRevision.items) is DomainResult.Success)
    }
}

/** Pure deterministic installment schedule, simulation and refund rules. */
object InstallmentAccountingPolicy {
    fun generate(request: InstallmentScheduleRequest): DomainResult<InstallmentScheduleRevision> = try {
        val basePrincipal = request.principalMinor / request.termCount
        var remaining = request.principalMinor
        val items = request.scheduleItemIds.mapIndexed { index, id ->
            val number = index + 1
            val principal = if (number == request.termCount) remaining else basePrincipal
            val interest = interestFor(request.terms, remaining)
            val fee = feeFor(request.terms, remaining, number)
            remaining = Math.subtractExact(remaining, principal)
            InstallmentScheduleItem(
                id = id,
                installmentNumber = number,
                statementDate = request.firstStatementDate.plusMonths(index.toLong()),
                principalMinor = principal,
                interestMinor = interest,
                feeMinor = fee,
                remainingPrincipalMinor = remaining,
            )
        }
        val schedule = InstallmentScheduleRevision(
            request.scheduleRevisionId,
            request.planId,
            request.revisionNumber,
            request.reason,
            request.generatedAt,
            request.createdCommitId,
            items,
        )
        when (val valid = InstallmentSchedulePolicy.validate(request.principalMinor, items)) {
            is DomainResult.Success -> DomainResult.Success(schedule)
            is DomainResult.Failure -> valid
        }
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("installment.schedule"))
    }

    fun summarize(schedule: InstallmentScheduleRevision): DomainResult<InstallmentCostSummary> = try {
        val principal = sum(schedule.items.map(InstallmentScheduleItem::principalMinor))
        val interest = sum(schedule.items.map(InstallmentScheduleItem::interestMinor))
        val fee = sum(schedule.items.map(InstallmentScheduleItem::feeMinor))
        val cost = Math.addExact(interest, fee)
        val total = Math.addExact(principal, cost)
        val rate = if (principal == 0L || schedule.items.isEmpty()) {
            BigDecimal.ZERO
        } else {
            BigDecimal.valueOf(cost)
                .multiply(MONTHS_PER_YEAR)
                .divide(BigDecimal.valueOf(principal), MathContext.DECIMAL128)
                .divide(BigDecimal.valueOf(schedule.items.size.toLong()), MathContext.DECIMAL128)
        }
        DomainResult.Success(InstallmentCostSummary(principal, interest, fee, total, InterestRate.of(rate).valueOrThrow()))
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("installment.cost"))
    }

    fun progress(
        currentPrincipalMinor: Long,
        schedule: InstallmentScheduleRevision,
        asOfDate: LocalDate,
    ): DomainResult<InstallmentProgress> = try {
        val posted = sum(schedule.items.filter { it.statementDate <= asOfDate }.map(InstallmentScheduleItem::principalMinor))
        val unposted = Math.subtractExact(currentPrincipalMinor, posted)
        if (posted < 0L || unposted < 0L) {
            DomainResult.Failure(DomainViolation.InvalidField("installment.progress"))
        } else {
            val paidCost = sum(schedule.items.filter { it.statementDate <= asOfDate }.map { Math.addExact(it.interestMinor, it.feeMinor) })
            val futureCost = sum(schedule.items.filter { it.statementDate > asOfDate }.map { Math.addExact(it.interestMinor, it.feeMinor) })
            DomainResult.Success(
                InstallmentProgress(
                    currentPrincipalMinor,
                    posted,
                    unposted,
                    paidCost,
                    futureCost,
                    schedule.items.firstOrNull { it.statementDate > asOfDate }?.statementDate,
                ),
            )
        }
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("installment.progress"))
    }

    fun simulateSettlement(
        plan: InstallmentPlan,
        terms: InstallmentPlanRevision,
        schedule: InstallmentScheduleRevision,
        settlementDate: LocalDate,
    ): DomainResult<InstallmentSettlementSimulation> = try {
        val future = schedule.items.filter { it.statementDate > settlementDate }
        val outstanding = sum(future.map(InstallmentScheduleItem::principalMinor))
        val futureInterest = sum(future.map(InstallmentScheduleItem::interestMinor))
        val futureFees = sum(future.map(InstallmentScheduleItem::feeMinor))
        val allowed = terms.prepaymentPolicy != InstallmentPrepaymentPolicy.NOT_ALLOWED && outstanding > 0L
        val settlementFee = if (allowed && terms.prepaymentPolicy == InstallmentPrepaymentPolicy.ALLOWED_WITH_FEE) {
            requireNotNull(terms.prepaymentFeeMinor)
        } else {
            0L
        }
        val payment = Math.addExact(outstanding, settlementFee)
        val saved = Math.subtractExact(Math.addExact(futureInterest, futureFees), settlementFee)
        DomainResult.Success(
            InstallmentSettlementSimulation(
                plan.id,
                schedule.id,
                settlementDate,
                outstanding,
                futureInterest,
                futureFees,
                settlementFee,
                payment,
                saved,
                allowed,
            ),
        )
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("installment.settlement"))
    }

    fun recalculateAfterRefund(
        request: InstallmentScheduleRequest,
        allocation: InstallmentRefundAllocation,
    ): DomainResult<InstallmentRefundRecalculation> = try {
        val currentPrincipal = Math.subtractExact(request.principalMinor, allocation.principalMinor)
        if (currentPrincipal <= 0L) {
            return DomainResult.Failure(DomainViolation.InvalidField("installment.refund.principal"))
        }
        val adjustedRequest = request.copy(
            principalMinor = currentPrincipal,
            reason = ScheduleRevisionReason.REFUND,
        )
        when (val generated = generate(adjustedRequest)) {
            is DomainResult.Failure -> generated
            is DomainResult.Success -> DomainResult.Success(
                InstallmentRefundRecalculation(allocation, currentPrincipal, generated.value),
            )
        }
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("installment.refund"))
    }

    private fun interestFor(terms: InstallmentPlanRevision, remainingMinor: Long): Long = if (terms.feeRateType == InstallmentFeeRateType.EFFECTIVE_ANNUAL_RATE) {
        rateAmount(remainingMinor, requireNotNull(terms.effectiveAnnualRate), terms)
    } else {
        0L
    }

    private fun feeFor(terms: InstallmentPlanRevision, remainingMinor: Long, installmentNumber: Int): Long = when (terms.feeRateType) {
        InstallmentFeeRateType.NONE, InstallmentFeeRateType.EFFECTIVE_ANNUAL_RATE -> 0L
        InstallmentFeeRateType.FIXED_PER_TERM -> requireNotNull(terms.fixedFeePerTermMinor)
        InstallmentFeeRateType.FIRST_TERM_FIXED -> if (installmentNumber == 1) requireNotNull(terms.firstTermFeeMinor) else 0L
        InstallmentFeeRateType.REMAINING_PRINCIPAL_RATE -> rateAmount(
            remainingMinor,
            requireNotNull(terms.remainingPrincipalRate),
            terms,
        )
    }

    private fun rateAmount(
        remainingMinor: Long,
        rate: InterestRate,
        terms: InstallmentPlanRevision,
    ): Long = BigDecimal.valueOf(remainingMinor)
        .multiply(rate.annualDecimal)
        .divide(MONTHS_PER_YEAR, MathContext.DECIMAL128)
        .setScale(0, terms.roundingMode)
        .longValueExact()

    private fun sum(values: List<Long>): Long = when (val result = CheckedArithmetic.sum(values)) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> throw ArithmeticException("installment sum overflow")
    }

    private fun <T> DomainResult<T>.valueOrThrow(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> throw ArithmeticException("invalid derived installment value")
    }

    private val MONTHS_PER_YEAR = BigDecimal.valueOf(MONTH_COUNT_PER_YEAR)
    private const val MONTH_COUNT_PER_YEAR = 12L
}
