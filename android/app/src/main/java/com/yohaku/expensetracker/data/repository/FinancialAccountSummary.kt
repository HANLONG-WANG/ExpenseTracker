package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.finance.FinancialCalculationBasis
import java.lang.Math.addExact
import java.lang.Math.subtractExact

internal fun FinancialBillEntity.hasUnknownPredictedFee() = !confirmed && basisJson?.let{kotlinx.serialization.json.Json.decodeFromString<FinancialCalculationBasis>(it).additionalFeeUnknown}==true

/** Bill month follows its effective debit date, never the purchase's calendar month. */
data class FinancialStatementRow(val plan:FinancialPlanEntity,val bill:FinancialBillEntity,val outstanding:FinancialBillOutstanding,
    val pendingPrincipalMinor:Long?,val pendingFeeMinor:Long?,val pendingTotalMinor:Long?,val cashRequiredMinor:Long?,val pendingPlatformFeeMinor:Long?)
data class FinancialStatementTotals(val principalMinor:Long?,val feeMinor:Long?,val totalMinor:Long?,val pendingMinor:Long?,val cashRequiredMinor:Long?,val confirmed:Int,val predicted:Int)
data class FinancialReconciliationRemainder(val planId:String,val principalMinor:Long)
data class FinancialAccountSummary(val rows:List<FinancialStatementRow>,val unplanned:List<EventEntity>,val unassignedOpeningMinor:Long?,val nextDate:String?,val next:List<FinancialStatementRow>,val overdue:List<FinancialStatementRow>,val reconciliationRemainders:List<FinancialReconciliationRemainder> = emptyList())
fun Iterable<Long?>.knownTotal():Long? {var value=0L;for(n in this)value=addExact(value,n?:return null);return value}
fun List<FinancialStatementRow>.statementTotals()=FinancialStatementTotals(map{it.outstanding.principalMinor}.knownTotal(),map{it.outstanding.feeMinor}.knownTotal(),map{it.outstanding.knownTotalMinor}.knownTotal(),map{it.pendingTotalMinor}.knownTotal(),map{it.cashRequiredMinor}.knownTotal(),count{it.bill.confirmed},count{!it.bill.confirmed})
internal fun List<FinancialStatementRow>.nextCashPayment():List<FinancialStatementRow> {
    val unpaid=filter{it.outstanding.hasUnpaidObligation&&it.cashRequiredMinor!=0L}
    val date=unpaid.minOfOrNull{it.bill.date}
    return unpaid.filter{it.bill.date==date}
}
fun BookSnapshot.financialAccountSummary(finance:FinancialGraph,accountId:String,asOf:String,stalePlanIds:Set<String> = emptySet()):FinancialAccountSummary {
    val account=accounts.first{it.id==accountId}
    val plans=finance.plans.filter{it.accountId==accountId&&it.active}
    val live=visibleEvents.associateBy{it.id}
    val waiting=repayments.filter{!it.platformConfirmed&&it.eventId in live}.map{it.eventId}.toSet()
    val rows=finance.bills.filter{it.active&&plans.any{p->p.id==it.planId}}.map{b->
        val remaining=billOutstanding(b)
        val pending=repaymentAllocations.filter{it.billId==b.id&&it.eventId in waiting}
        val known=pending.all{pendingRepaymentAmountKnown(it.eventId)}
        val principal=if(known)pending.map{it.principalMinor}.knownTotal()else null
        val fee=if(!known)null else pending.map{p->if(p.interestMinor==null||p.feeMinor==null)null else addExact(p.interestMinor,p.feeMinor)}.knownTotal()
        val total=if(principal==null||fee==null)null else addExact(principal,fee)
        FinancialStatementRow(plans.first{it.id==b.planId},b,remaining,principal,fee,total,total?.takeUnless{remaining.knownTotalMinor==null||!b.confirmed&&b.planId in stalePlanIds}?.let{subtractExact(remaining.totalMinor,it).coerceAtLeast(0)},if(known)pending.map{it.feeMinor}.knownTotal()else null)
    }.sortedWith(compareBy<FinancialStatementRow>{it.bill.date}.thenBy{it.plan.name}.thenBy{it.bill.number})
    val sources=plans.mapNotNull{it.sourceEventId}.toSet()+plans.filter{it.origin=="POOL"}.flatMap{poolInput(it)?.facts?.sources.orEmpty().mapNotNull{source->source.eventId}}

    val unplanned=visibleEvents.filter{it.accountId==accountId&&it.isFinancialSource(accounts.first{a->a.id==accountId})&&!it.historicalIncluded&&it.id !in sources&&financialSourcePrincipal(it.id,accountId)?.minor?.let{n->n>0}==true}
    val opening=finance.plans.filter{it.accountId==accountId&&it.origin=="OPENING"&&(it.active||finance.arrangements.any{a->a.originalPlanId==it.id})&&finance.arrangements.none{a->a.targetPlanId==it.id}}.map{it.principalMinor}.knownTotal()
    val poolOpening=plans.filter{it.origin=="POOL"}.mapNotNull{poolInput(it)?.openingMinor}.fold(0L,::addExact)
    val unassigned=opening?.let{subtractExact(account.openingMinor,addExact(it,poolOpening))}
    val next=rows.nextCashPayment()
    val remainders=finance.plans.filter{it.accountId==accountId}.mapNotNull{p->p.reconciliation()?.unassignedOriginalMinor?.takeIf{it>0}?.let{FinancialReconciliationRemainder(p.id,it)}}
    return FinancialAccountSummary(rows,unplanned,unassigned,next.firstOrNull()?.bill?.date,next,rows.filter{it.bill.date<asOf&&it.outstanding.hasUnpaidObligation},remainders)
}
