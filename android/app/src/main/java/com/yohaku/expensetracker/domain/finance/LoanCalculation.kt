package com.yohaku.expensetracker.domain.finance

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/** This pure schedule does not create a borrowing, expense, or account posting. */
fun fixedLoanPlan(
    principalMinor: Long, start: LocalDate, paymentDates: List<LocalDate>, method: LoanMethod,
    interest: InterestRule?, principalRemainder: RemainderPlacement?,
    manualPeriods: List<PeriodOverride> = emptyList()
): Calculation<List<PaymentPeriod>> {
    require(principalMinor > 0 && paymentDates.isNotEmpty())
    require(paymentDates.first() > start && paymentDates.zipWithNext().all { (a,b) -> a < b })
    if (method == LoanMethod.MANUAL) return manualPlan(principalMinor,paymentDates,manualPeriods)
    if (interest == null) return Calculation.Incomplete(listOf("loan.interest"))
    val issues = interest.issues()
    if (issues.isNotEmpty()) return Calculation.Incomplete(issues)
    if (method == LoanMethod.EQUAL_PRINCIPAL && principalRemainder == null) return Calculation.Incomplete(listOf("loan.principalRemainder"))
    if (method == LoanMethod.ANNUITY && (interest.frequency != ChargeFrequency.MONTHLY || interest.firstCharge != FirstCharge.NORMAL ||
            paymentDates.withIndex().any { (index,date) -> date != start.plusMonths(index+1L) }))
        return Calculation.Unsupported("loan.annuityRequiresRegularMonthlyInterest")
    val rounding = RoundingMode.valueOf(interest.roundingMode!!)
    val count = paymentDates.size
    val fixedPrincipal = if (method == LoanMethod.EQUAL_PRINCIPAL) principalMinor/count else 0
    val annuityPayment = if (method == LoanMethod.ANNUITY) {
        val mc = MathContext.DECIMAL128
        val monthlyRate = interest.annualRate!!.toBigDecimal().divide(BigDecimal(12),mc)
        if (monthlyRate.signum() == 0) BigDecimal.valueOf(principalMinor).divide(BigDecimal(count),0,rounding).longValueExact()
        else {
            val power = (BigDecimal.ONE + monthlyRate).pow(count,mc)
            BigDecimal.valueOf(principalMinor).multiply(monthlyRate,mc).multiply(power,mc)
                .divide(power-BigDecimal.ONE,0,rounding).longValueExact()
        }
    } else 0
    var remaining = principalMinor
    var previousDate = start
    val periods = mutableListOf<PaymentPeriod>()
    for ((index,date) in paymentDates.withIndex()) {
        val charge = when (val result = interestCharge(remaining,previousDate,date,interest,firstPeriod=index == 0)) {
            is Calculation.Ready -> result.value
            is Calculation.Incomplete -> return result
            is Calculation.Unsupported -> return result
            is Calculation.CannotSettle -> return result
        }
        val principal = when (method) {
            LoanMethod.EQUAL_PRINCIPAL -> fixedPrincipal + if (index == if (principalRemainder == RemainderPlacement.FIRST) 0 else count-1) principalMinor%count else 0
            LoanMethod.ANNUITY -> {
                if (annuityPayment <= charge.feeMinor) return Calculation.CannotSettle(remaining,charge.feeMinor,annuityPayment)
                if (index == count-1) remaining else minOf(remaining,annuityPayment-charge.feeMinor)
            }
            LoanMethod.INTEREST_ONLY -> if (index == count-1) remaining else 0
            LoanMethod.MANUAL -> error("manual plan handled separately")
        }
        remaining -= principal
        periods += PaymentPeriod(index+1,date,principal,charge.feeMinor,remaining,
            listOf("loan.$method", "interval.$previousDate/$date", "balance.${remaining+principal}", "rate.${interest.annualRate}", "interest.${interest.frequency}", "rounding.${interest.rounding}.${interest.roundingMode}"),charge)
        previousDate = date
    }
    return Calculation.Ready(periods)
}

fun manualPlan(principalMinor: Long, dates: List<LocalDate>, periods: List<PeriodOverride>): Calculation<List<PaymentPeriod>> {
    require(principalMinor > 0 && dates.isNotEmpty() && dates.zipWithNext().all { (a,b) -> a < b })
    if (periods.map { it.number }.sorted() != (1..dates.size).toList()) return Calculation.Incomplete(listOf("manual.periods"))
    require(periods.all { it.principalMinor >= 0 && it.feeMinor >= 0 })
    var remaining = principalMinor
    val plan = periods.sortedBy { it.number }.map { p ->
        remaining = Math.subtractExact(remaining,p.principalMinor)
        require(remaining >= 0) { "plan.principalExceedsDebt" }
        PaymentPeriod(p.number,dates[p.number-1],p.principalMinor,p.feeMinor,remaining,listOf("platform.manual"))
    }
    require(remaining == 0L) { "plan.principalMismatch" }
    return Calculation.Ready(plan)
}

/** The gross debt and net proceeds are distinct. Fees already withheld are included only once. */
data class BorrowingAmounts(val principalMinor: Long, val receivedMinor: Long, val withheldFeeMinor: Long, val separateFeeMinor: Long) {
    init {
        require(principalMinor > 0 && receivedMinor >= 0 && withheldFeeMinor >= 0 && separateFeeMinor >= 0)
        require(Math.addExact(receivedMinor,withheldFeeMinor) == principalMinor) { "borrowing.proceedsMismatch" }
    }
    val expenseMinor: Long get() = Math.addExact(withheldFeeMinor,separateFeeMinor)
    val cashIncreaseMinor: Long get() = Math.subtractExact(receivedMinor,separateFeeMinor)
}

data class BillDifference(val principalMinor: Long, val feeMinor: Long, val dateChanged: Boolean) {
    val matches: Boolean get() = principalMinor == 0L && feeMinor == 0L && !dateChanged
}
fun compareBill(predicted: PaymentPeriod, confirmed: PaymentPeriod) = BillDifference(
    Math.subtractExact(confirmed.principalMinor,predicted.principalMinor),
    Math.subtractExact(confirmed.feeMinor,predicted.feeMinor),confirmed.date != predicted.date
)

data class PlanComparison(val currentReductionMinor: Long, val extraFeeMinor: Long, val original: List<PaymentPeriod>, val proposed: List<PaymentPeriod>)
fun comparePlans(original: List<PaymentPeriod>, proposed: List<PaymentPeriod>): PlanComparison {
    require(original.isNotEmpty() && proposed.isNotEmpty())
    fun sum(plan: List<PaymentPeriod>, value: (PaymentPeriod) -> Long) = plan.fold(0L) { total,p -> Math.addExact(total,value(p)) }
    require(sum(original) { it.principalMinor } == sum(proposed) { it.principalMinor }) { "plan.comparisonPrincipalMismatch" }
    val currentDate = original.first().date
    return PlanComparison(Math.subtractExact(original.first().totalMinor,sum(proposed.filter { it.date <= currentDate }) { it.totalMinor }),
        Math.subtractExact(sum(proposed) { it.feeMinor },sum(original) { it.feeMinor }),original,proposed)
}
