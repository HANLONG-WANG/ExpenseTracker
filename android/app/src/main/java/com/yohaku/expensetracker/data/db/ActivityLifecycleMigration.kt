package com.yohaku.expensetracker.data.db

val MIGRATION_26_27=object:androidx.room.migration.Migration(26,27) {
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE activities ADD COLUMN deletedAt INTEGER DEFAULT NULL")
    }
}
