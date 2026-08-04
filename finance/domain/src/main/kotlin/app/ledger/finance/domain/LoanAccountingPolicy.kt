@file:Suppress("LongMethod", "LongParameterList", "MagicNumber", "MaxLineLength", "TooManyFunctions")

package app.ledger.finance.domain

import app.ledger.core.common.CheckedArithmetic
import app.ledger.core.common.DomainResult
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

data class LoanCustomScheduleLine(
    val plannedDate: LocalDate,
    val principalMinor: Long,
    val interestMinor: Long,
    val feeMinor: Long,
) {
    init {
        require(principalMinor >= 0L && interestMinor >= 0L && feeMinor >= 0L)
        require(principalMinor > 0L || interestMinor > 0L || feeMinor > 0L)
    }
}

data class LoanScheduleRequest(
    val scheduleRevisionId: LoanScheduleRevisionId,
    val scheduleItemIds: List<LoanScheduleItemId>,
    val revisionNumber: Int,
    val reason: ScheduleRevisionReason,
    val generatedAt: java.time.Instant,
    val createdCommitId: BookCommitId,
    val terms: LoanTermsRevision,
    val remainingPrincipalMinor: Long,
    val paymentCount: Int,
    val firstPaymentDate: LocalDate,
    val feePerPaymentMinor: Long = 0L,
    val customLines: List<LoanCustomScheduleLine> = emptyList(),
) {
    init {
        require(revisionNumber > 0)
        require(remainingPrincipalMinor > 0L)
        require(paymentCount > 0)
        require(scheduleItemIds.size == paymentCount)
        require(scheduleItemIds.toSet().size == scheduleItemIds.size)
        require(feePerPaymentMinor >= 0L)
        require(
            (terms.repaymentMethod == LoanRepaymentMethod.CUSTOM) == customLines.isNotEmpty(),
        )
        require(customLines.isEmpty() || customLines.size == paymentCount)
    }
}

data class LoanScheduleSummary(
    val principalMinor: Long,
    val interestMinor: Long,
    val feeMinor: Long,
    val totalPaymentMinor: Long,
    val paymentCount: Int,
    val endDate: LocalDate,
)

data class LoanPaymentComponentTotals(
    val principalMinor: Long,
    val interestMinor: Long,
    val feeMinor: Long,
    val penaltyMinor: Long,
) {
    val totalMinor: Long
        get() = Math.addExact(
            Math.addExact(principalMinor, interestMinor),
            Math.addExact(feeMinor, penaltyMinor),
        )

    init {
        require(principalMinor >= 0L && interestMinor >= 0L && feeMinor >= 0L && penaltyMinor >= 0L)
        require(totalMinor > 0L)
    }
}

data class LoanPrepaymentSimulation(
    val contractId: LoanContractId,
    val trancheId: LoanTrancheId,
    val baseScheduleRevisionId: LoanScheduleRevisionId,
    val scenario: LoanSimulationScenario,
    val remainingPrincipalBeforeMinor: Long,
    val prepaymentPrincipalMinor: Long,
    val penaltyMinor: Long,
    val paymentNowMinor: Long,
    val before: LoanScheduleSummary,
    val after: LoanScheduleRevision,
    val afterSummary: LoanScheduleSummary,
) {
    val savedInterestAndFeeMinor: Long
        get() = Math.subtractExact(
            Math.addExact(before.interestMinor, before.feeMinor),
            Math.addExact(Math.addExact(afterSummary.interestMinor, afterSummary.feeMinor), penaltyMinor),
        )
}

data class LoanTrancheMutation(
    val tranche: LoanTranche,
    val expectedTermsRevisionId: LoanTermsRevisionId?,
    val termsRevision: LoanTermsRevision,
    val scheduleRevision: LoanScheduleRevision,
) {
    init {
        require(tranche.id == termsRevision.trancheId)
        require(tranche.id == scheduleRevision.trancheId)
        require(scheduleRevision.termsRevisionId == termsRevision.id)
        require(LoanRatePeriodPolicy.validate(termsRevision.ratePeriods) is DomainResult.Success)
        require(
            if (scheduleRevision.items.isEmpty()) {
                tranche.status == LoanStatus.PAID_OFF
            } else {
                LoanSchedulePolicy.validate(
                    scheduleRevision.items.sumOfChecked(LoanScheduleItem::principalMinor),
                    scheduleRevision.items,
                ) is DomainResult.Success
            },
        )
    }
}

data class LoanContractMutation(
    val contract: LoanContract,
    val expectedLastCommitId: BookCommitId?,
    val tranches: List<LoanTrancheMutation>,
) {
    init {
        require(tranches.isNotEmpty())
        require(tranches.map { it.tranche.id } == contract.trancheIds)
        require(tranches.all { it.tranche.contractId == contract.id })
        require(tranches.map { it.tranche.ledgerAccountId }.toSet().size == tranches.size)
    }
}

/** Deterministic, checked-integer loan schedule, payment and prepayment policy. */
object LoanAccountingPolicy {
    fun generate(request: LoanScheduleRequest): DomainResult<LoanScheduleRevision> = try {
        val dates = paymentDates(request)
        val items = when (request.terms.repaymentMethod) {
            LoanRepaymentMethod.EQUAL_PAYMENT -> equalPayment(request, dates)
            LoanRepaymentMethod.EQUAL_PRINCIPAL -> equalPrincipal(request, dates)
            LoanRepaymentMethod.INTEREST_ONLY_THEN_PRINCIPAL -> interestOnly(request, dates)
            LoanRepaymentMethod.BULLET -> bullet(request, dates)
            LoanRepaymentMethod.CUSTOM -> custom(request)
        }
        val revision = LoanScheduleRevision(
            request.scheduleRevisionId,
            request.terms.trancheId,
            request.revisionNumber,
            request.terms.id,
            request.reason,
            request.generatedAt,
            request.createdCommitId,
            items,
        )
        when (val valid = LoanSchedulePolicy.validate(request.remainingPrincipalMinor, items)) {
            is DomainResult.Success -> DomainResult.Success(revision)
            is DomainResult.Failure -> valid
        }
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("loan.schedule"))
    } catch (_: IllegalArgumentException) {
        DomainResult.Failure(DomainViolation.InvalidField("loan.schedule"))
    }

    fun summarize(schedule: LoanScheduleRevision): DomainResult<LoanScheduleSummary> = try {
        if (schedule.items.isEmpty()) {
            DomainResult.Failure(DomainViolation.InvalidField("loan.schedule.empty"))
        } else {
            val principal = schedule.items.sumOfChecked(LoanScheduleItem::principalMinor)
            val interest = schedule.items.sumOfChecked(LoanScheduleItem::interestMinor)
            val fee = schedule.items.sumOfChecked(LoanScheduleItem::feeMinor)
            DomainResult.Success(
                LoanScheduleSummary(
                    principal,
                    interest,
                    fee,
                    Math.addExact(principal, Math.addExact(interest, fee)),
                    schedule.items.size,
                    schedule.items.last().plannedDate,
                ),
            )
        }
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("loan.schedule.summary"))
    }

    fun validatePayment(
        paymentMinor: Long,
        remainingPrincipalByTranche: Map<LoanTrancheId, Long>,
        allocations: List<LoanActualAllocation>,
    ): DomainResult<LoanPaymentComponentTotals> = try {
        if (paymentMinor <= 0L || allocations.isEmpty()) {
            return DomainResult.Failure(DomainViolation.InvalidField("loan.payment.allocations"))
        }
        val amounts = LoanPaymentComponent.entries.associateWith { component ->
            allocations.filter { it.component == component }.map { it.amount.minor.value }.sumChecked()
        }
        remainingPrincipalByTranche.forEach { (tranche, remaining) ->
            val principal = allocations.filter {
                it.trancheId == tranche && it.component == LoanPaymentComponent.PRINCIPAL
            }.map { it.amount.minor.value }.sumChecked()
            if (remaining < 0L || principal > remaining) {
                return DomainResult.Failure(DomainViolation.InvalidField("loan.payment.principalExceeded"))
            }
        }
        if (allocations.any { it.trancheId !in remainingPrincipalByTranche }) {
            return DomainResult.Failure(DomainViolation.InvalidField("loan.payment.tranche"))
        }
        val totals = LoanPaymentComponentTotals(
            amounts.getValue(LoanPaymentComponent.PRINCIPAL),
            amounts.getValue(LoanPaymentComponent.INTEREST),
            amounts.getValue(LoanPaymentComponent.FEE),
            amounts.getValue(LoanPaymentComponent.PENALTY),
        )
        if (totals.totalMinor == paymentMinor) {
            DomainResult.Success(totals)
        } else {
            DomainResult.Failure(DomainViolation.InvalidField("loan.payment.sumMismatch"))
        }
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("loan.payment"))
    }

    fun simulatePrepayment(
        contractId: LoanContractId,
        currentSchedule: LoanScheduleRevision,
        terms: LoanTermsRevision,
        scenario: LoanSimulationScenario,
        replacementRequest: LoanScheduleRequest,
    ): DomainResult<LoanPrepaymentSimulation> = try {
        if (terms.prepaymentPolicy == LoanPrepaymentPolicy.NOT_ALLOWED) {
            return DomainResult.Failure(DomainViolation.InvalidField("loan.prepayment.notAllowed"))
        }
        val remainingBefore = currentSchedule.items.firstOrNull()?.let {
            Math.addExact(it.principalMinor, it.remainingPrincipalMinor)
        } ?: return DomainResult.Failure(DomainViolation.InvalidField("loan.prepayment.empty"))
        val principal = when (scenario) {
            is LoanSimulationScenario.PartialPrepayment -> scenario.amountMinor
            is LoanSimulationScenario.FullSettlement -> remainingBefore
            is LoanSimulationScenario.RateChange -> 0L
        }
        val emptyPartial = scenario is LoanSimulationScenario.PartialPrepayment && principal == 0L
        if (principal < 0L || principal > remainingBefore || emptyPartial) {
            return DomainResult.Failure(DomainViolation.InvalidField("loan.prepayment.principal"))
        }
        val penalty = if (terms.prepaymentPolicy == LoanPrepaymentPolicy.ALLOWED_WITH_PENALTY) {
            rateMinor(principal, requireNotNull(terms.penaltyRate), terms.roundingMode)
        } else {
            0L
        }
        val afterPrincipal = Math.subtractExact(remainingBefore, principal)
        val after = if (afterPrincipal == 0L) {
            LoanScheduleRevision(
                replacementRequest.scheduleRevisionId,
                terms.trancheId,
                replacementRequest.revisionNumber,
                replacementRequest.terms.id,
                ScheduleRevisionReason.PREPAYMENT,
                replacementRequest.generatedAt,
                replacementRequest.createdCommitId,
                emptyList(),
            )
        } else {
            generate(
                replacementRequest.copy(
                    remainingPrincipalMinor = afterPrincipal,
                    reason = if (scenario is LoanSimulationScenario.RateChange) {
                        ScheduleRevisionReason.RATE_CHANGE
                    } else {
                        ScheduleRevisionReason.PREPAYMENT
                    },
                ),
            ).valueOrThrow()
        }
        val before = summarize(currentSchedule).valueOrThrow()
        val afterSummary = if (after.items.isEmpty()) {
            LoanScheduleSummary(0L, 0L, 0L, 0L, 0, scenario.date())
        } else {
            summarize(after).valueOrThrow()
        }
        DomainResult.Success(
            LoanPrepaymentSimulation(
                contractId,
                terms.trancheId,
                currentSchedule.id,
                scenario,
                remainingBefore,
                principal,
                penalty,
                Math.addExact(principal, penalty),
                before,
                after,
                afterSummary,
            ),
        )
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("loan.prepayment"))
    } catch (_: IllegalArgumentException) {
        DomainResult.Failure(DomainViolation.InvalidField("loan.prepayment"))
    }

    private fun equalPayment(request: LoanScheduleRequest, dates: List<LocalDate>): List<LoanScheduleItem> {
        var remaining = request.remainingPrincipalMinor
        return dates.mapIndexed { index, date ->
            val periodsLeft = dates.size - index
            val rate = periodicRate(rateFor(request.terms, date), request.terms.paymentFrequency)
            val payment = annuityPayment(remaining, rate, periodsLeft, request.terms.roundingMode)
            val interest = decimalMinor(remaining, rate, request.terms.roundingMode)
            val principal = if (periodsLeft == 1) remaining else minOf(remaining, Math.subtractExact(payment, interest))
            remaining = Math.subtractExact(remaining, principal)
            item(request, index, date, principal, interest, remaining)
        }
    }

    private fun equalPrincipal(request: LoanScheduleRequest, dates: List<LocalDate>): List<LoanScheduleItem> {
        var remaining = request.remainingPrincipalMinor
        val base = remaining / dates.size
        return dates.mapIndexed { index, date ->
            val principal = if (index == dates.lastIndex) remaining else base
            val interest = decimalMinor(
                remaining,
                periodicRate(rateFor(request.terms, date), request.terms.paymentFrequency),
                request.terms.roundingMode,
            )
            remaining = Math.subtractExact(remaining, principal)
            item(request, index, date, principal, interest, remaining)
        }
    }

    private fun interestOnly(request: LoanScheduleRequest, dates: List<LocalDate>): List<LoanScheduleItem> {
        var remaining = request.remainingPrincipalMinor
        return dates.mapIndexed { index, date ->
            val interest = decimalMinor(
                remaining,
                periodicRate(rateFor(request.terms, date), request.terms.paymentFrequency),
                request.terms.roundingMode,
            )
            val principal = if (index == dates.lastIndex) remaining else 0L
            remaining = Math.subtractExact(remaining, principal)
            item(request, index, date, principal, interest, remaining)
        }
    }

    private fun bullet(request: LoanScheduleRequest, dates: List<LocalDate>): List<LoanScheduleItem> {
        var remaining = request.remainingPrincipalMinor
        return dates.mapIndexed { index, date ->
            val isLast = index == dates.lastIndex
            val interest = if (isLast) {
                dates.sumOfChecked { plannedDate ->
                    decimalMinor(
                        request.remainingPrincipalMinor,
                        periodicRate(rateFor(request.terms, plannedDate), request.terms.paymentFrequency),
                        request.terms.roundingMode,
                    )
                }
            } else {
                0L
            }
            val principal = if (isLast) remaining else 0L
            remaining = Math.subtractExact(remaining, principal)
            item(request, index, date, principal, interest, remaining)
        }
    }

    private fun custom(request: LoanScheduleRequest): List<LoanScheduleItem> {
        var remaining = request.remainingPrincipalMinor
        return request.customLines.mapIndexed { index, line ->
            val principal = if (index == request.customLines.lastIndex) remaining else line.principalMinor
            require(principal <= remaining)
            remaining = Math.subtractExact(remaining, principal)
            LoanScheduleItem(
                request.scheduleItemIds[index],
                index + 1,
                line.plannedDate,
                principal,
                line.interestMinor,
                line.feeMinor,
                remaining,
                index == request.customLines.lastIndex,
            )
        }
    }

    private fun item(
        request: LoanScheduleRequest,
        index: Int,
        date: LocalDate,
        principal: Long,
        interest: Long,
        remaining: Long,
    ) = LoanScheduleItem(
        request.scheduleItemIds[index],
        index + 1,
        date,
        principal,
        interest,
        request.feePerPaymentMinor,
        remaining,
        index == request.paymentCount - 1,
    )

    private fun paymentDates(request: LoanScheduleRequest): List<LocalDate> = if (
        request.terms.repaymentMethod == LoanRepaymentMethod.CUSTOM
    ) {
        request.customLines.map(LoanCustomScheduleLine::plannedDate)
    } else {
        generateSequence(request.firstPaymentDate) { previous ->
            when (request.terms.paymentFrequency) {
                PaymentFrequency.WEEKLY -> previous.plusWeeks(1)
                PaymentFrequency.BIWEEKLY -> previous.plusWeeks(2)
                PaymentFrequency.MONTHLY -> previous.plusMonths(1)
                PaymentFrequency.QUARTERLY -> previous.plusMonths(3)
                PaymentFrequency.YEARLY -> previous.plusYears(1)
                PaymentFrequency.CUSTOM -> throw IllegalArgumentException("custom frequency requires custom schedule")
            }
        }.take(request.paymentCount).toList()
    }

    private fun rateFor(terms: LoanTermsRevision, date: LocalDate): InterestRate = terms.ratePeriods.singleOrNull {
        date >= it.effectiveFrom && (it.effectiveTo == null || date <= it.effectiveTo)
    }?.annualRate ?: throw IllegalArgumentException("rate period gap")

    private fun periodicRate(rate: InterestRate, frequency: PaymentFrequency): BigDecimal = rate.annualDecimal.divide(BigDecimal.valueOf(frequency.periodsPerYear()), MathContext.DECIMAL128)

    private fun annuityPayment(
        principalMinor: Long,
        periodicRate: BigDecimal,
        periods: Int,
        rounding: RoundingMode,
    ): Long {
        if (periodicRate.signum() == 0) {
            return BigDecimal.valueOf(principalMinor)
                .divide(BigDecimal.valueOf(periods.toLong()), 0, rounding)
                .longValueExact()
        }
        val onePlus = BigDecimal.ONE.add(periodicRate)
        val power = onePlus.pow(periods, MathContext.DECIMAL128)
        return BigDecimal.valueOf(principalMinor)
            .multiply(periodicRate, MathContext.DECIMAL128)
            .multiply(power, MathContext.DECIMAL128)
            .divide(power.subtract(BigDecimal.ONE), MathContext.DECIMAL128)
            .setScale(0, rounding)
            .longValueExact()
    }

    private fun decimalMinor(principalMinor: Long, rate: BigDecimal, rounding: RoundingMode): Long = BigDecimal.valueOf(principalMinor).multiply(rate, MathContext.DECIMAL128).setScale(0, rounding).longValueExact()

    private fun rateMinor(principalMinor: Long, rate: InterestRate, rounding: RoundingMode): Long = decimalMinor(principalMinor, rate.annualDecimal, rounding)
}

private fun PaymentFrequency.periodsPerYear(): Long = when (this) {
    PaymentFrequency.WEEKLY -> 52L
    PaymentFrequency.BIWEEKLY -> 26L
    PaymentFrequency.MONTHLY -> 12L
    PaymentFrequency.QUARTERLY -> 4L
    PaymentFrequency.YEARLY -> 1L
    PaymentFrequency.CUSTOM -> throw IllegalArgumentException("custom frequency")
}

private fun LoanSimulationScenario.date(): LocalDate = when (this) {
    is LoanSimulationScenario.PartialPrepayment -> onDate
    is LoanSimulationScenario.FullSettlement -> onDate
    is LoanSimulationScenario.RateChange -> effectiveFrom
}

private fun Iterable<Long>.sumChecked(): Long = when (val result = CheckedArithmetic.sum(toList())) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> throw ArithmeticException("loan sum overflow")
}

private inline fun <T> Iterable<T>.sumOfChecked(transform: (T) -> Long): Long = map(transform).sumChecked()

private fun <T> DomainResult<T>.valueOrThrow(): T = when (this) {
    is DomainResult.Success -> value
    is DomainResult.Failure -> throw IllegalArgumentException("invalid loan rule input")
}
