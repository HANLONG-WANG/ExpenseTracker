@file:Suppress("LongMethod", "LongParameterList", "MagicNumber", "TooManyFunctions", "MaxLineLength")

package app.ledger.finance.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.FxEvidence
import app.ledger.core.money.FxEvidenceInput
import app.ledger.core.money.FxProvider
import app.ledger.core.money.FxRateSource
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.Money
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.time.EffectiveTime
import app.ledger.finance.application.DefaultFinancialMutationCoordinator
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.FinancialPlanningPort
import app.ledger.finance.application.FinancialPlanningSnapshotRepository
import app.ledger.finance.application.LedgerWriteGate
import app.ledger.finance.application.OrdinaryAmountDraft
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryParticipantView
import app.ledger.finance.application.OrdinaryProjectView
import app.ledger.finance.application.OrdinaryRecentDefaultView
import app.ledger.finance.application.OrdinarySettlementActivityView
import app.ledger.finance.application.OrdinarySettlementShareDraft
import app.ledger.finance.application.OrdinaryTemplateView
import app.ledger.finance.application.OrdinaryTransactionEditView
import app.ledger.finance.application.OrdinaryTransactionEntryPort
import app.ledger.finance.application.OrdinaryTransactionEntrySnapshot
import app.ledger.finance.application.OrdinaryTransactionWriteRequest
import app.ledger.finance.application.ReferenceDataManagementPort
import app.ledger.finance.domain.AccountAmount
import app.ledger.finance.domain.AccountingPlanningContext
import app.ledger.finance.domain.AmountEvidenceKey
import app.ledger.finance.domain.AmountRole
import app.ledger.finance.domain.AutoGenerationMode
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.CategoryAssignment
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.CreditAccountProfile
import app.ledger.finance.domain.CreditCalendarPolicy
import app.ledger.finance.domain.CreditStatementId
import app.ledger.finance.domain.DeterministicFinancialPlanner
import app.ledger.finance.domain.DeviceInstanceId
import app.ledger.finance.domain.DueDateRule
import app.ledger.finance.domain.EditTransactionCommand
import app.ledger.finance.domain.ExpensePayer
import app.ledger.finance.domain.ExpensePayload
import app.ledger.finance.domain.FrozenAmountEvidence
import app.ledger.finance.domain.FrozenFxConversion
import app.ledger.finance.domain.FxRateSnapshotId
import app.ledger.finance.domain.GoalId
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.IncomePayload
import app.ledger.finance.domain.InstallmentPlanId
import app.ledger.finance.domain.LocationRecordId
import app.ledger.finance.domain.MerchantId
import app.ledger.finance.domain.MissingDayPolicy
import app.ledger.finance.domain.NewTransactionInput
import app.ledger.finance.domain.ParticipantId
import app.ledger.finance.domain.PaymentCardId
import app.ledger.finance.domain.PlanningIdentitySet
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.PositiveMoney
import app.ledger.finance.domain.ProjectId
import app.ledger.finance.domain.RecordExpenseCommand
import app.ledger.finance.domain.RecordIncomeCommand
import app.ledger.finance.domain.SettlementActivityId
import app.ledger.finance.domain.SettlementShare
import app.ledger.finance.domain.StatementAssignment
import app.ledger.finance.domain.StatementAssignmentMode
import app.ledger.finance.domain.StatementDateRule
import app.ledger.finance.domain.TransactionContextInput
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.TransactionRevisionId
import app.ledger.finance.domain.UserAccountId
import app.ledger.finance.domain.WeekendAdjustment
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.MathContext
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.YearMonth
import java.time.ZoneId

/** SQLCipher-backed ordinary entry adapter; every write terminates at FinancialMutationCoordinator. */
public class SecureRoomOrdinaryTransactionEntryPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val referenceDataPort: ReferenceDataManagementPort = SecureRoomReferenceDataManagementPort(context, keyProvider),
) : OrdinaryTransactionEntryPort {
    private val applicationContext = context.applicationContext
    private val gate = OrdinaryLedgerWriteGate()
    private val mapper = RoomReferenceFinancialSnapshotMapper()
    private val currencies = JvmLegalTenderCurrencyCatalog.create()

    override suspend fun snapshot(bookId: StableId, transactionId: StableId?): DomainResult<OrdinaryTransactionEntrySnapshot> {
        val references = when (val result = referenceDataPort.entrySnapshot(bookId)) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
        return withDatabase(bookId) { database ->
            database.readLedger { db ->
                if (RoomBookRepository.mapCurrent(db).id.value != bookId) abort(FinanceDataError.CorruptData)
                OrdinaryTransactionEntrySnapshot(
                    references = references,
                    projects = projects(db),
                    settlementActivities = settlementActivities(db),
                    templates = templates(db),
                    recentDefaults = recentDefaults(db),
                    editing = transactionId?.let { editing(db, it) },
                )
            }
        }
    }

    override suspend fun submit(request: OrdinaryTransactionWriteRequest): DomainResult<CommandReceipt> = when (
        val opened = withDatabase(request.ids.bookId) { database -> execute(database, request) }
    ) {
        is DomainResult.Success -> opened.value
        is DomainResult.Failure -> opened
    }

    private suspend fun execute(database: LedgerDatabase, request: OrdinaryTransactionWriteRequest): DomainResult<CommandReceipt> {
        val prepared = prepare(database, request)
        val repository = RoomFinancialCommitRepository(
            database,
            sideEffect = prepared.sideEffect,
            afterFinancialWriteSideEffect = prepared.afterFinancialWriteSideEffect,
        )
        return DefaultFinancialMutationCoordinator(
            writeGate = gate,
            receiptRepository = repository,
            snapshotRepository = object : FinancialPlanningSnapshotRepository {
                override suspend fun load(command: app.ledger.finance.domain.FinancialCommand): DomainResult<PlanningSnapshot> = DomainResult.Success(prepared.snapshot)
            },
            planner = FinancialPlanningPort(DeterministicFinancialPlanner::plan),
            commitRepository = repository,
        ).execute(prepared.command)
    }

    internal fun prepare(database: LedgerDatabase, request: OrdinaryTransactionWriteRequest): PreparedFinancialMutation {
        val automaticStatement = database.readLedger { db -> automaticStatement(db, request) }
        val settledActivity = database.readLedger { db -> settledActivityForEdit(db, request) }
        val snapshot = database.readLedger { db -> planningSnapshot(db, request, automaticStatement?.newStatement != null) }
        val references = requireNotNull(snapshot.accountingContext).references
        val account = request.accountId?.let { references.account(UserAccountId(it)) ?: abort(FinanceDataError.CorruptData) }
        val category = references.category(CategoryId(request.categoryId)) ?: abort(FinanceDataError.CorruptData)
        val evidence = requireNotNull(snapshot.accountingContext).amount(AmountRole.PRIMARY) ?: abort(FinanceDataError.CorruptData)
        val context = TransactionContextInput(
            occurredAt = EffectiveTime.fromInstant(request.occurredAt, request.zoneId),
            accrualDate = request.localDate,
            budgetMonth = YearMonth.from(request.localDate),
            merchantId = request.merchantId?.let(::MerchantId),
            projectId = request.projectId?.let(::ProjectId),
            goalId = request.goalId?.let(::GoalId),
            locationRecordId = request.locationRecordId?.let(::LocationRecordId),
            note = request.note?.trim(),
            amountExpression = request.amount.expression,
            source = request.source,
            sourceReferenceId = request.sourceReferenceId,
            statementAssignment = automaticStatement?.let { StatementAssignment(StatementAssignmentMode.AUTOMATIC, CreditStatementId(it.statementId)) },
            attachmentIds = request.attachmentIds.map { app.ledger.finance.domain.AttachmentId(it) },
        )
        val classification = CategoryAssignment(category.id, category.direction, category.statisticalNature)
        val payload = when (request.direction) {
            OrdinaryDirection.EXPENSE -> {
                val payerParticipantId = request.settlementShares.singleOrNull { it.paidMinor > 0L }?.participantId
                val selfParticipantId = snapshot.participants.singleOrNull { it.isSelf }?.id?.value
                val settlementActivityId = request.settlementActivityId
                val payer = if (settlementActivityId != null && payerParticipantId != null && payerParticipantId != selfParticipantId) {
                    if (account != null || request.cardId != null) abort(FinanceDataError.CorruptData)
                    ExpensePayer.ExternalParticipant(ParticipantId(payerParticipantId), SettlementActivityId(settlementActivityId))
                } else {
                    val localAccount = account ?: abort(FinanceDataError.CorruptData)
                    ExpensePayer.LocalAccount(AccountAmount.create(localAccount.account, evidence.accountAmount.money).valueOrAbort(), request.cardId?.let(::PaymentCardId))
                }
                ExpensePayload(
                    classification,
                    payer,
                    evidence.userInput,
                    request.settlementActivityId?.let(::SettlementActivityId),
                    request.settlementShares.map { it.toDomain() },
                    request.installmentPlanId?.let(::InstallmentPlanId),
                )
            }
            OrdinaryDirection.INCOME -> {
                val localAccount = account ?: abort(FinanceDataError.CorruptData)
                IncomePayload(classification, AccountAmount.create(localAccount.account, evidence.accountAmount.money).valueOrAbort(), evidence.userInput)
            }
        }
        val input = NewTransactionInput(context, payload)
        val emptyHash = Hash256.fromBytes(ByteArray(32)).valueOrAbort()
        val expectedRevisionId = request.expectedRevisionId
        val command = if (expectedRevisionId == null) {
            when (payload) {
                is ExpensePayload -> RecordExpenseCommand(CommandId(request.ids.commandId), emptyHash, NewTransactionInput(context, payload))
                is IncomePayload -> RecordIncomeCommand(CommandId(request.ids.commandId), emptyHash, NewTransactionInput(context, payload))
                else -> abort(FinanceDataError.CorruptData)
            }
        } else {
            EditTransactionCommand(
                CommandId(request.ids.commandId),
                TransactionRevisionId(expectedRevisionId),
                emptyHash,
                TransactionId(request.ids.transactionId),
                input,
                emptyList(),
            )
        }.withCanonicalHash()
        val hasReferenceSideEffect = request.newLocation != null || automaticStatement?.newStatement != null || settledActivity != null
        val sideEffect = if (hasReferenceSideEffect) {
            FinancialCommitSideEffect { db, plan ->
                val location = request.newLocation
                if (location != null) {
                    val internalId = db.allocateInternalId("location_record", location.id)
                    db.execSQL(
                        "INSERT INTO location_record(id,uid,lat_e7,lon_e7,accuracy_mm,captured_at,source,provider,place_id,created_commit_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        arrayOf<Any?>(
                            internalId,
                            location.id.bytes,
                            location.latitudeE7,
                            location.longitudeE7,
                            location.accuracyMillimeters,
                            location.capturedAt.toStorageEpochMillis(),
                            if (location.provider == app.ledger.finance.application.OrdinaryLocationProvider.MANUAL) 1 else 0,
                            location.provider.name,
                            db.optionalInternalId("place", location.placeId),
                            db.commitId(plan.commit.id),
                        ),
                    )
                }
                automaticStatement?.newStatement?.let { statement -> insertAutomaticStatement(db, plan, statement) }
                settledActivity?.let { activityId ->
                    db.execSQL(
                        "UPDATE settlement_activity SET status=?,requires_additional_settlement=1,last_commit_id=? WHERE uid=? AND status=?",
                        arrayOf<Any>(
                            app.ledger.finance.domain.SettlementActivityStatus.REQUIRES_ADDITIONAL_SETTLEMENT.ordinal,
                            db.commitId(plan.commit.id),
                            activityId.bytes,
                            app.ledger.finance.domain.SettlementActivityStatus.SETTLED.ordinal,
                        ),
                    )
                    db.execSQL(
                        "INSERT INTO entity_change(commit_id,entity_type,entity_uid,operation,before_hash,after_hash,entity_revision_uid) VALUES(?,?,?,?,NULL,NULL,NULL)",
                        arrayOf<Any>(
                            db.commitId(plan.commit.id),
                            app.ledger.finance.domain.EntityType.SETTLEMENT_ACTIVITY.ordinal,
                            activityId.bytes,
                            app.ledger.finance.domain.EntityChangeOperation.UPDATE.ordinal,
                        ),
                    )
                }
            }
        } else {
            FinancialCommitSideEffect.NONE
        }
        return PreparedFinancialMutation(
            command,
            snapshot,
            sideEffect,
            candidateAcceptanceSideEffect(request.acceptedCandidateId, request.ids.transactionId),
        )
    }

    private fun candidateAcceptanceSideEffect(candidateUid: StableId?, transactionUid: StableId): FinancialCommitSideEffect {
        if (candidateUid == null) return FinancialCommitSideEffect.NONE
        return FinancialCommitSideEffect { db, _ ->
            val candidateInternal = db.queryOne(
                "SELECT id FROM recurrence_candidate WHERE uid=? AND status IN (?,?)",
                arrayOf(
                    candidateUid.bytes,
                    app.ledger.finance.domain.RecurrenceCandidateStatus.PENDING_CONFIRMATION.ordinal,
                    app.ledger.finance.domain.RecurrenceCandidateStatus.INVALID.ordinal,
                ),
            ) { it.getLong(0) } ?: abort(app.ledger.finance.domain.DomainViolation.InvalidStateTransition("candidate.complete"))
            val transactionInternal = db.requireInternalId("business_transaction", transactionUid)
            db.execSQL(
                "UPDATE recurrence_candidate SET status=?,validation_error_code=NULL WHERE id=?",
                arrayOf<Any>(app.ledger.finance.domain.RecurrenceCandidateStatus.ACCEPTED.ordinal, candidateInternal),
            )
            db.execSQL(
                "UPDATE recurrence_occurrence SET status=?,transaction_id=?,candidate_id=NULL,error_code=NULL WHERE candidate_id=?",
                arrayOf<Any>(app.ledger.finance.domain.RecurrenceOccurrenceStatus.TRANSACTION_CREATED.ordinal, transactionInternal, candidateInternal),
            )
        }
    }

    private fun planningSnapshot(db: SupportSQLiteDatabase, request: OrdinaryTransactionWriteRequest, reserveStatementIds: Boolean): PlanningSnapshot {
        val book = RoomBookRepository.mapCurrent(db)
        if (book.id.value != request.ids.bookId || request.localDate != request.occurredAt.atZone(request.zoneId).toLocalDate()) {
            abort(FinanceDataError.CorruptData)
        }
        val references = mapper.references(db)
        val account = request.accountId?.let { references.account(UserAccountId(it)) ?: abort(FinanceDataError.CorruptData) }
        val category = references.category(CategoryId(request.categoryId)) ?: abort(FinanceDataError.CorruptData)
        val expectedDirection = if (request.direction == OrdinaryDirection.EXPENSE) CategoryDirection.EXPENSE else CategoryDirection.INCOME
        if (category.direction != expectedDirection || request.direction == OrdinaryDirection.INCOME && request.settlementShares.isNotEmpty()) {
            abort(FinanceDataError.CorruptData)
        }
        val evidence = amountEvidence(request.amount, account?.account?.currency ?: request.amount.userCurrency, book.baseCurrency, account?.account?.id, request)
        val factIds = if (reserveStatementIds) request.ids.factIds.dropLast(AUTOMATIC_STATEMENT_ID_COUNT) else request.ids.factIds
        val identities = PlanningIdentitySet(
            TransactionId(request.ids.transactionId),
            TransactionRevisionId(request.ids.revisionId),
            BookCommitId(request.ids.commitId),
            factIds,
        )
        val baseContext = AccountingPlanningContext(identities, request.createdAt, DeviceInstanceId(request.ids.deviceInstanceId), references, listOf(evidence), null)
        if (request.expectedRevisionId == null) {
            return PlanningSnapshot(book, null, null, emptyList(), emptySet(), emptyList(), null, domainParticipants(db), baseContext)
        }
        val source = mapper.load(
            db,
            request.ids.transactionId,
            request.ids.revisionId,
            request.ids.commitId,
            factIds,
            request.ids.fxRateSnapshotIds,
            request.createdAt,
            request.ids.deviceInstanceId,
        )
        // Keep the actual current revision in the planning snapshot. The coordinator compares it
        // with the command's expected revision and returns the typed optimistic-lock conflict.
        // Rejecting the mismatch here would incorrectly turn a normal concurrent edit into
        // corrupt-data and bypass the single application/domain conflict policy.
        if (source.revision.kind !in setOf(TransactionKind.EXPENSE, TransactionKind.INCOME)) {
            abort(FinanceDataError.CorruptData)
        }
        return source.snapshot.copy(
            participants = domainParticipants(db),
            accountingContext = requireNotNull(source.snapshot.accountingContext).copy(
                identities = identities,
                createdAt = request.createdAt,
                deviceInstanceId = DeviceInstanceId(request.ids.deviceInstanceId),
                amountEvidence = listOf(evidence),
            ),
        )
    }

    private fun domainParticipants(db: SupportSQLiteDatabase): List<app.ledger.finance.domain.Participant> = db.queryList(
        "SELECT p.uid,p.name,p.is_self,p.status,bc.uid commit_uid FROM participant p JOIN book_commit bc ON bc.id=p.last_commit_id WHERE p.status=? ORDER BY p.is_self DESC,p.uid",
        arrayOf(app.ledger.finance.domain.EntityStatus.ACTIVE.ordinal),
    ) {
        app.ledger.finance.domain.Participant(
            app.ledger.finance.domain.ParticipantId(it.stableId("uid")),
            it.getString(1),
            it.getInt(2) == 1,
            app.ledger.finance.domain.EntityStatus.entries[it.getInt(3)],
            app.ledger.finance.domain.BookCommitId(it.stableId("commit_uid")),
        )
    }

    private fun settledActivityForEdit(db: SupportSQLiteDatabase, request: OrdinaryTransactionWriteRequest): StableId? {
        if (request.expectedRevisionId == null) return null
        return db.queryOne(
            "SELECT sa.uid FROM business_transaction bt JOIN transaction_revision tr ON tr.id=bt.current_revision_id " +
                "JOIN expense_revision_detail erd ON erd.revision_id=tr.id JOIN settlement_activity sa ON sa.id=erd.settlement_activity_id " +
                "WHERE bt.uid=? AND sa.status=?",
            arrayOf(request.ids.transactionId.bytes, app.ledger.finance.domain.SettlementActivityStatus.SETTLED.ordinal),
        ) { it.stableId("uid") }
    }

    @Suppress("ReturnCount")
    private fun automaticStatement(db: SupportSQLiteDatabase, request: OrdinaryTransactionWriteRequest): AutomaticStatement? {
        val requestAccountId = request.accountId ?: return null
        val row = db.queryOne(
            "SELECT ua.id account_id,cap.statement_rule_type,cap.statement_day,cap.due_rule_type,cap.due_day,cap.days_after_statement," +
                "cap.zone_id,cap.standard_limit_minor,cap.temporary_limit_minor,cap.temporary_limit_expires_on,pay.uid payment_uid," +
                "cap.auto_payment_mode,cap.weekend_adjustment,last.uid last_commit_uid FROM user_account ua " +
                "JOIN credit_account_profile cap ON cap.account_id=ua.id JOIN book_commit last ON last.id=cap.last_commit_id " +
                "LEFT JOIN user_account pay ON pay.id=cap.default_payment_account_id WHERE ua.uid=? AND ua.type=2",
            arrayOf(requestAccountId.bytes),
        ) { cursor ->
            val statementRule = when (cursor.getInt(cursor.getColumnIndexOrThrow("statement_rule_type"))) {
                0 -> StatementDateRule.DayOfMonth(cursor.getInt(cursor.getColumnIndexOrThrow("statement_day")), MissingDayPolicy.MOVE_TO_MONTH_END)
                1 -> StatementDateRule.DayOfMonth(cursor.getInt(cursor.getColumnIndexOrThrow("statement_day")), MissingDayPolicy.SKIP)
                2 -> StatementDateRule.LastDayOfMonth
                else -> abort(FinanceDataError.CorruptData)
            }
            val dueRule = when (cursor.getInt(cursor.getColumnIndexOrThrow("due_rule_type"))) {
                0 -> DueDateRule.FixedDay(cursor.getInt(cursor.getColumnIndexOrThrow("due_day")), MissingDayPolicy.MOVE_TO_MONTH_END)
                1 -> DueDateRule.FixedDay(cursor.getInt(cursor.getColumnIndexOrThrow("due_day")), MissingDayPolicy.SKIP)
                2 -> DueDateRule.DaysAfterStatement(cursor.getInt(cursor.getColumnIndexOrThrow("days_after_statement")))
                else -> abort(FinanceDataError.CorruptData)
            }
            AutomaticProfile(
                cursor.getLong(cursor.getColumnIndexOrThrow("account_id")),
                CreditAccountProfile(
                    UserAccountId(requestAccountId), statementRule, dueRule, ZoneId.of(cursor.getString(cursor.getColumnIndexOrThrow("zone_id"))),
                    cursor.nullableLong("standard_limit_minor"), cursor.nullableLong("temporary_limit_minor"),
                    cursor.nullableLong("temporary_limit_expires_on")?.toInt()?.toStoredLocalDate(), cursor.nullableStableId("payment_uid")?.let(::UserAccountId),
                    AutoGenerationMode.entries[cursor.getInt(cursor.getColumnIndexOrThrow("auto_payment_mode"))],
                    WeekendAdjustment.entries[cursor.getInt(cursor.getColumnIndexOrThrow("weekend_adjustment"))], BookCommitId(cursor.stableId("last_commit_uid")),
                ),
            )
        } ?: return null
        val localDate = request.occurredAt.atZone(row.profile.statementZoneId).toLocalDate()
        val cycle = CreditCalendarPolicy.cycleContaining(localDate, row.profile).valueOrAbort()
        val existing = db.queryOne(
            "SELECT uid FROM credit_statement WHERE credit_account_id=? AND cycle_start=? AND cycle_end=?",
            arrayOf<Any>(row.accountInternalId, cycle.cycleStart.toStorageInt(), cycle.cycleEnd.toStorageInt()),
        ) { it.stableId("uid") }
        return existing?.let { AutomaticStatement(it, null) } ?: run {
            if (request.ids.factIds.size <= AUTOMATIC_STATEMENT_ID_COUNT) abort(FinanceDataError.CorruptData)
            val statementId = request.ids.factIds[request.ids.factIds.lastIndex - 1]
            val revisionId = request.ids.factIds.last()
            AutomaticStatement(statementId, AutomaticStatementDraft(statementId, revisionId, row.accountInternalId, cycle))
        }
    }

    private fun insertAutomaticStatement(
        db: SupportSQLiteDatabase,
        plan: app.ledger.finance.domain.FinancialMutationPlan,
        statement: AutomaticStatementDraft,
    ) {
        val statementId = db.allocateInternalId("credit_statement", statement.statementId)
        db.execSQL(
            "INSERT INTO credit_statement(id,uid,credit_account_id,cycle_start,cycle_end,due_date,current_revision_id,status) VALUES(?,?,?,?,?,?,NULL,0)",
            arrayOf<Any>(statementId, statement.statementId.bytes, statement.accountInternalId, statement.cycle.cycleStart.toStorageInt(), statement.cycle.cycleEnd.toStorageInt(), statement.cycle.dueDate.toStorageInt()),
        )
        val revisionId = db.allocateInternalId("credit_statement_revision", statement.revisionId)
        db.execSQL(
            "INSERT INTO credit_statement_revision(id,uid,statement_id,revision_no,estimated_amount_minor,official_amount_minor,official_recorded_at,difference_minor,statement_date,due_date,sealed,created_commit_id) VALUES(?,?,?,1,0,NULL,NULL,NULL,?,?,0,?)",
            arrayOf<Any>(revisionId, statement.revisionId.bytes, statementId, statement.cycle.cycleEnd.toStorageInt(), statement.cycle.dueDate.toStorageInt(), db.commitId(plan.commit.id)),
        )
        db.execSQL("UPDATE credit_statement SET current_revision_id=? WHERE id=?", arrayOf<Any>(revisionId, statementId))
    }

    private fun amountEvidence(
        draft: OrdinaryAmountDraft,
        accountCurrency: CurrencyCode,
        baseCurrency: CurrencyCode,
        accountId: UserAccountId?,
        request: OrdinaryTransactionWriteRequest,
    ): FrozenAmountEvidence {
        var fxIndex = 0
        val user = PositiveMoney.from(Money(draft.userMinor, draft.userCurrency)).valueOrAbort()
        val account = PositiveMoney.from(Money(draft.accountMinor, accountCurrency)).valueOrAbort()
        val base = PositiveMoney.from(Money(draft.baseMinor, baseCurrency)).valueOrAbort()
        fun conversion(source: PositiveMoney, target: PositiveMoney): FrozenFxConversion? {
            if (source.currency == target.currency) {
                if (source != target) abort(FinanceDataError.CorruptData)
                return null
            }
            val id = request.ids.fxRateSnapshotIds.getOrNull(fxIndex++) ?: abort(FinanceDataError.CorruptData)
            val sourceMetadata = currencies.require(source.currency).valueOrAbort()
            val targetMetadata = currencies.require(target.currency).valueOrAbort()
            val rate = target.money.toMajor(targetMetadata).valueOrAbort()
                .divide(source.money.toMajor(sourceMetadata).valueOrAbort(), MathContext(34, RoundingMode.HALF_EVEN))
            val fx = FxEvidence.create(
                FxEvidenceInput(source.currency, target.currency, rate, FxProvider.of("manual").valueOrAbort(), request.createdAt, request.createdAt, FxRateSource.MANUAL, true),
            ).valueOrAbort()
            return FrozenFxConversion.create(FxRateSnapshotId(id), source, target, fx, sourceMetadata, targetMetadata, false).valueOrAbort()
        }
        return FrozenAmountEvidence.create(AmountEvidenceKey(AmountRole.PRIMARY, 0), user, account, base, accountId, conversion(user, account), conversion(account, base)).valueOrAbort()
    }

    private fun projects(db: SupportSQLiteDatabase): List<OrdinaryProjectView> = db.queryList(
        "SELECT uid,name,status FROM project ORDER BY status,name,uid",
    ) { OrdinaryProjectView(it.stableId("uid"), it.getString(1), it.getInt(2) == 0) }

    private fun settlementActivities(db: SupportSQLiteDatabase): List<OrdinarySettlementActivityView> = db.queryList(
        "SELECT uid,name,settlement_currency,status FROM settlement_activity ORDER BY status,name,uid",
    ) { activity ->
        val id = activity.stableId("uid")
        OrdinarySettlementActivityView(
            id,
            activity.getString(1),
            CurrencyCode.parse(activity.getString(2)).valueOrAbort(),
            db.queryList(
                "SELECT p.uid,p.name,p.is_self FROM settlement_activity_participant sap JOIN settlement_activity sa ON sa.id=sap.activity_id JOIN participant p ON p.id=sap.participant_id WHERE sa.uid=? AND sap.left_at IS NULL ORDER BY sap.sort_order,p.uid",
                arrayOf(id.bytes),
            ) { OrdinaryParticipantView(it.stableId("uid"), it.getString(1), it.getInt(2) == 1) },
            activity.getInt(3) == 0,
        )
    }

    private fun templates(db: SupportSQLiteDatabase): List<OrdinaryTemplateView> = db.queryList(
        "SELECT tb.uid,tb.name,tbr.target_kind,c.uid category_uid,ua.uid account_uid,pc.uid card_uid,m.uid merchant_uid,p.uid project_uid,sa.uid activity_uid,tbr.amount_expression,tbr.currency_code,tbr.note_template,pl.uid place_uid " +
            "FROM transaction_blueprint tb JOIN transaction_blueprint_revision tbr ON tbr.id=tb.current_revision_id " +
            "LEFT JOIN category c ON c.id=tbr.category_id LEFT JOIN user_account ua ON ua.id=tbr.primary_account_id LEFT JOIN payment_card pc ON pc.id=tbr.card_id " +
            "LEFT JOIN merchant m ON m.id=tbr.merchant_id LEFT JOIN project p ON p.id=tbr.project_id LEFT JOIN settlement_activity sa ON sa.id=tbr.settlement_activity_id LEFT JOIN place pl ON pl.id=tbr.fixed_place_id " +
            "WHERE tb.status=0 AND tbr.target_kind IN (0,1) ORDER BY tb.name,tb.uid",
    ) { cursor ->
        OrdinaryTemplateView(
            cursor.stableId("uid"), cursor.getString(1), if (cursor.getInt(2) == 0) OrdinaryDirection.EXPENSE else OrdinaryDirection.INCOME,
            cursor.nullableStableId("category_uid"), cursor.nullableStableId("account_uid"), cursor.nullableStableId("card_uid"),
            cursor.nullableStableId("merchant_uid"), cursor.nullableStableId("project_uid"), cursor.nullableStableId("activity_uid"),
            cursor.nullableString("amount_expression"), cursor.nullableString("currency_code")?.let { CurrencyCode.parse(it).valueOrAbort() },
            cursor.nullableString("note_template"), cursor.nullableStableId("place_uid"),
        )
    }

    private fun recentDefaults(db: SupportSQLiteDatabase): List<OrdinaryRecentDefaultView> = db.queryList(
        "SELECT recent.kind,c.uid category_uid,ua.uid account_uid,pc.uid card_uid,recent.occurred_at FROM (" +
            "SELECT kind,category_id,primary_account_id,card_id,occurred_at,transaction_id FROM current_transaction_projection INDEXED BY ix_current_transaction_keyset " +
            "WHERE state=0 AND kind IN (0,1) ORDER BY occurred_at DESC,transaction_id DESC LIMIT 50" +
            ") recent JOIN category c ON c.id=recent.category_id LEFT JOIN user_account ua ON ua.id=recent.primary_account_id " +
            "LEFT JOIN payment_card pc ON pc.id=recent.card_id ORDER BY recent.occurred_at DESC,recent.transaction_id DESC",
    ) {
        OrdinaryRecentDefaultView(if (it.getInt(0) == 0) OrdinaryDirection.EXPENSE else OrdinaryDirection.INCOME, it.stableId("category_uid"), it.nullableStableId("account_uid"), it.nullableStableId("card_uid"), it.getLong(4).toStoredInstant())
    }

    private fun editing(db: SupportSQLiteDatabase, id: StableId): OrdinaryTransactionEditView = db.queryOne(
        "SELECT bt.kind,tr.uid revision_uid,c.uid category_uid,tr.amount_expression,user.amount_minor user_minor,user.currency_code user_currency,acct.amount_minor account_minor,ua.uid account_uid,pc.uid card_uid,m.uid merchant_uid,tr.occurred_at,tr.zone_id,p.uid project_uid,sa.uid activity_uid,lr.uid location_uid,tr.note,tr.source_type,tr.source_reference_uid " +
            "FROM business_transaction bt JOIN transaction_revision tr ON tr.id=bt.current_revision_id JOIN category c ON c.id=tr.category_id " +
            "JOIN revision_amount user ON user.revision_id=tr.id AND user.component_index=0 AND user.role=0 AND user.representation=0 " +
            "JOIN revision_amount acct ON acct.revision_id=tr.id AND acct.component_index=0 AND acct.role=0 AND acct.representation=1 " +
            "LEFT JOIN expense_revision_detail erd ON erd.revision_id=tr.id LEFT JOIN income_revision_detail ird ON ird.revision_id=tr.id LEFT JOIN user_account ua ON ua.id=COALESCE(erd.payer_account_id,ird.receiving_account_id) " +
            "LEFT JOIN payment_card pc ON pc.id=erd.payer_card_id LEFT JOIN merchant m ON m.id=tr.merchant_id LEFT JOIN project p ON p.id=tr.project_id LEFT JOIN settlement_activity sa ON sa.id=erd.settlement_activity_id LEFT JOIN location_record lr ON lr.id=tr.location_record_id " +
            "WHERE bt.uid=? AND bt.kind IN (0,1) AND bt.lifecycle_state=0",
        arrayOf(id.bytes),
    ) { cursor ->
        val revisionId = cursor.stableId("revision_uid")
        OrdinaryTransactionEditView(
            id, revisionId, if (cursor.getInt(0) == 0) OrdinaryDirection.EXPENSE else OrdinaryDirection.INCOME, cursor.stableId("category_uid"), cursor.nullableString("amount_expression"),
            cursor.getLong(cursor.getColumnIndexOrThrow("user_minor")), CurrencyCode.parse(cursor.getString(cursor.getColumnIndexOrThrow("user_currency"))).valueOrAbort(), cursor.getLong(cursor.getColumnIndexOrThrow("account_minor")),
            cursor.nullableStableId("account_uid"), cursor.nullableStableId("card_uid"), cursor.nullableStableId("merchant_uid"), cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at")).toStoredInstant(), java.time.ZoneId.of(cursor.getString(cursor.getColumnIndexOrThrow("zone_id"))),
            cursor.nullableStableId("project_uid"), cursor.nullableStableId("activity_uid"), settlementShares(db, revisionId), cursor.nullableStableId("location_uid"), cursor.nullableString("note"),
            db.queryList("SELECT a.uid FROM transaction_revision_attachment tra JOIN attachment a ON a.id=tra.attachment_id JOIN transaction_revision tr ON tr.id=tra.revision_id WHERE tr.uid=? ORDER BY tra.sort_order", arrayOf(revisionId.bytes)) { it.stableId("uid") },
            app.ledger.finance.domain.TransactionSource.entries[cursor.getInt(cursor.getColumnIndexOrThrow("source_type"))], cursor.nullableStableId("source_reference_uid"),
        )
    } ?: abort(FinanceDataError.CorruptData)

    private fun settlementShares(db: SupportSQLiteDatabase, revisionId: StableId): List<OrdinarySettlementShareDraft> = db.queryList(
        "SELECT p.uid,s.paid_minor,s.owed_minor,s.weight_decimal,s.rounding_adjustment_minor FROM transaction_revision_settlement_share s JOIN transaction_revision tr ON tr.id=s.revision_id JOIN participant p ON p.id=s.participant_id WHERE tr.uid=? ORDER BY p.uid",
        arrayOf(revisionId.bytes),
    ) { OrdinarySettlementShareDraft(it.stableId("uid"), it.getLong(1), it.getLong(2), it.nullableString("weight_decimal")?.toBigDecimal(), it.getLong(4)) }

    private suspend fun <T> withDatabase(bookId: StableId, block: suspend (LedgerDatabase) -> T): DomainResult<T> = try {
        keyProvider.open(bookId).use { keys ->
            val database = keys.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(applicationContext, it) }
            try {
                DomainResult.Success(block(database))
            } finally {
                database.close()
            }
        }
    } catch (abort: FinancialPersistenceAbort) {
        DomainResult.Failure(abort.domainError)
    } catch (_: Exception) {
        DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
    }

    @Suppress("UNCHECKED_CAST")
    private fun app.ledger.finance.domain.FinancialCommand.withCanonicalHash(): app.ledger.finance.domain.FinancialCommand = when (this) {
        is RecordExpenseCommand -> copy(payloadHash = CanonicalFinancialHash.command(this))
        is RecordIncomeCommand -> copy(payloadHash = CanonicalFinancialHash.command(this))
        is EditTransactionCommand -> copy(payloadHash = CanonicalFinancialHash.command(this))
        else -> error("ordinary command type")
    }

    private fun OrdinarySettlementShareDraft.toDomain(): SettlementShare = SettlementShare(ParticipantId(participantId), paidMinor, owedMinor, weight, roundingAdjustmentMinor)

    private data class AutomaticProfile(val accountInternalId: Long, val profile: CreditAccountProfile)
    private data class AutomaticStatement(val statementId: StableId, val newStatement: AutomaticStatementDraft?)
    private data class AutomaticStatementDraft(
        val statementId: StableId,
        val revisionId: StableId,
        val accountInternalId: Long,
        val cycle: app.ledger.finance.domain.CreditCycle,
    )

    private companion object {
        const val AUTOMATIC_STATEMENT_ID_COUNT = 2
    }
}

private class OrdinaryLedgerWriteGate : LedgerWriteGate {
    private val mutex = Mutex()
    override suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock { block() }
}
