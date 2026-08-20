package app.ledger.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase

data class MigrationPostValidationReport(
    val targetVersion: Int,
    val registeredLogicalVersion: Int,
    val contractHashMatches: Boolean,
    val integrity: DatabaseIntegrityReport,
    val projectionGenerationMatches: Boolean,
    val ftsQuerySucceeded: Boolean,
    val rTreeQueriesSucceeded: Boolean,
    val representativeQueryUsesIndex: Boolean,
    val representativeQueryElapsedMillis: Long,
) {
    val isValid: Boolean
        get() = registeredLogicalVersion == targetVersion && contractHashMatches && integrity.isValid &&
            projectionGenerationMatches && ftsQuerySucceeded && rTreeQueriesSucceeded &&
            representativeQueryUsesIndex && representativeQueryElapsedMillis <= MAX_REPRESENTATIVE_QUERY_MILLIS

    fun failureSummary(): String = buildList {
        if (registeredLogicalVersion != targetVersion) add("logical schema registry is $registeredLogicalVersion")
        if (!contractHashMatches) add("schema contract hash mismatch")
        if (!integrity.isValid) {
            add("database integrity failed (${integrity.failedInvariantIds.sorted().joinToString()})")
        }
        if (!projectionGenerationMatches) add("projection generation is not aligned with Book revision")
        if (!ftsQuerySucceeded) add("FTS5 query failed")
        if (!rTreeQueriesSucceeded) add("R*Tree query failed")
        if (!representativeQueryUsesIndex) add("representative transaction query does not use its keyset index")
        if (representativeQueryElapsedMillis > MAX_REPRESENTATIVE_QUERY_MILLIS) {
            add("representative query took ${representativeQueryElapsedMillis}ms")
        }
    }.joinToString(separator = "; ")

    companion object {
        const val MAX_REPRESENTATIVE_QUERY_MILLIS: Long = 5_000
    }
}

/** Mandatory validation executed before every primary-schema migration transaction can commit. */
object MigrationPostValidation {
    fun validateOrThrow(
        context: Context,
        database: SupportSQLiteDatabase,
        targetVersion: Int,
    ): MigrationPostValidationReport {
        val report = run(context, database, targetVersion)
        check(report.isValid) {
            "post-migration validation for schema v$targetVersion failed: ${report.failureSummary()}"
        }
        return report
    }

    fun run(
        context: Context,
        database: SupportSQLiteDatabase,
        targetVersion: Int,
    ): MigrationPostValidationReport {
        require(targetVersion in 2..LedgerSchemaDefinition.PRIMARY_VERSION)
        val startedAt = System.nanoTime()
        database.query(REPRESENTATIVE_QUERY).use { cursor ->
            while (cursor.moveToNext()) cursor.getLong(0)
        }
        val elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
        return MigrationPostValidationReport(
            targetVersion = targetVersion,
            registeredLogicalVersion = singleInt(
                database,
                "SELECT logicalSchemaVersion FROM _room_schema_registry WHERE id=1",
            ),
            contractHashMatches = singleString(
                database,
                "SELECT contractSha256 FROM _room_schema_registry WHERE id=1",
            ) == expectedContractHash(context, targetVersion),
            integrity = DatabaseIntegrityAudit.run(database),
            projectionGenerationMatches = projectionGenerationMatches(database, targetVersion),
            ftsQuerySucceeded = querySucceeds(
                database,
                "SELECT transaction_id FROM transaction_fts WHERE transaction_fts MATCH 'postmigrationtoken' LIMIT 1",
            ),
            rTreeQueriesSucceeded = querySucceeds(
                database,
                "SELECT location_id FROM location_rtree WHERE min_lat<=0 AND max_lat>=0 LIMIT 1",
            ) && querySucceeds(
                database,
                "SELECT place_id FROM place_rtree WHERE min_lon<=0 AND max_lon>=0 LIMIT 1",
            ),
            representativeQueryUsesIndex = queryPlanUsesRepresentativeIndex(database),
            representativeQueryElapsedMillis = elapsedMillis,
        )
    }

    private fun expectedContractHash(context: Context, targetVersion: Int): String = when (targetVersion) {
        2 -> LedgerSchemaDefinition.primaryV2ContractSha256(context)
        3 -> LedgerSchemaDefinition.primaryV3ContractSha256(context)
        LedgerSchemaDefinition.PRIMARY_VERSION -> LedgerSchemaDefinition.primaryContractSha256(context)
        else -> error("unsupported primary schema version $targetVersion")
    }

    private fun projectionGenerationMatches(database: SupportSQLiteDatabase, targetVersion: Int): Boolean {
        if (targetVersion < LedgerSchemaDefinition.PRIMARY_VERSION) return true
        return singleInt(
            database,
            "SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM book WHERE id=1) THEN " +
                "CASE WHEN COUNT(*)=0 THEN 1 ELSE 0 END ELSE " +
                "CASE WHEN COUNT(*)=15 " +
                "AND MIN(as_of_local_revision)=(SELECT local_revision FROM book WHERE id=1) " +
                "AND MAX(as_of_local_revision)=(SELECT local_revision FROM book WHERE id=1) " +
                "AND MIN(as_of_valuation_revision)=(SELECT valuation_revision FROM book WHERE id=1) " +
                "AND MAX(as_of_valuation_revision)=(SELECT valuation_revision FROM book WHERE id=1) " +
                "THEN 1 ELSE 0 END END FROM projection_family_state",
        ) == 1
    }

    private fun queryPlanUsesRepresentativeIndex(database: SupportSQLiteDatabase): Boolean = database.query(
        "EXPLAIN QUERY PLAN $REPRESENTATIVE_QUERY",
    ).use { cursor ->
        val detail = cursor.getColumnIndexOrThrow("detail")
        var indexed = false
        while (cursor.moveToNext()) {
            if (cursor.getString(detail).contains(REPRESENTATIVE_INDEX, ignoreCase = true)) indexed = true
        }
        indexed
    }

    private fun querySucceeds(database: SupportSQLiteDatabase, sql: String): Boolean = runCatching {
        database.query(sql).use { cursor -> while (cursor.moveToNext()) cursor.getLong(0) }
    }.isSuccess

    private fun singleInt(database: SupportSQLiteDatabase, sql: String): Int = database.query(sql).use { cursor ->
        check(cursor.moveToFirst()) { "query returned no row" }
        Math.toIntExact(cursor.getLong(0))
    }

    private fun singleString(database: SupportSQLiteDatabase, sql: String): String = database.query(sql).use { cursor ->
        check(cursor.moveToFirst()) { "query returned no row" }
        cursor.getString(0)
    }

    private const val REPRESENTATIVE_QUERY =
        "SELECT transaction_id FROM current_transaction_projection WHERE state=0 " +
            "ORDER BY local_date DESC,occurred_at DESC,transaction_id DESC LIMIT 100"
    private const val REPRESENTATIVE_INDEX = "ix_current_transaction_page"
    private const val NANOS_PER_MILLI = 1_000_000L
}
