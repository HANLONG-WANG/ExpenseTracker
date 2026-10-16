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
class FinancialPlanImpactTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-06-10T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    private fun rule(book:String,card:String,bank:String)=FinancialRuleVersion("",book,card,FinanceMode.REVOLVING,"2026-04-01","Asia/Tokyo",billing=BillingRule(31,true,1,27,bank),
        revolving=RevolvingRule(InterestRule("0.18",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,10000,allocation=AllocationOrder.FEES_FIRST,automatic=false))
    @Test fun actualPaymentAndFuturePlanCommitTogetherWithImmutableVersionsOrRollBackTogether()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db,clock);val book=repo.createBook("連動",Currency.JPY)
            val bank=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-04-01")
            val card=repo.saveAccount(book,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,100000),"2026-04-01")
            repo.commitFinancialRule(repo.previewFinancialRule(book,card,rule(book,card,bank)))
            val input=FinancialPredictionInput(card,"既存リボ",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-04-01",listOf("2026-05-27","2026-06-27","2026-07-27"),listOf(AccrualWindow("2026-04-01","2026-04-30"),AccrualWindow("2026-05-01","2026-05-31"),AccrualWindow("2026-06-01","2026-06-30"))),openingPrincipalMinor=100000,paymentAccountId=bank,currentArrangementConfirmed=true)
            val plan=repo.commitFinancialPrediction(repo.previewFinancialPrediction(book,input))!!
            val first=db.financialDao().bills(book,plan).first()
            repo.commitFinancialBillConfirmation(repo.previewFinancialBillConfirmation(book,first.id,KnownBillInput(first.date,21479,20000,1479,bank)))
            val confirmed=db.financialDao().bill(book,first.id)!!
            val before=db.financialDao().bills(book,plan)
            val original=db.financialDao().planRevisions(book,plan).first()
            assertEquals(listOf(2,1),original.map{it.version})
            val repayment=FinancialRepaymentInput(bank,card,Money(Currency.JPY,5100),"2026-06-05",Money(Currency.JPY,5100),Money(Currency.JPY,5000),Money(Currency.JPY,100),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-06-05",type=RepaymentType.PARTIAL,allocations=listOf(RepaymentAllocationInput(first.id,null,5000,100,0)))
            val review=repo.previewRepayment(book,repayment)
            val impact=review.planImpacts.single()
            assertTrue(impact.updatesPrediction);assertEquals(1395L,impact.nextBills.last().feeMinor)
            assertEquals(confirmed,impact.nextBills.first())
            assertEquals(before,db.financialDao().bills(book,plan));assertTrue(repo.snapshot(repo.dao.book(book)!!).first().events.isEmpty())
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_plan_revision BEFORE INSERT ON financial_plan_revisions WHEN NEW.reason='REPAYMENT' BEGIN SELECT RAISE(ABORT,'verification rollback'); END")
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java){runBlocking{repo.commitFinancialLedger(review)}}
            assertTrue(repo.snapshot(repo.dao.book(book)!!).first().events.isEmpty());assertEquals(before,db.financialDao().bills(book,plan));assertEquals(original,db.financialDao().planRevisions(book,plan).first())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_plan_revision")
            val payment=repo.commitFinancialLedger(review)!!
            assertNull(repo.commitFinancialLedger(review))
            var snapshot=repo.snapshot(repo.dao.book(book)!!).first();var graph=db.financialDao().graph(book).first()!!
            assertEquals(194900L,snapshot.balance(snapshot.accounts.first{it.id==bank}));assertEquals(95000L,snapshot.balance(snapshot.accounts.first{it.id==card}));assertEquals(100L,snapshot.monthExpense("2026-06"))
            assertFalse(repo.financialPredictionStale(snapshot,graph,graph.plans.single()))
            val versions=db.financialDao().planRevisions(book,plan).first()
            assertEquals(listOf(3,2,1),versions.map{it.version});assertEquals(original,versions.drop(1))
            assertEquals("REPAYMENT",versions.first().reason);assertEquals(payment,versions.first().causeEventId)
            val archived=repo.json.decodeFromString<FinancialPlanArchive>(versions.first().json)
            assertEquals(1395L,archived.bills.last().feeMinor);assertEquals(5000L,archived.facts!!.principalFacts().single().principalMinor)
            val stale=repo.previewRepayment(book,repayment.copy(eventId=payment))
            repo.saveAccount(book,"銀行 更新",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-04-01",bank)
            assertNull(repo.commitFinancialLedger(stale));assertEquals(versions,db.financialDao().planRevisions(book,plan).first())
            repo.commitFinancialRule(repo.previewFinancialRule(book,card,rule(book,card,bank).copy(revolving=rule(book,card,bank).revolving!!.copy(interest=InterestRule()))))
            val second=before[1]
            val missing=repo.previewRepayment(book,FinancialRepaymentInput(bank,card,Money(Currency.JPY,1000),"2026-06-10",Money(Currency.JPY,1000),Money(Currency.JPY,1000),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-06-10",allocations=listOf(RepaymentAllocationInput(second.id,null,1000,0,0))))
            assertFalse(missing.planImpacts.single().updatesPrediction)
            assertTrue(missing.planImpacts.single().forecast!!.calculation is Calculation.Incomplete)
            repo.commitFinancialLedger(missing)
            assertEquals(1395L,db.financialDao().bills(book,plan).last().feeMinor)
            assertEquals(confirmed,db.financialDao().bill(book,first.id))
            snapshot=repo.snapshot(repo.dao.book(book)!!).first();graph=db.financialDao().graph(book).first()!!
            assertEquals(193900L,snapshot.balance(snapshot.accounts.first{it.id==bank}));assertEquals(94000L,snapshot.balance(snapshot.accounts.first{it.id==card}));assertEquals(2,snapshot.events.size)
            assertTrue(repo.financialPredictionStale(snapshot,graph,graph.plans.single()))
            assertEquals("REPAYMENT_REVIEW",db.financialDao().planRevisions(book,plan).first().first().reason)
        }finally{db.close()}
    }

    @Test fun versionFourteenUpgradePreservesActualFactsAndArchivesTheExistingBaselineOnNextWrite()=runBlocking {
        val name="linked-plan-upgrade.db";context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build()
        try {
            var repo=LedgerRepository(db,clock);val book=repo.createBook("既存",Currency.JPY)
            val bank=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,20000),"2026-04-01")
            val card=repo.saveAccount(book,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,10000),"2026-04-01")
            val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(book,ManualFinancialPlanInput(card,"既知",FinanceMode.SINGLE,listOf(KnownBillInput("2026-06-27",10100,10000,100,bank)),principalMinor=10000,opening=true)))!!
            val bill=db.financialDao().bills(book,plan).single()
            fun input(amount:Long)=FinancialRepaymentInput(bank,card,Money(Currency.JPY,amount),"2026-06-10",Money(Currency.JPY,amount),Money(Currency.JPY,amount),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-06-10",allocations=listOf(RepaymentAllocationInput(bill.id,null,amount,0,0)))
            val paid=repo.commitFinancialLedger(repo.previewRepayment(book,input(3000)))!!
            val oldPlan=db.financialDao().plan(book,plan)!!
            db.close()
            val old=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE)
            try {
                dropCreditLimitFixtureTables(old)
                old.execSQL("DROP TABLE expected_incomes");old.execSQL("DROP TABLE financial_bill_adjustments")
                old.execSQL("DROP TABLE financial_arrangements")
                old.execSQL("DROP TABLE financial_plan_revisions")
                val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v14-schema.json").bufferedReader().readText()).getJSONObject("database")
                old.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42",arrayOf(schema.getString("identityHash")))
                restoreFixtureTable(old,"room-v14-schema.json","settlements")
                old.execSQL("DROP TABLE IF EXISTS financial_drafts");restoreBookLifecycleFixture(old);old.version=14
            }finally{old.close()}
            db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(MIGRATION_14_15,MIGRATION_15_16,MIGRATION_16_17,MIGRATION_17_18,MIGRATION_18_19,MIGRATION_19_20,MIGRATION_20_21,MIGRATION_21_22,MIGRATION_22_23,com.yohaku.expensetracker.data.db.MIGRATION_23_24,com.yohaku.expensetracker.data.db.MIGRATION_24_25,com.yohaku.expensetracker.data.db.MIGRATION_25_26,com.yohaku.expensetracker.data.db.MIGRATION_26_27,com.yohaku.expensetracker.data.db.MIGRATION_27_28).build();repo=LedgerRepository(db,clock)
            assertEquals(oldPlan,db.financialDao().plan(book,plan));assertEquals(bill,db.financialDao().bill(book,bill.id))
            assertEquals(paid,repo.snapshot(repo.dao.book(book)!!).first().repayments.single().eventId)
            assertTrue(db.financialDao().planRevisions(book,plan).first().isEmpty())
            val review=repo.previewRepayment(book,input(1000))
            assertEquals(FinancialPlanImpactIssue.MANUAL,review.planImpacts.single().issue)
            repo.commitFinancialLedger(review)
            val rows=db.financialDao().planRevisions(book,plan).first()
            assertEquals(listOf(oldPlan.revision+1,oldPlan.revision),rows.map{it.version});assertEquals("EXISTING",rows.last().reason)
            assertEquals("REPAYMENT_MANUAL",rows.first().reason)
            assertEquals(1,repo.json.decodeFromString<FinancialPlanArchive>(rows.last().json).facts!!.repayments.size)
            assertEquals(2,repo.json.decodeFromString<FinancialPlanArchive>(rows.first().json).facts!!.repayments.size)
            val s=repo.snapshot(repo.dao.book(book)!!).first()
            assertEquals(16000L,s.balance(s.accounts.first{it.id==bank}));assertEquals(6000L,s.balance(s.accounts.first{it.id==card}));assertEquals(2,s.events.size)
        }finally{db.close();context.deleteDatabase(name)}
    }
}
