package app.ledger.app

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.BackupKeyEnvelopeStore
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.finance.domain.LocalRevision
import app.ledger.transfer.data.AutomaticBackupCheckpoint
import app.ledger.transfer.data.AutomaticBackupCheckpointStore
import app.ledger.transfer.data.BackupConfigurationStore
import app.ledger.transfer.data.SqlCipherBackgroundOperationRepository
import app.ledger.transfer.domain.AutomaticBackupPolicy
import app.ledger.transfer.domain.BackgroundOperation
import app.ledger.transfer.domain.BackgroundOperationId
import app.ledger.transfer.domain.BackgroundOperationState
import app.ledger.transfer.domain.BackgroundOperationType
import app.ledger.transfer.domain.BackupNetworkPolicy
import app.ledger.transfer.domain.BackupRepositoryKind
import app.ledger.transfer.domain.OperationParameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/** Creates/resumes the one durable backup operation due after a day's first changed revision. */
internal class AutomaticBackupScheduler(
    private val application: LedgerApplication,
    private val settings: AppSettingsRepository,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val runtime: AppRuntimeSources,
) {
    private val mutex = Mutex()

    suspend fun scheduleIfDue() = mutex.withLock {
        runCatching {
            val saved = settings.current()
            val bookId = StableId.fromBytes(saved.bookId.toByteArray()).successOrNull() ?: return@runCatching
            val ledgerZone = runCatching { ZoneId.of(saved.zoneId.ifBlank { "UTC" }) }.getOrDefault(ZoneId.of("UTC"))
            val configuration = BackupConfigurationStore(application, keyProvider).read(bookId) ?: return@runCatching
            if (!BackupKeyEnvelopeStore(application, keyProvider).isConfigured(configuration.repositoryId.value)) return@runCatching
            val access = SecurePrimaryLedgerAccess(application, keyProvider)
            val operations = SqlCipherBackgroundOperationRepository(bookId, access)
            operations.recoverableBackupOperations()
                .filter { operation -> operation.repositoryIdOrNull() == configuration.repositoryId }
                .forEach { operation -> enqueue(operation.id.value, configuration.repositoryKind, configuration.policy.networkPolicy) }
            if (!configuration.policy.automaticLocalBackup) return@runCatching
            val checkpointStore = AutomaticBackupCheckpointStore(application, keyProvider)
            val today = runtime.clock.now().atZone(ledgerZone).toLocalDate()
            val marker = checkpointStore.read(bookId)
            if (marker?.date == today) {
                val existing = (operations.get(BackgroundOperationId(marker.operationId)) as? DomainResult.Success)?.value
                if (existing != null && existing.state !in TERMINAL_STATES) {
                    enqueue(existing.id.value, configuration.repositoryKind, configuration.policy.networkPolicy)
                }
                return@runCatching
            }
            val currentRevision = access.read(bookId) { database ->
                database.query("SELECT local_revision FROM book WHERE id=1").use { cursor ->
                    check(cursor.moveToFirst())
                    LocalRevision.of(cursor.getLong(0)).required()
                }
            }
            val latest = createBackupCatalog(bookId, access).completeSnapshots(configuration.repositoryId).firstOrNull()
            val due = AutomaticBackupPolicy.shouldCreateDailyBackup(
                today,
                currentRevision,
                latest?.createdAt?.atZone(ledgerZone)?.toLocalDate(),
                latest?.localRevision,
            )
            if (!due) return@runCatching
            val operationId = BackgroundOperationId(runtime.stableIds.nextStableId())
            operations.save(
                BackgroundOperation.queued(
                    operationId,
                    BackgroundOperationType.FULL_BACKUP,
                    runtime.clock.now(),
                    OperationParameters.FullBackup(configuration.repositoryId, portable = false),
                ),
            ).required()
            checkpointStore.save(bookId, AutomaticBackupCheckpoint(today, currentRevision, operationId.value))
            enqueue(operationId.value, configuration.repositoryKind, configuration.policy.networkPolicy)
        }
    }

    private fun enqueue(operationId: StableId, kind: BackupRepositoryKind, networkPolicy: BackupNetworkPolicy) {
        BackupWorkScheduler.enqueue(
            application,
            operationId,
            drive = kind == BackupRepositoryKind.GOOGLE_DRIVE,
            userInitiated = false,
            unmetered = networkPolicy == BackupNetworkPolicy.UNMETERED,
        )
    }

    private companion object {
        val TERMINAL_STATES = setOf(
            BackgroundOperationState.SUCCEEDED,
            BackgroundOperationState.FAILED_FINAL,
        )
    }
}

private fun <T> DomainResult<T>.required(): T = (this as? DomainResult.Success)?.value ?: error("automatic backup persistence failed")
private fun <T> DomainResult<T>.successOrNull(): T? = (this as? DomainResult.Success)?.value
private fun BackgroundOperation.repositoryIdOrNull() = when (val value = parameters) {
    is OperationParameters.FullBackup -> value.repositoryId
    is OperationParameters.BackupRecoveryReencryption -> value.repositoryId
    else -> null
}
