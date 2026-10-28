package com.yohaku.expensetracker

import androidx.room.Room
import com.yohaku.expensetracker.domain.category.CategoryIconChoice
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class CategoryManagementStorageTest {
    private lateinit var db:YohakuDatabase
    private lateinit var r:LedgerRepository
    @Before fun open(){db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,YohakuDatabase::class.java).build();r=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"),ZoneOffset.UTC))}
    @After fun close(){db.close()}
    private suspend fun snapshot(b:String)=r.snapshot(r.dao.book(b)!!).first()
    private suspend fun category(b:String,n:String,p:String?=null)=r.saveCategory(b,n,EntryKind.EXPENSE,p)
    private suspend fun budget(b:String,c:String,m:String,n:Long?){r.dao.putBudget(BudgetEntity(b,m,c,c,n))}
    @Test fun categoryIconAutoPreservesEditsAndExplicitChoiceOverrides()=runBlocking {
        val b=r.createBook("图标",Currency.JPY)
        val root=category(b,"咖啡");assertEquals("coffee",r.dao.category(b,root)!!.icon)
        val child=category(b,"咖啡",root);assertNull(r.dao.category(b,child)!!.icon)
        val different=category(b,"猫咪",root);assertEquals("cat",r.dao.category(b,different)!!.icon)
        r.saveCategory(b,"电车",EntryKind.EXPENSE,null,root)
        assertEquals("coffee",r.dao.category(b,root)!!.icon)
        r.saveCategory(b,"电车",EntryKind.EXPENSE,null,root,icon=CategoryIconChoice.Set(null))
        assertNull(r.dao.category(b,root)!!.icon)
        r.saveCategory(b,"猫咪",EntryKind.EXPENSE,null,root)
        assertNull(r.dao.category(b,root)!!.icon)
        r.saveCategory(b,"猫咪",EntryKind.EXPENSE,null,root,icon=CategoryIconChoice.Set("cat"))
        r.toggleCategoryFavorite(b,root);assertEquals("cat",r.dao.category(b,root)!!.icon)
        try {r.saveCategory(b,"猫咪",EntryKind.EXPENSE,null,root,icon=CategoryIconChoice.Set("unknown"));fail("unknown icon")}
        catch(_:IllegalArgumentException){}
    }
    @Test fun rootStopPreservesChildStateAndHistoryEditingWhileRejectingNewUse()=runBlocking {
        val b=r.createBook("停止",Currency.JPY);val root=category(b,"食事");val child=category(b,"コーヒー",root)
        val a=r.saveAccount(b,"現金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
        val d=EntryDraft(accountId=a,categoryId=child,amount="500",date="2026-10-16");val event=r.saveEntry(b,d)
        assertTrue(r.commitCategoryOperation(r.previewCategoryOperation(b,root,CategoryOperation.ARCHIVE)))
        assertFalse(snapshot(b).categoryEnabled(child));assertTrue(r.dao.category(b,child)!!.active)
        r.saveCategory(b,"食費",EntryKind.EXPENSE,null,root);assertFalse(r.dao.category(b,root)!!.active)
        try{r.saveEntry(b,d);fail("stopped parent")}catch(_:IllegalArgumentException){}
        assertEquals(event,r.saveEntry(b,d.copy(id=event,amount="600")))
        assertTrue(r.commitCategoryOperation(r.previewCategoryOperation(b,root,CategoryOperation.UNARCHIVE)))
        assertTrue(snapshot(b).categoryEnabled(child));assertEquals(9400L,snapshot(b).balance(snapshot(b).accounts.single()))
    }
    @Test fun reparentPreviewReprojectsHistoryAndBudgetWithoutChangingIdsOrMoney()=runBlocking {
        val b=r.createBook("移動",Currency.JPY);val old=category(b,"旧");val dest=category(b,"新");val child=category(b,"コーヒー",old)
        val a=r.saveAccount(b,"現金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
        val event=r.saveEntry(b,EntryDraft(accountId=a,categoryId=child,amount="500",date="2026-09-30"))
        budget(b,child,"2026-09",1000);val before=snapshot(b)
        val review=r.previewCategoryOperation(b,child,CategoryOperation.REPARENT,dest)
        assertTrue(review.projections.any{it.categoryId==old&&it.before.spentMinor==500L&&it.after.spentMinor==0L})
        assertTrue(review.projections.any{it.categoryId==dest&&it.after.spentMinor==500L})
        assertEquals(old,r.dao.category(b,child)!!.parentId)
        assertTrue(r.commitCategoryOperation(review));val after=snapshot(b)
        assertEquals(dest,r.dao.category(b,child)!!.parentId);assertEquals(before.events,after.events);assertEquals(before.postings,after.postings);assertEquals(before.budgets,after.budgets);assertEquals(event,after.events.single().id)
        try{r.previewCategoryOperation(b,dest,CategoryOperation.REPARENT,old);fail("third level")}catch(_:IllegalArgumentException){}
    }
    @Test fun mergeMovesTrashSchedulesDraftsAndBudgetsTransactionallyWithNoParentDoubleCount()=runBlocking {
        val b=r.createBook("統合",Currency.JPY);val other=r.createBook("独立",Currency.JPY);category(other,"保持");val independent=snapshot(other)
        val root=category(b,"食事");val child=category(b,"コーヒー",root);val target=category(b,"日常")
        val a=r.saveAccount(b,"現金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
        val event=r.saveEntry(b,EntryDraft(accountId=a,categoryId=child,amount="500",date="2026-10-16"));r.deleteEntry(b,event)
        r.saveDraft(b,EntryDraft(accountId=a,categoryId=child,amount="12."))
        r.storeFinancialDraft(FinancialDraftContext("EXPECTED",null),b,"{\"category\":\"$child\",\"name\":\"$child\"}","basis")
        r.saveRecurrence(b,RecurrenceInput("定期",a,child,Money(Currency.JPY,100),null,false,RecurrenceFrequency.MONTHLY,"2026-10-17"))
        budget(b,root,"2026-09",5000);budget(b,child,"2026-09",1000);budget(b,target,"2026-09",2000);budget(b,root,"2026-11",6000)
        val activity=r.id();r.dao.putActivity(ActivityEntity(b,activity,"旅行","2026-10-01","2026-10-31",10000,false,true))
        r.dao.putActivityBudget(ActivityBudgetEntity(b,activity,root,5000));r.dao.putActivityBudget(ActivityBudgetEntity(b,activity,child,1000));r.dao.putActivityBudget(ActivityBudgetEntity(b,activity,target,2000))
        val before=snapshot(b);val review=r.previewCategoryOperation(b,root,CategoryOperation.MERGE_DELETE,target,CategoryBudgetPolicy.COMBINE)
        assertEquals(2,review.sourceIds.size);assertEquals(1,review.recordCount);assertEquals(2,review.draftCount)
        assertTrue(r.commitCategoryOperation(review));val after=snapshot(b)
        assertNull(r.dao.category(b,child));assertNull(r.dao.category(b,root));assertEquals(target,after.events.single().categoryId);assertNotNull(after.events.single().deletedAt)
        assertEquals(before.postings,after.postings);assertEquals(before.storedPostings,after.storedPostings);assertEquals(target,after.draft!!.categoryId);assertEquals("12.",after.draft!!.amount)
        assertTrue(after.recurrences.all{it.categoryId==target});assertTrue(after.occurrences.isNotEmpty());assertTrue(after.occurrences.all{it.categoryId==target})
        assertEquals(7000L,after.budgetLimit("2026-10",target));assertEquals(8000L,after.budgetLimit("2026-12",target));assertEquals(7000L,after.activityBudgets.single().amountMinor)
        val draft=db.financialDao().bookDrafts(b).single().valuesJson;assertTrue(draft.contains("\"category\":\"$target\""));assertTrue(draft.contains("\"name\":\"$child\""))
        assertEquals(independent,snapshot(other));db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
    }
    @Test fun mergeIntoParentKeepsCoveringCapAndStalePreviewCannotDropNewData()=runBlocking {
        val b=r.createBook("確認",Currency.JPY);val parent=category(b,"親");val child=category(b,"子",parent)
        budget(b,parent,"2026-10",5000);budget(b,child,"2026-10",1000)
        val review=r.previewCategoryOperation(b,child,CategoryOperation.MERGE_DELETE,parent,CategoryBudgetPolicy.COMBINE)
        r.saveDraft(b,EntryDraft(categoryId=child))
        assertFalse(r.commitCategoryOperation(review));assertNotNull(r.dao.category(b,child))
        assertTrue(r.commitCategoryOperation(r.previewCategoryOperation(b,child,CategoryOperation.MERGE_DELETE,parent,CategoryBudgetPolicy.COMBINE)))
        assertEquals(5000L,snapshot(b).budgetLimit("2026-10",parent))
        val blocked=r.previewCategoryOperation(b,parent,CategoryOperation.DELETE_EMPTY)
        assertTrue(blocked.deletionBlocked)
        try{r.commitCategoryOperation(blocked);fail("budget reference must block deletion")}catch(_:IllegalArgumentException){}
        val empty=category(b,"未使用");val emptyChild=category(b,"空の子",empty)
        val unused=r.previewCategoryOperation(b,empty,CategoryOperation.DELETE_EMPTY)
        assertFalse(unused.deletionBlocked);assertEquals(2,unused.sourceIds.size)
        assertTrue(r.commitCategoryOperation(unused));assertNull(r.dao.category(b,empty));assertNull(r.dao.category(b,emptyChild))
    }
}
