package com.yohaku.expensetracker

import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.FinanceMode
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookLifecycleStorageTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private class MutableClock:Clock() {
        var now=Instant.parse("2026-10-16T03:00:00Z")
        override fun getZone():ZoneId=ZoneOffset.UTC
        override fun withZone(zone:ZoneId):Clock=Clock.fixed(now,zone)
        override fun instant():Instant=now
    }
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var root:File
    private lateinit var clock:MutableClock
    @Before fun open() {
        root=File(context.cacheDir,"book-files-${UUID.randomUUID()}").apply{mkdirs()}
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        clock=MutableClock();repo=LedgerRepository(db,clock,root)
    }
    @After fun close(){db.close();root.deleteRecursively()}
    private data class Seed(val book:String,val bank:String,val event:String,val files:List<FileBlobEntity>,val plan:String)
    private suspend fun seed(name:String):Seed {
        val b=repo.createBook(name,Currency.JPY,startDate="2026-10-01")
        repo.dao.putBook(repo.dao.book(b)!!.copy(initialized=true))
        val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
        val c=repo.saveCategory(b,"食事",EntryKind.EXPENSE,null)
        val draft=EntryDraft(accountId=bank,categoryId=c,amount="500",date="2026-10-16",merchant="喫茶 原名",mediaInitialized=true)
        repo.saveDraft(b,draft)
        val files=(1..2).map{n->repo.importMedia(b,draft.mediaSession,"$n.bin","application/octet-stream"){byteArrayOf(n.toByte(),2,3).inputStream()}}
        val event=repo.saveEntry(b,draft.copy(attachmentIds=files.map{it.id}))
        val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
        val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"今期",FinanceMode.SINGLE,listOf(KnownBillInput("2026-10-17",1000,1000,0,bank)),1000,complete=true,opening=true)))!!
        val statement=StatementFile("yohaku.statement",1,repo.id(),b,bank,"JPY","2026-10-01","2026-10-31","posted_date",true,"銀行",emptyList(),listOf(StatementRow(repo.id(),"2026-10-16","out",FileMoney("JPY","500"),"原文")))
        repo.saveStatement(b,repo.previewStatement(b,FileProtocols.json.encodeToString(statement)))
        return Seed(b,bank,event,files,plan)
    }
    private suspend fun snapshot(book:String)=repo.snapshot(repo.dao.storedBook(book)!!).first()
    private suspend fun apply(book:String,operation:BookOperation){assertTrue(repo.commitBookOperation(repo.previewBookOperation(book,operation)))}
    @Test fun archiveTrashRestorePreserveFactsFilesAndArchivedDebtReminders()=runBlocking {
        val a=seed("生活");val b=seed("独立")
        val original=snapshot(a.book);val other=snapshot(b.book)
        val bills=db.financialDao().bills(a.book,a.plan)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val path=File(context.cacheDir,"book-pref-${UUID.randomUUID()}.preferences_pb")
        val prefs=AppPreferences(context,PreferenceDataStoreFactory.create(scope=scope,produceFile={path}))
        try {
            val before=repo.reminderItems(prefs).filter{it.bookId==a.book}
            assertTrue(before.any{it.kind=="REPAYMENT"})
            apply(a.book,BookOperation.ARCHIVE)
            assertTrue(repo.dao.book(a.book)!!.archived)
            assertEquals(before,repo.reminderItems(prefs).filter{it.bookId==a.book})
            apply(a.book,BookOperation.TRASH)
            assertNull(repo.dao.book(a.book));assertNotNull(repo.dao.storedBook(a.book))
            assertTrue(repo.reminderItems(prefs).none{it.bookId==a.book})
            assertTrue(a.files.all{repo.mediaFile(it).readBytes().size==3})
            assertEquals(original.events,snapshot(a.book).events)
            apply(a.book,BookOperation.RESTORE)
            assertTrue(repo.dao.book(a.book)!!.archived)
            val restored=snapshot(a.book)
            assertEquals(original.events,restored.events);assertEquals(original.postings,restored.postings)
            assertEquals(original.files,restored.files);assertEquals(original.attachments,restored.attachments)
            assertEquals(bills,db.financialDao().bills(a.book,a.plan))
            assertEquals(before,repo.reminderItems(prefs).filter{it.bookId==a.book})
            assertEquals(other,snapshot(b.book))
        }finally{scope.cancel();path.delete()}
    }
    @Test fun permanentPurgeIsScopedTransactionalAndKeepsTheLastOtherBookFileReference()=runBlocking {
        val a=seed("削除対象");val b=seed("保持")
        val shared=a.files.first().copy(bookId=b.book,id=repo.id())
        repo.dao.putFile(shared);repo.dao.putAttachments(listOf(AttachmentLinkEntity(b.book,repo.id(),b.event,shared.id)))
        val other=snapshot(b.book)
        apply(a.book,BookOperation.TRASH);apply(a.book,BookOperation.PURGE)
        repo.collectUnusedMedia()
        assertNull(repo.dao.storedBook(a.book))
        assertTrue(repo.mediaFile(shared).exists())
        assertFalse(repo.mediaFile(a.files.last()).exists())
        assertEquals(other,snapshot(b.book))
        assertTrue(db.financialDao().bills(a.book,a.plan).isEmpty())
        assertTrue(db.fileWorkflowDao().statements(a.book).first().isEmpty())
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
    }
    @Test fun thirtyDayDeadlineUsesClockAndExpiredBooksAreActuallyCleaned()=runBlocking {
        val a=seed("期限");val b=seed("保持")
        val other=snapshot(b.book)
        apply(a.book,BookOperation.TRASH)
        clock.now=clock.now.plus(Duration.ofDays(29))
        repo.previewBookOperation(a.book,BookOperation.RESTORE)
        clock.now=clock.now.plus(Duration.ofDays(1))
        try{repo.previewBookOperation(a.book,BookOperation.RESTORE);fail("Expired restore must fail")}catch(expected:IllegalArgumentException){}
        repo.purgeExpired();repo.collectUnusedMedia()
        assertNull(repo.dao.storedBook(a.book));assertTrue(a.files.none{repo.mediaFile(it).exists()})
        assertEquals(other,snapshot(b.book))
    }
    @Test fun changedPreviewCannotDeleteNewScopeAndMetadataEditCannotUndoArchive()=runBlocking {
        val a=seed("範囲")
        val review=repo.previewBookOperation(a.book,BookOperation.TRASH)
        repo.saveAccount(a.book,"追加",AccountType.CASH,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
        assertFalse(repo.commitBookOperation(review));assertNull(repo.dao.book(a.book)!!.deletedAt)
        val before=repo.dao.book(a.book)!!
        apply(a.book,BookOperation.ARCHIVE)
        repo.updateBook(before.copy(name="新名称"))
        assertTrue(repo.dao.book(a.book)!!.archived)
    }
    @Test fun emptyTrashRequiresTheCurrentWholeSetAndKeepsOtherBooksAndSharedFiles()=runBlocking {
        val a=seed("回収A");val b=seed("回収B");val keep=seed("保持")
        val shared=a.files.first().copy(bookId=keep.book,id=repo.id())
        repo.dao.putFile(shared);repo.dao.putAttachments(listOf(AttachmentLinkEntity(keep.book,repo.id(),keep.event,shared.id)))
        val original=snapshot(keep.book)
        apply(a.book,BookOperation.TRASH)
        val stale=repo.previewEmptyBookTrash()
        apply(b.book,BookOperation.TRASH)
        assertFalse(repo.commitEmptyBookTrash(stale))
        assertNotNull(repo.dao.storedBook(a.book));assertNotNull(repo.dao.storedBook(b.book))
        val current=repo.previewEmptyBookTrash()
        assertEquals(setOf(a.book,b.book),current.map{it.scope.book.id}.toSet())
        assertTrue(repo.commitEmptyBookTrash(current));repo.collectUnusedMedia()
        assertNull(repo.dao.storedBook(a.book));assertNull(repo.dao.storedBook(b.book))
        assertTrue(repo.previewEmptyBookTrash().isEmpty());assertEquals(original,snapshot(keep.book))
        assertTrue(repo.mediaFile(shared).exists());assertFalse(repo.mediaFile(a.files.last()).exists())
        assertTrue(b.files.none{repo.mediaFile(it).exists()})
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
    }
    @Test fun version25MigratesOriginalIdsRelationsAndFilePathsTo26()=runBlocking {
        val name="book-migration-${UUID.randomUUID()}.db"
        db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build();repo=LedgerRepository(db,clock,root)
        try {
            val a=seed("移行前");val original=snapshot(a.book)
            db.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use{old->
                val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v25-schema.json").bufferedReader().readText()).getJSONObject("database")
                old.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42",arrayOf(schema.getString("identityHash")));restoreFixtureDatabase(old,25)
            }
            db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(*ALL_MIGRATIONS).build();repo=LedgerRepository(db,clock,root)
            assertEquals(original,snapshot(a.book))
            assertFalse(repo.dao.book(a.book)!!.archived);assertNull(repo.dao.book(a.book)!!.deletedAt)
            assertTrue(a.files.all{repo.mediaFile(it).readBytes().size==3})
        }finally{db.close();context.deleteDatabase(name)}
    }
}
