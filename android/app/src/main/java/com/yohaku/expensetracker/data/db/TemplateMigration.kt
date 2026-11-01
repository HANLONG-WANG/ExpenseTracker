package com.yohaku.expensetracker.data.db

val MIGRATION_33_34=object:androidx.room.migration.Migration(33,34) {
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE recurrences ADD COLUMN merchant TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE recurrences ADD COLUMN merchantId TEXT")
        db.execSQL("ALTER TABLE recurrences ADD COLUMN note TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE recurrences ADD COLUMN cardPaymentChoice TEXT")
        db.execSQL("ALTER TABLE recurrences ADD COLUMN cardPaymentPeriods INTEGER")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `entry_templates` (`bookId` TEXT NOT NULL, `id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `currency` TEXT NOT NULL, `amountMinor` INTEGER, `merchant` TEXT NOT NULL, `merchantId` TEXT, `note` TEXT NOT NULL, `cardPaymentChoice` TEXT, `cardPaymentPeriods` INTEGER, `activityId` TEXT, `sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`bookId`,`id`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(`bookId`,`accountId`) REFERENCES `accounts`(`bookId`,`id`) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(`bookId`,`categoryId`) REFERENCES `categories`(`bookId`,`id`) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(`bookId`,`activityId`) REFERENCES `activities`(`bookId`,`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        listOf("accountId","categoryId","activityId").forEach { db.execSQL("CREATE INDEX `index_entry_templates_bookId_${it}` ON `entry_templates` (`bookId`,`$it`)") }
    }
}
