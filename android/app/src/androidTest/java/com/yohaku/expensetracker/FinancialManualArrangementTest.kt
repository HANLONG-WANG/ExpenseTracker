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
class FinancialManualArrangementTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    @Test fun platformScheduleNeedsNoRuleAndKeepsUnknownBreakdownsActualPaymentsAndReopenedHistory()=runBlocking {
        val file="manual-arrangement-reopen.db";context.deleteDatabase(file)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,file).build()
        try {
            var r=LedgerRepository(db,clock);val b=r.createBook("手動変更",Currency.JPY,startDate="2026-10-01")
            val bank=r.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val card=r.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
            val old=r.commitManualFinancialPlan(r.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"原一括",FinanceMode.SINGLE,listOf(KnownBillInput("2026-11-05",1000,1000,0,bank)),1000,complete=true,opening=true)))!!
            val original=db.financialDao().bills(b,old).single()
            val input=FinancialArrangementInput(old,"平台两期",FinanceMode.INSTALLMENT,FinancialScheduleTerms("2026-10-25",listOf("2026-11-05","2026-12-05")),bank,OriginalFeeTreatment.CANCELLED_BY_PLATFORM,100,
                manualPeriods=listOf(KnownBillInput("2026-11-05",520,500,20),KnownBillInput("2026-12-05",520,500,20)),manualComplete=true)
            assertTrue(r.previewFinancialArrangement(b,input.copy(manualPeriods=null)).calculation is Calculation.Incomplete)
            val review=r.previewFinancialArrangement(b,input)
            assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready)
            assertEquals(listOf(620L,520L),review.bills.map{it.totalMinor});assertEquals(140L,review.comparison!!.totalExtraFeeMinor)
            assertEquals(0L,review.comparison!!.proposedRemainingPrincipalMinor)
            val target=r.commitFinancialArrangement(review,clock.instant().toString(),true)!!
            val first=db.financialDao().bills(b,target).first()
            val pay=r.commitFinancialLedger(r.previewRepayment(b,FinancialRepaymentInput(bank,card,Money(Currency.JPY,620),"2026-11-05",Money(Currency.JPY,620),Money(Currency.JPY,500),Money(Currency.JPY,0),Money(Currency.JPY,120),platformConfirmed=true,clearingDate="2026-11-05",allocations=listOf(RepaymentAllocationInput(first.id,null,500,0,120)))))!!
            assertEquals(review.bills,db.financialDao().bills(b,target))
            r=LedgerRepository(db,Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"),ZoneId.of("Asia/Tokyo")))
            val partial=FinancialArrangementInput(target,"平台下一期",FinanceMode.SINGLE,FinancialScheduleTerms("2026-11-06",listOf("2026-12-05")),bank,OriginalFeeTreatment.CANCELLED_BY_PLATFORM,0,
                manualPeriods=listOf(KnownBillInput("2026-12-05",300)),manualComplete=false)
            assertThrows(IllegalArgumentException::class.java){runBlocking{r.previewFinancialArrangement(b,partial.copy(manualComplete=true))}}
            val next=r.previewFinancialArrangement(b,partial)
            assertEquals(500L,next.target!!.principalMinor);assertFalse(next.target!!.complete)
            assertNull(next.bills.single().principalMinor);assertNull(next.bills.single().feeMinor)
            assertNull(next.comparison!!.totalExtraFeeMinor);assertNull(next.comparison!!.proposedRemainingPrincipalMinor)
            val current=r.commitFinancialArrangement(next,"2026-11-06T00:00:00Z",true)!!
            db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,file).build();r=LedgerRepository(db,r.clock)
            val s=r.snapshot(r.dao.book(b)!!).first();val f=db.financialDao().graph(b).first()!!
            assertEquals(9380L,s.balance(s.accounts.first{it.id==bank}));assertEquals(500L,s.balance(s.accounts.first{it.id==card}));assertEquals(120L,s.monthExpense("2026-11"));assertEquals(pay,s.events.single().id)
            assertEquals(original,db.financialDao().bill(b,original.id));assertNull(f.plans.first{it.id==current}.termsJson)
            assertFalse(r.financialPredictionStale(s,f,f.plans.first{it.id==current}))
            val archive=r.json.decodeFromString<FinancialPlanArchive>(db.financialDao().planRevisions(b,target).first().first{it.reason=="ARRANGEMENT_APPLIED"}.json)
            assertEquals(listOf(620L,520L),archive.bills.map{it.totalMinor})
        }finally{db.close();context.deleteDatabase(file)}
    }
    @Test fun selectedPoolPrincipalCanUseAPlatformScheduleWithoutAnInstallmentRule()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val r=LedgerRepository(db,clock);val b=r.createBook("池から手動",Currency.JPY,startDate="2026-10-01")
            val bank=r.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val card=r.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,3000),"2026-10-01")
            r.commitFinancialRule(r.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.REVOLVING,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,1000,allocation=AllocationOrder.FEES_FIRST,automatic=true))))
            val definition=FinancialPredictionInput(card,"原池",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-10-01",listOf("2026-11-05","2026-12-05","2027-01-05"),listOf(AccrualWindow("2026-10-01","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"),AccrualWindow("2026-12-01","2026-12-31"))),paymentAccountId=bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(3000,PoolSourceDate.TRANSACTION))
            val pool=r.commitFinancialPrediction(r.previewFinancialPool(b,definition))!!
            val bill=db.financialDao().bills(b,pool).first()
            val review=r.previewFinancialArrangement(b,FinancialArrangementInput(pool,"平台分两期",FinanceMode.INSTALLMENT,FinancialScheduleTerms("2026-10-25",listOf("2026-11-05","2026-12-05")),bank,OriginalFeeTreatment.RETAIN,40,
                poolSelection=PoolArrangementSelection(listOf(PoolBillTransfer(bill.id,500))),manualPeriods=listOf(KnownBillInput("2026-11-05",260,250,10),KnownBillInput("2026-12-05",260,250,10)),manualComplete=true))
            assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready);assertEquals(60L,review.comparison!!.totalExtraFeeMinor)
            val target=r.commitFinancialArrangement(review,clock.instant().toString(),true)!!
            val f=db.financialDao().graph(b).first()!!;val s=r.snapshot(r.dao.book(b)!!).first()
            assertEquals(listOf(300L,260L),f.bills.filter{it.planId==target}.map{it.totalMinor});assertTrue(f.plans.first{it.id==target}.complete)
            assertEquals(500L,s.billOutstanding(f.bills.first{it.id==bill.id}).principalMinor)
            assertFalse(r.financialPredictionStale(s,f,f.plans.first{it.id==pool}));assertTrue(s.events.isEmpty());assertEquals(3000L,s.balance(s.accounts.first{it.id==card}))
        }finally{db.close()}
    }
}
