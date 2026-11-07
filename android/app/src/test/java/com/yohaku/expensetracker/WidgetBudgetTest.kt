package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.repository.BudgetProjection
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.platform.desktop.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WidgetBudgetTest {
    private val today=LocalDate.of(2026,11,7)
    private fun projection(limit:Long?=180000,spent:Long=90360,reserve:Long?=40000,currency:Currency=Currency.JPY)=
        BudgetProjection("2026-11",currency,limit,spent,spent,0,reserve,0)
    @Test fun normalMatchesHomeAndIncludesToday() {
        val m=widgetBudget(projection(),today)
        assertEquals(WidgetBudgetState.NORMAL,m.state);assertEquals(89640,m.amountMinor)
        assertEquals(49640L,m.availableMinor);assertEquals(24,m.daysLeft);assertEquals(2068L,m.dailyMinor)
    }
    @Test fun noBudgetShowsSpent() {val m=widgetBudget(projection(limit=null),today);assertEquals(WidgetBudgetState.NO_BUDGET,m.state);assertEquals(90360,m.amountMinor);assertNull(m.dailyMinor)}
    @Test fun overspentShowsPositiveExcess() {val m=widgetBudget(projection(spent=192400),today);assertEquals(WidgetBudgetState.OVER_BUDGET,m.state);assertEquals(12400,m.amountMinor)}
    @Test fun unknownReserveKeepsUnknownAvailability() {val m=widgetBudget(projection(reserve=null),today);assertNull(m.availableMinor);assertNull(m.dailyMinor);assertEquals(89640,m.amountMinor)}
    @Test fun dailyFloorsMinorUnitsAndClampsNegativeAvailability() {
        assertEquals(4L,widgetBudget(projection(100,0,0,Currency.CNY),today).dailyMinor)
        val negative=widgetBudget(projection(100,90,20),today)
        assertEquals(WidgetBudgetState.NORMAL,negative.state);assertEquals(-10L,negative.availableMinor);assertEquals(0L,negative.dailyMinor)
    }
}
