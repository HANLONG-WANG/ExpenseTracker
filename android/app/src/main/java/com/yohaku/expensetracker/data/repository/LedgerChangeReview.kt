package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString

/** The exact same prepared change drives both the impact preview and transaction. */
data class PreparedLedgerChange internal constructor(val before:EventEntity?,val event:EventEntity,
    val settlement:SettlementEntity,val postings:List<PostingEntity>,val transfer:TransferEntity?=null,val components:List<ExpenseComponentEntity> = emptyList(),val activityId:String?=null,val occurrenceId:String?=null,val reserveUses:List<ReserveUseEntity> = emptyList(),val treat:PreparedTreat?=null,val merchantId:String?=null,val media:PreparedMedia?=null,val contract:DebtContractEntity?=null,val repayment:FinancialRepaymentEntity?=null,val repaymentAllocations:List<FinancialRepaymentAllocationEntity> = emptyList(),val cardRuleChecked:Boolean=false)
data class LedgerChangeReview internal constructor(val snapshot:BookSnapshot,val change:PreparedLedgerChange,val impacts:List<EntryImpact>,val originalNetBefore:Long?,val originalNetAfter:Long?)

@kotlinx.serialization.Serializable
data class LedgerDetails(val settlement:SettlementEntity?=null,val transfer:TransferEntity?=null,val components:List<ExpenseComponentEntity> = emptyList(),val activityId:String?=null,val occurrenceId:String?=null,val reserveUses:List<ReserveUseEntity> = emptyList(),val treat:TreatEntity?=null,val allocations:List<TreatAllocationEntity> = emptyList(),val merchantId:String?=null,val attachmentIds:List<String> = emptyList(),val receiptRevision:Int?=null,val contract:DebtContractEntity?=null,val repayment:FinancialRepaymentEntity?=null,val repaymentAllocations:List<FinancialRepaymentAllocationEntity> = emptyList(),val postings:List<PostingEntity> = emptyList())

internal suspend fun LedgerRepository.writeChange(change:PreparedLedgerChange,clearDraft:Boolean=false,refreshFinance:Boolean=true):String {
    val e=change.event
    val beforeDetails=change.before?.let{LedgerDetails(dao.settlement(e.bookId,e.id),dao.transfer(e.bookId,e.id),dao.components(e.bookId,e.id),dao.eventActivity(e.bookId,e.id)?.activityId,dao.eventOccurrences(e.bookId,e.id).firstOrNull{it.status in listOf("PENDING","COMPLETED")}?.id,dao.reserveUses(e.bookId,e.id),dao.eventTreat(e.bookId,e.id),dao.eventTreat(e.bookId,e.id)?.let{t->dao.allocations(e.bookId,t.id)}.orEmpty(),dao.eventMerchant(e.bookId,e.id)?.merchantId,dao.attachments(e.bookId,e.id).map{a->a.fileId},dao.receipt(e.bookId,e.id)?.revision,contract=db.financialDao().fundingContract(e.bookId,e.id),repayment=db.financialDao().repayment(e.bookId,e.id),repaymentAllocations=db.financialDao().repaymentAllocations(e.bookId,e.id),postings=dao.eventPostings(e.bookId,e.id))}
    dao.putEvent(e)
    if(e.kind in listOf(EntryKind.EXPENSE.name,EntryKind.INCOME.name)||change.before?.kind in listOf(EntryKind.EXPENSE.name,EntryKind.INCOME.name)) {
        dao.clearEventMerchant(e.bookId,e.id)
        change.merchantId?.let{dao.putEventMerchant(EventMerchantEntity(e.bookId,e.id,it))}
    }
    if(e.kind==EntryKind.EXPENSE.name||change.before?.kind==EntryKind.EXPENSE.name) {
        dao.clearReserveUses(e.bookId,e.id);dao.putReserveUses(change.reserveUses)
        dao.clearEventTreat(e.bookId,e.id)
        change.treat?.let{writeTreat(it)}
    }
    change.media?.let{writeMedia(e.bookId,e.id,it)}
    dao.putSettlement(change.settlement)
    dao.clearPostings(e.bookId,e.id)
    dao.putPostings(change.postings)
    change.transfer?.let{dao.putTransfer(it)}
    dao.clearComponents(e.bookId,e.id)
    dao.putComponents(change.components)
    dao.clearEventActivity(e.bookId,e.id)
    change.activityId?.let{dao.putEventActivity(EventActivityEntity(e.bookId,e.id,it))}
    if(e.kind==EntryKind.EXPENSE.name)dao.refunds(e.bookId,e.id).forEach{refund->
        dao.clearEventActivity(e.bookId,refund.id)
        change.activityId?.let{dao.putEventActivity(EventActivityEntity(e.bookId,refund.id,it))}
    }
    change.occurrenceId?.let{id->val occurrence=dao.occurrence(e.bookId,id)!!;dao.putOccurrence(occurrence.copy(status="COMPLETED",eventId=e.id))}
    recheckOccurrences(e.bookId,e.id)
    change.contract?.let{db.financialDao().putContract(it)}
    change.repayment?.let{
        db.financialDao().putRepayment(it)
        db.financialDao().clearRepaymentAllocations(e.bookId,e.id)
        db.financialDao().insertRepaymentAllocations(change.repaymentAllocations)
    }
    if(refreshFinance) {
        if(change.before!=null||e.cardPaymentChoice!=null)refreshCardEntryPrediction(e)
        refreshFinancialPools(change)
    }
    dao.putRevision(RevisionEntity(e.bookId,id(),e.id,e.revision,clock.millis(),change.before?.let{json.encodeToString(it)},json.encodeToString(e),beforeDetails?.let{json.encodeToString(it)},json.encodeToString(LedgerDetails(change.settlement,change.transfer,change.components,change.activityId,change.occurrenceId,change.reserveUses,change.treat?.treat,change.treat?.allocations.orEmpty(),change.merchantId,dao.attachments(e.bookId,e.id).map{it.fileId},dao.receipt(e.bookId,e.id)?.revision,contract=change.contract,repayment=change.repayment,repaymentAllocations=change.repaymentAllocations,postings=change.postings))))
    if(clearDraft)clearDraft(e.bookId)
    return e.id
}

internal fun BookSnapshot.reviewState()=copy(book=book.copy(theme="",language=""),draft=null)

suspend fun LedgerRepository.previewEntry(bookId:String,draft:EntryDraft):LedgerChangeReview=db.withTransaction {
    val book=dao.book(bookId) ?: error("账本不存在 / 帳簿がありません")
    val snapshot=snapshot(book).first()
    val prepared=if(draft.kind==EntryKind.TRANSFER)prepareTransfer(bookId,draft)else if(draft.kind==EntryKind.REFUND) {
        val original=snapshot.visibleEvents.firstOrNull{it.id==draft.originalEventId} ?: error("原消费不存在 / 元の支出がありません")
        val account=snapshot.accounts.firstOrNull{it.id==draft.accountId} ?: error("请选择退款账户 / 返金先口座を選択してください")
        val originalAmount=Money.parse(draft.amount,Currency.valueOf(original.currency))
        val accountAmount=Money.parse(draft.accountAmount,Currency.valueOf(account.currency))
        val base=if(account.currency==book.currency)accountAmount else Money.parse(draft.baseAmount,Currency.valueOf(book.currency))
        prepareRefund(bookId,RefundInput(original.id,originalAmount,account.id,accountAmount,base,draft.date,draft.settlementConfirmed,draft.id,draft.note,draft.rateSource,draft.rateDate))
    }else prepareEntry(bookId,draft)
    val change=withMedia(prepared,draft)
    val e=change.event
    val next=snapshot.copy(events=snapshot.events.filter{it.id!=e.id}+e,
        postings=snapshot.postings.filter{it.eventId!=e.id}+change.postings.filter{it.confirmed},
        settlements=snapshot.settlements.filter{it.eventId!=e.id}+change.settlement,
        transfers=snapshot.transfers.filter{it.eventId!=e.id}+listOfNotNull(change.transfer),
        expenseComponents=snapshot.expenseComponents.filter{it.eventId!=e.id}+change.components,
        eventActivities=snapshot.eventActivities.filter{it.eventId!=e.id}+listOfNotNull(change.activityId?.let{EventActivityEntity(bookId,e.id,it)}),
        eventMerchants=if(e.kind in listOf(EntryKind.EXPENSE.name,EntryKind.INCOME.name))snapshot.eventMerchants.filter{it.eventId!=e.id}+listOfNotNull(change.merchantId?.let{EventMerchantEntity(bookId,e.id,it)})else snapshot.eventMerchants,
        reserveUses=if(e.kind==EntryKind.EXPENSE.name||change.before?.kind==EntryKind.EXPENSE.name)snapshot.reserveUses.filter{it.eventId!=e.id}+change.reserveUses else snapshot.reserveUses,
        treats=if(e.kind==EntryKind.EXPENSE.name||change.before?.kind==EntryKind.EXPENSE.name)snapshot.treats.filter{it.eventId!=e.id}+listOfNotNull(change.treat?.treat)else snapshot.treats,
        allocations=if(e.kind==EntryKind.EXPENSE.name||change.before?.kind==EntryKind.EXPENSE.name)snapshot.allocations.filter{a->snapshot.treats.none{it.eventId==e.id&&it.id==a.treatId}}+change.treat?.allocations.orEmpty()else snapshot.allocations,
        occurrences=snapshot.occurrences.map{if(it.id==change.occurrenceId)it.copy(status="COMPLETED",eventId=e.id)else it})
    val original=change.settlement.originalEventId?.let{id->snapshot.events.first{it.id==id}}
    val months=if(original!=null)listOf(original.date.take(7))else (listOfNotNull(change.before?.date?.take(7),e.date.take(7))+snapshot.expenseComponents.filter{it.eventId==e.id}.map{it.date.take(7)}+change.components.map{it.date.take(7)}+snapshot.occurrences.filter{it.id==change.occurrenceId||it.eventId==e.id}.map{it.scheduledDate.take(7)}+snapshot.reserves.filter{r->change.reserveUses.any{it.reserveId==r.id}||snapshot.reserveUses.any{it.eventId==e.id&&it.reserveId==r.id}}.map{it.date.take(7)}).distinct()
    val balances=snapshot.accounts.mapNotNull{a->val before=snapshot.balance(a);val after=next.balance(a);if(before==after)null else AccountBalanceChange(a,before,after)}
    fun income(s:BookSnapshot,month:String)=s.visibleEvents.filter{it.kind==EntryKind.INCOME.name&&it.date.startsWith(month)}.sumOf{it.baseMinor}
    val impacts=months.mapIndexed{index,month->
        val before=snapshot.budgetProjection(month);val after=next.budgetProjection(month)
        EntryImpact(month,Currency.valueOf(book.currency),snapshot.monthExpense(month),next.monthExpense(month),income(snapshot,month),income(next,month),if(index==0)balances else emptyList(),before.spentMinor,after.spentMinor,before.reserveMinor,after.reserveMinor,if(index==0)snapshot.friendChanges(next)else emptyList())
    }
    val purchase=original?:change.before?.takeIf{it.kind==EntryKind.EXPENSE.name}
    val nextPurchase=if(original!=null)original else e.takeIf{it.kind==EntryKind.EXPENSE.name}
    LedgerChangeReview(snapshot,change,impacts,purchase?.let{snapshot.netExpense(it)},nextPurchase?.let{next.netExpense(it)})
}

/** null means authoritative data changed: return to a fresh preview instead of committing. */
suspend fun LedgerRepository.commitEntryReview(bookId:String,review:LedgerChangeReview):String?=db.withTransaction {
    require(review.change.event.bookId==bookId)
    val book=dao.book(bookId) ?: error("账本不存在 / 帳簿がありません")
    if(snapshot(book).first().reviewState()!=review.snapshot.reviewState())return@withTransaction null
    val e=review.change.event
    if(review.change.cardRuleChecked&&db.financialDao().rules(bookId,e.accountId).cardRule(e.accountId,e.date,if(e.cardPaymentChoice in listOf("BONUS","DEFERRED"))"SINGLE"else"REVOLVING")?.id!=e.cardPaymentRuleId)return@withTransaction null
    writeChange(review.change,clearDraft=true)
}
