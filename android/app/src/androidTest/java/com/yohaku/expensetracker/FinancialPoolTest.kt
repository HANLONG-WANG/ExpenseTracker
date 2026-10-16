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
class FinancialPoolTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    private data class Fixture(val r:LedgerRepository,val b:String,val a:String,val bank:String,val category:String) {
        suspend fun s()=r.snapshot(r.dao.book(b)!!).first()
        suspend fun f()=r.db.financialDao().graph(b).first()!!
        fun input(opening:Long=100000,shares:Map<String,Long> = emptyMap(),adopt:Boolean=false,loan:Boolean=false)=FinancialPredictionInput(a,"账户循环",if(loan)FinanceMode.CREDIT_LINE else FinanceMode.REVOLVING,FinancialScheduleTerms("2026-10-01",listOf("2026-11-05","2026-12-05"),listOf(AccrualWindow(if(loan)"2026-10-15"else"2026-10-01","2026-10-30"),AccrualWindow("2026-10-31","2026-11-30"))),paymentAccountId=bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(opening,PoolSourceDate.TRANSACTION,shares,adopt))
        fun draft(amount:String,date:String)=EntryDraft(accountId=a,categoryId=category,amount=amount,date=date,cardPaymentChoice="REVOLVING")
    }
    private suspend fun fixture(db:YohakuDatabase,loan:Boolean=false):Fixture {
        val r=LedgerRepository(db,clock);val b=r.createBook("循环",Currency.JPY)
        val a=r.saveAccount(b,"债务",if(loan)AccountType.LOAN else AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,if(loan)0 else 100000),"2026-10-01")
        val bank=r.saveAccount(b,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-10-01")
        val c=r.saveCategory(b,"生活",EntryKind.EXPENSE,null)
        val rule=FinancialRuleVersion("",b,a,if(loan)FinanceMode.CREDIT_LINE else FinanceMode.REVOLVING,if(loan)"2026-10-15"else"2026-10-01","Asia/Tokyo",if(loan)null else BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0.18",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"HALF_UP",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,if(loan)1000 else 10000,allocation=AllocationOrder.FEES_FIRST,automatic=true),limitRecovery=if(loan)LimitRecovery.PAYMENT_DATE else null)
        r.commitFinancialRule(r.previewFinancialRule(b,a,rule))
        return Fixture(r,b,a,bank,c)
    }
    @Test fun cardPoolUpdatesForNewSpendingAndActualPaymentWithoutDuplicatingDebtOrCash()=runBlocking {
        val file="pool-reopen.db";context.deleteDatabase(file)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,file).build()
        try {
            var x=fixture(db);var r=x.r
            val source=r.saveEntry(x.b,x.draft("50000","2026-10-16"))
            r.saveRefund(x.b,RefundInput(source,Money(Currency.JPY,5000),x.a,Money(Currency.JPY,5000),Money(Currency.JPY,5000),"2026-10-25",true))
            val payment=r.commitFinancialLedger(r.previewRepayment(x.b,FinancialRepaymentInput(x.bank,x.a,Money(Currency.JPY,20000),"2026-10-21",Money(Currency.JPY,20000),Money(Currency.JPY,20000),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-10-21")))!!
            val preview=r.previewFinancialPool(x.b,x.input(shares=mapOf(payment to 20000)))
            assertTrue(preview.calculation is Calculation.Ready)
            assertEquals(1736L,(preview.calculation as Calculation.Ready).value.first().period.feeMinor)
            val plan=r.commitFinancialPrediction(preview)!!
            assertFalse(r.financialPredictionStale(x.s(),x.f(),x.f().plans.first{it.id==plan}))
            val ids=x.f().bills.map{it.id}
            val extra=r.saveEntry(x.b,x.draft("30000","2026-10-28"))
            var f=x.f();assertEquals(ids,f.bills.filter{it.active}.map{it.id})
            assertEquals(1780L,f.bills.first{it.number==1}.feeMinor)
            assertEquals(10000L,f.bills.first{it.number==1}.principalMinor)
            assertEquals(155000L,x.s().balance(x.s().accounts.first{it.id==x.a}));assertEquals(180000L,x.s().balance(x.s().accounts.first{it.id==x.bank}))
            val first=f.bills.first{it.number==1};val secondFee=f.bills.first{it.number==2}.feeMinor!!
            val repay=r.previewRepayment(x.b,FinancialRepaymentInput(x.bank,x.a,Money(Currency.JPY,10000),"2026-10-29",Money(Currency.JPY,10000),Money(Currency.JPY,10000),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-10-29",allocations=listOf(RepaymentAllocationInput(first.id,null,10000,0,0))))
            assertTrue(repay.planImpacts.any{it.original.id==plan&&it.updatesPrediction})
            r.commitFinancialLedger(repay)
            f=x.f();assertEquals(first,f.bills.first{it.id==first.id});assertTrue(f.bills.first{it.number==2}.feeMinor!!<secondFee)
            assertEquals(145000L,x.s().balance(x.s().accounts.first{it.id==x.a}));assertEquals(170000L,x.s().balance(x.s().accounts.first{it.id==x.bank}))
            r.deleteEntry(x.b,extra);assertTrue(r.financialPredictionStale(x.s(),x.f(),x.f().plans.first{it.id==plan}))
            assertNull(x.s().financialAccountSummary(x.f(),x.a,"2026-10-15",setOf(plan)).rows.statementTotals().cashRequiredMinor)
            assertEquals(1,x.f().plans.count{it.origin=="POOL"});assertEquals(5,x.s().events.size)
            db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,file).build();r=LedgerRepository(db,clock);x=x.copy(r=r)
            assertTrue(r.financialPredictionStale(x.s(),x.f(),x.f().plans.first{it.id==plan}))
            assertEquals(115000L,x.s().balance(x.s().accounts.first{it.id==x.a}));assertEquals(170000L,x.s().balance(x.s().accounts.first{it.id==x.bank}))
            val pendingDraft=x.draft("1000","2026-10-30").copy(settlementConfirmed=false)
            val pending=r.saveEntry(x.b,pendingDraft)
            val stored=r.json.decodeFromString<FinancialPredictionInput>(x.f().plans.first{it.id==plan}.termsJson!!).copy(planId=plan)
            val awaiting=r.previewFinancialPool(x.b,stored)
            assertTrue((awaiting.calculation as Calculation.Incomplete).fields.contains("pool.sourcePending"))
            r.saveEntry(x.b,pendingDraft.copy(id=pending,settlementConfirmed=true))
            assertFalse(r.financialPredictionStale(x.s(),x.f(),x.f().plans.first{it.id==plan}))
        }finally{db.close();context.deleteDatabase(file)}
    }
    @Test fun adoptingLegacySchedulesKeepsConfirmedBillsAndReplacesOnlyFuturePredictionsAtomically()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val x=fixture(db);val r=x.r;val event=r.saveEntry(x.b,x.draft("50000","2026-10-16"))
            val source=r.previewFinancialPrediction(x.b,FinancialPredictionInput(x.a,"旧逐笔",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-10-16",listOf("2026-11-05","2026-12-05"),listOf(AccrualWindow("2026-10-16","2026-10-30"),AccrualWindow("2026-10-31","2026-11-30"))),sourceEventId=event,currentArrangementConfirmed=true))
            val oldPlan=r.commitFinancialPrediction(source)!!
            val first=x.f().bills.first{it.planId==oldPlan&&it.number==1}
            r.commitFinancialBillConfirmation(r.previewFinancialBillConfirmation(x.b,first.id,KnownBillInput("2026-11-05",10900,10000,900,x.bank)))
            r.commitManualFinancialPlan(r.previewManualFinancialPlan(x.b,ManualFinancialPlanInput(x.a,"期初リボ",FinanceMode.REVOLVING,listOf(KnownBillInput("2026-11-05",21800,20000,1800,x.bank)),principalMinor=100000,opening=true)))
            val originals=x.f().bills.filter{it.confirmed}
            val preview=r.previewFinancialPool(x.b,x.input(adopt=true))
            assertEquals(30000L,(preview.calculation as Calculation.Ready).value.first().period.principalMinor)
            assertEquals(2700L,preview.calculation.value.first().period.feeMinor)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_pool BEFORE INSERT ON financial_plans WHEN NEW.origin='POOL' BEGIN SELECT RAISE(ABORT,'atomic pool'); END")
            try{r.commitFinancialPrediction(preview);fail("rollback expected")}catch(_:android.database.sqlite.SQLiteException){}
            assertEquals(2,x.f().plans.size);assertTrue(x.f().bills.all{it.active});assertTrue(x.f().plans.none{it.origin=="POOL_RETAINED"})
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_pool")
            val pool=r.commitFinancialPrediction(preview)!!
            val f=x.f();assertEquals(originals,f.bills.filter{it.confirmed})
            assertFalse(f.bills.first{it.planId==oldPlan&&it.number==2}.active)
            val own=f.bills.single{it.planId==pool};assertEquals("2026-12-05",own.date);assertEquals(10000L,own.principalMinor);assertEquals(1908L,own.feeMinor)
            assertEquals(32700L,x.s().financialAccountSummary(f,x.a,"2026-10-15").rows.filter{it.bill.date.startsWith("2026-11")}.statementTotals().totalMinor)
            assertFalse(r.financialPredictionStale(x.s(),f,f.plans.first{it.id==pool}))
            assertEquals(150000L,x.s().balance(x.s().accounts.first{it.id==x.a}));assertEquals(200000L,x.s().balance(x.s().accounts.first{it.id==x.bank}));assertEquals(1,x.s().events.size)
        }finally{db.close()}
    }
    @Test fun circularLoanPoolIgnoresFixedContractsAndAddsLaterCircularDrawsToOneCourse()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val x=fixture(db,true);val r=x.r
            suspend fun borrow(name:String,mode:FinanceMode,amount:Long,date:String)=r.commitFinancialLedger(r.previewBorrowing(x.b,FinancialBorrowingInput(x.a,name,mode,Money(Currency.JPY,amount),date,x.bank,Money(Currency.JPY,amount))))!!
            val lineEvent=borrow("循环1",FinanceMode.CREDIT_LINE,3000,"2026-10-15")
            val initial=r.previewFinancialPool(x.b,x.input(0,loan=true))
            assertEquals(24L,(initial.calculation as Calculation.Ready).value.first().period.feeMinor)
            val id=r.commitFinancialPrediction(initial)!!
            val fixedEvent=borrow("固定",FinanceMode.FIXED_LOAN,5000,"2026-10-16")
            assertEquals(1,x.f().plans.first{it.id==id}.revision)
            borrow("循环2",FinanceMode.CREDIT_LINE,2000,"2026-10-20")
            val f=x.f();assertEquals(2,f.plans.first{it.id==id}.revision)
            val first=f.bills.first{it.planId==id&&it.number==1};assertEquals(1000L,first.principalMinor);assertEquals(35L,first.feeMinor)
            assertEquals(10000L,x.s().balance(x.s().accounts.first{it.id==x.a}));assertEquals(210000L,x.s().balance(x.s().accounts.first{it.id==x.bank}));assertEquals(3,x.s().events.size)
            val fixed=db.financialDao().fundingContract(x.b,fixedEvent)!!;val line=db.financialDao().fundingContract(x.b,lineEvent)!!
            val pay=FinancialRepaymentInput(x.bank,x.a,Money(Currency.JPY,1000),"2026-10-25",Money(Currency.JPY,1000),Money(Currency.JPY,1000),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-10-25",allocations=listOf(RepaymentAllocationInput(first.id,fixed.id,1000,0,0)))
            try{r.previewRepayment(x.b,pay);fail("fixed contract cannot clear circular pool bill")}catch(_:IllegalArgumentException){}
            r.commitFinancialLedger(r.previewRepayment(x.b,pay.copy(allocations=listOf(RepaymentAllocationInput(first.id,line.id,1000,0,0)))))
            assertEquals(2000L,x.s().contractRemaining(line));assertEquals(5000L,x.s().contractRemaining(fixed))
            assertEquals(9000L,x.s().balance(x.s().accounts.first{it.id==x.a}));assertEquals(209000L,x.s().balance(x.s().accounts.first{it.id==x.bank}))
        }finally{db.close()}
    }
}
