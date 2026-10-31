package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.lang.Math.addExact
import java.lang.Math.subtractExact

@Serializable enum class PoolSourceDate { TRANSACTION, ACCOUNT_POSTING }
@Serializable data class FinancialPoolInput(val openingMinor:Long,val sourceDate:PoolSourceDate,val unallocatedShares:Map<String,Long> = emptyMap(),val adoptExisting:Boolean=false,val facts:FinancialPoolFacts?=null,val paymentChanges:List<PoolPaymentChange> = emptyList(),val adoptedFees:List<PoolAdoptedFee> = emptyList())
@Serializable data class PoolSourceFact(val id:String,val eventId:String?,val date:String,val principalMinor:Long,val revision:Int,val kind:String)
@Serializable data class PoolBillFact(val bill:FinancialBillEntity,val principalMinor:Long?,val feeMinor:Long?,val totalMinor:Long)
@Serializable data class PoolPaymentFact(val eventId:String,val revision:Int,val date:String,val principalMinor:Long?,val allocations:List<FinancialRepaymentAllocationEntity>,val explicitMinor:Long?,val unallocatedMinor:Long)
@Serializable data class PoolArrangementFeeFact(val arrangementId:String,val date:String,val minor:Long,val includedInRetained:Boolean)
@Serializable data class FinancialPoolFacts(val openingDate:String,val accountOpeningMinor:Long,val sources:List<PoolSourceFact>,val payments:List<PoolPaymentFact>,val retained:List<PoolBillFact>,val rules:List<FinancialRuleEntity>,val issues:List<String>,val arrangementFees:List<PoolArrangementFeeFact> = emptyList())

private fun poolMode(s:BookSnapshot,f:FinancialGraph,accountId:String,eventId:String):String? = s.contracts.firstOrNull{it.accountId==accountId&&it.fundingEventId==eventId}?.mode
    ?:s.visibleEvents.firstOrNull{it.id==eventId}?.cardPaymentMode
    ?:f.plans.filter{it.accountId==accountId&&it.sourceEventId==eventId&&(it.active||it.origin=="POOL_RETAINED"||f.arrangements.any{a->a.originalPlanId==it.id})&&f.arrangements.none{a->a.targetPlanId==it.id}}.map{it.mode}.distinct().singleOrNull()
internal fun poolInput(plan:FinancialPlanEntity)=plan.termsJson?.let{kotlinx.serialization.json.Json.decodeFromString<FinancialPredictionInput>(it)}?.pool

internal fun LedgerRepository.poolFacts(s:BookSnapshot,f:FinancialGraph,input:FinancialPredictionInput,validateArrangements:Boolean=true):FinancialPoolFacts {
    val pool=requireNotNull(input.pool);val a=s.accounts.first{it.id==input.accountId};val mode=input.mode.name
    val issues=mutableListOf<String>();val sources=mutableListOf<PoolSourceFact>()
    val live=s.visibleEvents.associateBy{it.id}
    val eligible=live.values.filter{it.accountId==a.id&&poolMode(s,f,a.id,it.id)==mode}.map{it.id}.toSet()
    val unknown=s.storedPostings.filter{it.accountId==a.id&&it.deltaMinor>0&&it.eventId in live&&poolMode(s,f,a.id,it.eventId)==null}
    if(unknown.isNotEmpty())issues+="pool.sourceMode"
    for(p in s.storedPostings.filter{it.accountId==a.id&&it.deltaMinor>0&&it.eventId in eligible}) {
        if(!p.confirmed)issues+="pool.sourcePending"
        val e=live.getValue(p.eventId);val date=if(pool.sourceDate==PoolSourceDate.TRANSACTION)e.date else p.date
        if(date<a.openingDate){issues+="pool.sourceBeforeStart";continue}
        sources+=PoolSourceFact("source:${p.id}",e.id,date,p.deltaMinor,e.revision,"ADDITION")
    }
    for(r in s.settlements.filter{it.originalEventId in eligible&&it.confirmed&&it.eventId in live}) {
        val amount=s.postings.filter{it.eventId==r.eventId&&it.accountId==a.id}.fold(0L){n,p->subtractExact(n,p.deltaMinor)}
        if(amount>0&&r.settlementDate>=a.openingDate)sources+=PoolSourceFact("refund:${r.eventId}",r.eventId,r.settlementDate,-amount,live.getValue(r.eventId).revision,"REFUND")
    }
    for(change in f.currentArrangements.filter{it.effectiveDate>=a.openingDate}) {
        val old=f.plans.first{it.id==change.originalPlanId};val target=f.plans.first{it.id==change.targetPlanId}
        if(old.accountId!=a.id)continue
        val direction=(if(target.mode==mode)1 else 0)-(if(old.mode==mode)1 else 0)
        if(direction!=0)sources+=PoolSourceFact("arrangement:${change.id}",old.sourceEventId,change.effectiveDate,Math.multiplyExact(change.principalMinor,direction.toLong()),1,"TRANSFER")
        if(direction!=0) {
            if(validateArrangements)arrangementBaselineIssue(s,f,target)?.let{issues+=it}
            val refunds=target.reconciliation()?.refunds
                ?:predictionFacts(s,f,target.id,target.sourceEventId,LocalDate.now(clock).toString()).refunds.map{ScopedArrangementRefund(it,it.principalMinor,emptyList())}
            refunds.filter{it.fact.date>change.effectiveDate&&it.targetMinor>0}.forEach{refund->
                sources+=PoolSourceFact("refund-allocation:${change.id}:${refund.fact.eventId}",refund.fact.eventId,refund.fact.date,Math.multiplyExact(refund.targetMinor,-direction.toLong()),1,"REFUND_ALLOCATION")
            }
        }
    }
    val otherOpening=f.plans.filter{it.accountId==a.id&&(it.active||f.arrangements.any{change->change.originalPlanId==it.id})&&it.origin=="OPENING"&&it.mode!=mode}.map{it.principalMinor}.knownTotal()
    if(pool.openingMinor<0||otherOpening==null||pool.openingMinor>subtractExact(a.openingMinor,otherOpening))issues+="pool.opening"
    val knownOpening=f.plans.filter{it.accountId==a.id&&it.origin=="OPENING"&&it.mode==mode}.map{it.principalMinor}.knownTotal()
    if(knownOpening==null||pool.openingMinor<knownOpening)issues+="pool.opening"
    val openingContracts=s.contracts.filter{it.accountId==a.id&&it.fundingEventId==null}
    if(openingContracts.filter{it.mode==mode}.sumOf{it.principalMinor}>pool.openingMinor)issues+="pool.opening"
    val otherContracts=openingContracts.filter{it.mode!=mode&&f.contractPlans.none{link->link.contractId==it.id&&f.plans.any{p->p.id==link.planId&&p.origin=="OPENING"&&(p.active||f.arrangements.any{change->change.originalPlanId==p.id})}}}.sumOf{it.principalMinor}
    if(otherOpening!=null&&addExact(pool.openingMinor,addExact(otherOpening,otherContracts))>a.openingMinor)issues+="pool.opening"
    val memberPlans=f.plans.filter{it.accountId==a.id&&it.mode==mode&&(it.active||it.origin=="POOL_RETAINED")}.map{it.id}.toSet()
    val billIds=f.bills.filter{it.planId in memberPlans}.map{it.id}.toSet()
    val adjusted=f.billAdjustments.map{it.billId}.toSet()
    val referenced=f.repaymentAllocations.filter{it.billId in billIds&&it.eventId in live}.mapNotNull{it.billId}.toSet()
    val retained=f.bills.filter{it.active&&it.planId in memberPlans&&(it.confirmed||it.id in referenced||it.id in adjusted||it.date<LocalDate.now(clock).toString())}.map{b->
        val moved=s.billAdjustments.filter{it.billId==b.id}
        val principal=moved.fold(0L){n,x->addExact(n,x.principalMinor)};val fee=moved.fold(0L){n,x->addExact(n,x.feeMinor)}
        PoolBillFact(b,b.principalMinor?.let{subtractExact(it,principal)},b.feeMinor?.let{subtractExact(it,fee)},subtractExact(b.totalMinor,addExact(principal,fee)))
    }.sortedBy{it.bill.id}
    val payments=mutableListOf<PoolPaymentFact>()
    for(e in live.values) {
        val p=s.repayments.firstOrNull{it.eventId==e.id&&it.debtAccountId==a.id}
        val credits=s.storedPostings.filter{it.eventId==e.id&&it.accountId==a.id&&it.deltaMinor<0&&it.confirmed}
        val legacy=p==null&&e.kind==EntryKind.REPAYMENT.name&&credits.isNotEmpty()
        if(p==null&&!legacy)continue
        val confirmed=p?.platformConfirmed?:true
        val date=p?.clearingDate?:credits.maxOfOrNull{it.date}?:e.date
        if(!confirmed||date<a.openingDate)continue
        val principal=if(p==null)credits.fold(0L){n,x->subtractExact(n,x.deltaMinor)}else p.principalMinor
        val rows=s.repaymentAllocations.filter{it.eventId==e.id}
        val allocated=rows.map{it.principalMinor}.knownTotal()
        val share=pool.unallocatedShares[e.id]
        if(principal==null||allocated==null||allocated>principal){issues+="pool.repaymentShare";continue}
        if(principal>allocated&&share==null)issues+="pool.repaymentShare"
        if(share!=null&&(share<0||share>subtractExact(principal,allocated)))issues+="pool.repaymentShare"
        val selected=rows.filter{r->if(r.billId!=null)r.billId in billIds else r.contractId?.let{id->s.contracts.firstOrNull{it.id==id}?.mode==mode}==true}
        if(selected.any{r->r.billId!=null&&f.bills.any{it.id==r.billId&&!it.active}})issues+="pool.inactiveBill"
        // Keep zero classifications as evidence: changing an earlier payment invalidates a saved forecast.
        val fact=PoolPaymentFact(e.id,e.revision,date,principal,selected.sortedBy{it.id},share,subtractExact(principal,allocated))
        val previous=pool.facts?.payments?.firstOrNull{it.eventId==e.id}
        if(share!=null&&previous!=null&&(previous.date!=fact.date||previous.principalMinor!=fact.principalMinor||previous.allocations!=fact.allocations||previous.unallocatedMinor!=fact.unallocatedMinor))issues+="pool.repaymentShare"
        if(selected.isNotEmpty()||principal>allocated||share!=null)payments+=fact
    }
    if(pool.unallocatedShares.keys.any{it !in payments.map{p->p.eventId}})issues+="pool.repaymentShare"
    if(input.paymentAccountId!=null&&s.accounts.none{it.id==input.paymentAccountId&&it.active&&!AccountType.valueOf(it.type).debt})issues+="pool.paymentAccount"
    val poolId=input.planId?:f.plans.firstOrNull{it.origin=="POOL"&&it.active&&it.accountId==a.id&&it.mode==mode}?.id
    val arrangementFees=mutableListOf<PoolArrangementFeeFact>()
    for(change in pool.paymentChanges) {
        if(change.feeMinor>0) {
            val date=change.feeDate
            if(date==null||date !in input.terms.paymentDates||date<change.effectiveDate)issues+="pool.mergeFeeDate"
            else arrangementFees+=PoolArrangementFeeFact(change.id,date,change.feeMinor,change.feeIncludedInRetained)
        }
    }
    for(change in f.currentArrangements.filter{f.plans.any{p->p.id==it.targetPlanId&&p.origin=="POOL_MEMBER"}}) {
        val merge=json.decodeFromString<FinancialArrangementInput>(change.inputJson)
        if(merge.mergePoolId!=poolId)continue
        val member=f.plans.first{it.id==change.targetPlanId}
        if(validateArrangements)arrangementBaselineIssue(s,f,member)?.let{issues+=it}
        val baseline=json.decodeFromString<FinancialArrangementBaseline>(change.baselineJson)
        if(baseline.contractId!=null&&s.appliedRepaymentAllocations().any{it.billId==null&&it.contractId==baseline.contractId&&baseline.clearings.none{p->p.eventId==it.eventId}})issues+="arrangement.allocateClearing"
        if(change.changeFeeMinor>0) {
            val date=merge.mergeFeeDate
            if(date==null||date !in input.terms.paymentDates||date<change.effectiveDate)issues+="pool.mergeFeeDate"
            else arrangementFees+=PoolArrangementFeeFact(change.id,date,change.changeFeeMinor,merge.mergeFeeIncludedInRetained)
        }
    }
    val adopted=f.adoptedArrangements(a.id,mode,pool.adoptExisting)
    val requiredAdoptedFees=adopted.filter{it.changeFeeMinor>0}
    if(pool.adoptedFees.map{it.arrangementId}.distinct().size!=pool.adoptedFees.size||pool.adoptedFees.any{fee->requiredAdoptedFees.none{it.id==fee.arrangementId}})issues+="pool.adoptedFeeChoice"
    for(change in adopted) {
        val target=f.plans.first{it.id==change.targetPlanId}
        if(validateArrangements)arrangementBaselineIssue(s,f,target)?.let{issues+=it}
        val baseline=json.decodeFromString<FinancialArrangementBaseline>(change.baselineJson)
        if(baseline.contractId!=null&&s.appliedRepaymentAllocations().any{it.billId==null&&it.contractId==baseline.contractId&&baseline.clearings.none{p->p.eventId==it.eventId}})issues+="arrangement.allocateClearing"
        if(change.changeFeeMinor>0) {
            val placement=pool.adoptedFees.singleOrNull{it.arrangementId==change.id}
            if(placement==null)issues+="pool.adoptedFeeChoice"
            else if(placement.waivedMinor !in 0..change.changeFeeMinor)issues+="pool.adoptedFeeChoice"
            else {
                val current=subtractExact(change.changeFeeMinor,placement.waivedMinor)
                if(current>0&&(placement.date !in input.terms.paymentDates||placement.date<change.effectiveDate))issues+="pool.mergeFeeDate"
                else arrangementFees+=PoolArrangementFeeFact(change.id,placement.date,current,placement.includedInRetained)
            }
        }
    }
    for((date,fees) in arrangementFees.filter{it.minor>0}.groupBy{it.date}) {
        val kept=retained.filter{it.bill.date==date}
        if(kept.isEmpty())continue
        val period=input.terms.paymentDates.indexOf(date)+1
        // Inclusion is proved against the original bill. Later explicit fee cancellations reduce
        // its current obligation, but must not make an already recorded fee look unaccounted for.
        val known=kept.map{it.bill.feeMinor}.knownTotal()
        val required=addExact(fees.sumOf{it.minor},input.terms.additionalFees.filter{it.number==period}.sumOf{it.minor})
        val prior=pool.facts?.arrangementFees.orEmpty()
        if(known==null||known<required||fees.any{!it.includedInRetained&&it !in prior})issues+="pool.mergeFeeConfirmation"
    }
    val rules=f.rules.filter{it.accountId==a.id&&it.mode==mode&&it.effectiveFrom<=input.terms.accrualWindows.lastOrNull()?.end.orEmpty()}.sortedBy{it.id}
    return FinancialPoolFacts(a.openingDate,a.openingMinor,sources.sortedBy{it.id},payments.sortedBy{it.eventId},retained,rules,issues.distinct(),arrangementFees.sortedBy{it.arrangementId})
}
internal fun LedgerRepository.financialPoolStale(s:BookSnapshot,f:FinancialGraph,plan:FinancialPlanEntity):Boolean {
    val input=plan.termsJson?.let{json.decodeFromString<FinancialPredictionInput>(it)}?:return true
    return input.pool?.facts!=poolFacts(s,f,input)
}

internal fun LedgerRepository.prepareFinancialPool(state:FinancialReviewState,input:FinancialPredictionInput,allowPaymentChange:Boolean=false):FinancialPredictionReview {
    val s=state.ledger;val f=state.finance;val a=s.accounts.first{it.id==input.accountId};val pool=requireNotNull(input.pool)
    require(a.active&&input.mode==if(a.type==AccountType.CREDIT.name)FinanceMode.REVOLVING else FinanceMode.CREDIT_LINE)
    require(AccountType.valueOf(a.type).debt&&input.name.isNotBlank()&&input.sourceEventId==null)
    require(input.terms.agreementDate==a.openingDate&&input.terms.paymentDates.isNotEmpty()&&input.terms.accrualWindows.size==input.terms.paymentDates.size)
    require(input.terms.firstPeriodNumber>0)
    require(input.terms.paymentDates.zipWithNext().all{(x,y)->x<y})
    require(f.plans.none{it.active&&it.origin=="POOL"&&it.accountId==a.id&&it.id!=input.planId}){"本账户已有余额池，请打开原计划 / この口座には残高プールがあります。既存の予定を開いてください"}
    val old=input.planId?.let{id->requireNotNull(f.plans.firstOrNull{it.id==id&&it.accountId==a.id&&it.origin=="POOL"})}
    require(allowPaymentChange||pool.paymentChanges==(old?.let{poolInput(it)?.paymentChanges}.orEmpty())){"月付款变更需要独立记录已办理 / 月支払の変更は手続き済みとして別途記録してください"}
    val members=f.plans.filter{it.accountId==a.id&&it.mode==input.mode.name&&it.active&&it.id!=old?.id}
    require(members.isEmpty()||pool.adoptExisting){"请确认归并已有循环计划，保留已确认/有付款关联/逾期账单 / 既存の循環予定を統合し、確定・支払関連・期限経過の請求を保持することを確認してください"}
    val facts=poolFacts(s,f,input)
    val dates=input.terms.paymentDates
    val retained=facts.retained.groupBy{it.bill.date}.map{(date,rows)->ConfirmedSchedulePeriod(dates.indexOf(date)+1,rows.map{it.principalMinor}.knownTotal(),rows.map{it.feeMinor}.knownTotal(),rows.fold(0L){n,r->addExact(n,r.totalMinor)},LocalDate.parse(date))}
    val missing=facts.retained.any{it.bill.date !in dates}
    val principalFacts=facts.payments.flatMap{p->p.allocations.map{r->SchedulePrincipalFact(p.eventId,p.date,r.principalMinor!!,r.billId?.let{id->facts.retained.firstOrNull{it.bill.id==id}?.bill?.date?.let{dates.indexOf(it)+1}})}+listOfNotNull(p.explicitMinor?.takeIf{it>0}?.let{SchedulePrincipalFact(p.eventId,p.date,it)})}+
        facts.sources.filter{it.principalMinor<0}.map{SchedulePrincipalFact(it.id,it.date,Math.negateExact(it.principalMinor),kind=it.kind)}
    val additions=facts.sources.filter{it.principalMinor>0}.map{SchedulePrincipalAddition(it.id,it.date,it.principalMinor)}
    val versions=f.rules.filter{it.accountId==a.id}.map{EffectiveFinancialRule(it.version,json.decodeFromString<FinancialRuleVersion>(it.json))}
    val result=if(facts.issues.isNotEmpty()||missing)Calculation.Incomplete(facts.issues+if(missing)listOf("pool.retainedDates")else emptyList())
        else reconciledSchedule(pool.openingMinor,input.mode,input.terms.copy(additionalFees=input.terms.additionalFees+facts.arrangementFees.filter{it.minor>0}.map{AdditionalPeriodFee(dates.indexOf(it.date)+1,it.minor)},paymentSettings=pool.paymentChanges.map{it.setting}),versions,retained,principalFacts,LocalDate.now(clock),additions)
    val periods=(result as? Calculation.Ready)?.value.orEmpty()
    val existing=f.bills.filter{it.planId==old?.id};val ownRetained=facts.retained.filter{it.bill.planId==old?.id}.map{it.bill}
    val externalDates=facts.retained.filter{it.bill.planId!=old?.id}.map{it.bill.date}.toSet()
    val planId=old?.id?:id();var maxNumber=existing.maxOfOrNull{it.number}?:0
    val bills=ownRetained+periods.filter{it.period.date.toString() !in externalDates&&ownRetained.none{b->b.date==it.period.date.toString()}}.map{p->
        val previous=existing.firstOrNull{it.date==p.period.date.toString()&&it !in ownRetained}
        FinancialBillEntity(s.book.id,previous?.id?:id(),planId,previous?.number?:++maxNumber,input.paymentAccountId?:versions.firstOrNull{it.rule.id==p.ruleId}?.rule?.billing?.paymentAccountId,p.ruleId,p.period.date.toString(),p.period.principalMinor,p.period.feeMinor,p.period.totalMinor,json.encodeToString(p.basis),null,null,null,null,null,if(previous==null)1 else previous.revision+1)
    }
    val encoded=input.copy(planId=planId,pool=pool.copy(facts=facts),projections=periods.mapNotNull{p->bills.firstOrNull{it.date==p.period.date.toString()}?.let{FinancialPeriodProjection(it.number,it.date,p.period.principalMinor,p.period.feeMinor,p.basis)}})
    val gross=additions.fold(pool.openingMinor){n,p->addExact(n,p.principalMinor)}
    val plan=FinancialPlanEntity(s.book.id,planId,a.id,input.name,input.mode.name,"POOL",null,a.currency,gross,result is Calculation.Ready&&(periods.lastOrNull()?.period?.remainingPrincipalMinor?:0L)==0L&&additions.none{it.date>dates.last()},old?.createdAt?:clock.millis(),(old?.revision?:0)+1,true,json.encodeToString(encoded))
    val paymentFacts=facts.payments.map{p->val r=s.repayments.firstOrNull{it.eventId==p.eventId};r?.let{PredictionRepaymentFact(p.eventId,p.revision,s.visibleEvents.first{e->e.id==p.eventId}.date,it,p.allocations)}}.filterNotNull()
    val archiveFacts=FinancialPredictionFacts(LocalDate.now(clock).toString(),facts.retained.map{r->PredictionBillFact(r.bill.id,r.bill.number,r.bill.date,r.principalMinor,r.feeMinor,r.totalMinor,r.bill.confirmed,r.bill.paymentAccountId)},paymentFacts,emptyList(),billAdjustments=s.billAdjustments.filter{it.billId in facts.retained.map{r->r.bill.id}})
    val withdrawn=existing.filter{it.active&&it.id !in bills.map{b->b.id}&&it !in ownRetained}.map{it.copy(active=false,revision=it.revision+1)}
    return FinancialPredictionReview(state,encoded,plan,result,bills,ownRetained.filter{it.confirmed},ownRetained.filter{!it.confirmed},archiveFacts,gross,withdrawn,false)
}

internal suspend fun LedgerRepository.writeFinancialPoolAdoption(review:FinancialPredictionReview) {
    val facts=requireNotNull(review.input.pool?.facts);val keep=facts.retained.map{it.bill.id}.toSet()
    val mode=review.plan.mode;val dao=db.financialDao()
    for(old in review.before.finance.plans.filter{it.accountId==review.plan.accountId&&it.mode==mode&&it.active&&it.id!=review.plan.id}) {
        val original=review.before.finance.bills.filter{it.planId==old.id}
        val retired=original.map{if(it.active&&it.id !in keep)it.copy(active=false,revision=it.revision+1)else it}
        if(old.origin=="POOL_RETAINED"&&retired==original)continue
        val historyFacts=predictionFacts(review.before.ledger,review.before.finance,old.id,old.sourceEventId,LocalDate.now(clock).toString())
        archiveExistingPlan(old,original,historyFacts)
        val next=old.copy(origin="POOL_RETAINED",active=retired.any{it.active},complete=false,revision=old.revision+1)
        dao.updatePlan(next);retired.filter{it !in original}.forEach{dao.updateBill(it)}
        archiveFinancialPlan(next,retired,"POOL_ADOPTED",facts=historyFacts)
    }
}
internal suspend fun LedgerRepository.refreshFinancialPools(change:PreparedLedgerChange) {
    if(change.event.kind !in listOf(EntryKind.EXPENSE.name,EntryKind.TRANSFER.name,EntryKind.REFUND.name))return
    val accountIds=listOfNotNull(change.event.accountId,change.before?.accountId,change.settlement.originalEventId?.let{dao.event(change.event.bookId,it)?.accountId}).toSet()
    val candidates=accountIds.flatMap{db.financialDao().plans(change.event.bookId,it)}.filter{it.active&&it.origin=="POOL"}
    for(p in candidates) {
        val state=financialReviewState(p.bookId)
        if(state.ledger.accounts.none{it.id==p.accountId&&it.active}||!financialPoolStale(state.ledger,state.finance,p))continue
        val input=json.decodeFromString<FinancialPredictionInput>(p.termsJson!!).copy(planId=p.id)
        val preview=prepareFinancialPool(state,input)
        if(preview.calculation is Calculation.Ready)writeFinancialPrediction(preview,"POOL_UPDATED",change.event.id)
    }
}
suspend fun LedgerRepository.previewFinancialPool(bookId:String,input:FinancialPredictionInput):FinancialPredictionReview=db.withTransaction {prepareFinancialPool(financialReviewState(bookId),input)}

data class PoolUnallocatedPayment(val eventId:String,val date:String,val minor:Long)
fun poolUnallocatedPayments(s:BookSnapshot,accountId:String):List<PoolUnallocatedPayment> = s.visibleEvents.mapNotNull{e->
    val p=s.repayments.firstOrNull{it.eventId==e.id&&it.debtAccountId==accountId}
    val credits=s.storedPostings.filter{it.eventId==e.id&&it.accountId==accountId&&it.confirmed&&it.deltaMinor<0}
    if(p?.platformConfirmed!=true&&(p!=null||e.kind!=EntryKind.REPAYMENT.name||credits.isEmpty()))return@mapNotNull null
    val principal=if(p==null)credits.fold(0L){n,r->subtractExact(n,r.deltaMinor)}else p.principalMinor?:return@mapNotNull null
    val allocated=s.repaymentAllocations.filter{it.eventId==e.id}.map{it.principalMinor}.knownTotal()?:return@mapNotNull null
    PoolUnallocatedPayment(e.id,p?.clearingDate?:credits.maxOfOrNull{it.date}?:e.date,subtractExact(principal,allocated))
}
