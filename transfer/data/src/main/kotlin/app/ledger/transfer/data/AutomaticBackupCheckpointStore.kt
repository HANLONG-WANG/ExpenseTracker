@file:Suppress("TooGenericExceptionCaught")

package app.ledger.transfer.data

import android.content.Context
import android.util.AtomicFile
import app.ledger.core.common.StableId
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.finance.domain.LocalRevision
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.LocalDate

data class AutomaticBackupCheckpoint(
    val date: LocalDate,
    val revision: LocalRevision,
    val operationId: StableId,
)

class AutomaticBackupCheckpointStore(context: Context, private val keyProvider: DeviceLedgerKeyProvider) {
    private val directory = context.applicationContext.noBackupFilesDir.resolve(DIRECTORY)

    @Synchronized
    fun save(bookId: StableId, value: AutomaticBackupCheckpoint) {
        val plain = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeLong(value.date.toEpochDay())
                output.writeLong(value.revision.value)
                output.write(value.operationId.bytes)
            }
            bytes.toByteArray()
        }
        val ad = associatedData(bookId)
        val ciphertext = try {
            keyProvider.open(bookId).use { it.encryptSecureSettings(plain, ad) }
        } finally {
            plain.fill(0)
            ad.fill(0)
        }
        try {
            require(directory.isDirectory || directory.mkdirs())
            val atomic = AtomicFile(directory.resolve(bookId.toString() + SUFFIX))
            var output: java.io.FileOutputStream? = null
            try {
                output = atomic.startWrite()
                output.write(ciphertext)
                output.fd.sync()
                atomic.finishWrite(output)
            } catch (error: Exception) {
                output?.let(atomic::failWrite)
                throw error
            }
        } finally {
            ciphertext.fill(0)
        }
    }

    @Synchronized
    fun read(bookId: StableId): AutomaticBackupCheckpoint? {
        val source = directory.resolve(bookId.toString() + SUFFIX)
        if (!source.isFile) return null
        val ciphertext = AtomicFile(source).readFully()
        val ad = associatedData(bookId)
        val plain = try {
            keyProvider.open(bookId).use { it.decryptSecureSettings(ciphertext, ad) }
        } finally {
            ciphertext.fill(0)
            ad.fill(0)
        }
        return try {
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                require(input.readInt() == MAGIC)
                val date = LocalDate.ofEpochDay(input.readLong())
                val revision = LocalRevision.of(input.readLong()).required()
                val operationId = StableId.fromBytes(ByteArray(StableId.BYTE_COUNT).also(input::readFully)).required()
                require(input.read() == -1)
                AutomaticBackupCheckpoint(date, revision, operationId)
            }
        } finally {
            plain.fill(0)
        }
    }

    @Synchronized
    fun delete(bookId: StableId): Boolean {
        val source = directory.resolve(bookId.toString() + SUFFIX)
        return !source.exists() || source.delete()
    }

    private fun associatedData(bookId: StableId): ByteArray = "ledger-automatic-backup-v1\u0000".toByteArray(Charsets.US_ASCII) + bookId.bytes

    private companion object {
        const val DIRECTORY = "automatic-backup-v1"
        const val SUFFIX = ".checkpoint"
        const val MAGIC = 0x4c424143
    }
}

private fun <T> app.ledger.core.common.DomainResult<T>.required(): T = (this as? app.ledger.core.common.DomainResult.Success)?.value ?: error("invalid automatic backup checkpoint")
