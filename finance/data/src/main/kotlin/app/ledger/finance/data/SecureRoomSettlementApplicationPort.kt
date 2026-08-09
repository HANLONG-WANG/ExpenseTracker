@file:Suppress("LongMethod", "LongParameterList", "TooManyFunctions", "LargeClass", "MagicNumber", "MaxLineLength")

package app.ledger.finance.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.DatabaseIntegrityAudit
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.FxEvidence
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.Money
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.time.EffectiveTime
import app.ledger.finance.application.AtomicFinancialCommitRepository
import app.ledger.finance.application.DefaultFinancialMutationCoordinator
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.FinancialPlanningPort
import app.ledger.finance.application.FinancialPlanningSnapshotRepository
import app.ledger.finance.application.LedgerWriteGate
import app.ledger.finance.application.RecordSettlementExpenseRequest
import app.ledger.finance.application.RecordSettlementPaymentRequest
import app.ledger.finance.application.SaveSettlementActivityRequest
import app.ledger.finance.application.SettlementAccountOption
import app.ledger.finance.application.SettlementActivityView
import app.ledger.finance.application.SettlementApplicationPort
import app.ledger.finance.application.SettlementParticipantView
import app.ledger.finance.application.SettlementPaymentView
import app.ledger.finance.application.SettlementPositionView
import app.ledger.finance.application.SettlementProjectOption
import app.ledger.finance.application.SettlementSnapshot
import app.ledger.finance.application.SettlementTransactionView
import app.ledger.finance.domain.AccountAmount
import app.ledger.finance.domain.AccountingPlanningContext
import app.ledger.finance.domain.AmountEvidenceKey
import app.ledger.finance.domain.AmountRole
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.CategoryAssignment
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.CommitKind
import app.ledger.finance.domain.DebitCredit
import app.ledger.finance.domain.DeterministicFinancialPlanner
import app.ledger.finance.domain.DeviceInstanceId
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.EntityChangeOperation
import app.ledger.finance.domain.EntityRevisionAction
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.ExpensePayer
import app.ledger.finance.domain.ExpensePayload
import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.FrozenAmountEvidence
import app.ledger.finance.domain.FrozenFxConversion
import app.ledger.finance.domain.FxRateSnapshotId
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.LedgerAccountClass
import app.ledger.finance.domain.NewTransactionInput
import app.ledger.finance.domain.Participant
import app.ledger.finance.domain.ParticipantId
import app.ledger.finance.domain.PaymentCardId
import app.ledger.finance.domain.PlanningIdentitySet
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.PositiveMoney
import app.ledger.finance.domain.ProjectId
import app.ledger.finance.domain.RecordExpenseCommand
import app.ledger.finance.domain.RecordSettlementPaymentCommand
import app.ledger.finance.domain.SettlementActivityId
import app.ledger.finance.domain.SettlementActivityStatus
import app.ledger.finance.domain.SettlementPaymentPayload
import app.ledger.finance.domain.SettlementPaymentRecord
import app.ledger.finance.domain.SettlementPaymentRecordId
import app.ledger.finance.domain.SettlementShare
import app.ledger.finance.domain.SettlementSuggestionPolicy
import app.ledger.finance.domain.TransactionContextInput
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionRevisionId
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.time.Instant
import java.time.YearMonth

class SecureRoomSettlementApplicationPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val databaseName: String = EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME,
) : SettlementApplicationPort {
    private val applicationContext = context.applicationContext
    private val mapper = RoomReferenceFinancialSnapshotMapper()
    private val currencies = JvmLegalTenderCurrencyCatalog.create()
    private val writeGate: LedgerWriteGate = SettlementWriteGate()
    private val projections = RoomProjectionEngine()

    override suspend fun snapshot(bookId: StableId): DomainResult<SettlementSnapshot> = withDatabase(bookId) { database ->
        database.readLedger { db ->
            val book = RoomBookRepository.mapCurrent(db)
            if (book.id.value != bookId) abort(FinanceDataError.CorruptData)
            val stale = count(db, "SELECT COUNT(*) FROM settlement_position_projection WHERE as_of_local_revision<>?", book.localRevision.value)
            if (stale != 0L) abort(FinanceDataError.ProjectionMismatch)
            val participants = participants(db)
            DomainResult.Success(
                SettlementSnapshot(
                    bookId,
                    book.baseCurrency,
                    book.localRevision,
                    participants,
                    activities(db, participants, book.localRevision),
                    accounts(db),
                    projects(db),
                ),
            )
        }
    }

    override suspend fun saveActivity(request: SaveSettlementActivityRequest): DomainResult<Unit> = withDatabase(request.ids.bookId) { database ->
        writeGate.execute {
            database.inLedgerTransaction { db ->
                val book = requireBook(db, request.ids.bookId)
                if (book.localRevision != request.ids.expectedLocalRevision.value) abort(DomainViolation.StaleExpectedRevision)
                val existing = db.queryOne(
                    "SELECT sa.id,bc.uid last_commit_uid,sa.status,sa.requires_additional_settlement FROM settlement_activity sa JOIN book_commit bc ON bc.id=sa.last_commit_id WHERE sa.uid=?",
                    arrayOf(request.activityId.bytes),
                ) { ActivityCurrent(it.getLong(0), it.stableId("last_commit_uid"), it.getInt(2), it.getInt(3) == 1) }
                if (existing?.lastCommitId != request.expectedLastCommitId) abort(DomainViolation.StaleExpectedRevision)
                if (existing == null && request.expectedLastCommitId != null) abort(DomainViolation.StaleExpectedRevision)
                val currentPositions = if (existing == null) {
                    emptyList()
                } else {
                    db.queryList(
                        "SELECT net_position_minor FROM settlement_position_projection WHERE activity_id=?",
                        arrayOf(existing.id),
                    ) { it.getLong(0) }
                }
                if (request.status == SettlementActivityStatus.SETTLED && currentPositions.any { it != 0L }) {
                    abort(DomainViolation.InvalidStateTransition("settlementActivity.settled"))
                }
                val nextRevision = Math.addExact(book.localRevision, 1L)
                val canonicalActivity = canonicalActivity(request)
                startCommit(db, request, book, nextRevision, canonicalActivity)
                val commitId = db.requireInternalId("book_commit", request.ids.commitId)
                request.participants.forEachIndexed { index, participant ->
                    saveParticipant(db, request, participant, commitId, index)
                }
                val activityInternalId = if (existing == null) {
                    db.allocateInternalId("settlement_activity", request.activityId).also { id ->
                        db.execSQL(
                            "INSERT INTO settlement_activity(id,uid,name,description,settlement_currency,project_id,start_date,end_date,status,requires_additional_settlement,last_commit_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                            arrayOf<Any?>(
                                id, request.activityId.bytes, request.name.trim(), request.description.clean(), request.currency.value,
                                request.projectId?.let { db.requireInternalId("project", it) }, request.startDate.toStorageInt(),
                                request.endDate?.toStorageInt(), request.status.ordinal, 0, commitId,
                            ),
                        )
                    }
                } else {
                    val changed = db.compileStatement(
                        "UPDATE settlement_activity SET name=?,description=?,settlement_currency=?,project_id=?,start_date=?,end_date=?,status=?," +
                            "requires_additional_settlement=?,last_commit_id=? WHERE id=? AND last_commit_id=?",
                    ).apply {
                        bindString(1, request.name.trim())
                        request.description.clean()?.let { bindString(2, it) } ?: bindNull(2)
                        bindString(3, request.currency.value)
                        request.projectId?.let { bindLong(4, db.requireInternalId("project", it)) } ?: bindNull(4)
                        bindLong(5, request.startDate.toStorageInt().toLong())
                        request.endDate?.let { bindLong(6, it.toStorageInt().toLong()) } ?: bindNull(6)
                        bindLong(7, request.status.ordinal.toLong())
                        bindLong(
                            8,
                            if (request.status == SettlementActivityStatus.SETTLED) {
                                0L
                            } else if (existing.requiresAdditional) {
                                1L
                            } else {
                                0L
                            },
                        )
                        bindLong(9, commitId)
                        bindLong(10, existing.id)
                        bindLong(11, db.requireInternalId("book_commit", existing.lastCommitId))
                    }.executeUpdateDelete()
                    if (changed != 1) abort(DomainViolation.StaleExpectedRevision)
                    existing.id
                }
                saveMembershipAndLedgers(db, request, activityInternalId, commitId)
                audit(
                    db,
                    request,
                    EntityType.SETTLEMENT_ACTIVITY,
                    request.activityId,
                    request.participants.size,
                    if (existing == null) EntityRevisionAction.CREATE else EntityRevisionAction.EDIT,
                    if (existing == null) EntityChangeOperation.CREATE else EntityChangeOperation.UPDATE,
                    canonicalActivity,
                )
                db.execSQL("UPDATE book SET head_commit_id=?,local_revision=? WHERE id=1", arrayOf<Any>(commitId, nextRevision))
                projections.rebuildAll(db, nextRevision, book.valuationRevision)
                if (projections.mismatchedFamilies(db, nextRevision, book.valuationRevision).isNotEmpty()) abort(FinanceDataError.ProjectionMismatch)
                if (!DatabaseIntegrityAudit.run(db).isValid) abort(FinanceDataError.CorruptData)
                DomainResult.Success(Unit)
            }
        }
    }

    override suspend fun recordPayment(request: RecordSettlementPaymentRequest): DomainResult<CommandReceipt> = withDatabase(request.ids.bookId) { database ->
        val book = database.readLedger(RoomBookRepository::mapCurrent)
        val references = database.readLedger(mapper::references)
        val activity = database.readLedger { db -> paymentActivity(db, request.activityId) }
        val activeParticipants = database.readLedger { db -> domainParticipants(db, request.activityId) }
        val payer = activeParticipants.singleOrNull { it.id.value == request.payerParticipantId }
            ?: return@withDatabase DomainResult.Failure(DomainViolation.InvalidField("settlementPayment.payer"))
        val payee = activeParticipants.singleOrNull { it.id.value == request.payeeParticipantId }
            ?: return@withDatabase DomainResult.Failure(DomainViolation.InvalidField("settlementPayment.payee"))
        val selfParticipates = payer.isSelf || payee.isSelf
        if (selfParticipates != (request.accountId != null)) {
            return@withDatabase DomainResult.Failure(DomainViolation.InvalidField("settlementPayment.account"))
        }
        val settlementAmount = positive(request.amountMinor, activity.currency)
        val evidence = request.accountId?.let { accountId ->
            paymentEvidence(request, activity.currency, book.baseCurrency, UserAccountId(accountId), references)
        }
        val payload = SettlementPaymentPayload(
            SettlementActivityId(request.activityId),
            ParticipantId(request.payerParticipantId),
            ParticipantId(request.payeeParticipantId),
            settlementAmount,
            evidence?.accountAmount?.let { AccountAmount.create(references.account(UserAccountId(request.accountId!!))!!.account, it.money).valueOrAbort() },
            selfParticipates,
        )
        val context = TransactionContextInput(
            EffectiveTime.fromInstant(request.occurredAt, request.zoneId), request.localDate, null, null, null, null, null,
            request.note, request.amountMinor.toString(), TransactionSource.MANUAL, request.paymentRecordId(), null, emptyList(),
        )
        val record = SettlementPaymentRecord(
            SettlementPaymentRecordId(request.ids.paymentRecordId), SettlementActivityId(request.activityId),
            ParticipantId(request.payerParticipantId), ParticipantId(request.payeeParticipantId), settlementAmount,
            context.occurredAt, if (selfParticipates) TransactionId(request.ids.transactionId) else null, selfParticipates,
            BookCommitId(request.ids.commitId), null,
        )
        val unsigned = RecordSettlementPaymentCommand(
            request.ids.commandId,
            zeroHash(),
            NewTransactionInput(context, payload),
            record,
        )
        val command = unsigned.copy(payloadHash = CanonicalFinancialHash.command(unsigned))
        val accounting = AccountingPlanningContext(
            PlanningIdentitySet(
                TransactionId(request.ids.transactionId),
                TransactionRevisionId(request.ids.revisionId),
                BookCommitId(request.ids.commitId),
                request.ids.factIds,
            ),
            request.createdAt,
            DeviceInstanceId(request.ids.deviceInstanceId),
            references,
            evidence?.let(::listOf).orEmpty(),
            null,
        )
        val snapshot = PlanningSnapshot(
            book, null, null, emptyList(), emptySet(), emptyList(), null, activeParticipants,
            accountingContext = accounting,
        )
        val nextNet = database.readLedger { db -> nextPositions(db, request) }
        val sideEffect = FinancialCommitSideEffect { db, plan ->
            val settled = nextNet.values.all { it == 0L }
            val status = if (settled) SettlementActivityStatus.SETTLED else SettlementActivityStatus.ACTIVE
            db.execSQL(
                "UPDATE settlement_activity SET status=?,requires_additional_settlement=0,last_commit_id=? WHERE uid=?",
                arrayOf<Any>(status.ordinal, db.commitId(plan.commit.id), request.activityId.bytes),
            )
        }
        coordinate(database, command, snapshot, sideEffect)
    }

    override suspend fun recordExpense(request: RecordSettlementExpenseRequest): DomainResult<CommandReceipt> = withDatabase(request.ids.bookId) { database ->
        val book = database.readLedger(RoomBookRepository::mapCurrent)
        val references = database.readLedger(mapper::references)
        val activity = database.readLedger { db -> paymentActivity(db, request.activityId) }
        val activeParticipants = database.readLedger { db -> domainParticipants(db, request.activityId) }
        val self = activeParticipants.singleOrNull { it.isSelf }
            ?: return@withDatabase DomainResult.Failure(DomainViolation.InvalidField("participant.self"))
        val payer = activeParticipants.singleOrNull { it.id.value == request.payerParticipantId }
            ?: return@withDatabase DomainResult.Failure(DomainViolation.InvalidField("settlementExpense.payer"))
        val category = references.category(CategoryId(request.categoryId))
            ?: return@withDatabase DomainResult.Failure(DomainViolation.InvalidField("settlementExpense.category"))
        if (category.direction != CategoryDirection.EXPENSE) {
            return@withDatabase DomainResult.Failure(DomainViolation.InvalidField("settlementExpense.category"))
        }
        if ((payer.id == self.id) != (request.localAccountId != null)) {
            return@withDatabase DomainResult.Failure(DomainViolation.InvalidField("settlementExpense.payerAccount"))
        }
        val evidence = settlementExpenseEvidence(request, activity.currency, book.baseCurrency, references)
        val payerModel = if (request.localAccountId != null) {
            val accountId = UserAccountId(requireNotNull(request.localAccountId))
            val account = references.account(accountId)?.account
                ?: return@withDatabase DomainResult.Failure(DomainViolation.InvalidField("settlementExpense.account"))
            ExpensePayer.LocalAccount(
                AccountAmount.create(account, evidence.accountAmount.money).valueOrAbort(),
                request.cardId?.let(::PaymentCardId),
            )
        } else {
            ExpensePayer.ExternalParticipant(payer.id, SettlementActivityId(request.activityId))
        }
        val payload = ExpensePayload(
            CategoryAssignment(category.id, category.direction, category.statisticalNature),
            payerModel,
            evidence.userInput,
            SettlementActivityId(request.activityId),
            request.shares.map {
                SettlementShare(ParticipantId(it.participantId), it.paidMinor, it.owedMinor, it.weight, it.roundingAdjustmentMinor)
            },
            null,
        )
        val context = TransactionContextInput(
            EffectiveTime.fromInstant(request.occurredAt, request.zoneId), request.localDate, YearMonth.from(request.localDate),
            null, request.projectId?.let(::ProjectId), null, null, request.note, request.totalMinor.toString(),
            TransactionSource.MANUAL, null, null, emptyList(),
        )
        val unsigned = RecordExpenseCommand(
            request.ids.commandId,
            zeroHash(),
            NewTransactionInput(context, payload),
        )
        val command = unsigned.copy(payloadHash = CanonicalFinancialHash.command(unsigned))
        val accounting = AccountingPlanningContext(
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
        )
        coordinate(
            database,
            command,
            PlanningSnapshot(book, null, null, emptyList(), emptySet(), emptyList(), null, activeParticipants, accountingContext = accounting),
            FinancialCommitSideEffect.NONE,
        )
    }

    override suspend fun rebuildAndAudit(bookId: StableId): DomainResult<Unit> = withDatabase(bookId) { database ->
        writeGate.execute {
            database.inLedgerTransaction { db ->
                val book = RoomBookRepository.mapCurrent(db)
                projections.rebuildAll(db, book.localRevision.value, book.valuationRevision.value)
                if (projections.mismatchedFamilies(db, book.localRevision.value, book.valuationRevision.value).isNotEmpty()) {
                    abort(FinanceDataError.ProjectionMismatch)
                }
                if (!DatabaseIntegrityAudit.run(db).isValid) abort(FinanceDataError.CorruptData)
                DomainResult.Success(Unit)
            }
        }
    }

    private fun participants(db: SupportSQLiteDatabase): List<SettlementParticipantView> = db.queryList(
        "SELECT uid,name,is_self,status FROM participant ORDER BY is_self DESC,status,name,uid",
    ) { SettlementParticipantView(it.stableId("uid"), it.string("name"), it.int("is_self") == 1, it.int("status") == EntityStatus.ACTIVE.ordinal) }

    private fun activities(
        db: SupportSQLiteDatabase,
        participants: List<SettlementParticipantView>,
        revision: app.ledger.finance.domain.LocalRevision,
    ): List<SettlementActivityView> = db.queryList(
        "SELECT sa.uid,sa.name,sa.description,sa.settlement_currency,p.uid project_uid,sa.start_date,sa.end_date,sa.status,sa.requires_additional_settlement,bc.uid commit_uid FROM settlement_activity sa LEFT JOIN project p ON p.id=sa.project_id JOIN book_commit bc ON bc.id=sa.last_commit_id ORDER BY sa.status,sa.start_date DESC,sa.id",
    ) { row ->
        val id = row.stableId("uid")
        val memberIds = db.queryList(
            "SELECT p.uid FROM settlement_activity_participant sap JOIN settlement_activity sa ON sa.id=sap.activity_id JOIN participant p ON p.id=sap.participant_id WHERE sa.uid=? AND sap.left_at IS NULL ORDER BY sap.sort_order,p.uid",
            arrayOf(id.bytes),
        ) { it.stableId("uid") }
        val positions = positions(db, id, revision.value)
        val suggestions = SettlementSuggestionPolicy.suggest(
            positions.associate { ParticipantId(it.participantId) to it.netPositionMinor },
        ).valueOrAbort()
        SettlementActivityView(
            id, row.string("name"), row.nullableString("description"), currency(row.string("settlement_currency")),
            row.nullableStableId("project_uid"), row.int("start_date").toStoredLocalDate(), row.nullableLong("end_date")?.toInt()?.toStoredLocalDate(),
            SettlementActivityStatus.entries[row.int("status")], row.int("requires_additional_settlement") == 1, row.stableId("commit_uid"),
            memberIds.map { member -> participants.singleOrNull { it.id == member } ?: abort(FinanceDataError.CorruptData) },
            positions, payments(db, id), transactions(db, id), suggestions,
        )
    }

    private fun positions(db: SupportSQLiteDatabase, activityId: StableId, revision: Long): List<SettlementPositionView> = db.queryList(
        "SELECT p.uid,spp.paid_minor,spp.owed_minor,spp.settled_paid_minor,spp.settled_received_minor,spp.net_position_minor,spp.as_of_local_revision FROM settlement_position_projection spp JOIN settlement_activity sa ON sa.id=spp.activity_id JOIN participant p ON p.id=spp.participant_id WHERE sa.uid=? ORDER BY p.is_self DESC,p.name,p.uid",
        arrayOf(activityId.bytes),
    ) {
        if (it.long("as_of_local_revision") != revision) abort(FinanceDataError.ProjectionMismatch)
        SettlementPositionView(it.stableId("uid"), it.long("paid_minor"), it.long("owed_minor"), it.long("settled_paid_minor"), it.long("settled_received_minor"), it.long("net_position_minor"))
    }

    private fun payments(db: SupportSQLiteDatabase, activityId: StableId): List<SettlementPaymentView> = db.queryList(
        "SELECT spr.uid,payer.uid payer_uid,payee.uid payee_uid,spr.amount_minor,spr.occurred_at,bt.uid transaction_uid,reversal.uid reversal_uid FROM settlement_payment_record spr JOIN settlement_activity sa ON sa.id=spr.activity_id JOIN participant payer ON payer.id=spr.payer_participant_id JOIN participant payee ON payee.id=spr.payee_participant_id LEFT JOIN business_transaction bt ON bt.id=spr.linked_transaction_id LEFT JOIN settlement_payment_record reversal ON reversal.id=spr.reversal_of_id WHERE sa.uid=? ORDER BY spr.occurred_at DESC,spr.id DESC",
        arrayOf(activityId.bytes),
    ) { SettlementPaymentView(it.stableId("uid"), it.stableId("payer_uid"), it.stableId("payee_uid"), it.long("amount_minor"), it.long("occurred_at").toStoredInstant(), it.nullableStableId("transaction_uid"), it.nullableStableId("reversal_uid")) }

    private fun transactions(db: SupportSQLiteDatabase, activityId: StableId): List<SettlementTransactionView> = db.queryList(
        "SELECT bt.uid transaction_uid,tr.uid revision_uid,tr.occurred_at,payer.uid payer_uid,SUM(s.owed_minor) total_minor,SUM(CASE WHEN self.is_self=1 THEN s.owed_minor ELSE 0 END) self_minor FROM business_transaction bt JOIN transaction_revision tr ON tr.id=bt.current_revision_id JOIN expense_revision_detail erd ON erd.revision_id=tr.id JOIN settlement_activity sa ON sa.id=erd.settlement_activity_id JOIN transaction_revision_settlement_share s ON s.revision_id=tr.id JOIN participant self ON self.id=s.participant_id JOIN transaction_revision_settlement_share paid ON paid.revision_id=tr.id AND paid.paid_minor>0 JOIN participant payer ON payer.id=paid.participant_id WHERE sa.uid=? AND bt.lifecycle_state=0 GROUP BY bt.id,tr.id,payer.id ORDER BY tr.occurred_at DESC,bt.id DESC",
        arrayOf(activityId.bytes),
    ) { SettlementTransactionView(it.stableId("transaction_uid"), it.stableId("revision_uid"), it.long("occurred_at").toStoredInstant(), it.long("total_minor"), it.long("self_minor"), it.stableId("payer_uid"), emptyMap()) }
        .map { transaction ->
            transaction.copy(
                owedMinorByParticipant = db.queryList(
                    "SELECT p.uid,s.owed_minor FROM transaction_revision_settlement_share s JOIN transaction_revision tr ON tr.id=s.revision_id JOIN participant p ON p.id=s.participant_id WHERE tr.uid=? ORDER BY p.uid",
                    arrayOf(transaction.revisionId.bytes),
                ) { it.stableId("uid") to it.long("owed_minor") }.toMap(),
            )
        }

    private fun accounts(db: SupportSQLiteDatabase): List<SettlementAccountOption> = db.queryList(
        "SELECT uid,name,currency_code,status FROM user_account WHERE type IN (0,1) ORDER BY status,sort_order,id",
    ) { SettlementAccountOption(it.stableId("uid"), it.string("name"), currency(it.string("currency_code")), it.int("status") == EntityStatus.ACTIVE.ordinal) }

    private fun projects(db: SupportSQLiteDatabase): List<SettlementProjectOption> = db.queryList(
        "SELECT uid,name,status FROM project ORDER BY status,name,id",
    ) { SettlementProjectOption(it.stableId("uid"), it.string("name"), it.int("status") == EntityStatus.ACTIVE.ordinal) }

    private fun saveParticipant(
        db: SupportSQLiteDatabase,
        request: SaveSettlementActivityRequest,
        draft: app.ledger.finance.application.SettlementParticipantDraft,
        commitId: Long,
        revisionIndex: Int,
    ) {
        val existing = db.queryOne("SELECT id,name,is_self,status FROM participant WHERE uid=?", arrayOf(draft.id.bytes)) {
            ParticipantCurrent(it.getLong(0), it.getString(1), it.getInt(2) == 1, it.getInt(3))
        }
        val otherSelf = db.queryOne("SELECT uid FROM participant WHERE is_self=1 AND status=? AND uid<>?", arrayOf(EntityStatus.ACTIVE.ordinal, draft.id.bytes)) { it.getBlob(0) }
        if (draft.isSelf && draft.active && otherSelf != null) abort(DomainViolation.InvalidField("participant.self"))
        if (existing?.isSelf == true && !draft.isSelf) abort(DomainViolation.InvalidField("participant.selfImmutable"))
        val snapshot = canonical("participant", draft.id.toString(), draft.name.trim(), draft.isSelf.toString(), draft.active.toString())
        if (existing == null) {
            db.execSQL(
                "INSERT INTO participant(id,uid,name,is_self,status,last_commit_id) VALUES(?,?,?,?,?,?)",
                arrayOf<Any>(db.allocateInternalId("participant", draft.id), draft.id.bytes, draft.name.trim(), draft.isSelf.toSqlInt(), if (draft.active) EntityStatus.ACTIVE.ordinal else EntityStatus.ARCHIVED.ordinal, commitId),
            )
        } else {
            db.execSQL(
                "UPDATE participant SET name=?,status=?,last_commit_id=? WHERE id=?",
                arrayOf<Any>(draft.name.trim(), if (draft.active) EntityStatus.ACTIVE.ordinal else EntityStatus.ARCHIVED.ordinal, commitId, existing.id),
            )
        }
        audit(db, request, EntityType.PARTICIPANT, draft.id, revisionIndex, if (existing == null) EntityRevisionAction.CREATE else EntityRevisionAction.EDIT, if (existing == null) EntityChangeOperation.CREATE else EntityChangeOperation.UPDATE, snapshot)
    }

    private fun saveMembershipAndLedgers(db: SupportSQLiteDatabase, request: SaveSettlementActivityRequest, activityId: Long, commitId: Long) {
        val selected = request.participants.map { db.requireInternalId("participant", it.id) }.toSet()
        db.queryList("SELECT participant_id FROM settlement_activity_participant WHERE activity_id=? AND left_at IS NULL", arrayOf(activityId)) { it.getLong(0) }
            .filterNot(selected::contains)
            .forEach { db.execSQL("UPDATE settlement_activity_participant SET left_at=? WHERE activity_id=? AND participant_id=?", arrayOf<Any>(request.changedAt.toEpochMilli(), activityId, it)) }
        request.participants.forEachIndexed { index, participant ->
            val participantId = db.requireInternalId("participant", participant.id)
            val exists = db.queryOne("SELECT joined_at FROM settlement_activity_participant WHERE activity_id=? AND participant_id=?", arrayOf(activityId, participantId)) { it.getLong(0) }
            if (exists == null) {
                db.execSQL("INSERT INTO settlement_activity_participant(activity_id,participant_id,sort_order,joined_at,left_at) VALUES(?,?,?,?,NULL)", arrayOf<Any>(activityId, participantId, index, request.changedAt.toEpochMilli()))
            } else {
                db.execSQL("UPDATE settlement_activity_participant SET sort_order=?,left_at=NULL WHERE activity_id=? AND participant_id=?", arrayOf<Any>(index, activityId, participantId))
            }
            if (!participant.isSelf) ensureSettlementLedger(db, request, participant.id, commitId)
        }
    }

    private fun ensureSettlementLedger(db: SupportSQLiteDatabase, request: SaveSettlementActivityRequest, participantId: StableId, commitId: Long) {
        val systemCode = "SETTLEMENT:${request.activityId}:$participantId"
        val existing = db.queryOne("SELECT id,currency_code FROM ledger_account WHERE system_code=?", arrayOf(systemCode)) { it.getLong(0) to it.getString(1) }
        if (existing != null) {
            if (existing.second != request.currency.value) abort(DomainViolation.InvalidField("settlementActivity.currencyLocked"))
            return
        }
        val ledgerId = request.ids.settlementLedgerIds[participantId] ?: abort(DomainViolation.InvalidField("settlementActivity.ledgerId"))
        db.execSQL(
            "INSERT INTO ledger_account(id,uid,owner_type,account_class,normal_side,currency_code,parent_ledger_account_id,system_code,status,created_commit_id) VALUES(?,?,?,?,?,?,NULL,?,?,?)",
            arrayOf<Any>(db.allocateInternalId("ledger_account", ledgerId), ledgerId.bytes, 3, LedgerAccountClass.SETTLEMENT.ordinal, DebitCredit.DEBIT.ordinal, request.currency.value, systemCode, EntityStatus.ACTIVE.ordinal, commitId),
        )
    }

    private fun paymentEvidence(
        request: RecordSettlementPaymentRequest,
        settlementCurrency: CurrencyCode,
        baseCurrency: CurrencyCode,
        accountId: UserAccountId,
        references: app.ledger.finance.domain.PlanningReferenceData,
    ): FrozenAmountEvidence {
        val account = references.account(accountId)?.account ?: abort(DomainViolation.InvalidField("settlementPayment.account"))
        val settlement = positive(request.amountMinor, settlementCurrency)
        val accountAmount = positive(request.accountMinor ?: abort(DomainViolation.InvalidField("settlementPayment.accountMinor")), account.currency)
        val base = positive(request.baseMinor ?: abort(DomainViolation.InvalidField("settlementPayment.baseMinor")), baseCurrency)
        val fxIds = request.ids.fxRateSnapshotIds.iterator()
        fun conversion(source: PositiveMoney, target: PositiveMoney, evidence: FxEvidence?): FrozenFxConversion? {
            if (source.currency == target.currency) {
                if (source != target || evidence != null) abort(DomainViolation.InvalidField("settlementPayment.sameCurrency"))
                return null
            }
            val fx = evidence ?: abort(DomainViolation.InvalidField("settlementPayment.fxEvidence"))
            if (fx.sourceCurrency != source.currency || fx.targetCurrency != target.currency) abort(DomainViolation.InvalidField("settlementPayment.fxEvidence"))
            val id = fxIds.nextOrNull() ?: abort(DomainViolation.InvalidField("settlementPayment.fxIds"))
            return FrozenFxConversion.create(
                FxRateSnapshotId(id),
                source,
                target,
                fx,
                currencies.require(source.currency).valueOrAbort(),
                currencies.require(target.currency).valueOrAbort(),
                false,
            ).valueOrAbort()
        }
        val result = FrozenAmountEvidence.create(
            AmountEvidenceKey(AmountRole.SETTLEMENT, 0),
            settlement,
            accountAmount,
            base,
            accountId,
            conversion(settlement, accountAmount, request.settlementToAccountEvidence),
            conversion(accountAmount, base, request.accountToBaseEvidence),
        ).valueOrAbort()
        if (fxIds.hasNext()) abort(DomainViolation.InvalidField("settlementPayment.fxIds"))
        return result
    }

    private fun settlementExpenseEvidence(
        request: RecordSettlementExpenseRequest,
        settlementCurrency: CurrencyCode,
        baseCurrency: CurrencyCode,
        references: app.ledger.finance.domain.PlanningReferenceData,
    ): FrozenAmountEvidence {
        val user = positive(request.totalMinor, settlementCurrency)
        val accountId = request.localAccountId?.let(::UserAccountId)
        val accountCurrency = accountId?.let { references.account(it)?.account?.currency }
            ?: if (accountId == null) settlementCurrency else abort(DomainViolation.InvalidField("settlementExpense.account"))
        val account = positive(request.accountMinor ?: request.totalMinor, accountCurrency)
        val base = positive(request.baseMinor, baseCurrency)
        val fxIds = request.ids.fxRateSnapshotIds.iterator()
        fun conversion(source: PositiveMoney, target: PositiveMoney, evidence: FxEvidence?): FrozenFxConversion? {
            if (source.currency == target.currency) {
                if (source != target || evidence != null) abort(DomainViolation.InvalidField("settlementExpense.sameCurrency"))
                return null
            }
            val fx = evidence ?: abort(DomainViolation.InvalidField("settlementExpense.fxEvidence"))
            if (fx.sourceCurrency != source.currency || fx.targetCurrency != target.currency) abort(DomainViolation.InvalidField("settlementExpense.fxEvidence"))
            val id = fxIds.nextOrNull() ?: abort(DomainViolation.InvalidField("settlementExpense.fxIds"))
            return FrozenFxConversion.create(
                FxRateSnapshotId(id),
                source,
                target,
                fx,
                currencies.require(source.currency).valueOrAbort(),
                currencies.require(target.currency).valueOrAbort(),
                false,
            ).valueOrAbort()
        }
        val result = FrozenAmountEvidence.create(
            AmountEvidenceKey(AmountRole.PRIMARY, 0),
            user,
            account,
            base,
            accountId,
            conversion(user, account, request.settlementToAccountEvidence),
            conversion(account, base, request.accountToBaseEvidence),
        ).valueOrAbort()
        if (fxIds.hasNext()) abort(DomainViolation.InvalidField("settlementExpense.fxIds"))
        return result
    }

    private fun paymentActivity(db: SupportSQLiteDatabase, id: StableId): PaymentActivity = db.queryOne(
        "SELECT settlement_currency,status FROM settlement_activity WHERE uid=?",
        arrayOf(id.bytes),
    ) { PaymentActivity(currency(it.getString(0)), SettlementActivityStatus.entries[it.getInt(1)]) }
        ?.also { if (it.status == SettlementActivityStatus.ARCHIVED) abort(DomainViolation.InvalidStateTransition("settlementActivity.archived")) }
        ?: abort(DomainViolation.InvalidField("settlementActivity.id"))

    private fun domainParticipants(db: SupportSQLiteDatabase, activityId: StableId): List<Participant> = db.queryList(
        "SELECT p.uid,p.name,p.is_self,p.status,bc.uid commit_uid FROM settlement_activity_participant sap JOIN settlement_activity sa ON sa.id=sap.activity_id JOIN participant p ON p.id=sap.participant_id JOIN book_commit bc ON bc.id=p.last_commit_id WHERE sa.uid=? AND sap.left_at IS NULL AND p.status=? ORDER BY sap.sort_order,p.uid",
        arrayOf(activityId.bytes, EntityStatus.ACTIVE.ordinal),
    ) { Participant(ParticipantId(it.stableId("uid")), it.string("name"), it.int("is_self") == 1, EntityStatus.entries[it.int("status")], BookCommitId(it.stableId("commit_uid"))) }

    private fun nextPositions(db: SupportSQLiteDatabase, request: RecordSettlementPaymentRequest): Map<StableId, Long> {
        val current = db.queryList(
            "SELECT p.uid,COALESCE(spp.net_position_minor,0) net FROM settlement_activity_participant sap JOIN settlement_activity sa ON sa.id=sap.activity_id JOIN participant p ON p.id=sap.participant_id LEFT JOIN settlement_position_projection spp ON spp.activity_id=sap.activity_id AND spp.participant_id=sap.participant_id WHERE sa.uid=? AND sap.left_at IS NULL",
            arrayOf(request.activityId.bytes),
        ) { it.stableId("uid") to it.long("net") }.toMap().toMutableMap()
        current[request.payerParticipantId] = Math.addExact(current[request.payerParticipantId] ?: abort(FinanceDataError.CorruptData), request.amountMinor)
        current[request.payeeParticipantId] = Math.subtractExact(current[request.payeeParticipantId] ?: abort(FinanceDataError.CorruptData), request.amountMinor)
        return current
    }

    private suspend fun coordinate(
        database: LedgerDatabase,
        command: FinancialCommand,
        snapshot: PlanningSnapshot,
        sideEffect: FinancialCommitSideEffect,
    ): DomainResult<CommandReceipt> {
        val repository: AtomicFinancialCommitRepository = RoomFinancialCommitRepository(database, sideEffect = sideEffect)
        return DefaultFinancialMutationCoordinator(
            writeGate,
            repository as app.ledger.finance.application.CommandReceiptRepository,
            object : FinancialPlanningSnapshotRepository {
                override suspend fun load(command: FinancialCommand): DomainResult<PlanningSnapshot> = DomainResult.Success(snapshot)
            },
            FinancialPlanningPort(DeterministicFinancialPlanner::plan),
            repository,
        ).execute(command)
    }

    private fun startCommit(db: SupportSQLiteDatabase, request: SaveSettlementActivityRequest, book: BookRow, revision: Long, snapshot: ByteArray) {
        db.execSQL(
            "INSERT INTO book_commit(id,uid,local_revision,kind,command_uid,device_instance_uid,created_at,root_hash) VALUES(?,?,?,?,NULL,?,?,?)",
            arrayOf<Any>(db.allocateInternalId("book_commit", request.ids.commitId), request.ids.commitId.bytes, revision, CommitKind.REFERENCE_DATA_CHANGE.ordinal, request.ids.deviceInstanceId.bytes, request.changedAt.toEpochMilli(), sha256(snapshot)),
        )
        db.execSQL("INSERT INTO book_commit_parent(commit_id,parent_commit_id,ordinal) VALUES(?,?,0)", arrayOf<Any>(db.requireInternalId("book_commit", request.ids.commitId), book.headCommitId))
    }

    private fun audit(
        db: SupportSQLiteDatabase,
        request: SaveSettlementActivityRequest,
        type: EntityType,
        entityId: StableId,
        revisionIndex: Int,
        action: EntityRevisionAction,
        operation: EntityChangeOperation,
        snapshot: ByteArray,
    ) {
        val revisionId = request.ids.entityRevisionIds.getOrNull(revisionIndex) ?: abort(DomainViolation.InvalidField("settlementActivity.revisionIds"))
        val revisionNumber = Math.addExact(
            db.queryOne("SELECT COALESCE(MAX(revision_no),0) FROM entity_revision WHERE entity_type=? AND entity_uid=?", arrayOf(type.ordinal, entityId.bytes)) { it.getInt(0) } ?: 0,
            1,
        )
        val digest = sha256(snapshot)
        db.execSQL(
            "INSERT INTO entity_revision(id,uid,entity_type,entity_uid,revision_no,action,commit_id,content_hash,canonical_snapshot_blob,schema_version) VALUES(?,?,?,?,?,?,?,?,?,1)",
            arrayOf<Any>(db.allocateInternalId("entity_revision", revisionId), revisionId.bytes, type.ordinal, entityId.bytes, revisionNumber, action.ordinal, db.requireInternalId("book_commit", request.ids.commitId), digest, snapshot),
        )
        db.execSQL(
            "INSERT INTO entity_change(commit_id,entity_type,entity_uid,operation,before_hash,after_hash,entity_revision_uid) VALUES(?,?,?,?,NULL,?,?)",
            arrayOf<Any>(db.requireInternalId("book_commit", request.ids.commitId), type.ordinal, entityId.bytes, operation.ordinal, digest, revisionId.bytes),
        )
    }

    private fun canonicalActivity(request: SaveSettlementActivityRequest): ByteArray = canonical(
        "settlementActivity", request.activityId.toString(), request.name.trim(), request.description.clean().orEmpty(), request.currency.value,
        request.projectId?.toString().orEmpty(), request.startDate.toString(), request.endDate?.toString().orEmpty(), request.status.name,
        request.participants.joinToString("|") { "${it.id}:${it.name.trim()}:${it.isSelf}:${it.active}" },
    )

    private fun requireBook(db: SupportSQLiteDatabase, bookId: StableId): BookRow = db.queryOne(
        "SELECT uid,head_commit_id,local_revision,valuation_revision,state FROM book WHERE id=1",
    ) { BookRow(it.getBlob(0), it.getLong(1), it.getLong(2), it.getLong(3), it.getInt(4)) }
        ?.also { if (!it.uid.contentEquals(bookId.bytes) || it.state != 0) abort(FinanceDataError.MaintenanceRequired) }
        ?: abort(FinanceDataError.CorruptData)

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

    private fun positive(minor: Long, currency: CurrencyCode): PositiveMoney = PositiveMoney.from(Money(minor, currency)).valueOrAbort()
    private fun currency(code: String): CurrencyCode = CurrencyCode.parse(code).valueOrAbort()
    private fun zeroHash(): Hash256 = Hash256.fromBytes(ByteArray(32)).valueOrAbort()
    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun canonical(vararg values: String): ByteArray = values.joinToString("\u001f").toByteArray(Charsets.UTF_8)
    private fun count(db: SupportSQLiteDatabase, sql: String, vararg args: Any?): Long = db.queryOne(sql, args) { it.getLong(0) } ?: 0L

    private data class BookRow(val uid: ByteArray, val headCommitId: Long, val localRevision: Long, val valuationRevision: Long, val state: Int)
    private data class ActivityCurrent(val id: Long, val lastCommitId: StableId, val status: Int, val requiresAdditional: Boolean)
    private data class ParticipantCurrent(val id: Long, val name: String, val isSelf: Boolean, val status: Int)
    private data class PaymentActivity(val currency: CurrencyCode, val status: SettlementActivityStatus)
}

private class SettlementWriteGate : LedgerWriteGate {
    private val mutex = Mutex()
    override suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock { block() }
}

private fun RecordSettlementPaymentRequest.paymentRecordId(): StableId = ids.paymentRecordId
private fun String?.clean(): String? = this?.trim()?.takeIf(String::isNotEmpty)
private fun <T> Iterator<T>.nextOrNull(): T? = if (hasNext()) next() else null
private fun android.database.Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
private fun android.database.Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun android.database.Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
