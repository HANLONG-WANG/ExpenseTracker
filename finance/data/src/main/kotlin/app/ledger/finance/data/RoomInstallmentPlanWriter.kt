package app.ledger.finance.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.domain.FinancialMutationPlan

internal class RoomInstallmentPlanWriter {
    fun write(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        plan.installmentPlanMutations.forEach { mutation ->
            val aggregate = mutation.plan
            val planId = if (mutation.expectedRevisionId == null) {
                database.allocateInternalId("installment_plan", aggregate.id.value).also { id ->
                    database.execSQL(
                        "INSERT INTO installment_plan(id,uid,purchase_transaction_id,credit_account_id,currency_code," +
                            "original_principal_minor,term_count,current_revision_id,status) VALUES(?,?,?,?,?,?,?,NULL,?)",
                        arrayOf<Any>(
                            id,
                            aggregate.id.value.bytes,
                            database.requireInternalId("business_transaction", aggregate.purchaseTransactionId.value),
                            database.requireInternalId("user_account", aggregate.creditAccountId.value),
                            aggregate.currency.value,
                            aggregate.originalPrincipalMinor,
                            aggregate.termCount,
                            aggregate.status.ordinal,
                        ),
                    )
                }
            } else {
                database.requireInternalId("installment_plan", aggregate.id.value)
            }
            val revision = mutation.revision
            val revisionId = database.allocateInternalId("installment_plan_revision", revision.id.value)
            database.execSQL(
                "INSERT INTO installment_plan_revision(id,uid,plan_id,revision_no,fee_rate_type,fixed_fee_per_term_minor," +
                    "first_term_fee_minor,remaining_principal_rate_decimal,effective_annual_rate_decimal,prepayment_policy," +
                    "prepayment_fee_minor,refund_policy,rounding_mode,created_commit_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    revisionId,
                    revision.id.value.bytes,
                    planId,
                    revision.revisionNumber,
                    revision.feeRateType.ordinal,
                    revision.fixedFeePerTermMinor,
                    revision.firstTermFeeMinor,
                    revision.remainingPrincipalRate?.annualDecimal?.toPlainString(),
                    revision.effectiveAnnualRate?.annualDecimal?.toPlainString(),
                    revision.prepaymentPolicy.ordinal,
                    revision.prepaymentFeeMinor,
                    revision.refundPolicy.ordinal,
                    revision.roundingMode.ordinal,
                    database.commitId(revision.createdCommitId),
                ),
            )
            val schedule = mutation.scheduleRevision
            val scheduleId = database.allocateInternalId("installment_schedule_revision", schedule.id.value)
            database.execSQL(
                "INSERT INTO installment_schedule_revision(id,uid,plan_id,revision_no,reason,generated_at,created_commit_id) " +
                    "VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any>(
                    scheduleId,
                    schedule.id.value.bytes,
                    planId,
                    schedule.revisionNumber,
                    schedule.reason.ordinal,
                    schedule.generatedAt.toStorageEpochMillis(),
                    database.commitId(schedule.createdCommitId),
                ),
            )
            schedule.items.forEach { item ->
                database.execSQL(
                    "INSERT INTO installment_schedule_item(id,schedule_revision_id,installment_no,statement_date,principal_minor," +
                        "interest_minor,fee_minor,remaining_principal_minor) VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf<Any>(
                        item.id.value.internalId(),
                        scheduleId,
                        item.installmentNumber,
                        item.statementDate.toStorageInt(),
                        item.principalMinor,
                        item.interestMinor,
                        item.feeMinor,
                        item.remainingPrincipalMinor,
                    ),
                )
            }
            mutation.refundAllocation?.let { allocation ->
                if (allocation.reversalOfId != null) abort(FinanceDataError.CorruptData)
                database.execSQL(
                    "INSERT INTO installment_refund_allocation(refund_transaction_id,refund_revision_id,plan_id,principal_minor," +
                        "fee_minor,reversal_of_id) VALUES(?,?,?,?,?,NULL)",
                    arrayOf<Any>(
                        database.requireInternalId("business_transaction", allocation.refundTransactionId.value),
                        database.requireInternalId("transaction_revision", allocation.refundRevisionId.value),
                        planId,
                        allocation.principalMinor,
                        allocation.feeMinor,
                    ),
                )
            }
            val changed = if (mutation.expectedRevisionId == null) {
                database.compileStatement(
                    "UPDATE installment_plan SET current_revision_id=?,status=? WHERE id=? AND current_revision_id IS NULL",
                ).apply {
                    bindLong(CURRENT_REVISION_BIND, revisionId)
                    bindLong(CURRENT_STATUS_BIND, aggregate.status.ordinal.toLong())
                    bindLong(CURRENT_PLAN_BIND, planId)
                }.executeUpdateDelete()
            } else {
                database.compileStatement(
                    "UPDATE installment_plan SET term_count=?,current_revision_id=?,status=? WHERE id=? AND current_revision_id=?",
                ).apply {
                    bindLong(EDIT_TERM_COUNT_BIND, aggregate.termCount.toLong())
                    bindLong(EDIT_REVISION_BIND, revisionId)
                    bindLong(EDIT_STATUS_BIND, aggregate.status.ordinal.toLong())
                    bindLong(EDIT_PLAN_BIND, planId)
                    bindLong(
                        EDIT_EXPECTED_REVISION_BIND,
                        database.requireInternalId(
                            "installment_plan_revision",
                            requireNotNull(mutation.expectedRevisionId).value,
                        ),
                    )
                }.executeUpdateDelete()
            }
            if (changed != 1) abort(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
        }
    }

    private companion object {
        const val CURRENT_REVISION_BIND = 1
        const val CURRENT_STATUS_BIND = 2
        const val CURRENT_PLAN_BIND = 3
        const val EDIT_TERM_COUNT_BIND = 1
        const val EDIT_REVISION_BIND = 2
        const val EDIT_STATUS_BIND = 3
        const val EDIT_PLAN_BIND = 4
        const val EDIT_EXPECTED_REVISION_BIND = 5
    }
}
