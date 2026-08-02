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
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
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
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SecureLedgerInitializationDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var port: SecureRoomLedgerInitializationPort

    @Before
    fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        keys.destroyLocal(BOOK_ID)
        port = SecureRoomLedgerInitializationPort(context, keys)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys.destroyLocal(BOOK_ID)
    }

    @Test
    fun encryptedGenesisAndOptionalReferencesAreAtomicEmptyAndIdempotent() = runBlocking {
        val genesis = InitializeLedgerCommand(
            LedgerGenesisIds(
                BOOK_ID,
                id(2),
                id(3),
                id(4),
                SystemLedgerCode.entries.mapIndexed { index, code -> code to id(100L + index) }.toMap(),
            ),
            requireNotNull(CurrencyCode.parse("JPY").getOrNull()),
            ZoneId.of("Asia/Tokyo"),
            Instant.ofEpochMilli(1_000L),
        )
        port.initialize(genesis).success()
        port.initialize(genesis).success()

        val empty = port.emptyLedgerState(BOOK_ID).success()
        assertFalse(empty.hasUserAccount)
        assertFalse(empty.hasCategory)
        assertFalse(empty.hasTransaction)

        port.createFirstAccount(
            BOOK_ID,
            InitialAccountCommand(
                id(5), id(6), id(7), id(8), id(9), Instant.ofEpochMilli(2_000L),
                UserAccountType.CASH, "Wallet", requireNotNull(CurrencyCode.parse("JPY").getOrNull()), "account", 0,
            ),
        ).success()
        port.createFirstCategory(
            BOOK_ID,
            InitialCategoryCommand(
                id(10), id(11), id(12), id(13), Instant.ofEpochMilli(3_000L),
                CategoryDirection.EXPENSE, "Food", "food", StatisticalNature.CONSUMPTION_EXPENSE, "record", 0,
            ),
        ).success()

        keys.open(BOOK_ID).use { opened ->
            val database = opened.databaseDek.useBytes { passphrase -> EncryptedDatabaseFactory.openPrimary(context, passphrase) }
            try {
                database.readLedger { connection ->
                    assertEquals(1L, scalar(connection, "SELECT COUNT(*) FROM book"))
                    assertEquals(10L, scalar(connection, "SELECT COUNT(*) FROM ledger_account WHERE owner_type = 2"))
                    assertEquals(1L, scalar(connection, "SELECT COUNT(*) FROM user_account"))
                    assertEquals(1L, scalar(connection, "SELECT COUNT(*) FROM category"))
                    assertEquals(0L, scalar(connection, "SELECT COUNT(*) FROM business_transaction"))
                    assertEquals(0L, scalar(connection, "SELECT COUNT(*) FROM journal_entry"))
                    assertEquals(0L, scalar(connection, "SELECT COUNT(*) FROM posting"))
                    assertEquals(3L, scalar(connection, "SELECT local_revision FROM book"))
                    assertEquals(3L, scalar(connection, "SELECT COUNT(*) FROM book_commit"))
                    assertEquals(3L, scalar(connection, "SELECT COUNT(*) FROM entity_revision"))
                    assertEquals(1L, scalar(connection, "SELECT COUNT(*) FROM widget_book_snapshot WHERE as_of_local_revision = 3"))
                    assertTrue(connection.query("PRAGMA integrity_check").use { it.moveToFirst() && it.getString(0) == "ok" })
                }
            } finally {
                database.close()
            }
        }
    }

    private fun scalar(database: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long = database.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun id(index: Long): StableId = StableId.fromUuid(UUID(0x11L, index))

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private companion object {
        val BOOK_ID: StableId = StableId.fromUuid(UUID(0x11L, 1L))
    }
}
