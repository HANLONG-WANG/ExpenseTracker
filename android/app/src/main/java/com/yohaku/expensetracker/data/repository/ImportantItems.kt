package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.finance.*
import kotlinx.serialization.json.Json

data class ImportantPayment(val item:CashTimelineItem,val outstandingMinor:Long?,val confirmed:Boolean)
data class FinancialDeadline(val accountId:String,val accountName:String,val currency:String,val planName:String,
    val billDate:String,val outstandingMinor:Long,val deadline:ChangeDeadline)

/** Payment already recorded but awaiting clearing remains an obligation, even with no more cash needed. */
fun BookSnapshot.importantPayments(f:FinancialGraph,timeline:CashTimeline):List<ImportantPayment> = timeline.steps
    .map{it.item}.filter{it.kind in listOf("BILL","RECURRENCE")}.map{item->
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
