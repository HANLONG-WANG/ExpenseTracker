package com.yohaku.expensetracker.data.db

val MIGRATION_27_28=object:androidx.room.migration.Migration(27,28) {
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN deletionGroupId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE events ADD COLUMN purgedAt INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE statement_decisions ADD COLUMN invalidatedAt INTEGER DEFAULT NULL")
    }
}
