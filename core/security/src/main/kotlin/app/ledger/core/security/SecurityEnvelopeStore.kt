package app.ledger.core.security

import android.content.Context
import android.util.AtomicFile
import app.ledger.core.common.StableId
import java.io.File
import java.io.IOException

class SecurityEnvelopeStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)

    fun readDeviceBundle(bookId: StableId): WrappedKeyMaterial? = read(bookId, DEVICE_BUNDLE_SUFFIX)

    fun writeDeviceBundle(bookId: StableId, envelope: WrappedKeyMaterial) {
        require(envelope.purpose == KeyMaterialPurpose.DEVICE_LEDGER_BUNDLE)
        write(bookId, DEVICE_BUNDLE_SUFFIX, envelope)
    }

    fun readVaultDek(bookId: StableId): WrappedKeyMaterial? = read(bookId, VAULT_DEK_SUFFIX)

    fun writeVaultDek(bookId: StableId, envelope: WrappedKeyMaterial) {
        require(envelope.purpose == KeyMaterialPurpose.VAULT_DEK)
        write(bookId, VAULT_DEK_SUFFIX, envelope)
    }

    fun deleteAll(bookId: StableId) {
        AtomicFile(file(bookId, DEVICE_BUNDLE_SUFFIX)).delete()
        AtomicFile(file(bookId, VAULT_DEK_SUFFIX)).delete()
    }

    private fun read(bookId: StableId, suffix: String): WrappedKeyMaterial? {
        val target = file(bookId, suffix)
        if (!target.isFile) return null
        return try {
            WrappedKeyMaterialCodec.decode(AtomicFile(target).readFully())
        } catch (error: IOException) {
            throw SecurityException.CorruptEnvelope(error)
        } catch (error: IllegalArgumentException) {
            throw SecurityException.CorruptEnvelope(error)
        }
    }

    private fun write(bookId: StableId, suffix: String, envelope: WrappedKeyMaterial) {
        ensureDirectory()
        val atomic = AtomicFile(file(bookId, suffix))
        val bytes = WrappedKeyMaterialCodec.encode(envelope)
        val stream = startWrite(atomic, bytes)
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: IOException) {
            atomic.failWrite(stream)
            throw SecurityException.KeyUnavailable(error)
        } finally {
            bytes.fill(0)
        }
    }

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) throw SecurityException.KeyUnavailable()
    }

    private fun startWrite(atomic: AtomicFile, bytes: ByteArray) = try {
        atomic.startWrite()
    } catch (error: IOException) {
        bytes.fill(0)
        throw SecurityException.KeyUnavailable(error)
    }

    private fun file(bookId: StableId, suffix: String): File = File(directory, "${aliasSuffix(bookId)}.$suffix")

    companion object {
        private const val DIRECTORY_NAME = "ledger-security-v1"
        private const val DEVICE_BUNDLE_SUFFIX = "device"
        private const val VAULT_DEK_SUFFIX = "vault"

        fun aliasSuffix(bookId: StableId): String = buildString(ALIAS_HEX_CHARACTERS) {
            bookId.bytes.forEach { byte -> append("%02x".format(byte.toInt() and UNSIGNED_BYTE_MASK)) }
        }

        private const val ALIAS_HEX_CHARACTERS = StableId.BYTE_COUNT * 2
        private const val UNSIGNED_BYTE_MASK = 0xff
    }
}
