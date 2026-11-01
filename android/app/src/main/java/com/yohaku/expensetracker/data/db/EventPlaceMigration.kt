package com.yohaku.expensetracker.data.db

val MIGRATION_34_35=object:androidx.room.migration.Migration(34,35) {
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `event_places` (`bookId` TEXT NOT NULL, `eventId` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `accuracyMeters` REAL NOT NULL, PRIMARY KEY(`bookId`,`eventId`), FOREIGN KEY(`bookId`,`eventId`) REFERENCES `events`(`bookId`,`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
    }
}
