@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "NestedBlockDepth",
    "ReturnCount",
    "TooGenericExceptionCaught",
)

package app.ledger.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.security.BackupKeyEnvelopeStore
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.core.security.SecureTransferHandleStore
import app.ledger.core.time.LedgerClock
import app.ledger.transfer.data.BackupConfigurationStore
import app.ledger.transfer.data.BackupProgressStore
import app.ledger.transfer.data.BackupRecoveryReencryption
import app.ledger.transfer.data.BackupStorageArea
import app.ledger.transfer.data.DriveBackupArtifact
import app.ledger.transfer.data.DriveBackupRepositoryPublisher
import app.ledger.transfer.data.DriveResumableBackupClient
import app.ledger.transfer.data.DurableDriveBackupSession
import app.ledger.transfer.data.FileBackupRepositoryStorage
import app.ledger.transfer.data.ManagedBackupRepositoryEngine
import app.ledger.transfer.data.PortableBackupInput
import app.ledger.transfer.data.PortableBackupWriter
import app.ledger.transfer.data.SafBackupRepositoryStorage
import app.ledger.transfer.data.SafPortableBackupDestination
import app.ledger.transfer.data.SqlCipherBackgroundOperationRepository
import app.ledger.transfer.data.SqlCipherDriveCheckpointStore
import app.ledger.transfer.domain.BackgroundOperation
import app.ledger.transfer.domain.BackgroundOperationId
import app.ledger.transfer.domain.BackgroundOperationState
import app.ledger.transfer.domain.BackupFailure
import app.ledger.transfer.domain.BackupPhase
import app.ledger.transfer.domain.BackupProgressObserver
import app.ledger.transfer.domain.BackupRepositoryKind
import app.ledger.transfer.domain.BackupSnapshotId
import app.ledger.transfer.domain.DriveUploadState
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
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Backup worker input contains exactly one opaque operation id. */
internal class BackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = if (inputData.keyValueMap.keys == setOf(INPUT_OPERATION_ID)) {
            inputData.getString(INPUT_OPERATION_ID)?.let(StableId::parse)?.getOrNull()
        } else {
            null
        } ?: return Result.failure()
        setForeground(backupForegroundInfo(applicationContext, BackupPhase.DATABASE_SNAPSHOT, 0L))
        val outcome = BackupOperationRunner(applicationContext).run(id, runAttemptCount < MAX_RETRIES) { phase, bytes ->
            setProgress(Data.Builder().putString(PROGRESS_PHASE, phase.name).putLong(PROGRESS_BYTES, bytes).build())
            setForeground(backupForegroundInfo(applicationContext, phase, bytes))
        }
        return when (outcome) {
            BackupRunOutcome.SUCCEEDED, BackupRunOutcome.CANCELLED -> Result.success()
            BackupRunOutcome.RETRY -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            BackupRunOutcome.FAILED -> Result.failure()
        }
    }

    companion object {
        const val INPUT_OPERATION_ID = "operationId"
        const val PROGRESS_PHASE = "phase"
        const val PROGRESS_BYTES = "bytes"
        private const val MAX_RETRIES = 3
    }
}

internal class BackupUserInitiatedJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val id = if (params.extras.keySet() == setOf(BackupWorker.INPUT_OPERATION_ID)) {
            StableId.parse(params.extras.getString(BackupWorker.INPUT_OPERATION_ID, "")).getOrNull()
        } else {
            null
        } ?: return false
        if (Build.VERSION.SDK_INT >= 34) {
            setNotification(params, BACKUP_NOTIFICATION_ID, backupNotification(this, BackupPhase.DATABASE_SNAPSHOT, 0), JOB_END_NOTIFICATION_POLICY_REMOVE)
        }
        jobs[params.jobId] = scope.launch {
            val outcome = BackupOperationRunner(applicationContext).run(id, false) { phase, bytes ->
                if (Build.VERSION.SDK_INT >= 34) {
                    setNotification(params, BACKUP_NOTIFICATION_ID, backupNotification(this@BackupUserInitiatedJobService, phase, bytes), JOB_END_NOTIFICATION_POLICY_REMOVE)
                }
            }
            jobs.remove(params.jobId)
            jobFinished(params, outcome == BackupRunOutcome.RETRY)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        StableId.parse(params.extras.getString(BackupWorker.INPUT_OPERATION_ID, "")).getOrNull()?.let(BackupRunControlRegistry::cancel)
        jobs.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

private class BackupOperationRunner(private val context: Context) {
    suspend fun run(operationStableId: StableId, retryAllowed: Boolean, progress: suspend (BackupPhase, Long) -> Unit): BackupRunOutcome {
        val dependencies = EntryPointAccessors.fromApplication(context, BackupWorkerEntryPoint::class.java)
        val bookId = StableId.fromBytes(dependencies.settings().current().bookId.toByteArray()).getOrNull() ?: return BackupRunOutcome.FAILED
        val access = SecurePrimaryLedgerAccess(context, dependencies.keyProvider())
        val operations = SqlCipherBackgroundOperationRepository(bookId, access)
        val operationId = BackgroundOperationId(operationStableId)
        var operation = operations.get(operationId).successOrNull() ?: return BackupRunOutcome.FAILED
        val repositoryId = when (val parameters = operation.parameters) {
            is OperationParameters.FullBackup -> parameters.repositoryId
            is OperationParameters.BackupRecoveryReencryption -> parameters.repositoryId
            else -> return BackupRunOutcome.FAILED
        }
        if (operation.state == BackgroundOperationState.SUCCEEDED) return BackupRunOutcome.SUCCEEDED
        val configuration = BackupConfigurationStore(context, dependencies.keyProvider()).read(bookId)
            ?.takeIf { it.repositoryId == repositoryId } ?: return fail(operation, operations, BackupFailure.RepositoryUnavailable, dependencies.clock(), false)
        if (configuration.policy.validate(BackupKeyEnvelopeStore(context, dependencies.keyProvider()).isConfigured(configuration.repositoryId.value)) is DomainResult.Failure) {
            return fail(operation, operations, BackupFailure.RecoveryPasswordRequired, dependencies.clock(), false)
        }
        if (operation.state == BackgroundOperationState.FAILED_RETRYABLE) {
            operation = operation.transition(BackgroundOperationState.QUEUED, dependencies.clock().now(), errorCode = null).successOrNull()
                ?: return BackupRunOutcome.FAILED
            operations.save(operation)
        }
        if (operation.state == BackgroundOperationState.QUEUED) {
            operation = operation.transition(BackgroundOperationState.PREPARING, dependencies.clock().now()).successOrNull()
                ?: return BackupRunOutcome.FAILED
            operations.save(operation)
        }
        val control = BackupRunControlRegistry.control(operationStableId)
        val progressStore = BackupProgressStore(context, dependencies.keyProvider())
        progressStore.save(bookId, operationStableId, app.ledger.transfer.domain.BackupProgress(BackupPhase.DATABASE_SNAPSHOT, 0L, null, 0L))
        val snapshotId = BackupSnapshotId(operationStableId)
        val keyStore = BackupKeyEnvelopeStore(context, dependencies.keyProvider())
        val repositoryKey = try {
            keyStore.openForAutomaticBackup(bookId, configuration.repositoryId.value)
        } catch (_: Exception) {
            return fail(operation, operations, BackupFailure.RecoveryPasswordRequired, dependencies.clock(), false)
        }
        repositoryKey.use { key ->
            try {
                if (operation.state == BackgroundOperationState.PREPARING) {
                    operation = operation.transition(BackgroundOperationState.RUNNING, dependencies.clock().now()).successOrNull()
                        ?: return BackupRunOutcome.FAILED
                    operations.save(operation)
                }
                if (operation.parameters is OperationParameters.BackupRecoveryReencryption) {
                    val result = reencryptAccessibleHistory(
                        bookId,
                        operationStableId,
                        configuration,
                        access,
                        dependencies,
                        key,
                        keyStore.recoveryEnvelope(configuration.repositoryId.value),
                        control,
                    ) { value ->
                        progressStore.save(bookId, operationStableId, value)
                        operation.advance(OperationProgress(value.completedBytes, value.totalBytes), dependencies.clock().now())
                            .successOrNull()?.let { advanced ->
                                operation = advanced
                                operations.save(advanced)
                            }
                        progress(value.phase, value.completedBytes)
                    }
                    if (result is DomainResult.Failure) {
                        return if (result.error == BackupFailure.Cancelled) {
                            cancel(operation, operations, dependencies.clock())
                        } else {
                            fail(operation, operations, result.error as? BackupFailure ?: BackupFailure.RepositoryUnavailable, dependencies.clock(), retryAllowed)
                        }
                    }
                    operation = operation.transition(BackgroundOperationState.COMMITTING, dependencies.clock().now(), operation.progress).successOrNull()
                        ?: return BackupRunOutcome.FAILED
                    operations.save(operation)
                    operation = operation.transition(BackgroundOperationState.SUCCEEDED, dependencies.clock().now(), operation.progress).successOrNull()
                        ?: return BackupRunOutcome.FAILED
                    operations.save(operation)
                    BackupRunControlRegistry.remove(operationStableId)
                    return BackupRunOutcome.SUCCEEDED
                }
                val parameters = operation.parameters as OperationParameters.FullBackup
                val prepared = AndroidBackupInputFactory(context, dependencies.keyProvider()).prepare(
                    bookId,
                    operationStableId,
                    configuration.repositoryId,
                    configuration.repositoryKind,
                    configuration.repositoryHandleId,
                    snapshotId,
                    operation.createdAt,
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown",
                    configuration.policy.includeVault,
                )
                prepared.use {
                    val persistProgress: suspend (app.ledger.transfer.domain.BackupProgress) -> Unit = { value ->
                        progressStore.save(bookId, operationStableId, value)
                        operation.advance(OperationProgress(value.completedBytes, value.totalBytes), dependencies.clock().now())
                            .successOrNull()?.let { advanced ->
                                operation = advanced
                                operations.save(advanced)
                            }
                        progress(value.phase, value.completedBytes)
                    }
                    val result = if (parameters.portable) {
                        createPortable(
                            bookId,
                            prepared,
                            key,
                            keyStore.recoveryEnvelope(configuration.repositoryId.value),
                            parameters.destinationHandleId ?: configuration.repositoryHandleId,
                            control,
                            progressStore,
                            operationStableId,
                        )
                    } else {
                        createManagedAndMaybeDrive(
                            bookId, operationStableId, prepared, key, keyStore.recoveryEnvelope(configuration.repositoryId.value), configuration,
                            access, dependencies, control, persistProgress,
                        )
                    }
                    if (result is DomainResult.Failure) {
                        return if (result.error == BackupFailure.Cancelled) {
                            cancel(operation, operations, dependencies.clock())
                        } else {
                            fail(operation, operations, result.error as? BackupFailure ?: BackupFailure.RepositoryUnavailable, dependencies.clock(), retryAllowed)
                        }
                    }
                    progressStore.read(bookId, operationStableId)?.let { value ->
                        operation.advance(OperationProgress(value.completedBytes, value.totalBytes), dependencies.clock().now())
                            .successOrNull()?.let { advanced ->
                                operation = advanced
                                operations.save(advanced)
                            }
                    }
                }
                operation = operation.transition(BackgroundOperationState.COMMITTING, dependencies.clock().now(), operation.progress).successOrNull()
                    ?: return BackupRunOutcome.FAILED
                operations.save(operation)
                operation = operation.transition(BackgroundOperationState.SUCCEEDED, dependencies.clock().now(), operation.progress).successOrNull()
                    ?: return BackupRunOutcome.FAILED
                operations.save(operation)
                BackupRunControlRegistry.remove(operationStableId)
                return BackupRunOutcome.SUCCEEDED
            } catch (_: SecurityException) {
                return fail(operation, operations, BackupFailure.PermissionRevoked, dependencies.clock(), retryAllowed)
            } catch (_: Exception) {
                return fail(operation, operations, BackupFailure.RepositoryUnavailable, dependencies.clock(), retryAllowed)
            }
        }
    }

    private fun createPortable(
        bookId: StableId,
        prepared: PreparedAndroidBackupInput,
        repositoryKey: app.ledger.core.security.SecretBytes,
        recoveryEnvelope: ByteArray,
        handleId: StableId,
        control: AtomicBoolean,
        progressStore: BackupProgressStore,
        operationId: StableId,
    ): DomainResult<*> {
        val handle = SecureTransferHandleStore(context, EntryPointAccessors.fromApplication(context, BackupWorkerEntryPoint::class.java).keyProvider())
            .read(bookId, handleId) ?: return DomainResult.Failure(BackupFailure.PermissionRevoked)
        val tree = Uri.parse(handle.substringBefore('\n'))
        val fileName = handle.substringAfter('\n', "ledger-${prepared.input.snapshotId.value}.ledger-backup")
        val destination = SafPortableBackupDestination(context, tree, fileName)
        val source = prepared.input
        return destination.writeAndPublish { output ->
            PortableBackupWriter().write(
                PortableBackupInput(
                    source.bookId, source.repositoryId, source.snapshotId, recoveryEnvelope,
                    source.databaseSnapshot, source.settings, source.attachments, source.portableKeyMaterial, source.vaultRecoveryEnvelope,
                ),
                repositoryKey,
                output,
                cancelled = { control.get() },
                progress = { value -> progressStore.save(bookId, operationId, value) },
            )
        }
    }

    private suspend fun createManagedAndMaybeDrive(
        bookId: StableId,
        operationId: StableId,
        prepared: PreparedAndroidBackupInput,
        repositoryKey: app.ledger.core.security.SecretBytes,
        recoveryEnvelope: ByteArray,
        configuration: app.ledger.transfer.data.BackupConfiguration,
        access: SecurePrimaryLedgerAccess,
        dependencies: BackupWorkerEntryPoint,
        control: AtomicBoolean,
        onProgress: suspend (app.ledger.transfer.domain.BackupProgress) -> Unit,
    ): DomainResult<*> {
        val localRoot = repositoryRoot(bookId, configuration.repositoryId.value)
        val storage = when (configuration.repositoryKind) {
            BackupRepositoryKind.APP_PRIVATE -> FileBackupRepositoryStorage(localRoot)
            BackupRepositoryKind.USER_SELECTED_DIRECTORY -> {
                val handle = SecureTransferHandleStore(context, dependencies.keyProvider()).read(bookId, configuration.repositoryHandleId)
                    ?: return DomainResult.Failure(BackupFailure.PermissionRevoked)
                SafBackupRepositoryStorage(context, Uri.parse(handle.substringBefore('\n')))
            }
            BackupRepositoryKind.GOOGLE_DRIVE -> FileBackupRepositoryStorage(localRoot)
        }
        val catalog = createBackupCatalog(bookId, access)
        val alreadyComplete = catalog.completeSnapshots(configuration.repositoryId).any { it.id == prepared.input.snapshotId }
        val creation = if (alreadyComplete) {
            DomainResult.Success(Unit)
        } else {
            ManagedBackupRepositoryEngine(dependencies.stableIds()).create(
                prepared.input,
                storage,
                catalog,
                repositoryKey,
                recoveryEnvelope,
                configuration.policy.retention,
                cancelled = { control.get() },
                progress = BackupProgressObserver(onProgress),
            )
        }
        if (creation is DomainResult.Failure || configuration.repositoryKind != BackupRepositoryKind.GOOGLE_DRIVE) return creation
        val authorization = GoogleDriveAuthorizationGateway(context).authorize()
        val token = (authorization as? DomainResult.Success)?.value as? GoogleDriveAuthorization.Authorized
            ?: return DomainResult.Failure(BackupFailure.DriveAuthorizationRequired)
        val snapshotName = prepared.input.snapshotId.value.bytes.toHex() + ".manifest"
        val snapshotFiles = localRoot.resolve(BackupStorageArea.SNAPSHOTS.directoryName)
            .listFiles().orEmpty().filter(File::isFile).sortedBy(File::getName)
        val currentManifest = snapshotFiles.singleOrNull { it.name == snapshotName }
            ?: return DomainResult.Failure(BackupFailure.CorruptManifest)
        val artifacts = buildList {
            add(DriveBackupArtifact("repository-header.header", localRoot.resolve("repository-header.header"), replaceExisting = true))
            localRoot.resolve(BackupStorageArea.OBJECTS.directoryName).listFiles().orEmpty().filter(File::isFile).sortedBy(File::getName).forEach {
                add(DriveBackupArtifact(it.name, it))
            }
            snapshotFiles.filterNot { it == currentManifest }.forEach { add(DriveBackupArtifact(it.name, it)) }
            add(DriveBackupArtifact(snapshotName, currentManifest, finalManifest = true))
        }
        val driveClient = DriveResumableBackupClient(OkHttpClient())
        val folder = driveClient.ensureRepositoryFolder(token.accessToken, configuration.repositoryId.driveFolderName())
        val parentFolderId = (folder as? DomainResult.Success)?.value
            ?: return folder as DomainResult.Failure
        val now = dependencies.clock().now()
        val session = DurableDriveBackupSession(
            operationId,
            operationId,
            prepared.input.snapshotId,
            configuration.repositoryId,
            DriveUploadState.CREATED,
            null,
            emptySet(),
            false,
            now,
            now,
        )
        val publisher = DriveBackupRepositoryPublisher(
            driveClient,
            SqlCipherDriveCheckpointStore(bookId, access),
            dependencies.clock()::now,
        )
        val published = publisher.publish(session, token.accessToken, artifacts, parentFolderId) { control.get() }
        if (published is DomainResult.Success) {
            publisher.pruneUnreferenced(token.accessToken, parentFolderId, artifacts.mapTo(linkedSetOf(), DriveBackupArtifact::opaqueName))
        }
        return published
    }

    private suspend fun reencryptAccessibleHistory(
        bookId: StableId,
        operationId: StableId,
        configuration: app.ledger.transfer.data.BackupConfiguration,
        access: SecurePrimaryLedgerAccess,
        dependencies: BackupWorkerEntryPoint,
        repositoryKey: app.ledger.core.security.SecretBytes,
        recoveryEnvelope: ByteArray,
        control: AtomicBoolean,
        onProgress: suspend (app.ledger.transfer.domain.BackupProgress) -> Unit,
    ): DomainResult<*> {
        val localRoot = repositoryRoot(bookId, configuration.repositoryId.value)
        val storage = when (configuration.repositoryKind) {
            BackupRepositoryKind.APP_PRIVATE, BackupRepositoryKind.GOOGLE_DRIVE -> FileBackupRepositoryStorage(localRoot)
            BackupRepositoryKind.USER_SELECTED_DIRECTORY -> {
                val handle = SecureTransferHandleStore(context, dependencies.keyProvider()).read(bookId, configuration.repositoryHandleId)
                    ?: return DomainResult.Failure(BackupFailure.PermissionRevoked)
                SafBackupRepositoryStorage(context, Uri.parse(handle.substringBefore('\n')))
            }
        }
        val snapshots = createBackupCatalog(bookId, access).completeSnapshots(configuration.repositoryId)
        val total = snapshots.size.toLong()
        val result = BackupRecoveryReencryption().rewriteAccessibleHistory(
            storage,
            configuration.repositoryId,
            snapshots.map { it.id },
            repositoryKey,
            recoveryEnvelope,
            cancelled = { control.get() },
            progress = { completed, count ->
                onProgress(
                    app.ledger.transfer.domain.BackupProgress(
                        BackupPhase.WRITING_OR_UPLOADING,
                        completed.toLong(),
                        count.toLong(),
                        completed.toLong(),
                    ),
                )
            },
        )
        if (result is DomainResult.Failure || configuration.repositoryKind != BackupRepositoryKind.GOOGLE_DRIVE || snapshots.isEmpty()) return result
        val authorization = GoogleDriveAuthorizationGateway(context).authorize()
        val token = (authorization as? DomainResult.Success)?.value as? GoogleDriveAuthorization.Authorized
            ?: return DomainResult.Failure(BackupFailure.DriveAuthorizationRequired)
        val driveClient = DriveResumableBackupClient(OkHttpClient())
        val folder = driveClient.ensureRepositoryFolder(token.accessToken, configuration.repositoryId.driveFolderName())
        val parentFolderId = (folder as? DomainResult.Success)?.value
            ?: return folder as DomainResult.Failure
        onProgress(app.ledger.transfer.domain.BackupProgress(BackupPhase.PUBLISHING_MANIFEST, total, total, total))
        val manifestFiles = snapshots.map { snapshot ->
            localRoot.resolve("${BackupStorageArea.SNAPSHOTS.directoryName}/${snapshot.id.value.bytes.toHex()}.manifest")
                .also { require(it.isFile) { "catalogued backup manifest is unavailable" } }
        }
        val artifacts = buildList {
            manifestFiles.forEach { file ->
                add(DriveBackupArtifact(file.name, file, replaceExisting = true))
            }
            add(
                DriveBackupArtifact(
                    "repository-header.header",
                    localRoot.resolve("repository-header.header"),
                    finalManifest = true,
                    replaceExisting = true,
                ),
            )
        }
        val now = dependencies.clock().now()
        val finalSnapshot = snapshots.first().id
        val session = DurableDriveBackupSession(
            operationId,
            operationId,
            finalSnapshot,
            configuration.repositoryId,
            DriveUploadState.CREATED,
            null,
            emptySet(),
            false,
            now,
            now,
        )
        return DriveBackupRepositoryPublisher(
            driveClient,
            SqlCipherDriveCheckpointStore(bookId, access),
            dependencies.clock()::now,
        ).publish(session, token.accessToken, artifacts, parentFolderId) { control.get() }
    }

    private fun repositoryRoot(bookId: StableId, repositoryId: StableId): File = File(
        context.noBackupFilesDir,
        "backup-repositories/$bookId/$repositoryId",
    )

    private suspend fun cancel(
        operation: BackgroundOperation,
        operations: SqlCipherBackgroundOperationRepository,
        clock: LedgerClock,
    ): BackupRunOutcome {
        var current = operation.transition(BackgroundOperationState.CANCEL_REQUESTED, clock.now()).successOrNull() ?: operation
        operations.save(current)
        current = current.transition(BackgroundOperationState.ROLLING_BACK, clock.now()).successOrNull() ?: current
        operations.save(current)
        current = current.transition(BackgroundOperationState.FAILED_FINAL, clock.now(), errorCode = BackupFailure.Cancelled.code).successOrNull() ?: current
        operations.save(current)
        return BackupRunOutcome.CANCELLED
    }

    private suspend fun fail(
        operation: BackgroundOperation,
        operations: SqlCipherBackgroundOperationRepository,
        failure: BackupFailure,
        clock: LedgerClock,
        retryAllowed: Boolean,
    ): BackupRunOutcome {
        var current = operation
        if (current.state == BackgroundOperationState.QUEUED) {
            current = current.transition(BackgroundOperationState.PREPARING, clock.now()).successOrNull() ?: current
            operations.save(current)
        }
        val retryable = retryAllowed && failure in setOf(
            BackupFailure.NetworkUnavailable,
            BackupFailure.RepositoryUnavailable,
            BackupFailure.PermissionRevoked,
        )
        val state = if (retryable) BackgroundOperationState.FAILED_RETRYABLE else BackgroundOperationState.FAILED_FINAL
        val failed = current.transition(state, clock.now(), errorCode = failure.code).successOrNull()
        if (failed != null) operations.save(failed)
        return if (retryable) BackupRunOutcome.RETRY else BackupRunOutcome.FAILED
    }
}

private enum class BackupRunOutcome { SUCCEEDED, CANCELLED, RETRY, FAILED }

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface BackupWorkerEntryPoint {
    fun settings(): AppSettingsRepository
    fun keyProvider(): DeviceLedgerKeyProvider
    fun clock(): LedgerClock
    fun stableIds(): app.ledger.core.common.StableIdSource
}

internal object BackupRunControlRegistry {
    private val controls = ConcurrentHashMap<StableId, AtomicBoolean>()
    fun control(id: StableId): AtomicBoolean = controls.getOrPut(id) { AtomicBoolean(false) }
    fun cancel(id: StableId) {
        control(id).set(true)
    }
    fun remove(id: StableId) {
        controls.remove(id)
    }
}

internal object BackupWorkScheduler {
    fun enqueue(
        context: Context,
        operationId: StableId,
        drive: Boolean,
        userInitiated: Boolean,
        unmetered: Boolean = false,
    ) {
        if (Build.VERSION.SDK_INT >= 34 && drive && userInitiated) {
            val extras = PersistableBundle().apply { putString(BackupWorker.INPUT_OPERATION_ID, operationId.toString()) }
            val info = android.app.job.JobInfo.Builder(operationId.hashCode() and Int.MAX_VALUE, ComponentName(context, BackupUserInitiatedJobService::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(
                    if (unmetered) android.app.job.JobInfo.NETWORK_TYPE_UNMETERED else android.app.job.JobInfo.NETWORK_TYPE_ANY,
                )
                .setExtras(extras)
                .build()
            require(context.getSystemService(android.app.job.JobScheduler::class.java).schedule(info) == android.app.job.JobScheduler.RESULT_SUCCESS)
        } else {
            val constraints = Constraints.Builder().setRequiresStorageNotLow(true).apply {
                if (drive) {
                    setRequiredNetworkType(if (unmetered) androidx.work.NetworkType.UNMETERED else androidx.work.NetworkType.CONNECTED)
                }
            }.build()
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(Data.Builder().putString(BackupWorker.INPUT_OPERATION_ID, operationId.toString()).build())
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("ledger-backup-$operationId", ExistingWorkPolicy.KEEP, request)
        }
    }
}

private fun backupForegroundInfo(context: Context, phase: BackupPhase, bytes: Long): ForegroundInfo = ForegroundInfo(
    BACKUP_NOTIFICATION_ID,
    backupNotification(context, phase, bytes),
    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
)

private fun backupNotification(context: Context, phase: BackupPhase, bytes: Long): Notification {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(BACKUP_CHANNEL_ID, context.getString(R.string.backup_worker_channel), NotificationManager.IMPORTANCE_LOW),
    )
    return NotificationCompat.Builder(context, BACKUP_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(context.getString(R.string.backup_worker_title))
        .setContentText(context.getString(R.string.backup_worker_progress, context.getString(phase.labelResource()), bytes))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()
}

private fun BackupPhase.labelResource(): Int = when (this) {
    BackupPhase.DATABASE_SNAPSHOT -> R.string.backup_worker_phase_database
    BackupPhase.OBJECT_PROCESSING -> R.string.backup_worker_phase_objects
    BackupPhase.WRITING_OR_UPLOADING -> R.string.backup_worker_phase_writing
    BackupPhase.VERIFYING -> R.string.backup_worker_phase_verifying
    BackupPhase.PUBLISHING_MANIFEST -> R.string.backup_worker_phase_manifest
    BackupPhase.RETENTION -> R.string.backup_worker_phase_retention
    BackupPhase.COMPLETE -> R.string.backup_worker_phase_complete
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
private fun app.ledger.transfer.domain.BackupRepositoryId.driveFolderName(): String = value.bytes.toHex() + ".ledger-repository"
private fun <T> DomainResult<T>.successOrNull(): T? = (this as? DomainResult.Success)?.value
private const val BACKUP_CHANNEL_ID = "ledger-backup"
private const val BACKUP_NOTIFICATION_ID = 30_001
