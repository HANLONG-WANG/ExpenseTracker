package app.ledger.finance.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WidgetSnapshotApplicationPortDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var databaseAccess: DeviceTestLedgerDatabaseAccess

    @Before
    fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        keys.destroyLocal(BOOK_ID)
        databaseAccess = DeviceTestLedgerDatabaseAccess(context, keys)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys.destroyLocal(BOOK_ID)
    }

    @Test
    fun glanceReadUsesOnlyBoundedEncryptedSnapshotRowsWithoutSensitiveColumns() {
        runBlocking {
            val initialization = SecureRoomLedgerInitializationPort(context, keys)
            initialization.initialize(
                InitializeLedgerCommand(
                    LedgerGenesisIds(
                        BOOK_ID,
                        id(2),
                        id(3),
                        id(4),
                        SystemLedgerCode.entries.mapIndexed { index, code -> code to id(100L + index) }.toMap(),
                    ),
                    currency(),
                    ZoneId.of("Asia/Tokyo"),
                    Instant.parse("2026-08-11T00:00:00Z"),
                ),
            ).success()
            initialization.createFirstAccount(
                BOOK_ID,
                InitialAccountCommand(
                    id(5), id(6), id(7), id(8), id(9), Instant.parse("2026-08-11T00:01:00Z"),
                    UserAccountType.CASH, "財布", currency(), "wallet", 0,
                ),
            ).success()
            initialization.createFirstCategory(
                BOOK_ID,
                InitialCategoryCommand(
                    id(10), id(11), id(12), id(13), Instant.parse("2026-08-11T00:02:00Z"),
                    CategoryDirection.EXPENSE, "食費", "food", StatisticalNature.CONSUMPTION_EXPENSE, "food", 0,
                ),
            ).success()

            val port = SecureRoomWidgetSnapshotApplicationPort(databaseAccess)
            val bundle = port.read(BOOK_ID).success()
            assertNotNull(bundle.book)
            assertEquals("JPY", bundle.book?.baseCurrency)
            assertEquals(listOf("財布"), bundle.accounts.map { it.displayName })
            assertTrue(bundle.creditAccounts.isEmpty())
            assertTrue(bundle.goals.isEmpty())
            assertEquals(listOf("食費"), port.quickTargets(BOOK_ID).success().map { it.displayName })

            assertTrue(port.refreshIfStale(BOOK_ID, LocalDate.of(2026, 8, 12)).success())
            assertEquals(20260812, port.read(BOOK_ID).success().book?.snapshotLocalDate)
            assertFalse(port.refreshIfStale(BOOK_ID, LocalDate.of(2026, 8, 12)).success())

            keys.open(BOOK_ID).use { opened ->
                opened.databaseDek.useBytes { passphrase ->
                    val database = EncryptedDatabaseFactory.openPrimary(context, passphrase)
                    try {
                        val connection = database.openHelper.writableDatabase
                        SNAPSHOT_TABLES.forEach { table ->
                            val columns = connection.query("PRAGMA table_info($table)").use { cursor ->
                                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
                            }
                            assertFalse(columns.any { it in FORBIDDEN_COLUMNS })
                        }
                        assertEquals(4, SNAPSHOT_TABLES.size)
                        assertTrue(connection.query("PRAGMA cipher_integrity_check").use { cursor -> !cursor.moveToFirst() })
                    } finally {
                        database.close()
                    }
                }
            }
        }
    }

    private fun currency(): CurrencyCode = CurrencyCode.parse("JPY").success()

    private fun id(index: Long): StableId = StableId.fromUuid(UUID(0x33L, index))

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private companion object {
        val BOOK_ID: StableId = StableId.fromUuid(UUID(0x33L, 1L))
        val SNAPSHOT_TABLES = listOf(
            "widget_book_snapshot",
            "widget_account_snapshot",
            "widget_credit_snapshot",
            "widget_goal_snapshot",
        )
        val FORBIDDEN_COLUMNS = setOf(
            "note",
            "latitude",
            "longitude",
            "lat_e7",
            "lon_e7",
            "pan_ciphertext",
            "security_code_ciphertext",
            "holder_name_ciphertext",
            "custom_fields_ciphertext",
            "attachment_name",
            "merchant_name",
        )
    }
}
