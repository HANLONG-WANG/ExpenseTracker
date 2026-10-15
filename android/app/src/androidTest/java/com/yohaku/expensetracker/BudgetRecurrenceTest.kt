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
class BudgetRecurrenceTest {
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var book:String
    private lateinit var account:String
    private lateinit var parent:String
    @Before fun open()=runBlocking {
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC))
        book=repo.createBook("预算",Currency.JPY,startDate="2026-10-01",budgetMinor=100000)
        account=repo.saveAccount(book,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-10-01")
        parent=repo.saveCategory(book,"生活",EntryKind.EXPENSE,null)
    }
    @After fun close(){db.close()}
    private suspend fun snapshot()=repo.snapshot(repo.dao.book(book)!!).first()
    private suspend fun rule(amount:Long?=20000)=repo.saveRecurrence(book,RecurrenceInput("房租",account,parent,amount?.let{Money(Currency.JPY,it)},null,amount==null,RecurrenceFrequency.MONTHLY,"2026-10-20"))
    @Test fun budgetActivityExclusionAndPaymentReplaceReservationInsteadOfDoubleCounting()=runBlocking {
        val child=repo.saveCategory(book,"咖啡",EntryKind.EXPENSE,parent)
        repo.saveEntry(book,EntryDraft(accountId=account,categoryId=parent,amount="10000",date="2026-10-02"))
        repo.saveEntry(book,EntryDraft(accountId=account,categoryId=child,amount="20000",date="2026-10-03"))
        repo.setActivityMode(book,true)
        val activity=repo.saveActivity(book,"独立活动","2026-10-01","2026-10-31",Money(Currency.JPY,20000),true)
        repo.saveEntry(book,EntryDraft(accountId=account,categoryId=child,amount="20000",date="2026-10-04",activityId=activity))
        rule()
        val before=snapshot().budgetProjection("2026-10")
        assertEquals(30000L,before.spentMinor);assertEquals(50000L,before.allExpenseMinor);assertEquals(20000L,before.independentMinor)
        assertEquals(70000L,before.remainingMinor);assertEquals(50000L,before.availableMinor)
        assertEquals(30000L,snapshot().budgetProjection("2026-10",parent).spentMinor)
        assertEquals(20000L,snapshot().budgetProjection("2026-10",child).spentMinor)
        assertEquals(3,snapshot().visibleEvents.size)
        val occurrence=snapshot().pendingOccurrences().single()
        val review=repo.previewEntry(book,EntryDraft(accountId=account,categoryId=parent,amount="20000",date="2026-10-14",occurrenceId=occurrence.id))
        assertEquals(20000L,review.impacts.single().reserveBefore);assertEquals(0L,review.impacts.single().reserveAfter)
        val saved=repo.commitEntryReview(book,review)!!
        assertNull(repo.commitEntryReview(book,review))
        val after=snapshot().budgetProjection("2026-10")
        assertEquals(50000L,after.spentMinor);assertEquals(50000L,after.availableMinor)
        assertEquals(130000L,snapshot().balance(snapshot().accounts.single()))
        repo.deleteEntry(book,saved)
        assertEquals(20000L,snapshot().budgetProjection("2026-10").reserveMinor)
        assertEquals(50000L,snapshot().budgetProjection("2026-10").availableMinor)
        repo.restoreEntry(book,saved)
        assertEquals(0L,snapshot().budgetProjection("2026-10").reserveMinor)
        assertEquals(saved,snapshot().occurrences.single().eventId)
    }
    @Test fun linkingExistingExpenseChangesOnlyReservationAndCancellationKeepsFacts()=runBlocking {
        rule()
        val event=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=parent,amount="20000",date="2026-10-03"))
        val occurrence=snapshot().pendingOccurrences().single()
        val review=repo.previewOccurrenceLink(book,occurrence.id,event)
        assertEquals(60000L,review.before.availableMinor);assertEquals(80000L,review.after.availableMinor)
        assertTrue(repo.commitOccurrenceLink(review))
        assertEquals(1,snapshot().visibleEvents.size);assertEquals(180000L,snapshot().balance(snapshot().accounts.single()))
        try{repo.previewEntry(book,EntryDraft(accountId=account,categoryId=parent,amount="20000",occurrenceId=occurrence.id));fail("Completed occurrence created another expense")}catch(_:IllegalArgumentException){}
        repo.cancelOccurrence(book,occurrence.id)
        assertEquals(1,snapshot().visibleEvents.size);assertEquals(20000L,snapshot().monthExpense("2026-10"))
        assertEquals(0L,snapshot().budgetProjection("2026-10").reserveMinor)
    }
    @Test fun carryParentChildLimitsUnknownReserveAndStableFutureInstancesPersist()=runBlocking {
        val child=repo.saveCategory(book,"咖啡",EntryKind.EXPENSE,parent)
        assertTrue(repo.commitBudget(repo.previewBudget(book,"2026-10",parent,Money(Currency.JPY,10000))))
        assertTrue(repo.commitBudget(repo.previewBudget(book,"2026-10",child,Money(Currency.JPY,12000))))
        assertEquals(10000L,snapshot().budgetLimit("2026-11",parent));assertEquals(12000L,snapshot().budgetLimit("2026-11",child))
        assertEquals(100000L,snapshot().budgetLimit("2026-11"))
        val unknown=rule(null)
        try{repo.saveAccount(book,"银行",AccountType.BANK,Currency.CNY,Money(Currency.CNY,200000),"2026-10-01",account);fail("Recurring money reinterpreted by account currency change")}catch(_:IllegalArgumentException){}
        assertEquals("JPY",snapshot().accounts.single().currency)
        assertNull(snapshot().budgetProjection("2026-10").reserveMinor);assertNull(snapshot().budgetProjection("2026-10").availableMinor)
        repo.ensureOccurrences(book,"2026-11")
        val ids=snapshot().occurrences.map{it.id}.toSet()
        repo.ensureOccurrences(book,"2026-11");assertEquals(ids,snapshot().occurrences.map{it.id}.toSet())
        assertTrue(snapshot().events.isEmpty());assertEquals(unknown,snapshot().occurrences.first().recurrenceId)
        val second=repo.createBook("別",Currency.JPY)
        try{repo.previewBudget(second,"2026-10",child,Money(Currency.JPY,100));fail("Cross-book budget accepted")}catch(_:IllegalArgumentException){}
        val stale=repo.previewBudget(book,"2026-10",null,Money(Currency.JPY,120000))
        repo.saveEntry(book,EntryDraft(accountId=account,categoryId=parent,amount="1000",date="2026-10-14"))
        assertFalse(repo.commitBudget(stale));assertEquals(100000L,snapshot().budgetLimit("2026-10"))
        val balance=snapshot().balance(snapshot().accounts.single())
        val combined=repo.previewBookBudget(repo.dao.book(book)!!.copy(name="月预算资料"),"2026-10",Money(Currency.JPY,120000))
        assertTrue(repo.commitBudget(combined))
        assertEquals("月预算资料",repo.dao.book(book)!!.name);assertEquals(120000L,snapshot().budgetLimit("2026-10"))
        assertEquals(balance,snapshot().balance(snapshot().accounts.single()))
    }
    @Test fun ruleWithdrawalRestoresStableTasksButNotManualCancellationsOrPaidFacts()=runBlocking {
        val input=RecurrenceInput("每周费用",account,parent,Money(Currency.JPY,2000),null,true,RecurrenceFrequency.WEEKLY,"2026-10-14")
        val id=repo.saveRecurrence(book,input);repo.ensureOccurrences(book,"2026-11")
        val before=snapshot();val ids=before.occurrences.map{it.id}.toSet()
        val paid=before.occurrences.single{it.scheduledDate=="2026-10-14"};val cancelled=before.occurrences.single{it.scheduledDate=="2026-10-21"};val future=before.occurrences.single{it.scheduledDate=="2026-10-28"}
        val event=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=parent,amount="3000",date="2026-10-14",occurrenceId=paid.id))
        repo.cancelOccurrence(book,cancelled.id)
        repo.saveRecurrence(book,input.copy(id=id,active=false,amount=Money(Currency.JPY,4000)))
        assertTrue(snapshot().pendingOccurrences().isEmpty());assertEquals("WITHDRAWN",snapshot().occurrences.single{it.id==future.id}.status)
        assertEquals(0L,snapshot().budgetProjection("2026-10").reserveMinor)
        repo.saveRecurrence(book,input.copy(id=id,amount=Money(Currency.JPY,4000)))
        val resumed=snapshot()
        assertEquals(ids,resumed.occurrences.map{it.id}.toSet());assertEquals("CANCELLED",resumed.occurrences.single{it.id==cancelled.id}.status)
        assertEquals("COMPLETED",resumed.occurrences.single{it.id==paid.id}.status);assertEquals(event,resumed.occurrences.single{it.id==paid.id}.eventId);assertEquals(2000L,resumed.occurrences.single{it.id==paid.id}.amountMinor)
        assertEquals(4000L,resumed.budgetProjection("2026-10").reserveMinor);assertEquals(16000L,resumed.budgetProjection("2026-11").reserveMinor)
        assertEquals(197000L,resumed.balance(resumed.accounts.single()));assertEquals(1,resumed.events.size)
        repo.restoreOccurrence(book,cancelled.id)
        assertEquals(8000L,snapshot().budgetProjection("2026-10").reserveMinor)
        repo.saveRecurrence(book,input.copy(id=id,amount=Money(Currency.JPY,4000),endDate="2026-10-21"))
        assertEquals("WITHDRAWN",snapshot().occurrences.single{it.id==future.id}.status)
        repo.saveRecurrence(book,input.copy(id=id,amount=Money(Currency.JPY,4000)))
        assertEquals("PENDING",snapshot().occurrences.single{it.id==future.id}.status)
        assertEquals(ids,snapshot().occurrences.map{it.id}.toSet());assertEquals(1,snapshot().events.size)
        repo.saveRecurrence(book,input.copy(id=id,active=false))
        repo.deleteEntry(book,event)
        assertTrue(snapshot().pendingOccurrences().isEmpty());assertEquals("WITHDRAWN",snapshot().occurrences.single{it.id==paid.id}.status)
        repo.restoreEntry(book,event)
        assertEquals("COMPLETED",snapshot().occurrences.single{it.id==paid.id}.status);assertTrue(snapshot().pendingOccurrences().isEmpty())
        repo.unlinkOccurrence(book,paid.id)
        assertEquals("WITHDRAWN",snapshot().occurrences.single{it.id==paid.id}.status);assertTrue(snapshot().pendingOccurrences().isEmpty())
        assertEquals(197000L,snapshot().balance(snapshot().accounts.single()));assertEquals(3000L,snapshot().monthExpense("2026-10"))
    }

}
