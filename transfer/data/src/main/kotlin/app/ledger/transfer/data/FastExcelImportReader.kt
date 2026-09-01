@file:Suppress("LoopWithTooManyJumpStatements", "MagicNumber", "NestedBlockDepth", "TooGenericExceptionCaught")

package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.transfer.domain.ImportCell
import app.ledger.transfer.domain.ImportCellKind
import app.ledger.transfer.domain.ImportFailure
import app.ledger.transfer.domain.ImportReadRequest
import app.ledger.transfer.domain.ImportReadSummary
import app.ledger.transfer.domain.ImportRowConsumer
import app.ledger.transfer.domain.ImportSheet
import app.ledger.transfer.domain.ImportStreamReader
import app.ledger.transfer.domain.ImportStreamRow
import app.ledger.transfer.domain.StructuredEntityKind
import org.dhatim.fastexcel.reader.Cell
import org.dhatim.fastexcel.reader.CellType
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.dhatim.fastexcel.reader.ReadingOptions
import java.io.PushbackInputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** FastExcel 0.20.2 row streaming. Formulas expose only the stored cached value; no recalculation is attempted. */
class FastExcelImportReader : ImportStreamReader {
    override suspend fun read(request: ImportReadRequest, consumer: ImportRowConsumer): DomainResult<ImportReadSummary> = try {
        request.input.open().use { source ->
            val checked = PushbackInputStream(source, HEADER_SIZE)
            val header = ByteArray(HEADER_SIZE)
            val size = checked.read(header)
            if (size > 0) checked.unread(header, 0, size)
            if (size <= 0 || !ReadableWorkbook.isOOXMLZipHeader(header)) {
                return if (ReadableWorkbook.isOLE2Header(header)) {
                    DomainResult.Failure(ImportFailure.UnsupportedSource)
                } else {
                    DomainResult.Failure(ImportFailure.CorruptSource)
                }
            }
            val options = ReadingOptions(true, true)
            ReadableWorkbook(checked, options).use { workbook ->
                val summaries = mutableListOf<ImportSheet>()
                var totalRows = 0L
                workbook.sheets.use { sheetStream ->
                    val iterator = sheetStream.iterator()
                    while (iterator.hasNext()) {
                        ensureActive(request.cancellation)
                        val sheet = iterator.next()
                        summaries += ImportSheet(
                            sheet.name,
                            sheet.index,
                            StructuredEntityKind.fromSheetName(sheet.name),
                        )
                        val selectedSheets = request.selectedSheetNames
                        if (selectedSheets != null && selectedSheets.none { it.equals(sheet.name, ignoreCase = true) }) continue
                        var headers: List<String>? = null
                        sheet.openStream().use { rows ->
                            val rowIterator = rows.iterator()
                            while (rowIterator.hasNext()) {
                                ensureActive(request.cancellation)
                                val row = rowIterator.next()
                                val sourceRow = row.rowNum.toLong()
                                if (sourceRow < request.headerRowNumber) continue
                                if (sourceRow == request.headerRowNumber) {
                                    headers = headerNames(row.toList())
                                    continue
                                }
                                val names = headers ?: throw IllegalArgumentException("header row not found")
                                val cells = row.map { cell -> toImportCell(cell, names) }
                                consumer.accept(ImportStreamRow(sheet.name, sourceRow, cells))
                                totalRows++
                            }
                        }
                        requireNotNull(headers) { "header row not found" }
                    }
                }
                DomainResult.Success(ImportReadSummary(summaries, totalRows, 1, null, null))
            }
        }
    } catch (_: ImportCancelledException) {
        DomainResult.Failure(ImportFailure.Cancelled)
    } catch (_: Exception) {
        DomainResult.Failure(ImportFailure.CorruptSource)
    }

    private fun headerNames(cells: List<Cell>): List<String> {
        val maxColumn = cells.maxOfOrNull(Cell::getColumnIndex) ?: return emptyList()
        val byColumn = cells.associateBy(Cell::getColumnIndex)
        val counts = mutableMapOf<String, Int>()
        return (0..maxColumn).map { index ->
            val base = byColumn[index]?.text?.trim().orEmpty().ifBlank { "column_${index + 1}" }
            val ordinal = counts.merge(base, 1, Int::plus) ?: 1
            if (ordinal == 1) base else "${base}_$ordinal"
        }
    }

    private fun toImportCell(cell: Cell, names: List<String>): ImportCell {
        val name = names.getOrElse(cell.columnIndex) { "column_${cell.columnIndex + 1}" }
        val value = cell.value
        val kind: ImportCellKind
        val canonical: String?
        when {
            cell.type == CellType.EMPTY || value == null -> {
                kind = if (cell.type == CellType.ERROR) ImportCellKind.ERROR else ImportCellKind.EMPTY
                canonical = null
            }
            cell.type == CellType.ERROR -> {
                kind = ImportCellKind.ERROR
                canonical = null
            }
            value is Number && cell.isDateFormatted() -> {
                val dateTime = cell.asDate()
                if (dateTime.toLocalTime() == java.time.LocalTime.MIDNIGHT) {
                    kind = ImportCellKind.DATE
                    canonical = dateTime.toLocalDate().toString()
                } else {
                    kind = ImportCellKind.INSTANT
                    canonical = dateTime.toInstant(ZoneOffset.UTC).toString()
                }
            }
            value is LocalDateTime -> {
                if (value.toLocalTime() == java.time.LocalTime.MIDNIGHT) {
                    kind = ImportCellKind.DATE
                    canonical = value.toLocalDate().toString()
                } else {
                    kind = ImportCellKind.INSTANT
                    canonical = value.toInstant(ZoneOffset.UTC).toString()
                }
            }
            value is LocalDate -> {
                kind = ImportCellKind.DATE
                canonical = value.toString()
            }
            value is BigDecimal || value is Number -> {
                val decimal = value.toString().toBigDecimal()
                kind = if (decimal.scale() <= 0) ImportCellKind.INTEGER else ImportCellKind.DECIMAL
                canonical = decimal.stripTrailingZeros().toPlainString()
            }
            value is Boolean -> {
                kind = ImportCellKind.BOOLEAN
                canonical = value.toString()
            }
            else -> {
                val classified = ImportValueClassifier.cell(cell.columnIndex, name, cell.text)
                kind = classified.kind
                canonical = classified.canonicalValue
            }
        }
        return ImportCell(cell.columnIndex, name, kind, canonical, cell.formula)
    }

    private fun org.dhatim.fastexcel.reader.Row.toList(): List<Cell> = iterator().asSequence().toList()

    private fun Cell.isDateFormatted(): Boolean {
        val id = dataFormatId
        if (id != null && (id in 14..22 || id in 45..47)) return true
        val normalized = dataFormatString.orEmpty()
            .replace(Regex("\\[[^]]+]"), "")
            .replace(Regex("\"[^\"]*\""), "")
            .lowercase()
        return normalized.any { it == 'y' || it == 'd' } ||
            normalized.contains("h:") || normalized.contains("s")
    }

    private companion object {
        const val HEADER_SIZE: Int = 8
    }
}
