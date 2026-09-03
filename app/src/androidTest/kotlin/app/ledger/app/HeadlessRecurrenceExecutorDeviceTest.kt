@file:Suppress("UNCHECKED_CAST")

package app.ledger.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.money.CurrencyCode
import app.ledger.core.security.ActiveBookSessionManagerFactory
import app.ledger.core.security.ActiveBookSessionRuntime
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.BookSessionManager
import app.ledger.core.security.DefaultLedgerStartupInspector
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.core.security.SqlCipherBookDatabaseResourceFactory
import app.ledger.core.security.VaultExposureRegistry
import app.ledger.finance.application.AutomationApplicationPort
import app.ledger.finance.application.CatchUpResult
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.data.RoomLedgerStartupInspector
import app.ledger.finance.data.SecureRoomLedgerInitializationPort
import app.ledger.finance.domain.SystemLedgerCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HeadlessRecurrenceExecutorDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var initialization: SecureRoomLedgerInitializationPort

    @Before
    fun prepare() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        initialization = SecureRoomLedgerInitializationPort(context, keys)
        initialization.clearLocalBook(BOOK_ID)
        val initialized = initialization.initialize(
            InitializeLedgerCommand(
                LedgerGenesisIds(
                    BOOK_ID,
                    id(2),
                    id(3),
                    id(4),
                    SystemLedgerCode.entries.mapIndexed { index, code -> code to id(100L + index) }.toMap(),
                ),
                requireNotNull(CurrencyCode.parse("JPY").getOrNull()),
                ZoneId.of("Asia/Tokyo"),
                Instant.ofEpochMilli(1_000),
            ),
        )
        assertTrue(initialized is DomainResult.Success)
    }

    @After
    fun cleanUp() {
        runBlocking { initialization.clearLocalBook(BOOK_ID) }
    }

    @Test
    fun lockedProcessCatchUpUsesRealKeystoreSqlCipherHeadlessLeaseAndReleasesIt() = runBlocking {
        var observedBook: StableId? = null
        var observedThrough: Instant? = null
        val port = Proxy.newProxyInstance(
            AutomationApplicationPort::class.java.classLoader,
            arrayOf(AutomationApplicationPort::class.java),
        ) { _, method, arguments ->
            check(method.name == "catchUp")
            observedBook = arguments[0] as StableId
            observedThrough = arguments[1] as Instant
            DomainResult.Success(CatchUpResult(0, 0, 0, 0))
        } as AutomationApplicationPort

        val resourceFactory = SqlCipherBookDatabaseResourceFactory(
            context,
            listOf(DefaultLedgerStartupInspector, RoomLedgerStartupInspector()),
        )
        val runtime = ActiveBookSessionRuntime(
            ActiveBookSessionManagerFactory { bookId ->
                BookSessionManager(bookId, keys, resourceFactory, VaultExposureRegistry { 0L })
            },
        )
        val result = AppHeadlessRecurrenceExecutor(runtime, port).catchUp(BOOK_ID, THROUGH)

        assertTrue(result is DomainResult.Success)
        assertEquals(BOOK_ID, observedBook)
        assertEquals(THROUGH, observedThrough)
        keys.open(BOOK_ID).use { opened -> assertTrue(opened.databaseDek.toString().isNotBlank()) }
    }

    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0L, value))

    private companion object {
        val BOOK_ID: StableId = StableId.fromUuid(UUID(0L, 0x23_0001L))
        val THROUGH: Instant = Instant.parse("2026-08-06T12:00:00Z")
    }
}
