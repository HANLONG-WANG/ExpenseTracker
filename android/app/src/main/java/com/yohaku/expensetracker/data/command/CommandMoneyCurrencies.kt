package com.yohaku.expensetracker.data.command

import com.yohaku.expensetracker.data.repository.BookSnapshot
import kotlinx.serialization.json.*

/** Freeze mixed-currency units with confirmation facts, including nested prediction/entry details.
 * These are comparison/display facts, never entities or command input. */
internal class CommandMoneyCurrencies(private val s:BookSnapshot) {
    private val accounts=s.accounts.associate{it.id to it.currency}
    private val repayments=s.repayments.associateBy{it.eventId}
    fun annotate(value:JsonElement):JsonElement=when(value) {
        is JsonObject->{
            val fields=value.mapValues{(_,v)->annotate(v)}.toMutableMap()
            fun text(key:String)=(value[key] as? JsonPrimitive)?.contentOrNull
            fun accountCurrency(field:String)=text(field)?.let(accounts::get)?.let(::JsonPrimitive)?:JsonNull
            if("fundingAccountId" in value&&"withheldFeeMinor" in value) {
                fields["fundingCurrency"]=accountCurrency("fundingAccountId")
                fields["separateFeeCurrency"]=accountCurrency("separateFeeAccountId")
            }
            if("debtAccountId" in value&&"sourceFeeMinor" in value) {
                fields["currency"]=accountCurrency("debtAccountId")
                fields["sourceCurrency"]=text("eventId")?.let{s.eventById[it]?.currency}?.let(::JsonPrimitive)?:JsonNull
            }else if("eventId" in value&&"principalMinor" in value&&"interestMinor" in value&&"feeMinor" in value) {
                text("eventId")?.let(repayments::get)?.let{repayment->
                    fields["currency"]=JsonPrimitive(accounts.getValue(repayment.debtAccountId))
                }
            }
            JsonObject(fields)
        }
        is JsonArray->JsonArray(value.map(::annotate))
        else->value
    }
}
