package com.yohaku.expensetracker.domain.currency

import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.Serializable
import java.math.RoundingMode

const val REFERENCE_RATE_API="https://api.frankfurter.dev/v2/providers/ecb"
@Serializable data class ReferenceRateSnapshot(val base:Currency,val quote:Currency,val rate:String,val date:String,val fetchedAt:String,
    val api:String=REFERENCE_RATE_API,val provider:String="ECB") {
    fun convert(amount:Money):Money {
        require(amount.currency==base)
        return Money(quote,amount.decimal.multiply(rate.toBigDecimal()).setScale(quote.scale,RoundingMode.HALF_UP).movePointRight(quote.scale).longValueExact())
    }
    fun sourceLabel()="Frankfurter v2 / $provider · $fetchedAt"
}
@Serializable data class ReferenceRateAttempt(val base:Currency,val quote:Currency,val requestedDate:String,val attemptedOn:String,val failure:String?=null)
@Serializable data class ReferenceRateCache(val rates:List<ReferenceRateSnapshot> = emptyList(),val attempts:List<ReferenceRateAttempt> = emptyList())
data class ReferenceRateResult(val requestedDate:String,val rate:ReferenceRateSnapshot?,val failure:String?=null,val automatic:Boolean=true) {
    val previousDate get()=rate?.date?.let{it<requestedDate}==true
}
