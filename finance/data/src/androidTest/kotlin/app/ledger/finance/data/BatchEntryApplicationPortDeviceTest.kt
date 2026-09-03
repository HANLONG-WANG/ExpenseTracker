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
import app.ledger.finance.application.BatchEntryRowWriteRequest
import app.ledger.finance.application.BatchEntrySubmitRequest
import app.ledger.finance.application.BatchUndoRequest
import app.ledger.finance.application.BatchUndoRowIds
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitialCategoryCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.application.OrdinaryAmountDraft
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryTransactionWriteIds
import app.ledger.finance.application.OrdinaryTransactionWriteRequest
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CommitKind
import app.ledger.finance.domain.FinancialCommandType
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountType
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
class BatchEntryApplicationPortDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var databaseAccess: DeviceTestLedgerDatabaseAccess
    private lateinit var referenceData: SecureRoomReferenceDataManagementPort

    @Before
    fun prepare() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        keys.destroyLocal(BOOK_ID)
        databaseAccess = DeviceTestLedgerDatabaseAccess(context, keys)
        referenceData = SecureRoomReferenceDataManagementPort(databaseAccess)
        val initialization = SecureRoomLedgerInitializationPort(context, keys)
        initialization.initialize(
            InitializeLedgerCommand(
                LedgerGenesisIds(BOOK_ID, id(2), id(3), id(4), SystemLedgerCode.entries.mapIndexed { index, code -> code to id(100L + index) }.toMap()),
                currency("JPY"),
                ZONE,
                Instant.ofEpochMilli(1_000),
            ),
        ).success()
        initialization.createFirstAccount(
            BOOK_ID,
            InitialAccountCommand(ACCOUNT_ID, id(201), id(202), id(203), id(204), Instant.ofEpochMilli(2_000), UserAccountType.CASH, "Wallet", currency("JPY"), "account", 0xff006c4c.toInt()),
        ).success()
        initialization.createFirstCategory(
            BOOK_ID,
            InitialCategoryCommand(CATEGORY_ID, id(211), id(212), id(213), Instant.ofEpochMilli(3_000), CategoryDirection.EXPENSE, "Food", "food", StatisticalNature.CONSUMPTION_EXPENSE, "record", 0xff006c4c.toInt()),
        ).success()
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys.destroyLocal(BOOK_ID)
    }

    @Test
    fun invalidFailureRetryAuditAndProjectionAreOneAtomicBatch() = runBlocking {
        val production = SecureRoomBatchEntryApplicationPort(databaseAccess, referenceData)
        val invalid = request(parentSeed = 9_000L, amounts = listOf(1_000L), categoryId = id(99_999L))
        val invalidReport = production.validate(invalid).success()
        assertFalse(invalidReport.canCommit)
        assertCounts(transactions = 0L, revisions = 0L, receipts = 0L, localRevision = 3L)

        val valid = request(parentSeed = 10_000L, amounts = listOf(1_200L, 3_400L), categoryId = CATEGORY_ID)
        val injected = SecureRoomBatchEntryApplicationPort(
            databaseAccess,
            referenceData,
            FinancialCommitFailureInjector { phase -> if (phase == FinancialCommitPhase.AFTER_IMMUTABLE_FACTS) error("injected") },
        )
        assertTrue(injected.submit(valid) is DomainResult.Failure)
        assertCounts(transactions = 0L, revisions = 0L, receipts = 0L, localRevision = 3L)

        val first = production.submit(valid).success()
        val replay = production.submit(valid).success()
        assertEquals(first, replay)
        assertEquals(2, first.transactionIds.size)
        assertCounts(transactions = 2L, revisions = 2L, receipts = 1L, localRevision = 4L)

        val audit = requireNotNull(production.audit(BOOK_ID, valid.commandId).success())
        assertEquals(first.transactionIds, audit.transactionIds)
        assertFalse(audit.fullyReversed)
        withDatabase { db ->
            assertEquals(-4_600L, scalar(db, "SELECT normal_balance_minor FROM account_balance_current"))
            assertEquals(4L, scalar(db, "SELECT as_of_local_revision FROM account_balance_current"))
            assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM economic_effect"))
            assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM budget_effect"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM book_commit WHERE kind=${CommitKind.BATCH_MUTATION.ordinal}"))
            assertEquals(
                "ok",
                db.query("PRAGMA integrity_check").use {
                    it.moveToFirst()
                    it.getString(0)
                },
            )
        }

        val undo = BatchUndoRequest(
            BOOK_ID,
            valid.commandId,
            CommandId(id(20_000L)),
            id(20_001L),
            id(20_002L),
            Instant.ofEpochMilli(200_000L),
            audit.transactionIds.mapIndexed { index, transactionId ->
                val seed = 21_000L + index * 1_000L
                BatchUndoRowIds(transactionId, id(seed), (seed + 10..seed + 260).map(::id))
            },
        )
        production.undo(undo).success()
        assertCounts(transactions = 2L, revisions = 4L, receipts = 2L, localRevision = 5L)
        assertTrue(requireNotNull(production.audit(BOOK_ID, valid.commandId).success()).fullyReversed)
        withDatabase { db ->
            assertEquals(0L, scalar(db, "SELECT normal_balance_minor FROM account_balance_current"))
            assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM business_transaction WHERE lifecycle_state=1"))
            assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM book_commit WHERE kind=${CommitKind.BATCH_MUTATION.ordinal}"))
        }
    }

    private fun request(parentSeed: Long, amounts: List<Long>, categoryId: StableId): BatchEntrySubmitRequest {
        val createdAt = Instant.ofEpochMilli(parentSeed * 10)
        val commitId = id(parentSeed + 1)
        val deviceId = id(parentSeed + 2)
        val rows = amounts.mapIndexed { index, amount ->
            val seed = parentSeed + 100L + index * 1_000L
            BatchEntryRowWriteRequest.Ordinary(
                id(seed),
                OrdinaryTransactionWriteRequest(
                    OrdinaryTransactionWriteIds(
                        BOOK_ID,
                        id(seed + 1),
                        id(seed + 2),
                        id(seed + 3),
                        commitId,
                        deviceId,
                        (seed + 10..seed + 260).map(::id),
                        (seed + 300..seed + 307).map(::id),
                    ),
                    null,
                    OrdinaryDirection.EXPENSE,
                    categoryId,
                    OrdinaryAmountDraft(amount.toString(), amount, currency("JPY"), amount, amount),
                    ACCOUNT_ID,
                    null,
                    null,
                    Instant.parse("2026-08-06T03:30:00Z").plusSeconds(index.toLong()),
                    ZONE,
                    LocalDate.of(2026, 8, 6),
                    null,
                    null,
                    null,
                    emptyList(),
                    null,
                    null,
                    "batch row ${index + 1}",
                    emptyList(),
                    TransactionSource.BATCH_OPERATION,
                    id(parentSeed),
                    createdAt,
                ),
            )
        }
        return BatchEntrySubmitRequest(BOOK_ID, CommandId(id(parentSeed)), commitId, deviceId, createdAt, rows, false)
    }

    private fun assertCounts(transactions: Long, revisions: Long, receipts: Long, localRevision: Long) = withDatabase { db ->
        assertEquals(transactions, scalar(db, "SELECT COUNT(*) FROM business_transaction"))
        assertEquals(revisions, scalar(db, "SELECT COUNT(*) FROM transaction_revision"))
        assertEquals(receipts, scalar(db, "SELECT COUNT(*) FROM command_receipt WHERE command_type=${FinancialCommandType.BATCH_MUTATION.ordinal}"))
        assertEquals(localRevision, scalar(db, "SELECT local_revision FROM book"))
    }

    private fun withDatabase(block: (androidx.sqlite.db.SupportSQLiteDatabase) -> Unit) {
        keys.open(BOOK_ID).use { opened ->
            val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
            try {
                database.readLedger(block)
            } finally {
                database.close()
            }
        }
    }

    private fun scalar(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long = db.query(sql).use {
        it.moveToFirst()
        it.getLong(0)
    }
    private fun currency(value: String): CurrencyCode = requireNotNull(CurrencyCode.parse(value).getOrNull())
    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x24L, value))
    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private companion object {
        val BOOK_ID: StableId = StableId.fromUuid(UUID(0x24L, 1))
        val ACCOUNT_ID: StableId = StableId.fromUuid(UUID(0x24L, 200))
        val CATEGORY_ID: StableId = StableId.fromUuid(UUID(0x24L, 210))
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
