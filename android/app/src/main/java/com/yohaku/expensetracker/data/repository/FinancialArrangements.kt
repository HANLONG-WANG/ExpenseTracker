package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.first
import java.time.*
import com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot

@Serializable enum class OriginalFeeTreatment { RETAIN, CANCELLED_BY_PLATFORM }
@Serializable data class FinancialArrangementInput(
    val planId:String,val name:String,val mode:FinanceMode,val terms:FinancialScheduleTerms,
    val paymentAccountId:String?,val originalFeeTreatment:OriginalFeeTreatment?=null,val changeFeeMinor:Long?=null,val referenceRates:List<ReferenceRateSnapshot> = emptyList(),
    val clearingMoves:List<ArrangementClearingMove> = emptyList(),val poolSelection:PoolArrangementSelection?=null,
    val mergePoolId:String?=null,val mergeFeeDate:String?=null,val mergeFeeIncludedInRetained:Boolean=false,val loanLimitDeltaMinor:Long?=null,
    val manualPeriods:List<KnownBillInput>?=null,val manualComplete:Boolean=false,
    val transferPrincipalMinor:Long?=null,val undoGroupId:String?=null
)
/** Null period keeps this portion on its original bill; positive periods belong to the new plan. */
@Serializable data class ArrangementClearingMove(val allocationId:String,val period:Int?,val principalMinor:Long,val interestMinor:Long,val feeMinor:Long,val poolBillId:String?=null)
data class ArrangementClearingChange(val original:FinancialRepaymentAllocationEntity,val replacements:List<FinancialRepaymentAllocationEntity>)
@Serializable data class ArrangementClearingProof(val eventId:String,val date:String,val principalMinor:Long)
@Serializable data class FinancialArrangementBaseline(
    val sourceGrossMinor:Long,val sourceDate:String,val sourcePlanIds:List<String>,val contractId:String?,
    val clearings:List<ArrangementClearingProof>,val refunds:List<PredictionRefundFact>,val retainedBillIds:List<String>,val unbilledPrincipalMinor:Long,val pool:PoolArrangementBaseline?=null
)
@Serializable data class ArrangementMoneyRow(val date:String,val principalMinor:Long?,val feeMinor:Long?,val totalMinor:Long,val paymentAccountId:String?)
@Serializable data class ArrangementFundingAccount(val accountId:String,val currency:String,val beforeGapMinor:Long?,val afterGapMinor:Long?,val beforeIncomeGapMinor:Long?,val afterIncomeGapMinor:Long?,val beforeRiskDate:String?,val afterRiskDate:String?,val referenceEstimated:Boolean=false)
@Serializable data class FinancialArrangementComparison(
    val original:List<ArrangementMoneyRow>,val proposed:List<ArrangementMoneyRow>,val firstDueDate:String?,
    val currentReductionMinor:Long?,val feesInOriginalHorizonMinor:Long?,val feesInProposedHorizonMinor:Long?,
    val totalExtraFeeMinor:Long?,val originalUnscheduledPrincipalMinor:Long,val proposedRemainingPrincipalMinor:Long?,val funding:List<ArrangementFundingAccount> = emptyList(),val fundingThrough:String?=null,val fundingUnassigned:Boolean=false,val fundingRates:List<ReferenceRateSnapshot> = emptyList(),
    val originalComplete:Boolean=false,val proposedComplete:Boolean=false
)
class FinancialArrangementReview internal constructor(
    internal val before:FinancialReviewState,val input:FinancialArrangementInput,val original:FinancialPlanEntity,
    val calculation:Calculation<List<PredictedSchedulePeriod>>,val target:FinancialPlanEntity?,val bills:List<FinancialBillEntity>,
    val adjustments:List<FinancialBillAdjustmentEntity>,val arrangementId:String,val baseline:FinancialArrangementBaseline?,
    val comparison:FinancialArrangementComparison?,val deadlines:List<ChangeDeadline>,val zoneId:String,
    val clearingChanges:List<ArrangementClearingChange> = emptyList(),val mergedPool:FinancialPredictionReview?=null,val mergeSeed:FinancialPredictionReview?=null
)

internal fun arrangementLaterAllocations(s:BookSnapshot,f:FinancialGraph,old:FinancialPlanEntity,effective:String):List<FinancialRepaymentAllocationEntity> {
    if(old.origin=="POOL") {
        val ids=f.plans.filter{it.accountId==old.accountId&&it.mode==old.mode&&(it.active||it.origin=="POOL_RETAINED")}.map{it.id}.toSet()
        val bills=f.bills.filter{it.planId in ids&&it.active&&it.date>=effective}.map{it.id}.toSet()
        return s.appliedRepaymentAllocations().filter{it.billId in bills&&s.repayments.first{p->p.eventId==it.eventId}.clearingDate!!>effective}.sortedBy{it.id}
    }
    return predictionFacts(s,f,old.id,old.sourceEventId,effective).repayments.filter{it.repayment.platformConfirmed&&it.repayment.clearingDate!!>effective}
        .flatMap{it.allocations}.filter{row->row.billId==null||f.bills.first{it.id==row.billId}.date>=effective}.sortedBy{it.id}
}

internal fun arrangementLineage(f:FinancialGraph,planId:String):List<String> {
    val ids=mutableListOf(planId)
    var parent=f.arrangements.firstOrNull{it.targetPlanId==planId}
    while(parent!=null) {ids+=parent.originalPlanId;parent=f.arrangements.firstOrNull{it.targetPlanId==parent!!.originalPlanId}}
    return ids
}
internal fun arrangementClearings(s:BookSnapshot,f:FinancialGraph,planIds:List<String>,contractId:String?):List<ArrangementClearingProof> {
    val bills=f.bills.filter{it.planId in planIds}.map{it.id}.toSet()
    val payments=s.appliedRepaymentAllocations().filter{it.billId in bills||it.billId==null&&contractId!=null&&it.contractId==contractId}
    return payments.groupBy{it.eventId}.map{(id,rows)->ArrangementClearingProof(id,s.repayments.first{it.eventId==id}.clearingDate!!,rows.fold(0L){sum,a->Math.addExact(sum,a.principalMinor!!)})}.sortedBy{it.eventId}
}
internal fun LedgerRepository.arrangementBaselineIssue(s:BookSnapshot,f:FinancialGraph,plan:FinancialPlanEntity):String? {
    val applied=f.currentArrangements.firstOrNull{it.targetPlanId==plan.id}?:return null
    plan.reconciliation()?.let{return reconciliationIssue(s,f,plan,it)}
    f.arrangements.firstOrNull{it.targetPlanId==applied.originalPlanId}?.let{parent->if(f.plans.first{it.id==parent.targetPlanId}.let{it.reconciliation()!=null||arrangementBaselineIssue(s,f,it)!=null})return "arrangement.parentReview"}
    val base=json.decodeFromString<FinancialArrangementBaseline>(applied.baselineJson)
    if(plan.sourceEventId==null&&f.arrangements.none{it.targetPlanId==applied.originalPlanId}&&base.contractId!=null&&f.contracts.firstOrNull{it.id==base.contractId}?.principalMinor!=base.sourceGrossMinor)return "arrangement.sourceChanged"
    if(base.pool!=null)return poolArrangementBaselineIssue(s,f,applied,base.pool)
    val current=arrangementClearings(s,f,base.sourcePlanIds,base.contractId).filter{it.date<=applied.effectiveDate}
    if(current!=base.clearings)return "arrangement.baselineChanged"
    plan.sourceEventId?.let{id->
        val source=s.visibleEvents.firstOrNull{it.id==id&&it.accountId==plan.accountId}?:return "arrangement.sourceChanged"
        if(source.date!=base.sourceDate||s.accountMoney(source).minor!=base.sourceGrossMinor)return "arrangement.sourceChanged"
    }
    val postContract=arrangementClearings(s,f,emptyList(),base.contractId).any{p->base.clearings.none{it.eventId==p.eventId}}
    val retained=f.bills.filter{it.planId in base.sourcePlanIds&&it.active&&s.billOutstanding(it).totalMinor>0}
    for(refund in base.refunds) {
        val settlement=s.settlements.firstOrNull{it.eventId==refund.eventId&&it.confirmed&&s.visibleEvents.any{e->e.id==refund.eventId}}?:return "arrangement.baselineChanged"
        val amount=s.postings.filter{it.eventId==refund.eventId&&it.accountId==plan.accountId}.fold(0L){sum,p->Math.subtractExact(sum,p.deltaMinor)}
        if(amount!=refund.principalMinor||settlement.settlementDate!=refund.date)return "arrangement.baselineChanged"
    }
    val newRefunds=s.settlements.filter{plan.sourceEventId!=null&&it.originalEventId==plan.sourceEventId&&it.confirmed&&s.visibleEvents.any{e->e.id==it.eventId}&&base.refunds.none{r->r.eventId==it.eventId}}
        .filter{r->s.postings.any{it.eventId==r.eventId&&it.accountId==plan.accountId&&it.deltaMinor<0}}
    if(newRefunds.any{it.settlementDate<applied.effectiveDate})return "arrangement.baselineChanged"
    if(newRefunds.isNotEmpty()&&retained.any{s.billOutstanding(it).principalMinor?.let{n->n>0}!=false})return "arrangement.allocateRefund"
    if(postContract&&retained.any{s.billOutstanding(it).principalMinor?.let{n->n>0}!=false})return "arrangement.allocateClearing"
    return null
}

suspend fun LedgerRepository.previewFinancialArrangement(bookId:String,input:FinancialArrangementInput):FinancialArrangementReview=db.withTransaction {
    prepareFinancialArrangement(financialReviewState(bookId),input)
}

internal fun LedgerRepository.prepareFinancialArrangement(state:FinancialReviewState,input:FinancialArrangementInput):FinancialArrangementReview {
    val s=state.ledger;val f=state.finance
    val old=requireNotNull(f.plans.firstOrNull{it.id==input.planId&&it.active}){"请选择当前计划 / 適用中の予定を選択してください"}
    if(old.origin=="POOL")return preparePoolArrangement(state,input,old)
    require(old.origin !in listOf("POOL","POOL_RETAINED")){"账户池中的部分本金变更需先核对具体来源和保留账单 / プール内の一部元金の変更は対象と保持請求の確認が必要です"}
    require(f.arrangements.none{it.originalPlanId==old.id}){"此安排已经变更，请选择当前方案 / この予定は変更済みです。現在の予定を選択してください"}
    val account=s.accounts.first{it.id==old.accountId};requireMode(account,input.mode)
    require(input.name.isNotBlank());LocalDate.parse(input.terms.agreementDate)
    require(input.terms.paymentDates.isNotEmpty()&&input.terms.paymentDates.all{it>=input.terms.agreementDate}){"新付款日不能早于变更生效日 / 新しい支払日は変更適用日以降にしてください"}
    input.paymentAccountId?.let{id->require(s.accounts.any{it.id==id&&it.active&&!AccountType.valueOf(it.type).debt})}
    val existing=f.bills.filter{it.planId==old.id&&it.active}.sortedBy{it.number}
    val versions=f.rules.filter{it.accountId==old.accountId}.map{EffectiveFinancialRule(it.version,json.decodeFromString<FinancialRuleVersion>(it.json))}
    val rule=versions.filter{it.rule.mode==input.mode&&it.rule.effectiveFrom<=input.terms.agreementDate}.maxWithOrNull(compareBy<EffectiveFinancialRule>{it.rule.effectiveFrom}.thenBy{it.version})?.rule
    val deadlines=existing.filter{it.date>=input.terms.agreementDate}.flatMap{bill->rule?.deadlines.orEmpty().filter{it.billId==bill.id&&it.changeType==input.mode}}
    val arrangementId=id()
    fun incomplete(field:String)=FinancialArrangementReview(state,input,old,Calculation.Incomplete(listOf(field)),null,emptyList(),emptyList(),arrangementId,null,null,deadlines,rule?.zoneId?:clock.zone.id)
    val destination=f.plans.firstOrNull{it.accountId==old.accountId&&it.mode==input.mode.name&&it.origin=="POOL"&&it.active}
    if(destination!=null&&input.mergePoolId!=destination.id)return incomplete("arrangement.poolChoice")
    if(input.mergePoolId!=null&&destination?.id!=input.mergePoolId)return incomplete("arrangement.poolChanged")
    if(input.originalFeeTreatment==null)return incomplete("arrangement.originalFees")
    if(input.changeFeeMinor==null)return incomplete("arrangement.changeFee")
    require(input.changeFeeMinor>=0)
    if(old.sourceEventId!=null&&s.visibleEvents.none{it.id==old.sourceEventId&&it.accountId==old.accountId})return incomplete("arrangement.sourceChanged")
    arrangementBaselineIssue(s,f,old)?.let{return incomplete(it)}
    if(old.termsJson!=null&&financialPredictionStale(s,f,old)&&existing.any{!it.confirmed&&s.billOutstanding(it).totalMinor>0})return incomplete("arrangement.refreshOriginal")
    val incoming=f.currentArrangements.firstOrNull{it.targetPlanId==old.id}
    if(incoming!=null&&input.terms.agreementDate<incoming.effectiveDate)return incomplete("arrangement.effectiveBeforeOriginal")
    if(old.sourceEventId!=null&&s.settlements.any{it.eventId==old.sourceEventId&&!it.confirmed})return incomplete("arrangement.confirmSource")
    val facts=predictionFacts(s,f,old.id,old.sourceEventId,input.terms.agreementDate)
    if(facts.repayments.any{!it.repayment.platformConfirmed})return incomplete("arrangement.pendingClearing")
    val effective=input.terms.agreementDate
    if(facts.refunds.any{it.date>effective})return incomplete("arrangement.laterRefund")
    val later=arrangementLaterAllocations(s,f,old,effective)
    if(input.clearingMoves.any{move->later.none{it.id==move.allocationId}})return incomplete("arrangement.clearingChanged")
    if(later.any{row->input.clearingMoves.none{it.allocationId==row.id}})return incomplete("arrangement.laterClearing")
    for(row in later) {
        val moves=input.clearingMoves.filter{it.allocationId==row.id}
        if(moves.any{it.period?.let{p->p !in 1..input.terms.paymentDates.size}==true||it.period==null&&it.poolBillId==null&&row.billId==null||listOf(it.principalMinor,it.interestMinor,it.feeMinor).any{n->n<0}||Math.addExact(it.principalMinor,Math.addExact(it.interestMinor,it.feeMinor))==0L})return incomplete("arrangement.clearingSplit")
        if(moves.sumOf{it.principalMinor}!=row.principalMinor||moves.sumOf{it.interestMinor}!=row.interestMinor||moves.sumOf{it.feeMinor}!=row.feeMinor)return incomplete("arrangement.clearingSplit")
    }
    val prePayments=facts.repayments.filter{it.repayment.clearingDate!!<=effective}.map{it.eventId}.toSet()
    val atEffective=s.copy(repaymentAllocations=s.repaymentAllocations.filter{row->facts.repayments.none{it.eventId==row.eventId}||row.eventId in prePayments})
    val gross=incoming?.principalMinor?:old.sourceEventId?.let{id->s.visibleEvents.firstOrNull{it.id==id}?.let{s.accountMoney(it).minor}}?:old.principalMinor?:return incomplete("arrangement.sourcePrincipal")
    val remaining=Math.subtractExact(gross,facts.principalFacts().filter{it.date<=effective}.fold(0L){sum,p->Math.addExact(sum,p.principalMinor)})
    val retained=existing.filter{it.date<input.terms.agreementDate}
    if(existing.any{s.billOutstanding(it).principalMinor==null||s.billOutstanding(it).feeMinor==null})return incomplete("arrangement.originalBreakdown")
    if(existing.any{s.billOutstanding(it).totalMinor<0||s.billOutstanding(it).principalMinor!!<0||s.billOutstanding(it).feeMinor!!<0})return incomplete("arrangement.originalBreakdown")
    val kept=input.clearingMoves.filter{it.period==null&&it.poolBillId==null}.groupBy{move->later.first{it.id==move.allocationId}.billId}
    val reserved=Math.addExact(retained.fold(0L){sum,b->Math.addExact(sum,atEffective.billOutstanding(b).principalMinor!!)},kept.values.flatten().sumOf{it.principalMinor})
    val available=Math.subtractExact(remaining,reserved)
    val principal=input.transferPrincipalMinor?:available
    if(input.transferPrincipalMinor!=null) {
        if(old.mode!="SINGLE"||input.mergePoolId==null||existing.size!=1||existing.single().date<maxOf(effective,LocalDate.now(clock).toString())||facts.repayments.isNotEmpty())return incomplete("arrangement.partialSingle")
        if(principal<=0||principal>available||principal>atEffective.billOutstanding(existing.single()).principalMinor!!)return incomplete("arrangement.partialAmount")
    }
    if(principal<=0)return incomplete("arrangement.noEligiblePrincipal")
    if(input.loanLimitDeltaMinor?.let{it < -principal||it>principal}==true)return incomplete("arrangement.limitDelta")
    val eligible=existing.filter{it.date>=input.terms.agreementDate}
    if(eligible.any{s.billOutstanding(it).totalMinor<0||s.billOutstanding(it).principalMinor!!<0||s.billOutstanding(it).feeMinor!!<0})return incomplete("arrangement.originalBreakdown")
    val moved=if(input.transferPrincipalMinor!=null)principal else eligible.fold(0L){sum,b->Math.addExact(sum,Math.subtractExact(atEffective.billOutstanding(b).principalMinor!!,kept[b.id].orEmpty().sumOf{it.principalMinor}))}
    if(moved>principal)return incomplete("arrangement.originalBreakdown")
    val adjusted=eligible.map{bill->val due=atEffective.billOutstanding(bill);FinancialBillAdjustmentEntity(s.book.id,id(),arrangementId,bill.id,if(input.transferPrincipalMinor!=null)principal else Math.subtractExact(due.principalMinor!!,kept[bill.id].orEmpty().sumOf{it.principalMinor}),if(input.originalFeeTreatment==OriginalFeeTreatment.CANCELLED_BY_PLATFORM&&input.transferPrincipalMinor==null)Math.subtractExact(due.feeMinor!!,kept[bill.id].orEmpty().sumOf{Math.addExact(it.interestMinor,it.feeMinor)})else 0)}
    if(adjusted.any{it.principalMinor<0||it.feeMinor<0})return incomplete("arrangement.clearingSplit")
    if(input.mergePoolId!=null) {
        if(input.manualPeriods!=null)return incomplete("arrangement.manualMerge")
        return prepareExistingPoolMerge(state,input,old,principal,existing,adjusted,later,facts,remaining,moved,deadlines,rule?.zoneId?:clock.zone.id)
    }
    if(input.clearingMoves.any{it.poolBillId!=null})return incomplete("arrangement.poolClearingChoice")
    val terms=input.terms.copy(additionalFees=listOf(AdditionalPeriodFee(1,input.changeFeeMinor)))
    val movedFacts=input.clearingMoves.filter{it.period!=null}.map{move->val row=later.first{it.id==move.allocationId};SchedulePrincipalFact(row.eventId,s.repayments.first{it.eventId==row.eventId}.clearingDate!!,move.principalMinor,move.period)}
    val calculation=if(input.manualPeriods!=null)Calculation.Ready(emptyList())else reconciledSchedule(principal,input.mode,terms,versions,emptyList(),movedFacts,LocalDate.parse(effective))
    val periods=(calculation as? Calculation.Ready)?.value.orEmpty()
    val target=FinancialPlanEntity(s.book.id,id(),old.accountId,input.name.trim(),input.mode.name,"ARRANGEMENT",old.sourceEventId,old.currency,principal,
        if(input.manualPeriods!=null)input.manualComplete else periods.lastOrNull()?.basis?.remainingPrincipalMinor==0L,clock.millis(),termsJson=if(input.manualPeriods!=null)null else json.encodeToString(FinancialPredictionInput(old.accountId,input.name,input.mode,terms,old.sourceEventId,if(old.sourceEventId==null)principal else null,input.paymentAccountId,currentArrangementConfirmed=true)))
    val bills=manualArrangementBills(s,input,target)?:periods.map{p->FinancialBillEntity(s.book.id,id(),target.id,p.period.number,input.paymentAccountId?:rule?.billing?.paymentAccountId,p.ruleId,p.period.date.toString(),p.period.principalMinor,p.period.feeMinor,p.period.totalMinor,json.encodeToString(p.basis),null,null,null,null,null)}
    val changes=later.map{row->val moves=input.clearingMoves.filter{it.allocationId==row.id};ArrangementClearingChange(row,if(moves.size==1&&moves.single().period==null)listOf(row)else moves.map{move->
        row.copy(id=id(),billId=if(move.period==null)row.billId else bills.firstOrNull{it.number==move.period}?.id,principalMinor=move.principalMinor,interestMinor=move.interestMinor,feeMinor=move.feeMinor)
    })}
    val nextAllocations=s.repaymentAllocations.filter{row->later.none{it.id==row.id}}+changes.flatMap{it.replacements}
    val nextSnapshot=s.copy(billAdjustments=s.billAdjustments+adjusted,repaymentAllocations=nextAllocations)
    if(calculation is Calculation.Ready&&bills.any{val due=nextSnapshot.billOutstanding(it);due.principalMinor?.let{n->n<0}==true||due.feeMinor?.let{n->n<0}==true||due.totalMinor<0})return incomplete("arrangement.clearingExceedsBill")
    val sourceGross=old.sourceEventId?.let{id->s.visibleEvents.firstOrNull{it.id==id}?.let{s.accountMoney(it).minor}}?:gross
    val sourceDate=old.sourceEventId?.let{id->s.visibleEvents.firstOrNull{it.id==id}?.date}?:input.terms.agreementDate
    val lineage=arrangementLineage(f,old.id)
    val contractId=f.contractPlans.firstOrNull{it.planId==old.id}?.contractId?:old.sourceEventId?.let{id->f.contracts.firstOrNull{it.fundingEventId==id}?.id}
    val allRefunds=old.sourceEventId?.let{id->s.settlements.filter{it.originalEventId==id&&it.confirmed&&s.visibleEvents.any{e->e.id==it.eventId}}.mapNotNull{r->
        val reduction=s.postings.filter{it.eventId==r.eventId&&it.accountId==old.accountId}.fold(0L){sum,p->Math.subtractExact(sum,p.deltaMinor)}
        if(reduction>0)PredictionRefundFact(r.eventId,s.events.first{it.id==r.eventId}.revision,r.settlementDate,reduction)else null
    }}.orEmpty().sortedBy{it.eventId}
    val baseline=FinancialArrangementBaseline(sourceGross,sourceDate,lineage,contractId,arrangementClearings(s,f,lineage,contractId).filter{it.date<=effective},allRefunds,retained.map{it.id},principal-moved)
    fun row(b:FinancialBillEntity,snapshot:BookSnapshot):ArrangementMoneyRow {val due=snapshot.billOutstanding(b);return ArrangementMoneyRow(b.date,due.principalMinor,due.feeMinor,due.totalMinor,b.paymentAccountId)}
    val original=existing.map{row(it,s)}
    val proposed=existing.map{row(it,nextSnapshot)}.filter{it.totalMinor!=0L}+bills.map{row(it,nextSnapshot)}
    val first=original.filter{it.date>=input.terms.agreementDate&&it.totalMinor>0}.minOfOrNull{it.date}
    fun sum(rows:List<ArrangementMoneyRow>,amount:(ArrangementMoneyRow)->Long)=rows.fold(0L){n,r->Math.addExact(n,amount(r))}
    val oldFees=if(original.any{it.feeMinor==null})null else sum(original){it.feeMinor!!}
    val newFees=if(proposed.any{it.feeMinor==null})null else sum(proposed){it.feeMinor!!}
    val originalResidual=Math.subtractExact(Math.subtractExact(remaining,facts.principalFacts().filter{it.date>effective}.sumOf{it.principalMinor}),existing.fold(0L){n,b->Math.addExact(n,s.billOutstanding(b).principalMinor!!)})
    val residual=if(input.manualPeriods!=null)manualArrangementRemaining(input,principal)else (calculation as? Calculation.Ready)?.value?.lastOrNull()?.basis?.remainingPrincipalMinor
    val asOf=LocalDate.now(clock)
    val horizon=maxOf(asOf,LocalDate.parse((existing.map{it.date}+bills.map{it.date}).max()))
    val stale=f.plans.filter{financialPredictionStale(s,f,it)}.map{it.id}.toSet()
    val beforeFunds=s.cashTimeline(f,asOf,horizon,stale,input.referenceRates)
    val withoutPayments=nextSnapshot.copy(repaymentAllocations=emptyList())
    val afterFinance=f.copy(plans=f.plans.map{if(it.id==old.id)it.copy(active=existing.any{b->withoutPayments.billOutstanding(b).totalMinor>0})else it}+target,bills=f.bills+bills,billAdjustments=f.billAdjustments+adjusted,repaymentAllocations=nextAllocations)
    val afterFunds=nextSnapshot.cashTimeline(afterFinance,asOf,horizon,stale-old.id,input.referenceRates)
    fun gap(t:CashTimeline,accountId:String,including:Boolean):Long? {
        val opening=t.openings.first{it.account.id==accountId}.actualMinor
        val values=t.steps.filter{it.item.accountId==accountId}.map{if(including)it.includingIncomeAfterMinor else it.baselineAfterMinor}
        if(values.any{it==null})return null
        return Math.negateExact(minOf(opening,values.filterNotNull().minOrNull()?:opening,0L))
    }
    fun riskDate(t:CashTimeline,accountId:String):String? = if(t.openings.first{it.account.id==accountId}.actualMinor<0)t.asOf else t.steps.firstOrNull{it.item.accountId==accountId&&it.baselineAfterMinor?.let{n->n<0}==true}?.item?.date
    val affected=(original+proposed).mapNotNull{it.paymentAccountId}.toSet()
    val funding=beforeFunds.openings.map{a->ArrangementFundingAccount(a.account.id,a.account.currency,gap(beforeFunds,a.account.id,false),gap(afterFunds,a.account.id,false),gap(beforeFunds,a.account.id,true),gap(afterFunds,a.account.id,true),riskDate(beforeFunds,a.account.id),riskDate(afterFunds,a.account.id),(beforeFunds.steps+afterFunds.steps).any{it.item.accountId==a.account.id&&it.item.referenceRate!=null})}
        .filter{it.accountId in affected||it.beforeGapMinor!=0L||it.afterGapMinor!=0L}
    val fundIds=beforeFunds.openings.map{it.account.id}.toSet()
    val unassigned=(beforeFunds.steps+afterFunds.steps).any{it.item.accountId !in fundIds}
    val comparison=FinancialArrangementComparison(original,proposed,first,first?.let{d->sum(original.filter{it.date<=d}){it.totalMinor}-sum(proposed.filter{it.date<=d}){it.totalMinor}},oldFees,newFees,
        if(old.complete&&oldFees!=null&&newFees!=null&&originalResidual==0L&&residual==0L&&(input.manualPeriods==null||input.manualComplete))newFees-oldFees else null,originalResidual,residual,funding,horizon.toString(),unassigned,(beforeFunds.steps+afterFunds.steps).mapNotNull{it.item.referenceRate}.distinct(),originalComplete=old.complete,proposedComplete=target.complete)
    return FinancialArrangementReview(state,input,old,calculation,target,bills,adjusted,arrangementId,baseline,comparison,deadlines,rule?.zoneId?:clock.zone.id,changes)
}

/** Only records a platform action the user has already completed; it performs no external action. */
suspend fun LedgerRepository.commitFinancialArrangement(review:FinancialArrangementReview,appliedAt:String,platformConfirmed:Boolean,exceptionAccepted:Boolean=false):String?=db.withTransaction {
    require(platformConfirmed){"请先确认已在平台实际办理 / 返済先で手続き済みであることを確認してください"}
    require(review.calculation is Calculation.Ready)
    val instant=Instant.parse(appliedAt)
    require(instant<=clock.instant()){"已办理时间不能在未来 / 手続き済み時刻を未来に設定できません"}
    require(exceptionAccepted||review.deadlines.all{instant<=it.instant()}){"已超过配置期限；如平台确已接受，请明确记录期限外办理 / 設定期限を過ぎています。返済先が受理済みなら期限外の手続きを明示してください"}
    if(financialReviewState(review.original.bookId)!=review.before)return@withTransaction null
    val dao=db.financialDao();val target=review.target!!
    val originalBills=review.before.finance.bills.filter{it.planId==review.original.id}
    archiveExistingPlan(review.original,originalBills,predictionFacts(review.before.ledger,review.before.finance,review.original.id,review.original.sourceEventId,review.input.terms.agreementDate))
    review.mergeSeed?.let{seed->
        val originalPool=review.before.finance.plans.first{it.id==seed.plan.id}
        archiveExistingPlan(originalPool,review.before.finance.bills.filter{it.planId==originalPool.id},predictionFacts(review.before.ledger,review.before.finance,originalPool.id,null,review.input.terms.agreementDate))
        val referenced=review.clearingChanges.flatMap{it.replacements}.mapNotNull{it.billId}.toSet()
        seed.bills.filter{it.id in referenced}.forEach{bill->if(review.before.finance.bills.any{it.id==bill.id})dao.updateBill(bill)else dao.insertBills(listOf(bill))}
    }
    dao.insertPlan(target);dao.insertBills(review.bills)
    val arrangement=FinancialArrangementEntity(target.bookId,review.arrangementId,review.original.id,target.id,review.input.terms.agreementDate,appliedAt,target.principalMinor!!,review.input.changeFeeMinor!!,review.input.originalFeeTreatment!!.name,exceptionAccepted,
        json.encodeToString(review.baseline!!),json.encodeToString(review.input),json.encodeToString(review.comparison!!),clock.millis())
    dao.insertArrangement(arrangement);dao.insertBillAdjustments(review.adjustments)
    review.baseline.contractId?.let{dao.putContractPlan(FinancialContractPlanEntity(target.bookId,target.id,it))}
    for((eventId,changes) in review.clearingChanges.filter{it.replacements!=listOf(it.original)}.groupBy{it.original.eventId}) {
        val state=financialReviewState(target.bookId);val s=state.ledger
        val previous=s.visibleEvents.first{it.id==eventId}
        val parts=s.repaymentAllocations.filter{it.eventId==eventId&&changes.none{c->c.original.id==it.id}}+changes.flatMap{it.replacements}
        val rows=parts.groupBy{it.billId to it.contractId}.values.map{group->group.first().copy(principalMinor=group.fold(0L){n,r->Math.addExact(n,r.principalMinor!!)},interestMinor=group.fold(0L){n,r->Math.addExact(n,r.interestMinor!!)},feeMinor=group.fold(0L){n,r->Math.addExact(n,r.feeMinor!!)})}
        val next=s.copy(events=s.events.map{if(it.id==eventId)it.copy(revision=it.revision+1)else it},repaymentAllocations=s.repaymentAllocations.filter{it.eventId!=eventId}+rows)
        val change=PreparedLedgerChange(previous,previous.copy(revision=previous.revision+1),s.settlements.first{it.eventId==eventId},s.storedPostings.filter{it.eventId==eventId},components=s.expenseComponents.filter{it.eventId==eventId},activityId=s.eventActivities.firstOrNull{it.eventId==eventId}?.activityId,repayment=s.repayments.first{it.eventId==eventId},repaymentAllocations=rows)
        val impacts=financialPlanImpacts(state,next,change,null).filter{it.original.id!=target.id&&it.original.id!=review.original.id&&it.original.id!=review.input.mergePoolId}
        impacts.forEach{archiveExistingPlan(it.original,it.originalBills,predictionFacts(s,state.finance,it.original.id,it.original.sourceEventId,LocalDate.now(clock).toString()))}
        writeChange(change);writeFinancialPlanImpacts(impacts,"ARRANGEMENT_CLEARING",eventId)
    }
    val afterLedger=snapshot(this.dao.book(target.bookId)!!).first()
    // Paid retained obligations must remain reachable if the payment is later deleted/restored.
    val withoutPayments=afterLedger.copy(repaymentAllocations=emptyList())
    val retained=originalBills.any{it.active&&withoutPayments.billOutstanding(it).totalMinor>0}
    val old=review.original.copy(active=review.original.origin=="POOL"||retained,revision=Math.addExact(review.original.revision,1))
    dao.updatePlan(old)
    val nextFinance=dao.graph(target.bookId).first()!!
    val facts=predictionFacts(afterLedger,nextFinance,target.id,target.sourceEventId,arrangement.effectiveDate)
    val definition=target.termsJson?.let{json.decodeFromString<FinancialPredictionInput>(it).copy(planId=target.id,facts=facts,projections=if(review.input.mergePoolId!=null)emptyList()else (review.calculation as Calculation.Ready).value.map{FinancialPeriodProjection(it.period.number,it.period.date.toString(),it.period.principalMinor,it.period.feeMinor,it.basis)})}
    val final=target.copy(termsJson=definition?.let{json.encodeToString(it)});dao.updatePlan(final)
    archiveFinancialPlan(old,originalBills,"ARRANGEMENT_RETAINED",facts=predictionFacts(afterLedger,nextFinance,old.id,old.sourceEventId,arrangement.effectiveDate))
    archiveFinancialPlan(final,review.bills,if(review.input.mergePoolId==null)"ARRANGEMENT_APPLIED"else"POOL_MERGED",facts=facts,poolMerge=review.input.mergePoolId?.let{id->PoolMergeArchive(arrangement,review.before.finance.plans.first{it.id==id})})
    val refreshPoolId=review.input.mergePoolId?:review.original.takeIf{it.origin=="POOL"}?.id
    if(refreshPoolId!=null) {
        val state=financialReviewState(target.bookId)
        val current=state.finance.plans.first{it.id==refreshPoolId}
        val definition=(review.mergedPool?.input?:json.decodeFromString<FinancialPredictionInput>(current.termsJson!!)).copy(planId=current.id)
        val refreshed=prepareFinancialPool(state,definition)
        check(refreshed.calculation is Calculation.Ready){"池内剩余安排无法重算，请核对后再办理 / 残プールを再計算できません。確認してから手続きしてください"}
        writeFinancialPrediction(refreshed,"POOL_ARRANGEMENT",null)
    }
    target.id
}
