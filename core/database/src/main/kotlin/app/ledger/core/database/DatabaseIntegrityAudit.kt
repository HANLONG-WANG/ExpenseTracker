package app.ledger.core.database

import androidx.sqlite.db.SupportSQLiteDatabase

data class SqliteCapabilityReport(
    val sqlCipherVersion: String,
    val fts5: Boolean,
    val rTree: Boolean,
    val json: Boolean,
    val windowFunctions: Boolean,
)

data class DatabaseIntegrityReport(
    val integrityCheck: String,
    val foreignKeyViolationCount: Int,
    val unbalancedJournalCount: Int,
    val invalidCurrentSubtypeCount: Int,
    val capability: SqliteCapabilityReport,
) {
    val isValid: Boolean
        get() = integrityCheck == "ok" &&
            foreignKeyViolationCount == 0 &&
            unbalancedJournalCount == 0 &&
            invalidCurrentSubtypeCount == 0 &&
            capability.fts5 && capability.rTree && capability.json && capability.windowFunctions
}

object DatabaseIntegrityAudit {
    fun run(database: SupportSQLiteDatabase): DatabaseIntegrityReport = DatabaseIntegrityReport(
        integrityCheck = singleString(database, "PRAGMA integrity_check"),
        foreignKeyViolationCount = rowCount(database, "PRAGMA foreign_key_check"),
        unbalancedJournalCount = singleInt(
            database,
            "SELECT COUNT(*) FROM journal_balance_audit WHERE is_balanced = 0",
        ),
        invalidCurrentSubtypeCount = singleInt(
            database,
            "SELECT COUNT(*) FROM current_transaction_subtype_audit WHERE has_matching_detail = 0",
        ),
        capability = capabilityReport(database),
    )

    fun capabilityReport(database: SupportSQLiteDatabase): SqliteCapabilityReport {
        val compileOptions = mutableSetOf<String>()
        database.query("PRAGMA compile_options").use { cursor ->
            while (cursor.moveToNext()) compileOptions += cursor.getString(0)
        }
        val fts5 = "ENABLE_FTS5" in compileOptions && querySucceeds(database, "SELECT count(*) FROM transaction_fts")
        val rTree = "ENABLE_RTREE" in compileOptions && querySucceeds(database, "SELECT count(*) FROM location_rtree")
        val json = singleInt(database, "SELECT json_valid('{\"p07\":true}')") == 1
        val window = singleInt(
            database,
            "SELECT row_number FROM (SELECT ROW_NUMBER() OVER (ORDER BY value) AS row_number FROM (SELECT 1 AS value))",
        ) == 1
        return SqliteCapabilityReport(
            sqlCipherVersion = singleString(database, "PRAGMA cipher_version"),
            fts5 = fts5,
            rTree = rTree,
            json = json,
            windowFunctions = window,
        )
    }

    private fun querySucceeds(database: SupportSQLiteDatabase, sql: String): Boolean = runCatching { database.query(sql).use { it.moveToFirst() } }.isSuccess

    private fun rowCount(database: SupportSQLiteDatabase, sql: String): Int = database.query(sql).use { cursor ->
        var count = 0
        while (cursor.moveToNext()) count = Math.addExact(count, 1)
        count
    }

    private fun singleInt(database: SupportSQLiteDatabase, sql: String): Int = database.query(sql).use { cursor ->
        check(cursor.moveToFirst()) { "query returned no row" }
        cursor.getInt(0)
    }

    private fun singleString(database: SupportSQLiteDatabase, sql: String): String = database.query(sql).use { cursor ->
        check(cursor.moveToFirst()) { "query returned no row" }
        cursor.getString(0)
    }
}
