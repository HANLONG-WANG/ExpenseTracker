package com.yohaku.expensetracker.data.db

val MIGRATION_25_26=object:androidx.room.migration.Migration(25,26) {
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE books ADD COLUMN deletedAt INTEGER DEFAULT NULL")
    }
}
