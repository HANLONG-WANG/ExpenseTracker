package com.yohaku.expensetracker.domain.finance

import java.time.LocalDate
import kotlinx.serialization.Serializable

/** Positive principal reductions. A bill association prevents forecasting its paid part again. */
@Serializable data class SchedulePrincipalFact(
    val eventId:String,val date:String,val principalMinor:Long,val periodNumber:Int?=null,val kind:String="REPAYMENT"
)

@Serializable data class SchedulePrincipalAddition(val eventId:String,val date:String,val principalMinor:Long)

/**
 * Actual clearing dates drive interest. Confirmed/paid/overdue bills retain their gross obligations;
 * only their unpaid principal at a future payment date becomes an additional forecast movement.
 * Unpaid past bills remain outstanding rather than becoming imaginary historical repayments.
 * A forecast payment is computed after its current charge and affects subsequent intervals;
 * actual clearings enter the interest balance at the start of their recorded clearing date.
 */
fun reconciledSchedule(
    principalMinor:Long,mode:FinanceMode,terms:FinancialScheduleTerms,versions:List<EffectiveFinancialRule>,
    retained:List<ConfirmedSchedulePeriod>,facts:List<SchedulePrincipalFact>,asOf:LocalDate,additions:List<SchedulePrincipalAddition> = emptyList()
):Calculation<List<PredictedSchedulePeriod>> {
    require(facts.all{it.principalMinor>=0}&&additions.all{it.principalMinor>=0})
    if(additions.isNotEmpty()&&mode !in listOf(FinanceMode.REVOLVING,FinanceMode.CREDIT_LINE))return Calculation.Unsupported("pool.fixedLoan")
    if(additions.any{it.date<terms.agreementDate})return Calculation.Incomplete(listOf("pool.sourceBeforeStart"))
    if(facts.any{LocalDate.parse(it.date)<LocalDate.parse(terms.agreementDate)})return Calculation.Incomplete(listOf("plan.clearingBeforeSource"))
    if(retained.any{it.date<LocalDate.parse(terms.agreementDate)})return Calculation.Incomplete(listOf("plan.billBeforeSource"))
    if(retained.any{bill->bill.principalMinor?.let{principal->facts.filter{it.periodNumber==bill.number}.fold(0L){sum,p->Math.addExact(sum,p.principalMinor)}>principal}==true})return Calculation.Incomplete(listOf("plan.clearedExceedsBill"))
    require(terms.additionalFees.all{it.number in 1..terms.paymentDates.size&&it.minor>=0})
    val actual=facts.map{PrincipalChange(LocalDate.parse(it.date),-it.principalMinor)}+additions.map{PrincipalChange(LocalDate.parse(it.date),it.principalMinor)}
    var actualBalance=principalMinor
    actual.groupBy{it.date}.toSortedMap().values.forEach{day->
        actualBalance=day.fold(actualBalance){sum,p->Math.addExact(sum,p.deltaMinor)}
        if(actualBalance<0)return Calculation.Incomplete(listOf("plan.clearedExceedsSource"))
    }
    val totalCleared=facts.fold(0L){sum,p->Math.addExact(sum,p.principalMinor)}
    if(totalCleared>additions.fold(principalMinor){sum,p->Math.addExact(sum,p.principalMinor)})return Calculation.Incomplete(listOf("plan.clearedExceedsSource"))
    val revolving=mode in listOf(FinanceMode.REVOLVING,FinanceMode.CREDIT_LINE)
    val fixed=if(revolving)null else when(val result=predictedSchedule(principalMinor,mode,terms,versions)) {
        is Calculation.Ready->result.value
        is Calculation.Incomplete->return result
        is Calculation.Unsupported->return result
        is Calculation.CannotSettle->return result
    }
    val timeline=versions.filter{it.rule.mode==mode}.groupBy{it.rule.effectiveFrom}.values.map{it.maxBy{v->v.version}}.sortedBy{it.rule.effectiveFrom}
    val ruleDate=if(revolving)terms.accrualWindows.firstOrNull()?.start?:terms.agreementDate else terms.agreementDate
    val contract=timeline.lastOrNull{it.rule.effectiveFrom<=ruleDate}?.rule?:return Calculation.Incomplete(listOf("interest.ruleVersion"))
    if(revolving)require(terms.accrualWindows.size==terms.paymentDates.size)
    val activeRates=if(revolving)timeline.filter{it.rule.effectiveFrom<=terms.accrualWindows.last().end&&
        (it.rule.effectiveFrom>=ruleDate||it.rule.id==contract.id)}else emptyList()
    for(version in activeRates)when(val status=version.rule.readiness()) {
        is RuleReadiness.Incomplete->return Calculation.Incomplete(status.fields)
        is RuleReadiness.Unsupported->return Calculation.Unsupported(status.reason)
        RuleReadiness.Complete->Unit
    }
    val rates=activeRates.map{InterestRateVersion(LocalDate.parse(it.rule.effectiveFrom),it.rule.revolving!!.interest,it.rule.id)}
    val planned=mutableListOf<PrincipalChange>()
    fun position(date:LocalDate,inclusive:Boolean)=(actual+planned).filter{if(inclusive)it.date<=date else it.date<date}.fold(principalMinor){sum,p->Math.addExact(sum,p.deltaMinor)}
    val output=mutableListOf<PredictedSchedulePeriod>()
    val overdue=retained.filter{it.date<asOf}
    if(overdue.any{it.principalMinor==null}&&terms.paymentDates.any{LocalDate.parse(it)>=asOf})return Calculation.Incomplete(listOf("plan.overduePrincipal"))
    var overdueReserved=overdue.fold(0L){sum,bill->
        val paid=facts.filter{it.periodNumber==bill.number}.fold(0L){n,p->Math.addExact(n,p.principalMinor)}
        Math.addExact(sum,Math.subtractExact(bill.principalMinor?:paid,paid))
    }
    var previousLast:LocalDate?=null
    var previousPayment:LocalDate?=null
    for(index in terms.paymentDates.indices) {
        val number=index+1
        val known=retained.firstOrNull{it.number==number}
        val date=known?.date?:LocalDate.parse(terms.paymentDates[index])
        if(previousPayment!=null&&date<=previousPayment)return Calculation.Incomplete(listOf("plan.billDateOrder"))
        previousPayment=date
        val available=position(date,true)
        if(available<0)return Calculation.Incomplete(listOf("plan.clearedExceedsSource"))
        // A recorded later clearing is not yet applied to interest, but cannot be promised again.
        var laterBalance=0L;var laterActual=0L
        actual.filter{it.date>date}.groupBy{it.date}.toSortedMap().values.forEach{day->
            laterBalance=day.fold(laterBalance){sum,p->Math.addExact(sum,p.deltaMinor)}
            laterActual=maxOf(laterActual,Math.negateExact(laterBalance))
        }
        val unpromised=Math.subtractExact(available,laterActual)
        if(overdueReserved>unpromised)return Calculation.Incomplete(listOf("plan.retainedBillExceedsPrincipal"))
        val freePrincipal=Math.subtractExact(unpromised,overdueReserved)
        require(freePrincipal>=0)
        val paid=facts.filter{it.periodNumber==number}.fold(0L){sum,p->Math.addExact(sum,p.principalMinor)}
        val rule=if(revolving)timeline.lastOrNull{it.rule.effectiveFrom<=terms.accrualWindows[index].end}?.rule?:return Calculation.Incomplete(listOf("interest.ruleVersion"))else contract
        when(val status=rule.readiness()) {
            is RuleReadiness.Incomplete->return Calculation.Incomplete(status.fields)
            is RuleReadiness.Unsupported->return Calculation.Unsupported(status.reason)
            RuleReadiness.Complete->Unit
        }
        val start=if(revolving)LocalDate.parse(terms.accrualWindows[index].start)else if(index==0)LocalDate.parse(terms.agreementDate)else previousPaymentDate(terms,retained,index)
        val end=if(revolving)LocalDate.parse(terms.accrualWindows[index].end)else date
        if(revolving) {
            if(date<end)return Calculation.Incomplete(listOf("plan.paymentBeforeAccrualEnd"))
            val initial=rates.lastOrNull{it.effectiveDate<=start}?:return Calculation.Incomplete(listOf("interest.ruleVersion"))
            val first=if(initial.rule.frequency==ChargeFrequency.DAILY&&initial.rule.includeStart==false)start.plusDays(1)else start
            val last=if(initial.rule.frequency==ChargeFrequency.DAILY&&initial.rule.includeEnd==false)end.minusDays(1)else end
            if(previousLast!=null&&first<=previousLast)return Calculation.Incomplete(listOf("plan.overlappingIntervals"))
            previousLast=last
        }
        val extraFee=terms.additionalFees.filter{it.number==number}.fold(0L){sum,f->Math.addExact(sum,f.minor)}
        val rawCharge=if(revolving||mode==FinanceMode.FIXED_LOAN&&rule.loanMethod!=LoanMethod.MANUAL) {
            val interest=if(revolving)rates.lastOrNull{it.effectiveDate<=start}?.rule?:return Calculation.Incomplete(listOf("interest.ruleVersion"))else rule.loanInterest!!
            val openingAfterStart=interest.frequency==ChargeFrequency.MONTHLY||interest.balanceBasis==BalanceBasis.OPENING_BALANCE
            val changes=(actual+planned).filter{it.date<=end&&if(openingAfterStart)it.date>start else it.date>=start}
            when(val result=if(revolving)versionedInterestCharge(position(start,openingAfterStart),start,end,rates,changes,terms.firstPeriodNumber+index==1)
                else interestCharge(position(start,openingAfterStart),start,end,rule.loanInterest!!,changes,terms.firstPeriodNumber+index==1)) {
                is Calculation.Ready->result.value
                is Calculation.Incomplete->return result
                is Calculation.Unsupported->return result
                is Calculation.CannotSettle->return result
            }
        }else null
        val charge=rawCharge?.let{if(revolving)it.copy(feeMinor=Math.addExact(it.feeMinor,extraFee))else it}
        val paymentRule=if(revolving)terms.paymentRule(date.toString(),rule.revolving!!)else null
        if(paymentRule!=null&&paymentRule.issues().isNotEmpty())return Calculation.Incomplete(paymentRule.issues())
        val candidate=if(revolving&&known?.principalMinor!=null)PaymentPeriod(number,date,known.principalMinor,known.feeMinor?:charge!!.feeMinor,available,listOf("platform.retained"),charge)
        else if(revolving)when(val result=revolvingPayment(number,freePrincipal,date,paymentRule!!,charge!!,
            if(paymentRule.tierBalanceDate==TierBalanceDate.BILL_CLOSING)position(end,true)else available)) {
                is Calculation.Ready->result.value
                is Calculation.Incomplete->return result
                is Calculation.Unsupported->return result
                is Calculation.CannotSettle->return result
            }
        else {
            val base=fixed!!.first{it.period.number==number}.period
            val fee=Math.addExact(charge?.feeMinor?:base.feeMinor,extraFee)
            val nominalAvailable=Math.addExact(freePrincipal,paid)
            val principal=if(mode==FinanceMode.FIXED_LOAN&&rule.loanMethod==LoanMethod.ANNUITY) {
                if(base.totalMinor<=fee&&freePrincipal>0)return Calculation.CannotSettle(freePrincipal,fee,base.totalMinor)
                if(index==terms.paymentDates.lastIndex)nominalAvailable else minOf(nominalAvailable,(base.totalMinor-fee).coerceAtLeast(0))
            }else if(mode==FinanceMode.FIXED_LOAN&&rule.loanMethod==LoanMethod.INTEREST_ONLY&&index==terms.paymentDates.lastIndex)nominalAvailable
            else minOf(nominalAvailable,base.principalMinor)
            base.copy(date=date,principalMinor=principal,feeMinor=fee,interestCharge=charge)
        }
        val nominalPrincipal=known?.principalMinor?:if(known!=null&&index!=terms.paymentDates.lastIndex&&date>=asOf)return Calculation.Incomplete(listOf("plan.confirmedPrincipal"))else candidate.principalMinor
        if(nominalPrincipal<paid)return Calculation.Incomplete(listOf("plan.clearedExceedsBill"))
        val unpaid=nominalPrincipal-paid
        if(date<asOf&&known==null)overdueReserved=Math.addExact(overdueReserved,unpaid)
        val forecast=if(date>=asOf)unpaid else 0L
        if(forecast>freePrincipal)return Calculation.Incomplete(listOf("plan.retainedBillExceedsPrincipal"))
        if(forecast>0)planned+=PrincipalChange(date,-forecast)
        val remaining=position(date,true)
        if(known==null&&candidate.totalMinor==0L&&remaining==0L)continue
        val segments=charge?.segments.orEmpty().map{InterestSegmentSnapshot(it.start.toString(),it.endInclusive.toString(),it.balanceMinor,it.days,it.annualRate!!,it.yearDays!!,it.ruleId?:rule.id)}
        val rateIds=if(revolving)listOfNotNull(rates.lastOrNull{it.effectiveDate<=start}?.ruleId)+rates.filter{it.effectiveDate>start&&it.effectiveDate<=end}.mapNotNull{it.ruleId}else emptyList()
        val basis=FinancialCalculationBasis((listOf(rule.id)+rateIds+segments.mapNotNull{it.ruleId}).distinct(),remaining,
            candidate.basis+listOf("fee.additional.$extraFee","plan.actualClearingDates","plan.futureUnpaidPrincipalOnly"),segments,
            if(charge!=null)start.toString()else null,if(charge!=null)end.toString()else null,terms.agreementDate,
            if(mode in listOf(FinanceMode.SINGLE,FinanceMode.INSTALLMENT))billingDates(LocalDate.parse(terms.agreementDate),contract.billing!!).closingDate.toString()else null,
            asOf.toString(),forecast,facts,additions,paymentSetting=if(revolving&&known==null)terms.paymentSetting(date.toString())else null,additionalFeeMinor=extraFee)
        output+=PredictedSchedulePeriod(candidate.copy(principalMinor=nominalPrincipal,feeMinor=known?.feeMinor?:candidate.feeMinor,remainingPrincipalMinor=remaining),rule.id,basis)
    }
    return Calculation.Ready(output)
}

private fun previousPaymentDate(terms:FinancialScheduleTerms,retained:List<ConfirmedSchedulePeriod>,index:Int)=
    retained.firstOrNull{it.number==index}?.date?:LocalDate.parse(terms.paymentDates[index-1])
