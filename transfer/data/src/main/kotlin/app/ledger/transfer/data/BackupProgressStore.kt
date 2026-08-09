@file:Suppress("TooGenericExceptionCaught")

package app.ledger.transfer.data

import android.content.Context
import android.util.AtomicFile
import app.ledger.core.common.StableId
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.transfer.domain.BackupPhase
import app.ledger.transfer.domain.BackupProgress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Encrypted, fsync-backed phase checkpoint used by UI, Worker, and crash recovery. */
class BackupProgressStore(context: Context, private val keyProvider: DeviceLedgerKeyProvider) {
    private val directory = context.applicationContext.noBackupFilesDir.resolve(DIRECTORY)

    fun save(bookId: StableId, operationId: StableId, progress: BackupProgress) {
        val plain = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(progress.phase.ordinal)
                output.writeLong(progress.completedBytes)
                output.writeBoolean(progress.totalBytes != null)
                progress.totalBytes?.let(output::writeLong)
                output.writeLong(progress.completedObjects)
            }
            bytes.toByteArray()
        }
        val associatedData = associatedData(operationId)
        val sealed = try {
            keyProvider.open(bookId).use { it.encryptSecureSettings(plain, associatedData) }
        } finally {
            plain.fill(0)
            associatedData.fill(0)
        }
        try {
            require(directory.isDirectory || directory.mkdirs())
            val atomic = AtomicFile(file(operationId))
            var output: java.io.FileOutputStream? = null
            try {
                output = atomic.startWrite()
                output.write(sealed)
                output.fd.sync()
                atomic.finishWrite(output)
            } catch (error: Exception) {
                output?.let(atomic::failWrite)
                throw error
            }
        } finally {
            sealed.fill(0)
        }
    }

    fun read(bookId: StableId, operationId: StableId): BackupProgress? {
        val source = file(operationId)
        if (!source.isFile) return null
        val ciphertext = AtomicFile(source).readFully()
        val associatedData = associatedData(operationId)
        val plain = try {
            keyProvider.open(bookId).use { it.decryptSecureSettings(ciphertext, associatedData) }
        } finally {
            ciphertext.fill(0)
            associatedData.fill(0)
        }
        return try {
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                require(input.readInt() == MAGIC)
                val phase = BackupPhase.entries[input.readInt()]
                val completed = input.readLong()
                val total = if (input.readBoolean()) input.readLong() else null
                val objects = input.readLong()
                require(input.read() == -1)
                BackupProgress(phase, completed, total, objects)
            }
        } finally {
            plain.fill(0)
        }
    }

    fun delete(operationId: StableId): Boolean = !file(operationId).exists() || file(operationId).delete()

    private fun associatedData(operationId: StableId): ByteArray = "ledger-backup-progress-v1\u0000".toByteArray(Charsets.US_ASCII) + operationId.bytes

    private fun file(operationId: StableId): File = directory.resolve(operationId.toString() + SUFFIX)

    private companion object {
        const val DIRECTORY = "backup-progress-v1"
        const val SUFFIX = ".checkpoint"
        const val MAGIC = 0x4c425047
    }
}
