@file:Suppress("LargeClass", "LongMethod", "LongParameterList", "TooManyFunctions")

package app.ledger.finance.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.domain.BalanceAdjustmentPayload
import app.ledger.finance.domain.CreditPaymentPayload
import app.ledger.finance.domain.EffectPolarity
import app.ledger.finance.domain.ExpensePayer
import app.ledger.finance.domain.ExpensePayload
import app.ledger.finance.domain.FinancialMutationPlan
import app.ledger.finance.domain.FxExchangePayload
import app.ledger.finance.domain.IncomePayload
import app.ledger.finance.domain.LoanDisbursementPayload
import app.ledger.finance.domain.LoanPaymentPayload
import app.ledger.finance.domain.OpeningBalancePayload
import app.ledger.finance.domain.RefundPayload
import app.ledger.finance.domain.SettlementPaymentPayload
import app.ledger.finance.domain.TransactionRevision
import app.ledger.finance.domain.TransferPayload

internal class RoomFinancialPlanWriter {
    private val budgetPlanWriter = RoomBudgetPlanWriter()
    private val creditPlanWriter = RoomCreditPlanWriter()
    private val installmentPlanWriter = RoomInstallmentPlanWriter()
    private val loanContractWriter = RoomLoanContractWriter()

    fun write(
        database: SupportSQLiteDatabase,
        plan: FinancialMutationPlan,
        checkpoint: (FinancialCommitPhase) -> Unit,
        beforeCommitHeader: (SupportSQLiteDatabase, FinancialMutationPlan) -> Unit = { _, _ -> },
        afterCommitHeader: (SupportSQLiteDatabase, FinancialMutationPlan) -> Unit = { _, _ -> },
        afterFinancialWrite: (SupportSQLiteDatabase, FinancialMutationPlan) -> Unit = { _, _ -> },
    ) {
        beforeCommitHeader(database, plan)
        insertCommit(database, plan)
        checkpoint(FinancialCommitPhase.AFTER_COMMIT_HEADER)
        afterCommitHeader(database, plan)
        budgetPlanWriter.write(database, plan)
        creditPlanWriter.write(database, plan)
        installmentPlanWriter.write(database, plan)
        loanContractWriter.write(database, plan)
        insertTransactionShells(database, plan)
        insertSettlementPaymentRecords(database, plan)
        insertFxSnapshots(database, plan)
        insertRevisions(database, plan)
        insertAmounts(database, plan)
        insertJournals(database, plan)
        insertSubledgerFacts(database, plan)
        insertEffects(database, plan)
        insertEntityChanges(database, plan)
        insertPurgeTombstones(database, plan)
        checkpoint(FinancialCommitPhase.AFTER_IMMUTABLE_FACTS)
        updateCurrentTransactions(database, plan)
        afterFinancialWrite(database, plan)
    }

    private fun insertPurgeTombstones(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        plan.purgeTombstones.forEach { tombstone ->
            database.execSQL(
                "INSERT INTO purge_tombstone(entity_type,entity_uid,purge_commit_id,purged_at,purge_generation) " +
                    "VALUES(?,?,?,?,?) ON CONFLICT(entity_type,entity_uid) DO UPDATE SET " +
                    "purge_commit_id=excluded.purge_commit_id,purged_at=excluded.purged_at," +
                    "purge_generation=excluded.purge_generation WHERE excluded.purge_generation>purge_tombstone.purge_generation",
                arrayOf<Any>(
                    tombstone.entity.type.ordinal,
                    tombstone.entity.stableId.bytes,
                    database.commitId(tombstone.purgeCommitId),
                    tombstone.purgedAt.toStorageEpochMillis(),
                    tombstone.purgeGeneration,
                ),
            )
        }
    }

    private fun insertSettlementPaymentRecords(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        plan.settlementPaymentRecords.forEach { record ->
            database.execSQL(
                "INSERT INTO settlement_payment_record(id, uid, activity_id, payer_participant_id, payee_participant_id, " +
                    "amount_minor, currency_code, occurred_at, linked_transaction_id, created_commit_id, reversal_of_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    database.allocateInternalId("settlement_payment_record", record.id.value),
                    record.id.value.bytes,
                    database.requireInternalId("settlement_activity", record.activityId.value),
                    database.requireInternalId("participant", record.payerParticipantId.value),
                    database.requireInternalId("participant", record.payeeParticipantId.value),
                    record.amount.minor.value,
                    record.amount.currency.value,
                    record.occurredAt.instant.toStorageEpochMillis(),
                    record.linkedTransactionId?.let { database.requireInternalId("business_transaction", it.value) },
                    database.commitId(record.createdCommitId),
                    record.reversalOfId?.let { database.requireInternalId("settlement_payment_record", it.value) },
                ),
            )
        }
    }

    private fun insertCommit(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        val commitId = database.allocateInternalId("book_commit", plan.commit.id.value)
        database.execSQL(
            "INSERT INTO book_commit(id, uid, local_revision, kind, command_uid, device_instance_uid, created_at, root_hash) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(
                commitId,
                plan.commit.id.value.bytes,
                plan.targetLocalRevision.value,
                plan.commit.kind.ordinal,
                plan.commandId.stableId.bytes,
                plan.commit.deviceInstanceId.value.bytes,
                plan.commit.createdAt.toStorageEpochMillis(),
                plan.commit.rootHash.bytes,
            ),
        )
        plan.commit.parentIds.forEachIndexed { index, parent ->
            database.execSQL(
                "INSERT INTO book_commit_parent(commit_id, parent_commit_id, ordinal) VALUES (?, ?, ?)",
                arrayOf<Any>(commitId, database.commitId(parent), index),
            )
        }
    }

    private fun insertTransactionShells(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        plan.transactions.forEach { transaction ->
            val existing = database.queryOne(
                "SELECT id FROM business_transaction WHERE uid = ?",
                arrayOf(transaction.id.value.bytes),
            ) { it.getLong(0) }
            if (existing == null) {
                database.execSQL(
                    "INSERT INTO business_transaction(id, uid, kind, current_revision_id, lifecycle_state, created_commit_id, " +
                        "last_commit_id, row_version, trashed_at, purge_after, content_hash) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        database.allocateInternalId("business_transaction", transaction.id.value),
                        transaction.id.value.bytes,
                        transaction.kind.ordinal,
                        transaction.lifecycleState.ordinal,
                        database.commitId(transaction.createdCommitId),
                        database.commitId(transaction.lastCommitId),
                        transaction.rowVersion.value,
                        transaction.trashedAt?.toStorageEpochMillis(),
                        transaction.purgeAfter?.toStorageEpochMillis(),
                        transaction.contentHash.value.bytes,
                    ),
                )
            }
        }
    }

    private fun insertFxSnapshots(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        plan.fxRateSnapshots.forEach { snapshot ->
            database.execSQL(
                "INSERT INTO fx_rate_snapshot(id, uid, source_currency, target_currency, rate_decimal, provider, quoted_at, " +
                    "fetched_at, source_type, manual_override, stale_at_use, created_commit_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    database.allocateInternalId("fx_rate_snapshot", snapshot.id.value),
                    snapshot.id.value.bytes,
                    snapshot.evidence.sourceCurrency.value,
                    snapshot.evidence.targetCurrency.value,
                    snapshot.evidence.rate.toPlainString(),
                    snapshot.evidence.provider.value,
                    snapshot.evidence.quotedAt?.toStorageEpochMillis(),
                    snapshot.evidence.fetchedAt?.toStorageEpochMillis(),
                    snapshot.evidence.source.ordinal,
                    snapshot.evidence.manuallyOverridden.toSqlInt(),
                    snapshot.staleAtUse.toSqlInt(),
                    database.commitId(snapshot.createdCommitId),
                ),
            )
        }
    }

    private fun insertRevisions(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        plan.revisions.forEach { revision ->
            val revisionId = database.allocateInternalId("transaction_revision", revision.id.value)
            val transactionId = database.requireInternalId("business_transaction", revision.transactionId.value)
            database.execSQL(
                "INSERT INTO transaction_revision(id, uid, transaction_id, revision_no, action, resulting_state, previous_revision_id, " +
                    "created_commit_id, created_at, occurred_at, zone_id, local_date, category_id, statistical_nature_snapshot, " +
                    "merchant_id, project_id, goal_id, location_record_id, note, amount_expression, source_type, source_reference_uid, content_hash) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    revisionId,
                    revision.id.value.bytes,
                    transactionId,
                    revision.revisionNumber,
                    revision.action.ordinal,
                    revision.resultingState.ordinal,
                    revision.previousRevisionId?.let { database.requireInternalId("transaction_revision", it.value) },
                    database.commitId(revision.createdCommitId),
                    revision.createdAt.toStorageEpochMillis(),
                    revision.occurredAt.instant.toStorageEpochMillis(),
                    revision.occurredAt.zoneId.id,
                    revision.occurredAt.localDate.toStorageInt(),
                    database.optionalInternalId("category", revision.categoryId?.value),
                    revision.statisticalNatureSnapshot?.ordinal,
                    database.optionalInternalId("merchant", revision.merchantId?.value),
                    database.optionalInternalId("project", revision.projectId?.value),
                    database.optionalInternalId("goal", revision.goalId?.value),
                    database.optionalInternalId("location_record", revision.locationRecordId?.value),
                    revision.note,
                    revision.amountExpression,
                    revision.source.ordinal,
                    revision.sourceReferenceId?.bytes,
                    revision.contentHash.value.bytes,
                ),
            )
            insertRevisionDetail(database, revisionId, revision)
            revision.attachmentIds.forEachIndexed { index, attachmentId ->
                database.execSQL(
                    "INSERT INTO transaction_revision_attachment(revision_id, attachment_id, sort_order) VALUES (?, ?, ?)",
                    arrayOf<Any>(revisionId, database.requireInternalId("attachment", attachmentId.value), index),
                )
            }
            val settlement = when (val payload = revision.payload) {
                is ExpensePayload -> Triple(
                    payload.settlementActivityId
                        ?: (payload.payer as? ExpensePayer.ExternalParticipant)?.activityId,
                    payload.settlementShares,
                    payload.primaryAmount.currency,
                )
                is RefundPayload -> Triple(
                    payload.settlementActivityId,
                    payload.settlementShares,
                    payload.allocations.firstOrNull()?.amountInOriginalCurrency?.currency
                        ?: payload.receivingAmount.amount.currency,
                )
                else -> Triple(null, emptyList(), null)
            }
            settlement.second.forEach { share ->
                val activityId = settlement.first ?: abort(FinanceDataError.CorruptData)
                database.execSQL(
                    "INSERT INTO transaction_revision_settlement_share(revision_id, activity_id, participant_id, paid_minor, " +
                        "owed_minor, settlement_currency, weight_decimal, rounding_adjustment_minor) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        revisionId,
                        database.requireInternalId("settlement_activity", activityId.value),
                        database.requireInternalId("participant", share.participantId.value),
                        share.paidMinor,
                        share.owedMinor,
                        requireNotNull(settlement.third).value,
                        share.weight?.toPlainString(),
                        share.roundingAdjustmentMinor,
                    ),
                )
            }
        }
    }

    private fun insertRevisionDetail(database: SupportSQLiteDatabase, revisionId: Long, revision: TransactionRevision) {
        when (val payload = revision.payload) {
            is ExpensePayload -> {
                val local = payload.payer as? ExpensePayer.LocalAccount
                val external = payload.payer as? ExpensePayer.ExternalParticipant
                database.execSQL(
                    "INSERT INTO expense_revision_detail(revision_id, payer_kind, payer_account_id, payer_card_id, " +
                        "payer_participant_id, settlement_activity_id, installment_plan_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        revisionId,
                        if (local != null) 0 else 1,
                        local?.accountAmount?.accountId?.let { database.requireInternalId("user_account", it.value) },
                        local?.cardId?.let { database.requireInternalId("payment_card", it.value) },
                        external?.participantId?.let { database.requireInternalId("participant", it.value) },
                        (payload.settlementActivityId ?: external?.activityId)?.let {
                            database.requireInternalId("settlement_activity", it.value)
                        },
                        payload.installmentPlanId?.let { database.requireInternalId("installment_plan", it.value) },
                    ),
                )
            }
            is IncomePayload -> detail(
                database,
                "income_revision_detail",
                revisionId,
                "receiving_account_id",
                database.requireInternalId("user_account", payload.receivingAmount.accountId.value),
            )
            is TransferPayload -> database.execSQL(
                "INSERT INTO transfer_revision_detail(revision_id, from_account_id, to_account_id, source_card_id) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(
                    revisionId,
                    database.requireInternalId("user_account", payload.outgoing.accountId.value),
                    database.requireInternalId("user_account", payload.incoming.accountId.value),
                    payload.sourceCardId?.let { database.requireInternalId("payment_card", it.value) },
                ),
            )
            is RefundPayload -> database.execSQL(
                "INSERT INTO refund_revision_detail(revision_id, receiving_account_id, receiving_card_id, independent, budget_policy, target_month, allow_excess) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    revisionId,
                    database.requireInternalId("user_account", payload.receivingAmount.accountId.value),
                    payload.receivingCardId?.let { database.requireInternalId("payment_card", it.value) },
                    payload.independent.toSqlInt(),
                    payload.budgetPolicy.ordinal,
                    revision.budgetMonth?.toStorageInt(),
                    payload.allowExcessOverride.toSqlInt(),
                ),
            )
            is CreditPaymentPayload -> database.execSQL(
                "INSERT INTO credit_payment_revision_detail(revision_id, payment_account_id, credit_account_id, generation_mode) VALUES (?, ?, ?, ?)",
                arrayOf<Any>(
                    revisionId,
                    database.requireInternalId("user_account", payload.payment.accountId.value),
                    database.requireInternalId("user_account", payload.creditAccountId.value),
                    payload.generationMode.ordinal,
                ),
            )
            is LoanDisbursementPayload -> database.execSQL(
                "INSERT INTO loan_disbursement_revision_detail(revision_id, loan_contract_id, receiving_account_id) VALUES (?, ?, ?)",
                arrayOf<Any>(
                    revisionId,
                    database.requireInternalId("loan_contract", payload.loanContractId.value),
                    database.requireInternalId("user_account", payload.receivingAmount.accountId.value),
                ),
            )
            is LoanPaymentPayload -> database.execSQL(
                "INSERT INTO loan_payment_revision_detail(revision_id, loan_contract_id, payment_account_id, schedule_revision_id) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(
                    revisionId,
                    database.requireInternalId("loan_contract", payload.loanContractId.value),
                    database.requireInternalId("user_account", payload.payment.accountId.value),
                    payload.scheduleRevisionId?.let { database.requireInternalId("loan_schedule_revision", it.value) },
                ),
            )
            is BalanceAdjustmentPayload -> database.execSQL(
                "INSERT INTO balance_adjustment_revision_detail(revision_id, account_id, direction, checkpoint_id) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(
                    revisionId,
                    database.requireInternalId("user_account", payload.accountAmount.accountId.value),
                    payload.direction.ordinal,
                    payload.checkpointId?.let { database.requireInternalId("account_balance_checkpoint", it) },
                ),
            )
            is FxExchangePayload -> database.execSQL(
                "INSERT INTO fx_exchange_revision_detail(revision_id, from_account_id, to_account_id, valuation_policy) VALUES (?, ?, ?, ?)",
                arrayOf<Any>(
                    revisionId,
                    database.requireInternalId("user_account", payload.outgoing.accountId.value),
                    database.requireInternalId("user_account", payload.incoming.accountId.value),
                    payload.valuationPolicy.ordinal,
                ),
            )
            is SettlementPaymentPayload -> database.execSQL(
                "INSERT INTO settlement_payment_revision_detail(revision_id, activity_id, payer_participant_id, payee_participant_id, local_account_id) " +
                    "VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    revisionId,
                    database.requireInternalId("settlement_activity", payload.activityId.value),
                    database.requireInternalId("participant", payload.payerParticipantId.value),
                    database.requireInternalId("participant", payload.payeeParticipantId.value),
                    payload.localAccountAmount?.accountId?.let { database.requireInternalId("user_account", it.value) },
                ),
            )
            is OpeningBalancePayload -> database.execSQL(
                "INSERT INTO opening_balance_revision_detail(revision_id, account_id, balance_date) VALUES (?, ?, ?)",
                arrayOf<Any>(
                    revisionId,
                    database.requireInternalId("user_account", payload.accountAmount.accountId.value),
                    payload.balanceDate.toStorageInt(),
                ),
            )
        }
    }

    private fun detail(database: SupportSQLiteDatabase, table: String, revisionId: Long, column: String, value: Long) {
        database.execSQL("INSERT INTO $table(revision_id, $column) VALUES (?, ?)", arrayOf<Any>(revisionId, value))
    }

    private fun insertAmounts(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        plan.revisionAmounts.forEach { amount ->
            database.execSQL(
                "INSERT INTO revision_amount(revision_id, component_index, role, representation, amount_minor, currency_code, related_account_id, fx_rate_snapshot_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    database.requireInternalId("transaction_revision", amount.revisionId.value),
                    amount.componentIndex,
                    amount.role.ordinal,
                    amount.representation.ordinal,
                    amount.money.minor.value,
                    amount.money.currency.value,
                    amount.relatedAccountId?.let { database.requireInternalId("user_account", it.value) },
                    amount.fxRateSnapshotId?.let { database.requireInternalId("fx_rate_snapshot", it.value) },
                ),
            )
        }
    }

    private fun insertJournals(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        plan.journalBundles.forEach { bundle ->
            val entry = bundle.entry
            val entryId = database.allocateInternalId("journal_entry", entry.id.value)
            database.execSQL(
                "INSERT INTO journal_entry(id, uid, source_revision_id, applies_revision_id, entry_role, reverses_entry_id, effective_at, " +
                    "zone_id, local_date, base_currency, base_debit_total_minor, base_credit_total_minor, posting_count, rule_set_version, " +
                    "created_commit_id, content_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    entryId,
                    entry.id.value.bytes,
                    database.requireInternalId("transaction_revision", entry.sourceRevisionId.value),
                    database.requireInternalId("transaction_revision", entry.appliesRevisionId.value),
                    entry.role.ordinal,
                    entry.reversesEntryId?.let { database.requireInternalId("journal_entry", it.value) },
                    entry.effectiveAt.instant.toStorageEpochMillis(),
                    entry.effectiveAt.zoneId.id,
                    entry.effectiveAt.localDate.toStorageInt(),
                    entry.baseCurrency.value,
                    entry.baseDebitTotalMinor,
                    entry.baseCreditTotalMinor,
                    entry.postingCount,
                    entry.ruleSetVersion.value,
                    database.commitId(entry.createdCommitId),
                    entry.contentHash.value.bytes,
                ),
            )
            bundle.postings.forEach { posting ->
                database.execSQL(
                    "INSERT INTO posting(id, uid, journal_entry_id, line_no, ledger_account_id, side, account_amount_minor, " +
                        "account_currency, base_amount_minor, base_currency, valuation_rate_decimal, valuation_source, posting_role, reversal_of_posting_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        database.allocateInternalId("posting", posting.id.value),
                        posting.id.value.bytes,
                        entryId,
                        posting.lineNumber,
                        database.requireInternalId("ledger_account", posting.ledgerAccountId.value),
                        posting.side.ordinal,
                        posting.accountAmount.minor.value,
                        posting.accountAmount.currency.value,
                        posting.baseAmount.minor.value,
                        posting.baseAmount.currency.value,
                        posting.valuationRate?.toPlainString(),
                        posting.valuationSource.ordinal,
                        posting.role.ordinal,
                        posting.reversalOfPostingId?.let { database.requireInternalId("posting", it.value) },
                    ),
                )
            }
        }
    }

    private fun insertSubledgerFacts(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        plan.goalMovements.forEach { movement ->
            database.execSQL(
                "INSERT INTO goal_movement(id, uid, goal_id, kind, amount_minor, occurred_at, source_transaction_id, " +
                    "source_recurrence_occurrence_id, reversal_of_id, created_commit_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    database.allocateInternalId("goal_movement", movement.id.value), movement.id.value.bytes,
                    database.requireInternalId("goal", movement.goalId.value), movement.kind.ordinal,
                    movement.amount.minor.value, movement.occurredAt.instant.toStorageEpochMillis(),
                    movement.sourceTransactionId?.let { database.requireInternalId("business_transaction", it.value) },
                    movement.sourceRecurrenceOccurrenceId?.let { database.requireInternalId("recurrence_occurrence", it.value) },
                    movement.reversalOfId?.let { database.requireInternalId("goal_movement", it.value) },
                    database.commitId(movement.createdCommitId),
                ),
            )
        }
        plan.budgetAdjustments.forEach { adjustment ->
            database.execSQL(
                "INSERT INTO budget_adjustment(id, uid, year_month, scope, category_id, amount_base_minor, kind, created_commit_id, reversal_of_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    database.allocateInternalId("budget_adjustment", adjustment.id.value), adjustment.id.value.bytes,
                    adjustment.month.toStorageInt(),
                    adjustment.scope.ordinal, adjustment.categoryId?.let { database.requireInternalId("category", it.value) },
                    adjustment.amountBaseMinor, adjustment.kind.ordinal, database.commitId(adjustment.createdCommitId),
                    adjustment.reversalOfId?.let { database.requireInternalId("budget_adjustment", it.value) },
                ),
            )
        }
        plan.blobGcCandidates.forEach { candidate ->
            database.execSQL(
                "INSERT INTO blob_gc_candidate(blob_id, eligible_after, reason, last_checked_at) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(
                    database.requireInternalId("encrypted_blob", candidate.blobId.value),
                    candidate.eligibleAfter.toStorageEpochMillis(),
                    candidate.reason.ordinal,
                    candidate.lastCheckedAt?.toStorageEpochMillis(),
                ),
            )
        }
        RoomCreditPaymentAllocationWriter.write(database, plan)
        plan.revisions.forEach { revision ->
            val revisionId = database.requireInternalId("transaction_revision", revision.id.value)
            val transactionId = database.requireInternalId("business_transaction", revision.transactionId.value)
            when (val payload = revision.payload) {
                is RefundPayload -> Unit
                is LoanPaymentPayload -> payload.allocations.forEach { allocation ->
                    database.execSQL(
                        "INSERT INTO loan_actual_allocation(payment_transaction_id, payment_revision_id, tranche_id, schedule_item_id, component, " +
                            "amount_minor, base_amount_minor, reversal_of_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf<Any?>(
                            transactionId,
                            revisionId,
                            database.requireInternalId("loan_tranche", allocation.trancheId.value),
                            allocation.scheduleItemId?.let { database.requireInternalId("loan_schedule_item", it.value) },
                            allocation.component.ordinal,
                            allocation.amount.minor.value,
                            allocation.baseAmount.minor.value,
                            allocation.reversalOfId?.internalId(),
                        ),
                    )
                }
                else -> Unit
            }
        }
        RoomRefundFactWriter.write(database, plan)
    }

    private fun insertEffects(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        plan.economicEffects.forEach { effect ->
            database.execSQL(
                "INSERT INTO economic_effect(id, uid, source_entry_id, source_revision_id, reversal_of_id, polarity, nature, component, " +
                    "is_consumption, base_amount_minor, accrual_local_date, category_id, merchant_id, project_id, rule_set_version) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    database.allocateInternalId("economic_effect", effect.id.value), effect.id.value.bytes,
                    database.requireInternalId("journal_entry", effect.sourceEntryId.value),
                    database.requireInternalId("transaction_revision", effect.sourceRevisionId.value),
                    effect.reversalOfId?.let { database.requireInternalId("economic_effect", it.value) },
                    effect.polarity.sqlValue, effect.nature.ordinal, effect.component.ordinal, effect.isConsumption.toSqlInt(),
                    effect.baseAmount.minor.value, effect.accrualDate.toStorageInt(),
                    effect.categoryId?.let { database.requireInternalId("category", it.value) },
                    effect.merchantId?.let { database.requireInternalId("merchant", it.value) },
                    effect.projectId?.let { database.requireInternalId("project", it.value) }, effect.ruleSetVersion.value,
                ),
            )
        }
        plan.budgetEffects.forEach { effect ->
            database.execSQL(
                "INSERT INTO budget_effect(id, source_revision_id, reversal_of_id, polarity, kind, target_year_month, " +
                    "category_id, root_category_id, base_amount_minor, rule_set_version) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    effect.id.value.internalId(),
                    database.requireInternalId("transaction_revision", effect.sourceRevisionId.value),
                    effect.reversalOfId?.value?.internalId(),
                    effect.polarity.sqlValue, effect.kind.ordinal,
                    effect.targetMonth.toStorageInt(), effect.categoryId?.let { database.requireInternalId("category", it.value) },
                    effect.rootCategoryId?.let { database.requireInternalId("category", it.value) },
                    effect.baseAmount.minor.value, effect.ruleSetVersion.value,
                ),
            )
        }
        plan.projectEffects.forEach { effect ->
            database.execSQL(
                "INSERT INTO project_effect(id, project_id, source_revision_id, reversal_of_id, polarity, kind, " +
                    "base_amount_minor, monthly_budget_inclusion_snapshot, rule_set_version) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    effect.id.value.internalId(),
                    database.requireInternalId("project", effect.projectId.value),
                    database.requireInternalId("transaction_revision", effect.sourceRevisionId.value),
                    effect.reversalOfId?.value?.internalId(),
                    effect.polarity.sqlValue, effect.kind.ordinal,
                    effect.baseAmount.minor.value, effect.includedInMonthlyBudgetSnapshot.toSqlInt(), plan.ruleSetVersion.value,
                ),
            )
        }
        plan.goalEffects.forEach { effect ->
            database.execSQL(
                "INSERT INTO goal_effect(id, goal_id, source_revision_id, goal_movement_id, reversal_of_id, polarity, kind, amount_minor, currency_code, rule_set_version) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    effect.id.value.internalId(),
                    database.requireInternalId("goal", effect.goalId.value),
                    effect.sourceRevisionId?.let { database.requireInternalId("transaction_revision", it.value) },
                    effect.goalMovementId?.let { database.requireInternalId("goal_movement", it.value) },
                    effect.reversalOfId?.value?.internalId(),
                    effect.polarity.sqlValue, effect.kind.ordinal,
                    effect.amount.minor.value, effect.amount.currency.value, plan.ruleSetVersion.value,
                ),
            )
        }
        plan.statementEffects.forEach { effect ->
            database.execSQL(
                "INSERT INTO statement_effect(id, credit_account_id, statement_id, source_revision_id, reversal_of_id, " +
                    "kind, polarity, amount_minor, currency_code, manual_assignment, rule_set_version) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    effect.id.value.internalId(),
                    database.requireInternalId("user_account", effect.creditAccountId.value),
                    effect.statementId?.let { database.requireInternalId("credit_statement", it.value) },
                    database.requireInternalId("transaction_revision", effect.sourceRevisionId.value),
                    effect.reversalOfId?.value?.internalId(),
                    effect.kind.ordinal, effect.polarity.sqlValue,
                    effect.amount.minor.value, effect.amount.currency.value, effect.manualAssignment.toSqlInt(), plan.ruleSetVersion.value,
                ),
            )
        }
        plan.loanEffects.forEach { effect ->
            database.execSQL(
                "INSERT INTO loan_effect(id, loan_contract_id, loan_tranche_id, schedule_item_id, source_revision_id, reversal_of_id, kind, polarity, " +
                    "amount_minor, currency_code, base_amount_minor, rule_set_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    effect.id.value.internalId(),
                    database.requireInternalId("loan_contract", effect.loanContractId.value),
                    database.requireInternalId("loan_tranche", effect.loanTrancheId.value),
                    effect.scheduleItemId?.let { database.requireInternalId("loan_schedule_item", it.value) },
                    database.requireInternalId("transaction_revision", effect.sourceRevisionId.value),
                    effect.reversalOfId?.value?.internalId(),
                    effect.kind.ordinal, effect.polarity.sqlValue,
                    effect.amount.minor.value, effect.amount.currency.value, effect.baseAmount.minor.value, plan.ruleSetVersion.value,
                ),
            )
        }
        plan.settlementEffects.forEach { effect ->
            database.execSQL(
                "INSERT INTO settlement_effect(id, activity_id, participant_id, source_revision_id, " +
                    "settlement_payment_record_id, reversal_of_id, kind, signed_delta_minor, currency_code, rule_set_version) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    effect.id.value.internalId(),
                    database.requireInternalId("settlement_activity", effect.activityId.value),
                    database.requireInternalId("participant", effect.participantId.value),
                    effect.sourceRevisionId?.let { database.requireInternalId("transaction_revision", it.value) },
                    effect.settlementPaymentRecordId?.let { database.requireInternalId("settlement_payment_record", it.value) },
                    effect.reversalOfId?.value?.internalId(),
                    effect.kind.ordinal, effect.signedDeltaMinor,
                    effect.currency.value, plan.ruleSetVersion.value,
                ),
            )
        }
    }

    private fun insertEntityChanges(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        plan.entityChanges.forEach { change ->
            database.execSQL(
                "INSERT INTO entity_change(commit_id, entity_type, entity_uid, operation, before_hash, after_hash, entity_revision_uid) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    database.commitId(change.commitId),
                    change.entity.type.ordinal,
                    change.entity.stableId.bytes,
                    change.operation.ordinal,
                    change.beforeHash?.value?.bytes,
                    change.afterHash?.value?.bytes,
                    change.entityRevisionId?.value?.bytes,
                ),
            )
            val currentTable = CURRENT_ENTITY_TABLES[change.entity.type]
            val afterHash = change.afterHash
            if (currentTable != null && afterHash != null && change.entity.type != app.ledger.finance.domain.EntityType.TRANSACTION) {
                val changed = database.compileStatement(
                    "UPDATE $currentTable SET last_commit_id=?," +
                        "row_version=(SELECT COUNT(*) FROM entity_change WHERE entity_type=? AND entity_uid=? AND after_hash IS NOT NULL)," +
                        "content_hash=? WHERE uid=?",
                ).apply {
                    bindLong(1, database.commitId(change.commitId))
                    bindLong(2, change.entity.type.ordinal.toLong())
                    bindBlob(3, change.entity.stableId.bytes)
                    bindBlob(4, afterHash.value.bytes)
                    bindBlob(5, change.entity.stableId.bytes)
                }.executeUpdateDelete()
                if (changed != 1) abort(FinanceDataError.CorruptData)
            }
        }
    }

    private fun updateCurrentTransactions(database: SupportSQLiteDatabase, plan: FinancialMutationPlan) {
        plan.transactions.forEach { transaction ->
            database.execSQL(
                "UPDATE business_transaction SET current_revision_id = ?, lifecycle_state = ?, last_commit_id = ?, row_version = ?, " +
                    "trashed_at = ?, purge_after = ?, content_hash = ? WHERE uid = ?",
                arrayOf<Any?>(
                    database.requireInternalId("transaction_revision", transaction.currentRevisionId.value),
                    transaction.lifecycleState.ordinal,
                    database.commitId(transaction.lastCommitId),
                    transaction.rowVersion.value,
                    transaction.trashedAt?.toStorageEpochMillis(),
                    transaction.purgeAfter?.toStorageEpochMillis(),
                    transaction.contentHash.value.bytes,
                    transaction.id.value.bytes,
                ),
            )
        }
    }
}

private val EffectPolarity.sqlValue: Int
    get() = if (this == EffectPolarity.APPLY) 1 else -1

private val CURRENT_ENTITY_TABLES = mapOf(
    app.ledger.finance.domain.EntityType.ACCOUNT to "user_account",
    app.ledger.finance.domain.EntityType.CARD to "payment_card",
    app.ledger.finance.domain.EntityType.CATEGORY to "category",
    app.ledger.finance.domain.EntityType.MERCHANT to "merchant",
    app.ledger.finance.domain.EntityType.PLACE to "place",
    app.ledger.finance.domain.EntityType.TRANSACTION to "business_transaction",
    app.ledger.finance.domain.EntityType.PROJECT to "project",
    app.ledger.finance.domain.EntityType.GOAL to "goal",
    app.ledger.finance.domain.EntityType.BUDGET to "budget_month",
    app.ledger.finance.domain.EntityType.CREDIT_STATEMENT to "credit_statement",
    app.ledger.finance.domain.EntityType.INSTALLMENT_PLAN to "installment_plan",
    app.ledger.finance.domain.EntityType.LOAN to "loan_contract",
    app.ledger.finance.domain.EntityType.PARTICIPANT to "participant",
    app.ledger.finance.domain.EntityType.SETTLEMENT_ACTIVITY to "settlement_activity",
    app.ledger.finance.domain.EntityType.BLUEPRINT to "transaction_blueprint",
    app.ledger.finance.domain.EntityType.RECURRENCE_SERIES to "recurrence_series",
    app.ledger.finance.domain.EntityType.BUDGET_TEMPLATE to "budget_template",
)
