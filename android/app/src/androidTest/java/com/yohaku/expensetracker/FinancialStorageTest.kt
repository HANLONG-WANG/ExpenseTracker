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
class FinancialStorageTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    private fun rule(book:String,card:String,bank:String?)=FinancialRuleVersion("",book,card,FinanceMode.SINGLE,"2026-10-01","Asia/Tokyo",billing=BillingRule(31,true,1,27,bank))

    @Test fun incompleteRulesAndManualSchedulesSurviveReopenWithoutCreatingMoney()=runBlocking {
        val name="finance-storage-verification.db"
        context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build()
        try {
            var repo=LedgerRepository(db,clock)
            val book=repo.createBook("金融",Currency.JPY)
            val bank=repo.saveAccount(book,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,20000),"2026-10-01")
            val card=repo.saveAccount(book,"卡",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val incomplete=repo.previewFinancialRule(book,card,rule(book,card,null))
            assertTrue(incomplete.readiness is RuleReadiness.Incomplete)
            val first=repo.commitFinancialRule(incomplete)!!
            assertNull(repo.commitFinancialRule(incomplete))
            val manual=repo.previewManualFinancialPlan(book,ManualFinancialPlanInput(card,"已知下一期",FinanceMode.SINGLE,
                listOf(KnownBillInput("2026-11-27",3500,paymentAccountId=bank)),opening=true))
            assertTrue(db.financialDao().plans(book,card).isEmpty())
            val plan=repo.commitManualFinancialPlan(manual)!!
            assertNull(repo.commitManualFinancialPlan(manual))
            val original=db.financialDao().bills(book,plan).single()
            assertNull(original.principalMinor);assertNull(original.feeMinor)
            assertEquals(3500L,original.totalMinor);assertTrue(original.confirmed)
            val change=repo.previewFinancialRule(book,card,rule(book,card,bank))
            assertTrue(change.readiness is RuleReadiness.Complete)
            assertEquals(listOf(original),change.retainedConfirmedBills)
            assertTrue(change.affectedPredictions.isEmpty())
            val second=repo.commitFinancialRule(change)!!
            assertNotEquals(first,second)
            assertEquals(listOf(1,2),db.financialDao().rules(book,card).map{it.version})
            assertEquals(original,db.financialDao().bill(book,original.id))
            assertEquals(second,repo.effectiveFinancialRule(book,card,FinanceMode.SINGLE,LocalDate.of(2026,10,15))!!.id)
            assertEquals("UNVERIFIED",db.financialDao().graph(book).first()!!.ruleValidationStatus(second))
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                repo.saveAccount(book,"卡",AccountType.CREDIT,Currency.CNY,Money(Currency.CNY,10000),"2026-10-01",card)
            } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                repo.updateBook(repo.dao.book(book)!!.copy(currency="CNY"))
            } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                repo.previewBookBudget(repo.dao.book(book)!!.copy(currency="CNY"),"2026-10",null)
            } }
            db.close()
            db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build()
            repo=LedgerRepository(db,clock)
            val snapshot=repo.snapshot(repo.dao.book(book)!!).first()
            assertEquals(20000L,snapshot.balance(snapshot.accounts.first{it.id==bank}))
            assertEquals(10000L,snapshot.balance(snapshot.accounts.first{it.id==card}))
            assertTrue(snapshot.events.isEmpty());assertTrue(snapshot.postings.isEmpty())
            assertEquals(original,db.financialDao().bill(book,original.id))
            assertEquals(2,db.financialDao().rules(book,card).size)
        }finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun confirmationsPreservePredictionsAndValidationReportsExactDifferences()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db,clock)
            val book=repo.createBook("金融",Currency.JPY)
            val bank=repo.saveAccount(book,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,20000),"2026-10-01")
            val card=repo.saveAccount(book,"卡",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val ruleId=repo.commitFinancialRule(repo.previewFinancialRule(book,card,rule(book,card,bank)))!!
            // A deterministic calculation fixture, not a product path accepting arbitrary forecasts.
            db.financialDao().insertPlan(FinancialPlanEntity(book,"plan",card,"预测",FinanceMode.SINGLE.name,"OPENING",null,"JPY",10000,true,clock.millis()))
            val predicted=FinancialBillEntity(book,"bill","plan",1,bank,ruleId,"2026-11-27",10000,0,10000,"[]",null,null,null,null,null)
            db.financialDao().insertBills(listOf(predicted))
            val stale=repo.previewFinancialBillConfirmation(book,"bill",KnownBillInput("2026-11-27",10000,10000,0,bank))
            repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(book,ManualFinancialPlanInput(card,"其他已知",FinanceMode.SINGLE,listOf(KnownBillInput("2026-12-27",1000)))))
            assertFalse(repo.commitFinancialBillConfirmation(stale))
            assertFalse(db.financialDao().bill(book,"bill")!!.confirmed)
            val confirmation=repo.previewFinancialBillConfirmation(book,"bill",KnownBillInput("2026-11-28",10020,10000,20,bank))
            assertEquals(20L,confirmation.totalDifferenceMinor);assertEquals(true,confirmation.dateChanged)
            assertTrue(repo.commitFinancialBillConfirmation(confirmation))
            assertFalse(repo.commitFinancialBillConfirmation(confirmation))
            val actual=db.financialDao().bill(book,"bill")!!
            assertEquals(10000L,actual.predictedTotalMinor);assertEquals("2026-11-27",actual.predictedDate)
            assertEquals(10020L,actual.totalMinor);assertEquals("2026-11-28",actual.date)
            val diff=repo.validateFinancialRule(book,"bill")
            assertEquals(0L,diff.principalDifferenceMinor);assertEquals(20L,diff.feeDifferenceMinor)
            assertEquals("DIFFERENT",db.financialDao().graph(book).first()!!.ruleValidationStatus(ruleId))
            assertTrue(repo.commitFinancialBillConfirmation(repo.previewFinancialBillConfirmation(book,"bill",KnownBillInput("2026-11-27",10000,10000,0,bank))))
            assertEquals("UNVERIFIED",db.financialDao().graph(book).first()!!.ruleValidationStatus(ruleId))
            repo.validateFinancialRule(book,"bill")
            assertEquals("VERIFIED",db.financialDao().graph(book).first()!!.ruleValidationStatus(ruleId))
            val deadline=ChangeDeadline("bill",FinanceMode.INSTALLMENT,"2026-11-20","15:00","Asia/Tokyo")
            val deadlineRule=repo.commitFinancialRule(repo.previewFinancialRule(book,card,rule(book,card,bank).copy(mode=FinanceMode.INSTALLMENT,deadlines=listOf(deadline))))!!
            assertEquals(listOf(deadline),repo.json.decodeFromString<FinancialRuleVersion>(db.financialDao().rule(book,deadlineRule)!!.json).deadlines)
            val other=repo.createBook("其他",Currency.JPY)
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                repo.previewManualFinancialPlan(other,ManualFinancialPlanInput(card,"跨账本",FinanceMode.SINGLE,listOf(KnownBillInput("2026-12-27",1000))))
            } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                repo.previewFinancialRule(book,card,rule(book,card,repo.saveAccount(other,"别本银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")))
            } }
            assertTrue(repo.snapshot(repo.dao.book(book)!!).first().events.isEmpty())
        }finally{db.close()}
    }

    @Test fun versionElevenUpgradePreservesLedgerAndAddsEmptyFinancialStorage()=runBlocking {
        val name="upgrade-finance-verification.db"
        context.deleteDatabase(name)
        val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v11-schema.json").bufferedReader().readText()).getJSONObject("database")
        val old=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null)
        try {
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val e=entities.getJSONObject(i)
                old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))
                val indices=e.optJSONArray("indices")
                for(j in 0 until (indices?.length()?:0))old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))
            }
            val setup=schema.getJSONArray("setupQueries")
            for(i in 0 until setup.length())old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO books (id,name,currency,startDate,theme,language,initialized,budgetMinor) VALUES ('b','生活','JPY','2026-10-01','journal','ja',1,15000)")
            old.execSQL("INSERT INTO accounts VALUES ('b','a','现金','CASH','JPY',10000,'2026-10-01',1)")
            old.execSQL("INSERT INTO categories VALUES ('b','c','餐饮','EXPENSE',NULL,0,1)")
            old.execSQL("INSERT INTO events (bookId,id,kind,accountId,categoryId,amountMinor,currency,baseMinor,date,merchant,note,historicalIncluded,revision) VALUES ('b','e','EXPENSE','a','c',500,'JPY',500,'2026-10-13','店','',0,1)")
            old.execSQL("INSERT INTO postings VALUES ('b','p','e','a',-500,'JPY','2026-10-13',1)")
            old.execSQL("DROP TABLE IF EXISTS financial_drafts");old.version=11
        }finally{old.close()}
        val db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(MIGRATION_11_12,MIGRATION_12_13,MIGRATION_13_14,MIGRATION_14_15,MIGRATION_15_16,MIGRATION_16_17,MIGRATION_17_18,MIGRATION_18_19,MIGRATION_19_20,MIGRATION_20_21,MIGRATION_21_22,MIGRATION_22_23).build()
        try {
            val repo=LedgerRepository(db,clock)
            val snapshot=repo.snapshot(repo.dao.book("b")!!).first()
            assertEquals(9500L,snapshot.balance(snapshot.accounts.single()))
            assertEquals("e",snapshot.events.single().id)
            val finance=db.financialDao().graph("b").first()!!
            assertTrue(finance.rules.isEmpty()&&finance.plans.isEmpty()&&finance.bills.isEmpty())
        }finally{db.close();context.deleteDatabase(name)}
    }
}
