@file:Suppress("LongMethod", "MagicNumber", "LongParameterList", "MaxLineLength")

package app.ledger.finance.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.money.CurrencyCode
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitialCategoryCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.application.OrdinaryAmountDraft
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryLocationDraft
import app.ledger.finance.application.OrdinaryLocationProvider
import app.ledger.finance.application.OrdinaryTransactionWriteIds
import app.ledger.finance.application.OrdinaryTransactionWriteRequest
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.DomainViolation
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OrdinaryTransactionEntryDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var initialization: SecureRoomLedgerInitializationPort
    private lateinit var entry: SecureRoomOrdinaryTransactionEntryPort

    @Before
    fun prepare() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        keys.destroyLocal(BOOK_ID)
        initialization = SecureRoomLedgerInitializationPort(context, keys)
        entry = SecureRoomOrdinaryTransactionEntryPort(context, keys)
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
    fun createRetryEditConflictAndLocationAreAtomicThroughCoordinator() = runBlocking {
        val create = request(seed = 1_000, command = id(1_000), transaction = TRANSACTION_ID, expected = null, amount = 1_250, location = true)
        val first = entry.submit(create).success()
        val retry = entry.submit(create).success()
        assertEquals(first, retry)

        val editing = requireNotNull(entry.snapshot(BOOK_ID, TRANSACTION_ID).success().editing)
        assertEquals(1_250L, editing.userMinor)
        assertEquals(LOCATION_ID, editing.locationRecordId)
        assertEquals(create.amount.expression, editing.expression)

        val edit = request(seed = 2_000, command = id(2_000), transaction = TRANSACTION_ID, expected = editing.revisionId, amount = 2_000, location = false)
        entry.submit(edit).success()
        val stale = request(seed = 3_000, command = id(3_000), transaction = TRANSACTION_ID, expected = editing.revisionId, amount = 3_000, location = false)
        val conflict = entry.submit(stale)
        assertTrue(conflict is DomainResult.Failure && conflict.error == DomainViolation.StaleExpectedRevision)

        keys.open(BOOK_ID).use { opened ->
            val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
            try {
                database.readLedger { db ->
                    assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM business_transaction"))
                    assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM transaction_revision"))
                    assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM location_record"))
                    assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM command_receipt"))
                    assertEquals(5L, scalar(db, "SELECT local_revision FROM book"))
                    assertEquals(-2_000L, scalar(db, "SELECT normal_balance_minor FROM account_balance_current"))
                    assertEquals(5L, scalar(db, "SELECT as_of_local_revision FROM account_balance_current"))
                    assertEquals(
                        "ok",
                        db.query("PRAGMA integrity_check").use {
                            it.moveToFirst()
                            it.getString(0)
                        },
                    )
                }
            } finally {
                database.close()
            }
        }
    }

    private fun request(seed: Long, command: StableId, transaction: StableId, expected: StableId?, amount: Long, location: Boolean): OrdinaryTransactionWriteRequest = OrdinaryTransactionWriteRequest(
        ids = OrdinaryTransactionWriteIds(BOOK_ID, command, transaction, id(seed + 1), id(seed + 2), id(seed + 3), (seed + 10..seed + 300).map(::id), (seed + 400..seed + 407).map(::id)),
        expectedRevisionId = expected,
        direction = OrdinaryDirection.EXPENSE,
        categoryId = CATEGORY_ID,
        amount = OrdinaryAmountDraft("$amount", amount, currency("JPY"), amount, amount),
        accountId = ACCOUNT_ID,
        cardId = null,
        merchantId = null,
        occurredAt = Instant.parse("2026-08-03T03:30:00Z"),
        zoneId = ZONE,
        localDate = LocalDate.of(2026, 8, 3),
        projectId = null,
        settlementActivityId = null,
        settlementShares = emptyList(),
        locationRecordId = if (location) LOCATION_ID else null,
        newLocation = if (location) OrdinaryLocationDraft(LOCATION_ID, 356_000_000, 1_397_000_000, 5_000, Instant.parse("2026-08-03T03:29:59Z"), OrdinaryLocationProvider.FUSED, null) else null,
        note = "lunch",
        attachmentIds = emptyList(),
        source = TransactionSource.MANUAL,
        sourceReferenceId = null,
        createdAt = Instant.ofEpochMilli(seed * 10),
    )

    private fun scalar(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long = db.query(sql).use {
        it.moveToFirst()
        it.getLong(0)
    }
    private fun currency(value: String): CurrencyCode = requireNotNull(CurrencyCode.parse(value).getOrNull())
    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x13L, value))
    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private companion object {
        val BOOK_ID = StableId.fromUuid(UUID(0x13L, 1))
        val ACCOUNT_ID = StableId.fromUuid(UUID(0x13L, 200))
        val CATEGORY_ID = StableId.fromUuid(UUID(0x13L, 210))
        val TRANSACTION_ID = StableId.fromUuid(UUID(0x13L, 900))
        val LOCATION_ID = StableId.fromUuid(UUID(0x13L, 901))
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
