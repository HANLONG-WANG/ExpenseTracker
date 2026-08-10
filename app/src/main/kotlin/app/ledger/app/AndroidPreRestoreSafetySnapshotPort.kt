@file:Suppress("MagicNumber", "NestedBlockDepth", "TooGenericExceptionCaught")

package app.ledger.app

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.BackupKeyEnvelopeStore
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.core.security.SecureTransferHandleStore
import app.ledger.transfer.data.BackupConfigurationStore
import app.ledger.transfer.data.FileBackupRepositoryStorage
import app.ledger.transfer.data.ManagedBackupRepositoryEngine
import app.ledger.transfer.data.PreRestoreSafetySnapshotPort
import app.ledger.transfer.data.SafBackupRepositoryStorage
import app.ledger.transfer.domain.BackupRepositoryKind
import app.ledger.transfer.domain.BackupSnapshotId
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/** Publishes a normal, verified managed snapshot before any restore exchange can begin. */
internal class AndroidPreRestoreSafetySnapshotPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val runtime: AppRuntimeSources,
) : PreRestoreSafetySnapshotPort {
    private val applicationContext = context.applicationContext

    override suspend fun create(bookId: StableId, operationId: StableId): DomainResult<StableId> = try {
        val configuration = requireNotNull(BackupConfigurationStore(applicationContext, keyProvider).read(bookId))
        val snapshotId = BackupSnapshotId(runtime.stableIds.nextStableId())
        val input = AndroidBackupInputFactory(applicationContext, keyProvider).prepare(
            bookId = bookId,
            operationId = operationId,
            repositoryId = configuration.repositoryId,
            repositoryKind = configuration.repositoryKind,
            repositoryHandleId = configuration.repositoryHandleId,
            snapshotId = snapshotId,
            createdAt = runtime.clock.now(),
            applicationVersion = applicationContext.packageManager
                .getPackageInfo(applicationContext.packageName, 0).versionName ?: "unknown",
            includeVault = configuration.policy.includeVault,
        )
        input.use { prepared ->
            val storage = when (configuration.repositoryKind) {
                BackupRepositoryKind.APP_PRIVATE,
                BackupRepositoryKind.GOOGLE_DRIVE,
                -> FileBackupRepositoryStorage(repositoryRoot(bookId, configuration.repositoryId.value))
                BackupRepositoryKind.USER_SELECTED_DIRECTORY -> {
                    val handle = requireNotNull(
                        SecureTransferHandleStore(applicationContext, keyProvider).read(bookId, configuration.repositoryHandleId),
                    )
                    SafBackupRepositoryStorage(applicationContext, Uri.parse(handle.substringBefore('\n')))
                }
            }
            val access = SecurePrimaryLedgerAccess(applicationContext, keyProvider)
            val keyStore = BackupKeyEnvelopeStore(applicationContext, keyProvider)
            keyStore.openForAutomaticBackup(bookId, configuration.repositoryId.value).use { key ->
                when (
                    val created = ManagedBackupRepositoryEngine(runtime.stableIds).create(
                        prepared.input,
                        storage,
                        createBackupCatalog(bookId, access),
                        key,
                        keyStore.recoveryEnvelope(configuration.repositoryId.value),
                        configuration.policy.retention,
                    )
                ) {
                    is DomainResult.Failure -> created
                    is DomainResult.Success -> DomainResult.Success(snapshotId.value)
                }
            }
        }
    } catch (_: Exception) {
        createQuarantineSnapshot(bookId)
    }

    private fun createQuarantineSnapshot(bookId: StableId): DomainResult<StableId> = try {
        val snapshotId = runtime.stableIds.nextStableId()
        val root = File(applicationContext.noBackupFilesDir, "pre-restore-safety-v1/$bookId/$snapshotId")
        require(root.mkdirs())
        val database = SecurePrimaryLedgerAccess(applicationContext, keyProvider).encryptedDatabaseFile()
        val attachments = File(applicationContext.noBackupFilesDir, "attachment_objects/${bookId.toUuid()}/objects")
        val vaultEnvelope = File(applicationContext.noBackupFilesDir, "vault-backup-envelopes-v1/$bookId.envelope")
        val candidates = buildList {
            listOf(
                "database/ledger.db" to database,
                "database/ledger.db-wal" to File(database.path + "-wal"),
                "database/ledger.db-shm" to File(database.path + "-shm"),
                "settings/ledger_app_settings.pb" to applicationContext.filesDir.resolve("ledger_app_settings.pb"),
                "keys/vault-recovery.envelope" to vaultEnvelope,
            ).filterTo(this) { it.second.isFile }
            if (attachments.isDirectory) {
                attachments.walkTopDown().filter(File::isFile).forEach { file ->
                    add("attachments/${file.relativeTo(attachments).invariantSeparatorsPath}" to file)
                }
            }
        }
        require(candidates.isNotEmpty())
        val manifest = AtomicFile(File(root, "manifest.bin"))
        val output = manifest.startWrite()
        try {
            val data = DataOutputStream(output)
            data.writeInt(QUARANTINE_MAGIC)
            data.write(bookId.bytes)
            data.write(snapshotId.bytes)
            data.writeInt(candidates.size)
            candidates.forEachIndexed { index, (logicalName, source) ->
                val target = File(root, "object-$index.bin")
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(source).use { input ->
                    FileOutputStream(target).use { copied ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) {
                                digest.update(buffer, 0, count)
                                copied.write(buffer, 0, count)
                            }
                        }
                        buffer.fill(0)
                        copied.fd.sync()
                    }
                }
                data.writeUTF(logicalName)
                data.writeLong(target.length())
                data.write(digest.digest())
            }
            data.flush()
            output.fd.sync()
            manifest.finishWrite(output)
            require(verifyQuarantine(root, bookId, snapshotId, candidates.map { it.first }))
            DomainResult.Success(snapshotId)
        } catch (error: Exception) {
            manifest.failWrite(output)
            throw error
        }
    } catch (_: Exception) {
        DomainResult.Failure(app.ledger.transfer.domain.RestoreFailure.SafetySnapshotFailed)
    }

    private fun verifyQuarantine(
        root: File,
        bookId: StableId,
        snapshotId: StableId,
        expectedNames: List<String>,
    ): Boolean = DataInputStream(AtomicFile(File(root, "manifest.bin")).openRead()).use { input ->
        if (input.readInt() != QUARANTINE_MAGIC) return false
        if (!input.readStableId().contentEquals(bookId.bytes) || !input.readStableId().contentEquals(snapshotId.bytes)) return false
        if (input.readInt() != expectedNames.size) return false
        expectedNames.forEachIndexed { index, expectedName ->
            if (input.readUTF() != expectedName) return false
            val expectedBytes = input.readLong()
            val expectedHash = ByteArray(SHA256_BYTES).also(input::readFully)
            val objectFile = File(root, "object-$index.bin")
            if (!objectFile.isFile || objectFile.length() != expectedBytes) return false
            if (!MessageDigest.isEqual(expectedHash, objectFile.sha256())) return false
        }
        input.read() == -1
    }

    private fun repositoryRoot(bookId: StableId, repositoryId: StableId) = applicationContext.noBackupFilesDir.resolve("backup-repositories/$bookId/$repositoryId")

    private companion object {
        const val QUARANTINE_MAGIC = 0x52535153
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val SHA256_BYTES = 32
    }
}

private fun DataInputStream.readStableId(): ByteArray = ByteArray(StableId.BYTE_COUNT).also(::readFully)

private const val SHA256_BUFFER_BYTES = 64 * 1024

private fun File.sha256(): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(this).use { input ->
        val buffer = ByteArray(SHA256_BUFFER_BYTES)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        } finally {
            buffer.fill(0)
        }
    }
    return digest.digest()
}
