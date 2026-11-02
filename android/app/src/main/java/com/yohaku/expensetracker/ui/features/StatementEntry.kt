package com.yohaku.expensetracker.ui.features

import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*

internal fun AppViewModel.startStatementSupplement(s:BookSnapshot,seed:StatementSupplementSeed) {
    val draft=s.statementSupplementDraft(seed)
    entryReturnRoute.value=route.value
    entry.value=draft
    navigate(if(draft.kind in listOf(EntryKind.EXPENSE,EntryKind.INCOME))"category-picker"else "entry")
    persistDraft(s.book.id);markEntryBaseline()
}
internal fun AppViewModel.startStatementActual(s:BookSnapshot,seed:StatementActualSeed) {
    val event=s.visibleEvents.first{it.id==seed.eventId}
    if(event.kind==EntryKind.TRANSFER.name)editEntry(s,event)else confirmActual(s,event)
    changeEntry(s.book.id,s.statementActualDraft(seed))
}
