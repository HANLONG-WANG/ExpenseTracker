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
        val s=snapshot();val filter=AnalysisFilter("2026-10-01","2026-10-31")
        val report=s.analysis(filter)
        assertEquals(3500L,report.gross);assertEquals(500L,report.refunds);assertEquals(300L,report.fees);assertEquals(3300L,report.total)
        val groups=s.categoryGroups(report)
        assertEquals(3000L,groups.first{it.key==parent}.amount);assertEquals(300L,groups.first{it.key=="FEES"}.amount)
        val children=s.categoryChildren(report,parent)
        assertEquals(1500L,children.first{it.key==child}.amount);assertEquals(1500L,children.first{it.key=="UNSPECIFIED"}.amount)
        assertEquals(3000L,s.analysis(filter.copy(categoryId=parent)).total)
        assertEquals(1500L,s.analysis(filter.copy(categoryId=child,merchantKey="id:"+shop,activityId=activity)).total)
        assertEquals(1,s.confirmedRefunds(report).size);assertEquals("2026-11-02",s.confirmedRefunds(report).single().date)
        assertEquals(0L,s.analysis(AnalysisFilter("2026-11-01","2026-11-30")).total)
        assertEquals(4,report.groups{it.date}.size)
        val comparison=s.compareAnalysis(filter,LocalDate.parse("2026-10-14"))
        assertTrue(comparison.partialMonth);assertEquals("2026-09-14",comparison.previousEnd)
        assertEquals(2800L,comparison.current.total);assertEquals(200L,comparison.previous.total);assertEquals(2600L,comparison.change)
        val full=s.compareAnalysis(AnalysisFilter("2026-09-01","2026-09-30"),LocalDate.parse("2026-10-14"))
        assertFalse(full.partialMonth);assertEquals("2026-08-31",full.previousEnd)
        val both=s.analysis(AnalysisFilter("2026-09-01","2026-10-31"))
        assertEquals(listOf("2026-09","2026-10"),both.groups{it.date.take(7)}.sortedBy{it.key}.map{it.key})
        val legacyShop=repo.saveMerchant(book,"日常")
        assertEquals(1500L,snapshot().analysis(filter.copy(merchantKey="id:"+legacyShop)).total)
        assertEquals(1,snapshot().eventMerchants.size) // exact-name analysis grouping does not rewrite old facts
        assertEquals(s.events,snapshot().events);assertEquals(s.postings,snapshot().postings)
        assertEquals(1800L,s.budgetProjection("2026-10").spentMinor) // excluded activity still remains in analysis
    }
    @Test fun preferencesStayWithBookAndThemeWhileMerchantOriginalFactsRemain()=runBlocking {
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
        assertEquals("珈琲の原名",s.events.single().merchant);assertEquals(shop,s.eventMerchants.single().merchantId)
        assertEquals(1000L,s.analysis(AnalysisFilter("2026-10-01","2026-10-31",merchantKey="id:"+shop)).total)
        val review=repo.previewEntry(book,EntryDraft(id=event,accountId=account,categoryId=parent,amount="1000",date="2026-10-01",merchant="手动填写"))
        assertNotNull(repo.commitEntryReview(book,review));assertTrue(snapshot().eventMerchants.isEmpty())
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
