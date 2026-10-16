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

@RunWith(AndroidJUnit4::class)
class FinancialDraftTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun versionTwentyUpgradeAndReopeningKeepIndependentUnfinishedFieldsWithoutCreatingMoney()=runBlocking {
        val name="financial-draft-upgrade.db";context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build()
        try {
            var r=LedgerRepository(db);val b=r.createBook("草稿",Currency.JPY)
            val a=r.saveAccount(b,"贷款",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val other=r.createBook("別帳簿",Currency.JPY)
            val otherAccount=r.saveAccount(other,"别贷款",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
            db.close();val old=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE)
            try {
                old.execSQL("DROP TABLE financial_drafts")
                val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v20-schema.json").bufferedReader().readText()).getJSONObject("database")
                old.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42",arrayOf(schema.getString("identityHash")));restoreFixtureTable(old,"room-v20-schema.json","financial_plans");restoreBookLifecycleFixture(old);old.version=20
            }finally{old.close()}
            db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(MIGRATION_20_21,MIGRATION_21_22,MIGRATION_22_23,com.yohaku.expensetracker.data.db.MIGRATION_23_24,com.yohaku.expensetracker.data.db.MIGRATION_24_25,com.yohaku.expensetracker.data.db.MIGRATION_25_26,com.yohaku.expensetracker.data.db.MIGRATION_26_27,com.yohaku.expensetracker.data.db.MIGRATION_27_28).build();r=LedgerRepository(db)
            val repay=FinancialDraftContext("REPAY",a);val borrow=FinancialDraftContext("BORROW",a)
            r.storeFinancialDraft(repay,b,"{\"paid\":\"12.\",\"principal\":\"\",\"confirmed\":false}","basis1")
            r.storeFinancialDraft(borrow,b,"{\"name\":\"未完了\"}","basis1")
            r.storeFinancialDraft(FinancialDraftContext("EXPECTED",null),b,"{\"amount\":\"100.\"}","book-basis")
            r.storeFinancialDraft(FinancialDraftContext("REPAY",otherAccount),other,"{\"paid\":\"9\"}","basis2")
            try{r.storeFinancialDraft(repay,other,"{}","invalid");fail("cross book account")}catch(_:IllegalArgumentException){}
            try{r.saveAccount(b,"贷款",AccountType.LOAN,Currency.CNY,Money(Currency.CNY,10000),"2026-10-01",a);fail("draft currency cannot change")}catch(_:IllegalArgumentException){}
            db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build();r=LedgerRepository(db)
            assertEquals(2,db.financialDao().drafts(b,a).first().size)
            assertEquals(1,db.financialDao().drafts(b,null).first().size)
            assertTrue(db.financialDao().draft(b,repay.slot())!!.valuesJson.contains("12."))
            assertEquals("basis1",db.financialDao().draft(b,repay.slot())!!.basis)
            assertNull(db.financialDao().draft(other,repay.slot()))
            val s=r.snapshot(r.dao.book(b)!!).first();assertTrue(s.events.isEmpty());assertEquals(10000L,s.balance(s.accounts.first{it.id==a}))
            db.financialDao().clearDraft(b,repay.slot());assertEquals(1,db.financialDao().drafts(b,a).first().size)
            assertEquals(1,db.financialDao().drafts(other,otherAccount).first().size)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun rejectedSaveKeepsDraftAndDraftRemovalFailureRollsBackTheAcceptedBorrowing()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val r=LedgerRepository(db);val b=r.createBook("下書き",Currency.JPY)
            val a=r.saveAccount(b,"贷款",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
            val bank=r.saveAccount(b,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val ctx=FinancialDraftContext("BORROW",a);r.storeFinancialDraft(ctx,b,"{\"principal\":\"3000\"}","basis")
            val input=FinancialBorrowingInput(a,"循环",FinanceMode.CREDIT_LINE,Money(Currency.JPY,3000),"2026-10-15",bank,Money(Currency.JPY,3000))
            val stale=r.previewBorrowing(b,input)
            r.saveAccount(b,"贷款更新",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,0),"2026-10-01",a)
            assertNull(r.commitFinancialDraft(b,ctx.slot()){r.commitFinancialLedger(stale)})
            assertNotNull(db.financialDao().draft(b,ctx.slot()))
            val fresh=r.previewBorrowing(b,input)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_draft_removal BEFORE DELETE ON financial_drafts BEGIN SELECT RAISE(ABORT,'draft transaction'); END")
            try{r.commitFinancialDraft(b,ctx.slot()){r.commitFinancialLedger(fresh)};fail("atomic removal")}catch(_:android.database.sqlite.SQLiteException){}
            assertTrue(r.snapshot(r.dao.book(b)!!).first().events.isEmpty());assertNotNull(db.financialDao().draft(b,ctx.slot()))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_draft_removal")
            assertNotNull(r.commitFinancialDraft(b,ctx.slot()){r.commitFinancialLedger(fresh)})
            assertNull(db.financialDao().draft(b,ctx.slot()))
            val s=r.snapshot(r.dao.book(b)!!).first();assertEquals(1,s.events.size);assertEquals(3000L,s.balance(s.accounts.first{it.id==a}));assertEquals(13000L,s.balance(s.accounts.first{it.id==bank}))
        }finally{db.close()}
    }
}
