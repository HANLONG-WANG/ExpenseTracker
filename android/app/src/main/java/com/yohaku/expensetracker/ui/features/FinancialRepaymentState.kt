package com.yohaku.expensetracker.ui.features

import com.yohaku.expensetracker.data.repository.FinancialStatementRow
import com.yohaku.expensetracker.domain.finance.FinancialCalculationBasis
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money

/** Suggestions describe saved obligations, never platform receipt or payment-side fees. Interest and fee are null when the bill's charge is unknown. */
internal data class RepaymentSuggestion(val paid:Long?,val principal:Long?,val interest:Long?,val fee:Long?) {
    val split:Boolean get()=interest!=null&&fee!=null
    fun differsFromPaid(value:String,currency:Currency):Boolean = paid!=null &&
        value.trim().toBigDecimalOrNull()?.let{it.compareTo(Money(currency,paid).decimal)!=0}==true
}
internal fun repaymentSuggestion(row:FinancialStatementRow,stale:Boolean):RepaymentSuggestion {
    if(stale&&!row.bill.confirmed||row.bill.hasUnknownPredictedFee())return RepaymentSuggestion(null,null,null,null)
    val paid=row.cashRequiredMinor?.takeIf{it>0}
    val principal=row.outstanding.principalMinor?.let{p->row.pendingPrincipalMinor?.let{p-it}}?.takeIf{it>=0}
    val charges=row.outstanding.feeMinor?.let{f->row.pendingFeeMinor?.let{f-it}}?.takeIf{it>=0}
    // The charge is interest unless a calculation lists an additional fee; interest and fee are summed everywhere downstream.
    val basis=row.bill.basisJson?.let{Json.decodeFromString<FinancialCalculationBasis>(it)}
    val fee=charges?.let{c->if(basis==null)0L else basis.additionalFeeMinor.coerceAtMost(c).takeUnless{basis.additionalFeeUnknown&&c>0}}
    return RepaymentSuggestion(paid,principal,fee?.let{charges!!-it},fee)
}
private fun Long.plain(currency:Currency)=Money(currency,this).decimal.toPlainString()

@Serializable internal data class AllocationDraft(val billId:String?=null,val contractId:String?=null,val principal:String="",val interest:String="",val fee:String="")
@Serializable internal data class RepaymentDraft(
    val source:String?=null,val paid:String="",val date:String,val received:String="",val principal:String="",val interest:String="",val fee:String="",val feeBase:String="",
    val confirmed:Boolean=false,val clearingDate:String,val type:String="NORMAL",val sourceFee:String="",val sourceFeeBase:String="",
    val rows:List<AllocationDraft> = emptyList(),val linkedTransfer:String?=null,val existing:String?=null,val note:String="",
    // Typed interest is kept; otherwise it follows received − principal − fee.
    val interestManual:Boolean=false,
    // null denotes a legacy draft: retain independently entered allocations.
    val singleTarget:Boolean?=null
) {
    fun withPaid(value:String,suggestion:RepaymentSuggestion?,currency:Currency):RepaymentDraft {
        if(value==paid||singleTarget!=true||suggestion?.paid==null||value.trim().toBigDecimalOrNull()==null)return copy(paid=value)
        return if(suggestion.differsFromPaid(value,currency))copy(paid=value,principal="").withSingleAllocation()
        else copy(paid=value,principal=suggestion.principal?.plain(currency).orEmpty(),
            interest=suggestion.interest?.plain(currency)?:interest,fee=suggestion.fee?.plain(currency)?:fee).withSingleAllocation()
    }
    /** [sameCurrency]: paid minus payment-side fee is what the platform received. Unknown inputs leave interest as is. */
    fun withDerivedInterest(currency:Currency,sameCurrency:Boolean):RepaymentDraft {
        if(interestManual)return this
        fun minor(v:String)=runCatching{Money.parse(v,currency).minor}.getOrNull()
        val got=if(received.isNotBlank())minor(received)else if(sameCurrency)minor(paid)?.let{p->(if(sourceFee.isBlank())0L else minor(sourceFee))?.let{p-it}}else null
        val value=got?.let{r->minor(principal)?.let{p->minor(fee)?.let{f->r-p-f}}}?:return this
        return copy(interest=if(value>=0)value.plain(currency)else"")
    }
    fun canShareComposition():Boolean=rows.isEmpty()||rows.singleOrNull()?.let{it.principal==principal&&it.interest==interest&&it.fee==fee}==true
    fun withSingleAllocation():RepaymentDraft=if(singleTarget==true)copy(rows=rows.map{it.copy(principal=principal,interest=interest,fee=fee)})else this
}

/** Reuses per-bill evidence; unknown amounts stay unfilled. */
internal fun repaymentDateDraft(base:RepaymentDraft,rows:List<FinancialStatementRow>,stale:Set<String>,currency:Currency,sameCurrency:Boolean):RepaymentDraft {
    val suggestions=rows.map{repaymentSuggestion(it,it.plan.id in stale)}
    fun total(values:List<Long?>)=if(values.isNotEmpty()&&values.all{it!=null})values.sumOf{requireNotNull(it)}else null
    fun amount(value:Long?)=value?.let{Money(currency,it).decimal.toPlainString()}.orEmpty()
    return base.copy(paid=amount(total(suggestions.map{it.paid}).takeIf{sameCurrency}),
        principal=amount(total(suggestions.map{it.principal})),interest=amount(total(suggestions.map{it.interest})),fee=amount(total(suggestions.map{it.fee})),
        rows=rows.zip(suggestions).map{(row,s)->AllocationDraft(billId=row.bill.id,principal=amount(s.principal),interest=amount(s.interest),fee=amount(s.fee))},singleTarget=rows.size==1)
}
