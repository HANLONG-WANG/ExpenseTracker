package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import java.lang.Math.addExact
import java.lang.Math.subtractExact

/** Bill month follows its effective debit date, never the purchase's calendar month. */
data class FinancialStatementRow(val plan:FinancialPlanEntity,val bill:FinancialBillEntity,val outstanding:FinancialBillOutstanding,
    val pendingPrincipalMinor:Long?,val pendingFeeMinor:Long?,val pendingTotalMinor:Long?,val cashRequiredMinor:Long?)
data class FinancialStatementTotals(val principalMinor:Long?,val feeMinor:Long?,val totalMinor:Long,val pendingMinor:Long?,val cashRequiredMinor:Long?,val confirmed:Int,val predicted:Int)
data class FinancialReconciliationRemainder(val planId:String,val principalMinor:Long)
data class FinancialAccountSummary(val rows:List<FinancialStatementRow>,val unplanned:List<EventEntity>,val unassignedOpeningMinor:Long?,val nextDate:String?,val next:List<FinancialStatementRow>,val overdue:List<FinancialStatementRow>,val reconciliationRemainders:List<FinancialReconciliationRemainder> = emptyList())
fun Iterable<Long?>.knownTotal():Long? {var value=0L;for(n in this)value=addExact(value,n?:return null);return value}
fun List<FinancialStatementRow>.statementTotals()=FinancialStatementTotals(map{it.outstanding.principalMinor}.knownTotal(),map{it.outstanding.feeMinor}.knownTotal(),fold(0L){n,r->addExact(n,r.outstanding.totalMinor)},map{it.pendingTotalMinor}.knownTotal(),map{it.cashRequiredMinor}.knownTotal(),count{it.bill.confirmed},count{!it.bill.confirmed})
fun BookSnapshot.financialAccountSummary(finance:FinancialGraph,accountId:String,asOf:String,stalePlanIds:Set<String> = emptySet()):FinancialAccountSummary {
    val account=accounts.first{it.id==accountId}
    val plans=finance.plans.filter{it.accountId==accountId&&it.active}
    val live=visibleEvents.associateBy{it.id}
    val waiting=repayments.filter{!it.platformConfirmed&&it.eventId in live}.map{it.eventId}.toSet()
    val rows=finance.bills.filter{it.active&&plans.any{p->p.id==it.planId}}.map{b->
        val remaining=billOutstanding(b)
        val pending=repaymentAllocations.filter{it.billId==b.id&&it.eventId in waiting}
        val principal=pending.map{it.principalMinor}.knownTotal()
        val fee=pending.map{p->if(p.interestMinor==null||p.feeMinor==null)null else addExact(p.interestMinor,p.feeMinor)}.knownTotal()
        val total=if(principal==null||fee==null)null else addExact(principal,fee)
        FinancialStatementRow(plans.first{it.id==b.planId},b,remaining,principal,fee,total,total?.takeUnless{!b.confirmed&&b.planId in stalePlanIds}?.let{subtractExact(remaining.totalMinor,it).coerceAtLeast(0)})
    }.sortedWith(compareBy<FinancialStatementRow>{it.bill.date}.thenBy{it.plan.name}.thenBy{it.bill.number})
    val sources=plans.mapNotNull{it.sourceEventId}.toSet()+plans.filter{it.origin=="POOL"}.flatMap{poolInput(it)?.facts?.sources.orEmpty().mapNotNull{source->source.eventId}}

    val unplanned=visibleEvents.filter{it.accountId==accountId&&it.kind in listOf(EntryKind.EXPENSE.name,EntryKind.BORROWING.name)&&!it.historicalIncluded&&it.id !in sources&&financialSourcePrincipal(it.id,accountId)?.minor?.let{n->n>0}==true}
    val opening=finance.plans.filter{it.accountId==accountId&&it.origin=="OPENING"&&(it.active||finance.arrangements.any{a->a.originalPlanId==it.id})&&finance.arrangements.none{a->a.targetPlanId==it.id}}.map{it.principalMinor}.knownTotal()
    val poolOpening=plans.filter{it.origin=="POOL"}.mapNotNull{poolInput(it)?.openingMinor}.fold(0L,::addExact)
    val unassigned=opening?.let{subtractExact(account.openingMinor,addExact(it,poolOpening))}
    val unpaid=rows.filter{it.outstanding.totalMinor>0&&(it.cashRequiredMinor==null||it.cashRequiredMinor>0)}
    val nextDate=unpaid.minOfOrNull{it.bill.date}
    val remainders=finance.plans.filter{it.accountId==accountId}.mapNotNull{p->p.reconciliation()?.unassignedOriginalMinor?.takeIf{it>0}?.let{FinancialReconciliationRemainder(p.id,it)}}
    return FinancialAccountSummary(rows,unplanned,unassigned,nextDate,unpaid.filter{it.bill.date==nextDate},rows.filter{it.bill.date<asOf&&it.outstanding.totalMinor>0},remainders)
}
