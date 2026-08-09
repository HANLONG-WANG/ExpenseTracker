@file:Suppress("MagicNumber")

package app.ledger.core.security

import android.content.Context
import app.ledger.core.common.StableId
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Encrypted-at-rest SAF tree/document handle store shared by export/backup/restore operations. */
class SecureTransferHandleStore(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
) {
    private val directory = context.applicationContext.noBackupFilesDir.resolve("transfer_handles")

    fun save(bookId: StableId, handleId: StableId, persistedHandle: String) {
        require(persistedHandle.isNotBlank() && persistedHandle.length <= MAX_HANDLE_CHARS)
        val associatedData = associatedData(handleId)
        val plaintext = persistedHandle.toByteArray(Charsets.UTF_8)
        val ciphertext = try {
            keyProvider.open(bookId).use { it.encryptSecureSettings(plaintext, associatedData) }
        } finally {
            plaintext.fill(0)
            associatedData.fill(0)
        }
        Files.createDirectories(directory.toPath())
        val target = file(handleId).toPath()
        val temporary = directory.resolve(".${handleId.bytes.toHexString()}.pending").toPath()
        try {
            Files.write(temporary, ciphertext)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            ciphertext.fill(0)
            Files.deleteIfExists(temporary)
        }
    }

    fun read(bookId: StableId, handleId: StableId): String? {
        val source = file(handleId)
        if (!source.isFile) return null
        val ciphertext = source.readBytes()
        val associatedData = associatedData(handleId)
        val plaintext = try {
            keyProvider.open(bookId).use { it.decryptSecureSettings(ciphertext, associatedData) }
        } finally {
            ciphertext.fill(0)
            associatedData.fill(0)
        }
        return try {
            plaintext.toString(Charsets.UTF_8).also { require(it.isNotBlank() && it.length <= MAX_HANDLE_CHARS) }
        } finally {
            plaintext.fill(0)
        }
    }

    fun destroy(handleId: StableId): Boolean = !file(handleId).exists() || file(handleId).delete()

    private fun file(handleId: StableId) = directory.resolve("${handleId.bytes.toHexString()}.bin")
    private fun associatedData(handleId: StableId): ByteArray = "ledger-transfer-handle-v1\u0000".toByteArray(Charsets.US_ASCII) + handleId.bytes

    private companion object {
        const val MAX_HANDLE_CHARS = 8_192
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
