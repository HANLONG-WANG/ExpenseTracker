package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ImportantItemsTest {
    @Test fun effectiveDeadlinesAndPendingClearingDisappearOnlyWhenTheirObligationsAreResolved() {
        val fixture=CashProjectionFixture(1000,"2026-10-26",bankOpening=100,payerOpening=10000)
        val b="b";val card=fixture.card.id;val payer=fixture.payer.id;val bill=fixture.bill
        val rule=FinancialRuleVersion("rule",b,card,FinanceMode.INSTALLMENT,"2026-10-01","Asia/Tokyo",
        deadlines=listOf(ChangeDeadline(bill.id,FinanceMode.INSTALLMENT,"2026-10-18","18:00","Asia/Tokyo")))
        fun entity(id:String,version:Int,rule:FinancialRuleVersion)=FinancialRuleEntity(b,id,card,"INSTALLMENT",version,rule.effectiveFrom,kotlinx.serialization.json.Json.encodeToString(FinancialRuleVersion.serializer(),rule),version.toLong())
        val f=fixture.graph.copy(rules=listOf(entity("old",1,rule),entity("current",2,rule.copy(deadlines=rule.deadlines.map{it.copy(date="2026-10-20")})),
        entity("future",3,rule.copy(effectiveFrom="2026-11-01",deadlines=rule.deadlines.map{it.copy(date="2026-11-20")}))))
        var s=fixture.snapshot()
        assertEquals("2026-10-20",s.financialDeadlines(f,"2026-10-15","2027-01-15").single().deadline.date)
        assertEquals("2026-10-20",s.financialDeadlines(f,"2026-10-21","2027-01-15").single().deadline.date)
        assertTrue(s.financialDeadlines(f,"2026-10-15","2026-10-19").isEmpty())
        // Moving all principal leaves the original fees payable, but no principal to rearrange.
        val feeBill=bill.copy(confirmedTotalMinor=1200,confirmedFeeMinor=200)
        val feeGraph=f.copy(bills=listOf(feeBill))
        val moved=s.copy(billAdjustments=listOf(FinancialBillAdjustmentEntity(b,"adjustment","arrangement",bill.id,1000,0)))
        assertEquals(200L,moved.billOutstanding(feeBill).totalMinor)
        assertTrue(moved.financialDeadlines(feeGraph,"2026-10-15","2026-11-30").isEmpty())
        assertEquals(200L,moved.importantPayments(feeGraph,moved.cashTimeline(feeGraph,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-11-30"))).single().outstandingMinor)
        s=fixture.pending(s,1000,payer)
        val timeline=s.cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-11-30"))
        val important=s.importantPayments(f,timeline).single()
        assertEquals(1000L,important.outstandingMinor);assertEquals(0L,important.item.amountMinor)
        assertEquals("PENDING_CLEARING",important.item.issue);assertTrue(important.confirmed)
        assertEquals(1,s.financialDeadlines(f,"2026-10-15","2026-11-30").size)
        assertEquals(9000L,s.balance(s.accounts.first{it.id==payer}));assertEquals(1000L,s.balance(s.accounts.first{it.id==card}))
        s=s.copy(repayments=s.repayments.map{it.copy(platformConfirmed=true,clearingDate="2026-10-15")},
        postings=s.postings+PostingEntity(b,"credit","payment",card,-1000,"JPY","2026-10-15"))
        assertTrue(s.importantPayments(f,s.cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-11-30"))).isEmpty())
        assertTrue(s.financialDeadlines(f,"2026-10-15","2026-11-30").isEmpty())
        assertEquals(bill,f.bills.single());assertEquals(1,s.events.size)
        assertEquals(9000L,s.balance(s.accounts.first{it.id==payer}));assertEquals(0L,s.balance(s.accounts.first{it.id==card}))
    }
}
