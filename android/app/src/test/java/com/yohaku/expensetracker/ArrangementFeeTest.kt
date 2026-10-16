package com.yohaku.expensetracker

import com.yohaku.expensetracker.domain.finance.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ArrangementFeeTest {
    @Test fun additionalFeesStaySeparateFromPrincipalAndConsumeAnInclusivePaymentCourse() {
        val billing=BillingRule(31,true,1,27,"bank")
        val installment=FinancialRuleVersion("i","b","c",FinanceMode.INSTALLMENT,"2026-04-01","Asia/Tokyo",billing=billing,installment=InstallmentRule(listOf(3),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=201,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"))
        val terms=FinancialScheduleTerms("2026-04-01",listOf("2026-05-27","2026-06-27","2026-07-27"),additionalFees=listOf(AdditionalPeriodFee(1,50)))
        val split=reconciledSchedule(10000,FinanceMode.INSTALLMENT,terms,listOf(EffectiveFinancialRule(1,installment)),emptyList(),emptyList(),LocalDate.parse("2026-04-01")) as Calculation.Ready
        assertEquals(listOf(117L,67L,67L),split.value.map{it.period.feeMinor})
        assertEquals(10000L,split.value.sumOf{it.period.principalMinor});assertEquals(0L,split.value.last().basis.remainingPrincipalMinor)
        val revolving=installment.copy(id="r",mode=FinanceMode.REVOLVING,revolving=RevolvingRule(InterestRule("0.12",ChargeFrequency.MONTHLY,BalanceBasis.OPENING_BALANCE,rounding=ChargeRounding.PERIOD_TOTAL,roundingMode="DOWN",firstCharge=FirstCharge.NORMAL),PaymentMethod.TOTAL_PAYMENT,1000,allocation=AllocationOrder.FEES_FIRST,automatic=false))
        val one=FinancialScheduleTerms("2026-04-01",listOf("2026-05-27"),listOf(AccrualWindow("2026-04-01","2026-04-30")),additionalFees=listOf(AdditionalPeriodFee(1,200)))
        val inclusive=reconciledSchedule(10000,FinanceMode.REVOLVING,one,listOf(EffectiveFinancialRule(1,revolving)),emptyList(),emptyList(),LocalDate.parse("2026-04-01")) as Calculation.Ready
        assertEquals(700L,inclusive.value.single().period.principalMinor);assertEquals(300L,inclusive.value.single().period.feeMinor);assertEquals(9300L,inclusive.value.single().basis.remainingPrincipalMinor)
        assertTrue(reconciledSchedule(10000,FinanceMode.REVOLVING,one.copy(additionalFees=listOf(AdditionalPeriodFee(1,1000))),listOf(EffectiveFinancialRule(1,revolving)),emptyList(),emptyList(),LocalDate.parse("2026-04-01")) is Calculation.CannotSettle)
    }
}
