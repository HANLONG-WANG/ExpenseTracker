package com.yohaku.expensetracker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.finance.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class ExpectedIncomeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    @Test fun plannedIncomeDoesNotPostAndArrivalIsAtomicStaleSafeAndCanLinkExistingIncome()=runBlocking {
        val name="expected-income.db";context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build()
        try {
            var repo=LedgerRepository(db,clock);val b=repo.createBook("予定",Currency.JPY,budgetMinor=5000)
            val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
            val cat=repo.saveCategory(b,"給与",EntryKind.INCOME,null)
            val id=repo.commitExpectedIncome(repo.previewExpectedIncome(b,ExpectedIncomeInput("給与見込み",Money(Currency.JPY,500),"2026-10-19",bank)))!!
            var s=repo.snapshot(repo.dao.book(b)!!).first()
            assertEquals(1000L,s.balance(s.accounts.single()));assertEquals(5000L,s.budgetLimit("2026-10"));assertTrue(s.events.isEmpty())
            val draft=EntryDraft(kind=EntryKind.INCOME,accountId=bank,categoryId=cat,amount="450",date="2026-10-15")
            var review=repo.previewExpectedIncomeArrival(b,id,draft)
            repo.saveAccount(b,"銀行 更新",AccountType.BANK,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01",bank)
            assertNull(repo.commitExpectedIncomeArrival(review));assertTrue(repo.snapshot(repo.dao.book(b)!!).first().events.isEmpty())
            review=repo.previewExpectedIncomeArrival(b,id,draft)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_income BEFORE UPDATE ON expected_incomes WHEN NEW.status='ARRIVED' BEGIN SELECT RAISE(ABORT,'verification rollback'); END")
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java){runBlocking{repo.commitExpectedIncomeArrival(review)}}
            assertTrue(repo.snapshot(repo.dao.book(b)!!).first().events.isEmpty());assertEquals("PENDING",repo.dao.expectedIncome(b,id)!!.status)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_income")
            val event=repo.commitExpectedIncomeArrival(review)!!
            assertNull(repo.commitExpectedIncomeArrival(review))
            db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build();repo=LedgerRepository(db,clock)
            s=repo.snapshot(repo.dao.book(b)!!).first();val income=s.expectedIncomes.single()
            assertEquals(1450L,s.balance(s.accounts.single()));assertEquals(500L,income.amountMinor);assertEquals(event,s.expectedIncomeArrival(income)!!.id);assertEquals(1,s.events.size)
            assertTrue(s.cashTimeline(db.financialDao().graph(b).first(),LocalDate.parse("2026-10-15"),LocalDate.parse("2026-10-31")).steps.none{it.item.kind=="EXPECTED_INCOME"})
            repo.deleteEntry(b,event)
            s=repo.snapshot(repo.dao.book(b)!!).first();assertTrue(s.expectedIncomeNeedsReview(s.expectedIncomes.single()));assertEquals(1000L,s.balance(s.accounts.single()))
            repo.restoreEntry(b,event)
            val second=repo.commitExpectedIncome(repo.previewExpectedIncome(b,ExpectedIncomeInput("追加見込み",Money(Currency.JPY,300),"2026-10-20",bank)))!!
            assertThrows(IllegalArgumentException::class.java){runBlocking{repo.previewExpectedIncomeArrival(b,second,existingEventId=event)}}
            val other=repo.saveEntry(b,draft.copy(amount="200"))
            val linked=repo.commitExpectedIncomeArrival(repo.previewExpectedIncomeArrival(b,second,existingEventId=other))!!
            assertEquals(other,linked);s=repo.snapshot(repo.dao.book(b)!!).first();assertEquals(1650L,s.balance(s.accounts.single()));assertEquals(2,s.events.size)
            val third=repo.commitExpectedIncome(repo.previewExpectedIncome(b,ExpectedIncomeInput("取消",Money(Currency.CNY,20000),"2026-10-22",bank)))!!
            assertNull(repo.dao.expectedIncome(b,third)!!.accountMinor)
            repo.cancelExpectedIncome(b,third);assertEquals("CANCELLED",repo.dao.expectedIncome(b,third)!!.status)
            assertEquals(0L,s.monthExpense("2026-10"))
        }finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun cashTimelineDeduplicatesFuturePostingsBillsReservesAndPendingClearing()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db,clock);val b=repo.createBook("資金",Currency.JPY,startDate="2026-10-01")
            val bank=repo.saveAccount(b,"引落",AccountType.BANK,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
            repo.saveAccount(b,"他口座",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-10-01")
            val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,800),"2026-10-01")
            val cat=repo.saveCategory(b,"費用",EntryKind.EXPENSE,null)
            val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"カード支払",FinanceMode.SINGLE,listOf(KnownBillInput("2026-10-20",800,800,0,bank)),principalMinor=800,opening=true)))!!
            val bill=db.financialDao().bills(b,plan).single()
            repo.saveEntry(b,EntryDraft(accountId=bank,categoryId=cat,amount="100",date="2026-10-17"))
            val recurrence=repo.saveRecurrence(b,RecurrenceInput("固定費",bank,cat,Money(Currency.JPY,400),Money(Currency.JPY,400),false,RecurrenceFrequency.MONTHLY,"2026-10-18",null))
            repo.ensureOccurrences(b,"2026-10")
            repo.setActivityMode(b,true)
            val activity=repo.saveActivity(b,"シーン","2026-10-01","2026-10-31",null)
            val occurrence=repo.dao.occurrences(b).first{it.recurrenceId==recurrence}
            // A reserve for the same fixed payment is not an additional cash outflow.
            val linkedOccurrence=occurrence.copy(activityId=activity);repo.dao.putOccurrence(linkedOccurrence)
            repo.commitReserve(repo.previewReserve(b,ReserveInput(activity,"固定費の確保","2026-10-18",Money(Currency.JPY,300),bank,Money(Currency.JPY,300),occurrence.id)))
            repo.commitExpectedIncome(repo.previewExpectedIncome(b,ExpectedIncomeInput("入金見込み",Money(Currency.JPY,500),"2026-10-19",bank)))
            var s=repo.snapshot(repo.dao.book(b)!!).first();var f=db.financialDao().graph(b).first()!!
            var timeline=s.cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-10-31"))
            assertEquals(1000L,timeline.openings.first{it.account.id==bank}.actualMinor)
            assertEquals(3,timeline.steps.count{it.item.kind!="EXPECTED_INCOME"})
            var payment=timeline.steps.single{it.item.id=="bill:${bill.id}"}
            assertEquals(-300L,payment.baselineAfterMinor);assertEquals(200L,payment.includingIncomeAfterMinor)
            val risk=timeline.accountRisks().first{it.opening.account.id==bank}
            assertEquals(0L,risk.currentGapMinor);assertEquals(300L,risk.maximumGapMinor);assertEquals(0L,risk.includingIncomeGapMinor)
            assertEquals("2026-10-20",risk.firstRiskDate)
            assertEquals(0L,timeline.accountRisks().first{it.opening.account.id!=bank}.maximumGapMinor)
            val stopped=s.copy(accounts=s.accounts.map{if(it.id==bank)it.copy(active=false)else it}).cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-10-31"))
            assertEquals("PAYMENT_ACCOUNT_INACTIVE",stopped.steps.single{it.item.id=="bill:${bill.id}"}.item.issue)
            assertNull(stopped.accountRisks().first{it.opening.account.id==bank}.maximumGapMinor)
            // Already debited cash awaiting platform clearing must not be requested a second time.
            repo.commitFinancialLedger(repo.previewRepayment(b,FinancialRepaymentInput(bank,card,Money(Currency.JPY,300),"2026-10-15",Money(Currency.JPY,300),Money(Currency.JPY,300),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=false,allocations=listOf(RepaymentAllocationInput(bill.id,null,300,0,0)))))
            s=repo.snapshot(repo.dao.book(b)!!).first();f=db.financialDao().graph(b).first()!!
            timeline=s.cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-10-31"))
            payment=timeline.steps.single{it.item.id=="bill:${bill.id}"}
            assertEquals(-500L,payment.item.accountDeltaMinor);assertEquals(-300L,payment.baselineAfterMinor);assertEquals(800L,s.billOutstanding(bill).totalMinor)
            val unknownClearing=s.copy(repaymentAllocations=s.repaymentAllocations.map{it.copy(principalMinor=null)}).cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-10-31"))
            assertNull(unknownClearing.steps.last().baselineAfterMinor);assertEquals("PENDING_CLEARING_AMOUNT",unknownClearing.steps.last().item.issue)
            val income=s.expectedIncomes.single()
            val unknown=s.copy(expectedIncomes=listOf(income.copy(currency="CNY",accountMinor=null))).cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-10-31"))
            assertNull(unknown.steps.last().includingIncomeAfterMinor);assertEquals(-300L,unknown.steps.last().baselineAfterMinor)
            val stored=repo.dao.occurrences(b)
            val longer=s.cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-12-31"))
            assertEquals(3,longer.steps.count{it.item.kind=="RECURRENCE"})
            assertEquals(stored,repo.dao.occurrences(b)) // A longer preview must not materialize or post anything.
            repo.ensureOccurrences(b,"2026-11")
            val november=repo.dao.occurrences(b).single{it.scheduledDate=="2026-11-18"}
            repo.cancelOccurrence(b,november.id)
            s=repo.snapshot(repo.dao.book(b)!!).first()
            assertEquals(2,s.cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-12-31")).steps.count{it.item.kind=="RECURRENCE"})
        }finally{db.close()}
    }

    @Test fun versionSixteenUpgradePreservesActualIncomeAndCreatesNoExpectedItems()=runBlocking {
        val name="income-upgrade.db";context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build()
        try {
            val repo=LedgerRepository(db,clock);val b=repo.createBook("旧版",Currency.JPY)
            val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
            val cat=repo.saveCategory(b,"給与",EntryKind.INCOME,null)
            val event=repo.saveEntry(b,EntryDraft(kind=EntryKind.INCOME,accountId=bank,categoryId=cat,amount="450",date="2026-10-15"))
            db.close();val old=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE)
            try {
                dropCreditLimitFixtureTables(old)
                old.execSQL("DROP TABLE expected_incomes")
                val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v16-schema.json").bufferedReader().readText()).getJSONObject("database")
                old.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42",arrayOf(schema.getString("identityHash")));restoreFixtureTable(old,"room-v16-schema.json","settlements");old.execSQL("DROP TABLE IF EXISTS financial_drafts");old.version=16
            }finally{old.close()}
            db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(MIGRATION_16_17,MIGRATION_17_18,MIGRATION_18_19,MIGRATION_19_20,MIGRATION_20_21,MIGRATION_21_22,MIGRATION_22_23,com.yohaku.expensetracker.data.db.MIGRATION_23_24,com.yohaku.expensetracker.data.db.MIGRATION_24_25).build()
            val s=LedgerRepository(db,clock).snapshot(db.dao().book(b)!!).first()
            assertEquals(event,s.events.single().id);assertEquals(1450L,s.balance(s.accounts.single()));assertTrue(s.expectedIncomes.isEmpty())
        }finally{db.close();context.deleteDatabase(name)}
    }
}
