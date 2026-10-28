package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AnalysisProjectionCalculationTest {
    @Test fun parentChildrenRefundsFeesFiltersAndComparisonUseTheSameEconomicFacts() {
        val parent="parent";val child="child";val shop="shop";val activity="activity"
        fun expense(id:String,amount:Long,date:String,category:String=parent,merchant:String="日常")=EventEntity("b",id,"EXPENSE","bank",category,amount,"JPY",amount,date,merchant,"")
        val purchase=expense("purchase",2000,"2026-10-02",child,"喫茶店")
        val refund=EventEntity("b","refund","REFUND","bank",child,500,"JPY",500,"2026-11-02","","")
        val transfer=EventEntity("b","transfer","TRANSFER","bank",null,1000,"JPY",0,"2026-10-03","","")
        val s=BookSnapshot(BookEntity("b","分析","JPY","2026-09-01",budgetMinor=100000),
            listOf(AccountEntity("b","bank","銀行","BANK","JPY",200000,"2026-09-01")),
            listOf(CategoryEntity("b",parent,"生活","EXPENSE"),CategoryEntity("b",child,"咖啡","EXPENSE",parent)),
            listOf(expense("first",1000,"2026-10-01"),purchase,refund,expense("later",500,"2026-10-20"),expense("previous",200,"2026-09-02"),expense("previous-later",900,"2026-09-28"),transfer),emptyList(),null,
            settlements=listOf(SettlementEntity("b",refund.id,500,"JPY",true,refund.date,"MANUAL",refund.date,500,500,purchase.id)),
            expenseComponents=listOf(ExpenseComponentEntity("b","fee",transfer.id,"bank",300,"JPY",300,transfer.date,true,"SOURCE",300,"JPY")),
            activities=listOf(ActivityEntity("b",activity,"旅行","2026-10-01","2026-11-30",null,true)),
            features=BookFeaturesEntity("b",true),eventActivities=listOf(EventActivityEntity("b",purchase.id,activity)),
            merchants=listOf(MerchantEntity("b",shop,"喫茶店")),eventMerchants=listOf(EventMerchantEntity("b",purchase.id,shop)))
        val filter=AnalysisFilter("2026-10-01","2026-10-31")
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
        val legacyShop="legacy"
        val legacy=s.copy(merchants=s.merchants+MerchantEntity("b",legacyShop,"日常"))
        assertEquals(1500L,legacy.analysis(filter.copy(merchantKey="id:"+legacyShop)).total)
        assertEquals(1,legacy.eventMerchants.size) // exact-name analysis grouping does not rewrite old facts
        assertEquals(s.events,legacy.events);assertEquals(s.postings,legacy.postings)
        assertEquals(1800L,s.budgetProjection("2026-10").spentMinor) // excluded activity still remains in analysis
    }
}
