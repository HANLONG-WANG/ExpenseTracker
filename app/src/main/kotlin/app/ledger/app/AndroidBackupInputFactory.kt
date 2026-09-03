@file:Suppress("LongMethod", "LongParameterList", "TooGenericExceptionCaught")

package app.ledger.app

import android.content.Context
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.LedgerDatabaseOperationAccess
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.core.security.VaultBackupEnvelopeStore
import app.ledger.finance.data.SecureShadowLedgerAccess
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.LocalRevision
import app.ledger.transfer.data.ManagedBackupInput
import app.ledger.transfer.data.ReopenableBackupSource
import app.ledger.transfer.domain.BackupObjectKind
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupRepositoryKind
import app.ledger.transfer.domain.BackupSnapshotId
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Instant

/** Creates a consistent SQLCipher snapshot and only exposes reopenable, bounded-memory streams. */
internal class AndroidBackupInputFactory(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    databaseAccess: LedgerDatabaseOperationAccess,
) {
    private val applicationContext = context.applicationContext
    private val shadowAccess = SecureShadowLedgerAccess(applicationContext, keyProvider, databaseAccess)

    suspend fun prepare(
        bookId: StableId,
        operationId: StableId,
        repositoryId: BackupRepositoryId,
        repositoryKind: BackupRepositoryKind,
        repositoryHandleId: StableId,
        snapshotId: BackupSnapshotId,
        createdAt: Instant,
        applicationVersion: String,
        includeVault: Boolean,
    ): PreparedAndroidBackupInput {
        val shadow = shadowAccess.createSnapshot(bookId, operationId)
        try {
            val validation = shadowAccess.validate(bookId, operationId)
            require(validation.isValid) { "consistent SQLCipher backup snapshot failed validation" }
            val databaseFile = applicationContext.getDatabasePath(shadow.shadowDatabaseName)
            val localRevision = shadowAccess.readShadow(bookId, operationId) { database ->
                database.query("SELECT local_revision FROM book WHERE id=1").use { cursor ->
                    check(cursor.moveToFirst())
                    LocalRevision.of(cursor.getLong(0)).required()
                }
            }
            val portableKeyBytes = keyProvider.open(bookId).use { keys ->
                keys.portableKeyMaterial().useBytes(ByteArray::copyOf)
            }
            val vaultBytes = if (includeVault) {
                val recoveryEnvelope = VaultBackupEnvelopeStore(applicationContext).readForAutomaticBackup(bookId)
                if (SecurityEnvelopeStore(applicationContext).readVaultDek(bookId) != null) {
                    requireNotNull(recoveryEnvelope) { "vault recovery envelope is unavailable" }
                } else {
                    recoveryEnvelope
                }
            } else {
                null
            }
            val settingsFile = applicationContext.filesDir.resolve(SETTINGS_FILE_NAME)
            require(settingsFile.isFile) { "encrypted application settings are unavailable" }
            val attachmentDirectory = File(
                applicationContext.noBackupFilesDir,
                "attachment_objects/${bookId.toUuid()}/objects",
            )
            val attachments = attachmentDirectory.listFiles().orEmpty()
                .filter { it.isFile && ATTACHMENT_FILE.matches(it.name) }
                .sortedBy(File::getName)
                .map { it.asBackupSource("attachments/${it.name}", BackupObjectKind.ATTACHMENT) }
            val input = ManagedBackupInput(
                bookId,
                repositoryId,
                repositoryKind,
                repositoryHandleId,
                snapshotId,
                BookCommitId(StableId.fromBytes(shadow.expectedLiveHead).required()),
                localRevision,
                createdAt,
                applicationVersion,
                shadowAccess.currentDatabaseSchemaVersion,
                databaseFile.asBackupSource("database/ledger.db", BackupObjectKind.DATABASE_CHUNK),
                listOf(settingsFile.asBackupSource("settings/ledger_app_settings.pb", BackupObjectKind.SETTINGS)),
                attachments,
                portableKeyBytes.asBackupSource("keys/portable-key-material.envelope", BackupObjectKind.KEY_ENVELOPE),
                vaultBytes?.asBackupSource("keys/vault-recovery.envelope", BackupObjectKind.VAULT_ENVELOPE),
            )
            return PreparedAndroidBackupInput(input, operationId, shadowAccess, listOfNotNull(portableKeyBytes, vaultBytes))
        } catch (error: Exception) {
            shadowAccess.discard(operationId)
            throw error
        }
    }

    private fun File.asBackupSource(logicalName: String, kind: BackupObjectKind): ReopenableBackupSource {
        val expectedSize = length()
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(this).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
            buffer.fill(0)
        }
        val expectedHash = Hash256.fromBytes(digest.digest()).required()
        return ReopenableBackupSource(logicalName, kind, expectedSize, expectedHash) {
            check(length() == expectedSize) { "backup source changed" }
            FileInputStream(this)
        }
    }

    private fun ByteArray.asBackupSource(logicalName: String, kind: BackupObjectKind): ReopenableBackupSource {
        val value = this
        val hash = Hash256.fromBytes(MessageDigest.getInstance("SHA-256").digest(value)).required()
        return ReopenableBackupSource(logicalName, kind, value.size.toLong(), hash) { ByteArrayInputStream(value) }
    }

    private companion object {
        const val SETTINGS_FILE_NAME = "ledger_app_settings.pb"
        const val COPY_BUFFER_BYTES = 64 * 1024
        val ATTACHMENT_FILE = Regex("[0-9a-f]{32}\\.(?:blob|thumb)")
    }
}

internal class PreparedAndroidBackupInput(
    val input: ManagedBackupInput,
    private val operationId: StableId,
    private val shadowAccess: SecureShadowLedgerAccess,
    private val secrets: List<ByteArray>,
) : AutoCloseable {
    override fun close() {
        secrets.forEach { it.fill(0) }
        shadowAccess.discard(operationId)
    }
}

private fun <T> DomainResult<T>.required(): T = (this as? DomainResult.Success)?.value ?: error("invalid backup source identity")
