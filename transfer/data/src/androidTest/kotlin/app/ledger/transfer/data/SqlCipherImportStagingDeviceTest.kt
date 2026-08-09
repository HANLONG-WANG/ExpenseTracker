@file:Suppress("MagicNumber")

package app.ledger.transfer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.SecureImportSourceHandleStore
import app.ledger.core.security.SecureImportStagingAccess
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.TransactionId
import app.ledger.transfer.domain.BackgroundOperation
import app.ledger.transfer.domain.BackgroundOperationId
import app.ledger.transfer.domain.BackgroundOperationState
import app.ledger.transfer.domain.BackgroundOperationType
import app.ledger.transfer.domain.DuplicateMatchKind
import app.ledger.transfer.domain.ExportContent
import app.ledger.transfer.domain.ExportDescriptor
import app.ledger.transfer.domain.ExportField
import app.ledger.transfer.domain.ExportFilter
import app.ledger.transfer.domain.ExportFormat
import app.ledger.transfer.domain.ExportReportSnapshot
import app.ledger.transfer.domain.ImportCommitParameters
import app.ledger.transfer.domain.ImportFormat
import app.ledger.transfer.domain.ImportTargetField
import app.ledger.transfer.domain.ImportTransformation
import app.ledger.transfer.domain.OperationParameters
import app.ledger.transfer.domain.RawRowPayload
import app.ledger.transfer.domain.StagingDuplicateCandidate
import app.ledger.transfer.domain.StagingMapping
import app.ledger.transfer.domain.StagingParsedField
import app.ledger.transfer.domain.StagingParsedRow
import app.ledger.transfer.domain.StagingRawRow
import app.ledger.transfer.domain.StagingValue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SqlCipherImportStagingDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var access: SecureImportStagingAccess

    @Before
    fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("ledger.db")
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        keys.destroyLocal(BOOK_ID)
        keys.destroyLocal(OTHER_BOOK_ID)
        context.deleteDatabase("ledger.db")
        keys.initialize(BOOK_ID)
        keys.initialize(OTHER_BOOK_ID)
        access = SecureImportStagingAccess(context, keys)
    }

    @After
    fun cleanUp() {
        repository().let { runBlocking { it.destroy() } }
        keys.destroyLocal(BOOK_ID)
        keys.destroyLocal(OTHER_BOOK_ID)
    }

    @Test
    fun oneHundredThousandRowsRemainEncryptedPagedAndRestartRecoverable() = runBlocking {
        var repository = repository()
        repository.create(OPERATION_ID).success()
        var start = 1L
        while (start <= 100_000L) {
            val end = minOf(start + 255L, 100_001L)
            val raw = (start until end).map(::rawRow)
            val parsed = (start until end).map(::parsedRow)
            repository.appendRaw(raw).success()
            repository.appendParsed(parsed).success()
            start = end
        }

        assertEquals(100_000L, repository.counts().success().raw)
        assertEquals(512, repository.parsedRows(99_000L, 512).success().size)
        assertEquals(
            "東京-99999",
            (repository.parsedRows(99_999L, 512).success().single().fields.first().value as StagingValue.Text).value,
        )

        repository = repository()
        assertEquals(100_000L, repository.counts().success().parsed)
        assertEquals(100_000L, repository.rawRows(99_999L, 512).success().single().rowNumber)

        val databaseFile = context.getDatabasePath(databaseName())
        assertTrue(databaseFile.isFile)
        assertFalse(databaseFile.readBytes().containsSubsequence("東京-99999".toByteArray()))

        val wrongKeyRepository = SqlCipherStagingRepository(OTHER_BOOK_ID, OPERATION_ID, access)
        assertTrue(wrongKeyRepository.counts() is DomainResult.Failure)
        assertEquals(100_000L, repository.counts().success().raw)
    }

    @Test
    fun sourceUriIsEncryptedOutsideRoutesAndRestorableAfterProcessRecreation() {
        val store = SecureImportSourceHandleStore(context, keys)
        val handleId = StableId.fromUuid(UUID(0x28L, 4L))
        val uri = "content://document-provider/private/import.xlsx"
        store.save(BOOK_ID, handleId, uri)
        assertEquals(uri, SecureImportSourceHandleStore(context, keys).read(BOOK_ID, handleId))
        val handleFile = context.noBackupFilesDir.resolve("import_source_handles").resolve(
            "${handleId.bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }}.bin",
        )
        assertFalse(handleFile.readBytes().containsSubsequence(uri.toByteArray()))
        assertTrue(store.destroy(handleId))
    }

    @Test
    fun committingOperationParametersSurviveEncryptedRepositoryRecreation() = runBlocking {
        val access = SecurePrimaryLedgerAccess(context, keys)
        var repository = SqlCipherBackgroundOperationRepository(BOOK_ID, access)
        val source = StableId.fromUuid(UUID(0x28L, 10L))
        val importRecord = StableId.fromUuid(UUID(0x28L, 11L))
        val batch = StableId.fromUuid(UUID(0x28L, 12L))
        val created = Instant.ofEpochMilli(1_000L)
        val queued = BackgroundOperation.queued(
            OPERATION_ID,
            BackgroundOperationType.IMPORT,
            created,
            OperationParameters.Import(source, ImportFormat.XLSX, null, null, 3L),
        )
        val running = queued.transition(BackgroundOperationState.PREPARING, created.plusMillis(1)).success()
            .transition(BackgroundOperationState.RUNNING, created.plusMillis(2)).success()
        val commit = ImportCommitParameters(
            importRecord,
            batch,
            "JPY",
            "Asia/Tokyo",
            100_000L,
            99_995L,
            Hash256.sha256("prepared-rows".toByteArray()),
            4L,
            true,
        )
        val configured = running.configureImportCommit(
            (running.parameters as OperationParameters.Import).copy(commit = commit),
            created.plusMillis(3),
        ).success().transition(BackgroundOperationState.COMMITTING, created.plusMillis(4)).success()
        repository.save(configured).success()

        repository = SqlCipherBackgroundOperationRepository(BOOK_ID, SecurePrimaryLedgerAccess(context, keys))
        val restored = requireNotNull(repository.get(OPERATION_ID).success())
        assertEquals(BackgroundOperationState.COMMITTING, restored.state)
        assertEquals(commit, (restored.parameters as OperationParameters.Import).commit)
        assertFalse(context.getDatabasePath("ledger.db").readBytes().containsSubsequence("Asia/Tokyo".toByteArray()))
    }

    @Test
    fun exportDescriptorAndReportCheckpointSurviveEncryptedRepositoryRecreation() = runBlocking {
        val id = BackgroundOperationId(StableId.fromUuid(UUID(0x29L, 1L)))
        val handle = StableId.fromUuid(UUID(0x29L, 2L))
        val report = ExportReportSnapshot(
            "monthly-report",
            "2026-01-01",
            "2026-08-09",
            listOf("category", "amount"),
            listOf(listOf("食費", "12000 JPY")),
            42L,
            7L,
        )
        val descriptor = ExportDescriptor(
            ExportContent.REPORT,
            ExportFormat.XLSX,
            "report.xlsx",
            fields = setOf(ExportField.TRANSACTION_ID, ExportField.NOTE),
            filterSummary = "Current report · 東京",
            filter = ExportFilter(searchText = "食費"),
            report = report,
        )
        var repository = SqlCipherBackgroundOperationRepository(BOOK_ID, SecurePrimaryLedgerAccess(context, keys))
        repository.save(
            BackgroundOperation.queued(id, BackgroundOperationType.EXPORT, Instant.ofEpochMilli(29_000L), OperationParameters.Export(handle, descriptor)),
        ).success()
        repository = SqlCipherBackgroundOperationRepository(BOOK_ID, SecurePrimaryLedgerAccess(context, keys))
        val restored = requireNotNull(repository.get(id).success())
        assertEquals(descriptor, (restored.parameters as OperationParameters.Export).descriptor)
        assertFalse(context.getDatabasePath("ledger.db").readBytes().containsSubsequence("Current report · 東京".toByteArray()))
    }

    @Test
    fun mappingsAndDuplicateCandidatesRemainDurableAcrossStagingReopen() = runBlocking {
        var repository = repository()
        repository.create(OPERATION_ID).success()
        val mapping = StagingMapping(
            "账户",
            ImportTargetField.ACCOUNT,
            ImportTransformation.ClosedValueMap(mapOf("现金" to "create:现金")),
        )
        val duplicate = StagingDuplicateCandidate(
            99_999L,
            TransactionId(StableId.fromUuid(UUID(0x28L, 20L))),
            DuplicateMatchKind.CONTENT_HASH,
            "CONTENT_HASH",
        )
        // Duplicate/error/prepared records are deliberately FK-bound to an ingested source row.
        repository.appendRaw(listOf(rawRow(99_999L))).success()
        repository.saveMappings(listOf(mapping)).success()
        repository.saveDuplicates(listOf(duplicate)).success()

        repository = repository()
        assertEquals(listOf(mapping), repository.mappings().success())
        assertEquals(listOf(duplicate), repository.duplicateCandidates().success())
    }

    private fun repository(): SqlCipherStagingRepository = SqlCipherStagingRepository(BOOK_ID, OPERATION_ID, access)

    private fun rawRow(number: Long): StagingRawRow {
        val payload = "東京-${number - 1}".toByteArray()
        return StagingRawRow(number, requireNotNull(RawRowPayload.of(payload).getOrNull()), Hash256.sha256(payload))
    }

    private fun parsedRow(number: Long): StagingParsedRow = StagingParsedRow(
        number,
        listOf(StagingParsedField("note", StagingValue.Text("東京-${number - 1}"))),
    )

    private fun databaseName(): String = "import_${OPERATION_ID.value.bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }}.db"

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean = indices.any { start ->
        start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] }
    }

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private companion object {
        val BOOK_ID: StableId = StableId.fromUuid(UUID(0x28L, 1L))
        val OTHER_BOOK_ID: StableId = StableId.fromUuid(UUID(0x28L, 2L))
        val OPERATION_ID: BackgroundOperationId = BackgroundOperationId(StableId.fromUuid(UUID(0x28L, 3L)))
    }
}
