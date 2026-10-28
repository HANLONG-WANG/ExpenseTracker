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

    @Test fun timelinePreviewDoesNotPersistOccurrences()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db,clock);val b=repo.createBook("資金",Currency.JPY,startDate="2026-10-01")
            val bank=repo.saveAccount(b,"引落",AccountType.BANK,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
            val cat=repo.saveCategory(b,"費用",EntryKind.EXPENSE,null)
            repo.saveRecurrence(b,RecurrenceInput("固定費",bank,cat,Money(Currency.JPY,400),Money(Currency.JPY,400),false,RecurrenceFrequency.MONTHLY,"2026-10-18",null))
            repo.ensureOccurrences(b,"2026-10")
            val s=repo.snapshot(repo.dao.book(b)!!).first();val stored=repo.dao.occurrences(b)
            val longer=s.cashTimeline(db.financialDao().graph(b).first(),LocalDate.parse("2026-10-15"),LocalDate.parse("2026-12-31"))
            assertEquals(3,longer.steps.count{it.item.kind=="RECURRENCE"})
            assertEquals(stored,repo.dao.occurrences(b));assertTrue(repo.snapshot(repo.dao.book(b)!!).first().events.isEmpty())
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
                old.execSQL("DROP TABLE expected_incomes")
                val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v16-schema.json").bufferedReader().readText()).getJSONObject("database")
                old.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42",arrayOf(schema.getString("identityHash")));old.execSQL("DROP TABLE IF EXISTS financial_drafts");restoreFixtureDatabase(old,16)
            }finally{old.close()}
            db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(*ALL_MIGRATIONS).build()
            val s=LedgerRepository(db,clock).snapshot(db.dao().book(b)!!).first()
            assertEquals(event,s.events.single().id);assertEquals(1450L,s.balance(s.accounts.single()));assertTrue(s.expectedIncomes.isEmpty())
        }finally{db.close();context.deleteDatabase(name)}
    }
}
