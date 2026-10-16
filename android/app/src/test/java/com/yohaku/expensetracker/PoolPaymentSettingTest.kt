package com.yohaku.expensetracker

import com.yohaku.expensetracker.domain.finance.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PoolPaymentSettingTest {
    @Test fun paymentChangesUseTheirPaymentDatesAndKeepTheConfiguredInterest() {
        val interest=InterestRule("0.12",ChargeFrequency.MONTHLY,BalanceBasis.OPENING_BALANCE,null,null,null,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL)
        val rule=FinancialRuleVersion("r","b","a",FinanceMode.REVOLVING,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,"bank"),revolving=RevolvingRule(interest,PaymentMethod.FIXED_PRINCIPAL,1000,allocation=AllocationOrder.FEES_FIRST,automatic=true))
        val terms=FinancialScheduleTerms("2026-10-01",listOf("2026-10-31","2026-11-30","2026-12-31"),listOf(AccrualWindow("2026-10-01","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"),AccrualWindow("2026-12-01","2026-12-31")),paymentSettings=listOf(
            RevolvingPaymentSetting("2026-11-30",PaymentMethod.TOTAL_PAYMENT,600),
            RevolvingPaymentSetting("2026-12-31",PaymentMethod.BALANCE_TIERS,tiers=listOf(PaymentTier(1500,500),PaymentTier(null,800)),tierPaymentIncludesFees=true,tierBalanceDate=TierBalanceDate.PAYMENT_DATE)))
        val versions=listOf(EffectiveFinancialRule(1,rule))
        val predicted=predictedSchedule(3000,FinanceMode.REVOLVING,terms,versions) as Calculation.Ready
        val reconciled=reconciledSchedule(3000,FinanceMode.REVOLVING,terms,versions,emptyList(),emptyList(),LocalDate.parse("2026-10-01")) as Calculation.Ready
        for(rows in listOf(predicted.value,reconciled.value)) {
            assertEquals(listOf(1000L,580L,486L),rows.map{it.period.principalMinor})
            assertEquals(listOf(30L,20L,14L),rows.map{it.period.feeMinor})
            assertEquals(934L,rows.last().period.remainingPrincipalMinor)
            assertTrue(rows.all{it.basis.ruleIds==listOf("r")})
            assertNull(rows.first().basis.paymentSetting)
            assertEquals(600L,rows[1].basis.paymentSetting!!.paymentMinor)
            assertEquals(PaymentMethod.BALANCE_TIERS,rows[2].basis.paymentSetting!!.method)
        }
        assertEquals(PaymentMethod.FIXED_PRINCIPAL,rule.revolving!!.method)
        assertEquals(interest,rule.revolving!!.interest)
    }
}
