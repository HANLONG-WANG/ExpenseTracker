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
import app.ledger.core.money.FxEvidence
import app.ledger.core.money.FxEvidenceInput
import app.ledger.core.money.FxProvider
import app.ledger.core.money.FxRateSource
import app.ledger.core.network.FxQuoteNetworkPort
import app.ledger.core.network.NetworkFxQuote
import app.ledger.core.network.NetworkFxQuoteRequest
import app.ledger.core.network.NetworkFxQuoteResult
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.finance.application.AccountDraft
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.application.ReferenceMutation
import app.ledger.finance.application.ReferenceMutationCommand
import app.ledger.finance.application.ReferenceMutationIds
import app.ledger.finance.application.SpecializedAccountAmountDraft
import app.ledger.finance.application.SpecializedFxQuoteRequest
import app.ledger.finance.application.SpecializedTransactionContext
import app.ledger.finance.application.SpecializedTransactionWriteIds
import app.ledger.finance.application.SpecializedTransactionWriteRequest
import app.ledger.finance.domain.BalanceAdjustmentDirection
import app.ledger.finance.domain.FxValuationPolicy
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.UserAccountType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SpecializedTransactionEntryDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var references: SecureRoomReferenceDataManagementPort
    private lateinit var network: MutableFxNetwork
    private lateinit var entry: SecureRoomSpecializedTransactionEntryPort
    private var seed = 20_000L

    @Before
    fun prepare() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        keys.destroyLocal(BOOK)
        SecureRoomLedgerInitializationPort(context, keys).initialize(
            InitializeLedgerCommand(
                LedgerGenesisIds(BOOK, id(2), id(3), id(4), SystemLedgerCode.entries.mapIndexed { index, code -> code to id(100L + index) }.toMap()),
                JPY,
                ZONE,
                Instant.ofEpochMilli(1_000),
            ),
        ).success()
        references = SecureRoomReferenceDataManagementPort(context, keys)
        createAccount(USD_ACCOUNT, USD_LEDGER, "USD wallet", USD, 0)
        createAccount(EUR_ACCOUNT, EUR_LEDGER, "EUR wallet", EUR, 1)
        createAccount(JPY_ACCOUNT, JPY_LEDGER, "JPY wallet", JPY, 2)
        network = MutableFxNetwork(mutableMapOf("USD" to BigDecimal("150"), "EUR" to BigDecimal("165")))
        entry = SecureRoomSpecializedTransactionEntryPort(context, keys, references, network)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys.destroyLocal(BOOK)
    }

    @Test
    fun quotesAndAllSpecializedTransactionsRemainAtomicBalancedAndRevisionSeparated() = runBlocking {
        val initial = bookRevisions()
        val usdQuote = requireNotNull(entry.quote(SpecializedFxQuoteRequest(BOOK, USD, JPY, DATE, true)).success()).evidence
        val eurQuote = requireNotNull(entry.quote(SpecializedFxQuoteRequest(BOOK, EUR, JPY, DATE, true)).success()).evidence
        val afterQuotes = bookRevisions()
        assertEquals(initial.first, afterQuotes.first)
        assertEquals(initial.second + 2L, afterQuotes.second)

        val transfer = transferRequest(usdQuote)
        val receipt = entry.submit(transfer).success()
        assertEquals(receipt, entry.submit(transfer).success())
        assertEquals(1L, scalar("SELECT COUNT(*) FROM business_transaction"))
        assertEquals(0L, scalar("SELECT core_net_financial_assets_base_minor FROM widget_book_snapshot"))

        val checkpoint = createCheckpoint()
        entry.submit(adjustmentRequest(usdQuote, checkpoint)).success()
        assertEquals(adjustmentRequest(usdQuote, checkpoint).ids.transactionId, references.snapshot(BOOK).success().checkpoints.single { it.id == checkpoint }.adjustmentTransactionId)

        entry.submit(exchangeRequest(usdQuote, eurQuote)).success()
        entry.submit(openingRequest()).success()

        assertEquals(4L, scalar("SELECT COUNT(*) FROM business_transaction"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM journal_entry WHERE base_debit_total_minor<>base_credit_total_minor"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM economic_effect WHERE source_revision_id IN (SELECT revision_id FROM balance_adjustment_revision_detail UNION SELECT revision_id FROM opening_balance_revision_detail)"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM budget_effect WHERE source_revision_id IN (SELECT revision_id FROM balance_adjustment_revision_detail UNION SELECT revision_id FROM opening_balance_revision_detail)"))
        assertEquals("ok", text("PRAGMA integrity_check"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM pragma_foreign_key_check"))

        val historyRates = texts("SELECT rate_decimal FROM fx_rate_snapshot ORDER BY id")
        val revisionsBeforeRefresh = bookRevisions()
        network.rates["USD"] = BigDecimal("151.75")
        entry.quote(SpecializedFxQuoteRequest(BOOK, USD, JPY, DATE.plusDays(1), true)).success()
        assertEquals(revisionsBeforeRefresh, bookRevisions())
        entry.quote(SpecializedFxQuoteRequest(BOOK, USD, JPY, DATE, true)).success()
        val revisionsAfterRefresh = bookRevisions()
        assertEquals(revisionsBeforeRefresh.first, revisionsAfterRefresh.first)
        assertEquals(revisionsBeforeRefresh.second + 1L, revisionsAfterRefresh.second)
        assertEquals(historyRates, texts("SELECT rate_decimal FROM fx_rate_snapshot ORDER BY id"))

        val cached = entry.quote(SpecializedFxQuoteRequest(BOOK, USD, JPY, DATE.plusDays(1), false)).success()
        assertNotNull(cached)
        assertNotNull(cached?.evidence?.quotedAt)
    }

    private suspend fun createAccount(account: StableId, ledger: StableId, name: String, currency: CurrencyCode, order: Int) {
        mutate(
            ReferenceMutation.SaveAccount(
                AccountDraft(account, ledger, null, UserAccountType.CASH, name, currency, null, null, null, null, "account", 0xff006c4c.toInt(), order),
            ),
        )
    }

    private suspend fun createCheckpoint(): StableId {
        val checkpoint = nextId()
        mutate(ReferenceMutation.SaveCheckpoint(checkpoint, USD_ACCOUNT, NOW, DATE, 0L, "observed"))
        return checkpoint
    }

    private suspend fun mutate(mutation: ReferenceMutation) {
        val revision = references.snapshot(BOOK).success().localRevision
        references.mutate(
            ReferenceMutationCommand(
                ReferenceMutationIds(BOOK, revision, nextId(), List(16) { nextId() }, nextId(), Instant.ofEpochMilli(seed++)),
                mutation,
            ),
        ).success()
    }

    private fun transferRequest(usdQuote: FxEvidence): SpecializedTransactionWriteRequest.Transfer {
        val outgoing = SpecializedAccountAmountDraft(USD_ACCOUNT, 1_000L, 1_500L, usdQuote)
        val incoming = SpecializedAccountAmountDraft(EUR_ACCOUNT, 900L, 1_500L, evidence(EUR, JPY, BigDecimal("1500").divide(BigDecimal("9"), MATH_CONTEXT), FxRateSource.IMPLIED_FROM_ACTUAL_AMOUNTS))
        return SpecializedTransactionWriteRequest.Transfer(ids(30_000), context("10"), outgoing, incoming)
    }

    private fun adjustmentRequest(usdQuote: FxEvidence, checkpoint: StableId): SpecializedTransactionWriteRequest.BalanceAdjustment = SpecializedTransactionWriteRequest.BalanceAdjustment(ids(31_000), context("1"), SpecializedAccountAmountDraft(USD_ACCOUNT, 100L, 150L, usdQuote), BalanceAdjustmentDirection.INCREASE, checkpoint)

    private fun exchangeRequest(usdQuote: FxEvidence, eurQuote: FxEvidence): SpecializedTransactionWriteRequest.FxExchange = SpecializedTransactionWriteRequest.FxExchange(
        ids(32_000),
        context("10"),
        SpecializedAccountAmountDraft(USD_ACCOUNT, 1_000L, 1_500L, usdQuote),
        SpecializedAccountAmountDraft(EUR_ACCOUNT, 900L, 1_485L, eurQuote),
        FxValuationPolicy.EXPLICIT_ACCOUNT_AMOUNTS,
        15L,
    )

    private fun openingRequest(): SpecializedTransactionWriteRequest.OpeningBalance = SpecializedTransactionWriteRequest.OpeningBalance(
        ids(33_000),
        context("10000"),
        SpecializedAccountAmountDraft(JPY_ACCOUNT, 10_000L, 10_000L, null),
        DATE,
    )

    private fun ids(value: Long): SpecializedTransactionWriteIds = SpecializedTransactionWriteIds(
        BOOK,
        CommandId(id(value)),
        id(value + 1),
        id(value + 2),
        id(value + 3),
        id(value + 4),
        (value + 10..value + 120).map(::id),
        (value + 130..value + 137).map(::id),
    )

    private fun context(expression: String): SpecializedTransactionContext = SpecializedTransactionContext(NOW, ZONE, DATE, expression, null, emptyList(), NOW)

    private fun evidence(source: CurrencyCode, target: CurrencyCode, rate: BigDecimal, type: FxRateSource): FxEvidence = (FxEvidence.create(FxEvidenceInput(source, target, rate, PROVIDER, NOW, NOW, type, false)) as DomainResult.Success).value

    private fun bookRevisions(): Pair<Long, Long> = query { db ->
        db.query("SELECT local_revision,valuation_revision FROM book").use {
            it.moveToFirst()
            it.getLong(0) to it.getLong(1)
        }
    }
    private fun scalar(sql: String): Long = query { db ->
        db.query(sql).use {
            it.moveToFirst()
            it.getLong(0)
        }
    }
    private fun text(sql: String): String = query { db ->
        db.query(sql).use {
            it.moveToFirst()
            it.getString(0)
        }
    }
    private fun texts(sql: String): List<String> = query { db -> db.query(sql).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } } }

    private fun <T> query(block: (androidx.sqlite.db.SupportSQLiteDatabase) -> T): T = keys.open(BOOK).use { opened ->
        val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
        try {
            database.readLedger(block)
        } finally {
            database.close()
        }
    }

    private fun nextId(): StableId = id(seed++)
    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x1414L, value))
    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.toString())
    }

    private class MutableFxNetwork(val rates: MutableMap<String, BigDecimal>) : FxQuoteNetworkPort {
        override suspend fun quote(request: NetworkFxQuoteRequest): NetworkFxQuoteResult {
            assertEquals("JPY", request.targetCode)
            val rate = rates[request.sourceCode] ?: return NetworkFxQuoteResult.Unavailable
            return NetworkFxQuoteResult.Available(NetworkFxQuote(request.sourceCode, request.targetCode, rate, "device-test", request.date ?: DATE, NOW))
        }
    }

    private companion object {
        val BOOK: StableId = StableId.fromUuid(UUID(0x1414L, 1))
        val USD_ACCOUNT: StableId = StableId.fromUuid(UUID(0x1414L, 200))
        val EUR_ACCOUNT: StableId = StableId.fromUuid(UUID(0x1414L, 201))
        val JPY_ACCOUNT: StableId = StableId.fromUuid(UUID(0x1414L, 202))
        val USD_LEDGER: StableId = StableId.fromUuid(UUID(0x1414L, 210))
        val EUR_LEDGER: StableId = StableId.fromUuid(UUID(0x1414L, 211))
        val JPY_LEDGER: StableId = StableId.fromUuid(UUID(0x1414L, 212))
        val USD: CurrencyCode = requireNotNull(CurrencyCode.parse("USD").getOrNull())
        val EUR: CurrencyCode = requireNotNull(CurrencyCode.parse("EUR").getOrNull())
        val JPY: CurrencyCode = requireNotNull(CurrencyCode.parse("JPY").getOrNull())
        val DATE: LocalDate = LocalDate.of(2026, 8, 3)
        val NOW: Instant = Instant.parse("2026-08-03T04:05:06Z")
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
        val PROVIDER: FxProvider = (FxProvider.of("device-test") as DomainResult.Success).value
        val MATH_CONTEXT = MathContext(34, RoundingMode.HALF_EVEN)
    }
}
