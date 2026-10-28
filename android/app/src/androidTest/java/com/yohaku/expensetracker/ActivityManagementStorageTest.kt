package com.yohaku.expensetracker

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ActivityManagementStorageTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db:YohakuDatabase
    private lateinit var r:LedgerRepository
    private lateinit var root:File
    private val clock=Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"),ZoneOffset.UTC)
    @Before fun open(){db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build();root=File(context.cacheDir,"activity-${UUID.randomUUID()}").apply{mkdirs()};r=LedgerRepository(db,clock,root)}
    @After fun close(){db.close();root.deleteRecursively()}
    private data class Seed(val b:String,val a:String,val c:String,val activity:String,val event:String,val friend:String)
    private suspend fun seed():Seed {
        val b=r.createBook("旅行",Currency.JPY);r.setActivityMode(b,true)
        val a=r.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,20000),"2026-10-01")
        val c=r.saveCategory(b,"食事",EntryKind.EXPENSE,null)
        r.dao.putBudget(BudgetEntity(b,"2026-10",c,c,10000))
        val activity=r.saveActivity(b,"秋の旅","2026-10-01","2026-11-30",Money(Currency.JPY,20000),true)
        r.setActivityMode(b,true,activity)
        val friend=r.saveFriend(b,"友人");r.addParticipant(b,activity,friend)
        val event=r.saveEntry(b,EntryDraft(accountId=a,categoryId=c,amount="6000",date="2026-10-14",activityId=activity,treatEnabled=true,treatFriends=listOf(friend)))
        r.saveEntry(b,EntryDraft(accountId=a,categoryId=c,amount="3000",date="2026-11-01",activityId=activity))
        assertTrue(r.commitOtherTreat(r.previewOtherTreat(b,OtherTreatInput(activity,friend,Money(Currency.JPY,3000),Money(Currency.JPY,3000),"2026-10-15","夕食",listOf(friend)))))
        r.saveRecurrence(b,RecurrenceInput("入場料",a,c,Money(Currency.JPY,1000),null,false,RecurrenceFrequency.MONTHLY,"2026-10-17",activityId=activity))
        assertTrue(r.commitReserve(r.previewReserve(b,ReserveInput(activity,"交通","2026-10-20",Money(Currency.JPY,2000),a,Money(Currency.JPY,2000)))))
        return Seed(b,a,c,activity,event,friend)
    }
    private suspend fun snapshot(b:String)=r.snapshot(r.dao.book(b)!!).first()
    @Test fun deletionReassignsEveryHistoricalMonthButKeepsMoneyFilesTreatsAndOtherBooks()=runBlocking {
        val x=seed();val other=r.createBook("独立",Currency.JPY);val independent=snapshot(other)
        val d=EntryDraft(accountId=x.a,categoryId=x.c,activityId=x.activity,amount="12.",treatEnabled=true,treatFriends=listOf(x.friend))
        r.saveDraft(x.b,d)
        val file=r.importMedia(x.b,d.mediaSession,"original.bin","application/octet-stream"){byteArrayOf(1,2,3).inputStream()}
        r.dao.putAttachments(listOf(AttachmentLinkEntity(x.b,r.id(),x.event,file.id)))
        val before=snapshot(x.b);val review=r.previewActivityDeletion(x.b,x.activity)
        assertEquals(6000L,review.categoryBudgets.first{it.month=="2026-10"}.after.spentMinor);assertEquals(10000L,review.categoryBudgets.first{it.month=="2026-10"}.after.limitMinor)
        assertEquals(2000L,review.releasedMinor);assertEquals(1,review.cancelledCount);assertTrue(review.draftConverted)
        assertEquals(0L,review.months.first{it.first.month=="2026-10"}.first.spentMinor)
        assertEquals(6000L,review.months.first{it.first.month=="2026-10"}.second.spentMinor)
        assertEquals(3000L,review.months.first{it.first.month=="2026-11"}.second.spentMinor)
        assertNull(r.dao.activity(x.b,x.activity)!!.deletedAt)
        assertTrue(r.commitActivityDeletion(review));val after=snapshot(x.b)
        assertTrue(after.availableActivities.isEmpty());assertEquals(before.events,after.events);assertEquals(before.postings,after.postings);assertEquals(before.attachments,after.attachments)
        assertEquals(before.friendBalances(x.activity),after.friendBalances(x.activity));assertEquals(before.treats,after.treats);assertEquals(before.allocations,after.allocations)
        assertEquals(11000L,after.balance(after.accounts.single()));assertEquals(0L,after.activityHeld(x.activity));assertTrue(after.pendingOccurrences().isEmpty());assertTrue(after.recurrences.none{it.active})
        assertTrue(after.cashTimeline(null,LocalDate.parse("2026-10-16"),LocalDate.parse("2027-10-16")).steps.none{it.item.kind in listOf("RECURRENCE","RESERVE")})
        assertNull(after.features!!.activeActivityId);assertNull(after.draft!!.activityId);assertFalse(after.draft!!.treatEnabled);assertEquals("12.",after.draft!!.amount)
        assertEquals(6000L,after.analysis(AnalysisFilter("2026-10-01","2026-10-31",activityId="")).total)
        assertTrue(after.analysis(AnalysisFilter("2026-10-01","2026-10-31",activityId=x.activity)).facts.isEmpty())
        assertTrue(r.mediaFile(file).exists());assertEquals(independent,snapshot(other))
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
    }
    @Test fun originalFactsCanBeCorrectedButDeletedActivityCannotReceiveNewFactsOrBeResurrected()=runBlocking {
        val x=seed();assertTrue(r.commitActivityDeletion(r.previewActivityDeletion(x.b,x.activity)))
        val old=EntryDraft(id=x.event,accountId=x.a,categoryId=x.c,amount="6000",date="2026-10-14",activityId=x.activity,treatEnabled=true,treatFriends=listOf(x.friend))
        assertEquals(x.event,r.saveEntry(x.b,old.copy(amount="6200")))
        assertEquals(6200L,snapshot(x.b).budgetProjection("2026-10").spentMinor)
        try{r.saveEntry(x.b,old.copy(id=null));fail("new expense")}catch(_:IllegalArgumentException){}
        try{r.setActivityMode(x.b,true,x.activity);fail("new mode")}catch(_:IllegalArgumentException){}
        try{r.saveActivity(x.b,"復活","2026-10-01","2026-11-30",null,id=x.activity);fail("metadata resurrection")}catch(_:IllegalArgumentException){}
        try{r.addParticipant(x.b,x.activity,x.friend);fail("new participant")}catch(_:IllegalArgumentException){}
        r.deleteEntry(x.b,x.event);r.restoreEntry(x.b,x.event)
        assertNotNull(r.dao.activity(x.b,x.activity)!!.deletedAt);assertEquals(6200L,snapshot(x.b).budgetProjection("2026-10").spentMinor);assertTrue(snapshot(x.b).pendingOccurrences().isEmpty())
    }
    @Test fun newDraftOrRecordRequiresFreshConfirmation()=runBlocking {
        val x=seed();val review=r.previewActivityDeletion(x.b,x.activity)
        r.saveDraft(x.b,EntryDraft(accountId=x.a,categoryId=x.c,activityId=x.activity,amount="9."))
        assertFalse(r.commitActivityDeletion(review));assertNull(r.dao.activity(x.b,x.activity)!!.deletedAt)
        assertTrue(r.commitActivityDeletion(r.previewActivityDeletion(x.b,x.activity)))
    }
    @Test fun version26MigrationPreservesOriginalActivityFactsAndIds()=runBlocking {
        val name="activity-migration-${UUID.randomUUID()}.db";db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build();r=LedgerRepository(db,clock,root)
        try {
            val x=seed();val before=snapshot(x.b);db.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use{old->
                
                val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v26-schema.json").bufferedReader().readText()).getJSONObject("database")
                old.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42",arrayOf(schema.getString("identityHash")));restoreFixtureDatabase(old,26)
            }
            db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(*ALL_MIGRATIONS).build();r=LedgerRepository(db,clock,root)
            assertEquals(before,snapshot(x.b));assertNull(r.dao.activity(x.b,x.activity)!!.deletedAt)
        }finally{db.close();context.deleteDatabase(name)}
    }
}
