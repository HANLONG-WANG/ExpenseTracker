package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BudgetPaceTest {
    @Test fun dailyPaceExcludesActivitiesFixedPaymentsAndFeesAndDoesNotDoubleCountFutureActuals() {
        val book=BookEntity("b","帳簿","JPY","2026-10-01",budgetMinor=10000)
        fun expense(id:String,amount:Long,date:String)=EventEntity("b",id,"EXPENSE","bank","c",amount,"JPY",amount,date,"","")
        val daily=expense("daily",1500,"2026-10-15")
        val fixed=expense("fixed",600,"2026-10-03")
        val activity=expense("activity",200,"2026-10-04")
        val future=expense("future",400,"2026-10-22")
        fun occurrence(id:String,date:String,n:Long,eventId:String?=null)=OccurrenceEntity("b",id,"r",date,id,"bank","c","JPY",n,n,false,if(eventId==null)"PENDING"else"COMPLETED",eventId)
        val s=BookSnapshot(book,emptyList(),emptyList(),listOf(daily,fixed,activity,future),emptyList(),null,
            expenseComponents=listOf(ExpenseComponentEntity("b","fee",daily.id,"bank",100,"JPY",100,"2026-10-04",true,"SEPARATE",100,"JPY")),
            activities=listOf(ActivityEntity("b","a","シーン","2026-10-01","2026-10-31",null)),eventActivities=listOf(EventActivityEntity("b",activity.id,"a")),
            occurrences=listOf(occurrence("old","2026-10-03",600,fixed.id),occurrence("pending","2026-10-20",800),occurrence("future","2026-10-22",400,future.id)))
        val p=s.budgetPace(LocalDate.parse("2026-10-15"))
        assertEquals(2400L,p.actualSpentMinor);assertEquals(1500L,p.dailySampleMinor)
        assertEquals(15,p.sampleDays);assertEquals(16,p.remainingDays);assertEquals(1600L,p.futureDailyMinor)
        assertEquals(1200L,p.knownFutureMinor);assertEquals(5200L,p.projectedMinor);assertFalse(p.lowConfidence)
        assertNull(s.copy(occurrences=s.occurrences.map{if(it.id=="pending")it.copy(baseMinor=null)else it}).budgetPace(LocalDate.parse("2026-10-15")).projectedMinor)
    }
}
