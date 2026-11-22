package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ActivityPlanningReviewTest {
    private val fixture=CashProjectionFixture()
    private val activity=ActivityEntity("b","trip","旅行","2026-10-01","2026-10-31",null)
    private val s=fixture.snapshot().copy(activities=listOf(activity),features=BookFeaturesEntity("b",true))
    private val amount=Money(Currency.JPY,1000)
    private val input=ReserveInput(activity.id,"交通","2026-10-10",amount,fixture.bank.id,amount)

    @Test(expected=IllegalArgumentException::class) fun sameCurrencyReserveRejectsDifferentAmounts() {
        s.prepareReserve(input.copy(accountAmount=Money(Currency.JPY,2000)),"reserve")
    }

    @Test(expected=IllegalArgumentException::class) fun cashReserveRejectsDebtAccount() {
        s.prepareReserve(input.copy(accountId=fixture.card.id),"reserve")
    }

    @Test fun movingReserveShowsBothMonthlyBudgetChanges() {
        val old=s.prepareReserve(input,"reserve").reserve
        val review=s.copy(reserves=listOf(old)).prepareReserve(input.copy(id=old.id,date="2026-11-10"),old.id)
        assertEquals(listOf("2026-10","2026-11"),review.months.map{it.first.month})
        assertEquals(listOf(1000L to 0L,0L to 1000L),review.months.map{it.first.reserveMinor to it.second.reserveMinor})
    }

    @Test fun endingKeepsInPeriodTasksAndWithdrawsOutsideTasksWithoutRegeneratingThem() {
        val rule=RecurrenceEntity("b","stay","住宿",fixture.bank.id,"cat","JPY",1000,1000,false,"MONTHLY","2026-09-10",null,activityId=activity.id)
        val open=s.copy(recurrences=listOf(rule))
        val from=LocalDate.parse("2026-09-01")
        val through=LocalDate.parse("2026-11-30")
        val tasks=open.forecastOccurrences(from,through)
        val review=open.copy(occurrences=tasks).prepareActivity(ActivityInput(activity.name,activity.startDate,activity.endDate,null,ended=true,id=activity.id),activity.id)
        assertEquals(listOf("2026-09-10","2026-11-10"),review.withdrawn.map{it.scheduledDate})
        assertTrue(review.withdrawn.all{it.status=="WITHDRAWN"})
        val closed=open.copy(activities=listOf(review.activity))
        assertEquals(listOf("2026-10-10"),closed.forecastOccurrences(from,through.plusMonths(1)).map{it.scheduledDate})
        assertEquals("2026-10-10",closed.copy(occurrences=tasks).pendingOccurrences().single().scheduledDate)
    }
}
