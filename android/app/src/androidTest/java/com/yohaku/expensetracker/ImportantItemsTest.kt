package com.yohaku.expensetracker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class ImportantItemsTest {
    @Test fun effectiveDeadlinesAndPendingClearingDisappearOnlyWhenTheirObligationsAreResolved()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneId.of("Asia/Tokyo")))
            val b=repo.createBook("重要事項",Currency.JPY)
            val bank=repo.saveAccount(b,"引落",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100),"2026-10-01")
            val payer=repo.saveAccount(b,"支払済口座",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
            val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"今期",FinanceMode.SINGLE,
                listOf(KnownBillInput("2026-10-26",1000,1000,0,bank)),1000,complete=true,opening=true)))!!
            val bill=db.financialDao().bills(b,plan).single()
            val rule=FinancialRuleVersion("",b,card,FinanceMode.INSTALLMENT,"2026-10-01","Asia/Tokyo",
                deadlines=listOf(ChangeDeadline(bill.id,FinanceMode.INSTALLMENT,"2026-10-18","18:00","Asia/Tokyo")))
            repo.commitFinancialRule(repo.previewFinancialRule(b,card,rule))
            repo.commitFinancialRule(repo.previewFinancialRule(b,card,rule.copy(deadlines=rule.deadlines.map{it.copy(date="2026-10-20")})))
            repo.commitFinancialRule(repo.previewFinancialRule(b,card,rule.copy(effectiveFrom="2026-11-01",deadlines=rule.deadlines.map{it.copy(date="2026-11-20")})))
            var s=repo.snapshot(repo.dao.book(b)!!).first();var f=db.financialDao().graph(b).first()!!
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
            val payment=FinancialRepaymentInput(payer,card,Money(Currency.JPY,1000),"2026-10-15",Money(Currency.JPY,1000),Money(Currency.JPY,1000),
                Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=false,allocations=listOf(RepaymentAllocationInput(bill.id,null,1000,0,0)))
            val event=repo.commitFinancialLedger(repo.previewRepayment(b,payment))!!
            s=repo.snapshot(repo.dao.book(b)!!).first();f=db.financialDao().graph(b).first()!!
            val timeline=s.cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-11-30"))
            val important=s.importantPayments(f,timeline).single()
            assertEquals(1000L,important.outstandingMinor);assertEquals(0L,important.item.amountMinor)
            assertEquals("PENDING_CLEARING",important.item.issue);assertTrue(important.confirmed)
            assertEquals(1,s.financialDeadlines(f,"2026-10-15","2026-11-30").size)
            assertEquals(9000L,s.balance(s.accounts.first{it.id==payer}));assertEquals(1000L,s.balance(s.accounts.first{it.id==card}))
            repo.commitFinancialLedger(repo.previewRepayment(b,payment.copy(eventId=event,platformConfirmed=true,clearingDate="2026-10-15")))
            s=repo.snapshot(repo.dao.book(b)!!).first();f=db.financialDao().graph(b).first()!!
            assertTrue(s.importantPayments(f,s.cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-11-30"))).isEmpty())
            assertTrue(s.financialDeadlines(f,"2026-10-15","2026-11-30").isEmpty())
            assertEquals(bill,db.financialDao().bill(b,bill.id));assertEquals(1,s.events.size)
            assertEquals(9000L,s.balance(s.accounts.first{it.id==payer}));assertEquals(0L,s.balance(s.accounts.first{it.id==card}))
        }finally{db.close()}
    }
}
