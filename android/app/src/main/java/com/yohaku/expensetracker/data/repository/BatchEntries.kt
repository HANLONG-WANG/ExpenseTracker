package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.domain.model.EntryDraft
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/** Batch preparation uses one ledger graph. Writes remain in the caller's atomic transaction.
 * Per-row authoritative lookups see earlier writes (refund ceilings/reserve use), without a graph
 * reload. Finance is refreshed after all source facts have been written, sharing that final graph. */
internal suspend fun LedgerRepository.writeEntryBatchInTransaction(initial:BookSnapshot,drafts:List<EntryDraft>):List<PreparedLedgerChange> {
    require(drafts.size in 1..5000)
    var snapshot=initial
    val changes=mutableListOf<PreparedLedgerChange>()
    val firstSavedAt=maxOf(clock.millis(),initial.eventSaveTimes.values.maxOrNull()?:0L,initial.events.maxOfOrNull{it.createdAt}?:0L)+1L
    for((index,draft) in drafts.withIndex()) {
        currentCoroutineContext().ensureActive()
        val prepared=prepareEntryDraft(snapshot,draft)
        val savedAt=Math.addExact(firstSavedAt,index.toLong())
        val change=if(prepared.before==null)prepared.copy(event=prepared.event.copy(createdAt=savedAt))else prepared
        writeChange(change,clearDraft=false,refreshFinance=false,savedAt=savedAt)
        changes+=change
        // Only the reserve/default/refund facts needed by the next preparation are projected here.
        val e=change.event
        snapshot=snapshot.copy(events=snapshot.events.filter{it.id!=e.id}+e,
            storedPostings=snapshot.storedPostings.filter{it.eventId!=e.id}+change.postings,
            postings=snapshot.postings.filter{it.eventId!=e.id}+change.postings.filter{it.confirmed},
            settlements=snapshot.settlements.filter{it.eventId!=e.id}+change.settlement,
            reserveUses=snapshot.reserveUses.filter{it.eventId!=e.id}+change.reserveUses,
            occurrences=snapshot.occurrences.map{if(it.id==change.occurrenceId)it.copy(status="COMPLETED",eventId=e.id)else it})
    }
    val finalState=financialReviewState(initial.book.id)
    // No graph query of events/revisions/postings per row, including credit-card batches.
    suspend fun financeState()=finalState.copy(finance=requireNotNull(db.financialDao().graph(initial.book.id).first()))
    for(change in changes) {
        if(change.before!=null||change.event.cardPaymentChoice!=null)refreshCardEntryPrediction(change.event,::financeState)
    }
    // A pool may depend on several changed entries; recompute each on final source facts.
    for(change in changes)refreshFinancialPools(change,::financeState)
    return changes
}
