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
import app.ledger.finance.application.CreditMutationIds
import app.ledger.finance.application.CreditPaymentContext
import app.ledger.finance.application.CreditStatementMutationIds
import app.ledger.finance.application.CreditTransactionMutationIds
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitialCategoryCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.application.OrdinaryAmountDraft
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryTransactionWriteIds
import app.ledger.finance.application.OrdinaryTransactionWriteRequest
import app.ledger.finance.application.RecordCreditPaymentRequest
import app.ledger.finance.application.ReferenceMutation
import app.ledger.finance.application.ReferenceMutationCommand
import app.ledger.finance.application.ReferenceMutationIds
import app.ledger.finance.application.SaveCreditProfileRequest
import app.ledger.finance.application.SaveCreditStatementRequest
import app.ledger.finance.application.SpecializedAccountAmountDraft
import app.ledger.finance.domain.AutoGenerationMode
import app.ledger.finance.domain.AutoPaymentIneligibility
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.DueDateRule
import app.ledger.finance.domain.MissingDayPolicy
import app.ledger.finance.domain.StatementDateRule
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountType
import app.ledger.finance.domain.WeekendAdjustment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CreditApplicationPortDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var references: SecureRoomReferenceDataManagementPort
    private lateinit var ordinary: SecureRoomOrdinaryTransactionEntryPort
    private lateinit var credit: SecureRoomCreditApplicationPort
    private var seed = 19_000L

    @Before
    fun prepare() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        keys.destroyLocal(BOOK_ID)
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
                InitialAccountCommand(BANK_ID, id(201), id(202), id(203), id(204), Instant.parse("2026-08-01T00:00:01Z"), UserAccountType.BANK, "Bank", JPY, "account", 0xff006c4c.toInt()),
            ).success()
            createFirstCategory(
                BOOK_ID,
                InitialCategoryCommand(CATEGORY_ID, id(211), id(212), id(213), Instant.parse("2026-08-01T00:00:02Z"), CategoryDirection.EXPENSE, "Food", "food", StatisticalNature.CONSUMPTION_EXPENSE, "record", 0xff006c4c.toInt()),
            ).success()
        }
        references = SecureRoomReferenceDataManagementPort(context, keys)
        ordinary = SecureRoomOrdinaryTransactionEntryPort(context, keys, references)
        credit = SecureRoomCreditApplicationPort(context, keys)
        references.mutate(
            ReferenceMutationCommand(
                ReferenceMutationIds(BOOK_ID, 3, nextId(), List(16) { nextId() }, nextId(), Instant.parse("2026-08-01T00:00:03Z")),
                ReferenceMutation.SaveAccount(
                    AccountDraft(CREDIT_ID, nextId(), null, UserAccountType.CREDIT, "Credit", JPY, null, null, null, null, "credit", 0xff006c4c.toInt(), 1),
                ),
            ),
        ).success()
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys.destroyLocal(BOOK_ID)
    }

    @Test
    fun officialEstimatedStatementsPaymentsIdempotencyAndProjectionsRemainAtomicAndRebuildable() = runBlocking {
        val profileRequest = profileRequest()
        val profileReceipt = credit.saveProfile(profileRequest).success()
        assertEquals(profileReceipt, credit.saveProfile(profileRequest).success())

        val expenseRequest = expenseRequest()
        val expenseReceipt = ordinary.submit(expenseRequest).success()
        assertEquals(expenseReceipt, ordinary.submit(expenseRequest).success())
        var snapshot = credit.snapshot(BOOK_ID).success()
        var account = snapshot.accounts.single()
        assertEquals(600L, account.debtMinor)
        assertEquals(119_400L, account.availableLimitMinor)
        assertEquals(1, account.statements.size)
        val estimated = account.statements.single()
        assertEquals(600L, estimated.estimatedAmountMinor)
        assertEquals(null, estimated.officialAmountMinor)
        assertEquals(LocalDate.of(2026, 7, 26), estimated.cycleStart)
        assertEquals(LocalDate.of(2026, 8, 25), estimated.cycleEnd)
        assertEquals(LocalDate.of(2026, 9, 10), estimated.dueDate)

        val journalsBeforeOfficial = scalar("SELECT COUNT(*) FROM journal_entry")
        val officialRequest = officialRequest(estimated.id, estimated.revisionId, estimated.revisionNumber + 1, 650L)
        val officialReceipt = credit.saveStatement(officialRequest).success()
        assertEquals(officialReceipt, credit.saveStatement(officialRequest).success())
        assertEquals(journalsBeforeOfficial, scalar("SELECT COUNT(*) FROM journal_entry"))
        snapshot = credit.snapshot(BOOK_ID).success()
        account = snapshot.accounts.single()
        val official = account.statements.single()
        assertEquals(650L, official.officialAmountMinor)
        assertEquals(50L, official.differenceMinor)
        assertEquals(600L, account.debtMinor)
        assertEquals(650L, official.remainingAmountMinor)

        val stale = credit.saveStatement(officialRequest(official.id, estimated.revisionId, official.revisionNumber + 1, 640L, seedOffset = 1_000))
        assertTrue(stale is DomainResult.Failure)
        assertEquals(DomainViolation.StaleExpectedRevision, (stale as DomainResult.Failure).error)

        val proposal = credit.proposeAutoPayment(BOOK_ID, official.id, OCCURRENCE_ID).success()
        assertTrue(proposal.eligibility.eligible)
        assertTrue(proposal.bookkeepingDisclaimerRequired)
        val paymentRequest = paymentRequest(600L, OCCURRENCE_ID)
        val paymentReceipt = credit.recordPayment(paymentRequest).success()
        assertEquals(paymentReceipt, credit.recordPayment(paymentRequest).success())
        snapshot = credit.snapshot(BOOK_ID).success()
        account = snapshot.accounts.single()
        assertEquals(0L, account.debtMinor)
        assertEquals(0L, account.positiveBalanceMinor)
        assertEquals(50L, account.statements.single().remainingAmountMinor)
        assertEquals(100_000L, account.availableLimitMinor)

        val factsBeforeRejected = scalar("SELECT COUNT(*) FROM journal_entry")
        val revisionBeforeRejected = scalar("SELECT local_revision FROM book")
        val rejected = credit.recordPayment(paymentRequest(1L, null, seedOffset = 2_000))
        assertTrue(rejected is DomainResult.Failure)
        assertEquals(factsBeforeRejected, scalar("SELECT COUNT(*) FROM journal_entry"))
        assertEquals(revisionBeforeRejected, scalar("SELECT local_revision FROM book"))

        val duplicateProposal = credit.proposeAutoPayment(BOOK_ID, official.id, OCCURRENCE_ID).success()
        assertFalse(duplicateProposal.eligibility.eligible)
        assertTrue(AutoPaymentIneligibility.DUPLICATE_OCCURRENCE in duplicateProposal.eligibility.reasons)
        assertTrue(AutoPaymentIneligibility.NO_REMAINING_AMOUNT in duplicateProposal.eligibility.reasons)

        assertEquals(1L, scalar("SELECT COUNT(*) FROM economic_effect WHERE nature=1"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM economic_effect"))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM journal_entry"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM (SELECT journal_entry_id FROM posting GROUP BY journal_entry_id HAVING SUM(CASE side WHEN 0 THEN base_amount_minor ELSE -base_amount_minor END)<>0)"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM statement_effect WHERE kind=0"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM statement_effect WHERE kind=2"))
        assertEquals(revisionBeforeRejected, scalar("SELECT as_of_local_revision FROM credit_account_projection"))
        assertEquals(revisionBeforeRejected, scalar("SELECT as_of_local_revision FROM credit_statement_projection"))

        rebuildAndAuditCredit()
        val rebuilt = credit.snapshot(BOOK_ID).success().accounts.single()
        assertEquals(account.copy(statements = emptyList()), rebuilt.copy(statements = emptyList()))
        assertEquals(account.statements.single(), rebuilt.statements.single())
        assertEquals("ok", textScalar("PRAGMA integrity_check"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM pragma_foreign_key_check"))
    }

    private fun profileRequest(): SaveCreditProfileRequest {
        val mutation = mutationIds(-1_000)
        return SaveCreditProfileRequest(
            mutation,
            CREDIT_ID,
            null,
            StatementDateRule.DayOfMonth(25, MissingDayPolicy.MOVE_TO_MONTH_END),
            DueDateRule.FixedDay(10, MissingDayPolicy.MOVE_TO_MONTH_END),
            ZONE,
            100_000,
            20_000,
            LocalDate.of(2026, 8, 31),
            BANK_ID,
            AutoGenerationMode.FORMAL_TRANSACTION,
            WeekendAdjustment.NEXT_BUSINESS_DAY,
            LocalDate.of(2026, 8, 1),
            Instant.parse("2026-08-01T00:00:04Z"),
        )
    }

    private fun expenseRequest(): OrdinaryTransactionWriteRequest = OrdinaryTransactionWriteRequest(
        OrdinaryTransactionWriteIds(BOOK_ID, id(30_000), EXPENSE_ID, id(30_001), id(30_002), id(30_003), (30_010L..30_320L).map(::id), emptyList()),
        null,
        OrdinaryDirection.EXPENSE,
        CATEGORY_ID,
        OrdinaryAmountDraft("600", 600, JPY, 600, 600),
        CREDIT_ID,
        null,
        null,
        Instant.parse("2026-08-04T03:00:00Z"),
        ZONE,
        LocalDate.of(2026, 8, 4),
        null,
        null,
        null,
        emptyList(),
        null,
        null,
        "credit purchase",
        emptyList(),
        TransactionSource.MANUAL,
        null,
        Instant.parse("2026-08-04T03:00:01Z"),
    )

    private fun officialRequest(
        statementId: StableId,
        expectedRevisionId: StableId,
        revisionNumber: Int,
        amount: Long,
        seedOffset: Long = 0,
    ): SaveCreditStatementRequest = SaveCreditStatementRequest(
        CreditStatementMutationIds(mutationIds(seedOffset), statementId, id(40_100 + seedOffset)),
        CREDIT_ID,
        expectedRevisionId,
        revisionNumber,
        LocalDate.of(2026, 7, 26),
        LocalDate.of(2026, 8, 25),
        LocalDate.of(2026, 9, 10),
        600,
        amount,
        Instant.parse("2026-08-26T00:00:00Z").plusMillis(seedOffset),
        true,
        Instant.parse("2026-08-26T00:00:01Z").plusMillis(seedOffset),
    )

    private fun paymentRequest(amount: Long, occurrenceId: StableId?, seedOffset: Long = 0): RecordCreditPaymentRequest = RecordCreditPaymentRequest(
        CreditTransactionMutationIds(
            BOOK_ID,
            CommandId(id(50_000 + seedOffset)),
            id(50_001 + seedOffset),
            id(50_002 + seedOffset),
            id(50_003 + seedOffset),
            id(50_004 + seedOffset),
            (50_010L + seedOffset..50_310L + seedOffset).map(::id),
            emptyList(),
        ),
        CreditPaymentContext(Instant.parse("2026-09-10T03:00:00Z"), ZONE, LocalDate.of(2026, 9, 10), amount.toString(), null, Instant.parse("2026-09-10T03:00:01Z")),
        SpecializedAccountAmountDraft(BANK_ID, amount, amount, null),
        SpecializedAccountAmountDraft(CREDIT_ID, amount, amount, null),
        app.ledger.finance.domain.CreditPaymentSelection.EarliestUnpaid,
        occurrenceId,
        AutoGenerationMode.FORMAL_TRANSACTION,
    )

    private fun mutationIds(offset: Long = 0): CreditMutationIds = CreditMutationIds(
        BOOK_ID,
        CommandId(id(40_000 + offset)),
        id(40_001 + offset),
        id(40_002 + offset),
    )

    private fun rebuildAndAuditCredit() {
        keys.open(BOOK_ID).use { opened ->
            val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
            try {
                database.inLedgerTransaction { db ->
                    val book = RoomBookRepository.mapCurrent(db)
                    val engine = RoomProjectionEngine()
                    val before = engine.canonicalHash(db)
                    engine.rebuildAll(db, book.localRevision.value, book.valuationRevision.value, LocalDate.of(2026, 9, 10).toStorageInt())
                    assertEquals(before, engine.canonicalHash(db))
                    assertTrue(engine.mismatchedFamilies(db, book.localRevision.value, book.valuationRevision.value).isEmpty())
                }
            } finally {
                database.close()
            }
        }
    }

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

    private fun nextId(): StableId = id(seed++)
    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x1919L, value))
    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private companion object {
        val BOOK_ID: StableId = StableId.fromUuid(UUID(0x1919L, 1))
        val BANK_ID: StableId = StableId.fromUuid(UUID(0x1919L, 200))
        val CREDIT_ID: StableId = StableId.fromUuid(UUID(0x1919L, 300))
        val CATEGORY_ID: StableId = StableId.fromUuid(UUID(0x1919L, 210))
        val EXPENSE_ID: StableId = StableId.fromUuid(UUID(0x1919L, 500))
        val OCCURRENCE_ID: StableId = StableId.fromUuid(UUID(0x1919L, 600))
        val JPY: CurrencyCode = requireNotNull(CurrencyCode.parse("JPY").getOrNull())
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
