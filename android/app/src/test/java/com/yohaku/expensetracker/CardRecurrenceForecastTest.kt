package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.Currency
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CardRecurrenceForecastTest {
    private val fixture=CashProjectionFixture(principal=0)
    private val card=fixture.card.copy(name="JCB")
    private val bank=fixture.bank.copy(name="ゆうちょ")
    private val today=LocalDate.parse("2026-11-04")
    private val through=LocalDate.parse("2027-04-30")
    private val single=FinancialRuleVersion("single","b",card.id,FinanceMode.SINGLE,"2026-01-01","Asia/Tokyo",BillingRule(15,true,1,10,bank.id))
    private fun row(rule:FinancialRuleVersion,version:Int=1)=FinancialRuleEntity("b",rule.id,card.id,rule.mode.name,version,rule.effectiveFrom,Json.encodeToString(rule),0)
    private fun graph(vararg rules:FinancialRuleVersion)=fixture.graph.copy(plans=emptyList(),bills=emptyList(),rules=rules.map{row(it)})
    private fun snapshot(choice:String?="SINGLE",periods:Int?=null,date:String="2026-12-01"):BookSnapshot {
        val rule=RecurrenceEntity("b","galaxy","Galaxy Care",card.id,"cat","JPY",1091,1091,false,"MONTHLY",date,date,cardPaymentChoice=choice,cardPaymentPeriods=periods)
        return fixture.snapshot().copy(accounts=listOf(bank,card),recurrences=listOf(rule))
    }
    @Test fun singleUsesPurchaseDateRuleAndBankDebitDateForCashRiskAndImportantItems() {
        val s=snapshot()
        val f=graph(single,single.copy(id="later",effectiveFrom="2026-12-02",billing=single.billing!!.copy(paymentDay=20)))
        val t=s.cashTimeline(f,today,through)
        val i=t.steps.single().item
        assertEquals("2027-01-10",i.date);assertEquals(i.date,i.scheduledDate)
        assertEquals(bank.id,i.accountId);assertEquals(-1091L,i.accountDeltaMinor);assertTrue(i.estimated)
        assertEquals("JCB · Galaxy Care",i.name);assertNull(i.issue)
        assertTrue(s.financialAttention(f,today.toString(),t,emptySet()).isEmpty())
        assertEquals(i,s.importantPayments(f,t).single().item)
        assertEquals(91L,t.accountRisks().single().maximumGapMinor)
        assertTrue(s.cashTimeline(f,today,LocalDate.parse("2027-01-09")).steps.isEmpty())
        val insurance=s.recurrences.single().copy(id="fire",name="火灾保险",amountMinor=4000,baseMinor=4000,frequency="YEARLY",anchorDate="2027-01-03",endDate=null)
        val annual=s.copy(recurrences=listOf(insurance)).cashTimeline(graph(single),today,through).steps.single().item
        assertEquals("2027-02-10",annual.scheduledDate);assertEquals(-4000L,annual.accountDeltaMinor)
    }
    @Test fun installmentAndBonusUseTheRecordedPurchaseScheduleAndAmounts() {
        val installment=single.copy(id="installment",mode=FinanceMode.INSTALLMENT,installment=InstallmentRule(listOf(3),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=201,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"))
        val s=snapshot("INSTALLMENT",3)
        val items=s.cashTimeline(graph(installment),today,through).steps.map{it.item}
        assertEquals(listOf("2027-01-10","2027-02-10","2027-03-10"),items.map{it.date})
        assertEquals(listOf(-430L,-430L,-432L),items.map{it.accountDeltaMinor})
        val unsupported=snapshot("INSTALLMENT",5).cashTimeline(graph(installment),today,through).steps.single().item
        assertEquals("CARD_INSTALLMENT_PERIODS_UNSUPPORTED",unsupported.issue);assertNull(unsupported.accountDeltaMinor)
        val bonus=single.copy(bonusPayments=listOf(BonusPaymentWindow(12,1,12,31,3,10)))
        val b=snapshot("BONUS").cashTimeline(graph(bonus),today,through).steps.single().item
        assertEquals("2027-03-10",b.date);assertEquals(-1091L,b.accountDeltaMinor)
    }
    @Test fun automaticAndExplicitRevolvingDoNotCreateSeparateCashOrAttention() {
        val revolving=single.copy(id="revolving",mode=FinanceMode.REVOLVING,revolving=RevolvingRule(automatic=true))
        for(choice in listOf("REVOLVING","ACCOUNT_RULE")) {
            val s=snapshot(choice);val f=graph(single,revolving);val t=s.cashTimeline(f,today,through)
            assertTrue(t.steps.isEmpty());assertTrue(s.financialAttention(f,today.toString(),t,emptySet()).isEmpty())
        }
        val f=graph(single,revolving.copy(revolving=RevolvingRule(automatic=false)))
        assertEquals(-1091L,snapshot("ACCOUNT_RULE").cashTimeline(f,today,through).steps.single().item.accountDeltaMinor)
    }
    @Test fun missingRulesAndInactivePayersHaveActionableCardAttention() {
        val s=snapshot()
        fun check(snapshot:BookSnapshot,f:FinancialGraph,reason:String) {
            val t=snapshot.cashTimeline(f,today,through);val attention=snapshot.financialAttention(f,today.toString(),t,emptySet()).single()
            assertEquals(reason,attention.reason);assertEquals("CARD_RECURRENCE",attention.kind)
            assertEquals(card.id,attention.accountId);assertEquals(card.id,attention.sourceId)
            assertNull(t.steps.single().item.accountDeltaMinor)
        }
        check(s,graph(),"CARD_SINGLE_RULE_INCOMPLETE")
        check(s,graph(single.copy(billing=single.billing!!.copy(paymentAccountId=null))),"CARD_PAYMENT_ACCOUNT_REQUIRED")
        check(s.copy(accounts=listOf(bank.copy(active=false),card)),graph(single),"CARD_PAYMENT_ACCOUNT_INACTIVE")
        check(snapshot(null),graph(single),"CARD_PAYMENT_CHOICE_REQUIRED")
        check(snapshot("ACCOUNT_RULE"),graph(single),"CARD_AUTOMATIC_RULE_INCOMPLETE")
    }
    @Test fun bankCurrencyUsesReferenceRateAndRecordedOccurrenceIsReplacedByRealBill() {
        val s=snapshot().copy(accounts=listOf(bank.copy(currency="CNY"),card))
        val f=graph(single)
        val rate=ReferenceRateSnapshot(Currency.JPY,Currency.CNY,"0.05","2026-11-04","2026-11-04T00:00:00Z")
        val t=s.cashTimeline(f,today,through,referenceRates=listOf(rate))
        assertEquals(-5455L,t.steps.single().item.accountDeltaMinor);assertEquals(rate,t.steps.single().item.referenceRate)
        assertTrue(s.financialAttention(f,today.toString(),t,emptySet()).isEmpty())
        val occurrence=s.forecastOccurrences(today,through).single().copy(status="COMPLETED",eventId="purchase")
        val plan=fixture.plan.copy(origin="CARD_ENTRY",sourceEventId="purchase",principalMinor=1091)
        val bill=fixture.bill.copy(confirmedDate="2027-01-10",confirmedTotalMinor=1091,confirmedPrincipalMinor=1091)
        val purchase=EventEntity("b","purchase","EXPENSE",card.id,"cat",1091,"JPY",1091,"2026-12-01","Galaxy Care","",cardPaymentChoice="SINGLE",cardPaymentMode="SINGLE")
        val recorded=s.copy(occurrences=listOf(occurrence),events=listOf(purchase)).cashTimeline(f.copy(plans=listOf(plan),bills=listOf(bill)),today,through,referenceRates=listOf(rate))
        assertEquals(listOf("BILL"),recorded.steps.map{it.item.kind});assertEquals(-5455L,recorded.steps.single().item.accountDeltaMinor)
    }
}
