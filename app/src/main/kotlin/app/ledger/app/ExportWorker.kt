@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "MagicNumber",
    "ReturnCount",
    "TooGenericExceptionCaught",
)

package app.ledger.app

import android.app.Notification
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
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
import app.ledger.core.security.ActiveBookSessionRuntime
import app.ledger.core.security.BookSessionState
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.HeadlessBookLease
import app.ledger.core.security.HeadlessLeaseCapability
import app.ledger.core.security.HeadlessLedgerDatabaseAccess
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.core.security.SecureTransferHandleStore
import app.ledger.core.security.withHeadlessLedgerAccess
import app.ledger.core.time.LedgerClock
import app.ledger.finance.application.LedgerExportQueryPort
import app.ledger.transfer.data.LedgerExportTabularSource
import app.ledger.transfer.data.SafExportDestination
import app.ledger.transfer.data.SqlCipherBackgroundOperationRepository
import app.ledger.transfer.data.StreamingExportEngine
import app.ledger.transfer.domain.BackgroundOperationId
import app.ledger.transfer.domain.BackgroundOperationState
import app.ledger.transfer.domain.ExportContent
import app.ledger.transfer.domain.ExportFailure
import app.ledger.transfer.domain.ExportFormat
import app.ledger.transfer.domain.ExportProgressObserver
import app.ledger.transfer.domain.ExportResult
import app.ledger.transfer.domain.OperationCheckpoint
import app.ledger.transfer.domain.OperationParameters
import app.ledger.transfer.domain.OperationProgress
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class ExportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = (
            if (inputData.keyValueMap.keys == setOf(INPUT_OPERATION_ID)) {
                inputData.getString(INPUT_OPERATION_ID)?.let { StableId.parse(it).getOrNull() }
            } else {
                null
            }
            ) ?: return Result.failure()
        setForeground(exportForegroundInfo(applicationContext, 0L))
        val outcome = ExportOperationRunner(applicationContext).run(
            id,
            retryAllowed = runAttemptCount < MAX_RETRIES,
        ) { rows ->
            setProgress(Data.Builder().putLong(PROGRESS_ROWS, rows).build())
            setForeground(exportForegroundInfo(applicationContext, rows))
        }
        return when (outcome) {
            ExportRunOutcome.SUCCEEDED, ExportRunOutcome.CANCELLED -> Result.success()
            ExportRunOutcome.RETRY -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            ExportRunOutcome.FAILED -> Result.failure()
        }
    }

    companion object {
        const val INPUT_OPERATION_ID = "operationId"
        const val PROGRESS_ROWS = "rows"
        private const val MAX_RETRIES = 3
    }
}

internal class ExportUserInitiatedJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val keys = params.extras.keySet()
        val id = (
            if (keys == setOf(ExportWorker.INPUT_OPERATION_ID)) {
                StableId.parse(params.extras.getString(ExportWorker.INPUT_OPERATION_ID, "")).getOrNull()
            } else {
                null
            }
            ) ?: return false
        if (Build.VERSION.SDK_INT >= 34) {
            setNotification(params, EXPORT_NOTIFICATION_ID, exportNotification(this, 0L), JOB_END_NOTIFICATION_POLICY_REMOVE)
        }
        jobs[params.jobId] = scope.launch {
            val result = ExportOperationRunner(applicationContext).run(id, retryAllowed = false) { rows ->
                if (Build.VERSION.SDK_INT >= 34) {
                    setNotification(params, EXPORT_NOTIFICATION_ID, exportNotification(this@ExportUserInitiatedJobService, rows), JOB_END_NOTIFICATION_POLICY_REMOVE)
                }
            }
            jobs.remove(params.jobId)
            jobFinished(params, result == ExportRunOutcome.RETRY)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        params.extras.getString(ExportWorker.INPUT_OPERATION_ID, "").let(StableId::parse).getOrNull()?.let(ExportRunControlRegistry::cancel)
        jobs.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

private class ExportOperationRunner(private val context: Context) {
    @Suppress("NestedBlockDepth")
    suspend fun run(
        operationStableId: StableId,
        retryAllowed: Boolean,
        onProgress: suspend (Long) -> Unit,
    ): ExportRunOutcome {
        val dependencies = EntryPointAccessors.fromApplication(context, ExportWorkerEntryPoint::class.java)
        val bookId = StableId.fromBytes(dependencies.settings().current().bookId.toByteArray()).getOrNull() ?: return ExportRunOutcome.FAILED
        val sessionRuntime = dependencies.sessionRuntime()
        val manager = sessionRuntime.activate(bookId)
        var lease: HeadlessBookLease? = null
        try {
            if (manager.state.value == BookSessionState.Uninitialized) manager.initialize()
            lease = manager.acquireHeadlessLease(operationStableId, HeadlessLeaseCapability.EXPORT_WRITE)
            val operationId = BackgroundOperationId(operationStableId)
            val operations = SqlCipherBackgroundOperationRepository(
                bookId,
                SecurePrimaryLedgerAccess(
                    context,
                    dependencies.keyProvider(),
                    HeadlessLedgerDatabaseAccess(sessionRuntime, lease),
                ),
            )
            var operation = when (val loaded = operations.get(operationId)) {
                is DomainResult.Success -> loaded.value
                is DomainResult.Failure -> null
            } ?: return ExportRunOutcome.FAILED
            val parameters = operation.parameters as? OperationParameters.Export ?: return ExportRunOutcome.FAILED
            val handles = SecureTransferHandleStore(context, dependencies.keyProvider())
            val persisted = runCatching { handles.read(bookId, parameters.destinationHandleId) }.getOrNull()
                ?: return fail(operation, operations, ExportFailure.PermissionRevoked, dependencies.clock().now(), retryAllowed)
            val treeUri = Uri.parse(persisted.substringBefore('\n'))
            val alreadyPublished = persisted.substringAfter('\n', "").takeIf(String::isNotBlank)?.let(Uri::parse)
            val destination = SafExportDestination(context, operationStableId, treeUri, parameters.descriptor)
            if (operation.state == BackgroundOperationState.CANCEL_REQUESTED) {
                destination.cleanup()
                return cancel(operation, operations, dependencies.clock().now())
            }
            val control = ExportRunControlRegistry.control(operationStableId)
            if (operation.state == BackgroundOperationState.SUCCEEDED) return ExportRunOutcome.SUCCEEDED
            if (operation.state == BackgroundOperationState.COMMITTING && alreadyPublished != null) {
                val readable = runCatching {
                    context.contentResolver.openFileDescriptor(alreadyPublished, "r")?.use { true } ?: false
                }.getOrDefault(false)
                if (readable) {
                    val succeeded = operation.transition(BackgroundOperationState.SUCCEEDED, dependencies.clock().now()).successOrNull()
                        ?: return ExportRunOutcome.FAILED
                    operations.save(succeeded)
                    operations.recordCheckpoint(succeeded, dependencies.clock().now())
                    destination.cleanup()
                    return ExportRunOutcome.SUCCEEDED
                }
            }
            if (operation.state == BackgroundOperationState.FAILED_RETRYABLE) {
                operation = operation.transition(BackgroundOperationState.QUEUED, dependencies.clock().now(), errorCode = null).successOrNull()
                    ?: return ExportRunOutcome.FAILED
                operations.save(operation)
                operations.recordCheckpoint(operation, dependencies.clock().now())
            }
            if (operation.state == BackgroundOperationState.QUEUED) {
                operation = operation.transition(BackgroundOperationState.PREPARING, dependencies.clock().now()).successOrNull()
                    ?: return ExportRunOutcome.FAILED
                operations.save(operation)
                operations.recordCheckpoint(operation, dependencies.clock().now())
            }
            if (operation.state == BackgroundOperationState.PREPARING) {
                operation = operation.transition(BackgroundOperationState.RUNNING, dependencies.clock().now()).successOrNull()
                    ?: return ExportRunOutcome.FAILED
                operations.save(operation)
            }
            var exportResult: ExportResult? = null
            if (operation.state == BackgroundOperationState.RUNNING) {
                val source = LedgerExportTabularSource(
                    bookId,
                    parameters.descriptor,
                    dependencies.ledgerExport(),
                    operation.createdAt,
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown",
                )
                val result = withHeadlessLedgerAccess(requireNotNull(lease)) {
                    destination.openTemporary().use { output ->
                        StreamingExportEngine().export(
                            parameters.descriptor,
                            source,
                            output,
                            cancelled = { control.get() },
                            progress = ExportProgressObserver { rows ->
                                onProgress(rows)
                                val latest = operations.get(operationId).successOrNull() ?: operation
                                latest.advance(OperationProgress(rows, null), dependencies.clock().now()).successOrNull()?.let { advanced ->
                                    operation = advanced
                                    operations.save(advanced)
                                    operations.recordCheckpoint(advanced, dependencies.clock().now())
                                }
                            },
                        )
                    }
                }
                when (result) {
                    is DomainResult.Success -> exportResult = result.value
                    is DomainResult.Failure -> {
                        destination.cleanup()
                        return if (result.error == ExportFailure.Cancelled) {
                            cancel(operation, operations, dependencies.clock().now())
                        } else {
                            fail(
                                operation,
                                operations,
                                result.error as? ExportFailure ?: ExportFailure.DestinationUnavailable,
                                dependencies.clock().now(),
                                retryAllowed,
                            )
                        }
                    }
                }
                operation = operation.transition(
                    BackgroundOperationState.COMMITTING,
                    dependencies.clock().now(),
                    OperationProgress(exportResult.rows, exportResult.rows),
                ).successOrNull() ?: return ExportRunOutcome.FAILED
                operations.save(operation)
                operations.recordCheckpoint(operation, dependencies.clock().now())
            }
            if (operation.state != BackgroundOperationState.COMMITTING) return ExportRunOutcome.FAILED
            val recovered = exportResult ?: ExportResult(
                operation.progress.current,
                when {
                    parameters.descriptor.format != ExportFormat.XLSX -> 1
                    parameters.descriptor.content == ExportContent.FULL_WORKBOOK -> 16
                    else -> 2
                },
                destination.temporaryBytes(),
                parameters.descriptor.format.mimeType(),
            )
            return when (val published = destination.publish(recovered)) {
                is DomainResult.Success -> {
                    handles.save(bookId, parameters.destinationHandleId, "$treeUri\n${published.value.documentUri}")
                    val succeeded = operation.transition(BackgroundOperationState.SUCCEEDED, dependencies.clock().now()).successOrNull()
                        ?: return ExportRunOutcome.FAILED
                    operations.save(succeeded)
                    operations.recordCheckpoint(succeeded, dependencies.clock().now())
                    ExportRunControlRegistry.remove(operationStableId)
                    ExportRunOutcome.SUCCEEDED
                }
                is DomainResult.Failure -> {
                    val failure = published.error as? ExportFailure ?: ExportFailure.DestinationUnavailable
                    destination.cleanup()
                    fail(operation, operations, failure, dependencies.clock().now(), retryAllowed)
                }
            }
        } finally {
            try {
                lease?.release()
            } finally {
                if (manager.state.value !is BookSessionState.Ready) manager.close()
            }
        }
    }

    private suspend fun cancel(
        operation: app.ledger.transfer.domain.BackgroundOperation,
        operations: SqlCipherBackgroundOperationRepository,
        at: java.time.Instant,
    ): ExportRunOutcome {
        var current = operation.transition(BackgroundOperationState.CANCEL_REQUESTED, at).successOrNull() ?: operation
        operations.save(current)
        current = current.transition(BackgroundOperationState.ROLLING_BACK, at).successOrNull() ?: current
        operations.save(current)
        current = current.transition(BackgroundOperationState.FAILED_FINAL, at, errorCode = ExportFailure.Cancelled.code).successOrNull() ?: current
        operations.save(current)
        operations.recordCheckpoint(current, at)
        return ExportRunOutcome.CANCELLED
    }

    private suspend fun fail(
        operation: app.ledger.transfer.domain.BackgroundOperation,
        operations: SqlCipherBackgroundOperationRepository,
        failure: ExportFailure,
        at: java.time.Instant,
        retryAllowed: Boolean,
    ): ExportRunOutcome {
        var current = operation
        if (current.state == BackgroundOperationState.QUEUED) {
            current = current.transition(BackgroundOperationState.PREPARING, at).successOrNull() ?: current
            operations.save(current)
        }
        val retryable = retryAllowed &&
            failure in setOf(ExportFailure.PermissionRevoked, ExportFailure.DestinationUnavailable) &&
            current.state in setOf(
                BackgroundOperationState.PREPARING,
                BackgroundOperationState.RUNNING,
                BackgroundOperationState.COMMITTING,
            )
        val next = if (retryable) BackgroundOperationState.FAILED_RETRYABLE else BackgroundOperationState.FAILED_FINAL
        val failed = current.transition(next, at, errorCode = failure.code).successOrNull()
        if (failed != null) operations.save(failed)
        if (failed != null) operations.recordCheckpoint(failed, at)
        return if (failed?.state == BackgroundOperationState.FAILED_RETRYABLE) ExportRunOutcome.RETRY else ExportRunOutcome.FAILED
    }
}

private enum class ExportRunOutcome { SUCCEEDED, CANCELLED, RETRY, FAILED }

private suspend fun SqlCipherBackgroundOperationRepository.recordCheckpoint(
    operation: app.ledger.transfer.domain.BackgroundOperation,
    at: java.time.Instant,
) {
    append(
        OperationCheckpoint(
            operation.id,
            operation.checkpointVersion,
            operation.state,
            checkpoint(operation.progress.current),
            at,
        ),
    )
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ExportWorkerEntryPoint {
    fun settings(): AppSettingsRepository
    fun keyProvider(): DeviceLedgerKeyProvider
    fun clock(): LedgerClock
    fun ledgerExport(): LedgerExportQueryPort
    fun sessionRuntime(): ActiveBookSessionRuntime
}

internal object ExportRunControlRegistry {
    private val controls = ConcurrentHashMap<StableId, AtomicBoolean>()
    fun control(id: StableId): AtomicBoolean = controls.getOrPut(id) { AtomicBoolean(false) }
    fun cancel(id: StableId) {
        control(id).set(true)
    }
    fun remove(id: StableId) {
        controls.remove(id)
    }
}

internal object ExportWorkScheduler {
    fun enqueue(context: Context, operationId: StableId, remoteProvider: Boolean) {
        if (Build.VERSION.SDK_INT >= 34 && remoteProvider) {
            val extras = PersistableBundle().apply { putString(ExportWorker.INPUT_OPERATION_ID, operationId.toString()) }
            val info = android.app.job.JobInfo.Builder(operationId.hashCode() and Int.MAX_VALUE, ComponentName(context, ExportUserInitiatedJobService::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
                .setExtras(extras)
                .build()
            require(context.getSystemService(android.app.job.JobScheduler::class.java).schedule(info) == android.app.job.JobScheduler.RESULT_SUCCESS)
        } else {
            val request = OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(Data.Builder().putString(ExportWorker.INPUT_OPERATION_ID, operationId.toString()).build())
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("ledger-export-$operationId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}

private fun exportForegroundInfo(context: Context, rows: Long): ForegroundInfo = ForegroundInfo(
    EXPORT_NOTIFICATION_ID,
    exportNotification(context, rows),
    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
)

private fun exportNotification(context: Context, rows: Long): Notification = OperationNotificationCoordinator.create(
    context,
    OperationNotificationContent(
        context.getString(R.string.export_worker_channel),
        context.getString(R.string.export_worker_title),
        context.getString(R.string.export_worker_progress, rows),
    ),
)

private fun ExportFormat.mimeType(): String = when (this) {
    ExportFormat.CSV -> "text/csv"
    ExportFormat.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    ExportFormat.PDF -> "application/pdf"
    ExportFormat.IMAGE -> "image/png"
    ExportFormat.PORTABLE_BACKUP -> "application/octet-stream"
}

private fun <T> DomainResult<T>.successOrNull(): T? = (this as? DomainResult.Success)?.value

private const val EXPORT_NOTIFICATION_ID = 29_001
