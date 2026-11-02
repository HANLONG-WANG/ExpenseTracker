package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.FinancialDraftEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

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

/** Local discard remains a permanent boundary for cloud plans. */
suspend fun LedgerRepository.discardFinancialDraft(bookId:String,slot:String) {
    require(dao.book(bookId)!=null)
    db.financialDao().clearDraft(bookId,slot)
}

/** Only the category is edited; form inputs, original basis and resume context are retained. */
suspend fun LedgerRepository.reclassifyFinancialDraft(bookId:String,slot:String,categoryId:String)=db.withTransaction {
    val row=requireNotNull(db.financialDao().draft(bookId,slot)){"草稿不存在 / 下書きがありません"}
    val context=json.decodeFromString<FinancialDraftContext>(row.contextJson)
    require(context.kind=="EXPECTED"){"此金融草稿没有分类字段 / この金融下書きにはカテゴリがありません"}
    val category=requireNotNull(dao.category(bookId,categoryId))
    require(category.kind=="INCOME"&&categoryEnabled(bookId,categoryId)){"请选择启用的收入分类 / 有効な収入カテゴリを選択してください"}
    val fields=json.parseToJsonElement(row.valuesJson).jsonObject
    db.financialDao().putDraft(row.copy(valuesJson=kotlinx.serialization.json.JsonObject(fields+("category" to kotlinx.serialization.json.JsonPrimitive(categoryId))).toString(),updatedAt=clock.millis()))
}
