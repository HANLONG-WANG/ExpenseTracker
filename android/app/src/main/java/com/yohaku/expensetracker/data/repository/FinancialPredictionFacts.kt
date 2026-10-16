package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.finance.*
import kotlinx.serialization.Serializable

@Serializable data class PredictionBillFact(
    val id:String,val number:Int,val date:String,val principalMinor:Long?,val feeMinor:Long?,val totalMinor:Long,
    val confirmed:Boolean,val paymentAccountId:String?
) {
    fun period()=ConfirmedSchedulePeriod(number,principalMinor,feeMinor,totalMinor,java.time.LocalDate.parse(date))
}
@Serializable data class PredictionRepaymentFact(
    val eventId:String,val revision:Int,val date:String,val repayment:FinancialRepaymentEntity,
    val allocations:List<FinancialRepaymentAllocationEntity>
)
@Serializable data class PredictionRefundFact(val eventId:String,val revision:Int,val date:String,val principalMinor:Long)
@Serializable data class FinancialPredictionFacts(
    val asOf:String,val retainedBills:List<PredictionBillFact>,val repayments:List<PredictionRepaymentFact>,val refunds:List<PredictionRefundFact>,val contract:DebtContractEntity?=null,val billAdjustments:List<FinancialBillAdjustmentEntity> = emptyList()
) {
    fun retainedPeriods():List<ConfirmedSchedulePeriod> = retainedBills.map{bill->
        val applied=repayments.filter{it.repayment.platformConfirmed}.flatMap{it.allocations}.filter{it.billId==bill.id}
        val principal=applied.fold(0L){sum,a->Math.addExact(sum,a.principalMinor!!)}
        val fee=applied.fold(0L){sum,a->Math.addExact(sum,Math.addExact(a.interestMinor!!,a.feeMinor!!))}
        val fullyKnown=Math.addExact(principal,fee)==bill.totalMinor&&
            (bill.principalMinor==null||bill.principalMinor==principal)&&(bill.feeMinor==null||bill.feeMinor==fee)
        // A fully cleared amount has an explicit actual split; the original bill's nulls stay null.
        if(fullyKnown)bill.copy(principalMinor=principal,feeMinor=fee).period()else bill.period()
    }
    fun principalFacts():List<SchedulePrincipalFact> = repayments.filter{it.repayment.platformConfirmed}.flatMap{payment->
        payment.allocations.map{a->SchedulePrincipalFact(payment.eventId,payment.repayment.clearingDate!!,a.principalMinor!!,
            a.billId?.let{id->retainedBills.first{it.id==id}.number})}
    }+refunds.map{SchedulePrincipalFact(it.eventId,it.date,it.principalMinor,kind="REFUND")}
}

internal fun predictionFacts(s:BookSnapshot,f:FinancialGraph,planId:String?,sourceEventId:String?,asOf:String):FinancialPredictionFacts {
    val incoming=f.currentArrangements.firstOrNull{it.targetPlanId==planId}
    val baseline=incoming?.let{kotlinx.serialization.json.Json.decodeFromString<FinancialArrangementBaseline>(it.baselineJson)}
    val bills=f.bills.filter{it.planId==planId}
    val billIds=bills.map{it.id}.toSet()
    val plan=f.plans.firstOrNull{it.id==planId}
    val contractId=plan?.let{p->f.contractPlans.firstOrNull{it.planId==p.id}?.contractId}
        ?:(plan?.sourceEventId?:sourceEventId)?.let{source->f.contracts.firstOrNull{it.fundingEventId==source}?.id}?:baseline?.contractId
    val live=s.visibleEvents.associateBy{it.id}
    val payments=f.repayments.filter{it.eventId in live}.mapNotNull{p->
        val rows=f.repaymentAllocations.filter{it.eventId==p.eventId&&(it.billId in billIds||it.billId==null&&contractId!=null&&it.contractId==contractId&&baseline?.clearings?.none{p->p.eventId==it.eventId}!=false)}.sortedBy{it.id}
        if(rows.isEmpty())null else PredictionRepaymentFact(p.eventId,live.getValue(p.eventId).revision,live.getValue(p.eventId).date,p,rows)
    }.sortedBy{it.eventId}
    val referenced=payments.flatMap{it.allocations}.mapNotNull{it.billId}.toSet()
    val reviewed=plan?.reconciliation()?.input?.bills.orEmpty().map{it.billId}.toSet()
    val retained=bills.filter{it.confirmed||it.id in referenced||it.id in reviewed||it.active&&it.date<asOf}.sortedBy{it.number}.map{
        val adjustments=s.billAdjustments.filter{a->a.billId==it.id}
        val principal=adjustments.fold(0L){n,a->Math.addExact(n,a.principalMinor)};val fee=adjustments.fold(0L){n,a->Math.addExact(n,a.feeMinor)}
        PredictionBillFact(it.id,it.number,it.date,it.principalMinor?.let{p->Math.subtractExact(p,principal)},it.feeMinor?.let{p->Math.subtractExact(p,fee)},Math.subtractExact(it.totalMinor,Math.addExact(principal,fee)),it.confirmed,it.paymentAccountId)
    }
    val source=live[sourceEventId]
    val reconciled=plan?.reconciliation()
    val refunds=if(reconciled!=null)reconciled.refunds.filter{it.fact.date>reconciled.input.effectiveDate&&it.targetMinor>0}.map{it.fact.copy(principalMinor=it.targetMinor)}else if(source==null)emptyList()else s.settlements.filter{it.originalEventId==sourceEventId&&it.confirmed&&it.eventId in live}.mapNotNull{settlement->
        val reduction=s.postings.filter{it.eventId==settlement.eventId&&it.accountId==source.accountId}.fold(0L){sum,p->Math.subtractExact(sum,p.deltaMinor)}
        if(reduction<=0||baseline?.refunds?.any{it.eventId==settlement.eventId}==true)null else PredictionRefundFact(settlement.eventId,live.getValue(settlement.eventId).revision,settlement.settlementDate,reduction)
    }.sortedBy{it.eventId}
    return FinancialPredictionFacts(asOf,retained,payments,refunds,f.contracts.firstOrNull{it.id==contractId},f.billAdjustments.filter{it.billId in billIds}.sortedBy{it.id})
}
