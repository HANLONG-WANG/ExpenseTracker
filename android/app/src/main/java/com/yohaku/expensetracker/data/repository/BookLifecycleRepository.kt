package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.time.Duration

const val RECOVERY_DAYS=30L
val recoveryDuration:Duration=Duration.ofDays(RECOVERY_DAYS)
enum class BookOperation { ARCHIVE,UNARCHIVE,TRASH,RESTORE,PURGE }
data class BookScope(val book:BookEntity,val accounts:Int,val records:Int,val files:Int,val financialPlans:Int,val statements:Int)
data class BookLifecycleReview(val scope:BookScope,val operation:BookOperation)

suspend fun LedgerRepository.bookScope(bookId:String):BookScope {
    val overview=dao.bookOverviews().first().firstOrNull{it.book.id==bookId}?:error("账本不存在 / 帳簿がありません")
    return BookScope(overview.book,overview.accountCount,overview.recordCount,overview.fileCount,overview.financialPlanCount,overview.statementCount)
}
suspend fun LedgerRepository.previewBookOperation(bookId:String,operation:BookOperation):BookLifecycleReview=db.withTransaction {
    val scope=bookScope(bookId);val book=scope.book
    when(operation) {
        BookOperation.ARCHIVE->require(book.deletedAt==null&&!book.archived)
        BookOperation.UNARCHIVE->require(book.deletedAt==null&&book.archived)
        BookOperation.TRASH->require(book.deletedAt==null)
        BookOperation.RESTORE->require(book.deletedAt!=null&&book.deletedAt>clock.millis()-recoveryDuration.toMillis()){"账本已超过恢复期限 / 帳簿の復元期限を過ぎました"}
        BookOperation.PURGE->require(book.deletedAt!=null){"请先将账本移至回收站 / 帳簿を先にごみ箱へ移動してください"}
    }
    BookLifecycleReview(scope,operation)
}

/** The preview and commit compare the same visible scope; other books never participate. */
suspend fun LedgerRepository.commitBookOperation(review:BookLifecycleReview):Boolean=mediaWrites.withLock { db.withTransaction {
    val book=dao.storedBook(review.scope.book.id)?:return@withTransaction false
    if(bookScope(book.id)!=review.scope)return@withTransaction false
    previewBookOperation(book.id,review.operation) // Recheck the recovery deadline at confirmation time.
    when(review.operation) {
        BookOperation.ARCHIVE->dao.putBook(book.copy(archived=true))
        BookOperation.UNARCHIVE->dao.putBook(book.copy(archived=false))
        BookOperation.TRASH->dao.putBook(book.copy(deletedAt=clock.millis()))
        BookOperation.RESTORE->dao.putBook(book.copy(deletedAt=null))
        BookOperation.PURGE->purgeWholeBook(book.id)
    }
    true
} }

/**
 * Remove every table's rows in the selected book in one transaction, including file-workflow and
 * financial evidence. Deferred constraints allow mutually referring ledger/plan rows to go together.
 * Table and column names come only from this app's Room schema, never from imported input.
 */
internal fun LedgerRepository.purgeWholeBook(bookId:String) {
    val sql=db.openHelper.writableDatabase
    check(sql.inTransaction())
    sql.execSQL("PRAGMA defer_foreign_keys=ON")
    val tables=sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use{cursor->buildList{while(cursor.moveToNext())add(cursor.getString(0))}}
    for(table in tables) {
        val scoped=sql.query("PRAGMA table_info(`$table`)").use{cursor->var found=false;while(cursor.moveToNext())if(cursor.getString(1)=="bookId")found=true;found}
        if(scoped)sql.execSQL("DELETE FROM `$table` WHERE bookId=?",arrayOf(bookId))
    }
    sql.execSQL("DELETE FROM books WHERE id=?",arrayOf(bookId))
}

suspend fun LedgerRepository.previewEmptyBookTrash():List<BookLifecycleReview> = db.withTransaction {
    dao.books().first().filter{it.deletedAt!=null}.map{previewBookOperation(it.id,BookOperation.PURGE)}
}
suspend fun LedgerRepository.commitEmptyBookTrash(reviews:List<BookLifecycleReview>):Boolean=mediaWrites.withLock { db.withTransaction {
    if(previewEmptyBookTrash()!=reviews)return@withTransaction false
    reviews.forEach{purgeWholeBook(it.scope.book.id)}
    true
} }
