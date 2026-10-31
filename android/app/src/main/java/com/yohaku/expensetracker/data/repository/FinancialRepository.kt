package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.AccountType
import com.yohaku.expensetracker.domain.model.EntryKind
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.time.ZoneId

internal data class FinancialReviewState(val ledger:BookSnapshot,val finance:FinancialGraph,val limits:CreditLimitGraph)
internal suspend fun LedgerRepository.financialReviewState(bookId:String):FinancialReviewState {
    val book=dao.book(bookId)?:error("账本不存在 / 帳簿がありません")
    val graph=db.financialDao().graph(bookId).first()!!
    val limits=db.creditLimitDao().graph(bookId).first()!!
    return FinancialReviewState(snapshot(book).first().reviewState(),graph.copy(
        book=graph.book.copy(theme="",language=""),rules=graph.rules.sortedBy{it.id},plans=graph.plans.sortedBy{it.id},
        bills=graph.bills.sortedBy{it.id},validations=graph.validations.sortedBy{it.id},contracts=graph.contracts.sortedBy{it.id},repayments=graph.repayments.sortedBy{it.eventId},repaymentAllocations=graph.repaymentAllocations.sortedBy{it.id},contractPlans=graph.contractPlans.sortedBy{it.planId},arrangements=graph.arrangements.sortedBy{it.id},billAdjustments=graph.billAdjustments.sortedBy{it.id}),limits.copy(book=limits.book.copy(theme="",language=""),configs=limits.configs.sortedBy{it.id},groups=limits.groups.sortedBy{it.id},members=limits.members.sortedBy{it.accountId},recoveries=limits.recoveries.sortedBy{it.id},observations=limits.observations.sortedBy{it.id}))
}

class FinancialRuleReview internal constructor(
    internal val before:FinancialReviewState,val rule:FinancialRuleVersion,val version:Int,
    val affectedPredictions:List<FinancialBillEntity>,val retainedConfirmedBills:List<FinancialBillEntity>
) { val readiness:RuleReadiness get()=rule.readiness() }

internal fun requireMode(account:AccountEntity,mode:FinanceMode) {
    val allowed=if(account.type==AccountType.CREDIT.name)setOf(FinanceMode.SINGLE,FinanceMode.INSTALLMENT,FinanceMode.REVOLVING)
        else if(account.type==AccountType.LOAN.name)setOf(FinanceMode.FIXED_LOAN,FinanceMode.CREDIT_LINE)else emptySet()
    require(account.active&&mode in allowed){"请选择相应的信用卡或贷款账户 / 対応するカード・ローン口座を選択してください"}
}

internal suspend fun LedgerRepository.requirePaymentAccount(bookId:String,id:String?) {
    if(id==null)return
    val account=dao.account(bookId,id)
    require(account!=null&&account.active&&!AccountType.valueOf(account.type).debt){"请选择本账本的资金账户 / この帳簿の資金口座を選択してください"}
}

suspend fun LedgerRepository.previewFinancialRule(bookId:String,accountId:String,input:FinancialRuleVersion):FinancialRuleReview=db.withTransaction {
    require(input.bookId==bookId&&input.accountId==accountId)
    val state=financialReviewState(bookId)
    val account=requireNotNull(state.ledger.accounts.firstOrNull{it.id==accountId}){"账户不属于本账本 / 別の帳簿の口座です"}
    requireMode(account,input.mode)
    LocalDate.parse(input.effectiveFrom);ZoneId.of(input.zoneId)
    requirePaymentAccount(bookId,input.billing?.paymentAccountId)
    input.deadlines.forEach{deadline->
        deadline.instant()
        require(input.mode==deadline.changeType){"变更期限须属于当前配置模式 / 変更締切は設定中の方式を指定してください"}
        val bill=state.finance.bills.firstOrNull{it.id==deadline.billId}
        require(bill!=null&&state.finance.plans.any{it.id==bill.planId&&it.accountId==accountId}){"请选择本账户的具体账期 / この口座の対象請求を選択してください"}
    }
    val rule=input.copy(id=id())
    val old=state.finance.rules.filter{it.accountId==accountId&&it.mode==input.mode.name}
    val version=Math.addExact(old.maxOfOrNull{it.version}?:0,1)
    val plans=state.finance.plans.filter{it.active&&it.accountId==accountId&&it.mode==input.mode.name}.map{it.id}.toSet()
    val affected=state.finance.bills.filter{it.active&&it.planId in plans&&it.date>=input.effectiveFrom}
    FinancialRuleReview(state,rule,version,affected.filterNot{it.confirmed},affected.filter{it.confirmed})
}

/** Incomplete rules may be stored. Their readiness remains explicit, never "validated". */
suspend fun LedgerRepository.commitFinancialRule(review:FinancialRuleReview):String?=db.withTransaction {
    val r=review.rule
    if(financialReviewState(r.bookId)!=review.before)return@withTransaction null
    db.financialDao().insertRule(FinancialRuleEntity(r.bookId,r.id,r.accountId,r.mode.name,review.version,r.effectiveFrom,json.encodeToString(r),clock.millis()))
    // Existing bill snapshots are untouched; callers can explicitly recalculate unconfirmed plans.
    r.id
}

suspend fun LedgerRepository.effectiveFinancialRule(bookId:String,accountId:String,mode:FinanceMode,date:LocalDate):FinancialRuleVersion? {
    val selected=db.financialDao().rules(bookId,accountId).filter{it.mode==mode.name&&it.effectiveFrom<=date.toString()}
        .maxWithOrNull(compareBy<FinancialRuleEntity>{it.effectiveFrom}.thenBy{it.version})?:return null
    return json.decodeFromString(selected.json)
}

/** Partial known schedules are useful even when total debt or its breakdown is unknown. */
@kotlinx.serialization.Serializable data class KnownBillInput(
    val date:String,val totalMinor:Long,val principalMinor:Long?=null,val feeMinor:Long?=null,val paymentAccountId:String?=null
)
data class ManualFinancialPlanInput(
    val accountId:String,val name:String,val mode:FinanceMode,val periods:List<KnownBillInput>,
    val principalMinor:Long?=null,val complete:Boolean=false,val opening:Boolean=false,val sourceEventId:String?=null
)
class ManualFinancialPlanReview internal constructor(
    internal val before:FinancialReviewState,val plan:FinancialPlanEntity,val bills:List<FinancialBillEntity>
)

internal fun validateKnownBill(input:KnownBillInput) {
    LocalDate.parse(input.date)
    require(input.totalMinor>=0&&input.principalMinor?.let{it>=0}!=false&&input.feeMinor?.let{it>=0}!=false){"金额不能为负 / 金額は0以上にしてください"}
    if(input.principalMinor!=null&&input.feeMinor!=null)require(Math.addExact(input.principalMinor,input.feeMinor)==input.totalMinor){"本金与费用合计不符 / 元金と手数料の合計が一致しません"}
    require(input.principalMinor?.let{it<=input.totalMinor}!=false&&input.feeMinor?.let{it<=input.totalMinor}!=false){"金额组成超过总额 / 内訳が合計を超えています"}
}

suspend fun LedgerRepository.previewManualFinancialPlan(bookId:String,input:ManualFinancialPlanInput):ManualFinancialPlanReview=db.withTransaction {
    val state=financialReviewState(bookId)
    val account=requireNotNull(state.ledger.accounts.firstOrNull{it.id==input.accountId}){"账户不属于本账本 / 別の帳簿の口座です"}
    requireMode(account,input.mode)
    require(input.name.isNotBlank()&&input.periods.isNotEmpty()){ "请输入计划名称和至少一期金额 / プラン名と1回以上の支払額を入力してください" }
    input.periods.forEach { validateKnownBill(it);requirePaymentAccount(bookId,it.paymentAccountId) }
    require(input.periods.zipWithNext().all{(a,b)->a.date<b.date}){"请按还款日期顺序填写 / 支払日順に入力してください"}
    require(input.principalMinor?.let{it>0}!=false)
    if(input.complete) {
        require(input.principalMinor!=null&&input.periods.all{it.principalMinor!=null}){"完整计划需要本金资料 / 全期間のプランには元金の情報が必要です"}
        require(input.periods.fold(0L){sum,p->Math.addExact(sum,p.principalMinor!!)}==input.principalMinor){"逐期本金与计划本金不符 / 各回の元金合計がプラン元金と一致しません"}
    }else if(input.principalMinor!=null) {
        require(input.periods.fold(0L){sum,p->Math.addExact(sum,p.principalMinor?:0)}<=input.principalMinor)
    }
    require(!input.opening||input.sourceEventId==null)
    input.sourceEventId?.let{eventId->
        val event=state.ledger.visibleEvents.firstOrNull{it.id==eventId}
        require(event!=null&&event.accountId==account.id&&event.isFinancialSource(account)){"请选择本账户的消费或借入记录 / この口座の支出・借入記録を選択してください"}
        require(state.finance.plans.none{it.active&&it.sourceEventId==eventId}){"该记录已有计划 / この記録には既にプランがあります"}
    }
    input.sourceEventId?.let{source->state.finance.contracts.firstOrNull{it.fundingEventId==source}?.let{contract->
        require(contract.mode==input.mode.name){"计划方式须与借款合同一致，变更需单独记录已办理 / プランの方式は借入契約と一致させてください。変更は手続き済みとして別途記録してください"}
    }}
    val plan=FinancialPlanEntity(bookId,id(),account.id,input.name.trim(),input.mode.name,
        if(input.opening)"OPENING" else if(input.sourceEventId!=null)"EVENT" else "MANUAL",input.sourceEventId,account.currency,input.principalMinor,input.complete,clock.millis())
    val bills=input.periods.mapIndexed{index,p->FinancialBillEntity(bookId,id(),plan.id,index+1,p.paymentAccountId,null,
        null,null,null,null,null,p.date,p.principalMinor,p.feeMinor,p.totalMinor,clock.millis())}
    ManualFinancialPlanReview(state,plan,bills)
}

suspend fun LedgerRepository.commitManualFinancialPlan(review:ManualFinancialPlanReview):String?=db.withTransaction {
    if(financialReviewState(review.plan.bookId)!=review.before)return@withTransaction null
    db.financialDao().insertPlan(review.plan)
    db.financialDao().insertBills(review.bills)
    val created=review.before.finance.copy(plans=review.before.finance.plans+review.plan,bills=review.before.finance.bills+review.bills)
    archiveFinancialPlan(review.plan,review.bills,"CREATED",facts=predictionFacts(review.before.ledger,created,review.plan.id,review.plan.sourceEventId,LocalDate.now(clock).toString()))
    review.plan.id
}

class BillConfirmationReview internal constructor(
    internal val before:FinancialReviewState,val original:FinancialBillEntity,val bill:FinancialBillEntity
) {
    val totalDifferenceMinor:Long? get()=bill.predictedTotalMinor?.let{Math.subtractExact(bill.confirmedTotalMinor!!,it)}
    val dateChanged:Boolean? get()=bill.predictedDate?.let{it!=bill.confirmedDate}
}

/** Also used for an explicit correction of a confirmed bill; rule edits never take this path. */
suspend fun LedgerRepository.previewFinancialBillConfirmation(bookId:String,billId:String,input:KnownBillInput):BillConfirmationReview=db.withTransaction {
    val state=financialReviewState(bookId)
    val old=state.finance.bills.firstOrNull{it.id==billId}?:error("账单不存在 / 請求がありません")
    require(old.active){"此预测已退出当前计划 / この予測は現在のプランから除外されています"}
    validateKnownBill(input);requirePaymentAccount(bookId,input.paymentAccountId)
    val next=old.copy(paymentAccountId=input.paymentAccountId,confirmedDate=input.date,confirmedPrincipalMinor=input.principalMinor,
        confirmedFeeMinor=input.feeMinor,confirmedTotalMinor=input.totalMinor,confirmedAt=clock.millis(),revision=Math.addExact(old.revision,1))
    BillConfirmationReview(state,old,next)
}

suspend fun LedgerRepository.commitFinancialBillConfirmation(review:BillConfirmationReview):Boolean=db.withTransaction {
    if(financialReviewState(review.bill.bookId)!=review.before)return@withTransaction false
    val dao=db.financialDao()
    val plan=review.before.finance.plans.first{it.id==review.bill.planId}
    archiveExistingPlan(plan,review.before.finance.bills.filter{it.planId==plan.id},predictionFacts(review.before.ledger,review.before.finance,plan.id,plan.sourceEventId,LocalDate.now(clock).toString()))
    dao.updateBill(review.bill)
    val next=plan.copy(revision=Math.addExact(plan.revision,1))
    dao.updatePlan(next)
    val bills=dao.bills(next.bookId,next.id)
    val updated=review.before.finance.copy(plans=review.before.finance.plans.filter{it.id!=next.id}+next,bills=review.before.finance.bills.filter{it.planId!=next.id}+bills)
    archiveFinancialPlan(next,bills,"BILL_CONFIRMED",facts=predictionFacts(review.before.ledger,updated,next.id,next.sourceEventId,LocalDate.now(clock).toString()))
    true
}

suspend fun LedgerRepository.validateFinancialRule(bookId:String,billId:String):FinancialRuleValidationEntity=db.withTransaction {
    val bill=db.financialDao().bill(bookId,billId)?:error("账单不存在 / 請求がありません")
    require(bill.confirmed&&bill.ruleVersionId!=null&&bill.predictedDate!=null&&bill.predictedTotalMinor!=null){"需要同一期的预测与确认账单 / 同じ回の予測と確定請求が必要です"}
    val validation=FinancialRuleValidationEntity(bookId,id(),bill.ruleVersionId,bill.id,bill.revision,
        if(bill.confirmedPrincipalMinor!=null&&bill.predictedPrincipalMinor!=null)Math.subtractExact(bill.confirmedPrincipalMinor,bill.predictedPrincipalMinor)else null,
        if(bill.confirmedFeeMinor!=null&&bill.predictedFeeMinor!=null)Math.subtractExact(bill.confirmedFeeMinor,bill.predictedFeeMinor)else null,
        Math.subtractExact(bill.confirmedTotalMinor!!,bill.predictedTotalMinor),bill.confirmedDate!=bill.predictedDate,clock.millis())
    db.financialDao().insertValidation(validation);validation
}

/** A later discrepancy or a changed bill invalidates an earlier passing comparison. */
fun FinancialGraph.ruleValidationStatus(ruleId:String):String {
    val checks=validations.filter{it.ruleVersionId==ruleId&&bills.any{b->b.id==it.billId&&b.revision==it.billRevision}}
    if(checks.isEmpty())return "UNVERIFIED"
    if(checks.any{it.dateChanged||it.totalDifferenceMinor!=0L||it.principalDifferenceMinor?.let{n->n!=0L}==true||it.feeDifferenceMinor?.let{n->n!=0L}==true})return "DIFFERENT"
    return if(checks.all{it.principalDifferenceMinor==0L&&it.feeDifferenceMinor==0L})"VERIFIED" else "INCOMPLETE"
}
