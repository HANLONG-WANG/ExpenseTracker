@file:Suppress("LongMethod", "LongParameterList", "MaxLineLength", "TooManyFunctions")

package app.ledger.finance.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.DomainResult
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.Money
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.time.EffectiveTime
import app.ledger.finance.application.DefaultFinancialMutationCoordinator
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.FinancialPlanningPort
import app.ledger.finance.application.FinancialPlanningSnapshotRepository
import app.ledger.finance.application.LedgerWriteGate
import app.ledger.finance.application.ReferenceDataManagementPort
import app.ledger.finance.application.RefundApplicationPort
import app.ledger.finance.application.RefundNamedReference
import app.ledger.finance.application.RefundSearchQuery
import app.ledger.finance.application.RefundSnapshot
import app.ledger.finance.application.RefundWriteRequest
import app.ledger.finance.application.RefundableTransactionView
import app.ledger.finance.domain.AccountAmount
import app.ledger.finance.domain.AccountingPlanningContext
import app.ledger.finance.domain.AmountEvidenceKey
import app.ledger.finance.domain.AmountRole
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.CategoryAssignment
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.DeterministicFinancialPlanner
import app.ledger.finance.domain.DeviceInstanceId
import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.FrozenAmountEvidence
import app.ledger.finance.domain.FrozenFxConversion
import app.ledger.finance.domain.FxRateSnapshotId
import app.ledger.finance.domain.GoalId
import app.ledger.finance.domain.MerchantId
import app.ledger.finance.domain.NewTransactionInput
import app.ledger.finance.domain.ParticipantId
import app.ledger.finance.domain.PaymentCardId
import app.ledger.finance.domain.PlanningIdentitySet
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.PositiveMoney
import app.ledger.finance.domain.ProjectId
import app.ledger.finance.domain.RecordRefundCommand
import app.ledger.finance.domain.RefundAllocation
import app.ledger.finance.domain.RefundPayload
import app.ledger.finance.domain.RefundStatusProjection
import app.ledger.finance.domain.SettlementActivityId
import app.ledger.finance.domain.SettlementShare
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.TransactionContextInput
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionLifecycleState
import app.ledger.finance.domain.TransactionRevisionId
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

public class SecureRoomRefundApplicationPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val referenceDataPort: ReferenceDataManagementPort,
) : RefundApplicationPort {
    private val applicationContext = context.applicationContext
    private val mapper = RoomReferenceFinancialSnapshotMapper()
    private val catalog = JvmLegalTenderCurrencyCatalog.create()
    private val writeGate: LedgerWriteGate = RefundWriteGate()

    override suspend fun snapshot(bookId: app.ledger.core.common.StableId, query: RefundSearchQuery): DomainResult<RefundSnapshot> {
        val references = when (val result = referenceDataPort.snapshot(bookId)) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
        return withDatabase(bookId) { database ->
            database.readLedger { db ->
                val originals = refundableTransactions(db, query)
                val projects = db.queryList("SELECT uid,name FROM project WHERE status=0 ORDER BY name,id") {
                    RefundNamedReference(it.stableId("uid"), it.string("name"))
                }
                val goals = db.queryList("SELECT uid,name FROM goal WHERE status=0 ORDER BY name,id") {
                    RefundNamedReference(it.stableId("uid"), it.string("name"))
                }
                DomainResult.Success(RefundSnapshot(references, originals, projects, goals))
            }
        }
    }

    override suspend fun submit(request: RefundWriteRequest): DomainResult<CommandReceipt> = withDatabase(request.ids.bookId) { database ->
        val snapshot = database.readLedger { planningSnapshot(it, request) }
        val draft = RecordRefundCommand(request.ids.commandId, zeroHash(), NewTransactionInput(context(request), payload(snapshot, request)))
        val command = draft.copy(payloadHash = CanonicalFinancialHash.command(draft))
        val repository = RoomFinancialCommitRepository(database)
        DefaultFinancialMutationCoordinator(
            writeGate,
            repository,
            object : FinancialPlanningSnapshotRepository {
                override suspend fun load(command: FinancialCommand): DomainResult<PlanningSnapshot> = DomainResult.Success(snapshot)
            },
            FinancialPlanningPort(DeterministicFinancialPlanner::plan),
            repository,
        ).execute(command)
    }

    private fun planningSnapshot(db: SupportSQLiteDatabase, request: RefundWriteRequest): PlanningSnapshot {
        val book = RoomBookRepository.mapCurrent(db)
        if (book.id.value != request.ids.bookId) abort(FinanceDataError.CorruptData)
        val references = mapper.references(db)
        val statuses = request.allocations.map { allocation -> refundStatus(db, allocation.originalTransactionId, book.localRevision) }
        val evidence = frozenAmount(request, references, book.baseCurrency)
        return PlanningSnapshot(
            book = book,
            currentTransaction = null,
            currentRevision = null,
            dependencies = emptyList(),
            reversedApplyEntryIds = emptySet(),
            refundStatuses = statuses,
            budgetRevision = null,
            participants = emptyList(),
            accountingContext = AccountingPlanningContext(
                PlanningIdentitySet(
                    TransactionId(request.ids.transactionId),
                    TransactionRevisionId(request.ids.revisionId),
                    BookCommitId(request.ids.commitId),
                    request.ids.factIds,
                ),
                request.createdAt,
                DeviceInstanceId(request.ids.deviceInstanceId),
                references,
                listOf(evidence),
                null,
            ),
        )
    }

    private fun payload(snapshot: PlanningSnapshot, request: RefundWriteRequest): RefundPayload {
        val references = requireNotNull(snapshot.accountingContext).references
        val account = references.account(UserAccountId(request.amount.receivingAccountId))?.account ?: abort(FinanceDataError.CorruptData)
        val classification = request.categoryId?.let { stable ->
            val category = references.category(CategoryId(stable)) ?: abort(FinanceDataError.CorruptData)
            CategoryAssignment(category.id, category.direction, category.statisticalNature)
        }
        val allocations = request.allocations.map { allocation ->
            RefundAllocation(
                TransactionId(allocation.originalTransactionId),
                TransactionRevisionId(allocation.originalRevisionId),
                positive(allocation.originalCurrencyMinor, request.amount.inputCurrency),
                positive(allocation.baseMinor, snapshot.book.baseCurrency),
            )
        }
        return RefundPayload(
            classification = classification,
            receivingAmount = AccountAmount.create(account, Money(request.amount.receivingAccountMinor, account.currency)).valueOrAbort(),
            receivingCardId = request.receivingCardId?.let(::PaymentCardId),
            allocations = allocations,
            independent = request.independent,
            allowExcessOverride = request.allowExcessOverride,
            budgetPolicy = request.budgetPolicy,
            projectPolicy = request.projectPolicy,
            goalPolicy = request.goalPolicy,
            accrualPolicy = request.accrualPolicy,
            settlementActivityId = request.settlementActivityId?.let(::SettlementActivityId),
            settlementShares = request.settlementShares,
        )
    }

    private fun context(request: RefundWriteRequest): TransactionContextInput = TransactionContextInput(
        occurredAt = EffectiveTime.fromInstant(request.occurredAt, request.zoneId),
        accrualDate = request.accrualDate,
        budgetMonth = request.budgetTargetMonth,
        merchantId = request.merchantId?.let(::MerchantId),
        projectId = request.projectId?.let(::ProjectId),
        goalId = request.goalId?.let(::GoalId),
        locationRecordId = null,
        note = request.note,
        amountExpression = request.amountExpression,
        source = TransactionSource.MANUAL,
        sourceReferenceId = null,
        statementAssignment = null,
        attachmentIds = request.attachmentIds.map { app.ledger.finance.domain.AttachmentId(it) },
    )

    private fun frozenAmount(
        request: RefundWriteRequest,
        references: app.ledger.finance.domain.PlanningReferenceData,
        baseCurrency: CurrencyCode,
    ): FrozenAmountEvidence {
        val account = references.account(UserAccountId(request.amount.receivingAccountId))?.account ?: abort(FinanceDataError.CorruptData)
        val input = positive(request.amount.inputMinor, request.amount.inputCurrency)
        val accountAmount = positive(request.amount.receivingAccountMinor, account.currency)
        val base = positive(request.amount.baseMinor, baseCurrency)
        val ids = request.ids.fxRateSnapshotIds.iterator()
        fun conversion(source: PositiveMoney, target: PositiveMoney, evidence: app.ledger.core.money.FxEvidence?): FrozenFxConversion? {
            if (source.currency == target.currency) {
                if (source != target || evidence != null) abort(FinanceDataError.CorruptData)
                return null
            }
            val rate = evidence ?: abort(FinanceDataError.CorruptData)
            if (rate.sourceCurrency != source.currency || rate.targetCurrency != target.currency || !ids.hasNext()) abort(FinanceDataError.CorruptData)
            return FrozenFxConversion.create(
                FxRateSnapshotId(ids.next()),
                source,
                target,
                rate,
                catalog.require(source.currency).valueOrAbort(),
                catalog.require(target.currency).valueOrAbort(),
                false,
            ).valueOrAbort()
        }
        return FrozenAmountEvidence.create(
            AmountEvidenceKey(AmountRole.REFUND, 0),
            input,
            accountAmount,
            base,
            account.id,
            conversion(input, accountAmount, request.amount.inputToAccountEvidence),
            conversion(accountAmount, base, request.amount.accountToBaseEvidence),
        ).valueOrAbort()
    }

    private fun refundStatus(db: SupportSQLiteDatabase, transactionId: app.ledger.core.common.StableId, revision: app.ledger.finance.domain.LocalRevision): RefundStatusProjection = db.queryOne(
        """
        SELECT bt.uid,tr.uid revision_uid,bt.lifecycle_state,input.amount_minor,input.currency_code,
          COALESCE(SUM(CASE WHEN allocations.reversal_of_id IS NULL THEN allocations.original_currency_amount_minor ELSE -allocations.original_currency_amount_minor END),0) refunded
        FROM business_transaction bt JOIN transaction_revision tr ON tr.id=bt.current_revision_id
        JOIN revision_amount input ON input.revision_id=tr.id AND input.component_index=0 AND input.representation=0
        LEFT JOIN refund_allocation allocations ON allocations.original_transaction_id=bt.id
        WHERE bt.uid=? AND bt.kind=0 GROUP BY bt.id
        """.trimIndent(),
        arrayOf(transactionId.bytes),
    ) { cursor ->
        val gross = positive(cursor.long("amount_minor"), currency(cursor.string("currency_code")))
        val refunded = cursor.long("refunded")
        RefundStatusProjection(TransactionId(cursor.stableId("uid")), gross, refunded, maxOf(gross.minor.value - refunded, 0L), revision, TransactionLifecycleState.entries[cursor.int("lifecycle_state")])
    } ?: abort(FinanceDataError.CorruptData)

    private fun refundableTransactions(db: SupportSQLiteDatabase, query: RefundSearchQuery): List<RefundableTransactionView> {
        val pattern = "%${query.text.trim().lowercase().replace("%", "\\%").replace("_", "\\_")}%"
        val rows = db.queryList(
            """
            SELECT bt.id,bt.uid,tr.id revision_internal,tr.uid revision_uid,bt.lifecycle_state,input.amount_minor,input.currency_code,
              base.amount_minor base_minor,base.currency_code base_currency,
              COALESCE((SELECT SUM(CASE WHEN ra.reversal_of_id IS NULL THEN ra.original_currency_amount_minor ELSE -ra.original_currency_amount_minor END)
                FROM refund_allocation ra WHERE ra.original_transaction_id=bt.id),0) refunded,
              tr.occurred_at,tr.local_date,ua.uid account_uid,card.uid card_uid,category.uid category_uid,category.name category_name,
              tr.statistical_nature_snapshot,merchant.uid merchant_uid,merchant.name merchant_name,project.uid project_uid,project.name project_name,
              goal.uid goal_uid,goal.name goal_name,activity.uid activity_uid,installment.uid installment_uid
            FROM business_transaction bt JOIN transaction_revision tr ON tr.id=bt.current_revision_id
            JOIN expense_revision_detail expense ON expense.revision_id=tr.id
            JOIN revision_amount input ON input.revision_id=tr.id AND input.component_index=0 AND input.role=0 AND input.representation=0
            JOIN revision_amount base ON base.revision_id=tr.id AND base.component_index=0 AND base.role=0 AND base.representation=2
            JOIN category ON category.id=tr.category_id LEFT JOIN user_account ua ON ua.id=expense.payer_account_id
            LEFT JOIN payment_card card ON card.id=expense.payer_card_id LEFT JOIN merchant ON merchant.id=tr.merchant_id
            LEFT JOIN project ON project.id=tr.project_id LEFT JOIN goal ON goal.id=tr.goal_id
            LEFT JOIN settlement_activity activity ON activity.id=expense.settlement_activity_id
            LEFT JOIN installment_plan installment ON installment.id=expense.installment_plan_id
            WHERE bt.kind=0 AND bt.lifecycle_state=0
              AND (?='' OR lower(category.name) LIKE ? ESCAPE '\\' OR lower(COALESCE(merchant.name,'')) LIKE ? ESCAPE '\\')
              AND (? IS NULL OR expense.payer_account_id=(SELECT id FROM user_account WHERE uid=?))
            ORDER BY tr.occurred_at DESC,bt.id DESC LIMIT 100
            """.trimIndent(),
            arrayOf<Any?>(query.text.trim(), pattern, pattern, query.accountId?.bytes, query.accountId?.bytes),
        ) { cursor -> OriginalRow.from(cursor) }
        return rows.mapNotNull { row ->
            if (query.partiallyRefundedOnly && (row.refunded <= 0L || row.refunded >= row.originalMinor)) return@mapNotNull null
            val shares = db.queryList(
                "SELECT p.uid,trs.paid_minor,trs.owed_minor,trs.weight_decimal,trs.rounding_adjustment_minor FROM transaction_revision_settlement_share trs " +
                    "JOIN participant p ON p.id=trs.participant_id WHERE trs.revision_id=? ORDER BY p.uid",
                arrayOf(row.revisionInternal),
            ) { cursor -> SettlementShare(ParticipantId(cursor.stableId("uid")), cursor.long("paid_minor"), cursor.long("owed_minor"), cursor.nullableString("weight_decimal")?.toBigDecimal(), cursor.long("rounding_adjustment_minor")) }
            RefundableTransactionView(
                row.transactionId, row.revisionId, row.state, row.originalMinor, row.originalCurrency, row.baseMinor, row.baseCurrency,
                row.refunded, maxOf(row.originalMinor - row.refunded, 0L), maxOf(row.refunded - row.originalMinor, 0L), row.occurredAt,
                row.localDate, row.accountId, row.cardId, row.categoryId, row.categoryName, row.nature, row.merchantId, row.merchantName,
                row.projectId, row.projectName, row.goalId, row.goalName, row.activityId, shares, row.installmentId,
            )
        }
    }

    private suspend fun <T> withDatabase(
        bookId: app.ledger.core.common.StableId,
        block: suspend (LedgerDatabase) -> DomainResult<T>,
    ): DomainResult<T> = try {
        keyProvider.open(bookId).use { keys ->
            val database = keys.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(applicationContext, it) }
            try {
                block(database)
            } finally {
                database.close()
            }
        }
    } catch (abort: FinancialPersistenceAbort) {
        DomainResult.Failure(abort.domainError)
    } catch (_: ArithmeticException) {
        DomainResult.Failure(FinanceDataError.NumericRangeExceeded)
    } catch (_: Exception) {
        DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
    }

    private fun currency(value: String): CurrencyCode = CurrencyCode.parse(value).valueOrAbort()
    private fun positive(minor: Long, currency: CurrencyCode): PositiveMoney = PositiveMoney.from(Money(minor, currency)).valueOrAbort()
    private fun zeroHash() = app.ledger.finance.domain.Hash256.fromBytes(ByteArray(HASH_BYTE_COUNT)).valueOrAbort()

    private companion object {
        const val HASH_BYTE_COUNT = 32
    }

    private data class OriginalRow(
        val revisionInternal: Long,
        val transactionId: app.ledger.core.common.StableId,
        val revisionId: app.ledger.core.common.StableId,
        val state: TransactionLifecycleState,
        val originalMinor: Long,
        val originalCurrency: CurrencyCode,
        val baseMinor: Long,
        val baseCurrency: CurrencyCode,
        val refunded: Long,
        val occurredAt: java.time.Instant,
        val localDate: LocalDate,
        val accountId: app.ledger.core.common.StableId?,
        val cardId: app.ledger.core.common.StableId?,
        val categoryId: app.ledger.core.common.StableId,
        val categoryName: String,
        val nature: StatisticalNature,
        val merchantId: app.ledger.core.common.StableId?,
        val merchantName: String?,
        val projectId: app.ledger.core.common.StableId?,
        val projectName: String?,
        val goalId: app.ledger.core.common.StableId?,
        val goalName: String?,
        val activityId: app.ledger.core.common.StableId?,
        val installmentId: app.ledger.core.common.StableId?,
    ) {
        companion object {
            fun from(c: android.database.Cursor) = OriginalRow(
                c.long("revision_internal"), c.stableId("uid"), c.stableId("revision_uid"), TransactionLifecycleState.entries[c.int("lifecycle_state")],
                c.long("amount_minor"), CurrencyCode.parse(c.string("currency_code")).valueOrAbort(), c.long("base_minor"),
                CurrencyCode.parse(c.string("base_currency")).valueOrAbort(), c.long("refunded"), c.long("occurred_at").toStoredInstant(),
                c.int("local_date").toStoredLocalDate(), c.nullableStableId("account_uid"), c.nullableStableId("card_uid"), c.stableId("category_uid"),
                c.string("category_name"), StatisticalNature.entries[c.int("statistical_nature_snapshot")], c.nullableStableId("merchant_uid"),
                c.nullableString("merchant_name"), c.nullableStableId("project_uid"), c.nullableString("project_name"), c.nullableStableId("goal_uid"),
                c.nullableString("goal_name"), c.nullableStableId("activity_uid"), c.nullableStableId("installment_uid"),
            )
        }
    }
}

private class RefundWriteGate : LedgerWriteGate {
    private val mutex = Mutex()
    override suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock { block() }
}

private fun android.database.Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
private fun android.database.Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun android.database.Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
