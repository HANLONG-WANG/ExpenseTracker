package com.yohaku.expensetracker.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Exact original UTF-8 document is retained separately from ledger events. */
@Entity(tableName = "statement_batches", primaryKeys = ["bookId", "id"], foreignKeys = [
    ForeignKey(entity = AccountEntity::class, parentColumns = ["bookId", "id"], childColumns = ["bookId", "accountId"])
], indices = [Index(value = ["bookId", "accountId"]), Index(value = ["bookId", "fingerprint"], unique = true)])
data class StatementBatchEntity(val bookId: String, val id: String, val accountId: String,
    val fingerprint: String, val originalJson: String, val createdAt: Long)

@Entity(tableName = "receipt_exports", primaryKeys = ["bookId", "bundleId", "part"], foreignKeys = [
    ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"])
], indices = [Index("bookId")])
data class ReceiptExportEntity(val bookId: String, val bundleId: String, val part: Int,
    val manifestJson: String, val savedAt: Long)

@Entity(tableName = "receipt_item_documents", primaryKeys = ["bookId", "receiptId"], foreignKeys = [
    ForeignKey(entity = ReceiptEntity::class, parentColumns = ["bookId", "id"], childColumns = ["bookId", "receiptId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("bookId")])
data class ReceiptItemDocumentEntity(val bookId: String, val receiptId: String,
    val bundleId: String, val recordJson: String, val revision: Int, val updatedAt: Long)

@Dao
interface FileWorkflowDao {
    @Upsert suspend fun putDecision(decision:StatementDecisionEntity)
    @Query("SELECT * FROM statement_decisions WHERE bookId=:bookId ORDER BY updatedAt,batchId,rowId")
    fun decisions(bookId:String):Flow<List<StatementDecisionEntity>>
    @Query("DELETE FROM statement_decisions WHERE bookId=:bookId AND batchId=:batchId AND rowId=:rowId")
    suspend fun clearDecision(bookId:String,batchId:String,rowId:String)
    @Insert suspend fun insertStatement(batch: StatementBatchEntity)
    @Query("SELECT * FROM statement_batches WHERE bookId=:bookId ORDER BY rowid")
    fun statements(bookId: String): Flow<List<StatementBatchEntity>>
    @Query("SELECT * FROM statement_batches WHERE bookId=:bookId AND id=:id")
    suspend fun statement(bookId: String, id: String): StatementBatchEntity?
    @Query("DELETE FROM statement_batches WHERE bookId=:bookId AND id=:id")
    suspend fun deleteStatement(bookId: String, id: String)
    @Insert suspend fun insertExport(export: ReceiptExportEntity)
    @Query("SELECT * FROM receipt_exports WHERE bookId=:bookId AND bundleId=:bundleId AND part=:part")
    suspend fun exportPart(bookId:String,bundleId:String,part:Int):ReceiptExportEntity?
    @Query("SELECT * FROM receipt_exports WHERE bookId=:bookId ORDER BY savedAt,bundleId,part")
    fun exports(bookId: String): Flow<List<ReceiptExportEntity>>
    @Upsert suspend fun putItems(items: ReceiptItemDocumentEntity)
    @Query("SELECT * FROM receipt_item_documents WHERE bookId=:bookId")
    fun items(bookId: String): Flow<List<ReceiptItemDocumentEntity>>
    @Query("SELECT * FROM receipt_item_documents WHERE bookId=:bookId AND receiptId=:receiptId")
    suspend fun itemsForReceipt(bookId: String, receiptId: String): ReceiptItemDocumentEntity?
}

val MIGRATION_23_24 = object : androidx.room.migration.Migration(23, 24) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS statement_batches (bookId TEXT NOT NULL, id TEXT NOT NULL, accountId TEXT NOT NULL, fingerprint TEXT NOT NULL, originalJson TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(bookId,id), FOREIGN KEY(bookId,accountId) REFERENCES accounts(bookId,id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_statement_batches_bookId_accountId ON statement_batches(bookId,accountId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_statement_batches_bookId_fingerprint ON statement_batches(bookId,fingerprint)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS receipt_exports (bookId TEXT NOT NULL, bundleId TEXT NOT NULL, part INTEGER NOT NULL, manifestJson TEXT NOT NULL, savedAt INTEGER NOT NULL, PRIMARY KEY(bookId,bundleId,part), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_exports_bookId ON receipt_exports(bookId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS receipt_item_documents (bookId TEXT NOT NULL, receiptId TEXT NOT NULL, bundleId TEXT NOT NULL, recordJson TEXT NOT NULL, revision INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(bookId,receiptId), FOREIGN KEY(bookId,receiptId) REFERENCES receipts(bookId,id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_item_documents_bookId ON receipt_item_documents(bookId)")
    }
}

/** Independent review evidence survives posting rewrites; stale facts never count as exact. */
@Entity(tableName="statement_decisions",primaryKeys=["bookId","batchId","rowId"],foreignKeys=[
    ForeignKey(entity=StatementBatchEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","batchId"],onDelete=ForeignKey.CASCADE)
],indices=[Index(value=["bookId","batchId"]),Index(value=["bookId","postingId"],unique=true)])
data class StatementDecisionEntity(val bookId:String,val batchId:String,val rowId:String,val postingId:String,
    val postingJson:String,val note:String,val updatedAt:Long)

val MIGRATION_24_25=object:androidx.room.migration.Migration(24,25) {
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS statement_decisions (bookId TEXT NOT NULL, batchId TEXT NOT NULL, rowId TEXT NOT NULL, postingId TEXT NOT NULL, postingJson TEXT NOT NULL, note TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(bookId,batchId,rowId), FOREIGN KEY(bookId,batchId) REFERENCES statement_batches(bookId,id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_statement_decisions_bookId_batchId ON statement_decisions(bookId,batchId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_statement_decisions_bookId_postingId ON statement_decisions(bookId,postingId)")
    }
}
