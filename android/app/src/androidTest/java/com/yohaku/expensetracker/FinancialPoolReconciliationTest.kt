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
class FinancialPoolReconciliationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    private data class Fixture(val r:LedgerRepository,val b:String,val bank:String,val card:String,val category:String,val source:String,val pool:String,val bill:String,val target:String) {
        suspend fun s()=r.snapshot(r.dao.book(b)!!).first()
        suspend fun f()=r.db.financialDao().graph(b).first()!!
        fun input(principal:Long,oldDue:Long,refunds:List<ReconciledRefundShare> = emptyList(),plan:String=target)=ArrangementReconciliationInput(plan,"2026-10-25",principal,listOf(ReconciledBillObligation(bill,oldDue,0)),refunds)
        suspend fun poolPrincipal():Long {val snapshot=s();return f().bills.filter{it.planId==pool&&it.active}.sumOf{snapshot.billOutstanding(it).principalMinor!!}}
    }
    private suspend fun fixture(db:YohakuDatabase):Fixture {
        val r=LedgerRepository(db,clock);val b=r.createBook("池重核",Currency.JPY,startDate="2026-10-01")
        val bank=r.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
        val card=r.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,2000),"2026-10-01")
        val category=r.saveCategory(b,"利用",EntryKind.EXPENSE,null)
        val source=r.saveEntry(b,EntryDraft(accountId=card,categoryId=category,amount="1000",date="2026-10-02",cardPaymentChoice="REVOLVING"))
        r.commitFinancialRule(r.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.REVOLVING,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,1000,allocation=AllocationOrder.FEES_FIRST,automatic=true))))
        r.commitFinancialRule(r.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.INSTALLMENT,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),installment=InstallmentRule(listOf(2),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=40,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"))))
        val dates=listOf("2026-11-05","2026-12-05","2027-01-05","2027-02-05")
        val windows=listOf(AccrualWindow("2026-10-01","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"),AccrualWindow("2026-12-01","2026-12-31"),AccrualWindow("2027-01-01","2027-01-31"))
        val pool=r.commitFinancialPrediction(r.previewFinancialPool(b,FinancialPredictionInput(card,"原池",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-10-01",dates,windows),paymentAccountId=bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(2000,PoolSourceDate.TRANSACTION))))!!
        val first=db.financialDao().bills(b,pool).first()
        r.commitFinancialBillConfirmation(r.previewFinancialBillConfirmation(b,first.id,KnownBillInput(first.date,1000,1000,0,bank)))
        r.commitFinancialPrediction(r.previewFinancialPool(b,r.json.decodeFromString<FinancialPredictionInput>(db.financialDao().plans(b,card).first{it.id==pool}.termsJson!!)))
        val target=r.commitFinancialArrangement(r.previewFinancialArrangement(b,FinancialArrangementInput(pool,"转出",FinanceMode.INSTALLMENT,FinancialScheduleTerms("2026-10-25",dates.take(2)),bank,OriginalFeeTreatment.RETAIN,0,poolSelection=PoolArrangementSelection(listOf(PoolBillTransfer(first.id,500))))),clock.instant().toString(),true)!!
        return Fixture(r,b,bank,card,category,source,pool,first.id,target)
    }
    @Test fun changedSourceReconcilesBothSchedulesAtomicallyAndFutureAdditionsStayInThePool()=runBlocking {
        val file="pool-review-reopen.db";context.deleteDatabase(file)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,file).build()
        try {
            var x=fixture(db);val r=x.r;val applied=x.f().arrangements.single();val raw=x.f().bills.first{it.id==x.bill}
            r.saveEntry(x.b,EntryDraft(id=x.source,accountId=x.card,categoryId=x.category,amount="1200",date="2026-10-02",cardPaymentChoice="REVOLVING"))
            assertTrue(r.financialPredictionStale(x.s(),x.f(),x.f().plans.first{it.id==x.target}))
            val before=x.s();val review=r.previewArrangementReconciliation(x.b,x.input(700,300))
            assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready)
            assertTrue(review.poolPrediction?.calculation.toString(),review.poolPrediction?.calculation is Calculation.Ready)
            assertEquals(listOf(350L,350L),review.prediction!!.bills.map{it.principalMinor})
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_pool_review BEFORE UPDATE ON financial_plans WHEN NEW.id='${x.pool}' BEGIN SELECT RAISE(ABORT,'review rollback'); END")
            assertThrows(android.database.sqlite.SQLiteException::class.java){runBlocking{r.commitArrangementReconciliation(review,true)}}
            assertEquals(before,x.s());assertNull(x.f().plans.first{it.id==x.target}.reconciliationJson)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_pool_review")
            r.commitArrangementReconciliation(review,true)
            assertEquals(2500L,x.poolPrincipal());assertEquals(300L,x.s().billOutstanding(raw).principalMinor)
            assertEquals(before.storedPostings,x.s().storedPostings);assertEquals(before.expenseComponents,x.s().expenseComponents);assertEquals(applied,x.f().arrangements.single())
            db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,file).build();x=x.copy(r=LedgerRepository(db,clock))
            for(plan in x.f().plans.filter{it.id in listOf(x.pool,x.target)})assertFalse(x.r.financialPredictionStale(x.s(),x.f(),plan))
            x.r.saveEntry(x.b,EntryDraft(accountId=x.card,categoryId=x.category,amount="100",date="2026-10-26",cardPaymentChoice="REVOLVING"))
            assertEquals(2600L,x.poolPrincipal());assertEquals(raw,x.f().bills.first{it.id==x.bill})
            assertFalse(x.r.financialPredictionStale(x.s(),x.f(),x.f().plans.first{it.id==x.target}))
            assertEquals(3300L,x.s().balance(x.s().accounts.first{it.id==x.card}));assertEquals(10000L,x.s().balance(x.s().accounts.first{it.id==x.bank}))
        }finally{db.close();context.deleteDatabase(file)}
    }
    @Test fun refundCanBeSplitEditedAndDeletedWithoutDoubleReducingThePool()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val x=fixture(db);val r=x.r
            val refund=r.saveRefund(x.b,RefundInput(x.source,Money(Currency.JPY,100),x.card,Money(Currency.JPY,100),Money(Currency.JPY,100),"2026-10-27",true))
            val before=x.s()
            val review=r.previewArrangementReconciliation(x.b,x.input(500,450,listOf(ReconciledRefundShare(refund,50,listOf(ReconciledRefundBill(x.bill,50))))))
            assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready);assertTrue(review.poolPrediction?.calculation.toString(),review.poolPrediction?.calculation is Calculation.Ready)
            r.commitArrangementReconciliation(review,true)
            assertEquals(2450L,x.poolPrincipal());assertEquals(listOf(250L,200L),x.f().bills.filter{it.planId==x.target&&it.active}.map{it.principalMinor})
            assertEquals(before.storedPostings,x.s().storedPostings)
            r.saveRefund(x.b,RefundInput(x.source,Money(Currency.JPY,150),x.card,Money(Currency.JPY,150),Money(Currency.JPY,150),"2026-10-27",true,refund))
            val changed=r.previewArrangementReconciliation(x.b,x.input(500,450,listOf(ReconciledRefundShare(refund,100,listOf(ReconciledRefundBill(x.bill,50))))))
            assertTrue(changed.calculation.toString(),changed.calculation is Calculation.Ready);r.commitArrangementReconciliation(changed,true)
            assertEquals(2450L,x.poolPrincipal());assertEquals(400L,x.f().plans.first{it.id==x.target}.principalMinor)
            r.deleteEntry(x.b,refund)
            val removed=r.previewArrangementReconciliation(x.b,x.input(500,500))
            assertTrue(removed.calculation.toString(),removed.calculation is Calculation.Ready);r.commitArrangementReconciliation(removed,true)
            assertEquals(2500L,x.poolPrincipal());assertEquals(500L,x.f().plans.first{it.id==x.target}.principalMinor)
            assertEquals(3000L,x.s().balance(x.s().accounts.first{it.id==x.card}));assertEquals(1,x.s().visibleEvents.size)
        }finally{db.close()}
    }
    @Test fun twoTransfersShareOneRefundAndCanBeReviewedWithoutBlockingEachOther()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val x=fixture(db);val r=x.r
            val second=r.commitFinancialArrangement(r.previewFinancialArrangement(x.b,FinancialArrangementInput(x.pool,"再转出",FinanceMode.INSTALLMENT,FinancialScheduleTerms("2026-10-25",listOf("2026-11-05","2026-12-05")),x.bank,OriginalFeeTreatment.RETAIN,0,poolSelection=PoolArrangementSelection(listOf(PoolBillTransfer(x.bill,300))))),clock.instant().toString(),true)!!
            val refund=r.saveRefund(x.b,RefundInput(x.source,Money(Currency.JPY,100),x.card,Money(Currency.JPY,100),Money(Currency.JPY,100),"2026-10-27",true))
            val first=r.previewArrangementReconciliation(x.b,x.input(500,160,listOf(ReconciledRefundShare(refund,60,listOf(ReconciledRefundBill(x.bill,40))))))
            assertTrue(first.calculation.toString(),first.calculation is Calculation.Ready);assertTrue(first.poolPrediction?.calculation is Calculation.Incomplete)
            r.commitArrangementReconciliation(first,true)
            val wrong=r.previewArrangementReconciliation(x.b,x.input(300,200,listOf(ReconciledRefundShare(refund,50)),second))
            assertEquals(Calculation.Incomplete(listOf("arrangement.reviewRefundShares")),wrong.calculation)
            val next=r.previewArrangementReconciliation(x.b,x.input(300,200,listOf(ReconciledRefundShare(refund,40)),second))
            assertTrue(next.calculation.toString(),next.calculation is Calculation.Ready);assertTrue(next.poolPrediction?.calculation.toString(),next.poolPrediction?.calculation is Calculation.Ready)
            r.commitArrangementReconciliation(next,true)
            assertEquals(2200L,x.poolPrincipal());assertEquals(700L,x.f().plans.filter{it.id in listOf(x.target,second)}.sumOf{it.principalMinor!!})
            for(plan in x.f().plans.filter{it.id in listOf(x.target,second,x.pool)})assertFalse(r.financialPredictionStale(x.s(),x.f(),plan))
            assertEquals(2900L,x.s().balance(x.s().accounts.first{it.id==x.card}));assertEquals(2,x.s().visibleEvents.size)
        }finally{db.close()}
    }
    @Test fun earlierUnallocatedClearingCanBeClassifiedDeletedAndRestoredWithoutADeadEnd()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val x=fixture(db);val r=x.r
            val payment=r.commitFinancialLedger(r.previewRepayment(x.b,FinancialRepaymentInput(x.bank,x.card,Money(Currency.JPY,100),"2026-10-20",Money(Currency.JPY,100),Money(Currency.JPY,100),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-10-20")))!!
            assertNull(r.reconciliationSource(x.s(),x.f(),x.f().currentArrangements.single()).grossMinor)
            assertEquals(Calculation.Incomplete(listOf("pool.repaymentShare")),r.previewArrangementReconciliation(x.b,x.input(500,500)).calculation)
            suspend fun review(shares:Map<String,Long>,expected:Long) {
                val before=x.s()
                val preview=r.previewArrangementReconciliation(x.b,x.input(500,500).copy(poolUnallocatedShares=shares))
                assertTrue(preview.calculation.toString(),preview.calculation is Calculation.Ready);assertTrue(preview.poolPrediction?.calculation.toString(),preview.poolPrediction?.calculation is Calculation.Ready)
                r.commitArrangementReconciliation(preview,true)
                assertEquals(expected,x.poolPrincipal());assertEquals(before.storedPostings,x.s().storedPostings);assertEquals(before.repaymentAllocations,x.s().repaymentAllocations)
                assertFalse(r.financialPredictionStale(x.s(),x.f(),x.f().plans.first{it.id==x.target}))
            }
            review(mapOf(payment to 100L),2400)
            r.deleteEntry(x.b,payment);review(emptyMap(),2500)
            r.restoreEntry(x.b,payment);review(mapOf(payment to 100L),2400)
            assertEquals(9900L,x.s().balance(x.s().accounts.first{it.id==x.bank}));assertEquals(2900L,x.s().balance(x.s().accounts.first{it.id==x.card}))
        }finally{db.close()}
    }

}
