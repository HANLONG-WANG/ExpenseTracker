package com.yohaku.expensetracker.data.db

val MIGRATION_28_29=object:androidx.room.migration.Migration(28,29) {
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS cloud_tasks (localId TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, type TEXT NOT NULL, title TEXT NOT NULL, accountId TEXT, bundleId TEXT, origin TEXT NOT NULL, idempotencyKey TEXT NOT NULL, requestJson TEXT NOT NULL, taskId TEXT, stage TEXT NOT NULL, remoteStatus TEXT, inputRevision INTEGER, remoteJson TEXT, lastError TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, attempts INTEGER NOT NULL, FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_tasks_bookId ON cloud_tasks(bookId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS cloud_transfers (localId TEXT NOT NULL, position INTEGER NOT NULL, name TEXT NOT NULL, path TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL, mime TEXT NOT NULL, manifestJson TEXT, fileId TEXT, attempts INTEGER NOT NULL, uploadedAt INTEGER, supplement INTEGER NOT NULL, PRIMARY KEY(localId,position), FOREIGN KEY(localId) REFERENCES cloud_tasks(localId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS cloud_imports (importKey TEXT NOT NULL PRIMARY KEY, localId TEXT NOT NULL, taskId TEXT NOT NULL, resultId TEXT NOT NULL, resultVersion INTEGER NOT NULL, sha256 TEXT NOT NULL, localRef TEXT NOT NULL, importedAt INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_imports_localId ON cloud_imports(localId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS cloud_acks (localId TEXT NOT NULL, resultId TEXT NOT NULL, status TEXT NOT NULL, taskId TEXT NOT NULL, createdAt INTEGER NOT NULL, sentAt INTEGER, lastError TEXT, PRIMARY KEY(localId,resultId,status))")
    }
}
