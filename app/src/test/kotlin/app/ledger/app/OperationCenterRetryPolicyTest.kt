package app.ledger.app

import app.ledger.core.common.StableId
import app.ledger.transfer.domain.BackgroundOperation
import app.ledger.transfer.domain.BackgroundOperationId
import app.ledger.transfer.domain.BackgroundOperationState
import app.ledger.transfer.domain.BackgroundOperationType
import app.ledger.transfer.domain.ImportFormat
import app.ledger.transfer.domain.OperationParameters
import app.ledger.transfer.domain.OperationProgress
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OperationCenterRetryPolicyTest {
    @Test
    fun `unsupported import requests a new source instead of claiming retry`() {
        val operation = failedImport("IMPORT_UNSUPPORTED_SOURCE")

        assertTrue(operation.requiresReplacementImportSource())
        assertFalse(operation.canRetryFromOperationCenter())
    }

    @Test
    fun `recoverable import exposes executable retry`() {
        val operation = failedImport("IMPORT_SOURCE_TEMPORARILY_UNAVAILABLE")

        assertFalse(operation.requiresReplacementImportSource())
        assertTrue(operation.canRetryFromOperationCenter())
    }

    private fun failedImport(errorCode: String): BackgroundOperation = BackgroundOperation.restore(
        id = BackgroundOperationId(StableId.fromUuid(UUID(7L, 1L))),
        type = BackgroundOperationType.IMPORT,
        state = BackgroundOperationState.FAILED_RETRYABLE,
        createdAt = Instant.EPOCH,
        startedAt = Instant.EPOCH,
        updatedAt = Instant.ofEpochSecond(1L),
        progress = OperationProgress(0L, null),
        checkpointVersion = 1L,
        errorCode = errorCode,
        cancelRequested = false,
        parameters = OperationParameters.Import(
            StableId.fromUuid(UUID(7L, 2L)),
            ImportFormat.CSV,
            null,
        ),
    )
}
