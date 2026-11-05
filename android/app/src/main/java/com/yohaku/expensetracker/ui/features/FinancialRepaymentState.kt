package com.yohaku.expensetracker.ui.features

import com.yohaku.expensetracker.data.repository.FinancialStatementRow
import kotlinx.serialization.Serializable
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money

/** Suggestions describe saved obligations, never platform receipt or payment-side fees. */
internal data class RepaymentSuggestion(val paid:Long?,val principal:Long?,val zeroCombinedFee:Boolean) {
    fun differsFromPaid(value:String,currency:Currency):Boolean = paid!=null &&
        value.trim().toBigDecimalOrNull()?.let{it.compareTo(Money(currency,paid).decimal)!=0}==true
}
internal fun repaymentSuggestion(row:FinancialStatementRow,stale:Boolean):RepaymentSuggestion {
    if(stale&&!row.bill.confirmed||row.bill.hasUnknownPredictedFee())return RepaymentSuggestion(null,null,false)
    val paid=row.cashRequiredMinor?.takeIf{it>0}
    val principal=row.outstanding.principalMinor?.let{p->row.pendingPrincipalMinor?.let{p-it}}?.takeIf{it>=0}
    val fee=row.outstanding.feeMinor?.let{f->row.pendingFeeMinor?.let{f-it}}
    return RepaymentSuggestion(paid,principal,fee==0L)
}

@Serializable internal data class AllocationDraft(val billId:String?=null,val contractId:String?=null,val principal:String="",val interest:String="",val fee:String="")
@Serializable internal data class RepaymentDraft(
    val source:String?=null,val paid:String="",val date:String,val received:String="",val principal:String="",val interest:String="",val fee:String="",val feeBase:String="",
    val confirmed:Boolean=false,val clearingDate:String,val type:String="NORMAL",val sourceFee:String="",val sourceFeeBase:String="",
    val rows:List<AllocationDraft> = emptyList(),val linkedTransfer:String?=null,val existing:String?=null,val note:String="",
    // null denotes a legacy draft: retain independently entered allocations.
    val singleTarget:Boolean?=null
) {
    fun withPaid(value:String,suggestion:RepaymentSuggestion?,currency:Currency):RepaymentDraft {
        if(value==paid||singleTarget!=true||suggestion?.paid==null||value.trim().toBigDecimalOrNull()==null)return copy(paid=value)
        return if(suggestion.differsFromPaid(value,currency))copy(paid=value,principal="").withSingleAllocation()
        else copy(paid=value,principal=suggestion.principal?.let{Money(currency,it).decimal.toPlainString()}.orEmpty(),
            interest=if(suggestion.zeroCombinedFee)"0"else interest,fee=if(suggestion.zeroCombinedFee)"0"else fee).withSingleAllocation()
    }
    fun canShareComposition():Boolean=rows.isEmpty()||rows.singleOrNull()?.let{it.principal==principal&&it.interest==interest&&it.fee==fee}==true
    fun withSingleAllocation():RepaymentDraft=if(singleTarget==true)copy(rows=rows.map{it.copy(principal=principal,interest=interest,fee=fee)})else this
}

/** Reuses per-bill evidence; unknown amounts and nonzero combined fees stay unfilled. */
internal fun repaymentDateDraft(base:RepaymentDraft,rows:List<FinancialStatementRow>,stale:Set<String>,currency:Currency,sameCurrency:Boolean):RepaymentDraft {
    val suggestions=rows.map{repaymentSuggestion(it,it.plan.id in stale)}
    fun total(values:List<Long?>)=if(values.isNotEmpty()&&values.all{it!=null})values.sumOf{requireNotNull(it)}else null
    fun amount(value:Long?)=value?.let{Money(currency,it).decimal.toPlainString()}.orEmpty()
    val zero=suggestions.isNotEmpty()&&suggestions.all{it.zeroCombinedFee}
    return base.copy(paid=amount(total(suggestions.map{it.paid}).takeIf{sameCurrency}),
        principal=amount(total(suggestions.map{it.principal})),interest=if(zero)"0"else"",fee=if(zero)"0"else"",
        rows=rows.zip(suggestions).map{(row,s)->AllocationDraft(billId=row.bill.id,principal=amount(s.principal),interest=if(s.zeroCombinedFee)"0"else"",fee=if(s.zeroCombinedFee)"0"else"")},singleTarget=rows.size==1)
}
