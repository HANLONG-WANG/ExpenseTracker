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
class AnalysisProjectionTest {
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var book:String
    private lateinit var account:String
    private lateinit var parent:String
    private lateinit var child:String
    @Before fun open()=runBlocking {
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC))
        book=repo.createBook("分析",Currency.JPY,startDate="2026-09-01",budgetMinor=100000)
        account=repo.saveAccount(book,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-09-01")
        parent=repo.saveCategory(book,"生活",EntryKind.EXPENSE,null);child=repo.saveCategory(book,"咖啡",EntryKind.EXPENSE,parent)
    }
    @After fun close(){db.close()}
    private suspend fun snapshot()=repo.snapshot(repo.dao.book(book)!!).first()
    private suspend fun expense(amount:String,date:String,category:String=parent,merchant:String="日常",merchantId:String?=null,activityId:String?=null)=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount=amount,date=date,merchant=merchant,merchantId=merchantId,activityId=activityId))
    @Test fun parentChildrenRefundsFeesFiltersAndComparisonUseTheSameEconomicFacts()=runBlocking {
        val shop=repo.saveMerchant(book,"喫茶店")
        repo.setActivityMode(book,true)
        val activity=repo.saveActivity(book,"旅行","2026-10-01","2026-11-30",null,true)
        expense("1000","2026-10-01")
        val purchase=expense("2000","2026-10-02",child,"喫茶店",shop,activity)
        repo.saveRefund(book,RefundInput(purchase,Money(Currency.JPY,500),account,Money(Currency.JPY,500),Money(Currency.JPY,500),"2026-11-02",true))
        expense("500","2026-10-20")
        expense("200","2026-09-02");expense("900","2026-09-28")
        val other=repo.saveAccount(book,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,0),"2026-09-01")
        repo.commitEntryReview(book,repo.previewEntry(book,EntryDraft(kind=EntryKind.TRANSFER,accountId=account,toAccountId=other,amount="1000",toAmount="700",date="2026-10-03",toDate="2026-10-03",fromConfirmed=true,toConfirmed=true,feeAmount="300",feeDate="2026-10-03",feeConfirmed=true)))
        val s=snapshot()
        assertEquals(purchase,s.settlements.single{it.originalEventId!=null}.originalEventId)
        assertEquals(shop,s.eventMerchants.single{it.eventId==purchase}.merchantId)
        assertEquals(activity,s.eventActivities.single{it.eventId==purchase}.activityId)
        assertEquals(300L,s.expenseComponents.single().baseMinor)
        val beforeEvents=s.events;val beforePostings=s.postings
        repo.saveMerchant(book,"日常")
        assertEquals(5,snapshot().eventMerchants.size)
        assertEquals(beforeEvents,snapshot().events);assertEquals(beforePostings,snapshot().postings)
    }
    @Test fun preferencesStayWithBookAndThemeWhileMerchantRenameSynchronizesRecords()=runBlocking {
        assertNull(snapshot().analysisPreferences)
        repo.saveAnalysisPreferences(book,listOf(AnalysisModule.MERCHANTS,AnalysisModule.CATEGORIES,AnalysisModule.TREND),setOf(AnalysisModule.TREND))
        repo.updateAppearance(book,"journal","ja")
        val second=repo.createBook("別",Currency.JPY)
        assertEquals(listOf(AnalysisModule.MERCHANTS,AnalysisModule.CATEGORIES,AnalysisModule.TREND),repo.analysisModules(snapshot()))
        assertEquals(setOf(AnalysisModule.TREND),repo.analysisPins(snapshot()))
        assertTrue(repo.analysisModules(repo.snapshot(repo.dao.book(second)!!).first()).isEmpty())
        val shop=repo.saveMerchant(book,"珈琲の原名")
        val event=expense("1000","2026-10-01",merchant="珈琲の原名",merchantId=shop)
        repo.saveMerchant(book,"珈琲の新資料",id=shop)
        val fresh=LedgerRepository(db,repo.clock)
        val s=fresh.snapshot(fresh.dao.book(book)!!).first()
        assertEquals("珈琲の新資料",s.events.single().merchant);assertEquals(shop,s.eventMerchants.single().merchantId)
        assertEquals(1000L,s.analysis(AnalysisFilter("2026-10-01","2026-10-31",merchantKey="id:"+shop)).total)
        val review=repo.previewEntry(book,EntryDraft(id=event,accountId=account,categoryId=parent,amount="1000",date="2026-10-01",merchant="手动填写"))
        assertNotNull(repo.commitEntryReview(book,review));assertTrue(snapshot().eventMerchants.isEmpty())
    }
    @Test fun deletingMerchantDetachesLiveReferencesAndPreservesRecordedNames()=runBlocking {
        val shop=repo.saveMerchant(book,"原名")
        repo.saveMerchantLocation(book,shop,"入口",35.0,139.0)
        val event=expense("100","2026-10-01",merchant="记录原名",merchantId=shop)
        val trashed=expense("200","2026-10-02",merchant="回收站原名",merchantId=shop)
        repo.deleteEntry(book,trashed)
        val template=repo.saveTemplate(book,TemplateInput("常用",EntryKind.EXPENSE,parent,account,Currency.JPY,merchant="模板原名",merchantId=shop))
        val rule=repo.saveRecurrence(book,RecurrenceInput("每月",account,parent,Money(Currency.JPY,100),null,false,RecurrenceFrequency.MONTHLY,"2026-10-01",merchant="周期原名",merchantId=shop))
        val draft=EntryDraft(accountId=account,categoryId=parent,merchant="草稿原名",merchantId=shop,merchantLocated=true)
        repo.saveDraft(book,draft)
        val before=snapshot()
        val other=repo.createBook("別",Currency.JPY)
        try{repo.deleteMerchant(other,shop);fail("Cross-book deletion accepted")}catch(_:IllegalArgumentException){}
        assertEquals(before,snapshot())

        repo.deleteMerchant(book,shop)
        val after=snapshot()
        assertTrue(after.merchants.isEmpty());assertTrue(after.merchantLocations.isEmpty());assertTrue(after.eventMerchants.isEmpty())
        assertEquals(before.events,after.events)
        assertEquals("记录原名",after.events.single{it.id==event}.merchant)
        assertEquals("回收站原名",after.events.single{it.id==trashed}.merchant)
        assertEquals(before.templates.single().copy(merchantId=null),after.templates.single{it.id==template})
        assertEquals(before.recurrences.single().copy(merchantId=null),after.recurrences.single{it.id==rule})
        assertEquals(draft.copy(merchantId=null,merchantLocated=false),after.draft)
        assertEquals("",after.merchantLabel("id:$shop"))
        // A queued old form save must not reintroduce a dangling draft reference.
        repo.saveDraft(book,draft)
        assertEquals(after.draft,snapshot().draft)
        repo.restoreEntry(book,trashed)
        assertNull(repo.dao.event(book,trashed)!!.deletedAt)
        assertEquals("回收站原名",repo.dao.event(book,trashed)!!.merchant)
        assertTrue(snapshot().eventMerchants.isEmpty())
    }
    @Test fun renameSynchronizesLegacyLinkedTrashRefundAndReusableReferences()=runBlocking {
        val shop=repo.saveMerchant(book,"Ｃａｆｅ")
        val linked=expense("100","2026-10-01",merchant="以前改名留下的文字",merchantId=shop)
        val legacy=expense("200","2026-10-02",merchant=" cafe ")
        val refund=repo.saveRefund(book,RefundInput(legacy,Money(Currency.JPY,50),account,Money(Currency.JPY,50),Money(Currency.JPY,50),"2026-10-03",true))
        val trashed=expense("300","2026-10-04",merchant="CAFE")
        repo.deleteEntry(book,trashed)
        val incomeCategory=repo.saveCategory(book,"收入",EntryKind.INCOME,null)
        val income=repo.saveEntry(book,EntryDraft(kind=EntryKind.INCOME,accountId=account,categoryId=incomeCategory,amount="50",date="2026-10-05",merchant="cafe"))
        val cash=repo.saveAccount(book,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,0),"2026-09-01")
        val transfer=repo.commitEntryReview(book,repo.previewEntry(book,EntryDraft(kind=EntryKind.TRANSFER,accountId=account,toAccountId=cash,amount="100",toAmount="100",date="2026-10-05",toDate="2026-10-05",fromConfirmed=true,toConfirmed=true,merchant="cafe")))!!
        repo.saveTemplate(book,TemplateInput("常用",EntryKind.EXPENSE,parent,account,Currency.JPY,merchant="旧模板名",merchantId=shop))
        repo.saveRecurrence(book,RecurrenceInput("每月",account,parent,Money(Currency.JPY,100),null,false,RecurrenceFrequency.MONTHLY,"2026-10-01",merchant="旧周期名",merchantId=shop))
        repo.saveDraft(book,EntryDraft(accountId=account,categoryId=parent,merchant="旧草稿名",merchantId=shop))
        val before=snapshot()
        assertEquals(setOf(linked,legacy,refund,trashed,income,transfer),before.merchantEvents(shop).map{it.id}.toSet())
        repo.saveMerchant(book,"New cafe",id=shop)
        val after=snapshot()
        assertTrue(after.events.all{it.merchant=="New cafe"})
        assertEquals(setOf(linked,legacy,trashed),after.eventMerchants.map{it.eventId}.toSet())
        assertTrue(after.eventMerchants.all{it.merchantId==shop})
        assertEquals(before.events.map{it.id}.toSet(),after.merchantEvents(shop).map{it.id}.toSet())
        assertEquals("New cafe",after.templates.single().merchant);assertEquals("New cafe",after.recurrences.single().merchant);assertEquals("New cafe",after.draft!!.merchant)
        assertEquals(before.postings,after.postings)
        // The name is a reference to the merchant, not a record edit: no revision, so recent-entry defaults keep their order.
        assertEquals(before.events.map{it.copy(merchant="New cafe")},after.events)
        assertEquals(before.eventSaveTimes,after.eventSaveTimes)
        repo.saveMerchant(book,"New cafe",note="只改备注",id=shop,active=false)
        assertEquals(after.events,snapshot().events)
        repo.restoreEntry(book,trashed);assertEquals("New cafe",repo.dao.event(book,trashed)!!.merchant)
    }
    @Test fun adoptionRequiresConfirmationAndDuplicateNamesRequireExplicitMerge()=runBlocking {
        val source=repo.saveMerchant(book,"Source","来源备注")
        val target=repo.saveMerchant(book,"ＴＡＲＧＥＴ","目标备注",active=false)
        val sourceEvent=expense("100","2026-10-01",merchant="source")
        val targetEvent=expense("200","2026-10-02",merchant="target")
        repo.saveMerchantLocation(book,source,"入口",35.0,139.0)
        repo.saveTemplate(book,TemplateInput("常用",EntryKind.EXPENSE,parent,account,Currency.JPY,merchant="Source",merchantId=source))
        repo.saveRecurrence(book,RecurrenceInput("每月",account,parent,Money(Currency.JPY,100),null,false,RecurrenceFrequency.MONTHLY,"2026-10-01",merchant="Source",merchantId=source))
        val before=snapshot()
        for(id in listOf<String?>(null,source)) {
            try{repo.saveMerchant(book," target ",id=id,confirmAdoption=true);fail("Duplicate accepted")}catch(error:IllegalArgumentException){assertTrue(error.message!!.contains("应用内合并"))}
        }
        assertEquals(before,snapshot())
        val adopted=expense("50","2026-10-03",merchant="ＮＥＷ")
        try{repo.saveMerchant(book,"new",id=source);fail("Unconfirmed adoption")}catch(_:IllegalArgumentException){}
        assertEquals("Source",snapshot().merchants.single{it.id==source}.name)
        repo.saveMerchant(book,"new",note="来源备注",id=source,confirmAdoption=true)
        assertEquals(setOf(sourceEvent,adopted),snapshot().merchantEvents(source).map{it.id}.toSet())
        assertEquals(source,snapshot().eventMerchants.single{it.eventId==adopted}.merchantId)
        repo.saveDraft(book,EntryDraft(merchant="new",merchantId=source)) // Saving an entry clears the draft, so store it last.
        repo.mergeMerchants(book,source,target,sourceNote="编辑后的备注",sourceActive=true)
        val after=snapshot()
        assertEquals(target,after.merchants.single().id);assertEquals("目标备注\n编辑后的备注",after.merchants.single().note);assertTrue(after.merchants.single().active)
        assertEquals(setOf(sourceEvent,targetEvent,adopted),after.merchantEvents(target).map{it.id}.toSet())
        assertTrue(after.events.all{it.merchant=="ＴＡＲＧＥＴ"});assertTrue(after.eventMerchants.all{it.merchantId==target})
        assertEquals(target,after.merchantLocations.single().merchantId)
        assertEquals(target,after.templates.single().merchantId);assertEquals("ＴＡＲＧＥＴ",after.templates.single().merchant)
        assertEquals(target,after.recurrences.single().merchantId);assertEquals("ＴＡＲＧＥＴ",after.recurrences.single().merchant)
        assertEquals(target,after.draft!!.merchantId);assertEquals("ＴＡＲＧＥＴ",after.draft!!.merchant)
    }
    @Test fun historicalDuplicatesRemainAmbiguousUntilMerged()=runBlocking {
        val first=repo.saveMerchant(book,"Cafe")
        val second=repo.id();repo.dao.putMerchant(MerchantEntity(book,second,"ＣＡＦＥ"))
        val event=expense("100","2026-10-01",merchant="cafe")
        assertFalse(snapshot().merchantKey(repo.dao.event(book,event)!!).startsWith("id:"))
        repo.saveMerchant(book,"CAFE",id=first,active=false)
        assertTrue(snapshot().eventMerchants.isEmpty())
        repo.mergeMerchants(book,first,second)
        assertEquals(second,snapshot().eventMerchants.single().merchantId)
        assertEquals("ＣＡＦＥ",snapshot().events.single().merchant)
    }
    @Test fun savedLocationsReturnMultipleOwnedCandidatesAndCannotCrossBooks()=runBlocking {
        val first=repo.saveMerchant(book,"お店A");val second=repo.saveMerchant(book,"お店B")
        repo.saveMerchantLocation(book,first,"入口",35.0,139.0)
        repo.saveMerchantLocation(book,second,"近く",35.0001,139.0001)
        repo.saveMerchantLocation(book,first,"名称のみ",null,null)
        assertEquals(listOf(first,second),snapshot().merchantCandidates(35.0,139.0).map{it.merchant.id})
        repo.saveMerchant(book,"お店A",id=first,active=false)
        assertEquals(listOf(second),snapshot().merchantCandidates(35.0,139.0).map{it.merchant.id})
        val separate=repo.createBook("別",Currency.JPY)
        try{repo.saveMerchantLocation(separate,first,"別",35.0,139.0);fail("Cross-book location accepted")}catch(_:IllegalArgumentException){}
        try{repo.saveEntry(book,EntryDraft(accountId=account,categoryId=parent,amount="100",merchantId=repo.saveMerchant(separate,"別店")));fail("Cross-book merchant accepted")}catch(_:IllegalStateException){}
        assertTrue(snapshot().events.isEmpty())
    }
}
