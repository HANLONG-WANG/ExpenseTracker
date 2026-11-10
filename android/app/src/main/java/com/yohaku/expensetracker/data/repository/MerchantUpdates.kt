package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.EntryKind
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString

/** Ownership is evaluated before renaming, including recoverable records and legacy name matches. */
fun BookSnapshot.merchantEvents(id:String)=events.filter{merchantKey(it)=="id:$id"}
fun BookSnapshot.merchantAdoptions(name:String,old:MerchantEntity?):List<EventEntity> {
    val key=normalizedMerchantName(name)
    if(key.isEmpty()||old!=null&&key==normalizedMerchantName(old.name))return emptyList()
    return events.filter{it.id !in merchantByEvent&&normalizedMerchantName(it.merchant)==key&&
        (old==null||merchantKey(it)!="id:${old.id}")}
}
fun BookSnapshot.merchantNameChanges(name:String,old:MerchantEntity?)=
    (old?.let{merchantEvents(it.id)}.orEmpty()+merchantAdoptions(name,old)).distinctBy{it.id}.count{it.merchant!=name.trim()}

internal fun BookSnapshot.merchantMergeEvents(sourceId:String,targetId:String):List<EventEntity> {
    val afterRemoval=copy(merchants=merchants.filter{it.id!=sourceId})
    return (merchantEvents(sourceId)+merchantEvents(targetId)+afterRemoval.merchantEvents(targetId)).distinctBy{it.id}
}
fun BookSnapshot.merchantMergeCount(sourceId:String,targetId:String)=
    merchantMergeEvents(sourceId,targetId).count{merchantKey(it)!="id:$targetId"}

suspend fun LedgerRepository.saveMerchant(bookId:String,name:String,note:String="",id:String?=null,active:Boolean=true,confirmAdoption:Boolean=false):String=db.withTransaction {
    val s=snapshot(requireNotNull(dao.book(bookId))).first()
    require(name.isNotBlank()){"请输入商户原名 / お店の元の名前を入力してください"}
    val old=id?.let{key->s.merchants.singleOrNull{it.id==key}?:error("商户不属于本账本 / 別の帳簿のお店です")}
    val renamed=old==null||normalizedMerchantName(old.name)!=normalizedMerchantName(name)
    require(!renamed||s.merchants.none{it.id!=id&&normalizedMerchantName(it.name)==normalizedMerchantName(name)}) {
        "已有同名商户，请在应用内合并 / 同名のお店があります。アプリ内で統合してください"
    }
    val adopted=s.merchantAdoptions(name,old)
    require(old==null||adopted.isEmpty()||confirmAdoption) {
        "请先确认归入 ${adopted.size} 条同名记录 / 同名の記録 ${adopted.size} 件の取り込みを確認してください"
    }
    val merchant=MerchantEntity(bookId,old?.id?:this.id(),name.trim(),note,active)
    dao.putMerchant(merchant)
    synchronizeMerchant(s,merchant,old?.let{setOf(it.id)}.orEmpty(),
        (old?.let{s.merchantEvents(it.id)}.orEmpty()+adopted).distinctBy{it.id})
    merchant.id
}

/** A separate operation is the explicit merge confirmation. Editor changes are included atomically. */
suspend fun LedgerRepository.mergeMerchants(bookId:String,sourceId:String,targetId:String,sourceNote:String?=null,sourceActive:Boolean?=null):String=db.withTransaction {
    require(sourceId!=targetId)
    val s=snapshot(requireNotNull(dao.book(bookId))).first()
    val source=s.merchants.single{it.id==sourceId}.let{it.copy(note=sourceNote?:it.note,active=sourceActive?:it.active)}
    val target=s.merchants.single{it.id==targetId}
    val merged=target.copy(note=listOf(target.note,source.note).filter{it.isNotBlank()}.distinct().joinToString("\n"),active=target.active||source.active)
    dao.putMerchant(merged)
    s.merchantLocations.filter{it.merchantId==sourceId}.forEach{dao.putMerchantLocation(it.copy(merchantId=targetId))}
    // Removing S can resolve a historical same-name ambiguity. Include those newly owned rows too.
    val records=s.merchantMergeEvents(sourceId,targetId)
    synchronizeMerchant(s,merged,setOf(sourceId,targetId),records)
    dao.deleteMerchantLinks(bookId,sourceId)
    dao.deleteMerchant(bookId,sourceId)
    targetId
}

/**
 * The merchant name on a record is a live reference to the saved merchant, like its link. Updating it is
 * therefore not an edit of the record: no revision is written, so record history, recent-entry defaults,
 * exported receipt bundles and financial facts keyed by event revision stay untouched.
 */
private suspend fun LedgerRepository.synchronizeMerchant(s:BookSnapshot,target:MerchantEntity,sourceIds:Set<String>,events:List<EventEntity>) {
    for(event in events) {
        if(event.kind==EntryKind.EXPENSE.name||event.id in s.merchantByEvent) {
            dao.putEventMerchant(EventMerchantEntity(s.book.id,event.id,target.id))
            dao.clearEventPlace(s.book.id,event.id)
        }
        if(event.merchant!=target.name)dao.putEvent(event.copy(merchant=target.name))
    }
    s.templates.filter{it.merchantId in sourceIds}.forEach{dao.putTemplate(it.copy(merchantId=target.id,merchant=target.name))}
    s.recurrences.filter{it.merchantId in sourceIds}.forEach{dao.putRecurrence(it.copy(merchantId=target.id,merchant=target.name))}
    dao.storedDraft(s.book.id)?.let{stored->
        val draft=json.decodeFromString<com.yohaku.expensetracker.domain.model.EntryDraft>(stored.json)
        if(draft.merchantId in sourceIds)dao.putDraft(stored.copy(json=json.encodeToString(draft.copy(merchantId=target.id,merchant=target.name))))
    }
}
