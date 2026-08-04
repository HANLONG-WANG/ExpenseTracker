package app.ledger.finance.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.StableId
import app.ledger.finance.application.ApplyInstallmentRefundRequest
import app.ledger.finance.application.ApplyInstallmentSettlementRequest
import app.ledger.finance.domain.AmountRole
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.FinancialCommandType
import app.ledger.finance.domain.StableEntityReference

internal object InstallmentReplayReceiptVerifier {
    fun settlement(
        database: SupportSQLiteDatabase,
        request: ApplyInstallmentSettlementRequest,
    ): CommandReceipt? {
        val stored = database.commandReceipt(request.ids.mutation.commandId) ?: return null
        val actual = readSettlement(database, request)
        val expected = SettlementReplayIdentity(
            request.ids.transactionId,
            request.ids.transactionRevisionId,
            request.settlementDate.toStorageInt(),
            request.changedAt.toStorageEpochMillis(),
            request.payment.accountId,
            request.credit.accountId,
            request.ids.mutation.planId,
            request.ids.mutation.planRevisionId,
            request.ids.mutation.scheduleRevisionId,
            request.revisionNumber,
            request.scheduleRevisionNumber,
            request.payment.accountMinor,
            request.credit.accountMinor,
            request.settlementFee?.accountMinor ?: 0L,
        )
        val storedIdentityMatches =
            stored.commandTypeOrdinal == FinancialCommandType.APPLY_INSTALLMENT_SETTLEMENT.ordinal &&
                stored.commitId.value == request.ids.mutation.commitId &&
                stored.primaryEntityId == request.ids.transactionId
        if (!storedIdentityMatches || actual != expected) {
            abort(app.ledger.finance.domain.DomainViolation.DuplicateCommandPayloadMismatch)
        }
        return stored.installmentReceipt(FinancialCommandType.APPLY_INSTALLMENT_SETTLEMENT, EntityType.TRANSACTION)
    }

    fun refund(
        database: SupportSQLiteDatabase,
        request: ApplyInstallmentRefundRequest,
    ): CommandReceipt? {
        val stored = database.commandReceipt(request.ids.commandId) ?: return null
        val actual = readRefund(database, request)
        val expected = RefundReplayIdentity(
            request.ids.planId,
            request.ids.planRevisionId,
            request.revisionNumber,
            request.ids.scheduleRevisionId,
            request.scheduleRevisionNumber,
            request.changedAt.toStorageEpochMillis(),
            request.refundTransactionId,
            request.refundRevisionId,
            request.refundedPrincipalMinor,
            request.refundedFeeMinor,
            request.expectedRevisionId,
        )
        val itemIds = database.queryList(
            "SELECT id FROM installment_schedule_item WHERE schedule_revision_id=" +
                "(SELECT id FROM installment_schedule_revision WHERE uid=?) ORDER BY installment_no",
            arrayOf(request.ids.scheduleRevisionId.bytes),
        ) { it.getLong(0) }
        val storedIdentityMatches = stored.commandTypeOrdinal == FinancialCommandType.SAVE_INSTALLMENT_PLAN.ordinal &&
            stored.commitId.value == request.ids.commitId && stored.primaryEntityId == request.ids.planId
        val itemIdentityMatches = itemIds == request.ids.scheduleItemIds.map(StableId::internalId)
        if (!storedIdentityMatches || !itemIdentityMatches || actual != expected) {
            abort(app.ledger.finance.domain.DomainViolation.DuplicateCommandPayloadMismatch)
        }
        return stored.installmentReceipt(FinancialCommandType.SAVE_INSTALLMENT_PLAN, EntityType.INSTALLMENT_PLAN)
    }

    private fun readSettlement(
        database: SupportSQLiteDatabase,
        request: ApplyInstallmentSettlementRequest,
    ): SettlementReplayIdentity? = database.queryOne(
        "SELECT bt.uid transaction_uid,tr.uid transaction_revision_uid,tr.local_date,tr.occurred_at," +
            "payment.uid payment_uid,credit.uid credit_uid,ip.uid plan_uid,ipr.uid plan_revision_uid," +
            "isr.uid schedule_revision_uid,ipr.revision_no,isr.revision_no schedule_revision_no," +
            "COALESCE((SELECT amount_minor FROM revision_amount WHERE revision_id=tr.id AND role=? AND representation=1),0) outgoing," +
            "COALESCE((SELECT amount_minor FROM revision_amount WHERE revision_id=tr.id AND role=? AND representation=1),0) incoming," +
            "COALESCE((SELECT amount_minor FROM revision_amount WHERE revision_id=tr.id AND role=? AND representation=1),0) fee " +
            "FROM business_transaction bt JOIN transaction_revision tr ON tr.id=bt.current_revision_id " +
            "JOIN credit_payment_revision_detail cpd ON cpd.revision_id=tr.id " +
            "JOIN user_account payment ON payment.id=cpd.payment_account_id " +
            "JOIN user_account credit ON credit.id=cpd.credit_account_id " +
            "JOIN installment_plan ip ON ip.uid=? JOIN installment_plan_revision ipr ON ipr.id=ip.current_revision_id " +
            "JOIN installment_schedule_revision isr ON isr.plan_id=ip.id AND isr.revision_no=? WHERE bt.uid=?",
        arrayOf(
            AmountRole.OUTGOING.ordinal,
            AmountRole.INCOMING.ordinal,
            AmountRole.FEE.ordinal,
            request.ids.mutation.planId.bytes,
            request.scheduleRevisionNumber,
            request.ids.transactionId.bytes,
        ),
    ) { cursor ->
        SettlementReplayIdentity(
            cursor.stableId("transaction_uid"),
            cursor.stableId("transaction_revision_uid"),
            cursor.getInt(cursor.getColumnIndexOrThrow("local_date")),
            cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at")),
            cursor.stableId("payment_uid"),
            cursor.stableId("credit_uid"),
            cursor.stableId("plan_uid"),
            cursor.stableId("plan_revision_uid"),
            cursor.stableId("schedule_revision_uid"),
            cursor.getInt(cursor.getColumnIndexOrThrow("revision_no")),
            cursor.getInt(cursor.getColumnIndexOrThrow("schedule_revision_no")),
            cursor.getLong(cursor.getColumnIndexOrThrow("outgoing")),
            cursor.getLong(cursor.getColumnIndexOrThrow("incoming")),
            cursor.getLong(cursor.getColumnIndexOrThrow("fee")),
        )
    }

    private fun readRefund(
        database: SupportSQLiteDatabase,
        request: ApplyInstallmentRefundRequest,
    ): RefundReplayIdentity? = database.queryOne(
        "SELECT ip.uid plan_uid,ipr.uid plan_revision_uid,ipr.revision_no,isr.uid schedule_revision_uid," +
            "isr.revision_no schedule_revision_no,isr.generated_at,refund.uid refund_uid,refund_revision.uid refund_revision_uid," +
            "ira.principal_minor,ira.fee_minor," +
            "(SELECT prior.uid FROM installment_plan_revision prior WHERE prior.plan_id=ip.id " +
            "AND prior.revision_no=ipr.revision_no-1) expected_uid " +
            "FROM installment_plan ip JOIN installment_plan_revision ipr ON ipr.id=ip.current_revision_id " +
            "JOIN installment_schedule_revision isr ON isr.plan_id=ip.id AND isr.revision_no=? " +
            "JOIN installment_refund_allocation ira ON ira.plan_id=ip.id " +
            "JOIN business_transaction refund ON refund.id=ira.refund_transaction_id " +
            "JOIN transaction_revision refund_revision ON refund_revision.id=ira.refund_revision_id " +
            "WHERE ip.uid=? AND ipr.uid=? AND refund.uid=? AND refund_revision.uid=?",
        arrayOf(
            request.scheduleRevisionNumber,
            request.ids.planId.bytes,
            request.ids.planRevisionId.bytes,
            request.refundTransactionId.bytes,
            request.refundRevisionId.bytes,
        ),
    ) { cursor ->
        RefundReplayIdentity(
            cursor.stableId("plan_uid"),
            cursor.stableId("plan_revision_uid"),
            cursor.getInt(cursor.getColumnIndexOrThrow("revision_no")),
            cursor.stableId("schedule_revision_uid"),
            cursor.getInt(cursor.getColumnIndexOrThrow("schedule_revision_no")),
            cursor.getLong(cursor.getColumnIndexOrThrow("generated_at")),
            cursor.stableId("refund_uid"),
            cursor.stableId("refund_revision_uid"),
            cursor.getLong(cursor.getColumnIndexOrThrow("principal_minor")),
            cursor.getLong(cursor.getColumnIndexOrThrow("fee_minor")),
            cursor.stableId("expected_uid"),
        )
    }
}

private fun StoredCommandReceipt.installmentReceipt(
    type: FinancialCommandType,
    entityType: EntityType,
): CommandReceipt = CommandReceipt(
    commandId,
    type,
    payloadHash,
    commitId,
    primaryEntityId?.let { StableEntityReference(entityType, it) },
    executedAt,
)

private data class SettlementReplayIdentity(
    val transactionId: StableId,
    val transactionRevisionId: StableId,
    val localDate: Int,
    val occurredAt: Long,
    val paymentAccountId: StableId,
    val creditAccountId: StableId,
    val planId: StableId,
    val planRevisionId: StableId,
    val scheduleRevisionId: StableId,
    val revisionNumber: Int,
    val scheduleRevisionNumber: Int,
    val outgoingMinor: Long,
    val incomingMinor: Long,
    val feeMinor: Long,
)

private data class RefundReplayIdentity(
    val planId: StableId,
    val planRevisionId: StableId,
    val revisionNumber: Int,
    val scheduleRevisionId: StableId,
    val scheduleRevisionNumber: Int,
    val generatedAt: Long,
    val refundTransactionId: StableId,
    val refundRevisionId: StableId,
    val principalMinor: Long,
    val feeMinor: Long,
    val expectedRevisionId: StableId,
)
