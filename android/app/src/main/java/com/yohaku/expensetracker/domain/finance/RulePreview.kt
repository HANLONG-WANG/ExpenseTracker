package com.yohaku.expensetracker.domain.finance

import java.time.LocalDate

/** User-supplied trial amounts; this does not create a contract, bill, or payment. */
data class RulePreviewInput(val principalMinor:Long,val periods:Int,val start:LocalDate,val end:LocalDate,val paymentDate:LocalDate)
fun calculateRulePreview(rule:FinancialRuleVersion,input:RulePreviewInput):Calculation<List<PaymentPeriod>> {
    when(val readiness=rule.readiness()) {
        is RuleReadiness.Incomplete->return Calculation.Incomplete(readiness.fields)
        is RuleReadiness.Unsupported->return Calculation.Unsupported(readiness.reason)
        RuleReadiness.Complete->Unit
    }
    require(input.principalMinor>0&&input.periods>0)
    return when(rule.mode) {
        FinanceMode.SINGLE->{val date=billingDates(input.start,rule.billing!!).paymentDate
            Calculation.Ready(listOf(PaymentPeriod(1,date,input.principalMinor,0,0,listOf("billing.single"))))}
        FinanceMode.INSTALLMENT->installmentPlan(input.principalMinor,input.periods,List(input.periods){input.paymentDate.plusMonths(it.toLong())},rule.installment!!)
        FinanceMode.REVOLVING,FinanceMode.CREDIT_LINE->when(val period=revolvingPeriod(1,input.principalMinor,input.start,input.end,input.paymentDate,rule.revolving!!)) {
            is Calculation.Ready->Calculation.Ready(listOf(period.value))
            is Calculation.Incomplete->period
            is Calculation.Unsupported->period
            is Calculation.CannotSettle->period
        }
        FinanceMode.FIXED_LOAN->if(rule.loanMethod==LoanMethod.MANUAL)Calculation.Unsupported("loan.manual")else
            fixedLoanPlan(input.principalMinor,input.start,List(input.periods){input.paymentDate.plusMonths(it.toLong())},rule.loanMethod!!,rule.loanInterest,rule.loanPrincipalRemainder)
    }
}
