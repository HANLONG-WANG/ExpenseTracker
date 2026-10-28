package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.AccountType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.first
import java.time.LocalDate

@Serializable data class BatchRevolvingInput(val accountId:String,val paymentDate:String,val planIds:List<String>,val principalMinor:Long,
    val poolId:String,val effectiveDate:String,val feeMinor:Long?,val mergeFeeDate:String?=null,val mergeFeeIncludedInRetained:Boolean=false)
@Serializable data class BatchArrangementArchive(val undoGroupId:String,val paymentDate:String,val before:List<FinancialPlanArchive>,
    val afterPlans:List<FinancialPlanEntity>,val afterBills:List<FinancialBillEntity>,val arrangements:List<FinancialArrangementEntity>,val comparison:FinancialArrangementComparison,val repaymentAllocations:List<FinancialRepaymentAllocationEntity> = emptyList())
class BatchRevolvingReview internal constructor(internal val before:FinancialReviewState,val input:BatchRevolvingInput,
    val transfers:List<SinglePaymentTransfer>,val reviews:List<FinancialArrangementReview>,val comparison:FinancialArrangementComparison?,val calculation:Calculation<Unit>)

fun LedgerRepository.batchRevolvingCandidates(s:BookSnapshot,f:FinancialGraph,accountId:String,paymentDate:String,effectiveDate:String=LocalDate.now(clock).toString()):List<SinglePaymentPortion> {
    if(s.accounts.none{it.id==accountId&&it.active&&it.type==AccountType.CREDIT.name})return emptyList()
    val today=LocalDate.now(clock).toString()
    if(paymentDate<maxOf(today,effectiveDate))return emptyList()
    return f.plans.filter{it.active&&it.accountId==accountId&&it.mode=="SINGLE"&&it.sourceEventId!=null&&f.arrangements.none{a->a.originalPlanId==it.id}}.mapNotNull{p->
        val event=s.visibleEvents.firstOrNull{it.id==p.sourceEventId}?:return@mapNotNull null
        val bill=f.bills.filter{it.planId==p.id&&it.active}.singleOrNull()?.takeIf{it.date==paymentDate}?:return@mapNotNull null
        if(s.repaymentAllocations.any{it.billId==bill.id}||f.repaymentAllocations.any{it.billId==bill.id})return@mapNotNull null
        if(s.settlements.any{it.eventId==event.id&&!it.confirmed}||arrangementBaselineIssue(s,f,p)!=null)return@mapNotNull null
        if(!bill.confirmed&&financialPredictionStale(s,f,p))return@mapNotNull null
        val due=s.billOutstanding(bill)
        if(due.principalMinor==null||due.principalMinor<=0||due.feeMinor==null||due.feeMinor<0)return@mapNotNull null
        val facts=predictionFacts(s,f,p.id,event.id,effectiveDate)
        if(facts.repayments.isNotEmpty()||facts.refunds.any{it.date>effectiveDate})return@mapNotNull null
        val remaining=(f.currentArrangements.firstOrNull{it.targetPlanId==p.id}?.principalMinor?:s.accountMoney(event).minor)-facts.principalFacts().filter{it.date<=effectiveDate}.sumOf{it.principalMinor}
        if(due.principalMinor!=remaining)return@mapNotNull null
        SinglePaymentPortion(p.id,event.date,event.createdAt,due.principalMinor)
    }.sortedWith(compareBy<SinglePaymentPortion>{it.date}.thenBy{it.createdAt}.thenByDescending{part->s.events.indexOfFirst{it.id==f.plans.first{p->p.id==part.planId}.sourceEventId}})
}

private fun LedgerRepository.batchArrangementInput(f:FinancialGraph,input:BatchRevolvingInput,part:SinglePaymentTransfer,fee:Long?,group:String?):FinancialArrangementInput {
    val pool=f.plans.first{it.id==input.poolId&&it.accountId==input.accountId&&it.active&&it.origin=="POOL"&&it.mode=="REVOLVING"}
    val definition=json.decodeFromString<FinancialPredictionInput>(pool.termsJson!!)
    val dates=definition.terms.paymentDates.filter{it>=input.effectiveDate}
    require(dates.isNotEmpty()){"请先延长余额池预测范围 / プールの予測期間を先に延長してください"}
    return FinancialArrangementInput(part.planId,f.plans.first{it.id==part.planId}.name,FinanceMode.REVOLVING,
        FinancialScheduleTerms(input.effectiveDate,dates),definition.paymentAccountId,OriginalFeeTreatment.RETAIN,fee,
        mergePoolId=pool.id,mergeFeeDate=input.mergeFeeDate,mergeFeeIncludedInRetained=input.mergeFeeIncludedInRetained,transferPrincipalMinor=part.principalMinor,undoGroupId=group)
}

private fun LedgerRepository.batchPreviewState(state:FinancialReviewState,r:FinancialArrangementReview):FinancialReviewState {
    val pool=requireNotNull(r.mergedPool);val target=r.target!!;val old=r.original
    val arrangement=FinancialArrangementEntity(old.bookId,r.arrangementId,old.id,target.id,r.input.terms.agreementDate,clock.instant().toString(),target.principalMinor!!,r.input.changeFeeMinor!!,r.input.originalFeeTreatment!!.name,false,json.encodeToString(r.baseline!!),json.encodeToString(r.input),json.encodeToString(r.comparison!!),clock.millis())
    val ledger=state.ledger.copy(billAdjustments=state.ledger.billAdjustments+r.adjustments)
    val kept=state.finance.bills.any{it.planId==old.id&&it.active&&ledger.billOutstanding(it).totalMinor>0}
    val changed=(pool.bills+pool.withdrawn).associateBy{it.id}
    val bills=state.finance.bills.map{changed[it.id]?:it}+changed.values.filter{b->state.finance.bills.none{it.id==b.id}}
    return state.copy(ledger=ledger,finance=state.finance.copy(plans=state.finance.plans.map{when(it.id){old.id->old.copy(active=kept,revision=old.revision+1);pool.plan.id->pool.plan;else->it}}+target,
        bills=bills,arrangements=state.finance.arrangements+arrangement,billAdjustments=state.finance.billAdjustments+r.adjustments))
}

suspend fun LedgerRepository.previewBatchRevolving(bookId:String,input:BatchRevolvingInput):BatchRevolvingReview=db.withTransaction {
    val before=financialReviewState(bookId)
    LocalDate.parse(input.effectiveDate);LocalDate.parse(input.paymentDate)
    val order=dao.eventCreationOrder(bookId).withIndex().associate{it.value to it.index}
    val candidates=batchRevolvingCandidates(before.ledger,before.finance,input.accountId,input.paymentDate,input.effectiveDate)
        .sortedBy{part->order[before.finance.plans.first{it.id==part.planId}.sourceEventId]}
    require(input.planIds.isNotEmpty()&&input.planIds.distinct().size==input.planIds.size&&input.planIds.all{id->candidates.any{it.planId==id}}){"所选账单已变化或不可转换，请重新选择 / 選択した請求が変更されたか変更対象外です。選び直してください"}
    val transfers=allocateSinglePaymentTransfers(candidates.filter{it.planId in input.planIds},input.principalMinor)
    var next=before;val reviews=mutableListOf<FinancialArrangementReview>()
    for((index,part) in transfers.withIndex()) {
        val r=prepareFinancialArrangement(next,batchArrangementInput(next.finance,input,part,if(index==0)input.feeMinor else 0,null))
        reviews+=r
        if(r.calculation !is Calculation.Ready) {
            val result=when(val c=r.calculation){is Calculation.Incomplete->c;is Calculation.Unsupported->c;is Calculation.CannotSettle->c;is Calculation.Ready->error("unreachable")}
            return@withTransaction BatchRevolvingReview(before,input,transfers,reviews,null,result)
        }
        next=batchPreviewState(next,r)
    }
    val ids=before.finance.plans.filter{it.accountId==input.accountId&&it.mode=="SINGLE"&&before.finance.bills.any{b->b.planId==it.id&&b.active&&b.date==input.paymentDate}}.map{it.id}.toSet()+input.poolId+before.finance.plans.filter{it.accountId==input.accountId&&it.mode=="REVOLVING"&&it.origin=="POOL_RETAINED"}.map{it.id}
    fun rows(state:FinancialReviewState)=state.finance.bills.filter{it.active&&it.planId in ids}.map{b->val due=state.ledger.billOutstanding(b);ArrangementMoneyRow(b.date,due.principalMinor,due.feeMinor,due.totalMinor,b.paymentAccountId)}.filter{it.totalMinor>0}
    val original=rows(before);val proposed=rows(next);val oldFees=original.map{it.feeMinor}.knownTotal();val newFees=proposed.map{it.feeMinor}.knownTotal()
    val originalComplete=before.finance.plans.filter{it.id in ids}.all{it.complete};val proposedComplete=next.finance.plans.first{it.id==input.poolId}.complete
    val remaining=reviews.last().comparison!!.proposedRemainingPrincipalMinor
    val comparison=FinancialArrangementComparison(original,proposed,input.paymentDate,
        original.filter{it.date<=input.paymentDate}.sumOf{it.totalMinor}-proposed.filter{it.date<=input.paymentDate}.sumOf{it.totalMinor},oldFees,newFees,
        if(originalComplete&&proposedComplete&&!input.mergeFeeIncludedInRetained&&oldFees!=null&&newFees!=null)newFees-oldFees else null,
        reviews.first().comparison!!.originalUnscheduledPrincipalMinor,remaining,
        fundingThrough=(original+proposed).maxOfOrNull{it.date},originalComplete=originalComplete,proposedComplete=proposedComplete)
    BatchRevolvingReview(before,input,transfers,reviews,comparison,Calculation.Ready(Unit))
}

suspend fun LedgerRepository.commitBatchRevolving(review:BatchRevolvingReview,appliedAt:String,platformConfirmed:Boolean,exceptionAccepted:Boolean=false):String?=db.withTransaction {
    require(review.calculation is Calculation.Ready)
    val bookId=review.before.ledger.book.id
    if(financialReviewState(bookId)!=review.before)return@withTransaction null
    val group=id();val planIds=review.transfers.map{it.planId}.toSet()+review.input.poolId
    val before=review.before.finance.plans.filter{it.id in planIds}.map{p->FinancialPlanArchive(p,review.before.finance.bills.filter{it.planId==p.id})}
    val targets=mutableListOf<String>()
    for((index,part) in review.transfers.withIndex()) {
        val state=financialReviewState(bookId)
        val current=prepareFinancialArrangement(state,batchArrangementInput(state.finance,review.input,part,if(index==0)review.input.feeMinor else 0,group))
        check(current.calculation is Calculation.Ready){"转换条件已变化，请重新预览 / 変更条件が変わりました。再度確認してください"}
        targets+=checkNotNull(commitFinancialArrangement(current,appliedAt,platformConfirmed,exceptionAccepted))
    }
    val state=financialReviewState(bookId);val dao=db.financialDao()
    val pool=state.finance.plans.first{it.id==review.input.poolId}.let{it.copy(revision=it.revision+1)}
    dao.updatePlan(pool)
    val afterPlans=state.finance.plans.filter{it.id in planIds||it.id in targets}.map{if(it.id==pool.id)pool else it}
    val afterBills=state.finance.bills.filter{it.planId in planIds}
    val archive=BatchArrangementArchive(group,review.input.paymentDate,before,afterPlans,afterBills,state.finance.arrangements.filter{it.targetPlanId in targets},review.comparison!!,state.ledger.repaymentAllocations.filter{it.billId in afterBills.map{b->b.id}})
    archiveFinancialPlan(pool,afterBills.filter{it.planId==pool.id},"BATCH_REVOLVING",batchArrangement=archive)
    group
}

suspend fun LedgerRepository.undoBatchRevolving(bookId:String,undoGroupId:String)=db.withTransaction {
    val state=financialReviewState(bookId);val f=state.finance;val dao=db.financialDao()
    val arrangements=f.arrangements.filter{json.decodeFromString<FinancialArrangementInput>(it.inputJson).undoGroupId==undoGroupId}
    require(arrangements.isNotEmpty()){"此组已经撤销 / このグループは取消済みです"}
    val poolId=json.decodeFromString<FinancialArrangementInput>(arrangements.first().inputJson).mergePoolId!!
    val archive=dao.planRevisions(bookId,poolId).first().asSequence().map{json.decodeFromString<FinancialPlanArchive>(it.json)}.mapNotNull{it.batchArrangement}.first{it.undoGroupId==undoGroupId}
    val affected=archive.afterPlans.map{it.id}.toSet();val billIds=archive.afterBills.map{it.id}.toSet()
    require(archive.afterPlans.all{p->f.plans.first{it.id==p.id}==p}&&archive.afterBills.all{b->f.bills.first{it.id==b.id}==b}&&
        f.bills.none{it.planId in affected&&it.id !in billIds}&&state.ledger.repaymentAllocations.filter{it.billId in billIds}.toSet()==archive.repaymentAllocations.toSet()&&
        !financialPoolStale(state.ledger,f,f.plans.first{it.id==poolId})&&arrangements.toSet()==archive.arrangements.toSet()){"此组之后已有付款或安排变化，不能直接撤销，请核对当前方案 / この後に支払・予定の変更があります。直接取り消せないため現在のプランを確認してください"}
    dao.deleteArrangementAdjustments(bookId,arrangements.map{it.id});dao.deleteArrangements(bookId,arrangements.map{it.id})
    val originals=archive.before.associateBy{it.plan.id}
    for(current in archive.afterPlans) {
        val original=originals[current.id]
        val restored=original?.plan?.copy(revision=current.revision+1)?:current.copy(active=false,origin="CANCELLED_ARRANGEMENT",revision=current.revision+1)
        dao.updatePlan(restored)
        val previous=original?.bills.orEmpty().associateBy{it.id}
        val bills=f.bills.filter{it.planId==current.id}.map{bill->previous[bill.id]?.let{if(it==bill)bill else it.copy(revision=bill.revision+1)}?:bill.copy(active=false,revision=bill.revision+1)}
        bills.filter{b->f.bills.first{it.id==b.id}!=b}.forEach{dao.updateBill(it)}
        archiveFinancialPlan(restored,bills,"BATCH_REVOLVING_UNDONE",batchArrangement=archive.takeIf{current.id==poolId})
    }
}
