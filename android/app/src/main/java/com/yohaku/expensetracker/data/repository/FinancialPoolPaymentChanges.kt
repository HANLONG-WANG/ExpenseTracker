package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.*
import java.lang.Math.subtractExact

@Serializable data class PoolPaymentChangeInput(val planId:String,val effectiveDate:String,val setting:RevolvingPaymentSetting,
    val feeMinor:Long?,val feeDate:String?=null,val feeIncludedInRetained:Boolean=false,val referenceRates:List<ReferenceRateSnapshot> = emptyList())
@Serializable data class PoolPaymentChange(val id:String,val effectiveDate:String,val setting:RevolvingPaymentSetting,
    val feeMinor:Long,val feeDate:String?,val feeIncludedInRetained:Boolean,val appliedAt:String,val exceptionAccepted:Boolean=false,
    val comparison:FinancialArrangementComparison?=null)
class PoolPaymentChangeReview internal constructor(internal val before:FinancialReviewState,val input:PoolPaymentChangeInput,
    val original:FinancialPlanEntity,val prediction:FinancialPredictionReview?,val calculation:Calculation<List<PredictedSchedulePeriod>>,
    val change:PoolPaymentChange?,val comparison:FinancialArrangementComparison?,val deadlines:List<ChangeDeadline>,val zoneId:String)

suspend fun LedgerRepository.previewPoolPaymentChange(bookId:String,input:PoolPaymentChangeInput):PoolPaymentChangeReview=db.withTransaction {
    preparePoolPaymentChange(financialReviewState(bookId),input)
}

private fun LedgerRepository.preparePoolPaymentChange(state:FinancialReviewState,input:PoolPaymentChangeInput):PoolPaymentChangeReview {
    val s=state.ledger;val f=state.finance
    val old=requireNotNull(f.plans.firstOrNull{it.id==input.planId&&it.active&&it.origin=="POOL"})
    val definition=json.decodeFromString<FinancialPredictionInput>(old.termsJson!!).copy(planId=old.id)
    LocalDate.parse(input.effectiveDate);LocalDate.parse(input.setting.fromPaymentDate)
    val rule=f.rules.filter{it.accountId==old.accountId&&it.mode==old.mode&&it.effectiveFrom<=input.effectiveDate}.maxWithOrNull(compareBy<FinancialRuleEntity>{it.effectiveFrom}.thenBy{it.version})?.let{json.decodeFromString<FinancialRuleVersion>(it.json)}
    val members=f.plans.filter{it.accountId==old.accountId&&it.mode==old.mode&&(it.id==old.id||it.origin=="POOL_RETAINED")}.map{it.id}.toSet()
    val bills=f.bills.filter{it.active&&it.planId in members}
    val deadlines=bills.filter{it.date>=input.setting.fromPaymentDate}.flatMap{b->rule?.deadlines.orEmpty().filter{it.billId==b.id&&it.changeType.name==old.mode}}
    val zone=rule?.zoneId?:clock.zone.id
    fun failed(result:Calculation<List<PredictedSchedulePeriod>>,prediction:FinancialPredictionReview?=null)=PoolPaymentChangeReview(state,input,old,prediction,result,null,null,deadlines,zone)
    if(financialPoolStale(s,f,old))return failed(Calculation.Incomplete(listOf("arrangement.refreshOriginal")))
    if(input.effectiveDate<definition.terms.agreementDate||input.setting.fromPaymentDate<input.effectiveDate||input.setting.fromPaymentDate !in definition.terms.paymentDates)return failed(Calculation.Incomplete(listOf("pool.paymentChangeDate")))
    if(input.feeMinor==null)return failed(Calculation.Incomplete(listOf("arrangement.changeFee")))
    require(input.feeMinor>=0)
    if(input.feeMinor>0&&(input.feeDate==null||input.feeDate !in definition.terms.paymentDates||input.feeDate<input.effectiveDate))return failed(Calculation.Incomplete(listOf("pool.mergeFeeDate")))
    val change=PoolPaymentChange(id(),input.effectiveDate,input.setting,input.feeMinor,input.feeDate,input.feeIncludedInRetained,clock.instant().toString())
    val next=definition.copy(pool=definition.pool!!.copy(paymentChanges=definition.pool.paymentChanges+change))
    val prediction=prepareFinancialPool(state,next,allowPaymentChange=true)
    if(prediction.calculation !is Calculation.Ready)return failed(prediction.calculation,prediction)
    val comparison=poolPaymentComparison(state,old,bills,prediction,input)
    return PoolPaymentChangeReview(state,input,old,prediction,prediction.calculation,change,comparison,deadlines,zone)
}

private fun LedgerRepository.poolPaymentComparison(state:FinancialReviewState,old:FinancialPlanEntity,bills:List<FinancialBillEntity>,prediction:FinancialPredictionReview,input:PoolPaymentChangeInput):FinancialArrangementComparison {
    val s=state.ledger;val f=state.finance
    val next=f.copy(plans=f.plans.map{if(it.id==old.id)prediction.plan else it},bills=f.bills.filter{it.planId!=old.id}+prediction.bills+prediction.withdrawn)
    fun row(b:FinancialBillEntity):ArrangementMoneyRow {val due=s.billOutstanding(b);return ArrangementMoneyRow(b.date,due.principalMinor,due.feeMinor,due.totalMinor,b.paymentAccountId)}
    val original=bills.map(::row)
    val proposed=(bills.filter{it.planId!=old.id}+prediction.bills).filter{it.active}.map(::row)
    val first=(original+proposed).filter{it.date>=input.setting.fromPaymentDate&&it.totalMinor>0}.minOfOrNull{it.date}
    val through=maxOf(LocalDate.now(clock),LocalDate.parse((original.map{it.date}+proposed.map{it.date}).max()))
    val stale=f.plans.filter{financialPredictionStale(s,f,it)}.map{it.id}.toSet()
    val before=s.cashTimeline(f,LocalDate.now(clock),through,stale,input.referenceRates).accountRisks()
    val after=s.cashTimeline(next,LocalDate.now(clock),through,stale-old.id,input.referenceRates).accountRisks()
    val funding=before.map{a->val b=after.first{it.opening.account.id==a.opening.account.id};ArrangementFundingAccount(a.opening.account.id,a.opening.account.currency,a.maximumGapMinor,b.maximumGapMinor,a.includingIncomeGapMinor,b.includingIncomeGapMinor,a.firstRiskDate,b.firstRiskDate,a.estimated||b.estimated)}
    val oldFees=original.map{it.feeMinor}.knownTotal();val newFees=proposed.map{it.feeMinor}.knownTotal()
    val residual=(prediction.calculation as Calculation.Ready).value.lastOrNull()?.basis?.remainingPrincipalMinor?:0
    val oldInput=json.decodeFromString<FinancialPredictionInput>(old.termsJson!!)
    val originalResidual=oldInput.projections.lastOrNull()?.basis?.remainingPrincipalMinor?:0
    return FinancialArrangementComparison(original,proposed,first,first?.let{d->subtractExact(original.filter{it.date<=d}.sumOf{it.totalMinor},proposed.filter{it.date<=d}.sumOf{it.totalMinor})},oldFees,newFees,
        if(old.complete&&prediction.plan.complete&&!input.feeIncludedInRetained&&oldFees!=null&&newFees!=null)subtractExact(newFees,oldFees)else null,originalResidual,residual,funding,through.toString(),(original+proposed).any{it.paymentAccountId==null},input.referenceRates,originalComplete=old.complete,proposedComplete=prediction.plan.complete)
}

suspend fun LedgerRepository.commitPoolPaymentChange(review:PoolPaymentChangeReview,appliedAt:String,platformConfirmed:Boolean,exceptionAccepted:Boolean=false):String?=db.withTransaction {
    require(platformConfirmed){"请先确认已在平台办理 / 返済先で手続き済みであることを確認してください"}
    require(review.calculation is Calculation.Ready)
    val instant=Instant.parse(appliedAt)
    require(instant<=clock.instant()){"办理时间不能在未来 / 手続き時刻は未来に設定できません"}
    require(exceptionAccepted||review.deadlines.all{instant<=it.instant()}){"已超过变更期限，请明确记录平台接受 / 変更締切後です。返済先の受理を明示してください"}
    if(financialReviewState(review.original.bookId)!=review.before)return@withTransaction null
    val change=review.change!!.copy(appliedAt=appliedAt,exceptionAccepted=exceptionAccepted,comparison=review.comparison)
    val definition=review.prediction!!.input
    val input=definition.copy(pool=definition.pool!!.copy(paymentChanges=definition.pool.paymentChanges.map{if(it.id==change.id)change else it}))
    val final=prepareFinancialPool(review.before,input,allowPaymentChange=true)
    check(final.calculation is Calculation.Ready)
    writeFinancialPrediction(final,"POOL_PAYMENT_CHANGED")
}
