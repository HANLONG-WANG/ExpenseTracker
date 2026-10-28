package com.yohaku.expensetracker

import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.ui.features.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class CreditCardOptionsTest {
    private val windows=listOf(BonusPaymentWindow(12,16,6,15,8,10),BonusPaymentWindow(7,16,11,15,1,10))
    @Test fun bonusWindowsResolveCrossYearAndRoundTripWithRuleDraft() {
        assertEquals(LocalDate.parse("2027-08-10"),bonusPaymentDate(LocalDate.parse("2026-12-20"),windows))
        assertEquals(LocalDate.parse("2027-01-10"),bonusPaymentDate(LocalDate.parse("2026-07-20"),windows))
        assertNull(bonusPaymentDate(LocalDate.parse("2026-06-20"),windows))
        assertNull(bonusPaymentDate(LocalDate.parse("2026-12-20"),emptyList()))
        val rule=FinancialRuleVersion("","book","card",FinanceMode.SINGLE,"2026-01-01","Asia/Tokyo",BillingRule(15,true,1,10,"bank"),bonusPayments=windows)
        assertEquals(RuleReadiness.Complete,rule.copy(bonusPayments=emptyList()).readiness())
        val restored=Json.decodeFromString<FinancialRuleVersion>(Json.encodeToString(rule)).toForm(Currency.JPY).build("book","card",Currency.JPY)
        assertEquals(rule,restored)
        assertEquals(listOf("bonus.overlap"),bonusPaymentIssues(listOf(windows.first(),BonusPaymentWindow(6,15,8,1,10,10))))
        assertEquals(listOf("bonus.dates"),bonusPaymentIssues(listOf(BonusPaymentWindow(2,30,6,1,8,10))))
    }
    @Test fun deferredMonthCannotPrecedeNormalMonthAndAddsFeeToOnePeriod() {
        val billing=BillingRule(15,true,1,31,"bank");val purchase=LocalDate.parse("2026-10-10")
        assertThrows(IllegalArgumentException::class.java){deferredPaymentDate(purchase,YearMonth.parse("2026-10"),billing)}
        val payment=deferredPaymentDate(purchase,YearMonth.parse("2027-02"),billing)
        assertEquals(LocalDate.parse("2027-02-28"),payment)
        val rule=FinancialRuleVersion("single","book","card",FinanceMode.SINGLE,"2026-01-01","Asia/Tokyo",billing)
        val result=reconciledSchedule(3000,FinanceMode.SINGLE,FinancialScheduleTerms(purchase.toString(),listOf(payment.toString()),additionalFees=listOf(AdditionalPeriodFee(1,100))),listOf(EffectiveFinancialRule(1,rule)),emptyList(),emptyList(),purchase) as Calculation.Ready
        assertEquals(1,result.value.size);assertEquals(100L,result.value.single().period.feeMinor);assertEquals(3100L,result.value.single().period.totalMinor)
    }
    @Test fun recoveryDelayUsesCalendarDaysAndActualRecoveryStillWins() {
        val book=BookEntity("b","帳簿","JPY","2026-10-01")
        val card=AccountEntity("b","card","カード","CREDIT","JPY",1000,"2026-10-01")
        val event=EventEntity("b","paid","REPAYMENT","bank",null,1000,"JPY",0,"2026-10-28","","")
        val repayment=FinancialRepaymentEntity("b","paid","card",1000,1000,0,0,0,"2026-10-28",true,"NORMAL",null,"",null,null)
        val snapshot=BookSnapshot(book,listOf(card),emptyList(),listOf(event),emptyList(),null,repayments=listOf(repayment))
        val finance=FinancialGraph(book,emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),listOf(repayment),emptyList(),emptyList(),emptyList(),emptyList())
        val limits=CreditLimitGraph(book,emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
        fun movements(date:String,delay:Int,graph:CreditLimitGraph=limits)=creditLimitMovements(snapshot,finance,graph,"card",date,"PAYMENT_DATE",recoveryDelayDays=delay)
        assertTrue(movements("2026-11-03",7).isEmpty());assertEquals(-1000L,movements("2026-11-04",7).single().total)
        assertEquals(-1000L,movements("2026-10-28",0).single().total)
        val actual=CreditLimitRecoveryEntity("b","actual","card","paid","2026-10-30",400,null,null,limitPayments(snapshot,finance,"card").single().proof,0)
        assertEquals(-400L,movements("2026-11-04",7,limits.copy(recoveries=listOf(actual))).single().total)
    }
    @Test fun transferUsesPurchaseThenCreationOrderAndKeepsLastRemainder() {
        val sources=listOf(SinglePaymentPortion("third","2026-10-03",30,7000),SinglePaymentPortion("second","2026-10-02",20,5000),SinglePaymentPortion("first","2026-10-02",10,3000))
        val allocation=allocateSinglePaymentTransfers(sources,10000)
        assertEquals(listOf("first","second","third"),allocation.map{it.planId})
        assertEquals(listOf(3000L,5000L,2000L),allocation.map{it.principalMinor})
        assertEquals(listOf(0L,0L,5000L),allocation.map{it.retainedPrincipalMinor})
        assertEquals(sources.sumOf{it.principalMinor},allocation.sumOf{it.principalMinor+it.retainedPrincipalMinor})
        assertThrows(IllegalArgumentException::class.java){allocateSinglePaymentTransfers(sources,15001)}
    }
}
