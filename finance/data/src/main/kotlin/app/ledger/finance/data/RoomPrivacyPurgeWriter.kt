@file:Suppress("ComplexCondition", "MaxLineLength", "TooManyFunctions")

package app.ledger.finance.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.PurgeTransactionCommand
import java.time.Instant

internal data class PrivacyPurgeCounts(
    val detachedAttachments: Int,
    val queuedBlobs: Int,
)

/**
 * Deletes exactly one already-closed transaction chain. The caller owns the surrounding financial
 * commit transaction and the maintenance guard. No value from a UI or backup is interpolated as SQL.
 */
internal class RoomPrivacyPurgeWriter {
    fun revalidate(database: SupportSQLiteDatabase, command: PurgeTransactionCommand) {
        val row = database.queryOne(
            "SELECT id,lifecycle_state,purge_after FROM business_transaction WHERE uid=?",
            arrayOf(command.transactionId.value.bytes),
        ) { Triple(it.getLong(0), it.getInt(1), it.getLong(2)) } ?: abort(FinanceDataError.CorruptData)
        val eligibility = command.eligibility
        val transactionId = row.first
        if (
            row.second != app.ledger.finance.domain.TransactionLifecycleState.TRASHED.ordinal ||
            row.third != eligibility.purgeAfter.toEpochMilli() ||
            eligibility.evaluatedAt.toEpochMilli() < row.third
        ) {
            abort(FinanceDataError.MaintenanceRequired)
        }
        if (nonZeroAccountNets(database, transactionId) != 0L || nonZeroBaseNets(database, transactionId) != 0L) {
            abort(FinanceDataError.MaintenanceRequired)
        }
        if (nonZeroEffectNets(database, transactionId) != 0L) abort(FinanceDataError.MaintenanceRequired)
        if (dependencyReferences(database, transactionId, command) != 0L) abort(FinanceDataError.MaintenanceRequired)
        if (backupAttachmentReads(database, transactionId) != 0L) abort(FinanceDataError.MaintenanceRequired)
        if (
            !eligibility.accountCurrencyNetZero || !eligibility.baseCurrencyNetZero || !eligibility.effectsNetZero ||
            !eligibility.dependenciesClosed || eligibility.referencedByOperation || eligibility.attachmentsReadByBackup
        ) {
            abort(FinanceDataError.MaintenanceRequired)
        }
    }

    fun deleteChain(
        database: SupportSQLiteDatabase,
        command: PurgeTransactionCommand,
        purgeCommitInternalId: Long,
    ): PrivacyPurgeCounts {
        val transactionInternalId = database.queryOne(
            "SELECT id FROM business_transaction WHERE uid=?",
            arrayOf(command.transactionId.value.bytes),
        ) { it.getLong(0) } ?: abort(FinanceDataError.CorruptData)
        database.execSQL("PRAGMA defer_foreign_keys=ON")
        createTargets(database, transactionInternalId)
        val attachmentCount = database.scalarLong("SELECT COUNT(*) FROM temp.purge_attachment_target").toInt()
        val tables = database.foreignKeyTables()
        tables.forEach { target ->
            val predicate = when (target.parentTable) {
                "business_transaction" -> "${target.childColumn} IN (SELECT id FROM temp.purge_transaction_target)"
                "transaction_revision" -> "${target.childColumn} IN (SELECT id FROM temp.purge_revision_target)"
                "journal_entry" -> "${target.childColumn} IN (SELECT id FROM temp.purge_entry_target)"
                else -> return@forEach
            }
            if (target.childTable !in PROTECTED_TABLES) {
                database.execSQL("DELETE FROM ${target.childTable} WHERE $predicate")
            }
        }
        database.execSQL(
            "DELETE FROM entity_revision WHERE entity_type=? AND entity_uid=?",
            arrayOf(EntityType.TRANSACTION.ordinal, command.transactionId.value.bytes),
        )
        database.execSQL(
            "DELETE FROM entity_change WHERE entity_type=? AND entity_uid=? AND commit_id<>?",
            arrayOf(EntityType.TRANSACTION.ordinal, command.transactionId.value.bytes, purgeCommitInternalId),
        )
        database.execSQL(
            "DELETE FROM command_receipt WHERE primary_entity_uid=?",
            arrayOf(command.transactionId.value.bytes),
        )
        runCatching {
            database.execSQL(
                "DELETE FROM transaction_fts WHERE transaction_id IN (SELECT id FROM temp.purge_transaction_target)",
            )
        }
        database.execSQL("DELETE FROM business_transaction WHERE id IN (SELECT id FROM temp.purge_transaction_target)")
        database.execSQL(
            "DELETE FROM attachment WHERE id IN (SELECT id FROM temp.purge_attachment_target) " +
                "AND NOT EXISTS(SELECT 1 FROM transaction_revision_attachment x WHERE x.attachment_id=attachment.id)",
        )
        database.execSQL(
            "INSERT OR IGNORE INTO blob_gc_candidate(blob_id,eligible_after,reason,last_checked_at) " +
                "SELECT b.id,?,2,NULL FROM encrypted_blob b JOIN temp.purge_blob_target t ON t.id=b.id " +
                "WHERE NOT EXISTS(SELECT 1 FROM attachment a WHERE a.blob_id=b.id)",
            arrayOf(command.eligibility.evaluatedAt.toEpochMilli()),
        )
        val queued = database.scalarLong(
            "SELECT COUNT(*) FROM blob_gc_candidate WHERE blob_id IN (SELECT id FROM temp.purge_blob_target)",
        ).toInt()
        dropTargets(database)
        return PrivacyPurgeCounts(attachmentCount, queued)
    }

    fun deleteChainForMerge(
        database: SupportSQLiteDatabase,
        transactionUid: ByteArray,
        purgeCommitInternalId: Long,
        purgedAtEpochMillis: Long,
    ): PrivacyPurgeCounts {
        val transactionInternalId = database.queryOne(
            "SELECT id FROM business_transaction WHERE uid=?",
            arrayOf(transactionUid),
        ) { it.getLong(0) } ?: return PrivacyPurgeCounts(0, 0)
        return deletePreparedChain(
            database,
            transactionInternalId,
            transactionUid,
            purgeCommitInternalId,
            Instant.ofEpochMilli(purgedAtEpochMillis).toEpochMilli(),
        )
    }

    private fun deletePreparedChain(
        database: SupportSQLiteDatabase,
        transactionInternalId: Long,
        transactionUid: ByteArray,
        purgeCommitInternalId: Long,
        eligibleAtEpochMillis: Long,
    ): PrivacyPurgeCounts {
        database.execSQL("PRAGMA defer_foreign_keys=ON")
        createTargets(database, transactionInternalId)
        val attachmentCount = database.scalarLong("SELECT COUNT(*) FROM temp.purge_attachment_target").toInt()
        database.foreignKeyTables().forEach { target ->
            val predicate = when (target.parentTable) {
                "business_transaction" -> "${target.childColumn} IN (SELECT id FROM temp.purge_transaction_target)"
                "transaction_revision" -> "${target.childColumn} IN (SELECT id FROM temp.purge_revision_target)"
                "journal_entry" -> "${target.childColumn} IN (SELECT id FROM temp.purge_entry_target)"
                else -> return@forEach
            }
            if (target.childTable !in PROTECTED_TABLES) database.execSQL("DELETE FROM ${target.childTable} WHERE $predicate")
        }
        database.execSQL(
            "DELETE FROM entity_revision WHERE entity_type=? AND entity_uid=?",
            arrayOf(EntityType.TRANSACTION.ordinal, transactionUid),
        )
        database.execSQL(
            "DELETE FROM entity_change WHERE entity_type=? AND entity_uid=? AND commit_id<>?",
            arrayOf(EntityType.TRANSACTION.ordinal, transactionUid, purgeCommitInternalId),
        )
        database.execSQL("DELETE FROM command_receipt WHERE primary_entity_uid=?", arrayOf(transactionUid))
        runCatching { database.execSQL("DELETE FROM transaction_fts WHERE transaction_id IN (SELECT id FROM temp.purge_transaction_target)") }
        database.execSQL("DELETE FROM business_transaction WHERE id IN (SELECT id FROM temp.purge_transaction_target)")
        database.execSQL(
            "DELETE FROM attachment WHERE id IN (SELECT id FROM temp.purge_attachment_target) " +
                "AND NOT EXISTS(SELECT 1 FROM transaction_revision_attachment x WHERE x.attachment_id=attachment.id)",
        )
        database.execSQL(
            "INSERT OR IGNORE INTO blob_gc_candidate(blob_id,eligible_after,reason,last_checked_at) " +
                "SELECT b.id,?,2,NULL FROM encrypted_blob b JOIN temp.purge_blob_target t ON t.id=b.id " +
                "WHERE NOT EXISTS(SELECT 1 FROM attachment a WHERE a.blob_id=b.id)",
            arrayOf(eligibleAtEpochMillis),
        )
        val queued = database.scalarLong(
            "SELECT COUNT(*) FROM blob_gc_candidate WHERE blob_id IN (SELECT id FROM temp.purge_blob_target)",
        ).toInt()
        dropTargets(database)
        return PrivacyPurgeCounts(attachmentCount, queued)
    }

    private fun createTargets(database: SupportSQLiteDatabase, transactionId: Long) {
        database.execSQL("CREATE TEMP TABLE purge_transaction_target(id INTEGER PRIMARY KEY)")
        database.execSQL("CREATE TEMP TABLE purge_revision_target(id INTEGER PRIMARY KEY)")
        database.execSQL("CREATE TEMP TABLE purge_entry_target(id INTEGER PRIMARY KEY)")
        database.execSQL("CREATE TEMP TABLE purge_attachment_target(id INTEGER PRIMARY KEY)")
        database.execSQL("CREATE TEMP TABLE purge_blob_target(id INTEGER PRIMARY KEY)")
        database.execSQL("INSERT INTO temp.purge_transaction_target VALUES(?)", arrayOf(transactionId))
        database.execSQL(
            "INSERT INTO temp.purge_revision_target SELECT id FROM transaction_revision WHERE transaction_id=?",
            arrayOf(transactionId),
        )
        database.execSQL(
            "INSERT INTO temp.purge_entry_target SELECT id FROM journal_entry WHERE source_revision_id IN " +
                "(SELECT id FROM temp.purge_revision_target) OR applies_revision_id IN (SELECT id FROM temp.purge_revision_target)",
        )
        database.execSQL(
            "INSERT INTO temp.purge_attachment_target SELECT DISTINCT attachment_id FROM transaction_revision_attachment " +
                "WHERE revision_id IN (SELECT id FROM temp.purge_revision_target)",
        )
        database.execSQL(
            "INSERT INTO temp.purge_blob_target SELECT DISTINCT blob_id FROM attachment " +
                "WHERE id IN (SELECT id FROM temp.purge_attachment_target)",
        )
    }

    private fun dropTargets(database: SupportSQLiteDatabase) {
        listOf("purge_blob_target", "purge_attachment_target", "purge_entry_target", "purge_revision_target", "purge_transaction_target")
            .forEach { database.execSQL("DROP TABLE temp.$it") }
    }

    private fun nonZeroAccountNets(database: SupportSQLiteDatabase, transactionId: Long): Long = database.scalarLong(
        "SELECT COUNT(*) FROM (SELECT p.ledger_account_id,p.account_currency FROM posting p " +
            "JOIN journal_entry je ON je.id=p.journal_entry_id JOIN transaction_revision tr ON tr.id=je.source_revision_id " +
            "WHERE tr.transaction_id=? GROUP BY p.ledger_account_id,p.account_currency " +
            "HAVING SUM(CASE WHEN p.side=0 THEN p.account_amount_minor ELSE -p.account_amount_minor END)<>0)",
        arrayOf(transactionId),
    )

    private fun nonZeroBaseNets(database: SupportSQLiteDatabase, transactionId: Long): Long = database.scalarLong(
        "SELECT COUNT(*) FROM (SELECT p.base_currency FROM posting p JOIN journal_entry je ON je.id=p.journal_entry_id " +
            "JOIN transaction_revision tr ON tr.id=je.source_revision_id WHERE tr.transaction_id=? GROUP BY p.base_currency " +
            "HAVING SUM(CASE WHEN p.side=0 THEN p.base_amount_minor ELSE -p.base_amount_minor END)<>0)",
        arrayOf(transactionId),
    )

    private fun nonZeroEffectNets(database: SupportSQLiteDatabase, transactionId: Long): Long = EFFECT_NET_QUERIES.sumOf { sql ->
        database.scalarLong(sql, arrayOf(transactionId))
    }

    private fun dependencyReferences(
        database: SupportSQLiteDatabase,
        transactionId: Long,
        command: PurgeTransactionCommand,
    ): Long = DEPENDENCY_QUERIES.sumOf { sql -> database.scalarLong(sql, arrayOf(transactionId, transactionId)) } +
        database.scalarLong(
            "SELECT COUNT(*) FROM merge_conflict WHERE entity_type=? AND entity_uid=? AND resolution IS NULL",
            arrayOf(EntityType.TRANSACTION.ordinal, command.transactionId.value.bytes),
        )

    private fun backupAttachmentReads(database: SupportSQLiteDatabase, transactionId: Long): Long = database.scalarLong(
        "SELECT COUNT(*) FROM transaction_revision tr JOIN transaction_revision_attachment tra ON tra.revision_id=tr.id " +
            "JOIN attachment a ON a.id=tra.attachment_id JOIN encrypted_blob eb ON eb.id=a.blob_id " +
            "JOIN backup_object bo ON bo.content_hash=eb.plaintext_sha256 JOIN backup_snapshot_object bso ON bso.object_id=bo.id " +
            "JOIN backup_snapshot bs ON bs.id=bso.snapshot_id WHERE tr.transaction_id=? AND bs.state IN (0,1)",
        arrayOf(transactionId),
    )

    private data class ForeignKeyTarget(
        val childTable: String,
        val childColumn: String,
        val parentTable: String,
    )

    private fun SupportSQLiteDatabase.foreignKeyTables(): List<ForeignKeyTarget> = query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND substr(name,1,1)<>'_'",
    ).use { tables ->
        buildList {
            while (tables.moveToNext()) {
                val table = tables.getString(0)
                if (!SAFE_IDENTIFIER.matches(table)) abort(FinanceDataError.CorruptData)
                query("PRAGMA foreign_key_list($table)").use { keys ->
                    while (keys.moveToNext()) {
                        val parent = keys.getString(keys.getColumnIndexOrThrow("table"))
                        val childColumn = keys.getString(keys.getColumnIndexOrThrow("from"))
                        if (parent in TARGET_PARENT_TABLES) {
                            if (!SAFE_IDENTIFIER.matches(childColumn)) abort(FinanceDataError.CorruptData)
                            add(ForeignKeyTarget(table, childColumn, parent))
                        }
                    }
                }
            }
        }
    }

    private fun SupportSQLiteDatabase.scalarLong(
        sql: String,
        arguments: Array<out Any?> = emptyArray(),
    ): Long = query(sql, arguments).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else 0L
    }

    private companion object {
        val SAFE_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val TARGET_PARENT_TABLES = setOf("business_transaction", "transaction_revision", "journal_entry")
        val PROTECTED_TABLES = setOf("business_transaction")
        val EFFECT_NET_QUERIES = listOf(
            """SELECT COUNT(*) FROM (SELECT 1 FROM economic_effect e JOIN transaction_revision r ON r.id=e.source_revision_id WHERE r.transaction_id=? GROUP BY e.nature,e.component HAVING SUM(e.polarity*e.base_amount_minor)<>0)""",
            """SELECT COUNT(*) FROM (SELECT 1 FROM budget_effect e JOIN transaction_revision r ON r.id=e.source_revision_id WHERE r.transaction_id=? GROUP BY e.target_year_month,e.category_id HAVING SUM(e.polarity*e.base_amount_minor)<>0)""",
            """SELECT COUNT(*) FROM (SELECT 1 FROM project_effect e JOIN transaction_revision r ON r.id=e.source_revision_id WHERE r.transaction_id=? GROUP BY e.project_id HAVING SUM(e.polarity*e.base_amount_minor)<>0)""",
            """SELECT COUNT(*) FROM (SELECT 1 FROM goal_effect e JOIN transaction_revision r ON r.id=e.source_revision_id WHERE r.transaction_id=? GROUP BY e.goal_id,e.currency_code HAVING SUM(e.polarity*e.amount_minor)<>0)""",
            """SELECT COUNT(*) FROM (SELECT 1 FROM statement_effect e JOIN transaction_revision r ON r.id=e.source_revision_id WHERE r.transaction_id=? GROUP BY e.credit_account_id,e.currency_code HAVING SUM(e.polarity*e.amount_minor)<>0)""",
            """SELECT COUNT(*) FROM (SELECT 1 FROM loan_effect e JOIN transaction_revision r ON r.id=e.source_revision_id WHERE r.transaction_id=? GROUP BY e.loan_contract_id,e.currency_code HAVING SUM(e.polarity*e.amount_minor)<>0 OR SUM(e.polarity*e.base_amount_minor)<>0)""",
            """SELECT COUNT(*) FROM (SELECT 1 FROM settlement_effect e JOIN transaction_revision r ON r.id=e.source_revision_id WHERE r.transaction_id=? GROUP BY e.activity_id,e.participant_id,e.currency_code HAVING SUM(e.signed_delta_minor)<>0)""",
        )
        val DEPENDENCY_QUERIES = listOf(
            "SELECT COUNT(*) FROM transaction_dependency WHERE parent_transaction_id=? OR child_transaction_id=?",
            "SELECT COUNT(*) FROM import_source_reference WHERE transaction_id=? OR transaction_id=?",
            "SELECT COUNT(*) FROM installment_plan WHERE purchase_transaction_id=? OR purchase_transaction_id=?",
            "SELECT COUNT(*) FROM recurrence_occurrence WHERE transaction_id=? OR transaction_id=?",
            "SELECT COUNT(*) FROM account_balance_checkpoint WHERE adjustment_transaction_id=? OR adjustment_transaction_id=?",
            "SELECT COUNT(*) FROM refund_allocation WHERE (refund_transaction_id=?1 AND original_transaction_id<>?2) OR " +
                "(original_transaction_id=?1 AND refund_transaction_id<>?2)",
        )
    }
}
