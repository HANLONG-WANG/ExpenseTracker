package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString


enum class EntryLifecycleOperation { TRASH,RESTORE,PURGE,EMPTY }
class EntryLifecycleReview internal constructor(internal val before:FinancialReviewState,internal val decisions:List<StatementDecisionEntity>,internal val statementBatches:List<StatementBatchEntity>,
    internal val draft:DraftEntity?,internal val financialDrafts:List<FinancialDraftEntity>,
    val operation:EntryLifecycleOperation,val event:EventEntity?,val events:List<EventEntity>,val impacts:List<EntryImpact>,
    val categoryImpacts:List<CategoryProjectionChange>,val fileCount:Int,val statementCount:Int,val planCount:Int,val draftCount:Int)

suspend fun LedgerRepository.previewEntryLifecycle(bookId:String,eventId:String?,operation:EntryLifecycleOperation):EntryLifecycleReview=db.withTransaction {
    val state=financialReviewState(bookId);val s=state.ledger
    val event=eventId?.let{id->requireNotNull(s.events.find{it.id==id}){"记录不存在 / 記録がありません"}}
    require((operation==EntryLifecycleOperation.EMPTY)==(event==null))
    val events=when(operation) {
        EntryLifecycleOperation.EMPTY->s.events.filter{it.deletedAt!=null}.also{require(it.isNotEmpty())}
        EntryLifecycleOperation.TRASH->{require(event!!.deletedAt==null);listOf(event)+s.linkedLifecycleRefunds(event,false)}
        EntryLifecycleOperation.RESTORE->{validateEntryRestoration(s,event!!);listOf(event)+s.linkedLifecycleRefunds(event,true)}
        EntryLifecycleOperation.PURGE->{require(event!!.deletedAt!=null);listOf(event)+s.events.filter{r->s.settlements.any{it.eventId==r.id&&it.originalEventId==event.id}}.also{require(it.all{r->r.deletedAt!=null})}}
    }
    val ids=events.map{it.id}.toSet()
    val draft=dao.storedDraft(bookId)
    val financialDrafts=db.financialDao().bookDrafts(bookId)
    val decisions=db.fileWorkflowDao().decisions(bookId).first()
    val batches=db.fileWorkflowDao().statements(bookId).first()
    val postingIds=s.storedPostings.filter{it.eventId in ids}.map{it.id}.toSet()
    val impacts=if(operation in listOf(EntryLifecycleOperation.TRASH,EntryLifecycleOperation.RESTORE))s.let{if(operation==EntryLifecycleOperation.TRASH)it.removalImpacts(event!!.id)else it.restorationImpacts(event!!.id)}else emptyList()
    val restored=operation==EntryLifecycleOperation.RESTORE
    val nextEvents=s.events.map{if(it.id in ids)it.copy(deletedAt=if(restored)null else clock.millis())else it}
    val live=nextEvents.filter{it.deletedAt==null}.map{it.id}.toSet()
    val next=s.copy(events=nextEvents,postings=s.storedPostings.filter{it.confirmed&&it.eventId in live})
    val categoryImpacts=if(impacts.isEmpty())emptyList()else s.categories.flatMap{c->impacts.mapNotNull{impact->
        val before=s.budgetProjection(impact.month,c.id);val after=next.budgetProjection(impact.month,c.id)
        if(before.limitMinor!=null&&before!=after)CategoryProjectionChange(c.id,impact.month,before,after)else null
    }}
    val fileIds=s.attachments.filter{it.eventId in ids}.map{it.fileId}+s.receiptImages.filter{image->s.receipts.any{it.id==image.receiptId&&it.eventId in ids}}.map{it.fileId}
    val editingDraft=draft?.let{json.decodeFromString<EntryDraft>(it.json)}?.takeIf{it.id in ids}
    val stagedFiles=s.stagedMedia.filter{it.sessionId==editingDraft?.mediaSession}.map{it.fileId}
    val drafts=(if(draft?.let{json.decodeFromString<EntryDraft>(it.json).id in ids}==true)1 else 0)+financialDrafts.count{json.decodeFromString<FinancialDraftContext>(it.contextJson).targetId in ids}
    EntryLifecycleReview(state,decisions,batches,draft,financialDrafts,operation,event,events,impacts,categoryImpacts,(fileIds+stagedFiles).distinct().size,
        s.reviewStatements(batches,decisions).count{p->p.result.exact.any{it.postingId in postingIds}||p.decisions.any{it.saved.postingId in postingIds}},state.finance.plans.count{it.sourceEventId in ids||state.finance.repaymentAllocations.any{a->a.eventId in ids&&state.finance.bills.any{b->b.id==a.billId&&b.planId==it.id}}},drafts)
}
private fun LedgerRepository.validateEntryRestoration(s:BookSnapshot,event:EventEntity) {
    require(event.deletedAt?.let{it>clock.millis()-recoveryDuration.toMillis()}==true){"记录已超过恢复期限 / 復元期限を過ぎました"}
    val originalId=s.settlements.find{it.eventId==event.id}?.originalEventId
    if(originalId!=null) {
        val original=requireNotNull(s.events.find{it.id==originalId&&it.deletedAt==null}){"请先恢复原消费 / 元の支出を先に復元してください"}
        require(original.kind==EntryKind.EXPENSE.name&&original.currency==event.currency){"原消费类型或币种已改变，不能恢复退款 / 元の支出の種類・通貨が変わったため返金を復元できません"}
        val other=s.events.filter{e->e.deletedAt==null&&e.id!=event.id&&s.settlements.any{it.eventId==e.id&&it.originalEventId==originalId}}.fold(0L){n,e->Math.addExact(n,e.amountMinor)}
        require(Math.addExact(other,event.amountMinor)<=original.amountMinor){"恢复后退款超过原消费，请先修正金额 / 復元後の返金総額が元の支出額を超えます"}
    }
    s.reserveUses.filter{it.eventId==event.id}.forEach{use->val reserve=s.reserves.first{it.id==use.reserveId}
        require(!reserve.active||use.amountMinor<=s.reserveRemaining(reserve)){"原预留已被使用或释放，请先调整预留再恢复 / 元の確保は使用・解放済みです。確保を調整してから復元してください"}
    }
}
suspend fun LedgerRepository.commitEntryLifecycle(review:EntryLifecycleReview):Boolean=db.withTransaction {
    val b=review.before.ledger.book.id
    if(financialReviewState(b)!=review.before||db.fileWorkflowDao().decisions(b).first()!=review.decisions||db.fileWorkflowDao().statements(b).first()!=review.statementBatches||dao.storedDraft(b)!=review.draft||db.financialDao().bookDrafts(b)!=review.financialDrafts)return@withTransaction false
    val ids=review.events.map{it.id}.toSet()
    when(review.operation) {
        EntryLifecycleOperation.TRASH->{
            val group=id();val now=clock.millis()
            review.events.forEach{e->dao.putEvent(e.copy(deletedAt=now,deletionGroupId=group));recheckOccurrences(b,e.id)}
            val postings=review.before.ledger.storedPostings.filter{it.eventId in ids}.map{it.id}.toSet()
            review.decisions.filter{it.postingId in postings}.forEach{db.fileWorkflowDao().putDecision(it.copy(invalidatedAt=now))}
        }
        EntryLifecycleOperation.RESTORE->{
            validateEntryRestoration(review.before.ledger,requireNotNull(review.event))
            review.events.forEach{e->dao.putEvent(e.copy(deletedAt=null,deletionGroupId=null));recheckOccurrences(b,e.id)}
        }
        EntryLifecycleOperation.PURGE,EntryLifecycleOperation.EMPTY->{
            review.events.forEach{e->if(dao.event(b,e.id)!=null)purgeEntryGroup(b,e.id)}
        }
    }
    true
}

/** Remove user records and media references without converting a retained loan source into an opening balance. */
internal suspend fun LedgerRepository.purgeEntryGroup(bookId:String,eventId:String) {
    val event=dao.event(bookId,eventId)?:return
    require(event.deletedAt!=null)
    dao.refunds(bookId,eventId).forEach{refund->require(refund.deletedAt!=null);purgeEntryGroup(bookId,refund.id)}
    val sql=db.openHelper.writableDatabase
    val ids=arrayOf(bookId,eventId)
    dao.eventOccurrences(bookId,eventId).forEach{dao.putOccurrence(it.copy(eventId=null))}
    sql.execSQL("DELETE FROM financial_repayment_allocations WHERE bookId=? AND eventId=?",ids)
    sql.execSQL("DELETE FROM financial_repayments WHERE bookId=? AND eventId=?",ids)
    sql.execSQL("DELETE FROM credit_limit_recoveries WHERE bookId=? AND eventId=?",ids)
    // Every owned CASCADE row is removable, including receipt images and treat allocations through their parents.
    val tables=sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    for(table in tables) {
        val columns=sql.query("PRAGMA foreign_key_list(`$table`)").use{c->buildList{while(c.moveToNext())if(c.getString(2)=="events"&&c.getString(4)=="id"&&c.getString(6)=="CASCADE")add(c.getString(3))}}.distinct()
        if(columns.isNotEmpty())sql.execSQL("DELETE FROM `$table` WHERE bookId=? AND ("+columns.joinToString(" OR "){"`$it`=?"}+")",(listOf(bookId)+columns.map{eventId}).toTypedArray())
    }
    dao.storedDraft(bookId)?.let{if(json.decodeFromString<EntryDraft>(it.json).id==eventId)clearDraft(bookId)}
    db.financialDao().bookDrafts(bookId).filter{json.decodeFromString<FinancialDraftContext>(it.contextJson).targetId==eventId}.forEach{db.financialDao().clearDraft(bookId,it.slot)}
    if(scopedReferenceCounts(bookId,"events",eventId).isEmpty())sql.execSQL("DELETE FROM events WHERE bookId=? AND id=?",ids)
    else dao.putEvent(EventEntity(bookId,eventId,event.kind,event.accountId,null,0,event.currency,0,"1970-01-01","","",revision=0,deletedAt=0,purgedAt=clock.millis()))
}
