@file:Suppress("LongMethod", "MagicNumber", "ReturnCount", "TooGenericExceptionCaught", "TooManyFunctions")

package app.ledger.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.biometric.BiometricPrompt
import androidx.documentfile.provider.DocumentFile
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.Argon2idCalibrator
import app.ledger.core.security.Argon2idParameters
import app.ledger.core.security.BackupKeyEnvelopeStore
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.RecoveryPassword
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.core.security.SecureTransferHandleStore
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.core.security.VaultBackupEnvelopeStore
import app.ledger.core.security.VaultExposureRegistry
import app.ledger.core.security.VaultKeyHierarchy
import app.ledger.core.security.VaultRecoveryExportRequest
import app.ledger.feature.transfer.BackupExecutionPresentation
import app.ledger.feature.transfer.BackupFlowUiState
import app.ledger.feature.transfer.BackupHomePresentation
import app.ledger.feature.transfer.BackupIntegrityPresentation
import app.ledger.feature.transfer.BackupSnapshotUi
import app.ledger.feature.transfer.DriveAuthorizationPresentation
import app.ledger.transfer.data.BackupConfiguration
import app.ledger.transfer.data.BackupConfigurationStore
import app.ledger.transfer.data.BackupProgressStore
import app.ledger.transfer.data.BackupRepositoryInspector
import app.ledger.transfer.data.FileBackupRepositoryStorage
import app.ledger.transfer.data.SafBackupRepositoryStorage
import app.ledger.transfer.data.SqlCipherBackgroundOperationRepository
import app.ledger.transfer.domain.BackgroundOperation
import app.ledger.transfer.domain.BackgroundOperationId
import app.ledger.transfer.domain.BackgroundOperationState
import app.ledger.transfer.domain.BackgroundOperationType
import app.ledger.transfer.domain.BackupNetworkPolicy
import app.ledger.transfer.domain.BackupPolicy
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupRepositoryKind
import app.ledger.transfer.domain.BackupRetentionPolicy
import app.ledger.transfer.domain.OperationParameters
import app.ledger.transfer.domain.RecoveryPasswordChangeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class BackupController(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val runtime: AppRuntimeSources,
) {
    private val applicationContext = context.applicationContext
    private val mutableState = MutableStateFlow(BackupFlowUiState())
    val state: StateFlow<BackupFlowUiState> = mutableState.asStateFlow()
    private var bookId: StableId? = null
    private var configuration: BackupConfiguration? = null
    private var candidateRepositoryId: BackupRepositoryId? = null
    private var candidateHandleId: StableId? = null
    private var operationId: BackgroundOperationId? = null
    private var lastPortableTree: Uri? = null
    private var pendingVaultRequest: VaultRecoveryExportRequest? = null
    private var pendingVaultPassword: RecoveryPassword? = null
    private var pendingVaultParameters: Argon2idParameters? = null

    suspend fun begin(activeBookId: StableId) {
        bookId = activeBookId
        configuration = BackupConfigurationStore(applicationContext, keyProvider).read(activeBookId)
        val config = configuration
        candidateRepositoryId = config?.repositoryId ?: BackupRepositoryId(runtime.stableIds.nextStableId())
        candidateHandleId = config?.repositoryHandleId ?: runtime.stableIds.nextStableId()
        val recoveryConfigured = BackupKeyEnvelopeStore(applicationContext, keyProvider)
            .isConfigured(requireNotNull(candidateRepositoryId).value)
        val snapshots = loadSnapshots(activeBookId, config)
        mutableState.value = mutableState.value.copy(
            screenId = "BKP-001",
            homePresentation = when {
                config == null || !recoveryConfigured -> BackupHomePresentation.NOT_CONFIGURED
                else -> BackupHomePresentation.CONFIGURED
            },
            repositoryKind = config?.repositoryKind ?: BackupRepositoryKind.APP_PRIVATE,
            repositoryLabel = config?.let { repositoryCustomLabel(activeBookId, it) }.orEmpty(),
            directoryPermissionGranted = config?.let { hasRepositoryPermission(activeBookId, it) } ?: false,
            recoveryPasswordConfigured = recoveryConfigured,
            vaultBackupReady = vaultCanBeBackedUp(activeBookId),
            automaticBackup = config?.policy?.automaticLocalBackup ?: true,
            retentionCount = (config?.policy?.retention?.maximumSnapshots ?: 30).toString(),
            retentionDays = config?.policy?.retention?.maximumAgeDays?.toString().orEmpty(),
            includeVault = config?.policy?.includeVault ?: false,
            networkPolicy = config?.policy?.networkPolicy ?: BackupNetworkPolicy.ANY,
            snapshots = snapshots,
        )
    }

    fun setScreen(screenId: String) {
        if (screenId in SCREENS) mutableState.value = mutableState.value.copy(screenId = screenId)
    }

    fun selectRepositoryKind(kind: BackupRepositoryKind) {
        mutableState.value = mutableState.value.copy(
            repositoryKind = kind,
            repositoryLabel = "",
            directoryPermissionGranted = kind == BackupRepositoryKind.APP_PRIVATE,
        )
        if (kind == BackupRepositoryKind.APP_PRIVATE) persistConfiguration()
    }

    fun selectDirectory(uri: Uri): Boolean {
        val activeBook = bookId ?: return false
        return try {
            applicationContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val tree = DocumentFile.fromTreeUri(applicationContext, uri)?.takeIf(DocumentFile::exists) ?: return false
            val handle = requireNotNull(candidateHandleId)
            SecureTransferHandleStore(applicationContext, keyProvider).save(activeBook, handle, tree.uri.toString())
            mutableState.value = mutableState.value.copy(directoryPermissionGranted = true, repositoryLabel = uri.authority.orEmpty())
            persistConfiguration()
            true
        } catch (_: SecurityException) {
            mutableState.value = mutableState.value.copy(
                directoryPermissionGranted = false,
                homePresentation = BackupHomePresentation.PERMISSION_REVOKED,
            )
            false
        }
    }

    suspend fun authorizeDrive(): PendingIntent? {
        mutableState.value = mutableState.value.copy(driveAuthorization = DriveAuthorizationPresentation.AUTHORIZING)
        return when (val result = GoogleDriveAuthorizationGateway(applicationContext).authorize()) {
            is DomainResult.Success -> when (val authorization = result.value) {
                is GoogleDriveAuthorization.Authorized -> {
                    mutableState.value = mutableState.value.copy(driveAuthorization = DriveAuthorizationPresentation.CONNECTED)
                    persistConfiguration()
                    null
                }
                is GoogleDriveAuthorization.ResolutionRequired -> authorization.pendingIntent
            }
            is DomainResult.Failure -> {
                mutableState.value = mutableState.value.copy(driveAuthorization = DriveAuthorizationPresentation.FAILED)
                null
            }
        }
    }

    fun completeDriveAuthorization(intent: Intent?) {
        val result = intent?.let(GoogleDriveAuthorizationGateway(applicationContext)::resultFromIntent)
        val connected = (result as? DomainResult.Success)?.value is GoogleDriveAuthorization.Authorized
        mutableState.value = mutableState.value.copy(
            driveAuthorization = if (connected) DriveAuthorizationPresentation.CONNECTED else DriveAuthorizationPresentation.FAILED,
        )
        if (connected) persistConfiguration()
    }

    suspend fun disconnectDrive() {
        val success = GoogleDriveAuthorizationGateway(applicationContext).disconnect() is DomainResult.Success
        mutableState.value = mutableState.value.copy(
            driveAuthorization = if (success) DriveAuthorizationPresentation.DISCONNECTED else DriveAuthorizationPresentation.FAILED,
        )
    }

    fun changeRecoveryPassword(value: String) {
        mutableState.value = mutableState.value.copy(recoveryPassword = value.take(MAX_PASSWORD_CHARACTERS), recoveryPasswordError = false)
    }

    fun changeRecoveryConfirmation(value: String) {
        mutableState.value = mutableState.value.copy(recoveryPasswordConfirmation = value.take(MAX_PASSWORD_CHARACTERS), recoveryPasswordError = false)
    }

    fun changePasswordMode(mode: RecoveryPasswordChangeMode) {
        mutableState.value = mutableState.value.copy(recoveryPasswordChangeMode = mode)
    }

    suspend fun saveRecoveryPassword(): BackupRecoverySaveResult {
        val activeBook = bookId ?: return BackupRecoverySaveResult(false, null)
        val repositoryId = candidateRepositoryId ?: return BackupRecoverySaveResult(false, null)
        val state = mutableState.value
        operationId = null
        val chars = state.recoveryPassword.toCharArray()
        val valid = chars.size >= RecoveryPassword.MINIMUM_CHARACTERS && chars.any(Char::isLetter) && chars.any(Char::isDigit) &&
            state.recoveryPassword == state.recoveryPasswordConfirmation
        if (!valid) {
            chars.fill('\u0000')
            mutableState.value = state.copy(recoveryPasswordError = true)
            return BackupRecoverySaveResult(false, null)
        }
        return try {
            val store = BackupKeyEnvelopeStore(applicationContext, keyProvider)
            val wasConfigured = store.isConfigured(repositoryId.value)
            val parameters = Argon2idCalibrator().calibrate()
            val vaultPrompt = prepareVaultBackupEnrollment(activeBook, chars, parameters)
            RecoveryPassword.copyOf(chars).use { password ->
                if (wasConfigured) {
                    store.rewrap(activeBook, repositoryId.value, password, parameters)
                } else {
                    store.configure(activeBook, repositoryId.value, password, parameters)
                }
            }
            if (vaultPrompt != null) {
                mutableState.value = mutableState.value.copy(includeVault = false, vaultBackupReady = false)
            }
            persistConfiguration()
            val hasAccessibleHistory = wasConfigured && runCatching {
                createBackupCatalog(activeBook, SecurePrimaryLedgerAccess(applicationContext, keyProvider))
                    .completeSnapshots(repositoryId).isNotEmpty()
            }.getOrDefault(false)
            if (hasAccessibleHistory && state.recoveryPasswordChangeMode == RecoveryPasswordChangeMode.RE_ENCRYPT_ACCESSIBLE_HISTORY) {
                val id = BackgroundOperationId(runtime.stableIds.nextStableId())
                operationId = id
                operations(activeBook).save(
                    BackgroundOperation.queued(
                        id,
                        BackgroundOperationType.BACKUP_KEY_ROTATION,
                        runtime.clock.now(),
                        OperationParameters.BackupRecoveryReencryption(repositoryId),
                    ),
                ).requireSuccess()
                val config = requireNotNull(configuration)
                BackupWorkScheduler.enqueue(
                    applicationContext,
                    id.value,
                    drive = config.repositoryKind == BackupRepositoryKind.GOOGLE_DRIVE,
                    userInitiated = true,
                    unmetered = config.policy.networkPolicy == BackupNetworkPolicy.UNMETERED,
                )
            }
            mutableState.value = mutableState.value.copy(
                recoveryPasswordConfigured = true,
                vaultBackupReady = vaultPrompt == null,
                recoveryPassword = "",
                recoveryPasswordConfirmation = "",
                recoveryPasswordError = false,
                homePresentation = BackupHomePresentation.CONFIGURED,
                execution = if (operationId == null) BackupExecutionPresentation.READY else BackupExecutionPresentation.RUNNING,
            )
            BackupRecoverySaveResult(true, vaultPrompt)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(recoveryPasswordError = true)
            clearPendingVaultEnrollment()
            BackupRecoverySaveResult(false, null)
        } finally {
            chars.fill('\u0000')
        }
    }

    fun setAutomaticBackup(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(automaticBackup = enabled)
    }
    fun changeRetentionCount(value: String) {
        mutableState.value = mutableState.value.copy(retentionCount = value.filter(Char::isDigit).take(4))
    }
    fun changeRetentionDays(value: String) {
        mutableState.value = mutableState.value.copy(retentionDays = value.filter(Char::isDigit).take(5))
    }
    fun setIncludeVault(enabled: Boolean) {
        if (!enabled || mutableState.value.recoveryPasswordConfigured && mutableState.value.vaultBackupReady) {
            mutableState.value = mutableState.value.copy(includeVault = enabled)
        }
    }

    fun completeVaultBackupEnrollment(authenticatedCryptoObject: BiometricPrompt.CryptoObject): Boolean {
        val activeBook = bookId ?: return false
        val request = pendingVaultRequest ?: return false
        val password = pendingVaultPassword ?: return false
        val parameters = pendingVaultParameters ?: return false
        return try {
            request.complete(authenticatedCryptoObject).use { vaultDek ->
                VaultBackupEnvelopeStore(applicationContext).configure(activeBook, vaultDek, password, parameters)
            }
            mutableState.value = mutableState.value.copy(vaultBackupReady = true)
            true
        } catch (_: Exception) {
            false
        } finally {
            pendingVaultRequest = null
            pendingVaultPassword?.close()
            pendingVaultPassword = null
            pendingVaultParameters = null
        }
    }

    fun cancelVaultBackupEnrollment() = clearPendingVaultEnrollment()
    fun setNetworkPolicy(policy: BackupNetworkPolicy) {
        mutableState.value = mutableState.value.copy(networkPolicy = policy)
    }
    fun setPortable(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(portable = enabled)
    }
    fun changePortableName(value: String) {
        mutableState.value = mutableState.value.copy(portableFileName = value.filterNot { it == '/' || it == '\\' || it.code < 0x20 }.take(180))
    }

    fun saveSettings(): Boolean = persistConfiguration()

    fun selectSnapshot(snapshotId: String): Boolean {
        val snapshot = mutableState.value.snapshots.firstOrNull { it.snapshotId == snapshotId } ?: return false
        mutableState.value = mutableState.value.copy(selectedSnapshot = snapshot, screenId = "BKP-006")
        return true
    }

    suspend fun startBackup(portableTree: Uri?): Boolean {
        val activeBook = bookId ?: return false
        val config = configuration ?: return false
        if (!mutableState.value.recoveryPasswordConfigured) return false
        val destinationHandle = if (mutableState.value.portable) {
            val tree = portableTree ?: lastPortableTree ?: return false
            applicationContext.contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            lastPortableTree = tree
            val handle = runtime.stableIds.nextStableId()
            SecureTransferHandleStore(applicationContext, keyProvider).save(activeBook, handle, "${tree}\n${portableFileName()}")
            handle
        } else {
            null
        }
        val id = BackgroundOperationId(runtime.stableIds.nextStableId())
        operationId = id
        operations(activeBook).save(
            BackgroundOperation.queued(
                id,
                BackgroundOperationType.FULL_BACKUP,
                runtime.clock.now(),
                OperationParameters.FullBackup(config.repositoryId, mutableState.value.portable, destinationHandle),
            ),
        ).requireSuccess()
        mutableState.value = mutableState.value.copy(
            screenId = "BKP-007",
            execution = BackupExecutionPresentation.RUNNING,
            homePresentation = BackupHomePresentation.RUNNING,
            failureCode = null,
            temporaryCleanupComplete = false,
        )
        BackupWorkScheduler.enqueue(
            applicationContext,
            id.value,
            drive = config.repositoryKind == BackupRepositoryKind.GOOGLE_DRIVE,
            userInitiated = true,
            unmetered = config.policy.networkPolicy == BackupNetworkPolicy.UNMETERED,
        )
        return true
    }

    fun cancel() {
        operationId?.value?.let(BackupRunControlRegistry::cancel)
        mutableState.value = mutableState.value.copy(execution = BackupExecutionPresentation.CANCEL_REQUESTED)
    }

    suspend fun retry(): Boolean = startBackup(if (mutableState.value.portable) lastPortableTree else null)

    suspend fun awaitCurrent() {
        val activeBook = bookId ?: return
        val id = operationId ?: return
        val repository = operations(activeBook)
        val progressStore = BackupProgressStore(applicationContext, keyProvider)
        while (true) {
            progressStore.read(activeBook, id.value)?.let { progress ->
                mutableState.value = mutableState.value.copy(
                    phase = progress.phase,
                    completedBytes = progress.completedBytes,
                    totalBytes = progress.totalBytes,
                )
            }
            val operation = (repository.get(id) as? DomainResult.Success)?.value
            if (operation == null) {
                failUi("BACKUP_OPERATION_MISSING")
                return
            }
            when (operation.state) {
                BackgroundOperationState.SUCCEEDED -> {
                    val resultScreen = if (operation.parameters is OperationParameters.BackupRecoveryReencryption) "BKP-003" else "BKP-007"
                    mutableState.value = mutableState.value.copy(
                        execution = BackupExecutionPresentation.SUCCEEDED,
                        homePresentation = BackupHomePresentation.CONFIGURED,
                        temporaryCleanupComplete = true,
                    )
                    begin(activeBook)
                    mutableState.value = mutableState.value.copy(screenId = resultScreen, execution = BackupExecutionPresentation.SUCCEEDED)
                    return
                }
                BackgroundOperationState.FAILED_FINAL, BackgroundOperationState.FAILED_RETRYABLE -> {
                    failUi(operation.errorCode.orEmpty())
                    return
                }
                BackgroundOperationState.CANCEL_REQUESTED, BackgroundOperationState.ROLLING_BACK -> {
                    mutableState.value = mutableState.value.copy(execution = BackupExecutionPresentation.CANCEL_REQUESTED)
                }
                else -> Unit
            }
            delay(POLL_MILLIS)
        }
    }

    private fun failUi(code: String) {
        mutableState.value = mutableState.value.copy(
            execution = BackupExecutionPresentation.FAILED,
            homePresentation = if (code == "BACKUP_PERMISSION_REVOKED") BackupHomePresentation.PERMISSION_REVOKED else BackupHomePresentation.FAILED,
            failureCode = code,
            temporaryCleanupComplete = true,
        )
    }

    private fun prepareVaultBackupEnrollment(
        activeBook: StableId,
        passwordChars: CharArray,
        parameters: Argon2idParameters,
    ): BiometricPrompt.CryptoObject? {
        val hierarchy = VaultKeyHierarchy(
            AndroidKeystoreKeys(applicationContext),
            SecurityEnvelopeStore(applicationContext),
            VaultExposureRegistry(SystemClock::elapsedRealtime),
        )
        if (!hierarchy.isProvisioned(activeBook)) return null
        clearPendingVaultEnrollment()
        val request = hierarchy.beginRecoveryExport(activeBook)
        pendingVaultRequest = request
        pendingVaultPassword = RecoveryPassword.copyOf(passwordChars)
        pendingVaultParameters = parameters
        return request.cryptoObject
    }

    private fun vaultCanBeBackedUp(activeBook: StableId): Boolean {
        if (VaultBackupEnvelopeStore(applicationContext).isConfigured(activeBook)) return true
        val hierarchy = VaultKeyHierarchy(
            AndroidKeystoreKeys(applicationContext),
            SecurityEnvelopeStore(applicationContext),
            VaultExposureRegistry(SystemClock::elapsedRealtime),
        )
        return !hierarchy.isProvisioned(activeBook)
    }

    private fun clearPendingVaultEnrollment() {
        pendingVaultRequest?.close()
        pendingVaultRequest = null
        pendingVaultPassword?.close()
        pendingVaultPassword = null
        pendingVaultParameters = null
    }

    private fun persistConfiguration(): Boolean {
        val activeBook = bookId ?: return false
        val repositoryId = candidateRepositoryId ?: return false
        val handle = candidateHandleId ?: return false
        val retention = mutableState.value.retentionCount.toIntOrNull()?.takeIf { it in 1..3650 } ?: return false
        val days = mutableState.value.retentionDays.takeIf(String::isNotBlank)?.toIntOrNull()?.takeIf { it in 1..36500 }
            ?: if (mutableState.value.retentionDays.isBlank()) null else return false
        val config = BackupConfiguration(
            repositoryId,
            mutableState.value.repositoryKind,
            handle,
            BackupPolicy(
                mutableState.value.automaticBackup,
                BackupRetentionPolicy(retention, days),
                mutableState.value.includeVault,
                mutableState.value.networkPolicy,
            ),
        )
        BackupConfigurationStore(applicationContext, keyProvider).save(activeBook, config)
        configuration = config
        return true
    }

    private fun loadSnapshots(activeBook: StableId, config: BackupConfiguration?): List<BackupSnapshotUi> {
        if (config == null) return emptyList()
        val access = SecurePrimaryLedgerAccess(applicationContext, keyProvider)
        val catalog = createBackupCatalog(activeBook, access)
        val snapshots = runCatching { catalog.completeSnapshots(config.repositoryId) }.getOrDefault(emptyList())
        val storage = runCatching { repositoryStorage(activeBook, config) }.getOrNull()
        val keyStore = BackupKeyEnvelopeStore(applicationContext, keyProvider)
        val key = runCatching { keyStore.openForAutomaticBackup(activeBook, config.repositoryId.value) }.getOrNull()
        return try {
            snapshots.map { snapshot ->
                val manifest = if (storage != null && key != null) {
                    runCatching { BackupRepositoryInspector().readManifest(storage, config.repositoryId, snapshot.id, key) }.getOrNull()
                } else {
                    null
                }
                BackupSnapshotUi(
                    snapshot.id.value.toString(),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(snapshot.createdAt.atZone(ZoneId.systemDefault()).toLocalDateTime()),
                    if (manifest == null) "revision ${snapshot.localRevision.value}" else "${formatBytes(manifest.logicalBytes)} · ${manifest.objects.size} objects",
                    manifest?.physicalIncrementBytes?.let(::formatBytes) ?: "unavailable",
                    config.repositoryKind,
                    repositoryCustomLabel(activeBook, config),
                    if (manifest != null) BackupIntegrityPresentation.VERIFIED else BackupIntegrityPresentation.UNVERIFIED,
                    manifest?.includesVault == true,
                )
            }
        } finally {
            key?.close()
        }
    }

    private fun hasRepositoryPermission(activeBook: StableId, config: BackupConfiguration): Boolean = when (config.repositoryKind) {
        BackupRepositoryKind.APP_PRIVATE -> true
        BackupRepositoryKind.GOOGLE_DRIVE -> false
        BackupRepositoryKind.USER_SELECTED_DIRECTORY -> SecureTransferHandleStore(applicationContext, keyProvider)
            .read(activeBook, config.repositoryHandleId)?.substringBefore('\n')?.let(Uri::parse)
            ?.let { uri -> applicationContext.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission } } == true
    }

    private fun repositoryStorage(activeBook: StableId, config: BackupConfiguration) = when (config.repositoryKind) {
        BackupRepositoryKind.APP_PRIVATE, BackupRepositoryKind.GOOGLE_DRIVE -> FileBackupRepositoryStorage(repositoryRoot(activeBook, config.repositoryId.value))
        BackupRepositoryKind.USER_SELECTED_DIRECTORY -> {
            val handle = requireNotNull(SecureTransferHandleStore(applicationContext, keyProvider).read(activeBook, config.repositoryHandleId))
            SafBackupRepositoryStorage(applicationContext, Uri.parse(handle.substringBefore('\n')))
        }
    }

    private fun repositoryRoot(activeBook: StableId, repositoryId: StableId): File = File(applicationContext.noBackupFilesDir, "backup-repositories/$activeBook/$repositoryId")

    private fun portableFileName(): String {
        val base = mutableState.value.portableFileName.trim().ifBlank { "ledger" }
        return if (base.endsWith(".ledger-backup", ignoreCase = true)) base else "$base.ledger-backup"
    }

    private fun operations(activeBook: StableId) = SqlCipherBackgroundOperationRepository(
        activeBook,
        SecurePrimaryLedgerAccess(applicationContext, keyProvider),
    )

    private fun repositoryCustomLabel(activeBook: StableId, config: BackupConfiguration): String = if (config.repositoryKind == BackupRepositoryKind.USER_SELECTED_DIRECTORY) {
        SecureTransferHandleStore(applicationContext, keyProvider).read(activeBook, config.repositoryHandleId)
            ?.substringBefore('\n')?.let(Uri::parse)?.authority.orEmpty()
    } else {
        ""
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.2f MiB".format(bytes.toDouble() / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.2f KiB".format(bytes.toDouble() / 1024.0)
        else -> "$bytes B"
    }

    private fun <T> DomainResult<T>.requireSuccess(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }

    private companion object {
        const val MAX_PASSWORD_CHARACTERS = 512
        const val POLL_MILLIS = 200L
        val SCREENS = setOf("BKP-001", "BKP-002", "BKP-003", "BKP-004", "BKP-005", "BKP-006", "BKP-007", "SYS-003")
    }
}

internal data class BackupRecoverySaveResult(
    val succeeded: Boolean,
    val vaultCryptoObject: BiometricPrompt.CryptoObject?,
)
