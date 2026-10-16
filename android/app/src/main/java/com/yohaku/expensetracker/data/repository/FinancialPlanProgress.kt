package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import java.lang.Math.addExact

/** Describes saved obligations, never a second account debt balance or a promise of settlement. */
data class FinancialPlanProgress(
    val listedPeriods:Int,val unpaidPeriods:Int,val overduePeriods:Int,val principalMinor:Long?,val feeMinor:Long?,
    val totalMinor:Long,val pendingMinor:Long?,val cashRequiredMinor:Long?,val lastDate:String?,
    val confirmedPeriods:Int,val predictedPeriods:Int,val complete:Boolean,val stale:Boolean,val needsReconciliation:Boolean
)
fun BookSnapshot.financialPlanProgress(f:FinancialGraph,plan:FinancialPlanEntity,asOf:String,stale:Boolean=false):FinancialPlanProgress {
    val rows=financialAccountSummary(f,plan.accountId,asOf,if(stale)setOf(plan.id)else emptySet()).rows.filter{it.plan.id==plan.id}
    val unpaid=rows.filter{it.outstanding.totalMinor>0}
    val reconcile=rows.any{r->r.outstanding.totalMinor<0||r.outstanding.principalMinor?.let{it<0}==true||r.outstanding.feeMinor?.let{it<0}==true}
    fun total(select:(FinancialStatementRow)->Long?):Long? {
        var sum=0L
        for(row in unpaid){val value=select(row)?:return null;if(value<0)return null;sum=addExact(sum,value)}
        return sum
    }
    return FinancialPlanProgress(rows.size,unpaid.size,unpaid.count{it.bill.date<asOf},
        total{it.outstanding.principalMinor},total{it.outstanding.feeMinor},unpaid.fold(0L){n,r->addExact(n,r.outstanding.totalMinor)},
        total{it.pendingTotalMinor},total{it.cashRequiredMinor},unpaid.maxOfOrNull{it.bill.date},
        unpaid.count{it.bill.confirmed},unpaid.count{!it.bill.confirmed},plan.complete,stale,reconcile)
}
