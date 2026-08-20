@file:Suppress("ComplexCondition", "MaxLineLength")

package app.ledger.finance.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.PurgeTransactionCommand

/**
 * Revalidates a logical purge in the commit transaction. Purge appends only a non-sensitive
 * tombstone and removes the transaction from current projections; immutable revisions and
 * financial facts are never updated or deleted.
 */
internal class RoomLogicalPurgeValidator {
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

    private fun SupportSQLiteDatabase.scalarLong(sql: String, arguments: Array<out Any?> = emptyArray()): Long = query(sql, arguments).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private companion object {
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
