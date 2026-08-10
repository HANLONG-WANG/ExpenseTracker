package app.ledger.app

import android.content.Context
import app.ledger.core.common.StableId
import app.ledger.core.security.BackupKeyEnvelopeStore
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.SecureTransferHandleStore
import app.ledger.core.security.VaultBackupEnvelopeStore
import app.ledger.transfer.data.AutomaticBackupCheckpointStore
import app.ledger.transfer.data.BackupConfiguration
import app.ledger.transfer.data.BackupConfigurationStore
import java.io.File

/** Removes only app-owned artifacts for the single local ledger; SAF exports and Drive are out of scope. */
internal class LocalBookArtifactCleaner(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
) {
    private val applicationContext = context.applicationContext

    fun clear(bookId: StableId, configuration: BackupConfiguration?): Boolean {
        val results = mutableListOf<Boolean>()
        configuration?.let { value ->
            results += runCatching {
                BackupKeyEnvelopeStore(applicationContext, keyProvider).delete(value.repositoryId.value)
                true
            }.getOrDefault(false)
            results += runCatching { SecureTransferHandleStore(applicationContext, keyProvider).destroy(value.repositoryHandleId) }.getOrDefault(false)
        }
        results += runCatching { BackupConfigurationStore(applicationContext, keyProvider).delete(bookId) }.getOrDefault(false)
        results += runCatching { AutomaticBackupCheckpointStore(applicationContext, keyProvider).delete(bookId) }.getOrDefault(false)
        results += runCatching {
            VaultBackupEnvelopeStore(applicationContext).delete(bookId)
            true
        }.getOrDefault(false)
        listOf(
            applicationContext.noBackupFilesDir.resolve("attachment_objects/$bookId"),
            applicationContext.noBackupFilesDir.resolve("backup-repositories/$bookId"),
            applicationContext.noBackupFilesDir.resolve("pre-restore-safety-v1/$bookId"),
            applicationContext.noBackupFilesDir.resolve("restore-work-v1"),
            applicationContext.noBackupFilesDir.resolve("backup-progress-v1"),
        ).forEach { results += deleteScoped(it, applicationContext.noBackupFilesDir) }
        applicationContext.filesDir.listFiles().orEmpty()
            .filter { RESTORE_ARTIFACT.matches(it.name) }
            .forEach { results += deleteScoped(it, applicationContext.filesDir) }
        applicationContext.databaseList()
            .filter(DERIVED_DATABASE::matches)
            .forEach { results += runCatching { applicationContext.deleteDatabase(it) }.getOrDefault(false) }
        return results.all { it }
    }

    private fun deleteScoped(target: File, root: File): Boolean = runCatching {
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = target.canonicalFile
        require(canonicalTarget != canonicalRoot && canonicalTarget.toPath().startsWith(canonicalRoot.toPath()))
        if (!canonicalTarget.exists()) return@runCatching true
        var deleted = true
        canonicalTarget.walkBottomUp().forEach { item ->
            if (item.exists() && !item.delete()) deleted = false
        }
        deleted
    }.getOrDefault(false)

    private companion object {
        val RESTORE_ARTIFACT = Regex("restore-artifacts-[0-9a-f]{32}\\.(?:descriptor|marker)|\\.restore-[0-9a-f]{32}\\..+")
        val DERIVED_DATABASE = Regex("(?:import_[0-9a-f]{32}|ledger_(?:shadow|safety)_[0-9a-f]{32})\\.db")
    }
}
