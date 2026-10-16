package com.yohaku.expensetracker

import com.yohaku.expensetracker.domain.finance.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FinancialPoolCalculationTest {
    private fun version(date:String="2026-01-01",payment:Long=10000)=EffectiveFinancialRule(1,FinancialRuleVersion("r","b","a",FinanceMode.REVOLVING,date,"Asia/Tokyo",BillingRule(31,true,1,5,"bank"),revolving=RevolvingRule(InterestRule("0.18",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"HALF_UP",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,payment,allocation=AllocationOrder.FEES_FIRST,automatic=true)))
    @Test fun combinedAdvancesAndActualClearingUseOnePaymentAndTheirOwnInterestDates() {
        val terms=FinancialScheduleTerms("2026-01-01",listOf("2026-01-30"),listOf(AccrualWindow("2026-01-01","2026-01-30")))
        val result=reconciledSchedule(100000,FinanceMode.REVOLVING,terms,listOf(version()),emptyList(),listOf(SchedulePrincipalFact("paid","2026-01-21",20000)),LocalDate.parse("2026-01-15"),listOf(SchedulePrincipalAddition("draw","2026-01-16",50000))) as Calculation.Ready
        val p=result.value.single().period
        assertEquals(1751L,p.feeMinor);assertEquals(10000L,p.principalMinor);assertEquals(120000L,p.remainingPrincipalMinor)
        assertEquals(listOf(100000L,150000L,130000L),p.interestCharge!!.segments.map{it.balanceMinor})
    }
    @Test fun futureAdvanceCannotBeSpentEarlyAndZeroOpeningNeedsOnlyTheActualCalculationRule() {
        val terms=FinancialScheduleTerms("2026-01-01",listOf("2026-01-30","2026-02-27"),listOf(AccrualWindow("2026-01-15","2026-01-30"),AccrualWindow("2026-01-31","2026-02-27")))
        val result=reconciledSchedule(0,FinanceMode.REVOLVING,terms,listOf(version("2026-01-15",150000)),emptyList(),listOf(SchedulePrincipalFact("paid","2026-02-11",20000)),LocalDate.parse("2026-01-20"),listOf(SchedulePrincipalAddition("one","2026-01-15",100000),SchedulePrincipalAddition("two","2026-02-10",50000))) as Calculation.Ready
        assertEquals(listOf(100000L,30000L),result.value.map{it.period.principalMinor})
        assertEquals(listOf(789L,276L),result.value.map{it.period.feeMinor})
        assertEquals(listOf(0L,0L),result.value.map{it.period.remainingPrincipalMinor})
    }
}
