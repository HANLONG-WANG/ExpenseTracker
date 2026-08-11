@file:Suppress("LongMethod", "LongParameterList", "ReturnCount", "TooGenericExceptionCaught")

package app.ledger.app

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ledger.core.background.OperationNotificationContent
import app.ledger.core.background.OperationNotificationCoordinator
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.money.CurrencyCode
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.SecureImportSourceHandleStore
import app.ledger.core.security.SecureImportStagingAccess
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.core.time.LedgerClock
import app.ledger.finance.application.ImportCommitMetadata
import app.ledger.finance.application.ImportFinancialApplicationPort
import app.ledger.finance.application.ImportFinancialCommitRequest
import app.ledger.finance.application.StructuredImportApplicationPort
import app.ledger.finance.application.StructuredImportCommitRequest
import app.ledger.transfer.data.ImportIngestionService
import app.ledger.transfer.data.ImportProgressObserver
import app.ledger.transfer.data.ImportRunControl
import app.ledger.transfer.data.SqlCipherBackgroundOperationRepository
import app.ledger.transfer.data.SqlCipherStagingRepository
import app.ledger.transfer.domain.BackgroundOperationId
import app.ledger.transfer.domain.BackgroundOperationState
import app.ledger.transfer.domain.ImportFailure
import app.ledger.transfer.domain.ImportInput
import app.ledger.transfer.domain.ImportReadRequest
import app.ledger.transfer.domain.OperationParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** Local imports run as foreground WorkManager work; input Data contains only one opaque operation id. */
internal class ImportWorker(
    applicationContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(applicationContext, workerParameters) {
    override suspend fun doWork(): Result {
        val operationStableId = if (inputData.keyValueMap.keys == setOf(INPUT_OPERATION_ID)) {
            inputData.getString(INPUT_OPERATION_ID)?.let { StableId.parse(it).getOrNull() }
        } else {
            null
        } ?: return Result.failure()
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, ImportWorkerEntryPoint::class.java)
        val saved = dependencies.settings().current()
        val bookId = StableId.fromBytes(saved.bookId.toByteArray()).getOrNull() ?: return Result.failure()
        val operationId = BackgroundOperationId(operationStableId)
        val primary = SecurePrimaryLedgerAccess(applicationContext, dependencies.keyProvider())
        val operations = SqlCipherBackgroundOperationRepository(bookId, primary)
        val operation = when (val loaded = operations.get(operationId)) {
            is DomainResult.Success -> loaded.value ?: return Result.failure()
            is DomainResult.Failure -> return retryOrFailure()
        }
        val parameters = operation.parameters as? OperationParameters.Import ?: return Result.failure()
        val persistedCommit = parameters.commit
        if (operation.state == BackgroundOperationState.CANCEL_REQUESTED) {
            var cancelled = operation.transition(BackgroundOperationState.ROLLING_BACK, dependencies.clock().now())
                .valueOrNull() ?: return Result.failure()
            operations.save(cancelled)
            val stagingRemoved = SqlCipherStagingRepository(
                bookId,
                operationId,
                SecureImportStagingAccess(applicationContext, dependencies.keyProvider()),
            ).destroy() is DomainResult.Success
            val handleRemoved = removeSourceHandle(bookId, parameters.sourceHandleId, dependencies)
            cancelled = cancelled.transition(
                BackgroundOperationState.FAILED_FINAL,
                dependencies.clock().now(),
                errorCode = ImportFailure.Cancelled.code,
            ).valueOrNull() ?: return Result.failure()
            operations.save(cancelled)
            return Result.success(
                Data.Builder()
                    .putBoolean(OUTPUT_CANCELLED, true)
                    .putBoolean(OUTPUT_CLEANUP_COMPLETE, stagingRemoved && handleRemoved)
                    .build(),
            )
        }
        if (operation.state == BackgroundOperationState.SUCCEEDED && persistedCommit != null) {
            val stagingRemoved = SqlCipherStagingRepository(
                bookId,
                operationId,
                SecureImportStagingAccess(applicationContext, dependencies.keyProvider()),
            ).destroy() is DomainResult.Success
            val handleRemoved = removeSourceHandle(bookId, parameters.sourceHandleId, dependencies)
            return committedOutput(
                persistedCommit.totalPreparedRows,
                persistedCommit.useStructuredUndo,
                cleanupComplete = stagingRemoved && handleRemoved,
            )
        }
        if (operation.state == BackgroundOperationState.COMMITTING && persistedCommit != null) {
            setForeground(foregroundInfo(operation.progress.current))
            return commitPrepared(bookId, operationId, operation, parameters, operations, dependencies)
        }
        val persistedHandle = try {
            SecureImportSourceHandleStore(applicationContext, dependencies.keyProvider())
                .read(bookId, parameters.sourceHandleId)
        } catch (_: Exception) {
            null
        } ?: return Result.failure()
        val uri = Uri.parse(persistedHandle)
        val control = ImportRunControlRegistry.get(operationStableId)
        setForeground(foregroundInfo(0L))
        val workerContext = currentCoroutineContext()
        val result = ImportIngestionService(now = dependencies.clock()::now).ingest(
            operation,
            ImportReadRequest(
                input = ImportInput {
                    applicationContext.contentResolver.openInputStream(uri) ?: error("persisted source cannot be opened")
                },
                headerRowNumber = parameters.headerRowNumber,
                userCharset = parameters.userCharset,
                cancellation = { isStopped || !workerContext.isActive },
            ),
            SqlCipherStagingRepository(bookId, operationId, SecureImportStagingAccess(applicationContext, dependencies.keyProvider())),
            operations,
            control,
            ImportProgressObserver { progress ->
                setProgress(Data.Builder().putLong(PROGRESS_ROWS, progress.current).build())
                setForeground(foregroundInfo(progress.current))
            },
        )
        ImportRunControlRegistry.remove(operationStableId)
        return when (result) {
            is DomainResult.Success -> Result.success(
                Data.Builder()
                    .putString(OUTPUT_CHARSET, result.value.summary.selectedCharset)
                    .putLong(OUTPUT_ROWS, result.value.stagedRows)
                    .build(),
            )
            is DomainResult.Failure -> if (result.error == ImportFailure.Cancelled) {
                val handleRemoved = removeSourceHandle(bookId, parameters.sourceHandleId, dependencies)
                Result.success(
                    Data.Builder()
                        .putBoolean(OUTPUT_CANCELLED, true)
                        .putBoolean(OUTPUT_CLEANUP_COMPLETE, handleRemoved)
                        .build(),
                )
            } else if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                failIngestion(bookId, operationId, operation, parameters, operations, dependencies, result.error.code)
            }
        }
    }

    private suspend fun commitPrepared(
        bookId: StableId,
        operationId: BackgroundOperationId,
        operation: app.ledger.transfer.domain.BackgroundOperation,
        parameters: OperationParameters.Import,
        operations: SqlCipherBackgroundOperationRepository,
        dependencies: ImportWorkerEntryPoint,
    ): Result {
        val commit = requireNotNull(parameters.commit)
        val currency = CurrencyCode.parse(commit.baseCurrency).getOrNull()
            ?: return failCommit(
                bookId,
                operationId,
                operation,
                parameters,
                operations,
                dependencies,
                ImportFailure.ValidationFailed.code,
            )
        val parsedZone = runCatching { ZoneId.of(commit.zoneId) }.getOrNull()
            ?: return failCommit(
                bookId,
                operationId,
                operation,
                parameters,
                operations,
                dependencies,
                ImportFailure.ValidationFailed.code,
            )
        val staging = SqlCipherStagingRepository(
            bookId,
            operationId,
            SecureImportStagingAccess(applicationContext, dependencies.keyProvider()),
        )
        val metadata = ImportCommitMetadata(
            operationId.value,
            commit.importRecordId,
            commit.batchId,
            parameters.format.ordinal,
            commit.sourceFingerprint,
            commit.totalPreparedRows,
            operation.updatedAt,
        )
        val financialPages = ImportPreparedFinancialPageSource(
            bookId,
            operationId,
            commit.batchId,
            parameters.format,
            currency,
            parsedZone,
            operation.updatedAt,
            staging,
        )
        val result = when {
            parameters.format == app.ledger.transfer.domain.ImportFormat.STRUCTURED_WORKBOOK -> {
                dependencies.structuredImport().commit(
                    StructuredImportCommitRequest(
                        bookId,
                        metadata,
                        PreparedStructuredImportPageSource(staging),
                        financialPages.takeIf { commit.transactionRows > 0L },
                        commit.transactionRows,
                    ),
                )
            }
            commit.useStructuredUndo -> {
                val rows = when (
                    val loaded = PreparedGeneralMissingEntitySource.rows(
                        staging,
                        operationId.value,
                        commit.baseCurrency,
                        commit.firstSourceRowNumber,
                    )
                ) {
                    is DomainResult.Success -> loaded.value
                    is DomainResult.Failure -> return failCommit(
                        bookId,
                        operationId,
                        operation,
                        parameters,
                        operations,
                        dependencies,
                        loaded.error.code,
                    )
                }
                if (rows.isEmpty()) {
                    return failCommit(
                        bookId,
                        operationId,
                        operation,
                        parameters,
                        operations,
                        dependencies,
                        ImportFailure.ValidationFailed.code,
                    )
                }
                dependencies.structuredImport().commit(
                    StructuredImportCommitRequest(
                        bookId,
                        metadata,
                        PreparedGeneralMissingEntitySource.pageSource(rows),
                        financialPages.takeIf { commit.transactionRows > 0L },
                        commit.transactionRows,
                        entityRowsContributeToTotal = false,
                    ),
                )
            }
            else -> dependencies.financialImport().commit(ImportFinancialCommitRequest(bookId, metadata, financialPages))
        }
        return when (result) {
            is DomainResult.Success -> {
                val latest = when (val loaded = operations.get(operationId)) {
                    is DomainResult.Success -> loaded.value
                    is DomainResult.Failure -> null
                } ?: return retryOrFailure()
                if (latest.state == BackgroundOperationState.COMMITTING) {
                    when (
                        val saved = operations.save(
                            latest.transition(
                                BackgroundOperationState.SUCCEEDED,
                                dependencies.clock().now(),
                                progress = app.ledger.transfer.domain.OperationProgress(result.value.importedRows, result.value.importedRows),
                            ).valueOrNull() ?: return retryOrFailure(),
                        )
                    ) {
                        is DomainResult.Success -> Unit
                        is DomainResult.Failure -> return retryOrFailure()
                    }
                }
                val handleRemoved = removeSourceHandle(bookId, parameters.sourceHandleId, dependencies)
                val stagingRemoved = staging.destroy() is DomainResult.Success
                committedOutput(result.value.importedRows, commit.useStructuredUndo, handleRemoved && stagingRemoved)
            }
            is DomainResult.Failure -> if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                failCommit(bookId, operationId, operation, parameters, operations, dependencies, result.error.code)
            }
        }
    }

    private suspend fun failIngestion(
        bookId: StableId,
        operationId: BackgroundOperationId,
        operation: app.ledger.transfer.domain.BackgroundOperation,
        parameters: OperationParameters.Import,
        operations: SqlCipherBackgroundOperationRepository,
        dependencies: ImportWorkerEntryPoint,
        errorCode: String,
    ): Result {
        val latest = (operations.get(operationId) as? DomainResult.Success)?.value ?: operation
        if (latest.state == BackgroundOperationState.FAILED_RETRYABLE) {
            val rolling = latest.transition(BackgroundOperationState.ROLLING_BACK, dependencies.clock().now()).valueOrNull()
            if (rolling != null) {
                operations.save(rolling)
                rolling.transition(
                    BackgroundOperationState.FAILED_FINAL,
                    dependencies.clock().now(),
                    errorCode = errorCode,
                ).valueOrNull()?.let { operations.save(it) }
            }
        }
        val stagingRemoved = SqlCipherStagingRepository(
            bookId,
            operationId,
            SecureImportStagingAccess(applicationContext, dependencies.keyProvider()),
        ).destroy() is DomainResult.Success
        val handleRemoved = removeSourceHandle(bookId, parameters.sourceHandleId, dependencies)
        return Result.failure(
            Data.Builder()
                .putString(OUTPUT_ERROR_CODE, errorCode)
                .putBoolean(OUTPUT_CLEANUP_COMPLETE, stagingRemoved && handleRemoved)
                .build(),
        )
    }

    private suspend fun failCommit(
        bookId: StableId,
        operationId: BackgroundOperationId,
        operation: app.ledger.transfer.domain.BackgroundOperation,
        parameters: OperationParameters.Import,
        operations: SqlCipherBackgroundOperationRepository,
        dependencies: ImportWorkerEntryPoint,
        errorCode: String,
    ): Result {
        val latest = (operations.get(operationId) as? DomainResult.Success)?.value ?: operation
        if (latest.state == BackgroundOperationState.COMMITTING) {
            val rolling = latest.transition(BackgroundOperationState.ROLLING_BACK, dependencies.clock().now()).valueOrNull()
            if (rolling != null) {
                operations.save(rolling)
                rolling.transition(
                    BackgroundOperationState.FAILED_FINAL,
                    dependencies.clock().now(),
                    errorCode = errorCode,
                ).valueOrNull()?.let { operations.save(it) }
            }
        }
        SqlCipherStagingRepository(
            bookId,
            operationId,
            SecureImportStagingAccess(applicationContext, dependencies.keyProvider()),
        ).destroy()
        removeSourceHandle(bookId, parameters.sourceHandleId, dependencies)
        return Result.failure(Data.Builder().putString(OUTPUT_ERROR_CODE, errorCode).build())
    }

    private fun removeSourceHandle(
        bookId: StableId,
        handleId: StableId,
        dependencies: ImportWorkerEntryPoint,
    ): Boolean {
        val store = SecureImportSourceHandleStore(applicationContext, dependencies.keyProvider())
        return store.destroy(handleId) || runCatching { store.read(bookId, handleId) == null }.getOrDefault(false)
    }

    private fun committedOutput(rows: Long, structuredUndo: Boolean, cleanupComplete: Boolean): Result = Result.success(
        Data.Builder()
            .putLong(OUTPUT_ROWS, rows)
            .putBoolean(OUTPUT_COMMITTED, true)
            .putBoolean(OUTPUT_STRUCTURED_UNDO, structuredUndo)
            .putBoolean(OUTPUT_CLEANUP_COMPLETE, cleanupComplete)
            .build(),
    )

    private fun <T> DomainResult<T>.valueOrNull(): T? = (this as? DomainResult.Success)?.value

    private fun foregroundInfo(rows: Long): ForegroundInfo {
        val notification = OperationNotificationCoordinator.create(
            applicationContext,
            OperationNotificationContent(
                applicationContext.getString(R.string.import_worker_channel),
                applicationContext.getString(R.string.import_worker_title),
                applicationContext.getString(R.string.import_worker_progress, rows),
            ),
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun retryOrFailure(): Result = if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()

    companion object {
        const val INPUT_OPERATION_ID = "operationId"
        const val PROGRESS_ROWS = "rows"
        const val OUTPUT_CHARSET = "selectedCharset"
        const val OUTPUT_ROWS = "stagedRows"
        const val OUTPUT_CANCELLED = "cancelled"
        const val OUTPUT_COMMITTED = "committed"
        const val OUTPUT_STRUCTURED_UNDO = "structuredUndo"
        const val OUTPUT_CLEANUP_COMPLETE = "cleanupComplete"
        const val OUTPUT_ERROR_CODE = "errorCode"
        private const val MAX_RETRIES = 3
        private const val NOTIFICATION_ID = 28_001
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ImportWorkerEntryPoint {
    fun settings(): AppSettingsRepository
    fun keyProvider(): DeviceLedgerKeyProvider
    fun clock(): LedgerClock
    fun financialImport(): ImportFinancialApplicationPort
    fun structuredImport(): StructuredImportApplicationPort
}

internal object ImportRunControlRegistry {
    private val controls = ConcurrentHashMap<StableId, ImportRunControl>()
    fun get(operationId: StableId): ImportRunControl = controls.getOrPut(operationId, ::ImportRunControl)
    fun cancel(operationId: StableId) {
        get(operationId).cancel()
    }
    fun remove(operationId: StableId) {
        controls.remove(operationId)
    }
}

internal object ImportWorkScheduler {
    fun enqueue(context: Context, operationId: StableId) {
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(operationData(operationId))
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(operationId), ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueCommit(context: Context, operationId: StableId) {
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(operationData(operationId))
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(operationId), ExistingWorkPolicy.REPLACE, request)
    }

    fun operationData(operationId: StableId): Data = Data.Builder().putString(ImportWorker.INPUT_OPERATION_ID, operationId.toString()).build()

    fun uniqueName(operationId: StableId): String = "ledger-import-$operationId"
}
