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
class FinancialPoolMergeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    private data class Fixture(val r:LedgerRepository,val b:String,val bank:String,val card:String,val source:String,val pool:String,val dates:List<String>,val windows:List<AccrualWindow>,val mode:FinanceMode=FinanceMode.REVOLVING) {
        suspend fun s()=r.snapshot(r.dao.book(b)!!).first()
        suspend fun f()=r.db.financialDao().graph(b).first()!!
        fun change(fee:Long=100)=FinancialArrangementInput(source,"并入原池",mode,FinancialScheduleTerms("2026-10-25",dates,windows),bank,OriginalFeeTreatment.CANCELLED_BY_PLATFORM,fee,mergePoolId=pool,mergeFeeDate=dates.first())
    }
    private suspend fun fixture(db:YohakuDatabase,total:Boolean=true,loan:Boolean=false):Fixture {
        val r=LedgerRepository(db,clock);val b=r.createBook("合并",Currency.JPY,startDate="2026-10-01")
        val bank=r.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
        val card=r.saveAccount(b,"カード",if(loan)AccountType.LOAN else AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,3000),"2026-10-01")
        val source=r.commitManualFinancialPlan(r.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"待转原计划",if(loan)FinanceMode.FIXED_LOAN else FinanceMode.SINGLE,listOf(KnownBillInput("2026-11-05",1000,1000,0,bank)),1000,complete=true,opening=true)))!!
        if(loan) {
            r.commitFinancialLedger(r.previewBorrowing(b,FinancialBorrowingInput(card,"期初固定",FinanceMode.FIXED_LOAN,Money(Currency.JPY,1000),"2026-10-01",opening=true)))
            val contract=db.financialDao().graph(b).first()!!.contracts.single()
            r.linkFinancialContractPlan(b,contract.id,source)
            r.commitFinancialLedger(r.previewBorrowing(b,FinancialBorrowingInput(card,"期初循环",FinanceMode.CREDIT_LINE,Money(Currency.JPY,2000),"2026-10-01",opening=true)))
        }
        val mode=if(loan)FinanceMode.CREDIT_LINE else FinanceMode.REVOLVING
        r.commitFinancialRule(r.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,mode,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),if(total)PaymentMethod.TOTAL_PAYMENT else PaymentMethod.FIXED_PRINCIPAL,1000,allocation=AllocationOrder.FEES_FIRST,automatic=true),limitRecovery=if(loan)LimitRecovery.PAYMENT_DATE else null)))
        val dates=listOf("2026-11-05","2026-12-05","2027-01-05","2027-02-05")
        val windows=listOf(AccrualWindow("2026-10-01","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"),AccrualWindow("2026-12-01","2026-12-31"),AccrualWindow("2027-01-01","2027-01-31"))
        val pool=r.commitFinancialPrediction(r.previewFinancialPool(b,FinancialPredictionInput(card,"原池",mode,FinancialScheduleTerms("2026-10-01",dates,windows),paymentAccountId=bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(2000,PoolSourceDate.TRANSACTION))))!!
        return Fixture(r,b,bank,card,source,pool,dates,windows,mode)
    }
    @Test fun mergeUsesOneMonthlyTotalIncludesChangeFeeOnceAndRollsBackAllWrites()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val x=fixture(db);val r=x.r;val before=x.s();val original=x.f().bills.first{it.planId==x.source}
            val preview=r.previewFinancialArrangement(x.b,x.change())
            assertTrue(preview.calculation.toString(),preview.calculation is Calculation.Ready)
            assertEquals(900L,preview.mergedPool!!.bills.first{it.date==x.dates.first()}.principalMinor)
            assertEquals(100L,preview.mergedPool.bills.first{it.date==x.dates.first()}.feeMinor)
            assertEquals(100L,preview.comparison!!.totalExtraFeeMinor)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_merge BEFORE UPDATE ON financial_plans WHEN NEW.id='${x.pool}' BEGIN SELECT RAISE(ABORT,'merge rollback'); END")
            assertThrows(android.database.sqlite.SQLiteException::class.java){runBlocking{r.commitFinancialArrangement(preview,clock.instant().toString(),true)}}
            assertEquals(before,x.s());assertTrue(x.f().arrangements.isEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_merge")
            val member=r.commitFinancialArrangement(preview,clock.instant().toString(),true)!!
            val f=x.f();val s=x.s()
            assertEquals(original,f.bills.first{it.id==original.id});assertEquals(0L,s.billOutstanding(original).totalMinor)
            assertFalse(f.plans.first{it.id==member}.active);assertTrue(f.bills.none{it.planId==member})
            assertEquals(1,f.plans.count{it.active&&it.origin=="POOL"});assertEquals(before.storedPostings,s.storedPostings)
            assertEquals(listOf(900L,1000L,1000L,100L),f.bills.filter{it.active&&it.planId==x.pool}.sortedBy{it.date}.map{it.principalMinor})
            assertEquals(100L,f.bills.filter{it.active&&it.planId==x.pool}.sumOf{it.feeMinor!!})
            val current=r.json.decodeFromString<FinancialPredictionInput>(f.plans.first{it.id==x.pool}.termsJson!!)
            r.commitFinancialPrediction(r.previewFinancialPool(x.b,current.copy(planId=x.pool)))
            assertEquals(100L,x.f().bills.filter{it.active&&it.planId==x.pool}.sumOf{it.feeMinor!!})
            assertEquals(3000L,x.s().balance(x.s().accounts.first{it.id==x.card}));assertEquals(10000L,x.s().balance(x.s().accounts.first{it.id==x.bank}));assertEquals(0L,x.s().monthExpense("2026-10"))
        }finally{db.close()}
    }
    @Test fun laterClearingCanMoveToANewSharedPeriodWithoutAnotherCashPosting()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val x=fixture(db);val r=x.r;val oldBill=x.f().bills.first{it.planId==x.source}
            r.commitFinancialLedger(r.previewRepayment(x.b,FinancialRepaymentInput(x.bank,x.card,Money(Currency.JPY,500),"2027-01-02",Money(Currency.JPY,500),Money(Currency.JPY,500),Money(Currency.JPY,0),Money(Currency.JPY,0),clearingDate="2027-01-02",platformConfirmed=true,allocations=listOf(RepaymentAllocationInput(oldBill.id,null,500,0,0)))))
            val before=x.s();val allocation=before.repaymentAllocations.single()
            val preview=r.previewFinancialArrangement(x.b,x.change().copy(clearingMoves=listOf(ArrangementClearingMove(allocation.id,null,500,0,0,"DATE:2027-01-05"))))
            assertTrue(preview.calculation.toString(),preview.calculation is Calculation.Ready)
            val targetBill=preview.clearingChanges.single().replacements.single().billId!!
            assertTrue(x.f().bills.none{it.id==targetBill})
            r.commitFinancialArrangement(preview,clock.instant().toString(),true)!!
            val after=x.s()
            assertEquals(before.storedPostings,after.storedPostings);assertEquals(before.expenseComponents,after.expenseComponents)
            assertEquals(1,after.events.size);assertEquals(2,after.events.single().revision)
            assertEquals(500L,after.billOutstanding(x.f().bills.first{it.id==targetBill}).principalMinor)
            assertEquals(2500L,after.balance(after.accounts.first{it.id==x.card}));assertEquals(9500L,after.balance(after.accounts.first{it.id==x.bank}))
            assertFalse(r.financialPredictionStale(after,x.f(),x.f().plans.first{it.id==x.pool}))
        }finally{db.close()}
    }
    @Test fun aConfirmedSharedBillNeedsExplicitFeeCoverageAndSurvivesReopening()=runBlocking {
        val file="pool-merge-confirmed.db";context.deleteDatabase(file)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,file).build()
        try {
            var x=fixture(db);val r=x.r
            val first=x.f().bills.first{it.planId==x.pool&&it.date==x.dates.first()}
            r.commitFinancialBillConfirmation(r.previewFinancialBillConfirmation(x.b,first.id,KnownBillInput(first.date,1000,900,100,x.bank)))
            var current=r.json.decodeFromString<FinancialPredictionInput>(x.f().plans.first{it.id==x.pool}.termsJson!!)
            r.commitFinancialPrediction(r.previewFinancialPool(x.b,current.copy(planId=x.pool)))
            val confirmed=x.f().bills.first{it.id==first.id}
            assertTrue((r.previewFinancialArrangement(x.b,x.change()).calculation as Calculation.Incomplete).fields.contains("pool.mergeFeeConfirmation"))
            assertTrue((r.previewFinancialArrangement(x.b,x.change(150).copy(mergeFeeIncludedInRetained=true)).calculation as Calculation.Incomplete).fields.contains("pool.mergeFeeConfirmation"))
            val preview=r.previewFinancialArrangement(x.b,x.change().copy(mergeFeeIncludedInRetained=true))
            assertTrue(preview.calculation.toString(),preview.calculation is Calculation.Ready);assertNull(preview.comparison!!.totalExtraFeeMinor)
            r.commitFinancialArrangement(preview,clock.instant().toString(),true)!!
            assertEquals(confirmed,x.f().bills.first{it.id==first.id});assertEquals(100L,x.f().bills.filter{it.active&&it.planId==x.pool}.sumOf{it.feeMinor!!})
            val before=x.s();db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,file).build();x=x.copy(r=LedgerRepository(db,clock))
            assertEquals(before,x.s())
            current=x.r.json.decodeFromString<FinancialPredictionInput>(x.f().plans.first{it.id==x.pool}.termsJson!!)
            x.r.commitFinancialPrediction(x.r.previewFinancialPool(x.b,current.copy(planId=x.pool)))
            assertEquals(confirmed,x.f().bills.first{it.id==first.id});assertEquals(100L,x.f().bills.filter{it.active&&it.planId==x.pool}.sumOf{it.feeMinor!!})
        }finally{db.close();context.deleteDatabase(file)}
    }
    @Test fun fixedOpeningContractCanMergeWithoutDoubleOpeningOrWrongLimitRecoveryMode()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val x=fixture(db,loan=true);val r=x.r
            val contract=x.f().contracts.first{it.mode==FinanceMode.FIXED_LOAN.name}
            val preview=r.previewFinancialArrangement(x.b,x.change().copy(loanLimitDeltaMinor=1000))
            assertTrue(preview.calculation.toString(),preview.calculation is Calculation.Ready)
            r.commitFinancialArrangement(preview,clock.instant().toString(),true)!!
            val bill=x.f().bills.first{it.planId==x.pool&&it.date==x.dates.first()}
            r.commitFinancialLedger(r.previewRepayment(x.b,FinancialRepaymentInput(x.bank,x.card,Money(Currency.JPY,100),"2026-10-26",Money(Currency.JPY,100),Money(Currency.JPY,100),Money(Currency.JPY,0),Money(Currency.JPY,0),clearingDate="2026-10-26",platformConfirmed=true,allocations=listOf(RepaymentAllocationInput(bill.id,contract.id,100,0,0)))))
            val s=x.s();val f=x.f();val limits=db.creditLimitDao().graph(x.b).first()!!
            assertEquals(100L,limitPayments(s,f,x.card).single().principalMinor)
            val moves=creditLimitMovements(s,f,limits,x.card,"2026-10-26",LimitRecovery.PAYMENT_DATE.name)
            assertEquals(1000L,moves.single{it.kind=="ARRANGEMENT"}.total);assertEquals(-100L,moves.single{it.kind=="RULE_RECOVERY"}.total)
            assertEquals(900L,s.contractRemaining(contract));assertEquals(2900L,s.balance(s.accounts.first{it.id==x.card}));assertEquals(9900L,s.balance(s.accounts.first{it.id==x.bank}))
            assertFalse(r.financialPredictionStale(s,f,f.plans.first{it.id==x.pool}))
        }finally{db.close()}
    }
    @Test fun mergedSourceCanBeReconciledWithoutCreatingAnIndividualScheduleAndPoolFailureRollsBack()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val x=fixture(db,loan=true);val r=x.r
            val member=r.commitFinancialArrangement(r.previewFinancialArrangement(x.b,x.change()),clock.instant().toString(),true)!!
            val applied=x.f().arrangements.single();val old=x.f().bills.first{it.planId==x.source}
            val common=x.f().bills.first{it.planId==x.pool&&it.date==x.dates.first()}
            r.commitFinancialLedger(r.previewRepayment(x.b,FinancialRepaymentInput(x.bank,x.card,Money(Currency.JPY,300),"2026-11-01",Money(Currency.JPY,300),Money(Currency.JPY,200),Money(Currency.JPY,0),Money(Currency.JPY,100),platformConfirmed=true,clearingDate="2026-11-01",allocations=listOf(RepaymentAllocationInput(common.id,x.f().contracts.first{it.mode==FinanceMode.FIXED_LOAN.name}.id,200,0,100)))))
            val before=x.s()
            val invalid=r.previewArrangementReconciliation(x.b,ArrangementReconciliationInput(member,"2026-10-25",100,listOf(ReconciledBillObligation(old.id,0,0),ReconciledBillObligation(common.id,700,0)),emptyList()))
            assertTrue(invalid.calculation.toString(),invalid.calculation is Calculation.Incomplete)
            val review=r.previewArrangementReconciliation(x.b,ArrangementReconciliationInput(member,"2026-10-25",500,
                listOf(ReconciledBillObligation(old.id,0,0),ReconciledBillObligation(common.id,700,0)),emptyList()))
            assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready)
            assertEquals(500L,review.record!!.unassignedOriginalMinor)
            assertEquals(2500L,review.poolPrediction!!.bills.sumOf{it.principalMinor!!})
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_review_pool BEFORE UPDATE ON financial_plans WHEN NEW.id='${x.pool}' BEGIN SELECT RAISE(ABORT,'review rollback'); END")
            assertThrows(android.database.sqlite.SQLiteException::class.java){runBlocking{r.commitArrangementReconciliation(review,true)}}
            assertNull(x.f().plans.first{it.id==member}.reconciliationJson);assertEquals(before,x.s())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_review_pool")
            r.commitArrangementReconciliation(review,true)
            val f=x.f();val after=x.s();val target=f.plans.first{it.id==member}
            assertFalse(target.active);assertTrue(f.bills.none{it.planId==member});assertEquals(applied,f.arrangements.single())
            assertEquals(1,f.plans.count{it.origin=="POOL"&&it.active});assertEquals(2500L,f.bills.filter{it.active&&it.planId==x.pool}.sumOf{it.principalMinor!!})
            assertEquals(100L,f.bills.filter{it.active&&it.planId==x.pool}.sumOf{it.feeMinor!!})
            assertFalse(r.financialPredictionStale(after,f,f.plans.first{it.id==x.pool}))
            assertEquals(common,f.bills.first{it.id==common.id});assertEquals(700L,after.billOutstanding(common).principalMinor)
            assertEquals(before.repaymentAllocations,after.repaymentAllocations)
            assertEquals(before.storedPostings,after.storedPostings);assertEquals(before.expenseComponents,after.expenseComponents)
            assertEquals(2800L,after.balance(after.accounts.first{it.id==x.card}));assertEquals(9700L,after.balance(after.accounts.first{it.id==x.bank}))
        }finally{db.close()}
    }
    @Test fun mergedExpenseRefundShareAndSourceCorrectionUpdateTheCommonPoolOnly()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val x=fixture(db);val r=x.r
            val category=r.saveCategory(x.b,"利用",EntryKind.EXPENSE,null)
            val event=r.saveEntry(x.b,EntryDraft(accountId=x.card,categoryId=category,amount="1000",date="2026-10-02",cardPaymentChoice="SINGLE"))
            val old=r.commitManualFinancialPlan(r.previewManualFinancialPlan(x.b,ManualFinancialPlanInput(x.card,"実利用",FinanceMode.SINGLE,listOf(KnownBillInput("2026-11-05",1000,1000,0,x.bank)),1000,complete=true,sourceEventId=event)))!!
            val member=r.commitFinancialArrangement(r.previewFinancialArrangement(x.b,x.change(0).copy(planId=old)),clock.instant().toString(),true)!!
            val oldBill=x.f().bills.first{it.planId==old};val applied=x.f().arrangements.single()
            val refund=r.saveRefund(x.b,RefundInput(event,Money(Currency.JPY,100),x.card,Money(Currency.JPY,100),Money(Currency.JPY,100),"2026-10-27",true))
            r.saveEntry(x.b,EntryDraft(id=event,accountId=x.card,categoryId=category,amount="1200",date="2026-10-02",cardPaymentChoice="SINGLE"))
            val before=x.s()
            val review=r.previewArrangementReconciliation(x.b,ArrangementReconciliationInput(member,"2026-10-25",1200,
                listOf(ReconciledBillObligation(oldBill.id,0,0)),listOf(ReconciledRefundShare(refund,100))))
            assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready)
            assertEquals(3100L,review.poolPrediction!!.bills.sumOf{it.principalMinor!!})
            r.commitArrangementReconciliation(review,true)
            val f=x.f();val s=x.s()
            assertEquals(applied,f.arrangements.single());assertEquals(1100L,f.plans.first{it.id==member}.principalMinor)
            assertEquals(3100L,f.bills.filter{it.active&&it.planId==x.pool}.sumOf{it.principalMinor!!})
            assertEquals(before.storedPostings,s.storedPostings);assertFalse(r.financialPredictionStale(s,f,f.plans.first{it.id==x.pool}))
            r.saveRefund(x.b,RefundInput(event,Money(Currency.JPY,150),x.card,Money(Currency.JPY,150),Money(Currency.JPY,150),"2026-10-27",true,refund))
            val changed=r.previewArrangementReconciliation(x.b,review.input.copy(refunds=listOf(ReconciledRefundShare(refund,150))))
            assertTrue(changed.calculation.toString(),changed.calculation is Calculation.Ready);r.commitArrangementReconciliation(changed,true)
            assertEquals(3050L,x.f().bills.filter{it.active&&it.planId==x.pool}.sumOf{it.principalMinor!!})
            r.deleteEntry(x.b,refund)
            val removed=r.previewArrangementReconciliation(x.b,review.input.copy(refunds=emptyList()))
            assertTrue(removed.calculation.toString(),removed.calculation is Calculation.Ready);r.commitArrangementReconciliation(removed,true)
            assertEquals(3200L,x.f().bills.filter{it.active&&it.planId==x.pool}.sumOf{it.principalMinor!!})
        }finally{db.close()}
    }

    @Test fun twoChangedMergedSourcesCanBeReviewedInSequenceWithoutTrustingTheOtherStaleSource()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val x=fixture(db);val r=x.r;val category=r.saveCategory(x.b,"利用",EntryKind.EXPENSE,null)
            val items=(1..2).map{n->
                val event=r.saveEntry(x.b,EntryDraft(accountId=x.card,categoryId=category,amount="1000",date="2026-10-02",cardPaymentChoice="SINGLE"))
                val old=r.commitManualFinancialPlan(r.previewManualFinancialPlan(x.b,ManualFinancialPlanInput(x.card,"利用$n",FinanceMode.SINGLE,listOf(KnownBillInput("2026-11-05",1000,1000,0,x.bank)),1000,complete=true,sourceEventId=event)))!!
                val member=r.commitFinancialArrangement(r.previewFinancialArrangement(x.b,x.change(0).copy(planId=old)),clock.instant().toString(),true)!!
                Triple(event,old,member)
            }
            items.forEach{(event,_,_)->r.saveEntry(x.b,EntryDraft(id=event,accountId=x.card,categoryId=category,amount="1200",date="2026-10-02",cardPaymentChoice="SINGLE"))}
            val cash=x.s().storedPostings
            for((index,item) in items.withIndex()) {
                val bill=x.f().bills.first{it.planId==item.second}
                val review=r.previewArrangementReconciliation(x.b,ArrangementReconciliationInput(item.third,"2026-10-25",1200,listOf(ReconciledBillObligation(bill.id,0,0)),emptyList()))
                assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready)
                assertEquals(index==1,review.poolPrediction!!.calculation is Calculation.Ready)
                r.commitArrangementReconciliation(review,true)
                val f=x.f();val snapshot=x.s()
                assertFalse(r.financialPredictionStale(snapshot,f,f.plans.first{it.id==item.third}))
                assertEquals(index==0,r.financialPredictionStale(snapshot,f,f.plans.first{it.id==x.pool}))
                assertEquals(cash,snapshot.storedPostings)
            }
            assertEquals(4400L,x.f().plans.first{it.id==x.pool}.principalMinor)
            assertEquals(1,x.f().plans.count{it.active&&it.origin=="POOL"})
        }finally{db.close()}
    }

}
