package com.yohaku.expensetracker.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface YohakuDao {
    @Transaction
    @Query("SELECT * FROM books WHERE id=:bookId")
    fun graph(bookId:String):Flow<BookGraph?>
    @Query("""SELECT e.categoryId FROM events e
        JOIN categories c ON c.bookId=e.bookId AND c.id=e.categoryId
        LEFT JOIN revisions r ON r.bookId=e.bookId AND r.eventId=e.id
        WHERE e.bookId=:bookId AND e.kind=:kind AND e.deletedAt IS NULL AND c.active=1
        GROUP BY e.categoryId
        ORDER BY MAX(COALESCE(r.timestamp,e.createdAt)) DESC, MAX(e.createdAt) DESC
        LIMIT 5""")
    fun recentCategoryIds(bookId:String,kind:String):Flow<List<String>>
    @Query("SELECT * FROM books ORDER BY rowid") fun books():Flow<List<BookEntity>>
    @Query("SELECT books.*, (SELECT COUNT(*) FROM accounts WHERE accounts.bookId=books.id) AS accountCount, (SELECT COUNT(*) FROM events WHERE events.bookId=books.id) AS recordCount FROM books ORDER BY books.rowid") fun bookOverviews():Flow<List<BookOverview>>
    @Query("SELECT * FROM books WHERE id=:id") suspend fun book(id:String):BookEntity?
    @Upsert suspend fun putBook(book:BookEntity)
    @Query("SELECT * FROM accounts WHERE bookId=:bookId ORDER BY rowid") fun accounts(bookId:String):Flow<List<AccountEntity>>
    @Query("SELECT * FROM accounts WHERE bookId=:bookId AND id=:id") suspend fun account(bookId:String,id:String):AccountEntity?
    @Upsert suspend fun putAccount(account:AccountEntity)
    @Query("SELECT * FROM categories WHERE bookId=:bookId ORDER BY rowid") fun categories(bookId:String):Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories WHERE bookId=:bookId AND id=:id") suspend fun category(bookId:String,id:String):CategoryEntity?
    @Upsert suspend fun putCategory(category:CategoryEntity)
    @Query("SELECT * FROM events WHERE bookId=:bookId ORDER BY date DESC,rowid DESC") fun events(bookId:String):Flow<List<EventEntity>>
    @Query("SELECT * FROM events WHERE bookId=:bookId AND id=:id") suspend fun event(bookId:String,id:String):EventEntity?
    @Query("SELECT COUNT(*) FROM events WHERE bookId=:bookId") suspend fun eventCount(bookId:String):Int
    @Query("SELECT EXISTS(SELECT 1 FROM events WHERE bookId=:bookId AND accountId=:accountId) OR EXISTS(SELECT 1 FROM postings WHERE bookId=:bookId AND accountId=:accountId)") suspend fun hasAccountHistory(bookId:String,accountId:String):Boolean
    @Upsert suspend fun putEvent(event:EventEntity)
    @Query("DELETE FROM postings WHERE bookId=:bookId AND eventId=:eventId") suspend fun clearPostings(bookId:String,eventId:String)
    @Insert suspend fun putPostings(postings:List<PostingEntity>)
    @Query("SELECT p.* FROM postings p JOIN events e ON e.bookId=p.bookId AND e.id=p.eventId WHERE p.bookId=:bookId AND e.deletedAt IS NULL AND p.confirmed=1") fun postings(bookId:String):Flow<List<PostingEntity>>
    @Insert suspend fun putRevision(revision:RevisionEntity)
    @Query("SELECT * FROM revisions WHERE bookId=:bookId AND eventId=:id ORDER BY version DESC") fun revisions(bookId:String,id:String):Flow<List<RevisionEntity>>
    @Query("SELECT * FROM drafts WHERE bookId=:bookId") fun draft(bookId:String):Flow<DraftEntity?>
    @Upsert suspend fun putDraft(draft:DraftEntity)
    @Query("DELETE FROM drafts WHERE bookId=:bookId") suspend fun clearDraft(bookId:String)
    @Query("DELETE FROM events WHERE deletedAt IS NOT NULL AND deletedAt<=:cutoff AND NOT EXISTS (SELECT 1 FROM attachment_links a WHERE a.bookId=events.bookId AND a.eventId=events.id)") suspend fun purgeUnlinked(cutoff:Long)
}
@Database(entities=[BookEntity::class,AccountEntity::class,CategoryEntity::class,EventEntity::class,PostingEntity::class,RevisionEntity::class,DraftEntity::class,FileBlobEntity::class,AttachmentLinkEntity::class],version=3,exportSchema=true)
abstract class YohakuDatabase:RoomDatabase() { abstract fun dao():YohakuDao }

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN setupStep INTEGER NOT NULL DEFAULT 2")
    }
}

/** Read all ledger projections from the same database transaction. */
data class BookGraph(
    @Embedded val book:BookEntity,
    @Relation(parentColumn="id",entityColumn="bookId") val accounts:List<AccountEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val categories:List<CategoryEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val events:List<EventEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val postings:List<PostingEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val drafts:List<DraftEntity>
)

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""UPDATE events SET createdAt=COALESCE(
            (SELECT MIN(timestamp) FROM revisions r WHERE r.bookId=events.bookId AND r.eventId=events.id AND r.version=1),
            rowid)""")
    }
}
