package com.yohaku.expensetracker.data.db

val MIGRATION_38_39=object:androidx.room.migration.Migration(38,39) {
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN budgetExcluded INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE entry_templates ADD COLUMN budgetExcluded INTEGER NOT NULL DEFAULT 0")
    }
}
