@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength")

package app.ledger.finance.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.StableIdSource
import app.ledger.core.common.getOrNull
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.money.CurrencyCode
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.finance.application.BatchEntryRowWriteRequest
import app.ledger.finance.application.BatchEntrySubmitRequest
import app.ledger.finance.application.ImportCommitMetadata
import app.ledger.finance.application.ImportFinancialPage
import app.ledger.finance.application.ImportFinancialPageSource
import app.ledger.finance.application.ImportFinancialUndoRequest
import app.ledger.finance.application.ImportSourceRow
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.application.OrdinaryAmountDraft
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryTransactionWriteIds
import app.ledger.finance.application.OrdinaryTransactionWriteRequest
import app.ledger.finance.application.StructuredImportCommitRequest
import app.ledger.finance.application.StructuredImportEntityType
import app.ledger.finance.application.StructuredImportPageSource
import app.ledger.finance.application.StructuredImportPhase
import app.ledger.finance.application.StructuredImportRow
import app.ledger.finance.application.StructuredImportValues
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.TransactionSource
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
class StructuredImportApplicationPortDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy

    @Before
    fun prepare() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        deleteTestCopies()
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        keys.destroyLocal(BOOK_ID)
        SecureRoomLedgerInitializationPort(context, keys).initialize(
            InitializeLedgerCommand(
                LedgerGenesisIds(
                    BOOK_ID,
                    id(2),
                    id(3),
                    id(4),
                    SystemLedgerCode.entries.mapIndexed { index, code -> code to id(100L + index) }.toMap(),
                ),
                currency("JPY"),
                ZONE,
                Instant.ofEpochMilli(1_000),
            ),
        ).success()
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        deleteTestCopies()
        keys.destroyLocal(BOOK_ID)
    }

    @Test
    fun allFifteenStructuredEntityTypesApplyThroughTypedPortsInOneShadowExchange() = runBlocking {
        val operationId = id(90_001)
        insertOperation(operationId)
        val entities = entityRows()
        val transactionPage = transactionPage()
        val request = StructuredImportCommitRequest(
            BOOK_ID,
            ImportCommitMetadata(
                operationId,
                id(90_002),
                id(90_003),
                2,
                Hash256.sha256("all-structured-types".toByteArray()),
                entities.size + 1L,
                Instant.parse("2026-08-09T12:00:00Z"),
            ),
            entitySource(entities),
            ImportFinancialPageSource { after, _ -> if (after == 0L) DomainResult.Success(transactionPage) else DomainResult.Success(null) },
            1,
        )
        val port = SecureRoomStructuredImportApplicationPort(context, keys)
        val result = port.commit(request).success()

        assertTrue(result.usedShadowLedger)
        assertFalse(result.replayed)
        assertEquals(17L, result.importedRows)
        assertEquals(3L, scalar("SELECT COUNT(*) FROM user_account"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM payment_card"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM category"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM merchant"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM place"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM goal"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM project"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM settlement_activity"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM location_record"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM recurrence_series"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM business_transaction"))
        assertTrue(scalar("SELECT COUNT(*) FROM credit_statement") >= 1L)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM installment_plan"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM loan_contract"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM budget_month"))
        assertEquals(17L, scalar("SELECT COUNT(DISTINCT first_row_number) FROM import_batch_commit"))

        val replay = port.commit(request).success()
        assertTrue(replay.replayed)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM business_transaction"))

        val undo = port.undo(
            ImportFinancialUndoRequest(BOOK_ID, id(90_003), id(90_004), Instant.parse("2026-08-09T12:01:00Z")),
        ).success()
        assertEquals(17L, undo.reversedRows)
        assertFalse(undo.replayed)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM user_account"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM business_transaction"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM location_record"))
        val replayedUndo = port.undo(
            ImportFinancialUndoRequest(BOOK_ID, id(90_003), id(90_005), Instant.parse("2026-08-09T12:02:00Z")),
        ).success()
        assertTrue(replayedUndo.replayed)

        val auditPort = SecureRoomImportFinancialApplicationPort(
            context,
            keys,
            SecureRoomReferenceDataManagementPort(context, keys),
            StableIdSource { id(900_000) },
        )
        val audit = auditPort.audit(BOOK_ID, id(90_003)).success()
        requireNotNull(audit)
        assertEquals(17L, audit.importedRows)
        assertTrue(audit.reversed)
    }

    private fun entityRows(): List<StructuredImportRow> = listOf(
        row(1, StructuredImportEntityType.ACCOUNT, "id" to BANK.toString(), "name" to "Bank", "currency" to "JPY", "type" to "BANK", "ledger_account_id" to BANK_LEDGER.toString()),
        row(2, StructuredImportEntityType.ACCOUNT, "id" to CREDIT.toString(), "name" to "Credit", "currency" to "JPY", "type" to "CREDIT", "ledger_account_id" to CREDIT_LEDGER.toString()),
        row(3, StructuredImportEntityType.ACCOUNT, "id" to LOAN_ACCOUNT.toString(), "name" to "Loan", "currency" to "JPY", "type" to "LOAN", "ledger_account_id" to LOAN_LEDGER.toString()),
        row(4, StructuredImportEntityType.CARD, "id" to CARD.toString(), "account_id" to CREDIT.toString(), "name" to "Main card", "type" to "CREDIT_PRIMARY"),
        row(5, StructuredImportEntityType.CATEGORY, "id" to CATEGORY.toString(), "name" to "Food", "direction" to "EXPENSE"),
        row(6, StructuredImportEntityType.MERCHANT, "id" to MERCHANT.toString(), "name" to "Cafe"),
        row(7, StructuredImportEntityType.PLACE, "id" to PLACE.toString(), "name" to "Tokyo", "latitude_e7" to "356800000", "longitude_e7" to "1397600000", "merchant_id" to MERCHANT.toString()),
        row(8, StructuredImportEntityType.GOAL, "id" to GOAL.toString(), "account_id" to BANK.toString(), "name" to "Reserve", "target_amount" to "100000"),
        row(9, StructuredImportEntityType.PROJECT, "id" to PROJECT.toString(), "name" to "Trip", "start_date" to "2026-08-01", "goal_id" to GOAL.toString()),
        row(
            10,
            StructuredImportEntityType.SETTLEMENT_ACTIVITY,
            "id" to ACTIVITY.toString(),
            "name" to "Dinner",
            "currency" to "JPY",
            "start_date" to "2026-08-01",
            "participant_ids" to "$SELF|$FRIEND",
            "participant_names" to "Me|Friend",
            "self_participant_id" to SELF.toString(),
        ),
        row(11, StructuredImportEntityType.LOCATION, "id" to LOCATION.toString(), "latitude_e7" to "356800100", "longitude_e7" to "1397600100", "captured_at" to "2026-08-09T01:00:00Z", "place_id" to PLACE.toString()),
        row(
            12,
            StructuredImportEntityType.RECURRENCE,
            "id" to RECURRENCE.toString(),
            "name" to "Monthly food",
            "transaction_kind" to "EXPENSE",
            "category_id" to CATEGORY.toString(),
            "primary_account_id" to BANK.toString(),
            "frequency" to "MONTHLY_DAY",
            "month_day" to "1",
            "start_at" to "2026-09-01",
        ),
        row(14, StructuredImportEntityType.CREDIT_STATEMENT, "id" to STATEMENT.toString(), "account_id" to CREDIT.toString(), "cycle_start" to "2026-08-01", "cycle_end" to "2026-08-31", "due_date" to "2026-09-10", "official_amount_minor" to "12000"),
        row(15, StructuredImportEntityType.INSTALLMENT, "id" to INSTALLMENT.toString(), "purchase_transaction_id" to PURCHASE.toString(), "credit_account_id" to CREDIT.toString(), "currency" to "JPY", "original_principal_minor" to "12000", "term_count" to "12", "first_statement_date" to "2026-09-25"),
        row(
            16,
            StructuredImportEntityType.LOAN,
            "id" to LOAN.toString(),
            "account_id" to LOAN_ACCOUNT.toString(),
            "ledger_account_id" to LOAN_LEDGER.toString(),
            "name" to "Home loan",
            "principal" to "120000",
            "currency" to "JPY",
            "start_date" to "2026-08-01",
            "end_date" to "2027-07-31",
            "payment_count" to "12",
            "first_payment_date" to "2026-08-31",
            "annual_rate" to "0.03",
        ),
        row(17, StructuredImportEntityType.BUDGET, "id" to BUDGET.toString(), "month" to "2026-08", "amount" to "50000", "category_ids" to CATEGORY.toString(), "category_limit_minors" to "50000"),
    )

    private fun transactionPage(): ImportFinancialPage {
        val ids = OrdinaryTransactionWriteIds(
            BOOK_ID,
            id(93_001),
            PURCHASE,
            id(93_003),
            id(93_004),
            id(93_005),
            (93_010L..93_320L).map(::id),
            emptyList(),
        )
        val transaction = BatchEntryRowWriteRequest.Ordinary(
            id(93_000),
            OrdinaryTransactionWriteRequest(
                ids,
                null,
                OrdinaryDirection.EXPENSE,
                CATEGORY,
                OrdinaryAmountDraft("12000", 12_000, currency("JPY"), 12_000, 12_000),
                CREDIT,
                CARD,
                MERCHANT,
                Instant.parse("2026-08-09T03:00:00Z"),
                ZONE,
                LocalDate.of(2026, 8, 9),
                PROJECT,
                null,
                null,
                emptyList(),
                LOCATION,
                null,
                "structured purchase",
                emptyList(),
                TransactionSource.STRUCTURED_IMPORT,
                id(90_001),
                Instant.parse("2026-08-09T03:00:01Z"),
            ),
        )
        return ImportFinancialPage(
            1,
            1,
            BatchEntrySubmitRequest(BOOK_ID, CommandId(id(93_100)), id(93_004), id(93_005), Instant.parse("2026-08-09T03:00:01Z"), listOf(transaction), true),
            listOf(ImportSourceRow(13, PURCHASE, Hash256.sha256("purchase".toByteArray()))),
            true,
        )
    }

    private fun entitySource(rows: List<StructuredImportRow>) = StructuredImportPageSource { phase, offset, limit ->
        val allowed = if (phase == StructuredImportPhase.BEFORE_TRANSACTIONS) {
            rows.filter { it.entityType.ordinal < StructuredImportEntityType.TRANSACTION.ordinal }
        } else {
            rows.filter { it.entityType.ordinal > StructuredImportEntityType.TRANSACTION.ordinal }
        }
        DomainResult.Success(allowed.drop(offset.toInt()).take(limit))
    }

    private fun row(number: Long, type: StructuredImportEntityType, vararg values: Pair<String, String>) = StructuredImportRow(number, type, StructuredImportValues(values.toMap()))

    private fun insertOperation(operationId: StableId) = withDatabase(true) { database ->
        database.execSQL(
            "INSERT INTO background_operation(id,uid,type,state,created_at,started_at,updated_at,progress_current," +
                "progress_total,checkpoint_version,error_code,cancel_requested,parameters_ciphertext) " +
                "VALUES((SELECT COALESCE(MAX(id),0)+1 FROM background_operation),?,0,2,1,1,1,0,NULL,0,NULL,0,?)",
            arrayOf(operationId.bytes, byteArrayOf(1)),
        )
    }

    private fun scalar(sql: String): Long = withDatabase(false) { database ->
        database.query(sql).use {
            it.moveToFirst()
            it.getLong(0)
        }
    }

    private fun <T> withDatabase(write: Boolean, block: (SupportSQLiteDatabase) -> T): T = keys.open(BOOK_ID).use { opened ->
        val database = opened.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
        try {
            if (write) database.inLedgerTransaction(block) else database.readLedger(block)
        } finally {
            database.close()
        }
    }

    private fun currency(value: String): CurrencyCode = requireNotNull(CurrencyCode.parse(value).getOrNull())
    private fun deleteTestCopies() {
        context.databaseList()
            .filter { it.startsWith("ledger_shadow_") || it.startsWith("ledger_safety_") }
            .forEach(context::deleteDatabase)
    }
    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x28L, value))
    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error("${error.code}:$error")
    }

    private companion object {
        val BOOK_ID = StableId.fromUuid(UUID(0x28L, 1))
        val BANK = StableId.fromUuid(UUID(0x28L, 91_001))
        val BANK_LEDGER = StableId.fromUuid(UUID(0x28L, 91_002))
        val CREDIT = StableId.fromUuid(UUID(0x28L, 91_003))
        val CREDIT_LEDGER = StableId.fromUuid(UUID(0x28L, 91_004))
        val LOAN_ACCOUNT = StableId.fromUuid(UUID(0x28L, 91_005))
        val LOAN_LEDGER = StableId.fromUuid(UUID(0x28L, 91_006))
        val CARD = StableId.fromUuid(UUID(0x28L, 91_010))
        val CATEGORY = StableId.fromUuid(UUID(0x28L, 91_011))
        val MERCHANT = StableId.fromUuid(UUID(0x28L, 91_012))
        val PLACE = StableId.fromUuid(UUID(0x28L, 91_013))
        val GOAL = StableId.fromUuid(UUID(0x28L, 91_014))
        val PROJECT = StableId.fromUuid(UUID(0x28L, 91_015))
        val ACTIVITY = StableId.fromUuid(UUID(0x28L, 91_016))
        val SELF = StableId.fromUuid(UUID(0x28L, 91_017))
        val FRIEND = StableId.fromUuid(UUID(0x28L, 91_018))
        val LOCATION = StableId.fromUuid(UUID(0x28L, 91_019))
        val RECURRENCE = StableId.fromUuid(UUID(0x28L, 91_020))
        val PURCHASE = StableId.fromUuid(UUID(0x28L, 91_021))
        val STATEMENT = StableId.fromUuid(UUID(0x28L, 91_022))
        val INSTALLMENT = StableId.fromUuid(UUID(0x28L, 91_023))
        val LOAN = StableId.fromUuid(UUID(0x28L, 91_024))
        val BUDGET = StableId.fromUuid(UUID(0x28L, 91_025))
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
