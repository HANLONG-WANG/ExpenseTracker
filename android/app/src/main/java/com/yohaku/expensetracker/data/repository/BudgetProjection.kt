package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import java.time.YearMonth

data class BudgetProjection(val month:String,val currency:Currency,val limitMinor:Long?,val spentMinor:Long,val allExpenseMinor:Long,
    val independentMinor:Long,val reserveMinor:Long?,val pendingCount:Int,val fixedReserveMinor:Long?=reserveMinor,val activityReserveMinor:Long=0,val fundReserveMinor:Long=0) {
    val remainingMinor get()=limitMinor?.let{Math.subtractExact(it,spentMinor)}
    val availableMinor get()=remainingMinor?.let{remaining->reserveMinor?.let{Math.subtractExact(remaining,it)}}
}
fun BookSnapshot.excludedFromMonthly(event:EventEntity):Boolean {
    val activityId=eventActivities.find{it.eventId==event.id}?.activityId
    return activities.any{it.id==activityId&&it.excludeMonthly}
}
fun BookSnapshot.budgetLimit(month:String,categoryId:String?=null):Long? {
    YearMonth.parse(month)
    val scope=categoryId.orEmpty()
    val latest=budgets.filter{it.scope==scope&&it.month<=month}.maxByOrNull{it.month}
    if(latest!=null)return latest.amountMinor
    return if(categoryId==null&&month>=book.startDate.take(7))book.budgetMinor else null
}
fun BookSnapshot.budgetProjection(month:String,categoryId:String?=null):BudgetProjection {
    YearMonth.parse(month)
    val ids=categoryId?.let{id->setOf(id)+categories.filter{it.parentId==id}.map{it.id}}
    val expenses=visibleEvents.filter{it.kind==EntryKind.EXPENSE.name&&it.date.startsWith(month)&&(ids==null||it.categoryId in ids)}
    val all=expenses.fold(0L){sum,e->Math.addExact(sum,netExpense(e))}+
        if(ids==null)visibleComponents().filter{it.date.startsWith(month)}.sumOf{it.baseMinor}else 0L
    val independent=expenses.filter{excludedFromMonthly(it)}.fold(0L){sum,e->Math.addExact(sum,netExpense(e))}+
        if(ids==null)visibleComponents().filter{it.date.startsWith(month)&&visibleEvents.any{e->e.id==it.eventId&&excludedFromMonthly(e)}}.sumOf{it.baseMinor}else 0L
    val pending=pendingOccurrences().filter{o->o.scheduledDate.startsWith(month)&&(ids==null||o.categoryId in ids)&&!activities.any{it.id==o.activityId&&it.excludeMonthly}}
    val fixed=if(pending.any{it.baseMinor==null})null else pending.fold(0L){sum,o->Math.addExact(sum,o.baseMinor!!)}
    val activityReserve=if(categoryId!=null)0L else reserves.filter{r->r.date.startsWith(month)&&activities.any{it.id==r.activityId&&!it.excludeMonthly&&!it.ended}}.fold(0L){sum,r->
        val duplicate=pending.firstOrNull{it.id==r.occurrenceId}?.baseMinor?:0L
        Math.addExact(sum,(reserveRemaining(r)-duplicate).coerceAtLeast(0))
    }
    val reserve=fixed?.let{Math.addExact(it,activityReserve)}
    val fund=reserves.fold(0L){sum,r->Math.addExact(sum,reserveRemaining(r))}
    return BudgetProjection(month,Currency.valueOf(book.currency),budgetLimit(month,categoryId),Math.subtractExact(all,independent),all,independent,reserve,pending.size,fixed,activityReserve,fund)
}

fun BookSnapshot.provisionalExpenseCount(month:String):Int {
    val ids=visibleEvents.filter{it.kind==EntryKind.EXPENSE.name&&it.date.startsWith(month)&&!excludedFromMonthly(it)}.map{it.id}.toSet()
    return settlements.count{it.eventId in ids&&!it.confirmed}
}
