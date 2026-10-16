package com.yohaku.expensetracker

import com.yohaku.expensetracker.domain.finance.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FinanceCalculationTest {
    private val start = LocalDate.of(2026,4,1)
    private val end = LocalDate.of(2026,4,30)
    private val daily = InterestRule("0.18",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL)
    private val installment = InstallmentRule(listOf(3),InstallmentFeeMethod.PER_HUNDRED_TABLE,feeTable=listOf(InstallmentFee(3,"2.01")),principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN")
    private fun revolving(method: PaymentMethod = PaymentMethod.FIXED_PRINCIPAL, payment: Long = 10000) =
        RevolvingRule(daily,method,payment,allocation=AllocationOrder.FEES_FIRST,automatic=false)

    @Test fun installmentTailAndIndependentFeeTable() {
        val result = installmentPlan(10000,3,listOf(end,end.plusMonths(1),end.plusMonths(2)),installment) as Calculation.Ready
        assertEquals(listOf(3333L,3333L,3334L),result.value.map { it.principalMinor })
        assertEquals(listOf(67L,67L,67L),result.value.map { it.feeMinor })
        assertEquals(listOf(6667L,3334L,0L),result.value.map { it.remainingPrincipalMinor })
        assertEquals(10201L,result.value.sumOf { it.totalMinor })
        val manual = installmentPlan(10000,3,result.value.map { it.date },installment,
            listOf(PeriodOverride(1,4000,80),PeriodOverride(2,3000,70),PeriodOverride(3,3000,60))) as Calculation.Ready
        assertEquals(listOf(6000L,3000L,0L),manual.value.map { it.remainingPrincipalMinor })
        assertThrows(IllegalArgumentException::class.java) {
            installmentPlan(10000,3,result.value.map { it.date },installment,listOf(PeriodOverride(1,4000,80)))
        }
    }

    @Test fun thirtyDayPrincipalAndTotalPaymentCourses() {
        val principal = revolvingPeriod(1,100000,start,end,end,revolving()) as Calculation.Ready
        assertEquals(1479L,principal.value.feeMinor)
        assertEquals(11479L,principal.value.totalMinor)
        assertEquals(90000L,principal.value.remainingPrincipalMinor)
        val total = revolvingPeriod(1,100000,start,end,end,revolving(PaymentMethod.TOTAL_PAYMENT)) as Calculation.Ready
        assertEquals(8521L,total.value.principalMinor)
        assertEquals(91479L,total.value.remainingPrincipalMinor)
        assertEquals(10000L,total.value.totalMinor)
        assertTrue(revolvingPeriod(1,100000,start,end,end,revolving(PaymentMethod.TOTAL_PAYMENT,1000)) is Calculation.CannotSettle)
    }

    @Test fun midPeriodRepaymentAndRoundingBoundary() {
        val charge = interestCharge(100000,start,end,daily,listOf(PrincipalChange(start.plusDays(15),-20000))) as Calculation.Ready
        assertEquals(1331L,charge.value.feeMinor)
        assertEquals(listOf(15L,15L),charge.value.segments.map { it.days })
        assertEquals(listOf(100000L,80000L),charge.value.segments.map { it.balanceMinor })
        val perDay = interestCharge(100000,start,end,daily.copy(rounding=ChargeRounding.EACH_DAY)) as Calculation.Ready
        assertEquals(1470L,perDay.value.feeMinor)
        val excluded = interestCharge(100000,start,end,daily.copy(includeStart=false,includeEnd=false)) as Calculation.Ready
        assertEquals(1380L,excluded.value.feeMinor)
    }

    @Test fun missingAndUnsupportedRulesNeverBecomeZeroFees() {
        val incomplete = interestCharge(100000,start,end,InterestRule(annualRate="0.18")) as Calculation.Incomplete
        assertTrue("interest.frequency" in incomplete.fields)
        assertTrue("interest.rounding" in incomplete.fields)
        assertTrue(installmentPlan(10000,2,listOf(end,end.plusMonths(1)),installment) is Calculation.Unsupported)
        assertTrue(interestCharge(100000,start,end,daily.copy(frequency=ChargeFrequency.MONTHLY),listOf(PrincipalChange(start.plusDays(15),-20000))) is Calculation.Unsupported)
        val waived = interestCharge(100000,start,end,daily.copy(firstCharge=FirstCharge.WAIVED),firstPeriod=true) as Calculation.Ready
        assertEquals(0L,waived.value.feeMinor)
        val second = interestCharge(100000,start,end,daily.copy(firstCharge=FirstCharge.WAIVED),firstPeriod=false) as Calculation.Ready
        assertEquals(1479L,second.value.feeMinor)
    }

    @Test fun closingBoundaryMonthEndAndActualDateOverride() {
        val rule = BillingRule(31,true,1,31,"bank-id")
        assertTrue(rule.copy(closingDay=20,paymentDay=5,paymentMonthOffset=0).issues().contains("billing.paymentOrder"))
        val dates = billingDates(LocalDate.of(2026,2,28),rule)
        assertEquals(LocalDate.of(2026,2,28),dates.closingDate)
        assertEquals(LocalDate.of(2026,3,31),dates.paymentDate)
        assertTrue(dates.monthEndAdjusted)
        val next = billingDates(LocalDate.of(2026,2,28),rule.copy(closingDayInCurrentBill=false))
        assertEquals(LocalDate.of(2026,3,31),next.closingDate)
        assertEquals(LocalDate.of(2026,4,30),next.paymentDate)
        val actual = LocalDate.of(2026,4,2)
        assertEquals(actual,billingDates(LocalDate.of(2026,2,28),rule,actual).paymentDate)
    }
    @Test fun fixedLoanMethodsAndNetBorrowingKeepDebtSeparateFromIncome() {
        val dates = listOf(start.plusMonths(1),start.plusMonths(2),start.plusMonths(3))
        val monthly = daily.copy(annualRate="0.12",frequency=ChargeFrequency.MONTHLY,balanceBasis=BalanceBasis.OPENING_BALANCE,yearDays=null,includeStart=null,includeEnd=null)
        val equal = fixedLoanPlan(10000,start,dates,LoanMethod.EQUAL_PRINCIPAL,monthly,RemainderPlacement.LAST) as Calculation.Ready
        assertEquals(listOf(3333L,3333L,3334L),equal.value.map { it.principalMinor })
        assertEquals(listOf(100L,66L,33L),equal.value.map { it.feeMinor })
        val annuity = fixedLoanPlan(10000,start,dates,LoanMethod.ANNUITY,monthly,null) as Calculation.Ready
        assertEquals(listOf(3400L,3400L,3400L),annuity.value.map { it.totalMinor })
        assertEquals(listOf(3300L,3333L,3367L),annuity.value.map { it.principalMinor })
        val balloon = fixedLoanPlan(10000,start,dates,LoanMethod.INTEREST_ONLY,monthly,null) as Calculation.Ready
        assertEquals(listOf(0L,0L,10000L),balloon.value.map { it.principalMinor })
        assertEquals(listOf(100L,100L,100L),balloon.value.map { it.feeMinor })
        val manual = fixedLoanPlan(10000,start,dates,LoanMethod.MANUAL,null,null,
            listOf(PeriodOverride(1,3000,90),PeriodOverride(2,3000,60),PeriodOverride(3,4000,40))) as Calculation.Ready
        assertEquals(10190L,manual.value.sumOf { it.totalMinor })
        val proceeds = BorrowingAmounts(10000,9700,300,0)
        assertEquals(10000L,proceeds.principalMinor)
        assertEquals(9700L,proceeds.cashIncreaseMinor)
        assertEquals(300L,proceeds.expenseMinor)
        assertTrue(fixedLoanPlan(10000,start,dates,LoanMethod.ANNUITY,daily,null) is Calculation.Unsupported)
    }

}
