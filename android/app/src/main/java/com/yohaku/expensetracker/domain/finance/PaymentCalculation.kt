package com.yohaku.expensetracker.domain.finance

import java.math.BigDecimal
import java.math.RoundingMode
import java.math.BigInteger
import java.time.LocalDate
import java.time.temporal.ChronoUnit

sealed interface Calculation<out T> {
    data class Ready<T>(val value: T): Calculation<T>
    data class Incomplete(val fields: List<String>): Calculation<Nothing>
    data class Unsupported(val reason: String): Calculation<Nothing>
    data class CannotSettle(val principalMinor: Long, val feeMinor: Long, val paymentMinor: Long): Calculation<Nothing>
}

data class PaymentPeriod(
    val number: Int, val date: LocalDate, val principalMinor: Long, val feeMinor: Long,
    val remainingPrincipalMinor: Long, val basis: List<String>, val interestCharge:InterestCharge?=null
) { val totalMinor: Long get() = Math.addExact(principalMinor, feeMinor) }

@kotlinx.serialization.Serializable
data class PeriodOverride(val number: Int, val principalMinor: Long, val feeMinor: Long)

private fun split(total: Long, count: Int, remainder: RemainderPlacement): List<Long> {
    val part = total / count
    return List(count) { index -> part + if (index == if (remainder == RemainderPlacement.FIRST) 0 else count - 1) total % count else 0 }
}

/** Overrides are explicit platform amounts. Unoverridden periods retain their calculated amounts. */
fun installmentPlan(
    principalMinor: Long, periods: Int, dates: List<LocalDate>, rule: InstallmentRule,
    overrides: List<PeriodOverride> = emptyList()
): Calculation<List<PaymentPeriod>> {
    require(principalMinor > 0 && periods > 0)
    val issues = rule.issues()
    if (issues.isNotEmpty()) return Calculation.Incomplete(issues)
    if (periods !in rule.supportedPeriods) return Calculation.Unsupported("installment.periods")
    require(dates.size == periods && dates.zipWithNext().all { (a,b) -> a < b })
    require(overrides.map { it.number }.distinct().size == overrides.size)
    require(overrides.all { it.number in 1..periods && it.principalMinor >= 0 && it.feeMinor >= 0 })
    val rounding = RoundingMode.valueOf(rule.roundingMode!!)
    val totalFee = when (rule.feeMethod!!) {
        InstallmentFeeMethod.TOTAL_AMOUNT -> rule.totalFeeMinor!!
        InstallmentFeeMethod.TOTAL_RATE -> BigDecimal.valueOf(principalMinor).multiply(rule.totalRate!!.toBigDecimal()).setScale(0, rounding).longValueExact()
        InstallmentFeeMethod.PER_HUNDRED_TABLE -> BigDecimal.valueOf(principalMinor).multiply(rule.feeTable.single { it.periods == periods }.feePerHundred.toBigDecimal()).divide(BigDecimal(100), 0, rounding).longValueExact()
    }
    val principals = split(principalMinor, periods, rule.principalRemainder!!)
    val fees = split(totalFee, periods, rule.feeRemainder!!)
    var remaining = principalMinor
    val plan = (1..periods).map { n ->
        val override = overrides.find { it.number == n }
        val principal = override?.principalMinor ?: principals[n-1]
        val fee = override?.feeMinor ?: fees[n-1]
        remaining = Math.subtractExact(remaining, principal)
        require(remaining >= 0) { "plan.principalExceedsDebt" }
        PaymentPeriod(n, dates[n-1], principal, fee, remaining,
            listOf(if (override == null) "installment.${rule.feeMethod}" else "platform.override", "principal.remainder.${rule.principalRemainder}", "fee.remainder.${rule.feeRemainder}", "rounding.${rule.roundingMode}"))
    }
    require(remaining == 0L) { "plan.principalMismatch" }
    return Calculation.Ready(plan)
}

/** Changes take effect at the start of their date, before that day's interest. */
data class PrincipalChange(val date: LocalDate, val deltaMinor: Long)
data class InterestSegment(val start: LocalDate, val endInclusive: LocalDate, val balanceMinor: Long, val days: Long, val annualRate:String?=null, val yearDays:Int?=null, val ruleId:String?=null)
data class InterestCharge(val feeMinor: Long, val segments: List<InterestSegment>, val rule: InterestRule)

data class InterestRateVersion(val effectiveDate:LocalDate,val rule:InterestRule,val ruleId:String?=null)

fun interestCharge(
    openingPrincipalMinor:Long,start:LocalDate,end:LocalDate,rule:InterestRule,
    changes:List<PrincipalChange> = emptyList(),firstPeriod:Boolean=false
):Calculation<InterestCharge> = versionedInterestCharge(openingPrincipalMinor,start,end,
    listOf(InterestRateVersion(start,rule)),changes,firstPeriod)

/** Sum exact decimal numerators over a common integer denominator before period rounding. */
fun versionedInterestCharge(
    openingPrincipalMinor:Long,start:LocalDate,end:LocalDate,versions:List<InterestRateVersion>,
    changes:List<PrincipalChange> = emptyList(),firstPeriod:Boolean=false
):Calculation<InterestCharge> {
    require(openingPrincipalMinor>=0&&end>=start){"本金不能为负，计费结束不能早于开始 / 元金は0以上、計算終了日は開始日以降にしてください"}
    require(changes.all{it.date in start..end})
    require(versions.map{it.effectiveDate}.distinct().size==versions.size)
    val timeline=versions.sortedBy{it.effectiveDate}
    val initial=timeline.lastOrNull{it.effectiveDate<=start}?:return Calculation.Incomplete(listOf("interest.ruleVersion"))
    val rule=initial.rule
    val active=listOf(initial)+timeline.filter{it.effectiveDate>start&&it.effectiveDate<=end}
    val issues=active.flatMap{it.rule.issues()}.distinct()
    if(issues.isNotEmpty())return Calculation.Incomplete(issues)
    fun controls(value:InterestRule)=if(value.frequency==ChargeFrequency.MONTHLY)value.copy(annualRate=rule.annualRate,yearDays=null,includeStart=null,includeEnd=null)else value.copy(annualRate=rule.annualRate,yearDays=rule.yearDays)
    if(active.any{controls(it.rule)!=controls(rule)})
        return Calculation.Unsupported("interest.changedMethodWithinPeriod")
    val grouped=changes.groupBy{it.date}.toSortedMap()
    var closing=openingPrincipalMinor
    grouped.values.forEach{events->events.forEach{closing=Math.addExact(closing,it.deltaMinor)};require(closing>=0){"interest.negativePrincipal"}}
    val rounding=RoundingMode.valueOf(rule.roundingMode!!)
    if(rule.frequency==ChargeFrequency.MONTHLY) {
        if(rule.balanceBasis==BalanceBasis.EVENT_BALANCE&&changes.isNotEmpty())return Calculation.Unsupported("interest.monthlyEventBalance")
        if(active.any{it.rule.annualRate!!.toBigDecimal().compareTo(rule.annualRate!!.toBigDecimal())!=0})return Calculation.Unsupported("interest.monthlyRateBoundary")
        val fee=if(firstPeriod&&rule.firstCharge==FirstCharge.WAIVED)0L else BigDecimal.valueOf(openingPrincipalMinor)
            .multiply(rule.annualRate!!.toBigDecimal()).divide(BigDecimal(12),0,rounding).longValueExact()
        return Calculation.Ready(InterestCharge(fee,emptyList(),rule))
    }
    val first=if(rule.includeStart!!)start else start.plusDays(1)
    val last=if(rule.includeEnd!!)end else end.minusDays(1)
    val boundaries=(listOf(start,first,last.plusDays(1),end.plusDays(1))+grouped.keys+active.map{it.effectiveDate}.filter{it>=start}).distinct().sorted()
    var balance=openingPrincipalMinor
    val segments=mutableListOf<InterestSegment>()
    for((from,until)in boundaries.zipWithNext()) {
        grouped[from].orEmpty().forEach{balance=Math.addExact(balance,it.deltaMinor)}
        val left=maxOf(from,first);val right=minOf(until.minusDays(1),last)
        if(left>right)continue
        val version=timeline.last{it.effectiveDate<=left}
        segments+=InterestSegment(left,right,if(rule.balanceBasis==BalanceBasis.OPENING_BALANCE)openingPrincipalMinor else balance,
            ChronoUnit.DAYS.between(left,right)+1,version.rule.annualRate,version.rule.yearDays,version.ruleId)
    }
    val fee=if(firstPeriod&&rule.firstCharge==FirstCharge.WAIVED)BigDecimal.ZERO else if(rule.rounding==ChargeRounding.EACH_DAY)
        segments.fold(BigDecimal.ZERO){sum,s->sum+BigDecimal.valueOf(s.balanceMinor).multiply(s.annualRate!!.toBigDecimal())
            .divide(BigDecimal(s.yearDays!!),0,rounding).multiply(BigDecimal.valueOf(s.days))}
    else {
        val denominator=segments.fold(BigInteger.ONE){lcm,s->val n=BigInteger.valueOf(s.yearDays!!.toLong());lcm.divide(lcm.gcd(n)).multiply(n)}
        val numerator=segments.fold(BigDecimal.ZERO){sum,s->sum+BigDecimal.valueOf(s.balanceMinor).multiply(s.annualRate!!.toBigDecimal())
            .multiply(BigDecimal.valueOf(s.days)).multiply(BigDecimal(denominator.divide(BigInteger.valueOf(s.yearDays!!.toLong()))))}
        numerator.divide(BigDecimal(denominator),0,rounding)
    }
    return Calculation.Ready(InterestCharge(fee.longValueExact(),segments,rule))
}

/** One bill at a time: events and the applicable rate version are supplied for its interval. */
fun revolvingPeriod(
    number: Int, openingPrincipalMinor: Long, start: LocalDate, end: LocalDate, paymentDate: LocalDate,
    rule: RevolvingRule, changes: List<PrincipalChange> = emptyList()
): Calculation<PaymentPeriod> {
    require(number > 0 && openingPrincipalMinor > 0 && paymentDate >= end)
    val issues = rule.issues()
    if (issues.isNotEmpty()) return Calculation.Incomplete(issues)
    val charge = when (val result = interestCharge(openingPrincipalMinor,start,end,rule.interest,changes,number == 1)) {
        is Calculation.Ready -> result.value
        is Calculation.Incomplete -> return result
        is Calculation.Unsupported -> return result
        is Calculation.CannotSettle -> return result
    }
    val balance = changes.fold(openingPrincipalMinor) { sum,c -> Math.addExact(sum,c.deltaMinor) }
    return revolvingPayment(number,balance,paymentDate,rule,charge)
}

fun revolvingPayment(number:Int,balance:Long,paymentDate:LocalDate,rule:RevolvingRule,charge:InterestCharge,tierBalanceMinor:Long=balance):Calculation<PaymentPeriod> {
    val payment = if (rule.method == PaymentMethod.BALANCE_TIERS) rule.tiers.first { it.upToMinor == null || tierBalanceMinor <= it.upToMinor }.paymentMinor else rule.paymentMinor!!
    val includesFees = rule.method == PaymentMethod.TOTAL_PAYMENT || rule.method == PaymentMethod.BALANCE_TIERS && rule.tierPaymentIncludesFees == true
    if (includesFees && (payment < charge.feeMinor || payment == charge.feeMinor && balance > 0)) return Calculation.CannotSettle(balance,charge.feeMinor,payment)
    val principal = minOf(balance, if (includesFees) payment - charge.feeMinor else payment)
    return Calculation.Ready(PaymentPeriod(number,paymentDate,principal,charge.feeMinor,balance-principal,
        listOf("interest.${rule.interest.frequency}", "balance.${rule.interest.balanceBasis}", if(rule.interest.frequency==ChargeFrequency.DAILY)"days.${charge.segments.sumOf { it.days }}"else"months.1", "yearDays.${rule.interest.yearDays}", "rate.${rule.interest.annualRate}", "rounding.${rule.interest.rounding}.${rule.interest.roundingMode}", "payment.${rule.method}", "allocation.${rule.allocation}"),charge))
}
