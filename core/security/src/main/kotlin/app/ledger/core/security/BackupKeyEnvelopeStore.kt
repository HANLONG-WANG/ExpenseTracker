@file:Suppress("MaxLineLength", "TooGenericExceptionCaught", "TooManyFunctions")

package app.ledger.core.security

import android.content.Context
import android.util.AtomicFile
import app.ledger.core.common.StableId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

data class BackupKeyGeneration(
    val recoveryEnvelope: ByteArray,
    val parameters: Argon2idParameters,
)

/**
 * Keeps the background-readable repository key device-wrapped while exposing only an
 * independently salted Argon2id recovery envelope to backup media.
 */
class BackupKeyEnvelopeStore(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val wrapper: RecoveryPasswordKeyWrapper = RecoveryPasswordKeyWrapper(),
) {
    private val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY)

    fun configure(
        bookId: StableId,
        repositoryId: StableId,
        password: RecoveryPassword,
        parameters: Argon2idParameters,
    ): BackupKeyGeneration {
        val repositoryKey = LedgerTink.generateStreamingAeadKeyset()
        try {
            return writeGeneration(bookId, repositoryId, repositoryKey, password, parameters)
        } finally {
            repositoryKey.close()
        }
    }

    fun rewrap(
        bookId: StableId,
        repositoryId: StableId,
        password: RecoveryPassword,
        parameters: Argon2idParameters,
    ): BackupKeyGeneration = openForAutomaticBackup(bookId, repositoryId).use { key ->
        writeGeneration(bookId, repositoryId, key, password, parameters)
    }

    fun openForAutomaticBackup(bookId: StableId, repositoryId: StableId): SecretBytes {
        val stored = read(repositoryId)
        val associatedData = deviceAssociatedData(bookId, repositoryId)
        return try {
            keyProvider.open(bookId).use { keys ->
                val plaintext = keys.decryptSecureSettings(stored.deviceCiphertext, associatedData)
                SecretBytes.copyOf(plaintext).also { plaintext.fill(0) }
            }
        } finally {
            associatedData.fill(0)
        }
    }

    fun openWithRecoveryPassword(
        bookId: StableId,
        repositoryId: StableId,
        password: RecoveryPassword,
        encodedEnvelope: ByteArray = recoveryEnvelope(repositoryId),
    ): SecretBytes {
        val associatedData = recoveryAssociatedData(bookId, repositoryId)
        return try {
            wrapper.unwrap(password, RecoveryWrappedKeyMaterialCodec.decode(encodedEnvelope), associatedData)
        } finally {
            associatedData.fill(0)
        }
    }

    fun recoveryEnvelope(repositoryId: StableId): ByteArray = read(repositoryId).recoveryEnvelope.copyOf()

    fun isConfigured(repositoryId: StableId): Boolean = file(repositoryId).isFile

    fun delete(repositoryId: StableId) = AtomicFile(file(repositoryId)).delete()

    private fun writeGeneration(
        bookId: StableId,
        repositoryId: StableId,
        repositoryKey: SecretBytes,
        password: RecoveryPassword,
        parameters: Argon2idParameters,
    ): BackupKeyGeneration {
        val recoveryAd = recoveryAssociatedData(bookId, repositoryId)
        val deviceAd = deviceAssociatedData(bookId, repositoryId)
        try {
            val recovery = wrapper.wrap(password, repositoryKey, parameters, recoveryAd)
            val recoveryBytes = RecoveryWrappedKeyMaterialCodec.encode(recovery)
            val deviceCiphertext = keyProvider.open(bookId).use { keys ->
                repositoryKey.useBytes { keys.encryptSecureSettings(it, deviceAd) }
            }
            write(repositoryId, StoredBackupKey(recoveryBytes, deviceCiphertext))
            return BackupKeyGeneration(recoveryBytes.copyOf(), parameters)
        } finally {
            recoveryAd.fill(0)
            deviceAd.fill(0)
        }
    }

    private fun write(repositoryId: StableId, stored: StoredBackupKey) {
        require(directory.isDirectory || directory.mkdirs())
        val atomic = AtomicFile(file(repositoryId))
        val encoded = encode(stored)
        var output: java.io.FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(encoded)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: IOException) {
            output?.let(atomic::failWrite)
            throw SecurityException.KeyUnavailable(error)
        } finally {
            encoded.fill(0)
        }
    }

    private fun read(repositoryId: StableId): StoredBackupKey = try {
        decode(AtomicFile(file(repositoryId)).readFully())
    } catch (error: Exception) {
        throw SecurityException.CorruptEnvelope(error)
    }

    private fun encode(value: StoredBackupKey): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.field(value.recoveryEnvelope)
            output.field(value.deviceCiphertext)
        }
        bytes.toByteArray()
    }

    private fun decode(value: ByteArray): StoredBackupKey = DataInputStream(ByteArrayInputStream(value)).use { input ->
        require(input.readInt() == MAGIC)
        StoredBackupKey(input.field(), input.field()).also { require(input.read() == -1) }
    }

    private fun DataOutputStream.field(value: ByteArray) {
        require(value.size in 1..MAX_FIELD_BYTES)
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.field(): ByteArray {
        val size = readInt()
        require(size in 1..MAX_FIELD_BYTES)
        return ByteArray(size).also(::readFully)
    }

    private fun recoveryAssociatedData(bookId: StableId, repositoryId: StableId): ByteArray = "ledger-backup-recovery-v1\u0000".toByteArray(Charsets.US_ASCII) + bookId.bytes + repositoryId.bytes

    private fun deviceAssociatedData(bookId: StableId, repositoryId: StableId): ByteArray = "ledger-backup-device-v1\u0000".toByteArray(Charsets.US_ASCII) + bookId.bytes + repositoryId.bytes

    private fun file(repositoryId: StableId): File = File(directory, repositoryId.toString() + SUFFIX)

    private data class StoredBackupKey(val recoveryEnvelope: ByteArray, val deviceCiphertext: ByteArray)

    private companion object {
        const val DIRECTORY = "backup-key-envelopes-v1"
        const val SUFFIX = ".envelope"
        const val MAGIC = 0x4c424b45
        const val MAX_FIELD_BYTES = 1024 * 1024
    }
}

/** Stores only a recovery-password wrapped Vault DEK. Background callers never obtain Vault plaintext. */
class VaultBackupEnvelopeStore(
    context: Context,
    private val wrapper: RecoveryPasswordKeyWrapper = RecoveryPasswordKeyWrapper(),
) {
    private val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY)

    fun configure(
        bookId: StableId,
        vaultDek: SecretBytes,
        password: RecoveryPassword,
        parameters: Argon2idParameters,
    ): ByteArray {
        val associatedData = associatedData(bookId)
        return try {
            val encoded = RecoveryWrappedKeyMaterialCodec.encode(wrapper.wrap(password, vaultDek, parameters, associatedData))
            require(directory.isDirectory || directory.mkdirs())
            val atomic = AtomicFile(File(directory, bookId.toString() + SUFFIX))
            var output: java.io.FileOutputStream? = null
            try {
                output = atomic.startWrite()
                output.write(encoded)
                output.fd.sync()
                atomic.finishWrite(output)
            } catch (error: IOException) {
                output?.let(atomic::failWrite)
                throw SecurityException.KeyUnavailable(error)
            }
            encoded.copyOf()
        } finally {
            associatedData.fill(0)
        }
    }

    fun readForAutomaticBackup(bookId: StableId): ByteArray? = File(directory, bookId.toString() + SUFFIX)
        .takeIf(File::isFile)?.let { AtomicFile(it).readFully() }

    /** Recovery-only Vault DEK opening used solely to bind the restored key to this device's fresh auth KEK. */
    fun openWithRecoveryPassword(
        bookId: StableId,
        password: RecoveryPassword,
        encodedEnvelope: ByteArray = requireNotNull(readForAutomaticBackup(bookId)),
    ): SecretBytes {
        val associatedData = associatedData(bookId)
        return try {
            wrapper.unwrap(password, RecoveryWrappedKeyMaterialCodec.decode(encodedEnvelope), associatedData)
        } finally {
            associatedData.fill(0)
        }
    }

    fun isConfigured(bookId: StableId): Boolean = File(directory, bookId.toString() + SUFFIX).isFile

    fun delete(bookId: StableId) = AtomicFile(File(directory, bookId.toString() + SUFFIX)).delete()

    private fun associatedData(bookId: StableId): ByteArray = "ledger-backup-vault-v1\u0000".toByteArray(Charsets.US_ASCII) + bookId.bytes

    private companion object {
        const val DIRECTORY = "vault-backup-envelopes-v1"
        const val SUFFIX = ".envelope"
    }
}
