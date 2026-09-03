@file:Suppress("LongMethod", "LongParameterList", "MagicNumber", "MaxLineLength")

package app.ledger.finance.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.money.CurrencyCode
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.finance.application.AccountDraft
import app.ledger.finance.application.ApplyLoanSimulationRequest
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitialCategoryCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.application.LoanComponentAllocationDraft
import app.ledger.finance.application.LoanComponentAmountDraft
import app.ledger.finance.application.LoanMutationIds
import app.ledger.finance.application.LoanPaymentAmountsDraft
import app.ledger.finance.application.LoanSimulationRequest
import app.ledger.finance.application.LoanTermsDraft
import app.ledger.finance.application.LoanTrancheDraft
import app.ledger.finance.application.LoanTrancheMutationIds
import app.ledger.finance.application.LoanTransactionContext
import app.ledger.finance.application.LoanTransactionIds
import app.ledger.finance.application.RecordLoanDisbursementRequest
import app.ledger.finance.application.RecordLoanPaymentRequest
import app.ledger.finance.application.ReferenceMutation
import app.ledger.finance.application.ReferenceMutationCommand
import app.ledger.finance.application.ReferenceMutationIds
import app.ledger.finance.application.SaveLoanContractRequest
import app.ledger.finance.application.SpecializedAccountAmountDraft
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.InterestRate
import app.ledger.finance.domain.LoanPaymentComponent
import app.ledger.finance.domain.LoanPrepaymentPolicy
import app.ledger.finance.domain.LoanRatePeriod
import app.ledger.finance.domain.LoanRateType
import app.ledger.finance.domain.LoanRepaymentMethod
import app.ledger.finance.domain.LoanSimulationScenario
import app.ledger.finance.domain.LoanStatus
import app.ledger.finance.domain.PaymentFrequency
import app.ledger.finance.domain.PrepaymentRecalculationStrategy
import app.ledger.finance.domain.ScheduleRevisionReason
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.UserAccountType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LoanApplicationPortDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var databaseAccess: DeviceTestLedgerDatabaseAccess
    private lateinit var references: SecureRoomReferenceDataManagementPort
    private lateinit var loans: SecureRoomLoanApplicationPort

    @Before
    fun prepare() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        keys.destroyLocal(BOOK_ID)
        databaseAccess = DeviceTestLedgerDatabaseAccess(context, keys)
        SecureRoomLedgerInitializationPort(context, keys).apply {
            initialize(
                InitializeLedgerCommand(
                    LedgerGenesisIds(BOOK_ID, id(2), id(3), id(4), SystemLedgerCode.entries.mapIndexed { index, code -> code to id(100L + index) }.toMap()),
                    JPY,
                    ZONE,
                    Instant.parse("2026-08-01T00:00:00Z"),
                ),
            ).success()
            createFirstAccount(
                BOOK_ID,
                InitialAccountCommand(BANK_ID, BANK_LEDGER_ID, id(202), id(203), id(204), Instant.parse("2026-08-01T00:00:01Z"), UserAccountType.BANK, "Bank", JPY, "account", 0xff006c4c.toInt()),
            ).success()
            createFirstCategory(
                BOOK_ID,
                InitialCategoryCommand(CATEGORY_ID, id(211), id(212), id(213), Instant.parse("2026-08-01T00:00:02Z"), CategoryDirection.EXPENSE, "Finance", "finance", StatisticalNature.NON_CONSUMPTION_EXPENSE, "record", 0xff006c4c.toInt()),
            ).success()
        }
        references = SecureRoomReferenceDataManagementPort(databaseAccess)
        createLoanAccount(LOAN_ACCOUNT_ID, LOAN_LEDGER_ID, 3L, 250L, "Combined loan", 0)
        createLoanAccount(SECOND_LOAN_ACCOUNT_ID, SECOND_LOAN_LEDGER_ID, 4L, 350L, "Tranche B", 1)
        loans = SecureRoomLoanApplicationPort(databaseAccess)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys.destroyLocal(BOOK_ID)
    }

    @Test
    fun combinationLoanPaymentsSimulationsAndRebuildAreAtomicVersionedAndForecastOnly() = runBlocking {
        val create = contractRequest(1_000L, null, emptyList(), 1, 1, 100_000L, 50_000L)
        val localBeforePreview = scalar("SELECT local_revision FROM book")
        val previews = loans.preview(create).success()
        assertEquals(localBeforePreview, scalar("SELECT local_revision FROM book"))
        assertEquals(150_000L, previews.sumOf { schedule -> schedule.items.sumOf { it.principalMinor } })
        val created = loans.saveContract(create).success()
        assertEquals(created, loans.saveContract(create).success())
        var snapshot = loans.snapshot(BOOK_ID).success()
        var contract = snapshot.contracts.single()
        assertEquals(2, contract.tranches.size)
        assertEquals(150_000L, contract.originalPrincipalMinor)
        assertEquals(150_000L, contract.remainingPrincipalMinor)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM business_transaction"))
        assertTrue(scalar("SELECT COUNT(*) FROM loan_future_cashflow_projection") > 0L)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM current_transaction_projection"))

        val disbursement = disbursementRequest(2_000L)
        val disbursed = loans.recordDisbursement(disbursement).success()
        assertEquals(disbursed, loans.recordDisbursement(disbursement).success())
        assertEquals(1L, scalar("SELECT COUNT(*) FROM business_transaction"))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM loan_effect WHERE kind=0"))
        assertEquals(0L, unbalancedJournals())

        val paymentMutation = contractRequest(
            3_000L,
            contract.lastCommitId,
            contract.tranches.map { it.currentTermsRevisionId },
            2,
            2,
            90_000L,
            45_000L,
        )
        val payment = paymentRequest(paymentMutation, 3_500L)
        val paid = loans.recordPayment(payment).success()
        assertEquals(paid, loans.recordPayment(payment).success())
        snapshot = loans.snapshot(BOOK_ID).success()
        contract = snapshot.contracts.single()
        assertEquals(135_000L, contract.remainingPrincipalMinor)
        assertEquals(15_000L, contract.tranches.sumOf { it.paidPrincipalMinor })
        assertEquals(1_000L, contract.tranches.sumOf { it.paidInterestMinor })
        assertEquals(100L, contract.tranches.sumOf { it.paidFeeMinor })
        assertEquals(50L, contract.tranches.sumOf { it.paidPenaltyMinor })
        assertEquals(4L, scalar("SELECT COUNT(*) FROM economic_effect"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM economic_effect WHERE is_consumption<>0"))
        assertEquals(0L, unbalancedJournals())
        assertEquals(2L, scalar("SELECT COUNT(*) FROM loan_schedule_revision WHERE tranche_id=(SELECT id FROM loan_tranche WHERE uid=x'${LOAN_TRANCHE_ID.hex()}')"))
        assertEquals(scalar("SELECT local_revision FROM book"), scalar("SELECT MIN(as_of_local_revision) FROM loan_progress_projection"))
        assertEquals(scalar("SELECT local_revision FROM book"), scalar("SELECT MIN(as_of_local_revision) FROM loan_future_cashflow_projection"))

        val stale = payment.copy(
            mutation = contractRequest(
                4_000L,
                create.ids.commitId,
                contract.tranches.map { it.currentTermsRevisionId },
                3,
                3,
                85_000L,
                45_000L,
            ),
        )
        val staleResult = loans.recordPayment(stale)
        assertEquals(DomainViolation.StaleExpectedRevision, (staleResult as DomainResult.Failure).error)
        assertEquals(2L, scalar("SELECT COUNT(*) FROM business_transaction"))

        val localBeforeSimulation = scalar("SELECT local_revision FROM book")
        val simulationRequest = simulationRequest(contract, 5_000L)
        val simulation = loans.simulate(simulationRequest).success()
        assertEquals(localBeforeSimulation, scalar("SELECT local_revision FROM book"))
        assertEquals(5_000L, simulation.prepaymentPrincipalMinor)
        assertTrue(simulation.afterSummary.paymentCount < simulation.before.paymentCount)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM loan_simulation"))
        assertTrue(scalar("SELECT COUNT(*) FROM loan_simulation_item") > 0L)
        assertEquals(2L, scalar("SELECT COUNT(*) FROM business_transaction"))

        val applyMutation = contractRequest(
            6_000L,
            contract.lastCommitId,
            contract.tranches.map { it.currentTermsRevisionId },
            3,
            3,
            85_000L,
            45_000L,
        )
        val applied = loans.applySimulation(
            ApplyLoanSimulationRequest(
                simulationRequest,
                applyMutation,
                transactionIds(6_500L),
                context(Instant.parse("2026-09-02T03:00:00Z"), LocalDate.of(2026, 9, 2)),
                SpecializedAccountAmountDraft(BANK_ID, 5_000L, 5_000L, null),
                LoanComponentAmountDraft(5_000L, 5_000L, null),
                null,
            ),
        ).success()
        assertEquals(
            applied,
            loans.applySimulation(
                ApplyLoanSimulationRequest(
                    simulationRequest,
                    applyMutation,
                    transactionIds(6_500L),
                    context(Instant.parse("2026-09-02T03:00:00Z"), LocalDate.of(2026, 9, 2)),
                    SpecializedAccountAmountDraft(BANK_ID, 5_000L, 5_000L, null),
                    LoanComponentAmountDraft(5_000L, 5_000L, null),
                    null,
                ),
            ).success(),
        )
        contract = loans.snapshot(BOOK_ID).success().contracts.single()
        assertEquals(130_000L, contract.remainingPrincipalMinor)
        assertEquals(3L, scalar("SELECT COUNT(*) FROM business_transaction"))
        assertEquals(3L, scalar("SELECT MAX(revision_no) FROM loan_schedule_revision WHERE tranche_id=(SELECT id FROM loan_tranche WHERE uid=x'${LOAN_TRANCHE_ID.hex()}')"))
        assertEquals(0L, unbalancedJournals())
        rebuildAndAudit()
        assertEquals("ok", textScalar("PRAGMA integrity_check"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM pragma_foreign_key_check"))
    }

    private suspend fun createLoanAccount(
        accountId: StableId,
        ledgerId: StableId,
        revision: Long,
        seed: Long,
        name: String,
        order: Int,
    ) {
        references.mutate(
            ReferenceMutationCommand(
                ReferenceMutationIds(BOOK_ID, revision, id(seed), List(16) { id(seed + 10 + it) }, id(seed + 30), Instant.ofEpochMilli(seed)),
                ReferenceMutation.SaveAccount(
                    AccountDraft(accountId, ledgerId, null, UserAccountType.LOAN, name, JPY, null, null, null, null, "loan", 0xff4f6358.toInt(), order),
                ),
            ),
        ).success()
    }

    private fun contractRequest(
        seed: Long,
        expectedCommit: StableId?,
        expectedTerms: List<StableId>,
        termsRevision: Int,
        scheduleRevision: Int,
        firstPrincipal: Long,
        secondPrincipal: Long,
    ): SaveLoanContractRequest {
        val firstIds = trancheIds(seed + 100, LOAN_TRANCHE_ID, 12)
        val secondIds = trancheIds(seed + 300, SECOND_LOAN_TRANCHE_ID, 10)
        val ids = LoanMutationIds(BOOK_ID, CommandId(id(seed)), id(seed + 1), id(seed + 2), LOAN_CONTRACT_ID, listOf(firstIds, secondIds))
        return SaveLoanContractRequest(
            ids,
            LOAN_ACCOUNT_ID,
            "Home combination loan",
            "Local lender",
            JPY,
            LocalDate.of(2026, 8, 1),
            LoanStatus.ACTIVE,
            expectedCommit,
            listOf(
                trancheDraft(firstIds, LOAN_LEDGER_ID, "Fixed tranche", 100_000L, firstPrincipal, expectedTerms.getOrNull(0), termsRevision, scheduleRevision, LoanRepaymentMethod.EQUAL_PAYMENT, 12, "0.036"),
                trancheDraft(secondIds, SECOND_LOAN_LEDGER_ID, "Floating tranche", 50_000L, secondPrincipal, expectedTerms.getOrNull(1), termsRevision, scheduleRevision, LoanRepaymentMethod.EQUAL_PRINCIPAL, 10, "0.048"),
            ),
            Instant.ofEpochMilli(seed),
        )
    }

    private fun trancheDraft(
        ids: LoanTrancheMutationIds,
        ledgerId: StableId,
        name: String,
        original: Long,
        current: Long,
        expectedTerms: StableId?,
        termsRevision: Int,
        scheduleRevision: Int,
        method: LoanRepaymentMethod,
        count: Int,
        rate: String,
    ) = LoanTrancheDraft(
        ids,
        ledgerId,
        name,
        original,
        current,
        LoanStatus.ACTIVE,
        expectedTerms,
        termsRevision,
        scheduleRevision,
        if (termsRevision == 1) ScheduleRevisionReason.INITIAL else ScheduleRevisionReason.PREPAYMENT,
        LoanTermsDraft(
            method,
            LoanRateType.FIXED,
            PaymentFrequency.MONTHLY,
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2027, 12, 31),
            count,
            LocalDate.of(2026, 8, 31),
            RoundingMode.HALF_EVEN,
            LoanPrepaymentPolicy.ALLOWED,
            PrepaymentRecalculationStrategy.SHORTEN_TERM,
            null,
            listOf(LoanRatePeriod(LocalDate.of(2026, 8, 1), null, interest(rate), null, null)),
        ),
    )

    private fun disbursementRequest(seed: Long) = RecordLoanDisbursementRequest(
        mutationIdsOnly(seed),
        transactionIds(seed + 500),
        context(Instant.parse("2026-08-02T03:00:00Z"), LocalDate.of(2026, 8, 2)),
        SpecializedAccountAmountDraft(BANK_ID, 150_000L, 150_000L, null),
        LoanComponentAmountDraft(150_000L, 150_000L, null),
        listOf(
            LoanComponentAllocationDraft(LOAN_TRANCHE_ID, null, LoanPaymentComponent.PRINCIPAL, 100_000L, 100_000L),
            LoanComponentAllocationDraft(SECOND_LOAN_TRANCHE_ID, null, LoanPaymentComponent.PRINCIPAL, 50_000L, 50_000L),
        ),
    )

    private fun paymentRequest(mutation: SaveLoanContractRequest, seed: Long) = RecordLoanPaymentRequest(
        mutation,
        transactionIds(seed),
        context(Instant.parse("2026-09-01T03:00:00Z"), LocalDate.of(2026, 9, 1)),
        SpecializedAccountAmountDraft(BANK_ID, 16_150L, 16_150L, null),
        LoanPaymentAmountsDraft(
            LoanComponentAmountDraft(15_000L, 15_000L, null),
            LoanComponentAmountDraft(1_000L, 1_000L, null),
            LoanComponentAmountDraft(100L, 100L, null),
            LoanComponentAmountDraft(50L, 50L, null),
        ),
        listOf(
            LoanComponentAllocationDraft(LOAN_TRANCHE_ID, null, LoanPaymentComponent.PRINCIPAL, 10_000L, 10_000L),
            LoanComponentAllocationDraft(SECOND_LOAN_TRANCHE_ID, null, LoanPaymentComponent.PRINCIPAL, 5_000L, 5_000L),
            LoanComponentAllocationDraft(LOAN_TRANCHE_ID, null, LoanPaymentComponent.INTEREST, 700L, 700L),
            LoanComponentAllocationDraft(SECOND_LOAN_TRANCHE_ID, null, LoanPaymentComponent.INTEREST, 300L, 300L),
            LoanComponentAllocationDraft(LOAN_TRANCHE_ID, null, LoanPaymentComponent.FEE, 100L, 100L),
            LoanComponentAllocationDraft(SECOND_LOAN_TRANCHE_ID, null, LoanPaymentComponent.PENALTY, 50L, 50L),
        ),
    )

    private fun simulationRequest(contract: app.ledger.finance.application.LoanContractView, seed: Long): LoanSimulationRequest {
        val tranche = contract.tranches.first()
        val ids = trancheIds(seed + 100, LOAN_TRANCHE_ID, 8)
        return LoanSimulationRequest(
            id(seed),
            BOOK_ID,
            LOAN_CONTRACT_ID,
            LOAN_TRANCHE_ID,
            LoanSimulationScenario.PartialPrepayment(5_000L, PrepaymentRecalculationStrategy.SHORTEN_TERM, LocalDate.of(2026, 9, 2)),
            ids,
            LoanTermsDraft(
                tranche.repaymentMethod,
                tranche.rateType,
                tranche.paymentFrequency,
                LocalDate.of(2026, 9, 2),
                LocalDate.of(2027, 4, 30),
                8,
                LocalDate.of(2026, 9, 30),
                tranche.roundingMode,
                tranche.prepaymentPolicy,
                PrepaymentRecalculationStrategy.SHORTEN_TERM,
                tranche.penaltyRate,
                listOf(LoanRatePeriod(LocalDate.of(2026, 9, 2), null, interest("0.036"), null, null)),
            ),
            Instant.ofEpochMilli(seed),
        )
    }

    private fun mutationIdsOnly(seed: Long): LoanMutationIds = LoanMutationIds(
        BOOK_ID,
        CommandId(id(seed)),
        id(seed + 1),
        id(seed + 2),
        LOAN_CONTRACT_ID,
        listOf(trancheIds(seed + 100, id(seed + 90), 1)),
    )

    private fun trancheIds(seed: Long, trancheId: StableId, count: Int) = LoanTrancheMutationIds(
        trancheId,
        id(seed - 1),
        id(seed),
        List(count) { id(seed + 10 + it) },
    )

    private fun transactionIds(seed: Long) = LoanTransactionIds(
        id(seed),
        id(seed + 1),
        (seed + 10..seed + 320).map(::id),
        emptyList(),
    )

    private fun context(at: Instant, date: LocalDate) = LoanTransactionContext(at, ZONE, date, null, null)

    private fun rebuildAndAudit() = keys.open(BOOK_ID).use { opened ->
        val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
        try {
            database.inLedgerTransaction { db ->
                val book = RoomBookRepository.mapCurrent(db)
                val engine = RoomProjectionEngine()
                val before = engine.canonicalTableHashes(db)
                engine.rebuildAll(db, book.localRevision.value, book.valuationRevision.value, LocalDate.of(2026, 9, 2).toStorageInt())
                assertEquals(before, engine.canonicalTableHashes(db))
                assertTrue(engine.mismatchedFamilies(db, book.localRevision.value, book.valuationRevision.value).isEmpty())
            }
        } finally {
            database.close()
        }
    }

    private fun unbalancedJournals(): Long = scalar("SELECT COUNT(*) FROM (SELECT journal_entry_id FROM posting GROUP BY journal_entry_id HAVING SUM(CASE side WHEN 0 THEN base_amount_minor ELSE -base_amount_minor END)<>0)")
    private fun scalar(sql: String): Long = query { db ->
        db.query(sql).use {
            it.moveToFirst()
            it.getLong(0)
        }
    }
    private fun textScalar(sql: String): String = query { db ->
        db.query(sql).use {
            it.moveToFirst()
            it.getString(0)
        }
    }
    private fun <T> query(block: (androidx.sqlite.db.SupportSQLiteDatabase) -> T): T = keys.open(BOOK_ID).use { opened ->
        val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
        try {
            database.readLedger(block)
        } finally {
            database.close()
        }
    }

    private fun interest(value: String): InterestRate = InterestRate.of(BigDecimal(value)).success()
    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x2121L, value))
    private fun StableId.hex(): String = bytes.joinToString("") { "%02x".format(it) }
    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.toString())
    }

    private companion object {
        val BOOK_ID = StableId.fromUuid(UUID(0x2121L, 1))
        val BANK_ID = StableId.fromUuid(UUID(0x2121L, 200))
        val BANK_LEDGER_ID = StableId.fromUuid(UUID(0x2121L, 201))
        val CATEGORY_ID = StableId.fromUuid(UUID(0x2121L, 210))
        val LOAN_ACCOUNT_ID = StableId.fromUuid(UUID(0x2121L, 300))
        val LOAN_LEDGER_ID = StableId.fromUuid(UUID(0x2121L, 301))
        val SECOND_LOAN_ACCOUNT_ID = StableId.fromUuid(UUID(0x2121L, 400))
        val SECOND_LOAN_LEDGER_ID = StableId.fromUuid(UUID(0x2121L, 401))
        val LOAN_CONTRACT_ID = StableId.fromUuid(UUID(0x2121L, 500))
        val LOAN_TRANCHE_ID = StableId.fromUuid(UUID(0x2121L, 501))
        val SECOND_LOAN_TRANCHE_ID = StableId.fromUuid(UUID(0x2121L, 502))
        val JPY: CurrencyCode = requireNotNull(CurrencyCode.parse("JPY").getOrNull())
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
