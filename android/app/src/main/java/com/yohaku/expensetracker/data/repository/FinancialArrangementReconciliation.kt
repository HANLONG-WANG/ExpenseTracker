package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.finance.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.lang.Math.addExact
import java.lang.Math.subtractExact

@Serializable data class ReconciledBillObligation(val billId:String,val principalMinor:Long?,val feeMinor:Long?)
@Serializable data class ReconciledRefundBill(val billId:String,val principalMinor:Long)
@Serializable data class ReconciledRefundShare(val eventId:String,val targetMinor:Long,val bills:List<ReconciledRefundBill> = emptyList())
@Serializable data class ReconciledClearingMove(val allocationId:String,val billId:String?,val principalMinor:Long,val interestMinor:Long,val feeMinor:Long)
@Serializable data class ArrangementReconciliationInput(val planId:String,val effectiveDate:String,val principalMinor:Long?,
    val bills:List<ReconciledBillObligation>,val refunds:List<ReconciledRefundShare>,val clearingMoves:List<ReconciledClearingMove> = emptyList(),
    val openingSourceMinor:Long?=null,val manualComplete:Boolean?=null,val note:String="",val loanLimitDeltaMinor:Long?=null,val poolSourceDate:PoolSourceDate?=null,val poolUnallocatedShares:Map<String,Long>?=null)
@Serializable data class ReconciliationSourceProof(val sourceId:String?,val exists:Boolean,val grossMinor:Long?,val date:String,val openingMinor:Long,
    val contractPrincipalMinor:Long?=null,val contractMode:String?=null)
@Serializable data class ScopedArrangementRefund(val fact:PredictionRefundFact,val targetMinor:Long,val bills:List<ReconciledRefundBill>)
@Serializable data class ArrangementReconciliation(val id:String,val arrangementId:String,val recordedAt:String,val input:ArrangementReconciliationInput,
    val source:ReconciliationSourceProof,val parentReviewId:String?,val baseline:FinancialArrangementBaseline,val refunds:List<ScopedArrangementRefund>,
    val physicalRefunds:List<PredictionRefundFact>,val unassignedOriginalMinor:Long,val verifiedBills:List<FinancialBillEntity> = emptyList())
class ArrangementReconciliationReview internal constructor(internal val before:FinancialReviewState,val input:ArrangementReconciliationInput,
    val original:FinancialPlanEntity,val calculation:Calculation<Unit>,val record:ArrangementReconciliation?,val plans:List<FinancialPlanEntity>,
    val adjustments:List<FinancialBillAdjustmentEntity>,val clearingChanges:List<ArrangementClearingChange>,val prediction:FinancialPredictionReview?,val poolPrediction:FinancialPredictionReview?=null)

internal fun FinancialGraph.reconciliationPool(target:FinancialPlanEntity):FinancialPlanEntity? {
    if(target.origin=="POOL_RETAINED")return plans.firstOrNull{it.origin=="POOL"&&it.active&&it.accountId==target.accountId&&it.mode==target.mode}
    if(target.origin!="POOL_MEMBER")return null
    val arrangement=arrangements.first{it.targetPlanId==target.id}
    val definition=kotlinx.serialization.json.Json.decodeFromString<FinancialArrangementInput>(arrangement.inputJson)
    return plans.first{it.id==definition.mergePoolId&&it.origin=="POOL"}
}
internal fun FinancialGraph.reconciliationTargetBills(target:FinancialPlanEntity):List<FinancialBillEntity> {
    val pool=reconciliationPool(target)?:return bills.filter{it.planId==target.id&&it.active}
    val members=plans.filter{it.accountId==pool.accountId&&it.mode==pool.mode&&(it.id==pool.id||it.origin=="POOL_RETAINED")}.map{it.id}.toSet()
    return bills.filter{it.active&&it.planId in members}
}

internal fun FinancialPlanEntity.reconciliation()=reconciliationJson?.let{kotlinx.serialization.json.Json.decodeFromString<ArrangementReconciliation>(it)}
internal fun FinancialGraph.currentArrangement(row:FinancialArrangementEntity):FinancialArrangementEntity {
    val r=plans.first{it.id==row.targetPlanId}.reconciliation()?:return row
    val input=kotlinx.serialization.json.Json.decodeFromString<FinancialArrangementInput>(row.inputJson).copy(loanLimitDeltaMinor=r.input.loanLimitDeltaMinor)
    return row.copy(principalMinor=r.input.principalMinor!!,effectiveDate=r.input.effectiveDate,baselineJson=kotlinx.serialization.json.Json.encodeToString(r.baseline),inputJson=kotlinx.serialization.json.Json.encodeToString(input))
}
internal val FinancialGraph.currentArrangements:List<FinancialArrangementEntity> get()=arrangements.map(::currentArrangement)

internal fun LedgerRepository.reconciliationSource(s:BookSnapshot,f:FinancialGraph,a:FinancialArrangementEntity):ReconciliationSourceProof {
    val old=f.plans.first{it.id==a.originalPlanId};val account=s.accounts.first{it.id==old.accountId}
    val source=old.sourceEventId?.let{id->s.visibleEvents.firstOrNull{it.id==id&&it.accountId==old.accountId}}
    if(old.origin=="POOL") {
        val proof=reconciliationPoolEvidence(s,f,old,a.effectiveDate)
        return ReconciliationSourceProof(null,true,proof.balanceMinor(),account.openingDate,account.openingMinor)
    }
    val contractId=f.contractPlans.firstOrNull{it.planId==old.id}?.contractId?:old.sourceEventId?.let{id->f.contracts.firstOrNull{it.fundingEventId==id}?.id}
    val contract=f.contracts.firstOrNull{it.id==contractId}
    return ReconciliationSourceProof(old.sourceEventId,old.sourceEventId==null||source!=null,
        if(old.sourceEventId!=null)source?.let{s.accountMoney(it).minor}?:0 else old.principalMinor?:0,source?.date?:account.openingDate,account.openingMinor,contract?.principalMinor,contract?.mode)
}

internal fun LedgerRepository.physicalArrangementRefunds(s:BookSnapshot,f:FinancialGraph,a:FinancialArrangementEntity):List<PredictionRefundFact> {
    val old=f.plans.first{it.id==a.originalPlanId}
    if(old.origin=="POOL")return poolPhysicalRefunds(s,f,old)
    val source=old.sourceEventId?:return emptyList()
    val account=f.plans.first{it.id==a.targetPlanId}.accountId
    return s.settlements.filter{it.originalEventId==source&&it.confirmed&&s.visibleEvents.any{e->e.id==it.eventId}}.mapNotNull{r->
        val amount=s.postings.filter{it.eventId==r.eventId&&it.accountId==account}.fold(0L){n,p->subtractExact(n,p.deltaMinor)}
        if(amount<=0)null else PredictionRefundFact(r.eventId,0,r.settlementDate,amount)
    }.sortedBy{it.eventId}
}

internal fun LedgerRepository.reconciliationIssue(s:BookSnapshot,f:FinancialGraph,plan:FinancialPlanEntity,r:ArrangementReconciliation):String? {
    val a=f.currentArrangements.first{it.id==r.arrangementId}
    if(reconciliationSource(s,f,a)!=r.source)return "arrangement.sourceChanged"
    if(physicalArrangementRefunds(s,f,a)!=r.physicalRefunds)return "arrangement.baselineChanged"
    if(r.verifiedBills.any{bill->f.bills.firstOrNull{it.id==bill.id}!=bill})return "arrangement.baselineChanged"
    r.baseline.pool?.let{proof->
        if(reconciliationPoolEvidence(s,f,f.plans.first{it.id==proof.poolId},r.input.effectiveDate)!=proof)return "arrangement.baselineChanged"
        return null
    }
    val pre=arrangementClearings(s,f,r.baseline.sourcePlanIds,r.baseline.contractId).filter{it.date<=r.input.effectiveDate}
    if(pre!=r.baseline.clearings)return "arrangement.baselineChanged"
    val parent=f.arrangements.firstOrNull{it.targetPlanId==a.originalPlanId}
    if(parent!=null) {
        val p=f.plans.first{it.id==parent.targetPlanId}
        if(p.reconciliation()?.id!=r.parentReviewId||arrangementBaselineIssue(s,f,p)!=null)return "arrangement.parentReview"
    }
    return null
}

suspend fun LedgerRepository.previewArrangementReconciliation(bookId:String,input:ArrangementReconciliationInput):ArrangementReconciliationReview=db.withTransaction {
    prepareArrangementReconciliation(financialReviewState(bookId),input)
}

private fun LedgerRepository.prepareArrangementReconciliation(state:FinancialReviewState,input:ArrangementReconciliationInput):ArrangementReconciliationReview {
    val before=state.ledger;val f=state.finance
    val target=requireNotNull(f.plans.firstOrNull{it.id==input.planId})
    val raw=requireNotNull(f.arrangements.firstOrNull{it.targetPlanId==target.id})
    val applied=f.currentArrangement(raw);val old=f.plans.first{it.id==applied.originalPlanId}
    fun failed(field:String)=ArrangementReconciliationReview(state,input,target,Calculation.Incomplete(listOf(field)),null,emptyList(),emptyList(),emptyList(),null)
    if(target.origin=="POOL_RETAINED"&&f.reconciliationPool(target)==null)return failed("arrangement.reviewPoolScope")
    val effective=LocalDate.parse(input.effectiveDate).toString()
    val principal=input.principalMinor?:return failed("arrangement.sourcePrincipal")
    require(principal>=0)
    if(input.loanLimitDeltaMinor?.let{it < -principal||it>principal}==true)return failed("arrangement.limitDelta")
    val parent=f.arrangements.firstOrNull{it.targetPlanId==old.id}
    if(parent!=null&&arrangementBaselineIssue(before,f,old)!=null)return failed("arrangement.parentReview")
    if(parent!=null&&effective<f.currentArrangement(parent).effectiveDate)return failed("arrangement.effectiveBeforeOriginal")
    val sourcePool=old.takeIf{it.origin=="POOL"}
    if(sourcePool!=null&&poolExternalReviewIssue(before,f,sourcePool.id)!=null)return failed("arrangement.reviewPoolSource")
    val originalBills=f.reconciliationSourceBills(old)
    val destinationPool=f.reconciliationPool(target)
    val targetBills=f.reconciliationTargetBills(target)
    val allowed=(originalBills+targetBills).map{it.id}.toSet()
    val relevant=before.appliedRepaymentAllocations().filter{it.billId in allowed||it.billId==null&&it.contractId!=null&&it.contractId==json.decodeFromString<FinancialArrangementBaseline>(applied.baselineJson).contractId}
    if(input.clearingMoves.any{move->relevant.none{it.id==move.allocationId}||move.billId?.let{it !in allowed}==true||listOf(move.principalMinor,move.interestMinor,move.feeMinor).any{it<0}})return failed("arrangement.clearingSplit")
    val changes=relevant.mapNotNull{row->val moves=input.clearingMoves.filter{it.allocationId==row.id};if(moves.isEmpty())null else {
        if(moves.sumOf{it.principalMinor}!=row.principalMinor||moves.sumOf{it.interestMinor}!=row.interestMinor||moves.sumOf{it.feeMinor}!=row.feeMinor)return failed("arrangement.clearingSplit")
        if(moves.size==1&&moves.single().billId==row.billId)null else ArrangementClearingChange(row,moves.map{row.copy(id=id(),billId=it.billId,principalMinor=it.principalMinor,interestMinor=it.interestMinor,feeMinor=it.feeMinor)})
    }}
    if(changes.flatMap{it.replacements}.any{row->
        val bill=row.billId?.let{id->f.bills.first{it.id==id}}
        val billContract=bill?.let{f.contractForBill(it)}
        row.billId==null&&row.contractId==null||billContract!=null&&row.contractId!=billContract
    })return failed("arrangement.clearingSplit")
    val allocations=before.repaymentAllocations.filter{r->changes.none{it.original.id==r.id}}+changes.flatMap{it.replacements}
    var s=before.copy(repaymentAllocations=allocations)
    var next=f.copy(repaymentAllocations=allocations)
    var sourcePlan=old
    if(sourcePool!=null) {
        val definition=json.decodeFromString<FinancialPredictionInput>(old.termsJson!!)
        sourcePlan=old.copy(termsJson=json.encodeToString(definition.copy(pool=definition.pool!!.copy(openingMinor=input.openingSourceMinor?:definition.pool.openingMinor,sourceDate=input.poolSourceDate?:definition.pool.sourceDate,unallocatedShares=input.poolUnallocatedShares?:definition.pool.unallocatedShares,facts=definition.pool.facts?.let{facts->facts.copy(payments=facts.payments.filter{row->changes.none{it.original.eventId==row.eventId}&&row.eventId !in input.poolUnallocatedShares.orEmpty().keys})}))))
        next=next.copy(plans=next.plans.map{if(it.id==old.id)sourcePlan else it})
    }else if(input.openingSourceMinor!=null) {
        require(old.sourceEventId==null&&parent==null&&input.openingSourceMinor>=0)
        val account=s.accounts.first{it.id==old.accountId}
        val used=f.plans.filter{it.accountId==old.accountId&&it.origin=="OPENING"&&it.id!=old.id}.map{it.principalMinor}.knownTotal()?:return failed("plan.noPrincipal")
        if(input.openingSourceMinor>subtractExact(account.openingMinor,used))return failed("arrangement.sourcePrincipal")
        sourcePlan=old.copy(principalMinor=input.openingSourceMinor)
        next=next.copy(plans=next.plans.map{if(it.id==old.id)sourcePlan else it})
    }
    val source=reconciliationSource(s,next,applied.copy(effectiveDate=effective))
    val poolProof=sourcePool?.let{reconciliationPoolEvidence(s,next,sourcePlan,effective)}
    if(poolProof!=null) {
        val facts=poolFacts(s,next,json.decodeFromString(sourcePlan.termsJson!!),validateArrangements=false)
        facts.issues.firstOrNull{it in listOf("pool.opening","pool.sourceMode","pool.sourcePending","pool.sourceBeforeStart","pool.repaymentShare")}?.let{return failed(it)}
        if(poolProof.balanceMinor()==null)return failed("pool.repaymentShare")
    }
    if(source.grossMinor==null)return failed("pool.repaymentShare")
    if(parent==null&&old.sourceEventId==null&&source.contractPrincipalMinor!=null&&source.grossMinor!=source.contractPrincipalMinor)return failed("arrangement.reviewContractPrincipal")
    if(source.exists&&old.sourceEventId!=null&&source.date>effective)return failed("arrangement.reviewEffectiveDate")
    if(!source.exists&&principal!=0L)return failed("arrangement.sourceChanged")
    val physical=physicalArrangementRefunds(s,next,applied)
    val available=if(sourcePool!=null)availablePoolRefunds(s,next,old.id,target.id)else if(parent==null)physical else old.reconciliation()?.refunds?.filter{it.targetMinor>0}?.map{it.fact.copy(principalMinor=it.targetMinor)}
        ?:physical.filter{r->json.decodeFromString<FinancialArrangementBaseline>(next.currentArrangement(parent).baselineJson).refunds.none{it.eventId==r.eventId}}
    if(input.refunds.map{it.eventId}.distinct().size!=input.refunds.size||input.refunds.map{it.eventId}.toSet()!=available.map{it.eventId}.toSet())return failed("arrangement.reviewRefundShares")
    val refunds=available.map{fact->val share=input.refunds.first{it.eventId==fact.eventId}
        if(share.targetMinor<0||share.bills.any{it.principalMinor<0||it.billId !in originalBills.map{b->b.id}}||share.bills.map{it.billId}.distinct().size!=share.bills.size||addExact(share.targetMinor,share.bills.sumOf{it.principalMinor})>fact.principalMinor||fact.date<=effective&&share.targetMinor!=0L)return failed("arrangement.reviewRefundShares")
        ScopedArrangementRefund(fact,share.targetMinor,share.bills)
    }
    val referenced=allocations.mapNotNull{it.billId}.toSet()
    val mandatoryTarget=targetBills.filter{destinationPool==null&&principal==0L||it.confirmed||it.date<LocalDate.now(clock).toString()||it.id in referenced||s.billAdjustments.any{a->a.billId==it.id&&(destinationPool!=null||a.arrangementId!=applied.id)}}
    val mandatoryOriginal=if(sourcePool==null)originalBills else originalBills.filter{it.confirmed||it.id in referenced||it.date<LocalDate.now(clock).toString()||s.billAdjustments.any{a->a.billId==it.id}}
    val required=(mandatoryOriginal+mandatoryTarget).map{it.id}.toSet()
    val selected=input.bills.map{it.billId}.toSet()
    if(selected.size!=input.bills.size||!selected.containsAll(required)||selected.any{it !in allowed})return failed("arrangement.reviewBills")
    val retainedOriginal=originalBills.filter{it.id in selected}
    val retainedTarget=targetBills.filter{it.id in selected}
    val declaredAdjustments=input.bills.map{part->
        val bill=(retainedOriginal+retainedTarget).first{it.id==part.billId};val due=s.billOutstanding(bill)
        if(part.principalMinor?.let{it<0}==true||part.feeMinor?.let{it<0}==true)return failed("arrangement.originalBreakdown")
        if((part.principalMinor==null)!=(bill.principalMinor==null)||(part.feeMinor==null)!=(bill.feeMinor==null))return failed("arrangement.reviewBillBreakdown")
        if(part.principalMinor?.let{it>subtractExact(bill.principalMinor!!,due.paidPrincipalMinor)}==true||part.feeMinor?.let{it>subtractExact(bill.feeMinor!!,due.paidFeeMinor)}==true)return failed("arrangement.reviewBillCap")
        FinancialBillAdjustmentEntity(s.book.id,id(),applied.id,bill.id,
            if(due.principalMinor==null)0 else subtractExact(due.principalMinor,part.principalMinor!!),
            if(due.feeMinor==null)0 else subtractExact(due.feeMinor,part.feeMinor!!))
    }.filter{it.principalMinor!=0L||it.feeMinor!=0L}
    // Returning an unpaid, unconfirmed period to automatic projection replaces its former
    // reconciliation adjustment. Keeping that old deduction would subtract the correction twice.
    val automaticAdjustments=(targetBills+if(sourcePool!=null)originalBills else emptyList()).filter{it.id !in selected}.mapNotNull{bill->
        val prior=s.billAdjustments.filter{it.billId==bill.id&&it.arrangementId==applied.id}
        val p=prior.sumOf{it.principalMinor};val fee=prior.sumOf{it.feeMinor}
        if(p==0L&&fee==0L)null else FinancialBillAdjustmentEntity(s.book.id,id(),applied.id,bill.id,-p,-fee)
    }
    val adjustments=declaredAdjustments+automaticAdjustments
    s=s.copy(billAdjustments=s.billAdjustments+adjustments);next=next.copy(billAdjustments=next.billAdjustments+adjustments)
    val originalFacts=predictionFacts(s,next,old.id,old.sourceEventId,effective)
    val gross=parent?.let{next.currentArrangement(it).principalMinor}?:source.grossMinor
    val others=sourcePool?.let{otherReviewedPoolTransfers(s,next,it.id,target.id)}.orEmpty()
    val at=if(poolProof==null)subtractExact(gross,originalFacts.principalFacts().filter{it.date<=effective}.sumOf{it.principalMinor})else
        addExact(subtractExact(poolProof.balanceMinor()!!,others.filter{it.effectiveDate<=effective}.sumOf{it.principalMinor}),others.flatMap{a->next.plans.first{it.id==a.targetPlanId}.reconciliation()?.refunds.orEmpty()}.filter{it.fact.date<=effective}.sumOf{it.targetMinor})
    val afterPayments=if(sourcePool!=null)poolFacts(s,next,json.decodeFromString(sourcePlan.termsJson!!),validateArrangements=false).payments.filter{it.date>effective}.sumOf{addExact(it.allocations.sumOf{row->row.principalMinor!!},it.explicitMinor?:0)}else originalFacts.repayments.filter{it.repayment.platformConfirmed&&it.repayment.clearingDate!!>effective}.flatMap{it.allocations}.sumOf{it.principalMinor!!}
    val restoredRefunds=refunds.filter{it.fact.date>effective}.flatMap{it.bills}.sumOf{it.principalMinor}
    val originalPrincipal=retainedOriginal.map{s.billOutstanding(it).principalMinor}.knownTotal()?:return failed("arrangement.reviewOriginalPrincipal")
    val reserved=addExact(originalPrincipal,addExact(afterPayments,restoredRefunds))
    val unbilledRefund=refunds.filter{it.fact.date>effective}.sumOf{subtractExact(subtractExact(it.fact.principalMinor,it.targetMinor),it.bills.sumOf{b->b.principalMinor})}
    val availableRemainder=subtractExact(subtractExact(subtractExact(at,reserved),principal),unbilledRefund)
    if(availableRemainder<0)return failed("arrangement.reviewPrincipalCap")
    val unassigned=if(sourcePool==null)availableRemainder else 0L
    val contractId=json.decodeFromString<FinancialArrangementBaseline>(applied.baselineJson).contractId
    val lineage=arrangementLineage(next,old.id)
    val baseline=FinancialArrangementBaseline(source.grossMinor,source.date,lineage,contractId,arrangementClearings(s,next,lineage,contractId).filter{it.date<=effective},available.filter{it.date<=effective},retainedOriginal.filter{it.date<effective}.map{it.id},unassigned,poolProof)
    val record=ArrangementReconciliation(id(),applied.id,clock.instant().toString(),input,source,old.reconciliation()?.id,baseline,refunds,physical,unassigned,((if(sourcePool==null)retainedOriginal else emptyList())+if(destinationPool==null)retainedTarget else emptyList()))
    val oldDefinition=target.termsJson?.let{json.decodeFromString<FinancialPredictionInput>(it)}
    val definition=oldDefinition?.copy(facts=null,projections=if(principal==0L)emptyList()else oldDefinition.projections,terms=oldDefinition.terms.copy(agreementDate=effective,accrualWindows=oldDefinition.terms.accrualWindows.mapIndexed{i,w->if(i==0&&w.start<effective)w.copy(start=effective)else w}))
    val plan=target.copy(principalMinor=subtractExact(principal,refunds.filter{it.fact.date>effective}.sumOf{it.targetMinor}),reconciliationJson=json.encodeToString(record),termsJson=definition?.let{json.encodeToString(it)},revision=target.revision+1)
    next=next.copy(plans=next.plans.map{when(it.id){old.id->sourcePlan;plan.id->plan;else->it}})
    val targetFacts=predictionFacts(s,next,plan.id,plan.sourceEventId,LocalDate.now(clock).toString())
    if(destinationPool==null&&targetFacts.repayments.any{it.repayment.platformConfirmed&&it.repayment.clearingDate!!<effective})return failed("arrangement.reviewEarlierClearing")
    val remaining=if(destinationPool!=null)plan.principalMinor!! else subtractExact(plan.principalMinor!!,targetFacts.repayments.filter{it.repayment.platformConfirmed}.flatMap{it.allocations}.sumOf{it.principalMinor!!})
    if(remaining<0||destinationPool==null&&retainedTarget.mapNotNull{s.billOutstanding(it).principalMinor}.sum()>remaining)return failed("arrangement.reviewPrincipalCap")
    val outgoing=next.arrangements.any{it.originalPlanId==plan.id}
    val datesValid=definition?.terms?.let{t->t.paymentDates.none{it<effective}&&t.accrualWindows.all{it.start<=it.end}}?:true
    val prediction=if(destinationPool==null&&definition!=null&&principal>0&&!outgoing&&datesValid)prepareFinancialPrediction(FinancialReviewState(s,next,state.limits),definition.copy(planId=plan.id))else null
    val manualComplete=input.manualComplete?:target.complete
    if(destinationPool==null&&definition==null&&manualComplete&&retainedTarget.map{s.billOutstanding(it).principalMinor}.knownTotal()!=remaining)return failed("arrangement.reviewManualComplete")
    val final=plan.copy(active=target.origin=="POOL_RETAINED"&&f.bills.any{it.planId==target.id&&it.active}||destinationPool==null&&(outgoing&&target.active||remaining>0||retainedTarget.any{s.billOutstanding(it).totalMinor>0}),complete=if(principal==0L&&retainedTarget.all{s.billOutstanding(it).principalMinor!=null})true else if(definition==null)manualComplete else prediction?.plan?.complete?:false)
    val originalRemaining=originalBills.any{s.billOutstanding(it).totalMinor>0}||unassigned>0
    val original=sourcePlan.copy(active=sourcePool!=null||originalRemaining,revision=old.revision+1)
    if(destinationPool!=null&&contractId!=null&&f.contracts.first{it.id==contractId}.mode!=destinationPool.mode) {
        val transferred=next.poolArrangements(destinationPool.id).filter{change->
            json.decodeFromString<FinancialArrangementBaseline>(change.baselineJson).contractId==contractId
        }.sumOf{it.principalMinor}
        val paid=s.appliedRepaymentAllocations().filter{it.contractId==contractId&&it.billId in targetBills.map{b->b.id}}.sumOf{it.principalMinor!!}
        if(paid>transferred)return failed("arrangement.reviewPrincipalCap")
    }
    val poolPrediction=(destinationPool?:sourcePool)?.let{pool->
        val definition=json.decodeFromString<FinancialPredictionInput>((if(pool.id==old.id)sourcePlan else pool).termsJson!!).copy(planId=pool.id)
        val changed=changes.map{it.original.eventId}.toSet()
        val accepted=definition.copy(pool=definition.pool!!.copy(facts=definition.pool.facts?.let{facts->facts.copy(payments=facts.payments.filter{it.eventId !in changed})}))
        val finance=next.copy(plans=next.plans.map{when(it.id){old.id->original;target.id->final;else->it}})
        prepareFinancialPool(FinancialReviewState(s,finance,state.limits),accepted)
    }
    return ArrangementReconciliationReview(state,input,target,Calculation.Ready(Unit),record,listOf(original,final),adjustments,changes,prediction,poolPrediction)
}

suspend fun LedgerRepository.commitArrangementReconciliation(review:ArrangementReconciliationReview,confirmed:Boolean):String?=db.withTransaction {
    require(confirmed){"请确认已核对平台当前义务 / 返済先の現在の債務を確認してください"}
    require(review.calculation is Calculation.Ready)
    if(financialReviewState(review.original.bookId)!=review.before)return@withTransaction null
    val dao=db.financialDao();val b=review.original.bookId
    for(plan in review.plans) {
        val old=review.before.finance.plans.first{it.id==plan.id}
        archiveExistingPlan(old,review.before.finance.bills.filter{it.planId==old.id},predictionFacts(review.before.ledger,review.before.finance,old.id,old.sourceEventId,LocalDate.now(clock).toString()))
    }
    dao.insertBillAdjustments(review.adjustments)
    for((eventId,changes) in review.clearingChanges.groupBy{it.original.eventId}) {
        val s=snapshot(this.dao.book(b)!!).first();val event=s.visibleEvents.first{it.id==eventId}
        val parts=s.repaymentAllocations.filter{it.eventId==eventId&&changes.none{c->c.original.id==it.id}}+changes.flatMap{it.replacements}
        val rows=parts.groupBy{it.billId to it.contractId}.values.map{group->group.first().copy(principalMinor=group.sumOf{it.principalMinor!!},interestMinor=group.sumOf{it.interestMinor!!},feeMinor=group.sumOf{it.feeMinor!!})}
        writeChange(PreparedLedgerChange(event,event.copy(revision=event.revision+1),s.settlements.first{it.eventId==eventId},s.storedPostings.filter{it.eventId==eventId},components=s.expenseComponents.filter{it.eventId==eventId},repayment=s.repayments.first{it.eventId==eventId},repaymentAllocations=rows))
    }
    for(plan in review.plans) {
        dao.updatePlan(plan)
        archiveFinancialPlan(plan,dao.bills(b,plan.id),if(plan.id==review.original.id)"ARRANGEMENT_RECONCILED"else"RECONCILIATION_SOURCE",facts=predictionFacts(snapshot(this.dao.book(b)!!).first(),dao.graph(b).first()!!,plan.id,plan.sourceEventId,LocalDate.now(clock).toString()))
    }
    if(review.prediction?.calculation is Calculation.Ready) {
        val state=financialReviewState(b)
        val definition=json.decodeFromString<FinancialPredictionInput>(state.finance.plans.first{it.id==review.original.id}.termsJson!!).copy(planId=review.original.id)
        val refreshed=prepareFinancialPrediction(state,definition)
        check(refreshed.calculation is Calculation.Ready)
        writeFinancialPrediction(refreshed,"RECONCILIATION_RECALCULATED")
    }
    review.poolPrediction?.takeIf{it.calculation is Calculation.Ready}?.let{preview->
        val state=financialReviewState(b)
        val refreshed=prepareFinancialPool(state,preview.input)
        check(refreshed.calculation is Calculation.Ready)
        writeFinancialPrediction(refreshed,"RECONCILIATION_POOL")
    }
    review.original.id
}

internal fun LedgerRepository.reconciliationRefundChoices(s:BookSnapshot,f:FinancialGraph,target:FinancialPlanEntity):List<PredictionRefundFact> {
    val a=f.currentArrangements.first{it.targetPlanId==target.id}
    val old=f.plans.first{it.id==a.originalPlanId};val parent=f.arrangements.firstOrNull{it.targetPlanId==old.id}
    val physical=physicalArrangementRefunds(s,f,a)
    return if(old.origin=="POOL")availablePoolRefunds(s,f,old.id,target.id)else if(parent==null)physical else old.reconciliation()?.refunds?.filter{it.targetMinor>0}?.map{it.fact.copy(principalMinor=it.targetMinor)}
        ?:physical.filter{r->json.decodeFromString<FinancialArrangementBaseline>(f.currentArrangement(parent).baselineJson).refunds.none{it.eventId==r.eventId}}
}
