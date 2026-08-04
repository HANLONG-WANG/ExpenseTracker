package app.ledger.finance.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.finance.domain.CreditPaymentPayload
import app.ledger.finance.domain.FinancialMutationPlan
import app.ledger.finance.domain.TransactionLifecycleState

internal object RoomCreditPaymentAllocationWriter {
    fun write(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        plan.revisions.forEach { revision ->
            val payload = revision.payload as? CreditPaymentPayload ?: return@forEach
            val revisionId = database.requireInternalId("transaction_revision", revision.id.value)
            val transactionId = database.requireInternalId("business_transaction", revision.transactionId.value)
            revision.previousRevisionId?.let { previous ->
                reversePreviousAllocations(database, transactionId, revisionId, previous.value.bytes)
            }
            if (revision.resultingState == TransactionLifecycleState.ACTIVE) {
                payload.allocations.forEach { allocation ->
                    database.execSQL(
                        "INSERT INTO credit_payment_allocation(payment_transaction_id,payment_revision_id,statement_id,amount_minor,reversal_of_id) " +
                            "VALUES(?,?,?,?,NULL)",
                        arrayOf<Any?>(
                            transactionId,
                            revisionId,
                            allocation.statementId?.let { database.requireInternalId("credit_statement", it.value) },
                            allocation.amount.minor.value,
                        ),
                    )
                }
            }
        }
    }

    private fun reversePreviousAllocations(
        database: SupportSQLiteDatabase,
        transactionId: Long,
        revisionId: Long,
        previousRevisionUid: ByteArray,
    ) {
        database.queryList(
            "SELECT cpa.id,cpa.statement_id,cpa.amount_minor FROM credit_payment_allocation cpa " +
                "JOIN transaction_revision tr ON tr.id=cpa.payment_revision_id " +
                "WHERE tr.uid=? AND cpa.reversal_of_id IS NULL ORDER BY cpa.id",
            arrayOf(previousRevisionUid),
        ) { cursor -> Triple(cursor.getLong(0), cursor.nullableLong("statement_id"), cursor.getLong(2)) }
            .forEach { (originalId, statementId, amountMinor) ->
                database.execSQL(
                    "INSERT INTO credit_payment_allocation(payment_transaction_id,payment_revision_id,statement_id,amount_minor,reversal_of_id) " +
                        "VALUES(?,?,?,?,?)",
                    arrayOf<Any?>(transactionId, revisionId, statementId, amountMinor, originalId),
                )
            }
    }
}
