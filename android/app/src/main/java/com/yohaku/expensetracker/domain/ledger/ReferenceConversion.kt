package com.yohaku.expensetracker.domain.ledger

import com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot
import com.yohaku.expensetracker.domain.model.*

fun EntryDraft.withReference(rate:ReferenceRateSnapshot)=copy(rateSource=rate.sourceLabel(),rateDate=rate.date,
    referenceRates=referenceRates.filterNot{it.base==rate.base&&it.quote==rate.quote}+rate)
fun applyReferenceTransfer(d:EntryDraft,from:Currency,to:Currency,rate:ReferenceRateSnapshot,direction:String):EntryDraft {
    require(rate.base==from&&rate.quote==to)
    val value=estimateTransfer(d.withReference(rate).copy(manualRate=rate.rate),from,to,direction)
    return value.copy(referenceExpectedFromMinor=Money.parse(value.amount,from).minor,referenceExpectedToMinor=Money.parse(value.toAmount,to).minor)
}
fun applyReferenceAccountAmount(d:EntryDraft,accountCurrency:Currency,rate:ReferenceRateSnapshot):EntryDraft {
    require(!d.settlementConfirmed){"已确认金额不能被参考估算覆盖 / 確認済みの金額は参考見積もりで上書きできません"}
    require(rate.base==(d.originalCurrency?:accountCurrency)&&rate.quote==accountCurrency)
    val amount=rate.convert(Money.parse(d.amount,rate.base))
    return d.withReference(rate).copy(accountAmount=amount.decimal.toPlainString(),manualRate=rate.rate,referenceExpectedAccountMinor=amount.minor,referenceAccountLinked=true)
}
fun applyReferenceBaseAmount(d:EntryDraft,accountCurrency:Currency,baseCurrency:Currency,rate:ReferenceRateSnapshot):EntryDraft {
    require(rate.base==accountCurrency&&rate.quote==baseCurrency)
    val text=if((d.originalCurrency?:accountCurrency)==accountCurrency)d.amount else d.accountAmount
    val amount=rate.convert(Money.parse(text,accountCurrency))
    return d.withReference(rate).copy(baseAmount=amount.decimal.toPlainString(),referenceExpectedBaseMinor=amount.minor,referenceBaseLinked=true)
}
