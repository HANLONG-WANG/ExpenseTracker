@file:Suppress("ReturnCount")

package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.Hash256
import app.ledger.transfer.domain.BackgroundOperation
import app.ledger.transfer.domain.BackgroundOperationId
import app.ledger.transfer.domain.BackgroundOperationRepository
import app.ledger.transfer.domain.BackgroundOperationState
import app.ledger.transfer.domain.BackgroundOperationType
import app.ledger.transfer.domain.EncryptedStagingRepository
import app.ledger.transfer.domain.ImportCell
import app.ledger.transfer.domain.ImportCellKind
import app.ledger.transfer.domain.ImportFailure
import app.ledger.transfer.domain.ImportFormat
import app.ledger.transfer.domain.ImportInput
import app.ledger.transfer.domain.ImportReadRequest
import app.ledger.transfer.domain.ImportReadSummary
import app.ledger.transfer.domain.ImportRowConsumer
import app.ledger.transfer.domain.ImportSheet
import app.ledger.transfer.domain.ImportStreamReader
import app.ledger.transfer.domain.ImportStreamRow
import app.ledger.transfer.domain.OperationCheckpoint
import app.ledger.transfer.domain.OperationParameters
import app.ledger.transfer.domain.StagingAttachment
import app.ledger.transfer.domain.StagingCounts
import app.ledger.transfer.domain.StagingDuplicateCandidate
import app.ledger.transfer.domain.StagingMapping
import app.ledger.transfer.domain.StagingParsedRow
import app.ledger.transfer.domain.StagingPreparedCommand
import app.ledger.transfer.domain.StagingRawRow
import app.ledger.transfer.domain.StagingValidationError
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID

class ImportIngestionRecoveryTest {
    @Test
    fun crashRetryResumesFromDurableChunkWithoutDuplicatingStagingRows() = runBlocking {
        val staging = RecoveryMemoryStaging()
        val operations = MemoryOperations(operation())
        var tick = 1L
        val failed = ImportIngestionService(
            csvReader = GeneratedReader(600, failAfter = 300),
            now = { Instant.ofEpochMilli(tick++) },
        ).ingest(operations.current, request(), staging, operations, ImportRunControl())

        assertEquals(ImportFailure.CorruptSource, (failed as DomainResult.Failure).error)
        assertEquals(BackgroundOperationState.FAILED_RETRYABLE, operations.current.state)
        assertEquals(256, staging.raw.size)

        val recovered = ImportIngestionService(
            csvReader = GeneratedReader(600),
            now = { Instant.ofEpochMilli(tick++) },
        ).ingest(operations.current, request(), staging, operations, ImportRunControl())

        assertTrue(recovered is DomainResult.Success)
        assertEquals(600, staging.raw.size)
        assertEquals((1L..600L).toList(), staging.raw.map(StagingRawRow::rowNumber))
        assertEquals(600L, operations.current.progress.current)
        assertTrue(operations.checkpoints.isNotEmpty())
    }

    @Test
    fun pauseResumesOnlyAtDurableChunkBoundary() = runBlocking {
        val staging = RecoveryMemoryStaging()
        val operations = MemoryOperations(operation())
        val control = ImportRunControl()
        var tick = 1L
        var observedPauseBoundary = false
        val result = ImportIngestionService(
            csvReader = GeneratedReader(300),
            now = { Instant.ofEpochMilli(tick++) },
        ).ingest(operations.current, request(), staging, operations, control) { progress ->
            if (!observedPauseBoundary && progress.current == 256L) {
                observedPauseBoundary = true
                control.pause()
                kotlinx.coroutines.CoroutineScope(currentCoroutineContext()).launch {
                    delay(20)
                    control.resume()
                }
            }
        }

        assertTrue(result is DomainResult.Success)
        assertTrue(observedPauseBoundary)
        assertEquals(300, staging.raw.size)
        assertTrue(operations.states.contains(BackgroundOperationState.PAUSED))
        assertFalse(staging.destroyed)
    }

    @Test
    fun cancellationRollsBackAndDestroysStagingAtSafeBoundary() = runBlocking {
        val staging = RecoveryMemoryStaging()
        val operations = MemoryOperations(operation())
        val control = ImportRunControl()
        var tick = 1L
        val result = ImportIngestionService(
            csvReader = GeneratedReader(600),
            now = { Instant.ofEpochMilli(tick++) },
        ).ingest(operations.current, request(), staging, operations, control) { progress ->
            if (progress.current == 256L) control.cancel()
        }

        assertEquals(ImportFailure.Cancelled, (result as DomainResult.Failure).error)
        assertTrue(staging.destroyed)
        assertEquals(BackgroundOperationState.FAILED_FINAL, operations.current.state)
        assertEquals(ImportFailure.Cancelled.code, operations.current.errorCode)
    }

    private fun request() = ImportReadRequest(ImportInput { ByteArrayInputStream(byteArrayOf(1)) })

    private fun operation() = BackgroundOperation.queued(
        BackgroundOperationId(StableId.fromUuid(UUID(0x28L, 1L))),
        BackgroundOperationType.IMPORT,
        Instant.EPOCH,
        OperationParameters.Import(StableId.fromUuid(UUID(0x28L, 2L)), ImportFormat.CSV, null),
    )
}

private class GeneratedReader(
    private val rowCount: Int,
    private val failAfter: Int? = null,
) : ImportStreamReader {
    override suspend fun read(request: ImportReadRequest, consumer: ImportRowConsumer): DomainResult<ImportReadSummary> {
        for (number in 1..rowCount) {
            if (request.cancellation.isCancellationRequested()) return DomainResult.Failure(ImportFailure.Cancelled)
            consumer.accept(
                ImportStreamRow(
                    "csv",
                    number.toLong(),
                    listOf(ImportCell(0, "amount", ImportCellKind.INTEGER, number.toString())),
                ),
            )
            if (number == failAfter) return DomainResult.Failure(ImportFailure.CorruptSource)
        }
        return DomainResult.Success(ImportReadSummary(listOf(ImportSheet("csv", 0, null)), rowCount.toLong(), 1, "UTF-8", null))
    }
}

private class MemoryOperations(initial: BackgroundOperation) : BackgroundOperationRepository {
    var current: BackgroundOperation = initial
    val states = mutableListOf(initial.state)
    val checkpoints = mutableListOf<OperationCheckpoint>()

    override suspend fun get(id: BackgroundOperationId) = DomainResult.Success(current.takeIf { it.id == id })

    override suspend fun save(operation: BackgroundOperation): DomainResult<Unit> {
        current = operation
        states += operation.state
        return DomainResult.Success(Unit)
    }

    override suspend fun append(checkpoint: OperationCheckpoint): DomainResult<Unit> {
        checkpoints += checkpoint
        return DomainResult.Success(Unit)
    }
}

private class RecoveryMemoryStaging : EncryptedStagingRepository {
    val raw = mutableListOf<StagingRawRow>()
    val parsed = mutableListOf<StagingParsedRow>()
    var destroyed = false

    override suspend fun create(operationId: BackgroundOperationId) = DomainResult.Success(Unit)
    override suspend fun appendRaw(rows: List<StagingRawRow>): DomainResult<Unit> {
        rows.forEach { row ->
            raw.removeAll { it.rowNumber == row.rowNumber }
            raw += row
        }
        raw.sortBy(StagingRawRow::rowNumber)
        return DomainResult.Success(Unit)
    }
    override suspend fun appendParsed(rows: List<StagingParsedRow>): DomainResult<Unit> {
        rows.forEach { row ->
            parsed.removeAll { it.rowNumber == row.rowNumber }
            parsed += row
        }
        parsed.sortBy(StagingParsedRow::rowNumber)
        return DomainResult.Success(Unit)
    }
    override suspend fun saveMappings(mappings: List<StagingMapping>) = DomainResult.Success(Unit)
    override suspend fun saveErrors(errors: List<StagingValidationError>) = DomainResult.Success(Unit)
    override suspend fun saveDuplicates(candidates: List<StagingDuplicateCandidate>) = DomainResult.Success(Unit)
    override suspend fun savePrepared(commands: List<StagingPreparedCommand>) = DomainResult.Success(Unit)
    override suspend fun saveAttachments(attachments: List<StagingAttachment>) = DomainResult.Success(Unit)
    override suspend fun rawRows(offsetExclusive: Long, limit: Int) = DomainResult.Success(raw.filter { it.rowNumber > offsetExclusive }.take(limit))
    override suspend fun parsedRows(offsetExclusive: Long, limit: Int) = DomainResult.Success(parsed.filter { it.rowNumber > offsetExclusive }.take(limit))
    override suspend fun preparedCommands(offsetExclusive: Long, limit: Int) = DomainResult.Success(emptyList<StagingPreparedCommand>())
    override suspend fun counts() = DomainResult.Success(StagingCounts(raw.size.toLong(), parsed.size.toLong(), 0, 0, 0))
    override suspend fun destroy(): DomainResult<Unit> {
        destroyed = true
        raw.clear()
        parsed.clear()
        return DomainResult.Success(Unit)
    }
}
