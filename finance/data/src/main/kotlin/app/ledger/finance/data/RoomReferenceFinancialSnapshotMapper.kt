@file:Suppress("LongMethod", "LongParameterList", "TooManyFunctions", "LargeClass", "MagicNumber", "MaxLineLength")

package app.ledger.finance.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.FxEvidence
import app.ledger.core.money.FxEvidenceInput
import app.ledger.core.money.FxProvider
import app.ledger.core.money.FxRateSource
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.Money
import app.ledger.core.time.EffectiveTime
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.domain.AccountAmount
import app.ledger.finance.domain.AccountSnapshot
import app.ledger.finance.domain.AccountingPlanningContext
import app.ledger.finance.domain.AmountEvidenceKey
import app.ledger.finance.domain.AmountRepresentation
import app.ledger.finance.domain.AmountRole
import app.ledger.finance.domain.AutoGenerationMode
import app.ledger.finance.domain.BalanceAdjustmentDirection
import app.ledger.finance.domain.BalanceAdjustmentPayload
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.BudgetEffect
import app.ledger.finance.domain.BudgetEffectId
import app.ledger.finance.domain.BudgetEffectKind
import app.ledger.finance.domain.BusinessTransaction
import app.ledger.finance.domain.CardType
import app.ledger.finance.domain.CategoryAssignment
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.CategoryStatus
import app.ledger.finance.domain.ContentHash
import app.ledger.finance.domain.CreditPaymentAllocation
import app.ledger.finance.domain.CreditPaymentPayload
import app.ledger.finance.domain.CreditStatementId
import app.ledger.finance.domain.CurrentFinancialFacts
import app.ledger.finance.domain.DebitCredit
import app.ledger.finance.domain.DeviceInstanceId
import app.ledger.finance.domain.EconomicComponent
import app.ledger.finance.domain.EconomicEffect
import app.ledger.finance.domain.EconomicEffectId
import app.ledger.finance.domain.EconomicNature
import app.ledger.finance.domain.EffectPolarity
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.ExpensePayer
import app.ledger.finance.domain.ExpensePayload
import app.ledger.finance.domain.FrozenAmountEvidence
import app.ledger.finance.domain.FrozenFxConversion
import app.ledger.finance.domain.FxExchangePayload
import app.ledger.finance.domain.FxRateSnapshotId
import app.ledger.finance.domain.FxValuationPolicy
import app.ledger.finance.domain.GoalEffect
import app.ledger.finance.domain.GoalEffectId
import app.ledger.finance.domain.GoalEffectKind
import app.ledger.finance.domain.GoalId
import app.ledger.finance.domain.GoalMovementId
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.IncomePayload
import app.ledger.finance.domain.InstallmentPlanId
import app.ledger.finance.domain.JournalBundle
import app.ledger.finance.domain.JournalEntry
import app.ledger.finance.domain.JournalEntryId
import app.ledger.finance.domain.JournalEntryRole
import app.ledger.finance.domain.LedgerAccountClass
import app.ledger.finance.domain.LedgerAccountId
import app.ledger.finance.domain.LedgerAccountSnapshot
import app.ledger.finance.domain.LoanActualAllocation
import app.ledger.finance.domain.LoanContractId
import app.ledger.finance.domain.LoanDisbursementPayload
import app.ledger.finance.domain.LoanEffect
import app.ledger.finance.domain.LoanEffectId
import app.ledger.finance.domain.LoanEffectKind
import app.ledger.finance.domain.LoanPaymentComponent
import app.ledger.finance.domain.LoanPaymentComponents
import app.ledger.finance.domain.LoanPaymentPayload
import app.ledger.finance.domain.LoanScheduleItemId
import app.ledger.finance.domain.LoanScheduleRevisionId
import app.ledger.finance.domain.LoanTrancheId
import app.ledger.finance.domain.LocationRecordId
import app.ledger.finance.domain.MerchantId
import app.ledger.finance.domain.NewTransactionInput
import app.ledger.finance.domain.OpeningBalancePayload
import app.ledger.finance.domain.ParticipantId
import app.ledger.finance.domain.PaymentCardId
import app.ledger.finance.domain.PlanningAccount
import app.ledger.finance.domain.PlanningCard
import app.ledger.finance.domain.PlanningCategory
import app.ledger.finance.domain.PlanningGoal
import app.ledger.finance.domain.PlanningIdentitySet
import app.ledger.finance.domain.PlanningProject
import app.ledger.finance.domain.PlanningReferenceData
import app.ledger.finance.domain.PlanningSettlementLedger
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.PlanningSystemLedger
import app.ledger.finance.domain.PositiveMoney
import app.ledger.finance.domain.Posting
import app.ledger.finance.domain.PostingId
import app.ledger.finance.domain.PostingRole
import app.ledger.finance.domain.ProjectEffect
import app.ledger.finance.domain.ProjectEffectId
import app.ledger.finance.domain.ProjectEffectKind
import app.ledger.finance.domain.ProjectId
import app.ledger.finance.domain.ProjectStatus
import app.ledger.finance.domain.RefundAccrualPolicy
import app.ledger.finance.domain.RefundAllocation
import app.ledger.finance.domain.RefundBudgetPolicy
import app.ledger.finance.domain.RefundGoalPolicy
import app.ledger.finance.domain.RefundPayload
import app.ledger.finance.domain.RefundProjectPolicy
import app.ledger.finance.domain.RevisionAction
import app.ledger.finance.domain.RevisionAmount
import app.ledger.finance.domain.RowVersion
import app.ledger.finance.domain.RuleSetVersion
import app.ledger.finance.domain.SettlementActivityId
import app.ledger.finance.domain.SettlementEffect
import app.ledger.finance.domain.SettlementEffectId
import app.ledger.finance.domain.SettlementEffectKind
import app.ledger.finance.domain.SettlementPaymentPayload
import app.ledger.finance.domain.SettlementPaymentRecordId
import app.ledger.finance.domain.SettlementShare
import app.ledger.finance.domain.StatementAssignment
import app.ledger.finance.domain.StatementAssignmentMode
import app.ledger.finance.domain.StatementEffect
import app.ledger.finance.domain.StatementEffectId
import app.ledger.finance.domain.StatementEffectKind
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.TransactionContextInput
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.TransactionLifecycleState
import app.ledger.finance.domain.TransactionPayload
import app.ledger.finance.domain.TransactionRevision
import app.ledger.finance.domain.TransactionRevisionId
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.TransferPayload
import app.ledger.finance.domain.UserAccountId
import app.ledger.finance.domain.UserAccountType
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth

internal data class ReferenceEditSource(
    val snapshot: PlanningSnapshot,
    val revision: TransactionRevision,
)

/** Rehydrates only immutable, typed data needed by P12 context/category revision fan-out. */
internal class RoomReferenceFinancialSnapshotMapper {
    private val currencyCatalog = JvmLegalTenderCurrencyCatalog.create()

    fun load(
        db: SupportSQLiteDatabase,
        transactionId: StableId,
        revisionId: StableId,
        commitId: StableId,
        factIds: List<StableId>,
        fxIds: List<StableId>,
        createdAt: Instant,
        deviceInstanceId: StableId,
    ): ReferenceEditSource {
        val book = RoomBookRepository.mapCurrent(db)
        val references = references(db)
        val transaction = readTransaction(db, transactionId)
        val currentRevision = readRevision(db, transaction, references, transaction.currentRevisionId)
        val amounts = readRevisionAmounts(db, currentRevision.id)
        val evidence = readAmountEvidence(db, amounts, fxIds)
        val facts = readCurrentFacts(db, currentRevision.id)
        val snapshot = PlanningSnapshot(
            book = book,
            currentTransaction = transaction,
            currentRevision = currentRevision,
            dependencies = readDependencies(db, transaction.id),
            reversedApplyEntryIds = readReversedEntryIds(db, currentRevision.id),
            refundStatuses = emptyList(),
            budgetRevision = null,
            participants = emptyList(),
            accountingContext = AccountingPlanningContext(
                identities = PlanningIdentitySet(
                    transaction.id,
                    TransactionRevisionId(revisionId),
                    BookCommitId(commitId),
                    factIds,
                ),
                createdAt = createdAt,
                deviceInstanceId = DeviceInstanceId(deviceInstanceId),
                references = references,
                amountEvidence = evidence,
                currentFacts = facts,
            ),
        )
        return ReferenceEditSource(snapshot, currentRevision)
    }

    internal fun historicalInput(
        db: SupportSQLiteDatabase,
        transactionId: StableId,
        revisionId: StableId,
        fxIds: List<StableId>,
    ): Pair<NewTransactionInput<TransactionPayload>, List<FrozenAmountEvidence>> {
        val transaction = readTransaction(db, transactionId)
        val typedRevisionId = TransactionRevisionId(revisionId)
        val references = references(db)
        val revision = readRevision(db, transaction, references, typedRevisionId)
        if (revision.transactionId != transaction.id) abort(FinanceDataError.CorruptData)
        val evidence = readAmountEvidence(db, readRevisionAmounts(db, typedRevisionId), fxIds)
        return NewTransactionInput(revision.toContextInput(), revision.payload) to evidence
    }

    internal fun references(db: SupportSQLiteDatabase): PlanningReferenceData {
        val accounts = db.queryList(
            "SELECT ua.uid account_uid,la.uid ledger_uid,ua.type,ua.currency_code,ua.status,ua.row_version," +
                "EXISTS(SELECT 1 FROM posting p WHERE p.ledger_account_id=la.id) has_postings," +
                "la.account_class,la.normal_side,la.status ledger_status FROM user_account ua JOIN ledger_account la ON la.id=ua.ledger_account_id",
        ) { cursor ->
            val currency = currency(cursor.string("currency_code"))
            val ledger = LedgerAccountSnapshot(
                LedgerAccountId(cursor.stableId("ledger_uid")),
                LedgerAccountClass.entries[cursor.int("account_class")],
                DebitCredit.entries[cursor.int("normal_side")],
                currency,
                EntityStatus.entries[cursor.int("ledger_status")],
            )
            PlanningAccount(
                AccountSnapshot(
                    UserAccountId(cursor.stableId("account_uid")),
                    ledger.id,
                    UserAccountType.entries[cursor.int("type")],
                    currency,
                    EntityStatus.entries[cursor.int("status")],
                    RowVersion.of(cursor.long("row_version")).valueOrAbort(),
                    cursor.int("has_postings") == 1,
                ),
                ledger,
            )
        }
        val cards = db.queryList("SELECT pc.uid,ua.uid account_uid,pc.card_type,pc.status FROM payment_card pc JOIN user_account ua ON ua.id=pc.account_id") { cursor ->
            PlanningCard(PaymentCardId(cursor.stableId("uid")), UserAccountId(cursor.stableId("account_uid")), CardType.entries[cursor.int("card_type")], EntityStatus.entries[cursor.int("status")])
        }
        val categories = db.queryList(
            "SELECT c.uid,parent.uid parent_uid,c.direction,c.statistical_nature,c.status FROM category c LEFT JOIN category parent ON parent.id=c.parent_id",
        ) { cursor ->
            val id = CategoryId(cursor.stableId("uid"))
            PlanningCategory(
                id,
                cursor.nullableStableId("parent_uid")?.let(::CategoryId) ?: id,
                CategoryDirection.entries[cursor.int("direction")],
                StatisticalNature.entries[cursor.int("statistical_nature")],
                CategoryStatus.entries[cursor.int("status")],
            )
        }
        val systems = db.queryList("SELECT uid,account_class,normal_side,currency_code,status,system_code FROM ledger_account WHERE owner_type=2") { cursor ->
            PlanningSystemLedger(
                SystemLedgerCode.valueOf(cursor.string("system_code")),
                cursor.ledgerSnapshot(),
            )
        }
        val projects = db.queryList("SELECT uid,included_in_monthly_budget,status FROM project") { cursor ->
            PlanningProject(
                ProjectId(cursor.stableId("uid")),
                cursor.int("included_in_monthly_budget") == 1,
                ProjectStatus.entries[cursor.int("status")],
            )
        }
        val goals = db.queryList(
            "SELECT g.uid,ua.uid account_uid,ua.currency_code,g.status FROM goal g JOIN user_account ua ON ua.id=g.account_id",
        ) { cursor ->
            PlanningGoal(
                GoalId(cursor.stableId("uid")),
                UserAccountId(cursor.stableId("account_uid")),
                currency(cursor.string("currency_code")),
                app.ledger.finance.domain.GoalStatus.entries[cursor.int("status")],
            )
        }
        val settlementLedgers = db.queryList(
            "SELECT uid,account_class,normal_side,currency_code,status,system_code FROM ledger_account " +
                "WHERE owner_type=3 AND system_code LIKE 'SETTLEMENT:%'",
        ) { cursor ->
            val parts = cursor.string("system_code").split(':')
            if (parts.size != 3) abort(FinanceDataError.CorruptData)
            PlanningSettlementLedger(
                SettlementActivityId(StableId.parse(parts[1]).valueOrAbort()),
                ParticipantId(StableId.parse(parts[2]).valueOrAbort()),
                cursor.ledgerSnapshot(),
            )
        }
        val typedLedgerIds = (accounts.map { it.ledger.id } + systems.map { it.ledger.id }).toSet()
        val historical = db.queryList("SELECT uid,account_class,normal_side,currency_code,status FROM ledger_account") { it.ledgerSnapshot() }
            .filterNot { it.id in typedLedgerIds }
        return PlanningReferenceData(accounts, cards, categories, projects, goals, systems, emptyList(), settlementLedgers, historical)
    }

    private fun readTransaction(db: SupportSQLiteDatabase, id: StableId): BusinessTransaction = db.queryOne(
        "SELECT bt.uid,tr.uid revision_uid,bt.kind,bt.lifecycle_state,created.uid created_uid,last.uid last_uid,bt.row_version,bt.trashed_at,bt.purge_after,bt.content_hash " +
            "FROM business_transaction bt JOIN transaction_revision tr ON tr.id=bt.current_revision_id " +
            "JOIN book_commit created ON created.id=bt.created_commit_id JOIN book_commit last ON last.id=bt.last_commit_id WHERE bt.uid=?",
        arrayOf(id.bytes),
    ) { cursor ->
        BusinessTransaction(
            TransactionId(cursor.stableId("uid")),
            TransactionKind.entries[cursor.int("kind")],
            TransactionRevisionId(cursor.stableId("revision_uid")),
            TransactionLifecycleState.entries[cursor.int("lifecycle_state")],
            BookCommitId(cursor.stableId("created_uid")),
            BookCommitId(cursor.stableId("last_uid")),
            RowVersion.of(cursor.long("row_version")).valueOrAbort(),
            cursor.nullableLong("trashed_at")?.toStoredInstant(),
            cursor.nullableLong("purge_after")?.toStoredInstant(),
            ContentHash(Hash256.fromBytes(cursor.blob("content_hash")).valueOrAbort()),
        )
    } ?: abort(FinanceDataError.CorruptData)

    private fun readRevision(
        db: SupportSQLiteDatabase,
        transaction: BusinessTransaction,
        references: PlanningReferenceData,
        revisionId: TransactionRevisionId,
    ): TransactionRevision {
        val row = db.queryOne(
            "SELECT tr.id,tr.uid,bt.uid transaction_uid,tr.revision_no,tr.action,tr.resulting_state,prev.uid previous_uid,bc.uid commit_uid," +
                "tr.created_at,tr.occurred_at,tr.zone_id,tr.local_date,c.uid category_uid,tr.statistical_nature_snapshot,m.uid merchant_uid," +
                "p.uid project_uid,g.uid goal_uid,lr.uid location_uid,tr.note,tr.amount_expression,tr.source_type,tr.source_reference_uid,tr.content_hash " +
                "FROM transaction_revision tr JOIN business_transaction bt ON bt.id=tr.transaction_id JOIN book_commit bc ON bc.id=tr.created_commit_id " +
                "LEFT JOIN transaction_revision prev ON prev.id=tr.previous_revision_id LEFT JOIN category c ON c.id=tr.category_id " +
                "LEFT JOIN merchant m ON m.id=tr.merchant_id LEFT JOIN project p ON p.id=tr.project_id LEFT JOIN goal g ON g.id=tr.goal_id " +
                "LEFT JOIN location_record lr ON lr.id=tr.location_record_id WHERE tr.uid=?",
            arrayOf(revisionId.value.bytes),
        ) { cursor -> RevisionRow.from(cursor) } ?: abort(FinanceDataError.CorruptData)
        if (row.id != revisionId) abort(FinanceDataError.CorruptData)
        val amounts = readRevisionAmounts(db, revisionId)
        val classification = row.categoryId?.let { categoryId ->
            val category = references.category(categoryId) ?: abort(FinanceDataError.CorruptData)
            CategoryAssignment(categoryId, category.direction, row.statisticalNature ?: abort(FinanceDataError.CorruptData))
        }
        val payload = readPayload(db, row.internalId, transaction, amounts, classification, references)
        val attachmentIds = db.queryList(
            "SELECT a.uid FROM transaction_revision_attachment tra JOIN attachment a ON a.id=tra.attachment_id WHERE tra.revision_id=? ORDER BY tra.sort_order",
            arrayOf(row.internalId),
        ) { app.ledger.finance.domain.AttachmentId(it.stableId("uid")) }
        val statementAssignment = db.queryOne(
            "SELECT cs.uid,se.manual_assignment FROM statement_effect se LEFT JOIN credit_statement cs ON cs.id=se.statement_id WHERE se.source_revision_id=? AND se.polarity=1 LIMIT 1",
            arrayOf(row.internalId),
        ) { cursor ->
            val statement = cursor.nullableStableId("uid")?.let(::CreditStatementId)
            if (cursor.int("manual_assignment") == 1 && statement != null) {
                StatementAssignment(StatementAssignmentMode.EXPLICIT_STATEMENT, statement)
            } else {
                StatementAssignment(StatementAssignmentMode.AUTOMATIC, null)
            }
        }
        return TransactionRevision(
            row.id,
            transaction.id,
            row.revisionNumber,
            row.action,
            row.state,
            row.previousId,
            row.commitId,
            row.createdAt,
            EffectiveTime.fromInstant(row.occurredAt, row.zoneId),
            row.localDate,
            YearMonth.from(row.localDate),
            row.merchantId,
            row.projectId,
            row.goalId,
            row.locationId,
            row.note,
            row.amountExpression,
            row.source,
            row.sourceReferenceId,
            statementAssignment,
            attachmentIds,
            payload,
            row.contentHash,
        )
    }

    private fun readDependencies(db: SupportSQLiteDatabase, transactionId: TransactionId): List<app.ledger.finance.domain.TransactionDependency> = db.queryList(
        "SELECT parent.uid parent_uid,child.uid child_uid,td.dependency_type FROM transaction_dependency td " +
            "JOIN business_transaction parent ON parent.id=td.parent_transaction_id " +
            "JOIN business_transaction child ON child.id=td.child_transaction_id " +
            "WHERE parent.uid=? OR child.uid=? ORDER BY td.dependency_type,parent.uid,child.uid",
        arrayOf(transactionId.value.bytes, transactionId.value.bytes),
    ) { cursor ->
        app.ledger.finance.domain.TransactionDependency(
            TransactionId(cursor.stableId("parent_uid")),
            TransactionId(cursor.stableId("child_uid")),
            app.ledger.finance.domain.TransactionDependencyType.entries[cursor.int("dependency_type")],
        )
    }

    private fun readPayload(
        db: SupportSQLiteDatabase,
        revisionInternalId: Long,
        transaction: BusinessTransaction,
        amounts: List<RevisionAmount>,
        classification: CategoryAssignment?,
        references: PlanningReferenceData,
    ): TransactionPayload = when (transaction.kind) {
        TransactionKind.EXPENSE -> {
            val detail = db.queryOne(
                "SELECT d.payer_kind,ua.uid account_uid,pc.uid card_uid,pt.uid participant_uid,sa.uid activity_uid,ip.uid installment_uid " +
                    "FROM expense_revision_detail d LEFT JOIN user_account ua ON ua.id=d.payer_account_id LEFT JOIN payment_card pc ON pc.id=d.payer_card_id " +
                    "LEFT JOIN participant pt ON pt.id=d.payer_participant_id LEFT JOIN settlement_activity sa ON sa.id=d.settlement_activity_id " +
                    "LEFT JOIN installment_plan ip ON ip.id=d.installment_plan_id WHERE d.revision_id=?",
                arrayOf(revisionInternalId),
            ) { ExpenseDetail.from(it) } ?: abort(FinanceDataError.CorruptData)
            val primary = amounts.money(AmountRole.PRIMARY, AmountRepresentation.USER_INPUT)
            val payer = if (detail.payerKind == 0) {
                ExpensePayer.LocalAccount(amounts.accountAmount(AmountRole.PRIMARY, references), detail.cardId)
            } else {
                ExpensePayer.ExternalParticipant(detail.participantId ?: abort(FinanceDataError.CorruptData), detail.activityId ?: abort(FinanceDataError.CorruptData))
            }
            val shares = db.queryList(
                "SELECT p.uid,trs.paid_minor,trs.owed_minor,trs.weight_decimal,trs.rounding_adjustment_minor FROM transaction_revision_settlement_share trs " +
                    "JOIN participant p ON p.id=trs.participant_id WHERE trs.revision_id=? ORDER BY p.uid",
                arrayOf(revisionInternalId),
            ) { cursor -> SettlementShare(ParticipantId(cursor.stableId("uid")), cursor.long("paid_minor"), cursor.long("owed_minor"), cursor.nullableString("weight_decimal")?.toBigDecimal(), cursor.long("rounding_adjustment_minor")) }
            ExpensePayload(classification ?: abort(FinanceDataError.CorruptData), payer, primary, detail.activityId, shares, detail.installmentId)
        }
        TransactionKind.INCOME -> IncomePayload(
            classification ?: abort(FinanceDataError.CorruptData),
            amounts.accountAmount(AmountRole.PRIMARY, references),
            amounts.money(AmountRole.PRIMARY, AmountRepresentation.USER_INPUT),
        )
        TransactionKind.TRANSFER -> TransferPayload(
            amounts.accountAmount(AmountRole.OUTGOING, references),
            amounts.accountAmount(AmountRole.INCOMING, references),
            db.queryOne("SELECT pc.uid FROM transfer_revision_detail d LEFT JOIN payment_card pc ON pc.id=d.source_card_id WHERE d.revision_id=?", arrayOf(revisionInternalId)) { it.nullableStableId("uid")?.let(::PaymentCardId) },
        )
        TransactionKind.REFUND -> {
            val detail = db.queryOne(
                "SELECT pc.uid card_uid,d.independent,d.budget_policy,d.allow_excess FROM refund_revision_detail d LEFT JOIN payment_card pc ON pc.id=d.receiving_card_id WHERE d.revision_id=?",
                arrayOf(revisionInternalId),
            ) { RefundDetail.from(it) } ?: abort(FinanceDataError.CorruptData)
            val allocations = db.queryList(
                "SELECT obt.uid original_transaction_uid,otr.uid original_revision_uid,ra.original_currency_amount_minor,ra.base_amount_minor," +
                    "oa.currency_code original_currency,b.base_currency FROM refund_allocation ra JOIN business_transaction obt ON obt.id=ra.original_transaction_id " +
                    "JOIN transaction_revision otr ON otr.id=ra.original_revision_id JOIN transaction_revision rr ON rr.id=ra.refund_revision_id " +
                    "JOIN book b ON b.id=1 LEFT JOIN revision_amount oa ON oa.revision_id=rr.id AND oa.role=? AND oa.representation=? WHERE ra.refund_revision_id=?",
                arrayOf<Any?>(AmountRole.REFUND.ordinal, AmountRepresentation.ACCOUNT.ordinal, revisionInternalId),
            ) { cursor -> RefundAllocation(TransactionId(cursor.stableId("original_transaction_uid")), TransactionRevisionId(cursor.stableId("original_revision_uid")), positive(cursor.long("original_currency_amount_minor"), currency(cursor.string("original_currency"))), positive(cursor.long("base_amount_minor"), currency(cursor.string("base_currency")))) }
            RefundPayload(
                classification,
                amounts.accountAmount(AmountRole.REFUND, references),
                detail.cardId,
                allocations,
                detail.independent,
                detail.allowExcess,
                RefundBudgetPolicy.entries[detail.budgetPolicy],
                RefundProjectPolicy.DO_NOT_RESTORE,
                RefundGoalPolicy.DO_NOT_RESTORE,
                RefundAccrualPolicy.REFUND_DATE,
            )
        }
        TransactionKind.CREDIT_PAYMENT -> {
            val detail = db.queryOne("SELECT ua.uid credit_uid,d.generation_mode FROM credit_payment_revision_detail d JOIN user_account ua ON ua.id=d.credit_account_id WHERE d.revision_id=?", arrayOf(revisionInternalId)) { it.stableId("credit_uid") to it.int("generation_mode") } ?: abort(FinanceDataError.CorruptData)
            val allocations = db.queryList("SELECT cs.uid statement_uid,cpa.amount_minor,ua.currency_code FROM credit_payment_allocation cpa LEFT JOIN credit_statement cs ON cs.id=cpa.statement_id JOIN user_account ua ON ua.id=(SELECT credit_account_id FROM credit_payment_revision_detail WHERE revision_id=?) WHERE cpa.payment_revision_id=?", arrayOf(revisionInternalId, revisionInternalId)) { cursor -> CreditPaymentAllocation(cursor.nullableStableId("statement_uid")?.let(::CreditStatementId), positive(cursor.long("amount_minor"), currency(cursor.string("currency_code")))) }
            CreditPaymentPayload(amounts.accountAmount(AmountRole.OUTGOING, references), UserAccountId(detail.first), amounts.accountAmount(AmountRole.INCOMING, references), allocations, AutoGenerationMode.entries[detail.second])
        }
        TransactionKind.LOAN_DISBURSEMENT -> {
            val contract = db.queryOne("SELECT lc.uid FROM loan_disbursement_revision_detail d JOIN loan_contract lc ON lc.id=d.loan_contract_id WHERE d.revision_id=?", arrayOf(revisionInternalId)) { LoanContractId(it.stableId("uid")) } ?: abort(FinanceDataError.CorruptData)
            LoanDisbursementPayload(contract, amounts.accountAmount(AmountRole.INCOMING, references), amounts.money(AmountRole.PRINCIPAL, AmountRepresentation.ACCOUNT))
        }
        TransactionKind.LOAN_PAYMENT -> readLoanPayment(db, revisionInternalId, transaction, amounts, classification, references)
        TransactionKind.BALANCE_ADJUSTMENT -> {
            val detail = db.queryOne("SELECT d.direction,cp.uid checkpoint_uid FROM balance_adjustment_revision_detail d LEFT JOIN account_balance_checkpoint cp ON cp.id=d.checkpoint_id WHERE d.revision_id=?", arrayOf(revisionInternalId)) { it.int("direction") to it.nullableStableId("checkpoint_uid") } ?: abort(FinanceDataError.CorruptData)
            BalanceAdjustmentPayload(amounts.accountAmount(AmountRole.PRIMARY, references), BalanceAdjustmentDirection.entries[detail.first], detail.second)
        }
        TransactionKind.FX_EXCHANGE -> {
            val policy = db.queryOne("SELECT valuation_policy FROM fx_exchange_revision_detail WHERE revision_id=?", arrayOf(revisionInternalId)) { it.getInt(0) } ?: abort(FinanceDataError.CorruptData)
            FxExchangePayload(classification, amounts.accountAmount(AmountRole.OUTGOING, references), amounts.accountAmount(AmountRole.INCOMING, references), FxValuationPolicy.entries[policy], amounts.optionalMoney(AmountRole.FX_SPREAD, AmountRepresentation.USER_INPUT))
        }
        TransactionKind.SETTLEMENT_PAYMENT -> {
            val detail = db.queryOne(
                "SELECT sa.uid activity_uid,payer.uid payer_uid,payee.uid payee_uid,ua.uid account_uid FROM settlement_payment_revision_detail d " +
                    "JOIN settlement_activity sa ON sa.id=d.activity_id JOIN participant payer ON payer.id=d.payer_participant_id " +
                    "JOIN participant payee ON payee.id=d.payee_participant_id LEFT JOIN user_account ua ON ua.id=d.local_account_id WHERE d.revision_id=?",
                arrayOf(revisionInternalId),
            ) { SettlementDetail.from(it) } ?: abort(FinanceDataError.CorruptData)
            val settlement = amounts.optionalMoney(AmountRole.SETTLEMENT, AmountRepresentation.USER_INPUT)
                ?: amounts.money(AmountRole.SETTLEMENT, AmountRepresentation.SETTLEMENT)
            SettlementPaymentPayload(detail.activityId, detail.payerId, detail.payeeId, settlement, detail.accountId?.let { amounts.accountAmount(AmountRole.SETTLEMENT, references) }, detail.accountId != null)
        }
        TransactionKind.OPENING_BALANCE -> {
            val detail = db.queryOne("SELECT d.balance_date,la.normal_side FROM opening_balance_revision_detail d JOIN user_account ua ON ua.id=d.account_id JOIN ledger_account la ON la.id=ua.ledger_account_id WHERE d.revision_id=?", arrayOf(revisionInternalId)) { it.getInt(0).toStoredLocalDate() to DebitCredit.entries[it.getInt(1)] } ?: abort(FinanceDataError.CorruptData)
            OpeningBalancePayload(amounts.accountAmount(AmountRole.PRIMARY, references), detail.first, detail.second)
        }
    }

    private fun readLoanPayment(db: SupportSQLiteDatabase, revisionId: Long, transaction: BusinessTransaction, amounts: List<RevisionAmount>, classification: CategoryAssignment?, references: PlanningReferenceData): LoanPaymentPayload {
        val detail = db.queryOne("SELECT lc.uid contract_uid,lsr.uid schedule_uid FROM loan_payment_revision_detail d JOIN loan_contract lc ON lc.id=d.loan_contract_id LEFT JOIN loan_schedule_revision lsr ON lsr.id=d.schedule_revision_id WHERE d.revision_id=?", arrayOf(revisionId)) { it.stableId("contract_uid") to it.nullableStableId("schedule_uid") } ?: abort(FinanceDataError.CorruptData)
        fun component(role: AmountRole) = amounts.optionalMoney(role, AmountRepresentation.USER_INPUT)
        val allocations = db.queryList(
            "SELECT lt.uid tranche_uid,lsi.id schedule_id,la.component,la.amount_minor,la.base_amount_minor,lc.currency_code,b.base_currency,la.reversal_of_id " +
                "FROM loan_actual_allocation la JOIN loan_tranche lt ON lt.id=la.tranche_id JOIN loan_contract lc ON lc.id=lt.contract_id JOIN book b ON b.id=1 " +
                "LEFT JOIN loan_schedule_item lsi ON lsi.id=la.schedule_item_id WHERE la.payment_revision_id=?",
            arrayOf(revisionId),
        ) { cursor -> LoanActualAllocation(transaction.id, transaction.currentRevisionId, LoanTrancheId(cursor.stableId("tranche_uid")), cursor.nullableLong("schedule_id")?.let { LoanScheduleItemId(stableIdFromInternal(it)) }, LoanPaymentComponent.entries[cursor.int("component")], positive(cursor.long("amount_minor"), currency(cursor.string("currency_code"))), positive(cursor.long("base_amount_minor"), currency(cursor.string("base_currency"))), cursor.nullableLong("reversal_of_id")?.let(::stableIdFromInternal)) }
        return LoanPaymentPayload(classification, LoanContractId(detail.first), amounts.accountAmount(AmountRole.OUTGOING, references), detail.second?.let(::LoanScheduleRevisionId), LoanPaymentComponents(component(AmountRole.PRINCIPAL), component(AmountRole.INTEREST), component(AmountRole.FEE), component(AmountRole.PENALTY)), allocations)
    }

    private fun readRevisionAmounts(db: SupportSQLiteDatabase, revisionId: TransactionRevisionId): List<RevisionAmount> = db.queryList(
        "SELECT ra.component_index,ra.role,ra.representation,ra.amount_minor,ra.currency_code,ua.uid account_uid,fx.uid fx_uid " +
            "FROM revision_amount ra JOIN transaction_revision tr ON tr.id=ra.revision_id LEFT JOIN user_account ua ON ua.id=ra.related_account_id " +
            "LEFT JOIN fx_rate_snapshot fx ON fx.id=ra.fx_rate_snapshot_id WHERE tr.uid=? ORDER BY ra.component_index,ra.role,ra.representation",
        arrayOf(revisionId.value.bytes),
    ) { cursor -> RevisionAmount(revisionId, cursor.int("component_index"), AmountRole.entries[cursor.int("role")], AmountRepresentation.entries[cursor.int("representation")], positive(cursor.long("amount_minor"), currency(cursor.string("currency_code"))), cursor.nullableStableId("account_uid")?.let(::UserAccountId), cursor.nullableStableId("fx_uid")?.let(::FxRateSnapshotId)) }

    private fun readAmountEvidence(db: SupportSQLiteDatabase, amounts: List<RevisionAmount>, fxIds: List<StableId>): List<FrozenAmountEvidence> {
        var fxIndex = 0
        return amounts.groupBy { AmountEvidenceKey(it.role, it.componentIndex) }.mapNotNull { (key, values) ->
            val user = values.singleOrNull { it.representation == AmountRepresentation.USER_INPUT } ?: return@mapNotNull null
            val account = values.singleOrNull { it.representation == AmountRepresentation.ACCOUNT } ?: return@mapNotNull null
            val base = values.singleOrNull { it.representation == AmountRepresentation.BASE } ?: return@mapNotNull null
            fun conversion(source: PositiveMoney, target: PositiveMoney, storedId: FxRateSnapshotId?): FrozenFxConversion? {
                if (source.currency == target.currency) return null
                val sourceId = storedId ?: abort(FinanceDataError.CorruptData)
                val row = db.queryOne(
                    "SELECT source_currency,target_currency,rate_decimal,provider,quoted_at,fetched_at,source_type,manual_override,stale_at_use FROM fx_rate_snapshot WHERE uid=?",
                    arrayOf(sourceId.value.bytes),
                ) { FxRow.from(it) } ?: abort(FinanceDataError.CorruptData)
                val newId = fxIds.getOrNull(fxIndex++) ?: abort(FinanceDataError.CorruptData)
                val evidence = FxEvidence.create(FxEvidenceInput(currency(row.source), currency(row.target), row.rate, FxProvider.of(row.provider).valueOrAbort(), row.quotedAt, row.fetchedAt, FxRateSource.entries[row.sourceType], row.manual)).valueOrAbort()
                return FrozenFxConversion.create(FxRateSnapshotId(newId), source, target, evidence, currencyCatalog.require(source.currency).valueOrAbort(), currencyCatalog.require(target.currency).valueOrAbort(), row.stale).valueOrAbort()
            }
            FrozenAmountEvidence.create(key, user.money, account.money, base.money, account.relatedAccountId, conversion(user.money, account.money, account.fxRateSnapshotId), conversion(account.money, base.money, base.fxRateSnapshotId)).valueOrAbort()
        }
    }

    private fun readCurrentFacts(db: SupportSQLiteDatabase, revisionId: TransactionRevisionId): CurrentFinancialFacts {
        val journals = db.queryList(
            "SELECT je.id,je.uid,je.source_revision_id,je.applies_revision_id,je.effective_at,je.zone_id,je.base_currency,je.rule_set_version,bc.uid commit_uid,je.content_hash " +
                "FROM journal_entry je JOIN transaction_revision tr ON tr.id=je.applies_revision_id JOIN book_commit bc ON bc.id=je.created_commit_id " +
                "WHERE tr.uid=? AND je.entry_role=0 ORDER BY je.id",
            arrayOf(revisionId.value.bytes),
        ) { cursor -> readJournal(db, cursor) }
        val economic = db.queryList(
            "SELECT ee.uid,je.uid entry_uid,tr.uid revision_uid,ee.polarity,ee.nature,ee.component,ee.is_consumption,ee.base_amount_minor,b.base_currency,ee.accrual_local_date,c.uid category_uid,m.uid merchant_uid,p.uid project_uid,ee.rule_set_version " +
                "FROM economic_effect ee JOIN journal_entry je ON je.id=ee.source_entry_id JOIN transaction_revision tr ON tr.id=ee.source_revision_id JOIN book b ON b.id=1 " +
                "LEFT JOIN category c ON c.id=ee.category_id LEFT JOIN merchant m ON m.id=ee.merchant_id LEFT JOIN project p ON p.id=ee.project_id WHERE tr.uid=? AND ee.polarity=1",
            arrayOf(revisionId.value.bytes),
        ) { cursor -> EconomicEffect(EconomicEffectId(cursor.stableId("uid")), JournalEntryId(cursor.stableId("entry_uid")), TransactionRevisionId(cursor.stableId("revision_uid")), null, EffectPolarity.APPLY, EconomicNature.entries[cursor.int("nature")], EconomicComponent.entries[cursor.int("component")], cursor.int("is_consumption") == 1, positive(cursor.long("base_amount_minor"), currency(cursor.string("base_currency"))), cursor.int("accrual_local_date").toStoredLocalDate(), cursor.nullableStableId("category_uid")?.let(::CategoryId), cursor.nullableStableId("merchant_uid")?.let(::MerchantId), cursor.nullableStableId("project_uid")?.let(::ProjectId), RuleSetVersion.of(cursor.int("rule_set_version")).valueOrAbort()) }
        val budget = db.queryList(
            "SELECT be.id,tr.uid revision_uid,be.kind,be.target_year_month,c.uid category_uid,root.uid root_uid,be.base_amount_minor,b.base_currency,be.rule_set_version FROM budget_effect be JOIN transaction_revision tr ON tr.id=be.source_revision_id JOIN book b ON b.id=1 LEFT JOIN category c ON c.id=be.category_id LEFT JOIN category root ON root.id=be.root_category_id WHERE tr.uid=? AND be.polarity=1",
            arrayOf(revisionId.value.bytes),
        ) { cursor -> BudgetEffect(BudgetEffectId(stableIdFromInternal(cursor.long("id"))), TransactionRevisionId(cursor.stableId("revision_uid")), null, EffectPolarity.APPLY, BudgetEffectKind.entries[cursor.int("kind")], cursor.int("target_year_month").toStoredYearMonth(), cursor.nullableStableId("category_uid")?.let(::CategoryId), cursor.nullableStableId("root_uid")?.let(::CategoryId), positive(cursor.long("base_amount_minor"), currency(cursor.string("base_currency"))), RuleSetVersion.of(cursor.int("rule_set_version")).valueOrAbort()) }
        val project = db.queryList("SELECT pe.id,p.uid project_uid,tr.uid revision_uid,pe.kind,pe.base_amount_minor,b.base_currency,pe.monthly_budget_inclusion_snapshot FROM project_effect pe JOIN project p ON p.id=pe.project_id JOIN transaction_revision tr ON tr.id=pe.source_revision_id JOIN book b ON b.id=1 WHERE tr.uid=? AND pe.polarity=1", arrayOf(revisionId.value.bytes)) { cursor -> ProjectEffect(ProjectEffectId(stableIdFromInternal(cursor.long("id"))), ProjectId(cursor.stableId("project_uid")), ProjectEffectKind.entries[cursor.int("kind")], positive(cursor.long("base_amount_minor"), currency(cursor.string("base_currency"))), cursor.int("monthly_budget_inclusion_snapshot") == 1, TransactionRevisionId(cursor.stableId("revision_uid")), null, EffectPolarity.APPLY) }
        val goal = db.queryList("SELECT ge.id,g.uid goal_uid,tr.uid revision_uid,ge.kind,ge.amount_minor,ge.currency_code FROM goal_effect ge JOIN goal g ON g.id=ge.goal_id JOIN transaction_revision tr ON tr.id=ge.source_revision_id WHERE tr.uid=? AND ge.polarity=1", arrayOf(revisionId.value.bytes)) { cursor -> GoalEffect(GoalEffectId(stableIdFromInternal(cursor.long("id"))), GoalId(cursor.stableId("goal_uid")), GoalEffectKind.entries[cursor.int("kind")], positive(cursor.long("amount_minor"), currency(cursor.string("currency_code"))), TransactionRevisionId(cursor.stableId("revision_uid")), null, null, EffectPolarity.APPLY) }
        val statement = db.queryList("SELECT se.id,ua.uid account_uid,cs.uid statement_uid,tr.uid revision_uid,se.kind,se.amount_minor,se.currency_code,se.manual_assignment FROM statement_effect se JOIN user_account ua ON ua.id=se.credit_account_id LEFT JOIN credit_statement cs ON cs.id=se.statement_id JOIN transaction_revision tr ON tr.id=se.source_revision_id WHERE tr.uid=? AND se.polarity=1", arrayOf(revisionId.value.bytes)) { cursor -> StatementEffect(StatementEffectId(stableIdFromInternal(cursor.long("id"))), UserAccountId(cursor.stableId("account_uid")), cursor.nullableStableId("statement_uid")?.let(::CreditStatementId), TransactionRevisionId(cursor.stableId("revision_uid")), null, StatementEffectKind.entries[cursor.int("kind")], EffectPolarity.APPLY, positive(cursor.long("amount_minor"), currency(cursor.string("currency_code"))), cursor.int("manual_assignment") == 1) }
        val loan = db.queryList("SELECT le.id,lc.uid contract_uid,lt.uid tranche_uid,lsi.id schedule_id,tr.uid revision_uid,le.kind,le.amount_minor,le.currency_code,le.base_amount_minor,b.base_currency FROM loan_effect le JOIN loan_contract lc ON lc.id=le.loan_contract_id JOIN loan_tranche lt ON lt.id=le.loan_tranche_id LEFT JOIN loan_schedule_item lsi ON lsi.id=le.schedule_item_id JOIN transaction_revision tr ON tr.id=le.source_revision_id JOIN book b ON b.id=1 WHERE tr.uid=? AND le.polarity=1", arrayOf(revisionId.value.bytes)) { cursor -> LoanEffect(LoanEffectId(stableIdFromInternal(cursor.long("id"))), LoanContractId(cursor.stableId("contract_uid")), LoanTrancheId(cursor.stableId("tranche_uid")), cursor.nullableLong("schedule_id")?.let { LoanScheduleItemId(stableIdFromInternal(it)) }, TransactionRevisionId(cursor.stableId("revision_uid")), null, LoanEffectKind.entries[cursor.int("kind")], EffectPolarity.APPLY, positive(cursor.long("amount_minor"), currency(cursor.string("currency_code"))), positive(cursor.long("base_amount_minor"), currency(cursor.string("base_currency")))) }
        val settlement = db.queryList("SELECT se.id,sa.uid activity_uid,p.uid participant_uid,tr.uid revision_uid,se.kind,se.signed_delta_minor,se.currency_code FROM settlement_effect se JOIN settlement_activity sa ON sa.id=se.activity_id JOIN participant p ON p.id=se.participant_id JOIN transaction_revision tr ON tr.id=se.source_revision_id WHERE tr.uid=?", arrayOf(revisionId.value.bytes)) { cursor -> SettlementEffect(SettlementEffectId(stableIdFromInternal(cursor.long("id"))), SettlementActivityId(cursor.stableId("activity_uid")), ParticipantId(cursor.stableId("participant_uid")), TransactionRevisionId(cursor.stableId("revision_uid")), null, null, SettlementEffectKind.entries[cursor.int("kind")], cursor.long("signed_delta_minor"), currency(cursor.string("currency_code"))) }
        return CurrentFinancialFacts(journals, economic, budget, project, goal, statement, loan, settlement)
    }

    private fun readJournal(db: SupportSQLiteDatabase, cursor: Cursor): JournalBundle {
        val internalId = cursor.long("id")
        val entryId = JournalEntryId(cursor.stableId("uid"))
        val baseCurrency = currency(cursor.string("base_currency"))
        val postings = db.queryList(
            "SELECT p.uid,la.uid ledger_uid,p.line_no,p.side,p.account_amount_minor,p.account_currency,p.base_amount_minor,p.base_currency,p.valuation_rate_decimal,p.posting_role FROM posting p JOIN ledger_account la ON la.id=p.ledger_account_id WHERE p.journal_entry_id=? ORDER BY p.line_no",
            arrayOf(internalId),
        ) { row ->
            val ledger = LedgerAccountSnapshot(LedgerAccountId(row.stableId("ledger_uid")), LedgerAccountClass.ASSET, DebitCredit.DEBIT, currency(row.string("account_currency")), EntityStatus.ACTIVE)
            Posting.create(PostingId(row.stableId("uid")), entryId, row.int("line_no"), ledger, DebitCredit.entries[row.int("side")], positive(row.long("account_amount_minor"), ledger.currency), positive(row.long("base_amount_minor"), currency(row.string("base_currency"))), baseCurrency, row.nullableString("valuation_rate_decimal")?.toBigDecimal(), PostingRole.entries[row.int("posting_role")], null).valueOrAbort()
        }
        val sourceRevision = db.queryOne("SELECT uid FROM transaction_revision WHERE id=?", arrayOf(cursor.long("source_revision_id"))) { TransactionRevisionId(it.stableId("uid")) } ?: abort(FinanceDataError.CorruptData)
        val appliesRevision = db.queryOne("SELECT uid FROM transaction_revision WHERE id=?", arrayOf(cursor.long("applies_revision_id"))) { TransactionRevisionId(it.stableId("uid")) } ?: abort(FinanceDataError.CorruptData)
        val effective = EffectiveTime.fromInstant(cursor.long("effective_at").toStoredInstant(), java.time.ZoneId.of(cursor.string("zone_id")))
        val entry = JournalEntry.create(entryId, sourceRevision, appliesRevision, JournalEntryRole.APPLY, null, effective, baseCurrency, postings, RuleSetVersion.of(cursor.int("rule_set_version")).valueOrAbort(), BookCommitId(cursor.stableId("commit_uid")), ContentHash(Hash256.fromBytes(cursor.blob("content_hash")).valueOrAbort())).valueOrAbort()
        return JournalBundle(entry, postings)
    }

    private fun readReversedEntryIds(db: SupportSQLiteDatabase, revisionId: TransactionRevisionId): Set<JournalEntryId> = db.queryList(
        "SELECT original.uid FROM journal_entry reverse JOIN journal_entry original ON original.id=reverse.reverses_entry_id JOIN transaction_revision tr ON tr.id=original.applies_revision_id WHERE tr.uid=?",
        arrayOf(revisionId.value.bytes),
    ) { JournalEntryId(it.stableId("uid")) }.toSet()

    private fun List<RevisionAmount>.money(role: AmountRole, representation: AmountRepresentation, component: Int = 0): PositiveMoney = singleOrNull { it.role == role && it.representation == representation && it.componentIndex == component }?.money ?: abort(FinanceDataError.CorruptData)
    private fun List<RevisionAmount>.optionalMoney(role: AmountRole, representation: AmountRepresentation, component: Int = 0): PositiveMoney? = singleOrNull { it.role == role && it.representation == representation && it.componentIndex == component }?.money
    private fun List<RevisionAmount>.accountAmount(role: AmountRole, references: PlanningReferenceData, component: Int = 0): AccountAmount {
        val value = singleOrNull { it.role == role && it.representation == AmountRepresentation.ACCOUNT && it.componentIndex == component } ?: abort(FinanceDataError.CorruptData)
        val account = value.relatedAccountId?.let(references::account)?.account ?: abort(FinanceDataError.CorruptData)
        return AccountAmount.restoreHistorical(account, value.money.money).valueOrAbort()
    }
    private fun positive(minor: Long, currency: CurrencyCode): PositiveMoney = PositiveMoney.from(Money(minor, currency)).valueOrAbort()
    private fun TransactionRevision.toContextInput(): TransactionContextInput = TransactionContextInput(
        occurredAt,
        accrualDate,
        budgetMonth,
        merchantId,
        projectId,
        goalId,
        locationRecordId,
        note,
        amountExpression,
        source,
        sourceReferenceId,
        statementAssignment,
        attachmentIds,
    )
    private fun Cursor.ledgerSnapshot() = LedgerAccountSnapshot(LedgerAccountId(stableId("uid")), LedgerAccountClass.entries[int("account_class")], DebitCredit.entries[int("normal_side")], currency(string("currency_code")), EntityStatus.entries[int("status")])

    private data class RevisionRow(val internalId: Long, val id: TransactionRevisionId, val revisionNumber: Int, val action: RevisionAction, val state: TransactionLifecycleState, val previousId: TransactionRevisionId?, val commitId: BookCommitId, val createdAt: Instant, val occurredAt: Instant, val zoneId: java.time.ZoneId, val localDate: java.time.LocalDate, val categoryId: CategoryId?, val statisticalNature: StatisticalNature?, val merchantId: MerchantId?, val projectId: ProjectId?, val goalId: GoalId?, val locationId: LocationRecordId?, val note: String?, val amountExpression: String?, val source: TransactionSource, val sourceReferenceId: StableId?, val contentHash: ContentHash) {
        companion object {
            fun from(c: Cursor) = RevisionRow(c.getLong(0), TransactionRevisionId(c.stableId("uid")), c.int("revision_no"), RevisionAction.entries[c.int("action")], TransactionLifecycleState.entries[c.int("resulting_state")], c.nullableStableId("previous_uid")?.let(::TransactionRevisionId), BookCommitId(c.stableId("commit_uid")), c.long("created_at").toStoredInstant(), c.long("occurred_at").toStoredInstant(), java.time.ZoneId.of(c.string("zone_id")), c.int("local_date").toStoredLocalDate(), c.nullableStableId("category_uid")?.let(::CategoryId), c.nullableLong("statistical_nature_snapshot")?.toInt()?.let { StatisticalNature.entries[it] }, c.nullableStableId("merchant_uid")?.let(::MerchantId), c.nullableStableId("project_uid")?.let(::ProjectId), c.nullableStableId("goal_uid")?.let(::GoalId), c.nullableStableId("location_uid")?.let(::LocationRecordId), c.nullableString("note"), c.nullableString("amount_expression"), TransactionSource.entries[c.int("source_type")], c.nullableBlob("source_reference_uid")?.let { StableId.fromBytes(it).valueOrAbort() }, ContentHash(Hash256.fromBytes(c.blob("content_hash")).valueOrAbort()))
        }
    }
    private data class ExpenseDetail(val payerKind: Int, val cardId: PaymentCardId?, val participantId: ParticipantId?, val activityId: SettlementActivityId?, val installmentId: InstallmentPlanId?) {
        companion object {
            fun from(c: Cursor) = ExpenseDetail(c.int("payer_kind"), c.nullableStableId("card_uid")?.let(::PaymentCardId), c.nullableStableId("participant_uid")?.let(::ParticipantId), c.nullableStableId("activity_uid")?.let(::SettlementActivityId), c.nullableStableId("installment_uid")?.let(::InstallmentPlanId))
        }
    }
    private data class RefundDetail(val cardId: PaymentCardId?, val independent: Boolean, val budgetPolicy: Int, val allowExcess: Boolean) {
        companion object {
            fun from(c: Cursor) = RefundDetail(c.nullableStableId("card_uid")?.let(::PaymentCardId), c.int("independent") == 1, c.int("budget_policy"), c.int("allow_excess") == 1)
        }
    }
    private data class SettlementDetail(val activityId: SettlementActivityId, val payerId: ParticipantId, val payeeId: ParticipantId, val accountId: UserAccountId?) {
        companion object {
            fun from(c: Cursor) = SettlementDetail(SettlementActivityId(c.stableId("activity_uid")), ParticipantId(c.stableId("payer_uid")), ParticipantId(c.stableId("payee_uid")), c.nullableStableId("account_uid")?.let(::UserAccountId))
        }
    }
    private data class FxRow(val source: String, val target: String, val rate: BigDecimal, val provider: String, val quotedAt: Instant?, val fetchedAt: Instant?, val sourceType: Int, val manual: Boolean, val stale: Boolean) {
        companion object {
            fun from(c: Cursor) = FxRow(c.string("source_currency"), c.string("target_currency"), c.string("rate_decimal").toBigDecimal(), c.string("provider"), c.nullableLong("quoted_at")?.toStoredInstant(), c.nullableLong("fetched_at")?.toStoredInstant(), c.int("source_type"), c.int("manual_override") == 1, c.int("stale_at_use") == 1)
        }
    }
}

private fun currency(value: String): CurrencyCode = CurrencyCode.parse(value).valueOrAbort()
private fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
private fun Cursor.blob(name: String): ByteArray = getBlob(getColumnIndexOrThrow(name))
private fun Cursor.nullableBlob(name: String): ByteArray? {
    val index = getColumnIndexOrThrow(name)
    return if (isNull(index)) null else getBlob(index)
}
