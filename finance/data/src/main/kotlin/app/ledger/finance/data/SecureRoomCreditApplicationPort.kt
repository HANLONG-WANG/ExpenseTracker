@file:Suppress("LongMethod", "LongParameterList", "MagicNumber", "MaxLineLength", "TooManyFunctions")

package app.ledger.finance.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.Money
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.time.EffectiveTime
import app.ledger.finance.application.AssignCreditStatementRequest
import app.ledger.finance.application.CreditAccountView
import app.ledger.finance.application.CreditApplicationPort
import app.ledger.finance.application.CreditAutoPaymentProposal
import app.ledger.finance.application.CreditPaymentAccountView
import app.ledger.finance.application.CreditProfileView
import app.ledger.finance.application.CreditSnapshot
import app.ledger.finance.application.CreditStatementView
import app.ledger.finance.application.DefaultFinancialMutationCoordinator
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.FinancialPlanningPort
import app.ledger.finance.application.FinancialPlanningSnapshotRepository
import app.ledger.finance.application.LedgerWriteGate
import app.ledger.finance.application.ReallocateCreditPaymentRequest
import app.ledger.finance.application.RecordCreditPaymentRequest
import app.ledger.finance.application.SaveCreditProfileRequest
import app.ledger.finance.application.SaveCreditStatementRequest
import app.ledger.finance.application.SpecializedAccountAmountDraft
import app.ledger.finance.domain.AccountAmount
import app.ledger.finance.domain.AccountingPlanningContext
import app.ledger.finance.domain.AmountEvidenceKey
import app.ledger.finance.domain.AmountRole
import app.ledger.finance.domain.AutoGenerationMode
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.CreditAccountProfile
import app.ledger.finance.domain.CreditAutoPaymentPolicy
import app.ledger.finance.domain.CreditLimitPeriod
import app.ledger.finance.domain.CreditPaymentAllocationPolicy
import app.ledger.finance.domain.CreditPaymentPayload
import app.ledger.finance.domain.CreditProfileMutation
import app.ledger.finance.domain.CreditStatement
import app.ledger.finance.domain.CreditStatementId
import app.ledger.finance.domain.CreditStatementMutation
import app.ledger.finance.domain.CreditStatementPolicy
import app.ledger.finance.domain.CreditStatementRevision
import app.ledger.finance.domain.CreditStatementRevisionId
import app.ledger.finance.domain.CreditStatementStatus
import app.ledger.finance.domain.DeterministicFinancialPlanner
import app.ledger.finance.domain.DeviceInstanceId
import app.ledger.finance.domain.DueDateRule
import app.ledger.finance.domain.EditTransactionCommand
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.FinancialCommandType
import app.ledger.finance.domain.FrozenAmountEvidence
import app.ledger.finance.domain.FrozenFxConversion
import app.ledger.finance.domain.FxRateSnapshotId
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.NewTransactionInput
import app.ledger.finance.domain.PayableCreditStatement
import app.ledger.finance.domain.PlanningIdentitySet
import app.ledger.finance.domain.PlanningOperationContext
import app.ledger.finance.domain.PlanningReferenceData
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.PositiveMoney
import app.ledger.finance.domain.RecordCreditPaymentCommand
import app.ledger.finance.domain.SaveCreditProfileCommand
import app.ledger.finance.domain.SaveCreditStatementCommand
import app.ledger.finance.domain.StableEntityReference
import app.ledger.finance.domain.StatementAssignment
import app.ledger.finance.domain.StatementAssignmentMode
import app.ledger.finance.domain.StatementDateRule
import app.ledger.finance.domain.TransactionContextInput
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.TransactionRevision
import app.ledger.finance.domain.TransactionRevisionId
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountId
import app.ledger.finance.domain.WeekendAdjustment
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SecureRoomCreditApplicationPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val databaseName: String = EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME,
) : CreditApplicationPort {
    private val applicationContext = context.applicationContext
    private val writeGate: LedgerWriteGate = CreditWriteGate()
    private val currencyCatalog = JvmLegalTenderCurrencyCatalog.create()
    private val mapper = RoomReferenceFinancialSnapshotMapper()

    override suspend fun snapshot(bookId: StableId): DomainResult<CreditSnapshot> = withDatabase(bookId) { database ->
        database.readLedger { db ->
            val book = RoomBookRepository.mapCurrent(db)
            if (book.id.value != bookId) abort(FinanceDataError.CorruptData)
            DomainResult.Success(readSnapshot(db, bookId, book.baseCurrency, book.localRevision))
        }
    }

    override suspend fun saveProfile(request: SaveCreditProfileRequest): DomainResult<app.ledger.finance.domain.CommandReceipt> = withDatabase(request.ids.bookId) { database ->
        val book = database.readLedger(RoomBookRepository::mapCurrent)
        val profile = CreditAccountProfile(
            UserAccountId(request.accountId),
            request.statementRule,
            request.dueRule,
            request.statementZoneId,
            request.standardLimitMinor,
            request.temporaryLimitMinor,
            request.temporaryLimitExpiresOn,
            request.defaultPaymentAccountId?.let(::UserAccountId),
            request.autoPaymentMode,
            request.weekendAdjustment,
            BookCommitId(request.ids.commitId),
        )
        val limitPeriod = request.limitEffectiveFrom?.let { effective ->
            val alreadyRecorded = database.readLedger { db ->
                db.queryOne(
                    "SELECT COUNT(*) FROM credit_limit_period clp JOIN user_account ua ON ua.id=clp.credit_account_id " +
                        "WHERE ua.uid=? AND clp.effective_from=?",
                    arrayOf<Any>(request.accountId.bytes, effective.toStorageInt()),
                ) { it.getLong(0) } != 0L
            }
            val recordedByThisCommand = database.readLedger { db ->
                db.queryOne(
                    "SELECT COUNT(*) FROM credit_limit_period clp JOIN user_account ua ON ua.id=clp.credit_account_id " +
                        "JOIN book_commit bc ON bc.id=clp.created_commit_id WHERE ua.uid=? AND clp.effective_from=? AND bc.command_uid=?",
                    arrayOf<Any>(request.accountId.bytes, effective.toStorageInt(), request.ids.commandId.stableId.bytes),
                ) { it.getLong(0) } != 0L
            }
            request.standardLimitMinor?.takeIf { !alreadyRecorded || recordedByThisCommand }?.let {
                CreditLimitPeriod(profile.accountId, effective, null, it, profile.lastCommitId)
            }
        }
        val draft = SaveCreditProfileCommand(
            request.ids.commandId,
            zeroHash(),
            CreditProfileMutation(profile, request.expectedLastCommitId?.let(::BookCommitId), limitPeriod),
        )
        val command = draft.copy(payloadHash = CanonicalFinancialHash.command(draft))
        coordinate(database, command, operationSnapshot(book, request.ids.commitId, request.ids.deviceInstanceId, request.changedAt))
    }

    override suspend fun saveStatement(request: SaveCreditStatementRequest): DomainResult<app.ledger.finance.domain.CommandReceipt> = withDatabase(request.ids.mutation.bookId) { database ->
        val book = database.readLedger(RoomBookRepository::mapCurrent)
        val paid = database.readLedger { db ->
            db.queryOne(
                "SELECT paid_amount_minor FROM credit_statement_projection csp JOIN credit_statement cs ON cs.id=csp.statement_id WHERE cs.uid=?",
                arrayOf(request.ids.statementId.bytes),
            ) { it.getLong(0) } ?: 0L
        }
        val asOf = request.changedAt.atZone(statementZone(database, request.accountId)).toLocalDate()
        val status = CreditStatementPolicy.status(request.estimatedAmountMinor, request.officialAmountMinor, paid, request.dueDate, request.sealed, asOf)
        val statementId = CreditStatementId(request.ids.statementId)
        val revisionId = CreditStatementRevisionId(request.ids.statementRevisionId)
        val statement = CreditStatement(statementId, UserAccountId(request.accountId), request.cycleStart, request.cycleEnd, request.dueDate, revisionId, status)
        val revision = CreditStatementRevision(
            revisionId,
            statementId,
            request.revisionNumber,
            request.estimatedAmountMinor,
            request.officialAmountMinor,
            request.officialRecordedAt,
            CreditStatementPolicy.difference(request.estimatedAmountMinor, request.officialAmountMinor),
            request.cycleEnd,
            request.dueDate,
            request.sealed,
            BookCommitId(request.ids.mutation.commitId),
        )
        val draft = SaveCreditStatementCommand(
            request.ids.mutation.commandId,
            zeroHash(),
            CreditStatementMutation(statement, revision, request.expectedRevisionId?.let(::CreditStatementRevisionId)),
        )
        val command = draft.copy(payloadHash = CanonicalFinancialHash.command(draft))
        coordinate(
            database,
            command,
            operationSnapshot(book, request.ids.mutation.commitId, request.ids.mutation.deviceInstanceId, request.changedAt),
        )
    }

    override suspend fun recordPayment(request: RecordCreditPaymentRequest): DomainResult<app.ledger.finance.domain.CommandReceipt> = withDatabase(request.ids.bookId) { database ->
        if (request.generationMode != AutoGenerationMode.FORMAL_TRANSACTION) {
            return@withDatabase DomainResult.Failure(app.ledger.finance.domain.DomainViolation.Invariant("INV-028"))
        }
        database.readLedger { db -> repeatedPaymentReceipt(db, request) }?.let { return@withDatabase DomainResult.Success(it) }
        val snapshot = database.readLedger { db -> paymentPlanningSnapshot(db, request) }
        val references = requireNotNull(snapshot.accountingContext).references
        val creditAccount = references.account(UserAccountId(request.credit.accountId))?.account ?: abort(FinanceDataError.CorruptData)
        val payable = database.readLedger { db -> payableStatements(db, request.credit.accountId) }
        val activeDebtMinor = database.readLedger { db -> creditDebtMinor(db, request.credit.accountId) }
        val amount = positive(request.credit.accountMinor, creditAccount.currency)
        val allocations = CreditPaymentAllocationPolicy.allocate(amount, payable, request.selection, activeDebtMinor).valueOrAbort()
        val assignment = allocations.singleOrNull()?.statementId?.let {
            StatementAssignment(StatementAssignmentMode.EXPLICIT_STATEMENT, it)
        }
        val context = TransactionContextInput(
            occurredAt = EffectiveTime.fromInstant(request.context.occurredAt, request.context.zoneId),
            accrualDate = request.context.localDate,
            budgetMonth = null,
            merchantId = null,
            projectId = null,
            goalId = null,
            locationRecordId = null,
            note = request.context.note,
            amountExpression = request.context.amountExpression,
            source = if (request.sourceOccurrenceId == null) TransactionSource.MANUAL else TransactionSource.RECURRENCE_AUTO,
            sourceReferenceId = request.sourceOccurrenceId,
            statementAssignment = assignment,
            attachmentIds = emptyList(),
        )
        val draft = RecordCreditPaymentCommand(
            request.ids.commandId,
            zeroHash(),
            NewTransactionInput(
                context,
                CreditPaymentPayload(
                    accountAmount(request.payment, references),
                    UserAccountId(request.credit.accountId),
                    accountAmount(request.credit, references),
                    allocations,
                    AutoGenerationMode.FORMAL_TRANSACTION,
                ),
            ),
        )
        val command = draft.copy(payloadHash = CanonicalFinancialHash.command(draft))
        coordinate(database, command, snapshot)
    }

    override suspend fun assignStatement(request: AssignCreditStatementRequest): DomainResult<app.ledger.finance.domain.CommandReceipt> = withDatabase(request.ids.bookId) { database ->
        val source = database.readLedger { db ->
            mapper.load(db, request.ids.transactionId, request.ids.revisionId, request.ids.commitId, request.ids.factIds, request.ids.fxRateSnapshotIds, request.changedAt, request.ids.deviceInstanceId)
        }
        if (source.revision.kind !in setOf(TransactionKind.EXPENSE, TransactionKind.REFUND)) {
            return@withDatabase DomainResult.Failure(app.ledger.finance.domain.DomainViolation.InvalidField("creditStatement.assignmentTransaction"))
        }
        database.readLedger { db -> requireStatementMatchesTransactionAccount(db, request.statementId, request.ids.transactionId) }
        val replacement = NewTransactionInput(
            source.revision.toContextInput().copy(
                statementAssignment = StatementAssignment(request.mode, CreditStatementId(request.statementId)),
            ),
            source.revision.payload,
        )
        val draft = EditTransactionCommand(
            request.ids.commandId,
            TransactionRevisionId(request.expectedRevisionId),
            zeroHash(),
            TransactionId(request.ids.transactionId),
            replacement,
            emptyList(),
        )
        coordinate(database, draft.copy(payloadHash = CanonicalFinancialHash.command(draft)), source.snapshot)
    }

    override suspend fun reallocatePayment(request: ReallocateCreditPaymentRequest): DomainResult<app.ledger.finance.domain.CommandReceipt> = withDatabase(request.ids.bookId) { database ->
        val source = database.readLedger { db ->
            mapper.load(db, request.ids.transactionId, request.ids.revisionId, request.ids.commitId, request.ids.factIds, request.ids.fxRateSnapshotIds, request.changedAt, request.ids.deviceInstanceId)
        }
        val payload = source.revision.payload as? CreditPaymentPayload
            ?: return@withDatabase DomainResult.Failure(app.ledger.finance.domain.DomainViolation.InvalidField("creditPayment.transaction"))
        val payable = database.readLedger { db -> payableStatementsForReallocation(db, payload.creditAccountId.value, payload.allocations) }
        val activeDebtMinor = database.readLedger { db ->
            Math.addExact(creditDebtMinor(db, payload.creditAccountId.value), payload.creditAccountAmount.amount.minor.value)
        }
        val allocations = CreditPaymentAllocationPolicy.allocate(
            payload.creditAccountAmount.amount,
            payable,
            request.selection,
            activeDebtMinor,
        ).valueOrAbort()
        val assignment = allocations.singleOrNull()?.statementId?.let { StatementAssignment(StatementAssignmentMode.EXPLICIT_STATEMENT, it) }
        val replacement = NewTransactionInput(
            source.revision.toContextInput().copy(statementAssignment = assignment),
            payload.copy(allocations = allocations),
        )
        val draft = EditTransactionCommand(
            request.ids.commandId,
            TransactionRevisionId(request.expectedRevisionId),
            zeroHash(),
            TransactionId(request.ids.transactionId),
            replacement,
            emptyList(),
        )
        coordinate(database, draft.copy(payloadHash = CanonicalFinancialHash.command(draft)), source.snapshot)
    }

    override suspend fun proposeAutoPayment(bookId: StableId, statementId: StableId, occurrenceId: StableId): DomainResult<CreditAutoPaymentProposal> = withDatabase(bookId) { database ->
        database.readLedger { db ->
            val row = db.queryOne(
                "SELECT cs.uid statement_uid,ua.uid account_uid,ua.currency_code,ua.status,csp.official_amount_minor,csp.remaining_amount_minor,cp.debt_minor," +
                    "pay.uid payment_uid,pay.status payment_status FROM credit_statement cs " +
                    "JOIN user_account ua ON ua.id=cs.credit_account_id JOIN credit_statement_projection csp ON csp.statement_id=cs.id " +
                    "JOIN credit_account_projection cp ON cp.account_id=ua.id JOIN credit_account_profile cap ON cap.account_id=ua.id " +
                    "LEFT JOIN user_account pay ON pay.id=cap.default_payment_account_id WHERE cs.uid=?",
                arrayOf(statementId.bytes),
            ) { cursor ->
                AutoRow(
                    cursor.stableId("account_uid"),
                    currency(cursor.string("currency_code")),
                    cursor.int("status") == EntityStatus.ACTIVE.ordinal,
                    cursor.nullableLong("official_amount_minor"),
                    cursor.long("remaining_amount_minor"),
                    cursor.long("debt_minor"),
                    cursor.nullableStableId("payment_uid"),
                    cursor.nullableLong("payment_status")?.toInt() == EntityStatus.ACTIVE.ordinal,
                )
            } ?: abort(FinanceDataError.CorruptData)
            val duplicate = db.queryOne(
                "SELECT COUNT(*) FROM transaction_revision tr JOIN business_transaction bt ON bt.current_revision_id=tr.id " +
                    "WHERE tr.source_type=? AND tr.source_reference_uid=? AND bt.lifecycle_state=0",
                arrayOf(TransactionSource.RECURRENCE_AUTO.ordinal, occurrenceId.bytes),
            ) { it.getLong(0) } != 0L
            val authoritativeRemaining = minOf(maxOf(row.remaining, 0L), maxOf(row.activeDebt, 0L))
            val eligibility = CreditAutoPaymentPolicy.evaluate(
                row.official,
                authoritativeRemaining,
                row.paymentId != null && row.paymentActive,
                row.accountActive,
                duplicate,
            )
            DomainResult.Success(
                CreditAutoPaymentProposal(statementId, row.accountId, row.paymentId, authoritativeRemaining, row.currency, eligibility),
            )
        }
    }

    private fun readSnapshot(db: SupportSQLiteDatabase, bookId: StableId, baseCurrency: CurrencyCode, localRevision: app.ledger.finance.domain.LocalRevision): CreditSnapshot {
        val accounts = db.queryList(
            "SELECT ua.uid,ua.name,ua.currency_code,ua.status,COALESCE(abc.normal_balance_minor,0) signed_balance," +
                "cap.statement_rule_type,cap.statement_day,cap.due_rule_type,cap.due_day,cap.days_after_statement,cap.zone_id," +
                "cap.standard_limit_minor,cap.temporary_limit_minor,cap.temporary_limit_expires_on,pay.uid payment_uid," +
                "cap.auto_payment_mode,cap.weekend_adjustment,last.uid last_commit_uid,cp.debt_minor,cp.available_limit_minor," +
                "cp.estimated_unbilled_minor,cp.overdue_minor FROM user_account ua " +
                "LEFT JOIN account_balance_current abc ON abc.account_id=ua.id LEFT JOIN credit_account_profile cap ON cap.account_id=ua.id " +
                "LEFT JOIN user_account pay ON pay.id=cap.default_payment_account_id LEFT JOIN book_commit last ON last.id=cap.last_commit_id " +
                "LEFT JOIN credit_account_projection cp ON cp.account_id=ua.id WHERE ua.type=2 ORDER BY ua.sort_order,ua.name",
        ) { cursor ->
            val id = cursor.stableId("uid")
            val signed = cursor.long("signed_balance")
            CreditAccountView(
                id,
                cursor.string("name"),
                currency(cursor.string("currency_code")),
                cursor.int("status") != EntityStatus.ACTIVE.ordinal,
                cursor.nullableLong("statement_rule_type")?.let { profile(cursor) },
                signed,
                maxOf(signed, 0L),
                maxOf(-signed, 0L),
                cursor.nullableLong("available_limit_minor"),
                cursor.nullableLong("estimated_unbilled_minor") ?: 0L,
                cursor.nullableLong("overdue_minor") ?: 0L,
                statements(db, id),
            )
        }
        val paymentAccounts = db.queryList(
            "SELECT uid,name,currency_code,status FROM user_account WHERE type IN (0,1) ORDER BY sort_order,name",
        ) { CreditPaymentAccountView(it.stableId("uid"), it.string("name"), currency(it.string("currency_code")), it.int("status") == EntityStatus.ACTIVE.ordinal) }
        return CreditSnapshot(bookId, baseCurrency, localRevision, accounts, paymentAccounts)
    }

    private fun profile(cursor: android.database.Cursor): CreditProfileView = CreditProfileView(
        statementRule(cursor.int("statement_rule_type"), cursor.nullableLong("statement_day")?.toInt()),
        dueRule(cursor.int("due_rule_type"), cursor.nullableLong("due_day")?.toInt(), cursor.nullableLong("days_after_statement")?.toInt()),
        ZoneId.of(cursor.string("zone_id")),
        cursor.nullableLong("standard_limit_minor"),
        cursor.nullableLong("temporary_limit_minor"),
        cursor.nullableLong("temporary_limit_expires_on")?.toInt()?.toStoredLocalDate(),
        cursor.nullableStableId("payment_uid"),
        AutoGenerationMode.entries[cursor.int("auto_payment_mode")],
        WeekendAdjustment.entries[cursor.int("weekend_adjustment")],
        cursor.stableId("last_commit_uid"),
    )

    private fun statements(db: SupportSQLiteDatabase, accountId: StableId): List<CreditStatementView> = db.queryList(
        "SELECT cs.uid,csr.uid revision_uid,csr.revision_no,cs.cycle_start,cs.cycle_end,cs.due_date,csp.estimated_amount_minor," +
            "csp.official_amount_minor,csr.difference_minor,csp.paid_amount_minor,csp.remaining_amount_minor,csp.status,csr.sealed " +
            "FROM credit_statement cs JOIN credit_statement_revision csr ON csr.id=cs.current_revision_id " +
            "JOIN credit_statement_projection csp ON csp.statement_id=cs.id JOIN user_account ua ON ua.id=cs.credit_account_id " +
            "WHERE ua.uid=? ORDER BY cs.cycle_end DESC,cs.id DESC",
        arrayOf(accountId.bytes),
    ) { cursor ->
        CreditStatementView(
            cursor.stableId("uid"), cursor.stableId("revision_uid"), cursor.int("revision_no"),
            cursor.int("cycle_start").toStoredLocalDate(), cursor.int("cycle_end").toStoredLocalDate(), cursor.int("due_date").toStoredLocalDate(),
            cursor.long("estimated_amount_minor"), cursor.nullableLong("official_amount_minor"), cursor.nullableLong("difference_minor"),
            cursor.long("paid_amount_minor"), cursor.long("remaining_amount_minor"), CreditStatementStatus.entries[cursor.int("status")], cursor.int("sealed") == 1,
        )
    }

    private fun payableStatements(db: SupportSQLiteDatabase, accountId: StableId): List<PayableCreditStatement> = db.queryList(
        "SELECT cs.uid,cs.due_date,MAX(0,csp.remaining_amount_minor) remaining FROM credit_statement cs " +
            "JOIN credit_statement_projection csp ON csp.statement_id=cs.id JOIN user_account ua ON ua.id=cs.credit_account_id " +
            "WHERE ua.uid=? AND csp.remaining_amount_minor>0 ORDER BY cs.due_date,cs.id",
        arrayOf(accountId.bytes),
    ) { PayableCreditStatement(CreditStatementId(it.stableId("uid")), it.int("due_date").toStoredLocalDate(), it.long("remaining")) }

    private fun payableStatementsForReallocation(
        db: SupportSQLiteDatabase,
        accountId: StableId,
        currentAllocations: List<app.ledger.finance.domain.CreditPaymentAllocation>,
    ): List<PayableCreditStatement> {
        val allocatedByStatement = currentAllocations.filter { it.statementId != null }
            .groupBy { requireNotNull(it.statementId) }
            .mapValues { (_, values) -> values.fold(0L) { sum, allocation -> Math.addExact(sum, allocation.amount.minor.value) } }
        return db.queryList(
            "SELECT cs.uid,cs.due_date,MAX(0,csp.remaining_amount_minor) remaining FROM credit_statement cs " +
                "JOIN credit_statement_projection csp ON csp.statement_id=cs.id JOIN user_account ua ON ua.id=cs.credit_account_id " +
                "WHERE ua.uid=? ORDER BY cs.due_date,cs.id",
            arrayOf(accountId.bytes),
        ) {
            val statementId = CreditStatementId(it.stableId("uid"))
            PayableCreditStatement(
                statementId,
                it.int("due_date").toStoredLocalDate(),
                Math.addExact(it.long("remaining"), allocatedByStatement[statementId] ?: 0L),
            )
        }.filter { it.remainingMinor > 0L }
    }

    private fun creditDebtMinor(db: SupportSQLiteDatabase, accountId: StableId): Long = maxOf(
        0L,
        db.queryOne(
            "SELECT COALESCE(abc.normal_balance_minor,0) FROM user_account ua LEFT JOIN account_balance_current abc ON abc.account_id=ua.id WHERE ua.uid=? AND ua.type=2",
            arrayOf(accountId.bytes),
        ) { it.getLong(0) } ?: abort(FinanceDataError.CorruptData),
    )

    private fun paymentPlanningSnapshot(db: SupportSQLiteDatabase, request: RecordCreditPaymentRequest): PlanningSnapshot {
        val book = RoomBookRepository.mapCurrent(db)
        val references = mapper.references(db)
        val fxIds = request.ids.fxRateSnapshotIds.iterator()
        val evidence = listOf(
            frozenAmount(AmountRole.OUTGOING, request.payment, references, book.baseCurrency, fxIds),
            frozenAmount(AmountRole.INCOMING, request.credit, references, book.baseCurrency, fxIds),
        )
        return PlanningSnapshot(
            book, null, null, emptyList(), emptySet(), emptyList(), null, emptyList(),
            AccountingPlanningContext(
                PlanningIdentitySet(TransactionId(request.ids.transactionId), TransactionRevisionId(request.ids.revisionId), BookCommitId(request.ids.commitId), request.ids.factIds),
                request.context.createdAt,
                DeviceInstanceId(request.ids.deviceInstanceId),
                references,
                evidence,
                null,
            ),
        )
    }

    private fun repeatedPaymentReceipt(db: SupportSQLiteDatabase, request: RecordCreditPaymentRequest): CommandReceipt? {
        val stored = db.commandReceipt(request.ids.commandId) ?: return null
        val (historicalInput, evidence) = mapper.historicalInput(
            db,
            request.ids.transactionId,
            request.ids.revisionId,
            request.ids.fxRateSnapshotIds,
        )
        val payload = historicalInput.payload as? CreditPaymentPayload
            ?: abort(app.ledger.finance.domain.DomainViolation.DuplicateCommandPayloadMismatch)
        val context = historicalInput.context
        val source = if (request.sourceOccurrenceId == null) TransactionSource.MANUAL else TransactionSource.RECURRENCE_AUTO
        val accountEvidence = evidence.associateBy { it.key.role }
        val sameRequest = stored.commandTypeOrdinal == FinancialCommandType.RECORD_CREDIT_PAYMENT.ordinal &&
            stored.primaryEntityId == request.ids.transactionId &&
            payload.payment.accountId.value == request.payment.accountId &&
            payload.payment.amount.minor.value == request.payment.accountMinor &&
            payload.creditAccountId.value == request.credit.accountId &&
            payload.creditAccountAmount.amount.minor.value == request.credit.accountMinor &&
            payload.generationMode == request.generationMode &&
            context.occurredAt.instant == request.context.occurredAt &&
            context.occurredAt.zoneId == request.context.zoneId &&
            context.accrualDate == request.context.localDate &&
            context.amountExpression == request.context.amountExpression &&
            context.note == request.context.note &&
            context.source == source &&
            context.sourceReferenceId == request.sourceOccurrenceId &&
            accountEvidence[AmountRole.OUTGOING]?.baseAmount?.minor?.value == request.payment.baseMinor &&
            accountEvidence[AmountRole.INCOMING]?.baseAmount?.minor?.value == request.credit.baseMinor &&
            selectionMatches(request.selection, payload)
        val unsigned = RecordCreditPaymentCommand(request.ids.commandId, zeroHash(), NewTransactionInput(context, payload))
        if (!sameRequest || CanonicalFinancialHash.command(unsigned) != stored.payloadHash) {
            abort(app.ledger.finance.domain.DomainViolation.DuplicateCommandPayloadMismatch)
        }
        return CommandReceipt(
            stored.commandId,
            FinancialCommandType.RECORD_CREDIT_PAYMENT,
            stored.payloadHash,
            stored.commitId,
            StableEntityReference(EntityType.TRANSACTION, request.ids.transactionId),
            stored.executedAt,
        )
    }

    private fun selectionMatches(selection: app.ledger.finance.domain.CreditPaymentSelection, payload: CreditPaymentPayload): Boolean = when (selection) {
        app.ledger.finance.domain.CreditPaymentSelection.EarliestUnpaid -> payload.allocations.isNotEmpty() && payload.allocations.all { it.statementId != null }
        is app.ledger.finance.domain.CreditPaymentSelection.Specific ->
            payload.allocations.size == 1 && payload.allocations.single().statementId == selection.statementId
        app.ledger.finance.domain.CreditPaymentSelection.UnallocatedAdvance -> payload.allocations.size == 1 && payload.allocations.single().statementId == null
    }

    private fun frozenAmount(role: AmountRole, draft: SpecializedAccountAmountDraft, references: PlanningReferenceData, baseCurrency: CurrencyCode, fxIds: Iterator<StableId>): FrozenAmountEvidence {
        val account = references.account(UserAccountId(draft.accountId)) ?: abort(FinanceDataError.CorruptData)
        val accountMoney = positive(draft.accountMinor, account.account.currency)
        val baseMoney = positive(draft.baseMinor, baseCurrency)
        val conversion = if (account.account.currency == baseCurrency) {
            if (draft.accountMinor != draft.baseMinor || draft.accountToBaseEvidence != null) abort(app.ledger.finance.domain.DomainViolation.InvalidField("creditPayment.baseAmount"))
            null
        } else {
            val fx = draft.accountToBaseEvidence ?: abort(app.ledger.finance.domain.DomainViolation.InvalidField("creditPayment.fxEvidence"))
            if (fx.sourceCurrency != account.account.currency || fx.targetCurrency != baseCurrency) abort(app.ledger.finance.domain.DomainViolation.InvalidField("creditPayment.fxCurrency"))
            FrozenFxConversion.create(
                FxRateSnapshotId(if (fxIds.hasNext()) fxIds.next() else abort(app.ledger.finance.domain.DomainViolation.InvalidField("creditPayment.fxId"))),
                accountMoney,
                baseMoney,
                fx,
                currencyCatalog.require(account.account.currency).valueOrAbort(),
                currencyCatalog.require(baseCurrency).valueOrAbort(),
                false,
            ).valueOrAbort()
        }
        return FrozenAmountEvidence.create(AmountEvidenceKey(role, 0), accountMoney, accountMoney, baseMoney, account.account.id, null, conversion).valueOrAbort()
    }

    private fun operationSnapshot(book: app.ledger.finance.domain.Book, commitId: StableId, deviceId: StableId, changedAt: Instant): PlanningSnapshot = PlanningSnapshot(
        book, null, null, emptyList(), emptySet(), emptyList(), null, emptyList(),
        operationContext = PlanningOperationContext(BookCommitId(commitId), changedAt, DeviceInstanceId(deviceId)),
    )

    private suspend fun coordinate(database: LedgerDatabase, command: FinancialCommand, snapshot: PlanningSnapshot): DomainResult<app.ledger.finance.domain.CommandReceipt> {
        val repository = RoomFinancialCommitRepository(database)
        return DefaultFinancialMutationCoordinator(
            writeGate,
            repository,
            object : FinancialPlanningSnapshotRepository {
                override suspend fun load(command: FinancialCommand): DomainResult<PlanningSnapshot> = DomainResult.Success(snapshot)
            },
            FinancialPlanningPort(DeterministicFinancialPlanner::plan),
            repository,
        ).execute(command)
    }

    private fun accountAmount(draft: SpecializedAccountAmountDraft, references: PlanningReferenceData): AccountAmount {
        val account = references.account(UserAccountId(draft.accountId))?.account ?: abort(FinanceDataError.CorruptData)
        return AccountAmount.create(account, Money(draft.accountMinor, account.currency)).valueOrAbort()
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

    private fun statementZone(database: LedgerDatabase, accountId: StableId): ZoneId = database.readLedger { db ->
        db.queryOne(
            "SELECT COALESCE(cap.zone_id,b.default_zone_id) FROM user_account ua CROSS JOIN book b LEFT JOIN credit_account_profile cap ON cap.account_id=ua.id WHERE ua.uid=? AND b.id=1",
            arrayOf(accountId.bytes),
        ) { ZoneId.of(it.getString(0)) } ?: abort(FinanceDataError.CorruptData)
    }

    private fun requireStatementMatchesTransactionAccount(db: SupportSQLiteDatabase, statementId: StableId, transactionId: StableId) {
        val matches = db.queryOne(
            "SELECT COUNT(*) FROM credit_statement cs JOIN user_account ua ON ua.id=cs.credit_account_id " +
                "JOIN posting p ON p.ledger_account_id=ua.ledger_account_id JOIN journal_entry je ON je.id=p.journal_entry_id " +
                "JOIN transaction_revision tr ON tr.id=je.source_revision_id JOIN business_transaction bt ON bt.id=tr.transaction_id " +
                "WHERE cs.uid=? AND bt.uid=? AND je.role=0",
            arrayOf(statementId.bytes, transactionId.bytes),
        ) { it.getLong(0) } ?: 0L
        if (matches == 0L) abort(app.ledger.finance.domain.DomainViolation.InvalidField("creditStatement.account"))
    }

    private fun statementRule(type: Int, day: Int?): StatementDateRule = when (type) {
        0 -> StatementDateRule.DayOfMonth(requireNotNull(day), app.ledger.finance.domain.MissingDayPolicy.MOVE_TO_MONTH_END)
        1 -> StatementDateRule.DayOfMonth(requireNotNull(day), app.ledger.finance.domain.MissingDayPolicy.SKIP)
        2 -> StatementDateRule.LastDayOfMonth
        else -> abort(FinanceDataError.CorruptData)
    }

    private fun dueRule(type: Int, day: Int?, daysAfter: Int?): DueDateRule = when (type) {
        0 -> DueDateRule.FixedDay(requireNotNull(day), app.ledger.finance.domain.MissingDayPolicy.MOVE_TO_MONTH_END)
        1 -> DueDateRule.FixedDay(requireNotNull(day), app.ledger.finance.domain.MissingDayPolicy.SKIP)
        2 -> DueDateRule.DaysAfterStatement(requireNotNull(daysAfter))
        else -> abort(FinanceDataError.CorruptData)
    }

    private suspend fun <T> withDatabase(bookId: StableId, block: suspend (LedgerDatabase) -> DomainResult<T>): DomainResult<T> = try {
        keyProvider.open(bookId).use { keys ->
            val database = keys.databaseDek.useBytes { openSelectedLedger(applicationContext, it, databaseName) }
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

    private fun zeroHash(): Hash256 = Hash256.fromBytes(ByteArray(32)).valueOrAbort()

    private data class AutoRow(
        val accountId: StableId,
        val currency: CurrencyCode,
        val accountActive: Boolean,
        val official: Long?,
        val remaining: Long,
        val activeDebt: Long,
        val paymentId: StableId?,
        val paymentActive: Boolean,
    )
}

private class CreditWriteGate : LedgerWriteGate {
    private val mutex = Mutex()
    override suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock { block() }
}

private fun android.database.Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
private fun android.database.Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
private fun android.database.Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun currency(value: String): CurrencyCode = CurrencyCode.parse(value).valueOrAbort()
