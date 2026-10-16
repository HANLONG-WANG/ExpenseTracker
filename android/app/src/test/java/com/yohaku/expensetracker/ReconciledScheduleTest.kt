package com.yohaku.expensetracker

import com.yohaku.expensetracker.domain.finance.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReconciledScheduleTest {
    private val daily=InterestRule("0.18",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL)
    private val revolving=FinancialRuleVersion("r","b","a",FinanceMode.REVOLVING,"2026-04-01","Asia/Tokyo",billing=BillingRule(31,true,1,27,"bank"),revolving=RevolvingRule(daily,PaymentMethod.FIXED_PRINCIPAL,10000,allocation=AllocationOrder.FEES_FIRST,automatic=false))
    private val terms=FinancialScheduleTerms("2026-04-01",listOf("2026-05-27","2026-06-27","2026-07-27"),listOf(AccrualWindow("2026-04-01","2026-04-30"),AccrualWindow("2026-05-01","2026-05-31"),AccrualWindow("2026-06-01","2026-06-30")))
    @Test fun aLatePartialClearingChangesInterestOnItsRealDateAndDoesNotPayTheOverdueRemainder() {
        val known=listOf(ConfirmedSchedulePeriod(1,20000,1479,21479,LocalDate.parse("2026-05-27")))
        val facts=listOf(SchedulePrincipalFact("paid","2026-06-05",5000,1))
        val plan=reconciledSchedule(100000,FinanceMode.REVOLVING,terms,listOf(EffectiveFinancialRule(1,revolving)),known,facts,LocalDate.parse("2026-06-10")) as Calculation.Ready
        assertEquals(listOf(100000L,85000L,75000L),plan.value.map{it.basis.remainingPrincipalMinor})
        assertEquals(listOf(1479L,1528L,1395L),plan.value.map{it.period.feeMinor})
        assertEquals(listOf(0L,10000L,10000L),plan.value.map{it.basis.assumedPrincipalMinor})
        assertEquals(listOf(100000L,95000L,85000L),plan.value.last().basis.segments.map{it.balanceMinor})
        assertEquals(listOf(4L,22L,4L),plan.value.last().basis.segments.map{it.days})
    }
    @Test fun anInstallmentForecastSubtractsOnlyTheUnpaidPartAndKeepsContractFees() {
        val rule=revolving.copy(mode=FinanceMode.INSTALLMENT,installment=InstallmentRule(listOf(3),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=201,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"))
        val known=listOf(ConfirmedSchedulePeriod(1,3333,67,3400,LocalDate.parse("2026-05-27")))
        val facts=listOf(SchedulePrincipalFact("paid","2026-04-10",2000,1))
        val before=reconciledSchedule(10000,FinanceMode.INSTALLMENT,terms,listOf(EffectiveFinancialRule(1,rule)),known,facts,LocalDate.parse("2026-05-01")) as Calculation.Ready
        assertEquals(1333L,before.value.first().basis.assumedPrincipalMinor)
        assertEquals(0L,before.value.last().basis.remainingPrincipalMinor)
        val overdue=reconciledSchedule(10000,FinanceMode.INSTALLMENT,terms,listOf(EffectiveFinancialRule(1,rule)),known,facts,LocalDate.parse("2026-06-01")) as Calculation.Ready
        assertEquals(listOf(8000L,4667L,1333L),overdue.value.map{it.basis.remainingPrincipalMinor})
        assertEquals(listOf(67L,67L,67L),overdue.value.map{it.period.feeMinor})
        val contractOnly=reconciledSchedule(10000,FinanceMode.INSTALLMENT,terms,listOf(EffectiveFinancialRule(1,rule)),known,listOf(SchedulePrincipalFact("early","2026-04-10",5000)),LocalDate.parse("2026-06-01")) as Calculation.Ready
        assertEquals(listOf(3333L,1667L,0L),contractOnly.value.map{it.period.principalMinor})
        assertEquals(3333L,contractOnly.value.last().basis.remainingPrincipalMinor)
    }
    @Test fun aFixedDailyLoanUsesRealEarlyClearingAndStillChargesInterestBeforeFullSettlement() {
        val rule=revolving.copy(mode=FinanceMode.FIXED_LOAN,loanMethod=LoanMethod.EQUAL_PRINCIPAL,loanInterest=daily.copy(includeStart=false),loanPrincipalRemainder=RemainderPlacement.LAST,allocation=AllocationOrder.FEES_FIRST)
        val t=FinancialScheduleTerms("2026-04-01",listOf("2026-04-30","2026-05-30"))
        fun plan(principal:Long)=reconciledSchedule(100000,FinanceMode.FIXED_LOAN,t,listOf(EffectiveFinancialRule(1,rule)),emptyList(),listOf(SchedulePrincipalFact("early","2026-04-16",principal)),LocalDate.parse("2026-04-20")) as Calculation.Ready
        val partial=plan(20000).value
        assertEquals(listOf(50000L,30000L),partial.map{it.period.principalMinor})
        assertEquals(listOf(1282L,443L),partial.map{it.period.feeMinor})
        val monthly=rule.copy(loanMethod=LoanMethod.ANNUITY,loanInterest=daily.copy(annualRate="0.12",frequency=ChargeFrequency.MONTHLY,balanceBasis=BalanceBasis.OPENING_BALANCE,includeStart=null,includeEnd=null,yearDays=null))
        val regular=FinancialScheduleTerms("2026-04-01",listOf("2026-05-01","2026-06-01","2026-07-01"))
        val annuity=reconciledSchedule(10000,FinanceMode.FIXED_LOAN,regular,listOf(EffectiveFinancialRule(1,monthly)),emptyList(),emptyList(),LocalDate.parse("2026-04-01")) as Calculation.Ready
        assertEquals(listOf(3300L,3333L,3367L),annuity.value.map{it.period.principalMinor})
        assertEquals(listOf(100L,67L,33L),annuity.value.map{it.period.feeMinor})
        val full=plan(100000).value
        assertEquals(1,full.size);assertEquals(0L,full.single().period.principalMinor);assertEquals(690L,full.single().period.feeMinor)
    }
    @Test fun actualPrincipalAssignedToTheLastFixedPeriodRemainsPartOfItsGrossBill() {
        val rule=revolving.copy(mode=FinanceMode.SINGLE)
        val t=FinancialScheduleTerms("2026-04-01",listOf("2026-05-27"))
        val result=reconciledSchedule(10000,FinanceMode.SINGLE,t,listOf(EffectiveFinancialRule(1,rule)),emptyList(),listOf(SchedulePrincipalFact("paid","2026-04-10",2000,1)),LocalDate.parse("2026-04-15")) as Calculation.Ready
        assertEquals(10000L,result.value.single().period.principalMinor)
        assertEquals(8000L,result.value.single().basis.assumedPrincipalMinor)
        assertEquals(0L,result.value.single().basis.remainingPrincipalMinor)
    }

}
