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
class FinancialPoolAdoptionTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    private data class Fixture(val r:LedgerRepository,val b:String,val bank:String,val debt:String,val category:String,val source:String,val old:String,val target:String,val arrangement:String,val mode:FinanceMode,val definition:FinancialPredictionInput) {
        suspend fun s()=r.snapshot(r.dao.book(b)!!).first()
        suspend fun f()=r.db.financialDao().graph(b).first()!!
        fun input(included:Boolean=false)=definition.copy(pool=definition.pool!!.copy(adoptedFees=listOf(PoolAdoptedFee(arrangement,"2026-11-05",included))))
        suspend fun review(principal:Long,bills:List<ReconciledBillObligation> = emptyList(),refunds:List<ReconciledRefundShare> = emptyList())=r.previewArrangementReconciliation(b,ArrangementReconciliationInput(target,"2026-10-25",principal,
            listOf(ReconciledBillObligation(f().bills.first{it.planId==old}.id,0,0))+bills,refunds,loanLimitDeltaMinor=if(mode==FinanceMode.CREDIT_LINE)principal else null))
        suspend fun current(pool:String):List<FinancialBillEntity> = f().bills.filter{it.active&&(it.planId==pool||it.planId==target)}
    }
    private suspend fun fixture(db:YohakuDatabase,confirmed:Boolean=false,loan:Boolean=false):Fixture {
        val r=LedgerRepository(db,clock);val b=r.createBook("池归并",Currency.JPY,startDate="2026-10-01")
        val bank=r.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
        val debt=r.saveAccount(b,"債務",if(loan)AccountType.LOAN else AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,2000),"2026-10-01")
        val category=r.saveCategory(b,"利用",EntryKind.EXPENSE,null)
        val source=if(loan)r.commitFinancialLedger(r.previewBorrowing(b,FinancialBorrowingInput(debt,"固定借入",FinanceMode.FIXED_LOAN,Money(Currency.JPY,1000),"2026-10-02",bank,Money(Currency.JPY,1000),Money(Currency.JPY,0),Money(Currency.JPY,0))))!!else r.saveEntry(b,EntryDraft(accountId=debt,categoryId=category,amount="1000",date="2026-10-02",cardPaymentChoice="SINGLE"))
        val old=r.commitManualFinancialPlan(r.previewManualFinancialPlan(b,ManualFinancialPlanInput(debt,"元の予定",if(loan)FinanceMode.FIXED_LOAN else FinanceMode.SINGLE,listOf(KnownBillInput("2026-11-05",1000,1000,0,bank)),1000,complete=true,sourceEventId=source)))!!
        if(loan)r.linkFinancialContractPlan(b,db.financialDao().graph(b).first()!!.contracts.single().id,old)
        val mode=if(loan)FinanceMode.CREDIT_LINE else FinanceMode.REVOLVING
        r.commitFinancialRule(r.previewFinancialRule(b,debt,FinancialRuleVersion("",b,debt,mode,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.TOTAL_PAYMENT,1000,allocation=AllocationOrder.FEES_FIRST,automatic=true),limitRecovery=if(loan)LimitRecovery.PAYMENT_DATE else null)))
        val dates=listOf("2026-11-05","2026-12-05","2027-01-05","2027-02-05")
        val windows=listOf(AccrualWindow("2026-10-01","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"),AccrualWindow("2026-12-01","2026-12-31"),AccrualWindow("2027-01-01","2027-01-31"))
        val target=r.commitFinancialArrangement(r.previewFinancialArrangement(b,FinancialArrangementInput(old,"既存の変更",mode,FinancialScheduleTerms("2026-10-25",dates,windows),bank,OriginalFeeTreatment.CANCELLED_BY_PLATFORM,100,loanLimitDeltaMinor=if(loan)1000 else null)),clock.instant().toString(),true)!!
        val arrangement=db.financialDao().graph(b).first()!!.arrangements.single().id
        if(confirmed) {
            val first=db.financialDao().bills(b,target).first()
            r.commitFinancialBillConfirmation(r.previewFinancialBillConfirmation(b,first.id,KnownBillInput(first.date,1000,900,100,bank)))
        }
        return Fixture(r,b,bank,debt,category,source,old,target,arrangement,mode,FinancialPredictionInput(debt,"共同プール",mode,FinancialScheduleTerms("2026-10-01",dates,windows),paymentAccountId=bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(2000,PoolSourceDate.TRANSACTION,adoptExisting=true)))
    }
    @Test fun futureArrangementFeeSurvivesAdoptionSourceCorrectionRefundAndReopening()=runBlocking {
        val file="adoption-fee.db";context.deleteDatabase(file);var db=Room.databaseBuilder(context,YohakuDatabase::class.java,file).build()
        try {
            var x=fixture(db);val r=x.r
            val missing=r.previewFinancialPool(x.b,x.definition)
            assertTrue((missing.calculation as Calculation.Incomplete).fields.contains("pool.adoptedFeeChoice"))
            val before=x.s();val applied=x.f().arrangements.single()
            val preview=r.previewFinancialPool(x.b,x.input())
            assertTrue(preview.calculation.toString(),preview.calculation is Calculation.Ready)
            assertEquals(100L,preview.bills.sumOf{it.feeMinor!!});assertEquals(900L,preview.bills.first().principalMinor)
            val pool=r.commitFinancialPrediction(preview)!!
            assertEquals("POOL_RETAINED",x.f().plans.first{it.id==x.target}.origin);assertFalse(x.f().plans.first{it.id==x.target}.active)
            assertEquals(before.storedPostings,x.s().storedPostings)
            r.saveEntry(x.b,EntryDraft(id=x.source,accountId=x.debt,categoryId=x.category,amount="1200",date="2026-10-02",cardPaymentChoice="SINGLE"))
            val review=x.review(1200)
            assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready);assertTrue(review.poolPrediction?.calculation.toString(),review.poolPrediction?.calculation is Calculation.Ready)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_adopted_review BEFORE UPDATE ON financial_plans WHEN NEW.id='$pool' BEGIN SELECT RAISE(ABORT,'rollback'); END")
            assertThrows(android.database.sqlite.SQLiteException::class.java){runBlocking{r.commitArrangementReconciliation(review,true)}}
            assertNull(x.f().plans.first{it.id==x.target}.reconciliationJson)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_adopted_review");r.commitArrangementReconciliation(review,true)
            assertEquals(3200L,x.current(pool).sumOf{it.principalMinor!!});assertEquals(100L,x.current(pool).sumOf{it.feeMinor!!})
            val refund=r.saveRefund(x.b,RefundInput(x.source,Money(Currency.JPY,100),x.debt,Money(Currency.JPY,100),Money(Currency.JPY,100),"2026-10-27",true))
            val actual=x.s();val refunded=x.review(1200,refunds=listOf(ReconciledRefundShare(refund,100)))
            r.commitArrangementReconciliation(refunded,true)
            assertEquals(3100L,x.current(pool).sumOf{it.principalMinor!!});assertEquals(100L,x.current(pool).sumOf{it.feeMinor!!})
            assertEquals(actual.storedPostings,x.s().storedPostings);assertEquals(applied,x.f().arrangements.single())
            db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,file).build();x=x.copy(r=LedgerRepository(db,clock))
            assertEquals(3100L,x.current(pool).sumOf{it.principalMinor!!});assertEquals(100L,x.current(pool).sumOf{it.feeMinor!!})
            assertFalse(x.r.financialPredictionStale(x.s(),x.f(),x.f().plans.first{it.id==pool}))
            assertFalse(x.r.financialPredictionStale(x.s(),x.f(),x.f().plans.first{it.id==x.target}))
            assertEquals(3100L,x.s().balance(x.s().accounts.first{it.id==x.debt}));assertEquals(10000L,x.s().balance(x.s().accounts.first{it.id==x.bank}))
        }finally{db.close();context.deleteDatabase(file)}
    }
    @Test fun retainedConfirmedFeeNeedsExplicitInclusionAndLaterCancellationDoesNotAddItBack()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val x=fixture(db,confirmed=true);val r=x.r;val first=x.f().bills.first{it.planId==x.target&&it.number==1}
            assertTrue((r.previewFinancialPool(x.b,x.input()).calculation as Calculation.Incomplete).fields.contains("pool.mergeFeeConfirmation"))
            val pool=r.commitFinancialPrediction(r.previewFinancialPool(x.b,x.input(true)))!!
            val review=x.review(800,listOf(ReconciledBillObligation(first.id,900,0)))
            assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready);assertTrue(review.poolPrediction?.calculation.toString(),review.poolPrediction?.calculation is Calculation.Ready)
            r.commitArrangementReconciliation(review,true)
            val f=x.f();val s=x.s();assertTrue(f.plans.first{it.id==x.target}.active)
            assertEquals(first,f.bills.first{it.id==first.id});assertEquals(0L,s.billOutstanding(first).feeMinor)
            assertEquals(2800L,x.current(pool).sumOf{s.billOutstanding(it).principalMinor!!});assertEquals(0L,x.current(pool).sumOf{s.billOutstanding(it).feeMinor!!})
            assertTrue(s.financialAccountSummary(f,x.debt,"2026-10-25").rows.any{it.bill.id==first.id})
            assertEquals(3000L,s.balance(s.accounts.first{it.id==x.debt}));assertEquals(0L,s.expenseComponents.sumOf{it.baseMinor})
        }finally{db.close()}
    }
    @Test fun adoptedFixedLoanCanClearThroughTheCommonPoolWithinItsCorrectedContractShare()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val x=fixture(db,confirmed=true,loan=true);val r=x.r
            val pool=r.commitFinancialPrediction(r.previewFinancialPool(x.b,x.input(true)))!!
            val first=x.f().bills.first{it.planId==x.target&&it.number==1};val common=x.f().bills.first{it.planId==pool&&it.date=="2026-12-05"};val contract=x.f().contracts.single()
            r.commitFinancialLedger(r.previewRepayment(x.b,FinancialRepaymentInput(x.bank,x.debt,Money(Currency.JPY,200),"2026-11-27",Money(Currency.JPY,200),Money(Currency.JPY,200),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-11-27",allocations=listOf(RepaymentAllocationInput(common.id,contract.id,200,0,0)))))
            val before=x.s();val bills=listOf(ReconciledBillObligation(first.id,900,100),ReconciledBillObligation(common.id,800,0))
            assertTrue(x.review(100,bills).calculation is Calculation.Incomplete)
            val review=x.review(800,bills);assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready)
            assertTrue(review.poolPrediction?.calculation.toString(),review.poolPrediction?.calculation is Calculation.Ready);r.commitArrangementReconciliation(review,true)
            assertEquals(before.storedPostings,x.s().storedPostings);assertEquals(before.repaymentAllocations,x.s().repaymentAllocations)
            assertEquals(2600L,x.current(pool).sumOf{x.s().billOutstanding(it).principalMinor!!})
            assertEquals(10800L,x.s().balance(x.s().accounts.first{it.id==x.bank}));assertEquals(2800L,x.s().balance(x.s().accounts.first{it.id==x.debt}))
        }finally{db.close()}
    }
    @Test fun actualZeroFeeNeedsExplicitPlatformCancellationAndPreservesTheOriginalAppliedFee()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val x=fixture(db,confirmed=true);val r=x.r;val applied=x.f().arrangements.single()
            val pool=r.commitFinancialPrediction(r.previewFinancialPool(x.b,x.input(true)))!!
            val first=x.f().bills.first{it.planId==x.target&&it.number==1}
            r.commitFinancialBillConfirmation(r.previewFinancialBillConfirmation(x.b,first.id,KnownBillInput(first.date,900,900,0,x.bank)))
            val definition=r.json.decodeFromString<FinancialPredictionInput>(x.f().plans.first{it.id==pool}.termsJson!!)
            assertTrue((r.previewFinancialPool(x.b,definition).calculation as Calculation.Incomplete).fields.contains("pool.mergeFeeConfirmation"))
            val cancelled=definition.copy(pool=definition.pool!!.copy(adoptedFees=listOf(PoolAdoptedFee(x.arrangement,first.date,false,100))))
            val before=x.s();val review=r.previewFinancialPool(x.b,cancelled)
            assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready);r.commitFinancialPrediction(review)
            assertEquals(0L,x.current(pool).sumOf{x.s().billOutstanding(it).feeMinor!!})
            assertEquals(100L,x.f().arrangements.single().changeFeeMinor);assertEquals(applied,x.f().arrangements.single())
            assertEquals(before.storedPostings,x.s().storedPostings);assertEquals(before.expenseComponents,x.s().expenseComponents)
            assertEquals(3000L,x.s().balance(x.s().accounts.first{it.id==x.debt}))
        }finally{db.close()}
    }

}
