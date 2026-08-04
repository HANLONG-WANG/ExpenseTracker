@file:Suppress("LongMethod", "LongParameterList", "TooManyFunctions", "LargeClass", "MagicNumber", "MaxLineLength")

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
import app.ledger.finance.application.ApplyLoanSimulationRequest
import app.ledger.finance.application.AtomicFinancialCommitRepository
import app.ledger.finance.application.DefaultFinancialMutationCoordinator
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.FinancialPlanningPort
import app.ledger.finance.application.FinancialPlanningSnapshotRepository
import app.ledger.finance.application.LedgerWriteGate
import app.ledger.finance.application.LoanAccountOption
import app.ledger.finance.application.LoanApplicationPort
import app.ledger.finance.application.LoanComponentAllocationDraft
import app.ledger.finance.application.LoanComponentAmountDraft
import app.ledger.finance.application.LoanContractView
import app.ledger.finance.application.LoanMutationIds
import app.ledger.finance.application.LoanScheduleItemView
import app.ledger.finance.application.LoanSimulationRequest
import app.ledger.finance.application.LoanSnapshot
import app.ledger.finance.application.LoanTermsDraft
import app.ledger.finance.application.LoanTrancheDraft
import app.ledger.finance.application.LoanTrancheView
import app.ledger.finance.application.RecordLoanDisbursementRequest
import app.ledger.finance.application.RecordLoanPaymentRequest
import app.ledger.finance.application.SaveLoanContractRequest
import app.ledger.finance.application.SpecializedAccountAmountDraft
import app.ledger.finance.domain.AccountAmount
import app.ledger.finance.domain.AccountingPlanningContext
import app.ledger.finance.domain.AmountEvidenceKey
import app.ledger.finance.domain.AmountRole
import app.ledger.finance.domain.ApplyLoanPaymentCommand
import app.ledger.finance.domain.Book
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.CanonicalFinancialHash
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.DeterministicFinancialPlanner
import app.ledger.finance.domain.DeviceInstanceId
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.FinancialCommand
import app.ledger.finance.domain.FrozenAmountEvidence
import app.ledger.finance.domain.FrozenFxConversion
import app.ledger.finance.domain.FxRateSnapshotId
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.InterestRate
import app.ledger.finance.domain.LoanAccountingPolicy
import app.ledger.finance.domain.LoanActualAllocation
import app.ledger.finance.domain.LoanContract
import app.ledger.finance.domain.LoanContractId
import app.ledger.finance.domain.LoanContractMutation
import app.ledger.finance.domain.LoanDisbursementAllocation
import app.ledger.finance.domain.LoanDisbursementPayload
import app.ledger.finance.domain.LoanPaymentComponent
import app.ledger.finance.domain.LoanPaymentComponents
import app.ledger.finance.domain.LoanPaymentPayload
import app.ledger.finance.domain.LoanPrepaymentSimulation
import app.ledger.finance.domain.LoanRatePeriod
import app.ledger.finance.domain.LoanRateType
import app.ledger.finance.domain.LoanRepaymentMethod
import app.ledger.finance.domain.LoanScheduleItem
import app.ledger.finance.domain.LoanScheduleItemId
import app.ledger.finance.domain.LoanScheduleRequest
import app.ledger.finance.domain.LoanScheduleRevision
import app.ledger.finance.domain.LoanScheduleRevisionId
import app.ledger.finance.domain.LoanSimulationScenario
import app.ledger.finance.domain.LoanStatus
import app.ledger.finance.domain.LoanTermsRevision
import app.ledger.finance.domain.LoanTermsRevisionId
import app.ledger.finance.domain.LoanTranche
import app.ledger.finance.domain.LoanTrancheId
import app.ledger.finance.domain.LoanTrancheMutation
import app.ledger.finance.domain.NewTransactionInput
import app.ledger.finance.domain.PaymentFrequency
import app.ledger.finance.domain.PlanningIdentitySet
import app.ledger.finance.domain.PlanningOperationContext
import app.ledger.finance.domain.PlanningReferenceData
import app.ledger.finance.domain.PlanningSnapshot
import app.ledger.finance.domain.PositiveMoney
import app.ledger.finance.domain.RecordLoanDisbursementCommand
import app.ledger.finance.domain.SaveLoanContractCommand
import app.ledger.finance.domain.ScheduleRevisionReason
import app.ledger.finance.domain.TransactionContextInput
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionRevisionId
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate

class SecureRoomLoanApplicationPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
) : LoanApplicationPort {
    private val applicationContext = context.applicationContext
    private val mapper = RoomReferenceFinancialSnapshotMapper()
    private val currencyCatalog = JvmLegalTenderCurrencyCatalog.create()
    private val writeGate: LedgerWriteGate = LoanWriteGate()

    override suspend fun snapshot(bookId: StableId): DomainResult<LoanSnapshot> = withDatabase(bookId) { database ->
        database.readLedger { db ->
            val book = RoomBookRepository.mapCurrent(db)
            if (book.id.value != bookId) abort(FinanceDataError.CorruptData)
            val stale = db.queryOne(
                "SELECT (SELECT COUNT(*) FROM loan_progress_projection WHERE as_of_local_revision<>?) + " +
                    "(SELECT COUNT(*) FROM loan_future_cashflow_projection WHERE as_of_local_revision<>?)",
                arrayOf<Any>(book.localRevision.value, book.localRevision.value),
            ) { it.getLong(0) } ?: 0L
            if (stale != 0L) abort(FinanceDataError.ProjectionMismatch)
            val contracts = db.queryList("SELECT uid FROM loan_contract ORDER BY status,id") { it.stableId("uid") }
                .map { loadContract(db, it) }
            DomainResult.Success(
                LoanSnapshot(
                    bookId,
                    book.baseCurrency,
                    book.localRevision,
                    contracts,
                    accountOptions(db, setOf(LOAN_ACCOUNT_TYPE)),
                    accountOptions(db, PAYMENT_ACCOUNT_TYPES),
                ),
            )
        }
    }

    override suspend fun preview(request: SaveLoanContractRequest): DomainResult<List<LoanScheduleRevision>> = try {
        DomainResult.Success(buildMutation(request).tranches.map { it.scheduleRevision })
    } catch (_: IllegalArgumentException) {
        DomainResult.Failure(DomainViolation.InvalidField("loan.preview"))
    } catch (_: ArithmeticException) {
        DomainResult.Failure(DomainViolation.NumericOverflow("loan.preview"))
    }

    override suspend fun saveContract(request: SaveLoanContractRequest): DomainResult<CommandReceipt> = withDatabase(request.ids.bookId) { database ->
        val book = database.readLedger(RoomBookRepository::mapCurrent)
        val mutation = buildMutation(request)
        val unsigned = SaveLoanContractCommand(request.ids.commandId, zeroHash(), mutation)
        val command = unsigned.copy(payloadHash = CanonicalFinancialHash.command(unsigned))
        coordinate(database, command, operationSnapshot(book, request, mutation.expectedLastCommitId, null))
    }

    override suspend fun recordDisbursement(request: RecordLoanDisbursementRequest): DomainResult<CommandReceipt> = withDatabase(request.mutationIds.bookId) { database ->
        val book = database.readLedger(RoomBookRepository::mapCurrent)
        val references = database.readLedger(mapper::references)
        val contract = database.readLedger { loadContract(it, request.mutationIds.contractId) }
        val ids = request.transactionIds
        val fxIds = ids.fxRateSnapshotIds.iterator()
        val receiving = frozenAccount(AmountRole.INCOMING, request.receiving, references, book.baseCurrency, fxIds)
        val liability = frozenComponent(
            AmountRole.PRINCIPAL,
            request.liability,
            contract.currency,
            book.baseCurrency,
            fxIds,
        )
        if (fxIds.hasNext()) abort(DomainViolation.InvalidField("loan.disbursement.fxIds"))
        val allocationSum = request.allocations.sumChecked { it.accountMinor }
        val baseSum = request.allocations.sumChecked { it.baseMinor }
        if (allocationSum != request.liability.accountMinor || baseSum != request.liability.baseMinor) {
            return@withDatabase DomainResult.Failure(DomainViolation.InvalidField("loan.disbursement.allocations"))
        }
        val payload = LoanDisbursementPayload(
            LoanContractId(contract.id),
            accountAmount(request.receiving, references),
            liability.accountAmount,
            request.allocations.map {
                LoanDisbursementAllocation(
                    LoanTrancheId(it.trancheId),
                    positive(it.accountMinor, contract.currency),
                    positive(it.baseMinor, book.baseCurrency),
                )
            },
        )
        val context = transactionContext(request.context, null)
        val unsigned = RecordLoanDisbursementCommand(
            request.mutationIds.commandId,
            zeroHash(),
            NewTransactionInput(context, payload),
        )
        val command = unsigned.copy(payloadHash = CanonicalFinancialHash.command(unsigned))
        val snapshot = financialSnapshot(
            book,
            request.mutationIds.commitId,
            request.mutationIds.deviceInstanceId,
            ids.transactionId,
            ids.revisionId,
            ids.factIds,
            references,
            listOf(receiving, liability),
            request.context.occurredAt,
        )
        coordinate(database, command, snapshot)
    }

    override suspend fun recordPayment(request: RecordLoanPaymentRequest): DomainResult<CommandReceipt> = withDatabase(request.mutation.ids.bookId) { database ->
        val book = database.readLedger(RoomBookRepository::mapCurrent)
        val references = database.readLedger(mapper::references)
        val current = database.readLedger { loadContract(it, request.mutation.ids.contractId) }
        val mutation = buildMutation(request.mutation)
        val fxIds = request.transactionIds.fxRateSnapshotIds.iterator()
        val outgoing = frozenAccount(AmountRole.OUTGOING, request.payment, references, book.baseCurrency, fxIds)
        val componentEvidence = linkedMapOf<LoanPaymentComponent, FrozenAmountEvidence>()
        listOf(
            LoanPaymentComponent.PRINCIPAL to request.components.principal,
            LoanPaymentComponent.INTEREST to request.components.interest,
            LoanPaymentComponent.FEE to request.components.fee,
            LoanPaymentComponent.PENALTY to request.components.penalty,
        ).forEach { (component, draft) ->
            draft?.let {
                componentEvidence[component] = frozenComponent(
                    AmountRole.valueOf(component.name),
                    it,
                    current.currency,
                    book.baseCurrency,
                    fxIds,
                )
            }
        }
        if (fxIds.hasNext()) abort(DomainViolation.InvalidField("loan.payment.fxIds"))
        val transactionId = TransactionId(request.transactionIds.transactionId)
        val revisionId = TransactionRevisionId(request.transactionIds.revisionId)
        val allocations = request.allocations.map { draft ->
            LoanActualAllocation(
                transactionId,
                revisionId,
                LoanTrancheId(draft.trancheId),
                draft.scheduleItemId?.let(::LoanScheduleItemId),
                draft.component,
                positive(draft.accountMinor, current.currency),
                positive(draft.baseMinor, book.baseCurrency),
                null,
            )
        }
        val paymentValidation = LoanAccountingPolicy.validatePayment(
            request.payment.accountMinor,
            current.tranches.associate { LoanTrancheId(it.id) to it.remainingPrincipalMinor },
            allocations,
        )
        if (paymentValidation is DomainResult.Failure) return@withDatabase paymentValidation
        componentEvidence.forEach { (component, evidence) ->
            val accountAllocated = allocations.filter { it.component == component }.sumChecked { it.amount.minor.value }
            val baseAllocated = allocations.filter { it.component == component }.sumChecked { it.baseAmount.minor.value }
            if (accountAllocated != evidence.accountAmount.minor.value || baseAllocated != evidence.baseAmount.minor.value) {
                return@withDatabase DomainResult.Failure(DomainViolation.InvalidField("loan.payment.componentEvidence"))
            }
        }
        val payload = LoanPaymentPayload(
            null,
            LoanContractId(current.id),
            accountAmount(request.payment, references),
            mutation.tranches.firstOrNull()?.scheduleRevision?.id,
            LoanPaymentComponents(
                componentEvidence[LoanPaymentComponent.PRINCIPAL]?.userInput,
                componentEvidence[LoanPaymentComponent.INTEREST]?.userInput,
                componentEvidence[LoanPaymentComponent.FEE]?.userInput,
                componentEvidence[LoanPaymentComponent.PENALTY]?.userInput,
            ),
            allocations,
        )
        val unsigned = ApplyLoanPaymentCommand(
            request.mutation.ids.commandId,
            zeroHash(),
            NewTransactionInput(transactionContext(request.context, current.id), payload),
            mutation,
        )
        val command = unsigned.copy(payloadHash = CanonicalFinancialHash.command(unsigned))
        val snapshot = financialSnapshot(
            book,
            request.mutation.ids.commitId,
            request.mutation.ids.deviceInstanceId,
            request.transactionIds.transactionId,
            request.transactionIds.revisionId,
            request.transactionIds.factIds,
            references,
            listOf(outgoing) + componentEvidence.values,
            request.context.occurredAt,
        ).copy(loanContractLastCommitId = BookCommitId(current.lastCommitId))
        coordinate(database, command, snapshot)
    }

    override suspend fun simulate(request: LoanSimulationRequest): DomainResult<LoanPrepaymentSimulation> = withDatabase(request.bookId) { database ->
        val loaded = database.readLedger { db -> loadContractDomain(db, request.contractId, request.trancheId) }
        val replacementTerms = termsRevision(
            request.replacementTerms,
            request.replacementIds,
            loaded.terms.revisionNumber + 1,
            BookCommitId(request.replacementIds.termsRevisionId),
        )
        val replacementRequest = scheduleRequest(
            request.replacementTerms,
            request.replacementIds,
            replacementTerms,
            loaded.schedule.revisionNumber + 1,
            loaded.remainingPrincipalMinor,
            request.changedAt,
        )
        val simulation = LoanAccountingPolicy.simulatePrepayment(
            LoanContractId(request.contractId),
            loaded.schedule,
            loaded.terms,
            request.scenario,
            replacementRequest,
        )
        if (simulation is DomainResult.Success) {
            database.inLedgerTransaction { db -> persistSimulation(db, request, simulation.value) }
        }
        simulation
    }

    override suspend fun applySimulation(request: ApplyLoanSimulationRequest): DomainResult<CommandReceipt> = withDatabase(request.simulation.bookId) { database ->
        database.readLedger { db -> LoanReplayReceiptVerifier.payment(db, request) }
            ?.let { return@withDatabase DomainResult.Success(it) }
        val simulation = database.readLedger { db ->
            val stored = db.queryOne(
                "SELECT contract_id,base_schedule_revision_id FROM loan_simulation WHERE id=?",
                arrayOf(request.simulation.simulationId.internalId()),
            ) { it.getLong(0) to it.getLong(1) } ?: abort(DomainViolation.InvalidField("loan.simulation.missing"))
            val expectedContract = db.requireInternalId("loan_contract", request.simulation.contractId)
            val expectedSchedule = db.requireInternalId(
                "loan_schedule_revision",
                loadContractDomain(db, request.simulation.contractId, request.simulation.trancheId).schedule.id.value,
            )
            if (stored.first != expectedContract || stored.second != expectedSchedule) {
                abort(DomainViolation.StaleExpectedRevision)
            }
            loadSimulationResult(db, request.simulation)
        }
        val allocations = buildList {
            if (simulation.prepaymentPrincipalMinor > 0L) {
                add(
                    LoanComponentAllocationDraft(
                        request.simulation.trancheId,
                        null,
                        LoanPaymentComponent.PRINCIPAL,
                        request.principal.accountMinor,
                        request.principal.baseMinor,
                    ),
                )
            }
            request.penalty?.let {
                add(
                    LoanComponentAllocationDraft(
                        request.simulation.trancheId,
                        null,
                        LoanPaymentComponent.PENALTY,
                        it.accountMinor,
                        it.baseMinor,
                    ),
                )
            }
        }
        val paymentRequest = RecordLoanPaymentRequest(
            request.mutation,
            request.transactionIds,
            request.context,
            request.payment,
            app.ledger.finance.application.LoanPaymentAmountsDraft(
                request.principal,
                null,
                null,
                request.penalty,
            ),
            allocations,
        )
        recordPayment(paymentRequest)
    }

    private fun buildMutation(request: SaveLoanContractRequest): LoanContractMutation {
        val commit = BookCommitId(request.ids.commitId)
        val trancheMutations = request.tranches.map { draft ->
            val terms = termsRevision(draft.terms, draft.ids, draft.termsRevisionNumber, commit)
            val schedule = if (draft.currentPrincipalMinor == 0L && draft.status == LoanStatus.PAID_OFF) {
                LoanScheduleRevision(
                    LoanScheduleRevisionId(draft.ids.scheduleRevisionId),
                    LoanTrancheId(draft.ids.trancheId),
                    draft.scheduleRevisionNumber,
                    terms.id,
                    draft.scheduleReason,
                    request.changedAt,
                    commit,
                    emptyList(),
                )
            } else {
                LoanAccountingPolicy.generate(
                    scheduleRequest(
                        draft.terms,
                        draft.ids,
                        terms,
                        draft.scheduleRevisionNumber,
                        draft.currentPrincipalMinor,
                        request.changedAt,
                        draft.scheduleReason,
                    ),
                ).valueOrAbort()
            }
            LoanTrancheMutation(
                LoanTranche(
                    LoanTrancheId(draft.ids.trancheId),
                    LoanContractId(request.ids.contractId),
                    app.ledger.finance.domain.LedgerAccountId(draft.ledgerAccountId),
                    draft.name,
                    draft.originalPrincipalMinor,
                    draft.status,
                ),
                draft.expectedTermsRevisionId?.let(::LoanTermsRevisionId),
                terms,
                schedule,
            )
        }
        val contract = LoanContract(
            LoanContractId(request.ids.contractId),
            UserAccountId(request.displayAccountId),
            request.name,
            request.lender,
            request.currency,
            request.disbursementDate,
            request.status,
            commit,
            trancheMutations.map { it.tranche.id },
        )
        return LoanContractMutation(contract, request.expectedLastCommitId?.let(::BookCommitId), trancheMutations)
    }

    private fun termsRevision(
        draft: LoanTermsDraft,
        ids: app.ledger.finance.application.LoanTrancheMutationIds,
        revisionNumber: Int,
        commitId: BookCommitId,
    ) = LoanTermsRevision(
        LoanTermsRevisionId(ids.termsRevisionId),
        LoanTrancheId(ids.trancheId),
        revisionNumber,
        draft.repaymentMethod,
        draft.rateType,
        draft.paymentFrequency,
        draft.startDate,
        draft.endDate,
        draft.roundingMode,
        draft.prepaymentPolicy,
        draft.prepaymentStrategy,
        draft.penaltyRate,
        commitId,
        draft.ratePeriods,
    )

    private fun scheduleRequest(
        draft: LoanTermsDraft,
        ids: app.ledger.finance.application.LoanTrancheMutationIds,
        terms: LoanTermsRevision,
        revisionNumber: Int,
        principalMinor: Long,
        changedAt: Instant,
        reason: ScheduleRevisionReason = ScheduleRevisionReason.PREPAYMENT,
    ) = LoanScheduleRequest(
        LoanScheduleRevisionId(ids.scheduleRevisionId),
        ids.scheduleItemIds.map(::LoanScheduleItemId),
        revisionNumber,
        reason,
        changedAt,
        terms.createdCommitId,
        terms,
        principalMinor,
        draft.paymentCount,
        draft.firstPaymentDate,
        draft.feePerPaymentMinor,
    )

    private fun loadContract(db: SupportSQLiteDatabase, id: StableId): LoanContractView {
        val header = db.queryOne(
            "SELECT lc.uid,ua.uid account_uid,lc.name,lc.lender,lc.currency_code,lc.disbursement_date,lc.status,bc.uid commit_uid " +
                "FROM loan_contract lc JOIN user_account ua ON ua.id=lc.display_account_id JOIN book_commit bc ON bc.id=lc.last_commit_id WHERE lc.uid=?",
            arrayOf(id.bytes),
        ) { cursor ->
            ContractHeader(
                cursor.stableId("uid"),
                cursor.stableId("account_uid"),
                cursor.string("name"),
                cursor.nullableString("lender"),
                currency(cursor.string("currency_code")),
                storageDate(cursor.int("disbursement_date")),
                cursor.int("status"),
                cursor.stableId("commit_uid"),
            )
        } ?: abort(FinanceDataError.CorruptData)
        val tranches = db.queryList("SELECT uid FROM loan_tranche WHERE contract_id=(SELECT id FROM loan_contract WHERE uid=?) ORDER BY id", arrayOf(id.bytes)) {
            it.stableId("uid")
        }.map { loadTranche(db, it) }
        return LoanContractView(
            header.id,
            header.accountId,
            header.name,
            header.lender,
            header.currency,
            header.disbursementDate,
            LoanStatus.entries[header.status],
            header.lastCommitId,
            tranches,
        )
    }

    private fun loadTranche(db: SupportSQLiteDatabase, id: StableId): LoanTrancheView {
        val row = db.queryOne(
            "SELECT lt.name,la.uid ledger_uid,lt.original_principal_minor,lt.status,ltr.uid terms_uid,ltr.revision_no,ltr.repayment_method,ltr.rate_type," +
                "ltr.payment_frequency,ltr.rounding_mode,ltr.prepayment_policy,lsr.uid schedule_uid,lsr.revision_no schedule_no," +
                "(SELECT COUNT(*) FROM loan_schedule_revision history WHERE history.tranche_id=lt.id) history_count," +
                "COALESCE(lp.remaining_principal_minor,lt.original_principal_minor) remaining," +
                "COALESCE(lp.repaid_principal_minor,0) principal_paid," +
                "COALESCE((SELECT SUM(CASE WHEN le.kind=2 THEN le.polarity*le.amount_minor ELSE 0 END) FROM loan_effect le WHERE le.loan_tranche_id=lt.id),0) interest_paid," +
                "COALESCE((SELECT SUM(CASE WHEN le.kind=3 THEN le.polarity*le.amount_minor ELSE 0 END) FROM loan_effect le WHERE le.loan_tranche_id=lt.id),0) fee_paid," +
                "COALESCE((SELECT SUM(CASE WHEN le.kind=4 THEN le.polarity*le.amount_minor ELSE 0 END) FROM loan_effect le WHERE le.loan_tranche_id=lt.id),0) penalty_paid " +
                "FROM loan_tranche lt JOIN ledger_account la ON la.id=lt.ledger_account_id JOIN loan_terms_revision ltr ON ltr.tranche_id=lt.id " +
                "JOIN loan_schedule_revision lsr ON lsr.tranche_id=lt.id LEFT JOIN loan_progress_projection lp ON lp.tranche_id=lt.id " +
                "WHERE lt.uid=? AND ltr.revision_no=(SELECT MAX(r.revision_no) FROM loan_terms_revision r WHERE r.tranche_id=lt.id) " +
                "AND lsr.revision_no=(SELECT MAX(s.revision_no) FROM loan_schedule_revision s WHERE s.tranche_id=lt.id)",
            arrayOf(id.bytes),
        ) { cursor -> TrancheHeader.from(cursor) } ?: abort(FinanceDataError.CorruptData)
        val decoded = LoanPrepaymentCodec.decode(row.prepayment)
        val rates = readRates(db, row.termsId)
        val schedule = readScheduleItems(db, row.scheduleId)
        return LoanTrancheView(
            id,
            row.ledgerAccountId,
            row.name,
            row.originalPrincipal,
            row.remaining,
            row.principalPaid,
            row.interestPaid,
            row.feePaid,
            row.penaltyPaid,
            LoanStatus.entries[row.status],
            row.termsId,
            row.termsRevision,
            LoanRepaymentMethod.entries[row.repaymentMethod],
            LoanRateType.entries[row.rateType],
            PaymentFrequency.entries[row.frequency],
            decoded.policy,
            decoded.strategy,
            decoded.penaltyRate,
            java.math.RoundingMode.entries[row.roundingMode],
            rates,
            row.scheduleId,
            row.scheduleRevision,
            row.historyCount,
            schedule,
        )
    }

    private fun readRates(db: SupportSQLiteDatabase, termsId: StableId): List<LoanRatePeriod> = db.queryList(
        "SELECT effective_from,effective_to,annual_rate_decimal,benchmark,margin_decimal FROM loan_rate_period " +
            "WHERE terms_revision_id=(SELECT id FROM loan_terms_revision WHERE uid=?) ORDER BY effective_from",
        arrayOf(termsId.bytes),
    ) { cursor ->
        LoanRatePeriod(
            storageDate(cursor.int("effective_from")),
            cursor.nullableLong("effective_to")?.toInt()?.let(::storageDate),
            InterestRate.of(BigDecimal(cursor.string("annual_rate_decimal"))).valueOrAbort(),
            cursor.nullableString("benchmark"),
            cursor.nullableString("margin_decimal")?.let { InterestRate.of(BigDecimal(it)).valueOrAbort() },
        )
    }

    private fun readScheduleItems(db: SupportSQLiteDatabase, scheduleId: StableId): List<LoanScheduleItemView> = db.queryList(
        "SELECT lsi.id,lsi.installment_no,lsi.planned_date,lsi.principal_minor,lsi.interest_minor,lsi.fee_minor,lsi.remaining_principal_minor," +
            "COALESCE(SUM(CASE WHEN la.component=0 THEN la.amount_minor ELSE 0 END),0) actual_principal," +
            "COALESCE(SUM(CASE WHEN la.component=1 THEN la.amount_minor ELSE 0 END),0) actual_interest," +
            "COALESCE(SUM(CASE WHEN la.component=2 THEN la.amount_minor ELSE 0 END),0) actual_fee," +
            "COALESCE(SUM(CASE WHEN la.component=3 THEN la.amount_minor ELSE 0 END),0) actual_penalty " +
            "FROM loan_schedule_item lsi LEFT JOIN loan_actual_allocation la ON la.schedule_item_id=lsi.id AND la.reversal_of_id IS NULL " +
            "WHERE lsi.schedule_revision_id=(SELECT id FROM loan_schedule_revision WHERE uid=?) GROUP BY lsi.id ORDER BY lsi.installment_no",
        arrayOf(scheduleId.bytes),
    ) { cursor ->
        LoanScheduleItemView(
            cursor.int("installment_no"),
            storageDate(cursor.int("planned_date")),
            cursor.long("principal_minor"),
            cursor.long("interest_minor"),
            cursor.long("fee_minor"),
            cursor.long("remaining_principal_minor"),
            cursor.long("actual_principal"),
            cursor.long("actual_interest"),
            cursor.long("actual_fee"),
            cursor.long("actual_penalty"),
        )
    }

    private fun loadContractDomain(db: SupportSQLiteDatabase, contractId: StableId, trancheId: StableId): LoadedLoanTranche {
        val view = loadContract(db, contractId)
        val tranche = view.tranches.singleOrNull { it.id == trancheId } ?: abort(FinanceDataError.CorruptData)
        val termsCommit = db.queryOne(
            "SELECT bc.uid FROM loan_terms_revision ltr JOIN book_commit bc ON bc.id=ltr.created_commit_id WHERE ltr.uid=?",
            arrayOf(tranche.currentTermsRevisionId.bytes),
        ) { it.stableId("uid") } ?: abort(FinanceDataError.CorruptData)
        val scheduleHeader = db.queryOne(
            "SELECT lsr.reason,lsr.generated_at,bc.uid commit_uid FROM loan_schedule_revision lsr " +
                "JOIN book_commit bc ON bc.id=lsr.created_commit_id WHERE lsr.uid=?",
            arrayOf(tranche.currentScheduleRevisionId.bytes),
        ) { Triple(it.int("reason"), it.long("generated_at"), it.stableId("commit_uid")) } ?: abort(FinanceDataError.CorruptData)
        val terms = LoanTermsRevision(
            LoanTermsRevisionId(tranche.currentTermsRevisionId),
            LoanTrancheId(tranche.id),
            tranche.termsRevisionNumber,
            tranche.repaymentMethod,
            tranche.rateType,
            tranche.paymentFrequency,
            tranche.ratePeriods.first().effectiveFrom,
            tranche.ratePeriods.last().effectiveTo ?: tranche.schedule.last().plannedDate,
            tranche.roundingMode,
            tranche.prepaymentPolicy,
            tranche.prepaymentStrategy,
            tranche.penaltyRate,
            BookCommitId(termsCommit),
            tranche.ratePeriods,
        )
        val items = tranche.schedule.mapIndexed { index, item ->
            LoanScheduleItem(
                LoanScheduleItemId(
                    stableIdFromInternal(
                        db.queryOne(
                            "SELECT id FROM loan_schedule_item WHERE schedule_revision_id=(SELECT id FROM loan_schedule_revision WHERE uid=?) AND installment_no=?",
                            arrayOf<Any>(tranche.currentScheduleRevisionId.bytes, item.installmentNumber),
                        ) { it.getLong(0) } ?: abort(FinanceDataError.CorruptData),
                    ),
                ),
                item.installmentNumber,
                item.plannedDate,
                item.principalMinor,
                item.interestMinor,
                item.feeMinor,
                item.remainingPrincipalMinor,
                index == tranche.schedule.lastIndex,
            )
        }
        val schedule = LoanScheduleRevision(
            LoanScheduleRevisionId(tranche.currentScheduleRevisionId),
            LoanTrancheId(tranche.id),
            tranche.scheduleRevisionNumber,
            LoanTermsRevisionId(tranche.currentTermsRevisionId),
            ScheduleRevisionReason.entries[scheduleHeader.first],
            Instant.ofEpochMilli(scheduleHeader.second),
            BookCommitId(scheduleHeader.third),
            items,
        )
        return LoadedLoanTranche(view, tranche, terms, schedule, tranche.remainingPrincipalMinor)
    }

    private fun persistSimulation(
        db: SupportSQLiteDatabase,
        request: LoanSimulationRequest,
        simulation: LoanPrepaymentSimulation,
    ) {
        val id = request.simulationId.internalId()
        val exists = db.queryOne("SELECT COUNT(*) FROM loan_simulation WHERE id=?", arrayOf(id)) { it.getLong(0) } ?: 0L
        if (exists != 0L) abort(DomainViolation.InvalidField("loan.simulation.duplicate"))
        db.execSQL(
            "INSERT INTO loan_simulation(id,contract_id,base_schedule_revision_id,scenario_type,parameters_blob,created_at) VALUES(?,?,?,?,?,?)",
            arrayOf<Any>(
                id,
                db.requireInternalId("loan_contract", request.contractId),
                db.requireInternalId("loan_schedule_revision", simulation.baseScheduleRevisionId.value),
                scenarioOrdinal(request.scenario),
                simulationParameters(simulation),
                request.changedAt.toStorageEpochMillis(),
            ),
        )
        simulation.after.items.forEach { item ->
            db.execSQL(
                "INSERT INTO loan_simulation_item(simulation_id,installment_no,planned_date,principal_minor,interest_minor,fee_minor,remaining_principal_minor) " +
                    "VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any>(id, item.installmentNumber, item.plannedDate.toStorageInt(), item.principalMinor, item.interestMinor, item.feeMinor, item.remainingPrincipalMinor),
            )
        }
    }

    private fun loadSimulationResult(db: SupportSQLiteDatabase, request: LoanSimulationRequest): LoanPrepaymentSimulation {
        val loaded = loadContractDomain(db, request.contractId, request.trancheId)
        val replacementTerms = termsRevision(
            request.replacementTerms,
            request.replacementIds,
            loaded.terms.revisionNumber + 1,
            BookCommitId(request.replacementIds.termsRevisionId),
        )
        return LoanAccountingPolicy.simulatePrepayment(
            LoanContractId(request.contractId),
            loaded.schedule,
            loaded.terms,
            request.scenario,
            scheduleRequest(
                request.replacementTerms,
                request.replacementIds,
                replacementTerms,
                loaded.schedule.revisionNumber + 1,
                loaded.remainingPrincipalMinor,
                request.changedAt,
            ),
        ).valueOrAbort()
    }

    private fun simulationParameters(simulation: LoanPrepaymentSimulation): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES * 4)
        .putLong(simulation.remainingPrincipalBeforeMinor)
        .putLong(simulation.prepaymentPrincipalMinor)
        .putLong(simulation.penaltyMinor)
        .putLong(simulation.paymentNowMinor)
        .array()

    private fun scenarioOrdinal(scenario: LoanSimulationScenario): Int = when (scenario) {
        is LoanSimulationScenario.PartialPrepayment -> 0
        is LoanSimulationScenario.FullSettlement -> 1
        is LoanSimulationScenario.RateChange -> 2
    }

    private fun accountOptions(db: SupportSQLiteDatabase, types: Set<Int>): List<LoanAccountOption> {
        val marks = types.joinToString(",") { "?" }
        return db.queryList(
            "SELECT ua.uid,ua.name,ua.currency_code,ua.type,la.uid ledger_uid,ua.status FROM user_account ua " +
                "JOIN ledger_account la ON la.id=ua.ledger_account_id WHERE ua.type IN ($marks) ORDER BY ua.sort_order,ua.id",
            types.map { it as Any }.toTypedArray(),
        ) { cursor ->
            LoanAccountOption(
                cursor.stableId("uid"),
                cursor.string("name"),
                currency(cursor.string("currency_code")),
                cursor.int("type").toString(),
                cursor.stableId("ledger_uid"),
                cursor.int("status") == 0,
            )
        }
    }

    private fun frozenAccount(
        role: AmountRole,
        draft: SpecializedAccountAmountDraft,
        references: PlanningReferenceData,
        baseCurrency: CurrencyCode,
        fxIds: Iterator<StableId>,
    ): FrozenAmountEvidence {
        val account = references.account(UserAccountId(draft.accountId)) ?: abort(FinanceDataError.CorruptData)
        return frozen(role, draft.accountMinor, draft.baseMinor, account.account.currency, baseCurrency, draft.accountToBaseEvidence, account.account.id, fxIds)
    }

    private fun frozenComponent(
        role: AmountRole,
        draft: LoanComponentAmountDraft,
        currency: CurrencyCode,
        baseCurrency: CurrencyCode,
        fxIds: Iterator<StableId>,
    ): FrozenAmountEvidence = frozen(role, draft.accountMinor, draft.baseMinor, currency, baseCurrency, draft.accountToBaseEvidence, null, fxIds)

    private fun frozen(
        role: AmountRole,
        accountMinor: Long,
        baseMinor: Long,
        currency: CurrencyCode,
        baseCurrency: CurrencyCode,
        fx: app.ledger.core.money.FxEvidence?,
        accountId: UserAccountId?,
        fxIds: Iterator<StableId>,
    ): FrozenAmountEvidence {
        val account = positive(accountMinor, currency)
        val base = positive(baseMinor, baseCurrency)
        val conversion = if (currency == baseCurrency) {
            if (accountMinor != baseMinor || fx != null) abort(DomainViolation.InvalidField("loan.baseAmount"))
            null
        } else {
            val evidence = fx ?: abort(DomainViolation.InvalidField("loan.fxEvidence"))
            FrozenFxConversion.create(
                FxRateSnapshotId(fxIds.nextOrAbort()),
                account,
                base,
                evidence,
                currencyCatalog.require(currency).valueOrAbort(),
                currencyCatalog.require(baseCurrency).valueOrAbort(),
                false,
            ).valueOrAbort()
        }
        return FrozenAmountEvidence.create(AmountEvidenceKey(role, 0), account, account, base, accountId, null, conversion).valueOrAbort()
    }

    private fun accountAmount(draft: SpecializedAccountAmountDraft, references: PlanningReferenceData): AccountAmount {
        val account = references.account(UserAccountId(draft.accountId))?.account ?: abort(FinanceDataError.CorruptData)
        return AccountAmount.create(account, Money(draft.accountMinor, account.currency)).valueOrAbort()
    }

    private fun operationSnapshot(
        book: Book,
        request: SaveLoanContractRequest,
        currentCommit: BookCommitId?,
        accounting: AccountingPlanningContext?,
    ) = PlanningSnapshot(
        book, null, null, emptyList(), emptySet(), emptyList(), null, emptyList(), accountingContext = accounting,
        operationContext = PlanningOperationContext(
            BookCommitId(request.ids.commitId),
            request.changedAt,
            DeviceInstanceId(request.ids.deviceInstanceId),
        ),
        loanContractLastCommitId = currentCommit,
    )

    private fun financialSnapshot(
        book: Book,
        commitId: StableId,
        deviceId: StableId,
        transactionId: StableId,
        revisionId: StableId,
        factIds: List<StableId>,
        references: PlanningReferenceData,
        evidence: List<FrozenAmountEvidence>,
        changedAt: Instant,
    ) = PlanningSnapshot(
        book, null, null, emptyList(), emptySet(), emptyList(), null, emptyList(),
        AccountingPlanningContext(
            PlanningIdentitySet(
                TransactionId(transactionId),
                TransactionRevisionId(revisionId),
                BookCommitId(commitId),
                factIds,
            ),
            changedAt,
            DeviceInstanceId(deviceId),
            references,
            evidence,
            null,
        ),
    )

    private fun transactionContext(context: app.ledger.finance.application.LoanTransactionContext, sourceId: StableId?) = TransactionContextInput(
        EffectiveTime.fromInstant(context.occurredAt, context.zoneId),
        context.localDate,
        null,
        null,
        null,
        null,
        null,
        context.note,
        context.amountExpression,
        TransactionSource.MANUAL,
        sourceId,
        null,
        emptyList(),
    )

    private suspend fun coordinate(
        database: LedgerDatabase,
        command: FinancialCommand,
        snapshot: PlanningSnapshot,
    ): DomainResult<CommandReceipt> {
        val repository: AtomicFinancialCommitRepository = RoomFinancialCommitRepository(database)
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

    private suspend fun <T> withDatabase(
        bookId: StableId,
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

    private fun positive(minor: Long, currency: CurrencyCode): PositiveMoney = PositiveMoney.from(Money(minor, currency)).valueOrAbort()
    private fun currency(value: String): CurrencyCode = CurrencyCode.parse(value).valueOrAbort()
    private fun storageDate(value: Int): LocalDate = LocalDate.of(value / 10_000, value / 100 % 100, value % 100)
    private fun zeroHash(): Hash256 = Hash256.fromBytes(ByteArray(Hash256.BYTE_COUNT)).valueOrAbort()
    private fun Iterator<StableId>.nextOrAbort(): StableId = if (hasNext()) next() else abort(FinanceDataError.CorruptData)
    private inline fun <T> Iterable<T>.sumChecked(value: (T) -> Long): Long = fold(0L) { total, item -> Math.addExact(total, value(item)) }

    private data class LoadedLoanTranche(
        val contract: LoanContractView,
        val tranche: LoanTrancheView,
        val terms: LoanTermsRevision,
        val schedule: LoanScheduleRevision,
        val remainingPrincipalMinor: Long,
    )

    private data class ContractHeader(
        val id: StableId,
        val accountId: StableId,
        val name: String,
        val lender: String?,
        val currency: CurrencyCode,
        val disbursementDate: LocalDate,
        val status: Int,
        val lastCommitId: StableId,
    )

    private data class TrancheHeader(
        val name: String,
        val ledgerAccountId: StableId,
        val originalPrincipal: Long,
        val status: Int,
        val termsId: StableId,
        val termsRevision: Int,
        val repaymentMethod: Int,
        val rateType: Int,
        val frequency: Int,
        val roundingMode: Int,
        val prepayment: Long,
        val scheduleId: StableId,
        val scheduleRevision: Int,
        val historyCount: Int,
        val remaining: Long,
        val principalPaid: Long,
        val interestPaid: Long,
        val feePaid: Long,
        val penaltyPaid: Long,
    ) {
        companion object {
            fun from(cursor: android.database.Cursor) = TrancheHeader(
                cursor.string("name"), cursor.stableId("ledger_uid"), cursor.long("original_principal_minor"), cursor.int("status"), cursor.stableId("terms_uid"),
                cursor.int("revision_no"), cursor.int("repayment_method"), cursor.int("rate_type"), cursor.int("payment_frequency"),
                cursor.int("rounding_mode"), cursor.long("prepayment_policy"), cursor.stableId("schedule_uid"), cursor.int("schedule_no"),
                cursor.int("history_count"), cursor.long("remaining"), cursor.long("principal_paid"), cursor.long("interest_paid"),
                cursor.long("fee_paid"), cursor.long("penalty_paid"),
            )
        }
    }

    private class LoanWriteGate : LedgerWriteGate {
        private val mutex = Mutex()
        override suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock { block() }
    }

    private companion object {
        const val LOAN_ACCOUNT_TYPE = 3
        val PAYMENT_ACCOUNT_TYPES = setOf(0, 1)
    }
}

private fun android.database.Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
private fun android.database.Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
private fun android.database.Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
