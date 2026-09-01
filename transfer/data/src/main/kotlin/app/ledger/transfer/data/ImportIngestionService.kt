@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LongParameterList",
    "ReturnCount",
    "TooGenericExceptionCaught",
)

package app.ledger.transfer.data

import app.ledger.core.common.DomainError
import app.ledger.core.common.DomainResult
import app.ledger.finance.domain.Hash256
import app.ledger.transfer.domain.BackgroundOperation
import app.ledger.transfer.domain.BackgroundOperationRepository
import app.ledger.transfer.domain.BackgroundOperationState
import app.ledger.transfer.domain.EncryptedCheckpoint
import app.ledger.transfer.domain.EncryptedStagingRepository
import app.ledger.transfer.domain.ImportCancellationSignal
import app.ledger.transfer.domain.ImportCell
import app.ledger.transfer.domain.ImportCellKind
import app.ledger.transfer.domain.ImportFailure
import app.ledger.transfer.domain.ImportFormat
import app.ledger.transfer.domain.ImportProgressSnapshot
import app.ledger.transfer.domain.ImportReadRequest
import app.ledger.transfer.domain.ImportReadSummary
import app.ledger.transfer.domain.ImportStreamReader
import app.ledger.transfer.domain.ImportStreamRow
import app.ledger.transfer.domain.ImportWizardStage
import app.ledger.transfer.domain.OperationCheckpoint
import app.ledger.transfer.domain.OperationParameters
import app.ledger.transfer.domain.OperationProgress
import app.ledger.transfer.domain.RawRowPayload
import app.ledger.transfer.domain.StagingParsedField
import app.ledger.transfer.domain.StagingParsedRow
import app.ledger.transfer.domain.StagingRawRow
import app.ledger.transfer.domain.StagingValue
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

enum class ImportControlAction { RUN, PAUSE, CANCEL }

class ImportRunControl {
    private val action = AtomicReference(ImportControlAction.RUN)

    fun pause() {
        action.compareAndSet(ImportControlAction.RUN, ImportControlAction.PAUSE)
    }
    fun resume() {
        action.compareAndSet(ImportControlAction.PAUSE, ImportControlAction.RUN)
    }
    fun cancel() {
        action.set(ImportControlAction.CANCEL)
    }
    fun current(): ImportControlAction = action.get()
}

fun interface ImportProgressObserver {
    suspend fun onProgress(progress: ImportProgressSnapshot)

    companion object {
        val NONE: ImportProgressObserver = ImportProgressObserver { }
    }
}

data class ImportIngestionResult(
    val operation: BackgroundOperation,
    val summary: ImportReadSummary,
    val stagedRows: Long,
)

class ImportIngestionService(
    private val csvReader: ImportStreamReader = AndroidCsvImportReader(),
    private val xlsxReader: ImportStreamReader = FastExcelImportReader(),
    private val now: () -> Instant = Instant::now,
) {
    suspend fun ingest(
        operation: BackgroundOperation,
        request: ImportReadRequest,
        staging: EncryptedStagingRepository,
        operations: BackgroundOperationRepository,
        control: ImportRunControl,
        observer: ImportProgressObserver = ImportProgressObserver.NONE,
    ): DomainResult<ImportIngestionResult> {
        val parameters = operation.parameters as? OperationParameters.Import
            ?: return DomainResult.Failure(ImportIngestionError.NotAnImport)
        staging.create(operation.id).successOrReturn()?.let { return it }
        var current = operation
        if (current.state == BackgroundOperationState.FAILED_RETRYABLE) {
            current = when (val queued = current.transition(BackgroundOperationState.QUEUED, now(), errorCode = null)) {
                is DomainResult.Success -> queued.value
                is DomainResult.Failure -> return queued
            }
            operations.save(current).successOrReturn()?.let { return it }
        }
        if (current.state == BackgroundOperationState.QUEUED) {
            current = when (val preparing = current.transition(BackgroundOperationState.PREPARING, now())) {
                is DomainResult.Success -> preparing.value
                is DomainResult.Failure -> return preparing
            }
            operations.save(current).successOrReturn()?.let { return it }
        }
        if (current.state == BackgroundOperationState.PREPARING || current.state == BackgroundOperationState.PAUSED) {
            current = when (val running = current.transition(BackgroundOperationState.RUNNING, now())) {
                is DomainResult.Success -> running.value
                is DomainResult.Failure -> return running
            }
            operations.save(current).successOrReturn()?.let { return it }
        }
        if (current.state != BackgroundOperationState.RUNNING) {
            return DomainResult.Failure(ImportIngestionError.NotRecoverable)
        }
        if (parameters.format == ImportFormat.FULL_BACKUP) {
            return fail(current, ImportFailure.UnsupportedSource, staging, operations)
        }

        val rawBuffer = ArrayList<StagingRawRow>(CHUNK_ROWS)
        val parsedBuffer = ArrayList<StagingParsedRow>(CHUNK_ROWS)
        val resumeAfterRow = current.progress.current
        var streamedRows = 0L
        var stagedRows = resumeAfterRow
        var activeOperation = current
        val combinedCancellation = ImportCancellationSignal {
            request.cancellation.isCancellationRequested() || control.current() == ImportControlAction.CANCEL
        }

        suspend fun flush(): DomainResult<Unit> {
            if (rawBuffer.isEmpty()) return DomainResult.Success(Unit)
            staging.appendRaw(rawBuffer).successOrReturn()?.let { return it }
            staging.appendParsed(parsedBuffer).successOrReturn()?.let { return it }
            rawBuffer.clear()
            parsedBuffer.clear()
            when (val advanced = activeOperation.advance(OperationProgress(stagedRows, null), now())) {
                is DomainResult.Success -> activeOperation = advanced.value
                is DomainResult.Failure -> return advanced
            }
            operations.save(activeOperation).successOrReturn()?.let { return it }
            operations.append(
                OperationCheckpoint(
                    activeOperation.id,
                    activeOperation.checkpointVersion,
                    activeOperation.state,
                    checkpoint(stagedRows),
                    now(),
                ),
            ).successOrReturn()?.let { return it }
            observer.onProgress(
                ImportProgressSnapshot(
                    ImportWizardStage.STRUCTURE,
                    "PARSING_ROWS",
                    stagedRows,
                    null,
                    pauseAllowed = true,
                    cancelAllowed = true,
                    safeBoundaryDescriptionCode = "AFTER_STAGING_CHUNK",
                ),
            )
            return DomainResult.Success(Unit)
        }

        suspend fun pauseAtSafeBoundary(): DomainResult<Unit> {
            if (control.current() != ImportControlAction.PAUSE) return DomainResult.Success(Unit)
            when (val paused = activeOperation.transition(BackgroundOperationState.PAUSED, now())) {
                is DomainResult.Success -> activeOperation = paused.value
                is DomainResult.Failure -> return paused
            }
            operations.save(activeOperation).successOrReturn()?.let { return it }
            observer.onProgress(
                ImportProgressSnapshot(
                    ImportWizardStage.EXECUTION,
                    "PAUSED",
                    stagedRows,
                    null,
                    pauseAllowed = false,
                    cancelAllowed = true,
                    safeBoundaryDescriptionCode = "STAGING_CHUNK_DURABLE",
                ),
            )
            while (control.current() == ImportControlAction.PAUSE) delay(PAUSE_POLL_MILLIS)
            if (control.current() == ImportControlAction.CANCEL) return DomainResult.Failure(ImportFailure.Cancelled)
            when (val running = activeOperation.transition(BackgroundOperationState.RUNNING, now())) {
                is DomainResult.Success -> activeOperation = running.value
                is DomainResult.Failure -> return running
            }
            return operations.save(activeOperation)
        }

        val reader = if (parameters.format == ImportFormat.CSV) csvReader else xlsxReader
        val result = reader.read(request.copy(cancellation = combinedCancellation)) { sourceRow ->
            streamedRows++
            if (streamedRows <= resumeAfterRow) return@read
            stagedRows++
            val raw = ImportRawRowCodec.encode(sourceRow)
            rawBuffer += StagingRawRow(stagedRows, RawRowPayload.of(raw).requireValue(), Hash256.sha256(raw))
            parsedBuffer += StagingParsedRow(stagedRows, sourceRow.toParsedFields())
            if (rawBuffer.size >= CHUNK_ROWS) {
                flush().requireValue()
                pauseAtSafeBoundary().requireValue()
            }
        }
        if (result is DomainResult.Failure) {
            return fail(activeOperation, result.error, staging, operations)
        }
        if (streamedRows < resumeAfterRow) {
            return fail(activeOperation, ImportFailure.CorruptSource, staging, operations)
        }
        flush().successOrReturn()?.let { return it }
        val summary = (result as DomainResult.Success).value
        if (parameters.format == ImportFormat.STRUCTURED_WORKBOOK) {
            // Full-workbook exports contain a metadata sheet in addition to the governed entity
            // sheets. It is intentionally not staged, and therefore must not make the app's own
            // export fail structured re-import. Unknown sheets selected for ingestion are still
            // rejected rather than silently accepted.
            val selected = request.selectedSheetNames
            val ingestedSheets = summary.sheets.filter { sheet ->
                selected == null || selected.any { it.equals(sheet.name, ignoreCase = true) }
            }
            if (ingestedSheets.isEmpty() || ingestedSheets.any { it.structuredKind == null }) {
                return fail(activeOperation, ImportFailure.UnsupportedSource, staging, operations)
            }
        }
        observer.onProgress(
            ImportProgressSnapshot(
                ImportWizardStage.FIELD_MAPPING,
                "READY_FOR_MAPPING",
                stagedRows,
                stagedRows,
                pauseAllowed = false,
                cancelAllowed = true,
                safeBoundaryDescriptionCode = "STAGING_COMPLETE",
            ),
        )
        return DomainResult.Success(ImportIngestionResult(activeOperation, summary, stagedRows))
    }

    private suspend fun fail(
        operation: BackgroundOperation,
        error: DomainError,
        staging: EncryptedStagingRepository,
        operations: BackgroundOperationRepository,
    ): DomainResult<ImportIngestionResult> {
        if (error == ImportFailure.Cancelled) {
            var cancelled = operation.transition(BackgroundOperationState.CANCEL_REQUESTED, now()).requireValue()
            operations.save(cancelled)
            cancelled = cancelled.transition(BackgroundOperationState.ROLLING_BACK, now()).requireValue()
            operations.save(cancelled)
            staging.destroy()
            cancelled = cancelled.transition(BackgroundOperationState.FAILED_FINAL, now(), errorCode = error.code).requireValue()
            operations.save(cancelled)
        } else {
            val failureState = if (error in NON_RETRYABLE_SOURCE_FAILURES) {
                BackgroundOperationState.FAILED_FINAL
            } else {
                BackgroundOperationState.FAILED_RETRYABLE
            }
            val failed = operation.transition(failureState, now(), errorCode = error.code).requireValue()
            operations.save(failed)
        }
        return DomainResult.Failure(error)
    }

    private companion object {
        const val CHUNK_ROWS: Int = 256
        const val PAUSE_POLL_MILLIS: Long = 50L
        val NON_RETRYABLE_SOURCE_FAILURES: Set<DomainError> = setOf(
            ImportFailure.UnsupportedSource,
            ImportFailure.InvalidEncoding,
        )
    }

    private fun checkpoint(lastStagedRow: Long): EncryptedCheckpoint = EncryptedCheckpoint.of(
        ByteArray(Long.SIZE_BYTES) { index ->
            (lastStagedRow ushr ((Long.SIZE_BYTES - 1 - index) * Byte.SIZE_BITS)).toByte()
        },
    ).requireValue()
}

sealed interface ImportIngestionError : DomainError {
    data object NotAnImport : ImportIngestionError {
        override val code: String = "IMPORT_OPERATION_TYPE_INVALID"
    }
    data object NotRecoverable : ImportIngestionError {
        override val code: String = "IMPORT_OPERATION_NOT_RECOVERABLE"
    }
}

private object ImportRawRowCodec {
    fun encode(row: ImportStreamRow): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeSizedUtf8(row.sheetName)
            output.writeLong(row.rowNumber)
            output.writeInt(row.cells.size)
            row.cells.forEach { cell ->
                output.writeInt(cell.columnIndex)
                output.writeSizedUtf8(cell.columnName)
                output.writeInt(cell.kind.ordinal)
                output.writeNullableUtf(cell.canonicalValue)
                output.writeNullableUtf(cell.formula)
            }
        }
        bytes.toByteArray()
    }

    private fun DataOutputStream.writeNullableUtf(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeSizedUtf8(value)
    }

    private fun DataOutputStream.writeSizedUtf8(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_FIELD_BYTES) { "import field exceeds the bounded staging row size" }
        writeInt(encoded.size)
        write(encoded)
    }

    private const val MAX_FIELD_BYTES: Int = 16 * 1024 * 1024
}

private fun ImportStreamRow.toParsedFields(): List<StagingParsedField> = listOf(
    StagingParsedField("_sheet", StagingValue.Text(sheetName)),
    StagingParsedField("_source_row", StagingValue.Integer(rowNumber)),
) + cells.map(ImportCell::toParsedField)

private fun ImportCell.toParsedField(): StagingParsedField = StagingParsedField(
    columnName,
    when (kind) {
        ImportCellKind.EMPTY, ImportCellKind.ERROR -> StagingValue.Empty
        ImportCellKind.INTEGER -> StagingValue.Integer(requireNotNull(canonicalValue).toLong())
        ImportCellKind.DECIMAL -> StagingValue.Decimal(requireNotNull(canonicalValue).toBigDecimal())
        ImportCellKind.DATE -> StagingValue.Date(java.time.LocalDate.parse(requireNotNull(canonicalValue)))
        ImportCellKind.INSTANT -> StagingValue.InstantValue(Instant.parse(requireNotNull(canonicalValue)))
        ImportCellKind.TEXT, ImportCellKind.BOOLEAN -> StagingValue.Text(requireNotNull(canonicalValue))
    },
)

private fun DomainResult<Unit>.successOrReturn(): DomainResult.Failure? = this as? DomainResult.Failure
