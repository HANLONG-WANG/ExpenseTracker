package app.ledger.finance.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.finance.application.ApplyLoanSimulationRequest
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.FinancialCommandType
import app.ledger.finance.domain.StableEntityReference

/** Verifies a completed loan-application replay before stale schedule checks are evaluated. */
internal object LoanReplayReceiptVerifier {
    fun payment(database: SupportSQLiteDatabase, request: ApplyLoanSimulationRequest): CommandReceipt? {
        val stored = database.commandReceipt(request.mutation.ids.commandId) ?: return null
        val transactionMatches = database.queryOne(
            "SELECT tr.uid revision_uid,bt.uid transaction_uid,lc.uid contract_uid,ua.uid payment_uid " +
                "FROM business_transaction bt JOIN transaction_revision tr ON tr.id=bt.current_revision_id " +
                "JOIN loan_payment_revision_detail lpd ON lpd.revision_id=tr.id " +
                "JOIN loan_contract lc ON lc.id=lpd.loan_contract_id " +
                "JOIN user_account ua ON ua.id=lpd.payment_account_id WHERE bt.uid=?",
            arrayOf(request.transactionIds.transactionId.bytes),
        ) { cursor ->
            cursor.stableId("revision_uid") == request.transactionIds.revisionId &&
                cursor.stableId("transaction_uid") == request.transactionIds.transactionId &&
                cursor.stableId("contract_uid") == request.mutation.ids.contractId &&
                cursor.stableId("payment_uid") == request.payment.accountId
        } == true
        val mutationIdsMatch = request.mutation.tranches.all { tranche ->
            database.queryOne(
                "SELECT lt.uid tranche_uid,ltr.uid terms_uid,lsr.uid schedule_uid " +
                    "FROM loan_tranche lt JOIN loan_terms_revision ltr ON ltr.tranche_id=lt.id " +
                    "JOIN loan_schedule_revision lsr ON lsr.tranche_id=lt.id AND lsr.terms_revision_id=ltr.id " +
                    "WHERE lt.uid=? AND ltr.uid=? AND lsr.uid=?",
                arrayOf(tranche.ids.trancheId.bytes, tranche.ids.termsRevisionId.bytes, tranche.ids.scheduleRevisionId.bytes),
            ) { true } == true && database.queryList(
                "SELECT id FROM loan_schedule_item WHERE schedule_revision_id=" +
                    "(SELECT id FROM loan_schedule_revision WHERE uid=?) ORDER BY installment_no",
                arrayOf(tranche.ids.scheduleRevisionId.bytes),
            ) { it.getLong(0) } == tranche.ids.scheduleItemIds.map { it.internalId() }
        }
        val identityMatches = stored.commandTypeOrdinal == FinancialCommandType.APPLY_LOAN_PAYMENT.ordinal &&
            stored.commitId.value == request.mutation.ids.commitId &&
            stored.primaryEntityId == request.transactionIds.transactionId
        if (!identityMatches || !transactionMatches || !mutationIdsMatch) {
            abort(app.ledger.finance.domain.DomainViolation.DuplicateCommandPayloadMismatch)
        }
        return CommandReceipt(
            stored.commandId,
            FinancialCommandType.APPLY_LOAN_PAYMENT,
            stored.payloadHash,
            stored.commitId,
            StableEntityReference(EntityType.TRANSACTION, stored.primaryEntityId),
            stored.executedAt,
        )
    }
}
