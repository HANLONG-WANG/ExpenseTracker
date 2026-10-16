package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.finance.*
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.lang.Math.addExact
import java.lang.Math.subtractExact

/** An inactive membership keeps the applied fact; the existing pool owns every future payment. */
internal fun LedgerRepository.prepareExistingPoolMerge(state:FinancialReviewState,input:FinancialArrangementInput,old:FinancialPlanEntity,
    principal:Long,existing:List<FinancialBillEntity>,adjustments:List<FinancialBillAdjustmentEntity>,later:List<FinancialRepaymentAllocationEntity>,
    facts:FinancialPredictionFacts,remaining:Long,moved:Long,deadlines:List<ChangeDeadline>,zone:String):FinancialArrangementReview {
    val s=state.ledger;val f=state.finance;val effective=input.terms.agreementDate;val arrangementId=id()
    fun failed(result:Calculation<List<PredictedSchedulePeriod>>)=FinancialArrangementReview(state,input,old,result,null,emptyList(),emptyList(),arrangementId,null,null,deadlines,zone)
    fun incomplete(field:String)=failed(Calculation.Incomplete(listOf(field)))
    val pool=f.plans.firstOrNull{it.id==input.mergePoolId&&it.active&&it.origin=="POOL"&&it.accountId==old.accountId&&it.mode==input.mode.name}?:return incomplete("arrangement.poolChanged")
    if(old.mode==pool.mode)return failed(Calculation.Unsupported("arrangement.samePoolMode"))
    if(financialPoolStale(s,f,pool))return incomplete("arrangement.refreshPool")
    val definition=json.decodeFromString<FinancialPredictionInput>(pool.termsJson!!).copy(planId=pool.id)
    if(definition.terms.paymentDates.none{it>=effective})return incomplete("pool.mergeHorizon")
    if(input.changeFeeMinor!!>0&&(input.mergeFeeDate !in definition.terms.paymentDates||input.mergeFeeDate!!<effective))return incomplete("pool.mergeFeeDate")
    if(input.clearingMoves.any{it.period!=null})return incomplete("arrangement.poolClearingChoice")
    val target=FinancialPlanEntity(s.book.id,id(),old.accountId,input.name,input.mode.name,"POOL_MEMBER",old.sourceEventId,old.currency,principal,false,clock.millis(),active=false,
        termsJson=json.encodeToString(FinancialPredictionInput(old.accountId,input.name,input.mode,input.terms,sourceEventId=old.sourceEventId,openingPrincipalMinor=if(old.sourceEventId==null)principal else null,paymentAccountId=definition.paymentAccountId,currentArrangementConfirmed=true)))
    val lineage=arrangementLineage(f,old.id)
    val contractId=f.contractPlans.firstOrNull{it.planId==old.id}?.contractId?:old.sourceEventId?.let{id->f.contracts.firstOrNull{it.fundingEventId==id}?.id}
    val gross=old.sourceEventId?.let{id->s.accountMoney(s.visibleEvents.first{it.id==id}).minor}?:old.principalMinor?:return incomplete("arrangement.sourcePrincipal")
    val sourceDate=old.sourceEventId?.let{id->s.visibleEvents.first{it.id==id}.date}?:effective
    val baseline=FinancialArrangementBaseline(gross,sourceDate,lineage,contractId,arrangementClearings(s,f,lineage,contractId).filter{it.date<=effective},facts.refunds,existing.filter{it.date<effective}.map{it.id},principal-moved)
    val arrangement=FinancialArrangementEntity(s.book.id,arrangementId,old.id,target.id,effective,clock.instant().toString(),principal,input.changeFeeMinor,input.originalFeeTreatment!!.name,false,json.encodeToString(baseline),json.encodeToString(input),"{}",clock.millis())
    // Adjustments were prepared before the membership ID existed; use this action's ID consistently.
    val adjusted=adjustments.map{it.copy(arrangementId=arrangementId)}
    val provisional=s.copy(billAdjustments=s.billAdjustments+adjusted)
    val finance=f.copy(plans=f.plans+target,arrangements=f.arrangements+arrangement,billAdjustments=f.billAdjustments+adjusted)
    val seed=prepareFinancialPool(FinancialReviewState(provisional,finance,state.limits),definition)
    if(seed.calculation !is Calculation.Ready)return failed(seed.calculation)
    val seededBills=finance.bills.filter{it.planId!=pool.id}+seed.bills+seed.withdrawn
    val seededFinance=finance.copy(bills=seededBills)
    val poolMembers=seededFinance.plans.filter{it.accountId==pool.accountId&&it.mode==pool.mode&&(it.id==pool.id||it.origin=="POOL_RETAINED")}.map{it.id}.toSet()
    fun destination(value:String):FinancialBillEntity? = if(value.startsWith("DATE:"))seed.bills.firstOrNull{it.date==value.removePrefix("DATE:")}
        else seededBills.firstOrNull{it.id==value&&it.active&&it.planId in poolMembers}
    if(input.clearingMoves.any{it.poolBillId!=null&&destination(it.poolBillId)?.date?.let{d->d>=effective}!=true})return incomplete("arrangement.poolClearingChoice")
    val changes=later.map{row->val moves=input.clearingMoves.filter{it.allocationId==row.id}
        ArrangementClearingChange(row,if(moves.size==1&&moves.single().poolBillId==null)listOf(row)else moves.map{move->row.copy(id=id(),billId=move.poolBillId?.let{destination(it)!!.id}?:row.billId,principalMinor=move.principalMinor,interestMinor=move.interestMinor,feeMinor=move.feeMinor)})
    }
    val allocations=s.repaymentAllocations.filter{row->later.none{it.id==row.id}}+changes.flatMap{it.replacements}
    val after=provisional.copy(repaymentAllocations=allocations)
    val destinations=changes.flatMap{it.replacements}.mapNotNull{it.billId}.toSet()
    if((existing+seed.bills+seededBills.filter{it.id in destinations}).distinctBy{it.id}.any{val due=after.billOutstanding(it);due.totalMinor<0||due.principalMinor?.let{n->n<0}==true||due.feeMinor?.let{n->n<0}==true})return incomplete("arrangement.clearingExceedsBill")
    // Only the explicitly reassigned payments may refresh their former share classification proof.
    val changedEvents=changes.filter{it.replacements!=listOf(it.original)}.map{it.original.eventId}.toSet()
    val accepted=seed.input.copy(pool=seed.input.pool!!.copy(facts=seed.input.pool.facts!!.copy(payments=seed.input.pool.facts.payments.filter{it.eventId !in changedEvents})))
    val nextFinance=seededFinance.copy(repaymentAllocations=allocations)
    val update=prepareFinancialPool(FinancialReviewState(after,nextFinance,state.limits),accepted)
    if(update.calculation !is Calculation.Ready)return failed(update.calculation)
    val finalFinance=nextFinance.copy(plans=nextFinance.plans.map{if(it.id==pool.id)update.plan else it},bills=nextFinance.bills.filter{it.planId!=pool.id}+update.bills+update.withdrawn)
    val comparison=existingPoolMergeComparison(s,f,after,finalFinance,old,pool,existing,update,input,remaining,facts)
    return FinancialArrangementReview(state,input,old,update.calculation,target,emptyList(),adjusted,arrangementId,baseline,comparison,deadlines,zone,changes,update,seed)
}

private fun LedgerRepository.existingPoolMergeComparison(s:BookSnapshot,f:FinancialGraph,after:BookSnapshot,next:FinancialGraph,old:FinancialPlanEntity,pool:FinancialPlanEntity,existing:List<FinancialBillEntity>,update:FinancialPredictionReview,input:FinancialArrangementInput,remaining:Long,facts:FinancialPredictionFacts):FinancialArrangementComparison {
    val members=f.plans.filter{it.accountId==pool.accountId&&it.mode==pool.mode&&(it.active||it.origin=="POOL_RETAINED")}.map{it.id}.toSet()
    val beforeBills=(existing+f.bills.filter{it.active&&it.planId in members}).distinctBy{it.id}
    fun row(b:FinancialBillEntity,snapshot:BookSnapshot):ArrangementMoneyRow {val due=snapshot.billOutstanding(b);return ArrangementMoneyRow(b.date,due.principalMinor,due.feeMinor,due.totalMinor,b.paymentAccountId)}
    val original=beforeBills.map{row(it,s)}
    val proposed=(next.bills.filter{it.active&&(it.planId==pool.id||it.planId in members||it.planId==old.id)}).map{row(it,after)}.filter{it.totalMinor>0}
    val oldFees=original.map{it.feeMinor}.knownTotal();val newFees=proposed.map{it.feeMinor}.knownTotal()
    val first=original.filter{it.date>=input.terms.agreementDate&&it.totalMinor>0}.minOfOrNull{it.date}
    val residual=(update.calculation as Calculation.Ready).value.lastOrNull()?.basis?.remainingPrincipalMinor
    val sourceResidual=subtractExact(subtractExact(remaining,facts.principalFacts().filter{it.date>input.terms.agreementDate}.sumOf{it.principalMinor}),existing.sumOf{s.billOutstanding(it).principalMinor!!})
    val through=maxOf(LocalDate.now(clock),LocalDate.parse((original.map{it.date}+proposed.map{it.date}).max()))
    val stale=f.plans.filter{financialPredictionStale(s,f,it)}.map{it.id}.toSet()
    val before=s.cashTimeline(f,LocalDate.now(clock),through,stale,input.referenceRates)
    val afterFunds=after.cashTimeline(next,LocalDate.now(clock),through,stale-pool.id-old.id,input.referenceRates)
    val funding=before.accountRisks().map{a->val b=afterFunds.accountRisks().first{it.opening.account.id==a.opening.account.id};ArrangementFundingAccount(a.opening.account.id,a.opening.account.currency,a.maximumGapMinor,b.maximumGapMinor,a.includingIncomeGapMinor,b.includingIncomeGapMinor,a.firstRiskDate,b.firstRiskDate,a.estimated||b.estimated)}
    return FinancialArrangementComparison(original,proposed,first,first?.let{date->subtractExact(original.filter{it.date<=date}.sumOf{it.totalMinor},proposed.filter{it.date<=date}.sumOf{it.totalMinor})},oldFees,newFees,
        if(old.complete&&pool.complete&&update.plan.complete&&!input.mergeFeeIncludedInRetained&&sourceResidual==0L&&oldFees!=null&&newFees!=null)subtractExact(newFees,oldFees)else null,sourceResidual,residual,funding,through.toString(),(before.steps+afterFunds.steps).any{r->before.openings.none{it.account.id==r.item.accountId}},(before.steps+afterFunds.steps).mapNotNull{it.item.referenceRate}.distinct(),originalComplete=old.complete&&pool.complete,proposedComplete=update.plan.complete)
}
