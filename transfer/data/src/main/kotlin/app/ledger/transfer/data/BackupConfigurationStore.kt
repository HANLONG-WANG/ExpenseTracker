@file:Suppress("TooGenericExceptionCaught")

package app.ledger.transfer.data

import android.content.Context
import android.util.AtomicFile
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.transfer.domain.BackupNetworkPolicy
import app.ledger.transfer.domain.BackupPolicy
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupRepositoryKind
import app.ledger.transfer.domain.BackupRetentionPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

data class BackupConfiguration(
    val repositoryId: BackupRepositoryId,
    val repositoryKind: BackupRepositoryKind,
    val repositoryHandleId: StableId,
    val policy: BackupPolicy,
)

class BackupConfigurationStore(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
) {
    private val directory = context.applicationContext.noBackupFilesDir.resolve(DIRECTORY)

    fun save(bookId: StableId, value: BackupConfiguration) {
        val plaintext = encode(value)
        val associatedData = associatedData(bookId)
        val encrypted = try {
            keyProvider.open(bookId).use { it.encryptSecureSettings(plaintext, associatedData) }
        } finally {
            plaintext.fill(0)
            associatedData.fill(0)
        }
        try {
            require(directory.isDirectory || directory.mkdirs())
            val atomic = AtomicFile(file(bookId))
            var output: java.io.FileOutputStream? = null
            try {
                output = atomic.startWrite()
                output.write(encrypted)
                output.fd.sync()
                atomic.finishWrite(output)
            } catch (error: Exception) {
                output?.let(atomic::failWrite)
                throw error
            }
        } finally {
            encrypted.fill(0)
        }
    }

    fun read(bookId: StableId): BackupConfiguration? {
        val file = file(bookId)
        if (!file.isFile) return null
        val ciphertext = AtomicFile(file).readFully()
        val associatedData = associatedData(bookId)
        val plaintext = try {
            keyProvider.open(bookId).use { it.decryptSecureSettings(ciphertext, associatedData) }
        } finally {
            ciphertext.fill(0)
            associatedData.fill(0)
        }
        return try {
            decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    fun delete(bookId: StableId): Boolean = !file(bookId).exists() || file(bookId).delete()

    private fun encode(value: BackupConfiguration): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.write(value.repositoryId.value.bytes)
            output.writeInt(value.repositoryKind.ordinal)
            output.write(value.repositoryHandleId.bytes)
            output.writeBoolean(value.policy.automaticLocalBackup)
            output.writeInt(value.policy.retention.maximumSnapshots)
            output.writeBoolean(value.policy.retention.maximumAgeDays != null)
            value.policy.retention.maximumAgeDays?.let(output::writeInt)
            output.writeBoolean(value.policy.includeVault)
            output.writeInt(value.policy.networkPolicy.ordinal)
        }
        bytes.toByteArray()
    }

    private fun decode(value: ByteArray): BackupConfiguration = DataInputStream(ByteArrayInputStream(value)).use { input ->
        require(input.readInt() == MAGIC)
        val repositoryId = BackupRepositoryId(input.id())
        val kind = BackupRepositoryKind.entries[input.readInt()]
        val handle = input.id()
        val automatic = input.readBoolean()
        val maximumSnapshots = input.readInt()
        val maximumAge = if (input.readBoolean()) input.readInt() else null
        val includeVault = input.readBoolean()
        val network = BackupNetworkPolicy.entries[input.readInt()]
        require(input.read() == -1)
        BackupConfiguration(
            repositoryId,
            kind,
            handle,
            BackupPolicy(automatic, BackupRetentionPolicy(maximumSnapshots, maximumAge), includeVault, network),
        )
    }

    private fun DataInputStream.id(): StableId = StableId.fromBytes(ByteArray(StableId.BYTE_COUNT).also(::readFully)).required()

    private fun associatedData(bookId: StableId): ByteArray = "ledger-backup-configuration-v1\u0000".toByteArray(Charsets.US_ASCII) + bookId.bytes

    private fun file(bookId: StableId): File = directory.resolve(bookId.toString() + SUFFIX)

    private companion object {
        const val DIRECTORY = "backup-configuration-v1"
        const val SUFFIX = ".configuration"
        const val MAGIC = 0x4c424346
    }
}

private fun <T> DomainResult<T>.required(): T = (this as? DomainResult.Success)?.value ?: error("invalid backup configuration")
