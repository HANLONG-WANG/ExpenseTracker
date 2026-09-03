@file:Suppress("LongMethod", "LongParameterList", "MagicNumber")

package app.ledger.finance.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainError
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
import app.ledger.finance.application.ImportFinancialCommitRequest
import app.ledger.finance.application.ImportFinancialPage
import app.ledger.finance.application.ImportFinancialPageSource
import app.ledger.finance.application.ImportFinancialUndoRequest
import app.ledger.finance.application.ImportSourceRow
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitialCategoryCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.application.OrdinaryAmountDraft
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryTransactionWriteIds
import app.ledger.finance.application.OrdinaryTransactionWriteRequest
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.Hash256
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
class ImportFinancialApplicationPortDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var databaseAccess: DeviceTestLedgerDatabaseAccess
    private lateinit var referenceData: SecureRoomReferenceDataManagementPort
    private lateinit var importPort: SecureRoomImportFinancialApplicationPort
    private var generatedId = 1_000_000L

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
        initialization.createFirstAccount(
            BOOK_ID,
            InitialAccountCommand(
                ACCOUNT_ID,
                id(201),
                id(202),
                id(203),
                id(204),
                Instant.ofEpochMilli(2_000),
                UserAccountType.CASH,
                "Wallet",
                currency("JPY"),
                "account",
                0xff006c4c.toInt(),
            ),
        ).success()
        initialization.createFirstCategory(
            BOOK_ID,
            InitialCategoryCommand(
                CATEGORY_ID,
                id(211),
                id(212),
                id(213),
                Instant.ofEpochMilli(3_000),
                CategoryDirection.EXPENSE,
                "Food",
                "food",
                StatisticalNature.CONSUMPTION_EXPENSE,
                "record",
                0xff006c4c.toInt(),
            ),
        ).success()
        generatedId = 1_000_000L
        importPort = SecureRoomImportFinancialApplicationPort(
            context,
            keys,
            referenceData,
            StableIdSource { id(generatedId++) },
            databaseAccess = databaseAccess,
        )
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        keys.destroyLocal(BOOK_ID)
    }

    @Test
    fun shadowCommitIsAtomicIdempotentAuditedAndWholeBatchUndoable() = runBlocking {
        val operationId = id(30_001)
        insertOperation(operationId)
        val request = commitRequest(
            operationId = operationId,
            importRecordId = id(30_002),
            batchId = id(30_003),
            fingerprintSeed = "shadow-success",
            totalRows = 2,
            shadowThreshold = 1,
            source = twoPageSource(id(30_003)),
        )

        val committed = importPort.commit(request).success()
        assertTrue(committed.usedShadowLedger)
        assertFalse(committed.replayed)
        assertEquals(2L, committed.importedRows)
        assertEquals(2, committed.pageCount)
        assertEquals(2L, scalar("SELECT COUNT(*) FROM business_transaction WHERE lifecycle_state=0"))

        val replay = importPort.commit(request).success()
        assertTrue(replay.replayed)
        assertEquals(2L, scalar("SELECT COUNT(*) FROM business_transaction"))
        val audit = requireNotNull(importPort.audit(BOOK_ID, id(30_003)).success())
        assertEquals(2L, audit.importedRows)
        assertFalse(audit.reversed)
        assertEquals(1, importPort.history(BOOK_ID).success().size)

        val undone = importPort.undo(
            ImportFinancialUndoRequest(BOOK_ID, id(30_003), id(30_099), Instant.ofEpochMilli(90_000)),
        ).success()
        assertEquals(2L, undone.reversedRows)
        assertTrue(requireNotNull(importPort.audit(BOOK_ID, id(30_003)).success()).reversed)
        assertEquals(2L, scalar("SELECT COUNT(*) FROM business_transaction WHERE lifecycle_state=1"))
        val undoReplay = importPort.undo(
            ImportFinancialUndoRequest(BOOK_ID, id(30_003), id(30_100), Instant.ofEpochMilli(91_000)),
        ).success()
        assertTrue(undoReplay.replayed)
    }

    @Test
    fun validationFailureAtSourceRow99999LeavesPrimaryLedgerStateUnchanged() = runBlocking {
        val operationId = id(40_001)
        insertOperation(operationId)
        val before = primarySnapshot()
        val failed = importPort.commit(
            commitRequest(
                operationId = operationId,
                importRecordId = id(40_002),
                batchId = id(40_003),
                fingerprintSeed = "late-row-failure",
                totalRows = 99_999,
                shadowThreshold = 1,
                source = ImportFinancialPageSource { _, _ -> DomainResult.Failure(Row99999Invalid) },
            ),
        )
        assertEquals(Row99999Invalid, (failed as DomainResult.Failure).error)
        assertEquals(before, primarySnapshot())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM business_transaction"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM import_record"))
    }

    private fun twoPageSource(firstBatchId: StableId): ImportFinancialPageSource = ImportFinancialPageSource { after, _ ->
        when (after) {
            0L -> DomainResult.Success(page(1, firstBatchId, false))
            1L -> DomainResult.Success(page(2, id(30_004), true))
            else -> DomainResult.Success(null)
        }
    }

    private fun page(rowNumber: Long, batchId: StableId, last: Boolean): ImportFinancialPage {
        val transaction = transactionRow(rowNumber, batchId)
        return ImportFinancialPage(
            rowNumber,
            rowNumber,
            BatchEntrySubmitRequest(
                BOOK_ID,
                CommandId(batchId),
                id(31_000 + rowNumber * 1_000),
                id(31_001 + rowNumber * 1_000),
                Instant.ofEpochMilli(50_000 + rowNumber),
                listOf(transaction),
                true,
            ),
            listOf(ImportSourceRow(rowNumber, transaction.transactionId, Hash256.sha256("row-$rowNumber".toByteArray()))),
            last,
        )
    }

    private fun transactionRow(rowNumber: Long, batchId: StableId): BatchEntryRowWriteRequest {
        val seed = 50_000 + rowNumber * 1_000
        return BatchEntryRowWriteRequest.Ordinary(
            id(seed),
            OrdinaryTransactionWriteRequest(
                OrdinaryTransactionWriteIds(
                    BOOK_ID,
                    id(seed + 1),
                    id(seed + 2),
                    id(seed + 3),
                    id(31_000 + rowNumber * 1_000),
                    id(31_001 + rowNumber * 1_000),
                    (seed + 10..seed + 260).map(::id),
                    (seed + 300..seed + 307).map(::id),
                ),
                null,
                OrdinaryDirection.EXPENSE,
                CATEGORY_ID,
                OrdinaryAmountDraft("100", 100, currency("JPY"), 100, 100),
                ACCOUNT_ID,
                null,
                null,
                Instant.parse("2026-08-09T01:00:00Z").plusSeconds(rowNumber),
                ZONE,
                LocalDate.of(2026, 8, 9),
                null,
                null,
                null,
                emptyList(),
                null,
                null,
                "import row $rowNumber",
                emptyList(),
                TransactionSource.XLSX_IMPORT,
                batchId,
                Instant.ofEpochMilli(50_000 + rowNumber),
            ),
        )
    }

    private fun commitRequest(
        operationId: StableId,
        importRecordId: StableId,
        batchId: StableId,
        fingerprintSeed: String,
        totalRows: Long,
        shadowThreshold: Long,
        source: ImportFinancialPageSource,
    ) = ImportFinancialCommitRequest(
        BOOK_ID,
        ImportCommitMetadata(
            operationId,
            importRecordId,
            batchId,
            2,
            Hash256.sha256(fingerprintSeed.toByteArray()),
            totalRows,
            Instant.ofEpochMilli(80_000),
        ),
        source,
        shadowThreshold,
    )

    private fun insertOperation(operationId: StableId) = withDatabase(write = true) { database ->
        database.execSQL(
            "INSERT INTO background_operation(id,uid,type,state,created_at,started_at,updated_at,progress_current," +
                "progress_total,checkpoint_version,error_code,cancel_requested,parameters_ciphertext) " +
                "VALUES((SELECT COALESCE(MAX(id),0)+1 FROM background_operation),?,0,2,1,1,1,0,NULL,0,NULL,0,?)",
            arrayOf(operationId.bytes, byteArrayOf(1)),
        )
    }

    private fun primarySnapshot(): List<Long> = withDatabase(write = false) { database ->
        listOf(
            scalar(database, "SELECT local_revision FROM book"),
            scalar(database, "SELECT COUNT(*) FROM book_commit"),
            scalar(database, "SELECT COUNT(*) FROM business_transaction"),
            scalar(database, "SELECT COUNT(*) FROM transaction_revision"),
            scalar(database, "SELECT COUNT(*) FROM import_record"),
        )
    }

    private fun scalar(sql: String): Long = withDatabase(write = false) { scalar(it, sql) }

    private fun scalar(database: SupportSQLiteDatabase, sql: String): Long = database.query(sql).use {
        check(it.moveToFirst())
        it.getLong(0)
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
    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x28L, value))
    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private data object Row99999Invalid : DomainError {
        override val code: String = "IMPORT_SOURCE_ROW_99999_INVALID"
    }

    private companion object {
        val BOOK_ID: StableId = StableId.fromUuid(UUID(0x28L, 1))
        val ACCOUNT_ID: StableId = StableId.fromUuid(UUID(0x28L, 200))
        val CATEGORY_ID: StableId = StableId.fromUuid(UUID(0x28L, 210))
        val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
