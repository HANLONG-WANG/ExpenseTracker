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
    @Query("""SELECT books.*,
        (SELECT COUNT(*) FROM accounts WHERE accounts.bookId=books.id) AS accountCount,
        (SELECT COUNT(*) FROM events WHERE events.bookId=books.id) AS recordCount,
        CASE WHEN EXISTS(SELECT 1 FROM budgets b WHERE b.bookId=books.id AND b.scope='' AND b.month<=:month)
        THEN (SELECT b.amountMinor FROM budgets b WHERE b.bookId=books.id AND b.scope='' AND b.month<=:month ORDER BY b.month DESC LIMIT 1)
        ELSE CASE WHEN substr(books.startDate,1,7)<=:month THEN books.budgetMinor ELSE NULL END END AS monthlyBudgetMinor,
        (SELECT COUNT(*) FROM budgets b WHERE b.bookId=books.id AND b.amountMinor IS NOT NULL)+(SELECT COUNT(*) FROM recurrences r WHERE r.bookId=books.id)+(SELECT COUNT(*) FROM activities a WHERE a.bookId=books.id) AS planningCount
        FROM books ORDER BY books.rowid""")
    fun bookOverviews(month:String=java.time.YearMonth.now().toString()):Flow<List<BookOverview>>
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
    @Query("SELECT EXISTS(SELECT 1 FROM events WHERE bookId=:bookId AND accountId=:accountId) OR EXISTS(SELECT 1 FROM postings WHERE bookId=:bookId AND accountId=:accountId) OR EXISTS(SELECT 1 FROM recurrences WHERE bookId=:bookId AND accountId=:accountId) OR EXISTS(SELECT 1 FROM occurrences WHERE bookId=:bookId AND accountId=:accountId)") suspend fun hasAccountHistory(bookId:String,accountId:String):Boolean
    @Upsert suspend fun putEvent(event:EventEntity)
    @Query("DELETE FROM postings WHERE bookId=:bookId AND eventId=:eventId") suspend fun clearPostings(bookId:String,eventId:String)
    @Insert suspend fun putPostings(postings:List<PostingEntity>)
    @Query("SELECT p.* FROM postings p JOIN events e ON e.bookId=p.bookId AND e.id=p.eventId WHERE p.bookId=:bookId AND e.deletedAt IS NULL AND p.confirmed=1") fun postings(bookId:String):Flow<List<PostingEntity>>
    @Upsert suspend fun putSettlement(settlement:SettlementEntity)
    @Query("SELECT * FROM settlements WHERE bookId=:bookId AND eventId=:eventId") suspend fun settlement(bookId:String,eventId:String):SettlementEntity?
    @Query("SELECT e.* FROM events e JOIN settlements s ON s.bookId=e.bookId AND s.eventId=e.id WHERE s.bookId=:bookId AND s.originalEventId=:eventId") suspend fun refunds(bookId:String,eventId:String):List<EventEntity>
    @Upsert suspend fun putTransfer(transfer:TransferEntity)
    @Query("SELECT * FROM transfers WHERE bookId=:bookId AND eventId=:eventId") suspend fun transfer(bookId:String,eventId:String):TransferEntity?
    @Query("SELECT * FROM expense_components WHERE bookId=:bookId AND eventId=:eventId") suspend fun components(bookId:String,eventId:String):List<ExpenseComponentEntity>
    @Query("DELETE FROM expense_components WHERE bookId=:bookId AND eventId=:eventId") suspend fun clearComponents(bookId:String,eventId:String)
    @Insert suspend fun putComponents(components:List<ExpenseComponentEntity>)
    @Upsert suspend fun putBudget(budget:BudgetEntity)
    @Query("DELETE FROM budgets WHERE bookId=:bookId") suspend fun clearBudgets(bookId:String)
    @Query("SELECT * FROM budgets WHERE bookId=:bookId") suspend fun budgets(bookId:String):List<BudgetEntity>
    @Upsert suspend fun putActivity(activity:ActivityEntity)
    @Query("SELECT COUNT(*) FROM activities WHERE bookId=:bookId") suspend fun activityCount(bookId:String):Int
    @Query("SELECT * FROM activities WHERE bookId=:bookId AND id=:id") suspend fun activity(bookId:String,id:String):ActivityEntity?
    @Upsert suspend fun putFeatures(features:BookFeaturesEntity)
    @Query("SELECT * FROM book_features WHERE bookId=:bookId") suspend fun features(bookId:String):BookFeaturesEntity?
    @Upsert suspend fun putEventActivity(link:EventActivityEntity)
    @Query("SELECT * FROM event_activities WHERE bookId=:bookId AND eventId=:eventId") suspend fun eventActivity(bookId:String,eventId:String):EventActivityEntity?
    @Query("DELETE FROM event_activities WHERE bookId=:bookId AND eventId=:eventId") suspend fun clearEventActivity(bookId:String,eventId:String)
    @Upsert suspend fun putRecurrence(recurrence:RecurrenceEntity)
    @Query("SELECT * FROM recurrences WHERE bookId=:bookId AND id=:id") suspend fun recurrence(bookId:String,id:String):RecurrenceEntity?
    @Query("SELECT * FROM recurrences WHERE bookId=:bookId") suspend fun recurrences(bookId:String):List<RecurrenceEntity>
    @Upsert suspend fun putOccurrence(occurrence:OccurrenceEntity)
    @Query("SELECT * FROM occurrences WHERE bookId=:bookId AND id=:id") suspend fun occurrence(bookId:String,id:String):OccurrenceEntity?
    @Query("SELECT * FROM occurrences WHERE bookId=:bookId") suspend fun occurrences(bookId:String):List<OccurrenceEntity>
    @Query("SELECT * FROM occurrences WHERE bookId=:bookId AND eventId=:eventId") suspend fun eventOccurrences(bookId:String,eventId:String):List<OccurrenceEntity>
    @Upsert suspend fun putActivityBudget(budget:ActivityBudgetEntity)
    @Query("DELETE FROM activity_budgets WHERE bookId=:bookId AND activityId=:activityId") suspend fun clearActivityBudgets(bookId:String,activityId:String)
    @Query("SELECT * FROM activity_budgets WHERE bookId=:bookId AND activityId=:activityId") suspend fun activityBudgets(bookId:String,activityId:String):List<ActivityBudgetEntity>
    @Upsert suspend fun putReserve(reserve:ActivityReserveEntity)
    @Query("SELECT * FROM activity_reserves WHERE bookId=:bookId AND id=:id") suspend fun reserve(bookId:String,id:String):ActivityReserveEntity?
    @Query("SELECT * FROM activity_reserves WHERE bookId=:bookId") suspend fun reserves(bookId:String):List<ActivityReserveEntity>
    @Query("DELETE FROM reserve_uses WHERE bookId=:bookId AND eventId=:eventId") suspend fun clearReserveUses(bookId:String,eventId:String)
    @Insert suspend fun putReserveUses(uses:List<ReserveUseEntity>)
    @Query("SELECT * FROM reserve_uses WHERE bookId=:bookId AND eventId=:eventId") suspend fun reserveUses(bookId:String,eventId:String):List<ReserveUseEntity>
    @Upsert suspend fun putFriend(friend:FriendEntity)
    @Query("SELECT * FROM friends WHERE bookId=:bookId AND id=:id") suspend fun friend(bookId:String,id:String):FriendEntity?
    @Upsert suspend fun putParticipant(participant:ActivityParticipantEntity)
    @Query("SELECT * FROM activity_participants WHERE bookId=:bookId AND activityId=:activityId ORDER BY position") suspend fun participants(bookId:String,activityId:String):List<ActivityParticipantEntity>
    @Upsert suspend fun putTreat(treat:TreatEntity)
    @Query("SELECT * FROM treats WHERE bookId=:bookId AND id=:id") suspend fun treat(bookId:String,id:String):TreatEntity?
    @Query("SELECT * FROM treats WHERE bookId=:bookId AND eventId=:eventId") suspend fun eventTreat(bookId:String,eventId:String):TreatEntity?
    @Query("DELETE FROM treats WHERE bookId=:bookId AND eventId=:eventId") suspend fun clearEventTreat(bookId:String,eventId:String)
    @Query("DELETE FROM treat_allocations WHERE bookId=:bookId AND treatId=:treatId") suspend fun clearAllocations(bookId:String,treatId:String)
    @Insert suspend fun putAllocations(allocations:List<TreatAllocationEntity>)
    @Query("SELECT * FROM treat_allocations WHERE bookId=:bookId AND treatId=:treatId") suspend fun allocations(bookId:String,treatId:String):List<TreatAllocationEntity>
    @Upsert suspend fun putAnalysisPreferences(preferences:AnalysisPreferencesEntity)
    @Upsert suspend fun putMerchant(merchant:MerchantEntity)
    @Query("SELECT * FROM merchants WHERE bookId=:bookId AND id=:id") suspend fun merchant(bookId:String,id:String):MerchantEntity?
    @Upsert suspend fun putMerchantLocation(location:MerchantLocationEntity)
    @Query("SELECT * FROM merchant_locations WHERE bookId=:bookId AND id=:id") suspend fun merchantLocation(bookId:String,id:String):MerchantLocationEntity?
    @Query("DELETE FROM merchant_locations WHERE bookId=:bookId AND id=:id") suspend fun removeMerchantLocation(bookId:String,id:String)
    @Upsert suspend fun putEventMerchant(link:EventMerchantEntity)
    @Query("DELETE FROM event_merchants WHERE bookId=:bookId AND eventId=:eventId") suspend fun clearEventMerchant(bookId:String,eventId:String)
    @Query("SELECT * FROM event_merchants WHERE bookId=:bookId AND eventId=:eventId") suspend fun eventMerchant(bookId:String,eventId:String):EventMerchantEntity?
    @Insert suspend fun putFile(file:FileBlobEntity)
    @Query("SELECT * FROM file_blobs WHERE bookId=:bookId AND id=:id") suspend fun file(bookId:String,id:String):FileBlobEntity?
    @Query("SELECT * FROM file_blobs") suspend fun allFiles():List<FileBlobEntity>
    @Insert suspend fun stageMedia(media:DraftMediaEntity)
    @Query("SELECT * FROM draft_media WHERE bookId=:bookId AND sessionId=:sessionId") suspend fun stagedMedia(bookId:String,sessionId:String):List<DraftMediaEntity>
    @Query("DELETE FROM draft_media WHERE bookId=:bookId AND sessionId=:sessionId") suspend fun clearStagedMedia(bookId:String,sessionId:String)
    @Query("SELECT * FROM drafts WHERE bookId=:bookId") suspend fun storedDraft(bookId:String):DraftEntity?
    @Insert suspend fun putAttachments(links:List<AttachmentLinkEntity>)
    @Query("SELECT * FROM attachment_links WHERE bookId=:bookId AND eventId=:eventId ORDER BY rowid") suspend fun attachments(bookId:String,eventId:String):List<AttachmentLinkEntity>
    @Query("DELETE FROM attachment_links WHERE bookId=:bookId AND eventId=:eventId") suspend fun clearAttachments(bookId:String,eventId:String)
    @Upsert suspend fun putReceipt(receipt:ReceiptEntity)
    @Query("SELECT * FROM receipts WHERE bookId=:bookId AND eventId=:eventId") suspend fun receipt(bookId:String,eventId:String):ReceiptEntity?
    @Insert suspend fun putReceiptImages(images:List<ReceiptImageEntity>)
    @Query("SELECT * FROM receipt_images WHERE bookId=:bookId AND receiptId=:receiptId AND revision=:revision ORDER BY position") suspend fun receiptImages(bookId:String,receiptId:String,revision:Int):List<ReceiptImageEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM attachment_links WHERE bookId=:bookId AND fileId=:fileId) OR EXISTS(SELECT 1 FROM receipt_images WHERE bookId=:bookId AND fileId=:fileId) OR EXISTS(SELECT 1 FROM draft_media WHERE bookId=:bookId AND fileId=:fileId)") suspend fun fileReferenced(bookId:String,fileId:String):Boolean
    @Query("DELETE FROM file_blobs WHERE bookId=:bookId AND id=:fileId") suspend fun deleteFile(bookId:String,fileId:String)
    @Insert suspend fun putRevision(revision:RevisionEntity)
    @Query("SELECT * FROM revisions WHERE bookId=:bookId AND eventId=:id ORDER BY version DESC") fun revisions(bookId:String,id:String):Flow<List<RevisionEntity>>
    @Query("SELECT * FROM drafts WHERE bookId=:bookId") fun draft(bookId:String):Flow<DraftEntity?>
    @Upsert suspend fun putDraft(draft:DraftEntity)
    @Query("DELETE FROM drafts WHERE bookId=:bookId") suspend fun clearDraft(bookId:String)
    @Query("DELETE FROM events WHERE deletedAt IS NOT NULL AND deletedAt<=:cutoff AND kind='REFUND'") suspend fun purgeUnlinkedRefunds(cutoff:Long)
    @Query("DELETE FROM events WHERE deletedAt IS NOT NULL AND deletedAt<=:cutoff AND NOT EXISTS (SELECT 1 FROM settlements s WHERE s.bookId=events.bookId AND s.originalEventId=events.id) AND NOT EXISTS (SELECT 1 FROM occurrences o WHERE o.bookId=events.bookId AND o.eventId=events.id)") suspend fun purgeUnlinked(cutoff:Long)
}
@Database(entities=[BookEntity::class,AccountEntity::class,CategoryEntity::class,EventEntity::class,PostingEntity::class,RevisionEntity::class,DraftEntity::class,FileBlobEntity::class,AttachmentLinkEntity::class,SettlementEntity::class,TransferEntity::class,ExpenseComponentEntity::class,BudgetEntity::class,ActivityEntity::class,BookFeaturesEntity::class,EventActivityEntity::class,RecurrenceEntity::class,OccurrenceEntity::class,ActivityBudgetEntity::class,ActivityReserveEntity::class,ReserveUseEntity::class,FriendEntity::class,ActivityParticipantEntity::class,TreatEntity::class,TreatAllocationEntity::class,AnalysisPreferencesEntity::class,MerchantEntity::class,MerchantLocationEntity::class,EventMerchantEntity::class,DraftMediaEntity::class,ReceiptEntity::class,ReceiptImageEntity::class],version=11,exportSchema=true)
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
    @Relation(parentColumn="id",entityColumn="bookId") val drafts:List<DraftEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val settlements:List<SettlementEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val transfers:List<TransferEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val expenseComponents:List<ExpenseComponentEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val budgets:List<BudgetEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val activities:List<ActivityEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val features:List<BookFeaturesEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val eventActivities:List<EventActivityEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val recurrences:List<RecurrenceEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val occurrences:List<OccurrenceEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val activityBudgets:List<ActivityBudgetEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val reserves:List<ActivityReserveEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val reserveUses:List<ReserveUseEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val friends:List<FriendEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val participants:List<ActivityParticipantEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val treats:List<TreatEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val allocations:List<TreatAllocationEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val analysisPreferences:List<AnalysisPreferencesEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val merchants:List<MerchantEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val merchantLocations:List<MerchantLocationEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val eventMerchants:List<EventMerchantEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val files:List<FileBlobEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val attachments:List<AttachmentLinkEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val stagedMedia:List<DraftMediaEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val receipts:List<ReceiptEntity>,
    @Relation(parentColumn="id",entityColumn="bookId") val receiptImages:List<ReceiptImageEntity>
)

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""UPDATE events SET createdAt=COALESCE(
            (SELECT MIN(timestamp) FROM revisions r WHERE r.bookId=events.bookId AND r.eventId=events.id AND r.version=1),
            rowid)""")
    }
}

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS settlements (
            bookId TEXT NOT NULL, eventId TEXT NOT NULL, accountMinor INTEGER NOT NULL,
            accountCurrency TEXT NOT NULL, confirmed INTEGER NOT NULL, settlementDate TEXT NOT NULL,
            rateSource TEXT NOT NULL, rateDate TEXT NOT NULL, expectedAccountMinor INTEGER NOT NULL,
            expectedBaseMinor INTEGER NOT NULL, originalEventId TEXT,
            PRIMARY KEY(bookId,eventId),
            FOREIGN KEY(bookId,eventId) REFERENCES events(bookId,id) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(bookId,originalEventId) REFERENCES events(bookId,id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_settlements_bookId_originalEventId ON settlements(bookId,originalEventId)")
    }
}

val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE settlements ADD COLUMN manualRate TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE settlements ADD COLUMN expectedAccountCurrency TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE settlements SET expectedAccountCurrency=accountCurrency")
    }
}

val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS transfers (bookId TEXT NOT NULL,eventId TEXT NOT NULL,toAccountId TEXT NOT NULL,toMinor INTEGER NOT NULL,fromCurrency TEXT NOT NULL,toCurrency TEXT NOT NULL,fromDate TEXT NOT NULL,toDate TEXT NOT NULL,fromConfirmed INTEGER NOT NULL,toConfirmed INTEGER NOT NULL,expectedToMinor INTEGER NOT NULL,expectedToCurrency TEXT NOT NULL,manualRate TEXT NOT NULL,rateDate TEXT NOT NULL,PRIMARY KEY(bookId,eventId),FOREIGN KEY(bookId,eventId) REFERENCES events(bookId,id) ON UPDATE NO ACTION ON DELETE CASCADE,FOREIGN KEY(bookId,toAccountId) REFERENCES accounts(bookId,id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transfers_bookId_toAccountId ON transfers(bookId,toAccountId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS expense_components (bookId TEXT NOT NULL,id TEXT NOT NULL,eventId TEXT NOT NULL,accountId TEXT NOT NULL,amountMinor INTEGER NOT NULL,currency TEXT NOT NULL,baseMinor INTEGER NOT NULL,date TEXT NOT NULL,confirmed INTEGER NOT NULL,position TEXT NOT NULL,originalMinor INTEGER NOT NULL,originalCurrency TEXT NOT NULL,PRIMARY KEY(bookId,id),FOREIGN KEY(bookId,eventId) REFERENCES events(bookId,id) ON UPDATE NO ACTION ON DELETE CASCADE,FOREIGN KEY(bookId,accountId) REFERENCES accounts(bookId,id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_expense_components_bookId_eventId ON expense_components(bookId,eventId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_expense_components_bookId_accountId ON expense_components(bookId,accountId)")
        db.execSQL("ALTER TABLE revisions ADD COLUMN beforeDetailsJson TEXT")
        db.execSQL("ALTER TABLE revisions ADD COLUMN afterDetailsJson TEXT")
    }
}

val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `budgets` (`bookId` TEXT NOT NULL, `month` TEXT NOT NULL, `scope` TEXT NOT NULL, `categoryId` TEXT, `amountMinor` INTEGER, PRIMARY KEY(`bookId`, `month`, `scope`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`bookId`, `categoryId`) REFERENCES `categories`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION )""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_budgets_bookId_categoryId` ON `budgets` (`bookId`, `categoryId`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `activities` (`bookId` TEXT NOT NULL, `id` TEXT NOT NULL, `name` TEXT NOT NULL, `startDate` TEXT NOT NULL, `endDate` TEXT NOT NULL, `budgetMinor` INTEGER, `excludeMonthly` INTEGER NOT NULL, `ended` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `id`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_activities_bookId` ON `activities` (`bookId`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `book_features` (`bookId` TEXT NOT NULL, `activityMode` INTEGER NOT NULL, `activeActivityId` TEXT, PRIMARY KEY(`bookId`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`bookId`, `activeActivityId`) REFERENCES `activities`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION )""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_book_features_bookId_activeActivityId` ON `book_features` (`bookId`, `activeActivityId`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `event_activities` (`bookId` TEXT NOT NULL, `eventId` TEXT NOT NULL, `activityId` TEXT NOT NULL, PRIMARY KEY(`bookId`, `eventId`), FOREIGN KEY(`bookId`, `eventId`) REFERENCES `events`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`bookId`, `activityId`) REFERENCES `activities`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION )""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_event_activities_bookId_activityId` ON `event_activities` (`bookId`, `activityId`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `recurrences` (`bookId` TEXT NOT NULL, `id` TEXT NOT NULL, `name` TEXT NOT NULL, `accountId` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `currency` TEXT NOT NULL, `amountMinor` INTEGER, `baseMinor` INTEGER, `estimated` INTEGER NOT NULL, `frequency` TEXT NOT NULL, `anchorDate` TEXT NOT NULL, `endDate` TEXT, `active` INTEGER NOT NULL, `activityId` TEXT, PRIMARY KEY(`bookId`, `id`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`bookId`, `accountId`) REFERENCES `accounts`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`bookId`, `categoryId`) REFERENCES `categories`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`bookId`, `activityId`) REFERENCES `activities`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION )""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_recurrences_bookId_accountId` ON `recurrences` (`bookId`, `accountId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_recurrences_bookId_categoryId` ON `recurrences` (`bookId`, `categoryId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_recurrences_bookId_activityId` ON `recurrences` (`bookId`, `activityId`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `occurrences` (`bookId` TEXT NOT NULL, `id` TEXT NOT NULL, `recurrenceId` TEXT NOT NULL, `scheduledDate` TEXT NOT NULL, `name` TEXT NOT NULL, `accountId` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `currency` TEXT NOT NULL, `amountMinor` INTEGER, `baseMinor` INTEGER, `estimated` INTEGER NOT NULL, `status` TEXT NOT NULL, `eventId` TEXT, `activityId` TEXT, PRIMARY KEY(`bookId`, `id`), FOREIGN KEY(`bookId`, `recurrenceId`) REFERENCES `recurrences`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`bookId`, `eventId`) REFERENCES `events`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`bookId`, `accountId`) REFERENCES `accounts`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`bookId`, `categoryId`) REFERENCES `categories`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`bookId`, `activityId`) REFERENCES `activities`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION )""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_occurrences_bookId_recurrenceId` ON `occurrences` (`bookId`, `recurrenceId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_occurrences_bookId_eventId` ON `occurrences` (`bookId`, `eventId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_occurrences_bookId_accountId` ON `occurrences` (`bookId`, `accountId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_occurrences_bookId_categoryId` ON `occurrences` (`bookId`, `categoryId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_occurrences_bookId_activityId` ON `occurrences` (`bookId`, `activityId`)""")
        db.execSQL("INSERT INTO budgets (bookId,month,scope,categoryId,amountMinor) SELECT id,substr(startDate,1,7),'',NULL,budgetMinor FROM books WHERE budgetMinor IS NOT NULL")
    }
}

val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `activity_budgets` (`bookId` TEXT NOT NULL, `activityId` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `activityId`, `categoryId`), FOREIGN KEY(`bookId`, `activityId`) REFERENCES `activities`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`bookId`, `categoryId`) REFERENCES `categories`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_budgets_bookId_categoryId` ON `activity_budgets` (`bookId`, `categoryId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `activity_reserves` (`bookId` TEXT NOT NULL, `id` TEXT NOT NULL, `activityId` TEXT NOT NULL, `name` TEXT NOT NULL, `date` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `accountId` TEXT, `accountMinor` INTEGER, `accountCurrency` TEXT, `occurrenceId` TEXT, `releasedMinor` INTEGER NOT NULL, `active` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `id`), FOREIGN KEY(`bookId`, `activityId`) REFERENCES `activities`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`bookId`, `accountId`) REFERENCES `accounts`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`bookId`, `occurrenceId`) REFERENCES `occurrences`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_reserves_bookId_activityId` ON `activity_reserves` (`bookId`, `activityId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_reserves_bookId_accountId` ON `activity_reserves` (`bookId`, `accountId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_activity_reserves_bookId_occurrenceId` ON `activity_reserves` (`bookId`, `occurrenceId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `reserve_uses` (`bookId` TEXT NOT NULL, `eventId` TEXT NOT NULL, `reserveId` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `eventId`, `reserveId`), FOREIGN KEY(`bookId`, `eventId`) REFERENCES `events`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`bookId`, `reserveId`) REFERENCES `activity_reserves`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reserve_uses_bookId_reserveId` ON `reserve_uses` (`bookId`, `reserveId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `friends` (`bookId` TEXT NOT NULL, `id` TEXT NOT NULL, `name` TEXT NOT NULL, `active` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `id`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_friends_bookId` ON `friends` (`bookId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `activity_participants` (`bookId` TEXT NOT NULL, `activityId` TEXT NOT NULL, `friendId` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `activityId`, `friendId`), FOREIGN KEY(`bookId`, `activityId`) REFERENCES `activities`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`bookId`, `friendId`) REFERENCES `friends`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_participants_bookId_friendId` ON `activity_participants` (`bookId`, `friendId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `treats` (`bookId` TEXT NOT NULL, `id` TEXT NOT NULL, `activityId` TEXT NOT NULL, `eventId` TEXT, `payerFriendId` TEXT, `amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `baseMinor` INTEGER NOT NULL, `date` TEXT NOT NULL, `name` TEXT NOT NULL, `mode` TEXT NOT NULL, `weightsJson` TEXT NOT NULL, `revision` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`bookId`, `id`), FOREIGN KEY(`bookId`, `activityId`) REFERENCES `activities`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`bookId`, `eventId`) REFERENCES `events`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`bookId`, `payerFriendId`) REFERENCES `friends`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_treats_bookId_activityId` ON `treats` (`bookId`, `activityId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_treats_bookId_eventId` ON `treats` (`bookId`, `eventId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_treats_bookId_payerFriendId` ON `treats` (`bookId`, `payerFriendId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `treat_allocations` (`bookId` TEXT NOT NULL, `treatId` TEXT NOT NULL, `participant` TEXT NOT NULL, `friendId` TEXT, `amountMinor` INTEGER NOT NULL, `baseMinor` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `treatId`, `participant`), FOREIGN KEY(`bookId`, `treatId`) REFERENCES `treats`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`bookId`, `friendId`) REFERENCES `friends`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_treat_allocations_bookId_friendId` ON `treat_allocations` (`bookId`, `friendId`)")
    }
}

val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `analysis_preferences` (`bookId` TEXT NOT NULL, `modulesJson` TEXT NOT NULL, `pinnedJson` TEXT NOT NULL, PRIMARY KEY(`bookId`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE TABLE IF NOT EXISTS `merchants` (`bookId` TEXT NOT NULL, `id` TEXT NOT NULL, `name` TEXT NOT NULL, `note` TEXT NOT NULL, `active` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `id`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_merchants_bookId` ON `merchants` (`bookId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `merchant_locations` (`bookId` TEXT NOT NULL, `id` TEXT NOT NULL, `merchantId` TEXT NOT NULL, `name` TEXT NOT NULL, `latitude` REAL, `longitude` REAL, `radiusMeters` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `id`), FOREIGN KEY(`bookId`, `merchantId`) REFERENCES `merchants`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_merchant_locations_bookId_merchantId` ON `merchant_locations` (`bookId`, `merchantId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `event_merchants` (`bookId` TEXT NOT NULL, `eventId` TEXT NOT NULL, `merchantId` TEXT NOT NULL, PRIMARY KEY(`bookId`, `eventId`), FOREIGN KEY(`bookId`, `eventId`) REFERENCES `events`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`bookId`, `merchantId`) REFERENCES `merchants`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_event_merchants_bookId_merchantId` ON `event_merchants` (`bookId`, `merchantId`)")
    }
}

val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `draft_media` (`bookId` TEXT NOT NULL, `fileId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, PRIMARY KEY(`bookId`, `fileId`), FOREIGN KEY(`bookId`, `fileId`) REFERENCES `file_blobs`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_draft_media_bookId_sessionId` ON `draft_media` (`bookId`, `sessionId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `receipts` (`bookId` TEXT NOT NULL, `id` TEXT NOT NULL, `eventId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`bookId`, `id`), FOREIGN KEY(`bookId`, `eventId`) REFERENCES `events`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_receipts_bookId_eventId` ON `receipts` (`bookId`, `eventId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `receipt_images` (`bookId` TEXT NOT NULL, `receiptId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `position` INTEGER NOT NULL, `fileId` TEXT NOT NULL, PRIMARY KEY(`bookId`, `receiptId`, `revision`, `position`), FOREIGN KEY(`bookId`, `receiptId`) REFERENCES `receipts`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`bookId`, `fileId`) REFERENCES `file_blobs`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE NO ACTION )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipt_images_bookId_fileId` ON `receipt_images` (`bookId`, `fileId`)")
    }
}
