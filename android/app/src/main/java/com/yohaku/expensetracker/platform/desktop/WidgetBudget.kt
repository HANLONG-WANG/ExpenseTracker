package com.yohaku.expensetracker.platform.desktop

import com.yohaku.expensetracker.data.repository.*
import java.time.YearMonth
import com.yohaku.expensetracker.domain.model.Currency
import java.time.LocalDate

enum class WidgetBudgetState { NORMAL, NO_BUDGET, OVER_BUDGET }
data class WidgetBudget(
    val state:WidgetBudgetState,val currency:Currency,val amountMinor:Long,
    val limitMinor:Long?,val spentMinor:Long,val availableMinor:Long?,val dailyMinor:Long?,val daysLeft:Int
)
internal fun widgetBudget(snapshot:BookSnapshot,today:LocalDate):WidgetBudget {
    val month=YearMonth.from(today)
    val planned=snapshot.copy(occurrences=snapshot.forecastOccurrences(month.atDay(1),month.atEndOfMonth()))
    return widgetBudget(planned.budgetProjection(month.toString()),today)
}

/** 所有值均为币种最小单位；含今天，非负日均用整数除法向下取整。 */
fun widgetBudget(projection:BudgetProjection,today:LocalDate):WidgetBudget {
    val days=today.lengthOfMonth()-today.dayOfMonth+1
    val remaining=projection.remainingMinor
    val state=when {remaining==null->WidgetBudgetState.NO_BUDGET;remaining<0->WidgetBudgetState.OVER_BUDGET;else->WidgetBudgetState.NORMAL}
    return WidgetBudget(state,projection.currency,when(state) {
        WidgetBudgetState.NO_BUDGET->projection.spentMinor
        WidgetBudgetState.OVER_BUDGET->-remaining!!
        WidgetBudgetState.NORMAL->remaining!!
    },projection.limitMinor,projection.spentMinor,projection.availableMinor,projection.availableMinor?.coerceAtLeast(0)?.div(days),days)
}
