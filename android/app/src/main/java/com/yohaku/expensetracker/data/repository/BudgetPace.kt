package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.data.db.FinancialGraph
import java.time.LocalDate
import com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.math.RoundingMode

data class BudgetPace(val sampleStart:String,val asOf:String,val sampleDays:Int,val remainingDays:Int,
    val actualSpentMinor:Long,val dailySampleMinor:Long,val futureDailyMinor:Long,val knownFutureMinor:Long?,val limitMinor:Long?) {
    val projectedMinor get()=knownFutureMinor?.let{Math.addExact(actualSpentMinor,Math.addExact(futureDailyMinor,it))}
    val lowConfidence get()=sampleDays<7
    val actualOverMinor get()=limitMinor?.let{(actualSpentMinor-it).coerceAtLeast(0)}
    val projectedOverMinor get()=limitMinor?.let{limit->projectedMinor?.let{(it-limit).coerceAtLeast(0)}}
}

/** Calendar-day pace of daily net consumption; activity, fixed spending and fees are not extrapolated. */
fun BookSnapshot.budgetPace(asOf:LocalDate,finance:FinancialGraph?=null,referenceRates:List<ReferenceRateSnapshot> = emptyList()):BudgetPace {
    val today=asOf.toString();val month=YearMonth.from(asOf);val end=month.atEndOfMonth().toString()
    val start=maxOf(month.atDay(1),LocalDate.parse(book.startDate)).coerceAtMost(asOf)
    val days=ChronoUnit.DAYS.between(start,asOf).toInt()+1
    val current=copy(events=events.filter{it.date<=today},settlements=settlements.map{if(it.originalEventId!=null&&it.settlementDate>today)it.copy(confirmed=false)else it},expenseComponents=expenseComponents.filter{it.date<=today})
    val spent=current.budgetProjection(month.toString()).spentMinor
    fun rate(from:String)=referenceRates.filter{it.base.name==from&&it.quote.name==book.currency&&it.date<=today}.maxByOrNull{it.date}
    val planned=copy(occurrences=forecastOccurrences(asOf,month.atEndOfMonth()).map{o->if(o.baseMinor==null&&o.amountMinor!=null)rate(o.currency)?.let{q->o.copy(baseMinor=q.convert(Money(Currency.valueOf(o.currency),o.amountMinor)).minor,estimated=true)}?:o else o})
    val recurringIds=planned.occurrences.mapNotNull{it.eventId}.toSet()
    val activityIds=eventActivities.filter{link->availableActivities.any{it.id==link.activityId}}.map{it.eventId}.toSet()
    val daily=current.visibleEvents.filter{it.kind==EntryKind.EXPENSE.name&&it.date in start.toString()..today&&it.id !in recurringIds&&it.id !in activityIds}.fold(0L){sum,e->Math.addExact(sum,current.netExpense(e))}
    val remaining=month.lengthOfMonth()-asOf.dayOfMonth
    val futureDaily=daily.toBigDecimal().multiply(remaining.toBigDecimal()).divide(days.toBigDecimal(),0,RoundingMode.HALF_UP).longValueExact()
    val fixed=planned.budgetProjection(month.toString()).reserveMinor
    val futureEvents=visibleEvents.filter{it.kind==EntryKind.EXPENSE.name&&it.date>today&&it.date<=end&&!excludedFromMonthly(it)}.fold(0L){sum,e->Math.addExact(sum,netExpense(e))}
    val futureFees=visibleComponents().filter{it.date>today&&it.date<=end&&visibleEvents.none{e->e.id==it.eventId&&excludedFromMonthly(e)}}.fold(0L){sum,c->Math.addExact(sum,c.baseMinor)}
    val fees=finance?.bills?.filter{it.active&&it.date<=end&&finance.plans.any{p->p.id==it.planId&&p.active}}?.map{b->
        val plan=finance.plans.first{it.id==b.planId};val fee=billOutstanding(b).feeMinor
        if(fee==0L)0L else fee?.let{n->if(plan.currency==book.currency)n.coerceAtLeast(0)else rate(plan.currency)?.convert(Money(Currency.valueOf(plan.currency),n.coerceAtLeast(0)))?.minor}
    }.orEmpty()
    val known=if(fixed==null||fees.any{it==null})null else Math.addExact(Math.addExact(fixed,futureEvents),Math.addExact(futureFees,fees.filterNotNull().fold(0L,Math::addExact)))
    return BudgetPace(start.toString(),today,days,remaining,spent,daily,futureDaily,known,budgetLimit(month.toString()))
}
