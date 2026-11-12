package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.finance.*
import kotlinx.serialization.json.Json

data class ImportantPayment(val item:CashTimelineItem,val outstandingMinor:Long?,val confirmed:Boolean)
data class ImportantBillGroupKey(val sourceId:String,val scheduledDate:String,val accountId:String?,val currency:String) {
    val stableKey:String get()="$sourceId:$scheduledDate:${accountId?:"none"}:$currency"
}
data class ImportantBillGroup(val key:ImportantBillGroupKey,val payments:List<ImportantPayment>) {
    val item:CashTimelineItem get()=payments.first().item
    val count:Int get()=payments.size
    val confirmedCount=payments.count{it.confirmed}
    val predictedCount=count-confirmedCount
    val hasUnconfirmed=predictedCount>0
    val outstandingMinor=payments.map{it.outstandingMinor}.knownTotal()
    val cashRequiredMinor=payments.map{it.item.amountMinor}.knownTotal()
    val issues=payments.mapNotNull{it.item.issue}.distinct()
    val estimated=payments.any{it.item.estimated}||issues.isNotEmpty()
}

private fun List<Long?>.knownTotal():Long? = fold(0L as Long?){total,amount->
    if(total==null||amount==null)null else Math.addExact(total,amount)
}

/** Presentation/reminder groups only; the underlying obligations and cash timeline stay per bill. */
fun List<ImportantPayment>.billGroups():List<ImportantBillGroup> = filter{it.item.kind=="BILL"}
    .groupBy{val i=it.item;ImportantBillGroupKey(i.sourceId,i.scheduledDate,i.accountId,i.currency)}
    .map{(key,payments)->ImportantBillGroup(key,payments)}

data class FinancialDeadline(val accountId:String,val accountName:String,val currency:String,val planName:String,
    val billDate:String,val outstandingMinor:Long,val deadline:ChangeDeadline)

/** Payment already recorded but awaiting clearing remains an obligation, even with no more cash needed. */
fun BookSnapshot.importantPayments(f:FinancialGraph,timeline:CashTimeline):List<ImportantPayment> = timeline.steps
    .map{it.item}.filter{it.kind in listOf("BILL","RECURRENCE","CARD_RECURRENCE")}.map{item->
        val bill=if(item.kind=="BILL")f.bills.first{it.id==item.id.removePrefix("bill:")}else null
        val outstanding=bill?.let{billOutstanding(it).totalMinor}?:item.amountMinor
        ImportantPayment(item,outstanding,bill?.confirmed==true)
    }

/** The effective version replaces its earlier deadlines; paid or retired obligations disappear. */
fun BookSnapshot.financialDeadlines(f:FinancialGraph,asOf:String,through:String):List<FinancialDeadline> {
    val latest=f.rules.filter{it.effectiveFrom<=asOf}.groupBy{it.accountId to it.mode}.values
        .map{it.maxWith(compareBy<FinancialRuleEntity>{it.effectiveFrom}.thenBy{it.version})}
        .sortedWith(compareByDescending<FinancialRuleEntity>{it.effectiveFrom}.thenByDescending{it.version})
    return latest.flatMap{row->
        val account=accounts.first{it.id==row.accountId}
        Json.decodeFromString<FinancialRuleVersion>(row.json).deadlines.mapNotNull{deadline->
            val bill=f.bills.firstOrNull{it.id==deadline.billId&&it.active&&f.plans.any{p->p.id==it.planId&&p.active&&p.accountId==row.accountId}}?:return@mapNotNull null
            val outstanding=billOutstanding(bill)
            if(outstanding.totalMinor<=0||outstanding.principalMinor==0L||deadline.date>through)return@mapNotNull null
            FinancialDeadline(account.id,account.name,account.currency,f.plans.first{it.id==bill.planId}.name,bill.date,outstanding.totalMinor,deadline)
        }
    }.distinctBy{it.deadline.billId to it.deadline.changeType}.sortedBy{it.deadline.instant()}
}
