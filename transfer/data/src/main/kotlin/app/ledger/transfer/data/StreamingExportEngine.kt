@file:Suppress(
    "LongMethod",
    "MagicNumber",
    "NestedBlockDepth",
    "SpreadOperator",
    "SwallowedException",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package app.ledger.transfer.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.system.ErrnoException
import android.system.OsConstants
import app.ledger.core.common.DomainResult
import app.ledger.transfer.domain.ExportDescriptor
import app.ledger.transfer.domain.ExportFailure
import app.ledger.transfer.domain.ExportFormat
import app.ledger.transfer.domain.ExportMetadata
import app.ledger.transfer.domain.ExportProgressObserver
import app.ledger.transfer.domain.ExportResult
import app.ledger.transfer.domain.ExportTablePage
import app.ledger.transfer.domain.ExportTabularSource
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.dhatim.fastexcel.Workbook
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class StreamingExportEngine {
    suspend fun export(
        descriptor: ExportDescriptor,
        source: ExportTabularSource,
        output: OutputStream,
        cancelled: () -> Boolean = { false },
        progress: ExportProgressObserver = ExportProgressObserver {},
    ): DomainResult<ExportResult> = try {
        ensureSafeDescriptor(descriptor)
        val metadata = source.metadata().valueOrThrow()
        val counting = CountingOutputStream(output)
        val result = when (descriptor.format) {
            ExportFormat.CSV -> writeCsv(metadata, source, counting, cancelled, progress)
            ExportFormat.XLSX -> writeXlsx(metadata, source, counting, cancelled, progress)
            ExportFormat.PDF -> writePdf(metadata, source, counting, cancelled, progress)
            ExportFormat.IMAGE -> writeImage(metadata, source, counting, cancelled, progress)
            ExportFormat.PORTABLE_BACKUP -> error("ordinary export cannot create backup containers")
        }
        DomainResult.Success(result.copy(bytesWritten = counting.count))
    } catch (_: ExportCancelledException) {
        DomainResult.Failure(ExportFailure.Cancelled)
    } catch (failure: ExportSourceException) {
        DomainResult.Failure(failure.failure)
    } catch (security: SecurityException) {
        DomainResult.Failure(ExportFailure.PermissionRevoked)
    } catch (io: IOException) {
        DomainResult.Failure(if (io.isSpaceFailure()) ExportFailure.InsufficientSpace else ExportFailure.DestinationUnavailable)
    } catch (_: Exception) {
        DomainResult.Failure(ExportFailure.DestinationUnavailable)
    }

    private suspend fun writeCsv(
        metadata: ExportMetadata,
        source: ExportTabularSource,
        output: CountingOutputStream,
        cancelled: () -> Boolean,
        progress: ExportProgressObserver,
    ): ExportResult {
        output.write(UTF8_BOM)
        val sheet = source.sheetNames().valueOrThrow().single()
        val first = source.page(sheet, null, PAGE_SIZE).valueOrThrow()
        val format = CSVFormat.DEFAULT.builder().setCommentMarker('#').setHeader(*first.headers.toTypedArray()).get()
        var rows = 0L
        OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
            CSVPrinter(writer, format).use { printer ->
                metadataLines(metadata).forEach(printer::printComment)
                var page = first
                while (true) {
                    page.rows.forEach { row ->
                        checkCancelled(cancelled)
                        printer.printRecord(row.map(::spreadsheetSafe))
                        rows++
                    }
                    progress.onProgress(rows)
                    val next = page.nextKey ?: break
                    page = source.page(sheet, next, PAGE_SIZE).valueOrThrow()
                }
            }
        }
        return ExportResult(rows, 1, output.count, "text/csv")
    }

    private suspend fun writeXlsx(
        metadata: ExportMetadata,
        source: ExportTabularSource,
        output: CountingOutputStream,
        cancelled: () -> Boolean,
        progress: ExportProgressObserver,
    ): ExportResult {
        var rows = 0L
        val sheets = source.sheetNames().valueOrThrow()
        Workbook(output, "ExpenseTracker", FASTEXCEL_APPLICATION_VERSION).use { workbook ->
            workbook.newWorksheet("metadata").use { worksheet ->
                metadataLines(metadata).forEachIndexed { index, line ->
                    val separator = line.indexOf('=')
                    worksheet.value(index, 0, if (separator > 0) line.substring(0, separator) else "metadata")
                    worksheet.value(index, 1, if (separator > 0) line.substring(separator + 1) else line)
                }
            }
            sheets.forEach { sheetName ->
                checkCancelled(cancelled)
                workbook.newWorksheet(safeSheetName(sheetName)).use { worksheet ->
                    var page = source.page(sheetName, null, PAGE_SIZE).valueOrThrow()
                    page.headers.forEachIndexed { column, header -> worksheet.value(0, column, header) }
                    var targetRow = 1
                    while (true) {
                        page.rows.forEach { row ->
                            checkCancelled(cancelled)
                            row.forEachIndexed { column, value -> worksheet.value(targetRow, column, spreadsheetSafe(value)) }
                            targetRow++
                            rows++
                        }
                        progress.onProgress(rows)
                        val next = page.nextKey ?: break
                        page = source.page(sheetName, next, PAGE_SIZE).valueOrThrow()
                    }
                }
            }
        }
        return ExportResult(rows, sheets.size + 1, output.count, XLSX_MIME)
    }

    private suspend fun writePdf(
        metadata: ExportMetadata,
        source: ExportTabularSource,
        output: CountingOutputStream,
        cancelled: () -> Boolean,
        progress: ExportProgressObserver,
    ): ExportResult {
        val sheet = source.sheetNames().valueOrThrow().single()
        var page = source.page(sheet, null, PAGE_SIZE).valueOrThrow()
        var rows = 0L
        var pageNumber = 0
        var documentPage: PdfDocument.Page? = null
        var rowOnPage = ROWS_PER_PDF_PAGE
        val document = PdfDocument()
        try {
            fun newPage(headers: List<String>): PdfDocument.Page {
                documentPage?.let(document::finishPage)
                pageNumber++
                val next = document.startPage(PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, pageNumber).create())
                drawPdfHeader(next.canvas, metadata, headers, pageNumber)
                rowOnPage = 0
                documentPage = next
                return next
            }
            while (true) {
                page.rows.forEach { row ->
                    checkCancelled(cancelled)
                    val target = if (rowOnPage >= ROWS_PER_PDF_PAGE) newPage(page.headers) else requireNotNull(documentPage)
                    drawPdfRow(target.canvas, row, rowOnPage)
                    rowOnPage++
                    rows++
                }
                progress.onProgress(rows)
                val next = page.nextKey ?: break
                page = source.page(sheet, next, PAGE_SIZE).valueOrThrow()
            }
            if (documentPage == null) newPage(page.headers)
            documentPage?.let(document::finishPage)
            documentPage = null
            document.writeTo(output)
        } finally {
            document.close()
        }
        return ExportResult(rows, pageNumber, output.count, "application/pdf")
    }

    private suspend fun writeImage(
        metadata: ExportMetadata,
        source: ExportTabularSource,
        output: CountingOutputStream,
        cancelled: () -> Boolean,
        progress: ExportProgressObserver,
    ): ExportResult {
        val sheet = source.sheetNames().valueOrThrow().single()
        var page = source.page(sheet, null, PAGE_SIZE).valueOrThrow()
        val sampled = ArrayList<List<String>>(IMAGE_SAMPLE_ROWS)
        var rows = 0L
        val headers = page.headers
        while (true) {
            page.rows.forEach { row ->
                checkCancelled(cancelled)
                if (sampled.size < IMAGE_SAMPLE_ROWS) sampled += row
                rows++
            }
            progress.onProgress(rows)
            val next = page.nextKey ?: break
            page = source.page(sheet, next, PAGE_SIZE).valueOrThrow()
        }
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(28, 31, 36)
                textSize = 30f
            }
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("ExpenseTracker report", IMAGE_MARGIN, 58f, paint)
            paint.typeface = Typeface.DEFAULT
            paint.textSize = 18f
            canvas.drawText("${metadata.generatedAt} · rows=$rows", IMAGE_MARGIN, 90f, paint)
            canvas.drawText(metadata.disclaimer, IMAGE_MARGIN, 120f, paint)
            drawImageTable(canvas, paint, headers, sampled)
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("PNG encoder failed")
        } finally {
            bitmap.recycle()
        }
        return ExportResult(rows, 1, output.count, "image/png")
    }

    private fun drawPdfHeader(canvas: Canvas, metadata: ExportMetadata, headers: List<String>, pageNumber: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("ExpenseTracker report · page $pageNumber", PDF_MARGIN, 36f, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 9f
        canvas.drawText("${metadata.generatedAt} · ${metadata.filterSummary}", PDF_MARGIN, 54f, paint)
        canvas.drawText(metadata.disclaimer, PDF_MARGIN, 68f, paint)
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(headers.take(PDF_VISIBLE_COLUMNS).joinToString(" | ").take(PDF_LINE_CHARS), PDF_MARGIN, 88f, paint)
    }

    private fun drawPdfRow(canvas: Canvas, row: List<String>, index: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 8f
        }
        val value = row.take(PDF_VISIBLE_COLUMNS).joinToString(" | ").replace('\n', ' ').take(PDF_LINE_CHARS)
        canvas.drawText(value, PDF_MARGIN, 108f + index * PDF_ROW_HEIGHT, paint)
    }

    private fun drawImageTable(canvas: Canvas, paint: Paint, headers: List<String>, rows: List<List<String>>) {
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(headers.take(IMAGE_VISIBLE_COLUMNS).joinToString(" | ").take(IMAGE_LINE_CHARS), IMAGE_MARGIN, 165f, paint)
        paint.typeface = Typeface.DEFAULT
        rows.forEachIndexed { index, row ->
            canvas.drawText(
                row.take(IMAGE_VISIBLE_COLUMNS).joinToString(" | ").replace('\n', ' ').take(IMAGE_LINE_CHARS),
                IMAGE_MARGIN,
                200f + index * 30f,
                paint,
            )
        }
    }

    private fun ensureSafeDescriptor(descriptor: ExportDescriptor) {
        val forbidden = setOf("pan", "card_number", "security_code", "cvc", "cvv", "vault", "password", "ciphertext")
        if (descriptor.fields.any { field -> forbidden.any { token -> token in field.header.lowercase() } }) {
            throw ExportSourceException(ExportFailure.SensitiveFieldRejected)
        }
    }

    private fun metadataLines(metadata: ExportMetadata): List<String> = listOf(
        "export_schema_version=${metadata.schemaVersion}",
        "application_version=${metadata.applicationVersion}",
        "generated_at=${metadata.generatedAt}",
        "content=${metadata.content.name}",
        "filter_summary=${metadata.filterSummary.replace('\n', ' ')}",
        "as_of_local_revision=${metadata.localRevision}",
        "as_of_valuation_revision=${metadata.valuationRevision.orEmpty()}",
        "disclaimer=${metadata.disclaimer}",
    )

    private fun Long?.orEmpty(): String = this?.toString().orEmpty()
    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw ExportCancelledException
    }
    private fun safeSheetName(value: String): String = value.replace(Regex("[\\\\/*?:\\[\\]]"), "_").take(31).ifBlank { "data" }
    private fun spreadsheetSafe(value: String): String = if (value.firstOrNull() in setOf('=', '+', '@') || value.startsWith("-@")) "'$value" else value

    private fun <T> DomainResult<T>.valueOrThrow(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> throw ExportSourceException(if (error is ExportFailure) error as ExportFailure else ExportFailure.SourceUnavailable)
    }

    private companion object {
        const val PAGE_SIZE = 256
        const val ROWS_PER_PDF_PAGE = 45
        const val PDF_VISIBLE_COLUMNS = 8
        const val PDF_LINE_CHARS = 155
        const val PDF_WIDTH = 595
        const val PDF_HEIGHT = 842
        const val PDF_MARGIN = 24f
        const val PDF_ROW_HEIGHT = 15f
        const val IMAGE_WIDTH = 1200
        const val IMAGE_HEIGHT = 900
        const val IMAGE_MARGIN = 30f
        const val IMAGE_SAMPLE_ROWS = 22
        const val IMAGE_VISIBLE_COLUMNS = 6
        const val IMAGE_LINE_CHARS = 100
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val FASTEXCEL_APPLICATION_VERSION = "1.0"
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}

private data object ExportCancelledException : RuntimeException()
private class ExportSourceException(val failure: ExportFailure) : RuntimeException(failure.code)

private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    var count: Long = 0L
        private set

    override fun write(value: Int) {
        out.write(value)
        count++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        out.write(bytes, offset, length)
        count = Math.addExact(count, length.toLong())
    }
}

private fun IOException.isSpaceFailure(): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is ErrnoException && cause.errno == OsConstants.ENOSPC) return true
        cause = cause.cause
    }
    return message?.contains("ENOSPC", ignoreCase = true) == true || message?.contains("No space", ignoreCase = true) == true
}
