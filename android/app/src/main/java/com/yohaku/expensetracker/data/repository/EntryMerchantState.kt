package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.data.db.MerchantEntity

fun EntryDraft.merchantFields()=MerchantBaseline(merchant,merchantId)
fun merchantFieldsUnchanged(fields:MerchantBaseline,baseline:MerchantBaseline?,merchants:List<MerchantEntity>):Boolean =
    baseline!=null&&(fields==baseline||baseline.id!=null&&merchants.none{it.id==baseline.id}&&fields==baseline.copy(id=null))
data class EntryMerchantConflict(val current:MerchantBaseline,val requested:MerchantBaseline,val legacy:Boolean)
data class EntryMerchantDecision(val value:MerchantBaseline,val conflict:EntryMerchantConflict?=null)

/** Compare intent before resolving: normalization/ID lookup must not turn untouched input into an edit. */
fun EntryDraft.resolveEditingMerchant(bookId:String,current:MerchantBaseline?,merchants:List<MerchantEntity>):EntryMerchantDecision {
    val currentMerchant=current?.let{fields->
        val selected=resolveSavedMerchant(bookId,kind,fields.id,fields.name,merchants)
        MerchantBaseline(selected?.name?:fields.name,selected?.id)
    }
    if(currentMerchant!=null&&merchantFieldsUnchanged(merchantFields(),merchantBaseline,merchants))return EntryMerchantDecision(currentMerchant)
    val selected=resolveSavedMerchant(bookId,kind,merchantId,merchant,merchants)
    val requested=MerchantBaseline(selected?.name?:merchant,selected?.id)
    fun sameOwner(a:MerchantBaseline,b:MerchantBaseline)=if(a.id!=null||b.id!=null)a.id==b.id else normalizedMerchantName(a.name)==normalizedMerchantName(b.name)
    val conflict=current!=null&&(merchantBaseline==null||current!=merchantBaseline)&&!sameOwner(requireNotNull(currentMerchant),requested)
    return EntryMerchantDecision(requested,if(conflict)EntryMerchantConflict(current,requested,merchantBaseline==null)else null)
}

/** Shared by the live entry, template and recurrence editors; user edits retain their opening baseline. */
fun EntryDraft.syncMerchantFields(latest:MerchantBaseline,merchants:List<MerchantEntity>):EntryDraft =
    if(merchantFieldsUnchanged(merchantFields(),merchantBaseline,merchants))
        copy(merchant=latest.name,merchantId=latest.id,merchantBaseline=latest,merchantLocated=merchantLocated&&latest.id!=null)
    else this

fun EntryDraft.clearMissingMerchant(merchants:List<MerchantEntity>):EntryDraft {
    val ids=merchants.map{it.id}.toSet()
    return copy(merchantId=merchantId?.takeIf{it in ids},
        merchantLocated=merchantLocated&&merchantId in ids)
}

fun BookSnapshot.syncEditingMerchant(draft:EntryDraft):EntryDraft {
    if(draft.kind==EntryKind.REFUND)return draft.copy(merchant=events.firstOrNull{it.id==draft.originalEventId}?.merchant?:draft.merchant,merchantId=null)
    val event=events.firstOrNull{it.id==draft.id}?:return draft.clearMissingMerchant(merchants)
    return draft.syncMerchantFields(MerchantBaseline(event.merchant,eventMerchants.firstOrNull{it.eventId==event.id}?.merchantId),merchants).clearMissingMerchant(merchants)
}
