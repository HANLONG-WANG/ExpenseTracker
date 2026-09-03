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
    val journalStateKeysetQueryUsesIndex: Boolean,
    val journalStateKeysetAvoidsTempSort: Boolean,
    val referenceMerchantPageUsesBoundedIndexes: Boolean,
    val referenceLocationPageUsesBoundedIndexes: Boolean,
    val referencePagesAvoidTempAggregation: Boolean,
    val representativeQueryElapsedMillis: Long,
) {
    val isValid: Boolean
        get() = registeredLogicalVersion == targetVersion && contractHashMatches && integrity.isValid &&
            projectionGenerationMatches && ftsQuerySucceeded && rTreeQueriesSucceeded &&
            representativeQueryUsesIndex && journalStateKeysetQueryUsesIndex &&
            journalStateKeysetAvoidsTempSort && referenceMerchantPageUsesBoundedIndexes &&
            referenceLocationPageUsesBoundedIndexes && referencePagesAvoidTempAggregation &&
            representativeQueryElapsedMillis <= MAX_REPRESENTATIVE_QUERY_MILLIS

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
        if (!journalStateKeysetQueryUsesIndex) add("Journal state keyset query does not use its matching index")
        if (!journalStateKeysetAvoidsTempSort) add("Journal state keyset query uses a temporary ORDER BY sort")
        if (!referenceMerchantPageUsesBoundedIndexes) add("Merchant reference page does not use every bounded page/count index")
        if (!referenceLocationPageUsesBoundedIndexes) add("Location reference page does not use every bounded page/count index")
        if (!referencePagesAvoidTempAggregation) add("reference page uses a full projection scan or temporary aggregation")
        if (representativeQueryElapsedMillis > MAX_REPRESENTATIVE_QUERY_MILLIS) {
            add("representative query took ${representativeQueryElapsedMillis}ms")
        }
    }.joinToString(separator = "; ")

    companion object {
        const val MAX_REPRESENTATIVE_QUERY_MILLIS: Long = 5_000
    }
}

/** Mandatory validation executed before every primary-schema migration transaction can commit. */
@Suppress("MagicNumber", "TooManyFunctions")
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
        val journalPlan = journalStateKeysetPlan(database, targetVersion)
        val referencePlans = referencePagePlans(database, targetVersion)
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
            journalStateKeysetQueryUsesIndex = journalPlan.first,
            journalStateKeysetAvoidsTempSort = journalPlan.second,
            referenceMerchantPageUsesBoundedIndexes = referencePlans.first,
            referenceLocationPageUsesBoundedIndexes = referencePlans.second,
            referencePagesAvoidTempAggregation = referencePlans.third,
            representativeQueryElapsedMillis = elapsedMillis,
        )
    }

    private fun expectedContractHash(context: Context, targetVersion: Int): String = when (targetVersion) {
        2 -> LedgerSchemaDefinition.primaryV2ContractSha256(context)
        3 -> LedgerSchemaDefinition.primaryV3ContractSha256(context)
        4 -> LedgerSchemaDefinition.primaryV4ContractSha256(context)
        5 -> LedgerSchemaDefinition.primaryV5ContractSha256(context)
        6 -> LedgerSchemaDefinition.primaryV6ContractSha256(context)
        LedgerSchemaDefinition.PRIMARY_VERSION -> LedgerSchemaDefinition.primaryContractSha256(context)
        else -> error("unsupported primary schema version $targetVersion")
    }

    private fun projectionGenerationMatches(database: SupportSQLiteDatabase, targetVersion: Int): Boolean {
        if (targetVersion < PROJECTION_GENERATION_VERSION) return true
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

    private fun journalStateKeysetPlan(
        database: SupportSQLiteDatabase,
        targetVersion: Int,
    ): Pair<Boolean, Boolean> {
        if (targetVersion < JOURNAL_STATE_KEYSET_VERSION) return true to true
        var indexed = false
        var temporarySort = false
        database.query("EXPLAIN QUERY PLAN $JOURNAL_STATE_KEYSET_QUERY").use { cursor ->
            val detail = cursor.getColumnIndexOrThrow("detail")
            while (cursor.moveToNext()) {
                val step = cursor.getString(detail)
                if (step.contains(JOURNAL_STATE_KEYSET_INDEX, ignoreCase = true)) indexed = true
                if (step.contains("TEMP B-TREE", ignoreCase = true)) temporarySort = true
            }
        }
        return indexed to !temporarySort
    }

    private fun referencePagePlans(
        database: SupportSQLiteDatabase,
        targetVersion: Int,
    ): Triple<Boolean, Boolean, Boolean> {
        if (targetVersion < REFERENCE_KEYSET_VERSION) return Triple(true, true, true)
        val merchantPlan = queryPlan(database, MERCHANT_REFERENCE_PAGE_QUERY)
        val locationPlan = queryPlan(database, LOCATION_REFERENCE_PAGE_QUERY)
        val merchantIndexed = MERCHANT_REFERENCE_PAGE_INDEXES.all { index ->
            merchantPlan.any { step -> step.contains(index, ignoreCase = true) }
        }
        val locationIndexed = LOCATION_REFERENCE_PAGE_INDEXES.all { index ->
            locationPlan.any { step -> step.contains(index, ignoreCase = true) }
        }
        val bounded = (merchantPlan + locationPlan).none { step ->
            step.contains("SCAN current_transaction_projection", ignoreCase = true) ||
                step.contains("TEMP B-TREE", ignoreCase = true)
        }
        return Triple(merchantIndexed, locationIndexed, bounded)
    }

    private fun queryPlan(database: SupportSQLiteDatabase, query: String): List<String> = database.query(
        "EXPLAIN QUERY PLAN $query",
    ).use { cursor ->
        val detail = cursor.getColumnIndexOrThrow("detail")
        buildList { while (cursor.moveToNext()) add(cursor.getString(detail)) }
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
    private const val JOURNAL_STATE_KEYSET_QUERY =
        "SELECT transaction_id FROM current_transaction_projection WHERE state=0 " +
            "ORDER BY occurred_at DESC,transaction_id DESC LIMIT 100"
    private const val JOURNAL_STATE_KEYSET_INDEX = "ix_current_transaction_state_keyset"
    private const val JOURNAL_STATE_KEYSET_VERSION = 6
    private const val REFERENCE_KEYSET_VERSION = 7
    private val MERCHANT_REFERENCE_PAGE_INDEXES = setOf(
        "ix_merchant_name_keyset",
        "ix_current_transaction_merchant",
        "ix_place_merchant",
    )
    private val LOCATION_REFERENCE_PAGE_INDEXES = setOf(
        "ix_location_record_captured_keyset",
        "ix_transaction_revision_location",
        "ix_current_transaction_revision",
    )
    private const val MERCHANT_REFERENCE_PAGE_QUERY =
        "WITH page AS (SELECT m.id,m.uid,m.name,m.status,m.merged_into_id,m.row_version " +
            "FROM merchant m INDEXED BY ix_merchant_name_keyset ORDER BY m.name,m.uid LIMIT 51) " +
            "SELECT page.uid,page.name,page.status,merged.uid,page.row_version," +
            "(SELECT COUNT(*) FROM current_transaction_projection ctp INDEXED BY ix_current_transaction_merchant " +
            "WHERE ctp.merchant_id=page.id),(SELECT COUNT(*) FROM place p INDEXED BY ix_place_merchant " +
            "WHERE p.merchant_id=page.id) FROM page LEFT JOIN merchant merged ON merged.id=page.merged_into_id " +
            "ORDER BY page.name,page.uid"
    private const val LOCATION_REFERENCE_PAGE_QUERY =
        "WITH page AS (SELECT lr.id,lr.uid,lr.lat_e7,lr.lon_e7,lr.captured_at,lr.place_id " +
            "FROM location_record lr INDEXED BY ix_location_record_captured_keyset " +
            "ORDER BY lr.captured_at DESC,lr.uid LIMIT 51) " +
            "SELECT page.uid,page.lat_e7,page.lon_e7,page.captured_at,p.uid," +
            "(SELECT COUNT(*) FROM transaction_revision tr INDEXED BY ix_transaction_revision_location " +
            "JOIN current_transaction_projection ctp INDEXED BY ix_current_transaction_revision " +
            "ON ctp.current_revision_id=tr.id WHERE tr.location_record_id=page.id) " +
            "FROM page LEFT JOIN place p ON p.id=page.place_id ORDER BY page.captured_at DESC,page.uid"
    private const val PROJECTION_GENERATION_VERSION = 4
    private const val NANOS_PER_MILLI = 1_000_000L
}
