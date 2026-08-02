package app.ledger.core.security

import app.ledger.core.common.StableId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class SecretBytes private constructor(bytes: ByteArray) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val value = bytes.copyOf()

    val size: Int
        get() = value.size

    @Synchronized
    fun <T> useBytes(block: (ByteArray) -> T): T {
        check(!closed.get()) { "secret material is closed" }
        val copy = value.copyOf()
        return try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) value.fill(0)
    }

    override fun toString(): String = "SecretBytes(redacted,size=${value.size},closed=${closed.get()})"

    companion object {
        fun copyOf(bytes: ByteArray): SecretBytes {
            require(bytes.isNotEmpty()) { "secret material cannot be empty" }
            return SecretBytes(bytes)
        }
    }
}

class RecoveryPassword private constructor(chars: CharArray) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val value = chars.copyOf()

    @Synchronized
    fun <T> useChars(block: (CharArray) -> T): T {
        check(!closed.get()) { "recovery password is closed" }
        val copy = value.copyOf()
        return try {
            block(copy)
        } finally {
            copy.fill('\u0000')
        }
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) value.fill('\u0000')
    }

    override fun toString(): String = "RecoveryPassword(redacted,closed=${closed.get()})"

    companion object {
        const val MINIMUM_CHARACTERS: Int = 12

        fun copyOf(chars: CharArray): RecoveryPassword {
            require(chars.size >= MINIMUM_CHARACTERS) { "recovery password does not meet the minimum length" }
            require(chars.any(Char::isLetter) && chars.any(Char::isDigit)) {
                "recovery password must contain a letter and a digit"
            }
            return RecoveryPassword(chars)
        }
    }
}

enum class KeyMaterialPurpose(val storageCode: Int) {
    DEVICE_LEDGER_BUNDLE(DEVICE_LEDGER_BUNDLE_CODE),
    VAULT_DEK(VAULT_DEK_CODE),
    RECOVERY_BUNDLE(RECOVERY_BUNDLE_CODE),
    ATTACHMENT_DATA_KEY(ATTACHMENT_DATA_KEY_CODE),
}

class WrappedKeyMaterial(
    val formatVersion: Int,
    val purpose: KeyMaterialPurpose,
    nonce: ByteArray,
    ciphertext: ByteArray,
) {
    private val storedNonce = nonce.copyOf()
    private val storedCiphertext = ciphertext.copyOf()

    val nonce: ByteArray
        get() = storedNonce.copyOf()

    val ciphertext: ByteArray
        get() = storedCiphertext.copyOf()

    init {
        require(formatVersion > 0)
        require(storedNonce.size == GCM_NONCE_BYTES)
        require(storedCiphertext.size >= GCM_TAG_BYTES)
    }

    override fun toString(): String = "WrappedKeyMaterial(version=$formatVersion,purpose=$purpose,nonce=redacted,ciphertext=redacted)"

    companion object {
        const val GCM_NONCE_BYTES: Int = 12
        const val GCM_TAG_BYTES: Int = 16
    }
}

object WrappedKeyMaterialCodec {
    private const val MAGIC = 0x4c4b4559
    private const val MAX_CIPHERTEXT_BYTES = 1024 * 1024

    fun encode(value: WrappedKeyMaterial): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(value.formatVersion)
            output.writeInt(value.purpose.storageCode)
            val nonce = value.nonce
            val ciphertext = value.ciphertext
            output.writeInt(nonce.size)
            output.write(nonce)
            output.writeInt(ciphertext.size)
            output.write(ciphertext)
            nonce.fill(0)
            ciphertext.fill(0)
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): WrappedKeyMaterial = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == MAGIC) { "invalid wrapped-key envelope" }
        val version = input.readInt()
        val purposeCode = input.readInt()
        val purpose = KeyMaterialPurpose.entries.singleOrNull { it.storageCode == purposeCode }
            ?: throw IllegalArgumentException("unknown wrapped-key purpose")
        val nonceSize = input.readInt()
        require(nonceSize == WrappedKeyMaterial.GCM_NONCE_BYTES) { "invalid wrapped-key nonce" }
        val nonce = ByteArray(nonceSize).also(input::readFully)
        val ciphertextSize = input.readInt()
        require(ciphertextSize in WrappedKeyMaterial.GCM_TAG_BYTES..MAX_CIPHERTEXT_BYTES) {
            "invalid wrapped-key ciphertext"
        }
        val ciphertext = ByteArray(ciphertextSize).also(input::readFully)
        require(input.read() == -1) { "trailing wrapped-key data" }
        WrappedKeyMaterial(version, purpose, nonce, ciphertext).also {
            nonce.fill(0)
            ciphertext.fill(0)
        }
    }
}

enum class VaultFieldType(val wireName: String) {
    HOLDER_NAME("holder_name"),
    PAN("pan"),
    EXPIRY("expiry"),
    SECURITY_CODE("security_code"),
    CUSTOM_FIELDS("custom_fields"),
}

object SecurityAssociatedData {
    private const val FORMAT_VERSION = 1

    fun keyEnvelope(bookId: StableId, purpose: KeyMaterialPurpose): ByteArray = canonical(
        "ledger-key-envelope",
        bookId.bytes,
        purpose.name,
        FORMAT_VERSION,
    )

    fun vaultField(
        bookId: StableId,
        cardId: StableId,
        fieldType: VaultFieldType,
        schemaVersion: Int,
    ): ByteArray {
        require(schemaVersion > 0)
        return canonical("vault-field", bookId.bytes, cardId.bytes, fieldType.wireName, schemaVersion)
    }

    fun recoveryBundle(bookId: StableId, schemaVersion: Int): ByteArray {
        require(schemaVersion > 0)
        return canonical("recovery-bundle", bookId.bytes, schemaVersion)
    }

    fun attachmentKey(bookId: StableId, blobId: StableId, encryptionVersion: Int): ByteArray {
        require(encryptionVersion > 0)
        return canonical("attachment-data-key", bookId.bytes, blobId.bytes, encryptionVersion)
    }

    fun attachmentContent(bookId: StableId, blobId: StableId, encryptionVersion: Int): ByteArray {
        require(encryptionVersion > 0)
        return canonical("attachment-content", bookId.bytes, blobId.bytes, encryptionVersion)
    }

    fun attachmentThumbnail(bookId: StableId, blobId: StableId, encryptionVersion: Int): ByteArray {
        require(encryptionVersion > 0)
        return canonical("attachment-thumbnail", bookId.bytes, blobId.bytes, encryptionVersion)
    }

    private fun canonical(domain: String, vararg values: Any): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(FORMAT_VERSION)
            writeBytes(output, domain.toByteArray(StandardCharsets.UTF_8))
            values.forEach { value -> writeValue(output, value) }
        }
        bytes.toByteArray()
    }

    private fun writeBytes(output: DataOutputStream, value: ByteArray) {
        output.writeInt(value.size)
        output.write(value)
    }

    private fun writeValue(output: DataOutputStream, value: Any) {
        when (value) {
            is ByteArray -> writeBytes(output, value)
            is String -> writeBytes(output, value.toByteArray(StandardCharsets.UTF_8))
            is Int -> output.writeInt(value)
            else -> error("unsupported associated-data value")
        }
    }
}

private const val DEVICE_LEDGER_BUNDLE_CODE = 1
private const val VAULT_DEK_CODE = 2
private const val RECOVERY_BUNDLE_CODE = 3
private const val ATTACHMENT_DATA_KEY_CODE = 4

sealed class SecurityException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class DeviceSecurityUnavailable : SecurityException("device security is unavailable")

    class KeyUnavailable(cause: Throwable? = null) : SecurityException("key material is unavailable", cause)

    class AuthenticationRequired(cause: Throwable? = null) : SecurityException("authentication is required", cause)

    class AuthenticationFailed(cause: Throwable? = null) : SecurityException("authentication failed", cause)

    class CorruptEnvelope(cause: Throwable? = null) : SecurityException("encrypted key envelope is invalid", cause)

    class RecoveryAuthenticationFailed(cause: Throwable? = null) : SecurityException("recovery authentication failed", cause)

    class DatabaseUnavailable : SecurityException("encrypted database is unavailable")
}
