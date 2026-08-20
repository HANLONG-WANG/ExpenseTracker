@file:Suppress("LoopWithTooManyJumpStatements", "NestedBlockDepth", "TooGenericExceptionCaught")

package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.transfer.domain.ImportCancellationSignal
import app.ledger.transfer.domain.ImportCell
import app.ledger.transfer.domain.ImportCellKind
import app.ledger.transfer.domain.ImportFailure
import app.ledger.transfer.domain.ImportReadRequest
import app.ledger.transfer.domain.ImportReadSummary
import app.ledger.transfer.domain.ImportRowConsumer
import app.ledger.transfer.domain.ImportSheet
import app.ledger.transfer.domain.ImportStreamReader
import app.ledger.transfer.domain.ImportStreamRow
import com.ibm.icu.text.CharsetDetector
import org.apache.commons.csv.CSVFormat
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.nio.charset.Charset
import java.time.Instant
import java.time.LocalDate

/** Commons CSV record streaming with user override > BOM > Android ICU detection. */
class AndroidCsvImportReader : ImportStreamReader {
    override suspend fun read(request: ImportReadRequest, consumer: ImportRowConsumer): DomainResult<ImportReadSummary> = try {
        request.input.open().use { source ->
            val detected = detectEncoding(source, request.userCharset)
            InputStreamReader(detected.stream, detected.charset).use { reader ->
                CSVFormat.DEFAULT.builder()
                    .setCommentMarker('#')
                    .setIgnoreEmptyLines(false)
                    .get()
                    .parse(reader)
                    .use { parser ->
                        var headers: List<String>? = null
                        var rowCount = 0L
                        for (record in parser) {
                            ensureActive(request.cancellation)
                            val sourceRow = record.recordNumber
                            if (sourceRow < request.headerRowNumber) continue
                            if (sourceRow == request.headerRowNumber) {
                                headers = uniqueHeaders(record.values().asList())
                                continue
                            }
                            val names = headers ?: return DomainResult.Failure(ImportFailure.CorruptSource)
                            val cells = record.values().mapIndexed { index, value ->
                                ImportValueClassifier.cell(index, names.getOrElse(index) { "column_${index + 1}" }, value)
                            }
                            consumer.accept(ImportStreamRow(CSV_SHEET_NAME, sourceRow, cells))
                            rowCount++
                        }
                        if (headers == null) return DomainResult.Failure(ImportFailure.CorruptSource)
                        DomainResult.Success(
                            ImportReadSummary(
                                sheets = listOf(ImportSheet(CSV_SHEET_NAME, 0, null)),
                                rowCount = rowCount,
                                peakBufferedRows = 1,
                                selectedCharset = detected.charset.name(),
                                bomCharset = detected.bomCharset,
                            ),
                        )
                    }
            }
        }
    } catch (_: ImportCancelledException) {
        DomainResult.Failure(ImportFailure.Cancelled)
    } catch (_: IllegalArgumentException) {
        DomainResult.Failure(ImportFailure.InvalidEncoding)
    } catch (_: Exception) {
        DomainResult.Failure(ImportFailure.CorruptSource)
    }

    private fun detectEncoding(source: java.io.InputStream, userCharset: String?): DetectedInput {
        val stream = PushbackInputStream(source, DETECTION_BYTES)
        val sample = ByteArray(DETECTION_BYTES)
        var size = 0
        while (size < sample.size) {
            val count = stream.read(sample, size, sample.size - size)
            if (count < 0) break
            size += count
        }
        val actual = sample.copyOf(size)
        val bom = ByteOrderMark.detect(actual)
        if (size > bom.byteCount) stream.unread(actual, bom.byteCount, size - bom.byteCount)
        val selected = when {
            userCharset != null -> Charset.forName(userCharset)
            bom.charsetName != null -> Charset.forName(bom.charsetName)
            actual.isNotEmpty() -> Charset.forName(
                CharsetDetector().apply { setText(actual) }.detect()?.name ?: DEFAULT_CHARSET,
            )
            else -> Charset.forName(DEFAULT_CHARSET)
        }
        return DetectedInput(stream, selected, bom.charsetName)
    }

    private fun uniqueHeaders(values: List<String>): List<String> {
        val counts = mutableMapOf<String, Int>()
        return values.mapIndexed { index, value ->
            val base = value.trim().ifBlank { "column_${index + 1}" }
            val ordinal = counts.merge(base, 1, Int::plus) ?: 1
            if (ordinal == 1) base else "${base}_$ordinal"
        }
    }

    private data class DetectedInput(
        val stream: PushbackInputStream,
        val charset: Charset,
        val bomCharset: String?,
    )

    private companion object {
        const val DETECTION_BYTES: Int = 64 * 1024
        const val DEFAULT_CHARSET: String = "UTF-8"
        const val CSV_SHEET_NAME: String = "csv"
    }
}

internal object ImportValueClassifier {
    fun cell(index: Int, name: String, value: String): ImportCell {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ImportCell(index, name, ImportCellKind.EMPTY, null)
        val kind = when {
            trimmed.toLongOrNull() != null -> ImportCellKind.INTEGER
            trimmed.toBigDecimalOrNull() != null -> ImportCellKind.DECIMAL
            runCatching { Instant.parse(trimmed) }.isSuccess -> ImportCellKind.INSTANT
            runCatching { LocalDate.parse(trimmed) }.isSuccess -> ImportCellKind.DATE
            trimmed.equals("true", true) || trimmed.equals("false", true) -> ImportCellKind.BOOLEAN
            else -> ImportCellKind.TEXT
        }
        return ImportCell(index, name, kind, trimmed)
    }
}

internal enum class ByteOrderMark(val charsetName: String?, val bytes: ByteArray) {
    UTF32_BE("UTF-32BE", byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte())),
    UTF32_LE("UTF-32LE", byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00)),
    UTF8("UTF-8", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
    UTF16_BE("UTF-16BE", byteArrayOf(0xFE.toByte(), 0xFF.toByte())),
    UTF16_LE("UTF-16LE", byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
    NONE(null, byteArrayOf()),
    ;

    val byteCount: Int get() = bytes.size

    companion object {
        fun detect(sample: ByteArray): ByteOrderMark = entries.first { mark ->
            mark == NONE || sample.size >= mark.bytes.size && mark.bytes.indices.all { sample[it] == mark.bytes[it] }
        }
    }
}

internal class ImportCancelledException : RuntimeException()

internal fun ensureActive(signal: ImportCancellationSignal) {
    if (signal.isCancellationRequested()) throw ImportCancelledException()
}
