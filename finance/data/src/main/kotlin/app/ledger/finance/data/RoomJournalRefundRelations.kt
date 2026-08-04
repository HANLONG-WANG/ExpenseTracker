@file:Suppress("MaxLineLength")

package app.ledger.finance.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.StableId
import app.ledger.finance.domain.TransactionKind

internal object RoomJournalRefundRelations {
    fun summaries(database: SupportSQLiteDatabase, id: StableId): List<String> {
        val kind = database.queryOne(
            "SELECT kind FROM business_transaction WHERE uid=?",
            arrayOf(id.bytes),
        ) { TransactionKind.entries[it.getInt(0)] }
        return when (kind) {
            TransactionKind.REFUND -> refund(database, id)
            TransactionKind.EXPENSE -> originalExpense(database, id)
            else -> emptyList()
        }
    }

    private fun refund(database: SupportSQLiteDatabase, id: StableId): List<String> = database.queryList(
        "SELECT original.uid original_uid,input.amount_minor gross,input.currency_code," +
            "rrd.accrual_date,rrd.budget_target_year_month,rrd.budget_restore_policy,refund_revision.local_date cash_date," +
            "COALESCE((SELECT SUM(CASE WHEN all_allocations.reversal_of_id IS NULL THEN all_allocations.original_currency_amount_minor ELSE -all_allocations.original_currency_amount_minor END) " +
            "FROM refund_allocation all_allocations WHERE all_allocations.original_transaction_id=original.id),0) refunded " +
            "FROM business_transaction refund JOIN transaction_revision refund_revision ON refund_revision.id=refund.current_revision_id " +
            "JOIN refund_revision_detail rrd ON rrd.revision_id=refund_revision.id " +
            "JOIN refund_allocation current_allocation ON current_allocation.source_revision_id=refund_revision.id AND current_allocation.reversal_of_id IS NULL " +
            "JOIN business_transaction original ON original.id=current_allocation.original_transaction_id " +
            "JOIN transaction_revision original_revision ON original_revision.id=current_allocation.original_revision_id " +
            "JOIN revision_amount input ON input.revision_id=original_revision.id AND input.component_index=0 AND input.representation=0 " +
            "WHERE refund.uid=? ORDER BY current_allocation.id",
        arrayOf(id.bytes),
    ) { cursor ->
        val gross = cursor.long("gross")
        val refunded = cursor.long("refunded")
        listOf(
            "refund.original:${cursor.stableId("original_uid")}:gross=$gross:refunded=$refunded:remaining=${maxOf(gross - refunded, 0L)}:${cursor.string("currency_code")}",
            "refund.dates:cash=${cursor.int("cash_date")}:accrual=${cursor.int("accrual_date")}:budget=${cursor.nullableLong("budget_target_year_month") ?: "none"}:policy=${cursor.int("budget_restore_policy")}",
        )
    }.flatten()

    private fun originalExpense(database: SupportSQLiteDatabase, id: StableId): List<String> = database.queryOne(
        "SELECT input.amount_minor gross,input.currency_code," +
            "COALESCE(SUM(CASE WHEN allocation.reversal_of_id IS NULL THEN allocation.original_currency_amount_minor ELSE -allocation.original_currency_amount_minor END),0) refunded " +
            "FROM business_transaction original JOIN transaction_revision revision ON revision.id=original.current_revision_id " +
            "JOIN revision_amount input ON input.revision_id=revision.id AND input.component_index=0 AND input.representation=0 " +
            "LEFT JOIN refund_allocation allocation ON allocation.original_transaction_id=original.id WHERE original.uid=? GROUP BY original.id",
        arrayOf(id.bytes),
    ) { cursor ->
        val gross = cursor.long("gross")
        val refunded = cursor.long("refunded")
        listOf("refund.status:gross=$gross:refunded=$refunded:remaining=${maxOf(gross - refunded, 0L)}:${cursor.string("currency_code")}")
    } ?: emptyList()
}

private fun android.database.Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
private fun android.database.Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun android.database.Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
