@file:Suppress("LongMethod", "MagicNumber")

package app.ledger.transfer.data

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.application.LedgerExportBookMetadata
import app.ledger.finance.application.LedgerExportCursor
import app.ledger.finance.application.LedgerExportPage
import app.ledger.finance.application.LedgerExportQueryPort
import app.ledger.finance.application.LedgerWorkbookSheet
import app.ledger.finance.domain.TransactionFilter
import app.ledger.transfer.domain.ExportContent
import app.ledger.transfer.domain.ExportDescriptor
import app.ledger.transfer.domain.ExportFailure
import app.ledger.transfer.domain.ExportField
import app.ledger.transfer.domain.ExportFormat
import app.ledger.transfer.domain.ExportMetadata
import app.ledger.transfer.domain.ExportReportSnapshot
import app.ledger.transfer.domain.ExportTablePage
import app.ledger.transfer.domain.ExportTabularSource
import app.ledger.transfer.domain.ImportInput
import app.ledger.transfer.domain.ImportReadRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StreamingExportScaleDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun hundredThousandRowCsvStreamsUtf8MetadataAndNonAsciiWithoutLargeBuffers() = runBlocking {
        assertTrue(context.getSystemService(ActivityManager::class.java).memoryClass <= 512)
        val target = fresh("p29-100k.csv")
        val source = GeneratedSource(listOf("transactions"), mapOf("transactions" to 100_000))
        val result = target.outputStream().use { StreamingExportEngine().export(currentDescriptor(), source, it) }.success()
        assertEquals(100_000L, result.rows)
        assertTrue(target.length() > 1_000_000L)
        assertTrue(source.peakReturnedRows <= 256)
        target.inputStream().use { input -> assertEquals(listOf(0xEF, 0xBB, 0xBF), List(3) { input.read() }) }
        BufferedReader(target.reader()).use { reader ->
            val text = generateSequence(reader::readLine).take(20).joinToString("\n")
            assertTrue("not a complete backup" in text)
            assertTrue("東京" in text)
        }
        assertFalse(target.readText().contains(VAULT_SENTINEL))
    }

    @Test
    fun hundredThousandRowsAcrossFifteenXlsxSheetsRoundTripWithFastExcel() = runBlocking {
        val sheets = (1..15).map { "sheet_${it.toString().padStart(2, '0')}" }
        val counts = sheets.mapIndexed { index, sheet -> sheet to if (index == 0) 99_986 else 1 }.toMap()
        val source = GeneratedSource(sheets, counts)
        val target = fresh("p29-100k.xlsx")
        val descriptor = currentDescriptor().copy(content = ExportContent.FULL_WORKBOOK, format = ExportFormat.XLSX, fileName = target.name)
        val result = target.outputStream().use { StreamingExportEngine().export(descriptor, source, it) }.success()
        assertEquals(100_000L, result.rows)
        assertEquals(16, result.sheetsOrPages)
        assertTrue(source.peakReturnedRows <= 256)
        var parsed = 0L
        val read = FastExcelImportReader().read(
            ImportReadRequest(ImportInput { target.inputStream() }, selectedSheetNames = sheets.toSet()),
        ) { parsed++ }.success()
        assertEquals(100_000L, parsed)
        assertEquals(16, read.sheets.size)
        assertEquals(15, read.sheets.count { it.name in sheets })
        assertTrue(read.peakBufferedRows <= 1)
    }

    @Test
    fun largePdfAndHundredThousandRowImageFinishWithPageBoundedSources() = runBlocking {
        val pdfSource = GeneratedSource(listOf("report"), mapOf("report" to 12_000))
        val pdf = fresh("p29-large.pdf")
        val pdfResult = pdf.outputStream().use { StreamingExportEngine().export(reportDescriptor(ExportFormat.PDF), pdfSource, it) }.success()
        assertEquals(12_000L, pdfResult.rows)
        assertTrue(pdfResult.sheetsOrPages >= 267)
        assertTrue(pdf.length() > 100_000L)
        assertTrue(pdfSource.peakReturnedRows <= 256)

        val imageSource = GeneratedSource(listOf("report"), mapOf("report" to 100_000))
        val image = fresh("p29-large.png")
        val imageResult = image.outputStream().use { StreamingExportEngine().export(reportDescriptor(ExportFormat.IMAGE), imageSource, it) }.success()
        assertEquals(100_000L, imageResult.rows)
        assertTrue(image.length() > 10_000L)
        assertTrue(imageSource.peakReturnedRows <= 256)
    }

    @Test
    fun cancellationSpaceFailureAndPermissionRevocationReturnTypedStates() = runBlocking {
        var written = 0L
        val cancelled = StreamingExportEngine().export(
            currentDescriptor(),
            GeneratedSource(listOf("transactions"), mapOf("transactions" to 100_000)),
            object : OutputStream() {
                override fun write(value: Int) = Unit
            },
            cancelled = { written >= 512L },
            progress = { written = it },
        )
        assertEquals(ExportFailure.Cancelled, cancelled.failure())
        val noSpace = StreamingExportEngine().export(
            currentDescriptor(),
            GeneratedSource(listOf("transactions"), mapOf("transactions" to 1_000)),
            FailingOutputStream(IOException("ENOSPC: No space left on device")),
        )
        assertEquals(ExportFailure.InsufficientSpace, noSpace.failure())
        val revoked = StreamingExportEngine().export(
            currentDescriptor(),
            GeneratedSource(listOf("transactions"), mapOf("transactions" to 1_000)),
            FailingOutputStream(SecurityException("permission revoked")),
        )
        assertEquals(ExportFailure.PermissionRevoked, revoked.failure())
    }

    @Test
    fun ordinaryFieldSurfaceCannotRepresentVaultSecretsAndCoordinatesRequireOptIn() {
        val headers = ExportField.entries.map(ExportField::header)
        listOf("pan", "card_number", "security_code", "cvc", "cvv", "vault", "ciphertext").forEach { forbidden ->
            assertTrue(headers.none { forbidden in it.lowercase() })
        }
        assertTrue(ExportField.locationCoordinates.intersect(ExportField.defaultSelection).isEmpty())
        assertFalse(currentDescriptor().includeLocationCoordinates)
    }

    @Test
    fun preparedReportStreamsToCsvAndXlsxWithVersionedMetadata() = runBlocking {
        val snapshot = ExportReportSnapshot(
            "monthly-report",
            "2026-01-01",
            "2026-08-09",
            listOf("category", "amount"),
            listOf(listOf("食費", "12000 JPY"), listOf("交通", "8000 JPY")),
            42,
            7,
        )
        listOf(ExportFormat.CSV, ExportFormat.XLSX).forEach { format ->
            val descriptor = ExportDescriptor(
                ExportContent.REPORT,
                format,
                "report.${if (format == ExportFormat.CSV) "csv" else "xlsx"}",
                fields = setOf(ExportField.TRANSACTION_ID),
                filterSummary = "Monthly report",
                report = snapshot,
            )
            val target = fresh(descriptor.fileName)
            val source = LedgerExportTabularSource(
                StableId.fromUuid(UUID(0x29L, 0x100L)),
                descriptor,
                UNUSED_QUERY,
                Instant.parse("2026-08-09T00:00:00Z"),
                "29.0",
            )
            val result = target.outputStream().use { StreamingExportEngine().export(descriptor, source, it) }.success()
            assertEquals(2L, result.rows)
            if (format == ExportFormat.CSV) {
                val content = target.readText()
                assertTrue("application_version=29.0" in content)
                assertTrue("as_of_local_revision=42" in content)
                assertTrue("食費" in content)
            } else {
                var parsed = 0L
                val read = FastExcelImportReader().read(
                    ImportReadRequest(ImportInput { target.inputStream() }, selectedSheetNames = setOf("report")),
                ) { parsed++ }.success()
                assertEquals(2L, parsed)
                assertEquals(setOf("metadata", "report"), read.sheets.map { it.name }.toSet())
            }
        }
    }

    private fun currentDescriptor() = ExportDescriptor(
        ExportContent.CURRENT_FILTER,
        ExportFormat.CSV,
        "transactions.csv",
        fields = setOf(ExportField.TRANSACTION_ID, ExportField.NOTE),
        filterSummary = "Current filter",
    )

    private fun reportDescriptor(format: ExportFormat) = ExportDescriptor(
        ExportContent.REPORT,
        format,
        "report.${if (format == ExportFormat.PDF) "pdf" else "png"}",
        fields = setOf(ExportField.TRANSACTION_ID),
        filterSummary = "Report period",
        report = ExportReportSnapshot("monthly-report", "2026-01-01", "2026-08-09", listOf("id", "note"), listOf(listOf("1", "東京")), 42, 7),
    )

    private fun fresh(name: String): File = context.cacheDir.resolve(name).also { if (it.exists()) check(it.delete()) }

    private class GeneratedSource(
        private val sheets: List<String>,
        private val rowCounts: Map<String, Int>,
    ) : ExportTabularSource {
        var peakReturnedRows: Int = 0
            private set

        override suspend fun metadata() = DomainResult.Success(
            ExportMetadata(1, "p29-device", Instant.parse("2026-08-09T00:00:00Z"), ExportContent.REPORT, "Current filter", 42, 7),
        )

        override suspend fun sheetNames() = DomainResult.Success(sheets)

        override suspend fun page(sheetName: String, afterKey: Long?, limit: Int): DomainResult<ExportTablePage> {
            require(limit <= 256)
            val count = requireNotNull(rowCounts[sheetName])
            val start = (afterKey ?: 0L).toInt()
            val end = (start + limit).coerceAtMost(count)
            val rows = (start until end).map { index ->
                listOf("id-$index", if (index == 0) "東京-safe" else "row-$index")
            }
            peakReturnedRows = maxOf(peakReturnedRows, rows.size)
            return DomainResult.Success(ExportTablePage(listOf("id", "note"), rows, end.toLong().takeIf { end < count }))
        }
    }

    private class FailingOutputStream(private val failure: RuntimeException) : OutputStream() {
        constructor(failure: IOException) : this(RuntimeException(failure))
        override fun write(value: Int): Unit = throwFailure()
        override fun write(bytes: ByteArray, offset: Int, length: Int): Unit = throwFailure()
        private fun throwFailure(): Nothing {
            val cause = failure.cause
            if (cause is IOException) throw cause
            throw failure
        }
    }

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private fun DomainResult<*>.failure() = (this as DomainResult.Failure).error

    private companion object {
        const val VAULT_SENTINEL = "4111111111111111-CVC-123"
        val UNUSED_QUERY = object : LedgerExportQueryPort {
            override suspend fun metadata(bookId: StableId): DomainResult<LedgerExportBookMetadata> = error("report snapshot must be self-contained")
            override suspend fun currentTransactions(
                bookId: StableId,
                filter: TransactionFilter,
                headers: List<String>,
                cursor: LedgerExportCursor?,
                limit: Int,
            ): DomainResult<LedgerExportPage> = error("report snapshot must be self-contained")
            override suspend fun workbookSheet(
                bookId: StableId,
                sheet: LedgerWorkbookSheet,
                includeLocationCoordinates: Boolean,
                afterInternalId: Long,
                limit: Int,
            ): DomainResult<LedgerExportPage> = error("report snapshot must be self-contained")
        }
    }
}
