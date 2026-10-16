package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.json.*
import java.time.LocalDate

enum class AccountOperation { DEACTIVATE,ACTIVATE,DELETE }
data class AccountUsage(val references:Map<String,Int>,val draftKeys:List<String>) {
    val inUse get()=references.isNotEmpty()||draftKeys.isNotEmpty()
}

/** Derive reference roles from the app's composite foreign keys, including historical and pending rows. */
internal fun LedgerRepository.scopedReferenceCounts(bookId:String,parentTable:String,id:String):Map<String,Int> {
    val sql=db.openHelper.readableDatabase
    val tables=sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    return buildMap {
        for(table in tables) {
            val columns=sql.query("PRAGMA foreign_key_list(`$table`)").use{c->buildList{while(c.moveToNext())if(c.getString(2)==parentTable&&c.getString(4)=="id")add(c.getString(3))}}.distinct()
            if(columns.isEmpty())continue
            val count=sql.query("SELECT COUNT(*) FROM `$table` WHERE bookId=? AND ("+columns.joinToString(" OR "){"`$it`=?"}+")",(listOf(bookId)+columns.map{id}).toTypedArray()).use{c->c.moveToFirst();c.getInt(0)}
            if(count>0)put(table,count)
        }
    }.toSortedMap()
}
private fun JsonElement.references(id:String):Boolean=when(this) {
    is JsonPrimitive->isString&&content==id
    is JsonObject->any{(key,value)->if(key=="encoded"&&value is JsonPrimitive&&value.isString)Json.parseToJsonElement(value.content).references(id)else value.references(id)}
    is JsonArray->any{it.references(id)}
}
suspend fun LedgerRepository.accountUsage(bookId:String,accountId:String):AccountUsage=db.withTransaction {
    require(dao.book(bookId)!=null&&dao.account(bookId,accountId)!=null){"账户不属于当前可用账本 / 利用可能な帳簿の口座を指定してください"}
    val keys=mutableListOf<String>()
    dao.storedDraft(bookId)?.let{row->val d=json.decodeFromString<EntryDraft>(row.json)
        if(accountId in listOf(d.accountId,d.toAccountId,d.feeAccountId))keys+="entry:${d.mediaSession}"
    }
    db.financialDao().bookDrafts(bookId).filter{it.accountId==accountId||json.parseToJsonElement(it.valuesJson).references(accountId)||json.parseToJsonElement(it.contextJson).references(accountId)}.forEach{keys+="finance:${it.slot}"}
    AccountUsage(scopedReferenceCounts(bookId,"accounts",accountId),keys.sorted())
}
class AccountOperationReview internal constructor(internal val before:FinancialReviewState,val account:AccountEntity,val operation:AccountOperation,
    val usage:AccountUsage,val balanceMinor:Long,val knownOutstandingMinor:Long,val pendingCount:Int,val recordCount:Int)

suspend fun LedgerRepository.previewAccountOperation(bookId:String,accountId:String,operation:AccountOperation):AccountOperationReview=db.withTransaction {
    val state=financialReviewState(bookId);val s=state.ledger
    val account=s.accounts.firstOrNull{it.id==accountId}?:error("账户不属于本账本 / 別の帳簿の口座です")
    val usage=accountUsage(bookId,accountId)
    when(operation) {
        AccountOperation.DEACTIVATE->require(account.active)
        AccountOperation.ACTIVATE->require(!account.active)
        AccountOperation.DELETE->Unit
    }
    val today=LocalDate.now(clock)
    val outstanding=state.finance.bills.filter{b->b.active&&state.finance.plans.any{it.id==b.planId&&it.active&&it.accountId==accountId}}.fold(0L){n,b->Math.addExact(n,s.billOutstanding(b).totalMinor)}
    val pending=s.importantPayments(state.finance,s.cashTimeline(state.finance,today,today.plusDays(366))).count{it.item.accountId==accountId||it.item.sourceId==accountId}
    val records=s.events.count{e->e.accountId==accountId||s.storedPostings.any{it.eventId==e.id&&it.accountId==accountId}||s.expenseComponents.any{it.eventId==e.id&&it.accountId==accountId}||s.transfers.any{it.eventId==e.id&&it.toAccountId==accountId}}
    AccountOperationReview(state,account,operation,usage,if(AccountType.valueOf(account.type).debt)s.balance(account)else s.cashBalanceAt(account,today.toString()),outstanding,pending,records)
}
suspend fun LedgerRepository.commitAccountOperation(review:AccountOperationReview):Boolean=db.withTransaction {
    val account=review.account
    if(financialReviewState(account.bookId)!=review.before||accountUsage(account.bookId,account.id)!=review.usage)return@withTransaction false
    previewAccountOperation(account.bookId,account.id,review.operation)
    when(review.operation) {
        AccountOperation.DEACTIVATE->dao.putAccount(account.copy(active=false))
        AccountOperation.ACTIVATE->dao.putAccount(account.copy(active=true))
        AccountOperation.DELETE->{
            require(!review.usage.inUse){"账户仍有历史、计划、核对、预留或草稿引用，请优先停用 / 履歴・予定・照合・確保・下書きの参照がある口座は停止してください"}
            dao.deleteAccount(account.bookId,account.id)
        }
    }
    true
}
