package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*

/** Evidence excludes outflows: another reviewed transfer must not invalidate this source proof. */
internal fun LedgerRepository.reconciliationPoolEvidence(s:BookSnapshot,f:FinancialGraph,pool:FinancialPlanEntity,effective:String):PoolArrangementBaseline {
    val input=json.decodeFromString<FinancialPredictionInput>(pool.termsJson!!)
    val facts=poolFacts(s,f,input,validateArrangements=false)
    val outgoing=f.arrangements.filter{it.originalPlanId==pool.id}.map{it.id}.toSet()
    val sources=facts.sources.filter{row->outgoing.none{row.id=="arrangement:$it"||row.id.startsWith("refund-allocation:$it:")}}
    return PoolArrangementBaseline(pool.id,input.pool!!.openingMinor,facts.accountOpeningMinor,input.pool.sourceDate,
        sources.filter{it.date<=effective},facts.payments.filter{it.date<=effective},emptyList())
}
internal fun PoolArrangementBaseline.balanceMinor():Long? {
    if(payments.any{it.principalMinor==null||it.allocations.any{row->row.principalMinor==null}||it.unallocatedMinor>0&&it.explicitMinor==null})return null
    return Math.subtractExact(sources.fold(openingMinor){n,row->Math.addExact(n,row.principalMinor)},payments.fold(0L){n,row->Math.addExact(n,Math.addExact(row.allocations.sumOf{it.principalMinor!!},row.explicitMinor?:0))})
}
internal fun FinancialGraph.isPoolReturn(change:FinancialArrangementEntity,poolId:String)=arrangementLineage(this,change.originalPlanId).any{id->
    id==poolId||plans.first{it.id==id}.let{it.origin in listOf("POOL_RETAINED","POOL_MEMBER")&&reconciliationPool(it)?.id==poolId}
}
internal fun LedgerRepository.poolExternalReviewIssue(s:BookSnapshot,f:FinancialGraph,poolId:String)=f.poolArrangements(poolId).firstOrNull{change->
    !f.isPoolReturn(change,poolId)&&arrangementBaselineIssue(s,f,f.plans.first{it.id==change.targetPlanId})!=null
}

internal fun LedgerRepository.poolPhysicalRefunds(s:BookSnapshot,f:FinancialGraph,pool:FinancialPlanEntity):List<PredictionRefundFact> {
    val input=json.decodeFromString<FinancialPredictionInput>(pool.termsJson!!)
    val facts=poolFacts(s,f,input,validateArrangements=false)
    val outgoing=f.arrangements.filter{it.originalPlanId==pool.id}.map{it.id}.toSet()
    return facts.sources.filter{it.principalMinor<0&&it.kind in listOf("REFUND","REFUND_ALLOCATION")&&outgoing.none{id->it.id.startsWith("refund-allocation:$id:")}&&
        (it.kind=="REFUND"||f.currentArrangements.any{change->it.id=="refund-allocation:${change.id}:${it.eventId}"&&!f.isPoolReturn(change,pool.id)})}
        .groupBy{it.eventId!!}.map{(id,rows)->PredictionRefundFact(id,0,rows.first().date,Math.negateExact(rows.sumOf{it.principalMinor}))}.sortedBy{it.eventId}
}
internal fun LedgerRepository.otherReviewedPoolTransfers(s:BookSnapshot,f:FinancialGraph,poolId:String,targetId:String)=f.currentArrangements.filter{a->
    a.originalPlanId==poolId&&a.targetPlanId!=targetId&&f.plans.first{it.id==a.targetPlanId}.let{p->arrangementBaselineIssue(s,f,p)==null}
}
internal fun LedgerRepository.availablePoolRefunds(s:BookSnapshot,f:FinancialGraph,poolId:String,targetId:String):List<PredictionRefundFact> {
    val others=otherReviewedPoolTransfers(s,f,poolId,targetId)
    val reserved=others.flatMap{a->f.plans.first{it.id==a.targetPlanId}.reconciliation()?.refunds.orEmpty()}
    return poolPhysicalRefunds(s,f,f.plans.first{it.id==poolId}).map{fact->
        fact.copy(principalMinor=Math.subtractExact(fact.principalMinor,reserved.filter{it.fact.eventId==fact.eventId}.sumOf{it.targetMinor}))
    }
}
internal fun FinancialGraph.reconciliationSourceBills(source:FinancialPlanEntity):List<FinancialBillEntity> {
    val members=if(source.origin=="POOL")plans.filter{it.accountId==source.accountId&&it.mode==source.mode&&(it.id==source.id||it.origin=="POOL_RETAINED")}.map{it.id}.toSet()else setOf(source.id)
    return bills.filter{it.active&&it.planId in members}
}
