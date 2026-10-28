package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate

@Serializable data class FinancialPeriodProjection(val number:Int,val date:String,val principalMinor:Long,val feeMinor:Long,val basis:FinancialCalculationBasis)
@Serializable data class FinancialPredictionInput(
    val accountId:String,val name:String,val mode:FinanceMode,val terms:FinancialScheduleTerms,
    val sourceEventId:String?=null,val openingPrincipalMinor:Long?=null,val paymentAccountId:String?=null,val planId:String?=null,
    val currentArrangementConfirmed:Boolean=false,val facts:FinancialPredictionFacts?=null,val projections:List<FinancialPeriodProjection> = emptyList(),val pool:FinancialPoolInput?=null,val additionalFeeUnknown:Boolean=false
)
class FinancialPredictionReview internal constructor(
    internal val before:FinancialReviewState,val input:FinancialPredictionInput,val plan:FinancialPlanEntity,
    val calculation:Calculation<List<PredictedSchedulePeriod>>,val bills:List<FinancialBillEntity>,
    val retainedConfirmed:List<FinancialBillEntity>,val retainedOther:List<FinancialBillEntity>,val facts:FinancialPredictionFacts,val originalPrincipalMinor:Long,val withdrawn:List<FinancialBillEntity>,val sourceEstimated:Boolean
)

fun BookSnapshot.financialSourcePrincipal(eventId:String,accountId:String):Money? {
    val event=visibleEvents.firstOrNull{it.id==eventId&&it.accountId==accountId}?:return null
    val original=accountMoney(event)
    val refunds=settlements.filter{it.originalEventId==event.id&&it.confirmed}.map{it.eventId}.toSet()
    val delta=postings.filter{it.eventId in refunds&&it.accountId==accountId}.fold(0L){sum,p->Math.addExact(sum,p.deltaMinor)}
    return original.copy(minor=Math.addExact(original.minor,delta))
}

fun LedgerRepository.financialPredictionStale(s:BookSnapshot,finance:FinancialGraph,plan:FinancialPlanEntity):Boolean {
    if(plan.origin=="POOL_MEMBER")return arrangementBaselineIssue(s,finance,plan)!=null
    if(plan.origin=="POOL_RETAINED")return arrangementBaselineIssue(s,finance,plan)!=null
    if(plan.origin=="POOL")return financialPoolStale(s,finance,plan)
    if(finance.arrangements.any{it.originalPlanId==plan.id})return false
    if(arrangementBaselineIssue(s,finance,plan)!=null)return true
    if(plan.reconciliation()?.input?.principalMinor==0L)return false
    val arranged=finance.arrangements.any{it.targetPlanId==plan.id}
    if(!arranged&&plan.sourceEventId!=null){val source=s.visibleEvents.firstOrNull{it.id==plan.sourceEventId};if(source?.cardPaymentMode!=null&&source.cardPaymentMode!=plan.mode)return true;if(plan.origin=="CARD_ENTRY"&&plan.mode=="INSTALLMENT"&&source?.cardPaymentPeriods!=finance.bills.count{it.planId==plan.id&&it.active})return true}
    val input=plan.termsJson?.let{json.decodeFromString<FinancialPredictionInput>(it)}?:return false
    if(input.additionalFeeUnknown&&finance.bills.any{it.planId==plan.id&&it.active&&!it.confirmed})return true
    val savedFacts=input.facts?:return true
    if(predictionFacts(s,finance,plan.id,input.sourceEventId,savedFacts.asOf)!=savedFacts)return true
    val today=LocalDate.now(clock).toString()
    if(finance.bills.any{it.planId==plan.id&&it.active&&it.date>=savedFacts.asOf&&it.date<today&&s.billOutstanding(it).totalMinor>0})return true
    input.sourceEventId?.takeUnless{arranged}?.let{id->
        val source=s.visibleEvents.firstOrNull{it.id==id}?:return true
        if(source.date!=input.terms.agreementDate||s.financialSourcePrincipal(id,plan.accountId)?.minor!=plan.principalMinor)return true
    }
    val rules=finance.rules.filter{it.accountId==plan.accountId&&it.mode==plan.mode}.groupBy{it.effectiveFrom}.values.map{it.maxBy{v->v.version}}
    fun at(date:String)=rules.filter{it.effectiveFrom<=date}.maxByOrNull{it.effectiveFrom}?.id
    return finance.bills.filter{it.planId==plan.id&&it.active&&!it.confirmed&&it.basisJson!=null}.any{bill->
        val basis=json.decodeFromString<FinancialCalculationBasis>(bill.basisJson!!)
        val expected=if(plan.mode in listOf(FinanceMode.REVOLVING.name,FinanceMode.CREDIT_LINE.name)) {
            val w=input.terms.accrualWindows[bill.number-1]
            (listOfNotNull(at(w.start),at(w.end))+rules.filter{it.effectiveFrom>w.start&&it.effectiveFrom<=w.end}.map{it.id}).toSet()
        }else setOfNotNull(at(input.terms.agreementDate))
        basis.ruleIds.toSet()!=expected||finance.rules.filter{it.id in basis.ruleIds}.any{json.decodeFromString<FinancialRuleVersion>(it.json).readiness()!=RuleReadiness.Complete}
    }
}

/** Uses account-currency obligations, never base-currency consumption statistics. */
private fun FinancialReviewState.sourcePrincipal(input:FinancialPredictionInput,account:AccountEntity,old:FinancialPlanEntity?):Pair<Long,Boolean> {
    finance.currentArrangements.firstOrNull{it.targetPlanId==old?.id}?.let{arrangement->
        val facts=predictionFacts(ledger,finance,old!!.id,old.sourceEventId,input.terms.agreementDate)
        require(input.terms.agreementDate==arrangement.effectiveDate){"请保留已办理生效日 / 手続き済みの適用日を維持してください"}
        return Math.subtractExact(arrangement.principalMinor,facts.refunds.fold(0L){sum,r->Math.addExact(sum,r.principalMinor)}) to false
    }
    input.sourceEventId?.let{eventId->
        val event=requireNotNull(ledger.visibleEvents.firstOrNull{it.id==eventId&&it.accountId==account.id}){"请选择本账户仍有效的记录 / この口座の有効な記録を選択してください"}
        require(event.kind==if(account.type==AccountType.CREDIT.name)EntryKind.EXPENSE.name else EntryKind.BORROWING.name)
        require(input.terms.agreementDate==event.date){"消费日期已变化，请重新打开计划 / 利用日が変わりました。プランを開き直してください"}
        require(!event.historicalIncluded){"该记录已包含于期初，请使用期初欠款计划 / この記録は期首に含まれています。期首残高のプランを使用してください"}
        val settlement=ledger.settlements.firstOrNull{it.eventId==event.id}
        val original=ledger.accountMoney(event)
        require(original.currency.name==account.currency)
        return requireNotNull(ledger.financialSourcePrincipal(event.id,account.id)).minor to (settlement?.confirmed==false)
    }
    val amount=input.openingPrincipalMinor?:old?.principalMinor?:error("请填写期初已有本金 / 期首の既存元金を入力してください")
    require(amount>0)
    if(old?.origin!="MANUAL") {
        val allocated=finance.plans.filter{it.active&&it.accountId==account.id&&it.origin=="OPENING"&&it.id!=old?.id}
        require(allocated.all{it.principalMinor!=null}){"其他期初计划本金尚未明确 / 他の期首プランの元金が未確定です"}
        val used=allocated.fold(0L){sum,p->Math.addExact(sum,p.principalMinor!!)}
        require(amount<=Math.subtractExact(account.openingMinor,used)){"计划本金超过未分配的期初欠款 / プラン元金が未割当ての期首残高を超えています"}
    }
    return amount to false
}

suspend fun LedgerRepository.previewFinancialPrediction(bookId:String,input:FinancialPredictionInput):FinancialPredictionReview=db.withTransaction {
    prepareFinancialPrediction(financialReviewState(bookId),input)
}

internal fun LedgerRepository.prepareFinancialPrediction(state:FinancialReviewState,requested:FinancialPredictionInput):FinancialPredictionReview {
    if(requested.pool!=null)return prepareFinancialPool(state,requested)
    val cardSource=state.ledger.visibleEvents.firstOrNull{it.id==requested.sourceEventId&&it.cardPaymentChoice in listOf("BONUS","DEFERRED")}?.takeIf{requested.mode==FinanceMode.SINGLE&&state.finance.arrangements.none{a->a.targetPlanId==requested.planId}}
    val cardRule=cardSource?.let{state.finance.rules.cardRule(it.accountId,it.date,"SINGLE")}?.let{json.decodeFromString<FinancialRuleVersion>(it.json)}
    val cardIssue=when {
        cardSource==null->null
        cardSource.cardPaymentChoice=="BONUS"&&(cardRule==null||bonusPaymentIssues(cardRule.bonusPayments).isNotEmpty()||bonusPaymentDate(LocalDate.parse(cardSource.date),cardRule.bonusPayments)==null)->"bonus.settings"
        cardRule?.billing?.issues()?.isEmpty()!=true->"billing"
        else->null
    }
    val input=if(cardSource!=null&&cardIssue==null)requested.copy(terms=requested.terms.copy(paymentDates=listOf(recordedSinglePaymentDate(cardSource,cardRule!!).toString()),additionalFees=cardSource.cardPaymentFeeMinor?.let{listOf(AdditionalPeriodFee(1,it))}.orEmpty()),additionalFeeUnknown=cardSource.cardPaymentChoice=="DEFERRED"&&cardSource.cardPaymentFeeMinor==null)else requested
    val bookId=state.ledger.book.id
    val account=requireNotNull(state.ledger.accounts.firstOrNull{it.id==input.accountId}){"账户不属于本账本 / 別の帳簿の口座です"}
    requireMode(account,input.mode)
    input.paymentAccountId?.let{id->require(state.ledger.accounts.any{it.id==id&&it.active&&!AccountType.valueOf(it.type).debt}){"请选择本账本的资金账户 / この帳簿の資金口座を選択してください"}}
    require(input.name.isNotBlank()){"请输入计划名称 / プラン名を入力してください"}
    val old=input.planId?.let{id->requireNotNull(state.finance.plans.firstOrNull{it.id==id&&it.accountId==account.id&&it.active})}
    require(old!=null||input.mode !in listOf(FinanceMode.REVOLVING,FinanceMode.CREDIT_LINE)||state.finance.plans.none{it.active&&it.origin=="POOL"&&it.accountId==account.id}){"本账户循环部分已由余额池计算，请从余额池更新 / この口座の循環分はプールで計算中です。プールから更新してください"}
    require(state.finance.arrangements.none{it.originalPlanId==old?.id}){"此安排已变更，保留义务请核对原账单 / この予定は変更済みです。保持する債務は元の請求を確認してください"}
    require(old?.origin!="POOL_MEMBER"){"已并入账户余额池，请从原池核对 / 口座プールに統合済みです。元のプールから確認してください"}
    require(old?.origin!="POOL_RETAINED"){"此计划已归并余额池，请核对原账单并从余额池重算 / この予定は残高プールに統合済みです。元の請求を確認し、プールで再計算してください"}
    val incoming=state.finance.currentArrangements.firstOrNull{it.targetPlanId==old?.id}
    require(old!=null||input.currentArrangementConfirmed){"请确认这是平台当前已采用的安排；拟办理方案请先试算 / 返済先で現在適用中のプランであることを確認してください。変更予定は先に試算してください"}
    old?.termsJson?.let{saved->
        val previous=json.decodeFromString<FinancialPredictionInput>(saved)
        if(input.mode in listOf(FinanceMode.INSTALLMENT,FinanceMode.FIXED_LOAN))require(previous.terms.paymentDates.size==input.terms.paymentDates.size&&previous.terms.firstPeriodNumber==input.terms.firstPeriodNumber){"期数变更需记录已办理方案，不能仅重算覆盖 / 回数変更は手続き済みのプランとして記録してください。再計算だけでは変更できません"}
    }
    if(old!=null)require(old.mode==input.mode.name&&old.sourceEventId==input.sourceEventId){"重算不能改换债务来源或模式，请记录已办理变更 / 再計算で対象債務・方式は変更できません。手続き済みの変更を記録してください"}
    require(state.finance.plans.none{it.active&&it.id!=old?.id&&input.sourceEventId!=null&&it.sourceEventId==input.sourceEventId&&state.finance.arrangements.none{a->a.originalPlanId==it.id}}){"该记录已有计划，请打开原计划重算 / この記録にはプランがあります。元のプランを再計算してください"}
    input.sourceEventId?.takeIf{incoming==null}?.let{source->state.finance.contracts.firstOrNull{it.fundingEventId==source}?.let{contract->
        require(contract.mode==input.mode.name){"计划方式须与借款合同一致，变更需单独记录已办理 / プランの方式は借入契約と一致させてください。変更は手続き済みとして別途記録してください"}
    }}
    val (principal,estimated)=state.sourcePrincipal(input,account,old)
    val existing=state.finance.bills.filter{it.planId==old?.id}
    val confirmed=existing.filter{it.confirmed}
    require(confirmed.all{it.number<=input.terms.paymentDates.size}){"新范围必须保留已确认账期 / 新しい範囲に確定済みの回を含めてください"}
    val versions=state.finance.rules.filter{it.accountId==account.id}.map{EffectiveFinancialRule(it.version,json.decodeFromString<FinancialRuleVersion>(it.json))}
    val facts=predictionFacts(state.ledger,state.finance,old?.id,input.sourceEventId,LocalDate.now(clock).toString())
    val retainedIds=facts.retainedBills.map{it.id}.toSet()
    require(facts.retainedBills.all{it.number<=input.terms.paymentDates.size}){"重算范围须保留已确认、有付款关联或已过期的账期 / 再計算の範囲には確定・支払関連・期日経過の請求を含めてください"}
    val gross=if(incoming!=null)incoming.principalMinor else if(input.sourceEventId!=null)state.ledger.accountMoney(state.ledger.visibleEvents.first{it.id==input.sourceEventId}).minor else principal
    val baselineIssue=old?.let{arrangementBaselineIssue(state.ledger,state.finance,it)}
    val result=if(cardIssue!=null)Calculation.Incomplete(listOf(cardIssue))
        else if(baselineIssue!=null)Calculation.Incomplete(listOf(baselineIssue))
        else if(principal<0)Calculation.Incomplete(listOf("plan.noPrincipal"))
        else if(gross<=0)Calculation.Incomplete(listOf("plan.noPrincipal"))
        else if(incoming==null&&input.sourceEventId==null&&input.mode==FinanceMode.INSTALLMENT&&input.terms.remainingFeeMinor==null&&input.terms.overrides.size!=input.terms.paymentDates.size)Calculation.Incomplete(listOf("plan.remainingFees"))
        else reconciledSchedule(gross,input.mode,input.terms,versions,facts.retainedPeriods(),facts.principalFacts(),LocalDate.parse(facts.asOf))
    val periods=(result as? Calculation.Ready)?.value.orEmpty()
    val plan=FinancialPlanEntity(bookId,old?.id?:id(),account.id,input.name.trim(),input.mode.name,old?.origin?:if(input.sourceEventId==null)"OPENING"else"EVENT",
        input.sourceEventId,account.currency,principal,(!input.additionalFeeUnknown||existing.all{it.confirmed}&&existing.isNotEmpty())&&result is Calculation.Ready&&(periods.lastOrNull()?.period?.remainingPrincipalMinor?:Math.subtractExact(gross,facts.principalFacts().filter{it.date<=input.terms.paymentDates.last()}.fold(0L){sum,p->Math.addExact(sum,p.principalMinor)}))==0L,old?.createdAt?:clock.millis(),
        if(old==null)1 else Math.addExact(old.revision,1),true,json.encodeToString(input.copy(planId=old?.id,facts=facts,projections=periods.map{FinancialPeriodProjection(it.period.number,it.period.date.toString(),it.period.principalMinor,it.period.feeMinor,it.basis)})),reconciliationJson=old?.reconciliationJson)
    val bills=periods.map{p->
        val previous=existing.firstOrNull{it.number==p.period.number}
        if(previous?.id in retainedIds) {
            val kept=requireNotNull(previous)
            if(kept.predictedTotalMinor!=null)kept.copy(active=true) else kept.copy(ruleVersionId=p.ruleId,predictedDate=p.period.date.toString(),
                predictedPrincipalMinor=p.period.principalMinor,predictedFeeMinor=p.period.feeMinor,predictedTotalMinor=p.period.totalMinor,
                basisJson=json.encodeToString(p.basis),revision=Math.addExact(kept.revision,1),active=true)
        }else FinancialBillEntity(bookId,previous?.id?:id(),plan.id,p.period.number,
            input.paymentAccountId?:versions.firstOrNull{it.rule.id==p.ruleId}?.rule?.billing?.paymentAccountId,
            p.ruleId,p.period.date.toString(),p.period.principalMinor,if(input.additionalFeeUnknown)null else p.period.feeMinor,p.period.totalMinor,json.encodeToString(p.basis.copy(additionalFeeUnknown=input.additionalFeeUnknown)),
            null,null,null,null,null,if(previous==null)1 else Math.addExact(previous.revision,1))
    }
    val withdrawn=existing.filter{result is Calculation.Ready&&it.active&&it.id !in retainedIds&&it.number !in bills.map{b->b.number}}.map{it.copy(active=false,revision=Math.addExact(it.revision,1))}
    return FinancialPredictionReview(state,input,plan,result,bills,confirmed,existing.filter{it.id in retainedIds&&!it.confirmed},facts,gross,withdrawn,estimated)
}

suspend fun LedgerRepository.commitFinancialPrediction(review:FinancialPredictionReview):String?=db.withTransaction {
    require(review.calculation is Calculation.Ready)
    if(financialReviewState(review.plan.bookId)!=review.before)return@withTransaction null
    writeFinancialPrediction(review,"RECALCULATED")
}

internal suspend fun LedgerRepository.writeFinancialPrediction(review:FinancialPredictionReview,reason:String,causeEventId:String?=null,origin:String?=null):String {
    val dao=db.financialDao()
    if(review.input.pool!=null)writeFinancialPoolAdoption(review)
    review.before.finance.plans.firstOrNull{it.id==review.plan.id}?.let{old->archiveExistingPlan(old,review.before.finance.bills.filter{it.planId==old.id},predictionFacts(review.before.ledger,review.before.finance,old.id,old.sourceEventId,LocalDate.now(clock).toString()))}
    val plan=if(origin==null)review.plan else review.plan.copy(origin=origin)
    val existed=review.before.finance.plans.any{it.id==plan.id}
    if(!existed)dao.insertPlan(plan)else dao.updatePlan(plan)
    for(bill in review.bills) {
        val old=dao.bill(bill.bookId,bill.id)
        if(old==null)dao.insertBills(listOf(bill))else if(old!=bill)dao.updateBill(bill)
    }
    review.withdrawn.forEach{dao.updateBill(it)}
    archiveFinancialPlan(plan,dao.bills(review.plan.bookId,review.plan.id),if(!existed)"CREATED"else reason,causeEventId,review.facts)
    return review.plan.id
}

