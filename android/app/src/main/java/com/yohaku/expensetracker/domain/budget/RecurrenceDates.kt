package com.yohaku.expensetracker.domain.budget

import com.yohaku.expensetracker.domain.model.RecurrenceFrequency
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Month-end anchors retain their original day; a February clamp does not shift March. */
fun recurrenceDates(anchor:LocalDate,end:LocalDate?,frequency:RecurrenceFrequency,month:YearMonth):List<LocalDate> {
    val first=month.atDay(1);val last=month.atEndOfMonth()
    val dates=when(frequency) {
        RecurrenceFrequency.MONTHLY->listOf(month.atDay(anchor.dayOfMonth.coerceAtMost(month.lengthOfMonth())))
        RecurrenceFrequency.YEARLY->if(month.month==anchor.month)listOf(month.atDay(anchor.dayOfMonth.coerceAtMost(month.lengthOfMonth())))else emptyList()
        RecurrenceFrequency.WEEKLY->{
            val days=ChronoUnit.DAYS.between(anchor,first)
            var date=anchor.plusWeeks(if(days<=0)0 else (days+6)/7)
            buildList{while(date<=last){add(date);date=date.plusWeeks(1)}}
        }
    }
    return dates.filter{it>=anchor&&it>=first&&it<=last&&(end==null||it<=end)}
}
