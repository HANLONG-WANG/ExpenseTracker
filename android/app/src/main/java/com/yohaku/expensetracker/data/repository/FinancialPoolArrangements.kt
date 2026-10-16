package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.finance.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.lang.Math.addExact
import java.lang.Math.subtractExact

@Serializable data class PoolBillTransfer(val billId:String,val principalMinor:Long,val cancelledFeeMinor:Long?=null)
@Serializable data class PoolArrangementSelection(val bills:List<PoolBillTransfer>,val unbilledPrincipalMinor:Long=0)
@Serializable data class PoolArrangementBaseline(val poolId:String,val openingMinor:Long,val accountOpeningMinor:Long,val sourceDate:PoolSourceDate,
    val sources:List<PoolSourceFact>,val payments:List<PoolPaymentFact>,val selectedBills:List<FinancialBillEntity>)

internal fun LedgerRepository.poolArrangementBaselineIssue(s:BookSnapshot,f:FinancialGraph,applied:FinancialArrangementEntity,base:PoolArrangementBaseline):String? {
    val pool=f.plans.firstOrNull{it.id==base.poolId}?:return "arrangement.sourceChanged"
    val input=json.decodeFromString<FinancialPredictionInput>(pool.termsJson!!)
    val definition=input.pool!!
    if(definition.openingMinor!=base.openingMinor||definition.sourceDate!=base.sourceDate)return "arrangement.baselineChanged"
    val facts=poolFacts(s,f,input,validateArrangements=false)
    val outgoing=f.arrangements.filter{it.originalPlanId==base.poolId}.map{"arrangement:${it.id}"}.toSet()
    if(facts.accountOpeningMinor!=base.accountOpeningMinor||facts.sources.filter{it.date<=applied.effectiveDate&&it.id !in outgoing}!=base.sources||facts.payments.filter{it.date<=applied.effectiveDate}!=base.payments)return "arrangement.baselineChanged"
    if(base.selectedBills.any{old->f.bills.firstOrNull{it.id==old.id}!=old})return "arrangement.baselineChanged"
    if(poolPhysicalRefunds(s,f,pool).any{it.date>applied.effectiveDate})return "arrangement.allocateRefund"
    return null
}

internal fun LedgerRepository.preparePoolArrangement(state:FinancialReviewState,input:FinancialArrangementInput,old:FinancialPlanEntity):FinancialArrangementReview {
    val s=state.ledger;val f=state.finance;val effective=input.terms.agreementDate
    val account=s.accounts.first{it.id==old.accountId};requireMode(account,input.mode)
    require(input.name.isNotBlank());LocalDate.parse(effective)
    require(input.terms.paymentDates.isNotEmpty()&&input.terms.paymentDates.all{it>=effective})
    input.paymentAccountId?.let{id->require(s.accounts.any{it.id==id&&it.active&&!com.yohaku.expensetracker.domain.model.AccountType.valueOf(it.type).debt})}
    val id=id();val definition=json.decodeFromString<FinancialPredictionInput>(old.termsJson!!).copy(planId=old.id)
    val pool=definition.pool!!;val facts=poolFacts(s,f,definition)
    val versions=f.rules.filter{it.accountId==old.accountId}.map{EffectiveFinancialRule(it.version,json.decodeFromString<FinancialRuleVersion>(it.json))}
    val rule=versions.filter{it.rule.mode==input.mode&&it.rule.effectiveFrom<=effective}.maxWithOrNull(compareBy<EffectiveFinancialRule>{it.rule.effectiveFrom}.thenBy{it.version})?.rule
    val existing=(f.bills.filter{it.planId==old.id&&it.active}+facts.retained.map{it.bill}).distinctBy{it.id}.sortedBy{it.date}
    val deadlines=existing.filter{it.date>=effective}.flatMap{bill->rule?.deadlines.orEmpty().filter{it.billId==bill.id&&it.changeType==input.mode}}
    fun failed(result:Calculation<List<PredictedSchedulePeriod>>)=FinancialArrangementReview(state,input,old,result,null,emptyList(),emptyList(),id,null,null,deadlines,rule?.zoneId?:clock.zone.id)
    fun incomplete(field:String)=failed(Calculation.Incomplete(listOf(field)))
    if(effective<account.openingDate)return incomplete("arrangement.effectiveBeforeOriginal")
    if(input.mode.name==old.mode)return failed(Calculation.Unsupported("arrangement.samePoolMode"))
    if(input.originalFeeTreatment==null)return incomplete("arrangement.originalFees")
    if(input.changeFeeMinor==null)return incomplete("arrangement.changeFee")
    require(input.changeFeeMinor>=0)
    if(financialPoolStale(s,f,old)||facts.issues.isNotEmpty())return incomplete("arrangement.refreshOriginal")
    if(facts.sources.any{it.kind=="REFUND"&&it.date>effective})return incomplete("arrangement.laterRefund")
    val selection=input.poolSelection?:return incomplete("arrangement.poolSelection")
    if(selection.unbilledPrincipalMinor<0||selection.bills.map{it.billId}.distinct().size!=selection.bills.size)return incomplete("arrangement.poolSelection")
    val selectedIds=selection.bills.map{it.billId}.toSet()
    if(selection.bills.any{it.principalMinor<0||it.cancelledFeeMinor?.let{n->n<0}==true||existing.none{b->b.id==it.billId&&b.date>=effective}})return incomplete("arrangement.poolSelection")
    val pending=s.repayments.filter{!it.platformConfirmed&&s.visibleEvents.any{e->e.id==it.eventId}}.map{it.eventId}.toSet()
    if(s.repaymentAllocations.any{it.eventId in pending&&it.billId in selectedIds})return incomplete("arrangement.pendingClearing")
    val later=arrangementLaterAllocations(s,f,old,effective).filter{it.billId in selectedIds}
    if(input.clearingMoves.any{move->later.none{it.id==move.allocationId}})return incomplete("arrangement.clearingChanged")
    if(later.any{row->input.clearingMoves.none{it.allocationId==row.id}})return incomplete("arrangement.laterClearing")
    for(row in later) {
        val moves=input.clearingMoves.filter{it.allocationId==row.id}
        if(moves.any{it.period?.let{p->p !in 1..input.terms.paymentDates.size}==true||listOf(it.principalMinor,it.interestMinor,it.feeMinor).any{n->n<0}||addExact(it.principalMinor,addExact(it.interestMinor,it.feeMinor))==0L}||moves.sumOf{it.principalMinor}!=row.principalMinor||moves.sumOf{it.interestMinor}!=row.interestMinor||moves.sumOf{it.feeMinor}!=row.feeMinor)return incomplete("arrangement.clearingSplit")
    }
    val pre=s.repayments.filter{it.platformConfirmed&&it.clearingDate!!<=effective}.map{it.eventId}.toSet()
    val at=s.copy(repaymentAllocations=s.repaymentAllocations.filter{it.eventId in pre})
    if(existing.any{at.billOutstanding(it).principalMinor==null||at.billOutstanding(it).feeMinor==null})return incomplete("arrangement.originalBreakdown")
    val balance=subtractExact(facts.sources.filter{it.date<=effective}.fold(pool.openingMinor){n,row->addExact(n,row.principalMinor)},facts.payments.filter{it.date<=effective}.fold(0L){n,p->addExact(n,addExact(p.allocations.sumOf{it.principalMinor!!},p.explicitMinor?:0))})
    val reserved=existing.filter{it.date<effective}.fold(0L){n,b->addExact(n,at.billOutstanding(b).principalMinor!!)}
    val unbilled=subtractExact(balance,existing.fold(0L){n,b->addExact(n,at.billOutstanding(b).principalMinor!!)}).coerceAtLeast(0)
    val principal=selection.bills.fold(selection.unbilledPrincipalMinor){n,row->addExact(n,row.principalMinor)}
    if(principal<=0||principal>subtractExact(balance,reserved)||selection.unbilledPrincipalMinor>unbilled)return incomplete("arrangement.poolSelection")
    if(input.loanLimitDeltaMinor?.let{it < -principal||it>principal}==true)return incomplete("arrangement.limitDelta")
    val adjustments=selection.bills.map{part->
        val bill=existing.first{it.id==part.billId};val due=at.billOutstanding(bill)
        val fee=if(input.originalFeeTreatment==OriginalFeeTreatment.RETAIN)0 else part.cancelledFeeMinor?:return incomplete("arrangement.poolFees")
        if(part.principalMinor>due.principalMinor!!||fee>due.feeMinor!!||part.principalMinor==0L&&fee==0L)return incomplete("arrangement.poolSelection")
        FinancialBillAdjustmentEntity(s.book.id,id(),id,bill.id,part.principalMinor,fee)
    }
    val terms=input.terms.copy(additionalFees=listOf(AdditionalPeriodFee(1,input.changeFeeMinor)))
    val movedFacts=input.clearingMoves.filter{it.period!=null}.map{move->val row=later.first{it.id==move.allocationId};SchedulePrincipalFact(row.eventId,s.repayments.first{it.eventId==row.eventId}.clearingDate!!,move.principalMinor,move.period)}
    val calculation=if(input.manualPeriods!=null)Calculation.Ready(emptyList())else reconciledSchedule(principal,input.mode,terms,versions,emptyList(),movedFacts,LocalDate.parse(effective))
    if(calculation !is Calculation.Ready)return failed(calculation)
    val target=FinancialPlanEntity(s.book.id,id(),old.accountId,input.name.trim(),input.mode.name,"ARRANGEMENT",null,old.currency,principal,if(input.manualPeriods!=null)input.manualComplete else calculation.value.lastOrNull()?.basis?.remainingPrincipalMinor==0L,clock.millis(),termsJson=if(input.manualPeriods!=null)null else json.encodeToString(FinancialPredictionInput(old.accountId,input.name,input.mode,terms,openingPrincipalMinor=principal,paymentAccountId=input.paymentAccountId,currentArrangementConfirmed=true)))
    val bills=manualArrangementBills(s,input,target)?:calculation.value.map{p->FinancialBillEntity(s.book.id,id(),target.id,p.period.number,input.paymentAccountId?:rule?.billing?.paymentAccountId,p.ruleId,p.period.date.toString(),p.period.principalMinor,p.period.feeMinor,p.period.totalMinor,json.encodeToString(p.basis),null,null,null,null,null)}
    val changes=later.map{row->val moves=input.clearingMoves.filter{it.allocationId==row.id};ArrangementClearingChange(row,if(moves.size==1&&moves.single().period==null)listOf(row)else moves.map{move->row.copy(id=id(),billId=if(move.period==null)row.billId else bills.first{it.number==move.period}.id,principalMinor=move.principalMinor,interestMinor=move.interestMinor,feeMinor=move.feeMinor)})}
    val allocations=s.repaymentAllocations.filter{row->later.none{it.id==row.id}}+changes.flatMap{it.replacements}
    val after=s.copy(billAdjustments=s.billAdjustments+adjustments,repaymentAllocations=allocations)
    if((existing+bills).any{val due=after.billOutstanding(it);due.totalMinor<0||due.principalMinor?.let{n->n<0}==true||due.feeMinor?.let{n->n<0}==true})return incomplete("arrangement.clearingExceedsBill")
    val outgoing=f.arrangements.filter{it.originalPlanId==old.id}.map{"arrangement:${it.id}"}.toSet()
    val context=PoolArrangementBaseline(old.id,pool.openingMinor,facts.accountOpeningMinor,pool.sourceDate,facts.sources.filter{it.date<=effective&&it.id !in outgoing},facts.payments.filter{it.date<=effective},existing.filter{it.id in selectedIds})
    val baseline=FinancialArrangementBaseline(balance,effective,listOf(old.id),null,emptyList(),emptyList(),existing.filter{it.date<effective}.map{it.id},selection.unbilledPrincipalMinor,context)
    val arrangement=FinancialArrangementEntity(s.book.id,id,old.id,target.id,effective,clock.instant().toString(),principal,input.changeFeeMinor,input.originalFeeTreatment.name,false,json.encodeToString(baseline),json.encodeToString(input),"{}",clock.millis())
    val nextFinance=f.copy(plans=f.plans+target,bills=f.bills+bills,arrangements=f.arrangements+arrangement,billAdjustments=f.billAdjustments+adjustments,repaymentAllocations=allocations)
    val refresh=prepareFinancialPool(FinancialReviewState(after,nextFinance,state.limits),definition)
    if(refresh.calculation !is Calculation.Ready)return failed(refresh.calculation)
    val finalBills=nextFinance.bills.filter{it.planId!=old.id}+refresh.bills+refresh.withdrawn
    val finalFinance=nextFinance.copy(plans=nextFinance.plans.map{if(it.id==old.id)refresh.plan else it},bills=finalBills)
    val comparison=poolArrangementComparison(s,f,after,finalFinance,existing,bills,old,refresh,target,input)
    return FinancialArrangementReview(state,input,old,calculation,target,bills,adjustments,id,baseline,comparison,deadlines,rule?.zoneId?:clock.zone.id,changes)
}

private fun LedgerRepository.poolArrangementComparison(s:BookSnapshot,f:FinancialGraph,after:BookSnapshot,next:FinancialGraph,existing:List<FinancialBillEntity>,bills:List<FinancialBillEntity>,old:FinancialPlanEntity,refresh:FinancialPredictionReview,target:FinancialPlanEntity,input:FinancialArrangementInput):FinancialArrangementComparison {
    fun row(b:FinancialBillEntity,snapshot:BookSnapshot):ArrangementMoneyRow {val r=snapshot.billOutstanding(b);return ArrangementMoneyRow(b.date,r.principalMinor,r.feeMinor,r.totalMinor,b.paymentAccountId)}
    val original=existing.map{row(it,s)}
    val keptIds=existing.filter{it.planId!=old.id}.map{it.id}.toSet()
    val proposed=(next.bills.filter{it.active&&(it.planId==old.id||it.planId==target.id||it.id in keptIds)}).map{row(it,after)}.filter{it.totalMinor>0}
    val first=original.filter{it.date>=input.terms.agreementDate&&it.totalMinor>0}.minOfOrNull{it.date}
    val originalFees=original.map{it.feeMinor}.knownTotal();val proposedFees=proposed.map{it.feeMinor}.knownTotal()
    val residual=if(!refresh.plan.complete)null else if(input.manualPeriods!=null)manualArrangementRemaining(input,target.principalMinor!!)else bills.lastOrNull()?.basisJson?.let{json.decodeFromString<FinancialCalculationBasis>(it).remainingPrincipalMinor}
    val through=maxOf(LocalDate.now(clock),LocalDate.parse((original.map{it.date}+proposed.map{it.date}).max()))
    val stale=f.plans.filter{financialPredictionStale(s,f,it)}.map{it.id}.toSet()
    val before=s.cashTimeline(f,LocalDate.now(clock),through,stale,input.referenceRates)
    val afterFunds=after.cashTimeline(next,LocalDate.now(clock),through,stale-old.id,input.referenceRates)
    val affected=(original+proposed).mapNotNull{it.paymentAccountId}.toSet()
    fun gap(t:CashTimeline,id:String,income:Boolean):Long? {val risk=t.accountRisks().first{it.opening.account.id==id};return if(income)risk.includingIncomeGapMinor else risk.maximumGapMinor}
    val funding=before.openings.map{o->val id=o.account.id;ArrangementFundingAccount(id,o.account.currency,gap(before,id,false),gap(afterFunds,id,false),gap(before,id,true),gap(afterFunds,id,true),before.accountRisks().first{it.opening.account.id==id}.firstRiskDate,afterFunds.accountRisks().first{it.opening.account.id==id}.firstRiskDate,(before.steps+afterFunds.steps).any{it.item.accountId==id&&it.item.referenceRate!=null})}.filter{it.accountId in affected||it.beforeGapMinor!=0L||it.afterGapMinor!=0L}
    val originalResidual=if(old.complete)0L else json.decodeFromString<FinancialPredictionInput>(old.termsJson!!).projections.lastOrNull()?.basis?.remainingPrincipalMinor?:0L
    return FinancialArrangementComparison(original,proposed,first,first?.let{date->subtractExact(original.filter{it.date<=date}.sumOf{it.totalMinor},proposed.filter{it.date<=date}.sumOf{it.totalMinor})},originalFees,proposedFees,if(old.complete&&residual==0L&&originalFees!=null&&proposedFees!=null&&(input.manualPeriods==null||input.manualComplete))subtractExact(proposedFees,originalFees)else null,originalResidual,residual,funding,through.toString(),(before.steps+afterFunds.steps).any{row->before.openings.none{it.account.id==row.item.accountId}},(before.steps+afterFunds.steps).mapNotNull{it.item.referenceRate}.distinct(),originalComplete=old.complete,proposedComplete=refresh.plan.complete&&target.complete)
}
