package com.yohaku.expensetracker.domain.finance

import java.time.LocalDate
import kotlinx.serialization.Serializable

@Serializable data class AccrualWindow(val start:String,val end:String)
@Serializable data class AdditionalPeriodFee(val number:Int,val minor:Long)
@Serializable data class RevolvingPaymentSetting(val fromPaymentDate:String,val method:PaymentMethod?,val paymentMinor:Long?=null,
    val tiers:List<PaymentTier> = emptyList(),val tierPaymentIncludesFees:Boolean?=null,val tierBalanceDate:TierBalanceDate?=null) {
    fun apply(rule:RevolvingRule)=rule.copy(method=method,paymentMinor=paymentMinor,tiers=tiers,tierPaymentIncludesFees=tierPaymentIncludesFees,tierBalanceDate=tierBalanceDate)
}
internal fun FinancialScheduleTerms.paymentSetting(date:String)=paymentSettings.sortedBy{it.fromPaymentDate}.lastOrNull{it.fromPaymentDate<=date}
internal fun FinancialScheduleTerms.paymentRule(date:String,base:RevolvingRule)=paymentSetting(date)?.apply(base)?:base
@Serializable data class FinancialScheduleTerms(
    val agreementDate:String,val paymentDates:List<String>,val accrualWindows:List<AccrualWindow> = emptyList(),
    val overrides:List<PeriodOverride> = emptyList(),val firstPeriodNumber:Int=1,val remainingFeeMinor:Long?=null,
    val additionalFees:List<AdditionalPeriodFee> = emptyList(),val paymentSettings:List<RevolvingPaymentSetting> = emptyList()
)
data class EffectiveFinancialRule(val version:Int,val rule:FinancialRuleVersion)
data class ConfirmedSchedulePeriod(val number:Int,val principalMinor:Long?,val feeMinor:Long?,val totalMinor:Long,val date:LocalDate)
@Serializable data class InterestSegmentSnapshot(val start:String,val end:String,val balanceMinor:Long,val days:Long,val annualRate:String,val yearDays:Int,val ruleId:String?)
@Serializable data class FinancialCalculationBasis(
    val ruleIds:List<String>,val remainingPrincipalMinor:Long,val explanation:List<String>,
    val segments:List<InterestSegmentSnapshot> = emptyList(),val start:String?=null,val end:String?=null,
    val agreementDate:String?=null,val closingDate:String?=null,
    val forecastFrom:String?=null,val assumedPrincipalMinor:Long?=null,val principalFacts:List<SchedulePrincipalFact> = emptyList(),val principalAdditions:List<SchedulePrincipalAddition> = emptyList(),
    val paymentSetting:RevolvingPaymentSetting?=null,val additionalFeeMinor:Long=0,val additionalFeeUnknown:Boolean=false
)
data class PredictedSchedulePeriod(val period:PaymentPeriod,val ruleId:String,val basis:FinancialCalculationBasis)

/** A finite user-defined horizon. Unpaid principal after that horizon remains visible. */
fun predictedSchedule(
    principalMinor:Long,mode:FinanceMode,terms:FinancialScheduleTerms,versions:List<EffectiveFinancialRule>,
    confirmed:List<ConfirmedSchedulePeriod> = emptyList(),changes:List<PrincipalChange> = emptyList()
):Calculation<List<PredictedSchedulePeriod>> {
    require(principalMinor>0&&terms.firstPeriodNumber>0&&terms.paymentDates.isNotEmpty()){"本金和起始期数须大于0，并填写付款日期 / 元金と開始回数は0より大きく、支払日を入力してください"}
    val agreement=LocalDate.parse(terms.agreementDate)
    val dates=terms.paymentDates.map(LocalDate::parse)
    require(dates.zipWithNext().all{(a,b)->a<b}){"请按付款日期顺序填写 / 支払日順に入力してください"}
    require(confirmed.map{it.number}.distinct().size==confirmed.size)
    val timeline=versions.filter{it.rule.mode==mode}.groupBy{it.rule.effectiveFrom}.values.map{it.maxBy{v->v.version}}
        .sortedBy{it.rule.effectiveFrom}
    fun at(date:LocalDate)=timeline.lastOrNull{it.rule.effectiveFrom<=date.toString()}?.rule
    val contract=at(agreement)?:return Calculation.Incomplete(listOf("interest.ruleVersion"))
    when(val status=contract.readiness()) {
        is RuleReadiness.Incomplete->return Calculation.Incomplete(status.fields)
        is RuleReadiness.Unsupported->return Calculation.Unsupported(status.reason)
        RuleReadiness.Complete->Unit
    }
    if(mode in listOf(FinanceMode.REVOLVING,FinanceMode.CREDIT_LINE))return predictedRevolvingSchedule(principalMinor,terms,timeline,confirmed,changes)
    if(changes.isNotEmpty())return Calculation.Unsupported("loan.actualEventsRequireAdjustedPlan")
    if(mode==FinanceMode.SINGLE&&dates.size!=1)return Calculation.Unsupported("billing.singleRequiresOnePeriod")
    val fixed:List<PaymentPeriod>?=when(mode) {
        FinanceMode.SINGLE->{val payment=billingDates(agreement,contract.billing!!,dates.single()).paymentDate
            listOf(PaymentPeriod(1,payment,principalMinor,0,0,listOf("billing.single")))}
        FinanceMode.INSTALLMENT->when(val result=installmentPlan(principalMinor,dates.size,dates,
            if(terms.remainingFeeMinor==null)contract.installment!!else contract.installment!!.copy(supportedPeriods=listOf(dates.size),feeMethod=InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=terms.remainingFeeMinor),terms.overrides)) {
            is Calculation.Ready->result.value
            is Calculation.Incomplete->return result
            is Calculation.Unsupported->return result
            is Calculation.CannotSettle->return result
        }
        FinanceMode.FIXED_LOAN->when(val result=fixedLoanPlan(principalMinor,agreement,dates,contract.loanMethod!!,contract.loanInterest,contract.loanPrincipalRemainder,terms.overrides)) {
            is Calculation.Ready->result.value
            is Calculation.Incomplete->return result
            is Calculation.Unsupported->return result
            is Calculation.CannotSettle->return result
        }
        else->null
    }
    if(mode==FinanceMode.SINGLE)require(dates.size==1)
    if(fixed==null)require(terms.accrualWindows.size==dates.size){"请为每一期填写计费起止日期 / 各回の計算開始日・終了日を入力してください"}
    var balance=principalMinor
    val output=mutableListOf<PredictedSchedulePeriod>()
    for(index in dates.indices) {
        if(balance==0L&&fixed==null)break
        val number=index+1
        val known=confirmed.firstOrNull{it.number==number}
        val payRule=if(fixed!=null)contract else at(LocalDate.parse(terms.accrualWindows[index].end))?:return Calculation.Incomplete(listOf("interest.ruleVersion"))
        when(val status=payRule.readiness()) {
            is RuleReadiness.Incomplete->return Calculation.Incomplete(status.fields)
            is RuleReadiness.Unsupported->return Calculation.Unsupported(status.reason)
            RuleReadiness.Complete->Unit
        }
        val window=terms.accrualWindows.getOrNull(index)
        var period=fixed!![index]
        if(fixed!=null&&known==null) {
            // Actual earlier principal changes the outstanding principal; fees are not waived.
            var fee=period.feeMinor
            var details=period.interestCharge
            if(mode==FinanceMode.FIXED_LOAN&&contract.loanMethod!=LoanMethod.MANUAL) {
                val start=if(index==0)agreement else dates[index-1]
                fee=when(val charge=interestCharge(balance,start,dates[index],contract.loanInterest!!,firstPeriod=terms.firstPeriodNumber+index==1)) {
                    is Calculation.Ready->{details=charge.value;charge.value.feeMinor}
                    is Calculation.Incomplete->return charge
                    is Calculation.Unsupported->return charge
                    is Calculation.CannotSettle->return charge
                }
            }
            val scheduledPrincipal=if(mode==FinanceMode.FIXED_LOAN&&contract.loanMethod==LoanMethod.ANNUITY) {
                if(period.totalMinor<=fee&&balance>0)return Calculation.CannotSettle(balance,fee,period.totalMinor)
                if(index==dates.lastIndex)balance else period.totalMinor-fee
            }else period.principalMinor
            period=period.copy(principalMinor=minOf(balance,scheduledPrincipal),feeMinor=fee,interestCharge=details)
        }
        // A platform-confirmed principal is authoritative for the following projection.
        val principal=if(known!=null)known.principalMinor?:if(index==dates.lastIndex)period.principalMinor else return Calculation.Incomplete(listOf("plan.confirmedPrincipal"))else period.principalMinor
        require(principal>=0&&principal<=balance){"已确认本金超过剩余欠款，请明确调整平台计划 / 確定元金が残債を超えています。返済先の予定を明示的に修正してください"}
        balance=Math.subtractExact(balance,principal)
        val segments=period.interestCharge?.segments.orEmpty().map{InterestSegmentSnapshot(it.start.toString(),it.endInclusive.toString(),it.balanceMinor,it.days,it.annualRate!!,it.yearDays!!,it.ruleId)}
        val ids=(listOf(payRule.id)+segments.mapNotNull{it.ruleId}).distinct()
        output+=PredictedSchedulePeriod(period.copy(remainingPrincipalMinor=balance),payRule.id,
            FinancialCalculationBasis(ids,balance,period.basis,segments,window?.start,window?.end,terms.agreementDate,
                if(mode in listOf(FinanceMode.SINGLE,FinanceMode.INSTALLMENT))billingDates(agreement,contract.billing!!).closingDate.toString()else null))
    }
    return Calculation.Ready(output)
}

/** Previous scheduled principal affects interest on its payment date, never its closing date. */
private fun predictedRevolvingSchedule(
    principalMinor:Long,terms:FinancialScheduleTerms,timeline:List<EffectiveFinancialRule>,
    confirmed:List<ConfirmedSchedulePeriod>,changes:List<PrincipalChange>
):Calculation<List<PredictedSchedulePeriod>> {
    require(terms.accrualWindows.size==terms.paymentDates.size){"请为每期填写计费起止日期 / 各回の計算開始日・終了日を入力してください"}
    val planned=mutableListOf<PrincipalChange>()
    val output=mutableListOf<PredictedSchedulePeriod>()
    val rates=timeline.map{InterestRateVersion(LocalDate.parse(it.rule.effectiveFrom),it.rule.revolving!!.interest,it.rule.id)}
    fun position(date:LocalDate,inclusive:Boolean):Long=(changes+planned).filter{if(inclusive)it.date<=date else it.date<date}.fold(principalMinor){sum,c->Math.addExact(sum,c.deltaMinor)}
    var previousLast:LocalDate?=null
    var previousPayment:LocalDate?=null
    for(index in terms.paymentDates.indices) {
        val window=terms.accrualWindows[index]
        val start=LocalDate.parse(window.start);val end=LocalDate.parse(window.end)
        val payRule=timeline.lastOrNull{it.rule.effectiveFrom<=end.toString()}?.rule?:return Calculation.Incomplete(listOf("interest.ruleVersion"))
        when(val status=payRule.readiness()) {
            is RuleReadiness.Incomplete->return Calculation.Incomplete(status.fields)
            is RuleReadiness.Unsupported->return Calculation.Unsupported(status.reason)
            RuleReadiness.Complete->Unit
        }
        val initial=rates.lastOrNull{it.effectiveDate<=start}?:return Calculation.Incomplete(listOf("interest.ruleVersion"))
        val first=if(initial.rule.frequency==ChargeFrequency.DAILY&&initial.rule.includeStart==false)start.plusDays(1)else start
        val last=if(initial.rule.frequency==ChargeFrequency.DAILY&&initial.rule.includeEnd==false)end.minusDays(1)else end
        require(previousLast==null||first>previousLast){"计费区间重复，请核对起止端点 / 計算期間が重複しています。開始・終了の扱いを確認してください"}
        previousLast=last
        val known=confirmed.firstOrNull{it.number==index+1}
        val date=known?.date?:LocalDate.parse(terms.paymentDates[index])
        require(date>=end&&(previousPayment==null||date>previousPayment)){"付款日须在计费截止后并按期递增 / 支払日は計算終了日以降かつ昇順で入力してください"}
        previousPayment=date
        val available=position(date,true)
        require(available>=0){"本金变动超过原欠款 / 元金の減少額が残債を超えています"}
        if(available==0L)break
        val charge=when(val result=versionedInterestCharge(position(start,false),start,end,rates,(changes+planned).filter{it.date in start..end},terms.firstPeriodNumber+index==1)) {
            is Calculation.Ready->result.value
            is Calculation.Incomplete->return result
            is Calculation.Unsupported->return result
            is Calculation.CannotSettle->return result
        }
        val paymentRule=terms.paymentRule(date.toString(),payRule.revolving!!)
        if(paymentRule.issues().isNotEmpty())return Calculation.Incomplete(paymentRule.issues())
        val period=when(val result=revolvingPayment(index+1,available,date,paymentRule,charge,if(paymentRule.tierBalanceDate==TierBalanceDate.BILL_CLOSING)position(end,true)else available)) {
            is Calculation.Ready->result.value
            is Calculation.Incomplete->return result
            is Calculation.Unsupported->return result
            is Calculation.CannotSettle->return result
        }
        val applied=if(known!=null)known.principalMinor?:if(index==terms.paymentDates.lastIndex)period.principalMinor else return Calculation.Incomplete(listOf("plan.confirmedPrincipal"))else period.principalMinor
        require(applied in 0..available){"已确认本金超过剩余欠款 / 確定元金が残債を超えています"}
        planned+=PrincipalChange(date,-applied)
        val remaining=available-applied
        val segments=charge.segments.map{InterestSegmentSnapshot(it.start.toString(),it.endInclusive.toString(),it.balanceMinor,it.days,it.annualRate!!,it.yearDays!!,it.ruleId)}
        output+=PredictedSchedulePeriod(period.copy(remainingPrincipalMinor=remaining),payRule.id,FinancialCalculationBasis(
            (listOf(payRule.id,initial.ruleId)+rates.filter{it.effectiveDate>start&&it.effectiveDate<=end}.map{it.ruleId}+segments.mapNotNull{it.ruleId}).filterNotNull().distinct(),remaining,period.basis,segments,window.start,window.end,terms.agreementDate,paymentSetting=if(known==null)terms.paymentSetting(date.toString())else null))
    }
    return Calculation.Ready(output)
}
