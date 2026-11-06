package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.command.monthlySummary
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BudgetExclusionTest {
    private val date="2026-10-10"
    private fun expense(id:String,n:Long,excluded:Boolean=false)=EventEntity("b",id,"EXPENSE","bank","c",n,"JPY",n,date,"","",budgetExcluded=excluded)
    private fun snapshot():BookSnapshot {
        val events=listOf(expense("regular",100),expense("excluded",1000,true),expense("activity",300),expense("both",400,true),expense("refund",200).copy(kind="REFUND"))
        fun settlement(id:String,n:Long,confirmed:Boolean,original:String?=null)=SettlementEntity("b",id,n,"JPY",confirmed,date,"manual",date,n,n,original)
        return BookSnapshot(BookEntity("b","生活","JPY","2026-10-01",budgetMinor=5000),emptyList(),listOf(CategoryEntity("b","c","食費","EXPENSE",null,false)),events,emptyList(),null,
            settlements=listOf(settlement("regular",100,false),settlement("excluded",1000,false),settlement("refund",200,true,"excluded")),
            expenseComponents=listOf(ExpenseComponentEntity("b","fee","excluded","bank",50,"JPY",50,date,true,"SEPARATE",50,"JPY")),
            activities=listOf(ActivityEntity("b","independent","旅行",date,date,2000,true),ActivityEntity("b","ordinary","日常",date,date,2000)),
            eventActivities=listOf(EventActivityEntity("b","excluded","ordinary"),EventActivityEntity("b","activity","independent"),EventActivityEntity("b","both","independent")))
    }
    @Test fun monthlyAndCategoryExcludeUnionOnceButAnalysisAndActivityKeepRefundsAndFees() {
        val s=snapshot();val b=s.budgetProjection("2026-10")
        assertEquals(100L,b.spentMinor);assertEquals(1550L,b.independentMinor);assertEquals(1650L,b.allExpenseMinor)
        assertEquals(4900L,b.remainingMinor);assertEquals(4900L,b.availableMinor)
        val c=s.budgetProjection("2026-10","c");assertEquals(100L,c.spentMinor);assertEquals(1500L,c.independentMinor)
        assertEquals(1650L,s.monthExpense("2026-10"));assertEquals(850L,s.activitySpent("ordinary"));assertEquals(700L,s.activitySpent("independent"))
        assertEquals(1,s.provisionalExpenseCount("2026-10"));assertEquals(1,s.monthlySummary("2026-10","").provisionalExpenseCount)
        assertEquals(100L,s.monthlySummary("2026-10","").budgets.first().spentMinor)
    }
    @Test fun calendarUsesSeparateActualAndBudgetDailyTotalsAndPaceExcludesStandaloneSpending() {
        val s=snapshot()
        assertEquals(1650L,s.dailyExpense(s.visibleEvents)[date]);assertEquals(100L,s.dailyExpense(s.visibleEvents,budgetOnly=true)[date])
        val standalone=s.copy(eventActivities=emptyList(),events=s.events.filter{it.id !in setOf("activity","both")})
        val pace=standalone.budgetPace(LocalDate.parse(date))
        assertEquals(100L,pace.actualSpentMinor);assertEquals(100L,pace.dailySampleMinor);assertEquals(210L,pace.futureDailyMinor)
        val future=standalone.copy(events=standalone.events.map{if(it.id=="excluded")it.copy(date="2026-10-20")else it},expenseComponents=standalone.expenseComponents.map{it.copy(date="2026-10-20")})
        assertEquals(0L,future.budgetPace(LocalDate.parse(date)).knownFutureMinor)
    }
    @Test fun draftAndTemplateRoundTripPreserveExclusionAndOldDraftDefaultsFalse() {
        val s=snapshot().copy(accounts=listOf(AccountEntity("b","bank","銀行","BANK","JPY",0,"2026-01-01")))
        val d=EntryDraft(accountId="bank",categoryId="c",budgetExcluded=true,amount="100")
        assertEquals(d,Json.decodeFromString<EntryDraft>(Json.encodeToString(d)))
        assertFalse(Json.decodeFromString<EntryDraft>("{}").budgetExcluded)
        val input=s.templateInput(d,"日常",true);assertTrue(input.budgetExcluded)
        val template=EntryTemplateEntity("b","t",input.name,"EXPENSE","c","bank","JPY",createdAt=1,budgetExcluded=input.budgetExcluded)
        assertTrue(s.templateDraft(template).budgetExcluded)
        assertTrue(s.editingEntryDraft(s.events.first{it.id=="excluded"}).budgetExcluded)
        assertFalse(s.templateInput(d.copy(kind=EntryKind.INCOME),"収入",true).budgetExcluded)
    }
}
