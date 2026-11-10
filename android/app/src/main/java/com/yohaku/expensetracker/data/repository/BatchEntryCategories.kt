package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.EntryKind
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString

/** Category-only edits preserve settlement, card choice, media, occurrence and all financial facts.
 * One snapshot, one preparation pass. No per-row graph read or financial recalculation is needed. */
internal data class PreparedCategoryChange(val before:EventEntity,val after:EventEntity,val details:LedgerDetails)

internal fun prepareEntryCategories(snapshot:BookSnapshot,ids:List<String>,categoryId:String):List<PreparedCategoryChange> {
    require(ids.isNotEmpty()&&ids.size<=5000&&ids.distinct().size==ids.size)
    val category=snapshot.categoryById[categoryId]?:error("分类不存在 / カテゴリがありません")
    return ids.map{id->
        val old=snapshot.visibleById[id]?:error("目标记录已变化，请重新预览 / 対象の記録が変わりました。再確認してください")
        require(old.kind in listOf("EXPENSE","INCOME")){"仅支出和收入可改分类 / 支出・収入のみカテゴリを変更できます"}
        validateEntryCategory(old.kind,category,snapshot.categoryEnabled(categoryId),old.categoryId)
        PreparedCategoryChange(old,old.copy(categoryId=categoryId,revision=Math.addExact(old.revision,1)),snapshot.entryDetails(old.id))
    }
}

/** Caller holds mediaWrites and the outer transaction. Revision timestamps still drive recent defaults. */
internal suspend fun LedgerRepository.writeEntryCategoriesInTransaction(changes:List<PreparedCategoryChange>) {
    val timestamp=clock.millis()
    val merchants=changes.firstOrNull()?.let{dao.merchants(it.after.bookId)}.orEmpty()
    for((before,prepared,details) in changes) {
        val merchant=if(prepared.kind in listOf("EXPENSE","INCOME"))resolveSavedMerchant(prepared.bookId,EntryKind.valueOf(prepared.kind),details.merchantId,prepared.merchant,merchants)else null
        val after=prepared.copy(merchant=merchant?.name?:prepared.merchant)
        currentCoroutineContext().ensureActive()
        dao.putEvent(after)
        if(merchant!=null)dao.putEventMerchant(EventMerchantEntity(after.bookId,after.id,merchant.id))
        if(after.kind==EntryKind.EXPENSE.name)synchronizeRefundMerchant(after)
        val savedDetails=json.encodeToString(details)
        val afterDetails=json.encodeToString(details.copy(merchantId=merchant?.id))
        dao.putRevision(RevisionEntity(after.bookId,id(),after.id,after.revision,timestamp,json.encodeToString(before),json.encodeToString(after),savedDetails,afterDetails))
    }
}

/** Shared details for lightweight category revisions; merchant normalization updates only its ID. */
internal fun BookSnapshot.entryDetails(id:String):LedgerDetails {
    val treat=treats.firstOrNull{it.eventId==id}
    return LedgerDetails(settlement=settlementByEvent[id],transfer=transferByEvent[id],components=expenseComponents.filter{it.eventId==id},
        activityId=activityByEvent[id],occurrenceId=occurrences.firstOrNull{it.eventId==id&&it.status in listOf("PENDING","COMPLETED")}?.id,
        reserveUses=reserveUses.filter{it.eventId==id},treat=treat,allocations=allocations.filter{it.treatId==treat?.id},
        merchantId=merchantByEvent[id],attachmentIds=attachments.filter{it.eventId==id}.map{it.fileId}.sorted(),receiptRevision=receipts.firstOrNull{it.eventId==id}?.revision,
        contract=contracts.firstOrNull{it.fundingEventId==id},repayment=repayments.firstOrNull{it.eventId==id},repaymentAllocations=repaymentAllocations.filter{it.eventId==id},postings=storedPostingsByEvent[id].orEmpty())
}
