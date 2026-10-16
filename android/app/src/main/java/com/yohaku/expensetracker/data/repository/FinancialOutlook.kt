package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*

data class CashAccountRisk(val opening:CashAccountOpening,val nextDate:String?,val nextNeedMinor:Long?,val currentGapMinor:Long?,
    val maximumGapMinor:Long?,val includingIncomeGapMinor:Long?,val firstRiskDate:String?,val firstRiskItemId:String?,val incompleteDate:String?,
    val estimated:Boolean,val includingEstimated:Boolean)

/** A maximum needs the whole prefix to be known; an already observed deficit is still reported. */
fun CashTimeline.accountRisks():List<CashAccountRisk> = openings.map{opening->
    val rows=steps.filter{it.item.accountId==opening.account.id}
    val debits=rows.filter{!it.item.incoming&&it.item.accountDeltaMinor!=0L}
    val next=debits.firstOrNull()?.item?.date
    val due=debits.filter{it.item.date==next}
    val need=if(due.any{it.item.accountDeltaMinor==null})null else due.fold(0L){n,row->Math.subtractExact(n,row.item.accountDeltaMinor!!)}
    fun gap(income:Boolean):Long? {
        val values=rows.map{if(income)it.includingIncomeAfterMinor else it.baselineAfterMinor}
        if(values.any{it==null})return null
        return Math.negateExact(minOf(0L,opening.actualMinor,values.filterNotNull().minOrNull()?:opening.actualMinor))
    }
    val first=rows.firstOrNull{it.baselineAfterMinor?.let{n->n<0}==true}
    CashAccountRisk(opening,next,need,need?.let{Math.subtractExact(it,opening.actualMinor).coerceAtLeast(0)},gap(false),gap(true),
        if(opening.actualMinor<0)asOf else first?.item?.date,first?.item?.id,rows.firstOrNull{it.baselineAfterMinor==null}?.item?.date,
        rows.any{it.baselineEstimated},rows.any{it.includingEstimated})
}

data class FinancialAttention(val id:String,val kind:String,val accountId:String?,val sourceId:String,val reason:String)
fun BookSnapshot.financialAttention(f:FinancialGraph,asOf:String,timeline:CashTimeline):List<FinancialAttention> = buildList {
    for(a in accounts.filter{AccountType.valueOf(it.type).debt}) {
        val summary=financialAccountSummary(f,a.id,asOf)
        if(summary.unplanned.isNotEmpty()||summary.unassignedOpeningMinor!=0L||summary.reconciliationRemainders.isNotEmpty())add(FinancialAttention("unplanned:${a.id}","ACCOUNT",a.id,a.id,"UNPLANNED_DEBT"))
        val pending=repayments.filter{it.debtAccountId==a.id&&!it.platformConfirmed&&visibleEvents.any{e->e.id==it.eventId}}
        pending.forEach{add(FinancialAttention("clearing:${it.eventId}","RECORD",a.id,it.eventId,"PENDING_CLEARING"))}
        if(unallocatedRepaymentPrincipal(a.id)>0)add(FinancialAttention("unallocated:${a.id}","ACCOUNT",a.id,a.id,"UNALLOCATED_CLEARING"))
    }
    expectedIncomes.filter{expectedIncomeNeedsReview(it)}.forEach{add(FinancialAttention("income-review:${it.id}","EXPECTED_INCOME",it.accountId,it.id,"INCOME_RECEIPT_CHANGED"))}
    timeline.steps.filter{it.item.accountId==null||it.item.accountDeltaMinor==null}.forEach{row->
        val item=row.item
        add(FinancialAttention(item.id,item.kind,item.accountId,item.sourceId,item.issue?:"UNKNOWN_AMOUNT"))
    }
    for(row in appliedRepaymentAllocations().filter{it.billId!=null}) {
        val bill=f.bills.firstOrNull{it.id==row.billId}?:continue
        val payment=repayments.first{it.eventId==row.eventId}
        if(payment.clearingDate!!>asOf&&payment.clearingDate>bill.date)add(FinancialAttention("late:${row.id}","RECORD",payment.debtAccountId,payment.eventId,"CLEARING_AFTER_DUE"))
    }
}.distinctBy{it.id}
