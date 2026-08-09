@file:Suppress("LongMethod", "MagicNumber", "NestedBlockDepth")

package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.transfer.domain.ImportCancellationSignal
import app.ledger.transfer.domain.ImportCellKind
import app.ledger.transfer.domain.ImportFailure
import app.ledger.transfer.domain.ImportInput
import app.ledger.transfer.domain.ImportReadRequest
import kotlinx.coroutines.runBlocking
import org.dhatim.fastexcel.Workbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ImportStreamReaderTest {
    @Test
    fun csvStreamsOneHundredThousandNonAsciiRowsWithBomAndBoundedBuffer() = runBlocking {
        val bytes = ByteArrayOutputStream().apply {
            write(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
            writer(Charsets.UTF_8).use { writer ->
                writer.appendLine("date,amount,note")
                repeat(100_000) { index ->
                    writer.append("2026-08-09,").append((index + 1).toString()).append(",東京-")
                        .append(index.toString()).append('\n')
                }
            }
        }.toByteArray()
        var count = 0L
        var lastNote = ""
        val result = AndroidCsvImportReader().read(request(bytes)) { row ->
            count++
            lastNote = requireNotNull(row.cells[2].canonicalValue)
        }.success()

        assertEquals(100_000L, count)
        assertEquals(100_000L, result.rowCount)
        assertEquals(1, result.peakBufferedRows)
        assertEquals("UTF-8", result.bomCharset)
        assertEquals("東京-99999", lastNote)
    }

    @Test
    fun csvHonoursManualIcuCharsetAndCancellation() = runBlocking {
        val csv = "note,amount\n東京,42\n".toByteArray(charset("Shift_JIS"))
        var note = ""
        val decoded = AndroidCsvImportReader().read(request(csv, "Shift_JIS")) { row ->
            note = requireNotNull(row.cells.first().canonicalValue)
        }.success()
        assertEquals("Shift_JIS", decoded.selectedCharset)
        assertEquals("東京", note)

        var rows = 0
        val cancelled = AndroidCsvImportReader().read(
            request("h\n1\n2\n3\n".toByteArray()).copy(
                cancellation = ImportCancellationSignal { rows >= 2 },
            ),
        ) { rows++ }
        assertEquals(ImportFailure.Cancelled, (cancelled as DomainResult.Failure).error)
    }

    @Test
    fun xlsxStreamsMultipleSheetsAndCachedFormulaTypes() = runBlocking {
        val workbookBytes = ByteArrayOutputStream().use { output ->
            Workbook(output, "ledger-test", "01.2026").use { workbook ->
                workbook.newWorksheet("accounts").use { sheet ->
                    sheet.value(0, 0, "name")
                    sheet.value(0, 1, "opened_on")
                    sheet.value(1, 0, "现金账户")
                    sheet.value(1, 1, LocalDate.of(2026, 8, 9))
                    sheet.style(1, 1).format("yyyy-mm-dd").set()
                }
                workbook.newWorksheet("transactions").use { sheet ->
                    sheet.value(0, 0, "amount")
                    sheet.value(0, 1, "note")
                    sheet.value(0, 2, "occurred_on")
                    sheet.value(0, 3, "cached_formula")
                    repeat(100_000) { index ->
                        val row = index + 1
                        sheet.value(row, 0, index + 0.25)
                        sheet.value(row, 1, "東京-$index")
                        sheet.value(row, 2, LocalDate.of(2026, 8, 9))
                        sheet.style(row, 2).format("yyyy-mm-dd").set()
                        if (index == 0) sheet.formula(row, 3, "1+1")
                    }
                }
            }
            output.toByteArray()
        }.withFormulaCachedValue()

        var count = 0L
        var sawDate = false
        var sawDecimal = false
        var cachedFormula: Pair<String?, String?>? = null
        val summary = FastExcelImportReader().read(request(workbookBytes)) { row ->
            count++
            sawDate = sawDate || row.cells.any { it.kind == ImportCellKind.DATE }
            sawDecimal = sawDecimal || row.cells.any { it.kind == ImportCellKind.DECIMAL }
            row.cells.firstOrNull { it.formula != null }?.let { cachedFormula = it.formula to it.canonicalValue }
        }.success()

        assertEquals(100_001L, count)
        assertEquals(listOf("accounts", "transactions"), summary.sheets.map { it.name })
        assertEquals(1, summary.peakBufferedRows)
        assertTrue(sawDate)
        assertTrue(sawDecimal)
        assertEquals("1+1" to "2", cachedFormula)
    }

    @Test
    fun xlsxRejectsLegacyAndCorruptContainers() = runBlocking {
        val legacy = ByteArray(16).also { bytes ->
            byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte())
                .copyInto(bytes)
        }
        val unsupported = FastExcelImportReader().read(request(legacy)) { }
        assertEquals(ImportFailure.UnsupportedSource, (unsupported as DomainResult.Failure).error)
        val corrupt = FastExcelImportReader().read(request("not-xlsx".toByteArray())) { }
        assertEquals(ImportFailure.CorruptSource, (corrupt as DomainResult.Failure).error)
    }

    private fun request(bytes: ByteArray, charset: String? = null): ImportReadRequest = ImportReadRequest(
        ImportInput { ByteArrayInputStream(bytes) },
        userCharset = charset,
    )

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }
}

private fun ByteArray.withFormulaCachedValue(): ByteArray {
    val output = ByteArrayOutputStream()
    val inputFile = Files.createTempFile("ledger-import-formula", ".xlsx")
    try {
        Files.write(inputFile, this)
        ZipFile(inputFile.toFile()).use { zipInput ->
            ZipOutputStream(output).use { zipOutput ->
                zipInput.entries().asSequence().forEach { inputEntry ->
                    zipOutput.putNextEntry(ZipEntry(inputEntry.name))
                    val content = zipInput.getInputStream(inputEntry).use { it.readBytes() }
                    val patched = if (inputEntry.name.startsWith("xl/worksheets/sheet")) {
                        content.toString(Charsets.UTF_8)
                            .replace("<f>1+1</f></c>", "<f>1+1</f><v>2</v></c>")
                            .toByteArray(Charsets.UTF_8)
                    } else {
                        content
                    }
                    zipOutput.write(patched)
                    zipOutput.closeEntry()
                }
            }
        }
    } finally {
        Files.deleteIfExists(inputFile)
    }
    return output.toByteArray()
}
