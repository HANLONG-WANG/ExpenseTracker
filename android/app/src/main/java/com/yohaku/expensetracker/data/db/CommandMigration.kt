package com.yohaku.expensetracker.data.db

val MIGRATION_35_36=object:androidx.room.migration.Migration(35,36) {
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cloud_tasks ADD COLUMN purpose TEXT NOT NULL DEFAULT 'analysis'")
        db.execSQL("CREATE TABLE IF NOT EXISTS command_plans (planId TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, localId TEXT NOT NULL, origin TEXT NOT NULL, taskId TEXT NOT NULL, resultId TEXT NOT NULL, resultVersion INTEGER NOT NULL, sha256 TEXT NOT NULL, sourceKey TEXT NOT NULL, planJson TEXT NOT NULL, contextDigest TEXT NOT NULL, status TEXT NOT NULL, normalizedJson TEXT, confirmationJson TEXT, executionContextJson TEXT, checksJson TEXT NOT NULL, error TEXT, createdAt INTEGER NOT NULL, executedAt INTEGER, executionJson TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_command_plans_bookId ON command_plans(bookId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_command_plans_sourceKey ON command_plans(sourceKey)")
        db.execSQL("CREATE TABLE IF NOT EXISTS command_effects (id TEXT NOT NULL PRIMARY KEY, planId TEXT NOT NULL, stepId TEXT NOT NULL, mode TEXT NOT NULL, payloadJson TEXT NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL, lastError TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_command_effects_planId ON command_effects(planId)")
    }
}
