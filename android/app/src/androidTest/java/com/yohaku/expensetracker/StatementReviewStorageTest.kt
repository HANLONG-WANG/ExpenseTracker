package com.yohaku.expensetracker

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatementReviewStorageTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var book:String
    private lateinit var account:String
    private lateinit var category:String
    @Before fun open()=runBlocking {
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build();repo=LedgerRepository(db)
        book=repo.createBook("照合",Currency.JPY)
        account=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
        category=repo.saveCategory(book,"生活",EntryKind.EXPENSE,null)
    }
    @After fun close(){db.close()}
    private fun draft(id:String?=null,amount:String="1000")=EntryDraft(id=id,accountId=account,categoryId=category,amount=amount,date="2026-10-14")
    private fun file(amount:String)=StatementFile("yohaku.statement",1,repo.id(),book,account,"JPY","2026-10-01","2026-10-31","posted_date",true,"銀行",emptyList(),listOf(StatementRow(repo.id(),"2026-10-14","out",FileMoney("JPY",amount),"原文")))
    private suspend fun save(file:StatementFile,overlap:Boolean=false)=repo.saveStatement(book,repo.previewStatement(book,FileProtocols.json.encodeToString(file)),overlap)
    private suspend fun state()=repo.snapshot(repo.dao.book(book)!!).first()
    @Test fun manualAcceptanceRetainsDifferenceReservesPostingAndRechecksChangedFacts()=runBlocking {
        val event=repo.saveEntry(book,draft())
        val source=file("950");save(source)
        val posting=state().reconciliationPostings().single()
        val review=repo.previewStatementDifference(book,source.file_id,source.rows.single().row_id,posting.postingId,"已检查日期与金额")
        repo.acceptStatementDifference(review)
        fun result(s:BookSnapshot,batches:List<StatementBatchEntity>,decisions:List<StatementDecisionEntity>)=s.reviewStatements(batches,decisions).first()
        var current=result(state(),db.fileWorkflowDao().statements(book).first(),db.fileWorkflowDao().decisions(book).first())
        assertTrue(current.result.exact.isEmpty());assertTrue(current.decisions.single().active)
        assertTrue(current.result.ledgerOnly.isEmpty())
        assertEquals(1,state().events.size);assertEquals(9000L,state().balance(state().accounts.single()))
        val overlapping=file("1000");save(overlapping,true)
        val projections=state().reviewStatements(db.fileWorkflowDao().statements(book).first(),db.fileWorkflowDao().decisions(book).first())
        assertTrue(projections.last().result.exact.isEmpty())
        assertEquals(listOf(overlapping.rows.single().row_id),projections.last().result.statementOnly)
        repo.saveEntry(book,draft(event,"1100"))
        current=result(state(),db.fileWorkflowDao().statements(book).first(),db.fileWorkflowDao().decisions(book).first())
        assertFalse(current.decisions.single().active)
        assertTrue(current.result.exact.isEmpty())
        repo.clearStatementDecision(book,source.file_id,source.rows.single().row_id)
        assertTrue(db.fileWorkflowDao().decisions(book).first().isEmpty())
        assertEquals(1,state().events.size)
    }
    @Test fun exactReservationsAndBookAccountDirectionAndPreviewRaceAreEnforced()=runBlocking {
        val event=repo.saveEntry(book,draft())
        val exact=file("1000");save(exact)
        val other=file("950");save(other,true)
        val posting=state().reconciliationPostings().single()
        try{repo.previewStatementDifference(book,other.file_id,other.rows.single().row_id,posting.postingId,"");fail("occupied exact reused")}catch(_:IllegalArgumentException){}
        db.fileWorkflowDao().deleteStatement(book,exact.file_id)
        val review=repo.previewStatementDifference(book,other.file_id,other.rows.single().row_id,posting.postingId,"")
        repo.saveEntry(book,draft(event,"1100"))
        try{repo.acceptStatementDifference(review);fail("stale review saved")}catch(_:IllegalArgumentException){}
        val second=repo.saveAccount(book,"別口座",AccountType.BANK,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
        repo.saveEntry(book,draft().copy(accountId=second))
        val foreign=state().reconciliationPostings().first{it.accountId==second}
        try{repo.previewStatementDifference(book,other.file_id,other.rows.single().row_id,foreign.postingId,"");fail("cross account")}catch(_:IllegalArgumentException){}
        assertTrue(db.fileWorkflowDao().decisions(book).first().isEmpty())
    }
    @Test fun version24MigrationRetainsSourceAndManualReviewPersistsWithoutLedgerMutation()=runBlocking {
        val name="review-upgrade-${repo.id()}.db"
        var disk=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build()
        try {
            var r=LedgerRepository(disk)
            val b=r.createBook("旧文件资料",Currency.JPY)
            val a=r.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val c=r.saveCategory(b,"生活",EntryKind.EXPENSE,null)
            r.saveEntry(b,EntryDraft(accountId=a,categoryId=c,amount="1000",date="2026-10-14"))
            val source=file("950").copy(book_id=b,account_id=a)
            val original=FileProtocols.json.encodeToString(source)
            r.saveStatement(b,r.previewStatement(b,original));disk.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use{old->
                old.execSQL("DROP TABLE statement_decisions")
                val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v24-schema.json").bufferedReader().readText()).getJSONObject("database")
                old.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42",arrayOf(schema.getString("identityHash")));restoreBookLifecycleFixture(old);old.version=24
            }
            disk=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(MIGRATION_24_25,com.yohaku.expensetracker.data.db.MIGRATION_25_26,com.yohaku.expensetracker.data.db.MIGRATION_26_27,com.yohaku.expensetracker.data.db.MIGRATION_27_28).build();r=LedgerRepository(disk)
            assertEquals(original,disk.fileWorkflowDao().statement(b,source.file_id)!!.originalJson)
            val posting=r.snapshot(r.dao.book(b)!!).first().reconciliationPostings().single()
            r.acceptStatementDifference(r.previewStatementDifference(b,source.file_id,source.rows.single().row_id,posting.postingId,"原文保留"))
            disk.close();disk=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build();r=LedgerRepository(disk)
            val s=r.snapshot(r.dao.book(b)!!).first()
            val projection=s.reviewStatements(disk.fileWorkflowDao().statements(b).first(),disk.fileWorkflowDao().decisions(b).first()).single()
            assertTrue(projection.decisions.single().active);assertTrue(projection.result.exact.isEmpty())
            assertEquals("原文保留",projection.decisions.single().saved.note)
            disk.fileWorkflowDao().deleteStatement(b,source.file_id)
            assertTrue(disk.fileWorkflowDao().decisions(b).first().isEmpty());assertEquals(1,s.events.size);assertEquals(9000L,s.balance(s.accounts.single()))
        }finally{disk.close();context.deleteDatabase(name)}
    }
}
