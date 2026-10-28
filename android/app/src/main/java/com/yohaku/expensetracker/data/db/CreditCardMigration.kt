package com.yohaku.expensetracker.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_32_33 = object:Migration(32,33) {
    override fun migrate(db:SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE credit_limit_configs ADD COLUMN recoveryDelayDays INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE events ADD COLUMN cardPaymentMonth TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE events ADD COLUMN cardPaymentFeeMinor INTEGER DEFAULT NULL")
    }
}
