package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*

/** Count original opening allocations once; conversions move debt, they do not free its source. */
internal fun FinancialGraph.allocatedOpeningPrincipal(accountId:String,exceptPlanId:String?=null):Long? {
    val pools=plans.filter{it.accountId==accountId&&it.origin=="POOL"&&it.active}
    val pooledModes=pools.map{it.mode}.toSet()
    val roots=plans.filter{it.accountId==accountId&&it.id!=exceptPlanId&&it.origin=="OPENING"&&
        it.mode !in pooledModes&&(it.active||arrangements.any{a->a.originalPlanId==it.id})}
    // A pool's opening amount already includes adopted opening plans of the same mode.
    return (roots.map{it.principalMinor}+pools.filter{it.id!=exceptPlanId}.map{requireNotNull(poolInput(it)).openingMinor}).knownTotal()
}

internal fun FinancialGraph.requireOpeningPrincipal(account:AccountEntity,principal:Long?,knownPrincipal:Long=0,exceptPlanId:String?=null) {
    val used=requireNotNull(allocatedOpeningPrincipal(account.id,exceptPlanId)){"其他期初计划本金尚未明确 / 他の期首プランの元金が未確定です"}
    val remaining=Math.subtractExact(account.openingMinor,used)
    require((principal?:knownPrincipal)<=remaining&&(principal!=null||remaining>0)){"计划本金超过未分配的期初欠款 / プラン元金が未割当ての期首残高を超えています"}
}

/** Shared by manual and predicted plans, including inactive applied pool memberships. */
internal fun FinancialReviewState.financialSourceOwned(eventId:String,exceptPlanId:String?=null):Boolean {
    val lineage=exceptPlanId?.let{arrangementLineage(finance,it).toSet()}.orEmpty()
    val owned=finance.plans.any{p->p.sourceEventId==eventId&&p.id !in lineage&&
        (p.active||p.origin in listOf("POOL_MEMBER","POOL_RETAINED")||finance.arrangements.any{it.originalPlanId==p.id||it.targetPlanId==p.id})}
    // Retained plans still own deleted sources. Only live sources can gain implicit pool ownership;
    // source-principal validation separately rejects recalculating a missing purchase/borrowing.
    val event=ledger.visibleEvents.firstOrNull{it.id==eventId}?:return owned
    val pooled=finance.plans.any{p->p.active&&p.origin=="POOL"&&p.accountId==event.accountId&&p.id !in lineage&&
        poolMode(ledger,finance,event.accountId,eventId)==p.mode}
    return owned||pooled
}

internal fun FinancialReviewState.requireFinancialSourceAvailable(eventId:String,exceptPlanId:String?=null) {
    require(!financialSourceOwned(eventId,exceptPlanId)){"该记录已有计划或已并入余额池，请打开原计划 / この記録にはプランがあるかプールに統合済みです。元のプランを開いてください"}
}
