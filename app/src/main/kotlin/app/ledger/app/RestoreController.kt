@file:Suppress(
    "LargeClass",
    "LongMethod",
    "MagicNumber",
    "NestedBlockDepth",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package app.ledger.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.BackupKeyEnvelopeStore
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.RecoveryPassword
import app.ledger.core.security.SecretBytes
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.core.security.SecureTransferHandleStore
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.core.security.VaultBackupEnvelopeStore
import app.ledger.feature.transfer.CloudClearPresentation
import app.ledger.feature.transfer.RestoreConflictUi
import app.ledger.feature.transfer.RestoreConflictField
import app.ledger.feature.transfer.RestoreConflictFieldUi
import app.ledger.feature.transfer.RestoreFlowUiState
import app.ledger.feature.transfer.RestoreIntegrityCheck
import app.ledger.feature.transfer.RestoreIntegrityCheckUi
import app.ledger.feature.transfer.RestoreInspectPresentation
import app.ledger.feature.transfer.RestorePasswordInput
import app.ledger.feature.transfer.RestorePasswordPresentation
import app.ledger.feature.transfer.RestoreProgressPresentation
import app.ledger.feature.transfer.RestoreResultPresentation
import app.ledger.feature.transfer.RestoreSnapshotUi
import app.ledger.feature.transfer.RestoreSourcePicker
import app.ledger.finance.application.MaterializedRestorePackage
import app.ledger.finance.data.SecureRoomMergeRestoreApplicationPort
import app.ledger.finance.data.SecureRoomRestoreLedgerApplicationPort
import app.ledger.transfer.data.BackupConfiguration
import app.ledger.transfer.data.BackupConfigurationStore
import app.ledger.transfer.data.BackupRepositoryInspector
import app.ledger.transfer.data.DirectoryRestoreTarget
import app.ledger.transfer.data.DriveBackupRepositoryDownloader
import app.ledger.transfer.data.DriveSnapshotDeletionRequest
import app.ledger.transfer.data.DriveSnapshotDeletionService
import app.ledger.transfer.data.EncryptedRestoreSource
import app.ledger.transfer.data.FileBackupRepositoryStorage
import app.ledger.transfer.data.MergeRestoreCoordinator
import app.ledger.transfer.data.MergeRestorePreview
import app.ledger.transfer.data.ReplaceRestoreCoordinator
import app.ledger.transfer.data.RestoreMaterializationResult
import app.ledger.transfer.data.RestoreMaterializer
import app.ledger.transfer.data.RestoreProgress
import app.ledger.transfer.data.RestoreProgressObserver
import app.ledger.transfer.data.SafBackupRepositoryStorage
import app.ledger.transfer.data.SqlCipherBackgroundOperationRepository
import app.ledger.transfer.data.SqlCipherMergeSessionStore
import app.ledger.transfer.domain.BackgroundOperation
import app.ledger.transfer.domain.BackgroundOperationId
import app.ledger.transfer.domain.BackgroundOperationState
import app.ledger.transfer.domain.BackgroundOperationType
import app.ledger.transfer.domain.BackupRepositoryKind
import app.ledger.transfer.domain.BackupSnapshotId
import app.ledger.transfer.domain.MergeConflict
import app.ledger.transfer.domain.MergeResolution
import app.ledger.transfer.domain.OperationParameters
import app.ledger.transfer.domain.OperationProgress
import app.ledger.transfer.domain.RestoreMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** In-memory secret UI state plus durable SQLCipher operation/conflict checkpoints. */
internal class RestoreController(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val runtime: AppRuntimeSources,
    private val formatCreatedAt: (Instant) -> String,
) {
    private val applicationContext = context.applicationContext
    private val mutableState = MutableStateFlow(RestoreFlowUiState())
    val state: StateFlow<RestoreFlowUiState> = mutableState.asStateFlow()
    private val keyHierarchy = DeviceKeyHierarchy(AndroidKeystoreKeys(applicationContext), SecurityEnvelopeStore(applicationContext))
    private val restoreLedger = SecureRoomRestoreLedgerApplicationPort(
        applicationContext,
        keyHierarchy,
        AndroidRestoreArtifactSwapPort(applicationContext),
    )
    private val mergeApplication = SecureRoomMergeRestoreApplicationPort(
        applicationContext,
        keyHierarchy,
        runtime.stableIds,
        runtime.clock::now,
        restoreLedger,
    )
    private val mergeLedger = AndroidMergeLedgerPort(mergeApplication)
    private val materializer = RestoreMaterializer()
    private val safety = AndroidPreRestoreSafetySnapshotPort(applicationContext, keyProvider, runtime)
    private var bookId: StableId? = null
    private var operationId: StableId? = null
    private var source: EncryptedRestoreSource? = null
    private var materialized: RestoreMaterializationResult? = null
    private var recoveredVaultDek: SecretBytes? = null
    private var mergePreview: MergeRestorePreview? = null
    private val resolutions = linkedMapOf<StableId, MergeResolution>()
    private val cancelled = AtomicBoolean(false)
    private var operation: BackgroundOperation? = null
    private var retainedSafetyOperationId: StableId? = null
    private var cloudFiles = emptyMap<String, app.ledger.transfer.data.DriveRemoteFile>()
    private var cloudSnapshots = emptyMap<String, app.ledger.transfer.domain.BackupSnapshot>()

    fun begin(activeBookId: StableId, baseCurrency: String = "") {
        cleanupTransient()
        bookId = activeBookId
        replaceState(RestoreFlowUiState(screenId = "RST-001", baseCurrency = baseCurrency))
    }

    fun setScreen(screenId: String) {
        if (screenId in SCREENS) mutableState.value = mutableState.value.copy(screenId = screenId)
    }

    fun selectPortable(uri: Uri): Boolean = try {
        val activeBook = requireNotNull(bookId)
        runCatching {
            applicationContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val handle = runtime.stableIds.nextStableId()
        SecureTransferHandleStore(applicationContext, keyProvider).save(activeBook, handle, uri.toString())
        selectSource(
            handle,
            EncryptedRestoreSource.PortableFile {
                applicationContext.contentResolver.openInputStream(uri) ?: error("restore source unavailable")
            },
            uri.lastPathSegment.orEmpty(),
        )
        true
    } catch (_: Exception) {
        mutableState.value = mutableState.value.copy(sourcePresentation = app.ledger.feature.transfer.RestoreSourcePresentation.PERMISSION_ERROR)
        false
    }

    fun selectLatestRepository(): Boolean = try {
        val activeBook = requireNotNull(bookId)
        val configuration = requireNotNull(BackupConfigurationStore(applicationContext, keyProvider).read(activeBook))
        if (configuration.repositoryKind == BackupRepositoryKind.GOOGLE_DRIVE) return false
        val storage = repositoryStorage(activeBook, configuration)
        val snapshot = requireNotNull(latestSnapshotId(storage))
        selectSource(
            configuration.repositoryHandleId,
            EncryptedRestoreSource.ManagedRepository(storage, configuration.repositoryId, snapshot),
            snapshot.value.toString(),
        )
        true
    } catch (_: SecurityException) {
        mutableState.value = mutableState.value.copy(sourcePresentation = app.ledger.feature.transfer.RestoreSourcePresentation.PERMISSION_ERROR)
        false
    } catch (_: Exception) {
        false
    }

    fun showRepositorySnapshots(): Boolean = try {
        val activeBook = requireNotNull(bookId)
        val configuration = requireNotNull(BackupConfigurationStore(applicationContext, keyProvider).read(activeBook))
        if (configuration.repositoryKind == BackupRepositoryKind.GOOGLE_DRIVE) return false
        val snapshots = snapshotPickerItems(activeBook, configuration)
        mutableState.value = mutableState.value.copy(
            sourcePicker = RestoreSourcePicker.REPOSITORY,
            repositorySnapshots = snapshots,
            sourcePresentation = app.ledger.feature.transfer.RestoreSourcePresentation.CONTENT,
        )
        true
    } catch (_: SecurityException) {
        mutableState.value = mutableState.value.copy(sourcePresentation = app.ledger.feature.transfer.RestoreSourcePresentation.PERMISSION_ERROR)
        false
    } catch (_: Exception) {
        mutableState.value = mutableState.value.copy(
            sourcePicker = RestoreSourcePicker.REPOSITORY,
            repositorySnapshots = emptyList(),
        )
        false
    }

    fun selectRepositorySnapshot(snapshotValue: String): Boolean = try {
        val activeBook = requireNotNull(bookId)
        val requested = StableId.parse(snapshotValue)
        val snapshotId = (requested as? DomainResult.Success)?.value ?: return false
        val configuration = requireNotNull(BackupConfigurationStore(applicationContext, keyProvider).read(activeBook))
        val storage = repositoryStorage(activeBook, configuration)
        val exists = storage.exists(
            app.ledger.transfer.data.BackupStorageArea.SNAPSHOTS,
            "${snapshotId.hex()}.manifest",
        )
        if (!exists) return false
        selectSource(
            configuration.repositoryHandleId,
            EncryptedRestoreSource.ManagedRepository(
                storage,
                configuration.repositoryId,
                app.ledger.transfer.domain.BackupSnapshotId(snapshotId),
            ),
            snapshotValue,
        )
        true
    } catch (_: Exception) {
        false
    }

    /** Returns a consent intent only when Google Identity requires fresh drive.file authorization. */
    suspend fun selectLatestDriveRepository(authorizationResult: Intent? = null): PendingIntent? {
        val activeBook = bookId ?: return null
        val configuration = BackupConfigurationStore(applicationContext, keyProvider).read(activeBook)
        if (configuration == null || configuration.repositoryKind != BackupRepositoryKind.GOOGLE_DRIVE) {
            mutableState.value = mutableState.value.copy(
                screenId = "RST-001",
                sourcePresentation = app.ledger.feature.transfer.RestoreSourcePresentation.DRIVE_NOT_CONFIGURED,
            )
            return null
        }
        mutableState.value = mutableState.value.copy(
            screenId = "RST-001",
            sourcePresentation = app.ledger.feature.transfer.RestoreSourcePresentation.LOADING_REMOTE,
        )
        val gateway = GoogleDriveAuthorizationGateway(applicationContext)
        val authorization = authorizationResult?.let(gateway::resultFromIntent) ?: gateway.authorize()
        return when (val value = (authorization as? DomainResult.Success)?.value) {
            is GoogleDriveAuthorization.ResolutionRequired -> {
                mutableState.value = mutableState.value.copy(sourcePresentation = app.ledger.feature.transfer.RestoreSourcePresentation.CONTENT)
                value.pendingIntent
            }
            is GoogleDriveAuthorization.Authorized -> {
                val client = app.ledger.transfer.data.DriveResumableBackupClient(OkHttpClient())
                val folder = client.ensureRepositoryFolder(value.accessToken, configuration.repositoryId.driveFolderName())
                val folderId = (folder as? DomainResult.Success)?.value
                val downloaded = folderId?.let {
                    DriveBackupRepositoryDownloader(client).download(
                        value.accessToken,
                        it,
                        repositoryRoot(activeBook, configuration.repositoryId.value),
                        cancelled = cancelled::get,
                    )
                }
                val storage = FileBackupRepositoryStorage(repositoryRoot(activeBook, configuration.repositoryId.value))
                if (downloaded !is DomainResult.Success) {
                    mutableState.value = mutableState.value.copy(sourcePresentation = app.ledger.feature.transfer.RestoreSourcePresentation.PERMISSION_ERROR)
                } else {
                    mutableState.value = mutableState.value.copy(
                        screenId = "RST-001",
                        sourcePicker = RestoreSourcePicker.DRIVE,
                        driveSnapshots = snapshotPickerItems(activeBook, configuration),
                        sourcePresentation = app.ledger.feature.transfer.RestoreSourcePresentation.CONTENT,
                    )
                }
                null
            }
            null -> {
                mutableState.value = mutableState.value.copy(sourcePresentation = app.ledger.feature.transfer.RestoreSourcePresentation.PERMISSION_ERROR)
                null
            }
        }
    }

    fun passwordChanged(value: String) {
        replaceState(
            mutableState.value.copy(
                password = RestorePasswordInput.copyOf(value),
                passwordPresentation = RestorePasswordPresentation.EDITING,
            ),
        )
    }

    suspend fun verifyAndInspect(): Boolean {
        val activeBook = bookId ?: return false
        val activeOperation = operationId ?: return false
        val selectedSource = source ?: return false
        val chars = mutableState.value.password.copyChars()
        if (operation?.state == BackgroundOperationState.FAILED_RETRYABLE) transition(BackgroundOperationState.QUEUED)
        mutableState.value = mutableState.value.copy(passwordPresentation = RestorePasswordPresentation.VERIFYING, screenId = "RST-003")
        transition(BackgroundOperationState.PREPARING)
        return try {
            RecoveryPassword.copyOf(chars).use { password ->
                val targetRoot = File(applicationContext.noBackupFilesDir, RESTORE_WORK_DIRECTORY)
                require(targetRoot.isDirectory || targetRoot.mkdirs())
                val result = materializer.materialize(
                    selectedSource,
                    password,
                    DirectoryRestoreTarget(targetRoot, activeOperation),
                    activeBook,
                    cancelled::get,
                    RestoreProgressObserver(::publishProgress),
                )
                when (result) {
                    is DomainResult.Failure -> {
                        failInspection(result.error.code)
                        false
                    }
                    is DomainResult.Success -> {
                        recoveredVaultDek?.close()
                        val vaultEnvelope = result.value.targetDirectory.resolve("keys/vault-recovery.envelope").takeIf(File::isFile)
                        val recoveredVault = runCatching {
                            vaultEnvelope?.readBytes()?.let { encoded ->
                                try {
                                    VaultBackupEnvelopeStore(applicationContext)
                                        .openWithRecoveryPassword(activeBook, password, encoded)
                                } finally {
                                    encoded.fill(0)
                                }
                            }
                        }
                        if (recoveredVault.isFailure) {
                            result.value.targetDirectory.walkBottomUp().forEach { file -> check(!file.exists() || file.delete()) }
                            failInspection("RESTORE_VAULT_RECOVERY_FAILED")
                            return@use false
                        }
                        recoveredVaultDek = recoveredVault.getOrNull()
                        val packageValue = result.value.toFinancePackage(activeBook, activeOperation)
                        val ledgerInspection = restoreLedger.prepareReplacement(packageValue)
                        val prepared = (ledgerInspection as? DomainResult.Success)?.value
                        restoreLedger.cleanup(activeOperation)
                        if (prepared == null || !prepared.integrity.isValid) {
                            result.value.targetDirectory.walkBottomUp().forEach { file -> check(!file.exists() || file.delete()) }
                            failInspection((ledgerInspection as? DomainResult.Failure)?.error?.code ?: "RESTORE_INTEGRITY_FAILED")
                            return@use false
                        }
                        materialized = result.value
                        transition(BackgroundOperationState.RUNNING, result.value.logicalBytes, result.value.logicalBytes)
                        mutableState.value = mutableState.value.copy(
                            passwordPresentation = RestorePasswordPresentation.EDITING,
                            inspectPresentation = RestoreInspectPresentation.COMPATIBLE,
                            bookIdentity = result.value.bookId.toString(),
                            sourceVersion = result.value.databaseSchemaVersion?.toString() ?: "portable-v1",
                            restoredObjectCount = result.value.restoredEntries,
                            restoredLogicalBytes = result.value.logicalBytes,
                            attachmentCount = result.value.targetDirectory.resolve("attachments")
                                .takeIf(File::isDirectory)
                                ?.walkTopDown()
                                ?.count(File::isFile)
                                ?: 0,
                            includesVault = result.value.includesVault,
                            integrityChecks = prepared.integrity.toUiChecks(),
                            mergeAvailable = prepared.expectedLiveHead != null,
                            screenId = "RST-004",
                        )
                        true
                    }
                }
            }
        } finally {
            chars.fill('\u0000')
            clearPassword()
        }
    }

    fun selectMode(mode: RestoreMode) {
        mutableState.value = mutableState.value.copy(mode = mode)
        val activeBook = bookId ?: return
        val changed = operation?.configureRestoreMode(mode, runtime.clock.now()) as? DomainResult.Success ?: return
        operation = changed.value
        runCatching { operations(activeBook).saveImmediately(changed.value) }
    }

    fun takeRecoveredVaultDek(): SecretBytes? = recoveredVaultDek.also { recoveredVaultDek = null }

    fun highRiskPhraseChanged(value: String) {
        mutableState.value = mutableState.value.copy(highRiskPhrase = value.take(80))
    }

    suspend fun start(): Boolean {
        val activeBook = bookId ?: return false
        val activeOperation = operationId ?: return false
        val packageValue = materialized ?: return false
        if (
            mutableState.value.mode == RestoreMode.REPLACE &&
            mutableState.value.highRiskPhrase != applicationContext.getString(app.ledger.feature.transfer.R.string.restore_replace_phrase)
        ) {
            return false
        }
        cancelled.set(false)
        mutableState.value = mutableState.value.copy(screenId = "RST-006", progressPresentation = RestoreProgressPresentation.RUNNING)
        return if (mutableState.value.mode == RestoreMode.REPLACE) {
            transition(BackgroundOperationState.COMMITTING, packageValue.logicalBytes, packageValue.logicalBytes)
            val result = ReplaceRestoreCoordinator(materializer, safety, restoreLedger).executeMaterialized(
                activeBook,
                activeOperation,
                packageValue,
                cancelled::get,
                RestoreProgressObserver(::publishProgress),
            )
            finish(result)
        } else {
            val coordinator = mergeCoordinator()
            when (val preview = coordinator.previewMaterialized(activeBook, activeOperation, packageValue, cancelled::get)) {
                is DomainResult.Failure -> finish(preview)
                is DomainResult.Success -> {
                    mergePreview = preview.value
                    resolutions.clear()
                    preview.value.plan.conflicts.filter { it.purgeTombstone != null }.forEach {
                        resolutions[it.id.value] = MergeResolution.KeepPurgeTombstone
                    }
                    mutableState.value = mutableState.value.copy(
                        screenId = "RST-005",
                        conflicts = preview.value.plan.conflicts.map { conflict ->
                            RestoreConflictUi(
                                conflict.id.value.toString(),
                                conflict.kind,
                                conflict.entityLabel(),
                                "",
                                "",
                                "",
                                resolutions[conflict.id.value],
                                conflict.purgeTombstone != null,
                                fields = listOf(
                                    RestoreConflictFieldUi(
                                        RestoreConflictField.RECORD_CONTENT,
                                        "",
                                        "",
                                        "",
                                        ancestorGeneration = conflict.ancestor?.generation,
                                        localGeneration = conflict.local?.generation,
                                        incomingGeneration = conflict.incoming?.generation,
                                    ),
                                ),
                            )
                        },
                    )
                    if (preview.value.plan.conflicts.isEmpty()) applyMerge() else true
                }
            }
        }
    }

    fun resolve(conflictId: String, resolution: MergeResolution) {
        val preview = mergePreview ?: return
        val conflict = preview.plan.conflicts.singleOrNull { it.id.value.toString() == conflictId } ?: return
        val accepted = (conflict.resolve(resolution) as? DomainResult.Success)?.value ?: return
        val targets = if (mutableState.value.applyToSimilar) {
            preview.plan.conflicts.filter { it.kind == conflict.kind && it.purgeTombstone == null }
        } else {
            listOf(conflict)
        }
        targets.forEach { resolutions[it.id.value] = resolution }
        val targetIds = targets.mapTo(hashSetOf()) { it.id.value.toString() }
        mutableState.value = mutableState.value.copy(
            conflicts = mutableState.value.conflicts.map {
                if (it.id in targetIds) it.copy(resolution = accepted.resolution) else it
            },
        )
    }

    fun applyToSimilarChanged(value: Boolean) {
        mutableState.value = mutableState.value.copy(applyToSimilar = value)
    }

    suspend fun applyMerge(): Boolean {
        val activeBook = bookId ?: return false
        val activeOperation = operationId ?: return false
        val preview = mergePreview ?: return false
        mutableState.value = mutableState.value.copy(screenId = "RST-006")
        transition(BackgroundOperationState.COMMITTING, preview.materialized.logicalBytes, preview.materialized.logicalBytes)
        return finish(
            mergeCoordinator().executeResolved(
                activeBook,
                activeOperation,
                preview,
                resolutions,
                RestoreProgressObserver(::publishProgress),
            ),
        )
    }

    fun cancel() {
        if (mutableState.value.phase !in NON_CANCELABLE_STATES) {
            cancelled.set(true)
            transition(BackgroundOperationState.CANCEL_REQUESTED)
        }
    }

    suspend fun loadCloudBackups(): Boolean {
        val activeBook = bookId ?: return false
        mutableState.value = mutableState.value.copy(screenId = "CLR-002", cloudClearPresentation = CloudClearPresentation.LOADING)
        val configuration = BackupConfigurationStore(applicationContext, keyProvider).read(activeBook)
        if (configuration == null || configuration.repositoryKind != BackupRepositoryKind.GOOGLE_DRIVE) {
            mutableState.value = mutableState.value.copy(cloudClearPresentation = CloudClearPresentation.FAILED, cloudAuthenticated = false)
            return false
        }
        val authorization = GoogleDriveAuthorizationGateway(applicationContext).authorize()
        val token = (authorization as? DomainResult.Success)?.value as? GoogleDriveAuthorization.Authorized
        if (token == null) {
            mutableState.value = mutableState.value.copy(
                cloudClearPresentation = CloudClearPresentation.AUTH_REQUIRED,
                cloudAuthenticated = false,
            )
            return false
        }
        val client = app.ledger.transfer.data.DriveResumableBackupClient(OkHttpClient())
        val folder = client.ensureRepositoryFolder(token.accessToken, configuration.repositoryId.driveFolderName())
        val folderId = (folder as? DomainResult.Success)?.value ?: run {
            mutableState.value = mutableState.value.copy(cloudClearPresentation = CloudClearPresentation.FAILED)
            return false
        }
        val files = client.listRepositoryFiles(token.accessToken, folderId)
        val listed = (files as? DomainResult.Success)?.value ?: run {
            mutableState.value = mutableState.value.copy(cloudClearPresentation = CloudClearPresentation.FAILED)
            return false
        }
        val catalogSnapshots = createBackupCatalog(
            activeBook,
            SecurePrimaryLedgerAccess(applicationContext, keyProvider),
        ).completeSnapshots(configuration.repositoryId).associateBy { it.id.value.bytes.toHex() + ".manifest" }
        cloudFiles = listed.associateBy { it.name }
        cloudSnapshots = catalogSnapshots.filterKeys { it in cloudFiles }
        mutableState.value = mutableState.value.copy(
            cloudSnapshots = cloudSnapshots.entries
                .sortedByDescending { it.value.createdAt }
                .map { (name, snapshot) -> snapshot.toPickerUi(name, configuration.repositoryKind, activeBook, configuration) },
            cloudClearPresentation = if (cloudSnapshots.isEmpty()) CloudClearPresentation.EMPTY else CloudClearPresentation.CONTENT,
            cloudAuthenticated = true,
        )
        return true
    }

    fun selectCloudSnapshot(value: String) {
        val selected = mutableState.value.selectedCloudSnapshots
        mutableState.value = mutableState.value.copy(
            selectedCloudSnapshots = if (value in selected) selected - value else selected + value,
        )
    }

    fun cloudPhraseChanged(value: String) {
        mutableState.value = mutableState.value.copy(cloudConfirmationPhrase = value.take(80))
    }

    suspend fun deleteSelectedCloudBackups(): Boolean {
        val activeBook = bookId ?: return false
        val configuration = BackupConfigurationStore(applicationContext, keyProvider).read(activeBook) ?: return false
        if (
            mutableState.value.selectedCloudSnapshots.isEmpty() ||
            mutableState.value.cloudConfirmationPhrase != applicationContext.getString(app.ledger.feature.transfer.R.string.clear_cloud_phrase)
        ) {
            return false
        }
        val authorization = GoogleDriveAuthorizationGateway(applicationContext).authorize()
        val token = (authorization as? DomainResult.Success)?.value as? GoogleDriveAuthorization.Authorized
        if (token == null) {
            mutableState.value = mutableState.value.copy(
                cloudClearPresentation = CloudClearPresentation.AUTH_REQUIRED,
                cloudAuthenticated = false,
            )
            return false
        }
        mutableState.value = mutableState.value.copy(cloudClearPresentation = CloudClearPresentation.DELETING)
        val client = app.ledger.transfer.data.DriveResumableBackupClient(OkHttpClient())
        val selected = mutableState.value.selectedCloudSnapshots
        val requests = selected.mapNotNull { name ->
            val file = cloudFiles[name] ?: return@mapNotNull null
            val snapshot = cloudSnapshots[name] ?: return@mapNotNull null
            DriveSnapshotDeletionRequest(snapshot, file)
        }
        val deleted = if (requests.size == selected.size) {
            DriveSnapshotDeletionService(client).delete(
                token.accessToken,
                requests,
                cloudFiles.values.toList(),
                createBackupCatalog(activeBook, SecurePrimaryLedgerAccess(applicationContext, keyProvider)),
            )
        } else {
            DomainResult.Failure(app.ledger.transfer.domain.BackupFailure.RepositoryUnavailable)
        }
        val result = (deleted as? DomainResult.Success)?.value
        val success = result != null && result.failedManifestNames.isEmpty()
        val removed = result?.deletedManifestNames.orEmpty()
        val localStorage = FileBackupRepositoryStorage(repositoryRoot(activeBook, configuration.repositoryId.value))
        removed.forEach { name ->
            runCatching { localStorage.delete(app.ledger.transfer.data.BackupStorageArea.SNAPSHOTS, name) }
            cloudSnapshots -= name
            cloudFiles -= name
        }
        mutableState.value = mutableState.value.copy(
            cloudClearPresentation = if (success) CloudClearPresentation.CONTENT else CloudClearPresentation.FAILED,
            cloudSnapshots = mutableState.value.cloudSnapshots.filterNot { it.id in removed },
            selectedCloudSnapshots = selected - removed,
            cloudConfirmationPhrase = "",
        )
        return success
    }

    fun currentOperationId(): StableId? = operationId

    suspend fun recoverInterrupted(activeBook: StableId): Boolean {
        val markerOperations = applicationContext.filesDir.listFiles().orEmpty().mapNotNull { file ->
            MARKER_FILE.matchEntire(file.name)?.groupValues?.get(1)?.hexStableId()
        }
        markerOperations.forEach { id ->
            restoreLedger.recoverInterrupted(activeBook, id)
            restoreLedger.cleanup(id)
            mergeLedger.cleanup(id)
        }
        val repository = operations(activeBook)
        val pending = runCatching { repository.recoverableRestoreOperations() }.getOrDefault(emptyList())
        if (pending.isEmpty()) return markerOperations.isNotEmpty()
        pending.forEach { value ->
            val id = value.id.value
            if (value.state in setOf(BackgroundOperationState.COMMITTING, BackgroundOperationState.ROLLING_BACK)) {
                restoreLedger.recoverInterrupted(activeBook, id)
            }
            restoreLedger.cleanup(id)
            mergeLedger.cleanup(id)
            val temporary = File(applicationContext.noBackupFilesDir, "$RESTORE_WORK_DIRECTORY/restore-${id.hex()}")
            if (temporary.isDirectory) {
                temporary.walkBottomUp().forEach { file -> check(!file.exists() || file.delete()) }
            }
            var terminal = value
            if (terminal.state == BackgroundOperationState.QUEUED) {
                terminal = (terminal.transition(BackgroundOperationState.PREPARING, runtime.clock.now()) as DomainResult.Success).value
            }
            if (terminal.state == BackgroundOperationState.CANCEL_REQUESTED) {
                terminal = (terminal.transition(BackgroundOperationState.ROLLING_BACK, runtime.clock.now()) as DomainResult.Success).value
            }
            if (terminal.state !in setOf(BackgroundOperationState.FAILED_RETRYABLE, BackgroundOperationState.FAILED_FINAL)) {
                terminal = terminal.toInterruptedFailure()
            } else if (terminal.state == BackgroundOperationState.FAILED_RETRYABLE) {
                terminal = terminal.toInterruptedFailure()
            }
            repository.save(terminal)
        }
        return true
    }

    private fun selectSource(handle: StableId, value: EncryptedRestoreSource, label: String) {
        cleanupTransient()
        val activeBook = requireNotNull(bookId)
        val id = runtime.stableIds.nextStableId()
        operationId = id
        source = value
        operation = BackgroundOperation.queued(
            BackgroundOperationId(id),
            BackgroundOperationType.RESTORE_REPLACE,
            runtime.clock.now(),
            OperationParameters.Restore(handle, RestoreMode.REPLACE),
        )
        runCatching { operations(activeBook).saveImmediately(requireNotNull(operation)) }
        clearPassword { it.copy(screenId = "RST-002", sourceLabel = label) }
    }

    private fun mergeCoordinator() = MergeRestoreCoordinator(
        materializer,
        safety,
        mergeLedger,
        restoreLedger,
        SqlCipherMergeSessionStore(SecurePrimaryLedgerAccess(applicationContext, keyProvider), runtime.clock::now),
    )

    private fun MergeConflict.entityLabel(): String {
        val entity = local ?: incoming ?: ancestor
        return "${entity?.entityType}:${entity?.entityId}"
    }

    private fun BackgroundOperation.toInterruptedFailure(): BackgroundOperation = (
        transition(
            BackgroundOperationState.FAILED_FINAL,
            runtime.clock.now(),
            errorCode = "RESTORE_INTERRUPTED_ROLLED_BACK",
        ) as DomainResult.Success
        ).value

    private fun publishProgress(value: RestoreProgress) {
        mutableState.value = mutableState.value.copy(
            phase = value.state,
            completedBytes = value.completedBytes,
            totalBytes = value.totalBytes,
        )
        val state = if (value.state in NON_CANCELABLE_STATES) BackgroundOperationState.COMMITTING else BackgroundOperationState.RUNNING
        transition(state, value.completedBytes, value.totalBytes)
    }

    fun confirmSafetySnapshotCleanup(): Boolean {
        val retained = retainedSafetyOperationId ?: return false
        return when (restoreLedger.confirmSafetySnapshotCleanup(retained)) {
            is DomainResult.Success -> {
                retainedSafetyOperationId = null
                mutableState.value = mutableState.value.copy(
                    safetySnapshotRetained = false,
                    safetySnapshotLabel = "cleanup confirmed",
                )
                true
            }
            is DomainResult.Failure -> false
        }
    }

    private fun finish(result: DomainResult<*>): Boolean = when (result) {
        is DomainResult.Success -> {
            val restore = result.value as? app.ledger.transfer.data.ReplaceRestoreResult
            retainedSafetyOperationId = operationId
            transition(BackgroundOperationState.SUCCEEDED, mutableState.value.completedBytes, mutableState.value.totalBytes)
            mutableState.value = mutableState.value.copy(
                screenId = "RST-007",
                progressPresentation = RestoreProgressPresentation.SUCCEEDED,
                resultPresentation = RestoreResultPresentation.SUCCESS,
                safetySnapshotLabel = "",
                verificationSummary = "",
                failureCode = null,
            )
            true
        }
        is DomainResult.Failure -> {
            val rolledBack = result.error.code.contains("ROLLED_BACK")
            transition(BackgroundOperationState.FAILED_FINAL, errorCode = result.error.code)
            mutableState.value = mutableState.value.copy(
                screenId = "RST-007",
                progressPresentation = RestoreProgressPresentation.FAILED_ROLLBACK,
                resultPresentation = if (rolledBack) RestoreResultPresentation.ROLLED_BACK else RestoreResultPresentation.FAILED,
                failureCode = result.error.code,
                safetySnapshotLabel = "",
                verificationSummary = "",
            )
            false
        }
    }

    private fun failInspection(code: String) {
        transition(BackgroundOperationState.FAILED_RETRYABLE, errorCode = code)
        mutableState.value = mutableState.value.copy(
            screenId = "RST-002",
            passwordPresentation = if (code.contains("WRONG_PASSWORD")) RestorePasswordPresentation.WRONG_PASSWORD else RestorePasswordPresentation.EDITING,
            inspectPresentation = if (code.contains("BOOK_MISMATCH")) RestoreInspectPresentation.INCOMPATIBLE_BOOK else RestoreInspectPresentation.CORRUPT,
            failureCode = code,
        )
    }

    private fun transition(
        next: BackgroundOperationState,
        current: Long = mutableState.value.completedBytes,
        total: Long? = mutableState.value.totalBytes,
        errorCode: String? = null,
    ) {
        val activeBook = bookId ?: return
        val previous = operation ?: return
        if (previous.state == next && next in setOf(BackgroundOperationState.RUNNING, BackgroundOperationState.COMMITTING)) {
            val advanced = previous.advance(OperationProgress(current, total), runtime.clock.now()) as? DomainResult.Success ?: return
            operation = advanced.value
        } else {
            val changed = previous.transition(next, runtime.clock.now(), OperationProgress(current, total), errorCode) as? DomainResult.Success
                ?: return
            operation = changed.value
        }
        runCatching { operations(activeBook).saveImmediately(requireNotNull(operation)) }
    }

    private fun clearPassword(transform: (RestoreFlowUiState) -> RestoreFlowUiState = { it }) {
        replaceState(transform(mutableState.value).copy(password = RestorePasswordInput.empty()))
    }

    private fun replaceState(value: RestoreFlowUiState) {
        val previous = mutableState.value
        mutableState.value = value
        if (previous.password !== value.password) previous.password.close()
    }

    private fun operations(activeBook: StableId) = SqlCipherBackgroundOperationRepository(activeBook, SecurePrimaryLedgerAccess(applicationContext, keyProvider))

    private fun repositoryStorage(book: StableId, configuration: BackupConfiguration) = when (configuration.repositoryKind) {
        BackupRepositoryKind.APP_PRIVATE,
        BackupRepositoryKind.GOOGLE_DRIVE,
        -> FileBackupRepositoryStorage(repositoryRoot(book, configuration.repositoryId.value))
        BackupRepositoryKind.USER_SELECTED_DIRECTORY -> {
            val handle = requireNotNull(
                SecureTransferHandleStore(applicationContext, keyProvider).read(book, configuration.repositoryHandleId),
            )
            SafBackupRepositoryStorage(applicationContext, Uri.parse(handle.substringBefore('\n')))
        }
    }

    private fun repositoryRoot(book: StableId, repository: StableId) = applicationContext.noBackupFilesDir.resolve("backup-repositories/$book/$repository")

    private fun latestSnapshotId(storage: app.ledger.transfer.data.BackupRepositoryStorage): BackupSnapshotId? = storage.names(app.ledger.transfer.data.BackupStorageArea.SNAPSHOTS)
        .filter { it.matches(Regex("[0-9a-f]{32}\\.manifest")) }
        .maxOrNull()
        ?.removeSuffix(".manifest")
        ?.chunked(2)
        ?.map { it.toInt(16).toByte() }
        ?.toByteArray()
        ?.let(StableId::fromBytes)
        ?.let { (it as? DomainResult.Success)?.value }
        ?.let(::BackupSnapshotId)

    private fun snapshotPickerItems(activeBook: StableId, configuration: BackupConfiguration): List<RestoreSnapshotUi> {
        val storage = repositoryStorage(activeBook, configuration)
        return createBackupCatalog(activeBook, SecurePrimaryLedgerAccess(applicationContext, keyProvider))
            .completeSnapshots(configuration.repositoryId)
            .filter { snapshot ->
                storage.exists(
                    app.ledger.transfer.data.BackupStorageArea.SNAPSHOTS,
                    "${snapshot.id.value.hex()}.manifest",
                )
            }
            .map { snapshot ->
                snapshot.toPickerUi(snapshot.id.value.toString(), configuration.repositoryKind, activeBook, configuration)
            }
    }

    private fun app.ledger.transfer.domain.BackupSnapshot.toPickerUi(
        displayId: String,
        repositoryKind: BackupRepositoryKind,
        activeBook: StableId,
        configuration: BackupConfiguration,
    ): RestoreSnapshotUi {
        val includesVault = runCatching {
            val storage = repositoryStorage(activeBook, configuration)
            BackupKeyEnvelopeStore(applicationContext, keyProvider)
                .openForAutomaticBackup(activeBook, configuration.repositoryId.value)
                .use { key -> BackupRepositoryInspector().readManifest(storage, configuration.repositoryId, id, key).includesVault }
        }.getOrNull() == true
        return RestoreSnapshotUi(
            id = displayId,
            createdAt = formatCreatedAt(createdAt),
            repositoryKind = repositoryKind,
            verified = manifestHash != null,
            includesVault = includesVault,
        )
    }

    private fun app.ledger.finance.application.RestoreIntegrityReport.toUiChecks(): List<RestoreIntegrityCheckUi> = listOf(
        RestoreIntegrityCheckUi(RestoreIntegrityCheck.SCHEMA, schemaVersionSupported),
        RestoreIntegrityCheckUi(RestoreIntegrityCheck.MIGRATIONS, migrationsApplied),
        RestoreIntegrityCheckUi(RestoreIntegrityCheck.SQLCIPHER, sqlCipherReadable),
        RestoreIntegrityCheckUi(RestoreIntegrityCheck.ENCRYPTION_AND_HASHES, aeadAndHashesValid),
        RestoreIntegrityCheckUi(RestoreIntegrityCheck.FOREIGN_KEYS, foreignKeysValid),
        RestoreIntegrityCheckUi(RestoreIntegrityCheck.JOURNAL_BALANCE, journalsBalanced),
        RestoreIntegrityCheckUi(RestoreIntegrityCheck.PROJECTIONS, projectionsValid),
        RestoreIntegrityCheckUi(RestoreIntegrityCheck.TRANSACTION_SUBTYPES, transactionSubtypesValid),
        RestoreIntegrityCheckUi(RestoreIntegrityCheck.ATTACHMENTS, attachmentsValid),
        RestoreIntegrityCheckUi(RestoreIntegrityCheck.BOOK_IDENTITY, bookIdentityValid),
        RestoreIntegrityCheckUi(RestoreIntegrityCheck.BASE_CURRENCY, baseCurrencyValid),
    )

    private fun cleanupTransient() {
        operationId?.let {
            restoreLedger.cleanup(it)
            mergeLedger.cleanup(it)
        }
        materialized?.targetDirectory?.let { directory ->
            if (directory.name.startsWith("restore-") && directory.parentFile?.name == RESTORE_WORK_DIRECTORY) {
                directory.walkBottomUp().forEach { file -> if (file.exists()) check(file.delete()) }
            }
        }
        materialized = null
        recoveredVaultDek?.close()
        recoveredVaultDek = null
        mergePreview = null
        resolutions.clear()
        cancelled.set(false)
    }

    private fun RestoreMaterializationResult.toFinancePackage(
        expectedBookId: StableId,
        expectedOperationId: StableId,
    ): MaterializedRestorePackage {
        require(bookId == expectedBookId)
        return MaterializedRestorePackage(
            expectedBookId,
            expectedOperationId,
            targetDirectory.resolve("database/ledger.db").absolutePath,
            targetDirectory.resolve("settings").listFiles()?.singleOrNull()?.absolutePath,
            targetDirectory.resolve("attachments").takeIf(File::isDirectory)?.absolutePath,
            targetDirectory.resolve("keys/portable-key-material.envelope").absolutePath,
            targetDirectory.resolve("keys/vault-recovery.envelope").takeIf(File::isFile)?.absolutePath,
            databaseSchemaVersion,
            logicalBytes,
        )
    }

    private companion object {
        const val RESTORE_WORK_DIRECTORY = "restore-work-v1"
        val SCREENS = setOf("RST-001", "RST-002", "RST-003", "RST-004", "RST-005", "RST-006", "RST-007", "CLR-002")
        val NON_CANCELABLE_STATES = setOf(
            app.ledger.transfer.domain.RestoreState.EXCHANGING,
            app.ledger.transfer.domain.RestoreState.VERIFYING_LIVE,
            app.ledger.transfer.domain.RestoreState.ROLLING_BACK,
        )
        val MARKER_FILE = Regex("ledger-restore-([0-9a-f]{32})\\.marker")
    }
}

private fun StableId.hex(): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun String.hexStableId(): StableId? = takeIf { length == StableId.BYTE_COUNT * 2 }
    ?.chunked(2)
    ?.map { runCatching { it.toInt(16).toByte() }.getOrNull() ?: return null }
    ?.toByteArray()
    ?.let(StableId::fromBytes)
    ?.let { (it as? DomainResult.Success)?.value }

private fun app.ledger.transfer.domain.BackupRepositoryId.driveFolderName(): String = value.bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".ledger-repository"
