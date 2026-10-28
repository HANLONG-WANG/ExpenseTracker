package com.yohaku.expensetracker

import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.currency.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.ledger.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class ReferenceRateStorageTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    @Test fun referenceCacheAndAutomaticSettingSurviveDataStoreReopeningOffline()=runBlocking {
        val file=java.io.File(context.cacheDir,"rates-test.preferences_pb");file.delete()
        var scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            var prefs=AppPreferences(context,PreferenceDataStoreFactory.create(scope=scope){file})
            val first=ReferenceRates(prefs,ReferenceRateTransport{_,_,_->"""{"base":"JPY","quote":"CNY","date":"2026-10-14","rate":0.04}"""},clock).lookup(Currency.JPY,Currency.CNY)
            prefs.setAutomaticReferenceRates(false)
            scope.coroutineContext[Job]!!.cancelAndJoin();scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            prefs=AppPreferences(context,PreferenceDataStoreFactory.create(scope=scope){file})
            var calls=0
            val offline=ReferenceRates(prefs,ReferenceRateTransport{_,_,_->calls++;throw IOException("offline")},clock)
            val cached=offline.lookup(Currency.JPY,Currency.CNY)
            assertEquals(first.rate,cached.rate);assertFalse(cached.automatic);assertEquals(0,calls)
            val refreshed=offline.lookup(Currency.JPY,Currency.CNY,refresh=true)
            assertEquals(first.rate,refreshed.rate);assertEquals("NETWORK",refreshed.failure);assertEquals(1,calls)
        }finally{scope.coroutineContext[Job]!!.cancelAndJoin();file.delete()}
    }
    @Test fun versionSeventeenUpgradeAndReferenceEvidencePreserveActualAmountsAndSeparateCashEstimates()=runBlocking {
        val name="reference-upgrade.db";context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build()
        try {
            var repo=LedgerRepository(db,clock);val b=repo.createBook("換算",Currency.JPY)
            val bank=repo.saveAccount(b,"JPY銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val cny=repo.saveAccount(b,"CNY銀行",AccountType.BANK,Currency.CNY,Money(Currency.CNY,0),"2026-10-01")
            val draft=EntryDraft(kind=EntryKind.TRANSFER,accountId=bank,toAccountId=cny,amount="9000",toAmount="360",date="2026-10-15",fromConfirmed=true,toConfirmed=true)
            val event=repo.commitEntryReview(b,repo.previewEntry(b,draft))!!
            val income=repo.commitExpectedIncome(repo.previewExpectedIncome(b,ExpectedIncomeInput("見込み",Money(Currency.CNY,10000),"2026-10-20",cny)))!!
            db.close();val old=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE)
            try {
                
                val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v17-schema.json").bufferedReader().readText()).getJSONObject("database")
                old.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42",arrayOf(schema.getString("identityHash")));old.execSQL("DROP TABLE IF EXISTS financial_drafts");restoreFixtureDatabase(old,17)
            }finally{old.close()}
            db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(*ALL_MIGRATIONS).build();repo=LedgerRepository(db,clock)
            assertNull(repo.dao.settlement(b,event)!!.referenceRatesJson);assertNull(repo.dao.expectedIncome(b,income)!!.accountEstimateJson)
            val rate=ReferenceRateSnapshot(Currency.JPY,Currency.CNY,"0.04","2026-10-14",clock.instant().toString())
            val estimated=applyReferenceTransfer(draft.copy(id=event,toConfirmed=false),Currency.JPY,Currency.CNY,rate,"TO")
            repo.commitEntryReview(b,repo.previewEntry(b,estimated));repo.commitEntryReview(b,repo.previewEntry(b,estimated.copy(toAmount="355",toConfirmed=true)))
            val planned=repo.previewExpectedIncome(b,ExpectedIncomeInput("換算見込み",Money(Currency.JPY,1000),"2026-10-20",cny,Money(Currency.CNY,4000),income,rate))
            repo.commitExpectedIncome(planned)
            db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build();repo=LedgerRepository(db,clock)
            val s=repo.snapshot(repo.dao.book(b)!!).first()
            assertEquals(1000L,s.balance(s.accounts.first{it.id==bank}));assertEquals(35500L,s.balance(s.accounts.first{it.id==cny}));assertEquals(1,s.events.size)
            assertEquals(36000L,s.transfers.single().expectedToMinor)
            assertEquals(listOf(rate),repo.json.decodeFromString<List<ReferenceRateSnapshot>>(s.settlements.single().referenceRatesJson!!))
            assertEquals(listOf(rate),repo.json.decodeFromString<List<ReferenceRateSnapshot>>(s.expectedIncomes.single().accountEstimateJson!!))
            val debt=repo.saveAccount(b,"CNYカード",AccountType.CREDIT,Currency.CNY,Money(Currency.CNY,10000),"2026-10-01")
            repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(debt,"外貨請求",FinanceMode.SINGLE,listOf(KnownBillInput("2026-10-21",10000,10000,0,bank)),principalMinor=10000,opening=true)))
            val fresh=repo.snapshot(repo.dao.book(b)!!).first();val f=db.financialDao().graph(b).first()!!
            val reverse=ReferenceRateSnapshot(Currency.CNY,Currency.JPY,"25","2026-10-14",clock.instant().toString())
            val timeline=fresh.cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-10-31"),referenceRates=listOf(reverse))
            val bill=timeline.steps.single{it.item.kind=="BILL"}
            assertEquals(-1500L,bill.baselineAfterMinor);assertTrue(bill.baselineEstimated);assertEquals(reverse,bill.item.referenceRate)
            assertEquals(1000L,fresh.balance(fresh.accounts.first{it.id==bank}));assertEquals("0.04",repo.json.decodeFromString<List<ReferenceRateSnapshot>>(repo.dao.settlement(b,event)!!.referenceRatesJson!!).single().rate)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun liveFixedProviderClientReturnsTheObservedPreviousBusinessDate()=runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("networkSmoke")=="true")
        val raw=FrankfurterTransport().fetch(Currency.JPY,Currency.CNY,"2026-10-11")
        val json=JSONObject(raw)
        assertEquals("JPY",json.getString("base"));assertEquals("CNY",json.getString("quote"));assertEquals("2026-10-09",json.getString("date"));assertTrue(json.getDouble("rate")>0)
    }
}
