package app.ledger.core.security

import app.ledger.core.common.StableId
import com.google.crypto.tink.Aead
import com.google.crypto.tink.StreamingAead
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom

interface DeviceLedgerKeyProvider {
    fun initialize(bookId: StableId)

    fun open(bookId: StableId): DeviceLedgerKeys

    fun destroyLocal(bookId: StableId)
}

class DeviceKeyHierarchy(
    private val keystore: AndroidKeystoreKeys,
    private val envelopeStore: SecurityEnvelopeStore,
    private val secureRandom: SecureRandom = SecureRandom(),
) : DeviceLedgerKeyProvider {
    @Suppress("TooGenericExceptionCaught")
    override fun initialize(bookId: StableId) {
        val suffix = SecurityEnvelopeStore.aliasSuffix(bookId)
        val existing = envelopeStore.readDeviceBundle(bookId)
        if (existing != null) {
            if (!keystore.hasDeviceLedgerKek(suffix)) throw SecurityException.KeyUnavailable()
            return
        }
        if (keystore.hasDeviceLedgerKek(suffix)) {
            throw SecurityException.KeyUnavailable()
        }
        keystore.ensureDeviceLedgerKek(suffix)
        val databaseDek = ByteArray(DATABASE_DEK_BYTES).also(secureRandom::nextBytes)
        val attachmentRoot = LedgerTink.generateAeadKeyset()
        val secureSettings = LedgerTink.generateAeadKeyset()
        try {
            val bundle = encodeBundle(databaseDek, attachmentRoot, secureSettings)
            try {
                val associatedData = SecurityAssociatedData.keyEnvelope(bookId, KeyMaterialPurpose.DEVICE_LEDGER_BUNDLE)
                envelopeStore.writeDeviceBundle(
                    bookId,
                    keystore.wrapWithDeviceLedgerKek(
                        suffix,
                        bundle,
                        associatedData,
                        KeyMaterialPurpose.DEVICE_LEDGER_BUNDLE,
                    ),
                )
                associatedData.fill(0)
            } finally {
                bundle.fill(0)
            }
        } catch (error: Exception) {
            keystore.deleteDeviceLedgerKek(suffix)
            envelopeStore.deleteAll(bookId)
            throw error
        } finally {
            databaseDek.fill(0)
            attachmentRoot.close()
            secureSettings.close()
        }
    }

    override fun open(bookId: StableId): DeviceLedgerKeys {
        val suffix = SecurityEnvelopeStore.aliasSuffix(bookId)
        val envelope = envelopeStore.readDeviceBundle(bookId) ?: throw SecurityException.KeyUnavailable()
        require(envelope.purpose == KeyMaterialPurpose.DEVICE_LEDGER_BUNDLE)
        val associatedData = SecurityAssociatedData.keyEnvelope(bookId, KeyMaterialPurpose.DEVICE_LEDGER_BUNDLE)
        return try {
            keystore.unwrapWithDeviceLedgerKek(suffix, envelope, associatedData).use { plaintext ->
                plaintext.useBytes(::decodeBundle)
            }
        } finally {
            associatedData.fill(0)
        }
    }

    override fun destroyLocal(bookId: StableId) {
        val suffix = SecurityEnvelopeStore.aliasSuffix(bookId)
        envelopeStore.deleteAll(bookId)
        if (keystore.hasVaultAuthenticationKek(suffix)) keystore.deleteVaultAuthenticationKek(suffix)
        if (keystore.hasDeviceLedgerKek(suffix)) keystore.deleteDeviceLedgerKek(suffix)
    }

    private fun encodeBundle(
        databaseDek: ByteArray,
        attachmentRoot: SecretBytes,
        secureSettings: SecretBytes,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(BUNDLE_MAGIC)
            output.writeInt(BUNDLE_VERSION)
            writeSecret(output, databaseDek)
            attachmentRoot.useBytes { writeSecret(output, it) }
            secureSettings.useBytes { writeSecret(output, it) }
        }
        bytes.toByteArray()
    }

    private fun decodeBundle(bytes: ByteArray): DeviceLedgerKeys = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == BUNDLE_MAGIC) { "invalid device key bundle" }
            require(input.readInt() == BUNDLE_VERSION) { "unsupported device key bundle" }
            val databaseDek = readSecret(input, DATABASE_DEK_BYTES, DATABASE_DEK_BYTES)
            val attachmentRoot = readSecret(input, MINIMUM_TINK_KEYSET_BYTES, MAXIMUM_TINK_KEYSET_BYTES)
            val secureSettings = readSecret(input, MINIMUM_TINK_KEYSET_BYTES, MAXIMUM_TINK_KEYSET_BYTES)
            require(input.read() == -1) { "trailing device key material" }
            DeviceLedgerKeys(
                SecretBytes.copyOf(databaseDek),
                SecretBytes.copyOf(attachmentRoot),
                SecretBytes.copyOf(secureSettings),
            ).also {
                databaseDek.fill(0)
                attachmentRoot.fill(0)
                secureSettings.fill(0)
            }
        }
    } catch (error: IllegalArgumentException) {
        throw SecurityException.CorruptEnvelope(error)
    }

    private fun writeSecret(output: DataOutputStream, value: ByteArray) {
        output.writeInt(value.size)
        output.write(value)
    }

    private fun readSecret(input: DataInputStream, minimum: Int, maximum: Int): ByteArray {
        val size = input.readInt()
        require(size in minimum..maximum) { "invalid key material length" }
        return ByteArray(size).also(input::readFully)
    }

    private companion object {
        const val BUNDLE_MAGIC = 0x4c444b42
        const val BUNDLE_VERSION = 1
        const val DATABASE_DEK_BYTES = 32
        const val MINIMUM_TINK_KEYSET_BYTES = 32
        const val MAXIMUM_TINK_KEYSET_BYTES = 64 * 1024
    }
}

class DeviceLedgerKeys internal constructor(
    val databaseDek: SecretBytes,
    private val attachmentRootKeyset: SecretBytes,
    private val secureSettingsKeyset: SecretBytes,
) : AutoCloseable {
    fun secureSettingsAead(): Aead = secureSettingsKeyset.useBytes(LedgerTink::aead)

    fun encryptSecureSettings(plaintext: ByteArray, associatedData: ByteArray): ByteArray = secureSettingsKeyset.useBytes { keyset ->
        LedgerTink.aead(keyset).encrypt(plaintext, associatedData)
    }

    fun decryptSecureSettings(ciphertext: ByteArray, associatedData: ByteArray): ByteArray = secureSettingsKeyset.useBytes { keyset ->
        LedgerTink.aead(keyset).decrypt(ciphertext, associatedData)
    }

    fun createAttachmentDataKey(): SecretBytes = LedgerTink.generateStreamingAeadKeyset()

    fun wrapAttachmentDataKey(serializedDataKey: SecretBytes, associatedData: ByteArray): ByteArray = attachmentRootKeyset.useBytes { keyset ->
        serializedDataKey.useBytes { dataKey -> LedgerTink.aead(keyset).encrypt(dataKey, associatedData) }
    }

    fun unwrapAttachmentDataKey(wrappedDataKey: ByteArray, associatedData: ByteArray): StreamingAead = attachmentRootKeyset.useBytes { keyset ->
        val serialized = LedgerTink.aead(keyset).decrypt(wrappedDataKey, associatedData)
        try {
            LedgerTink.streamingAead(serialized)
        } finally {
            serialized.fill(0)
        }
    }

    override fun close() {
        databaseDek.close()
        attachmentRootKeyset.close()
        secureSettingsKeyset.close()
    }

    override fun toString(): String = "DeviceLedgerKeys(redacted)"
}
