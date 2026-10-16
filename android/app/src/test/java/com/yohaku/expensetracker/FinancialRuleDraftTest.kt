package com.yohaku.expensetracker

import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.features.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FinancialRuleDraftTest {
    @Test fun userPercentAndAccountCurrencyAmountsReachTheCalculatorExactly() {
        val billing=mapOf("effective" to "2026-10-01","zone" to "Asia/Shanghai","closing" to "31","closingIncluded" to "true","monthOffset" to "1","payday" to "27","bank" to "bank")
        val split=FinancialRuleDraft(FinanceMode.INSTALLMENT,billing+mapOf("periods" to "3","feeMethod" to "PER_HUNDRED_TABLE","feeTable" to "3:2.01","principalRemainder" to "LAST","feeRemainder" to "FIRST","rounding" to "DOWN"))
        val rule=split.build("book","card",Currency.CNY)
        val result=calculateRulePreview(rule,RulePreviewInput(Money.parse("10000",Currency.CNY).minor,3,LocalDate.of(2026,10,1),LocalDate.of(2026,10,30),LocalDate.of(2026,11,27))) as Calculation.Ready
        assertEquals(listOf(333333L,333333L,333334L),result.value.map{it.principalMinor})
        assertEquals(20100L,result.value.sumOf{it.feeMinor})
        assertEquals(split.values,rule.toForm(Currency.CNY).values)
        val revolving=FinancialRuleDraft(FinanceMode.REVOLVING,billing+mapOf("apr" to "18","frequency" to "DAILY","balance" to "EVENT_BALANCE","includeStart" to "true","includeEnd" to "true","yearDays" to "365","roundAt" to "PERIOD_TOTAL","rounding" to "DOWN","first" to "NORMAL","paymentMethod" to "FIXED_PRINCIPAL","payment" to "100.00","allocation" to "FEES_FIRST","automatic" to "false"))
        val interest=calculateRulePreview(revolving.build("book","card",Currency.CNY),RulePreviewInput(Money.parse("1000",Currency.CNY).minor,1,LocalDate.of(2026,4,1),LocalDate.of(2026,4,30),LocalDate.of(2026,4,30))) as Calculation.Ready
        assertEquals(1479L,interest.value.single().feeMinor)
        assertEquals(11479L,interest.value.single().totalMinor)
        val duplicate=rule.installment!!.copy(feeTable=listOf(InstallmentFee(3,"2.01"),InstallmentFee(3,"invalid")))
        assertTrue("installment.feeTable" in duplicate.issues())
    }
    @Test fun creditLineCalculationNeedsNoLimitPolicyAndLegacyInputStaysHistorical() {
        val draft=FinancialRuleDraft(FinanceMode.CREDIT_LINE,mapOf("effective" to "2026-10-01","zone" to "Asia/Tokyo",
            "apr" to "12","frequency" to "MONTHLY","balance" to "OPENING_BALANCE","roundAt" to "PERIOD_TOTAL","rounding" to "DOWN","first" to "NORMAL",
            "paymentMethod" to "TOTAL_PAYMENT","payment" to "200","allocation" to "FEES_FIRST","automatic" to "true","recovery" to "PAYMENT_DATE"))
        val rule=draft.build("book","loan",Currency.JPY)
        assertEquals(RuleReadiness.Complete,rule.readiness());assertNull(rule.limitRecovery);assertNull(rule.revolving!!.automatic)
        val result=calculateRulePreview(rule,RulePreviewInput(1000,1,LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),LocalDate.of(2026,11,1))) as Calculation.Ready
        assertEquals(190L,result.value.single().principalMinor);assertEquals(10L,result.value.single().feeMinor)
        val historical=rule.copy(limitRecovery=LimitRecovery.MANUAL)
        val reopened=Json.decodeFromString<FinancialRuleVersion>(Json.encodeToString(historical))
        assertEquals(historical,reopened);assertEquals("MANUAL",reopened.toForm(Currency.JPY)["recovery"])
        val next=reopened.toForm(Currency.JPY).build("book","loan",Currency.JPY)
        assertNull(next.limitRecovery);assertEquals(historical.revolving,next.revolving)
    }

}
