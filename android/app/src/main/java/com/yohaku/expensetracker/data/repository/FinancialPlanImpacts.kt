package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import java.time.LocalDate

enum class FinancialPlanImpactIssue { MANUAL, SOURCE_CHANGED, SOURCE_REMOVED, ACCOUNT_INACTIVE, PAYMENT_ACCOUNT, OPENING_PRINCIPAL, CONTRACT_PRINCIPAL, RETAINED_ARRANGEMENT, RETAINED_POOL }
class FinancialPlanImpact internal constructor(
    val original:FinancialPlanEntity,val originalBills:List<FinancialBillEntity>,val forecast:FinancialPredictionReview?,
    val issue:FinancialPlanImpactIssue?,val facts:FinancialPredictionFacts
) {
    val updatesPrediction:Boolean get()=issue==null&&forecast?.calculation is Calculation.Ready
    val nextBills:List<FinancialBillEntity> get()=if(updatesPrediction)forecast!!.bills.filter{it.active}else originalBills.filter{it.active}
}

internal fun LedgerRepository.financialPlanImpacts(before:FinancialReviewState,after:BookSnapshot,change:PreparedLedgerChange?,contract:DebtContractEntity?):List<FinancialPlanImpact> {
    val finance=before.finance.copy(contracts=after.contracts,repayments=after.repayments,repaymentAllocations=after.repaymentAllocations)
    val state=FinancialReviewState(after,finance,before.limits)
    val rows=(before.finance.repaymentAllocations+after.repaymentAllocations).filter{it.eventId==change?.event?.id}
    val billPlans=before.finance.bills.filter{bill->rows.any{it.billId==bill.id}}.map{it.planId}.toSet()
    val changedAccounts=listOfNotNull(change?.repayment?.debtAccountId,contract?.accountId,before.finance.repayments.firstOrNull{it.eventId==change?.event?.id}?.debtAccountId).toSet()
    return finance.plans.filter{it.active}.filter{plan->
        val contractId=finance.contractPlans.firstOrNull{it.planId==plan.id}?.contractId
            ?:plan.sourceEventId?.let{source->finance.contracts.firstOrNull{it.fundingEventId==source}?.id}
        plan.origin=="POOL"&&plan.accountId in changedAccounts&&financialPoolStale(after,finance,plan)||plan.id in billPlans||rows.any{it.billId==null&&it.contractId!=null&&it.contractId==contractId}||
            contract!=null&&(contract.id==contractId||contract.fundingEventId!=null&&contract.fundingEventId==plan.sourceEventId)
    }.map{plan->
        val facts=predictionFacts(after,finance,plan.id,plan.sourceEventId,LocalDate.now(clock).toString())
        val input=plan.termsJson?.let{json.decodeFromString<FinancialPredictionInput>(it).copy(planId=plan.id)}
        var issue=if(plan.origin=="POOL_RETAINED")FinancialPlanImpactIssue.RETAINED_POOL else if(plan.origin!="POOL"&&finance.arrangements.any{it.originalPlanId==plan.id})FinancialPlanImpactIssue.RETAINED_ARRANGEMENT else if(input==null)FinancialPlanImpactIssue.MANUAL else autoPredictionIssue(state,plan,input,facts)
        val forecast=if(issue==null)prepareFinancialPrediction(state,input!!)else null
        if(forecast?.calculation is Calculation.Ready&&forecast.bills.any{it.active&&it.date>=facts.asOf&&it.paymentAccountId!=null&&after.accounts.none{a->a.id==it.paymentAccountId&&a.active}})issue=FinancialPlanImpactIssue.PAYMENT_ACCOUNT
        FinancialPlanImpact(plan,finance.bills.filter{it.planId==plan.id}.sortedBy{it.number},forecast,issue,facts)
    }
}

private fun LedgerRepository.autoPredictionIssue(state:FinancialReviewState,plan:FinancialPlanEntity,input:FinancialPredictionInput,facts:FinancialPredictionFacts):FinancialPlanImpactIssue? {
    val s=state.ledger
    if(plan.origin=="POOL_RETAINED")return FinancialPlanImpactIssue.RETAINED_POOL
    if(s.accounts.none{it.id==plan.accountId&&it.active})return FinancialPlanImpactIssue.ACCOUNT_INACTIVE
    if(plan.origin=="POOL")return null
    if(input.paymentAccountId!=null&&s.accounts.none{it.id==input.paymentAccountId&&it.active&&!AccountType.valueOf(it.type).debt})return FinancialPlanImpactIssue.PAYMENT_ACCOUNT
    if(state.finance.arrangements.any{it.targetPlanId==plan.id})return null
    if(input.sourceEventId!=null) {
        val source=s.visibleEvents.firstOrNull{it.id==input.sourceEventId}?:return FinancialPlanImpactIssue.SOURCE_REMOVED
        if(source.accountId!=plan.accountId||source.historicalIncluded||source.date!=input.terms.agreementDate||input.terms.overrides.isNotEmpty()&&s.financialSourcePrincipal(source.id,plan.accountId)?.minor!=plan.principalMinor)return FinancialPlanImpactIssue.SOURCE_CHANGED
    }else {
        val amount=input.openingPrincipalMinor?:plan.principalMinor?:return FinancialPlanImpactIssue.OPENING_PRINCIPAL
        val others=state.finance.plans.filter{it.active&&it.accountId==plan.accountId&&it.origin=="OPENING"&&it.id!=plan.id}
        if(plan.origin!="MANUAL"&&(others.any{it.principalMinor==null}||amount>Math.subtractExact(s.accounts.first{it.id==plan.accountId}.openingMinor,others.fold(0L){sum,p->Math.addExact(sum,p.principalMinor?:0)})))return FinancialPlanImpactIssue.OPENING_PRINCIPAL
        if(facts.contract!=null&&facts.contract.principalMinor!=amount)return FinancialPlanImpactIssue.CONTRACT_PRINCIPAL
    }
    return null
}

internal suspend fun LedgerRepository.writeFinancialPlanImpacts(review:FinancialLedgerReview,causeEventId:String?) {
    val reason=if(review.repaymentInput!=null)"REPAYMENT"else"BORROWING"
    writeFinancialPlanImpacts(review.planImpacts,reason,causeEventId)
}
internal suspend fun LedgerRepository.writeFinancialPlanImpacts(impacts:List<FinancialPlanImpact>,reason:String,causeEventId:String?) {
    for(impact in impacts) {
        if(impact.updatesPrediction)writeFinancialPrediction(impact.forecast!!,reason,causeEventId)
        else {
            archiveExistingPlan(impact.original,impact.originalBills)
            val next=impact.original.copy(revision=Math.addExact(impact.original.revision,1))
            db.financialDao().updatePlan(next)
            archiveFinancialPlan(next,impact.originalBills,if(impact.issue==FinancialPlanImpactIssue.MANUAL)"${reason}_MANUAL"else"${reason}_REVIEW",causeEventId,impact.facts)
        }
    }
}
