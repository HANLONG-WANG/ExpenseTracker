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
import app.ledger.finance.application.LedgerWorkbookSheet
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.TransactionFilter
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
class LedgerExportQueryDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy

    @Before
    fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        keys.destroyLocal(BOOK_ID)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys.destroyLocal(BOOK_ID)
    }

    @Test
    fun allFifteenWorkbookQueriesExecuteAndVaultCiphertextsNeverReachOrdinaryExport() = runBlocking {
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
                CurrencyCode.parse("JPY").getOrNull()!!,
                ZoneId.of("Asia/Tokyo"),
                Instant.ofEpochMilli(1_000L),
            ),
        ).success()
        initialization.createFirstAccount(
            BOOK_ID,
            InitialAccountCommand(
                id(5), id(6), id(7), id(8), id(9), Instant.ofEpochMilli(2_000L), UserAccountType.CASH,
                "Wallet", CurrencyCode.parse("JPY").getOrNull()!!, "account", 0,
            ),
        ).success()
        initialization.createFirstCategory(
            BOOK_ID,
            InitialCategoryCommand(
                id(10), id(11), id(12), id(13), Instant.ofEpochMilli(3_000L), CategoryDirection.EXPENSE,
                "Food", "food", StatisticalNature.CONSUMPTION_EXPENSE, "record", 0,
            ),
        ).success()
        insertCardAndVaultSentinel()

        val port = SecureRoomLedgerExportQueryPort(context, keys)
        assertTrue(port.metadata(BOOK_ID).success().localRevision >= 3L)
        LedgerWorkbookSheet.entries.forEach { sheet ->
            val page = port.workbookSheet(
                BOOK_ID,
                sheet,
                includeLocationCoordinates = false,
                afterInternalId = 0L,
                limit = 256,
            ).success()
            assertFalse(page.headers.any { it in setOf("account_number", "pan", "security_code", "vault") })
            assertTrue(page.rows.flatten().none { VAULT_SENTINEL in it })
        }
        val cards = port.workbookSheet(BOOK_ID, LedgerWorkbookSheet.CARDS, false, 0L, 256).success()
        assertTrue("last_four" in cards.headers)
        assertTrue(cards.rows.flatten().contains("4242"))
        val current = port.currentTransactions(
            BOOK_ID,
            TransactionFilter(),
            listOf("transaction_id", "note"),
            null,
            256,
        ).success()
        assertEquals(0, current.rows.size)
    }

    private fun insertCardAndVaultSentinel() {
        keys.open(BOOK_ID).use { opened ->
            val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
            try {
                database.inLedgerTransaction { db ->
                    val accountId = db.query("SELECT id FROM user_account LIMIT 1").use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getLong(0)
                    }
                    val commitId = db.query("SELECT MAX(id) FROM book_commit").use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getLong(0)
                    }
                    db.execSQL(
                        INSERT_PAYMENT_CARD,
                        arrayOf(
                            1L, id(20).bytes, accountId, 0, "Daily card", "4242", 0,
                            null, "card", 0, 0, commitId, 1,
                        ),
                    )
                    val secret = VAULT_SENTINEL.toByteArray()
                    db.execSQL(
                        INSERT_VAULT_SECRET,
                        arrayOf(1L, secret, secret, secret, secret, secret, 1, 4_000L),
                    )
                }
            } finally {
                database.close()
            }
        }
    }

    private fun id(index: Long): StableId = StableId.fromUuid(UUID(0x29L, index))
    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private companion object {
        val BOOK_ID: StableId = StableId.fromUuid(UUID(0x29L, 1L))
        const val VAULT_SENTINEL = "4111111111111111-CVC-123"
        const val INSERT_PAYMENT_CARD =
            "INSERT INTO payment_card(id,uid,account_id,card_type,display_name,last_four,status," +
                "replacement_of_card_id,icon_key,color_argb,sort_order,last_commit_id,row_version) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)"
        const val INSERT_VAULT_SECRET =
            "INSERT INTO card_vault_secret(card_id,holder_name_ciphertext,pan_ciphertext,expiry_ciphertext," +
                "security_code_ciphertext,custom_fields_ciphertext,key_version,updated_at) VALUES(?,?,?,?,?,?,?,?)"
    }
}
