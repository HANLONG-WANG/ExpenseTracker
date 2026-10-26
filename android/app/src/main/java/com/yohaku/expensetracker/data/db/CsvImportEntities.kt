package com.yohaku.expensetracker.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="csv_batches",primaryKeys=["bookId","id"],foreignKeys=[ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"],onDelete=ForeignKey.CASCADE)])
data class CsvBatchEntity(val bookId:String,val id:String,val fileName:String,val sha256:String,val createdAt:Long,val rowCount:Int,val importedCount:Int,val skippedCount:Int,val accountId:String,val undoGroupId:String?=null)
@Entity(tableName="csv_batch_events",primaryKeys=["bookId","eventId"],foreignKeys=[
    ForeignKey(entity=CsvBatchEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","batchId"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=EventEntity::class,parentColumns=["bookId","id"],childColumns=["bookId","eventId"],onDelete=ForeignKey.CASCADE)
],indices=[Index(value=["bookId","batchId"])])
data class CsvBatchEventEntity(val bookId:String,val eventId:String,val batchId:String)
@Entity(tableName="csv_profiles",primaryKeys=["bookId","signature"],foreignKeys=[ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"],onDelete=ForeignKey.CASCADE)])
data class CsvProfileEntity(val bookId:String,val signature:String,val settingsJson:String)
@Dao interface CsvImportDao {
    @Query("SELECT * FROM csv_batches WHERE bookId=:bookId ORDER BY createdAt DESC") fun batches(bookId:String):Flow<List<CsvBatchEntity>>
    @Query("SELECT * FROM csv_batches WHERE bookId=:bookId AND id=:id") suspend fun batch(bookId:String,id:String):CsvBatchEntity?
    @Upsert suspend fun putBatch(value:CsvBatchEntity)
    @Insert suspend fun putEvents(values:List<CsvBatchEventEntity>)
    @Query("SELECT e.* FROM events e JOIN csv_batch_events c ON c.bookId=e.bookId AND c.eventId=e.id WHERE c.bookId=:bookId AND c.batchId=:batchId AND e.purgedAt IS NULL ORDER BY e.date,e.createdAt") suspend fun events(bookId:String,batchId:String):List<EventEntity>
    @Query("SELECT * FROM csv_profiles WHERE bookId=:bookId AND signature=:signature") suspend fun profile(bookId:String,signature:String):CsvProfileEntity?
    @Upsert suspend fun putProfile(value:CsvProfileEntity)
}
val MIGRATION_29_30=object:androidx.room.migration.Migration(29,30) {
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `csv_batches` (`bookId` TEXT NOT NULL, `id` TEXT NOT NULL, `fileName` TEXT NOT NULL, `sha256` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `rowCount` INTEGER NOT NULL, `importedCount` INTEGER NOT NULL, `skippedCount` INTEGER NOT NULL, `accountId` TEXT NOT NULL, `undoGroupId` TEXT, PRIMARY KEY(`bookId`,`id`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `csv_batch_events` (`bookId` TEXT NOT NULL, `eventId` TEXT NOT NULL, `batchId` TEXT NOT NULL, PRIMARY KEY(`bookId`,`eventId`), FOREIGN KEY(`bookId`,`batchId`) REFERENCES `csv_batches`(`bookId`,`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`bookId`,`eventId`) REFERENCES `events`(`bookId`,`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_csv_batch_events_bookId_batchId` ON `csv_batch_events` (`bookId`,`batchId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `csv_profiles` (`bookId` TEXT NOT NULL, `signature` TEXT NOT NULL, `settingsJson` TEXT NOT NULL, PRIMARY KEY(`bookId`,`signature`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
    }
}
