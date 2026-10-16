package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.FinancialDraftEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable data class FinancialDraftContext(val kind:String,val accountId:String?,val targetId:String?=null,val billId:String?=null,val contractId:String?=null,val opening:Boolean=false,val mode:String?=null) {
    fun slot()=kotlinx.serialization.json.Json.encodeToString(this)
}

suspend fun LedgerRepository.storeFinancialDraft(context:FinancialDraftContext,bookId:String,values:String,basis:String) {
    require(dao.book(bookId)!=null)
    context.accountId?.let{require(dao.account(bookId,it)!=null)}
    db.financialDao().putDraft(FinancialDraftEntity(bookId,context.slot(),context.accountId,json.encodeToString(context),values,basis,clock.millis()))
}

/** Draft removal and the accepted formal change either both commit or both roll back. */
suspend fun LedgerRepository.commitFinancialDraft(bookId:String,slot:String,action:suspend()->String?):String?=db.withTransaction {
    action().also{if(it!=null)db.financialDao().clearDraft(bookId,slot)}
}
