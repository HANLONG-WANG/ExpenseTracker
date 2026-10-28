package com.yohaku.expensetracker

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class FinancialArrangementTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    private fun installment(book:String,card:String,bank:String,periods:Int=3,fee:Long=201)=FinancialRuleVersion("",book,card,FinanceMode.INSTALLMENT,"2026-09-01","Asia/Tokyo",billing=BillingRule(31,true,1,27,bank),installment=InstallmentRule(listOf(periods),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=fee,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"))
    @Test fun comparisonAndRecordedApplicationsPreserveCashAndHistoryAcrossRecalculationAndASecondChange()=runBlocking {
        val name="arrangement-flow.db";context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build()
        try {
            var repo=LedgerRepository(db,clock)
            val book=repo.createBook("方式変更",Currency.JPY,startDate="2026-10-01")
            val bank=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,7000),"2026-10-01")
            val card=repo.saveAccount(book,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
            val category=repo.saveCategory(book,"買物",EntryKind.EXPENSE,null)
            val source=repo.saveEntry(book,EntryDraft(accountId=card,categoryId=category,amount="10000",date="2026-10-15"))
            repo.commitFinancialRule(repo.previewFinancialRule(book,card,installment(book,card,bank).copy(mode=FinanceMode.SINGLE)))
            repo.commitFinancialRule(repo.previewFinancialRule(book,card,installment(book,card,bank)))
            val original=repo.commitFinancialPrediction(repo.previewFinancialPrediction(book,FinancialPredictionInput(card,"一括",FinanceMode.SINGLE,FinancialScheduleTerms("2026-10-15",listOf("2026-11-27")),sourceEventId=source,paymentAccountId=bank,currentArrangementConfirmed=true)))!!
            val first=db.financialDao().bills(book,original).single()
            repo.commitFinancialBillConfirmation(repo.previewFinancialBillConfirmation(book,first.id,KnownBillInput(first.date,10000,10000,0,bank)))
            val confirmed=db.financialDao().bill(book,first.id)!!
            val input=FinancialArrangementInput(original,"3回払い",FinanceMode.INSTALLMENT,FinancialScheduleTerms("2026-10-15",listOf("2026-11-27","2026-12-27","2027-01-27")),bank,OriginalFeeTreatment.RETAIN,0)
            var review=repo.previewFinancialArrangement(book,input)
            assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready);assertEquals(6600L,review.comparison!!.currentReductionMinor);assertEquals(201L,review.comparison!!.totalExtraFeeMinor)
            assertEquals(listOf(3400L,3400L,3401L),review.bills.map{it.totalMinor})
            assertTrue(review.comparison!!.originalComplete);assertTrue(review.comparison!!.proposedComplete)
            val funding=review.comparison!!.funding.single{it.accountId==bank}
            assertEquals(3000L,funding.beforeGapMinor);assertEquals(3201L,funding.afterGapMinor)
            assertEquals("2026-11-27",funding.beforeRiskDate);assertEquals("2027-01-27",funding.afterRiskDate)
            assertEquals(1,db.financialDao().graph(book).first()!!.plans.size)
            assertThrows(IllegalArgumentException::class.java){runBlocking{repo.commitFinancialArrangement(review,"2026-10-15T00:00:00Z",false)}}
            repo.saveAccount(book,"別資金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
            assertNull(repo.commitFinancialArrangement(review,"2026-10-15T00:00:00Z",true))
            review=repo.previewFinancialArrangement(book,input)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_arrangement BEFORE INSERT ON financial_plan_revisions WHEN NEW.reason='ARRANGEMENT_APPLIED' BEGIN SELECT RAISE(ABORT,'verification rollback'); END")
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java){runBlocking{repo.commitFinancialArrangement(review,"2026-10-15T00:00:00Z",true)}}
            assertEquals(1,db.financialDao().graph(book).first()!!.plans.size);assertTrue(db.financialDao().graph(book).first()!!.arrangements.isEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_arrangement")
            val target=repo.commitFinancialArrangement(review,"2026-10-15T00:00:00Z",true)!!
            assertNull(repo.commitFinancialArrangement(review,"2026-10-15T00:00:00Z",true))
            var s=repo.snapshot(repo.dao.book(book)!!).first();var f=db.financialDao().graph(book).first()!!
            assertEquals(7000L,s.balance(s.accounts.first{it.id==bank}));assertEquals(10000L,s.balance(s.accounts.first{it.id==card}));assertEquals(10000L,s.monthExpense("2026-10"));assertEquals(1,s.events.size)
            assertEquals(confirmed,db.financialDao().bill(book,first.id));assertEquals(0L,s.billOutstanding(confirmed).totalMinor)
            assertEquals(10201L,f.bills.filter{b->f.plans.any{it.id==b.planId&&it.active}}.sumOf{s.billOutstanding(it).totalMinor})
            assertFalse(repo.financialPredictionStale(s,f,f.plans.first{it.id==target}))
            val saved=repo.json.decodeFromString<FinancialPredictionInput>(f.plans.first{it.id==target}.termsJson!!)
            assertEquals(target,repo.commitFinancialPrediction(repo.previewFinancialPrediction(book,saved.copy(planId=target))))
            val bill=db.financialDao().bills(book,target).first()
            val paid=repo.commitFinancialLedger(repo.previewRepayment(book,FinancialRepaymentInput(bank,card,Money(Currency.JPY,3400),"2026-11-27",Money(Currency.JPY,3400),Money(Currency.JPY,3333),Money(Currency.JPY,0),Money(Currency.JPY,67),platformConfirmed=true,clearingDate="2026-11-27",allocations=listOf(RepaymentAllocationInput(bill.id,null,3333,0,67)))))!!
            s=repo.snapshot(repo.dao.book(book)!!).first();f=db.financialDao().graph(book).first()!!
            assertEquals(3600L,s.balance(s.accounts.first{it.id==bank}));assertEquals(6667L,s.balance(s.accounts.first{it.id==card}));assertEquals(67L,s.monthExpense("2026-11"));assertFalse(repo.financialPredictionStale(s,f,f.plans.first{it.id==target}))
            val later=Clock.fixed(Instant.parse("2026-11-27T00:00:00Z"),ZoneId.of("Asia/Tokyo"));repo=LedgerRepository(db,later)
            repo.commitFinancialRule(repo.previewFinancialRule(book,card,installment(book,card,bank).copy(mode=FinanceMode.REVOLVING,revolving=RevolvingRule(InterestRule("0.12",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,1000,allocation=AllocationOrder.FEES_FIRST,automatic=false))))
            val next=repo.previewFinancialArrangement(book,FinancialArrangementInput(target,"リボへ変更",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-11-27",listOf("2026-12-27","2027-01-27"),listOf(AccrualWindow("2026-11-27","2026-11-30"),AccrualWindow("2026-12-01","2026-12-31"))),bank,OriginalFeeTreatment.CANCELLED_BY_PLATFORM,100))
            assertTrue(next.calculation.toString(),next.calculation is Calculation.Ready);assertEquals(6667L,next.target!!.principalMinor);assertNull(next.comparison!!.totalExtraFeeMinor);assertEquals(4667L,next.comparison!!.proposedRemainingPrincipalMinor)
            assertTrue(next.comparison!!.originalComplete);assertFalse(next.comparison!!.proposedComplete)
            val current=repo.commitFinancialArrangement(next,"2026-11-27T00:00:00Z",true)!!
            db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build();repo=LedgerRepository(db,later)
            f=db.financialDao().graph(book).first()!!;s=repo.snapshot(repo.dao.book(book)!!).first()
            val savedComparison=repo.json.decodeFromString<FinancialArrangementComparison>(f.arrangements.first{it.originalPlanId==original}.comparisonJson)
            assertEquals(review.comparison,savedComparison)
            assertEquals(2,f.arrangements.size);assertFalse(repo.financialPredictionStale(s,f,f.plans.first{it.id==current}));assertEquals(paid,s.repayments.single().eventId)
            assertEquals(3600L,s.balance(s.accounts.first{it.id==bank}));assertEquals(6667L,s.balance(s.accounts.first{it.id==card}));assertEquals(2,s.events.size)
            // Confirmed bills do not excuse a changed clearing baseline on a later application.
            for(b in f.bills.filter{it.planId==current})repo.commitFinancialBillConfirmation(repo.previewFinancialBillConfirmation(book,b.id,KnownBillInput(b.date,b.totalMinor,b.principalMinor,b.feeMinor,b.paymentAccountId)))
            repo.deleteEntry(book,paid)
            val changed=repo.previewFinancialArrangement(book,input.copy(planId=current,terms=input.terms.copy(agreementDate="2026-11-27")))
            assertEquals(Calculation.Incomplete(listOf("arrangement.baselineChanged")),changed.calculation)
        }finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun retainedOverduePrincipalAndOriginalFeesStayOwedAndDeadlineExceptionsAreExplicit()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db,clock);val book=repo.createBook("既存変更",Currency.JPY)
            val bank=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,15000),"2026-09-01")
            val card=repo.saveAccount(book,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,10000),"2026-09-01")
            val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(book,ManualFinancialPlanInput(card,"既知",FinanceMode.INSTALLMENT,listOf(KnownBillInput("2026-09-27",2100,2000,100,bank),KnownBillInput("2026-10-27",4200,4000,200,bank),KnownBillInput("2026-11-27",4200,4000,200,bank)),principalMinor=10000,complete=true,opening=true)))!!
            val bills=db.financialDao().bills(book,plan)
            repo.commitFinancialLedger(repo.previewRepayment(book,FinancialRepaymentInput(bank,card,Money(Currency.JPY,500),"2026-09-28",Money(Currency.JPY,500),Money(Currency.JPY,500),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-09-28",allocations=listOf(RepaymentAllocationInput(bills.first().id,null,500,0,0)))))
            val rule=installment(book,card,bank,2,160).copy(deadlines=bills.drop(1).map{ChangeDeadline(it.id,FinanceMode.INSTALLMENT,"2026-10-14","15:00","Asia/Tokyo")})
            repo.commitFinancialRule(repo.previewFinancialRule(book,card,rule))
            val input=FinancialArrangementInput(plan,"残り2回",FinanceMode.INSTALLMENT,FinancialScheduleTerms("2026-10-15",listOf("2026-10-27","2026-11-27")),bank,OriginalFeeTreatment.RETAIN,40)
            assertTrue(repo.previewFinancialArrangement(book,input.copy(changeFeeMinor=null)).calculation is Calculation.Incomplete)
            val review=repo.previewFinancialArrangement(book,input)
            assertEquals(8000L,review.target!!.principalMinor);assertEquals(200L,review.comparison!!.totalExtraFeeMinor);assertEquals(-120L,review.comparison!!.currentReductionMinor)
            assertThrows(IllegalArgumentException::class.java){runBlocking{repo.commitFinancialArrangement(review,"2026-10-15T00:00:00Z",true)}}
            val target=repo.commitFinancialArrangement(review,"2026-10-14T05:00:00Z",true)!!
            val s=repo.snapshot(repo.dao.book(book)!!).first();val f=db.financialDao().graph(book).first()!!
            assertEquals(1600L,s.billOutstanding(bills[0]).totalMinor);assertEquals(200L,s.billOutstanding(bills[1]).totalMinor);assertEquals(200L,s.billOutstanding(bills[2]).totalMinor)
            assertEquals(14500L,s.balance(s.accounts.first{it.id==bank}));assertEquals(9500L,s.balance(s.accounts.first{it.id==card}));assertEquals(0L,s.monthExpense("2026-10"))
            assertTrue(f.plans.first{it.id==plan}.active);assertEquals(listOf(4120L,4080L),f.bills.filter{it.planId==target}.map{it.totalMinor})
            assertEquals(bills,db.financialDao().bills(book,plan))
        }finally{db.close()}
    }

    @Test fun versionFifteenUpgradePreservesPlanVersionsAndAddsEmptyArrangementTables()=runBlocking {
        val name="arrangement-upgrade.db";context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build()
        try {
            var repo=LedgerRepository(db,clock);val book=repo.createBook("旧版",Currency.JPY)
            val card=repo.saveAccount(book,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(book,ManualFinancialPlanInput(card,"既知",FinanceMode.SINGLE,listOf(KnownBillInput("2026-11-27",10000,10000,0)),principalMinor=10000,opening=true)))!!
            val versions=db.financialDao().planRevisions(book,plan).first();db.close()
            val old=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE)
            try {
                old.execSQL("DROP TABLE expected_incomes");old.execSQL("DROP TABLE financial_bill_adjustments");old.execSQL("DROP TABLE financial_arrangements")
                val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v15-schema.json").bufferedReader().readText()).getJSONObject("database")
                old.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42",arrayOf(schema.getString("identityHash")));old.execSQL("DROP TABLE IF EXISTS financial_drafts");restoreFixtureDatabase(old,15)
            }finally{old.close()}
            db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(*ALL_MIGRATIONS).build();repo=LedgerRepository(db,clock)
            val f=db.financialDao().graph(book).first()!!
            assertTrue(f.arrangements.isEmpty());assertTrue(f.billAdjustments.isEmpty());assertEquals(versions,db.financialDao().planRevisions(book,plan).first());assertEquals(10000L,repo.snapshot(repo.dao.book(book)!!).first().balance(f.book.let{repo.dao.account(book,card)!!}))
        }finally{db.close();context.deleteDatabase(name)}
    }
}
