package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** Pending purchases create cash forecasts only, never ledger entries or obligations. */
internal fun BookSnapshot.cardRecurrenceForecast(o:OccurrenceEntity,finance:FinancialGraph?,asOf:LocalDate,referenceRates:List<ReferenceRateSnapshot>):List<CashTimelineItem> {
    val card=accounts.first{it.id==o.accountId}
    val recurrence=recurrences.first{it.id==o.recurrenceId}
    val rules=finance?.rules.orEmpty()
    fun item(number:Int,date:String,amount:Long?,payer:String?,delta:Long?,issue:String?,rate:ReferenceRateSnapshot?=null)=
        CashTimelineItem("cardOccurrence:${o.id}:$number","CARD_RECURRENCE",card.id,date,"${card.name} · ${o.name}",payer,card.currency,amount,false,delta,true,issue,referenceRate=rate)
    fun failed(issue:String,date:String=o.scheduledDate,payer:String?=null)=listOf(item(1,date,null,payer,null,issue).copy(currency=o.currency,amountMinor=o.amountMinor))
    if(!card.active)return failed("CARD_ACCOUNT_INACTIVE")
    val choice=recurrence.cardPaymentChoice?.let{CardPaymentChoice.valueOf(it)}?:return failed("CARD_PAYMENT_CHOICE_REQUIRED")
    val automatic=if(choice==CardPaymentChoice.ACCOUNT_RULE)rules.cardRule(card.id,o.scheduledDate,"REVOLVING")?.let{Json.decodeFromString<FinancialRuleVersion>(it.json).revolving?.automatic}else null
    val mode=resolvedCardPaymentMode(choice,automatic)?:return failed("CARD_AUTOMATIC_RULE_INCOMPLETE")
    // These purchases enter the revolving balance; there is no separate bank debit.
    if(mode==FinanceMode.REVOLVING.name)return emptyList()
    val event=EventEntity(book.id,o.id,EntryKind.EXPENSE.name,card.id,o.categoryId,o.amountMinor?:0,o.currency,o.baseMinor?:0,o.scheduledDate,o.name,"",
        cardPaymentChoice=choice.name,cardPaymentMode=mode,cardPaymentPeriods=recurrence.cardPaymentPeriods)
    val schedule=cardEntrySchedule(event,accounts,rules)
    schedule.issue?.let{return failed(it)}
    val terms=requireNotNull(schedule.terms)
    val payer=accounts.first{it.id==schedule.paymentAccountId}
    fun rate(from:String,to:String)=referenceRates.filter{it.base.name==from&&it.quote.name==to&&it.date<=asOf.toString()}.maxByOrNull{it.date}
    val amount=o.amountMinor?:return failed("UNKNOWN_AMOUNT",terms.paymentDates.first(),payer.id)
    val sourceRate=if(o.currency!=card.currency)rate(o.currency,card.currency)else null
    val principal=if(o.currency==card.currency)amount else sourceRate?.convert(Money(Currency.valueOf(o.currency),amount))?.minor
        ?:return failed("UNKNOWN_CONVERSION",terms.paymentDates.first(),payer.id)
    val versions=rules.filter{it.accountId==card.id}.map{EffectiveFinancialRule(it.version,Json.decodeFromString<FinancialRuleVersion>(it.json))}
    val result=reconciledSchedule(principal,FinanceMode.valueOf(mode),terms,versions,emptyList(),emptyList(),asOf)
    if(result !is Calculation.Ready)return failed(if(mode=="INSTALLMENT")"CARD_INSTALLMENT_RULE_INCOMPLETE"else if(choice==CardPaymentChoice.BONUS)"CARD_BONUS_RULE_INCOMPLETE"else "CARD_SINGLE_RULE_INCOMPLETE")
    val debitRate=if(payer.currency!=card.currency)rate(card.currency,payer.currency)else null
    return result.value.map{p->
        val amountMinor=p.period.totalMinor
        val debit=if(payer.currency==card.currency)amountMinor else debitRate?.convert(Money(Currency.valueOf(card.currency),amountMinor))?.minor
        val issue=if(debit==null)"UNKNOWN_CONVERSION"else if(debitRate!=null||sourceRate!=null)"REFERENCE_CONVERSION"else null
        item(p.period.number,p.period.date.toString(),amountMinor,payer.id,debit?.let{Math.negateExact(it)},issue,debitRate?:sourceRate)
    }
}
