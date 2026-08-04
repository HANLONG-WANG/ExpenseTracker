package app.ledger.finance.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.domain.FinancialMutationPlan

internal object RoomRefundFactWriter {
    fun write(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        plan.refundAllocations.forEach { allocation ->
            val reversalId = allocation.reversalOf?.let { reversal ->
                database.queryOne(
                    "SELECT ra.id FROM refund_allocation ra JOIN transaction_revision rr ON rr.id=ra.refund_revision_id " +
                        "JOIN business_transaction original ON original.id=ra.original_transaction_id " +
                        "WHERE rr.uid=? AND original.uid=? AND ra.reversal_of_id IS NULL",
                    arrayOf(reversal.refundRevisionId.value.bytes, reversal.originalTransactionId.value.bytes),
                ) { cursor -> cursor.getLong(0) } ?: abort(FinanceDataError.CorruptData)
            }
            database.execSQL(
                "INSERT INTO refund_allocation(refund_transaction_id, refund_revision_id, original_transaction_id, original_revision_id, " +
                    "original_currency_amount_minor, base_amount_minor, created_commit_id, reversal_of_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    database.requireInternalId("business_transaction", allocation.refundTransactionId.value),
                    database.requireInternalId("transaction_revision", allocation.refundRevisionId.value),
                    database.requireInternalId("business_transaction", allocation.originalTransactionId.value),
                    database.requireInternalId("transaction_revision", allocation.originalRevisionId.value),
                    allocation.amountInOriginalCurrency.minor.value,
                    allocation.amountInBaseCurrency.minor.value,
                    database.commitId(allocation.createdCommitId),
                    reversalId,
                ),
            )
        }
    }
}
