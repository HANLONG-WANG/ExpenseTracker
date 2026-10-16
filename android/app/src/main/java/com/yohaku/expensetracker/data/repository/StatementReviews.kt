package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.domain.reconcile.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class ReviewedPostingFact(val bookId:String,val accountId:String,val postingId:String,val eventId:String,
    val currency:String,val amount:String,val direction:String,val postedDate:String,val occurredDate:String)
fun ReconcilePosting.reviewFact()=ReviewedPostingFact(bookId,accountId,postingId,eventId,currency,amount.toPlainString(),direction,postedDate,occurredDate)
data class ReviewedStatementDifference(val saved:StatementDecisionEntity,val original:ReviewedPostingFact,
    val current:ReviewedPostingFact?,val active:Boolean)
data class StatementReviewProjection(val batch:StatementBatchEntity,val file:StatementFile,
    val result:StatementMatchingResult,val decisions:List<ReviewedStatementDifference>)

/** Manual differences reserve their own posting, but are never promoted to exact matches.
 * A changed/deleted posting releases its reservation and leaves the row explicitly awaiting review.
 */
fun BookSnapshot.reviewStatements(batches:List<StatementBatchEntity>,decisions:List<StatementDecisionEntity>):List<StatementReviewProjection> {
    require(batches.all{it.bookId==book.id}&&decisions.all{it.bookId==book.id})
    val postings=reconciliationPostings()
    val facts=postings.associate{it.postingId to it.reviewFact()}
    val batchIds=batches.map{it.id}.toSet()
    val reviewed=decisions.filter{it.batchId in batchIds}.map { saved ->
        val original=FileProtocols.json.decodeFromString<ReviewedPostingFact>(saved.postingJson)
        val current=facts[saved.postingId]
        ReviewedStatementDifference(saved,original,current,current==original)
    }
    val occupied=reviewed.filter{it.active}.map{it.saved.postingId}.toMutableSet()
    val projected=batches.map { batch ->
        val file=FileProtocols.statement(batch.originalJson)
        val manual=reviewed.filter{it.saved.batchId==batch.id}
        val excluded=manual.map{it.saved.rowId}.toSet()
        val result=matchStatement(file.copy(rows=file.rows.filter{it.row_id !in excluded}),postings,occupied)
        occupied+=result.exact.map{it.postingId}
        StatementReviewProjection(batch,file,result,manual)
    }
    // A later overlapping batch can cover a posting absent from an earlier file.
    // Global reservations must be considered before calling anything ledger-only.
    return projected.map { projection ->
        val result=projection.result
        val differences=result.differences.map { it.copy(candidatePostingIds=it.candidatePostingIds.filterNot{candidate->candidate in occupied}) }
        projection.copy(result=result.copy(
            ledgerOnly=result.ledgerOnly.filterNot{it in occupied},uncovered=result.uncovered.filterNot{it in occupied},
            statementOnly=result.statementOnly+differences.filter{it.candidatePostingIds.isEmpty()}.map{it.rowId},
            differences=differences.filter{it.candidatePostingIds.isNotEmpty()}))
    }
}

data class StatementDifferenceReview(val bookId:String,val batchId:String,val row:StatementRow,
    val posting:ReviewedPostingFact,val prior:StatementDecisionEntity?,val note:String)
suspend fun LedgerRepository.previewStatementDifference(bookId:String,batchId:String,rowId:String,postingId:String,note:String):StatementDifferenceReview {
    val book=dao.book(bookId)?:error("账本不存在 / 帳簿がありません")
    val s=snapshot(book).first()
    val batches=db.fileWorkflowDao().statements(bookId).first()
    val decisions=db.fileWorkflowDao().decisions(bookId).first()
    val projections=s.reviewStatements(batches,decisions)
    val projection=projections.firstOrNull{it.batch.id==batchId}?:error("核对资料不存在 / 照合資料がありません")
    val row=projection.file.rows.firstOrNull{it.row_id==rowId}?:error("流水行不存在 / 明細行がありません")
    val posting=s.reconciliationPostings().firstOrNull{it.postingId==postingId}?:error("资金端已变化 / 資金側の記録が変わりました")
    require(posting.accountId==projection.file.account_id&&posting.direction==row.direction&&posting.currency==row.settlement.currency){"账户、方向或币种不一致 / 口座・方向・通貨が一致しません"}
    val occupied=projections.flatMap{p->p.result.exact.filterNot{p.batch.id==batchId&&it.rowId==rowId}.map{it.postingId}+
        p.decisions.filter{it.active&&!(p.batch.id==batchId&&it.saved.rowId==rowId)}.map{it.saved.postingId}}
    require(postingId !in occupied){"资金端已被其他流水占用 / 資金側の記録は別の明細で使用中です"}
    require(decisions.none{it.postingId==postingId&&(it.batchId!=batchId||it.rowId!=rowId)}){"请先解除其他流水保留的旧关联 / 別明細に残る旧関連を先に解除してください"}
    return StatementDifferenceReview(bookId,batchId,row,posting.reviewFact(),decisions.find{it.batchId==batchId&&it.rowId==rowId},note.trim())
}
suspend fun LedgerRepository.acceptStatementDifference(review:StatementDifferenceReview)=db.withTransaction {
    val fresh=previewStatementDifference(review.bookId,review.batchId,review.row.row_id,review.posting.postingId,review.note)
    require(fresh==review){"预览后资料已变化，请重新检查 / プレビュー後に情報が変わりました。再確認してください"}
    db.fileWorkflowDao().putDecision(StatementDecisionEntity(review.bookId,review.batchId,review.row.row_id,
        review.posting.postingId,FileProtocols.json.encodeToString(review.posting),review.note,clock.millis()))
}
suspend fun LedgerRepository.clearStatementDecision(bookId:String,batchId:String,rowId:String)=db.withTransaction {
    require(dao.book(bookId)!=null)
    db.fileWorkflowDao().clearDecision(bookId,batchId,rowId)
}
