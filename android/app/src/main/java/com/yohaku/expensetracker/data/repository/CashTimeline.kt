package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.lang.Math.addExact
import java.lang.Math.subtractExact

/** Original currency is retained even when the debit-account conversion is not known. */
data class CashTimelineItem(val id:String,val kind:String,val sourceId:String,val date:String,val name:String,
    val accountId:String?,val currency:String,val amountMinor:Long?,val incoming:Boolean,
    val accountDeltaMinor:Long?,val estimated:Boolean=false,val issue:String?=null,val scheduledDate:String=date,val referenceRate:ReferenceRateSnapshot?=null)
data class CashTimelineStep(val item:CashTimelineItem,val baselineAfterMinor:Long?,val includingIncomeAfterMinor:Long?,val baselineEstimated:Boolean=false,val includingEstimated:Boolean=false)
data class CashAccountOpening(val account:AccountEntity,val actualMinor:Long)
data class CashTimeline(val asOf:String,val through:String,val openings:List<CashAccountOpening>,val steps:List<CashTimelineStep>)

/** Physical cash as of a date; confirmed future postings enter the timeline exactly once. */
fun BookSnapshot.cashBalanceAt(account:AccountEntity,date:String):Long = postings.filter{it.accountId==account.id&&it.date<=date}
    .fold(if(account.openingDate<=date)account.openingMinor else 0L){sum,p->addExact(sum,p.deltaMinor)}

fun BookSnapshot.cashTimeline(finance:FinancialGraph?,asOf:LocalDate,through:LocalDate,stalePlanIds:Set<String> = emptySet(),referenceRates:List<ReferenceRateSnapshot> = emptyList()):CashTimeline {
    val today=asOf.toString();val end=through.toString()
    val funds=accounts.filter{!AccountType.valueOf(it.type).debt}
    val fundIds=funds.map{it.id}.toSet();val live=visibleEvents.associateBy{it.id}
    val items=mutableListOf<CashTimelineItem>()
    fun add(item:CashTimelineItem){val next=item.copy(date=maxOf(item.date,today));if(next.date<=end)items+=next}
    for(a in funds.filter{it.openingDate>today})add(CashTimelineItem("opening:${a.id}","OPENING",a.id,a.openingDate,a.name,a.id,a.currency,kotlin.math.abs(a.openingMinor),a.openingMinor>=0,a.openingMinor))
    for(p in storedPostings.filter{it.eventId in live&&it.accountId in fundIds&&(it.date>today||!it.confirmed)}) {
        val e=live.getValue(p.eventId)
        add(CashTimelineItem("posting:${p.id}",if(transfers.any{it.eventId==e.id})"TRANSFER"else "ACTUAL",e.id,p.date,e.merchant.ifBlank{e.note.ifBlank{e.kind}},p.accountId,p.currency,kotlin.math.abs(p.deltaMinor),p.deltaMinor>0,p.deltaMinor,!p.confirmed,if(!p.confirmed)"PENDING_POSTING"else null))
    }
    val pending=copy(occurrences=forecastOccurrences(asOf,through)).pendingOccurrences()
    for(o in pending) {
        val a=accounts.first{it.id==o.accountId}
        add(CashTimelineItem("occurrence:${o.id}","RECURRENCE",o.id,o.scheduledDate,o.name,o.accountId,o.currency,o.amountMinor,false,
            o.amountMinor?.takeIf{a.id in fundIds&&a.active&&a.currency==o.currency}?.let{Math.negateExact(it)},o.estimated,
            when{a.id !in fundIds->"DEBT_PAYMENT_UNSCHEDULED";!a.active->"PAYMENT_ACCOUNT_INACTIVE";o.amountMinor==null->"UNKNOWN_AMOUNT";a.currency!=o.currency->"UNKNOWN_CONVERSION";else->null}))
    }
    for(r in reserves.filter{it.active&&reserveRemaining(it)>0&&activities.any{a->a.id==it.activityId&&!a.ended}}) {
        val amount=reserveAccountRemaining(r)
        // The linked pending fixed payment already consumes this portion of this same cash pool.
        val duplicate=pending.firstOrNull{it.id==r.occurrenceId&&it.accountId==r.accountId&&it.currency==amount?.currency?.name}
        val held=amount?.minor?.let{n->duplicate?.amountMinor?.let{(n-it).coerceAtLeast(0)}?:n}
        if(held==0L)continue
        val issue=when{r.accountId !in fundIds->"RESERVE_ACCOUNT_REQUIRED";accounts.first{it.id==r.accountId}.active.not()->"PAYMENT_ACCOUNT_INACTIVE";amount==null->"UNKNOWN_CONVERSION";duplicate!=null&&duplicate.amountMinor==null->"UNKNOWN_AMOUNT";else->null}
        add(CashTimelineItem("reserve:${r.id}","RESERVE",r.activityId,r.date,r.name,r.accountId,amount?.currency?.name?:book.currency,held?:reserveRemaining(r),false,if(issue==null)held?.let{Math.negateExact(it)}else null,true,issue))
    }
    if(finance!=null)for(b in finance.bills.filter{it.active&&finance.plans.any{p->p.id==it.planId&&p.active}}) {
        val outstanding=billOutstanding(b)
        if(outstanding.totalMinor<=0)continue
        val plan=finance.plans.first{it.id==b.planId}
        val waiting=repayments.filter{!it.platformConfirmed&&it.eventId in live}.map{it.eventId}.toSet()
        val allocations=repaymentAllocations.filter{it.billId==b.id&&it.eventId in waiting}
        val unknown=allocations.any{it.principalMinor==null||it.interestMinor==null||it.feeMinor==null}
        val paidWaiting=if(unknown)0 else allocations.fold(0L){sum,a->addExact(sum,addExact(a.principalMinor!!,addExact(a.interestMinor!!,a.feeMinor!!)))}
        val remaining=(subtractExact(outstanding.totalMinor,paidWaiting)).coerceAtLeast(0)
        val account=accounts.firstOrNull{it.id==b.paymentAccountId}
        val rate=account?.takeIf{it.currency!=plan.currency}?.let{a->referenceRates.filter{it.base.name==plan.currency&&it.quote.name==a.currency&&it.date<=today}.maxByOrNull{it.date}}
        val converted=if(account?.currency==plan.currency)remaining else rate?.convert(Money(Currency.valueOf(plan.currency),remaining))?.minor
        val issue=when{!b.confirmed&&b.planId in stalePlanIds->"STALE_PLAN";unknown->"PENDING_CLEARING_AMOUNT";account==null||account.id !in fundIds->"PAYMENT_ACCOUNT_REQUIRED";!account.active->"PAYMENT_ACCOUNT_INACTIVE";account.currency!=plan.currency&&rate==null->"UNKNOWN_CONVERSION";allocations.isNotEmpty()->"PENDING_CLEARING";rate!=null->"REFERENCE_CONVERSION";else->null}
        add(CashTimelineItem("bill:${b.id}","BILL",plan.accountId,b.date,plan.name,b.paymentAccountId,plan.currency,if(unknown)null else remaining,false,
            if(issue in listOf(null,"PENDING_CLEARING","REFERENCE_CONVERSION"))converted?.let{Math.negateExact(it)}else null,!b.confirmed||rate!=null,issue,referenceRate=rate))
    }
    for(e in expectedIncomes.filter{it.status=="PENDING"}) {
        val a=accounts.first{it.id==e.accountId}
        val savedRate=e.accountEstimateJson?.let{Json.decodeFromString<List<ReferenceRateSnapshot>>(it).single()}
        val rate=if(e.accountMinor!=null)savedRate else referenceRates.filter{it.base.name==e.currency&&it.quote.name==a.currency&&it.date<=today}.maxByOrNull{it.date}
        val converted=e.accountMinor?:rate?.convert(Money(Currency.valueOf(e.currency),e.amountMinor))?.minor
        add(CashTimelineItem("income:${e.id}","EXPECTED_INCOME",e.id,e.date,e.name,e.accountId,e.currency,e.amountMinor,true,
            if(!a.active)null else if(e.currency==a.currency)e.amountMinor else converted,true,if(!a.active)"PAYMENT_ACCOUNT_INACTIVE"else if(e.currency!=a.currency&&converted==null)"UNKNOWN_CONVERSION"else if(rate!=null)"REFERENCE_CONVERSION"else null,referenceRate=rate))
    }
    val opening=funds.map{CashAccountOpening(it,cashBalanceAt(it,today))}
    val baseline=opening.associate{it.account.id to (it.actualMinor as Long?)}.toMutableMap()
    val including=baseline.toMutableMap()
    // Dates have no intra-day bank execution order. Debits precede credits conservatively.
    val sorted=items.sortedWith(compareBy<CashTimelineItem>{it.date}.thenBy{if(it.incoming)1 else 0}.thenBy{it.id})
    val baseEstimated=mutableSetOf<String>();val includingEstimated=mutableSetOf<String>()
    val steps=sorted.map{item->
        if(item.accountId in fundIds) {
            val id=item.accountId!!
            if(item.estimated||item.referenceRate!=null){includingEstimated+=id;if(item.kind!="EXPECTED_INCOME")baseEstimated+=id}
            if(item.kind!="EXPECTED_INCOME")baseline[id]=baseline[id]?.let{n->item.accountDeltaMinor?.let{addExact(n,it)}}
            including[id]=including[id]?.let{n->item.accountDeltaMinor?.let{addExact(n,it)}}
        }
        CashTimelineStep(item,baseline[item.accountId],including[item.accountId],item.accountId in baseEstimated,item.accountId in includingEstimated)
    }
    return CashTimeline(today,end,opening,steps)
}
