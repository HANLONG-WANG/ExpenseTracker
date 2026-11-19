package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import kotlinx.serialization.json.Json
import java.lang.Math.addExact
import java.lang.Math.subtractExact

/** Unknown components do not erase the lower bound supplied by known allocations. */
internal fun repaymentAllocationsWithinAmounts(allocations:List<FinancialRepaymentAllocationEntity>,principal:Long?,interest:Long?,fee:Long?,received:Long?,paidInDebtCurrency:Long?):Boolean {
    fun total(value:(FinancialRepaymentAllocationEntity)->Long?)=allocations.fold(0L){sum,a->addExact(sum,value(a)?:0)}
    val p=total{it.principalMinor};val i=total{it.interestMinor};val f=total{it.feeMinor}
    val all=addExact(p,addExact(i,f))
    return (principal==null||p<=principal)&&(interest==null||i<=interest)&&(fee==null||f<=fee)&&
        (received==null||all<=received)&&(paidInDebtCurrency==null||all<=paidInDebtCurrency)
}

/** A pending payment offsets bills only when every allocation is complete and a known cash amount
 * (platform receipt, or the same-currency payment net of its source fee) bounds them. A pending
 * clearing normally has no platform receipt yet, so the actual payment is the usual bound. */
internal fun BookSnapshot.pendingRepaymentAmountKnown(eventId:String):Boolean {
    val repayment=repayments.first{it.eventId==eventId}
    val event=visibleEvents.first{it.id==eventId}
    val debt=accounts.first{it.id==repayment.debtAccountId}
    val rows=repaymentAllocations.filter{it.eventId==eventId}
    if(rows.any{it.principalMinor==null||it.interestMinor==null||it.feeMinor==null})return false
    val paid=event.amountMinor.takeIf{event.currency==debt.currency}?.let{subtractExact(it,repayment.sourceFeeMinor?:0)}
    if(repayment.receivedMinor==null&&paid==null)return false
    return repaymentAllocationsWithinAmounts(rows,repayment.principalMinor,repayment.interestMinor,repayment.feeMinor,repayment.receivedMinor,paid)
}

/** Shared by save and restore, with the candidate event excluded from the other applied payments.
 * A restored payment is bounded by other live payments only: arrangement transfers made while it was in the
 * trash assumed it absent, so restoring marks that arrangement stale and its reconciliation returns the principal. */
internal fun BookSnapshot.validateRepaymentAllocationCapacity(f:FinancialGraph,eventId:String,allocations:List<FinancialRepaymentAllocationEntity>,json:Json,restoring:Boolean=false) {
    allocations.filter{it.billId!=null}.groupBy{it.billId}.forEach{(id,rows)->
        val bill=f.bills.first{it.id==id};val outstanding=billOutstanding(bill,eventId)
        val transfers=if(restoring)billAdjustments.filter{it.billId==id}else emptyList()
        val movedPrincipal=transfers.fold(0L){sum,a->addExact(sum,a.principalMinor)};val movedFee=transfers.fold(0L){sum,a->addExact(sum,a.feeMinor)}
        val principal=rows.fold(0L){sum,a->addExact(sum,a.principalMinor!!)}
        val fee=rows.fold(0L){sum,a->addExact(sum,addExact(a.interestMinor!!,a.feeMinor!!))}
        require(addExact(principal,fee)<=addExact(outstanding.totalMinor,addExact(movedPrincipal,movedFee))&&outstanding.principalMinor?.let{principal<=addExact(it,movedPrincipal)}!=false&&outstanding.feeMinor?.let{fee<=addExact(it,movedFee)}!=false){"该账期累计分配超过剩余义务，请先调整还款关联 / この回の合計充当額が残額を超えています。返済の関連付けを先に調整してください"}
    }
    allocations.filter{it.contractId!=null}.groupBy{it.contractId}.forEach{(id,values)->
        val contract=f.contracts.first{it.id==id}
        require(values.fold(0L){sum,a->addExact(sum,a.principalMinor!!)}<=contractRemaining(contract,eventId)){"合同分配超过剩余本金，请先调整还款关联 / 契約への充当額が残元金を超えています。返済の関連付けを先に調整してください"}
    }
    allocations.filter{row->row.contractId!=null&&row.billId!=null&&f.bills.any{b->b.id==row.billId&&f.plans.any{p->p.id==b.planId&&p.origin=="POOL"&&f.contracts.first{it.id==row.contractId}.mode!=p.mode}}}.groupBy{row->f.bills.first{it.id==row.billId}.planId to row.contractId!!}.forEach{(key,rows)->
        val (poolId,contractId)=key
        val transferred=f.poolArrangements(poolId).filter{change->json.decodeFromString<FinancialArrangementBaseline>(change.baselineJson).contractId==contractId}.fold(0L){n,change->addExact(n,change.principalMinor)}
        val paid=appliedRepaymentAllocations(eventId).filter{it.contractId==contractId&&f.bills.any{b->b.id==it.billId&&b.planId==poolId}}.fold(0L){n,row->addExact(n,row.principalMinor!!)}
        require(rows.sumOf{it.principalMinor!!}<=subtractExact(transferred,paid)){"本次分配超过此合同已并入池的剩余本金，请先调整还款关联 / 充当額がこの契約からプールへ統合済みの残元金を超えています。返済の関連付けを先に調整してください"}
    }
}
