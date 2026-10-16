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
class FinancialReconciliationChainTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    private data class Fixture(val r:LedgerRepository,val b:String,val bank:String,val card:String,val category:String,val source:String,val root:String,val parent:String,val child:String) {
        suspend fun s()=r.snapshot(r.dao.book(b)!!).first()
        suspend fun f()=r.db.financialDao().graph(b).first()!!
        suspend fun input(target:String,principal:Long,refunds:List<ReconciledRefundShare> = emptyList()):ArrangementReconciliationInput {
            val f=f();val s=s();val old=f.arrangements.first{it.targetPlanId==target}.originalPlanId
            val required=f.bills.filter{it.active&&(it.planId==old||it.planId==target&&(it.confirmed||s.repaymentAllocations.any{a->a.billId==it.id}||s.billAdjustments.any{a->a.billId==it.id}))}
            return ArrangementReconciliationInput(target,if(target==parent)"2026-10-25"else"2026-10-27",principal,required.map{b->s.billOutstanding(b).let{ReconciledBillObligation(b.id,it.principalMinor,it.feeMinor)}},refunds)
        }
    }
    private suspend fun fixture(db:YohakuDatabase,manual:Boolean):Fixture {
        val r=LedgerRepository(db,clock);val b=r.createBook("連続変更",Currency.JPY,startDate="2026-10-01")
        val bank=r.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
        val card=r.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
        val category=r.saveCategory(b,"利用",EntryKind.EXPENSE,null)
        val source=r.saveEntry(b,EntryDraft(accountId=card,categoryId=category,amount="1000",date="2026-10-01",cardPaymentChoice="SINGLE"))
        val root=r.commitManualFinancialPlan(r.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"元の一括",FinanceMode.SINGLE,listOf(KnownBillInput("2026-11-05",1000,1000,0,bank)),1000,complete=true,sourceEventId=source)))!!
        r.commitFinancialRule(r.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.INSTALLMENT,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),installment=InstallmentRule(listOf(2),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=40,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"))))
        val dates=listOf("2026-11-05","2026-12-05")
        val parent=r.commitFinancialArrangement(r.previewFinancialArrangement(b,FinancialArrangementInput(root,"二回分割",FinanceMode.INSTALLMENT,FinancialScheduleTerms("2026-10-25",dates),bank,OriginalFeeTreatment.RETAIN,0)),clock.instant().toString(),true)!!
        if(!manual)r.commitFinancialRule(r.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.REVOLVING,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.TOTAL_PAYMENT,1000,allocation=AllocationOrder.FEES_FIRST,automatic=true))))
        val child=r.commitFinancialArrangement(r.previewFinancialArrangement(b,FinancialArrangementInput(parent,"次の変更",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-10-27",dates,if(manual)emptyList()else listOf(AccrualWindow("2026-10-27","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"))),bank,OriginalFeeTreatment.RETAIN,if(manual)0 else 100,
            manualPeriods=if(manual)listOf(KnownBillInput(dates[0],600),KnownBillInput(dates[1],500))else null)),clock.instant().toString(),true)!!
        return Fixture(r,b,bank,card,category,source,root,parent,child)
    }
    @Test fun unknownManualSplitsSurviveSequentialReviewReopeningAndLaterExplicitBillConfirmation()=runBlocking {
        val name="reconciliation-chain.db";context.deleteDatabase(name);var db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build()
        try {
            var x=fixture(db,true);val r=x.r;val actions=x.f().arrangements
            r.saveEntry(x.b,EntryDraft(id=x.source,accountId=x.card,categoryId=x.category,amount="1200",date="2026-10-02",cardPaymentChoice="SINGLE"))
            val cash=x.s();val childInput=x.input(x.child,1200)
            assertEquals(Calculation.Incomplete(listOf("arrangement.parentReview")),r.previewArrangementReconciliation(x.b,childInput).calculation)
            val parent=r.previewArrangementReconciliation(x.b,x.input(x.parent,1200))
            assertTrue(parent.calculation.toString(),parent.calculation is Calculation.Ready);r.commitArrangementReconciliation(parent,true)
            assertEquals(Calculation.Incomplete(listOf("arrangement.reviewManualComplete")),r.previewArrangementReconciliation(x.b,childInput.copy(manualComplete=true)).calculation)
            val child=r.previewArrangementReconciliation(x.b,childInput.copy(manualComplete=false))
            assertTrue(child.calculation.toString(),child.calculation is Calculation.Ready)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_child_review BEFORE UPDATE ON financial_plans WHEN NEW.id='${x.child}' BEGIN SELECT RAISE(ABORT,'child rollback'); END")
            assertThrows(android.database.sqlite.SQLiteException::class.java){runBlocking{r.commitArrangementReconciliation(child,true)}}
            assertNull(x.f().plans.first{it.id==x.child}.reconciliationJson)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_child_review");r.commitArrangementReconciliation(child,true)
            assertEquals(cash.storedPostings,x.s().storedPostings);assertEquals(cash.expenseComponents,x.s().expenseComponents);assertEquals(actions,x.f().arrangements)
            assertTrue(x.f().bills.filter{it.planId==x.child}.all{it.principalMinor==null&&it.feeMinor==null})
            assertEquals(listOf(600L,500L),x.f().bills.filter{it.planId==x.child}.map{it.totalMinor});assertFalse(x.f().plans.first{it.id==x.child}.complete)
            assertNull(r.arrangementBaselineIssue(x.s(),x.f(),x.f().plans.first{it.id==x.parent}))
            db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build();x=x.copy(r=LedgerRepository(db,clock))
            assertFalse(x.r.financialPredictionStale(x.s(),x.f(),x.f().plans.first{it.id==x.child}))
            val bill=x.f().bills.first{it.planId==x.child&&it.number==1}
            x.r.commitFinancialBillConfirmation(x.r.previewFinancialBillConfirmation(x.b,bill.id,KnownBillInput(bill.date,600,500,100,x.bank)))
            assertEquals(Calculation.Incomplete(listOf("arrangement.reviewBillBreakdown")),x.r.previewArrangementReconciliation(x.b,childInput).calculation)
            val updated=x.r.previewArrangementReconciliation(x.b,x.input(x.child,1200).copy(manualComplete=false))
            assertTrue(updated.calculation.toString(),updated.calculation is Calculation.Ready);x.r.commitArrangementReconciliation(updated,true)
            assertEquals(500L,x.f().bills.first{it.id==bill.id}.principalMinor);assertNull(x.f().bills.first{it.planId==x.child&&it.number==2}.principalMinor)
            val second=x.f().bills.first{it.planId==x.child&&it.number==2}
            x.r.commitFinancialBillConfirmation(x.r.previewFinancialBillConfirmation(x.b,second.id,KnownBillInput(second.date,500,null,20,x.bank)))
            val partial=x.input(x.child,1200).let{input->input.copy(bills=input.bills.map{if(it.billId==second.id)it.copy(feeMinor=10)else it},manualComplete=false)}
            val feeReview=x.r.previewArrangementReconciliation(x.b,partial)
            assertTrue(feeReview.calculation.toString(),feeReview.calculation is Calculation.Ready);x.r.commitArrangementReconciliation(feeReview,true)
            val due=x.s().billOutstanding(x.f().bills.first{it.id==second.id})
            assertNull(due.principalMinor);assertEquals(10L,due.feeMinor);assertEquals(490L,due.totalMinor)
            assertEquals(500L,x.f().bills.first{it.id==second.id}.totalMinor);assertEquals(20L,x.f().bills.first{it.id==second.id}.feeMinor)
            assertEquals(1200L,x.s().balance(x.s().accounts.first{it.id==x.card}));assertEquals(10000L,x.s().balance(x.s().accounts.first{it.id==x.bank}))
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun refundDatesAcrossBothEffectiveDatesDeductPrincipalOnceAndPreserveActualClearing()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val x=fixture(db,false);val r=x.r;val actions=x.f().arrangements
            val bill=x.f().bills.first{it.planId==x.child&&it.number==1}
            r.commitFinancialLedger(r.previewRepayment(x.b,FinancialRepaymentInput(x.bank,x.card,Money(Currency.JPY,100),"2026-10-28",Money(Currency.JPY,100),Money(Currency.JPY,100),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-10-28",allocations=listOf(RepaymentAllocationInput(bill.id,null,100,0,0)))))
            r.saveEntry(x.b,EntryDraft(id=x.source,accountId=x.card,categoryId=x.category,amount="1200",date="2026-10-02",cardPaymentChoice="SINGLE"))
            suspend fun save(target:String,principal:Long,refunds:List<ReconciledRefundShare>) {
                val before=x.s();val review=r.previewArrangementReconciliation(x.b,x.input(target,principal,refunds))
                assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready);r.commitArrangementReconciliation(review,true)
                assertEquals(before.storedPostings,x.s().storedPostings);assertEquals(before.repaymentAllocations,x.s().repaymentAllocations)
            }
            save(x.parent,1200,emptyList());save(x.child,1200,emptyList())
            assertEquals(300L,x.f().bills.first{it.planId==x.child&&it.number==2}.principalMinor)
            var refund:String?=null
            for(date in listOf("2026-10-30","2026-10-26","2026-10-24")) {
                refund=r.saveRefund(x.b,RefundInput(x.source,Money(Currency.JPY,100),x.card,Money(Currency.JPY,100),Money(Currency.JPY,100),date,true,refund))
                save(x.parent,if(date=="2026-10-24")1100 else 1200,listOf(ReconciledRefundShare(refund,if(date=="2026-10-24")0 else 100)))
                save(x.child,if(date=="2026-10-30")1200 else 1100,if(date=="2026-10-24")emptyList()else listOf(ReconciledRefundShare(refund,if(date=="2026-10-30")100 else 0)))
                assertEquals(200L,x.f().bills.first{it.planId==x.child&&it.number==2}.principalMinor)
                assertNull(r.arrangementBaselineIssue(x.s(),x.f(),x.f().plans.first{it.id==x.parent}));assertFalse(r.financialPredictionStale(x.s(),x.f(),x.f().plans.first{it.id==x.child}))
                assertEquals(1000L,x.s().balance(x.s().accounts.first{it.id==x.card}));assertEquals(9900L,x.s().balance(x.s().accounts.first{it.id==x.bank}))
            }
            assertEquals(actions,x.f().arrangements);assertEquals(3,x.s().visibleEvents.size)
        }finally{db.close()}
    }
    @Test fun aPoolReturnDoesNotTurnOnePhysicalRefundIntoTwoRefunds()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val x=fixture(db,false);val r=x.r
            val dates=listOf("2026-11-05","2026-12-05")
            val windows=listOf(AccrualWindow("2026-10-01","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"))
            val pool=r.commitFinancialPrediction(r.previewFinancialPool(x.b,FinancialPredictionInput(x.card,"共同池",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-10-01",dates,windows),paymentAccountId=x.bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(0,PoolSourceDate.TRANSACTION,adoptExisting=true,adoptedFees=listOf(PoolAdoptedFee(x.f().arrangements.first{it.targetPlanId==x.child}.id,dates.first()))))))!!
            val first=x.f().bills.first{it.planId==pool&&it.date==dates.first()}
            val out=r.commitFinancialArrangement(r.previewFinancialArrangement(x.b,FinancialArrangementInput(pool,"池转出",FinanceMode.INSTALLMENT,FinancialScheduleTerms("2026-10-28",dates),x.bank,OriginalFeeTreatment.RETAIN,0,poolSelection=PoolArrangementSelection(listOf(PoolBillTransfer(first.id,500))))),clock.instant().toString(),true)!!
            val back=r.commitFinancialArrangement(r.previewFinancialArrangement(x.b,FinancialArrangementInput(out,"回原池",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-10-29",dates),x.bank,OriginalFeeTreatment.RETAIN,0,mergePoolId=pool)),clock.instant().toString(),true)!!
            val actions=x.f().arrangements
            val refund=r.saveRefund(x.b,RefundInput(x.source,Money(Currency.JPY,100),x.card,Money(Currency.JPY,100),Money(Currency.JPY,100),"2026-10-30",true))
            assertEquals(listOf(100L),r.poolPhysicalRefunds(x.s(),x.f(),x.f().plans.first{it.id==pool}).map{it.principalMinor})
            val before=x.s()
            fun parts(f:FinancialGraph,s:BookSnapshot,ids:Set<String>)=f.bills.filter{it.active&&it.planId in ids&&(it.confirmed||s.billAdjustments.any{a->a.billId==it.id}||s.repaymentAllocations.any{a->a.billId==it.id})}.map{b->s.billOutstanding(b).let{ReconciledBillObligation(b.id,it.principalMinor,it.feeMinor)}}
            val outReview=r.previewArrangementReconciliation(x.b,ArrangementReconciliationInput(out,"2026-10-28",500,parts(x.f(),x.s(),setOf(pool,out)),listOf(ReconciledRefundShare(refund,100))))
            assertTrue(outReview.calculation.toString(),outReview.calculation is Calculation.Ready);r.commitArrangementReconciliation(outReview,true)
            val backReview=r.previewArrangementReconciliation(x.b,ArrangementReconciliationInput(back,"2026-10-29",500,parts(x.f(),x.s(),setOf(pool,out)),listOf(ReconciledRefundShare(refund,100))))
            assertTrue(backReview.calculation.toString(),backReview.calculation is Calculation.Ready);assertTrue(backReview.poolPrediction?.calculation.toString(),backReview.poolPrediction?.calculation is Calculation.Ready)
            r.commitArrangementReconciliation(backReview,true)
            assertEquals(listOf(100L),r.poolPhysicalRefunds(x.s(),x.f(),x.f().plans.first{it.id==pool}).map{it.principalMinor})
            assertEquals(900L,x.f().bills.filter{it.active&&it.planId==pool}.sumOf{x.s().billOutstanding(it).principalMinor!!})
            assertFalse(r.financialPredictionStale(x.s(),x.f(),x.f().plans.first{it.id==pool}))
            assertEquals(before.storedPostings,x.s().storedPostings);assertEquals(before.expenseComponents,x.s().expenseComponents);assertEquals(actions,x.f().arrangements)
            assertEquals(900L,x.s().balance(x.s().accounts.first{it.id==x.card}));assertEquals(10000L,x.s().balance(x.s().accounts.first{it.id==x.bank}))
            r.saveEntry(x.b,EntryDraft(id=x.source,accountId=x.card,categoryId=x.category,amount="1200",date="2026-10-01",cardPaymentChoice="SINGLE"))
            val blocked=r.previewArrangementReconciliation(x.b,ArrangementReconciliationInput(out,"2026-10-28",500,parts(x.f(),x.s(),setOf(pool,out)),listOf(ReconciledRefundShare(refund,100))))
            assertEquals(Calculation.Incomplete(listOf("arrangement.reviewPoolSource")),blocked.calculation)
        }finally{db.close()}
    }

}
