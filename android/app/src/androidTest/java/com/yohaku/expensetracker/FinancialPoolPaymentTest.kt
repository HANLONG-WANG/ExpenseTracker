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
class FinancialPoolPaymentTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    private data class Fixture(val b:String,val bank:String,val card:String,val pool:String,val rule:FinancialRuleVersion)
    private suspend fun fixture(r:LedgerRepository):Fixture {
        val b=r.createBook("共同月付",Currency.JPY,startDate="2026-10-01")
        val bank=r.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
        val card=r.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,3000),"2026-10-01")
        val rule=FinancialRuleVersion("",b,card,FinanceMode.REVOLVING,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,1000,allocation=AllocationOrder.FEES_FIRST,automatic=true))
        r.commitFinancialRule(r.previewFinancialRule(b,card,rule))
        val dates=listOf("2026-11-05","2026-12-05","2027-01-05","2027-02-05","2027-03-05")
        val windows=(0..4).map{n->val d=LocalDate.parse("2026-10-01").plusMonths(n.toLong());AccrualWindow(d.toString(),d.withDayOfMonth(d.lengthOfMonth()).toString())}
        val pool=r.commitFinancialPrediction(r.previewFinancialPool(b,FinancialPredictionInput(card,"原共同池",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-10-01",dates,windows),paymentAccountId=bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(3000,PoolSourceDate.TRANSACTION))))!!
        return Fixture(b,bank,card,pool,rule)
    }
    private suspend fun definition(r:LedgerRepository,x:Fixture)=r.json.decodeFromString<FinancialPredictionInput>(r.db.financialDao().graph(x.b).first()!!.plans.first{it.id==x.pool}.termsJson!!).copy(planId=x.pool)
    @Test fun changingMonthlyPaymentKeepsPoolActualsAndRetainedBillAndArchivesAnAtomicApplication()=runBlocking {
        val file="pool-payment-reopen.db";context.deleteDatabase(file)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,file).build()
        try {
            var r=LedgerRepository(db,clock);val x=fixture(r)
            val first=db.financialDao().bills(x.b,x.pool).first()
            r.commitFinancialBillConfirmation(r.previewFinancialBillConfirmation(x.b,first.id,KnownBillInput(first.date,1000,1000,0,x.bank)))
            r.commitFinancialLedger(r.previewRepayment(x.b,FinancialRepaymentInput(x.bank,x.card,Money(Currency.JPY,500),"2026-11-05",Money(Currency.JPY,500),Money(Currency.JPY,500),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-11-05",allocations=listOf(RepaymentAllocationInput(first.id,null,500,0,0)))))
            val second=db.financialDao().bills(x.b,x.pool).first{it.date=="2026-12-05"}
            r.commitFinancialRule(r.previewFinancialRule(x.b,x.card,x.rule.copy(deadlines=listOf(ChangeDeadline(second.id,FinanceMode.REVOLVING,"2026-10-24","15:00","Asia/Tokyo")))))
            r.commitFinancialPrediction(r.previewFinancialPool(x.b,definition(r,x)))
            val before=r.snapshot(r.dao.book(x.b)!!).first();val graph=db.financialDao().graph(x.b).first()!!
            val input=PoolPaymentChangeInput(x.pool,"2026-10-25",RevolvingPaymentSetting("2026-12-05",PaymentMethod.TOTAL_PAYMENT,600),100,"2026-12-05")
            val review=r.previewPoolPaymentChange(x.b,input)
            assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready)
            assertEquals(listOf(1000L,600L,600L,600L,300L),review.prediction!!.bills.map{it.totalMinor})
            assertEquals(listOf(0L,100L,0L,0L,0L),review.prediction!!.bills.map{it.feeMinor})
            assertEquals(400L,review.comparison!!.currentReductionMinor);assertEquals(100L,review.comparison!!.totalExtraFeeMinor)
            val basis=r.json.decodeFromString<FinancialCalculationBasis>(review.prediction!!.bills.first{it.date=="2026-12-05"}.basisJson!!)
            assertEquals(600L,basis.paymentSetting!!.paymentMinor);assertEquals(100L,basis.additionalFeeMinor)
            assertThrows(IllegalArgumentException::class.java){runBlocking{r.commitPoolPaymentChange(review,clock.instant().toString(),false)}}
            assertThrows(IllegalArgumentException::class.java){runBlocking{r.commitPoolPaymentChange(review,clock.instant().toString(),true)}}
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_pool_payment BEFORE INSERT ON financial_plan_revisions WHEN NEW.reason='POOL_PAYMENT_CHANGED' BEGIN SELECT RAISE(ABORT,'rollback'); END")
            assertThrows(android.database.sqlite.SQLiteException::class.java){runBlocking{r.commitPoolPaymentChange(review,clock.instant().toString(),true,true)}}
            assertEquals(graph,db.financialDao().graph(x.b).first());assertEquals(before,r.snapshot(r.dao.book(x.b)!!).first())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_pool_payment")
            assertEquals(x.pool,r.commitPoolPaymentChange(review,clock.instant().toString(),true,true));assertNull(r.commitPoolPaymentChange(review,clock.instant().toString(),true,true))
            var f=db.financialDao().graph(x.b).first()!!;var s=r.snapshot(r.dao.book(x.b)!!).first()
            assertEquals(1,f.plans.size);assertTrue(f.arrangements.isEmpty());assertTrue(f.billAdjustments.isEmpty());assertEquals(graph.rules,f.rules)
            assertEquals(before.storedPostings,s.storedPostings);assertEquals(before.repaymentAllocations,s.repaymentAllocations);assertEquals(before.expenseComponents,s.expenseComponents)
            assertEquals(graph.bills.first{it.id==first.id},f.bills.first{it.id==first.id});assertEquals(500L,s.billOutstanding(first).principalMinor)
            r.commitFinancialPrediction(r.previewFinancialPool(x.b,definition(r,x)))
            assertThrows(IllegalArgumentException::class.java){runBlocking{r.previewFinancialPool(x.b,definition(r,x).let{it.copy(pool=it.pool!!.copy(paymentChanges=emptyList()))})}}
            db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,file).build();r=LedgerRepository(db,clock)
            f=db.financialDao().graph(x.b).first()!!;s=r.snapshot(r.dao.book(x.b)!!).first()
            assertFalse(r.financialPredictionStale(s,f,f.plans.single()))
            assertEquals(100L,f.bills.filter{it.active}.sumOf{it.feeMinor!!});assertEquals(2500L,s.balance(s.accounts.first{it.id==x.card}));assertEquals(9500L,s.balance(s.accounts.first{it.id==x.bank}))
            val archive=r.json.decodeFromString<FinancialPlanArchive>(db.financialDao().planRevisions(x.b,x.pool).first().single{it.reason=="POOL_PAYMENT_CHANGED"}.json)
            val change=r.json.decodeFromString<FinancialPredictionInput>(archive.plan.termsJson!!).pool!!.paymentChanges.single()
            assertTrue(change.exceptionAccepted);assertEquals(review.comparison,change.comparison)
            assertEquals(600L,change.setting.paymentMinor);assertEquals(1,s.events.size)
        }finally{db.close();context.deleteDatabase(file)}
    }
    @Test fun repeatedChangesCanAdoptABalanceCourseWithoutReplacingRulesOrCreatingAnotherPlan()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val r=LedgerRepository(db,clock);val x=fixture(r)
            val fixed=PoolPaymentChangeInput(x.pool,"2026-10-25",RevolvingPaymentSetting("2026-11-05",PaymentMethod.FIXED_PRINCIPAL,700),0)
            r.commitPoolPaymentChange(r.previewPoolPaymentChange(x.b,fixed),clock.instant().toString(),true)
            val tiers=RevolvingPaymentSetting("2026-11-05",PaymentMethod.BALANCE_TIERS,tiers=listOf(PaymentTier(1000,400),PaymentTier(null,800)),tierPaymentIncludesFees=true,tierBalanceDate=TierBalanceDate.PAYMENT_DATE)
            val next=r.previewPoolPaymentChange(x.b,fixed.copy(setting=tiers))
            assertEquals(listOf(800L,800L,800L,400L,200L),next.prediction!!.bills.map{it.principalMinor})
            r.commitPoolPaymentChange(next,clock.instant().toString(),true)
            val f=db.financialDao().graph(x.b).first()!!;val s=r.snapshot(r.dao.book(x.b)!!).first()
            assertEquals(1,f.plans.size);assertEquals(1,f.rules.size);assertTrue(f.arrangements.isEmpty());assertTrue(s.events.isEmpty())
            assertEquals(2,definition(r,x).pool!!.paymentChanges.size);assertEquals(3000L,s.balance(s.accounts.first{it.id==x.card}))
        }finally{db.close()}
    }
    @Test fun feeInARetainedBillNeedsExplicitCoverageAndCannotBeAddedTwice()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val r=LedgerRepository(db,clock);val x=fixture(r);val bill=db.financialDao().bills(x.b,x.pool).first()
            r.commitFinancialBillConfirmation(r.previewFinancialBillConfirmation(x.b,bill.id,KnownBillInput(bill.date,1000,900,100,x.bank)))
            r.commitFinancialPrediction(r.previewFinancialPool(x.b,definition(r,x)))
            val input=PoolPaymentChangeInput(x.pool,"2026-10-25",RevolvingPaymentSetting("2026-11-05",PaymentMethod.TOTAL_PAYMENT,600),100,"2026-11-05")
            assertEquals(Calculation.Incomplete(listOf("pool.mergeFeeConfirmation")),r.previewPoolPaymentChange(x.b,input).calculation)
            assertTrue(r.previewPoolPaymentChange(x.b,input.copy(feeMinor=150,feeIncludedInRetained=true)).calculation is Calculation.Incomplete)
            val ready=r.previewPoolPaymentChange(x.b,input.copy(feeIncludedInRetained=true))
            assertTrue(ready.calculation.toString(),ready.calculation is Calculation.Ready);assertNull(ready.comparison!!.totalExtraFeeMinor)
            r.commitPoolPaymentChange(ready,clock.instant().toString(),true)
            r.commitFinancialPrediction(r.previewFinancialPool(x.b,definition(r,x)))
            assertEquals(100L,db.financialDao().bills(x.b,x.pool).filter{it.active}.sumOf{it.feeMinor!!})
            assertEquals(1000L,db.financialDao().bill(x.b,bill.id)!!.totalMinor)
            // A subsequent platform cancellation keeps the original recorded fee and bill,
            // while reducing today's obligation through the explicit noncash adjustment.
            val cancelled=r.previewFinancialArrangement(x.b,FinancialArrangementInput(x.pool,"费用已取消的转出",FinanceMode.INSTALLMENT,
                FinancialScheduleTerms("2026-10-25",listOf("2026-11-05")),x.bank,OriginalFeeTreatment.CANCELLED_BY_PLATFORM,0,
                poolSelection=PoolArrangementSelection(listOf(PoolBillTransfer(bill.id,100,100))),manualPeriods=listOf(KnownBillInput("2026-11-05",100,100,0)),manualComplete=true))
            assertTrue(cancelled.calculation.toString(),cancelled.calculation is Calculation.Ready)
            r.commitFinancialArrangement(cancelled,clock.instant().toString(),true)
            val f=db.financialDao().graph(x.b).first()!!;val s=r.snapshot(r.dao.book(x.b)!!).first()
            assertEquals(0L,f.bills.filter{it.active}.sumOf{s.billOutstanding(it).feeMinor!!})
            assertEquals(100L,definition(r,x).pool!!.paymentChanges.single().feeMinor)
            assertEquals(1000L,db.financialDao().bill(x.b,bill.id)!!.totalMinor)
            assertEquals(3000L,s.balance(s.accounts.first{it.id==x.card}));assertTrue(s.events.isEmpty())
        }finally{db.close()}
    }
}
