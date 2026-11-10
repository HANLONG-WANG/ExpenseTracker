package com.yohaku.expensetracker.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_39_40 = object:Migration(39,40) {
    override fun migrate(db:SupportSQLiteDatabase) {
        createMerchantUpgradeStructure(db)
        organizeMerchantHistoryV40(db)
    }
}

private fun createMerchantUpgradeStructure(db:SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE `merchant_operations` (`bookId` TEXT NOT NULL, `id` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `type` TEXT NOT NULL, `source` TEXT NOT NULL, `sourceMerchantId` TEXT, `sourceMerchantName` TEXT, `targetMerchantId` TEXT, `targetMerchantName` TEXT, `originalName` TEXT, `sourceBefore_name` TEXT, `sourceBefore_note` TEXT, `sourceBefore_active` INTEGER, `sourceAfter_name` TEXT, `sourceAfter_note` TEXT, `sourceAfter_active` INTEGER, `targetBefore_name` TEXT, `targetBefore_note` TEXT, `targetBefore_active` INTEGER, `targetAfter_name` TEXT, `targetAfter_note` TEXT, `targetAfter_active` INTEGER, `count_affectedRecords` INTEGER NOT NULL, `count_newlyLinkedRecords` INTEGER NOT NULL, `count_renamedRecords` INTEGER NOT NULL, `count_trashedRecords` INTEGER NOT NULL, `count_linkedRefunds` INTEGER NOT NULL, `count_templates` INTEGER NOT NULL, `count_recurrenceRules` INTEGER NOT NULL, `count_savedLocations` INTEGER NOT NULL, `count_pendingPlaces` INTEGER NOT NULL, `count_drafts` INTEGER NOT NULL, `cloudPlanId` TEXT, `cloudExecutionId` TEXT, `cloudCommandId` TEXT, `undoOfOperationId` TEXT, `undoneByOperationId` TEXT, `undoneAt` INTEGER, PRIMARY KEY(`bookId`, `id`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
    db.execSQL("CREATE INDEX `index_merchant_operations_bookId_timestamp_id` ON `merchant_operations` (`bookId`, `timestamp`, `id`)")
    db.execSQL("CREATE INDEX `index_merchant_operations_bookId_sourceMerchantId` ON `merchant_operations` (`bookId`, `sourceMerchantId`)")
    db.execSQL("CREATE INDEX `index_merchant_operations_bookId_targetMerchantId` ON `merchant_operations` (`bookId`, `targetMerchantId`)")
    db.execSQL("CREATE INDEX `index_merchant_operations_bookId_cloudExecutionId` ON `merchant_operations` (`bookId`, `cloudExecutionId`)")
    db.execSQL("CREATE TABLE `merchant_operation_impacts` (`bookId` TEXT NOT NULL, `operationId` TEXT NOT NULL, `objectType` TEXT NOT NULL, `objectId` TEXT NOT NULL, `oldMerchantName` TEXT, `newMerchantName` TEXT, `oldMerchantId` TEXT, `newMerchantId` TEXT, PRIMARY KEY(`bookId`, `operationId`, `objectType`, `objectId`), FOREIGN KEY(`bookId`, `operationId`) REFERENCES `merchant_operations`(`bookId`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
    db.execSQL("CREATE INDEX `index_merchant_operation_impacts_objectType_objectId` ON `merchant_operation_impacts` (`objectType`, `objectId`)")
    db.execSQL("CREATE INDEX `index_merchant_operation_impacts_bookId_objectType_objectId` ON `merchant_operation_impacts` (`bookId`, `objectType`, `objectId`)")
    db.execSQL("ALTER TABLE csv_batch_events ADD COLUMN sourceMatchKey TEXT DEFAULT NULL")
}

/** Batch E adds frozen migration-only normalization and data cleanup here, after structure SQL.
 * Room/prepareRestore run both steps in the same upgrade transaction. Batch A changes no data.
 */
@Suppress("UNUSED_PARAMETER")
private fun organizeMerchantHistoryV40(db:SupportSQLiteDatabase) = Unit
