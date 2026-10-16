package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import java.lang.Math.addExact
import java.lang.Math.subtractExact

fun BookSnapshot.appliedRepaymentAllocations(excludeEventId:String?=null):List<FinancialRepaymentAllocationEntity> {
    val ids=repayments.filter{it.platformConfirmed&&it.eventId!=excludeEventId&&visibleEvents.any{e->e.id==it.eventId}}.map{it.eventId}.toSet()
    return repaymentAllocations.filter{it.eventId in ids}
}
data class FinancialBillOutstanding(val bill:FinancialBillEntity,val principalMinor:Long?,val feeMinor:Long?,val totalMinor:Long,val paidPrincipalMinor:Long,val paidFeeMinor:Long) {
    val settled:Boolean get()=totalMinor<=0
}
fun BookSnapshot.billOutstanding(bill:FinancialBillEntity,excludeEventId:String?=null):FinancialBillOutstanding {
    val payments=appliedRepaymentAllocations(excludeEventId).filter{it.billId==bill.id}
    val principal=payments.fold(0L){sum,p->addExact(sum,p.principalMinor!!)}
    val fee=payments.fold(0L){sum,p->addExact(sum,addExact(p.interestMinor!!,p.feeMinor!!))}
    val adjustments=billAdjustments.filter{it.billId==bill.id}
    val moved=adjustments.fold(0L){sum,a->addExact(sum,a.principalMinor)}
    val cancelled=adjustments.fold(0L){sum,a->addExact(sum,a.feeMinor)}
    return FinancialBillOutstanding(bill,bill.principalMinor?.let{subtractExact(subtractExact(it,principal),moved)},bill.feeMinor?.let{subtractExact(subtractExact(it,fee),cancelled)},subtractExact(bill.totalMinor,addExact(addExact(principal,fee),addExact(moved,cancelled))),principal,fee)
}
fun BookSnapshot.contractRemaining(contract:DebtContractEntity,excludeEventId:String?=null):Long =
    appliedRepaymentAllocations(excludeEventId).filter{it.contractId==contract.id}.fold(contract.principalMinor){left,p->subtractExact(left,p.principalMinor!!)}
fun BookSnapshot.unallocatedRepaymentPrincipal(accountId:String):Long {
    return repayments.filter{it.platformConfirmed&&it.debtAccountId==accountId&&visibleEvents.any{e->e.id==it.eventId}}.fold(0L){sum,p->
        val allocated=repaymentAllocations.filter{it.eventId==p.eventId}.fold(0L){n,a->addExact(n,a.principalMinor!!)}
        addExact(sum,subtractExact(p.principalMinor!!,allocated))
    }
}
fun FinancialGraph.contractForBill(bill:FinancialBillEntity):String? = contractPlans.firstOrNull{it.planId==bill.planId}?.contractId
    ?:plans.firstOrNull{it.id==bill.planId}?.sourceEventId?.let{source->contracts.firstOrNull{it.fundingEventId==source}?.id}

fun BookSnapshot.planRepaymentAllocations(plan:FinancialPlanEntity,finance:FinancialGraph):List<FinancialRepaymentAllocationEntity> {
    val billIds=finance.bills.filter{it.planId==plan.id}.map{it.id}.toSet()
    val contractId=finance.contractPlans.firstOrNull{it.planId==plan.id}?.contractId
        ?:plan.sourceEventId?.let{id->contracts.firstOrNull{it.fundingEventId==id}?.id}
    return appliedRepaymentAllocations().filter{it.billId in billIds||it.billId==null&&contractId!=null&&it.contractId==contractId}
}
