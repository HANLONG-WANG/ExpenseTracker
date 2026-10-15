package com.yohaku.expensetracker

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class ActivityTreatTest {
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var book:String
    private lateinit var account:String
    private lateinit var category:String
    private lateinit var activity:String
    private lateinit var a:String
    private lateinit var b:String
    @Before fun open()=runBlocking {
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC))
        book=repo.createBook("活动",Currency.JPY,startDate="2026-10-01",budgetMinor=100000)
        account=repo.saveAccount(book,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-10-01")
        category=repo.saveCategory(book,"餐食",EntryKind.EXPENSE,null)
        repo.setActivityMode(book,true)
        activity=repo.saveActivity(book,"旅行","2026-10-01","2026-11-30",Money(Currency.JPY,50000))
        a=repo.saveFriend(book,"A");b=repo.saveFriend(book,"B")
        repo.addParticipant(book,activity,a);repo.addParticipant(book,activity,b)
    }
    @After fun close(){db.close()}
    private suspend fun snapshot()=repo.snapshot(repo.dao.book(book)!!).first()
    private fun draft(amount:String,date:String="2026-10-14")=EntryDraft(accountId=account,categoryId=category,amount=amount,date=date,activityId=activity)
    @Test fun ownWholeExpenseOnceOtherPayerOnlySharesAndEditsDeleteRestoreStayTogether()=runBlocking {
        val id=repo.saveEntry(book,draft("6000").copy(treatEnabled=true,treatFriends=listOf(a,b)))
        assertTrue(repo.commitOtherTreat(repo.previewOtherTreat(book,OtherTreatInput(activity,a,Money(Currency.JPY,3000),Money(Currency.JPY,3000),"2026-10-15","午饭",listOf(a)))))
        var s=snapshot()
        assertEquals(1,s.events.size);assertEquals(1,s.postings.size);assertEquals(194000L,s.balance(s.accounts.single()))
        assertEquals(6000L,s.activitySpent(activity));assertEquals(500L,s.friendBalances(activity).first{it.friend.id==a}.difference);assertEquals(2000L,s.friendBalances(activity).first{it.friend.id==b}.difference)
        val review=repo.previewEntry(book,draft("9000").copy(id=id,treatEnabled=true,treatFriends=listOf(a,b)))
        assertNotNull(repo.commitEntryReview(book,review))
        s=snapshot();assertEquals(1,s.events.size);assertEquals(1,s.postings.size);assertEquals(191000L,s.balance(s.accounts.single()));assertEquals(1500L,s.friendBalances(activity).first{it.friend.id==a}.difference)
        repo.deleteEntry(book,id);assertEquals(-1500L,snapshot().friendBalances(activity).first{it.friend.id==a}.difference)
        repo.restoreEntry(book,id);assertEquals(1500L,snapshot().friendBalances(activity).first{it.friend.id==a}.difference)
    }
    @Test fun percentagesUseActualSharesAndRejectActivityOutsideBook()=runBlocking {
        repo.saveEntry(book,draft("10000").copy(treatEnabled=true,treatFriends=listOf(a),treatMode=TreatSplitMode.PERCENT,treatWeights=mapOf("ME" to "70",a to "30")))
        val r=repo.previewOtherTreat(book,OtherTreatInput(activity,a,Money(Currency.JPY,6000),Money(Currency.JPY,6000),"2026-10-15","晚餐",listOf(a),TreatSplitMode.PERCENT,mapOf("ME" to "50",a to "50")))
        assertTrue(repo.commitOtherTreat(r));assertEquals(0L,snapshot().friendBalances(activity).first{it.friend.id==a}.difference)
        try{repo.saveEntry(book,draft("100").copy(activityId=null,treatEnabled=true,treatFriends=listOf(a)));fail("Activity-free treat accepted")}catch(_:IllegalStateException){}
        val second=repo.createBook("別",Currency.JPY);repo.setActivityMode(second,true)
        try{repo.previewOtherTreat(second,OtherTreatInput(activity,a,Money(Currency.JPY,100),Money(Currency.JPY,100),"2026-10-15","",listOf(a)));fail("Cross-book treat accepted")}catch(_:IllegalStateException){}
        assertEquals(1,snapshot().events.size)
    }
    @Test fun reservationOverlapUseHistoryExclusionAndEndNeverChangeBalanceTwice()=runBlocking {
        repo.saveRecurrence(book,RecurrenceInput("旅馆",account,category,Money(Currency.JPY,20000),null,false,RecurrenceFrequency.MONTHLY,"2026-10-20",activityId=activity))
        val o=snapshot().pendingOccurrences().single()
        val review=repo.previewReserve(book,ReserveInput(activity,"住宿","2026-10-20",Money(Currency.JPY,30000),account,Money(Currency.JPY,30000),o.id))
        assertEquals(20000L,review.before.reserveMinor);assertEquals(30000L,review.after.reserveMinor)
        assertTrue(repo.commitReserve(review));assertEquals(200000L,snapshot().balance(snapshot().accounts.single()))
        val event=repo.saveEntry(book,draft("20000").copy(occurrenceId=o.id))
        assertEquals(10000L,snapshot().budgetProjection("2026-10").reserveMinor);assertEquals(70000L,snapshot().budgetProjection("2026-10").availableMinor)
        repo.deleteEntry(book,event);assertEquals(30000L,snapshot().budgetProjection("2026-10").reserveMinor)
        repo.restoreEntry(book,event);assertEquals(10000L,snapshot().budgetProjection("2026-10").reserveMinor)
        repo.saveEntry(book,draft("5000","2026-11-02"))
        val excluded=repo.previewActivity(book,ActivityInput("旅行","2026-10-01","2026-11-30",Money(Currency.JPY,50000),mapOf(category to Money(Currency.JPY,40000)),excludeMonthly=true,id=activity))
        assertEquals(listOf("2026-10","2026-11"),excluded.months.map{it.first.month})
        assertTrue(repo.commitActivity(excluded));assertEquals(0L,snapshot().budgetProjection("2026-10").spentMinor);assertEquals(0L,snapshot().budgetProjection("2026-10").reserveMinor);assertEquals(10000L,snapshot().budgetProjection("2026-10").fundReserveMinor)
        assertEquals(25000L,snapshot().activitySpent(activity))
        assertTrue(repo.commitActivity(repo.previewActivity(book,ActivityInput("旅行","2026-10-01","2026-11-30",null,excludeMonthly=true,ended=true,id=activity))))
        assertNotNull(repo.commitEntryReview(book,repo.previewEntry(book,draft("19000").copy(id=event,occurrenceId=o.id,reserveId=review.reserve.id,reserveAmount="19000"))))
        assertEquals(176000L,snapshot().balance(snapshot().accounts.single()))
        assertEquals(0L,snapshot().activityHeld(activity));assertEquals(176000L,snapshot().balance(snapshot().accounts.single()));assertEquals(2,snapshot().events.size)
        val historicalFriend=repo.saveFriend(book,"遗漏的朋友")
        repo.addParticipant(book,activity,historicalFriend)
        assertTrue(repo.commitOtherTreat(repo.previewOtherTreat(book,OtherTreatInput(activity,historicalFriend,Money(Currency.JPY,1000),Money(Currency.JPY,1000),"2026-10-12","补记期间事实",listOf(historicalFriend)))))
        assertEquals(176000L,snapshot().balance(snapshot().accounts.single()))
        try{repo.previewOtherTreat(book,OtherTreatInput(activity,historicalFriend,Money(Currency.JPY,1000),Money(Currency.JPY,1000),"2026-12-01","活动外新事实",listOf(historicalFriend)));fail("New round outside ended activity accepted")}catch(_:IllegalArgumentException){}
        try{repo.setActivityMode(book,true,activity);fail("Ended activity mode accepted")}catch(_:IllegalArgumentException){}
    }
}
