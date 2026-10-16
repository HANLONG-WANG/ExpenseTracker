package com.yohaku.expensetracker

import com.yohaku.expensetracker.domain.finance.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PredictedScheduleTest {
    private val interest=InterestRule("0.18",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL)
    private fun rule(id:String="r1",effective:String="2026-04-01",annual:String="0.18")=FinancialRuleVersion(id,"book","card",FinanceMode.REVOLVING,effective,"Asia/Tokyo",billing=BillingRule(31,true,1,27,"bank"),revolving=RevolvingRule(interest.copy(annualRate=annual),PaymentMethod.FIXED_PRINCIPAL,10000,allocation=AllocationOrder.FEES_FIRST,automatic=false))
    @Test fun rateChangesAndRepaymentsShareOnePeriodRoundingBoundary() {
        val start=LocalDate.of(2026,4,1);val end=LocalDate.of(2026,4,30)
        val rates=listOf(InterestRateVersion(start,interest,"r1"),InterestRateVersion(start.plusDays(15),interest.copy(annualRate="0.12"),"r2"))
        val charge=versionedInterestCharge(100000,start,end,rates) as Calculation.Ready
        assertEquals(1232L,charge.value.feeMinor)
        assertEquals(listOf(15L,15L),charge.value.segments.map{it.days})
        assertEquals(listOf("r1","r2"),charge.value.segments.map{it.ruleId})
        val partial=versionedInterestCharge(100000,start,end,rates,listOf(PrincipalChange(start.plusDays(15),-20000))) as Calculation.Ready
        assertEquals(1134L,partial.value.feeMinor)
        val method=rates.last().copy(rule=interest.copy(frequency=ChargeFrequency.MONTHLY,annualRate="0.12"))
        assertTrue(versionedInterestCharge(100000,start,end,listOf(rates.first(),method)) is Calculation.Unsupported)
    }
    @Test fun previousPrincipalStartsReducingInterestOnPaymentDateAndConfirmedPrincipalWins() {
        val terms=FinancialScheduleTerms("2026-04-01",listOf("2026-05-27","2026-06-27"),listOf(AccrualWindow("2026-04-01","2026-04-30"),AccrualWindow("2026-05-01","2026-05-31")))
        val plan=predictedSchedule(100000,FinanceMode.REVOLVING,terms,listOf(EffectiveFinancialRule(1,rule()))) as Calculation.Ready
        assertEquals(listOf(1479L,1504L),plan.value.map{it.period.feeMinor})
        assertEquals(listOf(90000L,80000L),plan.value.map{it.period.remainingPrincipalMinor})
        assertEquals(listOf(100000L,90000L),plan.value[1].basis.segments.map{it.balanceMinor})
        assertEquals(listOf(26L,5L),plan.value[1].basis.segments.map{it.days})
        val actual=predictedSchedule(100000,FinanceMode.REVOLVING,terms,listOf(EffectiveFinancialRule(1,rule())),listOf(ConfirmedSchedulePeriod(1,20000,1479,21479,LocalDate.of(2026,5,27)))) as Calculation.Ready
        assertEquals(1479L,actual.value[1].period.feeMinor)
        assertEquals(70000L,actual.value[1].period.remainingPrincipalMinor)
        assertEquals(listOf(100000L,80000L),actual.value[1].basis.segments.map{it.balanceMinor})
    }
    @Test fun confirmedExtraPrincipalDoesNotCauseInstallmentOverpayment() {
        val r=rule().copy(mode=FinanceMode.INSTALLMENT,installment=InstallmentRule(listOf(3),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=201,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"))
        val terms=FinancialScheduleTerms("2026-04-01",listOf("2026-05-27","2026-06-27","2026-07-27"))
        val plan=predictedSchedule(10000,FinanceMode.INSTALLMENT,terms,listOf(EffectiveFinancialRule(1,r)),listOf(ConfirmedSchedulePeriod(1,3500,67,3567,LocalDate.of(2026,5,27)))) as Calculation.Ready
        assertEquals(3167L,plan.value.last().period.principalMinor)
        assertEquals(0L,plan.value.last().period.remainingPrincipalMinor)
        assertEquals(67L,plan.value.last().period.feeMinor)
    }
    @Test fun tierCourseUsesTheConfiguredClosingOrPaymentBalance() {
        val terms=FinancialScheduleTerms("2026-04-01",listOf("2026-05-27"),listOf(AccrualWindow("2026-04-01","2026-04-30")))
        val course=rule().revolving!!.copy(method=PaymentMethod.BALANCE_TIERS,tiers=listOf(PaymentTier(80000,5000),PaymentTier(null,10000)),tierPaymentIncludesFees=false,tierBalanceDate=TierBalanceDate.BILL_CLOSING)
        val events=listOf(PrincipalChange(LocalDate.of(2026,5,1),-30000))
        val closing=predictedSchedule(100000,FinanceMode.REVOLVING,terms,listOf(EffectiveFinancialRule(1,rule().copy(revolving=course))),changes=events) as Calculation.Ready
        assertEquals(10000L,closing.value.single().period.principalMinor)
        assertEquals(60000L,closing.value.single().period.remainingPrincipalMinor)
        val payment=predictedSchedule(100000,FinanceMode.REVOLVING,terms,listOf(EffectiveFinancialRule(1,rule().copy(revolving=course.copy(tierBalanceDate=TierBalanceDate.PAYMENT_DATE)))),changes=events) as Calculation.Ready
        assertEquals(5000L,payment.value.single().period.principalMinor)
        assertEquals(65000L,payment.value.single().period.remainingPrincipalMinor)
        assertEquals(1479L,payment.value.single().period.feeMinor)
        assertTrue("revolving.tierBalanceDate" in course.copy(tierBalanceDate=null).issues())
    }

}
