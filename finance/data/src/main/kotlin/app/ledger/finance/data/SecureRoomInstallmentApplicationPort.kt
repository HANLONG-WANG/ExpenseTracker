@file:Suppress("LongMethod", "LongParameterList", "TooManyFunctions", "MagicNumber", "MaxLineLength")

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
import app.ledger.finance.application.ApplyInstallmentRefundRequest
import app.ledger.finance.application.ApplyInstallmentSettlementRequest
import app.ledger.finance.application.CreditPaymentAccountView
import app.ledger.finance.application.DefaultFinancialMutationCoordinator
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.FinancialPlanningPort
import app.ledger.finance.application.FinancialPlanningSnapshotRepository
import app.ledger.finance.application.InstallmentApplicationPort
import app.ledger.finance.application.InstallmentPlanView
import app.ledger.finance.application.InstallmentPurchaseView
import app.ledger.finance.application.InstallmentSnapshot
import app.ledger.finance.application.LedgerWriteGate
import app.ledger.finance.application.SaveInstallmentPlanRequest
import app.ledger.finance.application.SpecializedAccountAmountDraft
import app.ledger.finance.domain.AccountAmount
import app.ledger.finance.domain.AccountingPlanningContext
import app.ledger.finance.domain.AmountEvidenceKey
import app.ledger.finance.domain.AmountRole
import app.ledger.finance.domain.ApplyInstallmentSettlementCommand
import app.ledger.finance.domain.AutoGenerationMode
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.CreditPaymentAllocation
import app.ledger.finance.domain.CreditPaymentPayload
import app.ledger.finance.domain.DeterministicFinancialPlanner
import app.ledger.finance.domain.DeviceInstanceId
import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.FrozenAmountEvidence
import app.ledger.finance.domain.FrozenFxConversion
import app.ledger.finance.domain.FxRateSnapshotId
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.InstallmentAccountingPolicy
import app.ledger.finance.domain.InstallmentPlan
import app.ledger.finance.domain.InstallmentPlanId
import app.ledger.finance.domain.InstallmentPlanMutation
import app.ledger.finance.domain.InstallmentPlanRevision
import app.ledger.finance.domain.InstallmentPlanRevisionId
import app.ledger.finance.domain.InstallmentRefundAllocation
import app.ledger.finance.domain.InstallmentScheduleItem
import app.ledger.finance.domain.InstallmentScheduleItemId
import app.ledger.finance.domain.InstallmentScheduleRequest
import app.ledger.finance.domain.InstallmentScheduleRevision
import app.ledger.finance.domain.InstallmentScheduleRevisionId
import app.ledger.finance.domain.InstallmentSettlementSimulation
import app.ledger.finance.domain.InstallmentStatus
import app.ledger.finance.domain.InterestRate
import app.ledger.finance.domain.NewTransactionInput
import app.ledger.finance.domain.PlanningIdentitySet
import app.ledger.finance.domain.PlanningOperationContext
import app.ledger.finance.domain.PlanningReferenceData
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.PositiveMoney
import app.ledger.finance.domain.SaveInstallmentPlanCommand
import app.ledger.finance.domain.ScheduleRevisionReason
import app.ledger.finance.domain.TransactionContextInput
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionRevisionId
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

class SecureRoomInstallmentApplicationPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val databaseName: String = EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME,
) : InstallmentApplicationPort {
    private val applicationContext = context.applicationContext
    private val mapper = RoomReferenceFinancialSnapshotMapper()
    private val currencyCatalog = JvmLegalTenderCurrencyCatalog.create()
    private val writeGate: LedgerWriteGate = InstallmentWriteGate()

    override suspend fun snapshot(bookId: StableId, asOfDate: LocalDate): DomainResult<InstallmentSnapshot> = withDatabase(bookId) { database ->
        database.readLedger { db ->
            val book = RoomBookRepository.mapCurrent(db)
            if (book.id.value != bookId) abort(FinanceDataError.CorruptData)
            val plans = db.queryList("SELECT uid FROM installment_plan ORDER BY status,id") { it.stableId("uid") }
                .map { loadPlan(db, it, asOfDate).view }
            val purchases = readPurchases(db)
            val paymentAccounts = db.queryList(
                "SELECT uid,name,currency_code,status FROM user_account WHERE type IN (0,1) ORDER BY sort_order,id",
            ) { cursor ->
                CreditPaymentAccountView(
                    cursor.stableId("uid"),
                    cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    currency(cursor.getString(cursor.getColumnIndexOrThrow("currency_code"))),
                    cursor.getInt(cursor.getColumnIndexOrThrow("status")) == 0,
                )
            }
            if (!db.isProjectionFamilyCurrent(app.ledger.finance.application.ProjectionFamily.INSTALLMENT, book.localRevision)) {
                abort(FinanceDataError.ProjectionMismatch)
            }
            DomainResult.Success(InstallmentSnapshot(bookId, book.baseCurrency, book.localRevision, plans, purchases, paymentAccounts))
        }
    }

    override suspend fun preview(request: SaveInstallmentPlanRequest): DomainResult<InstallmentScheduleRevision> = try {
        InstallmentAccountingPolicy.generate(scheduleRequest(request, termsRevision(request), request.currentPrincipalMinor))
    } catch (_: IllegalArgumentException) {
        DomainResult.Failure(app.ledger.finance.domain.DomainViolation.InvalidField("installment.preview"))
    }

    override suspend fun save(request: SaveInstallmentPlanRequest): DomainResult<CommandReceipt> = withDatabase(request.ids.bookId) { database ->
        val book = database.readLedger(RoomBookRepository::mapCurrent)
        val revision = termsRevision(request)
        val schedule = InstallmentAccountingPolicy.generate(scheduleRequest(request, revision, request.currentPrincipalMinor)).valueOrAbort()
        val status = if (request.currentPrincipalMinor == 0L) InstallmentStatus.SETTLED else InstallmentStatus.ACTIVE
        val aggregate = InstallmentPlan(
            InstallmentPlanId(request.ids.planId),
            TransactionId(request.purchaseTransactionId),
            UserAccountId(request.creditAccountId),
            request.originalPrincipalMinor,
            request.currency,
            request.termCount,
            revision.id,
            status,
        )
        val mutation = InstallmentPlanMutation(
            aggregate,
            request.expectedRevisionId?.let(::InstallmentPlanRevisionId),
            revision,
            schedule,
            request.currentPrincipalMinor,
        )
        val draft = SaveInstallmentPlanCommand(request.ids.commandId, zeroHash(), mutation)
        val command = draft.copy(payloadHash = CanonicalFinancialHash.command(draft))
        coordinate(database, command, operationSnapshot(book, request, revision))
    }

    override suspend fun simulateSettlement(
        bookId: StableId,
        planId: StableId,
        settlementDate: LocalDate,
    ): DomainResult<InstallmentSettlementSimulation> = withDatabase(bookId) { database ->
        database.readLedger { db ->
            val loaded = loadPlan(db, planId, settlementDate)
            InstallmentAccountingPolicy.simulateSettlement(loaded.plan, loaded.revision, loaded.schedule, settlementDate)
        }
    }

    override suspend fun applySettlement(request: ApplyInstallmentSettlementRequest): DomainResult<CommandReceipt> = withDatabase(request.ids.mutation.bookId) { database ->
        database.readLedger { db -> InstallmentReplayReceiptVerifier.settlement(db, request) }
            ?.let { return@withDatabase DomainResult.Success(it) }
        val loaded = database.readLedger { db -> loadPlan(db, request.ids.mutation.planId, request.settlementDate) }
        if (loaded.revision.id.value != request.expectedRevisionId) {
            return@withDatabase DomainResult.Failure(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
        }
        val simulation = InstallmentAccountingPolicy.simulateSettlement(
            loaded.plan,
            loaded.revision,
            loaded.schedule,
            request.settlementDate,
        ).valueOrAbort()
        if (!settlementConfirmationMatches(request, loaded.plan.creditAccountId.value, simulation)) {
            return@withDatabase DomainResult.Failure(app.ledger.finance.domain.DomainViolation.InvalidField("installment.settlement.confirmation"))
        }
        val debt = database.readLedger { db ->
            db.queryOne(
                "SELECT COALESCE(abc.normal_balance_minor,0) FROM user_account ua " +
                    "LEFT JOIN account_balance_current abc ON abc.account_id=ua.id WHERE ua.uid=? AND ua.type=2",
                arrayOf(loaded.plan.creditAccountId.value.bytes),
            ) { it.getLong(0) } ?: abort(FinanceDataError.CorruptData)
        }
        if (simulation.outstandingPrincipalMinor > debt) {
            return@withDatabase DomainResult.Failure(app.ledger.finance.domain.DomainViolation.InvalidField("installment.settlement.debt"))
        }
        val book = database.readLedger(RoomBookRepository::mapCurrent)
        val references = database.readLedger(mapper::references)
        val revision = loaded.revision.copy(
            id = InstallmentPlanRevisionId(request.ids.mutation.planRevisionId),
            revisionNumber = request.revisionNumber,
            createdCommitId = BookCommitId(request.ids.mutation.commitId),
        )
        val schedule = InstallmentScheduleRevision(
            InstallmentScheduleRevisionId(request.ids.mutation.scheduleRevisionId),
            loaded.plan.id,
            request.scheduleRevisionNumber,
            ScheduleRevisionReason.PREPAYMENT,
            request.changedAt,
            BookCommitId(request.ids.mutation.commitId),
            emptyList(),
        )
        val settledPlan = loaded.plan.copy(
            currentRevisionId = revision.id,
            status = InstallmentStatus.SETTLED,
        )
        val transactionId = TransactionId(request.ids.transactionId)
        val mutation = InstallmentPlanMutation(
            settledPlan,
            loaded.revision.id,
            revision,
            schedule,
            0L,
            settlementTransactionId = transactionId,
        )
        val fxIds = request.ids.fxRateSnapshotIds.iterator()
        val evidence = buildList {
            add(frozenAmount(AmountRole.OUTGOING, request.payment, references, book.baseCurrency, fxIds))
            add(frozenAmount(AmountRole.INCOMING, request.credit, references, book.baseCurrency, fxIds))
            request.settlementFee?.let { add(frozenAmount(AmountRole.FEE, it, references, book.baseCurrency, fxIds)) }
        }
        if (fxIds.hasNext()) abort(app.ledger.finance.domain.DomainViolation.InvalidField("installment.settlement.fxIds"))
        val paymentAccount = references.account(UserAccountId(request.payment.accountId))?.account ?: abort(FinanceDataError.CorruptData)
        val creditAccount = references.account(loaded.plan.creditAccountId)?.account ?: abort(FinanceDataError.CorruptData)
        val paymentAmount = AccountAmount.create(paymentAccount, Money(request.payment.accountMinor, paymentAccount.currency)).valueOrAbort()
        val creditAmount = AccountAmount.create(creditAccount, Money(request.credit.accountMinor, creditAccount.currency)).valueOrAbort()
        val context = TransactionContextInput(
            EffectiveTime.fromInstant(request.changedAt, request.zoneId),
            request.settlementDate,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            TransactionSource.SYSTEM_GENERATED,
            loaded.plan.id.value,
            null,
            emptyList(),
        )
        val payload = CreditPaymentPayload(
            paymentAmount,
            loaded.plan.creditAccountId,
            creditAmount,
            listOf(CreditPaymentAllocation(null, positive(simulation.outstandingPrincipalMinor, loaded.plan.currency))),
            AutoGenerationMode.FORMAL_TRANSACTION,
            loaded.plan.id,
            simulation.settlementFeeMinor.takeIf { it > 0L }?.let { positive(it, loaded.plan.currency) },
        )
        val unsigned = ApplyInstallmentSettlementCommand(
            request.ids.mutation.commandId,
            zeroHash(),
            NewTransactionInput(context, payload),
            mutation,
        )
        val command = unsigned.copy(payloadHash = CanonicalFinancialHash.command(unsigned))
        val snapshot = PlanningSnapshot(
            book, null, null, emptyList(), emptySet(), emptyList(), null, emptyList(),
            AccountingPlanningContext(
                PlanningIdentitySet(
                    transactionId,
                    TransactionRevisionId(request.ids.transactionRevisionId),
                    BookCommitId(request.ids.mutation.commitId),
                    request.ids.factIds,
                ),
                request.changedAt,
                DeviceInstanceId(request.ids.mutation.deviceInstanceId),
                references,
                evidence,
                null,
            ),
            installmentPlanRevision = loaded.revision,
        )
        coordinate(database, command, snapshot)
    }

    override suspend fun applyRefund(request: ApplyInstallmentRefundRequest): DomainResult<CommandReceipt> = withDatabase(request.ids.bookId) { database ->
        database.readLedger { db -> InstallmentReplayReceiptVerifier.refund(db, request) }
            ?.let { return@withDatabase DomainResult.Success(it) }
        val loaded = database.readLedger { db -> loadPlan(db, request.ids.planId, request.firstRemainingStatementDate.minusDays(1)) }
        if (loaded.revision.id.value != request.expectedRevisionId || request.refundedPrincipalMinor <= 0L) {
            return@withDatabase DomainResult.Failure(app.ledger.finance.domain.DomainViolation.StaleExpectedRevision)
        }
        val allocatedRefundMinor = database.readLedger { db ->
            db.queryOne(
                "SELECT COALESCE(SUM(ra.original_currency_amount_minor),0) FROM refund_allocation ra " +
                    "JOIN business_transaction refund_tx ON refund_tx.id=ra.refund_transaction_id " +
                    "JOIN transaction_revision refund_revision ON refund_revision.id=ra.refund_revision_id " +
                    "WHERE refund_tx.uid=? AND refund_revision.uid=? AND ra.reversal_of_id IS NULL " +
                    "AND ra.original_transaction_id=(SELECT purchase_transaction_id FROM installment_plan WHERE uid=?)",
                arrayOf(request.refundTransactionId.bytes, request.refundRevisionId.bytes, request.ids.planId.bytes),
            ) { it.getLong(0) } ?: 0L
        }
        val installmentAllocationMinor = Math.addExact(request.refundedPrincipalMinor, request.refundedFeeMinor)
        if (allocatedRefundMinor <= 0L || installmentAllocationMinor > allocatedRefundMinor) {
            return@withDatabase DomainResult.Failure(app.ledger.finance.domain.DomainViolation.InvalidField("installment.refund.allocation"))
        }
        val allocation = InstallmentRefundAllocation(
            TransactionId(request.refundTransactionId),
            TransactionRevisionId(request.refundRevisionId),
            loaded.plan.id,
            request.refundedPrincipalMinor,
            request.refundedFeeMinor,
            null,
        )
        val currentPrincipal = Math.subtractExact(loaded.view.currentPrincipalMinor, request.refundedPrincipalMinor)
        if (currentPrincipal < 0L) {
            return@withDatabase DomainResult.Failure(app.ledger.finance.domain.DomainViolation.InvalidField("installment.refund.excess"))
        }
        val revision = loaded.revision.copy(
            id = InstallmentPlanRevisionId(request.ids.planRevisionId),
            revisionNumber = request.revisionNumber,
            createdCommitId = BookCommitId(request.ids.commitId),
        )
        val schedule = if (currentPrincipal == 0L) {
            InstallmentScheduleRevision(
                InstallmentScheduleRevisionId(request.ids.scheduleRevisionId),
                loaded.plan.id,
                request.scheduleRevisionNumber,
                ScheduleRevisionReason.REFUND,
                request.changedAt,
                BookCommitId(request.ids.commitId),
                emptyList(),
            )
        } else {
            InstallmentAccountingPolicy.generate(
                InstallmentScheduleRequest(
                    loaded.plan.id,
                    InstallmentScheduleRevisionId(request.ids.scheduleRevisionId),
                    request.ids.scheduleItemIds.map(::InstallmentScheduleItemId),
                    request.scheduleRevisionNumber,
                    ScheduleRevisionReason.REFUND,
                    request.changedAt,
                    BookCommitId(request.ids.commitId),
                    currentPrincipal,
                    request.ids.scheduleItemIds.size,
                    request.firstRemainingStatementDate,
                    revision,
                ),
            ).valueOrAbort()
        }
        val plan = loaded.plan.copy(
            termCount = if (currentPrincipal == 0L) loaded.plan.termCount else schedule.items.size,
            currentRevisionId = revision.id,
            status = if (currentPrincipal == 0L) InstallmentStatus.SETTLED else InstallmentStatus.ACTIVE,
        )
        val mutation = InstallmentPlanMutation(plan, loaded.revision.id, revision, schedule, currentPrincipal, refundAllocation = allocation)
        val unsigned = SaveInstallmentPlanCommand(request.ids.commandId, zeroHash(), mutation)
        val command = unsigned.copy(payloadHash = CanonicalFinancialHash.command(unsigned))
        val book = database.readLedger(RoomBookRepository::mapCurrent)
        coordinate(
            database,
            command,
            PlanningSnapshot(
                book, null, null, emptyList(), emptySet(), emptyList(), null, emptyList(),
                operationContext = PlanningOperationContext(BookCommitId(request.ids.commitId), request.changedAt, DeviceInstanceId(request.ids.deviceInstanceId)),
                installmentPlanRevision = loaded.revision,
            ),
        )
    }

    private fun termsRevision(request: SaveInstallmentPlanRequest): InstallmentPlanRevision = InstallmentPlanRevision(
        InstallmentPlanRevisionId(request.ids.planRevisionId),
        InstallmentPlanId(request.ids.planId),
        request.revisionNumber,
        request.terms.feeRateType,
        request.terms.fixedFeePerTermMinor,
        request.terms.firstTermFeeMinor,
        request.terms.remainingPrincipalRate,
        request.terms.effectiveAnnualRate,
        request.terms.prepaymentPolicy,
        request.terms.prepaymentFeeMinor,
        request.terms.refundPolicy,
        request.terms.roundingMode,
        BookCommitId(request.ids.commitId),
    )

    private fun scheduleRequest(
        request: SaveInstallmentPlanRequest,
        revision: InstallmentPlanRevision,
        principalMinor: Long,
    ): InstallmentScheduleRequest = InstallmentScheduleRequest(
        InstallmentPlanId(request.ids.planId),
        InstallmentScheduleRevisionId(request.ids.scheduleRevisionId),
        request.ids.scheduleItemIds.map(::InstallmentScheduleItemId),
        request.scheduleRevisionNumber,
        request.reason,
        request.changedAt,
        BookCommitId(request.ids.commitId),
        principalMinor,
        request.termCount,
        request.firstStatementDate,
        revision,
    )

    private fun operationSnapshot(
        book: app.ledger.finance.domain.Book,
        request: SaveInstallmentPlanRequest,
        revision: InstallmentPlanRevision,
    ): PlanningSnapshot = PlanningSnapshot(
        book, null, null, emptyList(), emptySet(), emptyList(), null, emptyList(),
        operationContext = PlanningOperationContext(BookCommitId(request.ids.commitId), request.changedAt, DeviceInstanceId(request.ids.deviceInstanceId)),
        installmentPlanRevision = request.expectedRevisionId?.let { revision.copy(id = InstallmentPlanRevisionId(it)) },
    )

    private fun readPurchases(db: SupportSQLiteDatabase): List<InstallmentPurchaseView> = db.queryList(
        "SELECT bt.uid transaction_uid,ua.uid account_uid,ua.name,ua.currency_code,ra.amount_minor,tr.local_date," +
            "CASE WHEN ip.id IS NULL THEN 0 ELSE 1 END linked FROM business_transaction bt " +
            "JOIN transaction_revision tr ON tr.id=bt.current_revision_id JOIN expense_revision_detail erd ON erd.revision_id=tr.id " +
            "JOIN user_account ua ON ua.id=erd.payer_account_id JOIN revision_amount ra ON ra.revision_id=tr.id AND ra.role=0 AND ra.representation=1 " +
            "LEFT JOIN installment_plan ip ON ip.purchase_transaction_id=bt.id WHERE bt.kind=0 AND bt.lifecycle_state=0 AND ua.type=2 " +
            "ORDER BY tr.occurred_at DESC,bt.id DESC",
    ) { cursor ->
        InstallmentPurchaseView(
            cursor.stableId("transaction_uid"),
            cursor.stableId("account_uid"),
            cursor.getString(cursor.getColumnIndexOrThrow("name")),
            currency(cursor.getString(cursor.getColumnIndexOrThrow("currency_code"))),
            cursor.getLong(cursor.getColumnIndexOrThrow("amount_minor")),
            storageDate(cursor.getInt(cursor.getColumnIndexOrThrow("local_date"))),
            cursor.getInt(cursor.getColumnIndexOrThrow("linked")) != 0,
        )
    }

    private fun loadPlan(db: SupportSQLiteDatabase, planUid: StableId, asOfDate: LocalDate): LoadedInstallment {
        val header = db.queryOne(
            "SELECT ip.uid,bt.uid purchase_uid,ua.uid account_uid,ua.name account_name,ip.currency_code,ip.original_principal_minor," +
                "ip.term_count,ip.status,ipr.uid revision_uid,ipr.revision_no,ipr.fee_rate_type,ipr.fixed_fee_per_term_minor," +
                "ipr.first_term_fee_minor,ipr.remaining_principal_rate_decimal,ipr.effective_annual_rate_decimal,ipr.prepayment_policy," +
                "ipr.prepayment_fee_minor,ipr.refund_policy,ipr.rounding_mode,bc.uid commit_uid " +
                "FROM installment_plan ip JOIN business_transaction bt ON bt.id=ip.purchase_transaction_id " +
                "JOIN user_account ua ON ua.id=ip.credit_account_id JOIN installment_plan_revision ipr ON ipr.id=ip.current_revision_id " +
                "JOIN book_commit bc ON bc.id=ipr.created_commit_id WHERE ip.uid=?",
            arrayOf(planUid.bytes),
        ) { cursor ->
            PlanHeader(
                cursor.stableId("uid"), cursor.stableId("purchase_uid"), cursor.stableId("account_uid"),
                cursor.getString(cursor.getColumnIndexOrThrow("account_name")),
                currency(cursor.getString(cursor.getColumnIndexOrThrow("currency_code"))),
                cursor.getLong(cursor.getColumnIndexOrThrow("original_principal_minor")),
                cursor.getInt(cursor.getColumnIndexOrThrow("term_count")),
                cursor.getInt(cursor.getColumnIndexOrThrow("status")),
                cursor.stableId("revision_uid"), cursor.getInt(cursor.getColumnIndexOrThrow("revision_no")),
                cursor.getInt(cursor.getColumnIndexOrThrow("fee_rate_type")), cursor.nullableLong("fixed_fee_per_term_minor"),
                cursor.nullableLong("first_term_fee_minor"), cursor.nullableString("remaining_principal_rate_decimal"),
                cursor.nullableString("effective_annual_rate_decimal"), cursor.getInt(cursor.getColumnIndexOrThrow("prepayment_policy")),
                cursor.nullableLong("prepayment_fee_minor"), cursor.getInt(cursor.getColumnIndexOrThrow("refund_policy")),
                cursor.getInt(cursor.getColumnIndexOrThrow("rounding_mode")), cursor.stableId("commit_uid"),
            )
        } ?: abort(app.ledger.finance.domain.DomainViolation.InvalidField("installment.planId"))
        val planId = InstallmentPlanId(header.planId)
        val revision = InstallmentPlanRevision(
            InstallmentPlanRevisionId(header.revisionId), planId, header.revisionNumber,
            app.ledger.finance.domain.InstallmentFeeRateType.entries[header.feeRateType],
            header.fixedFee, header.firstFee,
            header.remainingRate?.let { InterestRate.of(BigDecimal(it)).valueOrAbort() },
            header.effectiveRate?.let { InterestRate.of(BigDecimal(it)).valueOrAbort() },
            app.ledger.finance.domain.InstallmentPrepaymentPolicy.entries[header.prepaymentPolicy],
            header.prepaymentFee,
            app.ledger.finance.domain.InstallmentRefundPolicy.entries[header.refundPolicy],
            RoundingMode.entries[header.roundingMode], BookCommitId(header.createdCommitId),
        )
        val scheduleHeader = db.queryOne(
            "SELECT isr.uid,isr.revision_no,isr.reason,isr.generated_at,bc.uid commit_uid FROM installment_schedule_revision isr " +
                "JOIN book_commit bc ON bc.id=isr.created_commit_id WHERE isr.plan_id=(SELECT id FROM installment_plan WHERE uid=?) " +
                "ORDER BY isr.revision_no DESC LIMIT 1",
            arrayOf(planUid.bytes),
        ) { cursor ->
            ScheduleHeader(
                cursor.stableId("uid"),
                cursor.getInt(cursor.getColumnIndexOrThrow("revision_no")),
                cursor.getInt(cursor.getColumnIndexOrThrow("reason")),
                cursor.getLong(cursor.getColumnIndexOrThrow("generated_at")),
                cursor.stableId("commit_uid"),
            )
        } ?: abort(FinanceDataError.CorruptData)
        val items = db.queryList(
            "SELECT id,installment_no,statement_date,principal_minor,interest_minor,fee_minor,remaining_principal_minor " +
                "FROM installment_schedule_item WHERE schedule_revision_id=(SELECT id FROM installment_schedule_revision WHERE uid=?) ORDER BY installment_no",
            arrayOf(scheduleHeader.id.bytes),
        ) { cursor ->
            InstallmentScheduleItem(
                InstallmentScheduleItemId(stableIdFromInternal(cursor.getLong(cursor.getColumnIndexOrThrow("id")))),
                cursor.getInt(cursor.getColumnIndexOrThrow("installment_no")),
                storageDate(cursor.getInt(cursor.getColumnIndexOrThrow("statement_date"))),
                cursor.getLong(cursor.getColumnIndexOrThrow("principal_minor")),
                cursor.getLong(cursor.getColumnIndexOrThrow("interest_minor")),
                cursor.getLong(cursor.getColumnIndexOrThrow("fee_minor")),
                cursor.getLong(cursor.getColumnIndexOrThrow("remaining_principal_minor")),
            )
        }
        val schedule = InstallmentScheduleRevision(
            InstallmentScheduleRevisionId(scheduleHeader.id),
            planId,
            scheduleHeader.revisionNumber,
            ScheduleRevisionReason.entries[scheduleHeader.reason],
            Instant.ofEpochMilli(scheduleHeader.generatedAt),
            BookCommitId(scheduleHeader.createdCommitId),
            items,
        )
        val refunded = db.queryOne(
            "SELECT COALESCE(SUM(CASE WHEN reversal_of_id IS NULL THEN principal_minor ELSE -principal_minor END),0)," +
                "COALESCE(SUM(CASE WHEN reversal_of_id IS NULL THEN fee_minor ELSE -fee_minor END),0) " +
                "FROM installment_refund_allocation WHERE plan_id=(SELECT id FROM installment_plan WHERE uid=?)",
            arrayOf(planUid.bytes),
        ) { it.getLong(0) to it.getLong(1) } ?: (0L to 0L)
        val currentPrincipal = if (header.status == InstallmentStatus.SETTLED.ordinal) 0L else Math.subtractExact(header.originalPrincipal, refunded.first)
        val progress = InstallmentAccountingPolicy.progress(currentPrincipal, schedule, asOfDate).valueOrAbort()
        val plan = InstallmentPlan(
            planId,
            TransactionId(header.purchaseId),
            UserAccountId(header.accountId),
            header.originalPrincipal,
            header.currency,
            header.termCount,
            revision.id,
            InstallmentStatus.entries[header.status],
        )
        return LoadedInstallment(
            plan,
            revision,
            schedule,
            InstallmentPlanView(
                header.planId, header.purchaseId, header.accountId, header.accountName, header.currency,
                header.originalPrincipal, currentPrincipal, header.termCount, plan.status, revision, schedule, progress,
                refunded.first, refunded.second, previousSchedule(db, planUid, planId),
            ),
        )
    }

    private fun previousSchedule(
        db: SupportSQLiteDatabase,
        planUid: StableId,
        planId: InstallmentPlanId,
    ): InstallmentScheduleRevision? {
        val header = db.queryOne(
            "SELECT isr.uid,isr.revision_no,isr.reason,isr.generated_at,bc.uid commit_uid FROM installment_schedule_revision isr " +
                "JOIN book_commit bc ON bc.id=isr.created_commit_id WHERE isr.plan_id=(SELECT id FROM installment_plan WHERE uid=?) " +
                "ORDER BY isr.revision_no DESC LIMIT 1 OFFSET 1",
            arrayOf(planUid.bytes),
        ) { cursor ->
            ScheduleHeader(
                cursor.stableId("uid"), cursor.int("revision_no"), cursor.int("reason"), cursor.long("generated_at"), cursor.stableId("commit_uid"),
            )
        } ?: return null
        val items = db.queryList(
            "SELECT id,installment_no,statement_date,principal_minor,interest_minor,fee_minor,remaining_principal_minor " +
                "FROM installment_schedule_item WHERE schedule_revision_id=(SELECT id FROM installment_schedule_revision WHERE uid=?) ORDER BY installment_no",
            arrayOf(header.id.bytes),
        ) { cursor ->
            InstallmentScheduleItem(
                InstallmentScheduleItemId(stableIdFromInternal(cursor.long("id"))), cursor.int("installment_no"),
                storageDate(cursor.int("statement_date")), cursor.long("principal_minor"), cursor.long("interest_minor"),
                cursor.long("fee_minor"), cursor.long("remaining_principal_minor"),
            )
        }
        return InstallmentScheduleRevision(
            InstallmentScheduleRevisionId(header.id), planId, header.revisionNumber, ScheduleRevisionReason.entries[header.reason],
            Instant.ofEpochMilli(header.generatedAt), BookCommitId(header.createdCommitId), items,
        )
    }

    private fun frozenAmount(
        role: AmountRole,
        draft: SpecializedAccountAmountDraft,
        references: PlanningReferenceData,
        baseCurrency: CurrencyCode,
        fxIds: Iterator<StableId>,
    ): FrozenAmountEvidence {
        val account = references.account(UserAccountId(draft.accountId)) ?: abort(FinanceDataError.CorruptData)
        val accountMoney = positive(draft.accountMinor, account.account.currency)
        val baseMoney = positive(draft.baseMinor, baseCurrency)
        val conversion = if (account.account.currency == baseCurrency) {
            if (draft.accountMinor != draft.baseMinor || draft.accountToBaseEvidence != null) {
                abort(app.ledger.finance.domain.DomainViolation.InvalidField("installment.baseAmount"))
            }
            null
        } else {
            val fx = draft.accountToBaseEvidence ?: abort(app.ledger.finance.domain.DomainViolation.InvalidField("installment.fxEvidence"))
            FrozenFxConversion.create(
                FxRateSnapshotId(fxIds.nextOrAbort()),
                accountMoney,
                baseMoney,
                fx,
                currencyCatalog.require(account.account.currency).valueOrAbort(),
                currencyCatalog.require(baseCurrency).valueOrAbort(),
                false,
            ).valueOrAbort()
        }
        return FrozenAmountEvidence.create(
            AmountEvidenceKey(role, 0),
            accountMoney,
            accountMoney,
            baseMoney,
            account.account.id,
            null,
            conversion,
        ).valueOrAbort()
    }

    private suspend fun coordinate(
        database: LedgerDatabase,
        command: FinancialCommand,
        snapshot: PlanningSnapshot,
    ): DomainResult<CommandReceipt> {
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

    private suspend fun <T> withDatabase(
        bookId: StableId,
        block: suspend (LedgerDatabase) -> DomainResult<T>,
    ): DomainResult<T> = try {
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
    private fun currency(value: String): CurrencyCode = CurrencyCode.parse(value).valueOrAbort()
    private fun storageDate(value: Int): LocalDate = LocalDate.of(value / 10_000, value / 100 % 100, value % 100)
    private fun zeroHash(): Hash256 = Hash256.fromBytes(ByteArray(Hash256.BYTE_COUNT)).valueOrAbort()
    private fun Iterator<StableId>.nextOrAbort(): StableId = if (hasNext()) next() else abort(FinanceDataError.CorruptData)

    private data class LoadedInstallment(
        val plan: InstallmentPlan,
        val revision: InstallmentPlanRevision,
        val schedule: InstallmentScheduleRevision,
        val view: InstallmentPlanView,
    )

    private data class PlanHeader(
        val planId: StableId,
        val purchaseId: StableId,
        val accountId: StableId,
        val accountName: String,
        val currency: CurrencyCode,
        val originalPrincipal: Long,
        val termCount: Int,
        val status: Int,
        val revisionId: StableId,
        val revisionNumber: Int,
        val feeRateType: Int,
        val fixedFee: Long?,
        val firstFee: Long?,
        val remainingRate: String?,
        val effectiveRate: String?,
        val prepaymentPolicy: Int,
        val prepaymentFee: Long?,
        val refundPolicy: Int,
        val roundingMode: Int,
        val createdCommitId: StableId,
    )

    private data class ScheduleHeader(
        val id: StableId,
        val revisionNumber: Int,
        val reason: Int,
        val generatedAt: Long,
        val createdCommitId: StableId,
    )

    private class InstallmentWriteGate : LedgerWriteGate {
        private val mutex = Mutex()
        override suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock { block() }
    }
}

private fun settlementConfirmationMatches(
    request: ApplyInstallmentSettlementRequest,
    planCreditAccountId: StableId,
    simulation: InstallmentSettlementSimulation,
): Boolean = simulation.allowed &&
    request.credit.accountId == planCreditAccountId &&
    request.credit.accountMinor == simulation.outstandingPrincipalMinor &&
    request.payment.accountMinor == simulation.paymentMinor &&
    request.settlementFee?.accountMinor == simulation.settlementFeeMinor.takeIf { it > 0L }
