package com.yohaku.expensetracker

import com.yohaku.expensetracker.domain.budget.recurrenceDates
import com.yohaku.expensetracker.domain.model.RecurrenceFrequency
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Test
import org.junit.Assert.*

class RecurrenceDatesTest {
    @Test fun naturalWeeklyMonthlyAndYearlyDatesKeepTheirAnchor() {
        fun dates(anchor:String,frequency:RecurrenceFrequency,month:String)=recurrenceDates(LocalDate.parse(anchor),null,frequency,YearMonth.parse(month)).map{it.toString()}
        assertEquals(listOf("2026-02-28"),dates("2026-01-31",RecurrenceFrequency.MONTHLY,"2026-02"))
        assertEquals(listOf("2026-03-31"),dates("2026-01-31",RecurrenceFrequency.MONTHLY,"2026-03"))
        assertEquals(listOf("2026-01-05","2026-01-12","2026-01-19","2026-01-26"),dates("2026-01-05",RecurrenceFrequency.WEEKLY,"2026-01"))
        assertEquals(listOf("2025-02-28"),dates("2024-02-29",RecurrenceFrequency.YEARLY,"2025-02"))
        assertEquals(listOf("2028-02-29"),dates("2024-02-29",RecurrenceFrequency.YEARLY,"2028-02"))
    }
}
