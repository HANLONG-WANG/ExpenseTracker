package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*

fun EventEntity.isFinancialSource(account:AccountEntity)=accountId==account.id&&if(account.type==AccountType.CREDIT.name)kind in listOf(EntryKind.EXPENSE.name,EntryKind.TRANSFER.name)else kind==EntryKind.BORROWING.name
fun BookSnapshot.lastSavedEntry(predicate:(EventEntity)->Boolean)=visibleEvents.filter(predicate).maxWithOrNull(compareBy<EventEntity>{eventSaveTimes[it.id]?:it.createdAt}.thenBy{it.createdAt})
fun BookSnapshot.defaultEntryAccount(kind:EntryKind):String? {
    val eligible=entryAccounts(kind);val last=lastSavedEntry{it.kind==kind.name}
    return eligible.firstOrNull{it.id==last?.accountId}?.id?:eligible.firstOrNull()?.id
}
fun BookSnapshot.transferSources()=accounts.filter{it.active&&it.type!=AccountType.LOAN.name}
fun BookSnapshot.transferTargets()=accounts.filter{it.active&&!AccountType.valueOf(it.type).debt}
fun BookSnapshot.newTransferDraft(date:String,fromId:String?=null,toAccountId:String?=null):EntryDraft {
    val sources=transferSources();val targets=transferTargets();val last=lastSavedEntry{it.kind==EntryKind.TRANSFER.name}
    val destination=targets.firstOrNull{it.id==toAccountId}
    val from=sources.firstOrNull{it.id==fromId}?:sources.firstOrNull{it.id==last?.accountId&&it.id!=destination?.id}?:sources.firstOrNull{it.id!=destination?.id}
    val previous=transfers.firstOrNull{it.eventId==last?.id}?.toAccountId
    val to=destination?.takeIf{it.id!=from?.id}?:targets.firstOrNull{it.id==previous&&it.id!=from?.id}?:targets.firstOrNull{it.id!=from?.id}
    return withRememberedCardPayment(EntryDraft(kind=EntryKind.TRANSFER,date=date,accountId=from?.id,toAccountId=to?.id))
}
fun EntryDraft.clearCardPayment()=copy(cardPaymentChoice=null,cardPaymentPeriods="",cardPaymentMonth="",cardPaymentFee="",cardPaymentRecheck=false)
fun BookSnapshot.withRememberedCardPayment(d:EntryDraft):EntryDraft {
    if(accounts.none{it.id==d.accountId&&it.type==AccountType.CREDIT.name}||d.kind !in listOf(EntryKind.EXPENSE,EntryKind.TRANSFER))return d.clearCardPayment()
    if(d.id!=null)return d
    val last=lastSavedEntry{it.accountId==d.accountId&&it.kind in listOf(EntryKind.EXPENSE.name,EntryKind.TRANSFER.name)}
    return d.clearCardPayment().copy(cardPaymentChoice=last?.cardPaymentChoice,cardPaymentPeriods=last?.takeIf{it.cardPaymentChoice=="INSTALLMENT"}?.cardPaymentPeriods?.toString().orEmpty())
}
fun EntryDraft.hasManualInput(baseline:EntryDraft?):Boolean {
    if(kind!=EntryKind.TRANSFER&&categoryId==null)return false
    if(baseline==null)return true
    fun EntryDraft.comparable()=copy(capturedPlace=null,categoryId=null,merchant=if(merchantManuallyEdited)merchant else "",merchantId=if(merchantManuallyEdited)merchantId else null,merchantLocated=false)
    return comparable()!=baseline.comparable()
}
fun AccountBalanceChange.needsNegativeBalanceConfirmation()=!AccountType.valueOf(account.type).debt&&after<0&&after<before
fun LedgerChangeReview.negativeBalanceChanges()=impacts.flatMap{it.balances}.filter{it.needsNegativeBalanceConfirmation()}
fun normalizedMerchantName(value:String)=normalizedSearchText(value.trim())
fun List<MerchantEntity>.matchingMerchants(query:String)=filter{it.active&&normalizedMerchantName(it.name).contains(normalizedMerchantName(query))}
fun BookSnapshot.merchantInput(d:EntryDraft,value:String)=d.copy(merchant=value,merchantId=merchants.filter{normalizedMerchantName(it.name)==normalizedMerchantName(value)}.singleOrNull()?.takeIf{it.active}?.id,merchantManuallyEdited=true,merchantLocated=false)

/** The editor and command adapters share this policy; risk acceptance remains separate. */
suspend fun LedgerRepository.entryNeedsReview(bookId:String,d:EntryDraft):Boolean {
    val old=d.id?.let{dao.event(bookId,it)}
    val account=dao.account(bookId,d.accountId?:"")
    val linked=old?.let{dao.refunds(bookId,it.id)}.orEmpty()
    val settlement=old?.let{dao.settlement(bookId,it.id)}
    return d.occurrenceId!=null || d.activityId!=null || d.kind in listOf(EntryKind.REFUND,EntryKind.TRANSFER) ||
        old!=null&&(old.currency!=account?.currency || account?.currency!=dao.book(bookId)?.currency || linked.isNotEmpty() || settlement?.confirmed==false || dao.eventActivity(bookId,old.id)!=null)
}

internal fun validateEntryCategory(kind:String,category:CategoryEntity,enabled:Boolean,previous:String?) {
    require(category.kind==kind&&(enabled||previous==category.id)){"分类用途不符或已归档 / カテゴリの用途が異なるかアーカイブ済みです"}
}
