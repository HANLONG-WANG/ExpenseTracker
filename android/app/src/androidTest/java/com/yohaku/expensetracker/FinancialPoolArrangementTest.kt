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
class FinancialPoolArrangementTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    @Test fun selectedPoolPrincipalMovesAtomicallyAndTheSamePoolKeepsFutureSourcesAndRepeatedTransfers()=runBlocking {
        val file="pool-arrangement-reopen.db";context.deleteDatabase(file)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,file).build()
        try {
            var r=LedgerRepository(db,clock);val b=r.createBook("部分转出",Currency.JPY,startDate="2026-10-01")
            val bank=r.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val a=r.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,3000),"2026-10-01")
            val cat=r.saveCategory(b,"利用",EntryKind.EXPENSE,null)
            r.commitFinancialRule(r.previewFinancialRule(b,a,FinancialRuleVersion("",b,a,FinanceMode.REVOLVING,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,1000,allocation=AllocationOrder.FEES_FIRST,automatic=true))))
            r.commitFinancialRule(r.previewFinancialRule(b,a,FinancialRuleVersion("",b,a,FinanceMode.INSTALLMENT,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),installment=InstallmentRule(listOf(2),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=100,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"))))
            suspend fun s()=r.snapshot(r.dao.book(b)!!).first()
            suspend fun f()=db.financialDao().graph(b).first()!!
            val input=FinancialPredictionInput(a,"账户池",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-10-01",listOf("2026-11-05","2026-12-05","2027-01-05"),listOf(AccrualWindow("2026-10-01","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"),AccrualWindow("2026-12-01","2026-12-31"))),paymentAccountId=bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(3000,PoolSourceDate.TRANSACTION))
            val pool=r.commitFinancialPrediction(r.previewFinancialPool(b,input))!!
            val first=f().bills.first{it.planId==pool&&it.number==1}
            r.commitFinancialBillConfirmation(r.previewFinancialBillConfirmation(b,first.id,KnownBillInput(first.date,1000,1000,0,bank)))
            r.commitFinancialPrediction(r.previewFinancialPool(b,r.json.decodeFromString<FinancialPredictionInput>(f().plans.first{it.id==pool}.termsJson!!).copy(planId=pool)))
            val confirmed=f().bills.first{it.id==first.id};val before=s()
            fun change(n:Long)=FinancialArrangementInput(pool,"转出$n",FinanceMode.INSTALLMENT,FinancialScheduleTerms("2026-10-25",listOf("2026-11-05","2026-12-05")),bank,OriginalFeeTreatment.RETAIN,0,poolSelection=PoolArrangementSelection(listOf(PoolBillTransfer(first.id,n))))
            val review=r.previewFinancialArrangement(b,change(500))
            assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready)
            assertEquals(500L,review.target!!.principalMinor)
            assertEquals(200L,review.comparison!!.currentReductionMinor)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_pool_change BEFORE UPDATE ON financial_plans WHEN NEW.id='$pool' BEGIN SELECT RAISE(ABORT,'pool rollback'); END")
            assertThrows(android.database.sqlite.SQLiteException::class.java){runBlocking{r.commitFinancialArrangement(review,clock.instant().toString(),true)}}
            assertTrue(f().arrangements.isEmpty());assertTrue(f().billAdjustments.isEmpty());assertEquals(before,s())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_pool_change")
            val target=r.commitFinancialArrangement(review,clock.instant().toString(),true)!!
            assertEquals(confirmed,f().bills.first{it.id==first.id});assertEquals(500L,s().billOutstanding(confirmed).principalMinor)
            assertTrue(f().plans.first{it.id==pool}.active)
            assertFalse(r.financialPredictionStale(s(),f(),f().plans.first{it.id==pool}))
            assertFalse(r.financialPredictionStale(s(),f(),f().plans.first{it.id==target}))
            assertEquals(before.storedPostings,s().storedPostings);assertEquals(before.expenseComponents,s().expenseComponents)
            val stored=s();val saved=f()
            db.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(file).path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use{legacy->
                legacy.execSQL("DROP INDEX index_financial_arrangements_bookId_originalPlanId")
                legacy.execSQL("CREATE UNIQUE INDEX index_financial_arrangements_bookId_originalPlanId ON financial_arrangements(bookId, originalPlanId)")
                val schema=org.json.JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v21-schema.json").bufferedReader().readText()).getJSONObject("database")
                legacy.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42",arrayOf(schema.getString("identityHash")))
                restoreFixtureTable(legacy,"room-v21-schema.json","financial_plans");legacy.version=21
            }
            db=Room.databaseBuilder(context,YohakuDatabase::class.java,file).addMigrations(MIGRATION_21_22,MIGRATION_22_23).build();r=LedgerRepository(db,clock)
            assertEquals(stored,s());assertEquals(saved.arrangements,f().arrangements);assertEquals(saved.billAdjustments,f().billAdjustments)
            val second=r.commitFinancialArrangement(r.previewFinancialArrangement(b,change(200)),clock.instant().toString(),true)!!
            assertEquals(2,f().arrangements.size);assertEquals(300L,s().billOutstanding(confirmed).principalMinor)
            assertFalse(r.financialPredictionStale(s(),f(),f().plans.first{it.id==target}))
            assertFalse(r.financialPredictionStale(s(),f(),f().plans.first{it.id==second}))
            val spend=r.saveEntry(b,EntryDraft(accountId=a,categoryId=cat,amount="200",date="2026-10-26",cardPaymentChoice="REVOLVING"))
            assertEquals(1,f().plans.count{it.origin=="POOL"});assertEquals(3200L,s().balance(s().accounts.first{it.id==a}))
            assertFalse(r.financialPredictionStale(s(),f(),f().plans.first{it.id==pool}))
            assertFalse(r.financialPredictionStale(s(),f(),f().plans.first{it.id==target}))
            assertEquals(1,s().events.size);assertEquals(spend,s().events.single().id);assertEquals(10000L,s().balance(s().accounts.first{it.id==bank}))
            val poolBefore=f().plans.first{it.id==pool}
            val targetBill=f().bills.first{it.planId==target&&it.number==1}
            r.commitFinancialLedger(r.previewRepayment(b,FinancialRepaymentInput(bank,a,Money(Currency.JPY,100),"2026-10-26",Money(Currency.JPY,100),Money(Currency.JPY,100),Money(Currency.JPY,0),Money(Currency.JPY,0),clearingDate="2026-10-26",platformConfirmed=true,allocations=listOf(RepaymentAllocationInput(targetBill.id,null,100,0,0)))))
            assertEquals(poolBefore,f().plans.first{it.id==pool})
            assertEquals(3100L,s().balance(s().accounts.first{it.id==a}));assertEquals(9900L,s().balance(s().accounts.first{it.id==bank}))
            assertEquals(200L,s().monthExpense("2026-10"))
        }finally{db.close();context.deleteDatabase(file)}
    }
    @Test fun retrospectiveClearingAndExplicitCancelledFeesStayConservedAndUnbilledPrincipalCanMove()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val r=LedgerRepository(db,clock);val b=r.createBook("部分转出",Currency.JPY,startDate="2026-10-01")
            val bank=r.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val a=r.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,3000),"2026-10-01")
            val cat=r.saveCategory(b,"利用",EntryKind.EXPENSE,null)
            r.commitFinancialRule(r.previewFinancialRule(b,a,FinancialRuleVersion("",b,a,FinanceMode.REVOLVING,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,1000,allocation=AllocationOrder.FEES_FIRST,automatic=true))))
            r.commitFinancialRule(r.previewFinancialRule(b,a,FinancialRuleVersion("",b,a,FinanceMode.INSTALLMENT,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),installment=InstallmentRule(listOf(2),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=100,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"))))
            suspend fun s()=r.snapshot(r.dao.book(b)!!).first()
            suspend fun f()=db.financialDao().graph(b).first()!!
            val input=FinancialPredictionInput(a,"账户池",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-10-01",listOf("2026-11-05","2026-12-05","2027-01-05"),listOf(AccrualWindow("2026-10-01","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"),AccrualWindow("2026-12-01","2026-12-31"))),paymentAccountId=bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(3000,PoolSourceDate.TRANSACTION))
            val pool=r.commitFinancialPrediction(r.previewFinancialPool(b,input))!!
            val first=f().bills.first{it.planId==pool&&it.number==1}
            r.commitFinancialBillConfirmation(r.previewFinancialBillConfirmation(b,first.id,KnownBillInput(first.date,1060,1000,60,bank)))
            r.commitFinancialPrediction(r.previewFinancialPool(b,r.json.decodeFromString<FinancialPredictionInput>(f().plans.first{it.id==pool}.termsJson!!).copy(planId=pool)))

            val confirmed=f().bills.first{it.id==first.id}
            val payment=r.commitFinancialLedger(r.previewRepayment(b,FinancialRepaymentInput(bank,a,Money(Currency.JPY,210),"2026-10-28",Money(Currency.JPY,210),Money(Currency.JPY,200),Money(Currency.JPY,0),Money(Currency.JPY,10),clearingDate="2026-10-28",platformConfirmed=true,allocations=listOf(RepaymentAllocationInput(first.id,null,200,0,10)))))!!
            val allocation=s().repaymentAllocations.single()
            val before=s()
            val inputChange=FinancialArrangementInput(pool,"部分转出",FinanceMode.INSTALLMENT,FinancialScheduleTerms("2026-10-25",listOf("2026-11-05","2026-12-05")),bank,OriginalFeeTreatment.CANCELLED_BY_PLATFORM,0,poolSelection=PoolArrangementSelection(listOf(PoolBillTransfer(first.id,500,20))))
            assertEquals(Calculation.Incomplete(listOf("arrangement.laterClearing")),r.previewFinancialArrangement(b,inputChange).calculation)
            val moves=listOf(ArrangementClearingMove(allocation.id,null,100,0,5),ArrangementClearingMove(allocation.id,1,100,0,5))
            val review=r.previewFinancialArrangement(b,inputChange.copy(clearingMoves=moves))
            assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready)
            val target=r.commitFinancialArrangement(review,clock.instant().toString(),true)!!
            assertEquals(before.storedPostings,s().storedPostings);assertEquals(before.expenseComponents,s().expenseComponents)
            assertEquals(2,s().events.single{it.id==payment}.revision)
            assertEquals(confirmed,f().bills.first{it.id==first.id})
            assertEquals(435L,s().billOutstanding(confirmed).totalMinor)
            assertEquals(195L,s().billOutstanding(f().bills.first{it.planId==target&&it.number==1}).totalMinor)
            assertEquals(200L,s().repaymentAllocations.sumOf{it.principalMinor!!});assertEquals(10L,s().repaymentAllocations.sumOf{it.feeMinor!!})
            val current=r.json.decodeFromString<FinancialPredictionInput>(f().plans.first{it.id==pool}.termsJson!!)
            val short=current.copy(planId=pool,terms=current.terms.copy(paymentDates=current.terms.paymentDates.take(1),accrualWindows=current.terms.accrualWindows.take(1)))
            r.commitFinancialPrediction(r.previewFinancialPool(b,short))
            val unbilled=inputChange.copy(name="未排期转出",originalFeeTreatment=OriginalFeeTreatment.RETAIN,poolSelection=PoolArrangementSelection(emptyList(),300),clearingMoves=emptyList())
            val unbilledReview=r.previewFinancialArrangement(b,unbilled)
            assertTrue(unbilledReview.calculation.toString(),unbilledReview.calculation is Calculation.Ready)
            assertNull(unbilledReview.comparison!!.totalExtraFeeMinor)
            r.commitFinancialArrangement(unbilledReview,clock.instant().toString(),true)!!
            assertEquals(2800L,s().balance(s().accounts.first{it.id==a}));assertEquals(9790L,s().balance(s().accounts.first{it.id==bank}));assertEquals(10L,s().monthExpense("2026-10"))
            assertFalse(r.financialPredictionStale(s(),f(),f().plans.first{it.id==pool}))
        }finally{db.close()}
    }
    @Test fun creditLineTransfersRespectBillModeEvenWhenTheOriginalContractStaysRevolving()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val r=LedgerRepository(db,clock);val b=r.createBook("部分转出",Currency.JPY,startDate="2026-10-01")
            val bank=r.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val a=r.saveAccount(b,"カード",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,3000),"2026-10-01")
            val cat=r.saveCategory(b,"利用",EntryKind.EXPENSE,null)
            r.commitFinancialRule(r.previewFinancialRule(b,a,FinancialRuleVersion("",b,a,FinanceMode.CREDIT_LINE,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,1000,allocation=AllocationOrder.FEES_FIRST,automatic=true),limitRecovery=LimitRecovery.PAYMENT_DATE)))
            r.commitFinancialRule(r.previewFinancialRule(b,a,FinancialRuleVersion("",b,a,FinanceMode.FIXED_LOAN,"2026-10-01","Asia/Tokyo",loanMethod=LoanMethod.EQUAL_PRINCIPAL,loanInterest=InterestRule("0",ChargeFrequency.MONTHLY,BalanceBasis.OPENING_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),allocation=AllocationOrder.FEES_FIRST,loanPrincipalRemainder=RemainderPlacement.LAST)))
            r.commitFinancialLedger(r.previewBorrowing(b,FinancialBorrowingInput(a,"期初循环",FinanceMode.CREDIT_LINE,Money(Currency.JPY,3000),"2026-10-01",opening=true)))

            suspend fun s()=r.snapshot(r.dao.book(b)!!).first()
            suspend fun f()=db.financialDao().graph(b).first()!!
            val input=FinancialPredictionInput(a,"账户池",FinanceMode.CREDIT_LINE,FinancialScheduleTerms("2026-10-01",listOf("2026-11-05","2026-12-05","2027-01-05"),listOf(AccrualWindow("2026-10-01","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"),AccrualWindow("2026-12-01","2026-12-31"))),paymentAccountId=bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(3000,PoolSourceDate.TRANSACTION))
            val pool=r.commitFinancialPrediction(r.previewFinancialPool(b,input))!!
            val first=f().bills.first{it.planId==pool&&it.number==1}
            r.commitFinancialBillConfirmation(r.previewFinancialBillConfirmation(b,first.id,KnownBillInput(first.date,1060,1000,60,bank)))
            r.commitFinancialPrediction(r.previewFinancialPool(b,r.json.decodeFromString<FinancialPredictionInput>(f().plans.first{it.id==pool}.termsJson!!).copy(planId=pool)))

            val confirmed=f().bills.first{it.id==first.id}
            val contract=f().contracts.single()
            val payment=r.commitFinancialLedger(r.previewRepayment(b,FinancialRepaymentInput(bank,a,Money(Currency.JPY,210),"2026-10-28",Money(Currency.JPY,210),Money(Currency.JPY,200),Money(Currency.JPY,0),Money(Currency.JPY,10),clearingDate="2026-10-28",platformConfirmed=true,allocations=listOf(RepaymentAllocationInput(first.id,contract.id,200,0,10)))))!!
            val allocation=s().repaymentAllocations.single()
            val before=s()
            val inputChange=FinancialArrangementInput(pool,"部分转出",FinanceMode.FIXED_LOAN,FinancialScheduleTerms("2026-10-25",listOf("2026-11-05","2026-12-05")),bank,OriginalFeeTreatment.CANCELLED_BY_PLATFORM,100,poolSelection=PoolArrangementSelection(listOf(PoolBillTransfer(first.id,500,20))))
            assertEquals(Calculation.Incomplete(listOf("arrangement.laterClearing")),r.previewFinancialArrangement(b,inputChange).calculation)
            val moves=listOf(ArrangementClearingMove(allocation.id,null,100,0,5),ArrangementClearingMove(allocation.id,1,100,0,5))
            val review=r.previewFinancialArrangement(b,inputChange.copy(clearingMoves=moves))
            assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready)
            val target=r.commitFinancialArrangement(review,clock.instant().toString(),true)!!
            assertEquals(before.storedPostings,s().storedPostings);assertEquals(before.expenseComponents,s().expenseComponents)
            assertEquals(2,s().events.single{it.id==payment}.revision)
            assertEquals(confirmed,f().bills.first{it.id==first.id})
            assertEquals(435L,s().billOutstanding(confirmed).totalMinor)
            assertEquals(245L,s().billOutstanding(f().bills.first{it.planId==target&&it.number==1}).totalMinor)
            assertEquals(200L,s().repaymentAllocations.sumOf{it.principalMinor!!});assertEquals(10L,s().repaymentAllocations.sumOf{it.feeMinor!!})
            assertEquals(2400L,f().bills.filter{it.planId==pool&&it.active}.map{s().billOutstanding(it).principalMinor}.knownTotal())
            val current=r.json.decodeFromString<FinancialPredictionInput>(f().plans.first{it.id==pool}.termsJson!!)
            val short=current.copy(planId=pool,terms=current.terms.copy(paymentDates=current.terms.paymentDates.take(1),accrualWindows=current.terms.accrualWindows.take(1)))
            r.commitFinancialPrediction(r.previewFinancialPool(b,short))
            val unbilled=inputChange.copy(name="未排期转出",originalFeeTreatment=OriginalFeeTreatment.RETAIN,poolSelection=PoolArrangementSelection(emptyList(),300),clearingMoves=emptyList())
            val unbilledReview=r.previewFinancialArrangement(b,unbilled)
            assertTrue(unbilledReview.calculation.toString(),unbilledReview.calculation is Calculation.Ready)
            assertNull(unbilledReview.comparison!!.totalExtraFeeMinor)
            r.commitFinancialArrangement(unbilledReview,clock.instant().toString(),true)!!
            assertEquals(2800L,s().balance(s().accounts.first{it.id==a}));assertEquals(9790L,s().balance(s().accounts.first{it.id==bank}));assertEquals(10L,s().monthExpense("2026-10"))
            assertFalse(r.financialPredictionStale(s(),f(),f().plans.first{it.id==pool}))
            assertEquals(1700L,r.json.decodeFromString<FinancialPredictionInput>(f().plans.first{it.id==pool}.termsJson!!).projections.last().basis.remainingPrincipalMinor)
        }finally{db.close()}
    }

}
