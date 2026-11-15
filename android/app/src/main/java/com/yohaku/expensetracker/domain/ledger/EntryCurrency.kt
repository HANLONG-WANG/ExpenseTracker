package com.yohaku.expensetracker.domain.ledger

import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.EntryDraft

/** Currency changes affect conversion inputs, never confirmation of actual settlement. */
fun changeEntryOriginalCurrency(d:EntryDraft,accountCurrency:Currency,currency:Currency):EntryDraft {
    val original=d.originalCurrency?:accountCurrency
    if(currency==original)return d
    val foreign=original!=accountCurrency
    val accountAmount=if(foreign)d.accountAmount else d.amount
    return d.copy(
        originalCurrency=currency,
        amount=if(currency==accountCurrency)accountAmount else if(foreign)d.amount else "",
        accountAmount=if(currency==accountCurrency)"" else accountAmount,
        manualRate="",rateSource="manual",referenceAccountLinked=false,referenceBaseLinked=false,
        referenceExpectedAccountMinor=null,referenceExpectedBaseMinor=null,referenceRates=emptyList()
    )
}
